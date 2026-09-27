// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.frame.VirtualFrame;

final class DoubleWordDivisionExpression extends Expr {
    @Child private Expr high;
    @Child private Expr low;
    @Child private Expr divisor;

    DoubleWordDivisionExpression(CoreRepresentation proof, Expr high, Expr low, Expr divisor) {
        this.high = high;
        this.low = low;
        this.divisor = divisor;
        setRepresentation(new CoreRepresentation(proof.getKind(), true, proof.getPresent(),
                proof.getPrimReps(), proof.getComponents(), proof.getVector(), proof.getAlternatives(),
                proof.getTagSlot(), proof.getAlternativeSlots()));
    }

    @Override public Object execute(VirtualFrame frame) {
        CompilerDirectives.transferToInterpreterAndInvalidate();
        throw new RuntimeFault("Tuple primitive requires a destination");
    }

    @Override public Object executeTuple(VirtualFrame frame, int[] slots, int offset) {
        long h = high.executeRequiredLong(frame);
        long l = low.executeRequiredLong(frame);
        long d = divisor.executeRequiredLong(frame);
        long q = Scalar64Primitives.unsignedDoubleWordQuotient(h, l, d);
        FrameAccess.INSTANCE.writeLong(frame, slots[offset], q);
        FrameAccess.INSTANCE.writeLong(frame, slots[offset + 1], l - q * d);
        return null;
    }
}
