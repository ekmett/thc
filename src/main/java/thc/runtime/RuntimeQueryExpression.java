// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.frame.VirtualFrame;
import static thc.runtime.RuntimeServiceStatus.fault;

final class RuntimeQueryExpression extends Expr {
    private final int backend;
    @Child private Expr selector;
    @Child private Expr index;
    @Child private Expr detail;
    @Child private Expr state;

    RuntimeQueryExpression(int backend, Expr selector, Expr index, Expr detail, Expr state) {
        this.backend = backend;
        this.selector = selector;
        this.index = index;
        this.detail = detail;
        this.state = state;
    }

    @Override public Object execute(VirtualFrame frame) {
        throw fault("Runtime query requires a tuple destination");
    }

    @Override public Object executeTuple(VirtualFrame frame, int[] slots, int offset) {
        int key = selector.executeRequiredInt(frame);
        long item, field;
        try {
            item = index.executeLong(frame);
            field = detail.executeLong(frame);
        } catch (com.oracle.truffle.api.nodes.UnexpectedResultException failure) {
            throw propagate(failure);
        }
        TupleResults.requireVoidCarrier(state.execute(frame));
        FrameAccess.INSTANCE.writeLong(frame, slots[offset], RuntimeServices.query(this, backend, key, item, field));
        return null;
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable> RuntimeException propagate(Throwable failure) throws E {
        throw (E) failure;
    }
}
