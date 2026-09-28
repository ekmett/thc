// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.frame.VirtualFrame;
import static thc.runtime.RuntimeServiceStatus.fault;

final class MemorySearchExpression extends Expr {
    @Children private Expr[] operands;
    private final MemorySearchOp operation;

    MemorySearchExpression(MemorySearchOp operation, Expr[] operands, CoreRepresentation proof) {
        this.operands = operands;
        this.operation = operation;
        setRepresentation(new CoreRepresentation(proof.getKind(), true, proof.getPresent(),
            proof.getPrimReps(), proof.getComponents(), proof.getVector(), proof.getAlternatives(),
            proof.getTagSlot(), proof.getAlternativeSlots()));
    }

    @Override public Object execute(VirtualFrame frame) {
        throw fault("Memory search requires a tuple destination");
    }

    @Override public Object executeTuple(VirtualFrame frame, int[] slots, int offset) {
        var first = operands[0].executeRequiredAddress(frame);
        if (operation == MemorySearchOp.COMPARE) {
            var second = operands[1].executeRequiredAddress(frame);
            long count = operands[2].executeRequiredLong(frame);
            TupleResults.requireVoidCarrier(operands[3].execute(frame));
            FrameAccess.INSTANCE.writeInt(frame, slots[offset], (int) first.compareBytes(second, count));
        } else {
            long needle = operands[1].executeRequiredInt(frame);
            long count = operands[2].executeRequiredLong(frame);
            TupleResults.requireVoidCarrier(operands[3].execute(frame));
            FrameAccess.INSTANCE.writeObject(frame, slots[offset], first.findByte(needle, count));
        }
        return null;
    }
}
