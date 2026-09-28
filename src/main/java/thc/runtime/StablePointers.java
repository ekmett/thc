// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.nodes.Node;
import java.util.Arrays;
import java.util.HashMap;
import thc.Language;

/** StablePtr# roots belong to a context; their AddrRep carrier is opaque. */
public final class StablePointers {
    public static final class Handle {
        private final StablePointers owner;
        private final long id;
        public Handle(StablePointers owner, long id) { this.owner = owner; this.id = id; }
        public StablePointers getOwner() { return owner; }
        public long getId() { return id; }
    }
    private static final class Entry {
        final Handle handle;
        final Object value;
        StablePointerToken token;
        Entry(Handle handle, Object value) { this.handle = handle; this.value = value; }
    }
    private final HashMap<Long, Entry> entries = new HashMap<>();
    private final HashMap<Long, Handle> tokens = new HashMap<>();
    private final Handle[] sharedCAFStores = new Handle[SharedCAFStore.values().length];
    private long nextId = 1;
    private boolean disposed;

    @TruffleBoundary public synchronized ManagedAddress make(Object value) {
        if (disposed || nextId <= 0) throw RuntimeFault.fault("StablePtr context is closed or exhausted");
        if (value == null) throw RuntimeFault.fault("StablePtr# requires a lifted referent");
        var handle = new Handle(this, nextId++);
        entries.put(handle.id, new Entry(handle, value));
        return ManagedAddress.Companion.fromStableHandle$org_intelligence_thc(handle);
    }
    private Entry entry(ManagedAddress address) {
        var handle = address.stableHandle$org_intelligence_thc();
        if (handle == null) throw RuntimeFault.fault("Expected an opaque StablePtr#");
        var entry = entries.get(handle.id);
        if (disposed || handle.owner != this || entry == null || entry.handle != handle)
            throw RuntimeFault.fault("Stale or foreign StablePtr#");
        return entries.get(handle.id);
    }
    @TruffleBoundary public synchronized Object dereference(ManagedAddress address) { return entry(address).value; }
    @TruffleBoundary public synchronized void validate(ManagedAddress address) { entry(address); }
    @TruffleBoundary public void requireNativeAccess() {
        var state = Language.currentState(null);
        if (state.getStablePointers() != this) throw RuntimeFault.fault("StablePtr belongs to another context");
        if (!state.getEnv().isNativeAccessAllowed()) throw RuntimeFault.fault("StablePtr C transport requires native access");
    }
    /** Materialize only when a StablePtr crosses C FFI or exposes native bits. */
    @TruffleBoundary public synchronized StablePointerToken nativeTransport(ManagedAddress address) {
        requireNativeAccess();
        var entry = entry(address);
        if (entry.token != null) return entry.token;
        var token = new StablePointerToken(this);
        try {
            tokens.put(token.getBits(), entry.handle);
            entry.token = token;
            return token;
        } catch (Throwable failure) { token.close(); throw failure; }
    }
    public long nativeToken(ManagedAddress address) { return nativeTransport(address).getBits(); }
    /** Only this context's exact live identities regain StablePtr authority. */
    @TruffleBoundary public synchronized ManagedAddress recoverToken(long bits) {
        if (disposed) throw RuntimeFault.fault("StablePtr context is closed");
        var handle = tokens.get(bits);
        return handle == null ? null : ManagedAddress.Companion.fromStableHandle$org_intelligence_thc(handle);
    }
    @TruffleBoundary public synchronized boolean equal(ManagedAddress left, ManagedAddress right) {
        var first = entry(left);
        var second = entry(right);
        return first.handle == second.handle;
    }
    @TruffleBoundary public synchronized void free(ManagedAddress address) {
        var entry = entry(address);
        var handle = entry.handle;
        for (var shared : sharedCAFStores) if (shared == handle)
            throw RuntimeFault.fault("RTS shared CAF StablePtr# remains owned until context disposal");
        entries.remove(handle.id);
        if (entry.token != null) { tokens.remove(entry.token.getBits()); entry.token.close(); }
    }
    @TruffleBoundary public synchronized ManagedAddress getOrSetSharedCAF(SharedCAFStore store, ManagedAddress candidate) {
        if (disposed) throw RuntimeFault.fault("StablePtr context is closed");
        var supplied = candidate == ManagedAddress.Companion.nullAddress() ? null : entry(candidate).handle;
        var current = sharedCAFStores[store.ordinal()];
        if (current != null) return ManagedAddress.Companion.fromStableHandle$org_intelligence_thc(current);
        if (supplied == null) return ManagedAddress.Companion.nullAddress();
        sharedCAFStores[store.ordinal()] = supplied;
        return candidate;
    }
    public synchronized void close() {
        disposed = true;
        Arrays.fill(sharedCAFStores, null);
        tokens.clear();
        for (var entry : entries.values()) if (entry.token != null) entry.token.close();
        entries.clear();
    }
    public static StablePointers current(Node node) { return Language.currentState(node).getStablePointers(); }
}
