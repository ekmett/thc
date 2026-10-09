// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.TruffleSafepoint;
import com.oracle.truffle.api.ThreadLocalAction;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.ControlFlowException;
import com.oracle.truffle.api.exception.AbstractTruffleException;
import java.io.PrintStream;
import java.util.concurrent.CancellationException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import thc.Language;

public final class GuestThreadOps {
    private GuestThreadOps() {}
    private static final Object CONSUMED_REGISTRATION = new Object();
    public static TupleShape actionResult(Closure action, boolean asyncEnabled) {
        if (!(action.target.getRootNode() instanceof GuestRoot root)) throw RuntimeFault.fault("fork# requires a guest action");
        if (asyncEnabled) {
            if (!(root instanceof BytecodeRoot bytecode && bytecode.isAsyncEnabled() || root instanceof FunctionRoot function && function.getEnableAsync()))
                throw RuntimeFault.fault("fork# action has no async continuation capture");
        } else if (!(root instanceof FunctionRoot function && !function.getEnableAsync() || root instanceof BytecodeRoot bytecode && !bytecode.isAsyncEnabled()))
            throw new UnsupportedCore("fork# synchronous action requires nonresumable guest execution");
        TupleShape shape = root.getTupleResult();
        if (shape == null) throw RuntimeFault.fault("fork# action has no tuple result proof");
        List<CoreRepresentation> fields = shape.getProof().getComponents();
        if (fields == null || fields.size() != 2 || fields.get(0).getKind() != CoreKind.VOID ||
            !List.of().equals(fields.get(0).getPrimReps()) || !List.of("BoxedRep (Just Lifted)").equals(fields.get(1).getPrimReps()))
            throw RuntimeFault.fault("fork# action must return State# and a lifted result");
        return shape;
    }
    @TruffleBoundary public static GuestThreadId myThreadId(Node node) { return Language.currentState(node).getThreads().currentIdentity(); }
    @TruffleBoundary public static GuestThreadSnapshot threadStatus(Node node, Object id) {
        GuestThreads threads = Language.currentState(node).getThreads();
        if (!(id instanceof GuestThreadId target)) throw RuntimeFault.fault("threadStatus# requires a ThreadId#");
        return new GuestThreadSnapshot(threads.status(target).getCode(), target.getCapability(), target.getCapabilityLocked() ? 1L : 0L);
    }
    @TruffleBoundary public static void labelThread(Node node, Object id, Object bytes) {
        if (!(id instanceof GuestThreadId target)) throw RuntimeFault.fault("labelThread# requires a ThreadId#");
        Language.currentState(node).getThreads().label(target, bytes);
    }
    @TruffleBoundary public static Object threadLabel(Node node, Object id) {
        if (!(id instanceof GuestThreadId target)) throw RuntimeFault.fault("threadLabel# requires a ThreadId#");
        return Language.currentState(node).getThreads().label(target);
    }
    public static GuestThreadId fork(Node node, Object action, boolean asyncEnabled) { return fork(node, action, asyncEnabled, null); }
    /** Start a real Truffle thread, waiting only for its guest registration. */
    @TruffleBoundary public static GuestThreadId fork(Node node, Object action, boolean asyncEnabled, Long capability) {
        Language.State state = Language.currentState(node);
        GuestThreads threads = state.getThreads();
        TupleShape shape;
        if (action instanceof Closure closure) shape = actionResult(closure, asyncEnabled);
        else if (action instanceof Thunk) shape = null;
        else throw RuntimeFault.fault("fork# requires a lazy state-transformer action");
        state.admitGuestConcurrency();
        Language language = TruffleLanguage.LanguageReference.create(Language.class).get(node);
        var root = new ForkActionRoot(language, shape, asyncEnabled).getCallTarget();
        MaskingState inheritedMask = state.getMaskingState().get();
        CountDownLatch ready = new CountDownLatch(1);
        // Only the waiting parent owns this result, never the retained carrier task.
        // The terminal marker also rejects publication after the parent abandons its wait.
        AtomicReference<Object> registration = new AtomicReference<>();
        Thread child = threads.newThread(state.getEnv(),
            new ForkRunner(state, root, action, inheritedMask, asyncEnabled, capability, ready, registration), capability, node);
        var handler = child.getUncaughtExceptionHandler();
        child.setUncaughtExceptionHandler((thread, failure) -> {
            // This includes failure after enterCurrent but before identity publication.
            // A consumed successful result must not be mistaken for failed registration.
            if (ready.getCount() != 0) { registration.compareAndSet(null, failure); ready.countDown(); }
            if (handler != null) handler.uncaughtException(thread, failure);
        });
        // pthreads inherit their creator's mask. Broaden even across Truffle entry.
        try {
            threads.startThread(child);
            try (var admission = LoomScheduler.suspendCurrentGuest()) {
                TruffleSafepoint.setBlockedThreadInterruptibleFunction(node, AWAIT_REGISTRATION, ready);
            }
            Object result = registration.getAndSet(CONSUMED_REGISTRATION);
            if (result instanceof Throwable failure)
                throw new RuntimeFault("fork# child registration failed: " + failure.getClass().getSimpleName());
            if (!(result instanceof GuestThreadId identity)) throw RuntimeFault.fault("fork# child did not publish its ThreadId#");
            return identity;
        } finally { registration.set(CONSUMED_REGISTRATION); }
    }

    /** Each execute activation returns before neutral wait; the task consumes its initial action. */
    private static final class ForkRunner implements Runnable {
        private final Language.State state;
        private final com.oracle.truffle.api.RootCallTarget root;
        private Object initial;
        private final MaskingState inheritedMask;
        private final boolean async;
        private final Long capability;
        private final CountDownLatch ready;
        private final AtomicReference<Object> registration;
        private final GuestWakePort port = new GuestWakePort();
        private AutoCloseable affinity;
        private boolean registered, published;
        ForkRunner(Language.State state, com.oracle.truffle.api.RootCallTarget root, Object action,
                MaskingState mask, boolean async, Long capability, CountDownLatch ready, AtomicReference<Object> registration) {
            this.state = state; this.root = root; initial = action; inheritedMask = mask;
            this.async = async; this.capability = capability; this.ready = ready; this.registration = registration;
        }
        private void initialize() throws Exception {
            GuestThreads threads = state.getThreads();
            threads.enterCurrent(inheritedMask, true, async, capability); registered = true;
            if (!threads.isLoom()) affinity = capability == null ? threads.getCpuAffinity().resetCurrent() :
                threads.getCpuAffinity().bindCurrent(threads.currentIdentity().getCapability());
            GuestThreadId identity = threads.currentIdentity();
            if (!threads.isLoom()) identity.setAffinityApplied(capability != null && affinity != null);
            threads.installSuspensionBoundary(port);
            registration.compareAndSet(null, identity); published = true; ready.countDown();
        }
        private boolean execute() {
            GuestThreads threads = state.getThreads();
            GuestThreads.GuestThread resumed = port.peek();
            Object work;
            if (resumed != null) { threads.resumeSuspended(resumed); port.take(); work = resumed.work; resumed.work = null; }
            else { work = initial; initial = null; }
            try { root.call(work); return true; }
            catch (ForkSuspension cut) { threads.suspendCurrent(cut.work, cut.wait); return false; }
        }
        @Override public void run() {
            GuestThreads threads = state.getThreads();
            GuestThreadStatus outcome = GuestThreadStatus.FINISHED;
            Throwable failure = null;
            try {
                initialize();
                for (;;) { if (execute()) break; port.await(); }
            } catch (UncaughtForkAsync uncaught) {
                outcome = GuestThreadStatus.DIED; uncaught.request.acknowledge();
            } catch (AsyncDelivery uncaught) {
                outcome = GuestThreadStatus.DIED; uncaught.getRequest().acknowledge();
            } catch (Throwable caught) {
                failure = caught; outcome = GuestThreadStatus.uncaught(caught);
                if (!registered) registration.compareAndSet(null, caught);
            } finally {
                initial = null;
                if (!registered) ready.countDown();
                try { if (registered) { threads.recoverSuspended(port); threads.leaveCurrent(outcome); } }
                catch (Throwable cleanup) { failure = cleanupFailure(failure, cleanup); }
                try { if (affinity != null) affinity.close(); }
                catch (Throwable cleanup) { failure = cleanupFailure(failure, cleanup); }
            }
            if (failure != null) {
                if (published) reportHostFailure(state, failure);
                GuestThreadOps.<RuntimeException, Object>rethrow(failure);
            }
        }
    }

    /** A language-owned thread cannot cancel its non-creator TruffleContext. */
    static void reportHostFailure(Language.State state, Throwable failure) {
        if (!fatalForkFailure(failure)) return;
        // Unwind active entries at their safepoints without permanently closing the context.
        failure.printStackTrace(new PrintStream(state.getEnv().err(), true));
        state.getEnv().submitThreadLocal(null, new ThreadLocalAction(true, false) {
            @Override protected void perform(Access access) { throw new ForkFailure(failure); }
        });
    }
    /** A delivered host failure must neither enter a Haskell handler nor broadcast again. */
    private static final class ForkFailure extends RuntimeException implements InternalGuestControl {
        ForkFailure(Throwable cause) { super("Internal guest fork failure", cause); }
    }
    /** Guest/control transfers keep their existing thread and context semantics. */
    @SuppressWarnings("removal") private static boolean fatalForkFailure(Throwable failure) {
        if (failure instanceof org.graalvm.polyglot.PolyglotException polyglot &&
            (polyglot.isCancelled() || polyglot.isInterrupted() || polyglot.isExit())) return false;
        return !(failure instanceof ThreadDeath || failure instanceof AbstractTruffleException ||
            failure instanceof ControlFlowException || failure instanceof InternalGuestControl ||
            failure instanceof InterruptedException || failure instanceof CancellationException ||
            failure instanceof PrivateIOUnwind || failure instanceof AsyncThunkUnwind);
    }
    /** Cleanup cannot replace the initial failure or a hard context exit. */
    @SuppressWarnings("removal") private static Throwable cleanupFailure(Throwable failure, Throwable cleanup) {
        if (failure == null || failure == cleanup) return cleanup;
        if (cleanup instanceof ThreadDeath && !(failure instanceof ThreadDeath)) {
            cleanup.addSuppressed(failure); return cleanup;
        }
        failure.addSuppressed(cleanup); return failure;
    }
    /** Enqueue exactly once; a retry retains this token rather than sending again. */
    @TruffleBoundary public static AsyncRequest beginKill(Node node, Object id, Object payload) {
        GuestThreads threads = Language.currentState(node).getThreads();
        if (!(id instanceof GuestThreadId target)) throw RuntimeFault.fault("killThread# requires a ThreadId#");
        if (target.getOwner() != threads) throw RuntimeFault.fault("ThreadId# belongs to another guest context");
        return threads.send(target, payload);
    }
    public static Object killSelf(Node node, Object id, Object payload) {
        if (id != myThreadId(node)) throw new UnsupportedCore("Bytecode external killThread# requires a captured sender continuation");
        AsyncRequest sent = beginKill(node, id, payload);
        boolean compiled = CompilerDirectives.inCompiledCode();
        AsyncRequest incoming = GuestThreads.pollMandatoryCurrent(node, false);
        if (!sent.getForceSelf() || incoming != sent) throw RuntimeFault.fault("Self-directed killThread# did not claim its own request");
        sent.compiledCapture = compiled;
        throw new AsyncDelivery(sent, node);
    }
    @TruffleBoundary public static void finishKill(Node node, AsyncRequest request) {
        if (request.getForceSelf()) return;
        switch (request.await(node)) {
            case ACKNOWLEDGED, TARGET_FINISHED -> { }
            case FAILED -> throw RuntimeFault.fault("killThread# delivery failed");
            case CANCELLED -> throw RuntimeFault.fault("killThread# was cancelled");
            case PENDING, CLAIMED, PAUSED -> throw new IllegalStateException("Await returned before async completion");
        }
    }
    private static final TruffleSafepoint.InterruptibleFunction<CountDownLatch, Object> AWAIT_REGISTRATION = ready -> { ready.await(); return null; };
    @SuppressWarnings("unchecked") private static <E extends Throwable, T> T rethrow(Throwable failure) throws E { throw (E) failure; }
}
