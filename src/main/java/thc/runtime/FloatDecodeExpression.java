// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.frame.VirtualFrame;

final class FloatDecodeExpression extends Expr {
    private final FloatDecodeOp operation;
    @Child private Expr operand;

    FloatDecodeExpression(FloatDecodeOp operation, CoreRepresentation proof, Expr operand) {
        this.operation = operation;
        this.operand = operand;
        setRepresentation(new CoreRepresentation(proof.getKind(), true, proof.getPresent(),
                proof.getPrimReps(), proof.getComponents(), proof.getVector(), proof.getAlternatives(),
                proof.getTagSlot(), proof.getAlternativeSlots()));
    }

    @Override public Object execute(VirtualFrame frame) {
        CompilerDirectives.transferToInterpreterAndInvalidate();
        throw new RuntimeFault("Floating decode requires a destination");
    }

    @Override public Object executeTuple(VirtualFrame frame, int[] slots, int offset) {
        long bits = operation == FloatDecodeOp.FLOAT
                ? Float.floatToRawIntBits(operand.executeRequiredFloat(frame))
                : Double.doubleToRawLongBits(operand.executeRequiredDouble(frame));
        if (operation == FloatDecodeOp.DOUBLE_WORDS) {
            FrameAccess.INSTANCE.writeLong(frame, slots[offset], operation.sign(bits));
            FrameAccess.INSTANCE.writeLong(frame, slots[offset + 1], operation.high(bits));
            FrameAccess.INSTANCE.writeLong(frame, slots[offset + 2], operation.low(bits));
            FrameAccess.INSTANCE.writeLong(frame, slots[offset + 3], operation.exponent(bits));
        } else {
            FrameAccess.INSTANCE.writeLong(frame, slots[offset], operation.mantissa(bits));
            FrameAccess.INSTANCE.writeLong(frame, slots[offset + 1], operation.exponent(bits));
        }
        return null;
    }
}
