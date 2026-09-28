// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

/** Projectors remain paired with program/unit identity after caught exceptions escape. */
public final class ForeignExceptionRegistry {
    public static final class Projector {
        private final ForeignExceptionBridge bridge;
        private final Closure closure;
        private final DataLayout exceptionLayout;
        public Projector(ForeignExceptionBridge bridge, Closure closure, DataLayout exceptionLayout) {
            this.bridge = bridge; this.closure = closure; this.exceptionLayout = exceptionLayout;
        }
        public ForeignExceptionBridge getBridge() { return bridge; }
        public Closure getClosure() { return closure; }
        public DataLayout getExceptionLayout() { return exceptionLayout; }
    }

    private final ArrayList<WeakReference<Projector>> projectors = new ArrayList<>();

    @TruffleBoundary public synchronized void register(Projector projector) {
        for (var iterator = projectors.iterator(); iterator.hasNext();) {
            if (iterator.next().get() == null) iterator.remove();
        }
        for (var reference : projectors) if (reference.get() == projector) return;
        projectors.add(new WeakReference<>(projector));
    }

    @TruffleBoundary public synchronized List<Projector> snapshot() {
        for (var iterator = projectors.iterator(); iterator.hasNext();) {
            if (iterator.next().get() == null) iterator.remove();
        }
        var result = new ArrayList<Projector>();
        for (var reference : projectors) {
            var projector = reference.get();
            if (projector != null) result.add(projector);
        }
        return result;
    }
}
