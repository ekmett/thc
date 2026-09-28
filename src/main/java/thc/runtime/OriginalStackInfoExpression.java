// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.UnexpectedResultException;

/** Original getter/IPE calls retain typed managed addresses and target layout. */
public final class OriginalStackInfoExpression extends Expr {
    private final OriginalStackInfoOp operation;
    private final TargetLayout layout;
    @Children private Expr[] operands;
    public OriginalStackInfoExpression(OriginalStackInfoOp operation, TargetLayout layout, Expr[] operands, CoreRepresentation proof) {
        this.operation = operation; this.layout = layout; this.operands = operands;
        setRepresentation(proof.copy(proof.getKind(), true, proof.getPresent(), proof.getPrimReps(), proof.getComponents(),
            proof.getVector(), proof.getAlternatives(), proof.getTagSlot(), proof.getAlternativeSlots()));
    }
    @Override public Object execute(VirtualFrame frame) {
        if (operation == OriginalStackInfoOp.STACK_INFO) return executeRequiredAddress(frame);
        if (operation == OriginalStackInfoOp.STACK_FIELDS) return executeRequiredInt(frame);
        if (operation == OriginalStackInfoOp.WORD) return executeRequiredLong(frame);
        if (operation.getTupleResult()) throw RuntimeFault.fault("Original stack info tuple requires a destination");
        return incompatible(frame);
    }
    @Override public int executeInt(VirtualFrame frame) throws UnexpectedResultException {
        if (operation == OriginalStackInfoOp.STACK_FIELDS) return (int) ManagedStackRuntime.stackFields(operands[0].execute(frame), layout);
        return super.executeInt(frame);
    }
    @Override public long executeLong(VirtualFrame frame) throws UnexpectedResultException {
        if (operation == OriginalStackInfoOp.WORD)
            return ManagedStackRuntime.word(operands[0].execute(frame), operands[1].executeRequiredLong(frame), layout);
        return super.executeLong(frame);
    }
    private Object incompatible(VirtualFrame frame) {
        return ManagedStackRuntime.incompatibleGetter(operation, operands[0].execute(frame), operands[1].executeRequiredLong(frame), layout);
    }
    @Override public ManagedAddress executeAddress(VirtualFrame frame) throws UnexpectedResultException {
        if (operation == OriginalStackInfoOp.STACK_INFO) return ManagedStackRuntime.stackInfo(operands[0].execute(frame), layout);
        return super.executeAddress(frame);
    }
    @Override public Object executeTuple(VirtualFrame frame, int[] slots, int offset) {
        // Explicit comparisons keep the constant operation out of mutable enum ordinal tables.
        if (operation == OriginalStackInfoOp.FRAME_INFO) {
            Object snapshot = operands[0].execute(frame);
            long wordOffset = operands[1].executeRequiredLong(frame);
            ManagedStackFrameInfo result = ManagedStackRuntime.frameInfo(snapshot, wordOffset, layout);
            FrameAccess.writeObject(frame, slots[offset], result.standard());
            FrameAccess.writeObject(frame, slots[offset + 1], result.key());
        } else if (operation == OriginalStackInfoOp.SMALL_BITMAP) {
            ManagedStackBitmap bitmap = ManagedStackRuntime.smallBitmap(operands[0].execute(frame), operands[1].executeRequiredLong(frame), layout);
            FrameAccess.writeLong(frame, slots[offset], bitmap.getBitmap());
            FrameAccess.writeLong(frame, slots[offset + 1], bitmap.getSize());
        } else if (operation == OriginalStackInfoOp.ADVANCE) {
            ManagedStackAdvance next = ManagedStackRuntime.advance(operands[0].execute(frame), operands[1].executeRequiredLong(frame), layout);
            FrameAccess.writeObject(frame, slots[offset], next.getSnapshot());
            FrameAccess.writeLong(frame, slots[offset + 1], next.getWordOffset());
            FrameAccess.writeLong(frame, slots[offset + 2], next.getHasNext());
        } else if (operation == OriginalStackInfoOp.LOOKUP_IPE) {
            ManagedAddress key = operands[0].executeRequiredAddress(frame);
            ManagedAddress destination = operands[1].executeRequiredAddress(frame);
            TupleResultsKt.requireVoidCarrier(operands[2].execute(frame));
            FrameAccess.writeInt(frame, slots[offset], (int) ManagedStackRuntime.lookupIpe(key, destination, layout));
        } else if (operation.getTupleResult()) incompatible(frame);
        else throw RuntimeFault.fault("Original stack info scalar cannot write a tuple");
        return null;
    }
}
