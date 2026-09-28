// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.frame.VirtualFrame;

public final class ThreadObservation extends Expr {
    private final boolean listing;
    @Child private Expr state;
    public ThreadObservation(boolean listing, Expr state, CoreRepresentation proof) {
        this.listing = listing; this.state = state;
        setRepresentation(proof.withEvaluated(true));
    }
    @Override public Object execute(VirtualFrame frame) { throw RuntimeFault.fault("Thread observation requires a tuple destination"); }
    @Override public Object executeTuple(VirtualFrame frame, int[] slots, int offset) {
        TupleResultsKt.requireVoidCarrier(state.execute(frame));
        GuestThreads threads = GuestThreads.current(this);
        if (listing) FrameAccess.write(frame, slots[offset], threads.snapshot());
        else FrameAccess.writeLong(frame, slots[offset], threads.isCurrentBound() ? 1L : 0L);
        return null;
    }
}
