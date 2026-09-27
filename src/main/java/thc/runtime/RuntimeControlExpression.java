// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.frame.VirtualFrame;
import static thc.runtime.RuntimeServiceStatus.fault;

final class RuntimeControlExpression extends Expr {
    @Child private Expr selector;
    @Child private Expr setting;
    @Child private Expr state;

    RuntimeControlExpression(Expr selector, Expr setting, Expr state) {
        this.selector = selector;
        this.setting = setting;
        this.state = state;
    }

    @Override public Object execute(VirtualFrame frame) {
        throw fault("Runtime control requires a tuple destination");
    }

    @Override public Object executeTuple(VirtualFrame frame, int[] slots, int offset) {
        int key = selector.executeRequiredInt(frame);
        long value;
        try {
            value = setting.executeLong(frame);
        } catch (com.oracle.truffle.api.nodes.UnexpectedResultException failure) {
            throw propagate(failure);
        }
        TupleResultsKt.requireVoidCarrier(state.execute(frame));
        FrameAccess.INSTANCE.writeLong(frame, slots[offset], RuntimeServices.control(this, key, value));
        return null;
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable> RuntimeException propagate(Throwable failure) throws E {
        throw (E) failure;
    }
}
