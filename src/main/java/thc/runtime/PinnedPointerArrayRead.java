// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;
import com.oracle.truffle.api.frame.VirtualFrame;
import static thc.runtime.RuntimeServiceStatus.fault;
public final class PinnedPointerArrayRead extends Expr {
    private final boolean byteOffset;
    @Child private Expr array, index, state;
    public PinnedPointerArrayRead(CoreRepresentation proof, boolean byteOffset, Expr array, Expr index, Expr state) {
        this.byteOffset = byteOffset; this.array = array; this.index = index; this.state = state;
        setRepresentation(proof.copy(proof.getKind(), true, proof.getPresent(), proof.getPrimReps(), proof.getComponents(), proof.getVector(), proof.getAlternatives(), proof.getTagSlot(), proof.getAlternativeSlots()));
    }
    @Override public Object execute(VirtualFrame frame) { throw fault("AddrArray# read requires a tuple destination"); }
    @Override public Object executeTuple(VirtualFrame frame, int[] slots, int offset) {
        Object allocation = array.execute(frame); long element = index.executeRequiredLong(frame); ManagedByteArray.requireState(state.execute(frame));
        FrameAccess.write(frame, slots[offset], PinnedMemory.readAddressArray(allocation, element, byteOffset)); return null;
    }
}
