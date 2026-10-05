// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.nodes.Node;
import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import thc.Language;
import static thc.runtime.RuntimeServiceStatus.fault;

/** Collect actionless identity/MutVar/MVar keys; managed GC calls can retire one canonical owned free. */
public final class ManagedWeaks {
    private static final class Handle {
        final ManagedWeaks owner;
        Handle(ManagedWeaks owner) { this.owner = owner; }
    }
    private static final class Payload extends WeakReference<Object> {
        final Handle handle;
        Object key, value;
        final Object action;
        final boolean keyOwnedValue;
        final ArrayList<Runnable> callbacks = new ArrayList<>();
        boolean ownedFree;
        ManagedNativeAllocations.Owner freeOwner;
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
    private final ArrayList<ManagedNativeAllocations.Owner> pendingFrees = new ArrayList<>();
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
            pendingFrees.add(payload.freeOwner);
            payload.ownedFree = false; payload.freeOwner = null;
        }
    }

    /** Claim DEAD before native effects; busy borrows retain only their native owner for a later GC call. */
    @TruffleBoundary void drainOwnedFrees() {
        ArrayList<ManagedNativeAllocations.Owner> pending;
        synchronized (this) {
            if (closed) return;
            reap();
            // Java may clear a referent before publishing its queue record.
            for (var iterator = live.values().iterator(); iterator.hasNext();) {
                var payload = iterator.next();
                if (payload.ownedFree && payload.key() == null) {
                    iterator.remove(); pendingFrees.add(payload.freeOwner);
                    payload.ownedFree = false; payload.freeOwner = null;
                }
            }
            pending = new ArrayList<>(pendingFrees); pendingFrees.clear();
        }
        var allocations = Language.currentState(null).getNativeAllocations();
        for (int i = 0; i < pending.size(); i++) {
            try {
                if (!allocations.tryFree(pending.get(i))) synchronized (this) {
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

    /** One-address C callbacks use a zero environment flag. */
    @TruffleBoundary public synchronized long addCFinalizer(ManagedAddress function, ManagedAddress address,
            long flag, Object weak, SulongCbits provider) {
        var callback = function.finalizerFunction();
        if (callback == null) throw fault("Expected an original C function label");
        callback.requireOwner(provider);
        if (flag != 0L) throw fault("Original C finalizer requires a one-address ABI");
        var handle = handle(weak);
        var payload = live.get(handle);
        Object key = payload == null ? null : payload.key();
        if (key == null) { if (payload != null) retire(payload); return 0L; }
        // The zero-flag RTS form ignores environment; lowering checks its Addr# carrier.
        if (callback.getSymbol().equals("free")) Language.currentState(null).getNativeAllocations().requireFreeTarget(address);
        else if (address != ManagedAddress.nullAddress()) address.requireByteRegion(0L, false);
        if (payload.action == null && payload.key == null
                && payload.callbacks.isEmpty() && !payload.ownedFree && provider.isOwnedFree(callback)
                && Language.currentState(null).getWeaks() == this) {
            var owner = Language.currentState(null).getNativeAllocations().ownedFreeTarget(address);
            if (owner != null || address == ManagedAddress.nullAddress()) {
                payload.ownedFree = true; payload.freeOwner = owner;
                Reference.reachabilityFence(key);
                return 1L;
            }
        }
        payload.addCallback(key, () -> callback.invoke(address));
        return 1L;
    }
    @TruffleBoundary public synchronized long addCallback(Object value, Runnable callback) {
        var handle = handle(value);
        var payload = live.get(handle);
        Object key = payload == null ? null : payload.key();
        if (key == null) { if (payload != null) retire(payload); return 0L; }
        payload.addCallback(key, callback);
        return 1L;
    }
    @TruffleBoundary public WeakResult finalize(Object value) {
        // Publish DEAD before calling C; neither Sulong nor guest code runs under this monitor.
        Payload payload;
        synchronized (this) {
            var handle = handle(value);
            payload = live.get(handle);
            if (payload != null) {
                Object key = payload.key();
                if (key == null) { retire(payload); payload = null; }
                else { live.remove(handle); payload.detach(); Reference.reachabilityFence(key); }
            }
        }
        if (payload == null) return DEAD;
        for (var callback : payload.callbacks) callback.run();
        if (payload.ownedFree) {
            var state = Language.currentState(null);
            if (state.getWeaks() != this) throw fault("C finalizer belongs to another THC context");
            state.getNativeAllocations().free(payload.freeOwner == null ? ManagedAddress.nullAddress()
                : ManagedAddress.fromNativeAllocation(payload.freeOwner));
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
    public synchronized void close() {
        closed = true;
        for (var payload : live.values()) payload.detach();
        live.clear(); pendingFrees.clear();
        while (collected.poll() != null) { /* Release queued registrations too. */ }
    }
    public static ManagedWeaks current(Node node) { return Language.currentState(node).getWeaks(); }
}
