// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;
import com.oracle.truffle.api.frame.VirtualFrame;
public final class PinnedPointerArrayWrite extends Expr {
    private final boolean byteOffset;
    @Child private Expr array, index, address, state;
    public PinnedPointerArrayWrite(CoreRepresentation proof, boolean byteOffset, Expr array, Expr index, Expr address, Expr state) {
        this.byteOffset = byteOffset; this.array = array; this.index = index; this.address = address; this.state = state;
        setRepresentation(proof.copy(proof.getKind(), true, proof.getPresent(), proof.getPrimReps(), proof.getComponents(), proof.getVector(), proof.getAlternatives(), proof.getTagSlot(), proof.getAlternativeSlots()));
    }
    @Override public Object execute(VirtualFrame frame) {
        Object allocation = array.execute(frame); long element = index.executeRequiredLong(frame);
        var value = address.executeRequiredAddress(frame); Object token = state.execute(frame); ManagedByteArray.requireState(token);
        PinnedMemory.writeAddressArray(allocation, element, value, byteOffset); return token;
    }
}
