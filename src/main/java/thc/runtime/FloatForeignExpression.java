// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.frame.VirtualFrame;

final class FloatForeignExpression extends Expr {
    private final FloatForeignOp operation;
    @Child private Expr value;
    @Child private Expr state;

    FloatForeignExpression(FloatForeignOp operation, Expr value, Expr state, CoreRepresentation proof) {
        this.operation = operation;
        this.value = value;
        this.state = state;
        setRepresentation(new CoreRepresentation(proof.getKind(), true, proof.getPresent(),
                proof.getPrimReps(), proof.getComponents(), proof.getVector(), proof.getAlternatives(),
                proof.getTagSlot(), proof.getAlternativeSlots()));
    }

    @Override public Object execute(VirtualFrame frame) {
        CompilerDirectives.transferToInterpreterAndInvalidate();
        throw new RuntimeFault("Floating foreign call requires a tuple destination");
    }

    @Override public Object executeTuple(VirtualFrame frame, int[] slots, int offset) {
        if (operation.getSingle()) {
            float argument = value.executeRequiredFloat(frame);
            TupleResultsKt.requireVoidCarrier(state.execute(frame));
            if (operation.getRounding()) FrameAccess.INSTANCE.writeFloat(frame, slots[offset], FloatForeignOp.round(argument));
            else FrameAccess.INSTANCE.writeLong(frame, slots[offset], operation.classify(argument));
        } else {
            double argument = value.executeRequiredDouble(frame);
            TupleResultsKt.requireVoidCarrier(state.execute(frame));
            if (operation.getRounding()) FrameAccess.INSTANCE.writeDouble(frame, slots[offset], FloatForeignOp.round(argument));
            else FrameAccess.INSTANCE.writeLong(frame, slots[offset], operation.classify(argument));
        }
        return null;
    }
}
