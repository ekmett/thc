// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;
import java.util.ArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class InputDispatchThreadTest {
    private static class AddRoot extends GuestRoot {
        private final long amount;
        AddRoot(long amount) {
            super(null, FrameDescriptor.newBuilder().build()); this.amount = amount;
            configureEntry(new boolean[]{false}, false);
        }
        @Override public long bloom(VirtualFrame frame) { return 0L; }
        @Override public Object execute(VirtualFrame frame) { return (Long) frame.getArguments()[1] + amount; }
    }
    private static class CallerRoot extends RootNode {
        @Child private InputDispatch dispatch = new InputDispatch(new ScalarArrayInputSource(null), 1, false, new Metrics(false), null, 0);
        CallerRoot() { super(null); }
        @Override public Object execute(VirtualFrame frame) {
            return dispatch.execute(frame, (Closure) frame.getArguments()[0], new Object[]{frame.getArguments()[1]});
        }
    }
    @Test void concurrentColdArmsAndGenericFallbackPreserveAllTargets() throws Exception {
        var host = new CallerRoot().getCallTarget(); var closures = new ArrayList<Closure>();
        for (long i = 0; i < 5; i++) closures.add(new Closure(null, 1, new AddRoot(i * 17L).getCallTarget()));
        var barrier = new CyclicBarrier(5); var workers = Executors.newFixedThreadPool(5);
        try {
            var calls = new ArrayList<Future<Void>>();
            for (int i = 0; i < 5; i++) {
                int worker = i;
                calls.add(workers.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    for (int step = 0; step < 500; step++) {
                        int index = (worker + step) % closures.size(); long input = worker * 1000L + step;
                        assertEquals(input + index * 17L, Calls.target(host, new Object[]{closures.get(index), input}),
                            "worker=" + worker + " step=" + step + " target=" + index);
                    }
                    return null;
                }));
            }
            for (var call : calls) call.get(15, TimeUnit.SECONDS);
        } finally {
            workers.shutdownNow(); assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS), "Call-site workers did not terminate");
        }
    }
    private static final class ColdInputBody extends Expr {
        @Child private InputDispatch dispatch = new InputDispatch(new ScalarArrayInputSource(null), 1, false, new Metrics(false));
        boolean compiledSuffix;
        @Override public Object execute(VirtualFrame frame) {
            Object result = dispatch.execute(frame, (Closure) frame.getObject(FrameLayout.TAIL_FUNCTION),
                (Object[]) frame.getObject(FrameLayout.TAIL_ARGUMENTS));
            compiledSuffix = com.oracle.truffle.api.CompilerDirectives.inCompiledCode();
            return result;
        }
    }
    private static final class ColdTupleBody extends Expr {
        @Child private TupleDispatch dispatch;
        long answer;
        boolean compiledSuffix;
        ColdTupleBody(TupleShape shape) {
            dispatch = new TupleDispatch(new TupleDestination(shape) {
                @Override public void consume(VirtualFrame frame, com.oracle.truffle.api.nodes.Node node, Object result) {
                    HandoffStorage owned = TupleResults.ownedTupleResult(result, shape);
                    answer = shape.getLayout().getLong(owned, 0);
                }
            }, new Metrics(false), 1, false);
        }
        @Override public Object execute(VirtualFrame frame) {
            dispatch.execute(frame, (Closure) frame.getObject(FrameLayout.TAIL_FUNCTION),
                (Object[]) frame.getObject(FrameLayout.TAIL_ARGUMENTS));
            compiledSuffix = com.oracle.truffle.api.CompilerDirectives.inCompiledCode();
            return answer;
        }
    }
    private static final class ColdAggregateBody extends Expr {
        @Child private Expr application;
        private final int[] slots;
        boolean compiledSuffix;
        ColdAggregateBody(thc.Language language, TupleShape shape, FrameLayout layout, boolean typed) {
            slots = new int[]{layout.bind("aggregate answer", com.oracle.truffle.api.frame.FrameSlotKind.Long)};
            Expr function = new Expr() {
                @Override public Object execute(VirtualFrame frame) { return frame.getObject(FrameLayout.TAIL_FUNCTION); }
            }.proven(new CoreRepresentation(CoreKind.CLOSURE, true, false, null, null, null, null, null, null));
            Expr argument = new Expr() {
                @Override public Object execute(VirtualFrame frame) { return ((Object[]) frame.getObject(FrameLayout.TAIL_ARGUMENTS))[0]; }
            };
            if (typed) argument.setRepresentation(new CoreRepresentation(CoreKind.DOUBLE, true, true,
                java.util.List.of("DoubleRep"), null, null, null, null, null));
            application = typed
                ? new AstTypedApplication(function, new Expr[]{argument}, layout, false, new Metrics(false), shape)
                : new TupleApplication(language, shape, function, new Expr[]{argument}, false, new Metrics(false));
            application.prepareTuple(slots, 0);
        }
        @Override public Object execute(VirtualFrame frame) {
            application.executeTuple(frame, slots, 0);
            compiledSuffix = com.oracle.truffle.api.CompilerDirectives.inCompiledCode();
            return frame.getLong(slots[0]);
        }
    }
    private static final class TupleAddRoot extends GuestRoot {
        private final TupleShape shape;
        private final long amount;
        TupleAddRoot(thc.Language language, TupleShape shape, long amount) {
            super(language, new FrameLayout().build()); this.shape = shape; this.amount = amount;
            configureEntry(new boolean[]{false}, false); configureTupleResult(shape);
        }
        @Override public long bloom(VirtualFrame frame) { return 0L; }
        @Override public Object execute(VirtualFrame frame) {
            HandoffStorage result = shape.getLayout().create();
            shape.getLayout().setLong(result, 0, ((Number) frame.getArguments()[1]).longValue() + amount);
            return result;
        }
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void preparedColdDispatchRetainsItsFirstInstallationAcrossTargets(boolean tuple) {
        try (var context = org.graalvm.polyglot.Context.newBuilder("thc").allowExperimentalOptions(true)
                .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
                .option("engine.CompilationFailureAction", "Throw").build()) {
            context.initialize("thc"); context.enter();
            try {
                var language = com.oracle.truffle.api.TruffleLanguage.LanguageReference.create(thc.Language.class).get(null);
                var scalar = new CoreRepresentation(CoreKind.LONG, true, false, java.util.List.of("IntRep"), null, null, null, null, null);
                var aggregate = new CoreRepresentation(CoreKind.UNKNOWN, true, true, java.util.List.of("IntRep"),
                    java.util.List.of(scalar), null, null, null, null);
                var shape = new TupleShape(aggregate, language);
                Expr body = tuple ? new ColdTupleBody(shape) : new ColdInputBody();
                var root = FunctionRoot.application(language, "cold dispatch", body, new Metrics(false), false, false);
                var target = (com.oracle.truffle.runtime.OptimizedCallTarget) root.getCallTarget();
                var first = new Closure(null, 1, tuple ? new TupleAddRoot(language, shape, 17L).getCallTarget() : new AddRoot(17L).getCallTarget());
                var second = new Closure(null, 1, tuple ? new TupleAddRoot(language, shape, 34L).getCallTarget() : new AddRoot(34L).getCallTarget());
                assertFalse(target.wasExecuted()); assertTrue(target.prepareForAOT());
                target.compile(true); target.waitForCompilation();
                assertFalse(target.wasExecuted()); assertTrue(target.isValidLastTier());
                long address = target.getCodeAddress(); int installations = target.getSuccessfulCompilationCount();
                assertNotEquals(0L, address);
                assertEquals(59L, Calls.target(target, new Object[]{0L, first, new Object[]{42L}}));
                assertTrue(tuple ? ((ColdTupleBody) body).compiledSuffix : ((ColdInputBody) body).compiledSuffix,
                    "The first dispatch must return into compiled code: tuple=" + tuple);
                assertTrue(target.isValidLastTier(), "Cold dispatch cannot invalidate the prepared caller");
                assertEquals(address, target.getCodeAddress()); assertEquals(installations, target.getSuccessfulCompilationCount());
                if (tuple) ((ColdTupleBody) body).compiledSuffix = false; else ((ColdInputBody) body).compiledSuffix = false;
                assertEquals(134L, Calls.target(target, new Object[]{0L, second, new Object[]{100L}}));
                assertTrue(tuple ? ((ColdTupleBody) body).compiledSuffix : ((ColdInputBody) body).compiledSuffix);
                assertTrue(target.isValidLastTier()); assertEquals(address, target.getCodeAddress());
                assertEquals(installations, target.getSuccessfulCompilationCount());
            } finally { context.leave(); }
        }
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void preparedAggregateApplicationRetainsItsFirstInstallationAcrossTargets(boolean typed) {
        try (var context = org.graalvm.polyglot.Context.newBuilder("thc").allowExperimentalOptions(true)
                .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
                .option("engine.CompilationFailureAction", "Throw").build()) {
            context.initialize("thc"); context.enter();
            try {
                var language = com.oracle.truffle.api.TruffleLanguage.LanguageReference.create(thc.Language.class).get(null);
                var scalar = new CoreRepresentation(CoreKind.LONG, true, false, java.util.List.of("IntRep"), null, null, null, null, null);
                var aggregate = new CoreRepresentation(CoreKind.UNKNOWN, true, true, java.util.List.of("IntRep"),
                    java.util.List.of(scalar), null, null, null, null);
                var shape = new TupleShape(aggregate, language);
                var layout = new FrameLayout();
                var body = new ColdAggregateBody(language, shape, layout, typed);
                var root = new FunctionRoot(language, layout.build(), "cold aggregate application", null, new int[0],
                    new int[]{FrameLayout.TAIL_FUNCTION, FrameLayout.TAIL_ARGUMENTS}, new int[]{0, 1}, body, new Metrics(false),
                    new CoreRepresentation[]{CoreRepresentation.UNKNOWN, CoreRepresentation.UNKNOWN},
                    CoreRepresentation.UNKNOWN, null, new boolean[2], null, null, new int[0], null,
                    false, new int[0][], false, FunctionRootRole.FUNCTION, false);
                var target = (com.oracle.truffle.runtime.OptimizedCallTarget) root.getCallTarget();
                var first = new Closure(null, 1, new TupleAddRoot(language, shape, 17L).getCallTarget());
                var second = new Closure(null, 1, new TupleAddRoot(language, shape, 34L).getCallTarget());
                assertFalse(target.wasExecuted()); assertTrue(target.prepareForAOT());
                target.compile(true); target.waitForCompilation();
                assertFalse(target.wasExecuted()); assertTrue(target.isValidLastTier());
                long address = target.getCodeAddress(); int installations = target.getSuccessfulCompilationCount();
                assertNotEquals(0L, address);
                assertEquals(59L, Calls.target(target, new Object[]{0L, first, new Object[]{typed ? (Object) 42.0 : (Object) 42L}}));
                assertTrue(body.compiledSuffix, "The first aggregate application must return into compiled code: typed=" + typed);
                assertTrue(target.isValidLastTier()); assertEquals(address, target.getCodeAddress());
                assertEquals(installations, target.getSuccessfulCompilationCount());
                body.compiledSuffix = false;
                assertEquals(134L, Calls.target(target, new Object[]{0L, second, new Object[]{typed ? (Object) 100.0 : (Object) 100L}}));
                assertTrue(body.compiledSuffix); assertTrue(target.isValidLastTier());
                assertEquals(address, target.getCodeAddress()); assertEquals(installations, target.getSuccessfulCompilationCount());
            } finally { context.leave(); }
        }
    }

}
