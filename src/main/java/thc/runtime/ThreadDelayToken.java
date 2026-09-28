// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.TruffleSafepoint;
import com.oracle.truffle.api.nodes.Node;

/** A single deadline survives safepoint wakeups and captured guest continuations. */
public final class ThreadDelayToken {
    private final GuestThreads owner;
    private final long deadline;
    public ThreadDelayToken(GuestThreads owner, long microseconds) {
        this.owner = owner;
        deadline = System.nanoTime() + Math.max(0L, Math.min(Long.MAX_VALUE / 1000L, microseconds)) * 1000L;
    }
    @TruffleBoundary(transferToInterpreterOnException = false)
    public void await(Node node, boolean async, boolean compiledAtCut) {
        if (GuestThreads.current(node) != owner) throw RuntimeFault.fault("Delay belongs to another guest context");
        TruffleSafepoint.setBlockedThreadInterruptibleFunction(node,
            (TruffleSafepoint.InterruptibleFunction<ThreadDelayToken, Object>) token -> {
                for (;;) {
                    if (async) {
                        AsyncRequest incoming = owner.poll(node, true);
                        if (incoming != null) {
                            incoming.compiledCapture = compiledAtCut;
                            throw new AsyncBlocked(incoming, node);
                        }
                    }
                    long remaining = token.deadline - System.nanoTime();
                    if (remaining <= 0) break;
                    try (GuestThreadExtent ignored = GuestThreads.Companion.blocking$org_intelligence_thc(GuestThreadStatus.DELAY)) {
                        Thread.sleep(remaining / 1_000_000L, (int) (remaining % 1_000_000L));
                    }
                }
                return null;
            }, this);
    }
}
