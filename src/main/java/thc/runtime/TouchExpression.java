// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.frame.VirtualFrame;

public final class TouchExpression extends Expr {
    @Child private Expr kept, state;
    public TouchExpression(Expr kept, Expr state, CoreRepresentation proof) {
        this.kept = kept; this.state = state;
        setRepresentation(proof.copy(proof.getKind(), true, proof.getPresent(), proof.getPrimReps(),
            proof.getComponents(), proof.getVector(), proof.getAlternatives(), proof.getTagSlot(), proof.getAlternativeSlots()));
    }
    @Override public Object execute(VirtualFrame frame) {
        // Merely obtain the lazy carrier: touching bottom must not enter it.
        Object value = kept.execute(frame);
        return Touch.preserve(value, state.execute(frame));
    }
}
