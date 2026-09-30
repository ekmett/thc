// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.RootCallTarget;
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
