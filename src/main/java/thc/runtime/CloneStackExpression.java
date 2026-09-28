// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.frame.VirtualFrame;

/** Erase the logical State field, retaining a detached diagnostic snapshot. */
public final class CloneStackExpression extends Expr {
    @Child private Expr state;
    public CloneStackExpression(Expr state, CoreRepresentation proof) {
        this.state = state;
        setRepresentation(proof.copy(proof.getKind(), true, proof.getPresent(), proof.getPrimReps(), proof.getComponents(),
            proof.getVector(), proof.getAlternatives(), proof.getTagSlot(), proof.getAlternativeSlots()));
    }
    @Override public Object execute(VirtualFrame frame) { throw RuntimeFault.fault("Stack clone requires a State/snapshot tuple destination"); }
    @Override public Object executeTuple(VirtualFrame frame, int[] slots, int offset) {
        TupleResultsKt.requireVoidCarrier(state.execute(frame));
        FrameAccess.write(frame, slots[offset], ManagedStackSnapshot.capture(this));
        return null;
    }
}
