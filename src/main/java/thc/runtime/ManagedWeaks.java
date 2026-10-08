// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.nodes.Node;
import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import jam.vm.Weak;
import thc.Language;
import static thc.runtime.RuntimeServiceStatus.fault;

/** Jam owns conditional reachability; this context owns guest finalizer execution. */
public final class ManagedWeaks {
    private static final class Handle {
        final ManagedWeaks owner;
        final WeakReference<Object> key;
        long token;
        Handle(ManagedWeaks owner, Object key) { this.owner = owner; this.key = new WeakReference<>(key); }
    }
    // The finalizer deliberately has no edge to this payload: claiming F must not retain V.
    private record Payload(Object value, Finalizer finalizer) { }
    private static final class Finalizer implements Runnable {
        final Handle handle;
        final Object action, runner;
        final CallTarget root;
        final ArrayList<Runnable> callbacks = new ArrayList<>();
        Finalizer(Handle handle, Object action, Object runner, CallTarget root) {
            this.handle = handle; this.action = action; this.runner = runner; this.root = root;
        }
        void callbacks() { for (var callback : callbacks) callback.run(); }
        // Weak.pump() may be called by another host client. Returning means the real
        // carrier has terminated, including cancellation before its Runnable entered.
        @Override public void run() { handle.owner.run(this); }
    }
    /** A host-only drainer: neither one blocked guest nor thread construction stalls other contexts. */
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
                    Weak.complete(claimed);
                    throw failure;
                }
            }
        });
        static void start() { if (!THREAD.isAlive()) throw fault("Weak finalizer drainer terminated"); }
    }
    private static final WeakResult DEAD = new WeakResult(0L, null);
    private final Language.State state;
    private final Language language;
    // No context root points at active V or F. Handles carry only native tokens and a weak key projection.
    private final HashMap<Long, Handle> live = new HashMap<>();
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
        var handle = new Handle(this, key);
        var finalizer = new Finalizer(handle, action, runner,
            action == null ? null : new ForkActionRoot(language, null, true, 2).getCallTarget());
        // Never force K, V or F. Jam's conditional association is the only root of this payload.
        handle.token = Weak.create(key, new Payload(value, finalizer), finalizer);
        try { live.put(handle.token, handle); }
        catch (Throwable failure) {
            if (Weak.finalizeNow(handle.token) != null) Weak.complete(handle.token);
            throw failure;
        }
        Reference.reachabilityFence(key);
        return handle;
    }
    @TruffleBoundary public synchronized WeakResult dereference(Object value) {
        var handle = handle(value);
        var payload = (Payload) Weak.deref(handle.token);
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
    @TruffleBoundary public synchronized long addCallback(Object value, Runnable callback) {
        var handle = handle(value);
        var payload = (Payload) Weak.deref(handle.token);
        if (payload == null) return 0L;
        requireThreads();
        // RTS prepends. Native dereference roots the payload across the mutation.
        payload.finalizer().callbacks.add(0, java.util.Objects.requireNonNull(callback));
        Reference.reachabilityFence(payload);
        return 1L;
    }
    @TruffleBoundary public WeakResult finalize(Object value) {
        Finalizer finalizer;
        synchronized (this) {
            var handle = handle(value);
            finalizer = (Finalizer) Weak.finalizeNow(handle.token);
            if (finalizer == null) return DEAD;
            live.remove(handle.token);
        }
        // The native claim made the registration dead before any effect. Explicit
        // finalize returns the original reusable action; GHC decides when to run it.
        try {
            finalizer.callbacks();
            return finalizer.action == null ? DEAD : new WeakResult(1L, finalizer.action);
        } finally { Weak.complete(finalizer.handle.token); }
    }
    private void run(Finalizer finalizer) {
        synchronized (this) {
            live.remove(finalizer.handle.token);
            if (stopping || finalizer.action == null && finalizer.callbacks.isEmpty()) return;
            running++;
        }
        boolean interrupted = false;
        try {
            state.admitGuestConcurrency();
            var threads = state.getThreads();
            // Construction can block entering Truffle. Never hold the admission monitor here.
            Thread child = threads.newThread(state.getEnv(), () -> execute(finalizer), null, null);
            synchronized (this) {
                if (stopping) return;
                threads.startThread(child);
            }
            for (;;) {
                try { child.join(); break; }
                catch (InterruptedException ignored) { interrupted = true; }
            }
        } finally {
            synchronized (this) { running--; notifyAll(); }
            if (interrupted) Thread.currentThread().interrupt();
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
            finalizer.callbacks();
            if (finalizer.action != null) finalizer.root.call(finalizer.runner, finalizer.action);
        } catch (UncaughtForkAsync uncaught) {
            outcome = GuestThreadStatus.DIED;
            uncaught.request.acknowledge();
        } catch (AsyncDelivery uncaught) {
            outcome = GuestThreadStatus.DIED;
            uncaught.getRequest().acknowledge();
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
            GuestThreadOps.reportHostFailure(state, failure);
            throw propagate(failure);
        }
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
    public synchronized void requestStop() { stopping = true; }
    /** Called after the guest join, before any native provider is disposed. */
    public void close() {
        ArrayList<Handle> abandoned;
        boolean interrupted = false;
        synchronized (this) {
            stopping = true;
            while (running != 0) {
                try { wait(); } catch (InterruptedException ignored) { interrupted = true; }
            }
            closed = true;
            abandoned = new ArrayList<>(live.values()); live.clear();
        }
        try {
            for (var handle : abandoned) if (Weak.finalizeNow(handle.token) != null) Weak.complete(handle.token);
        } finally { if (interrupted) Thread.currentThread().interrupt(); }
    }
    public static ManagedWeaks current(Node node) { return Language.currentState(node).getWeaks(); }
}
