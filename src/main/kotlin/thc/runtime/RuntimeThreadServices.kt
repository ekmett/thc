// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
import java.lang.management.ManagementFactory
import java.lang.management.ThreadMXBean
import thc.Language

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
internal object RuntimeThreadServices {
    @TruffleBoundary
    fun query(state: Language.State, selector: Int, index: Long, detail: Long): Long =
        query(state.threads, state.env.isNativeAccessAllowed, selector, index, detail)

    internal fun query(threads: GuestThreads, nativeAccess: Boolean, selector: Int,
                       index: Long, detail: Long): Long {
        if (selector !in 100..110) fault("Unknown thread service selector: $selector")
        if (detail != 0L || index < 0 || (selector !in 109..110 && index != 0L))
            fault("Invalid thread service query indices")
        return when (selector) {
            100 -> if (Thread.currentThread().isVirtual) 2L else 1L
            101 -> threads.currentIdentity().capability
            102 -> if (threads.currentIdentity().capabilityLocked) 1L else 0L
            103 -> if (Thread.currentThread().isVirtual) RuntimeServiceStatus.UNSUPPORTED
                else affinitySupport(threads.cpuAffinity, nativeAccess)
            104 -> if (threads.currentIdentity().affinityApplied) 1L else 0L
            in 105..107 -> accounting(selector)
            else -> {
                val affinity = threads.cpuAffinity
                if (selector != 108 && index >= affinity.count.toLong())
                    fault("Eligible CPU index is outside the context snapshot")
                val support = affinitySupport(affinity, nativeAccess)
                if (support < 0) support
                else if (selector == 108) affinity.count.toLong()
                else affinity.coordinate(index.toInt())?.let {
                    if (selector == 109) it.group.toLong() else it.processor.toLong()
                } ?: RuntimeServiceStatus.UNAVAILABLE
            }
        }
    }

    private fun affinitySupport(affinity: CpuAffinity, nativeAccess: Boolean): Long = when {
        !nativeAccess -> RuntimeServiceStatus.DENIED
        affinity.mode != CpuAffinityMode.UNAVAILABLE -> affinity.mode.ordinal.toLong()
        System.getProperty("os.name", "").let { it.startsWith("Linux") || it.startsWith("Windows") } ->
            RuntimeServiceStatus.UNAVAILABLE
        else -> RuntimeServiceStatus.UNSUPPORTED
    }

    /** Public MXBeans do not attribute these counters to virtual threads. Never
     * inspect or report a carrier thread, and never enable global accounting.
     * The injected bean factory lets tests cover disabled/denied providers
     * without changing JVM-wide instrumentation settings. */
    internal fun accounting(selector: Int, virtual: Boolean = Thread.currentThread().isVirtual,
                            bean: () -> ThreadMXBean = ManagementFactory::getThreadMXBean): Long {
        if (selector !in 105..107) fault("Unknown thread accounting selector: $selector")
        if (virtual) return RuntimeServiceStatus.UNSUPPORTED
        return try {
            val management = bean()
            val value = if (selector == 107) {
                val allocation = management as? com.sun.management.ThreadMXBean
                    ?: return RuntimeServiceStatus.UNSUPPORTED
                if (!allocation.isThreadAllocatedMemorySupported) return RuntimeServiceStatus.UNSUPPORTED
                if (!allocation.isThreadAllocatedMemoryEnabled) return RuntimeServiceStatus.DISABLED
                allocation.currentThreadAllocatedBytes
            } else {
                if (!management.isCurrentThreadCpuTimeSupported) return RuntimeServiceStatus.UNSUPPORTED
                if (!management.isThreadCpuTimeEnabled) return RuntimeServiceStatus.DISABLED
                if (selector == 105) management.currentThreadCpuTime else management.currentThreadUserTime
            }
            if (value < 0) RuntimeServiceStatus.UNAVAILABLE else value
        } catch (_: SecurityException) {
            RuntimeServiceStatus.DENIED
        } catch (_: UnsupportedOperationException) {
            RuntimeServiceStatus.UNSUPPORTED
        }
    }
}
