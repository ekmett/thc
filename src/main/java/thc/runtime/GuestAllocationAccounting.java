// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;

/** Actual JVM heap allocation, including runtime bookkeeping, not a guest limit. */
public final class GuestAllocationAccounting {
    private GuestAllocationAccounting() {}
    private static final ThreadMXBean BEAN = allocationBean();

    private static ThreadMXBean allocationBean() {
        try {
            var bean = ManagementFactory.getThreadMXBean();
            if (!(bean instanceof ThreadMXBean allocation) || !allocation.isThreadAllocatedMemorySupported()) return null;
            if (!allocation.isThreadAllocatedMemoryEnabled()) allocation.setThreadAllocatedMemoryEnabled(true);
            return allocation;
        } catch (RuntimeException unavailable) { return null; }
    }

    /** Availability must not interrupt completion or strand pending async senders. */
    public static long sample(long threadId) {
        try { return BEAN == null ? -1L : BEAN.getThreadAllocatedBytes(threadId); }
        catch (RuntimeException unavailable) { return -1L; }
    }

    public static long bytes(long threadId) {
        long bytes = sample(threadId);
        if (bytes < 0) throw RuntimeFault.fault("Thread allocation accounting is unavailable for this carrier");
        return bytes;
    }
}
