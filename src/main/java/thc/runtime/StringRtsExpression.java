// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.frame.VirtualFrame;
import static thc.runtime.RuntimeServiceStatus.fault;

final class StringRtsExpression extends Expr {
    private final StringRtsOp operation;
    private final long constantValue;
    @Children private Expr[] operands;

    StringRtsExpression(StringRtsOp operation, Expr[] operands, CoreRepresentation proof, long constantValue) {
        this.operation = operation;
        this.constantValue = constantValue;
        this.operands = operands;
        setRepresentation(new CoreRepresentation(proof.getKind(), true, proof.getPresent(),
            proof.getPrimReps(), proof.getComponents(), proof.getVector(), proof.getAlternatives(),
            proof.getTagSlot(), proof.getAlternativeSlots()));
    }

    @Override public Object execute(VirtualFrame frame) {
        throw fault("Original Posix call requires a tuple destination");
    }

    @Override public Object executeTuple(VirtualFrame frame, int[] slots, int offset) {
        // Enum.equals is final and retains the original null failure before operands.
        var address = operation.equals(StringRtsOp.STRLEN) || operation == StringRtsOp.STRLEN_CSIZE
            ? operands[0].executeRequiredAddress(frame) : null;
        TupleResults.requireVoidCarrier(operands[operands.length - 1].execute(frame));
        FrameAccess.INSTANCE.writeLong(frame, slots[offset], address != null ? address.cStringLength()
            : constantValue);
        return null;
    }
}
