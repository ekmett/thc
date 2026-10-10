// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.bytecode.ContinuationResult;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;

/** Context-owned requests for the private, exact catch# continuation cut. */
public final class CapturedAsyncRequests {
    private final LinkedHashSet<CapturedAsyncRequest> active = new LinkedHashSet<>();
    private final IdentityHashMap<Object, CapturedAsyncRequest> byParent = new IdentityHashMap<>();
    private boolean closed;

    public synchronized CapturedAsyncRequest submit(Object parent, CallSegment child, Object payload) {
        if (closed) throw new IllegalStateException("Guest context has closed");
        Object parked = null;
        if (parent instanceof Thunk thunk) {
            synchronized (thunk.getMonitor()) { if (thunk.getState() == 5) parked = thunk.getValue(); }
        } else if (parent instanceof CallSegment segment) {
            synchronized (segment.getMonitor()) { if (segment.getState() == 5) parked = segment.getValue(); }
        }
        if (!(parked instanceof ContinuationResult saved) ||
            !(saved.getContinuationRootNode().getSourceRootNode() instanceof BytecodeRoot) ||
            !(saved.getResult() instanceof CallSegmentSuspended suspended) || suspended.getSegment() != child ||
            !child.getCaughtIOAction() || child.getTupleShape() == null)
            throw new IllegalStateException("Async request requires the exact parked catch# action");
        if (byParent.containsKey(parent))
            throw new IllegalStateException("This captured boundary already has a pending request");
        CapturedAsyncRequest request = new CapturedAsyncRequest(this, parent, child, payload);
        active.add(request);
        byParent.put(parent, request);
        return request;
    }

    /** Context authority is this request manager, even if its parent already advanced. */
    synchronized void requireOwner(com.oracle.truffle.api.nodes.Node node) {
        if (closed || thc.Language.currentState(node).getCapturedAsyncRequests() != this)
            throw RuntimeFault.fault("Captured async request belongs to another execution context");
    }

    synchronized void finished(CapturedAsyncRequest request) {
        active.remove(request);
        byParent.remove(request.getParent(), request);
    }

    public void close() {
        ArrayList<CapturedAsyncRequest> pending;
        synchronized (this) {
            closed = true;
            pending = new ArrayList<>(active);
            active.clear();
            byParent.clear();
        }
        for (CapturedAsyncRequest request : pending) request.contextClosed();
    }
}
