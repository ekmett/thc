// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;
import com.oracle.truffle.api.frame.VirtualFrame;
import static thc.runtime.RuntimeServiceStatus.fault;
public final class GetCurrentCCS extends Expr {
    @Child private Expr dummy, state;
    public GetCurrentCCS(Expr dummy, Expr state, CoreRepresentation proof) {
        this.dummy = dummy; this.state = state;
        setRepresentation(proof.copy(proof.getKind(), true, proof.getPresent(), proof.getPrimReps(), proof.getComponents(), proof.getVector(), proof.getAlternatives(), proof.getTagSlot(), proof.getAlternativeSlots()));
    }
    @Override public Object execute(VirtualFrame frame) { throw fault("getCurrentCCS# requires a tuple destination"); }
    @Override public Object executeTuple(VirtualFrame frame, int[] slots, int offset) {
        // The provenance child is deliberately never entered.
        TupleResults.requireVoidCarrier(state.execute(frame)); FrameAccess.write(frame, slots[offset], ManagedAddress.nullAddress()); return null;
    }
}
