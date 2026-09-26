// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
import java.lang.management.ManagementFactory
import java.lang.management.MemoryUsage
import thc.Language

/** Read-only JVM-wide usage, plus explicitly context-owned libc allocations.
 * Each scalar call samples independently; this is not an atomic heap snapshot.
 * Native bytes are requested sizes, not allocator overhead or all native memory. */
internal object RuntimeMemoryServices {
    @TruffleBoundary
    fun query(state: Language.State, selector: Int, index: Long, detail: Long): Long {
        if (selector in 200..207) return queryJvm(selector, index, detail)
        if (index != 0L || detail != 0L) fault("Memory queries require zero index and detail")
        return when (selector) {
            208 -> try { state.nativeAllocations.liveBytes() }
                catch (_: ArithmeticException) { RuntimeServiceStatus.UNAVAILABLE }
            209 -> state.nativeAllocations.liveCount().toLong()
            else -> fault("Unknown memory query selector: $selector")
        }
    }

    /** Provider injection exercises unsupported/denied/unknown JVM values without
     * changing JVM-global management settings or manufacturing a host bean. */
    internal fun queryJvm(
        selector: Int, index: Long, detail: Long,
        usage: (Boolean) -> MemoryUsage? = { heap ->
            val bean = ManagementFactory.getMemoryMXBean()
            if (heap) bean.heapMemoryUsage else bean.nonHeapMemoryUsage
        }
    ): Long {
        if (selector !in 200..207) fault("Unknown JVM memory query selector: $selector")
        if (index != 0L || detail != 0L) fault("Memory queries require zero index and detail")
        return try {
            val sample = usage(selector < 204) ?: return RuntimeServiceStatus.UNAVAILABLE
            val value = when ((selector - 200) % 4) {
                0 -> sample.used
                1 -> sample.committed
                2 -> sample.max
                else -> sample.init
            }
            if (value < 0) RuntimeServiceStatus.UNAVAILABLE else value
        } catch (_: SecurityException) { RuntimeServiceStatus.DENIED }
          catch (_: UnsupportedOperationException) { RuntimeServiceStatus.UNSUPPORTED }
    }
}
