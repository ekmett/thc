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
                var lock = new PreparationLock(); var answer = new Object(); var evaluations = new AtomicInteger(); var publications = new AtomicInteger();
                var root = new GuestRoot(language, com.oracle.truffle.api.frame.FrameDescriptor.newBuilder().build()) {
                    @Override public long bloom(VirtualFrame frame) { return 0; }
                    @Override public Object execute(VirtualFrame frame) {
                        assertFalse(lock.isHeldByCurrentThread());
                        assertEquals(MaskingState.UNMASKED, SynchronousMasking.current(this));
                        evaluations.incrementAndGet(); return answer;
                    }
                };
                SynchronousMasking.set(root, MaskingState.MASKED_INTERRUPTIBLE);
                var initializer = new GlobalBinding.Initializer(root.getCallTarget(), new Object[]{0L}, new Metrics(false), publications::incrementAndGet);
                SynchronousMasking.set(root, MaskingState.UNMASKED);
                var preparations = new AtomicInteger();
                var cell = new GlobalBinding("strict"); cell.defer(lock, () -> { preparations.incrementAndGet(); return initializer; });
                cell.prepareCode(); cell.prepareCode();
                assertEquals(1, preparations.get());
                assertNull(cell.peek()); assertEquals(0, evaluations.get()); assertEquals(0, publications.get());
                assertSame(answer, cell.read()); assertSame(answer, cell.read());
                assertSame(answer, cell.peek()); assertEquals(1, evaluations.get()); assertEquals(1, publications.get());
            } finally { context.leave(); }
        }
    }

    @Test void inertPreparationMemoizesFailuresButAllowsCancelledLoweringToRetry() {
        var failure = new IllegalArgumentException("bad Core"); var attempts = new AtomicInteger();
        var failed = new GlobalBinding("failed");
        failed.defer(new PreparationLock(), () -> { attempts.incrementAndGet(); throw failure; });
        assertSame(failure, assertThrows(IllegalArgumentException.class, failed::prepareCode));
        assertSame(failure, assertThrows(IllegalArgumentException.class, failed::read));
        assertEquals(1, attempts.get());
        var cancelled = new GlobalBinding("cancelled"); var cancellations = new AtomicInteger(); var answer = new Object();
        cancelled.defer(new PreparationLock(), () -> {
            if (cancellations.getAndIncrement() == 0) throw new java.util.concurrent.CancellationException();
            return answer;
        });
        assertThrows(java.util.concurrent.CancellationException.class, cancelled::prepareCode);
        cancelled.prepareCode(); assertSame(answer, cancelled.read()); assertEquals(2, cancellations.get());
        var interrupted = new GlobalBinding("interrupted"); var interruptions = new AtomicInteger();
        interrupted.defer(new PreparationLock(), () -> {
            if (interruptions.getAndIncrement() == 0) throw new InterruptedException();
            return answer;
        });
        try {
            assertThrows(InterruptedException.class, interrupted::prepareCode);
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
        interrupted.prepareCode(); assertSame(answer, interrupted.read()); assertEquals(2, interruptions.get());
    }

    @Test void suspendedGlobalReadPublishesItsCompletedValueWithoutReplayingInitializer() throws Exception {
        try (var context = Main.executionContext(false)) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var effects = new AtomicInteger(); var publications = new AtomicInteger(); var marker = new Object();
                var target = ThunkYieldProofRoot.target(language, effects, new AtomicInteger(), new ThunkYieldProofRoot.Gate(), marker);
                var cell = new GlobalBinding("yielding");
                cell.defer(new PreparationLock(), () -> new GlobalBinding.Initializer(target, new Object[]{0L}, new Metrics(false), publications::incrementAndGet));
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

    @Test void resumedCallCapturePublishesFailureThroughParkedThunkParents() throws Exception {
        for (boolean bytecodeParent : new boolean[]{false, true}) {
            try (var context = Main.executionContext(false)) {
                context.initialize("thc"); context.enter();
                try {
                    var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                    var effects = new AtomicInteger(); var suffixes = new AtomicInteger();
                    var shape = new TupleShape(new CoreRepresentation(CoreKind.UNKNOWN, false, false, java.util.List.of(),
                        java.util.List.of(new CoreRepresentation(CoreKind.VOID, false, false, java.util.List.of())),
                        null, null, null, null), language);
                    var leaf = new GuestRoot(language, new FrameLayout().build()) {
                        @Override public long bloom(VirtualFrame frame) { return 0; }
                        @Override public Object execute(VirtualFrame frame) {
                            effects.incrementAndGet();
                            return new AstCapture(Unit.INSTANCE, SynchronousMasking.current(this)).append((saved, input) -> {
                                assertSame(Unit.INSTANCE, input);
                                assertEquals(MaskingState.MASKED_INTERRUPTIBLE, SynchronousMasking.current(this));
                                throw new DelimitedCut(new PromptTag(Language.currentState(this)), null, shape,
                                    SynchronousMasking.current(this), this);
                            }).freeze(this, frame.materialize());
                        }
                    };
                    class Caller extends GuestRoot {
                        private final RootCallTarget child;
                        Caller(RootCallTarget child) { super(language, new FrameLayout().build()); this.child = child; }
                        @Override public long bloom(VirtualFrame frame) { return 0; }
                        @Override public Object execute(VirtualFrame frame) {
                            effects.incrementAndGet();
                            try { return AstControl.completeCallback(this, Calls.target(child, new Object[]{0L}), child, null, false); }
                            catch (AstCapture cut) { return cut.append((saved, input) -> { suffixes.incrementAndGet(); return input; }).freeze(this, frame.materialize()); }
                        }
                    }
                    var middle = new Caller(leaf.getCallTarget());
                    var inner = new Thunk(new Caller(middle.getCallTarget()).getCallTarget(), null);
                    var driver = new Driver();
                    var gate = new ThunkYieldProofRoot.Gate();
                    var astParent = new GuestRoot(language, new FrameLayout().build()) {
                        @Child private Force force = new Force(new Metrics(false));
                        @Override public long bloom(VirtualFrame frame) { return 0; }
                        @Override public Object execute(VirtualFrame frame) {
                            effects.incrementAndGet();
                            try { return AstControl.forceCallback(frame, this, force, inner); }
                            catch (AstCapture cut) {
                                return cut.enclose(steps -> (saved, input) -> {
                                    try { return AstContinuations.resumeAstSteps(saved, steps, input); }
                                    catch (RuntimeFault failure) {
                                        gate.entered.countDown();
                                        try { assertTrue(gate.release.await(5, TimeUnit.SECONDS)); }
                                        catch (InterruptedException interrupted) { throw new AssertionError(interrupted); }
                                        throw failure;
                                    }
                                }).append((saved, input) -> { suffixes.incrementAndGet(); return input; }).freeze(this, frame.materialize());
                            }
                        }
                    };
                    var outerEffects = new AtomicInteger();
                    var outer = new Thunk(bytecodeParent ? ThunkYieldProofRoot.caller(language, inner, outerEffects) : astParent.getCallTarget(), null);
                    SynchronousMasking.set(leaf, MaskingState.MASKED_INTERRUPTIBLE);
                    assertSame(outer, assertThrows(ThunkSuspended.class, () -> driver.force(outer)).getThunk());
                    assertEquals(5, inner.getState()); assertEquals(5, outer.getState());
                    var outerSaved = SavedGuestContinuations.savedGuestContinuation(outer.getValue());
                    var innerSaved = SavedGuestContinuations.savedGuestContinuation(inner.getValue());
                    int before = effects.get();
                    SynchronousMasking.set(leaf, MaskingState.MASKED_UNINTERRUPTIBLE);
                    RuntimeFault failure;
                    if (bytecodeParent) failure = assertThrows(RuntimeFault.class, () -> driver.force(outer));
                    else try (var pool = Executors.newFixedThreadPool(2)) {
                        var evaluator = pool.submit(() -> entered(context, () -> {
                            SynchronousMasking.set(leaf, MaskingState.MASKED_UNINTERRUPTIBLE);
                            var result = assertThrows(RuntimeFault.class, () -> driver.force(outer));
                            assertEquals(MaskingState.MASKED_UNINTERRUPTIBLE, SynchronousMasking.current(leaf));
                            return result;
                        }));
                        assertTrue(gate.entered.await(5, TimeUnit.SECONDS));
                        assertEquals(1, outer.getState());
                        var waiting = new CountDownLatch(1);
                        var reader = pool.submit(() -> entered(context, () -> {
                            waiting.countDown(); return assertThrows(RuntimeFault.class, () -> driver.force(outer));
                        }));
                        assertTrue(waiting.await(5, TimeUnit.SECONDS));
                        try { assertThrows(TimeoutException.class, () -> reader.get(50, TimeUnit.MILLISECONDS)); }
                        finally { gate.release.countDown(); }
                        failure = evaluator.get(5, TimeUnit.SECONDS);
                        assertSame(failure, reader.get(5, TimeUnit.SECONDS));
                    }
                    assertEquals("control0# cannot capture across a thunk update", failure.getMessage());
                    assertEquals(3, inner.getState()); assertEquals(3, outer.getState());
                    assertSame(failure, inner.getValue()); assertSame(failure, outer.getValue());
                    assertNull(inner.getOwner()); assertNull(outer.getOwner());
                    if (!bytecodeParent) assertThrows(RuntimeFault.class, () -> outerSaved.continueWith(Unit.INSTANCE));
                    assertThrows(RuntimeFault.class, () -> innerSaved.continueWith(Unit.INSTANCE));
                    assertSame(failure, assertThrows(RuntimeFault.class, () -> driver.force(outer)));
                    assertEquals(before, effects.get()); assertEquals(bytecodeParent ? 1 : 0, outerEffects.get());
                    assertEquals(0, suffixes.get());
                    assertEquals(MaskingState.MASKED_UNINTERRUPTIBLE, SynchronousMasking.current(leaf));
                    assertEquals(0, AstStacks.astStackScope(leaf).getDepth());
                    try (var pool = Executors.newSingleThreadExecutor()) {
                        assertSame(failure, pool.submit(() -> entered(context,
                            () -> assertThrows(RuntimeFault.class, () -> driver.force(outer)))).get(5, TimeUnit.SECONDS));
                    }
                } finally { context.leave(); }
            }
        }
    }

    @Test void promptInsideResumedThunkHandlesCaptureBeforeItsUpdate() {
        try (var context = Main.executionContext(false)) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var marker = new Object(); var effects = new AtomicInteger(); var handlers = new AtomicInteger();
                var state = new CoreRepresentation(CoreKind.VOID, false, false, java.util.List.of());
                var value = new CoreRepresentation(CoreKind.OBJECT, false, false, java.util.List.of("BoxedRep (Just Lifted)"));
                var shape = new TupleShape(new CoreRepresentation(CoreKind.UNKNOWN, false, false, value.getPrimReps(),
                    java.util.List.of(state, value), null, null, null, null), language);
                var answer = shape.getLayout().create(); shape.getLayout().setObject(answer, 0, marker);
                var handler = new GuestRoot(language, new FrameLayout().build()) {
                    { configureEntry(new boolean[]{false, false}, false); configureTupleResult(shape); }
                    @Override public long bloom(VirtualFrame frame) { return 0; }
                    @Override public Object execute(VirtualFrame frame) { handlers.incrementAndGet(); return answer; }
                };
                var tag = new PromptTag(Language.currentState());
                var action = new GuestRoot(language, new FrameLayout().build()) {
                    { configureEntry(new boolean[]{false}, false); configureTupleResult(shape); }
                    @Override public long bloom(VirtualFrame frame) { return 0; }
                    @Override public Object execute(VirtualFrame frame) {
                        effects.incrementAndGet();
                        return new AstCapture(Unit.INSTANCE, SynchronousMasking.current(this)).append((saved, input) -> {
                            assertSame(Unit.INSTANCE, input);
                            throw new DelimitedCut(tag, new Closure(null, 2, handler.getCallTarget()), shape,
                                SynchronousMasking.current(this), this);
                        }).freeze(this, frame.materialize());
                    }
                };
                var body = new GuestRoot(language, new FrameLayout().build()) {
                    @Child private DelimitedActionSite site = new DelimitedActionSite(language, new Metrics(false));
                    @Override public long bloom(VirtualFrame frame) { return 0; }
                    @Override public Object execute(VirtualFrame frame) {
                        return new AstCapture(Unit.INSTANCE, SynchronousMasking.current(this)).append((saved, input) ->
                            shape.getLayout().getObject((HandoffStorage) site.prompt(saved, tag,
                                new Closure(null, 1, action.getCallTarget()), Unit.INSTANCE, shape), 0))
                            .freeze(this, frame.materialize());
                    }
                };
                var thunk = new Thunk(body.getCallTarget(), null); var driver = new Driver();
                assertThrows(ThunkSuspended.class, () -> driver.force(thunk));
                assertSame(marker, driver.force(thunk)); assertSame(marker, driver.force(thunk));
                assertEquals(2, thunk.getState()); assertNull(thunk.getOwner());
                assertEquals(1, effects.get()); assertEquals(1, handlers.get());
                assertEquals(MaskingState.UNMASKED, SynchronousMasking.current(body));
                assertEquals(0, AstStacks.astStackScope(body).getDepth());
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
