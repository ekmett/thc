// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.TruffleSafepoint;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.nodes.Node;
import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import jam.vm.Weak;
import thc.Language;
import static thc.runtime.RuntimeServiceStatus.fault;

/** Jam owns conditional reachability; this context owns guest finalizer execution. */
public final class ManagedWeaks {
    private static final class Handle {
        final ManagedWeaks owner;
        WeakReference<Object> key;
        long token;
        Handle previous, next;
        boolean bootstrap, retired;
        Handle(ManagedWeaks owner) { this.owner = owner; }
    }
    /** Claimed work may transfer to the context only after an unentered failure. */
    private abstract static class Work implements Runnable {
        final Handle handle;
        Work nextFailed;
        Throwable failure;
        Work(Handle handle) { this.handle = handle; }
        abstract Finalizer release();
    }
    // The finalizer deliberately has no edge to this payload: claiming F must not retain V.
    private record Payload(Object value, Finalizer finalizer) { }
    private static final class Finalizer extends Work {
        boolean begun;
        Throwable entryFailure;
        final Object action, runner;
        final CallTarget root;
        final ArrayDeque<Runnable> callbacks = new ArrayDeque<>();
        Finalizer(Handle handle, Object action, Object runner, CallTarget root) {
            super(handle); this.action = action; this.runner = runner; this.root = root;
        }
        @Override Finalizer release() { return this; }
        void callbacks() {
            try {
                for (Runnable callback; (callback = callbacks.pollFirst()) != null;) {
                    begun = true; callback.run();
                }
            }
            finally { callbacks.clear(); }
        }
        // Weak.pump() may be called by another host client. Returning means the real
        // carrier has terminated, including cancellation before its Runnable entered.
        @Override public void run() { handle.owner.run(this); }
    }
    /** Only the conditional/native finalizer root owns these handoff captures. */
    private static final class Bootstrap extends Work {
        Object key, value;
        Finalizer finalizer;
        Bootstrap(Handle handle, Object key, Object value, Finalizer finalizer) {
            super(handle); this.key = key; this.value = value; this.finalizer = finalizer;
        }
        @Override Finalizer release() {
            Finalizer result = finalizer;
            key = null; value = null; finalizer = null;
            return result;
        }
        @Override public void run() {
            Finalizer result;
            try { result = handle.owner.handoff(this); }
            catch (Throwable failure) {
                reportFailure(handle.owner.state, failure);
                throw propagate(failure);
            }
            if (result != null) result.run();
        }
    }
    /** Independent claim carriers keep normal dispatch out of guest waits. */
    private static final class Drainer {
        static final Thread THREAD = Thread.ofPlatform().daemon().name("THC weak finalizers").start(() -> {
            var token = new long[1];
            for (;;) {
                Runnable finalizer = Weak.take(token);
                if (finalizer == null) {
                    try { Thread.sleep(10); } catch (InterruptedException ignored) { }
                    continue;
                }
                long claimed = token[0];
                try {
                    Thread.ofVirtual().name("THC weak claim").start(() -> {
                        try { finalizer.run(); }
                        finally { Weak.complete(claimed); }
                    });
                } catch (Throwable failure) {
                    if (finalizer instanceof Work work) {
                        work.handle.owner.failed(work, failure);
                        Weak.complete(claimed);
                        reportFailure(work.handle.owner.state, failure);
                    } else {
                        // ponytail: foreign dispatch failure runs inline and may
                        // block this pump; a foreign owner can supply its own pump.
                        try { finalizer.run(); }
                        catch (Throwable caught) {
                            try {
                                var current = Thread.currentThread();
                                current.getUncaughtExceptionHandler().uncaughtException(current, caught);
                            } catch (Throwable ignored) { }
                        } finally { Weak.complete(claimed); }
                    }
                }
            }
        });
        static void start() { if (!THREAD.isAlive()) throw fault("Weak finalizer drainer terminated"); }
    }
    private static final WeakResult DEAD = new WeakResult(0L, null);
    private final Language.State state;
    private final Language language;
    // No context root points at active K, V or F. Membership follows the logical lifetime.
    private Handle live;
    // Failed installation is unfinished work, not evidence of key death. Explicit
    // finalize/close settles it; normal active handles never reach these captures.
    private Work failedWork;
    private boolean stopping, closed;
    private int running;

    public ManagedWeaks(Language.State state, Language language) { this.state = state; this.language = language; }
    private Handle handle(Object value) {
        if (!(value instanceof Handle handle)) throw fault("Expected a context-owned Weak#");
        if (closed || handle.owner != this) throw fault("Disposed or foreign Weak# context");
        return handle;
    }
    private void requireThreads() {
        if (!state.getEnv().isCreateThreadAllowed()) throw fault("Automatic weak finalizers require guest thread permission");
    }
    @TruffleBoundary public synchronized Object make(Object key, Object value, Object action, Object runner) {
        if (stopping) throw fault("Weak# context is stopping");
        if (key == null || value == null) throw fault("Weak# key and value require boxed carriers");
        if (action != null) {
            requireThreads();
            if (runner == null) throw fault("Missing original Haskell weak finalizer runner");
        }
        Drainer.start();
        Object referent = LiftedValues.resolveBoxed(key);
        var handle = new Handle(this);
        var finalizer = new Finalizer(handle, action, runner,
            action == null ? null : new ForkActionRoot(language, null, true, 2).getCallTarget());
        // Never force K, V or F. Jam's conditional association is the only root of this payload.
        install(handle, referent, value, finalizer);
        handle.next = live;
        if (live != null) live.previous = handle;
        live = handle;
        Reference.reachabilityFence(key);
        return handle;
    }
    private void install(Handle handle, Object key, Object value, Finalizer finalizer) {
        boolean bootstrap = key instanceof Thunk;
        Runnable callback = bootstrap ? new Bootstrap(handle, key, value, finalizer) : finalizer;
        var projection = new WeakReference<>(key);
        var payload = new Payload(value, finalizer);
        long token = Weak.create(key, payload, callback);
        handle.key = projection;
        handle.token = token;
        handle.bootstrap = bootstrap;
        Reference.reachabilityFence(key);
    }
    private void retire(Handle handle) {
        if (!handle.retired) {
            if (handle.previous == null) { if (live == handle) live = handle.next; }
            else handle.previous.next = handle.next;
            if (handle.next != null) handle.next.previous = handle.previous;
            handle.previous = null; handle.next = null; handle.retired = true;
        }
        handle.bootstrap = false; notifyAll();
    }
    private synchronized void failed(Work work, Throwable failure) {
        if (closed) { work.release(); retire(work.handle); return; }
        if (work instanceof Finalizer) retire(work.handle);
        // ponytail: linear failed-work search; index only if exceptional backlog
        // matters. Retention itself must not allocate after OOME.
        work.failure = failure;
        work.nextFailed = failedWork;
        failedWork = work;
        notifyAll();
    }
    private Work failure(Handle handle, boolean remove) {
        Work previous = null;
        for (Work current = failedWork; current != null; current = current.nextFailed) {
            if (current.handle == handle) {
                if (remove) {
                    if (previous == null) failedWork = current.nextFailed;
                    else previous.nextFailed = current.nextFailed;
                    current.nextFailed = null;
                }
                return current;
            }
            previous = current;
        }
        return null;
    }
    /** No forcing or guest action: installation and publication precede capture release. */
    private synchronized Finalizer handoff(Bootstrap bootstrap) {
        var handle = bootstrap.handle;
        if (handle.retired) return bootstrap.release();
        if (stopping) { retire(handle); bootstrap.release(); return null; }
        try {
            Object replacement = LiftedValues.resolveBoxed(bootstrap.key);
            if (replacement == bootstrap.key) {
                retire(handle);
                return bootstrap.release();
            }
            install(handle, replacement, bootstrap.value, bootstrap.finalizer);
            bootstrap.release();
            notifyAll();
            return null;
        } catch (Throwable failure) {
            failed(bootstrap, failure);
            throw propagate(failure);
        }
    }
    private synchronized void waitHandoff(Handle handle, long token) throws InterruptedException {
        while (!closed && handle.bootstrap && handle.token == token && failure(handle, false) == null) wait();
    }
    private void awaitHandoff(Handle handle, long token) {
        if (!state.getEnv().getContext().isEntered()) {
            boolean interrupted = false;
            try {
                for (;;) {
                    try { waitHandoff(handle, token); return; }
                    catch (InterruptedException ignored) { interrupted = true; }
                }
            } finally { if (interrupted) Thread.currentThread().interrupt(); }
        }
        // Suspension/reacquisition is outside the registry monitor. Native claims
        // arbitrate helpers; only a claimed bootstrap may need this short wait.
        try (var admission = LoomScheduler.suspendCurrentGuest()) {
            TruffleSafepoint.setBlockedThreadInterruptible(null, waiting -> waitHandoff(waiting, token), handle);
        }
    }
    private Payload payload(Handle handle) {
        for (;;) {
            Bootstrap bootstrap;
            long token;
            synchronized (this) {
                handle(handle);
                var failed = failure(handle, false);
                if (failed != null) throw propagate(failed.failure);
                if (handle.retired) return null;
                var payload = (Payload) Weak.deref(handle.token);
                if (payload != null || !handle.bootstrap) return payload;
                token = handle.token;
                bootstrap = (Bootstrap) Weak.finalizeNow(token);
            }
            if (bootstrap == null) { awaitHandoff(handle, token); continue; }
            Finalizer finalizer;
            try { finalizer = handoff(bootstrap); }
            catch (Throwable failure) { Weak.complete(token); throw propagate(failure); }
            if (finalizer == null) { Weak.complete(token); continue; }
            // A helper must not block on a real guest finalizer (which can in
            // turn wait for the helper). Retain the captured old claim until
            // its independently dispatched real carrier has terminated.
            try {
                Thread.ofVirtual().name("THC weak finalizer").start(() -> {
                    try { finalizer.run(); }
                    finally { Weak.complete(token); }
                });
            } catch (Throwable failure) {
                bootstrap.finalizer = finalizer;
                failed(bootstrap, failure);
                Weak.complete(token);
                throw propagate(failure);
            }
        }
    }
    @TruffleBoundary public WeakResult dereference(Object value) {
        Handle handle;
        synchronized (this) { handle = handle(value); }
        var payload = payload(handle);
        return payload == null ? DEAD : new WeakResult(1L, payload.value());
    }
    /** A nonzero flag selects the environment/object C ABI; zero ignores the environment. */
    @TruffleBoundary public long addCFinalizer(ManagedAddress function, ManagedAddress address,
            long flag, ManagedAddress environment, Object weak, SulongCbits provider) {
        var callback = function.finalizerFunction();
        if (callback == null) throw fault("Expected an original C function label");
        callback.requireOwner(provider);
        callback.requireArity(flag == 0L ? 1 : 2);
        if (callback.getSymbol().equals("free")) state.getNativeAllocations().requireFreeTarget(address);
        return addCallback(weak, flag == 0L ? () -> callback.invoke(address) : () -> callback.invoke(environment, address));
    }
    @TruffleBoundary public long addCallback(Object value, Runnable callback) {
        Handle handle;
        synchronized (this) { handle = handle(value); }
        for (;;) {
            var payload = payload(handle);
            if (payload == null) return 0L;
            synchronized (this) {
                handle(handle);
                if (Weak.deref(handle.token) != payload) { payload = null; continue; }
                requireThreads();
                // RTS prepends. Native dereference roots the payload across the mutation.
                payload.finalizer().callbacks.addFirst(java.util.Objects.requireNonNull(callback));
                Reference.reachabilityFence(payload);
                return 1L;
            }
        }
    }
    @TruffleBoundary public WeakResult finalize(Object value) {
        Finalizer finalizer;
        long token;
        for (;;) {
            Handle handle;
            synchronized (this) {
                handle = handle(value);
                token = handle.token; // Complete the claim, never a later successor.
                var failed = failure(handle, true);
                if (failed != null) {
                    retire(handle); finalizer = failed.release(); break;
                }
                if (handle.retired) return DEAD;
                Runnable claimed = Weak.finalizeNow(token);
                if (claimed != null) {
                    retire(handle);
                    finalizer = claimed instanceof Bootstrap bootstrap ? bootstrap.release() : (Finalizer) claimed;
                    break;
                }
                if (!handle.bootstrap) return DEAD;
            }
            awaitHandoff(handle, token);
        }
        // Explicit claim unwraps a bootstrap without re-registering or forcing.
        try {
            finalizer.callbacks();
            return finalizer.action == null ? DEAD : new WeakResult(1L, finalizer.action);
        } finally { Weak.complete(token); }
    }
    private void run(Finalizer finalizer) {
        synchronized (this) {
            retire(finalizer.handle);
            if (stopping || finalizer.action == null && finalizer.callbacks.isEmpty()) return;
            running++;
        }
        boolean interrupted = false;
        Thread child = null;
        Throwable failure = null;
        try {
            state.admitGuestConcurrency();
            var threads = state.getThreads();
            // Construction can block entering Truffle. Never hold the admission monitor here.
            child = threads.newThread(state.getEnv(), () -> execute(finalizer), null, null);
            var handler = child.getUncaughtExceptionHandler();
            child.setUncaughtExceptionHandler((thread, caught) -> {
                if (!finalizer.begun) finalizer.entryFailure = caught;
                if (handler != null) handler.uncaughtException(thread, caught);
            });
            synchronized (this) {
                if (stopping) return;
                threads.startThread(child);
            }
        } catch (Throwable caught) { failure = caught; }
        finally {
            // startThread can throw restoring affinity AFTER Thread.start succeeds.
            // A started carrier owns the claim until actual Java termination.
            if (child != null && child.getState() != Thread.State.NEW) {
                for (;;) {
                    try { child.join(); break; }
                    catch (InterruptedException ignored) { interrupted = true; }
                }
            }
            synchronized (this) {
                if (!finalizer.begun) {
                    Throwable unentered = failure == null ? finalizer.entryFailure : failure;
                    if (unentered != null) failed(finalizer, unentered);
                }
                running--; notifyAll();
            }
            if (interrupted) Thread.currentThread().interrupt();
        }
        if (failure == null) failure = finalizer.entryFailure;
        if (failure != null) {
            reportFailure(state, failure);
            throw propagate(failure);
        }
    }
    private void execute(Finalizer finalizer) {
        var threads = state.getThreads();
        boolean registered = false;
        AutoCloseable affinity = null;
        GuestThreadStatus outcome = GuestThreadStatus.FINISHED;
        Throwable failure = null;
        try {
            threads.enterCurrent(MaskingState.UNMASKED, true, true, null);
            registered = true;
            if (!threads.isLoom()) affinity = threads.getCpuAffinity().resetCurrent();
            Object[] arguments = finalizer.action == null ? null : new Object[]{finalizer.runner, finalizer.action};
            finalizer.callbacks();
            if (arguments != null) {
                finalizer.begun = true;
                finalizer.root.call(arguments);
            }
        } catch (UncaughtForkAsync uncaught) {
            outcome = GuestThreadStatus.DIED;
            uncaught.request.acknowledge();
            if (!finalizer.begun) failure = uncaught;
        } catch (AsyncDelivery uncaught) {
            outcome = GuestThreadStatus.DIED;
            uncaught.getRequest().acknowledge();
            if (!finalizer.begun) failure = uncaught;
        } catch (Throwable caught) {
            failure = caught;
            outcome = GuestThreadStatus.uncaught(caught);
        } finally {
            try { if (registered) threads.leaveCurrent(outcome); }
            catch (Throwable cleanup) { failure = cleanupFailure(failure, cleanup); }
            try { if (affinity != null) affinity.close(); }
            catch (Throwable cleanup) { failure = cleanupFailure(failure, cleanup); }
        }
        if (failure != null) {
            if (!finalizer.begun) { finalizer.entryFailure = failure; return; }
            reportFailure(state, failure);
            throw propagate(failure);
        }
    }
    private static void reportFailure(Language.State state, Throwable failure) {
        // Reporting may allocate too. The original failure is already retained or
        // propagated; a secondary reporting failure must not kill the shared pump.
        try { GuestThreadOps.reportHostFailure(state, failure); }
        catch (Throwable ignored) { }
    }
    private static Throwable cleanupFailure(Throwable failure, Throwable cleanup) {
        if (failure == null) return cleanup;
        if (failure != cleanup) failure.addSuppressed(cleanup);
        return failure;
    }
    @SuppressWarnings("unchecked") private static <E extends Throwable> RuntimeException propagate(Throwable failure) throws E { throw (E) failure; }

    /** rts_setMainThread consumes the KEY (ThreadId#), not the boxed ThreadId value. */
    @TruffleBoundary public synchronized MainThreadWeakKey mainThreadKey(Object value, GuestThreads threads) {
        var weak = handle(value);
        Object key = weak.key.get();
        if (key == null || Weak.deref(weak.token) == null) throw fault("Main thread requires a live Weak#");
        if (!(key instanceof GuestThreadId identity)) throw fault("Main thread Weak# key is not a ThreadId#");
        threads.requireIdentity(identity);
        return new MainThreadWeakKey(this, weak, threads);
    }
    @TruffleBoundary synchronized Long mainThreadJavaId(Object value, GuestThreads threads) {
        if (closed) return null;
        var weak = handle(value);
        Object key = weak.key.get();
        if (key == null || Weak.deref(weak.token) == null) return null;
        return threads.liveJavaId((GuestThreadId) key);
    }
    /** Fence admission before GuestThreads stops and joins the actual carriers. */
    public synchronized void requestStop() { stopping = true; notifyAll(); }
    /** Called after the guest join, before any native provider is disposed. */
    public void close() {
        boolean interrupted = false;
        synchronized (this) {
            stopping = true;
            while (running != 0) {
                try { wait(); } catch (InterruptedException ignored) { interrupted = true; }
            }
            closed = true;
            while (failedWork != null) {
                var failed = failedWork; failedWork = failed.nextFailed;
                failed.nextFailed = null; retire(failed.handle); failed.release();
            }
            notifyAll();
        }
        try {
            for (;;) {
                long token;
                synchronized (this) {
                    if (live == null) break;
                    var handle = live;
                    token = handle.token; retire(handle);
                }
                Runnable claimed = Weak.finalizeNow(token);
                if (claimed instanceof Bootstrap bootstrap) bootstrap.release();
                if (claimed != null) Weak.complete(token);
            }
        } finally { if (interrupted) Thread.currentThread().interrupt(); }
    }
    public static ManagedWeaks current(Node node) { return Language.currentState(node).getWeaks(); }
}
