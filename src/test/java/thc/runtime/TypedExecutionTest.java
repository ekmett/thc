// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleLanguage;
import thc.Language;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.nodes.UnexpectedResultException;
import thc.runtime.Unit;
import org.junit.jupiter.api.Test;
import thc.Main;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class TypedExecutionTest {
    private static final class Effects {
        int childExecutions, thunkEvaluations, compiledEntries;
    }
    private static final class SuppliedValue extends Expr {
        private final Effects effects;
        SuppliedValue(Effects effects) { this.effects = effects; }
        @Override public Object execute(VirtualFrame frame) {
            effects.childExecutions++;
            return frame.getArguments()[0];
        }
    }
    private static final class EvaluationRoot extends RootNode {
        final Effects effects;
        @Child private Evaluate value;
        EvaluationRoot(Effects effects, Metrics metrics) {
            super(null); this.effects = effects; value = new Evaluate(new SuppliedValue(effects), metrics);
        }
        @Override public Object execute(VirtualFrame frame) {
            if (CompilerDirectives.inCompiledCode()) effects.compiledEntries++;
            try { return value.executeLong(frame); }
            catch (UnexpectedResultException failure) { return TypedExecutionTest.<RuntimeException, Object>rethrow(failure); }
        }
    }
    @SuppressWarnings("unchecked")
    private static <E extends Throwable, T> T rethrow(Throwable failure) throws E { throw (E) failure; }
    private static void compile(RootCallTarget target) throws Exception {
        var type = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget");
        assertTrue(type.isInstance(target));
        type.getMethod("compile", boolean.class).invoke(target, true);
        type.getMethod("waitForCompilation").invoke(target);
        assertTrue(validLastTier(target), "Requested guest code must be installed");
    }
    private static boolean validLastTier(RootCallTarget target) throws Exception {
        return Boolean.TRUE.equals(Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget")
            .getMethod("isValidLastTier").invoke(target));
    }
    private static void check(Effects effects, RootCallTarget target, Object value, long expected) {
        int before = effects.childExecutions;
        assertEquals(expected, Calls.target(target, new Object[]{value}));
        assertEquals(before + 1, effects.childExecutions,
            "Typed fallback must consume the returned value rather than executing the child again");
    }
    @Test void typedEvaluationRunsItsChildOnceWhenACompiledLongPathReceivesAThunk() throws Exception {
        try (var context = Main.executionContext()) {
            context.initialize("thc"); context.enter();
            try {
                var effects = new Effects();
                var metrics = new Metrics(true);
                var root = new EvaluationRoot(effects, metrics);
                var target = root.getCallTarget();
                long answer = 3_000_000_017L;
                var thunkTarget = new RootNode(null) {
                    @Override public Object execute(VirtualFrame frame) { effects.thunkEvaluations++; return answer; }
                    @Override public String getName() { return "typed evaluation test thunk"; }
                }.getCallTarget();
                for (int i = 0; i < 40; i++) check(effects, target, Long.MAX_VALUE, Long.MAX_VALUE);
                compile(target);
                int compiledBefore = effects.compiledEntries;
                check(effects, target, Long.MIN_VALUE, Long.MIN_VALUE);
                assertTrue(effects.compiledEntries > compiledBefore);
                // The first unexpected representation arrives only after installing the primitive path.
                var firstThunk = new Thunk(thunkTarget, null);
                check(effects, target, firstThunk, answer);
                assertEquals(1, effects.thunkEvaluations);
                assertEquals(1L, metrics.getThunkEvaluations());
                assertEquals(2, firstThunk.getState());
                check(effects, target, firstThunk, answer);
                assertEquals(1, effects.thunkEvaluations, "Forcing the same thunk again observes its update");
                assertEquals(1L, metrics.getThunkHits());
                // Warm both representations before recompiling. Fresh thunks must not throw a
                // fresh UnexpectedResultException on every entry into the now-general path.
                for (int i = 0; i < 40; i++) {
                    check(effects, target, new Thunk(thunkTarget, null), answer);
                    check(effects, target, Long.MIN_VALUE, Long.MIN_VALUE);
                }
                compile(target);
                int compiledStableBefore = effects.compiledEntries;
                for (int i = 0; i < 8; i++) {
                    check(effects, target, new Thunk(thunkTarget, null), answer);
                    assertTrue(validLastTier(target), "A stable thunk fallback must retain installed code");
                    check(effects, target, Long.MAX_VALUE, Long.MAX_VALUE);
                    assertTrue(validLastTier(target), "The generalized path must still accept primitive values");
                }
                assertEquals(compiledStableBefore + 16, effects.compiledEntries,
                    "Every call after recompilation must enter installed code");
            } finally { context.leave(); }
        }
    }
    @Test void genericEvaluatedDemandPreservesSingleExecutionAndCutOwnership() {
        try (var context = Main.executionContext()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                class Operand extends Expr {
                    Object answer = 17L; AstCapture cut; int executions;
                    Operand(boolean evaluated) { setRepresentation(new CoreRepresentation(CoreKind.LONG,
                        evaluated, true, List.of("IntRep"), null, null, null, null, null)); }
                    @Override public Object execute(VirtualFrame frame) {
                        executions++; if (cut != null) throw cut; return answer;
                    }
                }
                int[] demands = {0};
                var thunk = new Thunk(new RootNode(null) {
                    @Override public Object execute(VirtualFrame frame) { demands[0]++; return 23L; }
                }.getCallTarget(), null);
                for (boolean captures : new boolean[]{false, true}) {
                    var operand = new Operand(true); var evaluation = new Evaluate(operand, new Metrics(false));
                    var root = evaluationOwner(language, evaluation, captures);
                    var frame = Truffle.getRuntime().createVirtualFrame(new Object[]{0L}, root.getFrameDescriptor());
                    assertEquals(17L, evaluation.execute(frame)); assertEquals(1, operand.executions);
                    operand.answer = thunk;
                    var failure = assertThrows(RuntimeFault.class, () -> evaluation.execute(frame));
                    assertEquals("Expected primitive Long", failure.getMessage()); assertEquals(2, operand.executions);
                    assertEquals(0, demands[0], "Evaluated carrier failures must not demand an unexpected thunk");
                    operand.cut = new AstCapture(AstStackSpill.INSTANCE, MaskingState.UNMASKED);
                    var cut = assertThrows(AstCapture.class, () -> evaluation.execute(frame));
                    assertSame(operand.cut, cut); assertEquals(3, operand.executions);
                    var saved = cut.freeze(root, frame.materialize());
                    assertSame(thunk, saved.continueWith(thunk), "Evaluated demand must not append a forcing recipe");
                    assertEquals(0, demands[0]); assertEquals(3, operand.executions);
                    assertThrows(RuntimeFault.class, () -> saved.continueWith(thunk));
                }
                // Scalar representation alone does not establish WHNF. A suspended
                // unevaluated operand still owns a demand step after its child returns.
                var operand = new Operand(false); var evaluation = new Evaluate(operand, new Metrics(false));
                var root = evaluationOwner(language, evaluation, true);
                var frame = Truffle.getRuntime().createVirtualFrame(new Object[]{0L}, root.getFrameDescriptor());
                operand.cut = new AstCapture(AstStackSpill.INSTANCE, MaskingState.UNMASKED);
                var cut = assertThrows(AstCapture.class, () -> evaluation.execute(frame));
                assertSame(operand.cut, cut); assertEquals(1, operand.executions);
                var saved = cut.freeze(root, frame.materialize());
                assertEquals(23L, saved.continueWith(thunk)); assertEquals(1, demands[0]);
                assertEquals(1, operand.executions, "Resume consumes the child result without replaying it");
                assertThrows(RuntimeFault.class, () -> saved.continueWith(thunk));
            } finally { context.leave(); }
        }
    }
    @Test void evaluatedLongDemandPreservesTypedFailureAndCutOwnership() throws Exception {
        try (var context = Main.executionContext()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                class Operand extends Expr {
                    Object answer = 17L; AstCapture cut; UnexpectedResultException unexpected;
                    int genericExecutions, longExecutions, doubleExecutions;
                    Operand(CoreKind kind) { setRepresentation(new CoreRepresentation(kind,
                        true, true, List.of(kind == CoreKind.LONG ? "IntRep" : "DoubleRep"), null, null, null, null, null)); }
                    @Override public Object execute(VirtualFrame frame) { genericExecutions++; return answer; }
                    @Override public long executeLong(VirtualFrame frame) throws UnexpectedResultException {
                        longExecutions++; if (cut != null) throw cut;
                        if (unexpected != null) throw unexpected;
                        return RuntimeTypesGen.expectLong(answer);
                    }
                    @Override public double executeDouble(VirtualFrame frame) {
                        doubleExecutions++; return 13.5;
                    }
                }
                int[] demands = {0};
                var thunk = new Thunk(new RootNode(null) {
                    @Override public Object execute(VirtualFrame frame) { demands[0]++; return 23L; }
                }.getCallTarget(), null);
                for (boolean captures : new boolean[]{false, true}) {
                    var operand = new Operand(CoreKind.LONG);
                    var evaluation = new Evaluate(operand, new Metrics(false));
                    var root = evaluationOwner(language, evaluation, captures);
                    var frame = Truffle.getRuntime().createVirtualFrame(new Object[]{0L}, root.getFrameDescriptor());
                    assertEquals(17L, evaluation.executeLong(frame)); assertEquals(1, operand.longExecutions);
                    operand.unexpected = new UnexpectedResultException(thunk);
                    if (captures) {
                        var failure = assertThrows(RuntimeFault.class, () -> evaluation.executeLong(frame));
                        assertEquals("Expected primitive Long", failure.getMessage());
                    } else {
                        var failure = assertThrows(UnexpectedResultException.class, () -> evaluation.executeLong(frame));
                        assertSame(operand.unexpected, failure); assertSame(thunk, failure.getResult());
                    }
                    assertEquals(2, operand.longExecutions); assertEquals(0, demands[0]);
                    operand.cut = new AstCapture(AstStackSpill.INSTANCE, MaskingState.UNMASKED);
                    var cut = assertThrows(AstCapture.class, () -> evaluation.executeLong(frame));
                    assertSame(operand.cut, cut); assertEquals(3, operand.longExecutions);
                    var saved = cut.freeze(root, frame.materialize());
                    assertSame(thunk, saved.continueWith(thunk), "Evaluated typed demand must not append a forcing recipe");
                    assertEquals(0, demands[0]); assertEquals(3, operand.longExecutions);
                    assertEquals(0, operand.genericExecutions); assertEquals(0, operand.doubleExecutions);
                    assertThrows(RuntimeFault.class, () -> saved.continueWith(thunk));

                    // A nonmatching evaluated proof retains the declared primitive
                    // route in capture mode and the requested typed route otherwise.
                    var other = new Operand(CoreKind.DOUBLE);
                    other.unexpected = new UnexpectedResultException(thunk);
                    var mismatch = new Evaluate(other, new Metrics(false));
                    var otherRoot = evaluationOwner(language, mismatch, captures);
                    var otherFrame = Truffle.getRuntime().createVirtualFrame(new Object[]{0L}, otherRoot.getFrameDescriptor());
                    var failure = assertThrows(UnexpectedResultException.class, () -> mismatch.executeLong(otherFrame));
                    if (captures) {
                        assertEquals(13.5, failure.getResult());
                        assertEquals(1, other.doubleExecutions); assertEquals(0, other.longExecutions);
                    } else {
                        assertSame(other.unexpected, failure); assertSame(thunk, failure.getResult());
                        assertEquals(0, other.doubleExecutions); assertEquals(1, other.longExecutions);
                    }
                    assertEquals(0, other.genericExecutions); assertEquals(0, demands[0]);
                }
            } finally { context.leave(); }
        }
    }
    private static FunctionRoot evaluationOwner(Language language, Evaluate evaluation, boolean captures) {
        var root = new FunctionRoot(language, new FrameLayout().build(), "generic evaluated demand", null,
            new int[0], new int[0], new int[0], evaluation, new Metrics(false), new CoreRepresentation[0],
            evaluation.getRepresentation(), null, new boolean[0], null, null, new int[0], null, false,
            new int[0][], false, FunctionRootRole.FUNCTION, captures);
        root.getCallTarget();
        return root;
    }

    @Test void runtimeTypesPreserveUnexpectedValuesAndDoNotCoerceDistinctPrimitiveKinds() throws Exception {
        assertEquals(Long.MIN_VALUE, RuntimeTypesGen.expectLong(Long.MIN_VALUE));
        assertEquals(Long.MAX_VALUE, RuntimeTypesGen.expectLong(Long.MAX_VALUE));
        assertTrue(RuntimeTypesGen.expectBoolean(true));
        assertSame(Unit.INSTANCE, RuntimeTypesGen.expectUnit(Unit.INSTANCE));
        var marker = new Object();
        for (var unexpected : List.of(marker, true, 1)) {
            var failure = assertThrows(UnexpectedResultException.class, () -> RuntimeTypesGen.expectLong(unexpected));
            assertSame(unexpected, failure.getResult(), "Forwarders need the original value without re-execution");
        }
        var unitFailure = assertThrows(UnexpectedResultException.class, () -> RuntimeTypesGen.expectUnit(marker));
        assertSame(marker, unitFailure.getResult());
    }
}
