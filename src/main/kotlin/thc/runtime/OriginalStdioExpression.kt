// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.frame.VirtualFrame

/** Preserve typed addresses and numeric carriers; validate State before effects. */
internal class OriginalStdioExpression(private val operation: OriginalStdioOp,
    @field:Children private var operands: Array<Expr>, proof: CoreRepresentation) : Expr() {
    init { representation = proof.copy(evaluated = true) }
    override fun execute(frame: VirtualFrame): Nothing = fault("Original stdio call requires a State/result tuple destination")

    override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Any? {
        // Direct enum comparison remains constant during partial evaluation.
        val result = if (operation == OriginalStdioOp.ERRNO) {
            requireVoidCarrier(operands[0].execute(frame))
            CoreOriginalStdio.current(this).errno()
        } else {
            val fd = operands[0].executeRequiredLong(frame)
            val address = operands[1].executeRequiredAddress(frame)
            val count = operands[2].executeRequiredLong(frame)
            requireVoidCarrier(operands[3].execute(frame))
            CoreOriginalStdio.current(this).write(fd, address, count)
        }
        FrameAccess.writeLong(frame, slots[offset], result)
        return null
    }
}
