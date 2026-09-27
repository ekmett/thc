// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc

import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException

/** Immutable inflated members only; never decoded or executable objects.
 * Loading reservations pin a single publication, including while waiters wake.
 * Inflation runs outside the cache lock, so unrelated members can progress. */
internal class CoreCbdSlabs(private val maxIdleBytes: Long, private val maxIdleEntries: Int) : AutoCloseable {
    init { require(maxIdleBytes >= 0 && maxIdleEntries >= 0) }
    companion object { val shared = CoreCbdSlabs(64L * 1024 * 1024, 64) }
    data class Key(val snapshot: Any, val member: String)
    class Slab(val arena: Arena, val bytes: MemorySegment)
    internal class Entry(val key: Key) {
        val ready = CompletableFuture<Slab>()
        var slab: Slab? = null
        var leases = 0L
    }
    class Lease internal constructor(private var owner: CoreCbdSlabs?, private var entry: Entry?,
                                     val inflated: Boolean) : AutoCloseable {
        val bytes: MemorySegment get() = synchronized(this) {
            checkNotNull(entry) { "CBD member lease is closed" }.slab!!.bytes
        }
        @Synchronized override fun close() {
            val retained = entry ?: return
            entry = null
            checkNotNull(owner).release(retained)
            owner = null
        }
    }
    data class Statistics(val inflations: Long, val hits: Long, val failures: Long, val closes: Long,
                          val activeLeases: Long, val idleEntries: Int, val idleBytes: Long)
    private val entries = HashMap<Key, Entry>()
    private val idle = LinkedHashMap<Key, Entry>()
    private var closed = false
    private var inflations = 0L
    private var hits = 0L
    private var failures = 0L
    private var closes = 0L
    private var leases = 0L
    private var idleBytes = 0L

    @Synchronized fun statistics() = Statistics(inflations, hits, failures, closes, leases, idle.size, idleBytes)

    fun acquire(key: Key, inflate: () -> Slab): Lease {
        val entry: Entry
        val creator: Boolean
        synchronized(this) {
            check(!closed) { "CBD slab cache is closed" }
            val previous = entries[key]
            creator = previous == null
            entry = previous ?: Entry(key).also { entries[key] = it }
            if (!creator) {
                hits++
                if (entry.leases == 0L) {
                    check(idle.remove(key) === entry)
                    idleBytes -= checkNotNull(entry.slab).bytes.byteSize()
                }
            }
            check(entry.leases < Long.MAX_VALUE && leases < Long.MAX_VALUE)
            entry.leases++
            leases++
        }
        if (creator) {
            try {
                val slab = inflate()
                synchronized(this) { entry.slab = slab; inflations++ }
                entry.ready.complete(slab)
            } catch (failure: Throwable) {
                synchronized(this) { check(entries.remove(key) === entry); failures++ }
                entry.ready.completeExceptionally(failure)
            }
        }
        try {
            entry.ready.join()
            return Lease(this, entry, creator)
        } catch (failure: Throwable) {
            synchronized(this) { entry.leases--; leases-- }
            throw (if (failure is CompletionException) failure.cause ?: failure else failure)
        }
    }

    @Synchronized private fun release(entry: Entry) {
        check(entry.leases > 0)
        leases--
        if (--entry.leases != 0L) return
        val size = checkNotNull(entry.slab).bytes.byteSize()
        if (closed || maxIdleEntries == 0 || size > maxIdleBytes) {
            check(entries.remove(entry.key) === entry)
            dispose(entry)
        } else {
            while (idle.size >= maxIdleEntries || size > maxIdleBytes - idleBytes) evictOldest()
            idle[entry.key] = entry
            idleBytes += size
        }
    }
    private fun dispose(entry: Entry) { checkNotNull(entry.slab).arena.close(); closes++ }
    private fun evictOldest() {
        val iterator = idle.entries.iterator()
        val entry = iterator.next().value
        iterator.remove()
        check(entries.remove(entry.key) === entry)
        idleBytes -= checkNotNull(entry.slab).bytes.byteSize()
        dispose(entry)
    }
    @Synchronized override fun close() {
        if (closed) return
        closed = true
        while (idle.isNotEmpty()) evictOldest()
    }
}
