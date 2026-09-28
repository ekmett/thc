// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.frame.VirtualFrame;
import static thc.runtime.RuntimeServiceStatus.fault;

final class RuntimeTraceExpression extends Expr {
    @Child private Expr operation;
    @Child private Expr token;
    @Child private Expr address;
    @Child private Expr length;
    @Child private Expr state;

    RuntimeTraceExpression(Expr operation, Expr token, Expr address, Expr length, Expr state) {
        this.operation = operation;
        this.token = token;
        this.address = address;
        this.length = length;
        this.state = state;
    }

    @Override public Object execute(VirtualFrame frame) {
        throw fault("Runtime trace requires a tuple destination");
    }

    @Override public Object executeTuple(VirtualFrame frame, int[] slots, int offset) {
        int op = operation.executeRequiredInt(frame);
        long id, count;
        ManagedAddress bytes;
        try {
            id = token.executeLong(frame);
            bytes = address.executeAddress(frame);
            count = length.executeLong(frame);
        } catch (com.oracle.truffle.api.nodes.UnexpectedResultException failure) {
            throw propagate(failure);
        }
        TupleResultsKt.requireVoidCarrier(state.execute(frame));
        FrameAccess.INSTANCE.writeLong(frame, slots[offset], RuntimeServices.trace(this, op, id, bytes, count));
        return null;
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable> RuntimeException propagate(Throwable failure) throws E {
        throw (E) failure;
    }
}
