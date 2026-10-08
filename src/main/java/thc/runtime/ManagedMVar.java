// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.TruffleSafepoint;
import com.oracle.truffle.api.nodes.Node;
import java.util.ArrayDeque;
import java.util.concurrent.CancellationException;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/** The lock orders cell changes, queue removal and request commitment. Wakeups
 * deliver committed results, never competition for a cell. No guest code, payload
 * equality or evaluation runs under the lock. Null is a value, not an empty marker. */
public final class ManagedMVar {
    private final ReentrantLock lock = new ReentrantLock();
    private boolean full;
    private Object value;
    private final ArrayDeque<Request> takers = new ArrayDeque<>();
    private final ArrayDeque<Request> readers = new ArrayDeque<>();
    private final ArrayDeque<Request> putters = new ArrayDeque<>();
    private static final TruffleSafepoint.InterruptibleFunction<Request, Object> AWAIT_REQUEST = Request::await;

    public static ManagedMVar require(Object value) {
        if (value instanceof ManagedMVar cell) return cell;
        throw new RuntimeFault("Expected managed MVar#");
    }
    private static void check(boolean condition) { if (!condition) throw new IllegalStateException("Check failed."); }
    @TruffleBoundary(transferToInterpreterOnException = false)
    public Object take(Node node) { return take(node, false); }
    @TruffleBoundary(transferToInterpreterOnException = false)
    public Object take(Node node, boolean async) { return awaitAt(new Request(this, Operation.TAKE, null, async ? node : null), node); }
    @TruffleBoundary public Object read(Node node) { return read(node, false); }
    @TruffleBoundary public Object read(Node node, boolean async) { return awaitAt(new Request(this, Operation.READ, null, async ? node : null), node); }
    @TruffleBoundary public void put(Object value, Node node) { put(value, node, false); }
    @TruffleBoundary public void put(Object value, Node node, boolean async) { awaitAt(new Request(this, Operation.PUT, value, async ? node : null), node); }
    private Object awaitAt(Request request, Node node) {
        try {
            // Harmless safepoint interruptions retry the callback, not registration.
            return TruffleSafepoint.setBlockedThreadInterruptibleFunction(node, AWAIT_REQUEST, request);
        } finally { request.cancel(); }
    }
    @TruffleBoundary public MVarReadResult tryTake() {
        lock.lock();
        try { return full ? new MVarReadResult(true, takeLocked()) : new MVarReadResult(false, null); }
        finally { lock.unlock(); }
    }
    @TruffleBoundary public MVarReadResult tryRead() {
        lock.lock();
        try { return new MVarReadResult(full, full ? value : null); }
        finally { lock.unlock(); }
    }
    @TruffleBoundary public boolean tryPut(Object value) {
        lock.lock();
        try { if (full) return false; putLocked(value); return true; }
        finally { lock.unlock(); }
    }
    @TruffleBoundary public boolean isEmpty() {
        lock.lock(); try { return !full; } finally { lock.unlock(); }
    }
    private Object takeLocked() {
        check(full);
        Object result = value;
        var putter = putters.pollFirst();
        if (putter == null) { full = false; value = null; }
        else {
            // Transfer the oldest blocked put before either participant resumes.
            value = putter.offeredValueLocked();
            putter.commitLocked(null);
        }
        return result;
    }
    private void putLocked(Object offered) {
        check(!full);
        // All read waiters observe the next value before the oldest taker consumes it.
        while (!readers.isEmpty()) readers.removeFirst().commitLocked(offered);
        var taker = takers.pollFirst();
        if (taker == null) { value = offered; full = true; }
        else taker.commitLocked(offered);
    }
    public enum Operation { TAKE, READ, PUT }
    public enum RequestState { PENDING, COMMITTED, CANCELLED }
    public record PendingCounts(int takers, int readers, int putters) {
        public int getTakers() { return takers; }
        public int getReaders() { return readers; }
        public int getPutters() { return putters; }
        @Override public String toString() { return "PendingCounts(takers=" + takers + ", readers=" + readers + ", putters=" + putters + ")"; }
    }
    /** Stable request identity is also the seam for deterministic protocol tests. */
    public static final class Request {
        private ManagedMVar cell;
        private final ReentrantLock lock;
        private final Condition completed;
        private final Operation operation;
        private final Node checkpoint;
        private RequestState status = RequestState.PENDING;
        private boolean submitted;
        private boolean queued;
        private Object offeredValue;
        private Object result;
        public Request(ManagedMVar cell, Operation operation) { this(cell, operation, null); }
        public Request(ManagedMVar cell, Operation operation, Object offered) { this(cell, operation, offered, null); }
        public Request(ManagedMVar cell, Operation operation, Object offered, Node checkpoint) {
            this.cell = java.util.Objects.requireNonNull(cell); lock = cell.lock; completed = lock.newCondition();
            this.operation = java.util.Objects.requireNonNull(operation); offeredValue = offered; this.checkpoint = checkpoint;
        }
        public RequestState getState() { lock.lock(); try { return status; } finally { lock.unlock(); } }
        public boolean isQueued() { lock.lock(); try { return queued; } finally { lock.unlock(); } }
        public Object pendingPutValue() { lock.lock(); try { return offeredValue; } finally { lock.unlock(); } }
        public void submitLocked() {
            check(lock.isHeldByCurrentThread());
            if (submitted || status != RequestState.PENDING) return;
            submitted = true;
            switch (operation) {
                case TAKE -> { if (cell.full) commitLocked(cell.takeLocked()); else enqueueLocked(cell.takers); }
                case READ -> { if (cell.full) commitLocked(cell.value); else enqueueLocked(cell.readers); }
                case PUT -> { if (cell.full) enqueueLocked(cell.putters); else { cell.putLocked(offeredValue); commitLocked(null); } }
            }
        }
        private void enqueueLocked(ArrayDeque<Request> queue) { queue.addLast(this); queued = true; }
        public Object offeredValueLocked() {
            check(lock.isHeldByCurrentThread() && status == RequestState.PENDING); return offeredValue;
        }
        public void commitLocked(Object value) {
            check(lock.isHeldByCurrentThread() && status == RequestState.PENDING);
            result = value; offeredValue = null; queued = false; status = RequestState.COMMITTED;
            // Safepoint retries need the cached result and lock, not the completed cell.
            cell = null; completed.signalAll();
        }
        /** InterruptedException leaves this same request queued at the same position. */
        public Object await() throws InterruptedException {
            if (checkpoint != null) GuestThreads.checkpointCurrent(checkpoint);
            lock.lockInterruptibly();
            try {
                submitLocked();
                while (status == RequestState.PENDING) {
                    var interruption = checkpoint == null ? null : GuestThreads.pollCurrentWithoutYield(checkpoint, true);
                    if (interruption != null) {
                        // Commitment and cancellation share the lock: only uncommitted requests retry.
                        check(cancel());
                        throw new AsyncBlocked(interruption, checkpoint);
                    }
                    var blocked = GuestThreads.blocking(operation == Operation.READ ? GuestThreadStatus.MVAR_READ : GuestThreadStatus.MVAR);
                    try { completed.await(); }
                    finally {
                        lock.unlock();
                        try { blocked.close(); }
                        finally { lock.lock(); }
                    }
                }
                if (status == RequestState.CANCELLED) throw new CancellationException("Managed MVar request cancelled");
                return result;
            } finally { lock.unlock(); }
        }
        /** A terminal caller may revoke Pending, never roll back Committed. */
        public boolean cancel() {
            lock.lock();
            try {
                if (status != RequestState.PENDING) return false;
                if (queued) {
                    var queue = switch (operation) { case TAKE -> cell.takers; case READ -> cell.readers; case PUT -> cell.putters; };
                    check(queue.remove(this));
                }
                queued = false; offeredValue = null; result = null; status = RequestState.CANCELLED;
                cell = null; completed.signalAll();
                return true;
            } finally { lock.unlock(); }
        }
        /** Bounded test observer, not used by guest execution. */
        public boolean hasWaitingThread() { lock.lock(); try { return lock.hasWaiters(completed); } finally { lock.unlock(); } }
    }
    // Direct tests register without entering a Truffle context. Production registers interruptibly in await.
    public Request beginTake() {
        lock.lock(); try { var request = new Request(this, Operation.TAKE); request.submitLocked(); return request; } finally { lock.unlock(); }
    }
    public Request beginRead() {
        lock.lock(); try { var request = new Request(this, Operation.READ); request.submitLocked(); return request; } finally { lock.unlock(); }
    }
    public Request beginPut(Object value) {
        lock.lock(); try { var request = new Request(this, Operation.PUT, value); request.submitLocked(); return request; } finally { lock.unlock(); }
    }
    public PendingCounts pendingCounts() {
        lock.lock(); try { return new PendingCounts(takers.size(), readers.size(), putters.size()); } finally { lock.unlock(); }
    }
}
