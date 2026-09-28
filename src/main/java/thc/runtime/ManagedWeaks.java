// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.nodes.Node;
import java.util.ArrayList;
import java.util.HashMap;
import thc.Language;
import static thc.runtime.RuntimeServiceStatus.fault;

/** PARTIAL weak support: retain registrations until explicit finalization/close, not guest GC. */
public final class ManagedWeaks {
    private static final class Handle {
        final ManagedWeaks owner;
        Handle(ManagedWeaks owner) { this.owner = owner; }
    }
    private static final class Payload {
        final Object key, value, action;
        final ArrayList<Runnable> callbacks = new ArrayList<>();
        Payload(Object key, Object value, Object action) { this.key = key; this.value = value; this.action = action; }
    }
    private static final WeakResult DEAD = new WeakResult(0L, null); // Invalid payload, never a fabricated no-op action.
    private final HashMap<Handle, Payload> live = new HashMap<>();
    private boolean closed;

    private Handle handle(Object value) {
        if (!(value instanceof Handle handle)) throw fault("Expected a context-owned Weak#");
        if (closed || handle.owner != this) throw fault("Disposed or foreign Weak# context");
        return handle;
    }
    @TruffleBoundary public synchronized Object make(Object key, Object value, Object action) {
        if (closed) throw fault("Weak# context is disposed");
        if (key == null || value == null) throw fault("Weak# key and value require boxed carriers");
        // Never force a key, value, or action. Private handles have identity equality.
        var handle = new Handle(this);
        live.put(handle, new Payload(key, value, action));
        return handle;
    }
    @TruffleBoundary public synchronized WeakResult dereference(Object value) {
        var payload = live.get(handle(value));
        return payload == null ? DEAD : new WeakResult(1L, payload.value);
    }

    /** One-address C callbacks use a zero environment flag. */
    @TruffleBoundary public synchronized long addCFinalizer(ManagedAddress function, ManagedAddress address,
            long flag, Object weak, SulongCbits provider) {
        var callback = function.finalizerFunction$org_intelligence_thc();
        if (callback == null) throw fault("Expected an original C function label");
        callback.requireOwner(provider);
        if (flag != 0L) throw fault("Original C finalizer requires a one-address ABI");
        var payload = live.get(handle(weak));
        if (payload == null) return 0L;
        // The zero-flag RTS form ignores environment; lowering checks its Addr# carrier.
        if (callback.getSymbol().equals("free")) Language.currentState(null).getNativeAllocations$org_intelligence_thc().requireFreeTarget(address);
        else if (address != ManagedAddress.Companion.nullAddress()) address.requireByteRegion$org_intelligence_thc(0L, false);
        payload.callbacks.add(0, () -> callback.invoke(address));
        return 1L;
    }
    @TruffleBoundary public synchronized long addCallback(Object value, Runnable callback) {
        var payload = live.get(handle(value));
        if (payload == null) return 0L;
        payload.callbacks.add(0, callback); // RTS prepends: explicit finalize visits newest first.
        return 1L;
    }
    @TruffleBoundary public WeakResult finalize(Object value) {
        // Publish DEAD before calling C; neither Sulong nor guest code runs under this monitor.
        Payload payload;
        synchronized (this) { payload = live.remove(handle(value)); }
        if (payload == null) return DEAD;
        for (var callback : payload.callbacks) callback.run();
        return payload.action == null ? DEAD : new WeakResult(1L, payload.action);
    }

    /** rts_setMainThread consumes the KEY (ThreadId#), not the boxed ThreadId value. */
    @TruffleBoundary public synchronized MainThreadWeakKey mainThreadKey(Object value, GuestThreads threads) {
        var weak = handle(value);
        var payload = live.get(weak);
        if (payload == null) throw fault("Main thread requires a live Weak#");
        threadKey(payload, threads);
        return new MainThreadWeakKey(this, weak, threads);
    }
    private GuestThreadId threadKey(Payload payload, GuestThreads threads) {
        if (!(payload.key instanceof GuestThreadId key)) throw fault("Main thread Weak# key is not a ThreadId#");
        if (key.getOwner() != threads) throw fault("Main thread Weak# key belongs to another context");
        return key;
    }
    // No key or callback escapes. The thread service checks the original canonical
    // carrier; the returned number is still only a snapshot.
    @TruffleBoundary synchronized Long mainThreadJavaId(Object value, GuestThreads threads) {
        if (closed) return null;
        var payload = live.get(handle(value));
        return payload == null ? null : threads.liveJavaId$org_intelligence_thc(threadKey(payload, threads));
    }
    public synchronized int retainedCount() { return live.size(); }
    public synchronized void close() { closed = true; live.clear(); }
    public static ManagedWeaks current(Node node) { return Language.currentState(node).getWeaks$org_intelligence_thc(); }
}
