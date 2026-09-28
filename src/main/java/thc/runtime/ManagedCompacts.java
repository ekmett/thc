// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import java.util.List;
import java.util.WeakHashMap;
import static thc.runtime.ManagedMutVar.completedBoxedIdentity;
import static thc.runtime.RuntimeServiceStatus.fault;

/** Weak keys never retain compacted data. Values are identity tokens, never regions,
 * which would introduce weak-key/value cycles. Admitted keys have JVM identity equality. */
public final class ManagedCompacts {
    private final WeakHashMap<Object, Object> membership = new WeakHashMap<>();
    public ManagedCompact require(Object value) {
        if (!(value instanceof ManagedCompact region)) throw fault("Expected Compact#");
        if (region.getOwner() != this) throw fault("Compact# belongs to another context");
        return region;
    }
    @TruffleBoundary public synchronized boolean contains(ManagedCompact region, Object value) {
        return membership.get(completedBoxedIdentity(value)) == region.identity();
    }
    @TruffleBoundary public synchronized boolean containsAny(Object value) {
        return membership.containsKey(completedBoxedIdentity(value));
    }
    @TruffleBoundary public synchronized void record(ManagedCompact region, List<ManagedCompact.Allocation> values) {
        for (var allocation : values) membership.put(allocation.value(), region.identity());
    }
}
