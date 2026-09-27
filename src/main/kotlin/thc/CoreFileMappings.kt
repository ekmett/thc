// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc

import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/** Process-shareable read-only mappings of immutable, producer-identified files.
 *
 * Only [acquire] opens a file. Key construction normalizes the absolute path;
 * it does not resolve symlinks, stat, read or hash the file. Identity is the
 * producer's version assertion, NOT a verification certificate. A caller that
 * requests verification must still verify the bytes of its acquired lease.
 * Never mutate a mapped file in place: publish changed bytes by replacement
 * with a changed identity. Decoded values, execution state and context counters
 * do not belong in this cache.
 *
 * Active leases pin their shared arena. Only zero-lease mappings enter the
 * bounded least-recently-released idle cache. Bounds cover mapped file bytes
 * and entry count, not resident memory; active mappings are never evicted.
 */
internal class CoreFileMappings(private val maxIdleBytes: Long, private val maxIdleEntries: Int) : AutoCloseable {
    init { require(maxIdleBytes >= 0 && maxIdleEntries >= 0) { "Negative mapping-cache budget" } }

    companion object {
        val shared = CoreFileMappings(256L * 1024 * 1024, 64)
    }

    data class Statistics(val mappingOpens: Long, val mappingHits: Long, val mappingCloses: Long,
        val failedOpens: Long, val activeMappings: Long, val activeLeases: Long,
        val idleMappings: Int, val idleBytes: Long)

    internal data class Key(val path: Path, val identity: String)
    internal class Mapping(val key: Key?, val arena: Arena, val bytes: MemorySegment) {
        val snapshot = Any()
        var leases = 0L
    }
    class Lease internal constructor(private var owner: CoreFileMappings?, private var mapping: Mapping?,
                                     val opened: Boolean) : AutoCloseable {
        /** Borrowed only until this lease closes; retaining this segment does
         * not create another lease. Callers serialize their own use vs close. */
        val bytes: MemorySegment get() = synchronized(this) {
            checkNotNull(mapping) { "Core file mapping lease is closed" }.bytes
        }
        val size: Long get() = bytes.byteSize()
        /** Identity of these exact mapped bytes, including fresh verified opens. */
        val snapshot: Any get() = synchronized(this) {
            checkNotNull(mapping) { "Core file mapping lease is closed" }.snapshot
        }
        /** Pins this snapshot without looking up or opening its pathname again. */
        @Synchronized fun retain(): Lease = checkNotNull(owner) { "Core file mapping lease is closed" }
            .retain(checkNotNull(mapping))
        @Synchronized override fun close() {
            val retained = mapping ?: return
            val cache = checkNotNull(owner)
            mapping = null
            owner = null
            cache.release(retained)
        }
    }

    private val mappings = HashMap<Key, Mapping>()
    private val idle = LinkedHashMap<Key, Mapping>()
    private var closed = false
    private var opens = 0L
    private var hits = 0L
    private var closes = 0L
    private var failures = 0L
    private var active = 0L
    private var leases = 0L
    private var idleBytes = 0L

    @Synchronized fun statistics() = Statistics(opens, hits, closes, failures, active, leases, idle.size, idleBytes)

    @Synchronized fun acquire(path: Path, identity: String): Lease {
        check(!closed) { "Core file mapping cache is closed" }
        require(identity.isNotEmpty()) { "Missing producer identity for shared mapping" }
        val key = Key(path.toAbsolutePath().normalize(), identity)
        var mapping = mappings[key]
        val opened = mapping == null
        if (mapping == null) {
            mapping = open(key.path, key)
            mappings[key] = mapping
        } else {
            hits++
            if (mapping.leases == 0L) {
                check(idle.remove(key) === mapping)
                idleBytes -= mapping.bytes.byteSize()
            }
        }
        if (mapping.leases == 0L) active++
        mapping.leases++
        leases++
        return Lease(this, mapping, opened)
    }

    /** Callers without an immutable producer identity cannot reuse a pathname-only
     * cache entry. Their lease (and any retained handles) owns a fresh mapping. */
    @Synchronized fun acquireUncached(path: Path): Lease {
        check(!closed) { "Core file mapping cache is closed" }
        val mapping = open(path.toAbsolutePath().normalize(), null)
        mapping.leases = 1
        active++
        leases++
        return Lease(this, mapping, true)
    }

    // Retaining an existing active lease is permitted after cache close: close
    // forbids new acquisition, not ownership transfer of already pinned bytes.
    @Synchronized private fun retain(mapping: Mapping): Lease {
        check(mapping.leases > 0 && mapping.leases < Long.MAX_VALUE && leases < Long.MAX_VALUE)
        mapping.leases++
        leases++
        return Lease(this, mapping, false)
    }

    private fun open(path: Path, key: Key?): Mapping {
        val arena = Arena.ofShared()
        try {
            val bytes = FileChannel.open(path, StandardOpenOption.READ).use { channel ->
                channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size(), arena)
            }
            return Mapping(key, arena, bytes).also { opens++ }
        } catch (failure: Throwable) {
            failures++
            try { arena.close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            throw failure
        }
    }

    @Synchronized private fun release(mapping: Mapping) {
        check(mapping.leases > 0)
        leases--
        if (--mapping.leases != 0L) return
        active--
        val key = mapping.key
        if (closed || key == null || maxIdleEntries == 0 || mapping.bytes.byteSize() > maxIdleBytes) {
            if (key != null) check(mappings.remove(key) === mapping)
            dispose(mapping)
            return
        }
        // Evict before adding, so even very large configured budgets cannot
        // overflow the sum of retained idle byte lengths.
        while (idle.size >= maxIdleEntries || mapping.bytes.byteSize() > maxIdleBytes - idleBytes) evictOldest()
        idle[key] = mapping
        idleBytes += mapping.bytes.byteSize()
    }

    private fun dispose(mapping: Mapping) {
        mapping.arena.close()
        closes++
    }
    private fun evictOldest() {
        val iterator = idle.entries.iterator()
        val entry = iterator.next()
        val mapping = entry.value
        iterator.remove()
        check(mappings.remove(entry.key) === mapping)
        idleBytes -= mapping.bytes.byteSize()
        dispose(mapping)
    }

    /** Release idle mappings at this normalized path or below it before a
     * temporary artifact tree is removed or immutable files are replaced.
     * Windows may deny deletion/replacement while any mapped view survives.
     * Active leases remain pinned; callers must close all their leases first
     * if they require filesystem removal to succeed. No files are inspected
     * or removed here, and unrelated idle cache entries retain their order. */
    @Synchronized fun evictIdleBelow(path: Path): Int {
        val prefix = path.toAbsolutePath().normalize()
        var removed = 0
        val iterator = idle.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (!entry.key.path.startsWith(prefix)) continue
            val mapping = entry.value
            check(mapping.leases == 0L)
            iterator.remove()
            check(mappings.remove(entry.key) === mapping)
            idleBytes -= mapping.bytes.byteSize()
            dispose(mapping)
            removed++
        }
        return removed
    }

    /** Stops new acquisition and drops idle entries. Existing active leases
     * remain usable and release their arenas when their last holder closes. */
    @Synchronized override fun close() {
        if (closed) return
        closed = true
        while (idle.isNotEmpty()) evictOldest()
    }
}
