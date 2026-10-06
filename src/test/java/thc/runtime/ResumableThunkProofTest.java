// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.bytecode.BytecodeRootNode;
import com.oracle.truffle.api.bytecode.ContinuationResult;
import com.oracle.truffle.api.bytecode.LocalVariable;
import com.oracle.truffle.api.frame.FrameSlotKind;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import thc.Language;
import thc.Main;
import java.util.ArrayList;
import java.util.NoSuchElementException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** An actual DSL continuation, kept by one shared thunk across guest threads. */
class ResumableThunkProofTest {
    private static final class Driver extends RootNode {
        @Child private Force force = new Force(new Metrics(true));
        Driver() { super(null); }
        @Override public Object execute(VirtualFrame frame) { return force.execute(frame, frame.getArguments()[0]); }
        Object force(Thunk thunk) { return Calls.target(getCallTarget(), new Object[]{thunk}); }
    }
    @FunctionalInterface private interface Action<T> { T run() throws Exception; }
    private static <T> T entered(Context context, Action<T> action) throws Exception {
        context.enter(); try { return action.run(); } finally { context.leave(); }
    }
    private static void compile(RootCallTarget target) throws Exception {
        var type = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget");
        assertTrue(type.isInstance(target)); type.getMethod("compile", boolean.class).invoke(target, true);
        type.getMethod("waitForCompilation").invoke(target);
        assertEquals(true, type.getMethod("isValidLastTier").invoke(target));
    }
    private record TargetDriver(RootCallTarget target, Driver driver) {}
    @Test void compiledBytecodeYieldResumesTypedLocalsOnAnotherThreadWithoutReplayingEffect() throws Exception {
        try (var context = Main.executionContext()) {
            context.initialize("thc");
            var language = entered(context, () -> TruffleLanguage.LanguageReference.create(Language.class).get(null));
            var effects = new AtomicInteger(); var compiledEffects = new AtomicInteger();
            var gate = new ThunkYieldProofRoot.Gate(); var marker = new Object();
            var pair = entered(context, () -> new TargetDriver(ThunkYieldProofRoot.target(language, effects, compiledEffects, gate, marker), new Driver()));
            var target = pair.target(); var driver = pair.driver();
            // Initialize both the bytecode root and its continuation target, then
            // explicitly install the initial root. A fresh thunk uses the same code.
            entered(context, () -> {
                for (int i = 0; i < 8; i++) {
                    var warm = new Thunk(target, null);
                    assertThrows(ThunkSuspended.class, () -> driver.force(warm));
                    assertThrows(ThunkSuspended.class, () -> driver.force(warm));
                    var answer = (ThunkYieldProofRoot.Answer) driver.force(warm);
                    assertEquals(42L, answer.number()); assertSame(marker, answer.marker());
                }
                compile(target); return null;
            });
            int before = effects.get(), compiledBefore = compiledEffects.get();
            var thunk = entered(context, () -> new Thunk(target, null));
            try (var pool = Executors.newFixedThreadPool(4)) {
                var first = pool.submit(() -> entered(context, () -> assertThrows(ThunkSuspended.class, () -> driver.force(thunk))));
                first.get(5, TimeUnit.SECONDS);
                assertEquals(5, thunk.getState());
                var saved = (ContinuationResult) thunk.getValue(); var frame = saved.getFrame();
                boolean numeric = false;
                for (int i = 0; i < frame.getFrameDescriptor().getNumberOfSlots(); i++) if (frame.isLong(i) && frame.getLong(i) == 42L) { numeric = true; break; }
                assertTrue(numeric, "The live numeric value must retain a primitive Long frame slot");
                boolean reference = false;
                for (int i = 0; i < frame.getFrameDescriptor().getNumberOfSlots(); i++) if (frame.isObject(i) && frame.getObject(i) == marker) { reference = true; break; }
                assertTrue(reference, "The live reference must retain its identity in the captured frame");
                var root = (BytecodeRootNode) target.getRootNode();
                LocalVariable number = null;
                for (var local : root.getBytecodeNode().getLocals()) if ("number".equals(local.getName())) {
                    if (number != null) throw new IllegalArgumentException("Collection contains more than one matching element.");
                    number = local;
                }
                if (number == null) throw new NoSuchElementException("Collection contains no element matching the predicate.");
                assertEquals(FrameSlotKind.Long, number.getTypeProfile());
                assertEquals(before + 1, effects.get());
                assertTrue(compiledEffects.get() > compiledBefore, "Initial yield must execute installed guest code");
                gate.armed = true;
                var second = pool.submit(() -> entered(context, () -> assertThrows(ThunkSuspended.class, () -> driver.force(thunk))));
                assertTrue(gate.entered.await(5, TimeUnit.SECONDS));
                assertEquals(1, thunk.getState(), "Only the resumer owns the thunk while its frame is live");
                var readersStarted = new CountDownLatch(2);
                var readers = new ArrayList<Future<Object>>();
                for (int i = 0; i < 2; i++) readers.add(pool.submit(() -> entered(context, () -> { readersStarted.countDown(); return driver.force(thunk); })));
                assertTrue(readersStarted.await(5, TimeUnit.SECONDS));
                assertThrows(TimeoutException.class, () -> readers.get(0).get(50, TimeUnit.MILLISECONDS));
                gate.release.countDown(); second.get(5, TimeUnit.SECONDS);
                var answers = new ArrayList<ThunkYieldProofRoot.Answer>();
                for (var reader : readers) answers.add((ThunkYieldProofRoot.Answer) reader.get(5, TimeUnit.SECONDS));
                assertSame(answers.get(0), answers.get(1), "Both waiters observe the single published WHNF");
                assertEquals(42L, answers.get(0).number()); assertSame(marker, answers.get(0).marker());
            }
            assertEquals(before + 1, effects.get(), "Resuming twice must not replay the pre-yield effect");
            assertEquals(2, thunk.getState()); assertNull(thunk.getTarget()); assertNull(thunk.getEnvironment());
            assertTrue(thunk.getValue() instanceof ThunkYieldProofRoot.Answer); assertNull(thunk.getOwner());
        }
    }
    @Test void globalInitializerRunsOutsidePreparationLockAndUsesItsFirstEvaluatorMask() throws Exception {
        try (var context = Main.executionContext(false)) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var lock = new Object(); var answer = new Object(); var evaluations = new AtomicInteger(); var publications = new AtomicInteger();
                var root = new GuestRoot(language, com.oracle.truffle.api.frame.FrameDescriptor.newBuilder().build()) {
                    @Override public long bloom(VirtualFrame frame) { return 0; }
                    @Override public Object execute(VirtualFrame frame) {
                        assertFalse(Thread.holdsLock(lock));
                        assertEquals(MaskingState.UNMASKED, SynchronousMasking.current(this));
                        evaluations.incrementAndGet(); return answer;
                    }
                };
                SynchronousMasking.set(root, MaskingState.MASKED_INTERRUPTIBLE);
                var initializer = new GlobalBinding.Initializer(root.getCallTarget(), new Object[]{0L}, new Metrics(false), publications::incrementAndGet);
                SynchronousMasking.set(root, MaskingState.UNMASKED);
                var cell = new GlobalBinding("strict"); cell.defer(lock, () -> initializer);
                assertNull(cell.peek()); assertEquals(0, evaluations.get());
                assertSame(answer, cell.read()); assertSame(answer, cell.read());
                assertSame(answer, cell.peek()); assertEquals(1, evaluations.get()); assertEquals(1, publications.get());
            } finally { context.leave(); }
        }
    }

    @Test void suspendedGlobalReadPublishesItsCompletedValueWithoutReplayingInitializer() throws Exception {
        try (var context = Main.executionContext(false)) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var effects = new AtomicInteger(); var publications = new AtomicInteger(); var marker = new Object();
                var target = ThunkYieldProofRoot.target(language, effects, new AtomicInteger(), new ThunkYieldProofRoot.Gate(), marker);
                var cell = new GlobalBinding("yielding");
                cell.defer(new Object(), () -> new GlobalBinding.Initializer(target, new Object[]{0L}, new Metrics(false), publications::incrementAndGet));
                var body = new GlobalRead(cell).proven(new CoreRepresentation(CoreKind.OBJECT, false, true, java.util.List.of("BoxedRep (Just Lifted)")));
                var root = new FunctionRoot(language, new FrameLayout().build(), "global read", null, new int[0], new int[0], new int[0],
                    body, new Metrics(false), new CoreRepresentation[0], body.getRepresentation(), null, new boolean[0], null,
                    null, new int[0], null, true, new int[0][], false, FunctionRootRole.INITIALIZER, false);
                Object result = Calls.target(root.getCallTarget(), new Object[]{0L});
                assertInstanceOf(SavedGuestContinuation.class, result); assertNull(cell.peek()); assertEquals(0, publications.get());
                var force = new Force(new Metrics(false), true);
                for (int i = 0; i < 4 && result instanceof SavedGuestContinuation saved; i++) result = force.drainStack(saved);
                var answer = assertInstanceOf(ThunkYieldProofRoot.Answer.class, result);
                assertEquals(42L, answer.number()); assertSame(marker, answer.marker());
                assertSame(answer, cell.peek()); assertSame(answer, cell.read());
                assertEquals(1, effects.get()); assertEquals(1, publications.get());
            } finally { context.leave(); }
        }
    }

    @Test void compactFailurePayloadResumeStillRaisesAfterTheFinalTaskWasConsumed() {
        try (var context = Main.executionContext(false)) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var payload = new Object(); var effects = new AtomicInteger();
                Expr suspendedPayload = new Expr() {
                    @Override public Object execute(VirtualFrame frame) {
                        effects.incrementAndGet();
                        throw new AstCapture(Unit.INSTANCE, SynchronousMasking.current(this)).append((saved, input) -> payload);
                    }
                };
                var region = new ManagedCompact(Language.currentState().compactRegions, 4096);
                var descriptor = com.oracle.truffle.api.frame.FrameDescriptor.newBuilder().build();
                var root = new GuestRoot(language, descriptor) {
                    @Child private CompactCopyNode copier = new CompactCopyNode(new Metrics(false), new Expr[]{suspendedPayload}, true);
                    @Override public long bloom(VirtualFrame frame) { return 0; }
                    @Override public Object execute(VirtualFrame frame) {
                        try { return copier.execute(frame, region, new Closure(null, 0, getCallTarget()), false); }
                        catch (AstCapture cut) { return cut.freeze(this, frame.materialize()); }
                    }
                };
                var saved = assertInstanceOf(SavedGuestContinuation.class, Calls.target(root.getCallTarget(), new Object[]{0L}));
                var failure = assertThrows(GuestException.class, () -> saved.continueWith(Unit.INSTANCE));
                assertSame(payload, failure.getPayload()); assertEquals(1, effects.get());
                assertTrue(region.getObjects().isEmpty()); region.begin(); region.end();
            } finally { context.leave(); }
        }
    }

    private record Thunks(Thunk inner, Thunk outer, Driver driver) {}
    @Test void uncapturedCallerUpdateFailsClosedAndWakesItsWaiter() throws Exception {
        try (var context = Main.executionContext()) {
            context.initialize("thc");
            var language = entered(context, () -> TruffleLanguage.LanguageReference.create(Language.class).get(null));
            var marker = new Object(); var effects = new AtomicInteger(); var outerEffects = new AtomicInteger();
            var thunks = entered(context, () -> {
                var driver = new Driver();
                var inner = new Thunk(ThunkYieldProofRoot.target(language, effects, new AtomicInteger(), new ThunkYieldProofRoot.Gate(), marker), null);
                var outer = new Thunk(new RootNode(null) {
                    @Override public Object execute(VirtualFrame frame) { outerEffects.incrementAndGet(); return driver.force(inner); }
                }.getCallTarget(), null);
                return new Thunks(inner, outer, driver);
            });
            var inner = thunks.inner(); var outer = thunks.outer(); var driver = thunks.driver();
            var yielded = entered(context, () -> assertThrows(ThunkSuspended.class, () -> driver.force(outer)));
            assertSame(inner, yielded.getThunk()); assertEquals(5, inner.getState());
            assertEquals(4, outer.getState(), "The uncaptured caller must not retain a blackhole");
            try (var pool = Executors.newSingleThreadExecutor()) {
                var waiter = pool.submit(() -> entered(context, () -> assertThrows(RuntimeFault.class, () -> driver.force(outer))));
                assertTrue(waiter.get(5, TimeUnit.SECONDS).getMessage().contains("no resumable continuation"));
            }
            assertEquals(1, outerEffects.get(), "Never replay the caller's effect");
            entered(context, () -> {
                assertThrows(ThunkSuspended.class, () -> driver.force(inner));
                var answer = (ThunkYieldProofRoot.Answer) driver.force(inner);
                assertEquals(42L, answer.number()); assertSame(marker, answer.marker()); return null;
            });
            assertEquals(1, effects.get());
        }
    }
    private record ThunkDriver(Thunk outer, Driver driver) {}
    @Test void nestedRootYieldCannotMasqueradeAsItsCallersContinuation() throws Exception {
        try (var context = Main.executionContext()) {
            context.initialize("thc");
            var language = entered(context, () -> TruffleLanguage.LanguageReference.create(Language.class).get(null));
            var effects = new AtomicInteger(); var calls = new AtomicInteger();
            var pair = entered(context, () -> {
                var innerTarget = ThunkYieldProofRoot.target(language, effects, new AtomicInteger(), new ThunkYieldProofRoot.Gate(), new Object());
                var outerTarget = new RootNode(null) {
                    @Override public Object execute(VirtualFrame frame) { calls.incrementAndGet(); return Calls.target(innerTarget, new Object[]{0L}); }
                }.getCallTarget();
                return new ThunkDriver(new Thunk(outerTarget, null), new Driver());
            });
            var outer = pair.outer(); var driver = pair.driver();
            var unsupported = entered(context, () -> assertThrows(IllegalStateException.class, () -> driver.force(outer)));
            assertTrue(unsupported.getMessage().contains("no captured caller segment")); assertEquals(4, outer.getState());
            entered(context, () -> assertThrows(RuntimeFault.class, () -> driver.force(outer)));
            assertEquals(1, calls.get()); assertEquals(1, effects.get());
        }
    }
}
