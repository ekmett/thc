// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.nodes.Node;
import java.lang.ref.Cleaner;
import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import thc.Language;
import static thc.runtime.RuntimeServiceStatus.fault;

/** Collect actionless identity/MutVar/MVar keys; one canonical MALLOC free may retire without guest execution. */
public final class ManagedWeaks {
    private static final class Handle {
        final ManagedWeaks owner;
        Handle(ManagedWeaks owner) { this.owner = owner; }
    }
    private static final class Cleaners { static final Cleaner INSTANCE = Cleaner.create(); }
    private static final class OwnedFreeCleanup implements Runnable {
        final WeakReference<ManagedNativeAllocations.Owner> owner;
        private boolean armed = true;
        OwnedFreeCleanup(WeakReference<ManagedNativeAllocations.Owner> owner) { this.owner = owner; }
        synchronized void disarm() { armed = false; }
        @Override public void run() {
            synchronized (this) { if (!armed) return; armed = false; }
            var retained = owner.get();
            if (retained != null) retained.requestRetirement();
        }
    }
    private static final class Payload extends WeakReference<Object> {
        final Handle handle;
        Object key, value;
        final Object action;
        final boolean keyOwnedValue;
        final ArrayList<Runnable> callbacks = new ArrayList<>();
        boolean ownedFree;
        WeakReference<ManagedNativeAllocations.Owner> freeOwner;
        OwnedFreeCleanup cleanup;
        Cleaner.Cleanable cleanable;
        Payload(Handle handle, Object key, Object value, Object action, ReferenceQueue<Object> queue) {
            super(action == null && (key == value || key instanceof ManagedMutVar || key instanceof ManagedMVar) ? key : null, queue);
            this.handle = handle; this.action = action;
            keyOwnedValue = action == null && key != value && (key instanceof ManagedMutVar || key instanceof ManagedMVar);
            if (keyOwnedValue) {
                if (key instanceof ManagedMutVar cell) cell.retainWeakValue(handle, value);
                else ((ManagedMVar) key).retainWeakValue(handle, value);
            } else if (action != null || key != value) { this.key = key; this.value = value; }
        }
        Object key() { return key == null ? get() : key; }
        Object value(Object retainedKey) {
            Object result = key != null ? value : retainedKey == null ? null
                : !keyOwnedValue ? retainedKey : retainedKey instanceof ManagedMutVar cell
                    ? cell.weakValue(handle) : ((ManagedMVar) retainedKey).weakValue(handle);
            Reference.reachabilityFence(retainedKey);
            return result;
        }
        void detach() {
            if (keyOwnedValue && key == null) {
                Object retainedKey = get();
                if (retainedKey instanceof ManagedMutVar cell) cell.releaseWeakValue(handle);
                else if (retainedKey instanceof ManagedMVar cell) cell.releaseWeakValue(handle);
            }
        }
        Cleaner.Cleanable disarm() {
            if (cleanup != null) cleanup.disarm();
            var cancelled = cleanable; cleanable = null; cleanup = null;
            return cancelled;
        }
        void addCallback(Object retainedKey, Runnable callback) {
            if (key == null) {
                value = value(retainedKey);
                key = retainedKey; // Retain both before detaching key-owned storage.
                if (keyOwnedValue) {
                    if (retainedKey instanceof ManagedMutVar cell) cell.releaseWeakValue(handle);
                    else ((ManagedMVar) retainedKey).releaseWeakValue(handle);
                }
                clear();
            }
            callbacks.add(0, callback); // RTS prepends: explicit finalize visits newest first.
        }
    }
    private static final WeakResult DEAD = new WeakResult(0L, null); // Invalid payload, never a fabricated no-op action.
    private final HashMap<Handle, Payload> live = new HashMap<>();
    private final ReferenceQueue<Object> collected = new ReferenceQueue<>();
    private final ArrayList<WeakReference<ManagedNativeAllocations.Owner>> pendingFrees = new ArrayList<>();
    private boolean closed;

    private void reap() {
        for (Object stale; (stale = collected.poll()) != null;) {
            var payload = (Payload) stale;
            // A callback may have promoted this entry before a queued record
            // was observed. Such an entry now has explicit strong ownership.
            if (payload.key == null) retire(payload);
        }
    }
    private void retire(Payload payload) {
        if (live.remove(payload.handle, payload) && payload.ownedFree) {
            if (payload.freeOwner != null) pendingFrees.add(payload.freeOwner);
            payload.ownedFree = false; payload.freeOwner = null;
        }
    }

    /** Optional managed-GC fallback/inspection; Cleaner and completion also retire eligible owners. */
    @TruffleBoundary void drainOwnedFrees() {
        ArrayList<WeakReference<ManagedNativeAllocations.Owner>> pending;
        synchronized (this) {
            if (closed) return;
            reap();
            // Java may clear a referent before publishing its queue record.
            for (var iterator = live.values().iterator(); iterator.hasNext();) {
                var payload = iterator.next();
                if (payload.ownedFree && payload.key() == null) {
                    iterator.remove();
                    if (payload.freeOwner != null) pendingFrees.add(payload.freeOwner);
                    payload.ownedFree = false; payload.freeOwner = null;
                }
            }
            pending = new ArrayList<>(pendingFrees); pendingFrees.clear();
        }
        var allocations = Language.currentState(null).getNativeAllocations();
        for (int i = 0; i < pending.size(); i++) {
            try {
                if (!allocations.tryFree(pending.get(i).get())) synchronized (this) {
                    if (!closed) pendingFrees.add(pending.get(i));
                }
            } catch (Throwable failure) {
                // The attempted token is consumed even on failure. Preserve unattempted tokens.
                synchronized (this) {
                    if (!closed) pendingFrees.addAll(pending.subList(i + 1, pending.size()));
                }
                throw failure;
            }
        }
    }
    private Handle handle(Object value) {
        if (!(value instanceof Handle handle)) throw fault("Expected a context-owned Weak#");
        if (closed || handle.owner != this) throw fault("Disposed or foreign Weak# context");
        reap();
        return handle;
    }
    @TruffleBoundary public synchronized Object make(Object key, Object value, Object action) {
        if (closed) throw fault("Weak# context is disposed");
        if (key == null || value == null) throw fault("Weak# key and value require boxed carriers");
        // Never force a key, value, or action. Private handles have identity equality.
        reap();
        var handle = new Handle(this);
        live.put(handle, new Payload(handle, key, value, action, collected));
        return handle;
    }
    @TruffleBoundary public synchronized WeakResult dereference(Object value) {
        var handle = handle(value);
        var payload = live.get(handle);
        Object result = payload == null ? null : payload.value(payload.key());
        if (result == null) { if (payload != null) retire(payload); return DEAD; }
        return new WeakResult(1L, result);
    }

    /** A nonzero flag selects the environment/object C ABI; zero ignores the environment. */
    @TruffleBoundary public long addCFinalizer(ManagedAddress function, ManagedAddress address,
            long flag, ManagedAddress environment, Object weak, SulongCbits provider) {
        Cleaner.Cleanable cancelled;
        synchronized (this) {
            var callback = function.finalizerFunction();
            if (callback == null) throw fault("Expected an original C function label");
            callback.requireOwner(provider);
            callback.requireArity(flag == 0L ? 1 : 2);
            var handle = handle(weak);
            var payload = live.get(handle);
            Object key = payload == null ? null : payload.key();
            if (key == null) { if (payload != null) retire(payload); return 0L; }
            // The zero-flag RTS form ignores environment; lowering checks its Addr# carrier.
            if (callback.getSymbol().equals("free")) Language.currentState(null).getNativeAllocations().requireFreeTarget(address);
            if (payload.action == null && payload.key == null
                    && payload.callbacks.isEmpty() && !payload.ownedFree && provider.isOwnedFree(callback)
                    && Language.currentState(null).getWeaks() == this) {
                var owner = Language.currentState(null).getNativeAllocations().ownedFreeTarget(address);
                if (owner != null || address == ManagedAddress.nullAddress()) {
                    if (owner != null) {
                        var reference = new WeakReference<>(owner);
                        var cleanup = new OwnedFreeCleanup(reference);
                        var cleanable = Cleaners.INSTANCE.register(key, cleanup);
                        payload.freeOwner = reference; payload.cleanup = cleanup; payload.cleanable = cleanable;
                    }
                    payload.ownedFree = true;
                    Reference.reachabilityFence(key); return 1L;
                }
            }
            cancelled = payload.disarm();
            payload.addCallback(key, flag == 0L ? () -> callback.invoke(address) : () -> callback.invoke(environment, address));
        }
        if (cancelled != null) cancelled.clean();
        return 1L;
    }
    @TruffleBoundary public long addCallback(Object value, Runnable callback) {
        Cleaner.Cleanable cancelled;
        synchronized (this) {
            var handle = handle(value);
            var payload = live.get(handle);
            Object key = payload == null ? null : payload.key();
            if (key == null) { if (payload != null) retire(payload); return 0L; }
            cancelled = payload.disarm();
            payload.addCallback(key, callback);
        }
        if (cancelled != null) cancelled.clean();
        return 1L;
    }
    @TruffleBoundary public WeakResult finalize(Object value) {
        // Publish DEAD before calling C; neither Sulong nor guest code runs under this monitor.
        Payload payload;
        Cleaner.Cleanable cancelled = null;
        synchronized (this) {
            var handle = handle(value);
            payload = live.get(handle);
            if (payload != null) {
                Object key = payload.key();
                if (key == null) { retire(payload); payload = null; }
                else {
                    cancelled = payload.disarm();
                    live.remove(handle); payload.detach(); Reference.reachabilityFence(key);
                }
            }
        }
        if (cancelled != null) cancelled.clean();
        if (payload == null) return DEAD;
        for (var callback : payload.callbacks) callback.run();
        if (payload.ownedFree) {
            var state = Language.currentState(null);
            if (state.getWeaks() != this) throw fault("C finalizer belongs to another THC context");
            var owner = payload.freeOwner == null ? null : payload.freeOwner.get();
            if (payload.freeOwner != null && owner == null)
                throw fault("Native free requires a live allocation from this context");
            state.getNativeAllocations().free(owner == null ? ManagedAddress.nullAddress()
                : ManagedAddress.fromNativeAllocation(owner));
        }
        return payload.action == null ? DEAD : new WeakResult(1L, payload.action);
    }

    /** rts_setMainThread consumes the KEY (ThreadId#), not the boxed ThreadId value. */
    @TruffleBoundary public synchronized MainThreadWeakKey mainThreadKey(Object value, GuestThreads threads) {
        var weak = handle(value);
        var payload = live.get(weak);
        Object key = payload == null ? null : payload.key();
        if (key == null) { if (payload != null) retire(payload); throw fault("Main thread requires a live Weak#"); }
        if (!(key instanceof GuestThreadId identity)) throw fault("Main thread Weak# key is not a ThreadId#");
        threads.requireIdentity(identity);
        return new MainThreadWeakKey(this, weak, threads);
    }
    // No key or callback escapes. The thread service checks the original canonical
    // carrier; the returned number is still only a snapshot.
    @TruffleBoundary synchronized Long mainThreadJavaId(Object value, GuestThreads threads) {
        if (closed) return null;
        var handle = handle(value);
        var payload = live.get(handle);
        Object key = payload == null ? null : payload.key();
        // mainThreadKey admitted this immutable key before publishing the capability.
        // Collection/close expires liveness; it does not invalidate the query itself.
        if (key == null) { if (payload != null) retire(payload); return null; }
        return threads.liveJavaId((GuestThreadId) key);
    }
    public synchronized int retainedCount() { reap(); return live.size(); }
    public void close() {
        var cancelled = new ArrayList<Cleaner.Cleanable>();
        synchronized (this) {
            closed = true;
            for (var payload : live.values()) {
                var cleanable = payload.disarm();
                if (cleanable != null) cancelled.add(cleanable);
                payload.detach();
            }
            live.clear(); pendingFrees.clear();
            while (collected.poll() != null) { /* Release queued registrations too. */ }
        }
        for (var cleanable : cancelled) cleanable.clean();
    }
    public static ManagedWeaks current(Node node) { return Language.currentState(node).getWeaks(); }
}
