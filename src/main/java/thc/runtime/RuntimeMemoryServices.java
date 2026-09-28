// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import thc.Language;
import static thc.runtime.RuntimeServiceStatus.fault;

/** Read-only JVM-wide usage, plus explicitly context-owned libc allocations.
 * Each scalar call samples independently; this is not an atomic heap snapshot.
 * Native bytes are requested sizes, not allocator overhead or all native memory. */
public final class RuntimeMemoryServices {
    private RuntimeMemoryServices() {}

    @TruffleBoundary
    public static long query(Language.State state, int selector, long index, long detail) {
        if (selector >= 200 && selector <= 207) return queryJvm(selector, index, detail);
        if (index != 0L || detail != 0L) throw fault("Memory queries require zero index and detail");
        return switch (selector) {
            case 208 -> {
                try { yield state.getNativeAllocations().liveBytes$org_intelligence_thc(); }
                catch (ArithmeticException ignored) { yield RuntimeServiceStatus.UNAVAILABLE; }
            }
            case 209 -> state.getNativeAllocations().liveCount$org_intelligence_thc();
            default -> throw fault("Unknown memory query selector: " + selector);
        };
    }

    /** Primitive selector avoids boxing the heap/non-heap flag at this boundary. */
    @FunctionalInterface
    public interface UsageProvider {
        MemoryUsage usage(boolean heap);
    }

    private static final UsageProvider JVM_USAGE = heap -> {
        var bean = ManagementFactory.getMemoryMXBean();
        return heap ? bean.getHeapMemoryUsage() : bean.getNonHeapMemoryUsage();
    };

    public static long queryJvm(int selector, long index, long detail) {
        return queryJvm(selector, index, detail, JVM_USAGE);
    }

    /** Provider injection exercises unsupported/denied/unknown JVM values without
     * changing JVM-global management settings or manufacturing a host bean. */
    public static long queryJvm(int selector, long index, long detail, UsageProvider usage) {
        if (selector < 200 || selector > 207) throw fault("Unknown JVM memory query selector: " + selector);
        if (index != 0L || detail != 0L) throw fault("Memory queries require zero index and detail");
        try {
            MemoryUsage sample = usage.usage(selector < 204);
            if (sample == null) return RuntimeServiceStatus.UNAVAILABLE;
            long value = switch ((selector - 200) % 4) {
                case 0 -> sample.getUsed();
                case 1 -> sample.getCommitted();
                case 2 -> sample.getMax();
                default -> sample.getInit();
            };
            return value < 0 ? RuntimeServiceStatus.UNAVAILABLE : value;
        } catch (SecurityException ignored) { return RuntimeServiceStatus.DENIED; }
        catch (UnsupportedOperationException ignored) { return RuntimeServiceStatus.UNSUPPORTED; }
    }
}
