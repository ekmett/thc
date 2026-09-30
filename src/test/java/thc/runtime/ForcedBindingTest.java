// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.*;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.nodes.UnexpectedResultException;
import java.util.*;
import org.junit.jupiter.api.Test;
import thc.Language;
import thc.Main;
import static org.junit.jupiter.api.Assertions.*;
import static thc.runtime.RepresentationTestSupport.*;

class ForcedBindingTest {
    private FrameDescriptor descriptor() { var builder = FrameDescriptor.newBuilder(); builder.addSlot(FrameSlotKind.Object, "binding", null); builder.addSlot(FrameSlotKind.Object, "alias", null); return builder.build(); }
    private static final class BindingDriver extends RootNode {
        @Child private Evaluate first, second, alias;
        Object bindingAfter, aliasAfter;
        int compiledEntries;
        BindingDriver(FrameDescriptor descriptor, Metrics metrics) { super(null, descriptor); first = new Evaluate(new LocalRead(0), metrics); second = new Evaluate(new LocalRead(0), metrics); alias = new Evaluate(new LocalRead(1), metrics); }
        @Override public Object execute(VirtualFrame frame) {
            if (CompilerDirectives.inCompiledCode()) compiledEntries++;
            FrameAccess.write(frame, 0, frame.getArguments()[0]); FrameAccess.write(frame, 1, frame.getArguments()[0]);
            try { long a = first.executeLong(frame), b = second.executeLong(frame), c = alias.executeLong(frame); return a + b + c; }
            catch (UnexpectedResultException failure) { throw rethrow(failure); }
            finally { bindingAfter = FrameAccess.read(frame, 0); aliasAfter = FrameAccess.read(frame, 1); }
        }
    }
    private void compile(RootCallTarget target) throws Exception { Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget").getMethod("compile", boolean.class).invoke(target, true); Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget").getMethod("waitForCompilation").invoke(target); assertTrue(valid(target)); }
    private boolean valid(RootCallTarget target) throws Exception { return Boolean.TRUE.equals(Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget").getMethod("isValidLastTier").invoke(target)); }
    private void entered(CheckedConsumer<Language> action) throws Exception {
        try (var context = Main.executionContext(false)) {
            context.initialize("thc"); context.enter();
            try { action.accept(TruffleLanguage.LanguageReference.create(Language.class).get(null)); } finally { context.leave(); }
        }
    }
    private void checkThunk(Metrics metrics, BindingDriver driver, long answer, Thunk thunk) {
        long entered = metrics.getThunkEvaluations(), hits = metrics.getThunkHits();
        assertEquals(answer * 3, Calls.target(driver.getCallTarget(), new Object[]{thunk}));
        assertEquals(answer, driver.bindingAfter, "The demanded binding must contain its answer"); assertEquals(answer, driver.aliasAfter, "The later alias demand also updates its own link");
        assertEquals(entered + 1, metrics.getThunkEvaluations()); assertEquals(hits + 1, metrics.getThunkHits(), "Only the separate alias still needs the shared thunk update");
        assertEquals(2, thunk.getState()); assertEquals(answer, thunk.getValue());
    }
    @Test void astForcingReplacesOnlyTheDemandedLocalAndRetainsSharedThunkAliases() throws Exception {
        entered(language -> {
            var metrics = new Metrics(true); var driver = new BindingDriver(descriptor(), metrics); long answer = 3_000_000_017L; int[] evaluations = {0};
            var producer = new RootNode(null) { @Override public Object execute(VirtualFrame frame) { evaluations[0]++; return answer; } }.getCallTarget();
            for (int i = 0; i < 40; i++) checkThunk(metrics, driver, answer, new Thunk(producer, null));
            compile(driver.getCallTarget()); int before = driver.compiledEntries;
            for (int i = 0; i < 8; i++) { checkThunk(metrics, driver, answer, new Thunk(producer, null)); assertTrue(valid(driver.getCallTarget()), "Fresh activation writeback must keep its warmed compiled path"); }
            assertEquals(before + 8, driver.compiledEntries); assertEquals(48, evaluations[0]); long hits = metrics.getThunkHits();
            assertEquals(Long.MIN_VALUE * 3, Calls.target(driver.getCallTarget(), new Object[]{Long.MIN_VALUE})); assertEquals(Long.MIN_VALUE, driver.bindingAfter);
            assertEquals(hits, metrics.getThunkHits(), "An already evaluated value needs no thunk update");
        });
    }
    @Test void failedForcingLeavesLocalLinksPointingAtTheMemoizedFailure() throws Exception {
        entered(language -> {
            var metrics = new Metrics(true); var driver = new BindingDriver(descriptor(), metrics); int[] evaluations = {0}; var failure = new RuntimeFault("forced binding test failure");
            var producer = new RootNode(null) { @Override public Object execute(VirtualFrame frame) { evaluations[0]++; throw failure; } }.getCallTarget(); var thunk = new Thunk(producer, null);
            for (int i = 0; i < 2; i++) {
                assertSame(failure, assertThrows(RuntimeFault.class, () -> Calls.target(driver.getCallTarget(), new Object[]{thunk})));
                assertSame(thunk, driver.bindingAfter, "No successful answer exists to replace this link"); assertSame(thunk, driver.aliasAfter); assertEquals(3, thunk.getState());
            }
            assertEquals(1, evaluations[0]); assertEquals(1L, metrics.getThunkEvaluations());
        });
    }
    @Test void recursiveCellIdentitySurvivesStrictRhsForcingBeforeGroupPublication() throws Exception {
        entered(language -> {
            var answerLayout = new DataLayout(language, "Answer", "Answer", new String[0]); var answer = answerLayout.create(new Object[0]);
            var strictLayout = new DataLayout(language, "Strict", "Strict", new String[]{"LiftedRep"}); var producer = new RootNode(null) { @Override public Object execute(VirtualFrame frame) { return answer; } }.getCallTarget();
            var thunk = new Thunk(producer, null); var firstCell = new RecCell(); firstCell.setInitialized(true); firstCell.setValue(thunk); var secondCell = new RecCell(); var metrics = new Metrics(true);
            var root = new RootNode(null, descriptor()) {
                @Child private Evaluate strictOperand = new Evaluate(new LocalRead(0), metrics);
                @Override public Object execute(VirtualFrame frame) {
                    FrameAccess.write(frame, 0, firstCell); FrameAccess.write(frame, 1, secondCell);
                    // Strict RHS captures still own the original recursive cells.
                    secondCell.setValue(strictLayout.create(new Object[]{strictOperand.execute(frame)})); secondCell.setInitialized(true);
                    assertSame(firstCell, FrameAccess.read(frame, 0), "The frame must retain its unpublished cell"); assertSame(answer, firstCell.getValue(), "The mutable cell link can retain the successful answer");
                    for (int slot = 0; slot <= 1; slot++) { var cell = (RecCell) FrameAccess.read(frame, slot); FrameAccess.write(frame, slot, cell.getValue()); }
                    assertSame(answer, FrameAccess.read(frame, 0)); return Objects.requireNonNull(FrameAccess.read(frame, 1));
                }
            };
            var strict = (DataValue) Calls.target(root.getCallTarget(), new Object[0]); assertSame(answer, strictLayout.read(strict, 0)); assertSame(answer, thunk.getValue()); assertEquals(1L, metrics.getThunkEvaluations());
        });
    }
    private List<Object> variable(String id) { return list("var", id); }
    private List<Object> integer(long value) { return list("lit", "int", Long.toString(value)); }
    @SafeVarargs private final List<Object> primitive(String name, List<Object>... operands) { return list("app", list("prim", name), list(operands), Collections.nCopies(operands.length, false)); }
    private List<Object> lambda(String id, List<Object> body) { return list("lam", list(map("id", id, "name", id, "type", "Int#", "lifted", false, "coercion", false)), body); }
    private Map<String, Object> binding(String id, List<Object> expression) { return binding(id, expression, 0); }
    private Map<String, Object> binding(String id, List<Object> expression, int arity) { return map("id", id, "name", id, "type", "Synthetic", "lifted", true, "arity", arity, "expr", expression); }
    private List<Object> demand(List<Object> value, String name, List<Object> body) { return list("case", value, name, list(list("default", null, list(), body))); }
    private List<Object> let(String id, List<Object> value, List<Object> body) { return list("let", false, list(binding(id, value)), body); }
    private Map<String, Object> module(List<Object> body) { return map("schema", 1, "ghc", "9.14.1", "module", "Synthetic.ForcedBinding", "instrument", true, "constructors", list(), "bindings", list(binding("entry", lambda("input", body), 1))); }
    private long count(ExecutableProgram program, String key) { return ((Number) program.diagnostics().get(key)).longValue(); }
    private Object invoke(ExecutableProgram program, long input) { return Calls.target(program.hostEntryTarget(1), new Object[]{program.entryValue("entry"), new Object[]{input}}); }
    private void check(ExecutableProgram program, String backend, long coefficient, long input) {
        long evaluations = count(program, "thunkEvaluations"), hits = count(program, "thunkHits"); assertEquals(input * coefficient, invoke(program, input), backend);
        assertEquals(evaluations + 1, count(program, "thunkEvaluations"), backend + " evaluates its shared thunk once");
        assertEquals(hits + 1, count(program, "thunkHits"), backend + " only enters the thunk through the distinct alias/capture");
    }
    private void bothBackends(List<Object> body, long coefficient) throws Exception {
        entered(language -> {
            for (var backend : list("ast", "bytecode")) {
                ExecutableProgram program = backend.equals("ast") ? new Program(language, module(body)) : new BytecodeProgram(language, module(body));
                for (int i = 0; i < 40; i++) check(program, backend, coefficient, 3_000_000_017L); compile(program.entryTarget("entry")); long compiled = count(program, "compiledEntries");
                for (long input : new long[]{3_000_000_017L, Long.MIN_VALUE, Long.MAX_VALUE, 0L, -3_000_000_017L}) check(program, backend, coefficient, input);
                assertTrue(count(program, "compiledEntries") > compiled); assertEquals(0L, count(program, "blackholes"));
            }
        });
    }
    @Test void repeatedLocalDemandsBypassThunkHeadersButSeparateAliasesKeepSharing() throws Exception {
        var sum = primitive("+#", primitive("+#", variable("first"), variable("second")), primitive("+#", variable("third"), variable("fourth")));
        var reads = demand(variable("shared"), "first", demand(variable("shared"), "second", demand(variable("alias"), "third", demand(variable("alias"), "fourth", sum))));
        var delayed = demand(variable("input"), "delayed", variable("delayed")); bothBackends(let("shared", delayed, let("alias", variable("shared"), reads)), 4);
    }
    @Test void preexistingCapturesKeepTheirFinalThunkReferenceWhileTheirRestoredLocalUpdates() throws Exception {
        var captured = lambda("unused", demand(variable("shared"), "first", demand(variable("shared"), "second", primitive("+#", variable("first"), variable("second")))));
        List<Object> call = list("app", variable("captured"), list(integer(0)), list(false)); var reads = demand(variable("shared"), "outer", primitive("+#", variable("outer"), call));
        var delayed = demand(variable("input"), "delayed", variable("delayed")); bothBackends(let("shared", delayed, let("captured", captured, reads)), 3);
        // Recursive closures retain cells after publication; forcing can update their values.
        List<Object> recursive = list("let", true, list(binding("shared", delayed), binding("captured", captured)), reads); bothBackends(recursive, 3);
    }
}
