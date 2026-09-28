// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.frame.VirtualFrame;
import static thc.runtime.RuntimeServiceStatus.fault;

final class TextForeignExpression extends Expr {
    private final TextForeignOp operation;
    @Children private Expr[] operands;

    TextForeignExpression(TextForeignOp operation, Expr[] operands, CoreRepresentation proof) {
        this.operation = operation;
        this.operands = operands;
        setRepresentation(new CoreRepresentation(proof.getKind(), true, proof.getPresent(),
            proof.getPrimReps(), proof.getComponents(), proof.getVector(), proof.getAlternatives(),
            proof.getTagSlot(), proof.getAlternativeSlots()));
    }

    @Override public Object execute(VirtualFrame frame) {
        throw fault("Text foreign call requires a tuple destination");
    }

    @Override public Object executeTuple(VirtualFrame frame, int[] slots, int offset) {
        if (operation == TextForeignOp.REVERSE) {
            var destination = operands[0].execute(frame);
            var source = operands[1].execute(frame);
            long start = operands[2].executeRequiredLong(frame);
            long length = operands[3].executeRequiredLong(frame);
            TupleResults.requireVoidCarrier(operands[4].execute(frame));
            ManagedText.reverse(destination, source, start, length);
            return null;
        }
        var bytes = operands[0].execute(frame);
        long start = operands[1].executeRequiredLong(frame);
        long length = operands[2].executeRequiredLong(frame);
        long count = operation == TextForeignOp.MEMCHR ? operands[3].executeRequiredInt(frame)
            : operands[3].executeRequiredLong(frame);
        TupleResults.requireVoidCarrier(operands[4].execute(frame));
        FrameAccess.INSTANCE.writeLong(frame, slots[offset], ManagedText.invoke(operation, bytes, start, length, count));
        return null;
    }
}
