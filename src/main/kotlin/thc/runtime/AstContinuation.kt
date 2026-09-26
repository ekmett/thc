// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

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

    fun append(step: AstResumeStep): AstCapture {
        steps.add(step)
        return this
    }

    /** Retain the lexical exception/cleanup scope around the saved child work. */
    fun enclose(wrapper: (List<AstResumeStep>) -> AstResumeStep): AstCapture {
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

    fun freeze(sourceRoot: GuestRoot, frame: MaterializedFrame): AstContinuation =
        AstContinuation(sourceRoot, yielded, logicalMask, frame, steps.toList(), annotations)

    fun appendRemaining(old: List<AstResumeStep>, first: Int): AstCapture {
        for (i in first until old.size) steps.add(old[i])
        return this
    }
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
    private val annotations: StackAnnotationState
) : SavedGuestContinuation {
    override val identity: Any get() = this
    private val claimed = AtomicBoolean()

    override fun continueWith(input: Any?): Any? {
        if (!claimed.compareAndSet(false, true)) fault("AST continuation was already resumed")
        val ambient = SynchronousMasking.current(sourceRoot)
        val ambientAnnotations = StackAnnotations.current(sourceRoot)
        try {
            SynchronousMasking.set(sourceRoot, logicalMask)
            StackAnnotations.set(sourceRoot, annotations)
            return try { resumeAstSteps(frame, steps, input) }
            catch (cut: AstCapture) { cut.freeze(sourceRoot, frame) }
        } finally {
            SynchronousMasking.set(sourceRoot, ambient)
            StackAnnotations.set(sourceRoot, ambientAnnotations)
        }
    }
}
