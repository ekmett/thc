// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.frame.VirtualFrame;

public final class ThreadStatus extends Expr {
    @Child private Expr identity, state;
    public ThreadStatus(Expr identity, Expr state, CoreRepresentation proof) {
        this.identity = identity; this.state = state;
        setRepresentation(proof.withEvaluated(true));
    }
    @Override public Object execute(VirtualFrame frame) { throw RuntimeFault.fault("threadStatus# requires a tuple destination"); }
    @Override public Object executeTuple(VirtualFrame frame, int[] slots, int offset) {
        Object target = identity.execute(frame);
        TupleResultsKt.requireVoidCarrier(state.execute(frame));
        GuestThreadSnapshot snapshot = GuestThreadOps.threadStatus(this, target);
        FrameAccess.writeLong(frame, slots[offset], snapshot.status());
        FrameAccess.writeLong(frame, slots[offset + 1], snapshot.capability());
        FrameAccess.writeLong(frame, slots[offset + 2], snapshot.locked());
        return null;
    }
}
