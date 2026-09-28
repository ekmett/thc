// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.frame.VirtualFrame;
import thc.Language;

public final class ClosureInspectExpression extends Expr {
    private final ClosureInspectOp operation;
    @Children private final Expr[] operands;
    public ClosureInspectExpression(ClosureInspectOp operation, Expr[] operands, CoreRepresentation proof) {
        this.operation = operation; this.operands = operands;
        setRepresentation(proof.withEvaluated(true));
    }
    @Override public Object execute(VirtualFrame frame) { return executeLong(frame); }
    @Override public long executeLong(VirtualFrame frame) {
        if (operation != ClosureInspectOp.SIZE) {
            CompilerDirectives.transferToInterpreter();
            throw RuntimeFault.fault(operation.getPrimitive() + " requires a tuple destination");
        }
        return ClosureInspection.size(operands[0].execute(frame));
    }
    @Override public Object executeTuple(VirtualFrame frame, int[] slots, int offset) {
        // Explicit comparisons preserve a constant operation without mutable ordinal tables.
        if (operation == ClosureInspectOp.UNPACK) {
            ClosureImage image = ClosureInspection.image(operands[0].execute(frame));
            FrameAccess.writeObject(frame, slots[offset], Language.currentState(this).closureInfo.address(image.getDescriptor()));
            FrameAccess.writeObject(frame, slots[offset + 1], image.getBytes());
            FrameAccess.writeObject(frame, slots[offset + 2], image.getPointers());
        } else if (operation == ClosureInspectOp.AP_STACK) {
            Object value = operands[0].execute(frame);
            operands[1].executeRequiredLong(frame);
            // Saved guest continuations are not native AP_STACK payloads.
            FrameAccess.writeLong(frame, slots[offset], 0);
            FrameAccess.writeObject(frame, slots[offset + 1], value);
        } else if (operation == ClosureInspectOp.CCS) {
            TupleResultsKt.requireVoidCarrier(operands[1].execute(frame));
            FrameAccess.writeObject(frame, slots[offset], ManagedAddress.nullAddress());
        } else if (operation == ClosureInspectOp.WHERE) {
            operands[1].executeRequiredAddress(frame);
            TupleResultsKt.requireVoidCarrier(operands[2].execute(frame));
            FrameAccess.writeLong(frame, slots[offset], 0); // No closure IPE: destination remains untouched.
        } else throw RuntimeFault.fault("closureSize# is scalar");
        return null;
    }
}
