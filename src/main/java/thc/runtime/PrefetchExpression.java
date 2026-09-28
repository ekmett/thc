// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.frame.VirtualFrame;
import java.util.LinkedHashMap;
import java.util.Map;

/** GHC's four locality levels are optional performance hints on this target. */
public final class PrefetchExpression extends Expr {
    public static final Map<String, Integer> ARITIES;
    static {
        var arities = new LinkedHashMap<String, Integer>();
        for (int locality = 0; locality < 4; locality++) {
            for (String kind : new String[]{"ByteArray", "MutableByteArray", "Addr"})
                arities.put("prefetch" + kind + locality + "#", 3);
            arities.put("prefetchValue" + locality + "#", 2);
        }
        ARITIES = java.util.Collections.unmodifiableMap(arities);
    }
    @Child private Expr value, offset, state;
    public PrefetchExpression(Expr value, Expr offset, Expr state, CoreRepresentation proof) {
        this.value = value; this.offset = offset; this.state = state;
        setRepresentation(proof.copy(proof.getKind(), true, proof.getPresent(), proof.getPrimReps(),
            proof.getComponents(), proof.getVector(), proof.getAlternatives(), proof.getTagSlot(), proof.getAlternativeSlots()));
    }
    @Override public Object execute(VirtualFrame frame) {
        // Retain operand order without forcing a lifted hint or dereferencing ignored memory.
        value.execute(frame);
        try { if (offset != null) offset.executeLong(frame); }
        catch (com.oracle.truffle.api.nodes.UnexpectedResultException failure) { throw propagate(failure); }
        TupleResultsKt.requireVoidCarrier(state.execute(frame));
        return thc.runtime.Unit.INSTANCE;
    }
    @SuppressWarnings("unchecked")
    private static <E extends Throwable> RuntimeException propagate(Throwable failure) throws E { throw (E) failure; }
}
