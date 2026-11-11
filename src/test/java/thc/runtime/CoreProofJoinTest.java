// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.LoopNode;
import com.oracle.truffle.api.nodes.NodeUtil;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.bytecode.ContinuationResult;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import com.oracle.truffle.runtime.OptimizedCallTarget;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.frame.FrameSlotKind;
import thc.Language;
import static org.junit.jupiter.api.Assertions.*;
import static thc.Main.executionContext;

class CoreProofJoinTest {
    private static List<Object> list(Object... values) { return Arrays.asList(values); }
    private static Map<String, Object> map(Object... values) { var result = new LinkedHashMap<String, Object>(); for (int i = 0; i < values.length; i += 2) result.put((String) values[i], values[i + 1]); return result; }
    private static Map<String, Object> with(Map<String, Object> original, Object... values) { var result = new LinkedHashMap<>(original); result.putAll(map(values)); return result; }
    private final Map<String, Object> longProof = map("kind", "long", "primReps", list("IntRep"), "evaluated", true);
    private final Map<String, Object> closure = map("kind", "closure", "primReps", list("BoxedRep (Just Lifted)"), "evaluated", true);
    private Map<String, Object> meta() { return meta(longProof); }
    private Map<String, Object> meta(Map<String, Object> rep) { return map("rep", rep); }
    private List<Object> v(String id) { return list("var", id, meta()); }
    private List<Object> n(long value) { return list("lit", "int", Long.toString(value), meta()); }
    private Map<String, Object> arg(String id) { return map("id", id, "name", id, "lifted", false, "rep", longProof); }
    @SafeVarargs private final List<Object> app(List<Object> fn, List<Object>... args) { return list("app", fn, Arrays.asList(args), Collections.nCopies(args.length, false), false, false, meta()); }
    @SafeVarargs private final List<Object> call(String id, List<Object>... args) { return app(list("var", id, meta(closure)), args); }
    @SafeVarargs private final List<Object> prim(String id, List<Object>... args) { return app(list("prim", id), args); }
    private List<Object> lam(List<String> args, List<Object> body) { var parameters = new ArrayList<Map<String, Object>>(); for (var id : args) parameters.add(arg(id)); return list("lam", parameters, body, map("rep", closure, "resultRep", longProof)); }
    private Map<String, Object> bind(String id, List<Object> rhs) { return bind(id, rhs, null); }
    private Map<String, Object> bind(String id, List<Object> rhs, Integer join) { var result = map("id", id, "name", id, "lifted", true, "rep", closure, "expr", rhs); if (join != null) { result.put("joinValueArity", join); result.put("joinResultRep", longProof); } return result; }
    private List<Object> let(boolean recursive, List<Map<String, Object>> bindings, List<Object> body) { return list("let", recursive, bindings, body, meta()); }
    private List<Object> choose(List<Object> condition, List<Object> yes, List<Object> no) { return list("case", condition, "condition", list(list("lit", list("int", "1"), list(), yes), list("default", null, list(), no)), with(meta(), "binder", arg("condition"))); }
    @Test void malformedVariableIdentifiersRejectBeforeInputSignatureLookup() {
        for (var id : Arrays.asList(null, 3L)) {
            var binding = bind("entry", lam(List.of("input"), app(list("var", id, meta(closure)), v("input"))));
            var failure = assertThrows(RuntimeFault.class, () -> CoreInputCalls.validate(List.of(binding)));
            assertEquals("Missing Core variable identifier", failure.getMessage());
        }
        assertDoesNotThrow(() -> CoreInputCalls.validate(List.of(bind("entry", lam(List.of("input"), call("external", v("input")))))));
    }
    @FunctionalInterface private interface Action { void run(Program program) throws ReflectiveOperationException; }
    private void program(List<Object> body, Action action) throws ReflectiveOperationException {
        try (var context = executionContext()) {
            context.initialize("thc"); context.enter();
            try { var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); action.run(new Program(language, map("bindings", list(bind("entry", lam(List.of("input"), body))), "instrument", true))); } finally { context.leave(); }
        }
    }
    private long run(Program program, long n) { return (Long) Calls.target(program.hostEntryTarget(1), new Object[]{program.entryValue("entry"), new Object[]{n}}); }
    private long count(Program program, String name) { return ((Number) program.diagnostics().get(name)).longValue(); }
    @Test void firstCompiledSelfTailLoopRetainsItsColdInstallation() {
        try (var context = org.graalvm.polyglot.Context.newBuilder("thc").allowExperimentalOptions(true)
                .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
                .option("engine.CompilationFailureAction", "Throw").build()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var layout = new FrameLayout();
                int remaining = layout.bind("remaining", FrameSlotKind.Long);
                var compiledCompletion = new java.util.concurrent.atomic.AtomicBoolean();
                Expr body = new Expr() {
                    @Override public Object execute(VirtualFrame frame) { return executeLong(frame); }
                    @Override public long executeLong(VirtualFrame frame) {
                        long n = frame.getLong(remaining);
                        if (n == 0) { compiledCompletion.set(CompilerDirectives.inCompiledCode()); return 42L; }
                        frame.setLong(remaining, n - 1);
                        throw AstSelfCall.INSTANCE;
                    }
                };
                var proof = new CoreRepresentation(CoreKind.LONG, true, true, List.of("IntRep"));
                body.setRepresentation(proof);
                var metrics = new Metrics(true);
                var root = new FunctionRoot(language, layout.build(), "cold self tail", null, new int[0],
                    new int[]{remaining}, new int[]{0}, body, metrics, new CoreRepresentation[]{proof}, proof,
                    null, new boolean[]{false}, null, null, new int[0], null, false, new int[0][], false,
                    FunctionRootRole.FUNCTION, false);
                var target = (OptimizedCallTarget) root.getCallTarget();
                assertFalse(target.wasExecuted()); assertTrue(target.prepareForAOT());
                target.compile(true); target.waitForCompilation(); assertTrue(target.isValidLastTier());
                long address = target.getCodeAddress(); int installations = target.getSuccessfulCompilationCount();
                assertFalse(target.wasExecuted()); assertEquals(0L, metrics.getSelfTailReentries());
                assertEquals(42L, Calls.target(target, new Object[]{0L, 1_000L}));
                assertTrue(compiledCompletion.get(), "The first recursive call must finish in its original compiled loop");
                assertEquals(1_000L, metrics.getSelfTailReentries());
                assertTrue(target.isValidLastTier()); assertEquals(address, target.getCodeAddress());
                assertEquals(installations, target.getSuccessfulCompilationCount());
            } finally { context.leave(); }
        }
    }
    @ParameterizedTest @ValueSource(strings = {"long", "int", "float", "double"})
    void firstCompiledLocalJoinRetainsItsDeclaredCarrierAndInstallation(String carrier) {
        String kind = carrier.equals("int") ? "long" : carrier;
        String primRep = switch (carrier) { case "int" -> "Int32Rep"; case "float" -> "FloatRep"; case "double" -> "DoubleRep"; default -> "IntRep"; };
        var proof = map("kind", kind, "primReps", list(primRep), "evaluated", true);
        var parameter = map("id", "answer", "name", "answer", "lifted", false, "rep", proof);
        var join = with(bind("done", list("lam", list(parameter), list("var", "answer", meta(proof)),
            map("rep", closure, "resultRep", proof)), 1), "joinResultRep", proof);
        var body = list("let", true, list(join), list("app", list("var", "done", meta(closure)),
            list(list("var", "input", meta(proof))), list(false), false, false, meta(proof)), meta(proof));
        var input = with(parameter, "id", "input", "name", "input");
        var entry = bind("entry", list("lam", list(input), body, map("rep", closure, "resultRep", proof)));
        Object value = switch (carrier) { case "int" -> Integer.valueOf(42); case "float" -> Float.valueOf(42.5f); case "double" -> Double.valueOf(42.5); default -> Long.valueOf(42); };
        try (var context = org.graalvm.polyglot.Context.newBuilder("thc").allowExperimentalOptions(true)
                .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
                .option("engine.CompilationFailureAction", "Throw").build()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var program = new Program(language, map("bindings", list(entry), "instrument", true));
                var target = (OptimizedCallTarget) program.entryTarget("entry");
                assertFalse(target.wasExecuted()); assertTrue(target.prepareForAOT());
                target.compile(true); target.waitForCompilation(); assertTrue(target.isValidLastTier());
                long address = target.getCodeAddress(); int installations = target.getSuccessfulCompilationCount();
                assertFalse(target.wasExecuted()); assertEquals(0L, count(program, "localJoinTransfers"));
                // The host caller takes the empty-packet pending-loan ingress
                // for an eligible dense Long root; other carriers use their
                // ordinary or typed-packet ingress to this same original target.
                assertEquals(value, Calls.target(program.hostEntryTarget(1),
                    new Object[]{program.entryValue("entry"), new Object[]{value}}));
                assertEquals(1L, count(program, "compiledEntries")); assertEquals(1L, count(program, "localJoinTransfers"));
                assertTrue(target.isValidLastTier(), "The first join must not discover its frame carrier during execution");
                assertEquals(address, target.getCodeAddress()); assertEquals(installations, target.getSuccessfulCompilationCount());
            } finally { context.leave(); }
        }
    }
    private void compile(Program program) throws ReflectiveOperationException { var type = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget"); var target = program.entryTarget("entry"); type.getMethod("compile", boolean.class).invoke(target, true); type.getMethod("waitForCompilation").invoke(target); assertEquals(true, type.getMethod("isValidLastTier").invoke(target)); }
    private static final class CloneCaller extends RootNode {
        @Child private DirectCallNode call;
        CloneCaller(RootCallTarget target) { super(null); call = DirectCallNode.create(target); }
        @Override public Object execute(VirtualFrame frame) { return Calls.direct(call, frame.getArguments()); }
        RootCallTarget cloneTarget() { getCallTarget(); assertTrue(call.cloneCallTarget()); return (RootCallTarget) call.getClonedCallTarget(); }
    }
    private void assertBranchRegions(RootNode root, int expected) {
        var regions = NodeUtil.findAllNodeInstances(root, LocalJoinRegion.class); assertEquals(expected, regions.size());
        // Acyclic join regions must not own loops; roots and generic forcing retain theirs.
        boolean all = true; for (var region : regions) { for (var child : region.getChildren()) if (child instanceof LoopNode) { all = false; break; } if (!all) break; } assertTrue(all);
    }
    @Test void compiledRecursiveJoinPollsWithoutCallingAnotherRoot() throws Exception {
        var step = choose(prim("<=#", v("n"), n(0)), v("acc"), call("loop", prim("-#", v("n"), n(1)), prim("+#", v("acc"), n(1))));
        var body = let(true, List.of(bind("loop", lam(List.of("n", "acc"), step), 2)), call("loop", v("input"), n(0)));
        try (var context = executionContext()) {
            context.initialize("thc"); final BytecodeProgram p; final GuestThreads threads; context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); threads = Language.currentState().getThreads(); p = new BytecodeProgram(language, map("bindings", list(bind("entry", lam(List.of("input"), body))), "instrument", true), true);
                var target = p.entryTarget("entry"); assertEquals(10L, Calls.target(target, new Object[]{0L, 10L})); target.getClass().getMethod("compile", boolean.class).invoke(target, true); target.getClass().getMethod("waitForCompilation").invoke(target); assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target));
            } finally { context.leave(); }
            var completed = new CompletableFuture<Object>();
            var worker = new Thread(() -> {
                context.enter(); threads.enterCurrent(null, false, true, null);
                try {
                    var answer = Calls.target(p.entryTarget("entry"), new Object[]{0L, 10_000_000L});
                    // This isolated loop acknowledges the cut; native public-thread tests cover catch#.
                    if (answer instanceof ContinuationResult saved) { var request = AsyncContinuations.request(saved); if (request != null) request.acknowledge(); } completed.complete(answer);
                } catch (Throwable failure) { completed.completeExceptionally(failure); } finally { threads.leaveCurrent(GuestThreadStatus.FINISHED); context.leave(); }
            });
            worker.setDaemon(true); worker.start();
            try {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (((Number) p.diagnostics().get("localJoinTransfers")).longValue() < 1000 && !completed.isDone() && System.nanoTime() < deadline) Thread.sleep(1);
                assertFalse(completed.isDone(), "The request must arrive inside the join loop"); var request = threads.send(Objects.requireNonNull(threads.pollState(worker).getCurrent()).getIdentity(), "join loop");
                var saved = (ContinuationResult) completed.get(10, TimeUnit.SECONDS); worker.join(5000); assertFalse(worker.isAlive()); assertSame(request, saved.getResult()); assertTrue(request.compiledCapture); assertEquals(AsyncRequestState.ACKNOWLEDGED, request.getState());
                context.enter(); try { assertEquals(10_000_000L, saved.continueWith(thc.runtime.Unit.INSTANCE)); } finally { context.leave(); }
            } finally { if (worker.isAlive()) context.close(true); }
        }
    }
    @Test void savedRecursiveJoinEntryCompilesWithPendingOperandAndBothCycleEntries() throws Exception {
        var state = map("kind", "void", "primReps", list(), "evaluated", true);
        var pause = list("app", list("prim", "noDuplicate#"), list(list("void", meta(state))),
                list(false), false, false, meta(state));
        var afterPause = list("case", pause,
                "paused", list(list("default", null, list(), n(2))), with(meta(), "binder",
                        map("id", "paused", "name", "paused", "lifted", false, "rep", state)));
        // Initial entry takes B, then A, before the third iteration captures a real
        // pending operand. A transfers forward to B; B transfers backward to A.
        var a = call("b", v("cycles"), prim("+#", prim("*#", v("traceA"), n(10)), n(1)));
        var bTrace = prim("+#", prim("*#", v("traceB"), n(10)), n(2));
        var b = choose(prim("==#", v("cyclesB"), n(1)), bTrace,
                call("a", prim("+#", v("cyclesB"), n(1)), bTrace));
        var pending = prim("+#", n(40), afterPause);
        var selected = choose(prim("==#", pending, n(42)),
                choose(prim("==#", prim("remInt#", v("iteration"), n(2)), n(0)),
                        call("a", n(0), v("trace")), call("b", n(0), v("trace"))), n(-1));
        var entry = list("case", call("arm", v("iteration")), "armed",
                list(list("default", null, list(), selected)), with(meta(), "binder", arg("armed")));
        var inner = let(true, List.of(bind("a", lam(List.of("cycles", "traceA"), a), 2),
                bind("b", lam(List.of("cyclesB", "traceB"), b), 2)), entry);
        var next = list("case", inner, "nextTrace", list(list("default", null, list(),
                call("outer", prim("+#", v("iteration"), n(1)), v("nextTrace")))),
                with(meta(), "binder", arg("nextTrace")));
        var outer = choose(prim("==#", v("iteration"), n(5)), v("trace"), next);
        var body = let(true, List.of(bind("outer", lam(List.of("iteration", "trace"), outer), 2)),
                call("outer", n(1), n(0)));
        assertSavedJoinResume(body, 3, 10L, 21212122121212L, 19L);
    }

    @Test void savedNonrecursiveJoinsInsideRecursiveRegionCompileWithPendingOperand() throws Exception {
        var state = map("kind", "void", "primReps", list(), "evaluated", true);
        var pause = list("app", list("prim", "noDuplicate#"), list(list("void", meta(state))),
                list(false), false, false, meta(state));
        var afterPause = list("case", pause,
                "paused", list(list("default", null, list(), n(2))), with(meta(), "binder",
                        map("id", "paused", "name", "paused", "lifted", false, "rep", state)));
        // Each NonRec has one binding. A can see its lexical ancestor B; B returns
        // to the enclosing recursive loop, so both forward joins lie in its cycle.
        var b = call("outer", prim("+#", v("lexicalIteration"), n(1)),
                prim("+#", prim("*#", v("traceB"), n(10)), n(2)));
        var a = call("b", prim("+#", prim("*#", v("traceA"), n(10)), n(1)));
        var pausedEntry = choose(prim("==#", prim("+#", n(40), afterPause), n(42)),
                choose(prim("==#", prim("remInt#", v("iteration"), n(2)), n(0)),
                        call("a", v("trace")), call("b", v("trace"))), n(-1));
        // The first two iterations take both post-pause arms; the third bypasses it.
        var selected = choose(prim("==#", v("iteration"), n(3)), call("a", v("trace")), pausedEntry);
        var entry = list("case", call("arm", v("iteration")), "armed",
                list(list("default", null, list(), selected)), with(meta(), "binder", arg("armed")));
        var inner = let(false, List.of(bind("b", lam(List.of("traceB"), b), 1)),
                let(false, List.of(bind("a", lam(List.of("traceA"), a), 1)), entry));
        // Only B names this case-bound local; A must retain B's original capture
        // source by physical identity when its own body is lifted out of the case.
        var captured = list("case", prim("+#", v("iteration"), n(0)), "lexicalIteration",
                list(list("default", null, list(), inner)), with(meta(), "binder", arg("lexicalIteration")));
        var outer = choose(prim("==#", v("iteration"), n(6)), v("trace"), captured);
        var body = let(true, List.of(bind("outer", lam(List.of("iteration", "trace"), outer), 2)),
                call("outer", n(1), n(0)));
        assertSavedJoinResume(body, 4, 9L, 21212122L, 14L);
    }

    private void assertSavedJoinResume(List<Object> body, int captureIteration, long transfersBeforeResume,
            long expectedTrace, long transfersAfterResume) throws Exception {
        try (var context = org.graalvm.polyglot.Context.newBuilder("thc").allowExperimentalOptions(true)
                .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
                .option("engine.CompilationFailureAction", "Throw")
                .option("compiler.CompilationTimeout", "30").option("compiler.MaximumGraalGraphSize", "100000").build()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var checkpoint = new BytecodeCheckpoint();
                var effects = new java.util.concurrent.atomic.AtomicInteger();
                // Earlier real iterations take the same checkpoint's ordinary false path.
                var arm = new Closure(null, 1, new RootNode(language) {
                    @Override public Object execute(VirtualFrame frame) {
                        effects.incrementAndGet();
                        checkpoint.setArmed((Long) frame.getArguments()[1] == captureIteration);
                        return 0L;
                    }
                }.getCallTarget());
                var function = list("lam", list(map("id", "arm", "name", "arm", "lifted", true, "rep", closure)),
                        body, map("rep", closure, "resultRep", longProof));
                var program = new BytecodeProgram(language,
                        map("bindings", list(bind("entry", function)), "instrument", true), checkpoint, true);
                var root = (BytecodeRoot) program.entryTarget("entry").getRootNode();
                assertTrue(root.prepareForCompilation(true, 2, true));
                root.getBytecodeNode().ensureSourceInformation();
                var first = assertInstanceOf(ContinuationResult.class, Calls.target(root.getCallTarget(), new Object[]{0L, arm}));
                assertSame(Unit.INSTANCE, first.getResult());
                assertEquals(1, checkpoint.getVisits().get()); assertEquals(captureIteration, effects.get());
                assertEquals(transfersBeforeResume, program.diagnostics().get("localJoinTransfers"), "both entry paths precede capture exactly once");
                var frame = first.getFrame(); var continuation = first.getContinuationRootNode();
                int pc = continuation.getLocation().getBytecodeIndex();
                root.getRootNodes().ensureSourceInformation();
                var location = continuation.getLocation();
                assertEquals(com.oracle.truffle.api.bytecode.BytecodeTier.CACHED, location.getBytecodeNode().getTier());
                assertEquals(pc, location.getBytecodeIndex());
                var target = (com.oracle.truffle.runtime.OptimizedCallTarget) first.getContinuationCallTarget();
                assertTrue(target.compile(true)); assertTrue(target.isValidLastTier());
                assertEquals(1, checkpoint.getVisits().get(), "compilation must not execute a guest prefix");
                assertEquals(0, checkpoint.getCompiledVisits().get()); assertEquals(captureIteration, effects.get());
                ((com.oracle.truffle.runtime.OptimizedTruffleRuntime) com.oracle.truffle.api.Truffle.getRuntime()).bypassedInstalledCode(target);
                assertEquals(expectedTrace, first.continueWith(Unit.INSTANCE));
                assertSame(frame, first.getFrame()); assertEquals(captureIteration + 1, effects.get());
                assertEquals(1, checkpoint.getVisits().get());
                assertEquals(1, checkpoint.getCompiledVisits().get(), "first installed resume executes the next iteration checkpoint");
                assertEquals(transfersAfterResume, program.diagnostics().get("localJoinTransfers"), "all join transfers execute once");
                assertSame(continuation, first.getContinuationRootNode()); assertSame(target, first.getContinuationCallTarget());
                assertSame(root, continuation.getSourceRootNode());
                assertSame(location.getBytecodeNode(), continuation.getLocation().getBytecodeNode());
                assertEquals(pc, continuation.getLocation().getBytecodeIndex());
            } finally { context.leave(); }
        }
    }

    @Test void deeplyNestedNonrecursiveJoinsAreBranchesIncludingInClonedCompiledTargets() throws ReflectiveOperationException {
        var region = let(false, List.of(bind("positive", lam(List.of("value"), prim("+#", v("value"), n(17))), 1), bind("negative", lam(List.of("value"), prim("-#", n(0), v("value"))), 1)), choose(prim("<#", v("input"), n(0)), call("negative", v("input")), choose(prim("==#", v("input"), n(0)), n(4096), call("positive", v("input")))));
        // Exercise zero-arity, multiple targets, direct return and a non-tail continuation without multiplying loop nesting.
        for (int index = 0; index < 24; index++) region = let(false, List.of(bind("finish" + index, region, 0)), v("finish" + index));
        program(prim("+#", region, n(19)), p -> {
            var original = p.entryTarget("entry"); assertBranchRegions(original.getRootNode(), 25); var caller = new CloneCaller(original); var cloned = caller.cloneTarget(); assertBranchRegions(cloned.getRootNode(), 25);
            java.util.function.LongConsumer check = input -> {
                long expected = (input < 0 ? -input : input == 0L ? 4096L : input + 17) + 19; assertEquals(expected, run(p, input)); assertEquals(expected, Calls.target(caller.getCallTarget(), new Object[]{0L, input}));
            };
            for (int i = 0; i < 30; i++) check.accept((long) i - 15); compile(p); var type = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget"); type.getMethod("compile", boolean.class).invoke(cloned, true); type.getMethod("waitForCompilation").invoke(cloned); assertEquals(true, type.getMethod("isValidLastTier").invoke(cloned));
            long before = count(p, "compiledEntries"); for (long input : new long[]{Long.MIN_VALUE, Long.MAX_VALUE, 0L, 3_000_000_001L, -3_000_000_001L}) check.accept(input); assertTrue(count(p, "compiledEntries") > before); assertTrue(count(p, "localJoinTransfers") > 0);
        });
    }
    @Test void nonrecursiveShadowedJoinRhsTransfersToItsLexicalAncestor() throws ReflectiveOperationException {
        var inner = let(false, List.of(bind("finish", lam(List.of("inner"), call("finish", prim("+#", v("inner"), n(5)))), 1)), call("finish", v("input")));
        var outer = let(false, List.of(bind("finish", lam(List.of("outer"), prim("+#", v("outer"), n(7))), 1)), inner);
        program(prim("+#", outer, n(11)), p -> { assertBranchRegions(p.entryTarget("entry").getRootNode(), 2); for (int i = 0; i < 30; i++) assertEquals((long) i + 23, run(p, i)); compile(p); for (long input : new long[]{Long.MIN_VALUE, Long.MAX_VALUE, 3_000_000_001L}) assertEquals(input + 23, run(p, input)); assertEquals(66L, count(p, "localJoinTransfers")); assertEquals(0L, count(p, "tailBounces")); });
    }
    @Test void recursivePrimitiveJoinUsesParallelMovesAndReturnsToNonTailContinuation() throws ReflectiveOperationException {
        var step = choose(prim("<=#", v("n"), n(0)), prim("-#", v("a"), v("b")), call("loop", prim("-#", v("n"), n(1)), v("b"), v("a")));
        var region = let(true, List.of(bind("loop", lam(List.of("n", "a", "b"), step), 3)), call("loop", v("input"), n(4_000_000_001L), n(9_000_000_002L)));
        program(prim("+#", region, n(17)), p -> {
            java.util.function.LongConsumer check = n -> assertEquals((n % 2 == 0L ? -5_000_000_001L : 5_000_000_001L) + 17, run(p, n)); check.accept(100_000); check.accept(100_001); compile(p); check.accept(100_002); check.accept(100_003);
            assertTrue(count(p, "localJoinTransfers") > 400_000); assertEquals(0L, count(p, "tailBounces")); assertEquals(0L, count(p, "papAllocations"));
        });
    }
    @Test void mutualJoinsAndNestedOuterTransfersStayInTheirLexicalRegion() throws ReflectiveOperationException {
        var even = choose(prim("<=#", v("n"), n(0)), n(20), call("odd", prim("-#", v("n"), n(1))));
        var odd = choose(prim("<=#", v("n"), n(0)), n(30), let(false, List.of(bind("inner", call("even", prim("-#", v("n"), n(1))), 0)), list("var", "inner", meta())));
        program(let(true, List.of(bind("even", lam(List.of("n"), even), 1), bind("odd", lam(List.of("n"), odd), 1)), call("even", v("input"))), p -> {
            assertEquals(20L, run(p, 100_000)); assertEquals(30L, run(p, 100_001)); compile(p); assertEquals(30L, run(p, 100_003)); assertEquals(0L, count(p, "tailBounces")); assertTrue(count(p, "localJoinTransfers") > 300_000);
        });
    }
    @Test void invalidJoinUsesRejectAtLoadRatherThanEscapingIntoClosures() {
        var definition = bind("join", lam(List.of("x"), v("x")), 1);
        for (var body : List.of(v("join"), call("join"), call("join", n(1), n(2)), prim("+#", call("join", n(1)), n(2)), lam(List.of(), call("join", n(1))))) assertThrows(RuntimeFault.class, () -> program(let(false, List.of(definition), body), p -> {}));
    }
    @Test void exportedEvaluatedCafAndRecursiveAliasesStillForceIntroducedThunks() throws ReflectiveOperationException {
        var dataRep = map("kind", "data", "primReps", list("BoxedRep (Just Lifted)"), "evaluated", true);
        var box = map("id", "Box", "name", "Box", "arity", 1, "kind", "boxed", "fieldReps", list(list("IntRep")), "fieldLifted", list(false), "strictFields", list(false));
        class Data {
            List<Object> variable(String id) { return list("var", id, meta(dataRep)); }
            Map<String, Object> binding(String id, List<Object> rhs) { return map("id", id, "name", id, "lifted", true, "rep", dataRep, "expr", rhs); }
            List<Object> boxed(List<Object> value) { return list("app", list("con", "Box", 1, meta(closure)), list(value), list(false), true, true, meta(dataRep)); }
            List<Object> extract(List<Object> value) { return list("case", value, "box", list(list("data", "Box", list("payload"), v("payload"), map("binders", list(arg("payload"))))), with(meta(), "binder", map("id", "box", "lifted", true, "rep", dataRep))); }
        }
        var d = new Data(); var caf = d.binding("caf", d.boxed(n(4_000_000_003L)));
        var recursive = list("let", true, list(d.binding("value", d.boxed(v("input"))), d.binding("alias", d.variable("value"))), d.extract(d.variable("alias")), meta()); var body = prim("+#", d.extract(d.variable("caf")), recursive);
        try (var context = executionContext()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); var p = new Program(language, map("constructors", list(box), "bindings", list(caf, bind("entry", lam(List.of("input"), body)))));
                assertEquals(7_000_000_004L, run(p, 3_000_000_001L)); assertTrue(count(p, "thunkEvaluations") >= 3, "CAF and recursive aliases must retain actual storage forcing"); compile(p); assertEquals(4_000_000_003L + Long.MAX_VALUE, run(p, Long.MAX_VALUE));
            } finally { context.leave(); }
        }
    }
    @Test void provenLongCapturesPreserveFullWidthValuesWithoutAnObjectArm() {
        try (var context = executionContext()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); var layout = new FrameLayout(); int slot = layout.bind("captured"); var descriptor = layout.build(); var captures = new CaptureLayout(language, new boolean[]{true}, new boolean[]{true});
                for (long value : new long[]{3_000_000_001L, Long.MIN_VALUE, Long.MAX_VALUE}) {
                    var frame = com.oracle.truffle.api.Truffle.getRuntime().createVirtualFrame(new Object[0], descriptor); FrameAccess.writeLong(frame, slot, value); var environment = captures.capture(frame, new int[]{slot});
                    assertTrue(environment.isLong(0)); assertFalse(environment.isObject(0)); assertEquals(value, environment.getLong(0)); var restored = com.oracle.truffle.api.Truffle.getRuntime().createVirtualFrame(new Object[0], descriptor); captures.restore(environment, 0, restored, slot); assertTrue(restored.isLong(slot)); assertEquals(value, restored.getLong(slot));
                }
                assertThrows(RuntimeFault.class, () -> captures.captureValues(new Object[]{thc.runtime.Unit.INSTANCE}));
            } finally { context.leave(); }
        }
    }
    @Test void representationMetadataRequiresExactCarriersAndPreservesLegacyUnknown() {
        assertFalse(CoreRepresentations.expression(list("var", "x")).getPresent()); assertTrue(CoreRepresentations.parse(longProof).isLong()); var absent = new LinkedHashMap<>(longProof); absent.remove("primReps");
        for (var bad : List.of(absent, with(longProof, "primReps", list("AddrRep")), with(closure, "primReps", list("IntRep")), with(closure, "primReps", list()))) assertThrows(RuntimeFault.class, () -> CoreRepresentations.parse(bad));
        assertThrows(RuntimeFault.class, () -> CoreRepresentations.parse(longProof).refine(CoreRepresentations.parse(closure))); assertThrows(RuntimeFault.class, () -> CoreRepresentations.joinArity(map("joinValueArity", 1.5))); assertNull(CoreRepresentations.joinArity(map("info", map("joinArity", 1))));
    }
}
