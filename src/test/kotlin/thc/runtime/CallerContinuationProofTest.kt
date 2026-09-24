// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.bytecode.ContinuationResult
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.RootNode
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.Language
import thc.executionContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** A child yield becomes a second real bytecode continuation at its caller. */
class CallerContinuationProofTest {
    private class Driver : RootNode(null) {
        @Child private var force = Force(Metrics(true))
        override fun execute(frame: VirtualFrame): Any? = force.execute(frame, frame.arguments[0])
        fun force(thunk: Thunk): Any? = Calls.target(callTarget, arrayOf(thunk))
    }

    private fun <T> entered(context: Context, action: () -> T): T {
        context.enter()
        try { return action() } finally { context.leave() }
    }

    private fun compile(target: RootCallTarget) {
        val type = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget")
        assertTrue(type.isInstance(target))
        type.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
        assertEquals(true, type.getMethod("isValidLastTier").invoke(target))
    }

    @Test fun callerOperandBelowTrySurvivesTwoChildSuspensions() {
        executionContext().use { context ->
            context.initialize("thc")
            val language = entered(context) { TruffleLanguage.LanguageReference.create(Language::class.java).get(null) }
            val childEffects = AtomicInteger()
            val callerEffects = AtomicInteger()
            val compiledCallerEffects = AtomicInteger()
            val (child, caller, driver) = entered(context) {
                val warm = Thunk(object : RootNode(null) {
                    override fun execute(frame: VirtualFrame): Any = ThunkYieldProofRoot.Answer(42L, this)
                }.callTarget, null)
                val selectedChild = AtomicReference(warm)
                val callerTarget = ThunkYieldProofRoot.caller(language, selectedChild,
                    callerEffects, compiledCallerEffects)
                val driver = Driver()
                repeat(8) { assertEquals(142L, driver.force(Thunk(callerTarget, null))) }
                compile(callerTarget)
                val child = Thunk(ThunkYieldProofRoot.target(language, childEffects, AtomicInteger(),
                    ThunkYieldProofRoot.Gate(), Any()), null)
                selectedChild.set(child)
                Triple(child, Thunk(callerTarget, null), driver)
            }
            val callerEffectsBefore = callerEffects.get()
            val compiledBefore = compiledCallerEffects.get()
            val first = entered(context) { assertThrows(ThunkSuspended::class.java) { driver.force(caller) } }
            assertSame(caller, first.thunk)
            assertEquals(5, child.state)
            assertEquals(5, caller.state)
            val callerSegment = caller.value as ContinuationResult
            assertSame(child, (callerSegment.result as ThunkSuspended).thunk)
            val frame = callerSegment.frame
            assertTrue((0 until frame.frameDescriptor.numberOfSlots).any {
                frame.isLong(it) && frame.getLong(it) == 100L
            }, "The primitive left operand remains live below the try/call boundary: " +
                (0 until frame.frameDescriptor.numberOfSlots).map {
                    "$it=${when { frame.isLong(it) -> frame.getLong(it); frame.isObject(it) -> frame.getObject(it); else -> "unset" }}" +
                        " long=${frame.isLong(it)}"
                })

            val second = entered(context) { assertThrows(ThunkSuspended::class.java) { driver.force(caller) } }
            assertSame(child, second.thunk, "Only the child yielded again; the caller stayed suspended")
            assertEquals(5, child.state)
            assertEquals(5, caller.state)
            assertSame(callerSegment, caller.value)
            assertEquals(142L, entered(context) { driver.force(caller) })
            assertEquals(2, caller.state)
            assertEquals(2, child.state)
            assertEquals(1, childEffects.get())
            assertEquals(callerEffectsBefore + 1, callerEffects.get())
            assertTrue(compiledCallerEffects.get() > compiledBefore,
                "The initial caller yield must execute installed guest code")
        }
    }

    @Test fun twoCallerThunksShareOneChildAndPublishToTheirReaders() {
        executionContext().use { context ->
            context.initialize("thc")
            val language = entered(context) { TruffleLanguage.LanguageReference.create(Language::class.java).get(null) }
            val childEffects = AtomicInteger()
            val callerEffects = AtomicInteger()
            val (child, callers, driver) = entered(context) {
                val child = Thunk(ThunkYieldProofRoot.target(language, childEffects, AtomicInteger(),
                    ThunkYieldProofRoot.Gate(), Any()), null)
                val callerTarget = ThunkYieldProofRoot.caller(language, child, callerEffects)
                Triple(child, List(2) { Thunk(callerTarget, null) }, Driver())
            }
            entered(context) {
                callers.forEach { caller ->
                    assertSame(caller, assertThrows(ThunkSuspended::class.java) { driver.force(caller) }.thunk)
                }
            }
            assertEquals(5, child.state)
            assertTrue(callers.all { it.state == 5 })
            Executors.newFixedThreadPool(4).use { pool ->
                val start = CountDownLatch(1)
                val results = List(4) { index -> pool.submit<Any?> { entered(context) {
                    assertTrue(start.await(5, TimeUnit.SECONDS))
                    driver.force(callers[index % 2])
                } } }
                start.countDown()
                results.forEach { assertEquals(142L, it.get(5, TimeUnit.SECONDS)) }
            }
            assertEquals(2, child.state)
            assertTrue(callers.all { it.state == 2 })
            assertEquals(1, childEffects.get(), "The original child entry runs only once")
            assertEquals(2, callerEffects.get(), "Each caller runs its prefix only once")
        }
    }

    @Test fun ordinaryGuestFailureIsNotCapturedAsAnInternalSuspension() {
        executionContext().use { context ->
            context.initialize("thc")
            val language = entered(context) { TruffleLanguage.LanguageReference.create(Language::class.java).get(null) }
            val payload = Any()
            val (child, caller, driver) = entered(context) {
                val child = Thunk(object : RootNode(null) {
                    override fun execute(frame: VirtualFrame): Any? = throw GuestException(payload, this)
                }.callTarget, null)
                val caller = Thunk(ThunkYieldProofRoot.caller(language, child, AtomicInteger()), null)
                Triple(child, caller, Driver())
            }
            val failure = entered(context) { assertThrows(GuestException::class.java) { driver.force(caller) } }
            assertSame(payload, failure.payload)
            assertEquals(3, child.state)
            assertEquals(3, caller.state)
            assertFalse(caller.value is ContinuationResult)
        }
    }

    @Test fun childFailureAfterSuspensionReentersCallerAndMemoizesNormally() {
        executionContext().use { context ->
            context.initialize("thc")
            val language = entered(context) { TruffleLanguage.LanguageReference.create(Language::class.java).get(null) }
            val payload = Any()
            val childEffects = AtomicInteger()
            val callerEffects = AtomicInteger()
            val (child, caller, driver) = entered(context) {
                val driver = Driver()
                val failure = GuestException(payload, driver)
                val child = Thunk(ThunkYieldProofRoot.target(language, childEffects, AtomicInteger(),
                    ThunkYieldProofRoot.Gate(), failure), null)
                val caller = Thunk(ThunkYieldProofRoot.caller(language, child, callerEffects), null)
                Triple(child, caller, driver)
            }
            entered(context) {
                assertSame(caller, assertThrows(ThunkSuspended::class.java) { driver.force(caller) }.thunk)
                assertSame(child, assertThrows(ThunkSuspended::class.java) { driver.force(caller) }.thunk)
                val failure = assertThrows(GuestException::class.java) { driver.force(caller) }
                assertSame(payload, failure.payload)
                assertSame(payload, assertThrows(GuestException::class.java) { driver.force(caller) }.payload)
            }
            assertEquals(3, child.state)
            assertEquals(3, caller.state)
            assertFalse(caller.value is ContinuationResult)
            assertFalse(caller.value is ThunkSuspended)
            assertEquals(1, childEffects.get())
            assertEquals(1, callerEffects.get())
        }
    }
}
