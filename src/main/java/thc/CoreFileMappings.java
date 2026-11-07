// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Objects;

/** Process-shareable read-only mappings of immutable, producer-identified files.
 * Only acquire opens a file. Keys normalize the absolute path without resolving
 * symlinks, stat, reads or hashing. Identity is a producer assertion, not a
 * verification certificate: verification callers must verify their own snapshot.
 * Never mutate a mapped file in place; publish a replacement with a new identity.
 * Active leases pin shared arenas. Only zero-lease mappings enter the bounded,
 * least-recently-released idle cache. Bounds describe mapped bytes, not RSS.
 * Decoded values, execution state and context counters do not belong here. */
public final class CoreFileMappings implements AutoCloseable {
    public static final CoreFileMappings shared = new CoreFileMappings(256L * 1024 * 1024, 64);
    private final long maxIdleBytes;
    private final int maxIdleEntries;

    public CoreFileMappings(long maxIdleBytes, int maxIdleEntries) {
        if (maxIdleBytes < 0 || maxIdleEntries < 0) throw new IllegalArgumentException("Negative mapping-cache budget");
        this.maxIdleBytes = maxIdleBytes;
        this.maxIdleEntries = maxIdleEntries;
    }

    public record Statistics(long mappingOpens, long mappingHits, long mappingCloses,
            long failedOpens, long activeMappings, long activeLeases, int idleMappings, long idleBytes) {}
    private record Key(Path path, String identity) {}
    private static final class Mapping {
        final Key key;
        final Arena arena;
        final MemorySegment bytes;
        final Object snapshot = new Object();
        long leases;
        Mapping(Key key, Arena arena, MemorySegment bytes) {
            this.key = key;
            this.arena = arena;
            this.bytes = bytes;
        }
    }

    public static final class Lease implements AutoCloseable {
        private CoreFileMappings owner;
        private Mapping mapping;
        private final boolean opened;
        private Lease(CoreFileMappings owner, Mapping mapping, boolean opened) {
            this.owner = owner;
            this.mapping = mapping;
            this.opened = opened;
        }
        public boolean getOpened() { return opened; }
        /** Borrowed until this lease closes. Callers serialize their use vs close. */
        public synchronized MemorySegment getBytes() { return live().bytes; }
        public long getSize() { return getBytes().byteSize(); }
        /** Exact snapshot identity, including fresh verified opens. */
        public synchronized Object getSnapshot() { return live().snapshot; }
        private Mapping live() {
            if (mapping == null) throw new IllegalStateException("Core file mapping lease is closed");
            return mapping;
        }
        /** Pin these bytes without looking up or opening their pathname again. */
        public synchronized Lease retain() { return owner().retain(live()); }
        private CoreFileMappings owner() {
            if (owner == null) throw new IllegalStateException("Core file mapping lease is closed");
            return owner;
        }
        @Override public synchronized void close() {
            if (mapping == null) return;
            Mapping retained = mapping;
            CoreFileMappings cache = owner();
            mapping = null;
            owner = null;
            cache.release(retained);
        }
    }

    private final HashMap<Key, Mapping> mappings = new HashMap<>();
    private final LinkedHashMap<Key, Mapping> idle = new LinkedHashMap<>();
    private boolean closed;
    private long opens, hits, closes, failures, active, leases, idleBytes;

    public synchronized Statistics statistics() {
        return new Statistics(opens, hits, closes, failures, active, leases, idle.size(), idleBytes);
    }
    public synchronized Lease acquire(Path path, String identity) throws IOException {
        Objects.requireNonNull(path);
        Objects.requireNonNull(identity);
        if (closed) throw new IllegalStateException("Core file mapping cache is closed");
        if (identity.isEmpty()) throw new IllegalArgumentException("Missing producer identity for shared mapping");
        Key key = new Key(path.toAbsolutePath().normalize(), identity);
        Mapping mapping = mappings.get(key);
        boolean opened = mapping == null;
        if (mapping == null) {
            mapping = open(key.path, key);
            mappings.put(key, mapping);
        } else {
            hits++;
            if (mapping.leases == 0) {
                check(idle.remove(key) == mapping);
                idleBytes -= mapping.bytes.byteSize();
            }
        }
        if (mapping.leases == 0) active++;
        mapping.leases++;
        leases++;
        return new Lease(this, mapping, opened);
    }
    /** No producer identity means no pathname-only reuse. Retained handles
     * share this fresh mapping, not a future pathname lookup. */
    public synchronized Lease acquireUncached(Path path) throws IOException {
        Objects.requireNonNull(path);
        if (closed) throw new IllegalStateException("Core file mapping cache is closed");
        Mapping mapping = open(path.toAbsolutePath().normalize(), null);
        mapping.leases = 1;
        active++;
        leases++;
        return new Lease(this, mapping, true);
    }
    // Retaining an active lease remains legal after cache close.
    private synchronized Lease retain(Mapping mapping) {
        check(mapping.leases > 0 && mapping.leases < Long.MAX_VALUE && leases < Long.MAX_VALUE);
        mapping.leases++;
        leases++;
        return new Lease(this, mapping, false);
    }
    private Mapping open(Path path, Key key) throws IOException {
        Arena arena = Arena.ofShared();
        try {
            MemorySegment bytes;
            try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
                bytes = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size(), arena);
            }
            Mapping mapping = new Mapping(key, arena, bytes);
            opens++;
            return mapping;
        } catch (Throwable failure) {
            failures++;
            try { arena.close(); } catch (Throwable cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }
    private synchronized void release(Mapping mapping) {
        check(mapping.leases > 0);
        leases--;
        if (--mapping.leases != 0) return;
        active--;
        Key key = mapping.key;
        if (closed || key == null || maxIdleEntries == 0 || mapping.bytes.byteSize() > maxIdleBytes) {
            if (key != null) check(mappings.remove(key) == mapping);
            dispose(mapping);
            return;
        }
        // Evict first: summing idle lengths cannot overflow even a huge budget.
        while (idle.size() >= maxIdleEntries || mapping.bytes.byteSize() > maxIdleBytes - idleBytes) evictOldest();
        idle.put(key, mapping);
        idleBytes += mapping.bytes.byteSize();
    }
    private void dispose(Mapping mapping) { mapping.arena.close(); closes++; }
    private void evictOldest() {
        var iterator = idle.entrySet().iterator();
        var entry = iterator.next();
        Mapping mapping = entry.getValue();
        iterator.remove();
        check(mappings.remove(entry.getKey()) == mapping);
        idleBytes -= mapping.bytes.byteSize();
        dispose(mapping);
    }
    /** A standalone launcher may checkpoint only after every CBD reader releases
     * its lease. Drop the idle arenas too; the cache remains usable on restore. */
    synchronized void prepareCheckpoint() {
        if (leases != 0) throw new IllegalStateException("Cannot checkpoint with active CBD mapping leases: " + leases);
        while (!idle.isEmpty()) evictOldest();
    }
    /** Evict idle mappings at/below a normalized path before temporary-tree
     * cleanup or replacement, notably on Windows. Active leases remain pinned.
     * No files are inspected or removed; unrelated idle order is unchanged. */
    public synchronized int evictIdleBelow(Path path) {
        Objects.requireNonNull(path);
        Path prefix = path.toAbsolutePath().normalize();
        int removed = 0;
        var iterator = idle.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (!entry.getKey().path.startsWith(prefix)) continue;
            Mapping mapping = entry.getValue();
            check(mapping.leases == 0);
            iterator.remove();
            check(mappings.remove(entry.getKey()) == mapping);
            idleBytes -= mapping.bytes.byteSize();
            dispose(mapping);
            removed++;
        }
        return removed;
    }
    /** Stop acquisition and close idle entries; active arenas survive until
     * their last lease closes. */
    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        while (!idle.isEmpty()) evictOldest();
    }
    private static void check(boolean condition) {
        if (!condition) throw new IllegalStateException("Check failed.");
    }
}
