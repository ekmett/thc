// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.frame.VirtualFrame;

public final class ForkThread extends Expr {
    @Child private Expr action, state, capability;
    public ForkThread(Expr action, Expr state, CoreRepresentation proof) { this(action, state, proof, null); }
    public ForkThread(Expr action, Expr state, CoreRepresentation proof, Expr capability) {
        this.action = action; this.state = state; this.capability = capability;
        setRepresentation(proof.withEvaluated(true));
    }
    @Override public Object execute(VirtualFrame frame) { throw RuntimeFault.fault("fork# requires a tuple destination"); }
    @Override public Object executeTuple(VirtualFrame frame, int[] slots, int offset) {
        Long requested = capability == null ? null : capability.executeRequiredLong(frame);
        Object child = action.execute(frame); // Do not force the lifted action on its parent.
        TupleResults.requireVoidCarrier(state.execute(frame));
        FrameAccess.write(frame, slots[offset], GuestThreadOps.fork(this, child, AstControl.enabled(this), requested));
        return null;
    }
}
