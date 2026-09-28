// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import static thc.runtime.RuntimeServiceStatus.fault;

/** Target allocation accounting, not a GHC heap image or a JVM GC exclusion zone. */
public final class ManagedCompact {
    public record Allocation(Object value, long bytes) {}
    private static final long HEADER_BYTES = 128L;
    private final ManagedCompacts owner;
    private final Object identity = new Object();
    private final ArrayList<Object> objects = new ArrayList<>();
    private long blockSize, capacity, available, generation;
    private boolean adding;
    public ManagedCompact(ManagedCompacts owner, long requested) {
        this.owner = owner;
        blockSize = blockSize(requested); capacity = blockSize; available = blockSize - HEADER_BYTES;
    }
    public ManagedCompacts getOwner() { return owner; }
    Object identity() { return identity; }
    public List<Object> getObjects() { return objects; }
    public long getGeneration() { return generation; }
    public synchronized <T> T snapshot(Function<List<Object>, T> action) {
        if (adding) throw fault("Cannot serialize a compact region while adding to it");
        return action.apply(objects);
    }
    public synchronized long size() { return capacity; }
    public synchronized void resize(long requested) {
        if (adding) throw fault("Concurrent compact-region mutation");
        blockSize = blockSize(requested);
        capacity = Math.addExact(capacity, blockSize);
        available = blockSize - HEADER_BYTES;
        generation++;
    }
    public synchronized void begin() {
        if (adding) throw fault("Concurrent or reentrant compactAdd# on one region");
        adding = true;
    }
    public synchronized void finish(List<Allocation> copied) {
        for (var allocation : copied) {
            long bytes = allocation.bytes();
            if (bytes > available) {
                long next = Math.max(blockSize, blockSize(bytes));
                capacity = Math.addExact(capacity, next);
                available = next - HEADER_BYTES;
            }
            available -= bytes;
            objects.add(allocation.value());
        }
        generation++;
    }
    public synchronized void end() { adding = false; }
    private static long blockSize(long requested) {
        if (requested < 0 || requested > (long) Integer.MAX_VALUE - 4096)
            throw fault("Compact allocation size outside managed target domain");
        return Math.max(4096L, (requested + HEADER_BYTES + 4095) / 4096 * 4096);
    }
}
