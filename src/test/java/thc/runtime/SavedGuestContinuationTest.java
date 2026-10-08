// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.bytecode.BytecodeConfig;
import com.oracle.truffle.api.bytecode.ContinuationResult;
import com.oracle.truffle.api.bytecode.LocalAccessor;
import com.oracle.truffle.api.frame.FrameSlotKind;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.runtime.OptimizedCallTarget;
import com.oracle.truffle.runtime.OptimizedTruffleRuntime;
import java.util.Arrays;
import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import thc.Language;
import thc.Main;
import static org.junit.jupiter.api.Assertions.*;

class SavedGuestContinuationTest {
    private static final class LifetimeOwner extends GuestRoot {
        LifetimeOwner(Language language) { super(language, com.oracle.truffle.api.frame.FrameDescriptor.newBuilder().build()); }
        @Override public Object execute(VirtualFrame frame) { throw new AssertionError("Only the saved activation is entered"); }
        @Override public long bloom(VirtualFrame frame) { return 0L; }
    }
    private static final class LifetimeDriver extends RootNode {
        @Child private Force force = new Force(new Metrics(false));
        LifetimeDriver(Language language) { super(language); }
        @Override public Object execute(VirtualFrame frame) {
            return force.drainStack((SavedGuestContinuation) frame.getArguments()[0]);
        }
    }
    private static final class LifetimeObservation {
        WeakReference<Object> produced;
    }
    private static WeakReference<Object> deadWitness() { return new WeakReference<>(new Object()); }
    private static boolean collect(WeakReference<Object> reference) {
        long deadline = System.nanoTime() + 3_000_000_000L;
        do {
            System.gc();
            if (reference.refersTo(null)) return true;
            try { Thread.sleep(10); }
            catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
        } while (System.nanoTime() < deadline);
        return reference.refersTo(null);
    }
    private static Object observeReleasedChild(LifetimeObservation observation) {
        Object live = new Object();
        var liveReference = new WeakReference<>(live);
        var witness = deadWitness();
        try {
            boolean collected = collect(observation.produced);
            assertTrue(witness.refersTo(null), "Independent dead key must establish collector progress");
            assertTrue(liveReference.refersTo(live), "A Java-held key must remain live");
            assertTrue(collected, "A discarded child result must die during the unrelated suffix");
            return Unit.INSTANCE;
        } finally { Reference.reachabilityFence(live); }
    }
    private static AstContinuation lifetimeActivation(Language language, LifetimeObservation observation, int scopes) {
        var owner = new LifetimeOwner(language);
        var childFrame = Truffle.getRuntime().createMaterializedFrame(new Object[]{0L}, owner.getFrameDescriptor());
        var child = new AstContinuation(owner, AstStackSpill.INSTANCE, MaskingState.UNMASKED, childFrame,
            List.of((frame, input) -> {
                assertSame(Unit.INSTANCE, input);
                Object key = new Object();
                observation.produced = new WeakReference<>(key);
                return key;
            }), StackAnnotationState.EMPTY);
        var suspended = new CallSegmentSuspended(new CallSegment(child), null, null, true);
        var cut = AstControl.captureChild(owner, suspended)
            .append((frame, input) -> Unit.INSTANCE)
            .append((frame, input) -> { assertSame(Unit.INSTANCE, input); return observeReleasedChild(observation); });
        for (int i = 0; i < scopes; i++)
            cut.enclose(steps -> (frame, input) -> AstContinuations.resumeAstSteps(frame, steps, input));
        return cut.freeze(owner, Truffle.getRuntime().createMaterializedFrame(new Object[]{0L}, owner.getFrameDescriptor()));
    }
    @ParameterizedTest @ValueSource(ints = {0, 2})
    void consumedChildDoesNotOwnItsDiscardedResultDuringSuffix(int scopes) {
        try (var context = Main.executionContext()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var observation = new LifetimeObservation();
                // The factory returns before the child creates its key; the driver
                // legitimately retains the parent activation during the suffix.
                assertSame(Unit.INSTANCE, Calls.target(new LifetimeDriver(language).getCallTarget(),
                    new Object[]{lifetimeActivation(language, observation, scopes)}));
            } finally { context.leave(); }
        }
    }

    @Test void compiledBytecodeResumeRestoresTransactionFromSavedLocals() {
        try (var context = Main.executionContext()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var stm = Language.currentState().stm;
                for (boolean transactional : new boolean[]{false, true}) {
                    var savedTransaction = transactional ? stm.begin() : null;
                    var slot = new LocalAccessor[1];
                    var metrics = new Metrics(true);
                    var marker = new Object();
                    var root = BytecodeRootGen.create(language, BytecodeConfig.DEFAULT, b -> {
                        b.beginRoot();
                        var transaction = b.createLocal("saved transaction", FrameSlotKind.Object);
                        slot[0] = LocalAccessor.constantOf(transaction);
                        b.beginStaticStoreObject(transaction); b.emitCurrentTransaction(); b.endStaticStoreObject();
                        b.beginYield(); b.emitLoadConstant(marker); b.endYield();
                        b.emitEnterRoot(metrics);
                        b.beginReturn(); b.emitCurrentTransaction(); b.endReturn();
                        b.endRoot();
                    }).getNode(0);
                    root.configureAsync(true); root.configureStackDriver(metrics);
                    root.configureStackTransaction(slot[0]);
                    var saved = assertInstanceOf(ContinuationResult.class, Calls.target(root.getCallTarget(), new Object[]{0L}));
                    assertSame(marker, saved.getResult());
                    var target = (OptimizedCallTarget) saved.getContinuationRootNode().getCallTarget();
                    target.compile(true); target.waitForCompilation(); assertTrue(target.isValidLastTier());
                    ((OptimizedTruffleRuntime) Truffle.getRuntime()).bypassedInstalledCode(target);
                    var ambient = stm.begin();
                    try {
                        assertSame(savedTransaction, saved.continueWith(Unit.INSTANCE));
                        // Later cold operations may deoptimize; the installed entry must
                        // still read its transaction from the saved frame, not operand locals.
                        assertSame(root, saved.getContinuationRootNode().getSourceRootNode());
                        assertSame(ambient, stm.currentTransaction());
                        assertEquals(0, AstStacks.astStackScope(root).getDepth());
                        assertFalse(AstStacks.astStackScope(root).getDriving());
                    } finally {
                        stm.retire(ambient);
                        if (savedTransaction != null) stm.retire(savedTransaction);
                        stm.restore(null);
                    }
                }
            } finally { context.leave(); }
        }
    }

    private static final class Driver extends RootNode {
        @Child private Force force = new Force(new Metrics(false));
        Driver() { super(null); }
        @Override public Object execute(VirtualFrame frame) { return force.execute(frame, frame.getArguments()[0]); }
        Object force(Thunk thunk) { return Calls.target(getCallTarget(), new Object[]{thunk}); }
    }
    private static final class Saved implements SavedGuestContinuation {
        private final Object savedRoot, savedYield;
        private final Function<Object, Object> resume;
        int resumes;
        Saved(Object savedRoot, Object savedYield, Function<Object, Object> resume) {
            this.savedRoot = savedRoot; this.savedYield = savedYield; this.resume = resume;
        }
        @Override public Object getSourceRoot() { return savedRoot; }
        @Override public Object getYielded() { return savedYield; }
        @Override public Object getIdentity() { return this; }
        @Override public Object continueWith(Object input) { resumes++; return resume.apply(input); }
    }
    private Thunk parked(RootCallTarget target, Saved saved) {
        var thunk = new Thunk(target, null);
        thunk.setTarget(null); thunk.setEnvironment(null); thunk.setValue(saved); thunk.setState(5);
        return thunk;
    }
    private static final class CompletionProbe extends Expr {
        int compiled;
        CompletionProbe() {
            setRepresentation(new CoreRepresentation(CoreKind.OBJECT, true, true, List.of("BoxedRep (Just Unlifted)"), null, null, null, null, null));
        }
        @Override public Object execute(VirtualFrame frame) {
            if (CompilerDirectives.inCompiledCode()) compiled++;
            return complete(frame.getArguments()[1]);
        }
        Object complete(Object value) { return complete(value, null, null); }
        Object complete(Object value, RootCallTarget target) { return complete(value, target, null); }
        Object complete(Object value, RootCallTarget target, TupleShape shape) { return AstControl.complete(this, value, target, shape); }
    }
    private FunctionRoot root(Language language, CompletionProbe probe, boolean enabled) {
        return new FunctionRoot(language, new FrameLayout().build(), "completion control", null,
            new int[0], new int[0], new int[0], probe, new Metrics(false), new CoreRepresentation[0], probe.getRepresentation(),
            probe.getCoreSourceLocation(), new boolean[0], null, null, new int[0], null, enabled, new int[0][], false,
            FunctionRootRole.FUNCTION, false);
    }
    @Test void ordinaryCompletionKeepsItsFirstInstalledIdentityAndColdProofChecks() throws Exception {
        try (var context = Main.executionContext()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var probe = new CompletionProbe(); var root = root(language, probe, true); var target = root.getCallTarget();
                target.getClass().getMethod("compile", boolean.class).invoke(target, true);
                target.getClass().getMethod("waitForCompilation").invoke(target);
                assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target));
                var marker = new Object();
                assertSame(marker, Calls.target(target, new Object[]{0L, marker}));
                assertEquals(1, probe.compiled, "The first call must enter the original installed guest code");
                assertSame(target, root.getCallTarget());
                assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target));
                for (var ordinary : Arrays.asList(null, thc.runtime.Unit.INSTANCE, 17L, 23, marker, new Object[]{marker}))
                    assertSame(ordinary, probe.complete(ordinary));
                var ambient = SynchronousMasking.current(probe);
                SynchronousMasking.set(probe, MaskingState.MASKED_INTERRUPTIBLE);
                try {
                    var saved = new Saved(root, thc.runtime.Unit.INSTANCE, input -> fail("Completion must not resume the child"));
                    var cut = assertThrows(AstCapture.class, () -> probe.complete(saved, target));
                    var parked = (CallSegmentSuspended) cut.getYielded();
                    assertSame(saved, parked.getSegment().getValue());
                    assertEquals(5, parked.getSegment().getState());
                    assertEquals(MaskingState.MASKED_INTERRUPTIBLE, parked.getSegment().getCallerMask());
                    assertEquals(MaskingState.MASKED_INTERRUPTIBLE, cut.getLogicalMask());
                    assertEquals(0, saved.resumes);
                    assertEquals(MaskingState.MASKED_INTERRUPTIBLE, SynchronousMasking.current(probe));
                    var other = root(language, new CompletionProbe(), true).getCallTarget();
                    assertThrows(RuntimeFault.class, () -> probe.complete(saved, other));
                    assertThrows(RuntimeFault.class, () -> probe.complete(new Saved(new Object(), thc.runtime.Unit.INSTANCE, input -> null)));
                    assertThrows(RuntimeFault.class, () -> probe.complete(new Saved(root, new Object(), input -> null)));
                    var scalar = new CoreRepresentation(CoreKind.LONG, true, true, List.of("IntRep"), null, null, null, null, null);
                    var shape = new TupleShape(new CoreRepresentation(CoreKind.UNKNOWN, true, true, List.of("IntRep"), List.of(scalar), null, null, null, null), language);
                    assertThrows(RuntimeFault.class, () -> probe.complete(saved, target, shape));
                    assertEquals(0, saved.resumes);
                    var disabled = new CompletionProbe(); root(language, disabled, false).getCallTarget();
                    assertSame(saved, disabled.complete(saved, other, shape), "A nonresumable root must preserve its existing completion policy");
                } finally { SynchronousMasking.set(probe, ambient); }
            } finally { context.leave(); }
        }
    }
    @Test void coldRecordResumesSharedChildBeforeParentAndPublishesOnce() {
        try (var context = Main.executionContext()) {
            context.initialize("thc"); context.enter();
            try {
                var original = new RootNode(null) {
                    @Override public Object execute(VirtualFrame frame) { return fail("Original body replayed"); }
                }.getCallTarget();
                var childSaved = new Saved(original.getRootNode(), thc.runtime.Unit.INSTANCE, input -> 42L);
                var child = parked(original, childSaved);
                var parentSaved = new Saved(original.getRootNode(), new ThunkSuspended(child), input -> {
                    var completed = (ChildResume) input;
                    assertNull(completed.getFailure()); assertEquals(42L, completed.getValue()); return 43L;
                });
                var parent = parked(original, parentSaved); var driver = new Driver();
                assertEquals(43L, driver.force(parent));
                assertEquals(2, parent.getState()); assertEquals(2, child.getState());
                assertEquals(1, parentSaved.resumes); assertEquals(1, childSaved.resumes);
                assertEquals(43L, driver.force(parent)); assertEquals(1, parentSaved.resumes);
            } finally { context.leave(); }
        }
    }
}
