// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;
import java.util.Objects;
/** A lexical blocking extent follows guest re-entry and is restored even on an unwind. */
public final class GuestThreadExtent implements AutoCloseable {
    private final GuestThreadId identity;
    private final GuestThreadStatus previous;
    public GuestThreadExtent(GuestThreadId identity, GuestThreadStatus previous) { this.identity = identity; this.previous = previous; }
    @Override public void close() {
        if (identity != null && !identity.status.getTerminal()) {
            if (previous == null) throw new IllegalStateException("Required value was null.");
            identity.status = previous;
        }
    }
}
