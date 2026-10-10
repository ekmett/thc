// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.TruffleSafepoint;
import com.oracle.truffle.api.nodes.Node;

/** Parent and child name logical continuations, never carrier threads. */
public final class CapturedAsyncRequest {
    private final CapturedAsyncRequests requests;
    private final Object parent;
    private final CallSegment child;
    private final Object payload;
    private final Object monitor = new Object();
    private volatile CapturedRequestState state = CapturedRequestState.PENDING;

    public CapturedAsyncRequest(CapturedAsyncRequests requests, Object parent, CallSegment child, Object payload) {
        this.requests = requests;
        this.parent = parent;
        this.child = child;
        this.payload = payload;
    }
    public Object getParent() { return parent; }
    public CallSegment getChild() { return child; }
    public Object getPayload() { return payload; }
    public CapturedRequestState getState() { return state; }
    void requireOwner(Node node) { requests.requireOwner(node); }

    /** Called under the exact parent's claim monitor, before consuming the continuation. */
    public boolean commit(Object parent, CallSegment child) {
        synchronized (monitor) {
            if (this.parent != parent || this.child != child || state != CapturedRequestState.PENDING) return false;
            state = CapturedRequestState.COMMITTED;
            monitor.notifyAll();
            return true;
        }
    }
    public boolean commit$org_intelligence_thc(Object parent, CallSegment child) { return commit(parent, child); }

    @TruffleBoundary public void acknowledge() {
        synchronized (monitor) {
            if (state != CapturedRequestState.COMMITTED) throw new IllegalStateException("Async delivery was not committed");
            state = CapturedRequestState.ACKNOWLEDGED;
            monitor.notifyAll();
        }
        requests.finished(this);
    }
    public boolean cancel() {
        synchronized (monitor) {
            if (state != CapturedRequestState.PENDING) return false;
            state = CapturedRequestState.CANCELLED;
            monitor.notifyAll();
        }
        requests.finished(this);
        return true;
    }
    public void fail() {
        synchronized (monitor) {
            if (state != CapturedRequestState.PENDING && state != CapturedRequestState.COMMITTED) return;
            state = CapturedRequestState.FAILED;
            monitor.notifyAll();
        }
        requests.finished(this);
    }
    public void fail$org_intelligence_thc() { fail(); }
    void contextClosed() { fail(); }

    @TruffleBoundary public CapturedRequestState await(Node node) {
        try { return TruffleSafepoint.setBlockedThreadInterruptibleFunction(node, WAIT_FOR_TERMINAL, this); }
        catch (Throwable failure) { cancel(); throw failure; }
    }
    private CapturedRequestState waitForTerminal() throws InterruptedException {
        while (true) {
            GuestThreadExtent blocked = null;
            try { synchronized (monitor) {
                if (state != CapturedRequestState.PENDING && state != CapturedRequestState.COMMITTED) return state;
                blocked = GuestThreads.blocking(GuestThreadStatus.THROW_TO); monitor.wait();
            } } finally { if (blocked != null) blocked.close(); }
        }
    }
    private static final TruffleSafepoint.InterruptibleFunction<CapturedAsyncRequest, CapturedRequestState>
        WAIT_FOR_TERMINAL = CapturedAsyncRequest::waitForTerminal;
}
