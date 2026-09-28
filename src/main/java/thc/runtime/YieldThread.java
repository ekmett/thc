// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node;
import static thc.runtime.RuntimeServiceStatus.fault;
import static thc.runtime.TupleResultsKt.requireVoidCarrier;
public final class YieldThread extends Expr {
    @Child private Expr state;
    private final boolean async;
    private static final AstResumeStep RESUME = (frame, input) -> {
        if (input != thc.runtime.Unit.INSTANCE) throw fault("Invalid yield# continuation");
        return thc.runtime.Unit.INSTANCE;
    };
    public YieldThread(Expr state, boolean async, CoreRepresentation proof) { this.state = state; this.async = async; setRepresentation(proof.copy(proof.getKind(), true, proof.getPresent(), proof.getPrimReps(), proof.getComponents(), proof.getVector(), proof.getAlternatives(), proof.getTagSlot(), proof.getAlternativeSlots())); }
    @Override public Object execute(VirtualFrame frame) {
        requireVoidCarrier(state.execute(frame)); CoreYield.giveWay();
        if (async) {
            AsyncRequest request = GuestThreads.pollCurrent(this, false);
            if (request != null) throw new AstCapture(request, SynchronousMasking.current(this)).append(RESUME);
        }
        return thc.runtime.Unit.INSTANCE;
    }
}
