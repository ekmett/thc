// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.frame.VirtualFrame;

public final class ShutdownRuntime extends Expr {
    private final RtsShutdownOp operation;
    @Child private Expr code, fast, state;
    public ShutdownRuntime(RtsShutdownOp operation, Expr code, Expr fast, Expr state) {
        this.operation = operation; this.code = code; this.fast = fast; this.state = state;
    }
    @Override public Object execute(VirtualFrame frame) { throw RuntimeFault.fault("Shutdown requires its declared tuple convention"); }
    @Override public Object executeTuple(VirtualFrame frame, int[] slots, int offset) {
        long status = code.executeRequiredInt(frame);
        long mode = fast.executeRequiredInt(frame);
        return CoreRtsShutdown.shutdown(this, operation, status, mode, state.execute(frame));
    }
}
