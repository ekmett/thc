// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.frame.VirtualFrame;

public final class LabelThread extends Expr {
    @Child private Expr identity, bytes, state;
    public LabelThread(Expr identity, Expr bytes, Expr state, CoreRepresentation proof) {
        this.identity = identity; this.bytes = bytes; this.state = state;
        setRepresentation(proof.copy(proof.getKind(), true, proof.getPresent(), proof.getPrimReps(), proof.getComponents(),
            proof.getVector(), proof.getAlternatives(), proof.getTagSlot(), proof.getAlternativeSlots()));
    }
    @Override public Object execute(VirtualFrame frame) {
        Object target = identity.execute(frame);
        Object label = bytes.execute(frame);
        TupleResultsKt.requireVoidCarrier(state.execute(frame));
        GuestThreadOps.labelThread(this, target, label);
        return kotlin.Unit.INSTANCE;
    }
}
