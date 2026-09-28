// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.frame.VirtualFrame;
import static thc.runtime.RuntimeServiceStatus.fault;

final class GmpForeignExpression extends Expr {
    private final GmpForeignOp operation;
    @Children private Expr[] operands;

    GmpForeignExpression(GmpForeignOp operation, Expr[] operands, CoreRepresentation proof) {
        this.operation = operation;
        this.operands = operands;
        setRepresentation(new CoreRepresentation(proof.getKind(), true, proof.getPresent(),
            proof.getPrimReps(), proof.getComponents(), proof.getVector(), proof.getAlternatives(),
            proof.getTagSlot(), proof.getAlternativeSlots()));
    }

    @Override public Object execute(VirtualFrame frame) {
        throw fault("Original GMP call requires a State/result tuple destination");
    }

    @Override public Object executeTuple(VirtualFrame frame, int[] slots, int offset) {
        if (operation.getForm() == GmpForm.ENCODE_DOUBLE || operation.getForm() == GmpForm.GET_DOUBLE) {
            Object input = operation.getForm() == GmpForm.GET_DOUBLE ? operands[0].execute(frame) : null;
            int start = operation.getForm() == GmpForm.GET_DOUBLE ? 1 : 0;
            long a = operands[start].executeRequiredLong(frame);
            long b = operands[start + 1].executeRequiredLong(frame);
            TupleResults.requireVoidCarrier(operands[start + 2].execute(frame));
            FrameAccess.INSTANCE.writeDouble(frame, slots[offset], ManagedGmp.invokeDouble(this, operation, input, a, b));
            return null;
        }
        if (operation.getForm() == GmpForm.WORD_PAIR) {
            long left = operands[0].executeRequiredLong(frame);
            long right = operands[1].executeRequiredLong(frame);
            TupleResults.requireVoidCarrier(operands[2].execute(frame));
            FrameAccess.INSTANCE.writeLong(frame, slots[offset], ManagedGmp.invoke(this, operation,
                null, null, null, null, left, right, 0));
            return null;
        }
        Object first = operands[0].execute(frame);
        // Primitive counts/words stay long locals; object/primitive interleaving
        // follows the original FCallId argument order exactly. Direct enum
        // comparisons let partial evaluation retain only the selected frame path.
        long result;
        if (operation.getForm() == GmpForm.BINARY) {
            Object second = operands[1].execute(frame);
            long a = operands[2].executeRequiredLong(frame);
            Object third = operands[3].execute(frame);
            long b = operands[4].executeRequiredLong(frame);
            TupleResults.requireVoidCarrier(operands[5].execute(frame));
            result = ManagedGmp.invoke(this, operation, first, second, third, null, a, b, 0);
        } else if (operation.getForm() == GmpForm.WORD) {
            Object second = operands[1].execute(frame);
            long a = operands[2].executeRequiredLong(frame);
            long b = operands[3].executeRequiredLong(frame);
            TupleResults.requireVoidCarrier(operands[4].execute(frame));
            result = ManagedGmp.invoke(this, operation, first, second, null, null, a, b, 0);
        } else if (operation.getForm() == GmpForm.COMPARE) {
            Object second = operands[1].execute(frame);
            long a = operands[2].executeRequiredLong(frame);
            TupleResults.requireVoidCarrier(operands[3].execute(frame));
            result = ManagedGmp.invoke(this, operation, first, second, null, null, a, 0, 0);
        } else if (operation.getForm() == GmpForm.DIVIDE_WORD) {
            long a = operands[1].executeRequiredLong(frame);
            Object second = operands[2].execute(frame);
            long b = operands[3].executeRequiredLong(frame);
            long c = operands[4].executeRequiredLong(frame);
            TupleResults.requireVoidCarrier(operands[5].execute(frame));
            result = ManagedGmp.invoke(this, operation, first, second, null, null, a, b, c);
        } else if (operation.getForm() == GmpForm.MODULO_WORD) {
            long a = operands[1].executeRequiredLong(frame);
            long b = operands[2].executeRequiredLong(frame);
            TupleResults.requireVoidCarrier(operands[3].execute(frame));
            result = ManagedGmp.invoke(this, operation, first, null, null, null, a, b, 0);
        } else if (operation.getForm() == GmpForm.LOGICAL) {
            Object second = operands[1].execute(frame);
            Object third = operands[2].execute(frame);
            long count = operands[3].executeRequiredLong(frame);
            TupleResults.requireVoidCarrier(operands[4].execute(frame));
            result = ManagedGmp.invoke(this, operation, first, second, third, null, count, 0, 0);
        } else if (operation.getForm() == GmpForm.COUNT) {
            long count = operands[1].executeRequiredLong(frame);
            TupleResults.requireVoidCarrier(operands[2].execute(frame));
            result = ManagedGmp.invoke(this, operation, first, null, null, null, count, 0, 0);
        } else { // GmpForm.DIVIDE
            Object second = operands[1].execute(frame);
            long a = operands[2].executeRequiredLong(frame);
            Object third = operands[3].execute(frame);
            long b = operands[4].executeRequiredLong(frame);
            Object fourth = operands[5].execute(frame);
            long c = operands[6].executeRequiredLong(frame);
            TupleResults.requireVoidCarrier(operands[7].execute(frame));
            result = ManagedGmp.invoke(this, operation, first, second, third, fourth, a, b, c);
        }
        if (operation.getResult() != null) FrameAccess.INSTANCE.writeLong(frame, slots[offset], result);
        return null;
    }
}
