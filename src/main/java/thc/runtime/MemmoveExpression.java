// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.frame.VirtualFrame;
import static thc.runtime.RuntimeServiceStatus.fault;

final class MemmoveExpression extends Expr {
    @Children private Expr[] operands;

    MemmoveExpression(Expr[] operands, CoreRepresentation proof) {
        this.operands = operands;
        setRepresentation(new CoreRepresentation(proof.getKind(), true, proof.getPresent(),
            proof.getPrimReps(), proof.getComponents(), proof.getVector(), proof.getAlternatives(),
            proof.getTagSlot(), proof.getAlternativeSlots()));
    }

    @Override public Object execute(VirtualFrame frame) {
        throw fault("memmove requires a tuple destination");
    }

    @Override public Object executeTuple(VirtualFrame frame, int[] slots, int offset) {
        var destination = operands[0].executeRequiredAddress(frame);
        var source = operands[1].executeRequiredAddress(frame);
        long count = operands[2].executeRequiredLong(frame);
        TupleResultsKt.requireVoidCarrier(operands[3].execute(frame));
        FrameAccess.INSTANCE.writeObject(frame, slots[offset], source.moveTo(destination, count));
        return null;
    }
}
