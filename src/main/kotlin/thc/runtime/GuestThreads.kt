// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
import com.oracle.truffle.api.ThreadLocalAction
import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.TruffleSafepoint
import com.oracle.truffle.api.nodes.Node
import thc.Language
import java.util.ArrayDeque

/** Guest ThreadId is the Java thread ID. Entries retain their Thread until guest completion. */
internal class GuestThreads internal constructor(
    private val maskingState: ThreadLocal<MaskingState>,
    private val wake: (Thread) -> Unit
) {
    constructor(env: TruffleLanguage.Env, maskingState: ThreadLocal<MaskingState>) : this(
        maskingState,
        { target ->
            env.submitThreadLocal(arrayOf(target), object : ThreadLocalAction(true, false) {
                // Only wake the target's safepoint. An async exception needs a saved guest cut.
                override fun perform(access: Access) = Unit
            })
        }
    )

    private class GuestThread(val thread: Thread) {
        val queue = ArrayDeque<AsyncRequest>()
        var claimed: AsyncRequest? = null
        var entries = 0
        @Volatile var pending = false
    }

    private val threads = HashMap<Long, GuestThread>()
    private val currentSlot = ThreadLocal<GuestThread?>()
    private var closed = false

    @TruffleBoundary @Synchronized fun enterCurrent(inheritedMask: MaskingState? = null): Long {
        check(!closed) { "Guest context has closed" }
        val current = Thread.currentThread()
        val id = current.threadId()
        val prior = threads[id]
        check(prior == null || prior.thread === current) { "Java thread ID was reused before guest completion" }
        if (prior == null && inheritedMask != null) maskingState.set(inheritedMask)
        val slot = prior ?: GuestThread(current).also { threads[id] = it }
        slot.entries++
        currentSlot.set(slot)
        return id
    }

    fun registerCurrent(): Long = enterCurrent()

    /** myThreadId# observes an existing guest entry; it never creates a new lifetime. */
    fun currentId(): Long = currentSlot.get()?.takeIf { it.entries > 0 }?.thread?.threadId()
        ?: fault("Current Java thread has not entered this guest context")

    /** The sender waits on the returned token; mere safepoint observation is not delivery. */
    @TruffleBoundary fun send(targetId: Long, payload: Any?): AsyncRequest {
        val request = synchronized(this) {
            check(!closed) { "Guest context has closed" }
            val target = threads[targetId]
                ?: return@synchronized AsyncRequest(this, targetId, null, payload).also {
                    it.transition(AsyncRequestState.TARGET_FINISHED)
                }
            AsyncRequest(this, targetId, target.thread, payload).also {
                target.queue.addLast(it)
                target.pending = target.claimed == null
            }
        }
        val thread = request.target ?: return request
        try {
            wake(thread)
        } catch (failure: Throwable) {
            request.fail(failure)
            throw failure
        }
        return request
    }

    /** Called only at a real continuation cut; masked requests stay queued in FIFO order. */
    fun poll(node: Node, interruptible: Boolean = false): AsyncRequest? {
        val slot = currentSlot.get() ?: return null
        if (!slot.pending) return null
        return claim(slot, node, interruptible)
    }

    @TruffleBoundary @Synchronized private fun claim(
        target: GuestThread,
        @Suppress("UNUSED_PARAMETER") node: Node,
        interruptible: Boolean
    ): AsyncRequest? {
        if (closed) return null
        val current = Thread.currentThread()
        if (target.thread !== current || threads[current.threadId()] !== target ||
            target.claimed != null || target.queue.isEmpty()) return null
        val allowed = when (maskingState.get()) {
            MaskingState.UNMASKED -> true
            MaskingState.MASKED_INTERRUPTIBLE -> interruptible
            MaskingState.MASKED_UNINTERRUPTIBLE -> false
        }
        if (!allowed) return null
        val request = target.queue.first()
        check(request.state == AsyncRequestState.PENDING)
        request.transition(AsyncRequestState.CLAIMED)
        target.claimed = request
        target.pending = false
        return request
    }

    /** Called by the target in the same finally block that ends its guest action. */
    @TruffleBoundary fun leaveCurrent() {
        val finished = synchronized(this) {
            val current = Thread.currentThread()
            val target = threads[current.threadId()]
            if (target == null) return@synchronized emptyList<AsyncRequest>()
            check(target.thread === current) { "Guest completion ran on a different Java thread" }
            check(target.entries > 0)
            if (--target.entries != 0) return@synchronized emptyList<AsyncRequest>()
            threads.remove(current.threadId())
            target.pending = false
            target.queue.toList().also { target.queue.clear(); target.claimed = null }
        }
        if (currentSlot.get()?.entries == 0) {
            currentSlot.remove()
            maskingState.remove()
        }
        finished.forEach { it.finish(AsyncRequestState.TARGET_FINISHED) }
    }

    fun completeCurrent() = leaveCurrent()

    @TruffleBoundary fun close() {
        val remaining = synchronized(this) {
            closed = true
            threads.values.flatMap { slot ->
                slot.entries = 0
                slot.pending = false
                slot.queue.toList().also { slot.queue.clear(); slot.claimed = null }
            }.also { threads.clear() }
        }
        remaining.forEach { it.finish(AsyncRequestState.TARGET_FINISHED) }
    }

    @Synchronized internal fun finish(request: AsyncRequest, state: AsyncRequestState,
                                      cause: Throwable? = null): Boolean {
        val thread = request.target ?: return false
        val target = threads[request.targetId] ?: return false
        if (target.thread !== thread || request !in target.queue) return false
        if (state == AsyncRequestState.CANCELLED && request.state != AsyncRequestState.PENDING)
            return false
        if (state == AsyncRequestState.ACKNOWLEDGED && request.state != AsyncRequestState.CLAIMED)
            error("Async request was not claimed by its target")
        if (state == AsyncRequestState.FAILED && request.state !in setOf(
                AsyncRequestState.PENDING, AsyncRequestState.CLAIMED)) return false
        target.queue.remove(request)
        if (target.claimed === request) target.claimed = null
        target.pending = target.claimed == null && target.queue.isNotEmpty()
        request.failure = cause
        request.transition(state)
        return true
    }

    companion object {
        /** Java-callable poll for bytecode roots; it never delivers from a wake action. */
        @JvmStatic fun pollCurrent(node: Node, interruptible: Boolean): AsyncRequest? =
            Language.currentState(node).threads.poll(node, interruptible)
    }
}

internal enum class AsyncRequestState { PENDING, CLAIMED, ACKNOWLEDGED, CANCELLED, TARGET_FINISHED, FAILED }

/** A pending throwTo payload. Only the target's real catch handler may acknowledge it. */
internal class AsyncRequest internal constructor(
    private val owner: GuestThreads,
    val targetId: Long,
    val target: Thread?,
    val payload: Any?
) {
    private val monitor = java.lang.Object()
    @Volatile var state = AsyncRequestState.PENDING
        private set
    @Volatile var failure: Throwable? = null
        internal set

    internal fun transition(next: AsyncRequestState) = synchronized(monitor) {
        state = next
        monitor.notifyAll()
    }

    @TruffleBoundary fun acknowledge() {
        check(owner.finish(this, AsyncRequestState.ACKNOWLEDGED)) { "Async request is no longer active" }
    }

    @TruffleBoundary fun cancel(): Boolean = owner.finish(this, AsyncRequestState.CANCELLED)

    @TruffleBoundary fun fail(cause: Throwable? = null) {
        owner.finish(this, AsyncRequestState.FAILED, cause)
    }

    internal fun finish(next: AsyncRequestState) = transition(next)

    /** Blocking send completion is interruptible and revokes only an unclaimed request. */
    @TruffleBoundary fun await(node: Node): AsyncRequestState = try {
        TruffleSafepoint.setBlockedThreadInterruptibleFunction(node, waitForTerminal, this)
    } catch (failure: Throwable) {
        cancel()
        throw failure
    }

    private fun waitForTerminal(): AsyncRequestState = synchronized(monitor) {
        while (state == AsyncRequestState.PENDING || state == AsyncRequestState.CLAIMED)
            monitor.wait()
        state
    }

    companion object {
        private val waitForTerminal = TruffleSafepoint.InterruptibleFunction<AsyncRequest, AsyncRequestState> {
            it.waitForTerminal()
        }
    }
}
