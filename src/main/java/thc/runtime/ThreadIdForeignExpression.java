// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.frame.VirtualFrame;

final class ThreadIdForeignExpression extends Expr {
    private final ThreadIdForeignOp operation;
    @Children private Expr[] operands;
    ThreadIdForeignExpression(ThreadIdForeignOp operation, Expr[] operands, CoreRepresentation proof) {
        this.operation = operation; this.operands = operands; setRepresentation(proof.withEvaluated(true));
    }
    @Override public Object execute(VirtualFrame frame) { throw RuntimeFault.fault("Thread identity call requires a tuple destination"); }
    @Override public Object executeTuple(VirtualFrame frame, int[] slots, int offset) {
        var first = operands[0].execute(frame);
        var second = operation.getArguments() == 2 ? operands[1].execute(frame) : null;
        TupleResults.requireVoidCarrier(operands[operands.length - 1].execute(frame));
        long value = operation.execute(this, first, second);
        if (operation == ThreadIdForeignOp.ID) FrameAccess.writeLong(frame, slots[offset], value);
        else FrameAccess.writeInt(frame, slots[offset], (int) value);
        return null;
    }
}
