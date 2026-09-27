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

/** An unlifted ThreadId# names a guest lifetime, independently of its Java carrier. */
internal class GuestThreadId(val logicalId: Long, val owner: GuestThreads, @Volatile var capability: Long,
                             carrier: Thread, internal val forked: Boolean, internal val capabilityLocked: Boolean = false,
                             internal val callback: Boolean = false) {
    val javaId = carrier.threadId()
    internal val carrier = WeakReference(carrier)
    // Outcome of this fork's initial native request, not a perpetual OS promise.
    var affinityApplied = false
    @Volatile internal var status = GuestThreadStatus.RUNNING
    @Volatile internal var lastOutcome = GuestThreadStatus.FINISHED
    // Guarded by owner; host work outside outer guest entries is not charged.
    internal var allocationRemaining = 0L
    internal var allocationBaseline = -1L
    internal var allocationUnavailable = false
    internal var allocationSuspended = false
    override fun equals(other: Any?): Boolean =
        other is GuestThreadId && owner === other.owner && logicalId == other.logicalId
    override fun hashCode(): Int = 31 * System.identityHashCode(owner) + logicalId.hashCode()
}

/** GHC 9.14.1 Constants.h why_blocked codes and PrimOps.cmm terminal overrides. */
internal enum class GuestThreadStatus(val code: Long) {
    RUNNING(0), MVAR(1), BLACK_HOLE(2), READ(3), WRITE(4), DELAY(5), STM(6), FOREIGN(10), THROW_TO(12), MVAR_READ(14),
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

/** A carrier can suspend one guest in foreign code and enter a distinct bound callback. */
internal class GuestThreads internal constructor(
    private val maskingState: ThreadLocal<MaskingState>,
    val cpuAffinity: CpuAffinity = CpuAffinity.discover(false),
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
        CpuAffinity.discover(env.isNativeAccessAllowed),
        { target ->
            env.submitThreadLocal(arrayOf(target), object : ThreadLocalAction(true, false) {
                // Only wake the target's safepoint. An async exception needs a saved guest cut.
                override fun perform(access: Access) = Unit
            })
        }
    )

    internal class GuestThread(val thread: Thread, val identity: GuestThreadId, val externalAsync: Boolean) {
        val entriesPrevious = ArrayDeque<GuestEntry>()
        val queue = ArrayDeque<AsyncRequest>()
        var claimed: AsyncRequest? = null
        var entries = 0
        @Volatile var pending = false
    }

    internal class GuestEntry(val active: GuestThreadId?, val status: GuestThreadStatus, val astStack: AstStackScope,
                              val prior: GuestThread?, val mask: MaskingState)
    /** Stable per-context/carrier cell, including between nested guest entries. */
    internal class PollState {
        internal var current: GuestThread? = null
        internal var astStack = AstStackScope()
    }
    private val pollStates = WeakHashMap<Thread, PollState>()
    @TruffleBoundary @Synchronized internal fun pollState(thread: Thread): PollState =
        pollStates.getOrPut(thread) { PollState() }
    private val threads = HashMap<Long, GuestThread>()
    private var nextIdentity = 1L
    // Weak keys release dead Java carriers; retained IDs keep their assigned
    // capability, which other carriers may also use.
    private val identities = WeakHashMap<Thread, GuestThreadId>()
    // A retained ThreadId# keeps a finished guest observable even after its
    // Java carrier is collected. This registry itself retains neither.
    private val knownThreads = WeakHashMap<GuestThreadId, Unit>()
    // Like TSO.label, the exact ByteArray# stays live while its ThreadId# does.
    // Weak keys do not keep a terminated Java carrier or discarded ThreadId alive.
    private val labels = WeakHashMap<GuestThreadId, Any>()
    // The RTS registration retains the Weak# capability, never its ThreadId#
    // key or a numeric Java-thread snapshot. Signal delivery is not admitted yet.
    private var mainThreadWeak: MainThreadWeakKey? = null
    private var nextCapability = 0L
    private var logicalCapabilities = cpuAffinity.count.toLong()
    @TruffleBoundary @Synchronized internal fun capabilityCount(): Long {
        if (closed) fault("Guest context has closed")
        return logicalCapabilities
    }
    @TruffleBoundary @Synchronized internal fun setCapabilityCount(count: Long) {
        if (closed) fault("Guest context has closed")
        if (count !in 1L..0xffff_ffffL) fault("setNumCapabilities requires a positive Word32 count")
        logicalCapabilities = count
        nextCapability %= count
        // Keep retained ThreadId# observations in range, without changing the
        // initial affinity outcome or repinning live Java carriers.
        knownThreads.keys.forEach { it.capability %= count }
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

    @TruffleBoundary fun enterCurrent(inheritedMask: MaskingState? = null, forked: Boolean = false,
                                    externalAsync: Boolean = true, capability: Long? = null): Long {
        // Origin alone is not a declaration that an FFI call is safe. A future
        // foreign activation must authorize/reject reverse entry at this seam.
        val callback = delivery.get()?.permission != DeliveryPermission.GUEST && (foreignExtents.get() ?: 0) > 0
        val suspended = if (callback) activeIdentity.get() else null
        // Cross-context reverse entries must never acquire two registry locks.
        suspended?.owner?.pauseAllocation(suspended)
        try { return enterCurrentImpl(inheritedMask, forked, externalAsync, capability, callback) }
        catch (failure: Throwable) {
            suspended?.owner?.resumeAllocation(suspended)
            throw failure
        }
    }

    @Synchronized private fun enterCurrentImpl(inheritedMask: MaskingState?, forked: Boolean,
                                              externalAsync: Boolean, capability: Long?, callback: Boolean): Long {
        check(!closed) { "Guest context has closed" }
        val current = Thread.currentThread()
        val prior = currentSlot.get()
        val previousMask = maskingState.get()
        // A nested ordinary entry shares the guest. Only an actual reverse
        // foreign entry creates a new bound TSO, including cross-context entry.
        fun freshIdentity(): GuestThreadId {
            val selected = Math.floorMod(capability ?: nextCapability, logicalCapabilities)
            if (capability == null) nextCapability = (selected + 1L) % logicalCapabilities
            check(nextIdentity > 0L) { "Guest thread identity space exhausted" }
            return GuestThreadId(nextIdentity++, this, selected, current, forked, capability != null, callback)
                .also { knownThreads[it] = Unit }
        }
        val identity = if (callback) freshIdentity() else prior?.identity ?: identities.getOrPut(current, ::freshIdentity)
        check(!identity.status.terminal) { "Terminated guest Java thread re-entered" }
        if (callback) {
            // GHC's createIOThread callback starts unmasked even when the
            // suspended caller is masked. It never inherits that caller's queue.
            maskingState.set(MaskingState.UNMASKED)
        } else if (prior == null && inheritedMask != null) maskingState.set(inheritedMask)
        // Re-entry cannot turn a nonresumable fork into an async receiver.
        val slot = if (!callback && prior != null) prior else GuestThread(current, identity, externalAsync)
            .also { threads[identity.logicalId] = it }
        if (slot.entries == 0) {
            identity.allocationBaseline = GuestAllocationAccounting.sample(identity.javaId)
            if (identity.allocationBaseline < 0L) identity.allocationUnavailable = true
        }
        val poll = pollState(current)
        slot.entriesPrevious.addLast(GuestEntry(activeIdentity.get(), slot.identity.status, poll.astStack, prior, previousMask))
        poll.astStack = AstStackScope()
        slot.identity.status = GuestThreadStatus.RUNNING
        activeIdentity.set(slot.identity)
        slot.entries++
        currentSlot.set(slot)
        pollState(current).current = slot
        enterGuestPermission()
        return identity.logicalId
    }

    fun registerCurrent(): Long = enterCurrent()

    /** myThreadId# observes an existing guest entry; it never creates a new lifetime. */
    fun currentId(): Long = currentIdentity().logicalId

    fun currentIdentity(): GuestThreadId = currentSlot.get()?.takeIf { it.entries > 0 }?.identity
        ?: fault("Current Java thread has not entered this guest context")

    private fun settleAllocation(identity: GuestThreadId) {
        val end = GuestAllocationAccounting.sample(identity.javaId)
        if (end >= 0L && identity.allocationBaseline >= 0L)
            identity.allocationRemaining -= end - identity.allocationBaseline
        else identity.allocationUnavailable = true
        identity.allocationBaseline = -1L
    }

    @Synchronized private fun pauseAllocation(identity: GuestThreadId) {
        check(!identity.allocationSuspended)
        settleAllocation(identity)
        identity.allocationSuspended = true
    }

    @Synchronized private fun resumeAllocation(identity: GuestThreadId) {
        identity.allocationSuspended = false
        if (closed || identity.status.terminal) return
        identity.allocationBaseline = GuestAllocationAccounting.sample(identity.javaId)
        if (identity.allocationBaseline < 0L) identity.allocationUnavailable = true
    }

    @TruffleBoundary @Synchronized fun allocationCounter(): Long {
        if (closed) fault("Guest context has closed")
        val identity = currentIdentity()
        if (identity.allocationUnavailable || identity.allocationBaseline < 0L)
            fault("Thread allocation accounting was unavailable during this guest lifetime; reset it before reading")
        val allocated = GuestAllocationAccounting.sample(identity.javaId)
        if (allocated < 0L) {
            identity.allocationUnavailable = true
            fault("Thread allocation accounting is unavailable for this carrier")
        }
        return identity.allocationRemaining - (allocated - identity.allocationBaseline)
    }

    @TruffleBoundary @Synchronized fun setAllocationCounter(value: Long, identity: GuestThreadId = currentIdentity()) {
        if (closed) fault("Guest context has closed")
        if (identity.owner !== this || !knownThreads.containsKey(identity))
            fault("ThreadId# belongs to another guest context")
        // A completed or currently foreign-only carrier has no active allocation extent.
        if (!identity.allocationSuspended && threads[identity.logicalId]?.identity === identity)
            identity.allocationBaseline = GuestAllocationAccounting.bytes(identity.javaId)
        identity.allocationRemaining = value
        identity.allocationUnavailable = false
    }

    /** Independent Array# snapshot including retained completed identities.
     * Registry mutation and weak-key expunging share this lock. No guest code
     * or Java thread enumeration runs under it; ordering is unspecified. */
    @TruffleBoundary @Synchronized fun snapshot(): Array<Any?> {
        if (closed) fault("Guest context has closed")
        currentIdentity()
        return knownThreads.keys.toTypedArray<Any?>()
    }

    /** A reverse foreign entry is bound to its actual carrier for its lifetime.
     * Ordinary entries and fork# still make no forkOS/native-TLS promise. */
    @TruffleBoundary @Synchronized fun isCurrentBound(): Boolean {
        if (closed) fault("Guest context has closed")
        return currentIdentity().callback
    }

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

    @TruffleBoundary @Synchronized fun label(identity: GuestThreadId, bytes: Any?) {
        if (closed) fault("Guest context has closed")
        if (identity.owner !== this) fault("ThreadId# belongs to another guest context")
        // ByteArray# is opaque here: retain its identity and logical-size owner.
        // GHC requires UTF-8 but does not decode, validate, or NUL-terminate it.
        labels[identity] = ManagedByteArray.freezeGuest(bytes)
    }

    @TruffleBoundary @Synchronized fun label(identity: GuestThreadId): Any? {
        if (closed) fault("Guest context has closed")
        if (identity.owner !== this) fault("ThreadId# belongs to another guest context")
        return labels[identity]
    }

    /** Snapshot only: eventual dispatch must check the same identity atomically with enqueue. */
    @TruffleBoundary @Synchronized internal fun liveJavaId(identity: GuestThreadId): Long? {
        if (identity.owner !== this) fault("ThreadId# belongs to another guest context")
        if (closed || identity.status.terminal) return null
        val carrier = identity.carrier.get() ?: return null
        if (!carrier.isAlive || carrier.threadId() != identity.javaId || !knownThreads.containsKey(identity)) return null
        val active = threads[identity.logicalId]
        if (active != null && (active.identity !== identity || active.thread !== carrier)) return null
        if (active == null && identities[carrier] !== identity) return null
        // A live host carrier can be FOREIGN between guest entries; no new lifetime is registered.
        return identity.javaId
    }

    @TruffleBoundary fun send(identity: GuestThreadId, payload: Any?): AsyncRequest {
        if (identity.owner !== this) fault("ThreadId# belongs to another guest context")
        return send(identity.logicalId, payload)
    }

    /** The sender waits on the returned token; mere safepoint observation is not delivery. */
    @TruffleBoundary fun send(targetId: Long, payload: Any?): AsyncRequest {
        val request = synchronized(this) {
            check(!closed) { "Guest context has closed" }
            val target = threads[targetId]
                ?: return@synchronized AsyncRequest(this, targetId, null, payload).also {
                    it.transition(AsyncRequestState.TARGET_FINISHED)
                }
            // Another context may retain its suspended caller on this carrier.
            // Only the active logical guest receives self-throwTo semantics.
            val self = currentSlot.get() === target && activeIdentity.get() === target.identity
            if (!self && !target.externalAsync)
                throw UnsupportedCore("External killThread# to a nonresumable AST fork is unsupported")
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
            if (failure is ThreadDeath || request.fail(failure)) throw failure
        }
        return request
    }

    /** Called only at a real continuation cut; masked requests stay queued in FIFO order. */
    fun poll(node: Node, interruptible: Boolean = false): AsyncRequest? {
        val slot = currentSlot.get() ?: return null
        return poll(slot, node, interruptible)
    }

    private fun poll(slot: GuestThread, node: Node, interruptible: Boolean): AsyncRequest? {
        if (!slot.pending) return null
        // claim rechecks ownership, permission, masking and queue state under
        // the registry lock. Keep the ThreadLocal delivery lookup on that cold
        // boundary; its initialization/cleanup must not expand every guest loop.
        return claim(slot, node, interruptible)
    }

    /** Inspect, never claim, a request while an original interruptible open
     * owns an opaque foreign extent. GHC's wrapper chooses the delivery cut
     * only after a failed open; success must first publish its descriptor.
     * RaiseAsync.c preserves MaskedUninterruptible even for interruptible FFI.
     */
    @TruffleBoundary @Synchronized internal fun interruptibleForeignPending(): Boolean {
        val slot = currentSlot.get() ?: return false
        if (closed || threads[slot.identity.logicalId] !== slot ||
            slot.claimed != null || slot.queue.isEmpty()) return false
        return slot.queue.first().forceSelf || maskingState.get() != MaskingState.MASKED_UNINTERRUPTIBLE
    }

    @TruffleBoundary @Synchronized private fun claim(
        target: GuestThread,
        @Suppress("UNUSED_PARAMETER") node: Node,
        interruptible: Boolean
    ): AsyncRequest? {
        if (closed) return null
        val current = Thread.currentThread()
        if (target.thread !== current || currentSlot.get() !== target || threads[target.identity.logicalId] !== target ||
            delivery.get()?.permission != DeliveryPermission.GUEST || activeIdentity.get() !== target.identity ||
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
        var resumed: GuestThreadId? = null
        val finished = synchronized(this) {
            val current = Thread.currentThread()
            val target = currentSlot.get()
            if (target == null) return@synchronized emptyList<AsyncRequest>()
            check(target.thread === current) { "Guest completion ran on a different Java thread" }
            check(target.entries > 0)
            val previous = target.entriesPrevious.removeLast()
            pollState(current).astStack = previous.astStack
            if (previous.active == null) activeIdentity.remove() else activeIdentity.set(previous.active)
            if (--target.entries != 0) {
                if (!closed) target.identity.status = previous.status
                return@synchronized emptyList<AsyncRequest>()
            }
            settleAllocation(target.identity)
            target.identity.lastOutcome = outcome
            if (!closed) target.identity.status = if (target.identity.forked || target.identity.callback) outcome else GuestThreadStatus.FOREIGN
            threads.remove(target.identity.logicalId)
            if (previous.prior == null) currentSlot.remove() else currentSlot.set(previous.prior)
            pollState(current).current = previous.prior
            if (target.identity.callback) {
                maskingState.set(previous.mask)
                resumed = previous.active
            } else maskingState.remove()
            target.pending = false
            target.queue.toList().also { target.queue.clear(); target.claimed = null }
        }
        resumed?.let { it.owner.resumeAllocation(it) }
        finished.forEach { it.finish(AsyncRequestState.TARGET_FINISHED) }
    }

    fun completeCurrent() = leaveCurrent()

    @TruffleBoundary fun close() {
        val remaining = synchronized(this) {
            closed = true
            pollStates.values.forEach { it.current = null }
            pollStates.clear()
            mainThreadWeak = null
            labels.clear()
            knownThreads.clear()
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
        catch (failure: Throwable) {
            if (failure is ThreadDeath || request.fail(failure)) throw failure
        }
    }

    companion object {
        @JvmStatic fun current(node: Node): GuestThreads = Language.currentState(node).threads
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
        @JvmStatic fun pollCurrent(node: Node, interruptible: Boolean): AsyncRequest? {
            val context = Language.currentState(node)
            val slot = context.threadPollState.get().current ?: return null
            return context.threads.poll(slot, node, interruptible)
        }
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
