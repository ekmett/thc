// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.frame.VirtualFrame;

public final class InspectionState extends Expr {
    @Child private Expr state;
    public InspectionState(Expr state) {
        this.state = state; var proof = state.getRepresentation();
        setRepresentation(proof.withEvaluated(true));
    }
    @Override public Object execute(VirtualFrame frame) {
        TupleResults.requireVoidCarrier(state.execute(frame));
        return thc.runtime.Unit.INSTANCE;
    }
}
