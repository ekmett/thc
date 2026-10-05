// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.Assumption;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.ThreadLocalAction;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.nodes.Node;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.WeakHashMap;
import java.util.concurrent.Callable;
import thc.Language;
import static thc.runtime.RuntimeFault.fault;

/** A carrier can suspend one guest in foreign code and enter a distinct bound callback. */
public final class GuestThreads {
    @FunctionalInterface public interface Wake { void wake(Thread thread) throws Throwable; }
    private final ThreadLocal<MaskingState> maskingState;
    private final CpuAffinity cpuAffinity;
    private final Wake wake;
    private final TruffleLanguage.Env env;
    // Monotone mailbox history, independent of concurrency admission and masking.
    private final Assumption noAsyncRequestPublished = Truffle.getRuntime().createAssumption("THC no async request published");
    // Guest completion precedes Truffle carrier teardown. Retain ownership independently.
    private final WeakHashMap<Thread, Boolean> platformCarriers = new WeakHashMap<>();
    private boolean stoppingPlatform;
    @com.oracle.truffle.api.CompilerDirectives.CompilationFinal private LoomScheduler loom;
    /** This is execution permission, not the observable Haskell masking state. */
    public enum DeliveryPermission { NONE, GUEST, FOREIGN }
    private static final class DeliveryState {
        DeliveryPermission permission = DeliveryPermission.NONE;
        final ArrayDeque<DeliveryPermission> guestPrevious = new ArrayDeque<>();
    }
    public GuestThreads(ThreadLocal<MaskingState> maskingState, Wake wake) {
        this(maskingState, CpuAffinity.discover(false), wake);
    }
    public GuestThreads(ThreadLocal<MaskingState> maskingState, CpuAffinity cpuAffinity, Wake wake) {
        this(maskingState, cpuAffinity, wake, null, new PollStates());
    }
    private GuestThreads(ThreadLocal<MaskingState> maskingState, CpuAffinity cpuAffinity, Wake wake, TruffleLanguage.Env env, PollStates pollStates) {
        this.env = env;
        this.maskingState = maskingState; this.cpuAffinity = cpuAffinity; this.wake = wake;
        this.pollStates = pollStates;
        logicalCapabilities = cpuAffinity.getCount();
    }
    public GuestThreads(TruffleLanguage.Env env, ThreadLocal<MaskingState> maskingState) {
        this(env, maskingState, "platform");
    }
    public GuestThreads(TruffleLanguage.Env env, ThreadLocal<MaskingState> maskingState, String hosting) {
        this(env, maskingState, hosting, new PollStates());
    }
    public GuestThreads(TruffleLanguage.Env env, ThreadLocal<MaskingState> maskingState, String hosting, PollStates pollStates) {
        this(maskingState, CpuAffinity.discover(env.isNativeAccessAllowed()), target ->
            env.submitThreadLocal(new Thread[]{target}, new ThreadLocalAction(true, false) {
                // Only wake the target's safepoint. An async exception needs a saved guest cut.
                @Override protected void perform(Access access) {}
            }), env, pollStates);
        if (hosting.equals("loom")) loom = new LoomScheduler(env, cpuAffinity);
        else if (!hosting.equals("platform")) throw new RuntimeFault("Unknown THC thread hosting mode: " + hosting);
    }
    public CpuAffinity getCpuAffinity() { return cpuAffinity; }
    public boolean isLoom() { return loom != null; }
    public boolean needsHosting() {
        var foreign = foreignActivations.get();
        return loom != null && !loom.isCurrent() && (foreign == null || foreign.top() == null);
    }
    @TruffleBoundary public <T> T hostEntry(Node node, Callable<T> action) {
        var foreign = foreignActivations.get();
        if (foreign != null && foreign.top() != null) throw new UnsupportedCore("Foreign callbacks must enter on their native origin thread");
        Thread origin = admissionOrigin();
        return loom.invoke(node, () -> {
            Thread previous = hostedOrigin.get();
            hostedOrigin.set(origin);
            try { return action.call(); }
            finally { if (previous == null) hostedOrigin.remove(); else hostedOrigin.set(previous); }
        });
    }
    // Attribution follows a hosted Java stack across safe cross-context callbacks;
    // the assumptions and their first admitted owners remain per-context.
    private static final ThreadLocal<Thread> hostedOrigin = new ThreadLocal<>();
    public Thread admissionOrigin() {
        Thread origin = hostedOrigin.get();
        return origin == null ? Thread.currentThread() : origin;
    }
    @TruffleBoundary public void checkEntryAllowed() {
        if (closed) throw fault("Guest context has closed");
        var stack = foreignActivations.get();
        var activation = stack == null ? null : stack.top();
        var state = activation == null ? null : delivery.get();
        if (activation != null && (state == null || state.permission != DeliveryPermission.GUEST || activation.caller == activeIdentity.get())) {
            if (activation.owner.closed) throw fault("Foreign caller context has closed");
            if (activation.safety == ForeignSafety.UNSAFE) throw fault("Unsafe foreign call cannot re-enter guest code");
        }
    }
    @TruffleBoundary public Thread newThread(TruffleLanguage.Env env, Runnable task, Long capability, Node node) {
        return loom == null ? env.newTruffleThreadBuilder(task).virtual(false).build() : loom.newThread(task, capability, node);
    }
    @TruffleBoundary public void startThread(Thread thread) {
        if (loom != null) { loom.start(thread); return; }
        synchronized (this) {
            if (stoppingPlatform || closed) throw fault("Guest context is stopping");
            try (var ignored = cpuAffinity.resetCurrent()) {
                var previous = platformCarriers.put(thread, Boolean.TRUE);
                try { thread.start(); }
                catch (Throwable failure) { if (previous == null) platformCarriers.remove(thread); throw failure; }
            } catch (Throwable failure) { throw propagate(failure); }
        }
    }
    public void stopHostedThreads() {
        if (loom != null) { loom.stopThreads(); return; }
        Thread[] carriers;
        synchronized (this) {
            stoppingPlatform = true;
            carriers = platformCarriers.keySet().stream().filter(thread -> thread != Thread.currentThread() && thread.isAlive()).toArray(Thread[]::new);
        }
        if (carriers.length != 0) env.submitThreadLocal(carriers, new ThreadLocalAction(true, false) {
            @Override protected void perform(Access access) { throw new Stopped(); }
        });
        // Finalization must join the Java carriers even after Truffle has cancelled the
        // context or deregistered its guest threads. A blocking safepoint could cancel
        // this join itself and mistake its notification interrupt for a host interrupt.
        boolean interrupted = Thread.interrupted();
        try {
            for (var thread : carriers) for (;;) {
                try { thread.join(); break; }
                catch (InterruptedException failure) { interrupted = true; }
            }
            synchronized (this) { platformCarriers.clear(); }
        } finally { if (interrupted) Thread.currentThread().interrupt(); }
    }
    @SuppressWarnings("removal") private static final class Stopped extends ThreadDeath { }
    public static final class GuestThread {
        final Thread thread;
        final GuestThreadId identity;
        // Capture capability of every enclosing activation, not its ordinary poll policy.
        boolean externalAsync;
        final ArrayDeque<GuestEntry> entriesPrevious = new ArrayDeque<>();
        final ArrayDeque<AsyncRequest> queue = new ArrayDeque<>();
        AsyncRequest claimed;
        int entries;
        volatile boolean pending;
        public GuestThread(Thread thread, GuestThreadId identity, boolean externalAsync) {
            this.thread = thread; this.identity = identity; this.externalAsync = externalAsync;
        }
        public Thread getThread() { return thread; }
        public GuestThreadId getIdentity() { return identity; }
        public boolean getExternalAsync() { return externalAsync; }
        public ArrayDeque<GuestEntry> getEntriesPrevious() { return entriesPrevious; }
        public ArrayDeque<AsyncRequest> getQueue() { return queue; }
        public AsyncRequest getClaimed() { return claimed; }
        public void setClaimed(AsyncRequest value) { claimed = value; }
        public int getEntries() { return entries; }
        public void setEntries(int value) { entries = value; }
        public boolean getPending() { return pending; }
        public void setPending(boolean value) { pending = value; }
    }
    public static final class GuestEntry {
        final GuestThreadId active;
        final GuestThreadStatus status;
        final AstStackScope astStack;
        final GuestThread prior;
        final MaskingState mask;
        final LoomScheduler.Admission callbackAdmission;
        final boolean externalAsync;
        public GuestEntry(GuestThreadId active, GuestThreadStatus status, AstStackScope astStack, GuestThread prior, MaskingState mask, boolean externalAsync, LoomScheduler.Admission callbackAdmission) {
            this.active = active; this.status = status; this.astStack = astStack; this.prior = prior; this.mask = mask;
            this.externalAsync = externalAsync; this.callbackAdmission = callbackAdmission;
        }
        public GuestThreadId getActive() { return active; }
        public GuestThreadStatus getStatus() { return status; }
        public AstStackScope getAstStack() { return astStack; }
        public GuestThread getPrior() { return prior; }
        public MaskingState getMask() { return mask; }
    }
    /** Stable per-context/carrier cell, including between nested guest entries. */
    public static final class PollState {
        GuestThread current;
        AstStackScope astStack = new AstStackScope();
        public GuestThread getCurrent() { return current; }
        public void setCurrent(GuestThread value) { current = value; }
        public AstStackScope getAstStack() { return astStack; }
        public void setAstStack(AstStackScope value) { astStack = value; }
    }
    /** Carrier cells can be initialized before the Env-owned guest-thread service is patched. */
    public static final class PollStates {
        private final WeakHashMap<Thread, PollState> states = new WeakHashMap<>();
        @TruffleBoundary public synchronized PollState get(Thread thread) {
            var state = states.get(thread);
            if (state == null) { state = new PollState(); states.put(thread, state); }
            return state;
        }
        private synchronized void close() {
            for (var state : states.values()) state.current = null;
            states.clear();
        }
    }
    private final PollStates pollStates;
    public PollState pollState(Thread thread) { return pollStates.get(thread); }
    private final HashMap<Long, GuestThread> threads = new HashMap<>();
    private long nextIdentity = 1;
    // Weak keys release dead Java carriers; retained IDs keep their assigned capability.
    private final WeakHashMap<Thread, GuestThreadId> identities = new WeakHashMap<>();
    // This registry retains neither the completed ThreadId# nor its Java carrier.
    private final WeakHashMap<GuestThreadId, java.lang.ref.WeakReference<GuestThreadId>> knownThreads = new WeakHashMap<>();
    // Like TSO.label, the exact ByteArray# stays live while its ThreadId# does.
    private final WeakHashMap<GuestThreadId, Object> labels = new WeakHashMap<>();
    private MainThreadWeakKey mainThreadWeak;
    private long nextCapability;
    private long logicalCapabilities;
    @TruffleBoundary public synchronized long capabilityCount() {
        if (closed) throw fault("Guest context has closed"); return logicalCapabilities;
    }
    @TruffleBoundary public synchronized void setCapabilityCount(long count) {
        if (closed) throw fault("Guest context has closed");
        if (count < 1 || count > 0xffff_ffffL) throw fault("setNumCapabilities requires a positive Word32 count");
        logicalCapabilities = count; nextCapability %= count;
        // No change to the initial affinity outcome or pinning of live Java carriers.
        if (loom == null) for (var identity : knownThreads.keySet()) identity.capability %= count;
        else loom.resize(count);
    }
    public synchronized void registerMainThread(MainThreadWeakKey key) {
        if (closed) throw new IllegalStateException("Guest context has closed"); mainThreadWeak = key;
    }
    public synchronized MainThreadWeakKey mainThreadRegistration() { return mainThreadWeak; }
    private final ThreadLocal<GuestThread> currentSlot = new ThreadLocal<>();
    private final ThreadLocal<DeliveryState> delivery = new ThreadLocal<>();
    private volatile boolean closed;
    private DeliveryState deliveryState() {
        var state = delivery.get();
        if (state == null) { state = new DeliveryState(); delivery.set(state); }
        return state;
    }
    /** One reusable frame per nesting depth, no per-call lease for synchronous leaves. */
    private static final class ForeignActivation {
        GuestThreads owner;
        GuestThreadId caller;
        ForeignSafety safety = ForeignSafety.UNSAFE;
        DeliveryPermission previous = DeliveryPermission.NONE;
        GuestThreadStatus status;
        LoomScheduler.Admission admission;
    }
    private static final class ForeignStack {
        final ArrayList<ForeignActivation> frames = new ArrayList<>();
        int depth;
        ForeignActivation top() { return depth == 0 ? null : frames.get(depth - 1); }
    }
    /** Only an exact safe declaration admits callbacks. */
    @TruffleBoundary public DeliveryPermission enterForeign() { return enterForeign(ForeignSafety.UNSAFE); }
    @TruffleBoundary public DeliveryPermission enterForeign(ForeignSafety safety) { return enterForeignImpl(safety, false); }
    /** Final retirement grants no callback authority and cannot begin a new guest operation. */
    @TruffleBoundary public DeliveryPermission enterForeignForDisposal() { return enterForeignImpl(ForeignSafety.UNSAFE, true); }
    private DeliveryPermission enterForeignImpl(ForeignSafety safety, boolean disposal) {
        if (closed && !disposal) throw fault("Guest context has closed");
        var state = deliveryState();
        var stack = foreignActivations.get();
        if (stack == null) { stack = new ForeignStack(); foreignActivations.set(stack); }
        if (stack.depth == stack.frames.size()) stack.frames.add(new ForeignActivation());
        var activation = stack.frames.get(stack.depth++);
        if (activation.owner != null || activation.caller != null) throw new IllegalStateException("Check failed.");
        activation.owner = this; activation.caller = activeIdentity.get(); activation.safety = safety;
        activation.previous = state.permission;
        activation.status = activation.caller == null ? null : activation.caller.status;
        if (activation.caller != null && !activation.caller.status.getTerminal()) activation.caller.status = GuestThreadStatus.FOREIGN;
        state.permission = DeliveryPermission.FOREIGN;
        if (safety != ForeignSafety.UNSAFE) activation.admission = LoomScheduler.suspendCurrentGuest();
        return activation.previous;
    }
    @TruffleBoundary public void leaveForeign(DeliveryPermission previous) {
        var state = delivery.get();
        if (state == null) throw new IllegalStateException("Foreign execution has no delivery state");
        if (state.permission != DeliveryPermission.FOREIGN) throw new IllegalStateException("Foreign execution exited across a guest entry");
        var stack = foreignActivations.get();
        if (stack == null) throw new IllegalStateException("Foreign execution has no Java-thread origin");
        var activation = stack.top();
        if (activation == null) throw new IllegalStateException("Foreign execution has no Java-thread origin");
        if (activation.owner != this || activation.previous != previous) throw new IllegalStateException("Foreign execution exited out of order");
        try {
            if (activation.admission != null) activation.admission.owner().resumeGuest(activation.admission, null);
        } finally {
            if (activation.caller != null && !activation.caller.status.getTerminal()) {
                if (activation.status == null) throw new IllegalStateException("Required value was null.");
                activation.caller.status = activation.status;
            }
            activation.owner = null; activation.caller = null; activation.status = null; activation.admission = null;
            activation.previous = DeliveryPermission.NONE; activation.safety = ForeignSafety.UNSAFE;
            stack.depth--; state.permission = previous;
            if (previous == DeliveryPermission.NONE && state.guestPrevious.isEmpty()) delivery.remove();
        }
    }
    private void enterGuestPermission() {
        var state = deliveryState(); state.guestPrevious.addLast(state.permission); state.permission = DeliveryPermission.GUEST;
    }
    private void leaveGuestPermission() {
        var state = delivery.get();
        if (state == null) throw new IllegalStateException("Guest entry has no delivery state");
        if (state.permission != DeliveryPermission.GUEST || state.guestPrevious.isEmpty()) throw new IllegalStateException("Guest entry exited across foreign execution");
        state.permission = state.guestPrevious.removeLast();
        if (state.permission == DeliveryPermission.NONE && state.guestPrevious.isEmpty()) delivery.remove();
    }
    /** An uncaught callback cannot carry its opaque foreign caller as a guest continuation. */
    public boolean inForeignCallback() {
        var state = delivery.get();
        if (state == null || state.permission != DeliveryPermission.GUEST) return false;
        var stack = foreignActivations.get();
        return stack != null && stack.top() != null;
    }
    public long enterCurrent() { return enterCurrent(null, false, true, null); }
    public long enterCurrent(MaskingState inheritedMask) { return enterCurrent(inheritedMask, false, true, null); }
    public long enterCurrent(MaskingState inheritedMask, boolean forked) { return enterCurrent(inheritedMask, forked, true, null); }
    public long enterCurrent(MaskingState inheritedMask, boolean forked, boolean externalAsync) { return enterCurrent(inheritedMask, forked, externalAsync, null); }
    @TruffleBoundary public long enterCurrent(MaskingState inheritedMask, boolean forked, boolean externalAsync, Long capability) {
        var stack = foreignActivations.get();
        var activation = stack == null ? null : stack.top();
        var deliveryState = activation == null ? null : delivery.get();
        boolean callback = activation != null && (deliveryState == null || deliveryState.permission != DeliveryPermission.GUEST || activation.caller == activeIdentity.get());
        if (callback) {
            if (activation.owner.closed) throw fault("Foreign caller context has closed");
            if (activation.safety == ForeignSafety.UNSAFE) throw fault("Unsafe foreign call cannot re-enter guest code");
        }
        var suspended = callback ? activeIdentity.get() : null;
        // Cross-context reverse entries must never acquire two registry locks.
        if (suspended != null) suspended.owner.pauseAllocation(suspended);
        LoomScheduler.Admission admission = null;
        try {
            if (callback && loom != null) admission = loom.enterCallback(null);
            return enterCurrentImpl(inheritedMask, forked, externalAsync, capability, callback, admission);
        }
        catch (Throwable failure) {
            if (admission != null) admission.owner().leaveCallback(admission);
            if (suspended != null) suspended.owner.resumeAllocation(suspended);
            throw propagate(failure);
        }
    }
    private GuestThreadId freshIdentity(Long capability, Thread current, boolean forked, boolean callback) {
        long selected = Math.floorMod(capability == null ? nextCapability : capability.longValue(), logicalCapabilities);
        if (capability == null) nextCapability = (selected + 1L) % logicalCapabilities;
        if (nextIdentity <= 0) throw new IllegalStateException("Guest thread identity space exhausted");
        var identity = new GuestThreadId(nextIdentity++, this, selected, current, forked, capability != null, callback);
        if (loom != null) loom.attach(identity);
        knownThreads.put(identity, new java.lang.ref.WeakReference<>(identity)); return identity;
    }
    private synchronized long enterCurrentImpl(MaskingState inheritedMask, boolean forked, boolean externalAsync, Long capability, boolean callback, LoomScheduler.Admission admission) {
        if (closed) throw new IllegalStateException("Guest context has closed");
        var current = Thread.currentThread(); var prior = currentSlot.get(); var previousMask = maskingState.get();
        GuestThreadId identity;
        if (callback) identity = freshIdentity(capability, current, forked, true);
        else if (prior != null) identity = prior.identity;
        else {
            identity = identities.get(current);
            if (identity == null) { identity = freshIdentity(capability, current, forked, false); identities.put(current, identity); }
        }
        if (identity.status.getTerminal()) throw new IllegalStateException("Terminated guest Java thread re-entered");
        // A bound callback starts unmasked, never inheriting the caller's queue.
        if (callback) maskingState.set(MaskingState.UNMASKED);
        else if (prior == null && inheritedMask != null) maskingState.set(inheritedMask);
        GuestThread slot;
        if (!callback && prior != null) slot = prior;
        else { slot = new GuestThread(current, identity, externalAsync); threads.put(identity.logicalId, slot); }
        if (slot.entries == 0) {
            identity.allocationBaseline = GuestAllocationAccounting.sample(identity.javaId);
            if (identity.allocationBaseline < 0) identity.allocationUnavailable = true;
        }
        var poll = pollState(current);
        slot.entriesPrevious.addLast(new GuestEntry(activeIdentity.get(), slot.identity.status, poll.astStack, prior, previousMask, slot.externalAsync, admission));
        if (!callback && prior != null) slot.externalAsync &= externalAsync;
        poll.astStack = new AstStackScope(); slot.identity.status = GuestThreadStatus.RUNNING; activeIdentity.set(slot.identity);
        slot.entries++; currentSlot.set(slot); pollState(current).current = slot; enterGuestPermission(); return identity.logicalId;
    }
    public long registerCurrent() { return enterCurrent(); }
    /** Scope host-call admission without changing queued requests or the guest lifetime. */
    @TruffleBoundary public synchronized boolean setCurrentExternalAsync(boolean enabled) {
        var slot = currentSlot.get();
        if (slot == null || slot.entries <= 0) throw fault("Current Java thread has not entered this guest context");
        boolean previous = slot.externalAsync;
        // A nested entry cannot unwind through an outer nonresumable activation.
        slot.externalAsync = enabled && (slot.entries == 1 || slot.entriesPrevious.getLast().externalAsync);
        return previous;
    }
    /** myThreadId# observes an existing guest entry; it never creates a new lifetime. */
    public long currentId() { return currentIdentity().logicalId; }
    @TruffleBoundary public GuestThreadId currentIdentity() {
        var slot = currentSlot.get();
        if (slot == null || slot.entries <= 0) throw fault("Current Java thread has not entered this guest context");
        return slot.identity;
    }
    private void settleAllocation(GuestThreadId identity) {
        long end = GuestAllocationAccounting.sample(identity.javaId);
        if (end >= 0 && identity.allocationBaseline >= 0) identity.allocationRemaining -= end - identity.allocationBaseline;
        else identity.allocationUnavailable = true;
        identity.allocationBaseline = -1;
    }
    private synchronized void pauseAllocation(GuestThreadId identity) {
        if (identity.allocationSuspended) throw new IllegalStateException("Check failed.");
        settleAllocation(identity); identity.allocationSuspended = true;
    }
    private synchronized void resumeAllocation(GuestThreadId identity) {
        identity.allocationSuspended = false;
        if (closed || identity.status.getTerminal()) return;
        identity.allocationBaseline = GuestAllocationAccounting.sample(identity.javaId);
        if (identity.allocationBaseline < 0) identity.allocationUnavailable = true;
    }
    @TruffleBoundary public synchronized long allocationCounter() {
        if (closed) throw fault("Guest context has closed");
        var identity = currentIdentity();
        if (identity.allocationUnavailable || identity.allocationBaseline < 0)
            throw fault("Thread allocation accounting was unavailable during this guest lifetime; reset it before reading");
        long allocated = GuestAllocationAccounting.sample(identity.javaId);
        if (allocated < 0) { identity.allocationUnavailable = true; throw fault("Thread allocation accounting is unavailable for this carrier"); }
        return identity.allocationRemaining - (allocated - identity.allocationBaseline);
    }
    public void setAllocationCounter(long value) { setAllocationCounter(value, currentIdentity()); }
    /** A retained completed identity remains valid; a same-number fabricated object does not. */
    @TruffleBoundary public synchronized GuestThreadId requireIdentity(Object value) {
        if (closed) throw fault("Guest context has closed");
        if (!(value instanceof GuestThreadId identity) || identity.owner != this)
            throw fault("Expected a ThreadId# from this guest context");
        var canonical = knownThreads.get(identity);
        if (canonical == null || canonical.get() != identity) throw fault("ThreadId# is not a registered guest lifetime");
        return identity;
    }
    @TruffleBoundary public synchronized void setAllocationCounter(long value, GuestThreadId identity) {
        if (closed) throw fault("Guest context has closed");
        requireIdentity(identity);
        var slot = threads.get(identity.logicalId);
        if (!identity.allocationSuspended && slot != null && slot.identity == identity)
            identity.allocationBaseline = GuestAllocationAccounting.bytes(identity.javaId);
        identity.allocationRemaining = value; identity.allocationUnavailable = false;
    }
    /** Independent snapshot including retained completed identities under the registry lock. */
    @TruffleBoundary public synchronized Object[] snapshot() {
        if (closed) throw fault("Guest context has closed"); currentIdentity(); return knownThreads.keySet().toArray();
    }
    @TruffleBoundary public synchronized boolean isCurrentBound() {
        if (closed) throw fault("Guest context has closed"); return currentIdentity().callback;
    }
    @TruffleBoundary public synchronized GuestThreadStatus status(GuestThreadId identity) {
        if (closed) throw fault("Guest context has closed");
        if (identity.owner != this) throw fault("ThreadId# belongs to another guest context");
        if (!identity.status.getTerminal()) {
            var carrier = identity.carrier.get();
            if (carrier == null || !carrier.isAlive()) identity.status = identity.lastOutcome;
        }
        var status = identity.status;
        if (status == GuestThreadStatus.RUNTIME_FAILURE) throw fault("ThreadId# terminated because of a runtime failure");
        return status;
    }
    @TruffleBoundary public synchronized void label(GuestThreadId identity, Object bytes) {
        if (closed) throw fault("Guest context has closed");
        if (identity.owner != this) throw fault("ThreadId# belongs to another guest context");
        labels.put(identity, ManagedByteArray.freezeGuest(bytes));
    }
    @TruffleBoundary public synchronized Object label(GuestThreadId identity) {
        if (closed) throw fault("Guest context has closed");
        if (identity.owner != this) throw fault("ThreadId# belongs to another guest context"); return labels.get(identity);
    }
    /** Snapshot only: eventual dispatch checks the same identity atomically with enqueue. */
    @TruffleBoundary public synchronized Long liveJavaId(GuestThreadId identity) {
        if (identity.owner != this) throw fault("ThreadId# belongs to another guest context");
        if (closed || identity.status.getTerminal()) return null;
        var carrier = identity.carrier.get();
        if (carrier == null || !carrier.isAlive() || carrier.threadId() != identity.javaId || !knownThreads.containsKey(identity)) return null;
        var active = threads.get(identity.logicalId);
        if (active != null && (active.identity != identity || active.thread != carrier)) return null;
        if (active == null && identities.get(carrier) != identity) return null;
        return identity.javaId;
    }
    @TruffleBoundary public AsyncRequest send(GuestThreadId identity, Object payload) {
        if (identity.owner != this) throw fault("ThreadId# belongs to another guest context"); return send(identity.logicalId, payload);
    }
    /** The sender waits on this token; safepoint observation is not delivery. */
    @TruffleBoundary public AsyncRequest send(long targetId, Object payload) {
        AsyncRequest request;
        synchronized (this) {
            if (closed) throw new IllegalStateException("Guest context has closed");
            var target = threads.get(targetId);
            if (target == null) { request = new AsyncRequest(this, targetId, null, payload); request.transition(AsyncRequestState.TARGET_FINISHED); }
            else {
                boolean self = currentSlot.get() == target && activeIdentity.get() == target.identity;
                if (!self && !target.externalAsync) throw new UnsupportedCore("External killThread# to a nonresumable AST fork is unsupported");
                noAsyncRequestPublished.invalidate("An async request is being published");
                request = new AsyncRequest(this, targetId, target.thread, payload, self);
                if (self) target.queue.addFirst(request); else target.queue.addLast(request);
                target.pending = target.claimed == null;
            }
        }
        var thread = request.target;
        if (thread == null || request.forceSelf) return request;
        try { wake.wake(thread); }
        catch (Throwable failure) {
            // A late failed wake cannot revoke committed delivery or a finished target.
            if (failure instanceof ThreadDeath || request.fail(failure)) throw propagate(failure);
        }
        return request;
    }
    public AsyncRequest poll(Node node) { return poll(node, false); }
    public AsyncRequest poll(Node node, boolean interruptible) {
        var slot = currentSlot.get(); return slot == null ? null : poll(slot, node, interruptible);
    }
    private AsyncRequest poll(GuestThread slot, Node node, boolean interruptible) {
        return slot.pending ? claim(slot, node, interruptible) : null;
    }
    /** Inspect, never claim, a request while an interruptible open owns foreign execution. */
    @TruffleBoundary public synchronized boolean interruptibleForeignPending() {
        var slot = currentSlot.get();
        if (slot == null || closed || threads.get(slot.identity.logicalId) != slot || slot.claimed != null || slot.queue.isEmpty()) return false;
        return slot.queue.getFirst().forceSelf || slot.externalAsync && maskingState.get() != MaskingState.MASKED_UNINTERRUPTIBLE;
    }
    @TruffleBoundary private synchronized AsyncRequest claim(GuestThread target, Node node, boolean interruptible) {
        if (closed) return null;
        var current = Thread.currentThread();
        if (target.thread != current || currentSlot.get() != target || threads.get(target.identity.logicalId) != target) return null;
        var state = delivery.get();
        if (state == null || state.permission != DeliveryPermission.GUEST || activeIdentity.get() != target.identity || target.claimed != null || target.queue.isEmpty()) return null;
        var request = target.queue.getFirst();
        boolean allowed = request.forceSelf || target.externalAsync && switch (maskingState.get()) {
            case UNMASKED -> true; case MASKED_INTERRUPTIBLE -> interruptible; case MASKED_UNINTERRUPTIBLE -> false;
        };
        if (!allowed) return null;
        if (request.getState() != AsyncRequestState.PENDING) throw new IllegalStateException("Check failed.");
        request.transition(AsyncRequestState.CLAIMED); target.claimed = request; target.pending = false; return request;
    }
    public void leaveCurrent() { leaveCurrent(GuestThreadStatus.FINISHED); }
    /** Called by the target in the same finally block that ends its guest action. */
    @TruffleBoundary public void leaveCurrent(GuestThreadStatus outcome) {
        if (!outcome.getTerminal()) throw new IllegalArgumentException("Failed requirement.");
        leaveGuestPermission();
        GuestThreadId resumed = null;
        LoomScheduler.Admission admission = null;
        List<AsyncRequest> finished = List.of();
        synchronized (this) {
            var current = Thread.currentThread(); var target = currentSlot.get();
            if (target != null) {
                if (target.thread != current) throw new IllegalStateException("Guest completion ran on a different Java thread");
                if (target.entries <= 0) throw new IllegalStateException("Check failed.");
                var previous = target.entriesPrevious.removeLast(); pollState(current).astStack = previous.astStack;
                admission = previous.callbackAdmission;
                if (previous.active == null) activeIdentity.remove(); else activeIdentity.set(previous.active);
                if (--target.entries != 0) {
                    target.externalAsync = previous.externalAsync;
                    if (!closed) target.identity.status = previous.status;
                }
                else {
                    settleAllocation(target.identity); target.identity.lastOutcome = outcome;
                    if (!closed) target.identity.status = target.identity.forked || target.identity.callback ? outcome : GuestThreadStatus.FOREIGN;
                    threads.remove(target.identity.logicalId);
                    if (previous.prior == null) currentSlot.remove(); else currentSlot.set(previous.prior);
                    pollState(current).current = previous.prior;
                    if (target.identity.callback) { maskingState.set(previous.mask); resumed = previous.active; } else maskingState.remove();
                    target.pending = false; finished = new ArrayList<>(target.queue); target.queue.clear(); target.claimed = null;
                }
            }
        }
        if (admission != null) admission.owner().leaveCallback(admission);
        if (resumed != null) resumed.owner.resumeAllocation(resumed);
        for (var request : finished) request.finish(AsyncRequestState.TARGET_FINISHED);
    }
    public void completeCurrent() { leaveCurrent(); }
    @TruffleBoundary public void close() {
        var remaining = new ArrayList<AsyncRequest>();
        synchronized (this) {
            closed = true;
            pollStates.close(); mainThreadWeak = null; labels.clear(); knownThreads.clear();
            for (var identity : identities.values()) identity.status = GuestThreadStatus.RUNTIME_FAILURE;
            for (var slot : threads.values()) {
                slot.identity.status = GuestThreadStatus.RUNTIME_FAILURE; slot.pending = false;
                remaining.addAll(slot.queue); slot.queue.clear(); slot.claimed = null;
            }
            threads.clear();
        }
        for (var request : remaining) request.finish(AsyncRequestState.TARGET_FINISHED);
        if (loom != null) loom.close();
    }
    public boolean finish(AsyncRequest request, AsyncRequestState state) { return finish(request, state, null); }
    public synchronized boolean finish(AsyncRequest request, AsyncRequestState state, Throwable cause) {
        var thread = request.target; if (thread == null) return false;
        var target = threads.get(request.targetId); if (target == null || target.thread != thread) return false;
        boolean queued = target.queue.contains(request);
        if (!queued && request.getState() != AsyncRequestState.PAUSED) return false;
        if (state == AsyncRequestState.CANCELLED && request.getState() != AsyncRequestState.PENDING && request.getState() != AsyncRequestState.PAUSED) return false;
        if (state == AsyncRequestState.ACKNOWLEDGED && request.getState() != AsyncRequestState.CLAIMED) throw new IllegalStateException("Async request was not claimed by its target");
        if (state == AsyncRequestState.FAILED && request.getState() != AsyncRequestState.PENDING) return false;
        if (queued) target.queue.remove(request);
        if (target.claimed == request) target.claimed = null;
        target.pending = target.claimed == null && !target.queue.isEmpty(); request.failure = cause; request.transition(state); return true;
    }
    /** Remove an uncommitted outbound throwTo while its sender handles an async exception. */
    public synchronized boolean pause(AsyncRequest request) {
        var target = threads.get(request.targetId);
        if (target == null || target.thread != request.target || request.getState() != AsyncRequestState.PENDING || !target.queue.remove(request)) return false;
        target.pending = target.claimed == null && !target.queue.isEmpty(); request.transition(AsyncRequestState.PAUSED); return true;
    }
    /** Resume the same logical request after the sender's caught continuation resumes. */
    public void resume(AsyncRequest request) {
        Thread thread;
        synchronized (this) {
            if (request.getState() != AsyncRequestState.PAUSED) return;
            var target = threads.get(request.targetId);
            if (closed || target == null || target.thread != request.target) { request.transition(AsyncRequestState.TARGET_FINISHED); return; }
            noAsyncRequestPublished.invalidate("An async request is being resumed");
            target.queue.addLast(request); request.transition(AsyncRequestState.PENDING); target.pending = target.claimed == null; thread = target.thread;
        }
        try { wake.wake(thread); }
        catch (Throwable failure) { if (failure instanceof ThreadDeath || request.fail(failure)) throw propagate(failure); }
    }
    public static GuestThreads current(Node node) { return Language.currentState(node).getThreads(); }
    private static final ThreadLocal<ForeignStack> foreignActivations = new ThreadLocal<>();
    private static final ThreadLocal<GuestThreadId> activeIdentity = new ThreadLocal<>();
    /** No guest entry means no Haskell thread to mark (also used by cell protocol tests). */
    public static GuestThreadExtent blocking(GuestThreadStatus status) {
        var identity = activeIdentity.get();
        var previous = identity == null ? null : identity.status;
        if (identity != null && !identity.status.getTerminal()) identity.status = status;
        return new GuestThreadExtent(identity, previous, identity == null ? null : LoomScheduler.suspendCurrentGuest());
    }
    /** Java-callable poll for bytecode roots; it never delivers from a wake action. */
    public static AsyncRequest pollCurrent(Node node, boolean interruptible) {
        if (!ordinaryPollEnabled(node)) return null;
        checkpointCurrent(node);
        if (ordinaryMailboxUnpublished(node)) return null;
        return pollMandatoryCurrentWithoutYield(node, interruptible);
    }
    /** Only ordinary polls speculate. Calls and their capture handlers never do. */
    public static boolean ordinaryPollEnabled(Node node) {
        if (node == null || !(node.getRootNode() instanceof GuestRoot root) || root.getEagerAsyncPolls()) return true;
        var owner = Language.currentState(node);
        return root instanceof FunctionRoot function && function.usesRuntimeAsyncAdmission() ?
            owner.isGuestConcurrencyAdmitted() : !owner.getSingleGuestOriginAssumption().isValid();
    }
    /** Self throwTo is synchronous even while the single-origin assumption is valid. */
    public static AsyncRequest pollMandatoryCurrent(Node node, boolean interruptible) {
        checkpointCurrent(node);
        return pollMandatoryCurrentWithoutYield(node, interruptible);
    }
    public static void checkpointCurrent(Node node) {
        var context = Language.currentState(node);
        if (context.getThreads().loom != null) context.getThreads().loom.checkpoint();
    }
    /** Used under commit/owner locks; scheduling yield happens before acquiring them. */
    public static AsyncRequest pollCurrentWithoutYield(Node node, boolean interruptible) {
        if (!ordinaryPollEnabled(node)) return null;
        if (ordinaryMailboxUnpublished(node)) return null;
        return pollMandatoryCurrentWithoutYield(node, interruptible);
    }
    private static boolean ordinaryMailboxUnpublished(Node node) {
        // Eager delivery and prepared roots retain polling without this speculation.
        if (node != null && node.getRootNode() instanceof GuestRoot root &&
                (root.getEagerAsyncPolls() || root instanceof FunctionRoot function && function.usesRuntimeAsyncAdmission())) return false;
        return current(node).noAsyncRequestPublished.isValid();
    }
    private static AsyncRequest pollMandatoryCurrentWithoutYield(Node node, boolean interruptible) {
        var context = Language.currentState(node);
        var slot = context.getThreadPollState().get().current;
        return slot == null ? null : context.getThreads().poll(slot, node, interruptible);
    }
    @SuppressWarnings("unchecked") private static <E extends Throwable> RuntimeException propagate(Throwable failure) throws E { throw (E) failure; }
}
