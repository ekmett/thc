// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.FrameSlotKind;
import com.oracle.truffle.api.frame.MaterializedFrame;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExecutionSignature;
import com.oracle.truffle.api.nodes.NodeUtil;
import com.oracle.truffle.api.nodes.UnexpectedResultException;
import com.oracle.truffle.runtime.OptimizedCallTarget;
import com.oracle.truffle.runtime.OptimizedTruffleRuntime;
import com.oracle.truffle.runtime.OptimizedTruffleRuntimeListener;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import thc.Language;
import static org.junit.jupiter.api.Assertions.*;

/** Transport controls: explicit extraction here is not the real-bailout acceptance test. */
class SameFrameCaseTest {
    private static Context context() {
        return Context.newBuilder("thc").allowExperimentalOptions(true)
                .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
                .option("engine.SingleTierCompilationThreshold", "10000000")
                .option("engine.CompilationFailureAction", "Throw").build();
    }
    private static RootCallTarget[] targets(AstSameFrameArm arm) throws Exception {
        var field = AstSameFrameArm.class.getDeclaredField("targets");
        field.setAccessible(true);
        return (RootCallTarget[]) field.get(arm);
    }
    private static void compile(RootCallTarget raw) throws Exception {
        OptimizedCallTarget target = (OptimizedCallTarget) raw;
        target.prepareForAOT();
        assertTrue(target.compile(true));
        assertTrue(target.isValidLastTier());
        var runtime = Truffle.getRuntime();
        runtime.getClass().getMethod("bypassedInstalledCode", OptimizedCallTarget.class).invoke(runtime, target);
    }
    private static final class State {
        int effects, compiled;
        Object marker = new Object();
        final UnexpectedResultException unexpected = new UnexpectedResultException(marker);
    }
    private static final class ScratchValue extends Expr {
        private final State state;
        ScratchValue(State state) {
            this.state = state;
            setRepresentation(new CoreRepresentation(CoreKind.LONG, true, true, java.util.List.of("IntRep")));
        }
        @Override public long executeLong(VirtualFrame frame) {
            state.effects++;
            if (CompilerDirectives.inCompiledCode()) state.compiled++;
            if ((Boolean) frame.getArguments()[1]) FrameAccess.writeObject(frame, 0, state.marker);
            return (Long) frame.getArguments()[0];
        }
        @Override public Object execute(VirtualFrame frame) { return executeLong(frame); }
    }
    private static final class ScratchRoot extends ContextRoot {
        @Child private LocalBinding binding;
        ScratchRoot(Language language, State state) {
            super(language, FrameDescriptor.newBuilder().build());
            binding = new LocalBinding(0, new ScratchValue(state), true);
        }
        @Override protected ExecutionSignature prepareForAOT() { return ExecutionSignature.GENERIC; }
        @Override public Object execute(VirtualFrame frame) {
            binding.write((MaterializedFrame) frame.getArguments()[0]);
            return 42L;
        }
    }

    @ParameterizedTest @ValueSource(ints = {0, 1, 2, 3, 4})
    void exactLongScratchRetainsDynamicWideningAndRhsOrder(int mode) throws Exception {
        try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var state = new State();
                var root = new ScratchRoot(language, state);
                var target = (OptimizedCallTarget) root.getCallTarget();
                var builder = FrameDescriptor.newBuilder();
                FrameSlotKind kind = switch (mode) {
                    case 1 -> FrameSlotKind.Illegal;
                    case 2 -> FrameSlotKind.Object;
                    case 3 -> FrameSlotKind.Int;
                    default -> FrameSlotKind.Long;
                };
                builder.addSlot(kind, null, null);
                var descriptor = builder.build();
                long value = Long.MIN_VALUE + 5;
                var frame = Truffle.getRuntime().createMaterializedFrame(new Object[]{value, mode == 4}, descriptor);
                compile(target);
                assertEquals(0, state.effects);
                assertEquals(42L, Calls.target(target, new Object[]{frame}));
                assertEquals(1, state.effects, "RHS evaluated exactly once");
                assertEquals(value, FrameAccess.read(frame, 0));
                assertSame(target, root.getCallTarget());
                if (mode == 0) {
                    assertEquals(1, state.compiled, "first exact scalar write is compiled");
                    assertTrue(target.isValidLastTier());
                    // A sibling activation widens the shared descriptor after compilation.
                    var sibling = Truffle.getRuntime().createMaterializedFrame(new Object[0], descriptor);
                    FrameAccess.writeObject(sibling, 0, state.marker);
                    assertEquals(value, frame.getLong(0), "older activation retains its primitive tag");
                    var next = Truffle.getRuntime().createMaterializedFrame(new Object[]{value, false}, descriptor);
                    assertEquals(42L, Calls.target(target, new Object[]{next}));
                    assertTrue(next.isObject(0), "compiled writer observes sibling widening");
                    assertEquals(value, next.getObject(0));
                    assertEquals(2, state.effects);
                } else if (mode == 1) {
                    assertEquals(FrameSlotKind.Long, descriptor.getSlotKind(0));
                    assertTrue(frame.isLong(0));
                } else {
                    assertEquals(FrameSlotKind.Object, descriptor.getSlotKind(0));
                    assertTrue(frame.isObject(0), "widening during RHS must precede the write decision");
                }
            } finally { context.leave(); }
        }
    }
    private static final class Invalidations implements AutoCloseable {
        private final OptimizedTruffleRuntime runtime = (OptimizedTruffleRuntime) Truffle.getRuntime();
        private final StringBuilder events = new StringBuilder();
        private final OptimizedTruffleRuntimeListener listener;
        Invalidations(RootCallTarget... watched) {
            listener = new OptimizedTruffleRuntimeListener() {
                @Override public void onCompilationInvalidated(OptimizedCallTarget target, Object source, CharSequence reason) {
                    for (RootCallTarget expected : watched) if (target == expected)
                        events.append(target.getRootNode().getName()).append(':').append(reason)
                                .append(':').append(source == null ? "null" : source.getClass().getName()).append(';');
                }
            };
            runtime.addListener(listener);
        }
        @Override public String toString() { return events.toString(); }
        @Override public void close() { runtime.removeListener(listener); }
    }
    private static final class Body extends Expr {
        private final State state;
        private final boolean fail;
        Body(State state, boolean fail) { this.state = state; this.fail = fail; }
        private void enter(VirtualFrame frame) {
            if (!(getRootNode() instanceof Caller) || frame.getObject(4) != frame)
                throw new AssertionError("body lost original root or exact frame");
            state.effects++;
            if (CompilerDirectives.inCompiledCode()) state.compiled++;
            frame.setLong(0, frame.getLong(0) + 3);
        }
        @Override public Object execute(VirtualFrame frame) { enter(frame); return state.marker; }
        @Override public int executeInt(VirtualFrame frame) throws UnexpectedResultException {
            enter(frame);
            if (fail && (Boolean) frame.getArguments()[0]) throw state.unexpected;
            return Integer.MIN_VALUE;
        }
        @Override public long executeLong(VirtualFrame frame) { enter(frame); return Long.MIN_VALUE; }
        @Override public float executeFloat(VirtualFrame frame) { enter(frame); return Float.intBitsToFloat(0x80000000); }
        @Override public double executeDouble(VirtualFrame frame) { enter(frame); return Double.longBitsToDouble(0xfff8000000001234L); }
        @Override public Closure executeClosure(VirtualFrame frame) { enter(frame); return (Closure) state.marker; }
        @Override public DataValue executeDataValue(VirtualFrame frame) { enter(frame); return (DataValue) state.marker; }
        @Override public ManagedAddress executeAddress(VirtualFrame frame) { enter(frame); return (ManagedAddress) state.marker; }
        @Override public Object executeTuple(VirtualFrame frame, int[] slots, int offset) {
            enter(frame);
            frame.setInt(slots[offset], Integer.MIN_VALUE);
            frame.setFloat(slots[offset + 1], Float.intBitsToFloat(0x80000000));
            frame.setObject(slots[offset + 2], state.marker);
            return state.marker;
        }
    }
    private static final class Caller extends ContextRoot {
        @Child private AstSameFrameArm arm;
        private final int mode;
        @CompilationFinal(dimensions = 1) private final int[] tupleSlots = {5, 1, 2, 3};
        Caller(Language language, Body body, int mode) {
            super(language, descriptor()); this.mode = mode; arm = new AstSameFrameArm(body);
        }
        private static FrameDescriptor descriptor() {
            var builder = FrameDescriptor.newBuilder();
            for (var kind : new FrameSlotKind[]{FrameSlotKind.Long, FrameSlotKind.Int, FrameSlotKind.Float,
                    FrameSlotKind.Object, FrameSlotKind.Object, FrameSlotKind.Long}) builder.addSlot(kind, null, null);
            return builder.build();
        }
        protected boolean requiresMaterializableFrame() { return true; }
        @Override protected ExecutionSignature prepareForAOT() { return ExecutionSignature.GENERIC; }
        @Override public Object execute(VirtualFrame frame) {
            frame.setLong(0, 7L);
            frame.setLong(5, 99L);
            frame.setObject(4, frame.materialize());
            Object result;
            try {
                result = switch (mode) {
                    case 1 -> arm.executeInt(frame);
                    case 2 -> arm.executeLong(frame);
                    case 3 -> arm.executeFloat(frame);
                    case 4 -> arm.executeDouble(frame);
                    case 5 -> arm.executeClosure(frame);
                    case 6 -> arm.executeDataValue(frame);
                    case 7 -> arm.executeAddress(frame);
                    case 8 -> arm.executeTuple(frame, tupleSlots, 1);
                    default -> arm.execute(frame);
                };
            } catch (UnexpectedResultException failure) { result = failure; }
            if (frame.getLong(0) != 10L || frame.getLong(5) != 99L) throw new AssertionError("lost write or wrong tuple offset");
            if (mode == 8 && (frame.getInt(1) != Integer.MIN_VALUE ||
                    Float.floatToRawIntBits(frame.getFloat(2)) != 0x80000000 || frame.getObject(3) != result))
                throw new AssertionError("wrong tuple destination");
            return result;
        }
    }

    @ParameterizedTest @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6, 7, 8})
    void coldTypedBodyUsesOriginalFrameAndRetainsBothInstalledTargets(int mode) throws Exception {
        try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var state = new State();
                if (mode == 5) state.marker = new Closure(null, 0, com.oracle.truffle.api.nodes.RootNode.createConstantNode(1L).getCallTarget());
                if (mode == 6) state.marker = new DataLayout(language, "SameFrameData", "SameFrameData", new String[0]).create(new Object[0]);
                if (mode == 7) state.marker = ManagedAddress.nullAddress();
                var root = new Caller(language, new Body(state, false), mode);
                var target = (OptimizedCallTarget) root.getCallTarget();
                assertTrue(AstSameFrameArm.extract(root));
                assertFalse(AstSameFrameArm.extract(root), "finite boundary exhausted");
                var side = (OptimizedCallTarget) targets(root.arm)[mode];
                assertSame(root.compilationOwner(), ((ContextRoot) side.getRootNode()).compilationOwner());
                compile(side); compile(target);
                assertEquals(0, state.effects, "compilation executes no guest work");
                Object result = Calls.target(target, new Object[0]);
                switch (mode) {
                    case 1 -> assertEquals(Integer.MIN_VALUE, result);
                    case 2 -> assertEquals(Long.MIN_VALUE, result);
                    case 3 -> assertEquals(0x80000000, Float.floatToRawIntBits((Float) result));
                    case 4 -> assertEquals(0xfff8000000001234L, Double.doubleToRawLongBits((Double) result));
                    default -> assertSame(state.marker, result);
                }
                assertEquals(1, state.effects); assertEquals(1, state.compiled);
                assertSame(target, root.getCallTarget()); assertTrue(target.isValidLastTier()); assertTrue(side.isValidLastTier());
            } finally { context.leave(); }
        }
    }

    private static final class CaptureState {
        final Object marker = new Object();
        int prefix, resumes, compiled;
        MaterializedFrame frame;
        long bloom;
    }
    private static final class CutBody extends Expr {
        private final CaptureState state;
        CutBody(CaptureState state) { this.state = state; }
        @Override public Object execute(VirtualFrame frame) { return executeLong(frame); }
        @Override public long executeLong(VirtualFrame frame) {
            state.prefix++;
            if (CompilerDirectives.inCompiledCode()) state.compiled++;
            throw capture(frame.materialize());
        }
        @TruffleBoundary private AstCapture capture(MaterializedFrame frame) {
            state.frame = frame;
            state.bloom = frame.getLong(FrameLayout.BLOOM_FILTER);
            assertInstanceOf(FunctionRoot.class, getRootNode());
            return new AstCapture(state.marker, MaskingState.MASKED_INTERRUPTIBLE).append((saved, input) -> {
                assertSame(state.frame, saved);
                assertEquals(state.bloom, saved.getLong(FrameLayout.BLOOM_FILTER));
                assertEquals(MaskingState.MASKED_INTERRUPTIBLE, SynchronousMasking.current(this));
                assertEquals(500L, input);
                state.resumes++;
                return input;
            });
        }
    }

    @Test void extractedArmCaptureResumesOriginalJoinFrameAndSuffixOnce() throws Exception {
        try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var program = new Program(language, LocalJoinGraphBudgetTest.module(4), true);
                RootCallTarget target = program.entryTarget("entry");
                var arms = NodeUtil.findAllNodeInstances(target.getRootNode(), AstSameFrameArm.class).stream()
                        .filter(candidate -> candidate.getParent() instanceof Alternative).toList();
                assertEquals(8, arms.size());
                var state = new CaptureState();
                var arm = arms.getFirst();
                var field = AstSameFrameArm.class.getDeclaredField("body"); field.setAccessible(true);
                Expr old = (Expr) field.get(arm);
                old.replace(new CutBody(state).proven(old.getRepresentation()));
                assertTrue(AstSameFrameArm.extract(arm));
                while (AstSameFrameArm.extract(target.getRootNode())) { }
                RootCallTarget side = targets(arm)[2];
                compile(side);
                MaskingState ambient = SynchronousMasking.current(target.getRootNode());
                SavedGuestContinuation saved = SavedGuestContinuations.savedGuestContinuation(
                        Calls.target(target, new Object[]{0L, 1L, 0L}));
                assertNotNull(saved);
                assertSame(state.marker, saved.getYielded());
                assertSame(target.getRootNode(), saved.getSourceRoot());
                assertEquals(1, state.prefix); assertEquals(1, state.compiled);
                // A first async capture may deopt under the ordinary exception profile.
                // The saved suffix must still preserve identity, effects, frame and mask.
                // First arm's original sum[16..31]=376 is replaced with 500;
                // all seven siblings and the caller's +17 suffix still run once.
                assertEquals(10317L, saved.continueWith(500L));
                assertEquals(1, state.prefix); assertEquals(1, state.resumes);
                assertEquals(2L, ((Number) program.diagnostics().get("localJoinTransfers")).longValue());
                assertEquals(ambient, SynchronousMasking.current(target.getRootNode()));
                assertThrows(RuntimeFault.class, () -> saved.continueWith(500L));
                assertSame(target, program.entryTarget("entry"));

            } finally { context.leave(); }
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void typedFailureIdentityCrossesTheBoundaryWithoutASecondEvaluation(boolean extract) throws Exception {
        try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var state = new State(); var root = new Caller(language, new Body(state, true), 1);
                var target = (OptimizedCallTarget) root.getCallTarget();
                OptimizedCallTarget side = null;
                if (extract) {
                    assertTrue(AstSameFrameArm.extract(root));
                    side = (OptimizedCallTarget) targets(root.arm)[1]; compile(side);
                }
                compile(target);
                try (var invalidations = new Invalidations(target, side)) {
                // A constant unconditional SlowPathException is intentionally not compiled
                // by Truffle. Supply the failing input only after compiling the real prefix.
                assertSame(state.unexpected, Calls.target(target, new Object[]{true}));
                assertEquals(1, state.effects); assertEquals(1, state.compiled);
                // Pinned Truffle's UnexpectedResultException extends SlowPathException:
                // its catch is intentionally never compiled. This is distinct from
                // the strict valid-result controls and saved-capture semantics above.
                assertFalse(target.isValidLastTier(), invalidations.toString());
                if (side != null) assertFalse(side.isValidLastTier(), invalidations.toString());
                }
            } finally { context.leave(); }
        }
    }

    @Test void cloningAndReplacementDoNotShareBodyTargetsOrLoseInvalidation() throws Exception {
        try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var first = new State(); var body = new Body(first, false); var root = new Caller(language, body, 0);
                var original = (OptimizedCallTarget) root.getCallTarget(); assertTrue(AstSameFrameArm.extract(root));
                var side = (OptimizedCallTarget) targets(root.arm)[0]; compile(side); compile(original);
                var clone = NodeUtil.cloneNode(root); var clonedTarget = clone.getCallTarget();
                assertNull(targets(clone.arm)); assertTrue(AstSameFrameArm.extract(clone));
                assertNotSame(side, targets(clone.arm)[0]);
                assertSame(first.marker, Calls.target(clonedTarget, new Object[0]));
                var second = new State(); body.replace(new Body(second, false));
                assertFalse(original.isValid()); assertFalse(side.isValid());
                assertSame(second.marker, Calls.target(original, new Object[0]));
                assertSame(first.marker, Calls.target(clonedTarget, new Object[0]));
                assertEquals(2, first.effects); assertEquals(1, second.effects);
            } finally { context.leave(); }
        }
    }

    @Test void nestedExtractionReplacementAndCloneKeepOriginalOwner() throws Exception {
        try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var first = new State(); var body = new Body(first, false);
                var root = new Caller(language, body, 0);
                AstSameFrameArm inner = root.arm;
                root.arm = new AstSameFrameArm(inner);
                var original = (OptimizedCallTarget) root.getCallTarget();
                assertTrue(AstSameFrameArm.extract(root));
                var outerTarget = (OptimizedCallTarget) targets(root.arm)[0];
                compile(outerTarget); compile(original);
                assertTrue(AstSameFrameArm.extract(inner));
                assertFalse(outerTarget.isValid(), "nested extraction invalidates its enclosing side target");
                assertFalse(original.isValid(), "replacement also reaches the original owner");
                var innerTarget = (OptimizedCallTarget) targets(inner)[0];
                compile(innerTarget); compile(outerTarget); compile(original);
                assertEquals(0, first.effects);
                assertSame(first.marker, Calls.target(original, new Object[0]));
                assertEquals(1, first.effects); assertEquals(1, first.compiled);
                assertTrue(original.isValidLastTier());
                assertTrue(outerTarget.isValidLastTier()); assertTrue(innerTarget.isValidLastTier());
                var clone = NodeUtil.cloneNode(root);
                var clonedArms = NodeUtil.findAllNodeInstances(clone, AstSameFrameArm.class);
                assertEquals(2, clonedArms.size());
                for (var clonedArm : clonedArms) assertNull(targets(clonedArm));
                var second = new State(); body.replace(new Body(second, false));
                assertFalse(original.isValid()); assertFalse(outerTarget.isValid()); assertFalse(innerTarget.isValid());
                assertSame(second.marker, Calls.target(original, new Object[0]));
                assertSame(first.marker, Calls.target(clone.getCallTarget(), new Object[0]));
                assertEquals(2, first.effects); assertEquals(1, second.effects);
            } finally { context.leave(); }
        }
    }
}
