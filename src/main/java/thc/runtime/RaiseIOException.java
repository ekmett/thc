// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node;
import static thc.runtime.RuntimeServiceStatus.fault;
import static thc.runtime.TupleResults.requireVoidCarrier;
public final class RaiseIOException extends Expr {
    @Child private Expr payload, state;
    private final boolean someException;
    public RaiseIOException(Expr payload, Expr state, CoreRepresentation proof) { this(payload, state, proof, false); }
    public RaiseIOException(Expr payload, Expr state, CoreRepresentation proof, boolean someException) {
        this.payload = payload; this.state = state; this.someException = someException; setRepresentation(proof.copy(proof.getKind(), true, proof.getPresent(), proof.getPrimReps(), proof.getComponents(), proof.getVector(), proof.getAlternatives(), proof.getTagSlot(), proof.getAlternativeSlots()));
    }
    @Override public Object execute(VirtualFrame frame) { throw fault("raiseIO# requires a tuple destination"); }
    @Override public Object executeTuple(VirtualFrame frame, int[] slots, int offset) {
        Object value = payload.execute(frame); requireVoidCarrier(state.execute(frame));
        throw new GuestException(value, this, someException);
    }
}
