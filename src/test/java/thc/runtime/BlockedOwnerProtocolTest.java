// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import thc.Language;
import thc.Main;
import static org.junit.jupiter.api.Assertions.*;

/** Owning-operation models; no Candidate implementation, collector or inventory counts are substituted. */
class BlockedOwnerProtocolTest {
    private static final PendingWait.Operation PENDING = new PendingWait.Operation() {
        public Object resume() { return Unit.INSTANCE; }
        public boolean cancel() { return true; }
        public boolean ready() { return false; }
        public GuestThreadStatus status() { return GuestThreadStatus.MVAR; }
    };
    private static <T> T entered(Context context, Callable<T> action) throws Exception {
        context.enter(); try { return action.call(); } finally { context.leave(); }
    }
    private static final class Driver extends RootNode {
        @Child private Force force = new Force(new Metrics(false));
        Driver() { super(null); }
        @Override public Object execute(VirtualFrame frame) {
            Thunk thunk = (Thunk) frame.getArguments()[0];
            if (!(boolean) frame.getArguments()[1]) return force.execute(frame, thunk);
            // Exercise the precise owning operation with the stale null snapshot produced by an initial state1 observation.
            try {
                var method = Force.class.getDeclaredMethod("executeOne", Thunk.class, SavedGuestContinuation.class, Object.class, Metrics.class);
                method.setAccessible(true);
                return method.invoke(force, thunk, null, Unit.INSTANCE, new Metrics(false));
            } catch (InvocationTargetException failure) { throw rethrow(failure.getCause()); }
            catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
        }
        Object force(Thunk thunk, boolean stale) { return Calls.target(getCallTarget(), new Object[]{thunk, stale}); }
    }
    @SuppressWarnings("unchecked") private static <E extends Throwable> RuntimeException rethrow(Throwable failure) throws E { throw (E) failure; }
    private static void awaitDemander(Thunk thunk, Future<?> result, AtomicInteger continuations) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            synchronized (thunk.getMonitor()) { if (thunk.demandWaiters != null) return; }
            if (result.isDone()) break;
            Thread.onSpinWait();
        }
        assertEquals(0, continuations.get(), "Another logical guest stole the original evaluator's continuation");
        fail("Shared demand never reached its wait");
    }

    @Test void rescueUsesOrdinaryAsyncOriginAndDefersUnderUninterruptibleMask() {
        var masks = ThreadLocal.withInitial(() -> MaskingState.UNMASKED);
        var threads = new GuestThreads(masks, CpuAffinity.discover(false), thread -> {});
        threads.enterCurrent(null, true, true, null);
        try {
            var owner = threads.pollState(Thread.currentThread()).getCurrent();
            var payload = new Object();
            masks.set(MaskingState.MASKED_UNINTERRUPTIBLE);
            var wait = new PendingWait(PENDING, owner, MaskingState.MASKED_UNINTERRUPTIBLE, null);
            assertNull(threads.rescue(wait, payload, null));
            assertNull(threads.poll(null, true));
            masks.set(MaskingState.MASKED_INTERRUPTIBLE);
            var request = threads.rescue(wait, payload, null);
            assertSame(payload, request.getPayload()); assertSame(Thread.currentThread(), request.getTarget());
            assertFalse(request.getForceSelf(), "Collector delivery must not bypass the mask as a self throwTo");
            assertEquals(AsyncRequestState.CLAIMED, request.getState());
            request.acknowledge(); wait.resume();
            assertEquals(AsyncRequestState.ACKNOWLEDGED, request.getState());
        } finally { threads.leaveCurrent(); threads.close(); }
    }

    @Test void selectedMVarWaitUsesAsyncOrigin() throws Exception { selectedWait(false); }
    @Test void selectedSTMWaitUsesAsyncOrigin() throws Exception { selectedWait(true); }
    private void selectedWait(boolean transactional) throws Exception {
        try (var context = Main.executionContext(false)) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var payload = new Object();
                var root = new GuestRoot(language, new FrameLayout().build()) {
                    @Override public long bloom(VirtualFrame frame) { return 0; }
                    @Override public Object execute(VirtualFrame frame) {
                        var threads = Language.currentState(this).getThreads();
                        threads.enterCurrent(null, true, true, null);
                        threads.installSuspensionBoundary(new GuestWakePort());
                        try {
                            PendingWait cut;
                            ManagedMVar cell = new ManagedMVar();
                            var stm = Language.currentState(this).stm;
                            var variable = stm.newTVar(7L);
                            if (transactional) {
                                var tx = stm.begin(); stm.read(variable); stm.write(variable, 99L); stm.restore(null);
                                try { cut = assertThrows(PendingWait.class, () -> stm.await(tx, this, true, payload)); }
                                finally { stm.retire(tx); }
                            } else cut = assertThrows(PendingWait.class, () -> cell.take(this, true, payload));
                            // Model only the accepted selection flag; no native collector API is mocked or called.
                            cut.rescued = true;
                            AsyncBlocked failure = assertThrows(AsyncBlocked.class, cut::resume);
                            assertSame(payload, failure.getRequest().getPayload());
                            assertFalse(failure.getRequest().getForceSelf());
                            assertEquals(AsyncRequestState.CLAIMED, failure.getRequest().getState());
                            failure.getRequest().acknowledge();
                            if (transactional) assertEquals(7L, stm.readIO(variable), "Rescue must not publish the abandoned tentative write");
                            else {
                                assertTrue(cell.tryPut(42L));
                                assertEquals(42L, cell.tryTake().getValue(), "The rescued old taker must not steal a later value");
                            }
                            return Unit.INSTANCE;
                        } finally { threads.leaveCurrent(); }
                    }
                };
                assertSame(Unit.INSTANCE, Calls.target(root.getCallTarget(), new Object[]{0L}));
            } finally { context.leave(); }
        }
    }

    @Test void state5ClaimRechecksLogicalOwnerAfterAStaleState1Observation() throws Exception {
        try (var context = Main.executionContext(false); var pool = Executors.newSingleThreadExecutor()) {
            context.initialize("thc"); context.enter();
            GuestThreads threads = null;
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                threads = Language.currentState().getThreads(); threads.enterCurrent(null, true, true, null);
                var root = new GuestRoot(language, new FrameLayout().build()) {
                    @Override public long bloom(VirtualFrame frame) { return 0; }
                    @Override public Object execute(VirtualFrame frame) { throw new AssertionError("Original body replayed"); }
                };
                var wait = new PendingWait(PENDING, threads.pollState(Thread.currentThread()).getCurrent(), MaskingState.UNMASKED, root);
                var calls = new AtomicInteger(); var answer = new Object(); var thunk = new Thunk(root.getCallTarget(), null);
                SavedGuestContinuation saved = new SavedGuestContinuation() {
                    public Object getIdentity() { return this; }
                    public Object getYielded() { return wait; }
                    public Object getSourceRoot() { return root; }
                    public Object continueWith(Object input) { calls.incrementAndGet(); return answer; }
                };
                synchronized (thunk.getMonitor()) { thunk.setValue(saved); thunk.setState(5); }
                var driver = new Driver();
                var result = pool.submit(() -> entered(context, () -> driver.force(thunk, true)));
                try {
                    awaitDemander(thunk, result, calls);
                    synchronized (thunk.getMonitor()) {
                        thunk.setValue(answer); thunk.setOwner(null); thunk.setState(2); thunk.notifyUpdate();
                    }
                    assertSame(answer, result.get(5, TimeUnit.SECONDS)); assertEquals(0, calls.get());
                } finally { synchronized (thunk.getMonitor()) { thunk.setValue(answer); thunk.setState(2); thunk.notifyUpdate(); } }
            } finally { if (threads != null) threads.leaveCurrent(); context.leave(); }
        }
    }

    @Test void terminalAbandonmentNotifiesARealPublishedSharedUpdate() throws Exception {
        try (var context = Main.executionContext(false); var pool = Executors.newSingleThreadExecutor()) {
            context.initialize("thc"); context.enter();
            GuestThreads threads = null;
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                threads = Language.currentState().getThreads(); threads.enterCurrent(null, true, true, null);
                threads.installSuspensionBoundary(new GuestWakePort());
                var effects = new AtomicInteger(); var resumes = new AtomicInteger();
                var root = new GuestRoot(language, new FrameLayout().build()) {
                    @Override public long bloom(VirtualFrame frame) { return 0; }
                    @Override public Object execute(VirtualFrame frame) {
                        effects.incrementAndGet();
                        return new AstCapture(PendingWait.capture(PENDING, this), SynchronousMasking.current(this))
                            .append((saved, value) -> { resumes.incrementAndGet(); return 42L; }).freeze(this, frame.materialize());
                    }
                };
                var thunk = new Thunk(root.getCallTarget(), null); var driver = new Driver();
                assertThrows(ThunkSuspended.class, () -> driver.force(thunk, false));
                var pending = PendingWait.of(thunk.getValue()); assertNotNull(pending);
                var result = pool.submit(() -> entered(context, () -> driver.force(thunk, false)));
                try {
                    awaitDemander(thunk, result, resumes);
                    pending.abandon(); pending.operation.cancel();
                    var failure = assertThrows(java.util.concurrent.ExecutionException.class, () -> result.get(5, TimeUnit.SECONDS));
                    assertInstanceOf(RuntimeFault.class, failure.getCause());
                    assertTrue(failure.getCause().getMessage().contains("evaluator terminated"));
                    assertEquals(1, effects.get()); assertEquals(0, resumes.get());
                } finally {
                    pending.abandon();
                    // Failed evidence must still release the observer, including the deliberate missing-notification control.
                    synchronized (thunk.getMonitor()) {
                        if (thunk.getState() == 5) { thunk.setValue(new RuntimeFault("Test observer cleanup")); thunk.setState(3); thunk.notifyUpdate(); }
                    }
                }
            } finally { if (threads != null) threads.leaveCurrent(); context.leave(); }
        }
    }
}
