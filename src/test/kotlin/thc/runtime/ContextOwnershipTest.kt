// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.interop.InteropLibrary
import com.oracle.truffle.api.interop.UnsupportedMessageException
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.Engine
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import thc.Language
import java.util.concurrent.atomic.AtomicReference

class ContextOwnershipTest {
    @Test fun closingOneContextRetiresWeakStableAndNativeOwnersTogether() {
        data class Roots(val state: Language.State, val weak: Any, val stable: ManagedAddress,
            val address: ManagedAddress, val bits: Long, val pointer: NativeReadOnlyPointer)
        Engine.create().use { engine ->
            var finalizerCalls = 0
            fun capture(context: Context): Roots {
                context.initialize("thc")
                context.enter()
                return try {
                    val state = Language.currentState()
                    val address = ManagedAddress.fromHex("616263")
                    val stable = state.stablePointers.make(address)
                    state.stablePointers.getOrSetSharedCAF(SharedCAFStore.EVENT_MANAGER, stable)
                    val weak = state.weaks.make(address, stable, { ++finalizerCalls })
                    val bits = state.nativeAddresses.project(address)
                    Roots(state, weak, stable, address, bits, state.nativeAddresses.transport(address)!!)
                } finally { context.leave() }
            }
            val interop = InteropLibrary.getUncached()
            fun live(roots: Roots) {
                assertEquals(1, roots.state.weaks.retainedCount())
                assertSame(roots.stable, roots.state.weaks.dereference(roots.weak).value)
                assertSame(roots.address, roots.state.stablePointers.dereference(roots.stable))
                assertTrue(interop.isPointer(roots.pointer))
                assertEquals(roots.bits, interop.asPointer(roots.pointer))
                assertEquals(97L, roots.state.nativeAddresses.recover(roots.bits).readWord8(0))
            }
            fun retired(roots: Roots) {
                assertEquals(0, roots.state.weaks.retainedCount())
                assertThrows(RuntimeFault::class.java) { roots.state.weaks.dereference(roots.weak) }
                assertThrows(RuntimeFault::class.java) { roots.state.stablePointers.dereference(roots.stable) }
                assertThrows(RuntimeFault::class.java) {
                    roots.state.stablePointers.getOrSetSharedCAF(SharedCAFStore.EVENT_MANAGER,
                        ManagedAddress.nullAddress())
                }
                assertThrows(RuntimeFault::class.java) { roots.state.nativeAddresses.recover(roots.bits) }
                assertFalse(interop.isPointer(roots.pointer))
                assertThrows(UnsupportedMessageException::class.java) { interop.asPointer(roots.pointer) }
                assertEquals(0, finalizerCalls)
            }
            Context.newBuilder("thc").engine(engine).allowNativeAccess(true).build().use { first ->
                Context.newBuilder("thc").engine(engine).allowNativeAccess(true).build().use { second ->
                    val firstRoots = capture(first)
                    val secondRoots = capture(second)
                    live(firstRoots)
                    live(secondRoots)
                    first.close()
                    retired(firstRoots)
                    live(secondRoots)
                    second.close()
                    retired(secondRoots)
                    retired(firstRoots)
                }
            }
        }
    }

    @Test fun handoffLayoutsAndThreadUpgradeAreOwnedByEachContext() {
        Engine.create().use { engine ->
            fun capture(context: Context): Pair<Language.State, HandoffLayout> {
                context.initialize("thc")
                context.enter()
                return try {
                    val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                    val state = Language.currentState(null)
                    assertSame(state.handoffLayouts, language.handoffLayouts)
                    state to state.handoffLayouts.intern(listOf("IntRep", "BoxedRep (Just Lifted)"))
                } finally {
                    context.leave()
                }
            }

            Context.newBuilder("thc").engine(engine).build().use { firstContext ->
                Context.newBuilder("thc").engine(engine).build().use { secondContext ->
                    val (first, firstLayout) = capture(firstContext)
                    val (second, secondLayout) = capture(secondContext)
                    assertNotSame(first, second)
                    assertNotSame(first.handoffLayouts, second.handoffLayouts)
                    assertNotSame(first.javaScriptImports, second.javaScriptImports)
                    assertNotSame(firstLayout, secondLayout)
                    assertTrue(first.singleThreadedAssumption.isValid)
                    assertTrue(second.singleThreadedAssumption.isValid)

                    // Sequential access by a new thread is enough to leave the
                    // lockless phase, before that thread executes guest code.
                    val workerError = AtomicReference<Throwable?>()
                    val worker = Thread {
                        try {
                            firstContext.enter()
                            try { assertSame(first, Language.currentState(null)) }
                            finally { firstContext.leave() }
                        } catch (error: Throwable) { workerError.set(error) }
                    }
                    worker.isDaemon = true
                    worker.start()
                    worker.join(10_000)
                    assertFalse(worker.isAlive, "Second context thread did not leave")
                    workerError.get()?.let { throw AssertionError("Second context thread failed", it) }
                    assertFalse(first.singleThreadedAssumption.isValid)
                    assertTrue(second.singleThreadedAssumption.isValid)
                    firstContext.enter()
                    try { assertFalse(first.singleThreadedAssumption.isValid) }
                    finally { firstContext.leave() }
                }
            }
        }
    }
}
