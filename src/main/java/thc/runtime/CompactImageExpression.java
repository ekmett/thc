// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.frame.VirtualFrame;
import thc.Language;
import static thc.runtime.RuntimeServiceStatus.fault;

final class CompactImageExpression extends Expr {
    private final CompactImageOp op;
    @Children private Expr[] operands;

    CompactImageExpression(CompactImageOp op, Expr[] operands) {
        this.op = op;
        this.operands = operands;
    }

    @Override public Object execute(VirtualFrame frame) {
        throw fault("Compact image operation needs tuple destination");
    }

    @Override public Object executeTuple(VirtualFrame frame, int[] slots, int offset) {
        var state = Language.currentState(this);
        switch (op) {
            case FIRST, NEXT -> {
                var region = state.compactRegions.require(operands[0].execute(frame));
                var previous = op == CompactImageOp.NEXT ? operands[1].executeRequiredAddress(frame) : null;
                TupleResultsKt.requireVoidCarrier(operands[operands.length - 1].execute(frame));
                var address = previous == null ? state.compactImages.first(region) : state.compactImages.next(region, previous);
                FrameAccess.INSTANCE.write(frame, slots[offset], address);
                FrameAccess.INSTANCE.writeLong(frame, slots[offset + 1], address == ManagedAddress.Companion.nullAddress() ? 0 : address.availableBytes());
            }
            case ALLOCATE -> {
                var size = operands[0].executeRequiredLong(frame);
                var previous = operands[1].executeRequiredAddress(frame);
                TupleResultsKt.requireVoidCarrier(operands[2].execute(frame));
                FrameAccess.INSTANCE.write(frame, slots[offset], state.compactImages.allocate(size, previous));
            }
            case FIXUP -> {
                var first = operands[0].executeRequiredAddress(frame);
                var oldRoot = operands[1].executeRequiredAddress(frame);
                TupleResultsKt.requireVoidCarrier(operands[2].execute(frame));
                var fixed = state.compactImages.fixup(first, oldRoot);
                FrameAccess.INSTANCE.write(frame, slots[offset], fixed.getRegion());
                FrameAccess.INSTANCE.write(frame, slots[offset + 1], fixed.getRoot());
            }
            case TO_ADDRESS -> {
                var value = operands[0].execute(frame);
                TupleResultsKt.requireVoidCarrier(operands[1].execute(frame));
                FrameAccess.INSTANCE.write(frame, slots[offset], state.heapAddresses.address(value));
            }
            case FROM_ADDRESS -> FrameAccess.INSTANCE.write(frame, slots[offset],
                state.heapAddresses.dereference(operands[0].executeRequiredAddress(frame)));
        }
        return null;
    }
}
