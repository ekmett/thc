// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.bytecode.BytecodeConfig
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

/** Private three-root proof of logical masks carried by cold call segments. */
class CallMaskSegmentsTest {
    private data class Chain(val parent: Thunk, val a: RootCallTarget,
        val b: RootCallTarget, val c: RootCallTarget)
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

    /** Exactly the private Core call stages, including root-entry and call-active locals. */
    private fun maskedCaller(language: Language, callee: RootCallTarget, targetMask: MaskingState): RootCallTarget {
        val function = Closure(null, NO_PAP_ARGUMENTS, 0, callee)
        val metrics = Metrics(true)
        return BytecodeRootGen.create(language, BytecodeConfig.DEFAULT) { b ->
            b.beginRoot()
            val entry = b.createLocal("root entry mask", "object")
            val prior = b.createLocal("lexical prior mask", "object")
            val active = b.createLocal("caller active mask", "object")
            val result = b.createLocal("application result", "object")
            val suspended = b.createLocal("child segment", "object")
            b.beginBlock()
            b.beginStoreLocal(entry); b.emitCurrentMask(); b.endStoreLocal()
            b.beginStoreLocal(prior); b.emitEnterMask(targetMask); b.endStoreLocal()
            b.beginTryFinally(Runnable {
                b.beginRestoreMask(); b.emitLoadLocal(prior); b.endRestoreMask()
            })
            b.beginBlock()
            b.beginStoreLocal(active); b.emitCurrentMask(); b.endStoreLocal()
            b.beginTryCatch()
            b.beginStoreLocal(result)
            b.beginCaptureApplicationResult(0)
            b.emitLoadConstant(function)
            b.beginApply(0, false, metrics, booleanArrayOf())
            b.emitLoadConstant(function)
            b.endApply()
            b.emitLoadLocal(active)
            b.endCaptureApplicationResult()
            b.endStoreLocal()
            b.beginBlock()
            b.beginStoreLocal(suspended)
            b.beginCallSuspensionOnly(); b.emitLoadException(); b.endCallSuspensionOnly()
            b.endStoreLocal()
            b.beginStoreLocal(result)
            b.beginResumeApplication()
            b.emitLoadLocal(suspended)
            b.beginReenterCallMask()
            b.beginYield()
            b.beginParkCallMask()
            b.emitLoadLocal(suspended)
            b.emitLoadLocal(entry)
            b.emitLoadLocal(active)
            b.endParkCallMask()
            b.endYield()
            b.emitLoadLocal(active)
            b.endReenterCallMask()
            b.endResumeApplication()
            b.endStoreLocal()
            b.endBlock()
            b.endTryCatch()
            b.endBlock()
            b.endTryFinally()
            b.beginReturn(); b.emitLoadLocal(result); b.endReturn()
            b.endBlock()
            b.endRoot()
        }.getNode(0).callTarget
    }

    private fun maskedLeaf(language: Language, payload: Any? = null,
        invalidFinalMask: Boolean = false): RootCallTarget =
        BytecodeRootGen.create(language, BytecodeConfig.DEFAULT) { b ->
            b.beginRoot()
            val prior = b.createLocal("leaf prior mask", "object")
            b.beginBlock()
            b.beginStoreLocal(prior); b.emitEnterMask(MaskingState.MASKED_UNINTERRUPTIBLE); b.endStoreLocal()
            b.beginTryFinally(Runnable {
                b.beginRestoreMask(); b.emitLoadLocal(prior); b.endRestoreMask()
            })
            b.beginBlock()
            b.beginYield(); b.emitLoadConstant(Unit); b.endYield()
            b.beginYield(); b.emitLoadConstant(Unit); b.endYield()
            if (payload != null) {
                b.beginRaiseIO(); b.emitLoadConstant(payload); b.emitLoadConstant(Unit); b.endRaiseIO()
            }
            b.endBlock()
            b.endTryFinally()
            if (invalidFinalMask) b.emitEnterMask(MaskingState.MASKED_INTERRUPTIBLE)
            b.beginReturn(); b.emitLoadConstant(42L); b.endReturn()
            b.endBlock()
            b.endRoot()
        }.getNode(0).callTarget

    @Test fun maskedUnmaskedMaskedChainParksAndResumesAcrossCarrierThreads() {
        executionContext().use { context ->
            context.initialize("thc")
            val language = entered(context) { TruffleLanguage.LanguageReference.create(Language::class.java).get(null) }
            val driver = entered(context) { Driver() }
            val (parent, aTarget, bTarget, cTarget) = entered(context) {
                val c = maskedLeaf(language)
                val b = maskedCaller(language, c, MaskingState.UNMASKED)
                val a = maskedCaller(language, b, MaskingState.MASKED_INTERRUPTIBLE)
                Chain(Thunk(a, null), a, b, c)
            }
            entered(context) {
                assertSame(parent, assertThrows(ThunkSuspended::class.java) { driver.force(parent) }.thunk)
                assertEquals(MaskingState.UNMASKED, SynchronousMasking.current(driver))
            }
            val aContinuation = parent.value as ContinuationResult
            assertSame(aTarget.rootNode, aContinuation.continuationRootNode.sourceRootNode)
            val bSegment = (aContinuation.result as CallSegmentSuspended).segment
            val bContinuation = bSegment.value as ContinuationResult
            val cSegment = (bContinuation.result as CallSegmentSuspended).segment
            assertSame(bTarget.rootNode, bContinuation.continuationRootNode.sourceRootNode)
            assertSame(cTarget.rootNode, (cSegment.value as ContinuationResult).continuationRootNode.sourceRootNode)
            assertEquals(MaskingState.MASKED_INTERRUPTIBLE, bSegment.callerMask)
            assertEquals(MaskingState.UNMASKED, bSegment.logicalMask)
            assertEquals(MaskingState.UNMASKED, cSegment.callerMask)
            assertEquals(MaskingState.MASKED_UNINTERRUPTIBLE, cSegment.logicalMask)
            assertEquals(MaskingState.MASKED_INTERRUPTIBLE,
                (aContinuation.result as CallSegmentSuspended).parkedActiveMask)
            assertEquals(MaskingState.UNMASKED,
                (bContinuation.result as CallSegmentSuspended).parkedActiveMask)

            Executors.newSingleThreadExecutor().use { pool ->
                val first = pool.submit<Unit> { entered(context) {
                    SynchronousMasking.set(driver, MaskingState.MASKED_UNINTERRUPTIBLE)
                    try {
                        assertSame(parent, assertThrows(ThunkSuspended::class.java) { driver.force(parent) }.thunk)
                        assertEquals(MaskingState.MASKED_UNINTERRUPTIBLE, SynchronousMasking.current(driver))
                    } finally { SynchronousMasking.set(driver, MaskingState.UNMASKED) }
                } }
                first.get(5, TimeUnit.SECONDS)
                assertEquals(MaskingState.MASKED_UNINTERRUPTIBLE, cSegment.logicalMask)
                val second = pool.submit<Long> { entered(context) {
                    SynchronousMasking.set(driver, MaskingState.MASKED_INTERRUPTIBLE)
                    try {
                        val answer = driver.force(parent) as Long
                        assertEquals(MaskingState.MASKED_INTERRUPTIBLE, SynchronousMasking.current(driver))
                        answer
                    } finally { SynchronousMasking.set(driver, MaskingState.UNMASKED) }
                } }
                assertEquals(42L, second.get(5, TimeUnit.SECONDS))
            }
            assertEquals(2, cSegment.state)
            assertEquals(2, bSegment.state)
            assertEquals(2, parent.state)
        }
    }

    @Test fun ordinaryNestedMaskedApplicationRunsCompiledWithoutSuspension() {
        executionContext().use { context ->
            context.initialize("thc")
            val language = entered(context) { TruffleLanguage.LanguageReference.create(Language::class.java).get(null) }
            val driver = entered(context) { Driver() }
            val a = entered(context) {
                val c = object : RootNode(null) {
                    override fun execute(frame: VirtualFrame): Any = 42L
                }.callTarget
                val b = maskedCaller(language, c, MaskingState.UNMASKED)
                val a = maskedCaller(language, b, MaskingState.MASKED_INTERRUPTIBLE)
                repeat(8) { assertEquals(42L, driver.force(Thunk(a, null))) }
                compile(b)
                compile(a)
                a
            }
            entered(context) {
                assertEquals(42L, driver.force(Thunk(a, null)))
                assertEquals(MaskingState.UNMASKED, SynchronousMasking.current(driver))
            }
        }
    }

    @Test fun resumedGuestFailureRestoresEachCallerMaskAndCarrierAmbient() {
        executionContext().use { context ->
            context.initialize("thc")
            val language = entered(context) { TruffleLanguage.LanguageReference.create(Language::class.java).get(null) }
            val driver = entered(context) { Driver() }
            val payload = Any()
            val (parent, bSegment, cSegment) = entered(context) {
                val c = maskedLeaf(language, payload)
                val b = maskedCaller(language, c, MaskingState.UNMASKED)
                val a = maskedCaller(language, b, MaskingState.MASKED_INTERRUPTIBLE)
                val parent = Thunk(a, null)
                assertSame(parent, assertThrows(ThunkSuspended::class.java) { driver.force(parent) }.thunk)
                val bSegment = ((parent.value as ContinuationResult).result as CallSegmentSuspended).segment
                val cSegment = ((bSegment.value as ContinuationResult).result as CallSegmentSuspended).segment
                Triple(parent, bSegment, cSegment)
            }
            Executors.newSingleThreadExecutor().use { pool ->
                val result = pool.submit<Unit> { entered(context) {
                    SynchronousMasking.set(driver, MaskingState.MASKED_UNINTERRUPTIBLE)
                    try {
                        assertSame(parent, assertThrows(ThunkSuspended::class.java) { driver.force(parent) }.thunk)
                        assertEquals(MaskingState.MASKED_UNINTERRUPTIBLE, SynchronousMasking.current(driver))
                        assertSame(payload, assertThrows(GuestException::class.java) { driver.force(parent) }.payload)
                        assertEquals(MaskingState.MASKED_UNINTERRUPTIBLE, SynchronousMasking.current(driver))
                        assertSame(payload, assertThrows(GuestException::class.java) { driver.force(parent) }.payload)
                    } finally { SynchronousMasking.set(driver, MaskingState.UNMASKED) }
                } }
                result.get(5, TimeUnit.SECONDS)
            }
            assertEquals(3, cSegment.state)
            assertEquals(3, bSegment.state)
            assertEquals(3, parent.state)
        }
    }

    @Test fun invalidResumedMaskFailsClosedWithoutChangingCarrierAmbient() {
        executionContext().use { context ->
            context.initialize("thc")
            val language = entered(context) { TruffleLanguage.LanguageReference.create(Language::class.java).get(null) }
            val driver = entered(context) { Driver() }
            val (parent, cSegment) = entered(context) {
                val c = maskedLeaf(language, invalidFinalMask = true)
                val b = maskedCaller(language, c, MaskingState.UNMASKED)
                val parent = Thunk(maskedCaller(language, b, MaskingState.MASKED_INTERRUPTIBLE), null)
                assertThrows(ThunkSuspended::class.java) { driver.force(parent) }
                val bSegment = ((parent.value as ContinuationResult).result as CallSegmentSuspended).segment
                parent to ((bSegment.value as ContinuationResult).result as CallSegmentSuspended).segment
            }
            Executors.newSingleThreadExecutor().use { pool ->
                val result = pool.submit<Unit> { entered(context) {
                    SynchronousMasking.set(driver, MaskingState.MASKED_UNINTERRUPTIBLE)
                    try {
                        assertThrows(ThunkSuspended::class.java) { driver.force(parent) }
                        assertEquals(MaskingState.MASKED_UNINTERRUPTIBLE, SynchronousMasking.current(driver))
                        val failure = assertThrows(IllegalStateException::class.java) { driver.force(parent) }
                        assertTrue(failure.message!!.contains("caller mask"))
                        assertEquals(MaskingState.MASKED_UNINTERRUPTIBLE, SynchronousMasking.current(driver))
                    } finally { SynchronousMasking.set(driver, MaskingState.UNMASKED) }
                } }
                result.get(5, TimeUnit.SECONDS)
            }
            assertEquals(4, cSegment.state, "Unknown host failure cannot replay the captured callee")
            assertEquals(5, parent.state, "Parked parent remains available for a sound future continuation")
            entered(context) { assertThrows(RuntimeFault::class.java) { driver.force(parent) } }
        }
    }
}
