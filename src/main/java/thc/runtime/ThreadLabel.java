// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.frame.VirtualFrame;

public final class ThreadLabel extends Expr {
    @Child private Expr identity, state;
    public ThreadLabel(Expr identity, Expr state, CoreRepresentation proof) {
        this.identity = identity; this.state = state;
        setRepresentation(proof.copy(proof.getKind(), true, proof.getPresent(), proof.getPrimReps(), proof.getComponents(),
            proof.getVector(), proof.getAlternatives(), proof.getTagSlot(), proof.getAlternativeSlots()));
    }
    @Override public Object execute(VirtualFrame frame) { throw RuntimeFault.fault("threadLabel# requires a tuple destination"); }
    @Override public Object executeTuple(VirtualFrame frame, int[] slots, int offset) {
        Object target = identity.execute(frame);
        TupleResultsKt.requireVoidCarrier(state.execute(frame));
        Object label = GuestThreadOps.threadLabel(this, target);
        FrameAccess.writeLong(frame, slots[offset], label == null ? 0L : 1L);
        FrameAccess.write(frame, slots[offset + 1], label);
        return null;
    }
}
