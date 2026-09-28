// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node;
import static thc.runtime.RuntimeServiceStatus.fault;
import static thc.runtime.TupleResultsKt.requireVoidCarrier;
public final class RaiseArithmeticException extends Expr {
    @Child private Expr argument;
    @Child private RaiseException raise;
    @CompilerDirectives.CompilationFinal(dimensions = 1) private final int[] emptySlots = new int[0];
    public RaiseArithmeticException(Expr argument, RaiseException raise) {
        this.argument = argument; this.raise = raise;
        setRepresentation(new CoreRepresentation(CoreKind.UNKNOWN, true, false, null, null, null, null, null, null));
    }
    @Override public Object execute(VirtualFrame frame) { argument.executeTuple(frame, emptySlots, 0); return raise.execute(frame); }
    @Override public Object executeTuple(VirtualFrame frame, int[] slots, int offset) { return execute(frame); }
}
