// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.frame.VirtualFrame;
import static thc.runtime.RuntimeServiceStatus.fault;

final class MemcpyExpression extends Expr {
    @Children private Expr[] operands;
    private final boolean byteArrays;

    MemcpyExpression(Expr[] operands, CoreRepresentation proof, boolean byteArrays) {
        this.operands = operands;
        this.byteArrays = byteArrays;
        setRepresentation(new CoreRepresentation(proof.getKind(), true, proof.getPresent(),
            proof.getPrimReps(), proof.getComponents(), proof.getVector(), proof.getAlternatives(),
            proof.getTagSlot(), proof.getAlternativeSlots()));
    }

    @Override public Object execute(VirtualFrame frame) {
        throw fault("memcpy requires a tuple destination");
    }

    @Override public Object executeTuple(VirtualFrame frame, int[] slots, int offset) {
        var destination = byteArrays ? ManagedAddress.fromGuestByteArray(operands[0].execute(frame))
            : operands[0].executeRequiredAddress(frame);
        var source = byteArrays ? ManagedAddress.fromGuestByteArray(operands[1].execute(frame))
            : operands[1].executeRequiredAddress(frame);
        long count = operands[2].executeRequiredLong(frame);
        TupleResults.requireVoidCarrier(operands[3].execute(frame));
        source.copyNonOverlappingTo(destination, count);
        FrameAccess.INSTANCE.writeObject(frame, slots[offset], destination);
        return null;
    }
}
