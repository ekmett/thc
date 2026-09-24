// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.frame.VirtualFrame

/** Original getter/IPE calls retain typed managed addresses and target layout. */
internal class OriginalStackInfoExpression(private val operation: OriginalStackInfoOp,
    private val layout: TargetLayout, @field:Children private var operands: Array<Expr>,
    proof: CoreRepresentation) : Expr() {
    init { representation = proof.copy(evaluated = true) }

    override fun execute(frame: VirtualFrame): Any = executeAddress(frame)

    override fun executeAddress(frame: VirtualFrame): ManagedAddress {
        if (operation != OriginalStackInfoOp.STACK_INFO)
            fault("Original stack info tuple requires a destination")
        return ManagedStackRuntime.stackInfo(operands[0].execute(frame), layout)
    }

    override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Any? {
        when (operation) {
            OriginalStackInfoOp.FRAME_INFO -> {
                val snapshot = operands[0].execute(frame)
                val wordOffset = operands[1].executeRequiredLong(frame)
                val (standard, key) = ManagedStackRuntime.frameInfo(snapshot, wordOffset, layout)
                FrameAccess.write(frame, slots[offset], standard)
                FrameAccess.write(frame, slots[offset + 1], key)
            }
            OriginalStackInfoOp.LOOKUP_IPE -> {
                val key = operands[0].executeRequiredAddress(frame)
                val destination = operands[1].executeRequiredAddress(frame)
                requireVoidCarrier(operands[2].execute(frame))
                FrameAccess.writeLong(frame, slots[offset], ManagedStackRuntime.lookupIpe(key, destination, layout))
            }
            else -> fault("Original stack info scalar cannot write a tuple")
        }
        return null
    }
}
