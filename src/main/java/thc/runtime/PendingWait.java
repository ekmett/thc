// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.bytecode.ContinuationResult;
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
    private static final class Update {
        final Thunk thunk;
        Object continuation;
        Update(Thunk thunk, Object continuation) { this.thunk = thunk; this.continuation = continuation; }
    }
    private final RuntimeFault terminalFailure = new RuntimeFault("Suspended guest evaluator terminated");
    private java.util.ArrayList<Update> updates;
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
    synchronized void retainUpdate(Thunk thunk, Object continuation) {
        if (abandoned) throw terminalFailure;
        if (updates == null) updates = new java.util.ArrayList<>();
        for (int i = 0; i < updates.size(); ++i) {
            Update update = updates.get(i);
            if (update.thunk == thunk) { update.continuation = continuation; return; }
        }
        updates.add(new Update(thunk, continuation));
    }
    /** Host terminal cleanup cannot leave an independently retained shared update waiting for a dead evaluator. */
    void abandon() {
        java.util.ArrayList<Update> pending;
        synchronized (this) { abandoned = true; pending = updates; updates = null; }
        if (pending == null) return;
        // Obligations and failure were allocated before native arm could fail; terminal publication allocates nothing.
        for (int i = 0; i < pending.size(); ++i) {
            Update update = pending.get(i);
            Thunk thunk = update.thunk;
            synchronized (thunk.getMonitor()) {
                if (thunk.getState() == 5 && thunk.getValue() == update.continuation) {
                    thunk.setValue(terminalFailure);
                    thunk.setTarget(null); thunk.setEnvironment(null); thunk.setOwner(null); thunk.setState(3); thunk.notifyUpdate();
                }
            }
        }
    }
    boolean abandoned() { return abandoned; }
    /** Resume the host wait protocol; callers continue guest execution after it returns. */
    @TruffleBoundary public Object resume() {
        boolean captured = false;
        try {
            if (abandoned) throw terminalFailure;
            return operation.resume();
        }
        catch (PendingWait cut) { captured = true; throw cut; }
        finally { if (!captured && owner.wait == this) owner.wait = null; }
    }
    void wake() { owner.waitReady = true; GuestThreads.wakeSuspended(owner); }
    /** Inspect saved host wait carriers without forcing or continuing guest code. */
    static PendingWait of(Object value) {
        // Keep absence visible to partial evaluation before the saved-chain walk.
        if (value == null) return null;
        if (value instanceof PendingWait pending) return pending;
        return findSavedWait(value);
    }
    @TruffleBoundary private static PendingWait findSavedWait(Object value) {
        ContinuationVisited seen = null;
        Object first = null, second = null, third = null;
        try {
            for (;;) {
                if (value instanceof PendingWait pending) return pending;
                // Only carriers with successors need cycle membership.
                if (!(value instanceof ThunkSuspended) && !(value instanceof CallSegmentSuspended) &&
                    !(value instanceof SavedGuestContinuation) && !(value instanceof ContinuationResult)) return null;
                if (value != null) {
                    if (seen == null) {
                        if (value == first || value == second || value == third)
                            throw new RuntimeFault("Suspended guest dependency cycle");
                        if (first == null) first = value;
                        else if (second == null) second = value;
                        else if (third == null) third = value;
                        else {
                            seen = ContinuationVisited.acquire();
                            seen.add(first); seen.add(second); seen.add(third); seen.add(value);
                            first = null; second = null; third = null;
                        }
                    } else if (!seen.add(value)) throw new RuntimeFault("Suspended guest dependency cycle");
                }
                if (value instanceof ThunkSuspended suspended) value = suspended.getThunk().getValue();
                else if (value instanceof CallSegmentSuspended suspended) value = suspended.getSegment().getValue();
                else if (value instanceof ContinuationResult saved) value = saved.getResult();
                else value = ((SavedGuestContinuation) value).getYielded();
            }
        } finally {
            if (seen != null) seen.release();
        }
    }
}
