// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.OptimizationFailedException;
import com.oracle.truffle.api.bytecode.BytecodeConfig;
import com.oracle.truffle.api.bytecode.LocalAccessor;
import com.oracle.truffle.api.bytecode.ContinuationResult;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.frame.FrameSlotKind;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.RepeatingNode;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.NodeUtil;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.LoopNode;
import com.oracle.truffle.compiler.TruffleCompilerListener;
import com.oracle.truffle.runtime.AbstractCompilationTask;
import com.oracle.truffle.runtime.BaseOSRRootNode;
import com.oracle.truffle.runtime.OptimizedCallTarget;
import com.oracle.truffle.runtime.OptimizedOSRLoopNode;
import com.oracle.truffle.runtime.OptimizedTruffleRuntime;
import com.oracle.truffle.runtime.OptimizedTruffleRuntimeListener;
import java.util.List;
import java.util.Map;
import java.util.Arrays;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import thc.Language;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(90)
class StockGraphRecoveryTest {
    @Test void stockAstOsrCallbackExposesOriginalOwnerThroughPublicChildren() { ownership(false); }
    @Test void stockBytecodeOsrCallbackExposesOriginalOwnerThroughPublicChildren() { ownership(true); }
    @Test void terminalStockFailureRecoversOnlyFreshEntryWithoutReplayingEffects() {
        Truffle.getRuntime();
        Controls.recoverAst(false);
    }
    @Test void repeatedRealFailuresKeepEarlierReplacementLocalExtractions() {
        Truffle.getRuntime();
        Controls.recoverAst(true);
    }
    @Test void realAstOsrFailureFinishesOldActivationThenFreshEntryRunsCompiledOsr() {
        Truffle.getRuntime();
        Controls.recoverOsr();
    }
    @Test void stockBytecodePrologRedirectsNewArgumentsButNotParkedContinuation() {
        Truffle.getRuntime();
        Controls.bytecodeEntry();
    }
    @Test void realBytecodeGraphFailurePublishesIndependentReducedEntry() {
        Truffle.getRuntime();
        Controls.recoverBytecode();
    }
    @Test void concurrentEntryCompletesOldBodyWhileOneClaimPreparesReplacement() throws Exception {
        Truffle.getRuntime();
        Controls.concurrentEntry();
    }
    @Test void launcherReportsRealCompilerFailureWithoutUnwindingGuestEntry() throws Exception {
        Truffle.getRuntime();
        Controls.launcherPolicy();
    }
    @Test void transientFailureRetiresPhysicalTargetButCancellationDoesNot() {
        Truffle.getRuntime();
        Controls.failureAdmission();
    }
    @Test void thousandNodeBudgetRetiresFailedEntryWhileSmallTraceStillInstalls() {
        Truffle.getRuntime();
        Controls.smallBudget();
    }
    @Test void realBytecodeOsrFailureFinishesOldLoopThenFreshEntryRunsCompiledOsr() {
        Truffle.getRuntime();
        Controls.recoverBytecodeOsr(false);
    }
    @Test void installedBytecodeEntryClaimsTheRealFailureOfItsSavedLoopOsr() {
        Truffle.getRuntime();
        Controls.recoverBytecodeOsr(true);
    }
    @Test void realDeferredDefaultFailureKeepsPreparedSidesReplacementLocal() {
        Truffle.getRuntime();
        Controls.recoverDeferred();
    }
    @Test void typedLoansAndRealOldAndFreshResumeBodiesSurviveRecovery() {
        Truffle.getRuntime();
        Controls.typedResume();
    }
    @Test void failedExtractedSideRecoversAtNextEntryWithoutMutatingActiveBody() {
        Truffle.getRuntime();
        Controls.recoverSide();
    }
    @Test void realFailureReceiptsRespectContextCloseSharedRootsAndFiniteEligibility() {
        Truffle.getRuntime();
        Controls.lifecycle();
    }
    @Test void bytecodeTypedLoansAndParkedPcsSurviveRealFailureRecovery() {
        Truffle.getRuntime();
        Controls.bytecodeTypedResume();
    }
    @Test void capturedOldLogicalThunkFirstSuspendsInFreshCompiledBody() {
        Truffle.getRuntime();
        Controls.capturedThunk();
    }
    @Test void oldTailAnchorRestartsOldBodyAfterAutomaticEntryReplacement() {
        Truffle.getRuntime();
        Controls.tailAnchor();
    }

    private static void ownership(boolean bytecode) {
        try (var engine = Engine.newBuilder().allowExperimentalOptions(true)
                .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
                .option("engine.SingleTierCompilationThreshold", "10000000")
                .option("engine.OSRCompilationThreshold", "1024")
                .option("engine.CompilationFailureAction", "Throw")
                .option("compiler.DiagnoseFailure", "false").build();
             var first = Context.newBuilder("thc").engine(engine).build();
             var second = Context.newBuilder("thc").engine(engine).build()) {
            first.initialize("thc"); second.initialize("thc");
            // Load runtime implementation types only after the stock runtime bootstrap.
            Controls.ownership(first, second, bytecode);
        }
    }

    private static final class Controls {
        private static final CoreRepresentation LONG = new CoreRepresentation(CoreKind.LONG, true, true,
                List.of("IntRep"), null, null, null, null, null);
        private record Event(OptimizedCallTarget target, ContextRoot source, Object owner) {}

        private static Context strictContext() {
            return Context.newBuilder("thc").allowExperimentalOptions(true)
                    .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
                    .option("engine.SingleTierCompilationThreshold", "10000000")
                    .option("engine.CompilationFailureAction", "Throw")
                    .option("compiler.DiagnoseFailure", "false")
                    .option("compiler.MaximumGraalGraphSize", "10000").build();
        }
        static void failureAdmission() {
            try (var context = strictContext()) {
                context.initialize("thc"); context.enter();
                try {
                    var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                    for (boolean shared : List.of(false, true)) {
                        var root = new FunctionRoot(language, new FrameLayout().build(), "transient failure", null,
                                new int[0], new int[0], new int[0], new AstSameFrameArm(new Heavy(new long[4096],
                                new AtomicInteger(), new AtomicReference<RootNode>())), new Metrics(true));
                        if (shared) root.shareCompilationOwnership();
                        var target = (OptimizedCallTarget) root.getCallTarget();
                        var service = Language.currentState().getGraphRecovery();
                        // Callback controls exercise flags independently of the actual compiler tests.
                        service.failed(target,
                                "jdk.graal.compiler.core.common.CancellationBailoutException: Compilation cancelled",
                                true, false);
                        assertNull(root.graphFailure.get());
                        service.failed(target,
                                "jdk.vm.ci.code.BailoutException: transient compilation failure", true, false);
                        assertTrue(root.compilationFailureObserved, "owned and shared failed targets must be terminal");
                        if (shared) {
                            assertNull(root.graphFailure.get(), "shared code cannot publish context-owned recovery");
                        } else {
                            assertNotNull(root.graphFailure.get(), "even a nonpermanent failed graph must be retired");
                            assertEquals("jdk.vm.ci.code.BailoutException: transient compilation failure", root.graphFailure.get().reason());
                            assertTrue(root.graphFailure.get().bailout()); assertFalse(root.graphFailure.get().permanent());
                        }
                        assertFalse(target.canBeInlined(), "retired code cannot be absorbed into a caller's graph");
                        assertEquals(0, root.prepareGraphBudgetRetry(0), "the failed source must not extract and rearm itself");
                        assertEquals(-6705412340454524911L, Calls.target(target, new Object[]{0L}));
                        if (shared) assertNull(recovered(root), "shared code stays unowned after failure");
                        else assertNotNull(recovered(root), "a transient failure also installs a smaller fresh entry");
                        assertFalse(root.isCloningAllowed(), "the failed source cannot be split into unchanged retryable clones");
                    }
                } finally { context.leave(); }
            }
        }
        static void smallBudget() {
            try (var context = thc.Main.withContextProfile(Context.newBuilder("thc").allowNativeAccess(true), thc.ContextProfile.LAUNCHER)
                    .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
                    .option("engine.SingleTierCompilationThreshold", "2")
                    .build()) {
                context.initialize("thc"); context.enter();
                var runtime = (OptimizedTruffleRuntime) Truffle.getRuntime();
                var attempts = new AtomicInteger(); var failures = new AtomicInteger();
                OptimizedTruffleRuntimeListener listener = null;
                try {
                    var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                    var effects = new AtomicInteger(); var source = new AtomicReference<RootNode>();
                    var root = new FunctionRoot(language, new FrameLayout().build(), "1000 node exhausted root", null,
                            new int[0], new int[0], new int[0], new Heavy(new long[4096], effects, source), new Metrics(true));
                    var target = (OptimizedCallTarget) root.getCallTarget();
                    listener = new OptimizedTruffleRuntimeListener() {
                        @Override public void onCompilationStarted(OptimizedCallTarget compiling, AbstractCompilationTask task) {
                            if (compiling == target) attempts.incrementAndGet();
                        }
                        @Override public void onCompilationFailed(OptimizedCallTarget failed, String reason,
                                boolean bailout, boolean permanent, int tier, java.util.function.Supplier<String> stack) {
                            if (failed == target) {
                                assertTrue(GraphRecovery.graphTooBig(reason, bailout, permanent));
                                assertTrue(reason.endsWith("Limit: 1000."), "launcher must use the small speculative budget");
                                failures.incrementAndGet();
                            }
                        }
                    };
                    runtime.addListener(listener);
                    target.compile(true);
                    assertEquals(1, attempts.get()); assertEquals(1, failures.get());
                    assertEquals(0, effects.get()); assertNotNull(root.graphFailure.get());
                    assertTrue(root.graphFailure.get().bailout()); assertTrue(root.graphFailure.get().permanent());
                    assertEquals(0, root.prepareGraphBudgetRetry(0), "the old runtime hook cannot rearm a failed physical target");
                    for (int i = 0; i < 20; i++)
                        assertEquals(-6705412340454524911L, Calls.target(target, new Object[]{0L}));
                    assertEquals(20, effects.get()); assertSame(root, source.get());
                    assertEquals(1, attempts.get(), "hot interpreted entries cannot resubmit the failed target");
                    assertEquals(1, failures.get()); assertFalse(target.isValid());
                    assertNull(recovered(root)); assertFalse(root.isCloningAllowed());
                    var smallArm = new AstSameFrameArm(new Expr() {
                        @Override public Object execute(VirtualFrame frame) {
                            return CompilerDirectives.inCompiledCode() ? 17L : 19L;
                        }
                    });
                    var smallOwner = new FunctionRoot(language, new FrameLayout().build(), "small trace owner", null,
                            new int[0], new int[0], new int[0], smallArm, new Metrics(false));
                    var small = (OptimizedCallTarget) new AstSameFrameArm.ArmRoot(smallOwner, smallArm, 0).getCallTarget();
                    small.prepareForAOT();
                    small.compile(true); assertTrue(small.isValidLastTier());
                    var frame = Truffle.getRuntime().createMaterializedFrame(new Object[]{0L}, smallOwner.getFrameDescriptor());
                    assertEquals(17L, Calls.target(small, new Object[]{frame}),
                            "the first installed same-frame side trace call must execute compiled code");
                } finally {
                    if (listener != null) runtime.removeListener(listener);
                    context.leave();
                }
            }
        }
        private static FunctionRoot capturedRoot(Language language, CaptureLayout capture, Expr body, Metrics metrics, boolean async) {
            var layout = new FrameLayout(); int seed = layout.bind("seed", FrameSlotKind.Long);
            return new FunctionRoot(language, layout.build(), "captured recovery", capture, new int[]{seed},
                    new int[0], new int[0], body, metrics, new CoreRepresentation[0], LONG, null, new boolean[0],
                    null, null, new int[0], null, async, new int[0][], false, FunctionRootRole.FUNCTION, !async);
        }
        private static final class CapturedWork extends Expr {
            @Child private Expr heavy;
            CapturedWork(AtomicInteger effects, AtomicReference<RootNode> source) {
                heavy = new AstSameFrameArm(new Heavy(new long[4096], effects, source)); setRepresentation(LONG);
            }
            @Override public Object execute(VirtualFrame frame) {
                try { return heavy.executeLong(frame) + frame.getLong(4); }
                catch (com.oracle.truffle.api.nodes.UnexpectedResultException failure) { throw new AssertionError(failure); }
            }
        }
        static void capturedThunk() {
            try (var context = strictContext()) {
                context.initialize("thc"); context.enter();
                try {
                    var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                    var owner = Language.currentState(); var metrics = new Metrics(true);
                    var capture = new CaptureLayout(language, new boolean[]{true}, new boolean[]{true});
                    var environment = capture.captureValues(new Object[]{23L});
                    var effects = new AtomicInteger(); var source = new AtomicReference<RootNode>();
                    var root = capturedRoot(language, capture, new CapturedWork(effects, source), metrics, true);
                    var target = (OptimizedCallTarget) root.getCallTarget();
                    var escaped = new Closure(environment, 0, target);
                    assertThrows(OptimizationFailedException.class, () -> target.compile(true)); assertEquals(0, effects.get());
                    assertEquals(-6705412340454524888L, Calls.target(target, new Object[]{0L, environment}));
                    var fresh = (FunctionRoot) recovered(root).getRootNode(); assertSame(fresh, source.get());
                    var optimized = (OptimizedCallTarget) fresh.getCallTarget();
                    assertTrue(optimized.compile(true)); assertTrue(optimized.isValidLastTier());
                    ((OptimizedTruffleRuntime) Truffle.getRuntime()).bypassedInstalledCode(optimized);
                    assertSame(target, escaped.target); assertSame(environment, escaped.environment);
                    assertSame(capture, environment.getLayout()); assertTrue(root.isSelf(optimized));
                    var thunk = new Thunk(escaped.target, escaped.environment);
                    var demand = new RootNode(language, new FrameLayout().build()) {
                        @Child private Force force = new Force(metrics, true);
                        @Override public Object execute(VirtualFrame frame) { return force.execute(frame, thunk); }
                    }.getCallTarget();
                    owner.getThreads().enterCurrent(null, false, true, null);
                    try {
                        var request = owner.getThreads().send(owner.getThreads().currentIdentity(), new Object());
                        var suspension = assertThrows(ThunkSuspended.class, demand::call);
                        assertSame(thunk, suspension.getThunk()); assertSame(request, suspension.getAsyncRequest());
                        assertTrue(request.compiledCapture); assertEquals(5, thunk.getState());
                        var saved = SavedGuestContinuations.savedGuestContinuation(thunk.getValue());
                        assertNotNull(saved); assertSame(fresh, saved.getSourceRoot()); assertNotSame(root, saved.getSourceRoot());
                        assertSame(request, saved.asyncRequest()); assertEquals(1, effects.get()); request.acknowledge();
                        Object value = demand.call(); assertEquals(-6705412340454524888L, value);
                        assertSame(value, demand.call()); assertEquals(2, thunk.getState()); assertEquals(2, effects.get());
                        assertSame(fresh, source.get()); assertThrows(RuntimeFault.class, () -> saved.continueWith(Unit.INSTANCE));
                        assertEquals(MaskingState.UNMASKED, SynchronousMasking.current(root)); clean(language);
                    } finally { owner.getThreads().leaveCurrent(GuestThreadStatus.FINISHED); }
                } finally { context.leave(); }
            }
        }

        private static final class AnchorWork extends Expr {
            @Child private CapturedWork work;
            @Child private DirectCallNode call;
            private final com.oracle.truffle.api.RootCallTarget first;
            private final AtomicInteger iterations;
            AnchorWork(AtomicInteger effects, AtomicReference<RootNode> source, AtomicInteger iterations,
                    com.oracle.truffle.api.RootCallTarget first) {
                work = new CapturedWork(effects, source); this.iterations = iterations; this.first = first;
                call = DirectCallNode.create(first); setRepresentation(LONG);
            }
            @Override public Object execute(VirtualFrame frame) {
                Object result = work.execute(frame);
                if (iterations.incrementAndGet() == 1)
                    return AstControl.complete(this, Calls.direct(call, new Object[]{((GuestRoot) getRootNode()).bloom(frame)}), first, null, true);
                return result;
            }
        }
        static void tailAnchor() {
            try (var context = strictContext()) {
                context.initialize("thc"); context.enter();
                try {
                    var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                    var metrics = new Metrics(true); var capture = new CaptureLayout(language, new boolean[]{true}, new boolean[]{true});
                    var environment = capture.captureValues(new Object[]{23L});
                    FunctionRoot[] owner = new FunctionRoot[1]; int[] prefixes = new int[70];
                    com.oracle.truffle.api.RootCallTarget next = null;
                    for (int i = prefixes.length - 1; i >= 0; i--) {
                        int index = i; var following = next;
                        Expr side = new Expr() {
                            @Child private DirectCallNode call = following == null ? null : DirectCallNode.create(following);
                            @Child private TailCheck tail = new TailCheck(metrics);
                            @Override public Object execute(VirtualFrame frame) {
                                prefixes[index]++; long bloom = ((GuestRoot) getRootNode()).bloom(frame);
                                if (following == null) {
                                    tail.check(frame, owner[0].getCallTarget(), new Object[]{bloom, environment});
                                    throw new AssertionError("saved old anchor must own its restart");
                                }
                                return AstControl.complete(this, Calls.direct(call, new Object[]{bloom}), following, null, true);
                            }
                        }.proven(LONG);
                        next = new FunctionRoot(language, new FrameLayout().build(), "existing tail side " + i, null,
                                new int[0], new int[0], new int[0], side, metrics, new CoreRepresentation[0], LONG, null,
                                new boolean[0], null, null, new int[0], null, false, new int[0][], false,
                                FunctionRootRole.PASS_THROUGH, true).getCallTarget();
                    }
                    var effects = new AtomicInteger(); var source = new AtomicReference<RootNode>(); var iterations = new AtomicInteger();
                    var root = capturedRoot(language, capture, new AnchorWork(effects, source, iterations, next), metrics, false);
                    owner[0] = root; var target = (OptimizedCallTarget) root.getCallTarget();
                    var scope = AstStacks.astStackScope(root); AstTailAnchor saved;
                    scope.setDriving(true);
                    try { saved = assertInstanceOf(AstTailAnchor.class, Calls.target(target, new Object[]{0L, environment})); }
                    finally { scope.setDriving(false); }
                    assertSame(root, saved.getSourceRoot()); assertEquals(1, effects.get()); assertTrue(scope.getSpills() > 0);
                    assertThrows(OptimizationFailedException.class, () -> target.compile(true)); assertEquals(1, effects.get());
                    assertEquals(-6705412340454524888L, Calls.target(target, new Object[]{0L, environment}));
                    var fresh = (FunctionRoot) recovered(root).getRootNode(); assertSame(fresh, source.get());
                    assertTrue(((OptimizedCallTarget) fresh.getCallTarget()).compile(true));
                    assertEquals(-6705412340454524888L, saved.continueWith(Unit.INSTANCE));
                    assertSame(root, source.get(), "saved anchor restarts the original body, never the new entry");
                    assertEquals(3, iterations.get()); for (int count : prefixes) assertEquals(1, count);
                    assertEquals(1, scope.getTailAnchors()); assertNull(scope.getTailAnchor()); assertEquals(0, scope.getDepth());
                    assertThrows(IllegalStateException.class, () -> saved.continueWith(Unit.INSTANCE));
                    long entries = metrics.getCompiledEntries();
                    assertEquals(-6705412340454524888L, Calls.target(target, new Object[]{0L, environment}));
                    assertSame(fresh, source.get()); assertTrue(metrics.getCompiledEntries() > entries);
                    assertEquals(4, effects.get()); assertTrue(root.isSelf(fresh.getCallTarget())); clean(language);
                } finally { context.leave(); }
            }
        }

        static void bytecodeTypedResume() {
            try (var context = Context.newBuilder("thc").allowExperimentalOptions(true)
                    .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
                    .option("engine.SingleTierCompilationThreshold", "10000000")
                    .option("engine.CompilationFailureAction", "Throw")
                    .option("compiler.DiagnoseFailure", "false")
                    .option("compiler.MaximumGraalGraphSize", "10000").build()) {
                context.initialize("thc"); context.enter();
                try {
                    var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                    var owner = Language.currentState();
                    var reference = new CoreRepresentation(CoreKind.OBJECT, false, true,
                            List.of("BoxedRep (Just Lifted)"), null, null, null, null, null);
                    var pair = new CoreRepresentation(CoreKind.UNKNOWN, true, true,
                            List.of("IntRep", "BoxedRep (Just Lifted)"), List.of(LONG, reference), null, null, null, null);
                    var arguments = ArgumentLayout.fromProofs(List.of(pair));
                    var input = new TypedInputLayout(language, arguments, false); var tuple = new TupleShape(pair, language);
                    var prefixes = new BytecodeCheckpoint(); prefixes.setArmed(true);
                    var suffixes = new BytecodeCheckpoint(); suffixes.setArmed(true);
                    var side = BytecodeRootGen.create(language, BytecodeConfig.DEFAULT, b -> {
                        b.beginRoot(); b.beginReturn(); emitArithmetic(b, 0, 1024, () -> b.emitLoadArgument(1));
                        b.endReturn(); b.endRoot();
                    }).getNode(0);
                    side.configureCaseRegions(true, false, new BytecodeCaseRegion[0]);
                    var capture = new BytecodeCaseRegion.Source(new LocalAccessor[][]{new LocalAccessor[0]}, null);
                    LocalAccessor[] bloomSlot = new LocalAccessor[1], transactionSlot = new LocalAccessor[1];
                    var root = BytecodeRootGen.create(language, BytecodeConfig.DEFAULT, b -> {
                        b.beginRoot(); b.beginBlock();
                        var transaction = b.createLocal("transaction", FrameSlotKind.Object);
                        transactionSlot[0] = LocalAccessor.constantOf(transaction);
                        b.beginStaticStoreObject(transaction); b.emitCurrentTransaction(); b.endStaticStoreObject();
                        var bloom = b.createLocal("bloom", FrameSlotKind.Long); bloomSlot[0] = LocalAccessor.constantOf(bloom);
                        var number = b.createLocal("number", FrameSlotKind.Long);
                        var ref = b.createLocal("reference", FrameSlotKind.Object);
                        var result = b.createLocal("result", FrameSlotKind.Long);
                        var entryMask = b.createLocal("entry mask", FrameSlotKind.Object);
                        var activeMask = b.createLocal("active mask", FrameSlotKind.Object);
                        var request = b.createLocal("request", FrameSlotKind.Object);
                        b.emitRestoreTypedInput(new BytecodeTypedInputSlots(input, bloomSlot[0],
                                new LocalAccessor[]{LocalAccessor.constantOf(number), LocalAccessor.constantOf(ref)},
                                new int[]{0, 1}, new CoreRepresentation[]{LONG, reference}, null,
                                new LocalAccessor[0], new CoreRepresentation[0]));
                        b.beginStaticStoreObject(entryMask); b.emitCurrentMask(); b.endStaticStoreObject();
                        for (var local : List.of(activeMask, request)) {
                            b.beginStaticStoreObject(local); b.emitLoadNull(); b.endStaticStoreObject();
                        }
                        var complete = b.createLabel();
                        b.beginIfThen(); b.emitInlineCaseRegions(); b.beginBlock();
                        b.beginStaticStoreLong(result); emitArithmetic(b, 0, 1024, () -> b.emitStaticLoadLong(number));
                        b.endStaticStoreLong(); b.emitBranch(complete); b.endBlock(); b.endIfThen();
                        b.beginStaticStoreLong(result);
                        b.beginCallCaseRegion(0, capture); b.emitStaticLoadLong(number); b.emitStaticLoadObject(entryMask);
                        b.endCallCaseRegion(); b.endStaticStoreLong(); b.emitLabel(complete);
                        b.emitCheckpointArmed(prefixes);
                        b.beginIfThen(); b.emitPollAsync(request); b.beginBlock();
                        b.beginReenterPendingAsyncMask(activeMask); b.beginYield(); b.beginRecordContinuationOwner();
                        b.emitParkPendingAsyncMask(request, entryMask, activeMask);
                        b.endRecordContinuationOwner(); b.endYield(); b.endReenterPendingAsyncMask(); b.endBlock(); b.endIfThen();
                        b.emitCheckpointArmed(suffixes);
                        b.beginReturn(); b.emitFinishTuple(new BytecodeTupleSlots(tuple, new LocalAccessor[]{
                                LocalAccessor.constantOf(result), LocalAccessor.constantOf(ref)})); b.endReturn();
                        b.endBlock(); b.endRoot();
                    }).getNode(0);
                    root.configureCaseRegions(false, true, new BytecodeCaseRegion[]{new BytecodeCaseRegion(
                            new Object[0], 1, new com.oracle.truffle.api.RootCallTarget[]{side.getCallTarget()},
                            new CaptureLayout[]{null}, false)});
                    root.configureAsync(true); root.configureStackDriver(new Metrics(true));
                    root.configureStackTransaction(transactionSlot[0]);
                    root.configureTypedInput(input); root.configureInput(arguments); root.configureTypedBloom(bloomSlot[0]);
                    root.configureTupleResult(tuple); root.configureEntry(new boolean[]{false}, false);
                    var target = (OptimizedCallTarget) root.getCallTarget();
                    var lazy = new Thunk(new RootNode(language) {
                        @Override public Object execute(VirtualFrame frame) { throw new AssertionError("lazy reference forced"); }
                    }.getCallTarget(), null);
                    owner.getThreads().enterCurrent(null, false, true, null);
                    try {
                        SynchronousMasking.set(root, MaskingState.MASKED_INTERRUPTIBLE);
                        var oldRequest = owner.getThreads().send(owner.getThreads().currentIdentity(), new Object());
                        var oldPacket = packet(input, 23L, lazy);
                        var old = assertInstanceOf(ContinuationResult.class, Calls.target(target, new Object[]{oldPacket}));
                        consumed(input, oldPacket); assertSame(oldRequest, old.getResult()); oldRequest.acknowledge();
                        assertEquals(1, prefixes.getVisits().get()); assertEquals(0, suffixes.getVisits().get());
                        var oldFrame = old.getFrame(); int oldPc = old.getContinuationRootNode().getLocation().getBytecodeIndex();
                        assertSame(root, old.getContinuationRootNode().getSourceRootNode());
                        SynchronousMasking.set(root, MaskingState.UNMASKED);
                        assertSame(target, assertThrows(OptimizationFailedException.class, () -> target.compile(true)).getCallTarget());
                        assertNotNull(root.graphFailure.get()); assertFalse(target.isSubmittedForCompilation());
                        assertEquals(1, prefixes.getVisits().get());
                        var nextPacket = packet(input, 29L, lazy);
                        answer(language, tuple, Calls.target(target, new Object[]{nextPacket}), 553472L, lazy);
                        consumed(input, nextPacket);
                        var fresh = (BytecodeRoot) recovered(root).getRootNode();
                        assertTrue(root.isSelf(fresh.getCallTarget())); assertSame(input, fresh.getTypedInput());
                        assertSame(tuple, fresh.getTupleResult()); assertEquals(root.mask, fresh.mask);
                        var optimized = (OptimizedCallTarget) fresh.getCallTarget();
                        assertTrue(optimized.compile(true)); assertTrue(optimized.isValidLastTier());
                        ((OptimizedTruffleRuntime) Truffle.getRuntime()).bypassedInstalledCode(optimized);
                        var request = owner.getThreads().send(owner.getThreads().currentIdentity(), new Object());
                        var lastPacket = packet(input, 31L, lazy);
                        var saved = assertInstanceOf(ContinuationResult.class, Calls.target(target, new Object[]{lastPacket}));
                        consumed(input, lastPacket); assertSame(request, saved.getResult());
                        assertTrue(request.compiledCapture); assertTrue(prefixes.getCompiledVisits().get() > 0);
                        assertSame(fresh, saved.getContinuationRootNode().getSourceRootNode()); request.acknowledge();
                        answer(language, tuple, old.continueWith(Unit.INSTANCE), 547328L, lazy);
                        assertEquals(MaskingState.MASKED_INTERRUPTIBLE, SynchronousMasking.current(root));
                        answer(language, tuple, saved.continueWith(Unit.INSTANCE), 555520L, lazy);
                        assertEquals(MaskingState.UNMASKED, SynchronousMasking.current(root));
                        assertSame(oldFrame, old.getFrame());
                        assertEquals(oldPc, old.getContinuationRootNode().getLocation().getBytecodeIndex());
                        assertSame(root, old.getContinuationRootNode().getSourceRootNode());
                        assertEquals(3, prefixes.getVisits().get()); assertEquals(3, suffixes.getVisits().get());
                        assertEquals(0, lazy.getState()); assertEquals(0, root.getGraphBudgetGeneration()); clean(language);
                    } finally {
                        SynchronousMasking.set(root, MaskingState.UNMASKED);
                        owner.getThreads().leaveCurrent(GuestThreadStatus.FINISHED);
                    }
                } finally { context.leave(); }
            }
        }

        static void lifecycle() {
            try (var engine = Engine.newBuilder().allowExperimentalOptions(true)
                    .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
                    .option("engine.SingleTierCompilationThreshold", "10000000")
                    .option("engine.CompilationFailureAction", "Throw")
                    .option("compiler.DiagnoseFailure", "false")
                    .option("compiler.MaximumGraalGraphSize", "10000").build();
                 var first = Context.newBuilder("thc").engine(engine).build();
                 var second = Context.newBuilder("thc").engine(engine).build()) {
                first.initialize("thc"); second.initialize("thc");
                FunctionRoot pending; GraphRecovery service; GraphRecovery.Failure receipt;
                var effects = new AtomicInteger(); var source = new AtomicReference<RootNode>();
                first.enter();
                try {
                    var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                    service = Language.currentState().getGraphRecovery();
                    pending = new FunctionRoot(language, new FrameLayout().build(), "owned failure", null,
                            new int[0], new int[0], new int[0],
                            new AstSameFrameArm(new Heavy(new long[4096], effects, source)), new Metrics(false));
                    var target = (OptimizedCallTarget) pending.getCallTarget();
                    assertSame(target, assertThrows(OptimizationFailedException.class, () -> target.compile(true)).getCallTarget());
                    receipt = pending.graphFailure.get(); assertNotNull(receipt);
                    assertFalse(target.isSubmittedForCompilation()); assertFalse(receipt.claimed());
                    assertTrue(GraphRecovery.graphTooBig(receipt.reason(), true, true));
                    assertFalse(GraphRecovery.graphTooBig(receipt.reason(), false, true));
                    assertFalse(GraphRecovery.graphTooBig(receipt.reason(), true, false));
                    assertFalse(GraphRecovery.graphTooBig(null, true, true));
                    assertFalse(GraphRecovery.graphTooBig("unrelated compiler error: " + receipt.reason(), true, true));

                    var shared = new FunctionRoot(language, new FrameLayout().build(), "shared failure", null,
                            new int[0], new int[0], new int[0],
                            new AstSameFrameArm(new Heavy(new long[4096], effects, source)), new Metrics(false));
                    shared.shareCompilationOwnership(); var sharedTarget = (OptimizedCallTarget) shared.getCallTarget();
                    assertThrows(OptimizationFailedException.class, () -> sharedTarget.compile(true));
                    assertNull(shared.graphFailure.get(), "a shared root cannot choose one context's recovery service");
                    assertNull(service.claim(shared));

                    var exhausted = new FunctionRoot(language, new FrameLayout().build(), "no eligible boundary", null,
                            new int[0], new int[0], new int[0], new Heavy(new long[4096], effects, source), new Metrics(false));
                    var exhaustedTarget = (OptimizedCallTarget) exhausted.getCallTarget();
                    assertThrows(OptimizationFailedException.class, () -> exhaustedTarget.compile(true));
                    assertNotNull(exhausted.graphFailure.get()); assertEquals(0, effects.get());
                    assertEquals(-6705412340454524911L, Calls.target(exhaustedTarget, new Object[]{0L}));
                    assertEquals(1, effects.get()); assertSame(exhausted, source.get());
                    assertNull(recovered(exhausted)); assertNull(exhausted.graphFailure.get());
                } finally { first.leave(); }
                second.enter();
                try {
                    assertNull(Language.currentState().getGraphRecovery().claim(pending));
                    assertNull(service.claim(pending), "even the owning service requires its entered context");
                    assertSame(receipt, pending.graphFailure.get());
                } finally { second.leave(); }
                first.enter();
                try {
                    service.close(); service.close();
                    assertNull(service.claim(pending)); assertSame(receipt, pending.graphFailure.get());
                    assertEquals(-6705412340454524911L, Calls.target(pending.getCallTarget(), new Object[]{0L}));
                    assertEquals(2, effects.get()); assertSame(pending, source.get()); assertNull(recovered(pending));
                } finally { first.leave(); }
            }
        }

        static void recoverSide() {
            try (var context = Context.newBuilder("thc").allowExperimentalOptions(true)
                    .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
                    .option("engine.SingleTierCompilationThreshold", "1")
                    .option("engine.CompilationFailureAction", "Print")
                    .option("compiler.DiagnoseFailure", "false")
                    .option("compiler.MaximumGraalGraphSize", "10000").build()) {
                context.initialize("thc"); context.enter();
                try {
                    var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                    var effects = new AtomicInteger(); var source = new AtomicReference<RootNode>();
                    var body = new AstSameFrameArm(new AstSameFrameArm(new Heavy(new long[4096], effects, source)));
                    var root = new FunctionRoot(language, new FrameLayout().build(), "nested recovery", null,
                            new int[0], new int[0], new int[0], body, new Metrics(true));
                    var target = root.getCallTarget();
                    assertEquals(-6705412340454524911L, Calls.target(target, new Object[]{0L}));
                    assertEquals(1, effects.get(), "root and side compilation failures cannot replay the guest");
                    var first = (FunctionRoot) recovered(root).getRootNode();
                    assertSame(first, source.get()); assertEquals(1, first.getGraphBudgetGeneration());
                    assertTrue(((OptimizedCallTarget) first.getCallTarget()).isValidLastTier());
                    var pending = first.graphFailure.get();
                    assertNotNull(pending, "a failed extracted side must be attributed to its actual function owner");
                    assertInstanceOf(AstSameFrameArm.ArmRoot.class, pending.target().getRootNode());
                    assertFalse(pending.target().isSubmittedForCompilation()); assertFalse(pending.target().isValid());
                    ((OptimizedTruffleRuntime) Truffle.getRuntime()).bypassedInstalledCode((OptimizedCallTarget) first.getCallTarget());
                    assertEquals(-6705412340454524911L, Calls.target(target, new Object[]{0L}));
                    assertEquals(2, effects.get());
                    var second = recovered(first); assertNotNull(second, "even a valid enclosing entry must claim the failed side");
                    assertSame(second.getRootNode(), source.get()); assertTrue(root.isSelf(second));
                    assertEquals(2, ((FunctionRoot) second.getRootNode()).getGraphBudgetGeneration());
                    assertEquals(0, root.getGraphBudgetGeneration()); assertEquals(1, first.getGraphBudgetGeneration());
                } finally { context.leave(); }
            }
        }

        static void typedResume() {
            try (var context = Context.newBuilder("thc").allowExperimentalOptions(true)
                    .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
                    .option("engine.SingleTierCompilationThreshold", "10000000")
                    .option("engine.CompilationFailureAction", "Throw")
                    .option("compiler.DiagnoseFailure", "false")
                    .option("compiler.MaximumGraalGraphSize", "10000").build()) {
                context.initialize("thc"); context.enter();
                try {
                    var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                    var owner = Language.currentState(); var metrics = new Metrics(true);
                    var reference = new CoreRepresentation(CoreKind.OBJECT, false, true,
                            List.of("BoxedRep (Just Lifted)"), null, null, null, null, null);
                    var pair = new CoreRepresentation(CoreKind.UNKNOWN, true, true,
                            List.of("IntRep", "BoxedRep (Just Lifted)"), List.of(LONG, reference), null, null, null, null);
                    var arguments = ArgumentLayout.fromProofs(List.of(pair));
                    var input = new TypedInputLayout(language, arguments, false); var tuple = new TupleShape(pair, language);
                    var layout = new FrameLayout();
                    int number = layout.bind("number", FrameSlotKind.Long), ref = layout.bind("reference", FrameSlotKind.Object);
                    int[] results = {layout.bind("output number", FrameSlotKind.Long), layout.bind("output reference", FrameSlotKind.Object)};
                    var effects = new AtomicInteger(); var source = new AtomicReference<RootNode>();
                    var observedMask = new AtomicReference<MaskingState>();
                    var body = new TupleWork(new AstSameFrameArm(new Heavy(new long[4096], effects, source)), pair, number, ref, observedMask);
                    var root = new FunctionRoot(language, layout.build(), "typed recovery", null, new int[0],
                            new int[]{number, ref}, new int[]{0, 1}, body, metrics, new CoreRepresentation[]{LONG, reference},
                            pair, null, new boolean[]{false}, null, tuple, results, arguments, true, new int[0][], false,
                            FunctionRootRole.FUNCTION, false);
                    root.configureTypedInput(input);
                    var target = (OptimizedCallTarget) root.getCallTarget();
                    var lazy = new Thunk(new RootNode(language) {
                        @Override public Object execute(VirtualFrame frame) { throw new AssertionError("lazy reference was forced"); }
                    }.getCallTarget(), null);
                    owner.getThreads().enterCurrent(null, false, true, null);
                    try {
                        SynchronousMasking.set(root, MaskingState.MASKED_INTERRUPTIBLE);
                        var oldRequest = owner.getThreads().send(owner.getThreads().currentIdentity(), new Object());
                        var oldPacket = packet(input, 23L, lazy); long generation = oldPacket.getGeneration();
                        var old = assertInstanceOf(AstContinuation.class, Calls.target(target, new Object[]{oldPacket}));
                        assertSame(root, old.getSourceRoot()); assertSame(oldRequest, old.asyncRequest());
                        consumed(input, oldPacket); oldRequest.acknowledge(); assertEquals(0, effects.get());
                        SynchronousMasking.set(root, MaskingState.UNMASKED);
                        var failure = assertThrows(OptimizationFailedException.class, () -> target.compile(true));
                        assertSame(target, failure.getCallTarget()); assertFalse(target.isSubmittedForCompilation());
                        assertEquals(0, effects.get(), "old poll suspension and compilation do not execute the body");

                        var nextPacket = packet(input, 29L, lazy);
                        assertSame(oldPacket, nextPacket); assertTrue(nextPacket.getGeneration() > generation);
                        Object next = Calls.target(target, new Object[]{nextPacket}); consumed(input, nextPacket);
                        answer(language, tuple, next, -6705412340454524882L, lazy);
                        assertEquals(1, effects.get()); assertEquals(MaskingState.UNMASKED, observedMask.get());
                        var fresh = (FunctionRoot) recovered(root).getRootNode();
                        assertSame(fresh, source.get()); assertSame(input, fresh.getTypedInput());
                        assertSame(tuple, fresh.getTupleResult()); assertSame(arguments, fresh.getInputLayout());
                        assertEquals(root.mask, fresh.mask); assertTrue(root.isSelf(fresh.getCallTarget()));
                        var optimized = (OptimizedCallTarget) fresh.getCallTarget();
                        assertTrue(optimized.compile(true)); assertTrue(optimized.isValidLastTier());
                        ((OptimizedTruffleRuntime) Truffle.getRuntime()).bypassedInstalledCode(optimized);
                        long entries = metrics.getCompiledEntries();
                        var request = owner.getThreads().send(owner.getThreads().currentIdentity(), new Object());
                        var lastPacket = packet(input, 31L, lazy);
                        var saved = assertInstanceOf(AstContinuation.class, Calls.target(target, new Object[]{lastPacket}));
                        assertSame(fresh, saved.getSourceRoot()); assertSame(request, saved.asyncRequest());
                        assertTrue(metrics.getCompiledEntries() > entries); assertTrue(request.compiledCapture);
                        consumed(input, lastPacket); request.acknowledge(); assertEquals(1, effects.get());

                        answer(language, tuple, old.continueWith(Unit.INSTANCE), -6705412340454524888L, lazy);
                        assertSame(root, source.get()); assertEquals(MaskingState.MASKED_INTERRUPTIBLE, observedMask.get());
                        assertThrows(RuntimeFault.class, () -> old.continueWith(Unit.INSTANCE));
                        answer(language, tuple, saved.continueWith(Unit.INSTANCE), -6705412340454524880L, lazy);
                        assertSame(fresh, source.get()); assertEquals(MaskingState.UNMASKED, observedMask.get());
                        assertThrows(RuntimeFault.class, () -> saved.continueWith(Unit.INSTANCE));
                        assertEquals(3, effects.get()); assertEquals(0, lazy.getState());
                        assertEquals(MaskingState.UNMASKED, SynchronousMasking.current(root)); clean(language);
                    } finally {
                        SynchronousMasking.set(root, MaskingState.UNMASKED);
                        owner.getThreads().leaveCurrent(GuestThreadStatus.FINISHED);
                    }
                } finally { context.leave(); }
            }
        }

        private static final class TupleWork extends Expr {
            @Child private Expr body;
            private final int number, reference;
            private final AtomicReference<MaskingState> observed;
            TupleWork(Expr body, CoreRepresentation proof, int number, int reference, AtomicReference<MaskingState> observed) {
                this.body = body; this.number = number; this.reference = reference; this.observed = observed; setRepresentation(proof);
            }
            @Override public Object execute(VirtualFrame frame) { throw new AssertionError("aggregate requires typed result"); }
            @Override public Object executeTuple(VirtualFrame frame, int[] slots, int offset) {
                recordMask();
                try { frame.setLong(slots[offset], body.executeLong(frame) + frame.getLong(number)); }
                catch (com.oracle.truffle.api.nodes.UnexpectedResultException failure) { throw new AssertionError(failure); }
                frame.setObject(slots[offset + 1], frame.getObject(reference)); return null;
            }
            @CompilerDirectives.TruffleBoundary private void recordMask() { observed.set(SynchronousMasking.current(this)); }
        }

        private static HandoffStorage packet(TypedInputLayout input, long number, Object reference) {
            HandoffStorage packet = input.state().getArguments().acquire(input.getPacket()); packet.setInputMode(1);
            input.getPacket().setLong(packet, 0, 0L); input.getPacket().setLong(packet, input.getHeader(), number);
            input.getPacket().setObject(packet, input.getHeader() + 1, reference); return packet;
        }
        private static void consumed(TypedInputLayout input, HandoffStorage packet) {
            assertFalse(packet.getLive()); assertEquals(0, packet.getInputMode());
            assertNull(input.getPacket().getObject(packet, input.getHeader() + 1));
            assertEquals(0, input.state().getArguments().getDepth()); assertEquals(0, input.state().getArguments().retainedReferences());
        }
        private static void answer(Language language, TupleShape tuple, Object answer, long number, Object reference) {
            assertSame(TupleComplete.INSTANCE, answer);
            var pool = language.getHandoffState().get().getResults(); assertEquals(1, pool.getDepth());
            var storage = pool.completed();
            var layout = new FrameLayout(); int[] slots = {layout.bind("number", FrameSlotKind.Long), layout.bind("reference", FrameSlotKind.Object)};
            var frame = Truffle.getRuntime().createVirtualFrame(new Object[0], layout.build());
            tuple.consume(frame, answer, slots, 0); assertEquals(number, frame.getLong(slots[0])); assertSame(reference, frame.getObject(slots[1]));
            assertFalse(storage.getLive()); assertNull(tuple.getLayout().getObject(storage, 1)); clean(language);
        }
        private static void clean(Language language) {
            var handoff = language.getHandoffState().get(); assertNull(handoff.getPending());
            assertEquals(0, handoff.getArguments().getDepth()); assertEquals(0, handoff.getResults().getDepth());
            assertEquals(0, handoff.getArguments().retainedReferences()); assertEquals(0, handoff.getResults().retainedReferences());
        }

        static void recoverDeferred() {
            try (var context = Context.newBuilder("thc").allowExperimentalOptions(true)
                    .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
                    .option("engine.SingleTierCompilationThreshold", "10000000")
                    .option("engine.CompilationFailureAction", "Throw")
                    .option("compiler.DiagnoseFailure", "false")
                    .option("compiler.MaximumGraalGraphSize", "10000").build()) {
                context.initialize("thc"); context.enter();
                try {
                    var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                    var module = new java.util.LinkedHashMap<>(arithmeticSuffix(1024));
                    module.put("sourceFiles", List.of(Map.of("id", "control", "path", "StockRecovery.hs")));
                    module.put("sourceSpans", List.of(Map.of("id", "body", "file", "control", "startLine", 1,
                            "startColumn", 1, "endLine", 1, "endColumn", 2)));
                    var binding = new java.util.LinkedHashMap<>((Map<String, Object>) ((List<?>) module.get("bindings")).getFirst());
                    binding.put("source", "body"); module.put("bindings", List.of(binding));
                    var program = new Program(language, module, false, false, true);
                    var root = (FunctionRoot) program.entryTarget("entry").getRootNode();
                    var target = (OptimizedCallTarget) root.getCallTarget();
                    assertEquals(1, NodeUtil.findAllNodeInstances(root, AstDeferredArm.class).size());
                    var failure = assertThrows(OptimizationFailedException.class, () -> target.compile(true));
                    assertSame(target, failure.getCallTarget()); assertFalse(target.isSubmittedForCompilation());
                    assertEquals(526848L, Calls.target(target, new Object[]{0L, 3L}));
                    var fresh = recovered(root); assertNotNull(fresh, "deferred candidate must reduce only a fresh entry body");
                    assertTrue(root.isSelf(fresh)); assertNotSame(target, fresh);
                    assertEquals(1, NodeUtil.findAllNodeInstances(root, AstDeferredArm.class).size());
                    assertEquals(0, NodeUtil.findAllNodeInstances(fresh.getRootNode(), AstDeferredArm.class).size());
                    assertEquals(1, NodeUtil.findAllNodeInstances(fresh.getRootNode(), AstCaseArm.class).size());
                    assertEquals(0, root.getGraphBudgetGeneration());
                    assertEquals(1, ((FunctionRoot) fresh.getRootNode()).getGraphBudgetGeneration());
                    var optimized = (OptimizedCallTarget) fresh;
                    assertTrue(optimized.compile(true)); assertTrue(optimized.isValidLastTier());
                    ((OptimizedTruffleRuntime) Truffle.getRuntime()).bypassedInstalledCode(optimized);
                    long before = ((Number) program.diagnostics().get("compiledEntries")).longValue();
                    assertEquals(527872L, Calls.target(target, new Object[]{0L, 4L}));
                    assertTrue(((Number) program.diagnostics().get("compiledEntries")).longValue() > before);
                    assertSame(target, program.entryTarget("entry"));
                } finally { context.leave(); }
            }
        }

        static void recoverBytecodeOsr(boolean installedEntry) {
            try (var context = Context.newBuilder("thc").allowExperimentalOptions(true)
                    .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
                    .option("engine.SingleTierCompilationThreshold", "10000000")
                    .option("engine.OSRCompilationThreshold", "1024")
                    .option("engine.CompilationFailureAction", "Print")
                    .option("compiler.DiagnoseFailure", "false")
                    .option("compiler.MaximumGraalGraphSize", "10000").build()) {
                context.initialize("thc"); context.enter();
                try {
                    var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                    var side = BytecodeRootGen.create(language, BytecodeConfig.DEFAULT, b -> {
                        b.beginRoot(); b.beginReturn(); emitArithmetic(b, 0, 1024, () -> b.emitLoadArgument(1));
                        b.endReturn(); b.endRoot();
                    }).getNode(0);
                    side.configureCaseRegions(true, false, new BytecodeCaseRegion[0]);
                    var checkpoint = new BytecodeCheckpoint(); checkpoint.setArmed(true);
                    var entries = new BytecodeCheckpoint(); entries.setArmed(true);
                    var source = new BytecodeCaseRegion.Source(new com.oracle.truffle.api.bytecode.LocalAccessor[][]{
                            new com.oracle.truffle.api.bytecode.LocalAccessor[0]}, null);
                    var root = BytecodeRootGen.create(language, BytecodeConfig.DEFAULT, b -> {
                        b.beginRoot(); b.beginBlock();
                        if (installedEntry) {
                            b.emitCheckpointArmed(entries);
                            b.beginYield(); b.beginRecordContinuationOwner(); b.emitLoadConstant(Unit.INSTANCE); b.endRecordContinuationOwner(); b.endYield();
                        }
                        var counter = b.createLocal("counter", FrameSlotKind.Long);
                        var total = b.createLocal("total", FrameSlotKind.Long);
                        var answer = b.createLocal("case result", FrameSlotKind.Object);
                        b.beginStaticStoreLong(counter); b.emitLoadConstant(0L); b.endStaticStoreLong();
                        b.beginStaticStoreLong(total); b.emitLoadConstant(0L); b.endStaticStoreLong();
                        b.beginWhile(); b.beginLiteralBelow(4096L); b.emitStaticLoadLong(counter); b.endLiteralBelow();
                        b.beginBlock(); b.emitCheckpointArmed(checkpoint);
                        // Use the same branch/store shape as BytecodeProgram.partitionedCase.
                        var complete = b.createLabel();
                        b.beginIfThen(); b.emitInlineCaseRegions(); b.beginBlock();
                        b.beginStaticStoreObject(answer);
                        emitArithmetic(b, 0, 1024, () -> b.emitStaticLoadLong(counter));
                        b.endStaticStoreObject(); b.emitBranch(complete); b.endBlock(); b.endIfThen();
                        b.beginStaticStoreObject(answer);
                        b.beginCallCaseRegion(0, source); b.emitStaticLoadLong(counter);
                        b.emitLoadConstant(MaskingState.UNMASKED); b.endCallCaseRegion();
                        b.endStaticStoreObject(); b.emitLabel(complete);
                        b.beginStaticStoreLong(total); b.beginAdd(); b.emitStaticLoadLong(total);
                        b.emitStaticLoadObject(answer); b.endAdd(); b.endStaticStoreLong();
                        b.beginStaticStoreLong(counter); b.beginAdd(); b.emitStaticLoadLong(counter);
                        b.emitLoadConstant(1L); b.endAdd(); b.endStaticStoreLong();
                        b.endBlock(); b.endWhile(); b.beginReturn(); b.emitStaticLoadLong(total); b.endReturn();
                        b.endBlock(); b.endRoot();
                    }).getNode(0);
                    root.configureCaseRegions(false, true, new BytecodeCaseRegion[]{
                            new BytecodeCaseRegion(new Object[0], 1, new com.oracle.truffle.api.RootCallTarget[]{side.getCallTarget()},
                                    new CaptureLayout[]{null}, false)});
                    var target = root.getCallTarget();
                    ContinuationResult parked = null;
                    int parkedPc = -1;
                    if (installedEntry) {
                        parked = assertInstanceOf(ContinuationResult.class, Calls.target(target, new Object[]{0L}));
                        parkedPc = parked.getContinuationRootNode().getLocation().getBytecodeIndex();
                        assertSame(root, parked.getContinuationRootNode().getSourceRootNode());
                        assertEquals(1, entries.getVisits().get()); assertEquals(0, checkpoint.getVisits().get());
                        assertTrue(((OptimizedCallTarget) target).compile(true));
                        assertTrue(((OptimizedCallTarget) target).isValidLastTier());
                        ((OptimizedTruffleRuntime) Truffle.getRuntime()).bypassedInstalledCode((OptimizedCallTarget) target);
                        assertEquals(10733223936L, parked.continueWith(Unit.INSTANCE));
                        assertTrue(((OptimizedCallTarget) target).isValidLastTier(),
                                "the independently installed normal entry must survive the real OSR failure");
                    } else assertEquals(10733223936L, Calls.target(target, new Object[]{0L}));
                    assertEquals(4096, checkpoint.getVisits().get()); assertEquals(0, checkpoint.getCompiledVisits().get());
                    var failure = root.graphFailure.get(); assertNotNull(failure, "actual bytecode OSR must fail the fixed budget");
                    assertInstanceOf(BaseOSRRootNode.class, failure.target().getRootNode());
                    assertSame(root, GraphRecovery.source(failure.target().getRootNode()));
                    assertTrue(GraphRecovery.graphTooBig(failure.reason(), true, true));
                    assertFalse(failure.target().isSubmittedForCompilation()); assertFalse(failure.target().isValid());
                    assertNotSame(target, failure.target());
                    var failedBytecode = root.getBytecodeNode();
                    if (installedEntry) {
                        var next = assertInstanceOf(ContinuationResult.class, Calls.target(target, new Object[]{0L}));
                        assertEquals(2, entries.getVisits().get(), "new ingress executes exactly once");
                        assertNotSame(root, next.getContinuationRootNode().getSourceRootNode(),
                                "installed normal entry must claim the failed OSR receipt before ingress");
                        assertNotNull(recovered(root));
                        assertEquals(10733223936L, next.continueWith(Unit.INSTANCE));
                        assertSame(root, parked.getContinuationRootNode().getSourceRootNode());
                        assertEquals(parkedPc, parked.getContinuationRootNode().getLocation().getBytecodeIndex());
                    } else assertEquals(10733223936L, Calls.target(target, new Object[]{0L}));
                    assertEquals(8192, checkpoint.getVisits().get());
                    assertTrue(checkpoint.getCompiledVisits().get() > 0, "replacement must execute actual OSR-compiled loop visits");
                    var fresh = (BytecodeRoot) recovered(root).getRootNode();
                    assertTrue(root.isSelf(fresh.getCallTarget())); assertEquals(0, root.getGraphBudgetGeneration());
                    assertEquals(1, fresh.getGraphBudgetGeneration());
                    assertSame(failedBytecode, root.getBytecodeNode()); assertNotSame(failedBytecode, fresh.getBytecodeNode());
                    System.out.println("STOCK BYTECODE OSR RECOVERY effects=" + checkpoint.getVisits().get() +
                            " compiledVisits=" + checkpoint.getCompiledVisits().get());
                } finally { context.leave(); }
            }
        }

        private static void emitArithmetic(BytecodeRootGen.Builder b, int from, int end, Runnable value) {
            b.beginAdd();
            if (end - from == 1) { value.run(); b.emitLoadConstant((long) from); }
            else { emitArithmetic(b, from, (from + end) / 2, value); emitArithmetic(b, (from + end) / 2, end, value); }
            b.endAdd();
        }

        private static com.oracle.truffle.api.RootCallTarget recovered(GuestRoot root) {
            for (Node child : root.getChildren()) {
                if (child instanceof com.oracle.truffle.api.nodes.DirectCallNode call &&
                        call.getCallTarget() instanceof com.oracle.truffle.api.RootCallTarget called && root.isSelf(called)) return called;
            }
            return null;
        }

        static void concurrentEntry() throws Exception {
            try (var context = Context.newBuilder("thc").allowExperimentalOptions(true)
                    .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
                    .option("engine.SingleTierCompilationThreshold", "10000000")
                    .option("engine.CompilationFailureAction", "Throw")
                    .option("compiler.DiagnoseFailure", "false")
                    .option("compiler.MaximumGraalGraphSize", "10000").build();
                 var workers = Executors.newFixedThreadPool(2)) {
                context.initialize("thc"); context.enter();
                var reached = new CountDownLatch(1); var release = new CountDownLatch(1);
                try {
                    var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                    var effects = new AtomicInteger(); var source = new AtomicReference<RootNode>();
                    var gate = new CloneGate(new AstSameFrameArm(new Heavy(new long[4096], effects, source)), reached, release);
                    var root = new FunctionRoot(language, new FrameLayout().build(), "concurrent recovery", null,
                            new int[0], new int[0], new int[0], gate, new Metrics(true));
                    var target = (OptimizedCallTarget) root.getCallTarget();
                    assertThrows(OptimizationFailedException.class, () -> target.compile(true));
                    assertFalse(target.isSubmittedForCompilation()); assertEquals(0, effects.get());
                    gate.armed.set(true);
                    var first = workers.submit(() -> {
                        context.enter();
                        try { return Calls.target(target, new Object[]{0L}); }
                        finally { context.leave(); }
                    });
                    assertTrue(reached.await(20, TimeUnit.SECONDS), "claimant must reach candidate preparation");
                    assertTrue(root.graphFailure.get().claimed()); assertEquals(0, effects.get());
                    var second = workers.submit(() -> {
                        context.enter();
                        try { return Calls.target(target, new Object[]{0L}); }
                        finally { context.leave(); }
                    });
                    Object oldAnswer = second.get(20, TimeUnit.SECONDS);
                    assertEquals(1, effects.get()); assertSame(root, source.get());
                    assertTrue(root.graphFailure.get().claimed(), "competing entry cannot steal the terminal receipt");
                    release.countDown();
                    assertEquals(oldAnswer, first.get(20, TimeUnit.SECONDS));
                    assertEquals(2, effects.get()); assertNotSame(root, source.get());
                    var fresh = (FunctionRoot) source.get();
                    assertTrue(root.isSelf(fresh.getCallTarget())); assertEquals(root.mask, fresh.mask);
                    assertEquals(1, fresh.getGraphBudgetGeneration()); assertEquals(0, root.getGraphBudgetGeneration());
                    assertNull(root.graphFailure.get());
                    assertEquals(oldAnswer, Calls.target(target, new Object[]{0L}));
                    assertEquals(3, effects.get()); assertSame(fresh, source.get());
                } finally { release.countDown(); context.leave(); workers.shutdownNow(); }
            }
        }

        private static final class CloneGate extends Expr {
            @Child private Expr body;
            private final AtomicBoolean armed = new AtomicBoolean();
            private final CountDownLatch reached, release;
            CloneGate(Expr body, CountDownLatch reached, CountDownLatch release) {
                this.body = body; this.reached = reached; this.release = release; setRepresentation(LONG);
            }
            @Override public Node copy() {
                Node copy = super.copy();
                if (armed.compareAndSet(true, false)) {
                    reached.countDown();
                    try { if (!release.await(20, TimeUnit.SECONDS)) throw new AssertionError("candidate preparation timed out"); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
                }
                return copy;
            }
            @Override public Object execute(VirtualFrame frame) { return body.execute(frame); }
        }

        static void launcherPolicy() throws Exception {
            try (var context = thc.Main.withContextProfile(Context.newBuilder("thc").allowNativeAccess(true), thc.ContextProfile.LAUNCHER)
                    .option("engine.SingleTierCompilationThreshold", "2")
                    .option("compiler.DiagnoseFailure", "false")
                    .option("compiler.MaximumGraalGraphSize", "10000").build();
                 var worker = Executors.newSingleThreadExecutor()) {
                context.initialize("thc"); context.enter();
                var runtime = (OptimizedTruffleRuntime) Truffle.getRuntime();
                var reached = new CountDownLatch(1); var release = new CountDownLatch(1);
                var attempts = new AtomicInteger();
                OptimizedTruffleRuntimeListener listener = null;
                try {
                    var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                    var effects = new AtomicInteger(); var source = new AtomicReference<RootNode>();
                    var metrics = new Metrics(true);
                    var root = new FunctionRoot(language, new FrameLayout().build(), "launcher recovery", null,
                            new int[0], new int[0], new int[0],
                            new AstSameFrameArm(new Heavy(new long[4096], effects, source)), metrics);
                    var target = (OptimizedCallTarget) root.getCallTarget();
                    long expected = 17;
                    for (int i = 0; i < 4096; i++) expected = expected * 17 + i;
                    // One visible warm entry; only the second entry reaches the compilation threshold.
                    assertEquals(expected, Calls.target(target, new Object[]{0L}));
                    assertEquals(1, effects.get()); assertSame(root, source.get());
                    assertFalse(target.isSubmittedForCompilation()); assertNull(root.graphFailure.get());
                    listener = new OptimizedTruffleRuntimeListener() {
                        @Override public void onCompilationStarted(OptimizedCallTarget compiling, AbstractCompilationTask task) {
                            if (compiling != target) return;
                            attempts.incrementAndGet();
                            reached.countDown();
                            try {
                                if (!release.await(30, TimeUnit.SECONDS))
                                    throw new AssertionError("compiler release timed out");
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt(); throw new AssertionError(interrupted);
                            }
                        }
                    };
                    runtime.addListener(listener);
                    var entry = worker.submit(() -> {
                        context.enter();
                        try { return Calls.target(target, new Object[]{0L}); }
                        finally { context.leave(); }
                    });
                    assertTrue(reached.await(20, TimeUnit.SECONDS), "the real entry must submit compilation");
                    assertEquals(expected, entry.get(10, TimeUnit.SECONDS), "guest progress must not wait for compilation");
                    assertEquals(2, effects.get()); assertSame(root, source.get());
                    assertTrue(target.isSubmittedForCompilation()); assertNull(root.graphFailure.get());
                    release.countDown();
                    runtime.waitForCompilation(target, 20000);
                    assertFalse(target.isSubmittedForCompilation()); assertFalse(target.isValid());
                    assertNotNull(root.graphFailure.get());
                    assertSame(target, root.graphFailure.get().target());
                    assertTrue(root.graphFailure.get().reason().contains("GraphTooBigBailoutException"));
                    assertEquals(2, effects.get(), "compiler completion has no guest effects");
                    assertEquals(expected, Calls.target(target, new Object[]{0L}));
                    assertEquals(3, effects.get()); assertNotSame(root, source.get());
                    var fresh = (FunctionRoot) source.get();
                    assertEquals(0, root.getGraphBudgetGeneration()); assertEquals(1, fresh.getGraphBudgetGeneration());
                    assertSame(root.compilationOwner(), fresh.compilationOwner());
                    var installed = (OptimizedCallTarget) fresh.getCallTarget();
                    installed.compile(true);
                    runtime.waitForCompilation(installed, 20000);
                    assertTrue(installed.isValidLastTier()); assertEquals(3, effects.get());
                    long before = metrics.getCompiledEntries();
                    assertEquals(expected, Calls.target(target, new Object[]{0L}));
                    assertEquals(4, effects.get()); assertSame(fresh, source.get());
                    assertTrue(metrics.getCompiledEntries() > before, "the real entry must execute installed replacement code");
                    assertEquals(1, attempts.get(), "a physical failed target must never attempt compilation again");
                } finally {
                    release.countDown();
                    if (listener != null) runtime.removeListener(listener);
                    context.leave(); worker.shutdownNow();
                }
            }
        }

        static void recoverBytecode() {
            try (var context = Context.newBuilder("thc").allowExperimentalOptions(true)
                    .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
                    .option("engine.SingleTierCompilationThreshold", "10000000")
                    .option("engine.CompilationFailureAction", "Throw")
                    .option("compiler.DiagnoseFailure", "false")
                    .option("compiler.MaximumGraalGraphSize", "10000").build()) {
                context.initialize("thc"); context.enter();
                try {
                    var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                    var module = arithmeticSuffix(1024);
                    var program = new BytecodeProgram(language, module, false);
                    var root = (BytecodeRoot) program.entryTarget("entry").getRootNode();
                    var target = (OptimizedCallTarget) root.getCallTarget();
                    var failure = assertThrows(OptimizationFailedException.class, () -> target.compile(true));
                    assertSame(target, failure.getCallTarget());
                    assertTrue(failure.toString().contains("GraphTooBigBailoutException"));
                    assertFalse(target.isSubmittedForCompilation()); assertFalse(target.isValid());
                    assertEquals(0, root.getGraphBudgetGeneration());
                    assertEquals(526848L, Calls.target(target, new Object[]{0L, 3L}));
                    com.oracle.truffle.api.RootCallTarget fresh = null;
                    for (Node child : root.getChildren()) {
                        if (child instanceof com.oracle.truffle.api.nodes.DirectCallNode call &&
                                call.getCallTarget() instanceof com.oracle.truffle.api.RootCallTarget called && root.isSelf(called))
                            fresh = called;
                    }
                    assertNotNull(fresh, "next real bytecode entry must publish a fresh reduced root");
                    assertNotSame(target, fresh); assertTrue(root.isSelf(fresh));
                    assertEquals(0, root.getGraphBudgetGeneration(), "the original code/decision stays unchanged");
                    assertEquals(1, ((BytecodeRoot) fresh.getRootNode()).getGraphBudgetGeneration());
                    var optimized = (OptimizedCallTarget) fresh;
                    assertTrue(optimized.compile(true)); assertTrue(optimized.isValidLastTier());
                    ((OptimizedTruffleRuntime) Truffle.getRuntime()).bypassedInstalledCode(optimized);
                    long before = ((Number) program.diagnostics().get("compiledEntries")).longValue();
                    assertEquals(526848L, Calls.target(target, new Object[]{0L, 3L}));
                    assertTrue(((Number) program.diagnostics().get("compiledEntries")).longValue() > before);
                    assertSame(target, program.entryTarget("entry"));
                } finally { context.leave(); }
            }
        }

        private static Map<String, Object> arithmeticSuffix(int leaves) {
            var scalar = Map.<String, Object>of("kind", "long", "primReps", List.of("IntRep"), "evaluated", true);
            var closure = Map.<String, Object>of("kind", "closure", "primReps", List.of("BoxedRep (Just Lifted)"), "evaluated", true);
            var parameter = Map.of("id", "x", "name", "x", "lifted", false, "rep", scalar);
            var selected = Map.of("id", "selected", "name", "selected", "lifted", false, "rep", scalar);
            var body = Arrays.asList("case", List.of("var", "x", Map.of("rep", scalar)), "selected",
                    List.of(Arrays.asList("default", null, List.of(), arithmetic(0, leaves, scalar))),
                    Map.of("rep", scalar, "binder", selected));
            return Map.of("instrument", true, "bindings", List.of(Map.of("id", "entry", "name", "entry",
                    "lifted", true, "rep", closure, "expr", List.of("lam", List.of(parameter), body,
                            Map.of("rep", closure, "resultRep", scalar, "entryStrict", List.of(false))))));
        }

        private static List<Object> arithmetic(int from, int end, Map<String, Object> scalar) {
            Object left = end - from == 1 ? List.of("var", "selected", Map.of("rep", scalar))
                    : arithmetic(from, (from + end) / 2, scalar);
            Object right = end - from == 1 ? List.of("lit", "int", Integer.toString(from), Map.of("rep", scalar))
                    : arithmetic((from + end) / 2, end, scalar);
            return List.of("app", List.of("prim", "+#"), List.of(left, right), List.of(false, false), false, false,
                    Map.of("rep", scalar));
        }

        static void bytecodeEntry() {
            try (var context = Context.newBuilder("thc").allowExperimentalOptions(true)
                    .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
                    .option("engine.SingleTierCompilationThreshold", "10000000")
                    .option("engine.CompilationFailureAction", "Throw")
                    .option("compiler.DiagnoseFailure", "false").build()) {
                context.initialize("thc"); context.enter();
                try {
                    var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                    var prefixes = new AtomicInteger(); var suffixes = new AtomicInteger();
                    var compiled = new AtomicInteger();
                    var old = StockRecoveryEntryRootGen.create(language, BytecodeConfig.DEFAULT, b -> {
                        b.beginRoot(); b.emitEffect(prefixes, compiled);
                        b.beginYield(); b.emitLoadArgument(0); b.endYield();
                        b.emitEffect(suffixes, compiled);
                        b.beginReturn(); b.emitLoadArgument(0); b.endReturn(); b.endRoot();
                    }).getNode(0);
                    Object original = new Object(), later = new Object();
                    var saved = (com.oracle.truffle.api.bytecode.ContinuationResult)
                            Calls.target(old.getCallTarget(), new Object[]{original});
                    assertSame(original, saved.getResult()); assertEquals(1, prefixes.get()); assertEquals(0, suffixes.get());
                    var source = saved.getContinuationRootNode().getSourceRootNode();
                    var location = saved.getContinuationRootNode().getLocation();
                    var oldFrame = saved.getFrame();
                    // The required old suspension has already created its real cached interpreter.
                    // Stock cloning preserves that interpreter tier but creates fresh continuation owners.
                    var fresh = old.freshCopy();
                    var target = (OptimizedCallTarget) fresh.getCallTarget();
                    assertTrue(target.compile(true)); assertEquals(1, prefixes.get());
                    // Install the public HotSpot call-boundary stubs without executing a guest call.
                    ((OptimizedTruffleRuntime) Truffle.getRuntime()).bypassedInstalledCode(target);
                    old.install(target);
                    var freshSaved = (com.oracle.truffle.api.bytecode.ContinuationResult)
                            Calls.target(old.getCallTarget(), new Object[]{later});
                    assertSame(later, freshSaved.getResult()); assertEquals(2, prefixes.get());
                    assertTrue(compiled.get() > 0, "actual fresh compiled entry");
                    assertSame(fresh, freshSaved.getContinuationRootNode().getSourceRootNode());
                    assertSame(original, saved.continueWith(Unit.INSTANCE));
                    assertEquals(2, prefixes.get()); assertEquals(1, suffixes.get());
                    assertSame(later, freshSaved.continueWith(Unit.INSTANCE));
                    assertEquals(2, prefixes.get()); assertEquals(2, suffixes.get());
                    assertSame(old, source); assertSame(source, saved.getContinuationRootNode().getSourceRootNode());
                    // Stock resume may update the cached interpreter tier, but not the logical PC or saved frame.
                    assertEquals(location.getBytecodeIndex(), saved.getContinuationRootNode().getLocation().getBytecodeIndex());
                    assertSame(oldFrame, saved.getFrame()); assertNotSame(oldFrame, freshSaved.getFrame());
                } finally { context.leave(); }
            }
        }

        static void recoverOsr() {
            try (var context = Context.newBuilder("thc").allowExperimentalOptions(true)
                    .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
                    .option("engine.SingleTierCompilationThreshold", "10000000")
                    .option("engine.OSRCompilationThreshold", "1024")
                    .option("engine.CompilationFailureAction", "Print")
                    .option("compiler.DiagnoseFailure", "false")
                    .option("compiler.MaximumGraalGraphSize", "10000").build()) {
                context.initialize("thc"); context.enter();
                try {
                    var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                    var effects = new AtomicInteger(); var compiled = new AtomicInteger();
                    var source = new AtomicReference<RootNode>();
                    long[] values = new long[4096];
                    for (int i = 0; i < values.length; i++) values[i] = i + 1;
                    var layout = new FrameLayout(); int count = layout.bind("count", FrameSlotKind.Long);
                    var body = new LoopWork(new AstSameFrameArm(new Heavy(values, effects, source)), count, compiled);
                    var root = new FunctionRoot(language, layout.build(), "stock OSR recovery", null,
                            new int[0], new int[0], new int[0], body, new Metrics(true));
                    var target = root.getCallTarget();
                    var oldLoop = (OptimizedOSRLoopNode) body.loop;
                    assertEquals(4096L, Calls.target(target, new Object[]{0L}));
                    assertEquals(4096, effects.get(), "the failed OSR must not replay or abandon the old activation");
                    assertEquals(0, compiled.get()); assertSame(root, source.get());
                    var failure = root.graphFailure.get();
                    assertNotNull(failure, "a real OSR failure must be attributed to its original owner");
                    assertInstanceOf(BaseOSRRootNode.class, failure.target().getRootNode());
                    assertTrue(GraphRecovery.graphTooBig(failure.reason(), true, true));
                    assertFalse(failure.target().isSubmittedForCompilation()); assertFalse(failure.target().isValid());
                    assertEquals(0, root.getGraphBudgetGeneration());
                    assertEquals(4096L, Calls.target(target, new Object[]{0L}));
                    assertEquals(8192, effects.get(), "two complete invocations, not failed-call replay");
                    var fresh = (FunctionRoot) source.get();
                    assertNotSame(root, fresh); assertTrue(root.isSelf(fresh.getCallTarget()));
                    assertEquals(1, fresh.getGraphBudgetGeneration());
                    assertTrue(compiled.get() > 0, "the replacement must really execute OSR-compiled iterations");
                    assertSame(oldLoop, body.loop); assertEquals(0, root.getGraphBudgetGeneration());
                    var freshLoop = NodeUtil.findAllNodeInstances(fresh, LoopWork.class).getFirst().loop;
                    assertNotSame(oldLoop, freshLoop);
                    assertNotNull(((OptimizedOSRLoopNode) freshLoop).getCompiledOSRLoop());
                    System.out.println("STOCK AST OSR RECOVERY effects=" + effects.get() + " compiledIterations=" + compiled.get());
                } finally { context.leave(); }
            }
        }

        private static final class LoopWork extends Expr {
            @Child private LoopNode loop;
            private final int count;
            LoopWork(Expr work, int count, AtomicInteger compiled) {
                this.count = count; setRepresentation(LONG);
                loop = Truffle.getRuntime().createLoopNode(new WorkRepeater(work, count, compiled));
            }
            @Override public Object execute(VirtualFrame frame) { return executeLong(frame); }
            @Override public long executeLong(VirtualFrame frame) {
                frame.setLong(count, 0L); loop.execute(frame); return frame.getLong(count);
            }
        }
        private static final class WorkRepeater extends Node implements RepeatingNode {
            @Child private Expr work;
            private final int count;
            private final AtomicInteger compiled;
            WorkRepeater(Expr work, int count, AtomicInteger compiled) {
                this.work = work; this.count = count; this.compiled = compiled;
            }
            @Override public boolean executeRepeating(VirtualFrame frame) {
                try { work.executeLong(frame); }
                catch (com.oracle.truffle.api.nodes.UnexpectedResultException failure) { throw new AssertionError(failure); }
                if (CompilerDirectives.inCompiledCode()) compiled.incrementAndGet();
                long next = frame.getLong(count) + 1; frame.setLong(count, next);
                return next < 4096;
            }
        }

        static void recoverAst(boolean twice) {
            try (var context = Context.newBuilder("thc").allowExperimentalOptions(true)
                    .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
                    .option("engine.SingleTierCompilationThreshold", "10000000")
                    .option("engine.CompilationFailureAction", "Throw")
                    .option("compiler.DiagnoseFailure", "false")
                    .option("compiler.MaximumGraalGraphSize", "10000").build()) {
                context.initialize("thc"); context.enter();
                try {
                    var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                    var effects = new AtomicInteger(); var source = new AtomicReference<RootNode>();
                    long[] values = new long[4096];
                    for (int i = 0; i < values.length; i++) values[i] = i + 1;
                    Expr heavy = new AstSameFrameArm(new Heavy(values, effects, source));
                    if (twice) heavy = new Pair(heavy, new AstSameFrameArm(new Heavy(values, effects, source)));
                    var metrics = new Metrics(true);
                    var root = new FunctionRoot(language, new FrameLayout().build(), "stock recovery", null,
                            new int[0], new int[0], new int[0], heavy, metrics);
                    var target = (OptimizedCallTarget) root.getCallTarget();
                    var oldLoops = NodeUtil.findAllNodeInstances(root, OptimizedOSRLoopNode.class);
                    var failure = assertThrows(OptimizationFailedException.class, () -> target.compile(true));
                    assertSame(target, failure.getCallTarget());
                    assertTrue(failure.toString().contains("GraphTooBigBailoutException"));
                    assertFalse(target.isSubmittedForCompilation()); assertFalse(target.isValidLastTier());
                    assertFalse(target.canBeInlined()); assertEquals(0, effects.get());
                    assertEquals(0, root.getGraphBudgetGeneration(), "callback must not rewrite the old body");
                    long expected = 17;
                    for (int i = 0; i < values.length; i++) expected = (expected ^ values[i]) * 17 + i;
                    int perCall = twice ? 2 : 1;
                    if (twice) expected *= 2;
                    assertEquals(expected, Calls.target(target, new Object[]{0L}));
                    assertEquals(perCall, effects.get());
                    assertNotSame(root, source.get(), "the next fresh entry must run replacement-owned code");
                    var replacement = (FunctionRoot) source.get();
                    assertTrue(root.isSelf(replacement.getCallTarget())); assertEquals(root.mask, replacement.mask);
                    assertSame(root.getFrameDescriptor(), replacement.getFrameDescriptor());
                    assertEquals(oldLoops, NodeUtil.findAllNodeInstances(root, OptimizedOSRLoopNode.class));
                    assertEquals(0, root.getGraphBudgetGeneration());
                    assertEquals(1, replacement.getGraphBudgetGeneration());
                    var fresh = (OptimizedCallTarget) replacement.getCallTarget();
                    if (twice) {
                        var nextFailure = assertThrows(OptimizationFailedException.class, () ->
                                ((OptimizedCallTarget) source.get().getCallTarget()).compile(true));
                        assertSame(fresh, nextFailure.getCallTarget());
                        assertTrue(nextFailure.toString().contains("GraphTooBigBailoutException"));
                        assertFalse(fresh.isSubmittedForCompilation()); assertEquals(perCall, effects.get());
                        assertEquals(expected, Calls.target(target, new Object[]{0L}));
                        assertEquals(perCall * 2, effects.get());
                        assertNotSame(replacement, source.get());
                        replacement = (FunctionRoot) source.get();
                        assertEquals(2, replacement.getGraphBudgetGeneration());
                        fresh = (OptimizedCallTarget) replacement.getCallTarget();
                    }
                    assertTrue(fresh.compile(true)); assertTrue(fresh.isValidLastTier());
                    int beforeEffects = twice ? perCall * 2 : perCall;
                    assertEquals(beforeEffects, effects.get(), "explicit fresh compilation has no guest effects");
                    long before = metrics.getCompiledEntries();
                    assertEquals(expected, Calls.target(target, new Object[]{0L}));
                    assertEquals(beforeEffects + perCall, effects.get()); assertTrue(metrics.getCompiledEntries() > before);
                    assertSame(replacement, source.get());
                } finally { context.leave(); }
            }
        }

        private static final class Pair extends Expr {
            @Child private Expr left;
            @Child private Expr right;
            Pair(Expr left, Expr right) { this.left = left; this.right = right; setRepresentation(LONG); }
            @Override public Object execute(VirtualFrame frame) { return executeLong(frame); }
            @Override public long executeLong(VirtualFrame frame) {
                try { return left.executeLong(frame) + right.executeLong(frame); }
                catch (com.oracle.truffle.api.nodes.UnexpectedResultException failure) { throw new AssertionError(failure); }
            }
        }

        private static final class Heavy extends Expr {
            private final long[] values;
            private final AtomicInteger effects;
            private final AtomicReference<RootNode> source;
            Heavy(long[] values, AtomicInteger effects, AtomicReference<RootNode> source) {
                this.values = values; this.effects = effects; this.source = source; setRepresentation(LONG);
            }
            @Override public Object execute(VirtualFrame frame) { return executeLong(frame); }
            @Override @ExplodeLoop public long executeLong(VirtualFrame frame) {
                effect();
                long result = 17;
                for (int i = 0; i < values.length; i++) result = (result ^ values[i]) * 17 + i;
                return result;
            }
            @CompilerDirectives.TruffleBoundary private void effect() {
                effects.incrementAndGet(); source.set(getRootNode());
            }
        }

        private static ContextRoot source(RootNode root) {
            if (root instanceof ContextRoot contextRoot) return contextRoot;
            if (root instanceof BaseOSRRootNode) {
                ContextRoot source = null;
                for (Node child : root.getChildren()) {
                    if (!(child.getRootNode() instanceof ContextRoot candidate)) continue;
                    if (source != null && source != candidate) return null;
                    source = candidate;
                }
                return source;
            }
            return null;
        }

        static void ownership(Context first, Context second, boolean bytecode) {
            var events = new CopyOnWriteArrayList<Event>();
            var runtime = (OptimizedTruffleRuntime) Truffle.getRuntime();
            var listener = new OptimizedTruffleRuntimeListener() {
                @Override public void onCompilationSuccess(OptimizedCallTarget target, AbstractCompilationTask task,
                        TruffleCompilerListener.GraphInfo graph, TruffleCompilerListener.CompilationResultInfo result) {
                    if (!(target.getRootNode() instanceof BaseOSRRootNode)) return;
                    ContextRoot root = source(target.getRootNode());
                    events.add(new Event(target, root, root == null ? null : root.compilationOwner()));
                }
            };
            runtime.addListener(listener);
            try {
                Object previousOwner = null;
                for (Context context : List.of(first, second)) {
                    context.enter();
                    try {
                        var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                        Object owner = Language.currentState().getCompilationOwner();
                        assertNotSame(previousOwner, owner);
                        previousOwner = owner;
                        for (boolean shared : List.of(false, true)) {
                            ContextRoot root = bytecode ? bytecodeLoop(language) : new AstLoop(language);
                            if (shared) root.shareCompilationOwnership();
                            int before = events.size();
                            if (root instanceof AstLoop ast) {
                                ast.getCallTarget();
                                ast.loop.forceOSR();
                                assertEquals(0, ((Once) ast.loop.getRepeatingNode()).effects,
                                        "forced compilation must not execute the guest");
                            } else {
                                assertEquals(20000L, Calls.target(root.getCallTarget(), new Object[0]),
                                        "one real loop invocation, not a training call");
                            }
                            List<Event> actual = events.subList(before, events.size()).stream()
                                    .filter(event -> event.source == root).toList();
                            assertFalse(actual.isEmpty(), "an actual OSR compiler callback must identify the original root");
                            for (Event event : actual) {
                                assertSame(root, event.source);
                                assertSame(shared ? null : owner, event.owner);
                                assertFalse(event.target.isSubmittedForCompilation());
                            }
                            System.out.println("STOCK OSR OWNER " + (bytecode ? "bytecode" : "ast") +
                                    " shared=" + shared + " callbacks=" + actual.size());
                        }
                    } finally { context.leave(); }
                }
            } finally { runtime.removeListener(listener); }
        }

        private static BytecodeRoot bytecodeLoop(Language language) {
            return BytecodeRootGen.create(language, BytecodeConfig.DEFAULT, b -> {
                b.beginRoot(); b.beginBlock();
                var counter = b.createLocal("counter", null);
                b.beginStoreLocal(counter); b.emitLoadConstant(0L); b.endStoreLocal();
                b.beginWhile();
                b.beginLiteralBelow(20000L); b.emitLoadLocal(counter); b.endLiteralBelow();
                b.beginStoreLocal(counter); b.beginAdd(); b.emitLoadLocal(counter);
                b.emitLoadConstant(1L); b.endAdd(); b.endStoreLocal();
                b.endWhile();
                b.beginReturn(); b.emitLoadLocal(counter); b.endReturn();
                b.endBlock(); b.endRoot();
            }).getNode(0);
        }

        private static final class Once extends Node implements RepeatingNode {
            int effects;
            @Override public boolean executeRepeating(VirtualFrame frame) { effects++; return false; }
        }
        private static final class AstLoop extends ContextRoot {
            @Child OptimizedOSRLoopNode loop;
            AstLoop(Language language) {
                super(language, null);
                loop = (OptimizedOSRLoopNode) Truffle.getRuntime().createLoopNode(new Once());
            }
            @Override public Object execute(VirtualFrame frame) { loop.execute(frame); return 47L; }
        }
    }
}
