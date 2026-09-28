// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.frame.VirtualFrame;
import static thc.runtime.RuntimeServiceStatus.fault;

/** Original compiled CAPI wrapper; its State# is checked before the C call. */
final class CapiExpression extends Expr {
    private final CapiCall call;
    @Children private Expr[] operands;

    CapiExpression(CapiCall call, Expr[] operands, CoreRepresentation proof) {
        this.call = call;
        this.operands = operands;
        setRepresentation(new CoreRepresentation(proof.getKind(), true, proof.getPresent(),
            proof.getPrimReps(), proof.getComponents(), proof.getVector(), proof.getAlternatives(),
            proof.getTagSlot(), proof.getAlternativeSlots()));
    }

    @Override public Object execute(VirtualFrame frame) {
        throw fault("CAPI call requires a State/result tuple");
    }

    @Override public Object executeTuple(VirtualFrame frame, int[] slots, int offset) {
        long result;
        if (call.zeroArgument()) {
            TupleResultsKt.requireVoidCarrier(operands[0].execute(frame));
            result = CoreCapiForeign.zero(this, call);
        } else {
            long clock = call.timeClock() ? operands[0].executeRequiredInt(frame)
                : operands[0].executeRequiredLong(frame);
            var output = operands[1].executeRequiredAddress(frame);
            TupleResultsKt.requireVoidCarrier(operands[2].execute(frame));
            result = CoreCapiForeign.wordAddress(this, call, clock, output);
        }
        if (call.zeroArgument() && !call.timeClock()) FrameAccess.INSTANCE.writeLong(frame, slots[offset], result);
        else FrameAccess.INSTANCE.writeInt(frame, slots[offset], (int) result);
        return null;
    }
}
