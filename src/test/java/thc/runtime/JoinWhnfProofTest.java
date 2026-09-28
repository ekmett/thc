// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.nodes.NodeUtil;
import org.junit.jupiter.api.Test;
import thc.Language;
import thc.Main;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import static org.junit.jupiter.api.Assertions.*;

/** WHNF describes values actually returned, not control transfers to another join body. */
class JoinWhnfProofTest {
    private final Map<String, Object> integral = Map.of("kind", "long", "primReps", List.of("IntRep"), "evaluated", true);
    private final Map<String, Object> data = Map.of("kind", "data", "primReps", List.of("BoxedRep (Just Lifted)"), "evaluated", false);
    private final Map<String, Object> closure = Map.of("kind", "closure", "primReps", List.of("BoxedRep (Just Lifted)"), "evaluated", true);
    private List<Object> variable(String id) { return List.of("var", id); }
    private List<Object> integer(long n) { return List.of("lit", "int", Long.toString(n)); }
    private Map<String, Object> parameter(String id) { return parameter(id, integral); }
    private Map<String, Object> parameter(String id, Map<String, Object> rep) {
        return Map.of("id", id, "name", id, "lifted", rep != integral, "coercion", false, "rep", rep);
    }
    private List<Object> lambda(List<Map<String, Object>> args, List<Object> body) { return lambda(args, body, integral); }
    private List<Object> lambda(List<Map<String, Object>> args, List<Object> body, Map<String, Object> result) {
        return List.of("lam", args, body, Map.of("rep", closure, "resultRep", result));
    }
    private Map<String, Object> binding(String id, List<Object> rhs) { return binding(id, rhs, closure); }
    private Map<String, Object> binding(String id, List<Object> rhs, Map<String, Object> rep) {
        var result = new LinkedHashMap<>(parameter(id, rep)); result.put("expr", rhs); return result;
    }
    private List<Object> apply(List<Object> fn, List<List<Object>> args, Map<String, Object> result) {
        return apply(fn, args, Collections.nCopies(args.size(), false), result);
    }
    private List<Object> apply(List<Object> fn, List<List<Object>> args, List<Boolean> lifted, Map<String, Object> result) {
        return List.of("app", fn, args, lifted, false, false, Map.of("rep", result));
    }
    @SafeVarargs private final List<Object> primitive(String name, List<Object>... args) { return apply(List.of("prim", name), Arrays.asList(args), integral); }
    private List<Object> box(List<Object> n) { return List.of("app", List.of("con", "Box", 1), List.of(n), List.of(false), true, true, Map.of("rep", data)); }
    private List<Object> unbox(List<Object> value) {
        return List.of("case", value, "boxed", List.of(List.of("data", "Box", List.of("payload"), variable("payload"), Map.of("binders", List.of(parameter("payload"))))),
            Map.of("rep", integral, "binder", parameter("boxed", data)));
    }
    private Map<String, Object> join(String id, List<Map<String, Object>> parameters, List<Object> body) {
        var result = new LinkedHashMap<>(binding(id, lambda(parameters, body, data)));
        result.put("joinValueArity", parameters.size()); result.put("joinResultRep", data); return result;
    }
    private List<Object> region(Map<String, Object> join, List<Object> entry, boolean recursive) { return List.of("let", recursive, List.of(join), entry, Map.of("rep", data)); }
    private static Object call(ExecutableProgram p, String name, long input) { return Calls.target(p.hostEntryTarget(1), new Object[]{p.entryValue(name), new Object[]{input}}); }
    private static long count(ExecutableProgram p, String name) { return ((Number) p.diagnostics().get(name)).longValue(); }
    private static void compile(RootCallTarget target) throws Exception {
        var type = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget");
        type.getMethod("compile", boolean.class).invoke(target, true); assertEquals(true, type.getMethod("isValidLastTier").invoke(target));
    }
    private static <T> T single(List<T> values) {
        if (values.isEmpty()) throw new NoSuchElementException("List is empty.");
        if (values.size() != 1) throw new IllegalArgumentException("List has more than one element.");
        return values.getFirst();
    }
    @Test void recursiveDataJoinsKeepWhnfWhileLazyReturningJoinsRemainLazy() throws Exception { checkDataJoins(true); }
    @Test void nonrecursiveDataJoinsKeepWhnfWhileLazyReturningJoinsRemainLazy() throws Exception { checkDataJoins(false); }
    private void checkDataJoins(boolean recursive) throws Exception {
        List<Object> step = List.of("case", variable("remaining"), "choice", List.of(
            List.of("lit", List.of("int", "0"), List.of(), box(variable("acc"))),
            Arrays.asList("default", null, List.of(), apply(variable("loop"), List.of(primitive("-#", variable("remaining"), integer(1)), primitive("+#", variable("acc"), integer(1))), data))),
            Map.of("rep", data, "binder", parameter("choice")));
        var returningBody = recursive ? step : box(primitive("+#", variable("acc"), variable("remaining")));
        var returning = region(join("loop", List.of(parameter("remaining"), parameter("acc")), returningBody),
            apply(variable("loop"), List.of(integer(1_001), variable("input")), data), recursive);
        var lazy = region(join("identity", List.of(parameter("value", data)), variable("value")),
            apply(variable("identity"), List.of(variable("tree")), List.of(true), data), recursive);
        var unsafe = apply(List.of("con", "Box", 1), List.of(primitive("quotInt#", integer(1), variable("input"))), data);
        var lazyCall = apply(variable("lazyJoin"), List.of(unsafe), List.of(true), data);
        List<Object> ignored = List.of("let", false, List.of(binding("unused", lazyCall, data)), integer(7));
        var bindings = List.of(binding("returning", lambda(List.of(parameter("input")), returning, data)),
            binding("lazyJoin", lambda(List.of(parameter("tree", data)), lazy, data)),
            binding("ignored", lambda(List.of(parameter("input")), ignored)),
            binding("demanded", lambda(List.of(parameter("input")), unbox(lazyCall))));
        try (var context = Main.executionContext()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                for (var backend : List.of("ast", "bytecode")) {
                    Map<String, Object> module = Map.of("bindings", bindings, "instrument", true, "constructors", List.of(
                        Map.of("id", "Box", "name", "Box", "arity", 1, "kind", "boxed", "fieldReps", List.of(List.of("IntRep")), "strictFields", List.of(false), "fieldLifted", List.of(false))));
                    ExecutableProgram p = backend.equals("ast") ? new Program(language, module) : new BytecodeProgram(language, module);
                    if (p instanceof Program) {
                        var positive = single(NodeUtil.findAllNodeInstances(p.entryTarget("returning").getRootNode(), LocalJoinRegion.class));
                        assertEquals(CoreKind.DATA, positive.getRepresentation().getKind());
                        assertTrue(positive.getRepresentation().getEvaluated(), "A recursive transfer contributes no lazy return");
                        var negative = single(NodeUtil.findAllNodeInstances(p.entryTarget("lazyJoin").getRootNode(), LocalJoinRegion.class));
                        assertFalse(negative.getRepresentation().getEvaluated(), "A returned lazy formal must weaken the whole region");
                    }
                    for (int i = 0; i < 30; i++) check(p, backend, i);
                    compile(p.entryTarget("returning"));
                    for (long n : List.of(3_000_000_017L, Long.MIN_VALUE, Long.MAX_VALUE)) check(p, backend, n);
                    long evaluations = count(p, "thunkEvaluations");
                    for (int i = 0; i < 30; i++) assertEquals(7L, call(p, "ignored", 0), backend);
                    compile(p.entryTarget("ignored")); assertEquals(7L, call(p, "ignored", 0), backend);
                    assertEquals(evaluations, count(p, "thunkEvaluations"), backend + " must not force an ignored lazy join");
                    assertThrows(ArithmeticException.class, () -> call(p, "demanded", 0));
                    assertTrue(count(p, "thunkEvaluations") > evaluations, backend);
                }
            } finally { context.leave(); }
        }
    }
    private static void check(ExecutableProgram p, String backend, long n) {
        var result = (DataValue) call(p, "returning", n);
        assertEquals(n + 1_001, result.getLayout().readLong(result, 0), backend);
    }
}
