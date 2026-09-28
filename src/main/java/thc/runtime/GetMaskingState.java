// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node;
import static thc.runtime.RuntimeServiceStatus.fault;
import static thc.runtime.TupleResultsKt.requireVoidCarrier;
public final class GetMaskingState extends Expr {
    @Child private Expr state;
    public GetMaskingState(Expr state, CoreRepresentation proof) { this.state = state; setRepresentation(proof.copy(proof.getKind(), true, proof.getPresent(), proof.getPrimReps(), proof.getComponents(), proof.getVector(), proof.getAlternatives(), proof.getTagSlot(), proof.getAlternativeSlots())); }
    @Override public Object execute(VirtualFrame frame) { throw fault("getMaskingState# requires a tuple destination"); }
    @Override public Object executeTuple(VirtualFrame frame, int[] slots, int offset) {
        requireVoidCarrier(state.execute(frame));
        FrameAccess.writeLong(frame, slots[offset], SynchronousMasking.current(this).getTag()); return null;
    }
}
