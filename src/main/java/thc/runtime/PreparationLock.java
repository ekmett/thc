// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.TruffleContext;
import com.oracle.truffle.api.TruffleSafepoint;
import java.util.concurrent.locks.ReentrantLock;

/** Reentrant ownership of inert lowering, with cooperative guest contention. */
public final class PreparationLock implements AutoCloseable {
    private final ReentrantLock lock = new ReentrantLock();
    private final TruffleContext context;
    public PreparationLock() { this(null); }
    public PreparationLock(TruffleContext context) { this.context = context; }

    public PreparationLock acquire() {
        if (lock.tryLock()) return this;
        if (context == null || !context.isEntered()) { lock.lock(); return this; }
        do {
            try (var admission = LoomScheduler.suspendCurrentGuest()) {
                TruffleSafepoint.setBlockedThreadInterruptible(null, waiting -> {
                    waiting.lockInterruptibly();
                    waiting.unlock();
                }, lock);
            }
            // Never retain lowering ownership while waiting for HEC readmission.
        } while (!lock.tryLock());
        return this;
    }
    public boolean isHeldByCurrentThread() { return lock.isHeldByCurrentThread(); }
    @Override public void close() { lock.unlock(); }
}
