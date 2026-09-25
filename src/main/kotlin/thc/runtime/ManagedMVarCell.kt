// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
import com.oracle.truffle.api.TruffleSafepoint
import com.oracle.truffle.api.nodes.Node
import java.util.ArrayDeque
import java.util.concurrent.CancellationException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal class MVarReadResult(val present: Boolean, val value: Any?)

/**
 * Managed MVar storage, not a guest scheduler or an implementation of throwTo/masking.
 *
 * The lock orders cell changes, queue removal, and request commitment. A wakeup merely
 * delivers an already committed result: it never asks the awakened thread to compete
 * for the cell again. No guest code, payload equality, or payload evaluation runs while
 * holding the lock. In particular, null is a value, not the empty-cell marker.
 */
internal class ManagedMVar {
    private val lock = ReentrantLock()
    private var full = false
    private var value: Any? = null
    private val takers = ArrayDeque<Request>()
    private val readers = ArrayDeque<Request>()
    private val putters = ArrayDeque<Request>()

    companion object {
        @JvmStatic fun require(value: Any?): ManagedMVar = value as? ManagedMVar
            ?: throw RuntimeFault("Expected managed MVar#")

        private val awaitRequest = TruffleSafepoint.InterruptibleFunction<Request, Any?> { it.await() }
    }

    @JvmOverloads @TruffleBoundary fun take(node: Node, async: Boolean = false): Any? =
        awaitAt(Request(Operation.TAKE, checkpoint = if (async) node else null), node)

    @JvmOverloads @TruffleBoundary fun read(node: Node, async: Boolean = false): Any? =
        awaitAt(Request(Operation.READ, checkpoint = if (async) node else null), node)

    @JvmOverloads @TruffleBoundary fun put(value: Any?, node: Node, async: Boolean = false) {
        awaitAt(Request(Operation.PUT, value, if (async) node else null), node)
    }

    private fun awaitAt(request: Request, node: Node): Any? {
        try {
            // The callback can be retried after any harmless safepoint interruption.
            // Registration and the operation itself must therefore belong to the token,
            // not to the callback invocation. Lock acquisition is interruptible too.
            return TruffleSafepoint.setBlockedThreadInterruptibleFunction(node, awaitRequest, request)
        } finally {
            // Includes a terminal safepoint failure before registration, during a wait,
            // or after commitment. Only a still-pending request can be revoked.
            request.cancel()
        }
    }

    @TruffleBoundary fun tryTake(): MVarReadResult = lock.withLock {
        if (full) MVarReadResult(true, takeLocked()) else MVarReadResult(false, null)
    }

    @TruffleBoundary fun tryRead(): MVarReadResult = lock.withLock {
        MVarReadResult(full, if (full) value else null)
    }

    @TruffleBoundary fun tryPut(value: Any?): Boolean = lock.withLock {
        if (full) false else {
            putLocked(value)
            true
        }
    }

    @TruffleBoundary fun isEmpty(): Boolean = lock.withLock { !full }

    private fun takeLocked(): Any? {
        check(full)
        val result = value
        val putter = putters.pollFirst()
        if (putter == null) {
            full = false
            value = null
        } else {
            // Transfer the oldest blocked put before either participant can resume.
            value = putter.offeredValueLocked()
            putter.commitLocked(null)
        }
        return result
    }

    private fun putLocked(offered: Any?) {
        check(!full)
        // GHC's read waiters all observe the next value, including readers that
        // arrived after a taker. Only then may the oldest taker consume that value.
        while (readers.isNotEmpty()) readers.removeFirst().commitLocked(offered)
        val taker = takers.pollFirst()
        if (taker == null) {
            value = offered
            full = true
        } else {
            taker.commitLocked(offered)
        }
    }

    internal enum class Operation { TAKE, READ, PUT }
    internal enum class RequestState { PENDING, COMMITTED, CANCELLED }
    internal data class PendingCounts(val takers: Int, val readers: Int, val putters: Int)

    /** Stable request identity is also the small seam used by direct protocol tests. */
    internal inner class Request internal constructor(
        private val operation: Operation,
        offered: Any? = null,
        private val checkpoint: Node? = null,
    ) {
        private val completed = lock.newCondition()
        private var status = RequestState.PENDING
        private var submitted = false
        private var queued = false
        private var offeredValue: Any? = offered
        private var result: Any? = null

        val state: RequestState get() = lock.withLock { status }
        val isQueued: Boolean get() = lock.withLock { queued }
        internal fun pendingPutValue(): Any? = lock.withLock { offeredValue }

        internal fun submitLocked() {
            check(lock.isHeldByCurrentThread)
            if (submitted || status != RequestState.PENDING) return
            submitted = true
            when (operation) {
                Operation.TAKE -> if (full) commitLocked(takeLocked()) else enqueueLocked(takers)
                Operation.READ -> if (full) commitLocked(value) else enqueueLocked(readers)
                Operation.PUT -> if (full) enqueueLocked(putters) else {
                    putLocked(offeredValue)
                    commitLocked(null)
                }
            }
        }

        private fun enqueueLocked(queue: ArrayDeque<Request>) {
            queue.addLast(this)
            queued = true
        }

        internal fun offeredValueLocked(): Any? {
            check(lock.isHeldByCurrentThread && status == RequestState.PENDING)
            return offeredValue
        }

        internal fun commitLocked(value: Any?) {
            check(lock.isHeldByCurrentThread && status == RequestState.PENDING)
            result = value
            offeredValue = null
            queued = false
            status = RequestState.COMMITTED
            completed.signalAll()
        }

        /** InterruptedException leaves the same request queued at the same position. */
        @Throws(InterruptedException::class)
        internal fun await(): Any? {
            lock.lockInterruptibly()
            try {
                submitLocked()
                while (status == RequestState.PENDING) {
                    val interruption = checkpoint?.let { GuestThreads.pollCurrent(it, true) }
                    if (interruption != null) {
                        // Cell commitment and cancellation share this lock. A
                        // completed take/put always returns its answer; only an
                        // uncommitted request can be retried by the continuation.
                        check(cancel())
                        throw AsyncBlocked(interruption, checkpoint)
                    }
                    GuestThreads.blocking(if (operation == Operation.READ) GuestThreadStatus.MVAR_READ
                        else GuestThreadStatus.MVAR).use { completed.await() }
                }
                if (status == RequestState.CANCELLED) throw CancellationException("Managed MVar request cancelled")
                return result
            } finally {
                lock.unlock()
            }
        }

        /** A terminal caller may revoke Pending, never roll back Committed. */
        internal fun cancel(): Boolean = lock.withLock {
            if (status != RequestState.PENDING) return@withLock false
            if (queued) {
                val queue = when (operation) {
                    Operation.TAKE -> takers
                    Operation.READ -> readers
                    Operation.PUT -> putters
                }
                check(queue.remove(this))
            }
            queued = false
            offeredValue = null
            result = null
            status = RequestState.CANCELLED
            completed.signalAll()
            true
        }

        /** Bounded observer for deterministic tests; never used by guest execution. */
        internal fun hasWaitingThread(): Boolean = lock.withLock { lock.hasWaiters(completed) }
    }

    // Direct tests can register without an entered Truffle context. Production uses
    // interruptible registration inside Request.await, not these raw entry points.
    internal fun beginTake(): Request = lock.withLock { Request(Operation.TAKE).also { it.submitLocked() } }
    internal fun beginRead(): Request = lock.withLock { Request(Operation.READ).also { it.submitLocked() } }
    internal fun beginPut(value: Any?): Request = lock.withLock { Request(Operation.PUT, value).also { it.submitLocked() } }
    internal fun pendingCounts(): PendingCounts = lock.withLock { PendingCounts(takers.size, readers.size, putters.size) }
}
