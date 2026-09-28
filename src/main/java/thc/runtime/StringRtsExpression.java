// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.frame.VirtualFrame;
import static thc.runtime.RuntimeServiceStatus.fault;

final class StringRtsExpression extends Expr {
    private final StringRtsOp operation;
    @Children private Expr[] operands;

    StringRtsExpression(StringRtsOp operation, Expr[] operands, CoreRepresentation proof) {
        this.operation = operation;
        this.operands = operands;
        setRepresentation(new CoreRepresentation(proof.getKind(), true, proof.getPresent(),
            proof.getPrimReps(), proof.getComponents(), proof.getVector(), proof.getAlternatives(),
            proof.getTagSlot(), proof.getAlternativeSlots()));
    }

    @Override public Object execute(VirtualFrame frame) {
        throw fault("Original Posix call requires a tuple destination");
    }

    @Override public Object executeTuple(VirtualFrame frame, int[] slots, int offset) {
        var address = operation.getArguments().size() == 2 ? operands[0].executeRequiredAddress(frame) : null;
        TupleResults.requireVoidCarrier(operands[operands.length - 1].execute(frame));
        // Live THC programs retain their global CAF cells; there is no native RTS CAF reversion.
        FrameAccess.INSTANCE.writeLong(frame, slots[offset], address != null ? address.cStringLength()
            : operation == StringRtsOp.KEEP_CAFS ? 1L : 0L);
        return null;
    }
}
