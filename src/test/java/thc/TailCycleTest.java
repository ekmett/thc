// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import com.oracle.truffle.api.*;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.*;
import java.util.*;
import java.util.function.*;
import org.junit.jupiter.api.Test;
import thc.runtime.*;
import static org.junit.jupiter.api.Assertions.*;
import static thc.CoreExecutionTestSupport.*;

/** Tail cycles reenter the matching active root, not merely the host trampoline. */
class TailCycleTest {
    private List<Object> variable(String id) { return list("var", id); }
    private List<Object> integer(long value) { return list("lit", "int", Long.toString(value)); }
    private List<Object> application(List<Object> function, List<List<Object>> arguments, List<Boolean> lifted) { return list("app", function, arguments, lifted); }
    @SafeVarargs private final List<Object> call(String name, List<Object>... arguments) { return application(variable(name), Arrays.asList(arguments), Collections.nCopies(arguments.length, false)); }
    @SafeVarargs private final List<Object> primitive(String name, List<Object>... arguments) { return application(list("prim", name), Arrays.asList(arguments), Collections.nCopies(arguments.length, false)); }
    private List<Object> lambda(List<String> parameters, List<Object> body) {
        return list("lam", parameters.stream().map(id -> map("id", id, "name", id, "type", "Int#", "lifted", false, "coercion", false)).toList(), body);
    }
    private Map<String, Object> binding(String name, List<String> parameters, List<Object> body) {
        return map("id", name, "name", name, "type", "Synthetic", "lifted", true, "arity", parameters.size(), "expr", lambda(parameters, body));
    }
    private List<Object> caseDefault(List<Object> value, String binder, List<Object> body) { return list("case", value, binder, list(list("default", null, list(), body))); }
    private List<Object> choose(List<Object> condition, List<Object> yes, List<Object> no) {
        return list("case", condition, "condition", list(list("lit", list("int", "1"), list(), yes), list("default", null, list(), no)));
    }
    private Map<String, Object> module(List<Map<String, Object>> bindings) { return map("schema", 1, "ghc", "9.14.1", "module", "Synthetic.TailCycle", "instrument", true, "bindings", bindings, "constructors", list()); }
    private RootCallTarget target(ExecutableProgram program, String name) { return ((Closure) program.entryValue(name)).target; }
    private long count(ExecutableProgram program, String key) { return ((Number) program.diagnostics().get(key)).longValue(); }
    private Object invoke(ExecutableProgram program, String name, Object... arguments) { return Calls.target(program.hostEntryTarget(arguments.length), new Object[]{program.entryValue(name), arguments}); }
    private void compile(RootCallTarget target) throws Exception {
        var type = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget"); type.getMethod("compile", boolean.class).invoke(target, true);
        assertEquals(true, type.getMethod("isValidLastTier").invoke(target), "Guest code must be installed");
    }
    /** Identity-derived bloom collisions are valid; these fixtures select active-ancestor reentry. */
    private boolean collisionFree(List<List<RootCallTarget>> paths) {
        for (var path : paths) {
            long ancestry = 0;
            for (var target : path) {
                long mask = ((GuestRoot) target.getRootNode()).mask;
                if ((ancestry & mask) == mask) return false;
                ancestry |= mask;
            }
        }
        return true;
    }
    @FunctionalInterface private interface Action { void run(ExecutableProgram program, String backend) throws Exception; }
    private void bothBackends(Map<String, Object> data, Function<ExecutableProgram, List<List<RootCallTarget>>> paths, Action action) throws Exception {
        for (String backend : list("ast", "bytecode")) try (var context = MainKt.executionContext(false)) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); ExecutableProgram program = null;
                for (int attempt = 0; attempt < 64; attempt++) {
                    ExecutableProgram candidate = backend.equals("ast") ? new Program(language, data, false, false) : new BytecodeProgram(language, data, null, false);
                    if (collisionFree(paths.apply(candidate))) { program = candidate; break; }
                }
                if (program == null) throw new IllegalArgumentException("Could not select collision-free bloom masks for " + backend + " fixture");
                action.run(program, backend);
            } finally { context.leave(); }
        }
    }
    private void handled(ExecutableProgram program, String backend, long minimumReentries, long expected, Supplier<Object> action) {
        long reentries = count(program, "selfTailReentries"), trampolines = count(program, "trampolineIterations");
        assertEquals(expected, action.get(), backend);
        assertTrue(count(program, "selfTailReentries") - reentries >= minimumReentries, backend + " must consume tail transfers in the matching active root");
        assertEquals(trampolines, count(program, "trampolineIterations"), backend + " must not route this collision-free cycle through the outer trampoline");
    }
    private Map<String, Object> countingCycle(String[][] edges) {
        var n = variable("remaining"); var total = variable("total"); var bindings = new ArrayList<Map<String, Object>>();
        for (var edge : edges) bindings.add(binding(edge[0], list("remaining", "total"),
            choose(primitive("<=#", n, integer(0)), total, call(edge[1], primitive("-#", n, integer(1)), primitive("+#", total, n)))));
        return module(bindings);
    }
    @Test void twoRootCyclesReenterLocallyBeforeAndAfterCompilation() throws Exception {
        bothBackends(countingCycle(new String[][]{{"a", "b"}, {"b", "a"}}), p -> list(list(target(p, "a"), target(p, "b"))), (program, backend) -> {
            long n = 12_000L, seed = 3_000_000_017L, expected = seed + n * (n + 1) / 2;
            handled(program, backend, n / 2, expected, () -> invoke(program, "a", n, seed));
            for (int i = 0; i < 40; i++) assertEquals(seed + 36, invoke(program, "a", 8L, seed));
            compile(target(program, "a")); long compiled = count(program, "compiledEntries");
            handled(program, backend, n / 2, expected, () -> invoke(program, "a", n, seed));
            assertTrue(count(program, "compiledEntries") > compiled); assertEquals(seed, invoke(program, "a", 0L, seed), "A fresh entry must not retain loop arguments");
        });
    }
    private List<Object> step(String next, List<Object> n, List<Object> total) { return call(next, primitive("-#", n, integer(1)), primitive("+#", total, n)); }
    private Map<String, Object> worker(String name, List<Object> next, List<Object> n, List<Object> total) {
        return binding(name, list("remaining", "total"), choose(primitive("<=#", n, integer(0)), total, next));
    }
    @Test void anInteriorRootHandlesABCDECReturnsAndCanRetargetOutwardToB() throws Exception {
        var n = variable("remaining"); var total = variable("total");
        var data = module(list(binding("a", list("remaining", "total"), call("b", n, total)), binding("b", list("remaining", "total"), call("c", n, total)),
            worker("c", step("d", n, total), n, total), worker("d", step("e", n, total), n, total),
            worker("e", choose(primitive("<=#", n, integer(5)), step("b", n, total), step("c", n, total)), n, total)));
        bothBackends(data, p -> list(list("a", "b", "c", "d", "e").stream().map(id -> target(p, id)).toList()), (program, backend) -> {
            long input = 12_000L, expected = input * (input + 1) / 2;
            handled(program, backend, input / 3, expected, () -> invoke(program, "a", input, 0L));
            for (int i = 0; i < 40; i++) assertEquals(78L, invoke(program, "a", 12L, 0L));
            compile(target(program, "a")); long compiled = count(program, "compiledEntries");
            handled(program, backend, input / 3, expected, () -> invoke(program, "a", input, 0L)); assertTrue(count(program, "compiledEntries") > compiled);
            for (long small = 0; small <= 8; small++) assertEquals(small * (small + 1) / 2, invoke(program, "a", small, 0L));
        });
    }
    @Test void reentryRestoresChangedCapturesAndPapPrefixesAcrossDifferentArities() throws Exception {
        var n = variable("remaining"); var total = variable("total"); long seed = 3_000_000_017L;
        var capturedBody = choose(primitive("<=#", n, integer(0)), primitive("+#", total, variable("base")),
            call("b", primitive("-#", n, integer(1)), primitive("+#", total, n), primitive("+#", variable("base"), integer(1))));
        var pap = application(variable("next"), list(n), list(false));
        var resume = caseDefault(call("make", variable("base")), "next", caseDefault(pap, "partial", application(variable("partial"), list(total), list(false))));
        var entry = caseDefault(call("make", integer(seed)), "first", application(variable("first"), list(variable("input"), integer(0)), list(false, false)));
        var data = module(list(binding("make", list("base"), lambda(list("remaining", "total"), capturedBody)),
            binding("b", list("remaining", "total", "base"), resume), binding("entry", list("input"), entry)));
        bothBackends(data, p -> {
            var made = (Closure) invoke(p, "make", seed); return list(list(target(p, "entry"), made.target, target(p, "b")));
        }, (program, backend) -> {
            long input = 4_000L, expected = seed + input + input * (input + 1) / 2, paps = count(program, "papAllocations");
            handled(program, backend, input, expected, () -> invoke(program, "entry", input));
            assertTrue(count(program, "papAllocations") - paps >= input, "Each B→A transfer must exercise a supplied prefix");
            for (int i = 0; i < 40; i++) assertEquals(seed + 44L, invoke(program, "entry", 8L));
            compile(target(program, "entry")); handled(program, backend, input, expected, () -> invoke(program, "entry", input));
            assertEquals(seed + 35L, invoke(program, "entry", 7L), "Fresh captures must replace the previous loop's environment"); assertEquals(seed, invoke(program, "entry", 0L));
        });
    }
    @Test void overapplicationAndNonTailWorkSurviveAnInnerTailCycle() throws Exception {
        var n = variable("remaining"); var done = lambda(list("extra"), primitive("+#", variable("extra"), integer(42)));
        var data = module(list(binding("a", list("remaining"), choose(primitive("<=#", n, integer(0)), done, call("b", primitive("-#", n, integer(1))))),
            binding("b", list("remaining"), call("a", n)),
            binding("entry", list("input"), primitive("+#", call("a", variable("input"), integer(17)), primitive("+#", variable("input"), integer(1000))))));
        bothBackends(data, p -> list(list(target(p, "a"), target(p, "b"))), (program, backend) -> {
            long input = 4_000L; handled(program, backend, input, input + 1059L, () -> invoke(program, "entry", input));
            for (int i = 0; i < 40; i++) assertEquals(1067L, invoke(program, "entry", 8L));
            compile(target(program, "entry")); handled(program, backend, input, input + 1059L, () -> invoke(program, "entry", input));
            assertEquals(1059L, invoke(program, "entry", 0L));
        });
    }
    @Test void aNonTailEdgeRetainsEveryPendingAddition() throws Exception {
        var n = variable("remaining");
        var data = module(list(binding("a", list("remaining"), choose(primitive("<=#", n, integer(0)), integer(0),
            primitive("+#", integer(1), call("b", primitive("-#", n, integer(1)))))),
            binding("b", list("remaining"), choose(primitive("<=#", n, integer(0)), integer(0), call("a", primitive("-#", n, integer(1)))))));
        bothBackends(data, p -> list(list(target(p, "b"), target(p, "a"))), (program, backend) -> {
            long before = count(program, "selfTailReentries");
            for (long input : new long[]{0, 1, 2, 17, 120}) assertEquals((input + 1) / 2, invoke(program, "a", input));
            for (int i = 0; i < 40; i++) assertEquals(4L, invoke(program, "a", 8L));
            compile(target(program, "a"));
            for (long input : new long[]{0, 1, 2, 17, 120}) assertEquals((input + 1) / 2, invoke(program, "a", input));
            assertEquals(before, count(program, "selfTailReentries"), "A non-tail A→B edge starts new ancestry; B→A must not discard A's pending addition");
        });
    }
    private static final class CloneCaller extends RootNode {
        @Child private DirectCallNode call;
        CloneCaller(RootCallTarget target) { super(null); call = DirectCallNode.create(target); }
        @Override public Object execute(VirtualFrame frame) { return Calls.direct(call, frame.getArguments()); }
        RootCallTarget cloneTarget() {
            getCallTarget(); assertTrue(call.isCallTargetCloningAllowed()); assertTrue(call.cloneCallTarget(), "Exercise an actual Truffle target clone");
            return (RootCallTarget) call.getClonedCallTarget();
        }
    }
    private Object invokeClone(CloneCaller driver, long input) { return Calls.target(driver.getCallTarget(), new Object[]{0L, input, 0L}); }
    @Test void aClonedRootConsumesTransfersTargetingItsOriginalBody() throws Exception {
        bothBackends(countingCycle(new String[][]{{"a", "b"}, {"b", "a"}}), p -> list(list(target(p, "a"), target(p, "b"))), (program, backend) -> {
            var original = target(program, "a"); var driver = new CloneCaller(original); var cloned = driver.cloneTarget();
            assertNotSame(original, cloned); assertTrue(((GuestRoot) cloned.getRootNode()).isSelf(original)); assertFalse(((GuestRoot) cloned.getRootNode()).isSelf(target(program, "b")));
            long n = 4_000L, expected = n * (n + 1) / 2;
            handled(program, backend, n / 2, expected, () -> invokeClone(driver, n));
            for (int i = 0; i < 40; i++) assertEquals(36L, invokeClone(driver, 8L));
            compile(cloned); long compiled = count(program, "compiledEntries");
            handled(program, backend, n / 2, expected, () -> invokeClone(driver, n)); assertTrue(count(program, "compiledEntries") > compiled);
            assertEquals(0L, invokeClone(driver, 0));
        });
    }
    private static final class FalsePositiveCaller extends RootNode {
        @Child private DirectCallNode call;
        private final long incomingBloom; private final Metrics metrics = new Metrics(true);
        @Child private TailCallLoop fallback = new TailCallLoop(metrics);
        FalsePositiveCaller(RootCallTarget target, long incomingBloom) { super(null); call = DirectCallNode.create(target); this.incomingBloom = incomingBloom; }
        @Override public Object execute(VirtualFrame frame) {
            Object[] packet = {incomingBloom, frame.getArguments()[0], frame.getArguments()[1]};
            try { return Calls.direct(call, packet); } catch (TailCall tail) { return fallback.execute(tail); }
        }
    }
    @Test void falsePositiveAncestryStillUsesTheTrampolineWhenNoActiveRootMatches() throws Exception {
        bothBackends(countingCycle(new String[][]{{"a", "b"}, {"b", "a"}}),
            p -> list(list(target(p, "a"), target(p, "b")), list(target(p, "b"), target(p, "a"))), (program, backend) -> {
                long mask = ((GuestRoot) target(program, "b").getRootNode()).mask;
                var driver = new FalsePositiveCaller(target(program, "a"), mask); long n = 400L;
                assertEquals(n * (n + 1) / 2, Calls.target(driver.getCallTarget(), new Object[]{n, 0L}));
                assertEquals(1L, driver.metrics.getTrampolineIterations(), "A false hit must escape A, reset ancestry, and then resume locally");
                assertTrue(count(program, "selfTailReentries") > 0);
            });
    }
}
