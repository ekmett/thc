// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.frame.VirtualFrame;

final class NarrowScalarExpression extends Expr {
    private final NarrowScalarOp operation;
    @Children private Expr[] arguments;

    NarrowScalarExpression(String name, NarrowScalarOp operation, Expr[] arguments) {
        this.operation = operation;
        this.arguments = arguments;
        if (arguments.length != (operation.getUnary() ? 1 : 2)) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw new RuntimeFault("Primitive arity mismatch: " + name);
        }
        for (int index = 0; index < arguments.length; index++)
            operation.validateOperand(arguments[index].getRepresentation(), index);
        setRepresentation(operation.getResult());
    }

    @Override public Object execute(VirtualFrame frame) {
        if (operation.getResultLong()) return executeLong(frame);
        return executeInt(frame);
    }

    @Override public int executeInt(VirtualFrame frame) {
        if (operation.getResultLong()) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw new RuntimeFault("Expected primitive Int result");
        }
        int left = operation.getSourceLong() ? (int) arguments[0].executeRequiredLong(frame)
                : arguments[0].executeRequiredInt(frame);
        int right = operation.getUnary() ? 0 : operation.getShift() ? (int) arguments[1].executeRequiredLong(frame)
                : arguments[1].executeRequiredInt(frame);
        return operation.intResult(left, right);
    }

    @Override public long executeLong(VirtualFrame frame) {
        if (!operation.getResultLong()) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw new RuntimeFault("Expected primitive Long result");
        }
        int left = arguments[0].executeRequiredInt(frame);
        int right = operation.getUnary() ? 0 : arguments[1].executeRequiredInt(frame);
        return operation.longResult(left, right);
    }
}
