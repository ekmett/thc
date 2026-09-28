// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.frame.VirtualFrame;
import thc.Language;
import static thc.runtime.RuntimeServiceStatus.fault;

final class RtsArgumentsExpression extends Expr {
    private final RtsArgumentsOp operation;
    @Children private Expr[] operands;

    RtsArgumentsExpression(RtsArgumentsOp operation, Expr[] operands, CoreRepresentation proof) {
        this.operation = operation;
        this.operands = operands;
        setRepresentation(new CoreRepresentation(proof.getKind(), true, proof.getPresent(),
            proof.getPrimReps(), proof.getComponents(), proof.getVector(), proof.getAlternatives(),
            proof.getTagSlot(), proof.getAlternativeSlots()));
    }

    @Override public Object execute(VirtualFrame frame) {
        throw fault("Program arguments require a tuple destination");
    }

    @Override public Object executeTuple(VirtualFrame frame, int[] slots, int offset) {
        if (operation == RtsArgumentsOp.GET) {
            var argc = operands[0].executeRequiredAddress(frame);
            var argv = operands[1].executeRequiredAddress(frame);
            TupleResultsKt.requireVoidCarrier(operands[2].execute(frame));
            Language.currentState(this).getArguments().get(argc, argv);
        } else {
            long argc = operands[0].executeRequiredInt(frame);
            var argv = operands[1].executeRequiredAddress(frame);
            TupleResultsKt.requireVoidCarrier(operands[2].execute(frame));
            Language.currentState(this).getArguments().set(argc, argv);
        }
        return null;
    }
}
