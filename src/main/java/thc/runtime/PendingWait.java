// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.exception.AbstractTruffleException;
import com.oracle.truffle.api.nodes.Node;

/** An internal cut retains the exact uncommitted operation, never a new queue entry. */
public final class PendingWait extends AbstractTruffleException implements InternalGuestControl {
    interface Operation {
        Object resume();
        boolean cancel();
        boolean ready();
        GuestThreadStatus status();
    }
    final Operation operation;
    final GuestThreads.GuestThread owner;
    final long generation;
    final MaskingState mask;
    volatile boolean rescued;
    private PendingWait(Operation operation, GuestThreads.GuestThread owner, Node node) {
        super("Internal guest pending-operation suspension", null, 0, node);
        this.operation = operation; this.owner = owner; mask = SynchronousMasking.current(node);
        owner.waitReady = false;
        if (owner.waitGeneration == Long.MAX_VALUE) throw new RuntimeFault("Guest wait generation exhausted");
        generation = ++owner.waitGeneration;
        owner.wait = this;
    }
    static PendingWait capture(Operation operation, Node node) {
        GuestThreads.GuestThread owner = GuestThreads.suspendingCurrent(node);
        return owner == null ? null : new PendingWait(operation, owner, node);
    }
    public Object resume() {
        boolean captured = false;
        try { return operation.resume(); }
        catch (PendingWait cut) { captured = true; throw cut; }
        finally { if (!captured && owner.wait == this) owner.wait = null; }
    }
    void wake() { owner.waitReady = true; GuestThreads.wakeSuspended(owner); }
    static PendingWait of(Object value) {
        var seen = new java.util.IdentityHashMap<Object, Boolean>();
        for (;;) {
            if (value != null && seen.put(value, Boolean.TRUE) != null) throw new RuntimeFault("Suspended guest dependency cycle");
            if (value instanceof PendingWait pending) return pending;
            if (value instanceof ThunkSuspended suspended) value = suspended.getThunk().getValue();
            else if (value instanceof CallSegmentSuspended suspended) value = suspended.getSegment().getValue();
            else {
                SavedGuestContinuation saved = SavedGuestContinuations.savedGuestContinuation(value);
                if (saved == null) return null;
                value = saved.getYielded();
            }
        }
    }
}
