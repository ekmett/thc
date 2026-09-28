// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.frame.VirtualFrame;
import thc.Language;
import static thc.runtime.RuntimeServiceStatus.fault;

final class NativeAllocationExpression extends Expr {
    private final NativeAllocationOp operation;
    @Children private Expr[] operands;

    NativeAllocationExpression(NativeAllocationOp operation, Expr[] operands, CoreRepresentation proof) {
        this.operation = operation;
        this.operands = operands;
        setRepresentation(new CoreRepresentation(proof.getKind(), true, proof.getPresent(),
            proof.getPrimReps(), proof.getComponents(), proof.getVector(), proof.getAlternatives(),
            proof.getTagSlot(), proof.getAlternativeSlots()));
    }

    @Override public Object execute(VirtualFrame frame) {
        throw fault("Native allocation call requires a tuple destination");
    }

    @Override public Object executeTuple(VirtualFrame frame, int[] slots, int offset) {
        if (operation == NativeAllocationOp.MALLOC) {
            long size = operands[0].executeRequiredLong(frame);
            TupleResultsKt.requireVoidCarrier(operands[1].execute(frame));
            FrameAccess.INSTANCE.writeObject(frame, slots[offset],
                Language.currentState(this).getNativeAllocations().malloc(size));
        } else if (operation == NativeAllocationOp.REALLOC) {
            var address = operands[0].executeRequiredAddress(frame);
            long size = operands[1].executeRequiredLong(frame);
            TupleResultsKt.requireVoidCarrier(operands[2].execute(frame));
            FrameAccess.INSTANCE.writeObject(frame, slots[offset],
                Language.currentState(this).getNativeAllocations().realloc(address, size));
        } else {
            var address = operands[0].executeRequiredAddress(frame);
            TupleResultsKt.requireVoidCarrier(operands[1].execute(frame));
            Language.currentState(this).getNativeAllocations().free(address);
        }
        return null;
    }
}
