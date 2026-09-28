// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.CompilerDirectives
import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.RootNode
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.Main.executionContext
import thc.Language

class SavedGuestContinuationTest {
    private class Driver : RootNode(null) {
        @Child private var force = Force(Metrics(false))
        override fun execute(frame: VirtualFrame): Any? = force.execute(frame, frame.arguments[0])
        fun force(thunk: Thunk): Any? = Calls.target(callTarget, arrayOf(thunk))
    }

    private class Saved(
        private val savedRoot: Any,
        private val savedYield: Any?,
        private val resume: (Any?) -> Any?
    ) : SavedGuestContinuation {
        override fun getSourceRoot(): Any = savedRoot
        override fun getYielded(): Any? = savedYield
        override fun getIdentity(): Any = this
        var resumes = 0
        override fun continueWith(input: Any?): Any? {
            resumes++
            return resume(input)
        }
    }

    private fun parked(target: RootCallTarget, saved: Saved): Thunk = Thunk(target, null).apply {
        this.target = null
        this.environment = null
        this.value = saved
        this.state = 5
    }

    private class CompletionProbe : Expr() {
        init {
            representation = CoreRepresentation(CoreKind.OBJECT, true, true,
                listOf("BoxedRep (Just Unlifted)"))
        }
        var compiled = 0
        override fun execute(frame: VirtualFrame): Any? {
            if (CompilerDirectives.inCompiledCode()) compiled++
            return complete(frame.arguments[1])
        }
        fun complete(value: Any?, target: RootCallTarget? = null, shape: TupleShape? = null): Any? =
            AstControl.complete(this, value, target, shape)
    }

    @Test fun ordinaryCompletionKeepsItsFirstInstalledIdentityAndColdProofChecks() {
        executionContext().use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                fun root(probe: CompletionProbe, enabled: Boolean) = FunctionRoot(language, FrameLayout().build(), "completion control", null,
                    intArrayOf(), intArrayOf(), intArrayOf(), probe,
                    Metrics(false), emptyArray(), probe.representation, probe.coreSourceLocation,
                    booleanArrayOf(), null, null, intArrayOf(),
                    null, enabled, emptyArray(), false,
                    FunctionRootRole.FUNCTION, false)
                val probe = CompletionProbe()
                val root = root(probe, true)
                val target = root.callTarget
                target.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
                assertEquals(true, target.javaClass.getMethod("isValidLastTier").invoke(target))
                val marker = Any()
                assertSame(marker, Calls.target(target, arrayOf(0L, marker)))
                assertEquals(1, probe.compiled, "The first call must enter the original installed guest code")
                assertSame(target, root.callTarget)
                assertEquals(true, target.javaClass.getMethod("isValidLastTier").invoke(target))
                for (ordinary in listOf(null, Unit, 17L, 23, marker, arrayOf(marker)))
                    assertSame(ordinary, probe.complete(ordinary))

                val ambient = SynchronousMasking.current(probe)
                SynchronousMasking.set(probe, MaskingState.MASKED_INTERRUPTIBLE)
                try {
                    val saved = Saved(root, Unit) { fail<Any>("Completion must not resume the child") }
                    val cut = assertThrows(AstCapture::class.java) { probe.complete(saved, target) }
                    val parked = cut.yielded as CallSegmentSuspended
                    assertSame(saved, parked.segment.value)
                    assertEquals(5, parked.segment.state)
                    assertEquals(MaskingState.MASKED_INTERRUPTIBLE, parked.segment.callerMask)
                    assertEquals(MaskingState.MASKED_INTERRUPTIBLE, cut.logicalMask)
                    assertEquals(0, saved.resumes)
                    assertEquals(MaskingState.MASKED_INTERRUPTIBLE, SynchronousMasking.current(probe))

                    val other = root(CompletionProbe(), true).callTarget
                    assertThrows(RuntimeFault::class.java) { probe.complete(saved, other) }
                    assertThrows(RuntimeFault::class.java) { probe.complete(Saved(Any(), Unit) { null }) }
                    assertThrows(RuntimeFault::class.java) { probe.complete(Saved(root, Any()) { null }) }
                    val scalar = CoreRepresentation(CoreKind.LONG, true, true, listOf("IntRep"))
                    val shape = TupleShape(CoreRepresentation(CoreKind.UNKNOWN, true, true,
                        listOf("IntRep"), listOf(scalar)), language)
                    assertThrows(RuntimeFault::class.java) { probe.complete(saved, target, shape) }
                    assertEquals(0, saved.resumes)

                    val disabled = CompletionProbe()
                    root(disabled, false).callTarget
                    assertSame(saved, disabled.complete(saved, other, shape),
                        "A nonresumable root must preserve its existing completion policy")
                } finally { SynchronousMasking.set(probe, ambient) }
            } finally { context.leave() }
        }
    }

    @Test fun coldRecordResumesSharedChildBeforeParentAndPublishesOnce() {
        executionContext().use { context ->
            context.initialize("thc")
            context.enter()
            try {
                val original = object : RootNode(null) {
                    override fun execute(frame: VirtualFrame): Any? = fail<Any>("Original body replayed")
                }.callTarget
                val childSaved = Saved(original.rootNode, Unit) { 42L }
                val child = parked(original, childSaved)
                val parentSaved = Saved(original.rootNode, ThunkSuspended(child)) { input ->
                    val completed = input as ChildResume
                    assertNull(completed.failure)
                    assertEquals(42L, completed.value)
                    43L
                }
                val parent = parked(original, parentSaved)
                val driver = Driver()

                assertEquals(43L, driver.force(parent))
                assertEquals(2, parent.state)
                assertEquals(2, child.state)
                assertEquals(1, parentSaved.resumes)
                assertEquals(1, childSaved.resumes)
                assertEquals(43L, driver.force(parent))
                assertEquals(1, parentSaved.resumes)
            } finally { context.leave() }
        }
    }
}
