// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.ExplodeLoop

/** Lowering lifts primitive operands into locals before entering the primitive.
 * A cut owns the completed prefix; no committed operation is executed twice. */
internal class AstOperands(@field:Children private var operands: Array<LocalBinding>,
                           @field:CompilationFinal(dimensions = 1) private val temporaries: IntArray,
                           @field:Child private var body: Expr) : Expr() {
    init { representation = body.representation }

    @ExplodeLoop private fun prepare(frame: VirtualFrame, start: Int = 0) {
        for (index in start until operands.size) {
            try { operands[index].write(frame) }
            catch (cut: AstCapture) {
                throw cut.append(object : AstResumeStep {
                    override fun resume(frame: VirtualFrame, input: Any?): Any {
                        prepare(frame, index + 1)
                        return Unit
                    }
                })
            }
        }
    }

    private fun prepareBody(frame: VirtualFrame, slots: IntArray? = null, offset: Int = 0) {
        try { prepare(frame) }
        catch (cut: AstCapture) {
            throw cut.append(object : AstResumeStep {
                override fun resume(frame: VirtualFrame, input: Any?): Any? =
                    if (slots == null) body.execute(frame) else body.executeTuple(frame, slots, offset)
            })
        }
    }

    @ExplodeLoop private fun clear(frame: VirtualFrame) { for (slot in temporaries) frame.clear(slot) }

    private class Cleanup(private val owner: AstOperands, private val steps: List<AstResumeStep>) : AstResumeStep {
        override fun resume(frame: VirtualFrame, input: Any?): Any? = owner.scoped(frame) {
            resumeAstSteps(frame, steps, input)
        }
    }

    private inline fun <T> scoped(frame: VirtualFrame, action: () -> T): T {
        var suspended = false
        try { return action() }
        catch (cut: AstCapture) {
            suspended = true
            throw cut.enclose { Cleanup(this, it) }
        } finally {
            // The operand values belong to this expression, not its caller or
            // next loop iteration. A parked suffix alone keeps them live.
            if (!suspended) clear(frame)
        }
    }

    override fun execute(frame: VirtualFrame): Any? = scoped(frame) { prepareBody(frame); body.execute(frame) }
    override fun executeLong(frame: VirtualFrame): Long = scoped(frame) { prepareBody(frame); body.executeLong(frame) }
    override fun executeFloat(frame: VirtualFrame): Float = scoped(frame) { prepareBody(frame); body.executeFloat(frame) }
    override fun executeDouble(frame: VirtualFrame): Double = scoped(frame) { prepareBody(frame); body.executeDouble(frame) }
    override fun executeClosure(frame: VirtualFrame): Closure = scoped(frame) { prepareBody(frame); body.executeClosure(frame) }
    override fun executeDataValue(frame: VirtualFrame): DataValue = scoped(frame) { prepareBody(frame); body.executeDataValue(frame) }
    override fun executeAddress(frame: VirtualFrame): ManagedAddress = scoped(frame) { prepareBody(frame); body.executeAddress(frame) }
    override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Any? = scoped(frame) {
        prepareBody(frame, slots, offset)
        body.executeTuple(frame, slots, offset)
    }
}
