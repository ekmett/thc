// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime

import com.oracle.truffle.api.frame.VirtualFrame
import thc.Language

internal class NativeAllocationExpression(private val operation: NativeAllocationOp,
    @field:Children private var operands: Array<Expr>, proof: CoreRepresentation) : Expr() {
    init { representation = proof.copy(evaluated = true) }
    override fun execute(frame: VirtualFrame): Any = fault("Native allocation call requires a tuple destination")
    override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Any? {
        if (operation == NativeAllocationOp.MALLOC) {
            val size = operands[0].executeRequiredLong(frame)
            requireVoidCarrier(operands[1].execute(frame))
            FrameAccess.writeObject(frame, slots[offset], Language.currentState(this).nativeAllocations.malloc(size))
        } else if (operation == NativeAllocationOp.REALLOC) {
            val address = operands[0].executeRequiredAddress(frame)
            val size = operands[1].executeRequiredLong(frame)
            requireVoidCarrier(operands[2].execute(frame))
            FrameAccess.writeObject(frame, slots[offset], Language.currentState(this).nativeAllocations.realloc(address, size))
        } else {
            val address = operands[0].executeRequiredAddress(frame)
            requireVoidCarrier(operands[1].execute(frame))
            Language.currentState(this).nativeAllocations.free(address)
        }
        return null
    }
}
