// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.frame.VirtualFrame

/** Original getter/IPE calls retain typed managed addresses and target layout. */
internal class OriginalStackInfoExpression(private val operation: OriginalStackInfoOp,
    private val layout: TargetLayout, @field:Children private var operands: Array<Expr>,
    proof: CoreRepresentation) : Expr() {
    init { representation = proof.copy(evaluated = true) }

    override fun execute(frame: VirtualFrame): Any {
        if (operation == OriginalStackInfoOp.STACK_INFO) return executeAddress(frame)
        if (operation == OriginalStackInfoOp.STACK_FIELDS) return executeLong(frame)
        if (operation.tupleResult) fault("Original stack info tuple requires a destination")
        return incompatible(frame)
    }

    override fun executeLong(frame: VirtualFrame): Long {
        if (operation == OriginalStackInfoOp.STACK_FIELDS)
            return ManagedStackRuntime.stackFields(operands[0].execute(frame), layout)
        return incompatible(frame)
    }

    private fun incompatible(frame: VirtualFrame): Nothing = ManagedStackRuntime.incompatibleGetter(operation,
        operands[0].execute(frame), operands[1].executeRequiredLong(frame), layout)

    override fun executeAddress(frame: VirtualFrame): ManagedAddress {
        if (operation != OriginalStackInfoOp.STACK_INFO)
            fault("Original stack info tuple requires a destination")
        return ManagedStackRuntime.stackInfo(operands[0].execute(frame), layout)
    }

    override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Any? {
        // Enum `when` introduces a mutable ordinal table that partial evaluation
        // cannot fold; keep the operation and its destination shape constant.
        if (operation == OriginalStackInfoOp.FRAME_INFO) {
            val snapshot = operands[0].execute(frame)
            val wordOffset = operands[1].executeRequiredLong(frame)
            val (standard, key) = ManagedStackRuntime.frameInfo(snapshot, wordOffset, layout)
            FrameAccess.writeObject(frame, slots[offset], standard)
            FrameAccess.writeObject(frame, slots[offset + 1], key)
        } else if (operation == OriginalStackInfoOp.SMALL_BITMAP) {
            val bitmap = ManagedStackRuntime.smallBitmap(operands[0].execute(frame), operands[1].executeRequiredLong(frame), layout)
            FrameAccess.writeLong(frame, slots[offset], bitmap.bitmap)
            FrameAccess.writeLong(frame, slots[offset + 1], bitmap.size)
        } else if (operation == OriginalStackInfoOp.ADVANCE) {
            val next = ManagedStackRuntime.advance(operands[0].execute(frame), operands[1].executeRequiredLong(frame), layout)
            FrameAccess.writeObject(frame, slots[offset], next.snapshot)
            FrameAccess.writeLong(frame, slots[offset + 1], next.wordOffset)
            FrameAccess.writeLong(frame, slots[offset + 2], next.hasNext)
        } else if (operation == OriginalStackInfoOp.LOOKUP_IPE) {
            val key = operands[0].executeRequiredAddress(frame)
            val destination = operands[1].executeRequiredAddress(frame)
            requireVoidCarrier(operands[2].execute(frame))
            FrameAccess.writeLong(frame, slots[offset], ManagedStackRuntime.lookupIpe(key, destination, layout))
        } else if (operation.tupleResult) incompatible(frame)
        else fault("Original stack info scalar cannot write a tuple")
        return null
    }
}
