// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import java.util.ArrayList;
import java.util.List;
import thc.Language;

public final class HandoffEntry {
    static final Object COMPLETE = new Object();
    static final Object INT_COMPLETE = new Object();
    private final Language language;
    private final HandoffLayout arguments;
    private final boolean resultLong, resultInt;
    @CompilationFinal(dimensions = 1) private final int[] snapshotSlots;
    private final int destinationSlot;
    public HandoffEntry(Language language, HandoffLayout arguments, boolean resultLong, int[] snapshotSlots, int destinationSlot) {
        this(language, arguments, resultLong, snapshotSlots, destinationSlot, false);
    }
    public HandoffEntry(Language language, HandoffLayout arguments, boolean resultLong, int[] snapshotSlots, int destinationSlot, boolean resultInt) {
        this.language = language; this.arguments = arguments; this.resultLong = resultLong;
        this.snapshotSlots = snapshotSlots; this.destinationSlot = destinationSlot; this.resultInt = resultInt;
    }
    public HandoffLayout getArguments() { return arguments; }
    public boolean getResultLong() { return resultLong; }
    public boolean getResultInt() { return resultInt; }
    public int[] getSnapshotSlots() { return snapshotSlots; }
    public int getDestinationSlot() { return destinationSlot; }
    public HandoffState state() { return language.getHandoffState$org_intelligence_thc().get(); }
    public int destination(VirtualFrame frame) { return (int) frame.getLong(destinationSlot); }
    public void initializeOrdinary(VirtualFrame frame) { frame.setLong(destinationSlot, -1L); }
    @ExplodeLoop public void snapshot(VirtualFrame frame, HandoffStorage input) {
        if (input.getLayout() != arguments || !input.getLive()) throw new IllegalStateException("Check failed.");
        for (int i = 0; i < snapshotSlots.length; i++) {
            if (arguments.isInt(i)) FrameAccess.writeInt(frame, snapshotSlots[i], arguments.getInt(input, i));
            else if (arguments.isLong(i)) FrameAccess.writeLong(frame, snapshotSlots[i], arguments.getLong(input, i));
            else FrameAccess.write(frame, snapshotSlots[i], arguments.getObject(input, i));
        }
    }
    public Object finishLong(VirtualFrame frame, long value) {
        if (destination(frame) < 0) throw new IllegalStateException("Check failed.");
        state().setReturnLong(value);
        return COMPLETE;
    }
    public Object finishInt(VirtualFrame frame, int value) {
        if (destination(frame) < 0) throw new IllegalStateException("Check failed.");
        state().setReturnInt(value);
        return INT_COMPLETE;
    }
    public static HandoffEntry create(TruffleLanguage<?> language, FrameLayout layout, List<CoreRepresentation> argumentReps,
                                     CoreRepresentation resultRep, boolean hasEnvironment) {
        if (!(language instanceof Language thc)) return null;
        for (CoreRepresentation proof : argumentReps) if (proof.isTuple() && !proof.isEmptyTuple()) return null;
        if (!thc.getHandoffLayouts$org_intelligence_thc().getEnabled() || resultRep.isAggregate()) return null;
        List<String> resultReps = resultRep.getPrimReps();
        boolean resultReference = resultReps != null && resultReps.size() == 1 && resultReps.getFirst().startsWith("BoxedRep ");
        if (!resultRep.isInt() && !resultRep.isLong() && !resultReference) return null;
        ArrayList<String> packetReps = new ArrayList<>();
        packetReps.add("WordRep");
        if (hasEnvironment) packetReps.add("BoxedRep (Just Unlifted)");
        for (CoreRepresentation proof : argumentReps) {
            if (proof.isEmptyTuple()) continue;
            List<String> reps = proof.getPrimReps();
            if (reps == null || reps.size() != 1 || !HandoffLayout.supports(reps.getFirst())) return null;
            packetReps.add(reps.getFirst());
        }
        int[] slots = new int[packetReps.size()];
        for (int i = 0; i < slots.length; i++) slots[i] = layout.bind("<handoff entry " + i + ">");
        return new HandoffEntry(thc, thc.getHandoffLayouts$org_intelligence_thc().intern(packetReps), resultRep.isLong(),
            slots, layout.bind("<handoff result destination>"), resultRep.isInt());
    }
}
