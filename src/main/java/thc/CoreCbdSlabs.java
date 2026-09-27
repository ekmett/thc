// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/** Immutable inflated members, never decoded/executable objects. Reservations
 * pin one publication while waiters wake. Inflation is outside the cache lock. */
public final class CoreCbdSlabs implements AutoCloseable {
    public static final CoreCbdSlabs shared = new CoreCbdSlabs(64L * 1024 * 1024, 64);
    private final long maxIdleBytes;
    private final int maxIdleEntries;
    public CoreCbdSlabs(long maxIdleBytes, int maxIdleEntries) {
        if (maxIdleBytes < 0 || maxIdleEntries < 0) throw new IllegalArgumentException("Failed requirement.");
        this.maxIdleBytes = maxIdleBytes;
        this.maxIdleEntries = maxIdleEntries;
    }
    public record Key(Object snapshot, String member) {
        public Key { Objects.requireNonNull(snapshot); Objects.requireNonNull(member); }
    }
    public static final class Slab {
        private final Arena arena;
        private final MemorySegment bytes;
        public Slab(Arena arena, MemorySegment bytes) {
            this.arena = Objects.requireNonNull(arena);
            this.bytes = Objects.requireNonNull(bytes);
        }
        public Arena getArena() { return arena; }
        public MemorySegment getBytes() { return bytes; }
    }
    @FunctionalInterface public interface Inflation { Slab inflate() throws Throwable; }
    private static final class Entry {
        final Key key;
        final CompletableFuture<Slab> ready = new CompletableFuture<>();
        Slab slab;
        long leases;
        Entry(Key key) { this.key = key; }
    }
    public static final class Lease implements AutoCloseable {
        private CoreCbdSlabs owner;
        private Entry entry;
        private final boolean inflated;
        private Lease(CoreCbdSlabs owner, Entry entry, boolean inflated) {
            this.owner = owner;
            this.entry = entry;
            this.inflated = inflated;
        }
        public boolean getInflated() { return inflated; }
        public synchronized MemorySegment getBytes() {
            if (entry == null) throw new IllegalStateException("CBD member lease is closed");
            return entry.slab.bytes;
        }
        @Override public synchronized void close() {
            if (entry == null) return;
            Entry retained = entry;
            entry = null;
            owner.release(retained);
            owner = null;
        }
    }
    public record Statistics(long inflations, long hits, long failures, long closes,
            long activeLeases, int idleEntries, long idleBytes) {}
    private final HashMap<Key, Entry> entries = new HashMap<>();
    private final LinkedHashMap<Key, Entry> idle = new LinkedHashMap<>();
    private boolean closed;
    private long inflations, hits, failures, closes, leases, idleBytes;
    public synchronized Statistics statistics() {
        return new Statistics(inflations, hits, failures, closes, leases, idle.size(), idleBytes);
    }
    public Lease acquire(Key key, Inflation inflate) {
        Objects.requireNonNull(key);
        Objects.requireNonNull(inflate);
        final Entry entry;
        final boolean creator;
        synchronized (this) {
            if (closed) throw new IllegalStateException("CBD slab cache is closed");
            Entry previous = entries.get(key);
            creator = previous == null;
            entry = creator ? new Entry(key) : previous;
            if (creator) entries.put(key, entry);
            else {
                hits++;
                if (entry.leases == 0) {
                    check(idle.remove(key) == entry);
                    idleBytes -= entry.slab.bytes.byteSize();
                }
            }
            check(entry.leases < Long.MAX_VALUE && leases < Long.MAX_VALUE);
            entry.leases++;
            leases++;
        }
        if (creator) {
            try {
                Slab slab = Objects.requireNonNull(inflate.inflate());
                synchronized (this) { entry.slab = slab; inflations++; }
                entry.ready.complete(slab);
            } catch (Throwable failure) {
                synchronized (this) { check(entries.remove(key) == entry); failures++; }
                entry.ready.completeExceptionally(failure);
            }
        }
        try {
            entry.ready.join();
            return new Lease(this, entry, creator);
        } catch (Throwable failure) {
            synchronized (this) { entry.leases--; leases--; }
            Throwable original = failure instanceof CompletionException && failure.getCause() != null
                    ? failure.getCause() : failure;
            throw CoreCbdSlabs.<RuntimeException>propagate(original);
        }
    }
    // The callback's original exception (including checked inflater errors) is
    // shared by all waiters unchanged; no wrapper substitutes its identity.
    @SuppressWarnings("unchecked")
    private static <E extends Throwable> RuntimeException propagate(Throwable failure) throws E {
        throw (E) failure;
    }
    private synchronized void release(Entry entry) {
        check(entry.leases > 0);
        leases--;
        if (--entry.leases != 0) return;
        long size = entry.slab.bytes.byteSize();
        if (closed || maxIdleEntries == 0 || size > maxIdleBytes) {
            check(entries.remove(entry.key) == entry);
            dispose(entry);
        } else {
            while (idle.size() >= maxIdleEntries || size > maxIdleBytes - idleBytes) evictOldest();
            idle.put(entry.key, entry);
            idleBytes += size;
        }
    }
    private void dispose(Entry entry) { entry.slab.arena.close(); closes++; }
    private void evictOldest() {
        var iterator = idle.entrySet().iterator();
        Entry entry = iterator.next().getValue();
        iterator.remove();
        check(entries.remove(entry.key) == entry);
        idleBytes -= entry.slab.bytes.byteSize();
        dispose(entry);
    }
    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        while (!idle.isEmpty()) evictOldest();
    }
    private static void check(boolean condition) {
        if (!condition) throw new IllegalStateException("Check failed.");
    }
}
