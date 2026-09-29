// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.FrameSlotKind;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Allocate indexed slots during lowering, sharing allocation across lexical scopes. */
public final class FrameLayout {
    public static final int BLOOM_FILTER = 0, TAIL_RESULT = 1, TAIL_FUNCTION = 2, TAIL_ARGUMENTS = 3;
    private final FrameDescriptor.Builder builder;
    private final Map<String, Integer> locals;
    private final List<Integer> initialClears;
    private FrameLayout(FrameDescriptor.Builder builder, Map<String, Integer> locals, List<Integer> initialClears) {
        this.builder = builder; this.locals = locals; this.initialClears = initialClears;
    }
    public FrameLayout() {
        this(FrameDescriptor.newBuilder(), new HashMap<>(), new ArrayList<>());
        builder.addSlot(FrameSlotKind.Long, "<TCO Bloom Filter>", null);
        builder.addSlot(FrameSlotKind.Object, "<TCO Result>", null);
        builder.addSlot(FrameSlotKind.Object, "<TCO Function>", null);
        builder.addSlot(FrameSlotKind.Object, "<TCO Arguments>", null);
    }
    public FrameLayout scope() { return new FrameLayout(builder, new HashMap<>(locals), initialClears); }
    /** Only fresh compiler-owned slots whose consumers always write before reading. */
    void clearInitially(int... slots) {
        for (int slot : slots) {
            if (slot <= TAIL_ARGUMENTS || slot >= nextSlot()) throw new IllegalArgumentException("Invalid initial scratch slot");
            initialClears.add(slot);
        }
    }
    int[] initialClears() { return initialClears.stream().mapToInt(Integer::intValue).toArray(); }
    /** Storage for a completed scalar or a physical tuple leaf, not a WHNF certificate. */
    public static FrameSlotKind carrierKind(CoreRepresentation proof) {
        if (proof.isInt()) return FrameSlotKind.Int;
        return switch (proof.getKind()) {
            case LONG -> FrameSlotKind.Long;
            case FLOAT -> FrameSlotKind.Float;
            case DOUBLE -> FrameSlotKind.Double;
            case VOID, DATA, CLOSURE, ADDRESS, OBJECT, VECTOR -> FrameSlotKind.Object;
            case UNKNOWN -> FrameSlotKind.Illegal;
        };
    }
    public int bind(String name) { return bind(name, FrameSlotKind.Illegal); }
    public int bind(String name, FrameSlotKind kind) {
        int slot = builder.addSlot(kind, name, null);
        locals.put(name, slot);
        return slot;
    }
    public int slot(String name) {
        Integer slot = locals.get(name);
        if (slot != null) return slot;
        return bind(name);
    }
    /** Return the allocation cursor without reserving a slot. */
    int nextSlot() { return builder.addSlots(0); }
    public FrameDescriptor build() { return builder.build(); }
}
