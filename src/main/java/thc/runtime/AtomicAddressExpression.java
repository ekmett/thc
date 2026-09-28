// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;
import com.oracle.truffle.api.frame.VirtualFrame;
import static thc.runtime.RuntimeServiceStatus.fault;
public final class AtomicAddressExpression extends Expr {
    private final AtomicAddressOp operation;
    @Children private Expr[] operands;
    public AtomicAddressExpression(AtomicAddressOp operation, CoreRepresentation proof, Expr[] operands) {
        this.operation = operation; this.operands = operands;
        setRepresentation(proof.copy(proof.getKind(), true, proof.getPresent(), proof.getPrimReps(), proof.getComponents(), proof.getVector(), proof.getAlternatives(), proof.getTagSlot(), proof.getAlternativeSlots()));
    }
    @Override public Object execute(VirtualFrame frame) {
        if (operation != AtomicAddressOp.WRITE) throw fault("Atomic Addr# result requires a tuple destination");
        var address = operands[0].executeRequiredAddress(frame); long value = operands[1].executeRequiredLong(frame);
        Object token = operands[2].execute(frame); ManagedByteArray.requireState(token);
        operation.numeric(address, value); return token;
    }
    @Override public Object executeTuple(VirtualFrame frame, int[] slots, int offset) {
        if (operation == AtomicAddressOp.WRITE) throw fault("Atomic Addr# write does not produce a tuple");
        var location = operands[0].executeRequiredAddress(frame);
        if (operation.getPointer()) {
            var operand = operands[1].executeRequiredAddress(frame);
            var replacement = operation.getCas() ? operands[2].executeRequiredAddress(frame) : null;
            ManagedByteArray.requireState(operands[operands.length - 1].execute(frame));
            FrameAccess.write(frame, slots[offset], operation.address(location, operand, replacement));
        } else if (operation.getWidth() < 8) {
            int operand = operands[1].executeRequiredInt(frame), replacement = operands[2].executeRequiredInt(frame);
            ManagedByteArray.requireState(operands[operands.length - 1].execute(frame));
            FrameAccess.writeInt(frame, slots[offset], operation.numericInt(location, operand, replacement));
        } else {
            long operand = operation == AtomicAddressOp.READ ? 0 : operands[1].executeRequiredLong(frame);
            long replacement = operation.getCas() ? operands[2].executeRequiredLong(frame) : 0;
            ManagedByteArray.requireState(operands[operands.length - 1].execute(frame));
            FrameAccess.writeLong(frame, slots[offset], operation.numeric(location, operand, replacement));
        }
        return null;
    }
}
