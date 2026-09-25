// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.frame.VirtualFrame

internal class GmpForeignExpression(private val operation: GmpForeignOp,
    @field:Children private var operands: Array<Expr>, proof: CoreRepresentation) : Expr() {
    init { representation = proof.copy(evaluated = true) }
    override fun execute(frame: VirtualFrame): Nothing = fault("Original GMP call requires a State/result tuple destination")
    override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Any? {
        val first = operands[0].execute(frame)
        // Primitive counts/words stay long locals; object/primitive interleaving
        // follows the original FCallId argument order exactly.
        // Direct enum comparisons fold during partial evaluation; Kotlin's
        // synthetic enum-switch array otherwise retains unrelated operand
        // accesses and non-inlineable VirtualFrame calls in the graph.
        val result = when {
            operation.form == GmpForm.BINARY -> {
                val second = operands[1].execute(frame); val a = operands[2].executeRequiredLong(frame)
                val third = operands[3].execute(frame); val b = operands[4].executeRequiredLong(frame)
                requireVoidCarrier(operands[5].execute(frame))
                ManagedGmp.invoke(this, operation, first, second, third, null, a, b, 0)
            }
            operation.form == GmpForm.WORD -> {
                val second = operands[1].execute(frame); val a = operands[2].executeRequiredLong(frame)
                val b = operands[3].executeRequiredLong(frame)
                requireVoidCarrier(operands[4].execute(frame))
                ManagedGmp.invoke(this, operation, first, second, null, null, a, b, 0)
            }
            operation.form == GmpForm.COMPARE -> {
                val second = operands[1].execute(frame); val a = operands[2].executeRequiredLong(frame)
                requireVoidCarrier(operands[3].execute(frame))
                ManagedGmp.invoke(this, operation, first, second, null, null, a, 0, 0)
            }
            operation.form == GmpForm.DIVIDE_WORD -> {
                val a = operands[1].executeRequiredLong(frame); val second = operands[2].execute(frame)
                val b = operands[3].executeRequiredLong(frame); val c = operands[4].executeRequiredLong(frame)
                requireVoidCarrier(operands[5].execute(frame))
                ManagedGmp.invoke(this, operation, first, second, null, null, a, b, c)
            }
            operation.form == GmpForm.MODULO_WORD -> {
                val a = operands[1].executeRequiredLong(frame); val b = operands[2].executeRequiredLong(frame)
                requireVoidCarrier(operands[3].execute(frame))
                ManagedGmp.invoke(this, operation, first, null, null, null, a, b, 0)
            }
            else -> { // GmpForm.DIVIDE
                val second = operands[1].execute(frame); val a = operands[2].executeRequiredLong(frame)
                val third = operands[3].execute(frame); val b = operands[4].executeRequiredLong(frame)
                val fourth = operands[5].execute(frame); val c = operands[6].executeRequiredLong(frame)
                requireVoidCarrier(operands[7].execute(frame))
                ManagedGmp.invoke(this, operation, first, second, third, fourth, a, b, c)
            }
        }
        if (operation.result != null) FrameAccess.writeLong(frame, slots[offset], result)
        return null
    }
}
