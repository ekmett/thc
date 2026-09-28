// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.frame.VirtualFrame;
import static thc.runtime.RuntimeServiceStatus.fault;

final class AtomicIntArrayExpression extends Expr {
    private final AtomicIntArrayOp operation;
    @Children private Expr[] arguments;
    AtomicIntArrayExpression(AtomicIntArrayOp operation, Expr[] arguments) { this.operation = operation; this.arguments = arguments; }
    @Override public Object execute(VirtualFrame frame) {
        if (operation.getTuple()) throw fault("Tuple primitive requires a destination");
        var owner = arguments[0].execute(frame);
        long index = arguments[1].executeRequiredLong(frame), value = arguments[2].executeRequiredLong(frame);
        var state = arguments[3].execute(frame); ManagedByteArray.requireState(state);
        operation.execute(owner, index, value, 0); return state;
    }
    @Override public Object executeTuple(VirtualFrame frame, int[] slots, int offset) {
        var owner = arguments[0].execute(frame);
        long index = arguments[1].executeRequiredLong(frame);
        if (operation.getWidth() < 8) {
            int operand = arguments[2].executeRequiredInt(frame), replacement = arguments[3].executeRequiredInt(frame);
            ManagedByteArray.requireState(arguments[arguments.length - 1].execute(frame));
            FrameAccess.INSTANCE.writeInt(frame, slots[offset], operation.executeInt(owner, index, operand, replacement));
        } else {
            long operand = operation.getOperands() > 0 ? arguments[2].executeRequiredLong(frame) : 0L;
            long replacement = operation.getOperands() == 2 ? arguments[3].executeRequiredLong(frame) : 0L;
            ManagedByteArray.requireState(arguments[arguments.length - 1].execute(frame));
            FrameAccess.INSTANCE.writeLong(frame, slots[offset], operation.execute(owner, index, operand, replacement));
        }
        return null;
    }
}
