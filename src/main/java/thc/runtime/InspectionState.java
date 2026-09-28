// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.frame.VirtualFrame;

public final class InspectionState extends Expr {
    @Child private Expr state;
    public InspectionState(Expr state) {
        this.state = state; var proof = state.getRepresentation();
        setRepresentation(proof.copy(proof.getKind(), true, proof.getPresent(), proof.getPrimReps(), proof.getComponents(),
            proof.getVector(), proof.getAlternatives(), proof.getTagSlot(), proof.getAlternativeSlots()));
    }
    @Override public Object execute(VirtualFrame frame) {
        TupleResultsKt.requireVoidCarrier(state.execute(frame));
        return kotlin.Unit.INSTANCE;
    }
}
