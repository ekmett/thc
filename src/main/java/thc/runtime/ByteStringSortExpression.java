// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.frame.VirtualFrame;

final class ByteStringSortExpression extends Expr {
    @Children private Expr[] operands;

    ByteStringSortExpression(Expr[] operands, CoreRepresentation proof) {
        this.operands = operands;
        setRepresentation(new CoreRepresentation(proof.getKind(), true, proof.getPresent(),
                proof.getPrimReps(), proof.getComponents(), proof.getVector(), proof.getAlternatives(),
                proof.getTagSlot(), proof.getAlternativeSlots()));
    }

    @Override public Object execute(VirtualFrame frame) {
        CompilerDirectives.transferToInterpreterAndInvalidate();
        throw new RuntimeFault("ByteString sort requires a tuple destination");
    }

    @Override public Object executeTuple(VirtualFrame frame, int[] slots, int offset) {
        ManagedAddress address = operands[0].executeRequiredAddress(frame);
        long count = operands[1].executeRequiredLong(frame);
        TupleResultsKt.requireVoidCarrier(operands[2].execute(frame));
        ByteStringSort.sort(address, count);
        return null;
    }
}
