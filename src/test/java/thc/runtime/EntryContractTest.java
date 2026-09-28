// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import java.util.*;
import org.junit.jupiter.api.Test;
import thc.Language;
import static org.junit.jupiter.api.Assertions.*;
import static thc.Main.executionContext;

/** CBV is a saturated calling convention, not permission to force a partial application. */
class EntryContractTest {
    private static List<Object> list(Object... values) { return Arrays.asList(values); }
    private static Map<String, Object> map(Object... values) { var result = new LinkedHashMap<String, Object>(); for (int i = 0; i < values.length; i += 2) result.put((String) values[i], values[i + 1]); return result; }
    private static Map<String, Object> with(Map<String, Object> original, Object... values) { var result = new LinkedHashMap<>(original); result.putAll(map(values)); return result; }
    private final Map<String, Object> longProof = map("kind", "long", "primReps", list("IntRep"), "evaluated", true);
    private final Map<String, Object> data = map("kind", "data", "primReps", list("BoxedRep (Just Lifted)"), "evaluated", false);
    private final Map<String, Object> closure = map("kind", "closure", "primReps", list("BoxedRep (Just Lifted)"), "evaluated", false);
    private List<Object> variable(String id) { return list("var", id); }
    private List<Object> integer(long n) { return list("lit", "int", Long.toString(n)); }
    private Map<String, Object> parameter(String id) { return parameter(id, longProof); }
    private Map<String, Object> parameter(String id, Map<String, Object> rep) { return map("id", id, "name", id, "lifted", rep != longProof, "coercion", false, "rep", rep); }
    private List<Object> lambda(List<Map<String, Object>> args, List<Object> body) { return lambda(args, body, Collections.nCopies(args.size(), false), longProof); }
    private List<Object> lambda(List<Map<String, Object>> args, List<Object> body, List<Boolean> strict) { return lambda(args, body, strict, longProof); }
    private List<Object> lambda(List<Map<String, Object>> args, List<Object> body, Map<String, Object> result) { return lambda(args, body, Collections.nCopies(args.size(), false), result); }
    private List<Object> lambda(List<Map<String, Object>> args, List<Object> body, List<Boolean> strict, Map<String, Object> result) { return list("lam", args, body, map("rep", closure, "resultRep", result, "entryStrict", strict)); }
    private Map<String, Object> binding(String id, List<Object> rhs) { return map("id", id, "name", id, "lifted", true, "expr", rhs); }
    private List<Object> apply(List<Object> fn, List<List<Object>> args) { return apply(fn, args, Collections.nCopies(args.size(), false)); }
    private List<Object> apply(List<Object> fn, List<List<Object>> args, List<Boolean> lifted) { return list("app", fn, args, lifted, false, false); }
    @SafeVarargs private final List<Object> primitive(String name, List<Object>... args) { return apply(list("prim", name), Arrays.asList(args)); }
    private List<Object> box(List<Object> n) { return list("app", list("con", "Box", 1), list(n), list(false), true, true); }
    private List<Object> unbox(List<Object> value, List<Object> body) { return list("case", value, "boxed", list(list("data", "Box", list("payload"), body, map("binders", list(parameter("payload"))))), map("rep", longProof, "binder", parameter("boxed", data))); }
    private List<Object> choose(List<Object> value, List<Object> zero, List<Object> other) { return list("case", value, "choice", list(list("lit", list("int", "0"), list(), zero), list("default", null, list(), other))); }
    private List<Object> delayedBox(List<Object> value) { return choose(value, box(integer(9)), box(value)); }
    private List<Object> local(List<Map<String, Object>> group, List<Object> body) { return list("let", false, group, body); }
    private long count(ExecutableProgram p, String name) { return ((Number) p.diagnostics().get(name)).longValue(); }
    private Object call(ExecutableProgram p, Object function, Object... args) { return Calls.target(p.hostEntryTarget(args.length), new Object[]{function, args.clone()}); }
    private Object run(ExecutableProgram p, String name, Object... args) { return call(p, p.entryValue(name), args); }
    private void compile(RootCallTarget target) throws ReflectiveOperationException {
        var type = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget"); type.getMethod("compile", boolean.class).invoke(target, true); assertEquals(true, type.getMethod("isValidLastTier").invoke(target));
    }
    @FunctionalInterface private interface Action { void run(String backend, ExecutableProgram program) throws ReflectiveOperationException; }
    private void eachBackend(List<Map<String, Object>> bindings, Action action) throws ReflectiveOperationException {
        try (var context = executionContext()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                for (String backend : List.of("ast", "bytecode")) {
                    var module = map("bindings", bindings, "instrument", true, "constructors", list(map("id", "Box", "name", "Box", "arity", 1, "kind", "boxed", "fieldReps", list(list("IntRep")), "strictFields", list(false), "fieldLifted", list(false))));
                    action.run(backend, backend.equals("ast") ? new Program(language, module) : new BytecodeProgram(language, module));
                }
            } finally { context.leave(); }
        }
    }
    @Test void knownWorkerCompilesStrictOperandsDirectlyWhileUnknownCallsEnforceTheSameContract() throws ReflectiveOperationException {
        var worker = binding("worker", lambda(List.of(parameter("tree", data), parameter("extra")), unbox(variable("tree"), primitive("+#", variable("payload"), variable("extra"))), List.of(true, false)));
        var indirect = binding("indirect", lambda(List.of(parameter("fn", closure), parameter("tree", data), parameter("extra")), apply(variable("fn"), List.of(variable("tree"), variable("extra")), List.of(true, false))));
        var directEntry = binding("direct", lambda(List.of(parameter("input")), apply(variable("worker"), List.of(delayedBox(variable("input")), variable("input")), List.of(true, false))));
        var indirectEntry = binding("generic", lambda(List.of(parameter("input")), apply(variable("indirect"), List.of(variable("worker"), delayedBox(variable("input")), variable("input")), List.of(true, true, false))));
        eachBackend(List.of(worker, indirect, directEntry, indirectEntry), (backend, p) -> {
            for (int i = 0; i < 30; i++) assertEquals(14L, run(p, "direct", 7L), backend); assertEquals(0L, count(p, "thunkEvaluations"), backend + " known CBV operands need no suspension");
            for (int i = 0; i < 30; i++) assertEquals(14L, run(p, "generic", 7L), backend); assertEquals(30L, count(p, "thunkEvaluations"), backend + " unknown call forces its delayed operand");
            compile(p.entryTarget("direct")); compile(p.entryTarget("generic"));
            for (long n : new long[]{0L, 3_000_000_017L, Long.MIN_VALUE, Long.MAX_VALUE}) { long expected = (n == 0L ? 9L : n) + n; assertEquals(expected, run(p, "direct", n), backend); assertEquals(expected, run(p, "generic", n), backend); }
        });
    }
    @Test void papPrefixStaysLazyUntilSaturationAndItsThunkIsSharedAfterward() throws ReflectiveOperationException {
        var worker = binding("worker", lambda(List.of(parameter("tree", data), parameter("extra")), unbox(variable("tree"), primitive("+#", variable("payload"), variable("extra"))), List.of(true, false)));
        var partial = binding("partial", lambda(List.of(parameter("input")), apply(variable("worker"), List.of(delayedBox(variable("input"))), List.of(true)), closure));
        var failing = binding("failing", lambda(List.of(parameter("input")), apply(variable("worker"), List.of(apply(list("con", "Box", 1), List.of(primitive("quotInt#", integer(1), variable("input"))))), List.of(true)), closure));
        eachBackend(List.of(worker, partial, failing), (backend, p) -> {
            var a = (Closure) run(p, "partial", 3_000_000_017L); var b = (Closure) run(p, "partial", -7_000_000_003L); assertEquals(0L, count(p, "thunkEvaluations"), backend);
            assertEquals(3_000_000_018L, call(p, a, 1L), backend); assertEquals(-7_000_000_001L, call(p, b, 2L), backend); assertEquals(2L, count(p, "thunkEvaluations"), backend);
            for (int i = 0; i < 30; i++) assertEquals(3_000_000_020L, call(p, a, 3L), backend); compile(a.target);
            assertEquals(3_000_000_017L + Long.MAX_VALUE, call(p, a, Long.MAX_VALUE), backend); assertEquals(2L, count(p, "thunkEvaluations"), backend + " PAP prefixes retain sharing");
            // Merely constructing this unsafe-to-speculate PAP must not divide by zero.
            var failure = (Closure) run(p, "failing", 0L); assertThrows(ArithmeticException.class, () -> call(p, failure, 1L));
        });
    }
    @Test void overapplicationEnforcesOnlyTheSaturatedFirstFunctionBeforeApplyingItsResult() throws ReflectiveOperationException {
        var worker = binding("worker", lambda(List.of(parameter("tree", data)), lambda(List.of(parameter("extra")), unbox(variable("tree"), primitive("+#", variable("payload"), variable("extra")))), List.of(true), closure));
        var generic = binding("generic", lambda(List.of(parameter("fn", closure), parameter("tree", data), parameter("input")), apply(variable("fn"), List.of(variable("tree"), variable("input")), List.of(true, false))));
        var entry = binding("entry", lambda(List.of(parameter("input")), apply(variable("generic"), List.of(variable("worker"), delayedBox(variable("input")), variable("input")), List.of(true, true, false))));
        eachBackend(List.of(worker, generic, entry), (backend, p) -> {
            for (int i = 0; i < 30; i++) assertEquals(14L, run(p, "entry", 7L), backend); compile(p.entryTarget("entry"));
            for (long n : new long[]{0L, 3_000_000_017L, Long.MIN_VALUE, Long.MAX_VALUE}) assertEquals((n == 0L ? 9L : n) + n, run(p, "entry", n), backend); assertEquals(34L, count(p, "thunkEvaluations"), backend);
        });
    }
    @Test void strictJoinPrefixDoesNotStrictifyTheReturnedFunctionSuffix() throws ReflectiveOperationException {
        var joinBody = unbox(variable("tree"), primitive("+#", variable("payload"), variable("extra")));
        var join = with(binding("join", lambda(List.of(parameter("tree", data), parameter("extra")), joinBody, List.of(true, false))), "joinValueArity", 1, "joinResultRep", closure);
        var region = local(List.of(join), apply(variable("join"), List.of(delayedBox(variable("input"))), List.of(true))); var entry = binding("entry", lambda(List.of(parameter("input")), apply(region, List.of(variable("input")))));
        eachBackend(List.of(entry), (backend, p) -> {
            for (int i = 0; i < 30; i++) assertEquals(14L, run(p, "entry", 7L), backend); compile(p.entryTarget("entry")); assertEquals(9L, run(p, "entry", 0L), backend); assertEquals(0L, count(p, "thunkEvaluations"), backend); assertTrue(count(p, "localJoinTransfers") > 0, backend);
        });
    }
    @Test void malformedEntryContractsFailAtLoad() {
        var rhs = lambda(List.of(parameter("input")), variable("input"), List.of(true));
        assertThrows(RuntimeFault.class, () -> { var malformed = new ArrayList<>(rhs.subList(0, 3)); malformed.add(map("entryStrict", list("strict"))); CoreEntries.lambda(malformed); });
        assertThrows(RuntimeFault.class, () -> { var malformed = new ArrayList<>(rhs.subList(0, 3)); malformed.add(map("entryStrict", List.of())); CoreEntries.lambda(malformed); });
        assertThrows(RuntimeFault.class, () -> CoreEntries.binding(with(binding("bad", rhs), "entryStrict", list(false))));
    }
    @Test void asyncBytecodeCalleeDemandsUnusedStrictFormalsAndPapPrefixes() throws ReflectiveOperationException {
        var worker = binding("worker", lambda(List.of(parameter("ignored", data), parameter("answer")), variable("answer"), List.of(true, false)));
        var indirect = binding("indirect", lambda(List.of(parameter("fn", closure), parameter("tree", data), parameter("answer")), apply(variable("fn"), List.of(variable("tree"), variable("answer")), List.of(true, false))));
        var generic = binding("generic", lambda(List.of(parameter("input")), apply(variable("indirect"), List.of(variable("worker"), delayedBox(variable("input")), variable("input")), List.of(true, true, false))));
        var partial = binding("partial", lambda(List.of(parameter("input")), apply(variable("worker"), List.of(delayedBox(variable("input"))), List.of(true)), closure));
        try (var context = executionContext()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var module = map("bindings", list(worker, indirect, generic, partial), "instrument", true, "constructors", list(map("id", "Box", "name", "Box", "arity", 1, "kind", "boxed", "fieldReps", list(list("IntRep")), "strictFields", list(false), "fieldLifted", list(false))));
                var program = new BytecodeProgram(language, module, true); assertThrows(RuntimeFault.class, () -> run(program, "worker", 7L, 9L)); assertEquals(7L, run(program, "generic", 7L));
                assertEquals(1L, count(program, "thunkEvaluations"), "Unused strict formal must still be demanded"); var pap = (Closure) run(program, "partial", 3_000_000_017L);
                assertEquals(1L, count(program, "thunkEvaluations"), "PAP construction remains lazy"); assertEquals(5L, call(program, pap, 5L)); assertEquals(2L, count(program, "thunkEvaluations"), "Saturation demands the stored prefix once");
                assertEquals(6L, call(program, pap, 6L)); assertEquals(2L, count(program, "thunkEvaluations"), "The evaluated PAP prefix is shared"); compile(program.entryTarget("worker")); assertEquals(11L, run(program, "generic", 11L));
            } finally { context.leave(); }
        }
    }
    @Test void megamorphicCallsEnforceAndShareStrictPapPrefixes() throws ReflectiveOperationException {
        var workers = new ArrayList<Map<String, Object>>();
        for (int index = 0; index <= 3; index++) workers.add(binding("worker" + index, lambda(List.of(parameter("tree", data), parameter("extra")), unbox(variable("tree"), primitive("+#", variable("payload"), variable("extra"))), List.of(true, false))));
        for (int index = 0; index <= 3; index++) workers.add(binding("partial" + index, lambda(List.of(parameter("input")), apply(variable("worker" + index), List.of(delayedBox(variable("input"))), List.of(true)), closure)));
        eachBackend(workers, (backend, p) -> {
            var functions = new ArrayList<Closure>(); for (int i = 0; i <= 3; i++) functions.add((Closure) run(p, "partial" + i, 3_000_000_000L + i)); assertEquals(0L, count(p, "thunkEvaluations"), backend);
            for (int round = 0; round < 30; round++) for (int index = 0; index < functions.size(); index++) assertEquals(3_000_000_000L + index + round, call(p, functions.get(index), (long) round), backend);
            assertTrue(count(p, "indirectCalls") > 0, backend + " must exercise the generic saturated path"); assertEquals(4L, count(p, "thunkEvaluations"), backend); for (var fn : functions) compile(fn.target);
            for (int index = 0; index < functions.size(); index++) assertEquals(3_000_000_000L + index + Long.MAX_VALUE, call(p, functions.get(index), Long.MAX_VALUE), backend); assertEquals(4L, count(p, "thunkEvaluations"), backend);
        });
    }
}
