// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
import com.oracle.truffle.api.TruffleSafepoint
import com.oracle.truffle.api.nodes.ControlFlowException
import com.oracle.truffle.api.nodes.Node
import java.util.IdentityHashMap
import java.util.WeakHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Boxed payloads are never forced, compared with equals, or exposed without their context. */
internal class ManagedTVar internal constructor(val owner: ManagedSTM, internal var value: Any?) {
    internal var revision = Any()
}

/** Not Haskell exceptions: catchSTM must not catch retry or validation failure. */
internal object STMRetry : ControlFlowException()
internal object STMConflict : ControlFlowException()

/**
 * One commit domain per language context. The lock protects only storage, validation
 * and wake registration: no guest action, handler, force or equality runs under it.
 * Logs belong to the executing carrier and are removed on every exit. An async
 * unwind aborts the attempt; only the original atomically action may be saved for
 * restart. The abandoned invocation never carries its log or its child chain to
 * another carrier; saved inner thunk updates are demanded under a fresh attempt.
 */
internal class ManagedSTM : AutoCloseable {
    internal class Entry(val revision: Any, val original: Any?, var value: Any?, var written: Boolean) {
        fun copy() = Entry(revision, original, value, written)
    }
    internal class Transaction(val entries: IdentityHashMap<ManagedTVar, Entry> = IdentityHashMap())
    private val lock = ReentrantLock()
    private val current = ThreadLocal<Transaction?>()
    private val cells = WeakHashMap<ManagedTVar, Boolean>()
    private val waiters = mutableSetOf<RetryWait>()
    private var closed = false

    private fun live() { if (closed) fault("STM context has closed") }
    private fun cell(reference: Any?): ManagedTVar {
        val result = reference as? ManagedTVar ?: fault("Expected managed TVar#")
        if (result.owner !== this) fault("TVar# belongs to another context")
        return result
    }
    private fun transaction(): Transaction = current.get() ?: fault("STM operation outside atomically#")
    private fun valid(tx: Transaction): Boolean = tx.entries.all { (cell, entry) -> cell.revision === entry.revision }
    private fun validate(tx: Transaction) {
        live()
        if (!valid(tx)) throw STMConflict
    }
    private fun entry(tx: Transaction, cell: ManagedTVar): Entry = tx.entries.getOrPut(cell) {
        Entry(cell.revision, cell.value, cell.value, false)
    }

    @TruffleBoundary fun newTVar(value: Any?): ManagedTVar = lock.withLock {
        live()
        ManagedTVar(this, value).also { cells[it] = true }
    }
    @TruffleBoundary(transferToInterpreterOnException = false)
    fun read(reference: Any?): Any? = lock.withLock {
        val cell = cell(reference)
        val tx = transaction()
        // Validate on reads as well as commit: an inconsistent snapshot must not
        // escape into arbitrary pure guest computation before a later commit check.
        validate(tx)
        entry(tx, cell).value
    }
    @TruffleBoundary fun readIO(reference: Any?): Any? = lock.withLock {
        live()
        cell(reference).value // Even inside unsafeIOToSTM this observes committed storage.
    }
    @TruffleBoundary(transferToInterpreterOnException = false)
    fun write(reference: Any?, value: Any?) = lock.withLock {
        val cell = cell(reference)
        val tx = transaction()
        validate(tx)
        entry(tx, cell).also { it.value = value; it.written = true }
        Unit
    }
    @TruffleBoundary(transferToInterpreterOnException = false)
    fun retry(): Nothing {
        lock.withLock { validate(transaction()) }
        throw STMRetry
    }

    @TruffleBoundary private fun begin(): Transaction = lock.withLock {
        live()
        Transaction().also { current.set(it) }
    }
    @TruffleBoundary private fun restore(tx: Transaction?) {
        if (tx == null) current.remove() else current.set(tx)
    }
    @TruffleBoundary(transferToInterpreterOnException = false)
    private fun commit(tx: Transaction) = lock.withLock {
        validate(tx)
        var changed = false
        for ((cell, entry) in tx.entries) {
            if (entry.written && cell.value !== entry.value) {
                cell.value = entry.value
                cell.revision = Any() // Identity avoids version-counter wraparound/ABA.
                changed = true
            }
        }
        if (changed) waiters.forEach { it.changedLocked() }
    }
    @TruffleBoundary private fun validException(tx: Transaction): Boolean = lock.withLock { live(); valid(tx) }
    @TruffleBoundary private fun await(tx: Transaction, node: Node?, async: Boolean) {
        val request = RetryWait(tx, if (async) node else null)
        try {
            if (node == null) request.await() // Direct protocol tests, same wait state machine.
            else TruffleSafepoint.setBlockedThreadInterruptibleFunction(node, awaitRetry, request)
        } finally { request.cancel() }
    }

    // Inline callbacks before bytecode generation: a closure capturing the caller's
    // VirtualFrame must not become a loop-carried heap object across retries.
    // Only log/storage operations cross opaque Truffle boundaries.
    internal inline fun <T> atomically(node: Node?, crossinline nested: () -> Nothing, async: Boolean = false,
        crossinline action: () -> T): T {
        if (hasTransaction()) nested()
        while (true) {
            val tx = begin()
            try {
                val result = action()
                commit(tx)
                return result
            } catch (_: STMConflict) {
                // Only transactional effects are replayed; no lock spans action().
            } catch (_: STMRetry) {
                restore(null)
                await(tx, node, async)
            } catch (failure: GuestException) {
                // GHC validates before raising out of atomically: a stale snapshot
                // may have produced an exception that a fresh execution never raises.
                if (validException(tx)) throw failure
            } finally { restore(null) }
        }
    }

    /** Abort a nested frame but retain all its reads, as stmAbortTransaction does. */
    @TruffleBoundary private fun abort(parent: Transaction, child: Transaction) {
        for ((cell, entry) in child.entries) {
            parent.entries.putIfAbsent(cell, Entry(entry.revision, entry.original, entry.original, false))
        }
    }
    @TruffleBoundary private fun parent(): Transaction = transaction()
    internal inline fun <T> orElse(crossinline action: () -> T, crossinline alternative: () -> T): T {
        val parent = parent()
        return try { nested(parent, action) }
        catch (_: STMRetry) { nested(parent, alternative) }
    }
    internal inline fun <T> catchSTM(crossinline action: () -> T, crossinline handler: (Any?) -> T): T {
        val parent = parent()
        return try { nested(parent, action) }
        catch (failure: GuestException) { handler(failure.payload) }
    }
    @TruffleBoundary private fun beginNested(parent: Transaction): Transaction =
        Transaction(IdentityHashMap<ManagedTVar, Entry>().also { entries ->
            parent.entries.forEach { (cell, entry) -> entries[cell] = entry.copy() }
        }).also { current.set(it) }
    @TruffleBoundary(transferToInterpreterOnException = false)
    private fun commitNested(parent: Transaction, child: Transaction) {
        lock.withLock { validate(child) }
        parent.entries.clear()
        parent.entries.putAll(child.entries)
    }
    private inline fun <T> nested(parent: Transaction, crossinline action: () -> T): T {
        val child = beginNested(parent)
        try {
            val result = action()
            commitNested(parent, child)
            return result
        } catch (failure: Throwable) {
            abort(parent, child)
            throw failure
        } finally { restore(parent) }
    }

    private inner class RetryWait(tx: Transaction, private val checkpoint: Node?) {
        private val versions = IdentityHashMap<ManagedTVar, Any>().also { versions ->
            tx.entries.forEach { (cell, entry) -> versions[cell] = entry.revision }
        }
        private val ready = lock.newCondition()
        private var submitted = false
        private var changed = false
        fun changedLocked() {
            if (versions.any { (cell, revision) -> cell.revision !== revision }) {
                changed = true
                ready.signalAll()
            }
        }
        @Throws(InterruptedException::class) fun await(): Any {
            lock.lockInterruptibly()
            try {
                live()
                if (!submitted) {
                    submitted = true
                    changedLocked()
                    if (!changed) waiters.add(this)
                }
                while (!changed) {
                    live()
                    // The log was already discarded. A cancelled wait restarts
                    // atomically, never the suffix following retry#.
                    checkpoint?.let { GuestThreads.pollCurrent(it, true) }?.let {
                        waiters.remove(this)
                        versions.clear()
                        throw AsyncBlocked(it, checkpoint)
                    }
                    GuestThreads.blocking(GuestThreadStatus.STM).use { ready.await() }
                }
                live()
                return Unit
            } finally { lock.unlock() }
        }
        fun cancel() = lock.withLock { waiters.remove(this); versions.clear() }
        fun closeLocked() { ready.signalAll() }
    }
    internal fun pendingWaiters(): Int = lock.withLock { waiters.size }
    @TruffleBoundary internal fun hasTransaction(): Boolean = current.get() != null
    @TruffleBoundary override fun close() = lock.withLock {
        closed = true
        cells.keys.forEach { it.value = null }
        cells.clear()
        waiters.forEach { it.closeLocked() }
    }
    companion object {
        private val awaitRetry = TruffleSafepoint.InterruptibleFunction<RetryWait, Any> { it.await() }
    }
}
