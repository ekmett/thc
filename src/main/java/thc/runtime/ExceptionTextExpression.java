// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.frame.VirtualFrame;
import static thc.runtime.RuntimeServiceStatus.fault;

final class ExceptionTextExpression extends Expr {
    @Child private ForeignExceptionAccess access = new ForeignExceptionAccess();
    @Child private Expr handle;
    @Child private Expr selector;
    @Child private Expr index;
    @Child private Expr state;
    private final int programSlot;

    ExceptionTextExpression(Expr handle, Expr selector, Expr index, Expr state, int programSlot) {
        this.handle = handle;
        this.selector = selector;
        this.index = index;
        this.state = state;
        this.programSlot = programSlot;
    }

    @Override public Object execute(VirtualFrame frame) {
        throw fault("Exception metadata requires a tuple destination");
    }

    @Override public Object executeTuple(VirtualFrame frame, int[] slots, int offset) {
        ManagedAddress address;
        long key, item;
        try {
            address = handle.executeAddress(frame);
            key = selector.executeRequiredInt(frame);
            item = index.executeLong(frame);
        } catch (com.oracle.truffle.api.nodes.UnexpectedResultException failure) {
            throw propagate(failure);
        }
        Object token = state.execute(frame);
        long value = programSlot < 0 ? access.text(address, key, item, token)
            : access.text(address, key, item, token, Program.instance(frame, programSlot).foreignExceptionBridge());
        FrameAccess.INSTANCE.writeLong(frame, slots[offset], value);
        AstForeignCompleted.poll(this);
        return null;
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable> RuntimeException propagate(Throwable failure) throws E {
        throw (E) failure;
    }
}
