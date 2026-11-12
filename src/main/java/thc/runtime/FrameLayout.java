// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.FrameSlotKind;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** Allocate indexed slots during lowering, sharing allocation across lexical scopes. */
public final class FrameLayout {
    public static final int BLOOM_FILTER = 0, TAIL_RESULT = 1, TAIL_FUNCTION = 2, TAIL_ARGUMENTS = 3;
    private static final class Allocation {
        final FrameDescriptor.Builder builder = FrameDescriptor.newBuilder();
        final List<FrameSlotKind> kinds = new ArrayList<>();
        final LinkedHashSet<Integer> initialClears = new LinkedHashSet<>();
        int cursor;

        int bind(String name, FrameSlotKind kind) {
            if (kind == FrameSlotKind.Illegal) {
                // Unproved carriers and uniquely named scratch slots stay fresh.
                cursor = kinds.size();
            } else {
                while (cursor < kinds.size() && kinds.get(cursor) != kind) cursor++;
            }
            if (cursor == kinds.size()) {
                builder.addSlot(kind, name, null);
                kinds.add(kind);
            }
            return cursor++;
        }
    }

    private final Allocation allocation;
    private final Map<String, Integer> locals;
    private FrameLayout(Allocation allocation, Map<String, Integer> locals) {
        this.allocation = allocation;
        this.locals = locals;
    }
    public FrameLayout() {
        this(new Allocation(), new HashMap<>());
        allocation.bind("<TCO Bloom Filter>", FrameSlotKind.Long);
        allocation.bind("<TCO Result>", FrameSlotKind.Object);
        allocation.bind("<TCO Function>", FrameSlotKind.Object);
        allocation.bind("<TCO Arguments>", FrameSlotKind.Object);
    }
    public FrameLayout scope() { return new FrameLayout(allocation, new HashMap<>(locals)); }
    /** Only fresh compiler-owned slots whose consumers always write before reading. */
    void clearInitially(int... slots) {
        for (int slot : slots) {
            if (slot <= TAIL_ARGUMENTS || slot >= allocation.kinds.size()) throw new IllegalArgumentException("Invalid initial scratch slot");
            allocation.initialClears.add(slot);
        }
    }
    int[] initialClears() { return allocation.initialClears.stream().mapToInt(Integer::intValue).toArray(); }
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
    /** Allocation-time storage, unaffected by widening in later activations. */
    boolean referenceSlot(int slot) { return allocation.kinds.get(slot) == FrameSlotKind.Object; }
    public int bind(String name) { return bind(name, FrameSlotKind.Illegal); }
    public int bind(String name, FrameSlotKind kind) {
        int slot = allocation.bind(name, kind);
        locals.put(name, slot);
        return slot;
    }
    public int slot(String name) {
        Integer slot = locals.get(name);
        if (slot != null) return slot;
        return bind(name);
    }
    /** Return the allocation cursor without reserving a slot. */
    int nextSlot() { return allocation.cursor; }
    /** Move between mutually exclusive case alternatives or local-join bodies. */
    void alternativeSlot(int slot) { allocation.cursor = slot; }
    public FrameDescriptor build() { return allocation.builder.build(); }
}
