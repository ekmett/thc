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
        if (operation == OriginalStdioOp.SIGPROCMASK) {
            val how = operands[0].executeRequiredLong(frame)
            val set = operands[1].executeRequiredAddress(frame)
            val oldset = operands[2].executeRequiredAddress(frame)
            requireVoidCarrier(operands[3].execute(frame))
            FrameAccess.writeLong(frame, slots[offset], ManagedSignalMask.execute(this, how, set, oldset))
            return null
        }
        if (operation.sigset) {
            val address = operands[0].executeRequiredAddress(frame)
            val signal = if (operation == OriginalStdioOp.SIGADDSET) operands[1].executeRequiredLong(frame) else 0L
            requireVoidCarrier(operands.last().execute(frame))
            FrameAccess.writeLong(frame, slots[offset], SigsetImage.execute(operation, address, signal, CoreOriginalStdio.current(this)))
            return null
        }
        if (operation.savedTermios) {
            val fd = operands[0].executeRequiredLong(frame)
            val address = if (operation == OriginalStdioOp.SET_SAVED_TERMIOS) operands[1].executeRequiredAddress(frame)
                else ManagedAddress.nullAddress()
            requireVoidCarrier(operands.last().execute(frame))
            val result = SavedTermios.execute(this, operation, fd, address)
            if (operation == OriginalStdioOp.GET_SAVED_TERMIOS) FrameAccess.writeObject(frame, slots[offset], result)
            return null
        }
        if (operation.termios) {
            val address = if (operation.termiosAddress) operands[0].executeRequiredAddress(frame) else ManagedAddress.nullAddress()
            val value = if (operation == OriginalStdioOp.POKE_LFLAG) operands[1].executeRequiredLong(frame) else 0L
            requireVoidCarrier(operands.last().execute(frame))
            if (operation == OriginalStdioOp.PTR_C_CC) FrameAccess.writeObject(frame, slots[offset], TermiosImage.pointer(address))
            else {
                val result = TermiosImage.scalar(operation, address, value)
                if (operation.result != null) FrameAccess.writeLong(frame, slots[offset], result)
            }
            return null
        }
        if (operation == OriginalStdioOp.LOCALE) {
            requireVoidCarrier(operands[0].execute(frame))
            FrameAccess.write(frame, slots[offset], CoreOriginalStdio.iconv(this).localeEncoding())
            return null
        }
        // Direct enum comparison remains constant during partial evaluation.
        val result = if (operation.stat) {
            val address = if (operation.statField) operands[0].executeRequiredAddress(frame) else ManagedAddress.nullAddress()
            val mode = if (operation == OriginalStdioOp.SIZEOF_STAT || operation.statField) 0L
                else operands[0].executeRequiredLong(frame)
            requireVoidCarrier(operands[operands.lastIndex].execute(frame))
            PosixStat.execute(operation, address, mode)
        } else if (operation == OriginalStdioOp.LOCK) {
            val key = operands[0].executeRequiredLong(frame)
            val device = operands[1].executeRequiredLong(frame)
            val inode = operands[2].executeRequiredLong(frame)
            val writing = operands[3].executeRequiredLong(frame)
            requireVoidCarrier(operands[4].execute(frame))
            CoreOriginalStdio.locks(this).lock(key, device, inode, writing)
        } else if (operation == OriginalStdioOp.UNLOCK) {
            val key = operands[0].executeRequiredLong(frame)
            requireVoidCarrier(operands[1].execute(frame))
            CoreOriginalStdio.locks(this).unlock(key)
        } else if (operation.opening) {
            val path = operands[0].executeRequiredAddress(frame)
            val flags = operands[1].executeRequiredLong(frame)
            val mode = operands[2].executeRequiredLong(frame)
            requireVoidCarrier(operands[3].execute(frame))
            CoreOriginalStdio.current(this).open(path, flags, mode, operation, this)
        } else if (operation == OriginalStdioOp.TCSETATTR) {
            val fd = operands[0].executeRequiredLong(frame)
            val action = operands[1].executeRequiredLong(frame)
            val address = operands[2].executeRequiredAddress(frame)
            requireVoidCarrier(operands[3].execute(frame))
            CoreOriginalStdio.current(this).tcsetattr(fd, action, address)
        } else if (operation.readImage) {
            val fd = operands[0].executeRequiredLong(frame)
            val address = operands[1].executeRequiredAddress(frame)
            requireVoidCarrier(operands[2].execute(frame))
            if (operation == OriginalStdioOp.TCGETATTR) CoreOriginalStdio.current(this).tcgetattr(fd, address)
            else CoreOriginalStdio.current(this).fstat(fd, address)
        } else if (operation == OriginalStdioOp.ICONV_OPEN) {
            val to = operands[0].executeRequiredAddress(frame)
            val from = operands[1].executeRequiredAddress(frame)
            requireVoidCarrier(operands[2].execute(frame))
            CoreOriginalStdio.iconv(this).open(to, from)
        } else if (operation == OriginalStdioOp.ICONV_CLOSE) {
            val handle = operands[0].executeRequiredLong(frame)
            requireVoidCarrier(operands[1].execute(frame))
            CoreOriginalStdio.iconv(this).close(handle)
        } else if (operation == OriginalStdioOp.ICONV) {
            val handle = operands[0].executeRequiredLong(frame)
            val input = operands[1].executeRequiredAddress(frame)
            val inputCount = operands[2].executeRequiredAddress(frame)
            val output = operands[3].executeRequiredAddress(frame)
            val outputCount = operands[4].executeRequiredAddress(frame)
            requireVoidCarrier(operands[5].execute(frame))
            CoreOriginalStdio.iconv(this).convert(handle, input, inputCount, output, outputCount)
        } else if (operation == OriginalStdioOp.STRERROR) {
            val error = operands[0].executeRequiredLong(frame)
            val output = operands[1].executeRequiredAddress(frame)
            val length = operands[2].executeRequiredLong(frame)
            requireVoidCarrier(operands[3].execute(frame))
            CoreOriginalStdio.strerror(this).call(error, output, length)
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
            CoreOriginalStdio.current(this).ready(fd, writing, milliseconds, socket, this)
        } else if (operation == OriginalStdioOp.ISATTY || operation == OriginalStdioOp.CLOSE || operation == OriginalStdioOp.DUP) {
            val fd = operands[0].executeRequiredLong(frame)
            requireVoidCarrier(operands[1].execute(frame))
            val stdio = CoreOriginalStdio.current(this)
            if (operation == OriginalStdioOp.CLOSE) stdio.close(fd)
            else if (operation == OriginalStdioOp.DUP) stdio.duplicate(fd) else stdio.isTerminal(fd)
        } else if (operation == OriginalStdioOp.DUP2) {
            val fd = operands[0].executeRequiredLong(frame)
            val target = operands[1].executeRequiredLong(frame)
            requireVoidCarrier(operands[2].execute(frame))
            CoreOriginalStdio.current(this).duplicateTo(fd, target)
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
