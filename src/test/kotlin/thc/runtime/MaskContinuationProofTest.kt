// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.bytecode.ContinuationResult
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.RootNode
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.Language
import thc.executionContext
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** A test-only DSL continuation across nested mask and genuine guest catch scopes. */
class MaskContinuationProofTest {
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

    @Test fun compiledNestedMasksParkToAmbientAndResumeOnAnotherCarrier() {
        executionContext().use { context ->
            context.initialize("thc")
            val language = entered(context) { TruffleLanguage.LanguageReference.create(Language::class.java).get(null) }
            val driver = entered(context) { Driver() }
            val callerEffects = AtomicInteger()
            val compiledEffects = AtomicInteger()
            val childEffects = AtomicInteger()
            val probe = ThunkYieldProofRoot.MaskProbe()
            val selectedChild = entered(context) { AtomicReference(Thunk(object : RootNode(null) {
                override fun execute(frame: VirtualFrame): Any = ThunkYieldProofRoot.Answer(42L, this)
            }.callTarget, null)) }
            val target = entered(context) {
                ThunkYieldProofRoot.maskedCaller(language, selectedChild, callerEffects, compiledEffects,
                    MaskingState.MASKED_INTERRUPTIBLE, MaskingState.MASKED_UNINTERRUPTIBLE,
                    probe)
            }
            entered(context) {
                repeat(8) { assertEquals(142L, driver.force(Thunk(target, null))) }
                compile(target)
            }
            val callerBefore = callerEffects.get()
            val compiledBefore = compiledEffects.get()
            val child = entered(context) {
                Thunk(ThunkYieldProofRoot.target(language, childEffects, AtomicInteger(),
                    ThunkYieldProofRoot.Gate(), Any()), null)
            }
            selectedChild.set(child)
            val caller = Thunk(target, null)
            entered(context) {
                assertSame(caller, assertThrows(ThunkSuspended::class.java) { driver.force(caller) }.thunk)
                assertEquals(MaskingState.UNMASKED, SynchronousMasking.current(driver),
                    "Parking must unwind both active scopes to this carrier's ambient mask")
            }
            assertEquals(MaskingState.MASKED_UNINTERRUPTIBLE, probe.parked.get())
            assertEquals(5, caller.state)
            val frame = (caller.value as ContinuationResult).frame
            assertTrue((0 until frame.frameDescriptor.numberOfSlots).any {
                frame.isLong(it) && frame.getLong(it) == 100L
            }, "The primitive operand below mask/handler scopes survives")
            assertTrue((0 until frame.frameDescriptor.numberOfSlots).any {
                frame.isObject(it) && frame.getObject(it) == MaskingState.MASKED_UNINTERRUPTIBLE
            }, "The logical active mask is stored in the continuation frame")
            assertTrue(compiledEffects.get() > compiledBefore, "Initial masked caller ran installed guest code")

            Executors.newSingleThreadExecutor().use { pool ->
                val result = pool.submit<Long> { entered(context) {
                    SynchronousMasking.set(driver, MaskingState.MASKED_INTERRUPTIBLE)
                    try {
                        assertSame(caller, assertThrows(ThunkSuspended::class.java) { driver.force(caller) }.thunk)
                        assertEquals(MaskingState.MASKED_INTERRUPTIBLE, SynchronousMasking.current(driver))
                        val answer = driver.force(caller) as Long
                        assertEquals(MaskingState.MASKED_INTERRUPTIBLE, SynchronousMasking.current(driver),
                            "The resumer's ambient mask survives the logical computation")
                        answer
                    } finally { SynchronousMasking.set(driver, MaskingState.UNMASKED) }
                } }
                assertEquals(142L, result.get(5, TimeUnit.SECONDS))
            }
            assertEquals(MaskingState.MASKED_UNINTERRUPTIBLE, probe.reentered.get())
            assertEquals(MaskingState.MASKED_INTERRUPTIBLE, probe.afterInner.get())
            assertEquals(MaskingState.UNMASKED, probe.afterOuter.get())
            assertNull(probe.handlerMask.get())
            assertNull(probe.handlerPayload.get())
            assertEquals(callerBefore + 1, callerEffects.get())
            assertEquals(1, childEffects.get())
            assertEquals(2, caller.state)
            assertEquals(2, child.state)
        }
    }

    @Test fun resumedGuestFailureRunsHandlerUnderMaskAndRestoresCarrier() {
        executionContext().use { context ->
            context.initialize("thc")
            val language = entered(context) { TruffleLanguage.LanguageReference.create(Language::class.java).get(null) }
            val driver = entered(context) { Driver() }
            val payload = Any()
            val callerEffects = AtomicInteger()
            val compiledEffects = AtomicInteger()
            val childEffects = AtomicInteger()
            val probe = ThunkYieldProofRoot.MaskProbe()
            val selectedChild = entered(context) { AtomicReference(Thunk(object : RootNode(null) {
                override fun execute(frame: VirtualFrame): Any = ThunkYieldProofRoot.Answer(42L, this)
            }.callTarget, null)) }
            val target = entered(context) {
                ThunkYieldProofRoot.maskedCaller(language, selectedChild, callerEffects, compiledEffects,
                    MaskingState.UNMASKED, MaskingState.UNMASKED,
                    probe)
            }
            entered(context) {
                repeat(8) { assertEquals(142L, driver.force(Thunk(target, null))) }
                compile(target)
            }
            val compiledBefore = compiledEffects.get()
            val child = entered(context) {
                Thunk(ThunkYieldProofRoot.target(language, childEffects, AtomicInteger(),
                    ThunkYieldProofRoot.Gate(), GuestException(payload, driver)), null)
            }
            selectedChild.set(child)
            val caller = Thunk(target, null)
            entered(context) {
                assertSame(caller, assertThrows(ThunkSuspended::class.java) { driver.force(caller) }.thunk)
                assertEquals(MaskingState.UNMASKED, SynchronousMasking.current(driver))
            }
            assertEquals(MaskingState.UNMASKED, probe.parked.get())
            assertTrue(compiledEffects.get() > compiledBefore)

            Executors.newSingleThreadExecutor().use { pool ->
                val result = pool.submit<Long> { entered(context) {
                    SynchronousMasking.set(driver, MaskingState.MASKED_UNINTERRUPTIBLE)
                    try {
                        assertSame(caller, assertThrows(ThunkSuspended::class.java) { driver.force(caller) }.thunk)
                        assertEquals(MaskingState.MASKED_UNINTERRUPTIBLE, SynchronousMasking.current(driver))
                        val answer = driver.force(caller) as Long
                        assertEquals(MaskingState.MASKED_UNINTERRUPTIBLE, SynchronousMasking.current(driver))
                        answer
                    } finally { SynchronousMasking.set(driver, MaskingState.UNMASKED) }
                } }
                assertEquals(77L, result.get(5, TimeUnit.SECONDS))
            }
            assertEquals(MaskingState.UNMASKED, probe.reentered.get())
            assertEquals(MaskingState.UNMASKED, probe.afterInner.get())
            assertEquals(MaskingState.UNMASKED, probe.afterOuter.get())
            assertEquals(MaskingState.MASKED_INTERRUPTIBLE, probe.handlerMask.get(),
                "An unmasked catch handler runs masked, even after cross-thread resume")
            assertSame(payload, probe.handlerPayload.get())
            assertEquals(1, childEffects.get())
            assertEquals(1, callerEffects.get() - 8)
            assertEquals(3, child.state)
            assertEquals(2, caller.state, "The handler recovers the caller normally")
        }
    }

    @Test fun internalSuspensionBypassesHaskellHandlerAndUnwindsMask() {
        executionContext().use { context ->
            context.initialize("thc")
            val language = entered(context) { TruffleLanguage.LanguageReference.create(Language::class.java).get(null) }
            val driver = entered(context) { Driver() }
            val effects = AtomicInteger()
            val probe = ThunkYieldProofRoot.MaskProbe()
            val (child, caller) = entered(context) {
                val child = Thunk(ThunkYieldProofRoot.target(language, effects, AtomicInteger(),
                    ThunkYieldProofRoot.Gate(), Any()), null)
                child to Thunk(ThunkYieldProofRoot.uncapturedMaskedCaller(language, child, probe), null)
            }
            entered(context) {
                assertSame(child, assertThrows(ThunkSuspended::class.java) { driver.force(caller) }.thunk)
                assertEquals(MaskingState.UNMASKED, SynchronousMasking.current(driver))
                assertThrows(RuntimeFault::class.java) { driver.force(caller) }
            }
            assertNull(probe.handlerMask.get(), "A Haskell catch cannot consume the internal suspension signal")
            assertNull(probe.handlerPayload.get())
            assertEquals(4, caller.state, "An uncaptured caller fails closed without replay")
            assertEquals(5, child.state)
            assertEquals(1, effects.get())
        }
    }

    @Test fun resumedTailTrampolineKeepsLogicalMaskUntilChainCompletes() {
        executionContext().use { context ->
            context.initialize("thc")
            val language = entered(context) { TruffleLanguage.LanguageReference.create(Language::class.java).get(null) }
            val driver = entered(context) { Driver() }
            val effects = AtomicInteger()
            val compiledEffects = AtomicInteger()
            val childEffects = AtomicInteger()
            val observedTailMask = AtomicReference<MaskingState>()
            val probe = ThunkYieldProofRoot.MaskProbe()
            val (selectedChild, tailTarget) = entered(context) {
                val warm = Thunk(object : RootNode(null) {
                    override fun execute(frame: VirtualFrame): Any = ThunkYieldProofRoot.Answer(42L, this)
                }.callTarget, null)
                val tail = object : RootNode(null) {
                    override fun execute(frame: VirtualFrame): Any {
                        val mask = SynchronousMasking.current(this)
                        observedTailMask.set(mask)
                        return mask.tag
                    }
                }.callTarget
                AtomicReference(warm) to tail
            }
            val tailProbe = ThunkYieldProofRoot.TailProbe(tailTarget)
            val target = entered(context) {
                ThunkYieldProofRoot.maskedCaller(language, selectedChild, effects, compiledEffects,
                    MaskingState.MASKED_INTERRUPTIBLE, MaskingState.MASKED_UNINTERRUPTIBLE,
                    probe, tailProbe)
            }
            entered(context) {
                repeat(8) { assertEquals(142L, driver.force(Thunk(target, null))) }
                compile(target)
            }
            val effectsBefore = effects.get()
            val compiledBefore = compiledEffects.get()
            val child = entered(context) {
                Thunk(ThunkYieldProofRoot.target(language, childEffects, AtomicInteger(),
                    ThunkYieldProofRoot.Gate(), Any()), null)
            }
            selectedChild.set(child)
            tailProbe.armed = true
            val caller = Thunk(target, null)
            entered(context) {
                assertSame(caller, assertThrows(ThunkSuspended::class.java) { driver.force(caller) }.thunk)
                assertEquals(MaskingState.UNMASKED, SynchronousMasking.current(driver))
            }
            assertTrue(compiledEffects.get() > compiledBefore)
            Executors.newSingleThreadExecutor().use { pool ->
                val result = pool.submit<Long> { entered(context) {
                    SynchronousMasking.set(driver, MaskingState.MASKED_INTERRUPTIBLE)
                    try {
                        assertSame(caller, assertThrows(ThunkSuspended::class.java) { driver.force(caller) }.thunk)
                        assertEquals(MaskingState.MASKED_INTERRUPTIBLE, SynchronousMasking.current(driver))
                        val answer = driver.force(caller) as Long
                        assertEquals(MaskingState.MASKED_INTERRUPTIBLE, SynchronousMasking.current(driver))
                        answer
                    } finally { SynchronousMasking.set(driver, MaskingState.UNMASKED) }
                } }
                assertEquals(1L, result.get(5, TimeUnit.SECONDS))
            }
            assertEquals(MaskingState.MASKED_UNINTERRUPTIBLE, observedTailMask.get(),
                "The tail target still belongs to the resumed logical mask scope")
            assertEquals(effectsBefore + 1, effects.get())
            assertEquals(1, childEffects.get())
            assertEquals(2, caller.state)
        }
    }
}
