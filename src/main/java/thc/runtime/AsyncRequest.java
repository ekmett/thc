// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.TruffleSafepoint;
import com.oracle.truffle.api.nodes.Node;
/** A pending throwTo payload. Only the target's real catch handler may acknowledge it. */
public final class AsyncRequest {
    private final GuestThreads owner;
    final long targetId;
    final Thread target;
    private final Object payload;
    GuestThreads.GuestThread recipient;
    final boolean forceSelf;
    private final Object monitor = new Object();
    private volatile AsyncRequestState state = AsyncRequestState.PENDING;
    volatile Throwable failure;
    /** Set by the bytecode poll before crossing into the mailbox boundary. */
    public volatile boolean compiledCapture;
    public AsyncRequest(GuestThreads owner, long targetId, Thread target, Object payload) { this(owner, targetId, target, payload, false); }
    public AsyncRequest(GuestThreads owner, long targetId, Thread target, Object payload, boolean forceSelf) {
        this.owner = owner; this.targetId = targetId; this.target = target; this.payload = payload; this.forceSelf = forceSelf;
    }
    public long getTargetId() { return targetId; }
    public Thread getTarget() { return target; }
    public Object getPayload() { return payload; }
    public boolean getForceSelf() { return forceSelf; }
    public AsyncRequestState getState() { return state; }
    public Throwable getFailure() { return failure; }
    public void setFailure(Throwable value) { failure = value; }
    public boolean inForeignCallback() { return owner.inForeignCallback(); }
    public void transition(AsyncRequestState next) { synchronized (monitor) { state = next; monitor.notifyAll(); } }
    @TruffleBoundary public void acknowledge() {
        if (!owner.finish(this, AsyncRequestState.ACKNOWLEDGED)) throw new IllegalStateException("Async request is no longer active");
    }
    @TruffleBoundary public boolean cancel() { return owner.finish(this, AsyncRequestState.CANCELLED); }
    @TruffleBoundary public boolean fail() { return fail(null); }
    @TruffleBoundary public boolean fail(Throwable cause) { return owner.finish(this, AsyncRequestState.FAILED, cause); }
    public void finish(AsyncRequestState next) { transition(next); recipient = null; }
    /** Blocking send completion is interruptible and revokes only an unclaimed request. */
    @TruffleBoundary public AsyncRequestState await(Node node) {
        try {
            owner.resume(this);
            return TruffleSafepoint.setBlockedThreadInterruptibleFunction(node,
                (TruffleSafepoint.InterruptibleFunction<AsyncRequest, AsyncRequestState>) request -> request.waitForTerminal(node), this);
        } catch (Throwable failure) {
            if (!(failure instanceof AsyncBlocked)) cancel();
            throw propagate(failure);
        }
    }
    private AsyncRequestState waitForTerminal(Node node) throws InterruptedException {
        while (true) {
            var snapshot = state;
            if (snapshot != AsyncRequestState.PENDING && snapshot != AsyncRequestState.CLAIMED && snapshot != AsyncRequestState.PAUSED) return snapshot;
            var incoming = owner.poll(node, true);
            if (incoming != null) { owner.pause(this); throw new AsyncBlocked(incoming, node); }
            GuestThreadExtent blocked = null;
            try { synchronized (monitor) {
                if (state == AsyncRequestState.PENDING || state == AsyncRequestState.CLAIMED || state == AsyncRequestState.PAUSED) {
                    blocked = GuestThreads.blocking(GuestThreadStatus.THROW_TO); monitor.wait();
                }
            } } finally { if (blocked != null) blocked.close(); }
        }
    }
    @SuppressWarnings("unchecked") private static <E extends Throwable> RuntimeException propagate(Throwable failure) throws E { throw (E) failure; }
}
