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
        val result = if (operation.stat) {
            val address = if (operation.statField) operands[0].executeRequiredAddress(frame) else ManagedAddress.nullAddress()
            val mode = if (operation == OriginalStdioOp.SIZEOF_STAT || operation.statField) 0L
                else operands[0].executeRequiredLong(frame)
            requireVoidCarrier(operands[operands.lastIndex].execute(frame))
            PosixStat.execute(operation, address, mode)
        } else if (operation == OriginalStdioOp.ERRNO) {
            requireVoidCarrier(operands[0].execute(frame))
            CoreOriginalStdio.current(this).errno()
        } else if (operation.seekConstant) {
            requireVoidCarrier(operands[0].execute(frame))
            CoreOriginalStdio.current(this).seekConstant(operation)
        } else if (operation.readiness) {
            val fd = operands[0].executeRequiredLong(frame)
            val writing = operands[1].executeRequiredLong(frame)
            val milliseconds = operands[2].executeRequiredLong(frame)
            val socket = operands[3].executeRequiredLong(frame)
            requireVoidCarrier(operands[4].execute(frame))
            CoreOriginalStdio.current(this).ready(fd, writing, milliseconds, socket)
        } else if (operation == OriginalStdioOp.ISATTY || operation == OriginalStdioOp.CLOSE) {
            val fd = operands[0].executeRequiredLong(frame)
            requireVoidCarrier(operands[1].execute(frame))
            val stdio = CoreOriginalStdio.current(this)
            if (operation == OriginalStdioOp.CLOSE) stdio.close(fd) else stdio.isTerminal(fd)
        } else if (operation == OriginalStdioOp.SEEK) {
            val fd = operands[0].executeRequiredLong(frame)
            val displacement = operands[1].executeRequiredLong(frame)
            val whence = operands[2].executeRequiredLong(frame)
            requireVoidCarrier(operands[3].execute(frame))
            CoreOriginalStdio.current(this).seek(fd, displacement, whence)
        } else if (operation == OriginalStdioOp.TRUNCATE) {
            val fd = operands[0].executeRequiredLong(frame)
            val length = operands[1].executeRequiredLong(frame)
            requireVoidCarrier(operands[2].execute(frame))
            CoreOriginalStdio.current(this).truncate(fd, length)
        } else {
            val fd = operands[0].executeRequiredLong(frame)
            val address = operands[1].executeRequiredAddress(frame)
            val count = operands[2].executeRequiredLong(frame)
            requireVoidCarrier(operands[3].execute(frame))
            val stdio = CoreOriginalStdio.current(this)
            if (operation == OriginalStdioOp.READ_SAFE || operation == OriginalStdioOp.READ_UNSAFE)
                stdio.read(fd, address, count)
            else stdio.write(fd, address, count)
        }
        FrameAccess.writeLong(frame, slots[offset], result)
        return null
    }
}
