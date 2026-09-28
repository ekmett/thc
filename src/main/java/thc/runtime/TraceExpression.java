// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.frame.VirtualFrame;

public final class TraceExpression extends Expr {
    private final TraceOp operation;
    @Child private Expr address, length, state;
    public TraceExpression(TraceOp operation, Expr address, Expr length, Expr state, CoreRepresentation proof) {
        this.operation = operation; this.address = address; this.length = length; this.state = state;
        setRepresentation(proof.copy(proof.getKind(), true, proof.getPresent(), proof.getPrimReps(),
            proof.getComponents(), proof.getVector(), proof.getAlternatives(), proof.getTagSlot(), proof.getAlternativeSlots()));
    }
    @Override public Object execute(VirtualFrame frame) {
        try {
        ManagedAddress location = address.executeAddress(frame);
        long count = length == null ? 0L : length.executeLong(frame);
        TupleResultsKt.requireVoidCarrier(state.execute(frame));
        RtsDiagnostics.INSTANCE.trace(this, operation, location, count);
        return kotlin.Unit.INSTANCE;
        } catch (com.oracle.truffle.api.nodes.UnexpectedResultException failure) { throw propagate(failure); }
    }
    @SuppressWarnings("unchecked")
    private static <E extends Throwable> RuntimeException propagate(Throwable failure) throws E { throw (E) failure; }
}
