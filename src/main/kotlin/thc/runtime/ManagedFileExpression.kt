// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.frame.VirtualFrame
import thc.Language

/** The State component is erased; the payload retains its exact address/long carrier. */
internal class ManagedFileExpression(private val operation: ManagedFileOp,
    @field:Children private var operands: Array<Expr>, proof: CoreRepresentation) : Expr() {
    init { representation = proof.copy(evaluated = true) }
    override fun execute(frame: VirtualFrame): Nothing = fault("Managed file call requires a State/result tuple destination")

    private fun files(frame: VirtualFrame): ManagedFiles {
        requireVoidCarrier(operands.last().execute(frame))
        return Language.currentState(this).files
    }

    override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Any? {
        if (operation == ManagedFileOp.ERROR_MESSAGE) {
            FrameAccess.write(frame, slots[offset], files(frame).errorMessage())
            return null
        }
        // Compare the constant operation directly. Kotlin's synthetic enum-switch
        // array hides it from partial evaluation and merges incompatible frame paths.
        val result = when {
            operation == ManagedFileOp.OPEN -> {
                val path = operands[0].executeRequiredAddress(frame)
                val mode = operands[1].executeRequiredLong(frame)
                files(frame).open(path, mode)
            }
            operation == ManagedFileOp.READ || operation == ManagedFileOp.WRITE -> {
                val fd = operands[0].executeRequiredLong(frame)
                val address = operands[1].executeRequiredAddress(frame)
                val count = operands[2].executeRequiredLong(frame)
                val files = files(frame)
                if (operation == ManagedFileOp.READ) files.read(fd, address, count) else files.write(fd, address, count)
            }
            operation == ManagedFileOp.CLOSE -> {
                val fd = operands[0].executeRequiredLong(frame)
                files(frame).close(fd)
            }
            operation == ManagedFileOp.ERROR_KIND -> files(frame).errorKind()
            operation == ManagedFileOp.SEEK -> {
                val fd = operands[0].executeRequiredLong(frame)
                val displacement = operands[1].executeRequiredLong(frame)
                val mode = operands[2].executeRequiredLong(frame)
                files(frame).seek(fd, displacement, mode)
            }
            operation == ManagedFileOp.SIZE -> {
                val fd = operands[0].executeRequiredLong(frame)
                files(frame).size(fd)
            }
            operation == ManagedFileOp.SET_SIZE -> {
                val fd = operands[0].executeRequiredLong(frame)
                val length = operands[1].executeRequiredLong(frame)
                files(frame).setSize(fd, length)
            }
            operation == ManagedFileOp.IS_TERMINAL -> {
                val fd = operands[0].executeRequiredLong(frame)
                files(frame).isTerminal(fd)
            }
            operation == ManagedFileOp.DEVICE_TYPE -> {
                val fd = operands[0].executeRequiredLong(frame)
                files(frame).deviceType(fd)
            }
            else -> error("Address result handled above")
        }
        FrameAccess.writeLong(frame, slots[offset], result)
        return null
    }
}
