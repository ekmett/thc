// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.TruffleSafepoint;

/** Dormant carrier infrastructure. A payload exists only after strong runnable transfer. */
final class GuestWakePort {
    private GuestThreads.GuestThread runnable;
    LoomScheduler.Admission admission;
    synchronized void deliver(GuestThreads.GuestThread owner) {
        if (runnable != null && runnable != owner) throw new IllegalStateException("Duplicate guest wake owner");
        runnable = owner; notifyAll();
    }
    synchronized GuestThreads.GuestThread take() { var owner = runnable; runnable = null; return owner; }
    synchronized GuestThreads.GuestThread peek() { return runnable; }
    private synchronized void waitReady() throws InterruptedException { while (runnable == null) wait(); }
    void await() { TruffleSafepoint.setBlockedThreadInterruptible(null, GuestWakePort::waitReady, this); }
}
