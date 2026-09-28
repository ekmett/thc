// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.nodes.Node;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import thc.Language;

/** Canonical context-owned identity tokens that do not retain their referents. */
public final class StableNames {
    private static final class Key extends WeakReference<Object> {
        private final int hash;
        Key(Object value, ReferenceQueue<Object> queue) { super(value, queue); hash = System.identityHashCode(value); }
        @Override public int hashCode() { return hash; }
        @Override public boolean equals(Object other) {
            if (this == other) return true;
            Object value = get();
            return value != null && other instanceof Key key && value == key.get();
        }
    }
    private record Name(StableNames owner, long hash) {}
    private final ReferenceQueue<Object> queue = new ReferenceQueue<>();
    private final HashMap<Key, Name> names = new HashMap<>();
    private long nextHash = 1;
    private boolean closed;
    @TruffleBoundary public synchronized Object make(Object value) {
        if (closed || nextHash <= 0) throw new RuntimeFault("StableName context is closed or exhausted");
        if (value == null) throw new RuntimeFault("StableName# requires a boxed referent");
        for (Object stale; (stale = queue.poll()) != null;) names.remove(stale);
        Key key = new Key(value, queue);
        Name name = names.get(key);
        if (name == null) { name = new Name(this, nextHash++); names.put(key, name); }
        return name;
    }
    @TruffleBoundary public synchronized long hash(Object value) {
        if (!(value instanceof Name name)) throw new RuntimeFault("Expected a StableName#");
        if (closed || name.owner() != this) throw new RuntimeFault("Disposed or foreign StableName# context");
        return name.hash();
    }
    public synchronized void close() {
        closed = true; names.clear();
        while (queue.poll() != null) { /* Release queued keys too. */ }
    }
    public static StableNames current(Node node) { return Language.currentState(node).getStableNames(); }
}
