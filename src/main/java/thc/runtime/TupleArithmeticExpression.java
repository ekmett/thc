// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.frame.VirtualFrame;

final class TupleArithmeticExpression extends Expr {
    private final TupleArithmeticOp operation;
    @Child private Expr left;
    @Child private Expr right;

    TupleArithmeticExpression(TupleArithmeticOp operation, CoreRepresentation proof, Expr left, Expr right) {
        this.operation = operation;
        this.left = left;
        this.right = right;
        setRepresentation(new CoreRepresentation(proof.getKind(), true, proof.getPresent(),
                proof.getPrimReps(), proof.getComponents(), proof.getVector(), proof.getAlternatives(),
                proof.getTagSlot(), proof.getAlternativeSlots()));
    }

    @Override public Object execute(VirtualFrame frame) {
        CompilerDirectives.transferToInterpreterAndInvalidate();
        throw new RuntimeFault("Tuple primitive requires a destination");
    }

    @Override public Object executeTuple(VirtualFrame frame, int[] slots, int offset) {
        if (operation.isInt()) {
            int x = left.executeRequiredInt(frame);
            int y = right.executeRequiredInt(frame);
            FrameAccess.INSTANCE.writeInt(frame, slots[offset], operation.firstInt(x, y));
            FrameAccess.INSTANCE.writeInt(frame, slots[offset + 1], operation.secondInt(x, y));
            return null;
        }
        long x = left.executeRequiredLong(frame);
        long y = right.executeRequiredLong(frame);
        long first = operation.first(x, y);
        long second = operation == TupleArithmeticOp.QUOT_REM_INT || operation == TupleArithmeticOp.QUOT_REM_WORD
                ? x - first * y : operation.second(x, y);
        long third = operation.getResultArity() == 3 ? operation.third(x, y) : 0L;
        FrameAccess.INSTANCE.writeLong(frame, slots[offset], first);
        FrameAccess.INSTANCE.writeLong(frame, slots[offset + 1], second);
        if (operation.getResultArity() == 3) FrameAccess.INSTANCE.writeLong(frame, slots[offset + 2], third);
        return null;
    }
}
