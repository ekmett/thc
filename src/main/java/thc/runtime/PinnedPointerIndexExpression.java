// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;
import com.oracle.truffle.api.frame.VirtualFrame;
import static thc.runtime.RuntimeServiceStatus.fault;
public final class PinnedPointerIndexExpression extends Expr {
    private final PinnedMemoryOp operation;
    private final boolean byteOffset;
    @Child private Expr base, index;
    public PinnedPointerIndexExpression(PinnedMemoryOp operation, CoreRepresentation proof, boolean byteOffset, Expr base, Expr index) {
        this.operation = operation; this.byteOffset = byteOffset; this.base = base; this.index = index;
        setRepresentation(proof.copy(proof.getKind(), true, proof.getPresent(), proof.getPrimReps(), proof.getComponents(), proof.getVector(), proof.getAlternatives(), proof.getTagSlot(), proof.getAlternativeSlots()));
    }
    @Override public Object execute(VirtualFrame frame) { return executeAddress(frame); }
    @Override public ManagedAddress executeAddress(VirtualFrame frame) {
        if (operation == PinnedMemoryOp.INDEX_ADDR_OFF) return base.executeRequiredAddress(frame).readAddressElementIndex(index.executeRequiredLong(frame), byteOffset);
        if (operation == PinnedMemoryOp.INDEX_ADDR_ARRAY) return PinnedMemory.readAddressArray(base.execute(frame), index.executeRequiredLong(frame), byteOffset);
        throw fault("Expected a pointer index primitive");
    }
}
