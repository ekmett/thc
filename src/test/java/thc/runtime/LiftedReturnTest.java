// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeUtil;
import com.oracle.truffle.runtime.OptimizedCallTarget;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import thc.Language;
import static org.junit.jupiter.api.Assertions.*;
import static thc.runtime.ScalarValueTestSupport.*;

/** Ordinary lifted returns stay lazy; a surrounding Core case owns demand. */
class LiftedReturnTest {
    private static final Map<String, Object> INT = map("kind", "data",
        "primReps", list("BoxedRep (Just Lifted)"), "evaluated", false);
    private static final Map<String, Object> CLOSURE = map("kind", "closure",
        "primReps", list("BoxedRep (Just Lifted)"), "evaluated", true);

    private static Map<String, Object> parameter(String id) {
        return map("id", id, "name", id, "lifted", true, "coercion", false, "rep", INT);
    }
    private static List<Object> variable(String id) { return list("var", id, map("rep", INT)); }
    private static Map<String, Object> binding(String id, List<Map<String, Object>> parameters, List<Object> body) {
        return map("id", id, "name", id, "arity", parameters.size(), "lifted", true, "rep", CLOSURE,
            "expr", list("lam", parameters, body, map("rep", CLOSURE, "resultRep", INT)));
    }
    private static List<Object> identityCall() {
        return list("app", list("var", "identity", map("rep", CLOSURE)), list(variable("x")),
            list(true), false, false, map("rep", INT));
    }
    private static List<Object> caseOfIdentity(List<Object> result) {
        return list("case", identityCall(), "whole", list(list("default", null, list(), result)),
            map("rep", INT, "binder", parameter("whole")));
    }
    private static Map<String, Object> module() {
        return map("bindings", list(
            binding("identity", list(parameter("x")), variable("x")),
            binding("demand", list(parameter("x")), caseOfIdentity(variable("whole"))),
            binding("select", list(parameter("x"), parameter("y")), caseOfIdentity(variable("y")))));
    }
    private static Context context() {
        return Context.newBuilder("thc").allowCreateThread(true).allowExperimentalOptions(true)
            .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
            .option("engine.CompilationFailureAction", "Throw")
            .option("engine.SingleTierCompilationThreshold", "10000000")
            .option("compiler.MaximumGraalGraphSize", "200000")
            .option("compiler.DiagnoseFailure", "false").build();
    }
    private static Language language() { return TruffleLanguage.LanguageReference.create(Language.class).get(null); }
    private static ExecutableProgram program(String backend) {
        return backend.equals("ast") ? new Program(language(), module()) : new BytecodeProgram(language(), module());
    }
    private static DataValue answer() {
        return new DataLayout(language(), DataValues.BOXED_INT_CONSTRUCTOR_ID, "I#", new String[]{"IntRep"}).createLong(42L);
    }
    private static Thunk delayed(DataValue value, AtomicInteger evaluations) {
        return new Thunk(new GuestRoot(language(), new FrameLayout().build()) {
            @Override public long bloom(VirtualFrame frame) { return 0L; }
            @Override public Object execute(VirtualFrame frame) { evaluations.incrementAndGet(); return value; }
        }.getCallTarget(), null);
    }

    @ParameterizedTest @ValueSource(strings = {"ast", "bytecode"})
    void ordinaryIdentityReturnsOriginalUnevaluatedThunk(String backend) {
        try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var program = program(backend);
                var evaluations = new AtomicInteger();
                var value = answer(); var thunk = delayed(value, evaluations);
                var identity = program.entryTarget("identity");
                assertSame(thunk, Calls.target(identity, new Object[]{0L, thunk}));
                assertSame(thunk, Calls.target(identity, new Object[]{0L, thunk}));
                assertEquals(0, thunk.getState()); assertEquals(0, evaluations.get());
                assertSame(value, Calls.target(identity, new Object[]{0L, value}));
            } finally { context.leave(); }
        }
    }

    @ParameterizedTest @ValueSource(strings = {"ast", "bytecode"})
    void caseDemandsScrutineeOnceAndLeavesDefaultResultLazy(String backend) {
        try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var program = program(backend);
                var value = answer();
                var evaluations = new AtomicInteger(); var thunk = delayed(value, evaluations);
                var demand = program.entryTarget("demand");
                assertSame(value, Calls.target(demand, new Object[]{0L, thunk}));
                assertSame(value, Calls.target(demand, new Object[]{0L, thunk}));
                assertEquals(1, evaluations.get()); assertEquals(2, thunk.getState());

                var scrutineeEvaluations = new AtomicInteger(); var resultEvaluations = new AtomicInteger();
                var scrutinee = delayed(value, scrutineeEvaluations); var result = delayed(value, resultEvaluations);
                var select = program.entryTarget("select");
                assertSame(result, Calls.target(select, new Object[]{0L, scrutinee, result}));
                assertSame(result, Calls.target(select, new Object[]{0L, scrutinee, result}));
                assertEquals(1, scrutineeEvaluations.get()); assertEquals(2, scrutinee.getState());
                assertEquals(0, resultEvaluations.get()); assertEquals(0, result.getState());
            } finally { context.leave(); }
        }
    }

    @Test
    void firstCompiledIdentityCallReturnsThunkWithoutDemand() {
        try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var program = program("ast");
                // Construct both carrier and thunk before compilation so class loading cannot invalidate installed code.
                var value = answer(); var evaluations = new AtomicInteger(); var thunk = delayed(value, evaluations);
                var identity = (OptimizedCallTarget) program.entryTarget("identity");
                assertFalse(identity.wasExecuted());
                identity.compile(true); identity.waitForCompilation();
                assertTrue(identity.isValidLastTier()); assertNotEquals(0L, identity.getCodeAddress());
                assertFalse(identity.wasExecuted());
                long address = identity.getCodeAddress(); int installations = identity.getSuccessfulCompilationCount();
                long entries = ((Number) program.diagnostics().get("compiledEntries")).longValue();
                var stack = AstStacks.astStackScope(identity.getRootNode());
                long spills = stack.getSpills();
                stack.setDepth(AstStackScope.MAX_DEPTH - 1); stack.setDriving(true);
                try {
                    // Returning a local cannot deepen the guest stack or require a spill.
                    assertSame(thunk, Calls.target(identity, new Object[]{0L, thunk}));
                    assertEquals(AstStackScope.MAX_DEPTH - 1, stack.getDepth());
                    assertTrue(stack.getDriving()); assertEquals(spills, stack.getSpills());
                } finally { stack.setDepth(0); stack.setDriving(false); }
                assertEquals(entries + 1, program.diagnostics().get("compiledEntries"));
                assertEquals(0, evaluations.get()); assertEquals(0, thunk.getState());
                assertSame(value, Calls.target(identity, new Object[]{0L, value}));
                assertTrue(identity.isValidLastTier()); assertEquals(address, identity.getCodeAddress());
                assertEquals(installations, identity.getSuccessfulCompilationCount());

                // Replacing the read with demand must invalidate the omitted stack protocol.
                var read = NodeUtil.findFirstNodeInstance(identity.getRootNode(), LocalRead.class);
                assertNotNull(read);
                read.replace(new Evaluate(NodeUtil.cloneNode(read), new Metrics(false)));
                assertFalse(identity.isValid());
                stack.setDepth(AstStackScope.MAX_DEPTH - 1); stack.setDriving(true);
                final AstContinuation saved;
                try {
                    saved = assertInstanceOf(AstContinuation.class, Calls.target(identity, new Object[]{0L, thunk}));
                    assertTrue(saved.stackSpill()); assertEquals(0, evaluations.get());
                    assertEquals(AstStackScope.MAX_DEPTH - 1, stack.getDepth());
                } finally { stack.setDepth(0); stack.setDriving(false); }
                assertSame(value, saved.continueWith(Unit.INSTANCE));
                assertEquals(1, evaluations.get());
            } finally { context.leave(); }
        }
    }

    @Test
    void localReturnWithStrictIngressSpillsBeforeDemand() {
        try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var layout = new FrameLayout(); int slot = layout.bind("x");
                var body = new LocalRead(slot, false);
                var root = new FunctionRoot(language(), layout.build(), "strict local return", null,
                    new int[0], new int[]{slot}, new int[]{0}, body, new Metrics(false),
                    new CoreRepresentation[]{CoreRepresentation.UNKNOWN}, CoreRepresentation.UNKNOWN, null,
                    new boolean[]{true}, null, null, new int[0], null, false, new int[0][], false,
                    FunctionRootRole.FUNCTION, true);
                var value = answer(); var evaluations = new AtomicInteger(); var thunk = delayed(value, evaluations);
                var stack = AstStacks.astStackScope(root);
                stack.setDepth(AstStackScope.MAX_DEPTH - 1); stack.setDriving(true);
                final AstContinuation saved;
                try {
                    saved = assertInstanceOf(AstContinuation.class, Calls.target(root.getCallTarget(), new Object[]{0L, thunk}));
                    assertTrue(saved.stackSpill()); assertEquals(0, evaluations.get());
                    assertEquals(AstStackScope.MAX_DEPTH - 1, stack.getDepth());
                } finally { stack.setDepth(0); stack.setDriving(false); }
                assertSame(value, saved.continueWith(Unit.INSTANCE)); assertEquals(1, evaluations.get());
            } finally { context.leave(); }
        }
    }

    @Test
    void callAncestryReadRetainsIngressWithoutGuestDescent() {
        try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var root = new FunctionRoot(language(), new FrameLayout().build(), "call ancestry", null,
                    new int[0], new int[0], new int[0], new LocalRead(FrameLayout.BLOOM_FILTER, false), new Metrics(false));
                long inherited = 0x1234abcdL;
                assertEquals(root.entryBloom(inherited), Calls.target(root.getCallTarget(), new Object[]{inherited}));
            } finally { context.leave(); }
        }
    }

    @Test
    void localReturnStillCapturesEntryPollWithoutDemand() {
        try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var program = new Program(language(), module(), true);
                var value = answer(); var evaluations = new AtomicInteger(); var thunk = delayed(value, evaluations);
                var target = program.entryTarget("identity");
                var state = Language.currentState(); state.getThreads().enterCurrent();
                try {
                    var request = state.getThreads().send(state.getThreads().currentIdentity(), "entry poll");
                    long inherited = 0x1234abcdL;
                    var saved = assertInstanceOf(AstContinuation.class, Calls.target(target, new Object[]{inherited, thunk}));
                    assertSame(request, saved.asyncRequest()); assertFalse(saved.stackSpill());
                    assertEquals(0, evaluations.get()); request.acknowledge();
                    var read = NodeUtil.findFirstNodeInstance(target.getRootNode(), LocalRead.class);
                    assertNotNull(read);
                    var original = NodeUtil.cloneNode(read);
                    long expectedBloom = ((FunctionRoot) target.getRootNode()).entryBloom(inherited);
                    read.replace(new Expr() {
                        @Child private Expr value = original;
                        @Override public Object execute(VirtualFrame frame) {
                            assertEquals(expectedBloom, frame.getLong(FrameLayout.BLOOM_FILTER));
                            return value.execute(frame);
                        }
                    });
                    assertSame(thunk, saved.continueWith(Unit.INSTANCE)); assertEquals(0, evaluations.get());
                    var stack = AstStacks.astStackScope(target.getRootNode());
                    assertEquals(0, stack.getDepth()); assertFalse(stack.getDriving());
                } finally { state.getThreads().leaveCurrent(); }
            } finally { context.leave(); }
        }
    }
}
