// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
import java.lang.management.GarbageCollectorMXBean
import java.lang.management.ManagementFactory
import thc.Language

/** JVM-wide collector counters, not per-THC-context GC or GHC RTS statistics.
 * Collection time is the MXBean's approximate cumulative elapsed milliseconds,
 * not stop-the-world time, CPU time, or the sum of independent pause durations.
 * Calls sample independently; no collection or global instrumentation is enabled. */
internal object RuntimeGCServices {
    @Suppress("UNUSED_PARAMETER")
    @TruffleBoundary
    fun query(state: Language.State, selector: Int, index: Long, detail: Long): Long =
        queryJvm(selector, index, detail)

    internal fun queryJvm(
        selector: Int, index: Long, detail: Long,
        collectors: () -> List<GarbageCollectorMXBean> = ManagementFactory::getGarbageCollectorMXBeans
    ): Long {
        if (selector !in 300..303) fault("Unknown GC query selector: $selector")
        if (index < 0 || index > Int.MAX_VALUE.toLong()) fault("Invalid GC collector index: $index")
        if (selector == 300 && index != 0L) fault("Collector count requires zero index")
        if (selector == 301) {
            if (detail < -1L) fault("Invalid GC collector name offset: $detail")
        } else if (detail != 0L) fault("Numeric GC queries require zero detail")
        return try {
            val beans = collectors()
            if (selector == 300) return beans.size.toLong()
            val bean = beans.getOrNull(index.toInt()) ?: fault("Invalid GC collector index: $index")
            if (!bean.isValid) return RuntimeServiceStatus.UNAVAILABLE
            when (selector) {
                301 -> RuntimeServiceStatus.text(bean.name, detail)
                302 -> bean.collectionCount.let { if (it < 0) RuntimeServiceStatus.UNAVAILABLE else it }
                else -> bean.collectionTime.let { if (it < 0) RuntimeServiceStatus.UNAVAILABLE else it }
            }
        } catch (_: SecurityException) { RuntimeServiceStatus.DENIED }
          catch (_: UnsupportedOperationException) { RuntimeServiceStatus.UNSUPPORTED }
    }
}
