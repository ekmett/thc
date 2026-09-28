// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.frame.VirtualFrame;

final class ByteStringDecimalExpression extends Expr {
    private final ByteStringDecimalOp operation;
    @Children private Expr[] operands;

    ByteStringDecimalExpression(ByteStringDecimalOp operation, Expr[] operands, CoreRepresentation proof) {
        this.operation = operation;
        this.operands = operands;
        setRepresentation(new CoreRepresentation(proof.getKind(), true, proof.getPresent(),
                proof.getPrimReps(), proof.getComponents(), proof.getVector(), proof.getAlternatives(),
                proof.getTagSlot(), proof.getAlternativeSlots()));
    }

    @Override public Object execute(VirtualFrame frame) {
        CompilerDirectives.transferToInterpreterAndInvalidate();
        throw new RuntimeFault("ByteString decimal call requires a tuple destination");
    }

    @Override public Object executeTuple(VirtualFrame frame, int[] slots, int offset) {
        long value = operands[0].executeRequiredLong(frame);
        ManagedAddress address = operands[1].executeRequiredAddress(frame);
        TupleResults.requireVoidCarrier(operands[2].execute(frame));
        if (operation == ByteStringDecimalOp.SIGNED)
            FrameAccess.INSTANCE.writeObject(frame, slots[offset], ByteStringDecimal.signed(value, address));
        else ByteStringDecimal.padded18(value, address);
        return null;
    }
}
