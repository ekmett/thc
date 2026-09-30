// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import org.junit.jupiter.api.Test;
import thc.Language;
import thc.Main;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import static org.junit.jupiter.api.Assertions.*;

/** Root reentry must uphold the same saturated contract as an ordinary call. */
class EntrySelfCallTest {
    private final Map<String, Object> integral = Map.of("kind", "long", "primReps", List.of("IntRep"), "evaluated", true);
    private final Map<String, Object> data = Map.of("kind", "data", "primReps", List.of("BoxedRep (Just Lifted)"), "evaluated", false);
    private final Map<String, Object> closure = Map.of("kind", "closure", "primReps", List.of("BoxedRep (Just Lifted)"), "evaluated", true);
    private List<Object> variable(String id) { return List.of("var", id); }
    private List<Object> integer(long n) { return List.of("lit", "int", Long.toString(n)); }
    private Map<String, Object> parameter(String id) { return parameter(id, integral); }
    private Map<String, Object> parameter(String id, Map<String, Object> rep) {
        return Map.of("id", id, "name", id, "lifted", rep != integral, "coercion", false, "rep", rep);
    }
    private List<Object> lambda(List<Map<String, Object>> args, List<Object> body) { return lambda(args, body, Collections.nCopies(args.size(), false), integral); }
    private List<Object> lambda(List<Map<String, Object>> args, List<Object> body, List<Boolean> strict) { return lambda(args, body, strict, integral); }
    private List<Object> lambda(List<Map<String, Object>> args, List<Object> body, List<Boolean> strict, Map<String, Object> result) {
        return List.of("lam", args, body, Map.of("rep", closure, "resultRep", result, "entryStrict", strict));
    }
    private Map<String, Object> binding(String id, List<Object> rhs) { return Map.of("id", id, "name", id, "lifted", true, "expr", rhs); }
    private List<Object> apply(List<Object> fn, List<List<Object>> args) { return apply(fn, args, Collections.nCopies(args.size(), false)); }
    private List<Object> apply(List<Object> fn, List<List<Object>> args, List<Boolean> lifted) { return List.of("app", fn, args, lifted, false, false); }
    @SafeVarargs private final List<Object> primitive(String name, List<Object>... args) { return apply(List.of("prim", name), Arrays.asList(args)); }
    private List<Object> box(List<Object> n) { return List.of("app", List.of("con", "Box", 1), List.of(n), List.of(false), true, true); }
    private List<Object> choose(List<Object> value, List<Object> zero, List<Object> other) {
        return List.of("case", value, "choice", List.of(List.of("lit", List.of("int", "0"), List.of(), zero), Arrays.asList("default", null, List.of(), other)));
    }
    private List<Object> caseDefault(List<Object> value, String id, List<Object> body) { return List.of("case", value, id, List.of(Arrays.asList("default", null, List.of(), body))); }
    private List<Object> delayedBox(List<Object> n) { return choose(n, box(integer(9)), box(n)); }
    private static long count(ExecutableProgram p, String name) { return ((Number) p.diagnostics().get(name)).longValue(); }
    private static Object call(ExecutableProgram p, String name, Object... args) { return Calls.target(p.hostEntryTarget(args.length), new Object[]{p.entryValue(name), args.clone()}); }
    private static void compile(RootCallTarget target) throws Exception {
        var type = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget");
        type.getMethod("compile", boolean.class).invoke(target, true); type.getMethod("waitForCompilation").invoke(target); assertEquals(true, type.getMethod("isValidLastTier").invoke(target));
    }
    private Map<String, Object> module(List<Map<String, Object>> bindings) {
        return Map.of("bindings", bindings, "instrument", true, "constructors", List.of(Map.of("id", "Box", "name", "Box", "arity", 1, "kind", "boxed",
            "fieldReps", List.of(List.of("IntRep")), "strictFields", List.of(false), "fieldLifted", List.of(false))));
    }
    @FunctionalInterface private interface Action { void run(String backend, ExecutableProgram p) throws Exception; }
    private void eachBackend(List<Map<String, Object>> bindings, List<String> collisionPath, Action action) throws Exception {
        try (var context = Main.executionContext()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                for (var backend : List.of("ast", "bytecode")) {
                    var module = module(bindings); ExecutableProgram selected = null;
                    for (int i = 0; i < 64; i++) if (selected == null) {
                        ExecutableProgram candidate = backend.equals("ast") ? new Program(language, module) : new BytecodeProgram(language, module);
                        long ancestry = 0L; boolean allFresh = true;
                        for (var name : collisionPath) {
                            long mask = ((GuestRoot) candidate.entryTarget(name).getRootNode()).mask;
                            boolean fresh = (ancestry & mask) != mask; ancestry |= mask;
                            if (!fresh) { allFresh = false; break; }
                        }
                        if (allFresh) selected = candidate;
                    }
                    if (selected == null) throw new IllegalArgumentException("Could not select collision-free roots for " + backend);
                    action.run(backend, selected);
                }
            } finally { context.leave(); }
        }
    }
    private Map<String, Object> worker(String name, List<Object> prefix) {
        var partial = apply(variable(name), List.of(prefix), List.of(true));
        var again = apply(variable("partial"), List.of(primitive("-#", variable("remaining"), integer(1))));
        return binding(name, lambda(List.of(parameter("unused", data), parameter("remaining")),
            choose(variable("remaining"), integer(42), caseDefault(partial, "partial", again)), List.of(true, false)));
    }
    private Map<String, Object> makeBox() { return binding("makeBox", lambda(List.of(parameter("input")), box(variable("input")), List.of(false), data)); }
    private static void checkDirect(ExecutableProgram p, String backend, Object initial, long n) {
        long evaluations = count(p, "thunkEvaluations"), paps = count(p, "papAllocations"), trampolines = count(p, "trampolineIterations");
        assertEquals(42L, call(p, "worker", initial, n), backend);
        assertEquals(evaluations + n, count(p, "thunkEvaluations"), backend + " must force every unused PAP prefix");
        assertEquals(paps + n, count(p, "papAllocations"), backend); assertEquals(trampolines, count(p, "trampolineIterations"), backend);
    }
    @Test void directSelfPapPrefixForcesEvenUnusedStrictFormalsBeforeReentry() throws Exception {
        var safe = worker("worker", delayedBox(variable("remaining")));
        // Unsafe construction must remain suspended during partial application,
        // then fail at saturation even though the marked parameter is unused.
        var failing = worker("failing", apply(List.of("con", "Box", 1), List.of(primitive("quotInt#", integer(1), primitive("-#", variable("remaining"), integer(1))))));
        var makeBox = makeBox();
        eachBackend(List.of(safe, failing, makeBox), List.of(), (backend, p) -> {
            var initial = call(p, "makeBox", Long.MAX_VALUE);
            for (int i = 0; i < 30; i++) checkDirect(p, backend, initial, 8L);
            compile(p.entryTarget("worker")); long compiled = count(p, "compiledEntries");
            checkDirect(p, backend, initial, 4_000L); assertTrue(count(p, "compiledEntries") > compiled, backend);
            checkDirect(p, backend, initial, 0L); assertThrows(ArithmeticException.class, () -> call(p, "failing", initial, 1L));
        });
    }
    private Map<String, Object> ancestorA(List<Object> remaining) {
        return binding("a", lambda(List.of(parameter("unused", data), parameter("remaining")),
            choose(remaining, integer(73), apply(variable("b"), List.of(primitive("-#", remaining, integer(1))))), List.of(true, false)));
    }
    private Map<String, Object> ancestorB(List<Object> remaining) {
        return binding("b", lambda(List.of(parameter("remaining")), caseDefault(variable("a"), "unknown",
            apply(variable("unknown"), List.of(delayedBox(remaining), remaining), List.of(true, false)))));
    }
    private static void checkAncestor(ExecutableProgram p, String backend, Object initial, long n) {
        long evaluations = count(p, "thunkEvaluations"), reentries = count(p, "selfTailReentries"), trampolines = count(p, "trampolineIterations");
        assertEquals(73L, call(p, "a", initial, n), backend);
        assertEquals(evaluations + n, count(p, "thunkEvaluations"), backend + " must enforce the returning packet's contract");
        assertEquals(reentries + n, count(p, "selfTailReentries"), backend); assertEquals(trampolines, count(p, "trampolineIterations"), backend);
    }
    @Test void ancestorReentryReceivesForcedArgumentsThroughUnknownCalls() throws Exception {
        var remaining = variable("remaining"); var a = ancestorA(remaining);
        // A case-bound function has no immutable entry contract in the compiler;
        // Dispatch must enforce A's contract before the ancestor bounce packet.
        var b = ancestorB(remaining); var makeBox = makeBox();
        eachBackend(List.of(a, b, makeBox), List.of("a", "b"), (backend, p) -> {
            var initial = call(p, "makeBox", Long.MIN_VALUE);
            for (int i = 0; i < 30; i++) checkAncestor(p, backend, initial, 8L);
            compile(p.entryTarget("a")); long compiled = count(p, "compiledEntries");
            checkAncestor(p, backend, initial, 4_000L); assertTrue(count(p, "compiledEntries") > compiled, backend);
            checkAncestor(p, backend, initial, 0L);
        });
    }
    @Test void asyncAncestorReentryDefersColdStrictFormalUntilCapturedPrologue() throws Exception {
        var remaining = variable("remaining"); var a = ancestorA(remaining); var b = ancestorB(remaining); var makeBox = makeBox();
        try (var context = Main.executionContext()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); var module = module(List.of(a, b, makeBox));
                BytecodeProgram program = null;
                for (int i = 0; i < 64; i++) {
                    var candidate = new BytecodeProgram(language, module, true);
                    long left = ((GuestRoot) candidate.entryTarget("a").getRootNode()).mask, right = ((GuestRoot) candidate.entryTarget("b").getRootNode()).mask;
                    if ((left & right) == 0L) { program = candidate; break; }
                }
                if (program == null) throw new NoSuchElementException("Sequence contains no element matching the predicate.");
                var initial = call(program, "makeBox", Long.MIN_VALUE);
                assertEquals(73L, call(program, "a", initial, 2L));
                assertEquals(2L, count(program, "selfTailReentries"), "Both cold returns must use the ancestor packet");
                assertEquals(2L, count(program, "thunkEvaluations"), "Each strict delayed Box is demanded once");
                assertEquals(0L, count(program, "trampolineIterations"));
            } finally { context.leave(); }
        }
    }
    @Test void asyncDirectSelfCallKeepsTheLocalLoopWithoutTailPackets() throws Exception {
        var remaining = variable("remaining");
        var loop = binding("loop", lambda(List.of(parameter("remaining")), choose(remaining, integer(73), apply(variable("loop"), List.of(primitive("-#", remaining, integer(1)))))));
        try (var context = Main.executionContext()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var program = new BytecodeProgram(language, Map.of("bindings", List.of(loop), "instrument", true), true);
                assertEquals(73L, call(program, "loop", 10L)); compile(program.entryTarget("loop"));
                assertEquals(73L, call(program, "loop", 10_000L));
                assertEquals(0L, count(program, "selfTailReentries"), "Direct self calls must use local parallel moves rather than tail packets");
                assertEquals(0L, count(program, "trampolineIterations"));
            } finally { context.leave(); }
        }
    }
}
