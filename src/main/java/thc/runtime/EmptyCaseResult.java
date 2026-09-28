// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;
import com.oracle.truffle.api.frame.VirtualFrame;
import static thc.runtime.RuntimeServiceStatus.fault;
public final class EmptyCaseResult extends Expr {
    public EmptyCaseResult(CoreRepresentation proof) { setRepresentation(proof); }
    @Override public Object execute(VirtualFrame frame) { throw fault("Non-exhaustive Core case"); }
    @Override public Object executeTuple(VirtualFrame frame, int[] slots, int offset) { return execute(frame); }
}
