// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.frame.VirtualFrame;

public final class MyThreadId extends Expr {
    @Child private Expr state;
    public MyThreadId(Expr state, CoreRepresentation proof) {
        this.state = state;
        setRepresentation(proof.withEvaluated(true));
    }
    @Override public Object execute(VirtualFrame frame) { throw RuntimeFault.fault("myThreadId# requires a tuple destination"); }
    @Override public Object executeTuple(VirtualFrame frame, int[] slots, int offset) {
        TupleResultsKt.requireVoidCarrier(state.execute(frame));
        FrameAccess.write(frame, slots[offset], GuestThreadOps.myThreadId(this));
        return null;
    }
}
