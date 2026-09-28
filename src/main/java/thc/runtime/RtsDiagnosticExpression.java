// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.frame.VirtualFrame;
import static thc.runtime.RuntimeServiceStatus.fault;

final class RtsDiagnosticExpression extends Expr {
    private final RtsDiagnosticOp operation;
    @Children private Expr[] operands;

    RtsDiagnosticExpression(RtsDiagnosticOp operation, Expr[] operands, CoreRepresentation proof) {
        this.operation = operation;
        this.operands = operands;
        setRepresentation(new CoreRepresentation(proof.getKind(), true, proof.getPresent(),
            proof.getPrimReps(), proof.getComponents(), proof.getVector(), proof.getAlternatives(),
            proof.getTagSlot(), proof.getAlternativeSlots()));
    }

    @Override public Object execute(VirtualFrame frame) {
        throw fault("RTS diagnostic requires a tuple destination");
    }

    @Override public Object executeTuple(VirtualFrame frame, int[] slots, int offset) {
        var first = operands.length > 1 ? operands[0].execute(frame) : null;
        var second = operands.length > 2 ? operands[1].executeRequiredAddress(frame) : null;
        TupleResultsKt.requireVoidCarrier(operands[operands.length - 1].execute(frame));
        RtsDiagnostics.report(this, operation, first, second);
        return null;
    }
}
