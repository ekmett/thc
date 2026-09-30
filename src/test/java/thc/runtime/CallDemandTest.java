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
import static thc.runtime.CoreCallDemands.CALL_DEMANDS_PROPERTY;

/** A saturated call may demand an operand without changing the callee's entry convention. */
class CallDemandTest {
    private static List<Object> list(Object... values) { return Arrays.asList(values); }
    private static Map<String, Object> map(Object... values) { var result = new LinkedHashMap<String, Object>(); for (int i = 0; i < values.length; i += 2) result.put((String) values[i], values[i + 1]); return result; }
    private static Map<String, Object> with(Map<String, Object> original, Object... values) { var result = new LinkedHashMap<>(original); result.putAll(map(values)); return result; }
    private final Map<String, Object> longProof = map("kind", "long", "primReps", list("IntRep"), "evaluated", true);
    private final Map<String, Object> data = map("kind", "data", "primReps", list("BoxedRep (Just Lifted)"), "evaluated", false);
    private final Map<String, Object> closure = map("kind", "closure", "primReps", list("BoxedRep (Just Lifted)"), "evaluated", false);
    private final Map<String, Object> voidProof = map("kind", "void", "primReps", list(), "evaluated", true);
    private List<Object> variable(String id) { return list("var", id); }
    private List<Object> integer(long n) { return list("lit", "int", Long.toString(n)); }
    private Map<String, Object> parameter(String id) { return parameter(id, longProof, false); }
    private Map<String, Object> parameter(String id, Map<String, Object> rep) { return parameter(id, rep, false); }
    private Map<String, Object> parameter(String id, Map<String, Object> rep, boolean coercion) { return map("id", id, "name", id, "lifted", rep != longProof && rep != voidProof, "coercion", coercion, "rep", rep); }
    private List<Object> lambda(List<Map<String, Object>> args, List<Object> body) { return lambda(args, body, longProof); }
    private List<Object> lambda(List<Map<String, Object>> args, List<Object> body, Map<String, Object> result) { return list("lam", args, body, map("rep", closure, "resultRep", result, "entryStrict", Collections.nCopies(args.size(), false))); }
    private Map<String, Object> binding(String id, List<Object> rhs) { return map("id", id, "name", id, "lifted", true, "expr", rhs); }
    private List<Object> apply(List<Object> fn, List<List<Object>> args) { return apply(fn, args, Collections.nCopies(args.size(), false)); }
    private List<Object> apply(List<Object> fn, List<List<Object>> args, List<Boolean> lifted) { return list("app", fn, args, lifted, false, false); }
    private List<Object> apply(List<Object> fn, List<List<Object>> args, List<Boolean> lifted, int arity, List<Boolean> marks) {
        var expr = new ArrayList<>(apply(fn, args, lifted)); expr.add(map("callDemand", map("arity", arity, "strictArgs", marks))); return expr;
    }
    @SafeVarargs private final List<Object> primitive(String name, List<Object>... args) { return apply(list("prim", name), Arrays.asList(args)); }
    private List<Object> box(List<Object> n) { return list("app", list("con", "Box", 1), list(n), list(false), true, true); }
    private List<Object> unbox(List<Object> value) { return unbox(value, variable("payload"), longProof); }
    private List<Object> unbox(List<Object> value, List<Object> body) { return unbox(value, body, longProof); }
    private List<Object> unbox(List<Object> value, List<Object> body, Map<String, Object> result) { return list("case", value, "boxed", list(list("data", "Box", list("payload"), body, map("binders", list(parameter("payload"))))), map("rep", result, "binder", parameter("boxed", data))); }
    private List<Object> choose(List<Object> value, List<Object> zero, List<Object> other) { return list("case", value, "choice", list(list("lit", list("int", "0"), list(), zero), list("default", null, list(), other))); }
    private List<Object> delayedBox(List<Object> n) { return choose(n, box(integer(9)), box(n)); }
    private List<Object> failingBox(List<Object> n) { return apply(list("con", "Box", 1), List.of(primitive("quotInt#", integer(1), n))); }
    private List<Object> local(List<Map<String, Object>> group, List<Object> body) { return list("let", false, group, body); }
    @Test void certifiedPartialApplicationOfShorterRetainedLambdaStaysLazyInNonrecursiveLet() throws ReflectiveOperationException {
        var later = lambda(List.of(parameter("later")), variable("later"));
        var entered = list("case", primitive("quotInt#", integer(1), integer(0)), "quotient", list(list("default", null, list(), later)), map("rep", closure));
        var head = with(binding("localHead", lambda(List.of(parameter("first")), entered, closure)), "arity", 2);
        var partial = binding("partial", list("app", variable("localHead"), list(variable("first")), list(false), true, true, map("rep", closure)));
        var bound = local(List.of(head), local(List.of(partial), integer(7))); var forced = local(List.of(head), local(List.of(partial), list("case", variable("partial"), "forced", list(list("default", null, list(), integer(7))))));
        eachBackend(List.of(binding("withoutForce", lambda(List.of(parameter("first")), bound)), binding("withForce", lambda(List.of(parameter("first")), forced))), (backend, program) -> {
            assertEquals(7L, run(program, "withoutForce", 1L), backend); assertThrows(RuntimeException.class, () -> run(program, "withForce", 1L), backend);
        });
    }
    private Map<String, Object> consumer() { return binding("consumer", lambda(List.of(parameter("tree", data), parameter("extra")), unbox(variable("tree"), primitive("+#", variable("payload"), variable("extra"))))); }
    private long count(ExecutableProgram p, String name) { return ((Number) p.diagnostics().get(name)).longValue(); }
    private Object call(ExecutableProgram p, Object fn, Object... args) { return Calls.target(p.hostEntryTarget(args.length), new Object[]{fn, args.clone()}); }
    private Object run(ExecutableProgram p, String name, Object... args) { return call(p, p.entryValue(name), args); }
    private void compile(RootCallTarget target) throws ReflectiveOperationException { var type = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget"); type.getMethod("compile", boolean.class).invoke(target, true); type.getMethod("waitForCompilation").invoke(target); assertEquals(true, type.getMethod("isValidLastTier").invoke(target)); }
    private final List<Long> coldInputs = List.of(0L, 3_000_000_017L, -7_000_000_003L, Long.MIN_VALUE, Long.MAX_VALUE);
    @FunctionalInterface private interface Action { void run(String backend, ExecutableProgram program) throws ReflectiveOperationException; }
    private void eachBackend(List<Map<String, Object>> bindings, Action action) throws ReflectiveOperationException { eachBackend(bindings, true, action); }
    private void eachBackend(List<Map<String, Object>> bindings, Boolean callDemands, Action action) throws ReflectiveOperationException {
        String previous = System.getProperty(CALL_DEMANDS_PROPERTY);
        try {
            if (callDemands == null) System.clearProperty(CALL_DEMANDS_PROPERTY); else System.setProperty(CALL_DEMANDS_PROPERTY, callDemands.toString());
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
        } finally { if (previous == null) System.clearProperty(CALL_DEMANDS_PROPERTY); else System.setProperty(CALL_DEMANDS_PROPERTY, previous); }
    }
    @Test void callerDemandIsOptInAndDoesNotChangeAnAlreadyLoweredProgram() throws ReflectiveOperationException {
        var direct = binding("direct", lambda(List.of(parameter("input")), apply(variable("consumer"), List.of(delayedBox(variable("input")), variable("input")), List.of(true, false), 2, List.of(true, false))));
        eachBackend(List.of(consumer(), direct), null, (backend, p) -> {
            // Enabling later must not change this program's already-chosen lowering.
            System.setProperty(CALL_DEMANDS_PROPERTY, "true");
            try { for (int i = 0; i < 3; i++) assertEquals(14L, run(p, "direct", 7L), backend); assertEquals(3L, count(p, "thunkEvaluations"), backend + " absent property retains argument suspensions"); } finally { System.clearProperty(CALL_DEMANDS_PROPERTY); }
        });
    }
    private boolean anyStrict(ExecutableProgram p) { for (boolean strict : ((GuestRoot) p.entryTarget("consumer").getRootNode()).getEntryStrict()) if (strict) return true; return false; }
    @Test void ordinaryDemandEliminatesArgumentThunksWithoutChangingUnknownCallsOrFormalContracts() throws ReflectiveOperationException {
        var direct = binding("direct", lambda(List.of(parameter("input")), apply(variable("consumer"), List.of(delayedBox(variable("input")), variable("input")), List.of(true, false), 2, List.of(true, false))));
        var generic = binding("generic", lambda(List.of(parameter("fn", closure), parameter("input")), apply(variable("fn"), List.of(delayedBox(variable("input")), variable("input")), List.of(true, false))));
        eachBackend(List.of(consumer(), direct, generic), (backend, p) -> {
            assertFalse(anyStrict(p), backend); for (int i = 0; i < 12; i++) assertEquals(14L, run(p, "direct", 7L), backend); assertEquals(0L, count(p, "thunkEvaluations"), backend + " ordinary direct call needs no argument suspension");
            for (int i = 0; i < 12; i++) assertEquals(14L, run(p, "generic", p.entryValue("consumer"), 7L), backend); assertEquals(12L, count(p, "thunkEvaluations"), backend + " unknown call has no caller demand certificate");
            compile(p.entryTarget("direct")); compile(p.entryTarget("generic")); long compiled = count(p, "compiledEntries");
            for (long n : coldInputs) { long expected = (n == 0L ? 9L : n) + n, before = count(p, "thunkEvaluations"); assertEquals(expected, run(p, "direct", n), backend); assertEquals(before, count(p, "thunkEvaluations"), backend); assertEquals(expected, run(p, "generic", p.entryValue("consumer"), n), backend); assertEquals(before + 1, count(p, "thunkEvaluations"), backend); }
            assertTrue(count(p, "compiledEntries") > compiled, backend); assertFalse(anyStrict(p), backend);
        });
    }
    @Test void undersaturatedDemandKeepsThePapPrefixLazyAndSharesItAfterSaturation() throws ReflectiveOperationException {
        // A stored true bit exercises the independent runtime saturation guard.
        var partial = binding("partial", lambda(List.of(parameter("input")), apply(variable("consumer"), List.of(delayedBox(variable("input"))), List.of(true), 2, List.of(true)), closure));
        var failing = binding("failing", lambda(List.of(parameter("input")), apply(variable("consumer"), List.of(failingBox(variable("input"))), List.of(true), 2, List.of(true)), closure));
        eachBackend(List.of(consumer(), partial, failing), (backend, p) -> {
            var first = (Closure) run(p, "partial", 3_000_000_017L); var second = (Closure) run(p, "partial", -7_000_000_003L); assertEquals(0L, count(p, "thunkEvaluations"), backend);
            assertEquals(3_000_000_018L, call(p, first, 1L), backend); assertEquals(-7_000_000_001L, call(p, second, 2L), backend);
            for (int i = 0; i < 12; i++) assertEquals(3_000_000_020L, call(p, first, 3L), backend); assertEquals(2L, count(p, "thunkEvaluations"), backend + " PAP prefix is shared");
            for (int i = 0; i < 12; i++) assertTrue(run(p, "failing", 1L) instanceof Closure, backend); assertEquals(2L, count(p, "thunkEvaluations"), backend); compile(first.target); compile(p.entryTarget("failing"));
            assertEquals(3_000_000_017L + Long.MAX_VALUE, call(p, first, Long.MAX_VALUE), backend); assertEquals(2L, count(p, "thunkEvaluations"), backend); var failure = (Closure) run(p, "failing", 0L);
            assertEquals(2L, count(p, "thunkEvaluations"), backend + " making a failing PAP does not enter its argument"); assertThrows(ArithmeticException.class, () -> call(p, failure, 1L)); assertTrue(count(p, "papAllocations") > 0, backend);
        });
    }
    @Test void overapplicationDemandsOnlyTheSignaturePrefix() throws ReflectiveOperationException {
        var maker = binding("maker", lambda(List.of(parameter("tree", data)), unbox(variable("tree"), lambda(List.of(parameter("ignored", data)), variable("payload")), closure), closure));
        var entry = binding("entry", lambda(List.of(parameter("input")), apply(variable("maker"), List.of(delayedBox(variable("input")), failingBox(integer(0))), List.of(true, true), 1, List.of(true, false))));
        eachBackend(List.of(maker, entry), (backend, p) -> {
            for (int i = 0; i < 12; i++) assertEquals(7L, run(p, "entry", 7L), backend); compile(p.entryTarget("entry")); for (long n : coldInputs) assertEquals(n == 0L ? 9L : n, run(p, "entry", n), backend); assertEquals(0L, count(p, "thunkEvaluations"), backend + " ignored surplus argument remains lazy");
        });
    }
    @Test void strictVariableArgumentsForceOneSharedThunkAndKeepLazyEnclosingBindings() throws ReflectiveOperationException {
        var add = binding("add", lambda(List.of(parameter("left", data), parameter("right", data)), unbox(variable("left"), primitive("+#", variable("payload"), unbox(variable("right"))))));
        var demanded = apply(variable("add"), List.of(variable("shared"), variable("shared")), List.of(true, true), 2, List.of(true, true));
        var entry = binding("entry", lambda(List.of(parameter("input")), local(List.of(binding("shared", delayedBox(variable("input")))), demanded)));
        var unusedCall = apply(variable("add"), List.of(failingBox(integer(0)), failingBox(integer(0))), List.of(true, true), 2, List.of(true, true));
        var unused = binding("unused", lambda(List.of(parameter("input")), local(List.of(binding("ignored", unusedCall)), integer(41))));
        eachBackend(List.of(add, entry, unused), (backend, p) -> {
            for (int i = 0; i < 12; i++) assertEquals(14L, run(p, "entry", 7L), backend); assertEquals(12L, count(p, "thunkEvaluations"), backend + " one evaluation per shared binding");
            for (int i = 0; i < 12; i++) assertEquals(41L, run(p, "unused", 7L), backend); assertEquals(12L, count(p, "thunkEvaluations"), backend); compile(p.entryTarget("entry")); compile(p.entryTarget("unused"));
            for (long n : coldInputs) { long before = count(p, "thunkEvaluations"); assertEquals((n == 0L ? 9L : n) * 2, run(p, "entry", n), backend); assertEquals(before + 1, count(p, "thunkEvaluations"), backend); assertEquals(41L, run(p, "unused", n), backend); assertEquals(before + 1, count(p, "thunkEvaluations"), backend + " demand stays inside its lazy enclosing RHS"); }
        });
    }
    @Test void localJoinDemandRetainsTheCoercionSlotWithoutAddingAnEntryContract() throws ReflectiveOperationException {
        var join = with(binding("join", lambda(List.of(parameter("co", voidProof, true), parameter("tree", data), parameter("extra")), unbox(variable("tree"), primitive("+#", variable("payload"), variable("extra"))))), "joinValueArity", 3, "joinResultRep", longProof);
        var jump = apply(variable("join"), List.of(list("void"), delayedBox(variable("input")), variable("input")), List.of(false, true, false), 3, List.of(false, true, false)); var entry = binding("entry", lambda(List.of(parameter("input")), local(List.of(join), jump)));
        eachBackend(List.of(entry), (backend, p) -> {
            for (int i = 0; i < 12; i++) assertEquals(14L, run(p, "entry", 7L), backend); compile(p.entryTarget("entry")); for (long n : coldInputs) assertEquals((n == 0L ? 9L : n) + n, run(p, "entry", n), backend);
            assertEquals(0L, count(p, "thunkEvaluations"), backend + " join operands use the same demand path"); assertTrue(count(p, "localJoinTransfers") > 0, backend);
        });
    }
    @Test void demandedFunctionExpressionsAreForcedToClosureWhnf() throws ReflectiveOperationException {
        var invoke = binding("invoke", lambda(List.of(parameter("fn", closure), parameter("n")), apply(variable("fn"), List.of(variable("n")))));
        var delayedFunction = choose(variable("input"), lambda(List.of(parameter("x")), primitive("+#", variable("x"), integer(9))), lambda(List.of(parameter("x")), primitive("+#", variable("x"), variable("input"))));
        var entry = binding("entry", lambda(List.of(parameter("input")), apply(variable("invoke"), List.of(delayedFunction, variable("input")), List.of(true, false), 2, List.of(true, false))));
        eachBackend(List.of(invoke, entry), (backend, p) -> {
            for (int i = 0; i < 12; i++) assertEquals(14L, run(p, "entry", 7L), backend); compile(p.entryTarget("entry")); for (long n : coldInputs) assertEquals(n == 0L ? 9L : n + n, run(p, "entry", n), backend); assertEquals(0L, count(p, "thunkEvaluations"), backend + " demanded closure producer needs no argument suspension");
        });
    }
    @Test void malformedCallDemandFailsClosedAndMissingMetadataRemainsConservative() {
        var base = apply(variable("consumer"), List.of(variable("tree")), List.of(true));
        class Metadata { List<Object> make(Object arity, Object marks) { var result = new ArrayList<>(base); result.add(map("callDemand", map("arity", arity, "strictArgs", marks))); return result; } }
        var metadata = new Metadata(); assertArrayEquals(new boolean[]{false}, CoreCallDemands.application(base)); assertArrayEquals(new boolean[]{true}, CoreCallDemands.application(metadata.make(1, list(true))));
        assertThrows(RuntimeFault.class, () -> CoreCallDemands.lowerApplication(metadata.make(0, list(true)), false)); assertArrayEquals(new boolean[]{false}, CoreCallDemands.application(metadata.make(2, list(true))));
        var badKind = new ArrayList<>(base); badKind.add(map("callDemand", "strict"));
        for (var bad : List.of(badKind, metadata.make(-1, list(false)), metadata.make(1.5, list(true)), metadata.make(2_147_483_648L, list(false)), metadata.make(1, list()), metadata.make(1, list("strict")), metadata.make(0, list(true)))) assertThrows(RuntimeFault.class, () -> CoreCallDemands.application(bad));
    }
}
