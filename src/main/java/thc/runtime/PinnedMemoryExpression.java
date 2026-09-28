// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.UnexpectedResultException;
import static thc.runtime.RuntimeServiceStatus.fault;
public final class PinnedMemoryExpression extends Expr {
    private final PinnedMemoryOp operation;
    private final boolean byteOffset;
    @Children private Expr[] operands;
    public PinnedMemoryExpression(PinnedMemoryOp operation, CoreRepresentation proof, Expr[] operands) { this(operation, proof, operands, false); }
    public PinnedMemoryExpression(PinnedMemoryOp operation, CoreRepresentation proof, Expr[] operands, boolean byteOffset) {
        this.operation = operation; this.operands = operands; this.byteOffset = byteOffset;
        setRepresentation(proof.copy(proof.getKind(), true, proof.getPresent(), proof.getPrimReps(), proof.getComponents(), proof.getVector(), proof.getAlternatives(), proof.getTagSlot(), proof.getAlternativeSlots()));
    }
    @Override public Object execute(VirtualFrame frame) {
        if (operation == PinnedMemoryOp.CONTENTS || operation == PinnedMemoryOp.MUTABLE_CONTENTS)
            return ManagedAddress.fromGuestByteArray(operands[0].execute(frame));
        if (operation == PinnedMemoryOp.WRITE || operation == PinnedMemoryOp.WRITE_INT8 || operation == PinnedMemoryOp.WRITE_CHAR) {
            var address = operands[0].executeRequiredAddress(frame); long index = operands[1].executeRequiredLong(frame);
            int value = operation == PinnedMemoryOp.WRITE_CHAR ? (int) operands[2].executeRequiredLong(frame) : operands[2].executeRequiredInt(frame);
            Object token = operands[3].execute(frame); ManagedByteArray.requireState(token); address.writeWord8Int(index, value); return token;
        }
        if (operation == PinnedMemoryOp.WRITE_INT16 || operation == PinnedMemoryOp.WRITE_WORD16 || operation == PinnedMemoryOp.WRITE_INT32 || operation == PinnedMemoryOp.WRITE_WORD32) {
            var address = operands[0].executeRequiredAddress(frame); long index = operands[1].executeRequiredLong(frame); int value = operands[2].executeRequiredInt(frame);
            Object token = operands[3].execute(frame); ManagedByteArray.requireState(token);
            address.writeNativeInt(index, operation == PinnedMemoryOp.WRITE_INT16 || operation == PinnedMemoryOp.WRITE_WORD16 ? 2 : 4, value, byteOffset); return token;
        }
        if (operation == PinnedMemoryOp.WRITE_WIDE_CHAR || operation == PinnedMemoryOp.WRITE_INT || operation == PinnedMemoryOp.WRITE_WORD || operation == PinnedMemoryOp.WRITE_INT64 || operation == PinnedMemoryOp.WRITE_WORD64) {
            var address = operands[0].executeRequiredAddress(frame); long index = operands[1].executeRequiredLong(frame), value = operands[2].executeRequiredLong(frame);
            Object token = operands[3].execute(frame); ManagedByteArray.requireState(token);
            address.writeNativeScalar(index, operation == PinnedMemoryOp.WRITE_WIDE_CHAR ? 4 : 8, value, byteOffset); return token;
        }
        if (operation == PinnedMemoryOp.WRITE_ADDR) {
            var address = operands[0].executeRequiredAddress(frame); long index = operands[1].executeRequiredLong(frame); var value = operands[2].executeRequiredAddress(frame);
            Object token = operands[3].execute(frame); ManagedByteArray.requireState(token); address.writeAddressElementIndex(index, value, byteOffset); return token;
        }
        if (operation == PinnedMemoryOp.COPY_ADDR_NON_OVERLAPPING || operation == PinnedMemoryOp.COPY_ADDR) {
            var source = operands[0].executeRequiredAddress(frame); var destination = operands[1].executeRequiredAddress(frame); long count = operands[2].executeRequiredLong(frame);
            Object token = operands[3].execute(frame); ManagedByteArray.requireState(token);
            if (operation == PinnedMemoryOp.COPY_ADDR) source.moveTo(destination, count); else source.copyNonOverlappingTo(destination, count); return token;
        }
        if (operation == PinnedMemoryOp.SET_ADDR) {
            var destination = operands[0].executeRequiredAddress(frame); long count = operands[1].executeRequiredLong(frame), value = operands[2].executeRequiredLong(frame);
            Object token = operands[3].execute(frame); ManagedByteArray.requireState(token); destination.fill(count, value); return token;
        }
        throw fault("Pinned memory tuple operation requires a destination");
    }
    @Override public ManagedAddress executeAddress(VirtualFrame frame) throws UnexpectedResultException {
        if (operation == PinnedMemoryOp.CONTENTS || operation == PinnedMemoryOp.MUTABLE_CONTENTS)
            return ManagedAddress.fromGuestByteArray(operands[0].execute(frame));
        return super.executeAddress(frame);
    }
    @Override public Object executeTuple(VirtualFrame frame, int[] slots, int offset) {
        if (operation == PinnedMemoryOp.NEW || operation == PinnedMemoryOp.NEW_ALIGNED) {
            long size = operands[0].executeRequiredLong(frame), alignment = operation == PinnedMemoryOp.NEW_ALIGNED ? operands[1].executeRequiredLong(frame) : 1;
            ManagedByteArray.requireState(operands[operands.length - 1].execute(frame)); FrameAccess.write(frame, slots[offset], PinnedMemory.allocate(size, alignment));
        } else if (operation == PinnedMemoryOp.READ || operation == PinnedMemoryOp.READ_INT8 || operation == PinnedMemoryOp.READ_CHAR ||
            operation == PinnedMemoryOp.READ_WORD16 || operation == PinnedMemoryOp.READ_INT16 || operation == PinnedMemoryOp.READ_WORD32 || operation == PinnedMemoryOp.READ_WIDE_CHAR ||
            operation == PinnedMemoryOp.READ_WORD || operation == PinnedMemoryOp.READ_INT32 || operation == PinnedMemoryOp.READ_INT || operation == PinnedMemoryOp.READ_INT64 || operation == PinnedMemoryOp.READ_WORD64) {
            var address = operands[0].executeRequiredAddress(frame); long index = operands[1].executeRequiredLong(frame); ManagedByteArray.requireState(operands[2].execute(frame));
            var read = operation.getAddressRead();
            if (read != null && read.isInt()) FrameAccess.writeInt(frame, slots[offset], read.readInt(address, index, byteOffset));
            else if (read != null) FrameAccess.writeLong(frame, slots[offset], read.read(address, index, byteOffset));
            else if (operation == PinnedMemoryOp.READ_CHAR) FrameAccess.writeLong(frame, slots[offset], address.indexChar(index));
            else { int value = address.readWord8Int(index); FrameAccess.writeInt(frame, slots[offset], operation == PinnedMemoryOp.READ_INT8 ? (byte) value : value); }
        } else if (operation == PinnedMemoryOp.READ_ADDR) {
            var address = operands[0].executeRequiredAddress(frame); long index = operands[1].executeRequiredLong(frame); ManagedByteArray.requireState(operands[2].execute(frame));
            FrameAccess.write(frame, slots[offset], address.readAddressElementIndex(index, byteOffset));
        } else throw fault("Pinned memory scalar operation has no tuple destination");
        return null;
    }
}
