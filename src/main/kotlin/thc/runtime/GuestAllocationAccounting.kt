// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory

/** Actual JVM heap allocation, including guest runtime bookkeeping. Native/Sulong
 * allocation is outside this target counter; this does not enforce a limit. */
internal object GuestAllocationAccounting {
    private val bean = try {
        (ManagementFactory.getThreadMXBean() as? ThreadMXBean)?.takeIf { it.isThreadAllocatedMemorySupported }?.also {
            if (!it.isThreadAllocatedMemoryEnabled) it.isThreadAllocatedMemoryEnabled = true
        }
    } catch (_: RuntimeException) { null }

    // Availability failures must never interrupt guest-thread completion or
    // strand its pending async senders. Explicit counter operations report them.
    fun sample(threadId: Long): Long = try { bean?.getThreadAllocatedBytes(threadId) ?: -1L }
        catch (_: RuntimeException) { -1L }

    fun bytes(threadId: Long): Long {
        val bytes = sample(threadId)
        if (bytes < 0) fault("Thread allocation accounting is unavailable for this carrier")
        return bytes
    }
}
