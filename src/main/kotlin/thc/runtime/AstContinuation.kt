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

    fun freeze(sourceRoot: GuestRoot, frame: MaterializedFrame): AstContinuation =
        AstContinuation(sourceRoot, yielded, logicalMask, frame, steps.toList(), annotations)

    fun appendRemaining(old: List<AstResumeStep>, first: Int): AstCapture {
        for (i in first until old.size) steps.add(old[i])
        return this
    }
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
            var answer = input
            for (index in steps.indices) {
                answer = try { steps[index].resume(frame, answer) }
                catch (cut: AstCapture) {
                    // The completed prefix is gone. Only the unconsumed suffix
                    // belongs to a second interruption of this activation.
                    return cut.appendRemaining(steps, index + 1).freeze(sourceRoot, frame)
                }
            }
            return answer
        } finally {
            SynchronousMasking.set(sourceRoot, ambient)
            StackAnnotations.set(sourceRoot, ambientAnnotations)
        }
    }
}
