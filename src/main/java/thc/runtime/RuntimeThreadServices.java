// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.function.Supplier;
import thc.Language;
import static thc.runtime.RuntimeServiceStatus.fault;

/** Read-only observations of the current guest's Java thread. The management
 * counters include host/runtime work on that platform thread, not only Haskell
 * execution, and begin when the JVM starts accounting. They are neither live
 * heap measurements nor guest allocation-budget counters.
 *
 * 100 kind (1 platform, 2 virtual); 101 logical capability; 102 logical lock;
 * 103 native affinity mode (1 advisory, 2 pinned); 104 recorded fork acceptance;
 * 105 CPU ns; 106 user ns; 107 allocated heap bytes; 108 eligible CPU count;
 * 109 OS processor group; 110 OS processor index.
 *
 * 108..110 expose the context's initial quota-bounded dense capability mapping,
 * not the current OS mask, an unbounded topology, or continuing pin guarantees.
 * Only 109/110 accept a nonzero index. No selector uses detail.
 */
public final class RuntimeThreadServices {
    private RuntimeThreadServices() {}
    private static final Supplier<ThreadMXBean> JVM_THREADS = ManagementFactory::getThreadMXBean;

    @TruffleBoundary
    public static long query(Language.State state, int selector, long index, long detail) {
        return query(state.getThreads(), state.getEnv().isNativeAccessAllowed(), selector, index, detail);
    }

    public static long query(GuestThreads threads, boolean nativeAccess, int selector, long index, long detail) {
        if (selector < 100 || selector > 110) throw fault("Unknown thread service selector: " + selector);
        if (detail != 0L || index < 0 || ((selector < 109 || selector > 110) && index != 0L))
            throw fault("Invalid thread service query indices");
        return switch (selector) {
            case 100 -> Thread.currentThread().isVirtual() ? 2L : 1L;
            case 101 -> threads.currentIdentity().getCapability();
            case 102 -> threads.currentIdentity().getCapabilityLocked() ? 1L : 0L;
            case 103 -> Thread.currentThread().isVirtual() ? RuntimeServiceStatus.UNSUPPORTED :
                affinitySupport(threads.getCpuAffinity(), nativeAccess);
            case 104 -> threads.currentIdentity().getAffinityApplied() ? 1L : 0L;
            case 105, 106, 107 -> accounting(selector);
            default -> {
                var affinity = threads.getCpuAffinity();
                if (selector != 108 && index >= affinity.getCount())
                    throw fault("Eligible CPU index is outside the context snapshot");
                long support = affinitySupport(affinity, nativeAccess);
                if (support < 0) yield support;
                if (selector == 108) yield affinity.getCount();
                var coordinate = affinity.coordinate((int) index);
                yield coordinate == null ? RuntimeServiceStatus.UNAVAILABLE :
                    selector == 109 ? coordinate.getGroup() : coordinate.getProcessor();
            }
        };
    }

    private static long affinitySupport(CpuAffinity affinity, boolean nativeAccess) {
        if (!nativeAccess) return RuntimeServiceStatus.DENIED;
        if (affinity.getMode() != CpuAffinityMode.UNAVAILABLE) return affinity.getMode().ordinal();
        String os = System.getProperty("os.name", "");
        return os.startsWith("Linux") || os.startsWith("Windows") ?
            RuntimeServiceStatus.UNAVAILABLE : RuntimeServiceStatus.UNSUPPORTED;
    }

    public static long accounting(int selector) {
        return accounting(selector, Thread.currentThread().isVirtual(), JVM_THREADS);
    }

    public static long accounting(int selector, Supplier<? extends ThreadMXBean> bean) {
        return accounting(selector, Thread.currentThread().isVirtual(), bean);
    }

    public static long accounting(int selector, boolean virtual) {
        return accounting(selector, virtual, JVM_THREADS);
    }

    /** Public MXBeans do not attribute these counters to virtual threads. Never
     * inspect or report a carrier thread, and never enable global accounting.
     * The injected bean factory lets tests cover disabled/denied providers
     * without changing JVM-wide instrumentation settings. */
    public static long accounting(int selector, boolean virtual, Supplier<? extends ThreadMXBean> bean) {
        if (selector < 105 || selector > 107) throw fault("Unknown thread accounting selector: " + selector);
        if (virtual) return RuntimeServiceStatus.UNSUPPORTED;
        try {
            var management = bean.get();
            long value;
            if (selector == 107) {
                if (!(management instanceof com.sun.management.ThreadMXBean allocation))
                    return RuntimeServiceStatus.UNSUPPORTED;
                if (!allocation.isThreadAllocatedMemorySupported()) return RuntimeServiceStatus.UNSUPPORTED;
                if (!allocation.isThreadAllocatedMemoryEnabled()) return RuntimeServiceStatus.DISABLED;
                value = allocation.getCurrentThreadAllocatedBytes();
            } else {
                if (!management.isCurrentThreadCpuTimeSupported()) return RuntimeServiceStatus.UNSUPPORTED;
                if (!management.isThreadCpuTimeEnabled()) return RuntimeServiceStatus.DISABLED;
                value = selector == 105 ? management.getCurrentThreadCpuTime() : management.getCurrentThreadUserTime();
            }
            return value < 0 ? RuntimeServiceStatus.UNAVAILABLE : value;
        } catch (SecurityException ignored) { return RuntimeServiceStatus.DENIED; }
        catch (UnsupportedOperationException ignored) { return RuntimeServiceStatus.UNSUPPORTED; }
    }
}
