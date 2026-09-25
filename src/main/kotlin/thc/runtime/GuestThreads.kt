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
import java.util.WeakHashMap
import java.lang.ref.WeakReference

/** An unlifted ThreadId# is the actual JVM thread ID scoped to its owning context. */
internal class GuestThreadId(val javaId: Long, val owner: GuestThreads, val capability: Long,
                             carrier: Thread, internal val forked: Boolean) {
    internal val carrier = WeakReference(carrier)
    @Volatile internal var status = GuestThreadStatus.RUNNING
    @Volatile internal var lastOutcome = GuestThreadStatus.FINISHED
    override fun equals(other: Any?): Boolean =
        other is GuestThreadId && owner === other.owner && javaId == other.javaId
    override fun hashCode(): Int = 31 * System.identityHashCode(owner) + javaId.hashCode()
}

/** GHC 9.14.1 Constants.h why_blocked codes and PrimOps.cmm terminal overrides. */
internal enum class GuestThreadStatus(val code: Long) {
    RUNNING(0), MVAR(1), BLACK_HOLE(2), READ(3), WRITE(4), FOREIGN(10), THROW_TO(12), MVAR_READ(14),
    FINISHED(16), DIED(17), RUNTIME_FAILURE(-1);

    val terminal: Boolean get() = this == FINISHED || this == DIED || this == RUNTIME_FAILURE
    companion object {
        fun uncaught(failure: Throwable): GuestThreadStatus =
            if (failure is GuestException || failure is ForeignCallbackAsyncFailure) DIED else RUNTIME_FAILURE
    }
}

/** A lexical blocking extent follows guest re-entry and is restored even on an unwind. */
internal class GuestThreadExtent internal constructor(private val identity: GuestThreadId?,
                                                       private val previous: GuestThreadStatus?) : AutoCloseable {
    override fun close() {
        if (identity != null && !identity.status.terminal) identity.status = checkNotNull(previous)
    }
}

/** Active guest entries own one logical capability per Java carrier, scoped to this context. */
internal class GuestThreads internal constructor(
    private val maskingState: ThreadLocal<MaskingState>,
    private val wake: (Thread) -> Unit
) {
    /** This is execution permission, not the observable Haskell masking state. */
    internal enum class DeliveryPermission { NONE, GUEST, FOREIGN }

    private class DeliveryState {
        var permission = DeliveryPermission.NONE
        val guestPrevious = ArrayDeque<DeliveryPermission>()
        val foreignStatus = ArrayDeque<GuestThreadExtent>()
    }

    constructor(env: TruffleLanguage.Env, maskingState: ThreadLocal<MaskingState>) : this(
        maskingState,
        { target ->
            env.submitThreadLocal(arrayOf(target), object : ThreadLocalAction(true, false) {
                // Only wake the target's safepoint. An async exception needs a saved guest cut.
                override fun perform(access: Access) = Unit
            })
        }
    )

    private class GuestThread(val thread: Thread, val identity: GuestThreadId) {
        val entriesPrevious = ArrayDeque<GuestEntry>()
        val queue = ArrayDeque<AsyncRequest>()
        var claimed: AsyncRequest? = null
        var entries = 0
        @Volatile var pending = false
    }

    private class GuestEntry(val active: GuestThreadId?, val status: GuestThreadStatus)
    private val threads = HashMap<Long, GuestThread>()
    // Weak keys release dead Java carriers. Numbers are never recycled, so a
    // retained finished ThreadId still names its original logical capability.
    private val identities = WeakHashMap<Thread, GuestThreadId>()
    // The RTS registration retains the Weak# capability, never its ThreadId#
    // key or a numeric Java-thread snapshot. Signal delivery is not admitted yet.
    private var mainThreadWeak: MainThreadWeakKey? = null
    private var allocatedCapabilities = 0L
    @TruffleBoundary @Synchronized internal fun capabilityCount(): Long {
        if (closed) fault("Guest context has closed")
        return allocatedCapabilities
    }
    @Synchronized fun registerMainThread(key: MainThreadWeakKey) {
        check(!closed) { "Guest context has closed" }
        mainThreadWeak = key
    }
    @Synchronized fun mainThreadRegistration(): MainThreadWeakKey? = mainThreadWeak
    private val currentSlot = ThreadLocal<GuestThread?>()
    private val delivery = ThreadLocal<DeliveryState?>()
    private var closed = false

    private fun deliveryState(): DeliveryState = delivery.get() ?: DeliveryState().also { delivery.set(it) }

    /** A foreign call may run before a guest thread has registered with this context. */
    @TruffleBoundary fun enterForeign(): DeliveryPermission {
        val state = deliveryState()
        val previous = state.permission
        state.foreignStatus.addLast(enterStatus(currentSlot.get()?.identity, GuestThreadStatus.FOREIGN))
        state.permission = DeliveryPermission.FOREIGN
        foreignExtents.set((foreignExtents.get() ?: 0) + 1)
        return previous
    }

    @TruffleBoundary fun leaveForeign(previous: DeliveryPermission) {
        val state = delivery.get() ?: error("Foreign execution has no delivery state")
        check(state.permission == DeliveryPermission.FOREIGN) { "Foreign execution exited across a guest entry" }
        val depth = foreignExtents.get() ?: error("Foreign execution has no Java-thread origin")
        check(depth > 0)
        if (depth == 1) foreignExtents.remove() else foreignExtents.set(depth - 1)
        state.permission = previous
        state.foreignStatus.removeLast().close()
        if (previous == DeliveryPermission.NONE && state.guestPrevious.isEmpty()) delivery.remove()
    }

    private fun enterGuestPermission() {
        val state = deliveryState()
        state.guestPrevious.addLast(state.permission)
        state.permission = DeliveryPermission.GUEST
    }

    private fun leaveGuestPermission() {
        val state = delivery.get() ?: error("Guest entry has no delivery state")
        check(state.permission == DeliveryPermission.GUEST && state.guestPrevious.isNotEmpty()) {
            "Guest entry exited across foreign execution"
        }
        state.permission = state.guestPrevious.removeLast()
        if (state.permission == DeliveryPermission.NONE && state.guestPrevious.isEmpty()) delivery.remove()
    }

    /** An uncaught callback cannot carry its opaque foreign caller as a guest continuation. */
    internal fun inForeignCallback(): Boolean {
        val state = delivery.get() ?: return false
        // Another THC context may own the opaque Java frame. This process-wide
        // thread-local tags origin only; it never grants delivery in this context.
        return state.permission == DeliveryPermission.GUEST && (foreignExtents.get() ?: 0) > 0
    }

    @TruffleBoundary @Synchronized fun enterCurrent(inheritedMask: MaskingState? = null, forked: Boolean = false): Long {
        check(!closed) { "Guest context has closed" }
        val current = Thread.currentThread()
        val id = current.threadId()
        val prior = threads[id]
        check(prior == null || prior.thread === current) { "Java thread ID was reused before guest completion" }
        if (prior == null && inheritedMask != null) maskingState.set(inheritedMask)
        val identity = identities.getOrPut(current) {
            GuestThreadId(id, this, allocatedCapabilities++, current, forked)
        }
        check(!identity.status.terminal) { "Terminated guest Java thread re-entered" }
        val slot = prior ?: GuestThread(current, identity).also { threads[id] = it }
        slot.entriesPrevious.addLast(GuestEntry(activeIdentity.get(), slot.identity.status))
        slot.identity.status = GuestThreadStatus.RUNNING
        activeIdentity.set(slot.identity)
        slot.entries++
        currentSlot.set(slot)
        enterGuestPermission()
        return id
    }

    fun registerCurrent(): Long = enterCurrent()

    /** myThreadId# observes an existing guest entry; it never creates a new lifetime. */
    fun currentId(): Long = currentIdentity().javaId

    fun currentIdentity(): GuestThreadId = currentSlot.get()?.takeIf { it.entries > 0 }?.identity
        ?: fault("Current Java thread has not entered this guest context")

    @TruffleBoundary @Synchronized fun status(identity: GuestThreadId): GuestThreadStatus {
        if (closed) fault("Guest context has closed")
        if (identity.owner !== this) fault("ThreadId# belongs to another guest context")
        if (!identity.status.terminal && identity.carrier.get()?.isAlive != true)
            identity.status = identity.lastOutcome
        return identity.status.also {
            if (it == GuestThreadStatus.RUNTIME_FAILURE)
                fault("ThreadId# terminated because of a runtime failure")
        }
    }

    /** Snapshot only: eventual dispatch must check the same identity atomically with enqueue. */
    @TruffleBoundary @Synchronized internal fun liveJavaId(identity: GuestThreadId): Long? {
        if (identity.owner !== this) fault("ThreadId# belongs to another guest context")
        if (closed || identity.status.terminal) return null
        val carrier = identity.carrier.get() ?: return null
        if (!carrier.isAlive || carrier.threadId() != identity.javaId || identities[carrier] !== identity) return null
        val active = threads[identity.javaId]
        if (active != null && (active.identity !== identity || active.thread !== carrier)) return null
        // A live host carrier can be FOREIGN between guest entries; no new lifetime is registered.
        return identity.javaId
    }

    @TruffleBoundary fun send(identity: GuestThreadId, payload: Any?): AsyncRequest {
        if (identity.owner !== this) fault("ThreadId# belongs to another guest context")
        return send(identity.javaId, payload)
    }

    /** The sender waits on the returned token; mere safepoint observation is not delivery. */
    @TruffleBoundary fun send(targetId: Long, payload: Any?): AsyncRequest {
        val request = synchronized(this) {
            check(!closed) { "Guest context has closed" }
            val target = threads[targetId]
                ?: return@synchronized AsyncRequest(this, targetId, null, payload).also {
                    it.transition(AsyncRequestState.TARGET_FINISHED)
                }
            val self = target.thread === Thread.currentThread()
            AsyncRequest(this, targetId, target.thread, payload, self).also {
                if (self) target.queue.addFirst(it) else target.queue.addLast(it)
                target.pending = target.claimed == null
            }
        }
        val thread = request.target ?: return request
        if (request.forceSelf) return request
        try {
            wake(thread)
        } catch (failure: Throwable) {
            // Completion may race the wake. A dead target is a successful
            // no-op for throwTo, even if its last wake was rejected.
            if (request.fail(failure)) throw failure
        }
        return request
    }

    /** Called only at a real continuation cut; masked requests stay queued in FIFO order. */
    fun poll(node: Node, interruptible: Boolean = false): AsyncRequest? {
        val slot = currentSlot.get() ?: return null
        if (!slot.pending) return null
        if (delivery.get()?.permission != DeliveryPermission.GUEST) return null
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
            delivery.get()?.permission != DeliveryPermission.GUEST ||
            target.claimed != null || target.queue.isEmpty()) return null
        val request = target.queue.first()
        val allowed = request.forceSelf || when (maskingState.get()) {
            MaskingState.UNMASKED -> true
            MaskingState.MASKED_INTERRUPTIBLE -> interruptible
            MaskingState.MASKED_UNINTERRUPTIBLE -> false
        }
        if (!allowed) return null
        check(request.state == AsyncRequestState.PENDING)
        request.transition(AsyncRequestState.CLAIMED)
        target.claimed = request
        target.pending = false
        return request
    }

    /** Called by the target in the same finally block that ends its guest action. */
    @TruffleBoundary fun leaveCurrent(outcome: GuestThreadStatus = GuestThreadStatus.FINISHED) {
        require(outcome.terminal)
        leaveGuestPermission()
        val finished = synchronized(this) {
            val current = Thread.currentThread()
            val target = currentSlot.get()
            if (target == null) return@synchronized emptyList<AsyncRequest>()
            check(target.thread === current) { "Guest completion ran on a different Java thread" }
            check(target.entries > 0)
            val previous = target.entriesPrevious.removeLast()
            if (previous.active == null) activeIdentity.remove() else activeIdentity.set(previous.active)
            if (--target.entries != 0) {
                if (!closed) target.identity.status = previous.status
                return@synchronized emptyList<AsyncRequest>()
            }
            target.identity.lastOutcome = outcome
            if (!closed) target.identity.status = if (target.identity.forked) outcome else GuestThreadStatus.FOREIGN
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
            mainThreadWeak = null
            identities.values.forEach { it.status = GuestThreadStatus.RUNTIME_FAILURE }
            threads.values.flatMap { slot ->
                slot.identity.status = GuestThreadStatus.RUNTIME_FAILURE
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
        if (target.thread !== thread) return false
        val queued = request in target.queue
        if (!queued && request.state != AsyncRequestState.PAUSED) return false
        if (state == AsyncRequestState.CANCELLED && request.state !in setOf(
                AsyncRequestState.PENDING, AsyncRequestState.PAUSED))
            return false
        if (state == AsyncRequestState.ACKNOWLEDGED && request.state != AsyncRequestState.CLAIMED)
            error("Async request was not claimed by its target")
        // A wake can fail after the target has independently claimed the request.
        // Claim commits delivery; a late wake failure must not revoke its ACK.
        if (state == AsyncRequestState.FAILED && request.state != AsyncRequestState.PENDING)
            return false
        if (queued) target.queue.remove(request)
        if (target.claimed === request) target.claimed = null
        target.pending = target.claimed == null && target.queue.isNotEmpty()
        request.failure = cause
        request.transition(state)
        return true
    }

    /** Remove an uncommitted outbound throwTo while its sender handles an async exception. */
    @Synchronized internal fun pause(request: AsyncRequest): Boolean {
        val target = threads[request.targetId] ?: return false
        if (target.thread !== request.target || request.state != AsyncRequestState.PENDING ||
            !target.queue.remove(request)) return false
        target.pending = target.claimed == null && target.queue.isNotEmpty()
        request.transition(AsyncRequestState.PAUSED)
        return true
    }

    /** Resume the same logical request after the sender's caught continuation resumes. */
    internal fun resume(request: AsyncRequest) {
        val thread = synchronized(this) {
            if (request.state != AsyncRequestState.PAUSED) return
            val target = threads[request.targetId]
            if (closed || target == null || target.thread !== request.target) {
                request.transition(AsyncRequestState.TARGET_FINISHED)
                return
            }
            target.queue.addLast(request)
            request.transition(AsyncRequestState.PENDING)
            target.pending = target.claimed == null
            target.thread
        }
        try { wake(thread) }
        catch (failure: Throwable) { if (request.fail(failure)) throw failure }
    }

    companion object {
        private val foreignExtents = ThreadLocal<Int?>()
        private val activeIdentity = ThreadLocal<GuestThreadId?>()

        private fun enterStatus(identity: GuestThreadId?, status: GuestThreadStatus): GuestThreadExtent {
            val previous = identity?.status
            if (identity != null && !identity.status.terminal) identity.status = status
            return GuestThreadExtent(identity, previous)
        }

        /** No guest entry means no Haskell thread to mark (also used by cell protocol tests). */
        internal fun blocking(status: GuestThreadStatus): GuestThreadExtent = enterStatus(activeIdentity.get(), status)

        /** Java-callable poll for bytecode roots; it never delivers from a wake action. */
        @JvmStatic fun pollCurrent(node: Node, interruptible: Boolean): AsyncRequest? =
            Language.currentState(node).threads.poll(node, interruptible)
    }
}

internal enum class AsyncRequestState { PENDING, CLAIMED, PAUSED, ACKNOWLEDGED, CANCELLED, TARGET_FINISHED, FAILED }

/** A pending throwTo payload. Only the target's real catch handler may acknowledge it. */
internal class AsyncRequest internal constructor(
    private val owner: GuestThreads,
    val targetId: Long,
    val target: Thread?,
    val payload: Any?,
    internal val forceSelf: Boolean = false
) {
    private val monitor = java.lang.Object()
    @Volatile var state = AsyncRequestState.PENDING
        private set
    @Volatile var failure: Throwable? = null
        internal set
    /** Set by the bytecode poll before crossing into the mailbox boundary. */
    @JvmField @Volatile var compiledCapture = false

    internal fun inForeignCallback(): Boolean = owner.inForeignCallback()

    internal fun transition(next: AsyncRequestState) = synchronized(monitor) {
        state = next
        monitor.notifyAll()
    }

    @TruffleBoundary fun acknowledge() {
        check(owner.finish(this, AsyncRequestState.ACKNOWLEDGED)) { "Async request is no longer active" }
    }

    @TruffleBoundary fun cancel(): Boolean = owner.finish(this, AsyncRequestState.CANCELLED)

    @TruffleBoundary fun fail(cause: Throwable? = null): Boolean =
        owner.finish(this, AsyncRequestState.FAILED, cause)

    internal fun finish(next: AsyncRequestState) = transition(next)

    /** Blocking send completion is interruptible and revokes only an unclaimed request. */
    @TruffleBoundary fun await(node: Node): AsyncRequestState = try {
        owner.resume(this)
        TruffleSafepoint.setBlockedThreadInterruptibleFunction(node,
            TruffleSafepoint.InterruptibleFunction<AsyncRequest, AsyncRequestState> {
                it.waitForTerminal(node)
            }, this)
    } catch (failure: Throwable) {
        if (failure !is AsyncBlocked) cancel()
        throw failure
    }

    private fun waitForTerminal(node: Node): AsyncRequestState {
        while (true) {
            val snapshot = state
            if (snapshot != AsyncRequestState.PENDING && snapshot != AsyncRequestState.CLAIMED &&
                snapshot != AsyncRequestState.PAUSED) return snapshot
            val incoming = owner.poll(node, interruptible = true)
            if (incoming != null) {
                owner.pause(this)
                throw AsyncBlocked(incoming, node)
            }
            synchronized(monitor) {
                if (state == AsyncRequestState.PENDING || state == AsyncRequestState.CLAIMED ||
                    state == AsyncRequestState.PAUSED)
                    GuestThreads.blocking(GuestThreadStatus.THROW_TO).use { monitor.wait() }
            }
        }
    }
}
