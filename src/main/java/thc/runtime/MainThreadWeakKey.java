// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

/** Retain this capability, not a permanent ThreadId#/Java-ID snapshot. */
public final class MainThreadWeakKey {
    private final ManagedWeaks owner;
    private final Object weak;
    private final GuestThreads threads;
    MainThreadWeakKey(ManagedWeaks owner, Object weak, GuestThreads threads) {
        this.owner = owner; this.weak = weak; this.threads = threads;
    }
    public Long liveJavaId() { return owner.mainThreadJavaId(weak, threads); }
}
