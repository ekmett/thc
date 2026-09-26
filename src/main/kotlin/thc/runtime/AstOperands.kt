// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.ExplodeLoop

/** Lowering lifts primitive operands into locals before entering the primitive.
 * A cut owns the completed prefix; no committed operation is executed twice. */
internal class AstOperands(@field:Children private var operands: Array<LocalBinding>,
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

    override fun execute(frame: VirtualFrame): Any? { prepareBody(frame); return body.execute(frame) }
    override fun executeLong(frame: VirtualFrame): Long { prepareBody(frame); return body.executeLong(frame) }
    override fun executeFloat(frame: VirtualFrame): Float { prepareBody(frame); return body.executeFloat(frame) }
    override fun executeDouble(frame: VirtualFrame): Double { prepareBody(frame); return body.executeDouble(frame) }
    override fun executeClosure(frame: VirtualFrame): Closure { prepareBody(frame); return body.executeClosure(frame) }
    override fun executeDataValue(frame: VirtualFrame): DataValue { prepareBody(frame); return body.executeDataValue(frame) }
    override fun executeAddress(frame: VirtualFrame): ManagedAddress { prepareBody(frame); return body.executeAddress(frame) }
    override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Any? {
        prepareBody(frame, slots, offset)
        return body.executeTuple(frame, slots, offset)
    }
}
