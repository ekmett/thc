// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.frame.VirtualFrame;
import thc.Language;
import static thc.runtime.RuntimeServiceStatus.fault;

final class EnvironmentExpression extends Expr {
    private final EnvironmentOp operation;
    @Children private Expr[] operands;

    EnvironmentExpression(EnvironmentOp operation, Expr[] operands, CoreRepresentation proof) {
        this.operation = operation;
        this.operands = operands;
        setRepresentation(new CoreRepresentation(proof.getKind(), true, proof.getPresent(),
            proof.getPrimReps(), proof.getComponents(), proof.getVector(), proof.getAlternatives(),
            proof.getTagSlot(), proof.getAlternativeSlots()));
    }

    @Override public Object execute(VirtualFrame frame) {
        throw fault("Environment call requires a tuple destination");
    }

    @Override public Object executeTuple(VirtualFrame frame, int[] slots, int offset) {
        var address = operation == EnvironmentOp.ENUMERATE ? null : operands[0].executeRequiredAddress(frame);
        TupleResultsKt.requireVoidCarrier(operands[operands.length - 1].execute(frame));
        var environment = Language.currentState(this).getEnvironment();
        switch (operation) {
            case GET -> FrameAccess.INSTANCE.writeObject(frame, slots[offset], environment.get(address));
            case PUT -> FrameAccess.INSTANCE.writeInt(frame, slots[offset], (int) environment.put(address));
            case UNSET -> FrameAccess.INSTANCE.writeInt(frame, slots[offset], (int) environment.unset(address));
            case ENUMERATE -> FrameAccess.INSTANCE.writeObject(frame, slots[offset], environment.environ());
        }
        return null;
    }
}
