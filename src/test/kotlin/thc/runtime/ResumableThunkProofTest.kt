// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.bytecode.BytecodeRootNode
import com.oracle.truffle.api.bytecode.ContinuationResult
import com.oracle.truffle.api.frame.FrameSlotKind
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
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

/** An actual DSL continuation, kept by one shared thunk across guest threads. */
class ResumableThunkProofTest {
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

    @Test fun compiledBytecodeYieldResumesTypedLocalsOnAnotherThreadWithoutReplayingEffect() {
        executionContext().use { context ->
            context.initialize("thc")
            val language = entered(context) { TruffleLanguage.LanguageReference.create(Language::class.java).get(null) }
            val effects = AtomicInteger()
            val compiledEffects = AtomicInteger()
            val gate = ThunkYieldProofRoot.Gate()
            val marker = Any()
            val (target, driver) = entered(context) {
                ThunkYieldProofRoot.target(language, effects, compiledEffects, gate, marker) to Driver()
            }
            // Initialize both the bytecode root and its continuation target, then
            // explicitly install the initial root. A fresh thunk uses the same code.
            entered(context) {
                repeat(8) {
                    val warm = Thunk(target, null)
                    assertThrows(ThunkSuspended::class.java) { driver.force(warm) }
                    assertThrows(ThunkSuspended::class.java) { driver.force(warm) }
                    val answer = driver.force(warm) as ThunkYieldProofRoot.Answer
                    assertEquals(42L, answer.number)
                    assertSame(marker, answer.marker)
                }
                compile(target)
            }
            val before = effects.get()
            val compiledBefore = compiledEffects.get()
            val thunk = entered(context) { Thunk(target, null) }
            Executors.newFixedThreadPool(4).use { pool ->
                val first = pool.submit {
                    entered(context) { assertThrows(ThunkSuspended::class.java) { driver.force(thunk) } }
                }
                first.get(5, TimeUnit.SECONDS)
                assertEquals(5, thunk.state)
                val saved = thunk.value as ContinuationResult
                val frame = saved.frame
                val slots = 0 until frame.frameDescriptor.numberOfSlots
                assertTrue(slots.any { frame.isLong(it) && frame.getLong(it) == 42L },
                    "The live numeric value must retain a primitive Long frame slot")
                assertTrue(slots.any { frame.isObject(it) && frame.getObject(it) === marker },
                    "The live reference must retain its identity in the captured frame")
                val root = target.rootNode as BytecodeRootNode
                assertEquals(FrameSlotKind.Long,
                    root.bytecodeNode.locals.single { it.name == "number" }.typeProfile)
                assertEquals(before + 1, effects.get())
                assertTrue(compiledEffects.get() > compiledBefore, "Initial yield must execute installed guest code")

                gate.armed = true
                val second = pool.submit {
                    entered(context) { assertThrows(ThunkSuspended::class.java) { driver.force(thunk) } }
                }
                assertTrue(gate.entered.await(5, TimeUnit.SECONDS))
                assertEquals(1, thunk.state, "Only the resumer owns the thunk while its frame is live")
                val readersStarted = CountDownLatch(2)
                val readers = List(2) { pool.submit<Any?> { entered(context) {
                    readersStarted.countDown()
                    driver.force(thunk)
                } } }
                assertTrue(readersStarted.await(5, TimeUnit.SECONDS))
                assertThrows(TimeoutException::class.java) { readers[0].get(50, TimeUnit.MILLISECONDS) }
                gate.release.countDown()
                second.get(5, TimeUnit.SECONDS)
                val answers = readers.map { it.get(5, TimeUnit.SECONDS) as ThunkYieldProofRoot.Answer }
                assertSame(answers[0], answers[1], "Both waiters observe the single published WHNF")
                assertEquals(42L, answers[0].number)
                assertSame(marker, answers[0].marker)
            }
            assertEquals(before + 1, effects.get(), "Resuming twice must not replay the pre-yield effect")
            assertEquals(2, thunk.state)
            assertNull(thunk.target)
            assertNull(thunk.environment)
            assertTrue(thunk.value is ThunkYieldProofRoot.Answer)
            assertNull(thunk.owner)
        }
    }

    @Test fun uncapturedCallerUpdateFailsClosedAndWakesItsWaiter() {
        executionContext().use { context ->
            context.initialize("thc")
            val language = entered(context) { TruffleLanguage.LanguageReference.create(Language::class.java).get(null) }
            val marker = Any()
            val effects = AtomicInteger()
            val outerEffects = AtomicInteger()
            val (inner, outer, driver) = entered(context) {
                val driver = Driver()
                val inner = Thunk(ThunkYieldProofRoot.target(language, effects, AtomicInteger(),
                    ThunkYieldProofRoot.Gate(), marker), null)
                val outer = Thunk(object : RootNode(null) {
                    override fun execute(frame: VirtualFrame): Any? {
                        outerEffects.incrementAndGet()
                        return driver.force(inner)
                    }
                }.callTarget, null)
                Triple(inner, outer, driver)
            }
            val yielded = entered(context) { assertThrows(ThunkSuspended::class.java) { driver.force(outer) } }
            assertSame(inner, yielded.thunk)
            assertEquals(5, inner.state)
            assertEquals(4, outer.state, "The uncaptured caller must not retain a blackhole")
            Executors.newSingleThreadExecutor().use { pool ->
                val waiter = pool.submit<RuntimeFault> { entered(context) {
                    assertThrows(RuntimeFault::class.java) { driver.force(outer) }
                } }
                assertTrue(waiter.get(5, TimeUnit.SECONDS).message!!.contains("no resumable continuation"))
            }
            assertEquals(1, outerEffects.get(), "Never replay the caller's effect")
            entered(context) {
                assertThrows(ThunkSuspended::class.java) { driver.force(inner) }
                val answer = driver.force(inner) as ThunkYieldProofRoot.Answer
                assertEquals(42L, answer.number)
                assertSame(marker, answer.marker)
            }
            assertEquals(1, effects.get())
        }
    }

    @Test fun nestedRootYieldCannotMasqueradeAsItsCallersContinuation() {
        executionContext().use { context ->
            context.initialize("thc")
            val language = entered(context) { TruffleLanguage.LanguageReference.create(Language::class.java).get(null) }
            val effects = AtomicInteger()
            val calls = AtomicInteger()
            val (outer, driver) = entered(context) {
                val innerTarget = ThunkYieldProofRoot.target(language, effects, AtomicInteger(),
                    ThunkYieldProofRoot.Gate(), Any())
                val outerTarget = object : RootNode(null) {
                    override fun execute(frame: VirtualFrame): Any? {
                        calls.incrementAndGet()
                        return Calls.target(innerTarget, arrayOf(0L))
                    }
                }.callTarget
                Thunk(outerTarget, null) to Driver()
            }
            val unsupported = entered(context) {
                assertThrows(IllegalStateException::class.java) { driver.force(outer) }
            }
            assertTrue(unsupported.message!!.contains("no captured caller segment"))
            assertEquals(4, outer.state)
            entered(context) { assertThrows(RuntimeFault::class.java) { driver.force(outer) } }
            assertEquals(1, calls.get())
            assertEquals(1, effects.get())
        }
    }
}
