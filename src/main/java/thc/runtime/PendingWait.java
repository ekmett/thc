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
    private java.util.ArrayList<Thunk> updates;
    private volatile boolean abandoned;
    PendingWait(Operation operation, GuestThreads.GuestThread owner, MaskingState mask, Node node) {
        super("Internal guest pending-operation suspension", null, 0, node);
        this.operation = operation; this.owner = owner; this.mask = mask;
        owner.waitReady = false;
        if (owner.waitGeneration == Long.MAX_VALUE) throw new RuntimeFault("Guest wait generation exhausted");
        generation = ++owner.waitGeneration;
        owner.wait = this;
    }
    static PendingWait capture(Operation operation, Node node) {
        GuestThreads.GuestThread owner = GuestThreads.suspendingCurrent(node);
        return owner == null ? null : new PendingWait(operation, owner, SynchronousMasking.current(node), node);
    }
    synchronized void retainUpdate(Thunk thunk) {
        if (abandoned) throw new RuntimeFault("Suspended guest evaluator terminated");
        if (updates == null) updates = new java.util.ArrayList<>();
        for (Thunk update : updates) if (update == thunk) return;
        updates.add(thunk);
    }
    /** Host terminal cleanup cannot leave an independently retained shared update waiting for a dead evaluator. */
    void abandon() {
        java.util.ArrayList<Thunk> pending;
        synchronized (this) { abandoned = true; pending = updates; updates = null; }
        if (pending == null) return;
        for (Thunk thunk : pending) synchronized (thunk.getMonitor()) {
            if (thunk.getState() == 5 && of(thunk.getValue()) == this) {
                thunk.setValue(new RuntimeFault("Suspended guest evaluator terminated"));
                thunk.setTarget(null); thunk.setEnvironment(null); thunk.setOwner(null); thunk.setState(3); thunk.notifyUpdate();
            }
        }
    }
    boolean abandoned() { return abandoned; }
    public Object resume() {
        boolean captured = false;
        try {
            if (abandoned) throw new RuntimeFault("Suspended guest evaluator terminated");
            return operation.resume();
        }
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
