// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import thc.Language;
import static thc.runtime.RuntimeFault.fault;

/** Opaque anyToAddr# identities are weak roots and never expose a JVM pointer. */
public final class HeapAddresses {
    private static final class Key extends WeakReference<Object> {
        private final int hash;
        Key(Object value, ReferenceQueue<Object> queue) { super(value, queue); hash = System.identityHashCode(value); }
        @Override public int hashCode() { return hash; }
        @Override public boolean equals(Object other) {
            Object value = get();
            return this == other || other instanceof Key key && value != null && value == key.get();
        }
    }
    public static final class Handle {
        private final HeapAddresses owner;
        private final long id;
        private final WeakReference<Object> value;
        Handle(HeapAddresses owner, long id, Object value) {
            this.owner = owner; this.id = id; this.value = new WeakReference<>(value);
        }
        public HeapAddresses getOwner() { return owner; }
        public long getId() { return id; }
        public WeakReference<Object> getValue() { return value; }
    }
    private final ReferenceQueue<Object> queue = new ReferenceQueue<>();
    private final HashMap<Key, Handle> entries = new HashMap<>();
    private long nextId = 1;
    private boolean closed;

    @TruffleBoundary public synchronized Handle handle(Object raw) {
        if (closed || nextId <= 0) throw fault("Heap-address context is closed or exhausted");
        Object value = ManagedMutVar.completedBoxedIdentity(raw);
        if (value == null) throw fault("anyToAddr# requires a guest value");
        if (value instanceof Thunk) throw fault("anyToAddr# requires an evaluated value, not a thunk");
        Reference<?> dead;
        while ((dead = queue.poll()) != null) entries.remove(dead);
        try {
            var handle = entries.get(new Key(value, null));
            if (handle == null) {
                handle = new Handle(this, nextId++, value);
                entries.put(new Key(value, queue), handle);
            }
            return handle;
        } finally { Reference.reachabilityFence(value); }
    }
    public ManagedAddress address(Object value) { return ManagedAddress.fromHeapHandle(handle(value)); }
    public synchronized Handle require(Handle handle) {
        if (closed || handle.owner != this) throw fault("Foreign or closed heap address");
        return handle;
    }
    public Handle require(ManagedAddress address) {
        var handle = address.heapHandle();
        if (handle == null) throw fault("addrToAny# requires an opaque guest heap address");
        return require(handle);
    }
    public Object dereference(ManagedAddress address) {
        Object value = require(address).value.get();
        if (value == null) throw fault("Dangling guest heap address");
        return value;
    }
    public synchronized void close() { closed = true; entries.clear(); }
    public static HeapAddresses current() { return Language.currentState(null).heapAddresses; }
}
