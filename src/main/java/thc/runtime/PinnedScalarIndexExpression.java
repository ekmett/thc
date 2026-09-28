// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;
import com.oracle.truffle.api.frame.VirtualFrame;
public final class PinnedScalarIndexExpression extends Expr {
    private final ManagedAddressRead operation;
    private final boolean byteOffset;
    @Child private Expr base, index;
    public PinnedScalarIndexExpression(ManagedAddressRead operation, CoreRepresentation proof, boolean byteOffset, Expr base, Expr index) {
        this.operation = operation; this.byteOffset = byteOffset; this.base = base; this.index = index;
        setRepresentation(proof.copy(proof.getKind(), true, proof.getPresent(), proof.getPrimReps(), proof.getComponents(), proof.getVector(), proof.getAlternatives(), proof.getTagSlot(), proof.getAlternativeSlots()));
    }
    @Override public Object execute(VirtualFrame frame) { if (operation.isInt()) return executeInt(frame); return executeLong(frame); }
    @Override public int executeInt(VirtualFrame frame) { return operation.readInt(base.executeRequiredAddress(frame), index.executeRequiredLong(frame), byteOffset); }
    @Override public long executeLong(VirtualFrame frame) { return operation.read(base.executeRequiredAddress(frame), index.executeRequiredLong(frame), byteOffset); }
}
