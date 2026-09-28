// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.UnexpectedResultException;
import static thc.runtime.RuntimeServiceStatus.fault;
public final class FloatingAddressExpression extends Expr {
    private final FloatingAddressOp operation;
    private final boolean byteOffset;
    @Children private Expr[] operands;
    public FloatingAddressExpression(FloatingAddressOp operation, CoreRepresentation proof, Expr[] operands) { this(operation, proof, operands, false); }
    public FloatingAddressExpression(FloatingAddressOp operation, CoreRepresentation proof, Expr[] operands, boolean byteOffset) {
        this.operation = operation; this.operands = operands; this.byteOffset = byteOffset;
        setRepresentation(proof.copy(proof.getKind(), true, proof.getPresent(), proof.getPrimReps(), proof.getComponents(), proof.getVector(), proof.getAlternatives(), proof.getTagSlot(), proof.getAlternativeSlots()));
    }
    @Override public Object execute(VirtualFrame frame) {
        if (operation.getTuple()) throw fault("Floating Addr# tuple read requires a destination");
        if (operation.getWrite()) {
            var address = operands[0].executeRequiredAddress(frame); long index = operands[1].executeRequiredLong(frame);
            Object token;
            if (operation.getFloating()) {
                float value = operands[2].executeRequiredFloat(frame); token = operands[3].execute(frame);
                ManagedByteArray.requireState(token); FloatingAddresses.writeFloat(address, index, value, byteOffset);
            } else {
                double value = operands[2].executeRequiredDouble(frame); token = operands[3].execute(frame);
                ManagedByteArray.requireState(token); FloatingAddresses.writeDouble(address, index, value, byteOffset);
            }
            return token;
        }
        try { if (operation == FloatingAddressOp.INDEX_FLOAT) return executeFloat(frame); return executeDouble(frame); }
        catch (UnexpectedResultException failure) { throw propagate(failure); }
    }
    @Override public float executeFloat(VirtualFrame frame) throws UnexpectedResultException {
        if (operation == FloatingAddressOp.INDEX_FLOAT) return FloatingAddresses.readFloat(operands[0].executeRequiredAddress(frame), operands[1].executeRequiredLong(frame), byteOffset);
        if (operation == FloatingAddressOp.INDEX_DOUBLE) return super.executeFloat(frame);
        throw fault("Floating Addr# operation does not produce a scalar Float");
    }
    @Override public double executeDouble(VirtualFrame frame) throws UnexpectedResultException {
        if (operation == FloatingAddressOp.INDEX_DOUBLE) return FloatingAddresses.readDouble(operands[0].executeRequiredAddress(frame), operands[1].executeRequiredLong(frame), byteOffset);
        if (operation == FloatingAddressOp.INDEX_FLOAT) return super.executeDouble(frame);
        throw fault("Floating Addr# operation does not produce a scalar Double");
    }
    @Override public Object executeTuple(VirtualFrame frame, int[] slots, int offset) {
        if (!operation.getTuple()) throw fault("Floating Addr# operation does not produce a tuple");
        var address = operands[0].executeRequiredAddress(frame); long index = operands[1].executeRequiredLong(frame);
        ManagedByteArray.requireState(operands[2].execute(frame));
        if (operation.getFloating()) FrameAccess.writeFloat(frame, slots[offset], FloatingAddresses.readFloat(address, index, byteOffset));
        else FrameAccess.writeDouble(frame, slots[offset], FloatingAddresses.readDouble(address, index, byteOffset));
        return null;
    }
    @SuppressWarnings("unchecked") private static <E extends Throwable> RuntimeException propagate(Throwable failure) throws E { throw (E) failure; }
}
