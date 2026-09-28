// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import java.util.Arrays;

/** One incoming loan; entry makes its values durable before running guest code. */
public final class HandoffPool {
    private HandoffStorage[] slots = new HandoffStorage[4];
    private HandoffStorage active;
    // Prepared code retains descriptors from another context whose numeric IDs may collide.
    private java.util.IdentityHashMap<HandoffLayout, HandoffStorage> sharedLayouts;
    private long allocations;
    public int getDepth() { return active == null ? 0 : 1; }
    public long getAllocations() { return allocations; }
    public HandoffStorage acquire(HandoffLayout layout) {
        if (active != null) throw new IllegalStateException("Check failed.");
        HandoffStorage[] alternatives = layout.getId() < slots.length ? slots : grow(layout.getId());
        HandoffStorage storage = alternatives[layout.getId()];
        if (storage == null) storage = allocate(layout, alternatives);
        else if (storage.getLayout() != layout) storage = shared(layout);
        if (storage.getLive()) throw new IllegalStateException("Check failed.");
        storage.setLive(true);
        storage.setGeneration(storage.getGeneration() + 1);
        active = storage;
        return storage;
    }
    @TruffleBoundary private HandoffStorage[] grow(int id) { return slots = Arrays.copyOf(slots, Math.max(slots.length * 2, id + 1)); }
    @TruffleBoundary private HandoffStorage allocate(HandoffLayout layout, HandoffStorage[] alternatives) {
        HandoffStorage storage = layout.create();
        alternatives[layout.getId()] = storage;
        allocations++;
        return storage;
    }
    @TruffleBoundary private HandoffStorage shared(HandoffLayout layout) {
        if (sharedLayouts == null) sharedLayouts = new java.util.IdentityHashMap<>();
        return sharedLayouts.computeIfAbsent(layout, key -> { allocations++; return key.create(); });
    }
    public void release(HandoffStorage storage) { release(storage, storage.getLayout()); }
    public void release(HandoffStorage storage, HandoffLayout layout) {
        if (active != storage || !storage.getLive()) throw new IllegalStateException("Check failed.");
        layout.clearReferences(storage);
        storage.setLive(false); active = null;
    }
    public int retainedReferences() {
        int count = 0;
        for (HandoffStorage storage : slots) if (storage != null) {
            HandoffLayout layout = storage.getLayout();
            for (int i = 0; i < layout.getReps().size(); i++) if (layout.isObject(i) && layout.getObject(storage, i) != null) count++;
        }
        if (sharedLayouts != null) for (HandoffStorage storage : sharedLayouts.values()) {
            HandoffLayout layout = storage.getLayout();
            for (int i = 0; i < layout.getReps().size(); i++) if (layout.isObject(i) && layout.getObject(storage, i) != null) count++;
        }
        return count;
    }
    public int retainedReferences$org_intelligence_thc() { return retainedReferences(); }
}
