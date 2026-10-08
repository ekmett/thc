// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.frame.VirtualFrame;
import thc.runtime.Unit;
import thc.Language;
import static thc.runtime.RuntimeServiceStatus.fault;

final class CompactExpression extends Expr {
    private final CompactOp operation;
    @Children private Expr[] operands;
    @Child private CompactCopyNode copier;
    CompactExpression(CompactOp operation, Expr[] operands, Metrics metrics, Expr[] failures) {
        this.operation = operation; this.operands = operands;
        copier = operation.getAdds() ? new CompactCopyNode(metrics, failures, true) : null;
    }
    @Override public Object execute(VirtualFrame frame) {
        if (operation != CompactOp.RESIZE) throw fault("Compact primitive requires tuple destination");
        var region = Language.currentState(this).compactRegions.require(operands[0].execute(frame));
        long size = operands[1].executeRequiredLong(frame);
        TupleResults.requireVoidCarrier(operands[2].execute(frame));
        region.resize(size);
        return Unit.INSTANCE;
    }
    @Override public Object executeTuple(VirtualFrame frame, int[] slots, int offset) {
        var registry = Language.currentState(this).compactRegions;
        switch (operation) {
            case NEW -> {
                long size = operands[0].executeRequiredLong(frame);
                TupleResults.requireVoidCarrier(operands[1].execute(frame));
                FrameAccess.write(frame, slots[offset], new ManagedCompact(registry, size));
            }
            case SIZE -> {
                var region = registry.require(operands[0].execute(frame));
                TupleResults.requireVoidCarrier(operands[1].execute(frame));
                FrameAccess.writeLong(frame, slots[offset], region.size());
            }
            case CONTAINS_ANY -> {
                var value = operands[0].execute(frame);
                TupleResults.requireVoidCarrier(operands[1].execute(frame));
                FrameAccess.writeLong(frame, slots[offset], registry.containsAny(value) ? 1L : 0L);
            }
            case CONTAINS, ADD, ADD_SHARING -> {
                var region = registry.require(operands[0].execute(frame));
                var value = operands[1].execute(frame);
                TupleResults.requireVoidCarrier(operands[2].execute(frame));
                if (operation == CompactOp.CONTAINS) FrameAccess.writeLong(frame, slots[offset], registry.contains(region, value) ? 1L : 0L);
                else try { FrameAccess.write(frame, slots[offset], copier.execute(frame, region, value, operation == CompactOp.ADD_SHARING)); }
                catch (AstCapture cut) {
                    int destination = slots[offset];
                    throw cut.append((resumed, copied) -> { FrameAccess.write(resumed, destination, copied); return null; });
                }
            }
            default -> throw fault("Not a tuple compact operation");
        }
        return null;
    }
}
