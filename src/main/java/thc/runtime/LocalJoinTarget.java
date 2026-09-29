// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.frame.MaterializedFrame;
import com.oracle.truffle.api.nodes.*;
import static thc.runtime.RuntimeServiceStatus.fault;
public final class LocalJoinTarget {
    private final Object group;
    private final int index;
    @CompilerDirectives.CompilationFinal(dimensions = 1) private final int[] slots;
    @CompilerDirectives.CompilationFinal(dimensions = 1) private final CoreRepresentation[] proofs;
    @CompilerDirectives.CompilationFinal(dimensions = 1) private final boolean[] entryStrict;
    private final CoreRepresentation result;
    @CompilerDirectives.CompilationFinal(dimensions = 2) private final int[][] typedSlots;
    @CompilerDirectives.CompilationFinal private int firstBodySlot, bodySlotLimit;
    private final LocalJoinJump jump;
    public LocalJoinTarget(Object group, int index, int[] slots, CoreRepresentation[] proofs) { this(group, index, slots, proofs, new boolean[slots.length], CoreRepresentation.UNKNOWN, new int[proofs.length][]); }
    public LocalJoinTarget(Object group, int index, int[] slots, CoreRepresentation[] proofs, boolean[] entryStrict, CoreRepresentation result) { this(group, index, slots, proofs, entryStrict, result, new int[proofs.length][]); }
    public LocalJoinTarget(Object group, int index, int[] slots, CoreRepresentation[] proofs, boolean[] entryStrict, CoreRepresentation result, int[][] typedSlots) {
        this.group = group; this.index = index; this.slots = slots; this.proofs = proofs; this.entryStrict = entryStrict; this.result = result; this.typedSlots = typedSlots;
        jump = new LocalJoinJump(this);
    }
    public Object getGroup() { return group; } public int getIndex() { return index; }
    public int[] getSlots() { return slots; } public CoreRepresentation[] getProofs() { return proofs; }
    public boolean[] getEntryStrict() { return entryStrict; } public CoreRepresentation getResult() { return result; }
    public int[][] getTypedSlots() { return typedSlots; } public LocalJoinJump getJump() { return jump; }
    /** Set once after lowering the group, before publishing its AST. */
    void setBodySlots(int first, int limit) { firstBodySlot = first; bodySlotLimit = limit; }
    @ExplodeLoop void clearBodySlots(VirtualFrame frame) {
        for (int slot = firstBodySlot; slot < bodySlotLimit; slot++) frame.clear(slot);
    }
}
