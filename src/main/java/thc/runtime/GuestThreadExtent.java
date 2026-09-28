// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;
/** A lexical blocking extent follows guest re-entry and is restored even on an unwind. */
public final class GuestThreadExtent implements AutoCloseable {
    private final GuestThreadId identity;
    private final GuestThreadStatus previous;
    private final LoomScheduler.Admission admission;
    public GuestThreadExtent(GuestThreadId identity, GuestThreadStatus previous, LoomScheduler.Admission admission) {
        this.identity = identity; this.previous = previous; this.admission = admission;
    }
    @Override public void close() {
        try { if (admission != null) admission.owner().resumeGuest(admission, null); }
        finally {
            if (identity != null && !identity.status.getTerminal()) {
                if (previous == null) throw new IllegalStateException("Required value was null.");
                identity.status = previous;
            }
        }
    }
}
