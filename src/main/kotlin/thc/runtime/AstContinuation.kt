// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
import com.oracle.truffle.api.frame.MaterializedFrame
import com.oracle.truffle.api.nodes.ControlFlowException
import com.oracle.truffle.api.frame.VirtualFrame
import java.util.concurrent.atomic.AtomicBoolean

/** A cold AST fragment resumes after its child has produced the input value. */
internal interface AstResumeStep {
    fun resume(frame: VirtualFrame, input: Any?): Any?
}

/** Built only while unwinding an interrupted AST activation. Steps run leaf first. */
internal class AstCapture(val yielded: Any?, val logicalMask: MaskingState) : ControlFlowException() {
    private val annotations = StackAnnotations.current(null)
    private val steps = ArrayList<AstResumeStep>()

    @TruffleBoundary fun append(step: AstResumeStep): AstCapture {
        steps.add(step)
        return this
    }

    /** Retain the lexical exception/cleanup scope around the saved child work. */
    @TruffleBoundary fun enclose(wrapper: (List<AstResumeStep>) -> AstResumeStep): AstCapture {
        val scope = wrapper(steps.toList())
        steps.clear()
        steps.add(scope)
        return this
    }

    fun asyncRequest(): AsyncRequest? = when (val marker = yielded) {
        is AsyncRequest -> marker
        is ThunkSuspended -> marker.asyncRequest
        is CallSegmentSuspended -> marker.asyncRequest
        else -> null
    }

    @TruffleBoundary fun freeze(sourceRoot: GuestRoot, frame: MaterializedFrame,
                               rootEntrySpill: Boolean = false): AstContinuation =
        AstContinuation(sourceRoot, (yielded as? AstPendingTail)?.publish() ?: yielded,
            logicalMask, frame, steps.toList(), annotations, rootEntrySpill, yielded is AstPendingTail)

    @TruffleBoundary fun appendRemaining(old: List<AstResumeStep>, first: Int): AstCapture {
        for (i in first until old.size) steps.add(old[i])
        return this
    }

    /** Only an untouched, compiler-certified identity return may forward this
     * child. Any appended/enclosing cleanup keeps the ordinary caller frame. */
    fun pendingTail(): AstPendingTail? =
        (yielded as? AstPendingTail)?.takeIf { steps.size == 1 && steps[0] === it }
}

/** A consumed prefix is never retained after a second interruption. Scope steps
 * use this same runner so their exception and finally handlers remain active. */
internal fun resumeAstSteps(frame: VirtualFrame, steps: List<AstResumeStep>, input: Any?): Any? {
    var answer = input
    for (index in steps.indices) {
        answer = try { steps[index].resume(frame, answer) }
        catch (cut: AstCapture) { throw cut.appendRemaining(steps, index + 1) }
    }
    return answer
}

/** One owned activation; no ordinary AST call allocates this record or materializes its frame. */
internal class AstContinuation(
    override val sourceRoot: GuestRoot,
    override val yielded: Any?,
    private val logicalMask: MaskingState,
    private val frame: MaterializedFrame,
    private val steps: List<AstResumeStep>,
    private val annotations: StackAnnotationState,
    val rootEntrySpill: Boolean = false,
    tailSpill: Boolean = false
) : SavedGuestContinuation {
    override val identity: Any get() = this
    private val claimed = AtomicBoolean()
    var tailSpill = tailSpill
        private set

    /** Called only at a proved tail edge, before its result is published. */
    fun certifyTailEntry() {
        check(rootEntrySpill && !claimed.get())
        tailSpill = true
    }

    /** Only our unpublished tail cut may discard omitted ancestry; arbitrary
     * parked continuations do not acquire a new owner from their source root. */
    fun rebaseTailBloom(liveOwners: Long) {
        check(tailSpill && !claimed.get())
        val root = sourceRoot as FunctionRoot
        frame.setLong(FrameLayout.BLOOM_FILTER, root.entryBloom(liveOwners))
    }

    @TruffleBoundary override fun continueWith(input: Any?): Any? {
        if (!claimed.compareAndSet(false, true)) fault("AST continuation was already resumed")
        val ambient = SynchronousMasking.current(sourceRoot)
        val ambientAnnotations = StackAnnotations.current(sourceRoot)
        val stack = astStackScope(sourceRoot)
        stack.depth++
        try {
            SynchronousMasking.set(sourceRoot, logicalMask)
            StackAnnotations.set(sourceRoot, annotations)
            return try { resumeAstSteps(frame, steps, input) }
            catch (cut: AstCapture) {
                if (sourceRoot is FunctionRoot) sourceRoot.finishCapture(cut, frame)
                else cut.freeze(sourceRoot, frame)
            }
        } finally {
            stack.depth--
            SynchronousMasking.set(sourceRoot, ambient)
            StackAnnotations.set(sourceRoot, ambientAnnotations)
        }
    }
}
