// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;
import com.oracle.truffle.api.frame.VirtualFrame;
public final class PinnedByteArrayContents extends Expr {
    @Child private Expr array;
    public PinnedByteArrayContents(CoreRepresentation proof, Expr array) {
        this.array = array;
        setRepresentation(proof.copy(proof.getKind(), true, proof.getPresent(), proof.getPrimReps(), proof.getComponents(), proof.getVector(), proof.getAlternatives(), proof.getTagSlot(), proof.getAlternativeSlots()));
    }
    @Override public ManagedAddress execute(VirtualFrame frame) { return executeAddress(frame); }
    @Override public ManagedAddress executeAddress(VirtualFrame frame) { return ManagedAddress.fromGuestByteArray(array.execute(frame)); }
}
