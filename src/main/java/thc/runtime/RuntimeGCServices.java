// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.function.Supplier;
import thc.Language;
import static thc.runtime.RuntimeServiceStatus.fault;

/** JVM-wide collector counters, not per-THC-context GC or GHC RTS statistics.
 * Collection time is the MXBean's approximate cumulative elapsed milliseconds,
 * not stop-the-world time, CPU time, or the sum of independent pause durations.
 * Calls sample independently; no collection or global instrumentation is enabled. */
public final class RuntimeGCServices {
    private RuntimeGCServices() {}
    private static final Supplier<List<GarbageCollectorMXBean>> JVM_COLLECTORS =
        ManagementFactory::getGarbageCollectorMXBeans;

    @TruffleBoundary
    public static long query(Language.State state, int selector, long index, long detail) {
        return queryJvm(selector, index, detail);
    }

    public static long queryJvm(int selector, long index, long detail) {
        return queryJvm(selector, index, detail, JVM_COLLECTORS);
    }

    public static long queryJvm(int selector, long index, long detail,
                                Supplier<? extends List<? extends GarbageCollectorMXBean>> collectors) {
        if (selector < 300 || selector > 303) throw fault("Unknown GC query selector: " + selector);
        if (index < 0 || index > Integer.MAX_VALUE) throw fault("Invalid GC collector index: " + index);
        if (selector == 300 && index != 0L) throw fault("Collector count requires zero index");
        if (selector == 301) {
            if (detail < -1L) throw fault("Invalid GC collector name offset: " + detail);
        } else if (detail != 0L) throw fault("Numeric GC queries require zero detail");
        try {
            var beans = collectors.get();
            if (selector == 300) return beans.size();
            if (index >= beans.size()) throw fault("Invalid GC collector index: " + index);
            var bean = beans.get((int) index);
            if (bean == null) throw fault("Invalid GC collector index: " + index);
            if (!bean.isValid()) return RuntimeServiceStatus.UNAVAILABLE;
            if (selector == 301) return RuntimeServiceStatus.text(bean.getName(), detail);
            long value = selector == 302 ? bean.getCollectionCount() : bean.getCollectionTime();
            return value < 0 ? RuntimeServiceStatus.UNAVAILABLE : value;
        } catch (SecurityException ignored) { return RuntimeServiceStatus.DENIED; }
        catch (UnsupportedOperationException ignored) { return RuntimeServiceStatus.UNSUPPORTED; }
    }
}
