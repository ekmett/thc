// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.TruffleSafepoint;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node;
import java.util.ArrayDeque;
import thc.Language;

/** One context's bounded speculative WHNF work; Force owns every thunk update. */
public final class SparkPool {
    private final Language.State owner;
    private final Language language;
    private final int capacity;
    private final ArrayDeque<Thunk> pending = new ArrayDeque<>();
    private boolean stopped;
    private Thread worker;

    public SparkPool(Language.State owner, Language language, int capacity) {
        if (capacity < 0 || capacity > 65536) throw new IllegalArgumentException("SparkQueueCapacity must be between 0 and 65536");
        if (capacity != 0 && !owner.getEnv().isCreateThreadAllowed())
            throw new IllegalArgumentException("SparkQueueCapacity requires context thread creation permission");
        this.owner = owner; this.language = language; this.capacity = capacity;
    }
    public boolean isEnabled() { return capacity != 0; }
    static SparkPool current(Node node) { return Language.currentState(node).getSparks(); }
    private boolean suitable(Thunk thunk, Node node) {
        var target = thunk.getTarget();
        if (thunk.getState() != 0 || !thunk.getAsynchronousExceptions() || target == null ||
                !(target.getRootNode() instanceof GuestRoot root) ||
                !(root instanceof FunctionRoot function && function.getCapturesContinuations() ||
                  root instanceof BytecodeRoot bytecode && bytecode.isAsyncEnabled() ||
                  root instanceof GhcBCORoot bco && bco.getOwner() == owner)) return false;
        if (root.compilationOwner() == owner.getCompilationOwner()) return true;
        var environment = thunk.getEnvironment();
        return environment != null && environment.getProgram() instanceof Program program && program.belongsToCurrentContext(node);
    }
    void hint(Node node, Object value) { if (capacity != 0) offer(node, value); }
    @TruffleBoundary private synchronized void offer(Node node, Object value) {
        if (stopped || !(value instanceof Thunk thunk) || !suitable(thunk, node) || pending.size() >= capacity) return;
        // A repeated hint does not retain the same thunk many times or crowd out work.
        if (pending.contains(thunk)) return;
        pending.addLast(thunk);
        if (worker == null) {
            owner.admitGuestConcurrency();
            var root = new WorkerRoot(language);
            try {
                worker = owner.getThreads().newThread(owner.getEnv(), () -> run(root), null, node);
                owner.getThreads().startThread(worker);
            } catch (Throwable failure) { stop(); throw failure; }
        }
        notifyAll();
    }
    long count() { return capacity == 0 ? 0L : queuedCount(); }
    @TruffleBoundary private synchronized long queuedCount() { return pending.size(); }
    Thunk poll() { return capacity == 0 ? null : pollQueued(); }
    @TruffleBoundary private synchronized Thunk pollQueued() {
        while (!pending.isEmpty()) {
            var thunk = pending.removeFirst();
            if (thunk.getState() == 0) return thunk;
        }
        return null;
    }
    private Thunk take(Node node) {
        return TruffleSafepoint.setBlockedThreadInterruptibleFunction(node, pool -> {
            try (var admission = GuestThreads.blocking(GuestThreadStatus.MVAR)) {
                synchronized (pool) {
                    while (!pool.stopped) {
                        var request = GuestThreads.pollCurrentWithoutYield(node, true);
                        if (request != null) throw new AsyncDelivery(request, node);
                        var thunk = pool.poll();
                        if (thunk != null) return thunk;
                        pool.wait();
                    }
                    return null;
                }
            }
        }, this);
    }
    private void run(WorkerRoot root) {
        boolean registered = false;
        var outcome = GuestThreadStatus.FINISHED;
        Throwable failure = null;
        try {
            owner.getThreads().enterCurrent(MaskingState.UNMASKED, true, true, null);
            registered = true;
            while (true) {
                var thunk = take(root);
                if (thunk == null) break;
                try { root.getCallTarget().call(thunk); }
                catch (ThunkSuspended suspended) {
                    var request = suspended.getAsyncRequest();
                    if (request == null) throw new RuntimeFault("Spark worker suspended without an async request");
                    request.acknowledge(); outcome = GuestThreadStatus.DIED; break;
                } catch (AsyncBlocked blocked) {
                    blocked.getRequest().acknowledge(); outcome = GuestThreadStatus.DIED; break;
                } catch (GuestException | RuntimeFault deferred) {
                    // Force published this thunk's failure. No caller or other spark receives it.
                    if (thunk.getState() != 3) throw deferred;
                } finally { owner.getMaskingState().set(MaskingState.UNMASKED); }
            }
        } catch (AsyncDelivery delivery) {
            delivery.getRequest().acknowledge(); outcome = GuestThreadStatus.DIED;
        } catch (Throwable caught) {
            failure = caught; outcome = GuestThreadStatus.uncaught(caught);
        } finally {
            stop();
            try { if (registered) owner.getThreads().leaveCurrent(outcome); }
            catch (Throwable cleanup) {
                if (failure == null) failure = cleanup; else if (failure != cleanup) failure.addSuppressed(cleanup);
            }
        }
        if (failure != null) {
            GuestThreadOps.reportHostFailure(owner, failure);
            SparkPool.<RuntimeException>rethrow(failure);
        }
    }
    @SuppressWarnings("unchecked") private static <E extends Throwable> void rethrow(Throwable failure) throws E { throw (E) failure; }
    /** Stop admission and release unstarted work before managed carriers are cancelled/joined. */
    public void stop() { if (capacity != 0) stopPending(); }
    @TruffleBoundary private synchronized void stopPending() { stopped = true; pending.clear(); notifyAll(); }

    private static final class WorkerRoot extends ContextRoot {
        @Child private Force force = new Force(new Metrics(false), true);
        WorkerRoot(Language language) { super(language, new FrameLayout().build()); }
        @Override public Object execute(VirtualFrame frame) {
            frame.setLong(FrameLayout.BLOOM_FILTER, 0L);
            return force.execute(frame, frame.getArguments()[0]);
        }
        @Override public String getName() { return "THC spark worker"; }
    }
}
