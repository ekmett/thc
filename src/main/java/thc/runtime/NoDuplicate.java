// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node;
import static thc.runtime.RuntimeServiceStatus.fault;
import static thc.runtime.TupleResultsKt.requireVoidCarrier;
public final class NoDuplicate extends Expr {
    @Child private Expr state;
    public NoDuplicate(Expr state, CoreRepresentation proof) { this.state = state; setRepresentation(proof.copy(proof.getKind(), true, proof.getPresent(), proof.getPrimReps(), proof.getComponents(), proof.getVector(), proof.getAlternatives(), proof.getTagSlot(), proof.getAlternativeSlots())); }
    @Override public Object execute(VirtualFrame frame) { requireVoidCarrier(state.execute(frame)); return kotlin.Unit.INSTANCE; }
}
