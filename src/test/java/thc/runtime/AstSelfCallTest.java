// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.RootNode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import thc.Language;
import static org.junit.jupiter.api.Assertions.*;
import static thc.Main.executionContext;

class AstSelfCallTest {
    private final Map<String, Object> longRep = map("kind", "long", "primReps", list("IntRep"), "evaluated", true);
    private final Map<String, Object> closure = map("kind", "closure", "primReps", list("BoxedRep (Just Lifted)"), "evaluated", true);
    private static List<Object> list(Object... values) { return Arrays.asList(values); }
    private static Map<String, Object> map(Object... pairs) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]);
        return result;
    }
    private List<Object> variable(String id) { return list("var", id); }
    private List<Object> integer(long n) { return list("lit", "int", Long.toString(n)); }
    private Map<String, Object> parameter(String id) { return map("id", id, "name", id, "lifted", false, "coercion", false, "rep", longRep); }
    private List<Object> lambda(List<String> ids, List<Object> body) { return lambda(ids, body, longRep); }
    private List<Object> lambda(List<String> ids, List<Object> body, Map<String, Object> result) {
        List<Map<String, Object>> parameters = new ArrayList<>();
        for (String id : ids) parameters.add(parameter(id));
        return list("lam", parameters, body, map("rep", closure, "resultRep", result));
    }
    private Map<String, Object> binding(String id, List<Object> rhs) { return map("id", id, "name", id, "lifted", true, "expr", rhs); }
    @SafeVarargs private final List<Object> apply(List<Object> fn, List<Object>... args) {
        return list("app", fn, Arrays.asList(args), Collections.nCopies(args.length, false));
    }
    @SafeVarargs private final List<Object> primitive(String name, List<Object>... args) { return apply(list("prim", name), args); }
    private List<Object> choose(List<Object> value, List<Object> zero, List<Object> other) {
        return list("case", value, "choice", list(list("lit", list("int", "0"), List.of(), zero), list("default", null, List.of(), other)));
    }
    private List<Object> caseDefault(List<Object> value, String id, List<Object> body) {
        return list("case", value, id, list(list("default", null, List.of(), body)));
    }
    private long count(ExecutableProgram p, String name) { return ((Number) p.diagnostics().get(name)).longValue(); }
    private Object call(ExecutableProgram p, Object function, Object... args) { return Calls.target(p.hostEntryTarget(args.length), new Object[]{function, args.clone()}); }
    private void compile(RootCallTarget target) throws ReflectiveOperationException {
        Class<?> type = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget");
        type.getMethod("compile", boolean.class).invoke(target, true);
        type.getMethod("waitForCompilation").invoke(target);
        assertEquals(true, type.getMethod("isValidLastTier").invoke(target));
    }
    @FunctionalInterface private interface ProgramAction { void run(Program program) throws Exception; }
    private void withProgram(List<Map<String, Object>> bindings, ProgramAction action) throws Exception {
        try (var context = executionContext()) {
            context.initialize("thc"); context.enter();
            try {
                Language language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                action.run(new Program(language, map("bindings", bindings, "instrument", true)));
            } finally { context.leave(); }
        }
    }

    @Test void optionalCaptureAndArgumentMetadataRetainShortAndNullEntries() {
        try (var context = executionContext(false)) {
            context.initialize("thc"); context.enter();
            try {
                Language language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                RootCallTarget target = new RootNode(language) {
                    @Override public Object execute(VirtualFrame frame) { throw new IllegalStateException("Frame restoration must not invoke a target"); }
                }.getCallTarget();
                Closure first = new Closure(null, 0, target);
                Object second = new Object();
                CaptureLayout captures = new CaptureLayout(language, new boolean[]{false, false});
                var environment = captures.captureValues(new Object[]{first, second});
                Closure function = new Closure(environment, 0, target);
                for (int size = 0; size <= 2; size++) {
                    FrameLayout layout = new FrameLayout();
                    int[] slots = {layout.bind("first"), layout.bind("second")};
                    VirtualFrame frame = Truffle.getRuntime().createVirtualFrame(new Object[0], layout.build());
                    AstSelfLayout self = new AstSelfLayout(captures, slots, new int[0], new CoreRepresentation[0], new boolean[0], null, new int[size][]);
                    assertSame(AstSelfCall.INSTANCE, assertThrows(AstSelfCall.class, () -> self.transfer(frame, function, new int[0])));
                    assertSame(first, FrameAccess.read(frame, slots[0]));
                    assertSame(second, FrameAccess.read(frame, slots[1]));
                }
                CoreRepresentation reference = new CoreRepresentation(CoreKind.CLOSURE, true, false, null, null, null, null, null, null);
                for (CoreRepresentation[] proofs : List.of(new CoreRepresentation[0], new CoreRepresentation[]{CoreRepresentation.UNKNOWN},
                        new CoreRepresentation[]{reference}, new CoreRepresentation[]{reference, CoreRepresentation.UNKNOWN})) {
                    FrameLayout layout = new FrameLayout();
                    int[] slots = {layout.bind("first"), layout.bind("second")};
                    var descriptor = layout.build();
                    Expr body = new Expr() {
                        @Override public Object execute(VirtualFrame frame) { throw new IllegalStateException("Frame restoration must not execute its body"); }
                    };
                    FunctionRoot root = new FunctionRoot(language, descriptor, "optional references", null,
                        new int[0], slots, new int[]{0, 1}, body, new Metrics(false), proofs, body.getRepresentation(), body.getCoreSourceLocation(),
                        new boolean[0], null, null, new int[0], null, false, new int[0][], false, FunctionRootRole.FUNCTION, false);
                    VirtualFrame frame = Truffle.getRuntime().createVirtualFrame(new Object[0], descriptor);
                    root.buildFrame(new Object[]{0L, first, second}, frame);
                    assertSame(first, FrameAccess.read(frame, slots[0]));
                    assertSame(second, FrameAccess.read(frame, slots[1]));
                    if (proofs.length != 0 && reference.equals(proofs[0])) {
                        RuntimeFault failure = assertThrows(RuntimeFault.class, () -> root.buildFrame(new Object[]{0L, second, first}, frame));
                        assertEquals("Expected proven reference value", failure.getMessage());
                        assertSame(first, FrameAccess.read(frame, slots[0]));
                        assertSame(second, FrameAccess.read(frame, slots[1]));
                    }
                }
            } finally { context.leave(); }
        }
    }

    @Test void hostSuppliedPartialSelfCallValidatesErasedState() throws Exception {
        var state = map("kind", "void", "primReps", List.of(), "evaluated", true);
        var parameters = List.of(map("id", "state", "lifted", false, "rep", state),
            map("id", "next", "lifted", true, "rep", closure), parameter("remaining"));
        var body = choose(variable("remaining"), integer(0), apply(variable("next"), integer(0)));
        var worker = list("lam", parameters, body, map("rep", closure, "resultRep", longRep));
        withProgram(List.of(binding("worker", worker)), p -> {
            var function = (Closure) p.entryValue("worker");
            Object[] goodPrefix = {Unit.INSTANCE, null}, badPrefix = {9L, null};
            var good = new Closure(function.environment, goodPrefix, 1, function.target);
            var bad = new Closure(function.environment, badPrefix, 1, function.target);
            goodPrefix[1] = good; badPrefix[1] = bad;
            assertEquals(0L, Calls.target(function.target, new Object[]{0L, Unit.INSTANCE, good, 1L}));
            assertThrows(RuntimeFault.class, () -> Calls.target(function.target, new Object[]{0L, Unit.INSTANCE, bad, 1L}));
            compile(function.target);
            long before = count(p, "compiledEntries");
            assertEquals(0L, Calls.target(function.target, new Object[]{0L, Unit.INSTANCE, good, 1L}));
            assertTrue(count(p, "compiledEntries") > before, "the first installed call executes valid partial self transfer");
            assertThrows(RuntimeFault.class, () -> Calls.target(function.target, new Object[]{0L, Unit.INSTANCE, bad, 1L}));
        });
    }

    @Test void selfTransferSwapsWidePrimitiveArgumentsAndSurvivesColdCompiledRecursion() throws Exception {
        var n = variable("remaining"); var x = variable("x"); var y = variable("y");
        var body = choose(n, primitive("-#", x, y), apply(variable("swap"), primitive("-#", n, integer(1)), y, x));
        withProgram(List.of(binding("swap", lambda(List.of("remaining", "x", "y"), body))), p -> {
            Closure fn = (Closure) p.entryValue("swap");
            class Check {
                void run(long depth, long first, long second) {
                    long reentries = count(p, "selfTailReentries");
                    long bounces = count(p, "tailBounces");
                    long expected = depth % 2 == 0L ? first - second : second - first;
                    assertEquals(expected, call(p, fn, depth, first, second));
                    assertEquals(reentries + depth, count(p, "selfTailReentries"));
                    assertEquals(bounces, count(p, "tailBounces"), "Self transfers must never build a tail packet");
                    assertEquals(0L, count(p, "trampolineIterations"));
                }
            }
            Check check = new Check();
            for (int i = 0; i < 30; i++) check.run(0, 3_000_000_017L, -7_000_000_003L);
            compile(fn.target);
            check.run(100_001, Long.MIN_VALUE, Long.MAX_VALUE);
            compile(fn.target);
            long compiled = count(p, "compiledEntries");
            for (long depth : new long[]{0, 1, 2, 10_000, 10_001}) check.run(depth, 3_000_000_017L, -7_000_000_003L);
            assertTrue(count(p, "compiledEntries") > compiled);
        });
    }

    @Test void selfTransferRestoresDistinctEnvironmentsAndTheirRecursiveCells() throws Exception {
        var n = variable("remaining"); var x = variable("x"); var y = variable("y");
        var decrement = primitive("-#", n, integer(1));
        var self = apply(variable("worker"), decrement, y, x);
        var fresh = caseDefault(apply(variable("make"), primitive("+#", variable("base"), integer(1))), "next", apply(variable("next"), decrement, y, x));
        var worker = lambda(List.of("remaining", "x", "y"), choose(n,
            primitive("+#", variable("base"), primitive("-#", x, y)), choose(primitive("-#", n, integer(1)), self, fresh)));
        var group = list("let", true, list(binding("worker", worker)), variable("worker"));
        withProgram(List.of(binding("make", lambda(List.of("base"), group, closure))), p -> {
            Closure a = (Closure) call(p, p.entryValue("make"), 3_000_000_017L);
            Closure b = (Closure) call(p, p.entryValue("make"), -7_000_000_003L);
            assertSame(a.target, b.target);
            assertNotSame(a.environment, b.environment);
            class Check {
                void run(Closure fn, long base, long depth, long first, long second) {
                    long reentries = count(p, "selfTailReentries");
                    long bounces = count(p, "tailBounces");
                    long expected = base + Math.max(0, depth - 1) + (depth % 2 == 0L ? first - second : second - first);
                    assertEquals(expected, call(p, fn, depth, first, second));
                    assertEquals(reentries + depth, count(p, "selfTailReentries"));
                    assertEquals(bounces, count(p, "tailBounces"));
                }
            }
            Check check = new Check();
            for (int i = 0; i < 30; i++) check.run(a, 3_000_000_017L, 8, 3_000_000_019L, -7_000_000_005L);
            compile(a.target);
            // A counted-loop compilation warmed only at depth eight may speculate
            // remaining >= 1. Check its cold deoptimization before recompiling;
            // all captures and exact self-reentry counts must survive that path.
            for (long depth : new long[]{0, 1}) {
                check.run(b, -7_000_000_003L, depth, Long.MIN_VALUE, Long.MAX_VALUE);
                check.run(a, 3_000_000_017L, depth, Long.MAX_VALUE, Long.MIN_VALUE);
            }
            compile(a.target);
            long compiled = count(p, "compiledEntries");
            for (long depth : new long[]{0, 1, 2, 1_000, 1_001}) {
                long beforeB = count(p, "compiledEntries");
                check.run(b, -7_000_000_003L, depth, Long.MIN_VALUE, Long.MAX_VALUE);
                assertTrue(count(p, "compiledEntries") > beforeB, "compiled B at depth " + depth);
                long beforeA = count(p, "compiledEntries");
                check.run(a, 3_000_000_017L, depth, Long.MAX_VALUE, Long.MIN_VALUE);
                assertTrue(count(p, "compiledEntries") > beforeA, "compiled A at depth " + depth);
            }
            assertEquals(true, a.target.getClass().getMethod("isValidLastTier").invoke(a.target));
            assertTrue(count(p, "compiledEntries") > compiled);
            assertEquals(0L, count(p, "blackholes"));
            assertEquals(0L, count(p, "trampolineIterations"));
        });
    }

    @Test void polymorphicTailSiteRetainsSelfTransferAfterThreeNegativeTargetCacheEntries() throws Exception {
        Map<String, Object> lazyClosure = new LinkedHashMap<>(closure);
        lazyClosure.put("evaluated", false);
        var args = list(map("id", "next", "name", "next", "lifted", true, "coercion", false, "rep", lazyClosure), parameter("remaining"), parameter("answer"));
        java.util.function.Function<List<Object>, List<Object>> callable = body -> list("lam", args, body, map("rep", closure, "resultRep", longRep));
        var body = choose(variable("remaining"), variable("answer"), apply(variable("next"), variable("next"), primitive("-#", variable("remaining"), integer(1)), variable("answer")));
        List<Map<String, Object>> bindings = new ArrayList<>();
        bindings.add(binding("worker", callable.apply(body)));
        for (long marker = 1; marker <= 4; marker++) bindings.add(binding("other" + marker, callable.apply(primitive("+#", variable("answer"), integer(marker)))));
        withProgram(bindings, p -> {
            Closure worker = (Closure) p.entryValue("worker");
            List<Closure> others = new ArrayList<>();
            for (long marker = 1; marker <= 4; marker++) others.add((Closure) p.entryValue("other" + marker));
            class Check {
                void nonSelf(long seed) {
                    long reentries = count(p, "selfTailReentries");
                    for (int index = 0; index < others.size(); index++) assertEquals(seed + index + 1, call(p, worker, others.get(index), 1L, seed));
                    assertEquals(reentries, count(p, "selfTailReentries"));
                }
                void self(long depth, long seed) {
                    long reentries = count(p, "selfTailReentries");
                    long bounces = count(p, "tailBounces");
                    long trampolines = count(p, "trampolineIterations");
                    assertEquals(seed, call(p, worker, worker, depth, seed));
                    assertEquals(reentries + depth, count(p, "selfTailReentries"));
                    assertEquals(bounces, count(p, "tailBounces"));
                    assertEquals(trampolines, count(p, "trampolineIterations"));
                }
            }
            Check check = new Check();
            // Fill all three negative self-target entries and cross the normal
            // Dispatch cache limit before the site ever sees its own target.
            for (int i = 0; i < 30; i++) check.nonSelf(3_000_000_017L);
            compile(worker.target);
            check.self(10_001, Long.MIN_VALUE);
            compile(worker.target);
            long compiled = count(p, "compiledEntries");
            check.nonSelf(Long.MAX_VALUE);
            check.self(10_000, Long.MAX_VALUE);
            check.nonSelf(-7_000_000_003L);
            check.self(1, 3_000_000_017L);
            assertTrue(count(p, "compiledEntries") > compiled);
        });
    }

    private static class CloneCaller extends RootNode {
        @Child private DirectCallNode call;
        CloneCaller(RootCallTarget target) { super(null); call = DirectCallNode.create(target); }
        @Override public Object execute(VirtualFrame frame) { return Calls.direct(call, frame.getArguments()); }
        RootCallTarget cloneTarget() {
            getCallTarget();
            assertTrue(call.cloneCallTarget());
            return (RootCallTarget) call.getClonedCallTarget();
        }
    }

    @Test void clonedRootsShareBodyIdentityWhileKeepingIndependentSelfTargetCaches() throws Exception {
        var n = variable("remaining");
        var body = choose(n, integer(91), apply(variable("worker"), primitive("-#", n, integer(1))));
        withProgram(List.of(binding("worker", lambda(List.of("remaining"), body))), p -> {
            RootCallTarget original = p.entryTarget("worker");
            CloneCaller caller = new CloneCaller(original);
            RootCallTarget cloned = caller.cloneTarget();
            assertNotSame(original, cloned);
            class Check {
                void run(RootCallTarget target, long count) {
                    long reentries = count(p, "selfTailReentries");
                    assertEquals(91L, Calls.target(target, new Object[]{0L, count}));
                    assertEquals(reentries + count, count(p, "selfTailReentries"));
                    assertEquals(0L, count(p, "tailBounces"));
                }
            }
            Check check = new Check();
            // Warm the clone first, then the original: cache insertions must not
            // mutate an array retained by the other root or its compiled graph.
            for (int i = 0; i < 30; i++) check.run(cloned, 8);
            compile(cloned);
            for (int i = 0; i < 30; i++) check.run(original, 8);
            compile(original);
            check.run(cloned, 10_001);
            check.run(original, 10_000);
        });
    }
}
