// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

/** Original advance carriers; a terminal result has a null snapshot and zero words. */
public final class ManagedStackAdvance {
    private final ManagedStackSnapshot snapshot;
    private final long wordOffset, hasNext;
    public ManagedStackAdvance(ManagedStackSnapshot snapshot, long wordOffset, long hasNext) {
        this.snapshot = snapshot; this.wordOffset = wordOffset; this.hasNext = hasNext;
    }
    public ManagedStackSnapshot getSnapshot() { return snapshot; }
    public long getWordOffset() { return wordOffset; }
    public long getHasNext() { return hasNext; }
}
