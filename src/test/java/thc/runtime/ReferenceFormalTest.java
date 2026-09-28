// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;
import java.util.*;
import org.junit.jupiter.api.Test;
import thc.Language;
import thc.Main;
import static org.junit.jupiter.api.Assertions.*;
import static thc.runtime.RepresentationTestSupport.*;

class ReferenceFormalTest {
    private final Map<String, Object> wide = map("kind", "long", "primReps", list("IntRep"), "evaluated", true);
    private final Map<String, Object> data = map("kind", "data", "primReps", list("BoxedRep (Just Lifted)"), "evaluated", false);
    private final Map<String, Object> closure = map("kind", "closure", "primReps", list("BoxedRep (Just Lifted)"), "evaluated", false);
    private final Map<String, Object> address = map("kind", "address", "primReps", list("AddrRep"), "evaluated", true);
    private List<Object> variable(String id) { return list("var", id); }
    private List<Object> integer(long n) { return list("lit", "int", Long.toString(n)); }
    private Map<String, Object> parameter(String id) { return parameter(id, wide); }
    private Map<String, Object> parameter(String id, Map<String, Object> rep) { return map("id", id, "name", id, "lifted", rep == data || rep == closure, "coercion", false, "rep", rep); }
    private List<Object> lambda(List<Map<String, Object>> args, List<Object> body) { return lambda(args, body, Collections.nCopies(args.size(), false), wide); }
    private List<Object> lambda(List<Map<String, Object>> args, List<Object> body, List<Boolean> strict) { return lambda(args, body, strict, wide); }
    private List<Object> lambdaResult(List<Map<String, Object>> args, List<Object> body, Map<String, Object> result) { return lambda(args, body, Collections.nCopies(args.size(), false), result); }
    private List<Object> lambda(List<Map<String, Object>> args, List<Object> body, List<Boolean> strict, Map<String, Object> result) { return list("lam", args, body, map("rep", closure, "resultRep", result, "entryStrict", strict)); }
    private Map<String, Object> binding(String id, List<Object> rhs) { return map("id", id, "name", id, "lifted", true, "expr", rhs); }
    private List<Object> apply(List<Object> fn, List<List<Object>> args) { return apply(fn, args, Collections.nCopies(args.size(), false)); }
    private List<Object> apply(List<Object> fn, List<List<Object>> args, List<Boolean> lifted) { return list("app", fn, args, lifted, false, false); }
    @SafeVarargs private final List<Object> primitive(String name, List<Object>... args) { return apply(list("prim", name), list(args)); }
    private List<Object> box(List<Object> n) { return list("app", list("con", "Box", 1), list(n), list(false), true, true); }
    private List<Object> unbox(List<Object> value) { return list("case", value, "boxed", list(list("data", "Box", list("payload"), variable("payload"), map("binders", list(parameter("payload"))))), map("rep", wide, "binder", parameter("boxed", data))); }
    private List<Object> choose(List<Object> value, List<Object> zero, List<Object> other) { return list("case", value, "choice", list(list("lit", list("int", "0"), list(), zero), list("default", null, list(), other))); }
    private long count(ExecutableProgram p, String name) { return ((Number) p.diagnostics().get(name)).longValue(); }
    private Object call(ExecutableProgram p, String name, Object... args) { return Calls.target(p.hostEntryTarget(args.length), new Object[]{p.entryValue(name), args}); }
    private void compile(RootCallTarget target) throws Exception { var type = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget"); type.getMethod("compile", boolean.class).invoke(target, true); assertEquals(true, type.getMethod("isValidLastTier").invoke(target)); }
    private void eachBackend(List<Map<String, Object>> bindings, CheckedBiConsumer<String, ExecutableProgram> action) throws Exception {
        try (var context = Main.executionContext(false)) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                for (var backend : list("ast", "bytecode")) {
                    var module = map("bindings", bindings, "instrument", true, "constructors", list(map("id", "Box", "name", "Box", "arity", 1, "kind", "boxed", "fieldReps", list(list("IntRep")), "strictFields", list(false), "fieldLifted", list(false))));
                    ExecutableProgram selected = null;
                    for (int i = 0; i < 64; i++) if (selected == null) {
                        ExecutableProgram candidate = backend.equals("ast") ? new Program(language, module) : new BytecodeProgram(language, module);
                        var a = (GuestRoot) candidate.entryTarget("a").getRootNode(); var b = (GuestRoot) candidate.entryTarget("b").getRootNode();
                        if ((a.mask & b.mask) != b.mask) selected = candidate;
                    }
                    action.accept(backend, Objects.requireNonNull(selected));
                }
            } finally { context.leave(); }
        }
    }
    private List<Map<String, Object>> fixtures() {
        var parameters = list(parameter("n"), parameter("left", data), parameter("right", data), parameter("f", closure), parameter("g", closure), parameter("p", address), parameter("q", address));
        var flags = list(false, true, true, true, true, false, false); var strict = list(false, true, true, true, true, false, false);
        var result = primitive("+#", primitive("+#", unbox(variable("left")), primitive("*#", integer(2), unbox(variable("right")))),
            primitive("+#", apply(variable("f"), list(primitive("indexCharOffAddr#", variable("p"), integer(0)))), primitive("*#", integer(3), apply(variable("g"), list(primitive("indexCharOffAddr#", variable("q"), integer(0)))))));
        var swapped = list(primitive("-#", variable("n"), integer(1)), variable("right"), variable("left"), variable("g"), variable("f"), variable("q"), variable("p"));
        var forward = apply(variable("a"), parameters.stream().map(p -> variable((String) p.get("id"))).toList(), flags);
        var lazySelect = binding("lazy", lambda(list(parameter("tree", data), parameter("n")), choose(variable("n"), integer(7), unbox(variable("tree")))));
        return list(binding("self", lambda(parameters, choose(variable("n"), result, apply(variable("self"), swapped, flags)), strict)),
            binding("a", lambda(parameters, choose(variable("n"), result, apply(variable("b"), swapped, flags)), strict)), binding("b", lambda(parameters, forward, strict)),
            binding("box", lambdaResult(list(parameter("input")), box(variable("input")), data)),
            binding("add", lambdaResult(list(parameter("base")), lambda(list(parameter("input")), primitive("+#", variable("base"), variable("input"))), closure)), lazySelect,
            binding("strictData", lambda(list(parameter("value", data), parameter("n")), choose(variable("n"), integer(7), unbox(variable("value"))), list(true, false))),
            binding("strictClosure", lambda(list(parameter("value", closure), parameter("n")), choose(variable("n"), integer(7), apply(variable("value"), list(variable("n")))), list(true, false))),
            binding("strictAddress", lambda(list(parameter("value", address), parameter("n")), choose(variable("n"), integer(7), primitive("indexCharOffAddr#", variable("value"), integer(0))))));
    }
    private void check(ExecutableProgram p, String backend, String name, long n, Object left, Object right, Object f, Object g, ManagedAddress addressA, ManagedAddress addressB) {
        boolean swapped = n % 2 != 0L; long x = swapped ? -7_000_000_003L : 3_000_000_017L, y = swapped ? 3_000_000_017L : -7_000_000_003L;
        long first = swapped ? 2_002L + 255 : 1_001L + 65, second = swapped ? 1_001L + 65 : 2_002L + 255, bounces = count(p, "tailBounces");
        assertEquals(x + 2 * y + first + 3 * second, call(p, name, n, left, right, f, g, addressA, addressB), backend);
        if (name.equals("self")) assertEquals(bounces, count(p, "tailBounces"), backend); assertEquals(0L, count(p, "trampolineIterations"), backend);
    }
    @Test void concreteReferenceFormalsSurviveDirectAndAncestorParallelMoves() throws Exception {
        eachBackend(fixtures(), (backend, p) -> {
            var left = call(p, "box", 3_000_000_017L); var right = call(p, "box", -7_000_000_003L); var f = call(p, "add", 1_001L); var g = call(p, "add", 2_002L);
            var addressA = ManagedAddress.fromHex("41"); var addressB = ManagedAddress.fromHex("ff");
            for (var name : list("self", "a")) {
                for (int i = 0; i < 30; i++) check(p, backend, name, 8, left, right, f, g, addressA, addressB);
                compile(p.entryTarget(name)); long compiled = count(p, "compiledEntries");
                for (long n : new long[]{0L, 1L, 2L, 4_000L, 4_001L}) check(p, backend, name, n, left, right, f, g, addressA, addressB);
                assertTrue(count(p, "compiledEntries") > compiled, backend);
            }
        });
    }
    private record NamedValue(String name, Object value) {}
    @Test void checkedReferenceEntryRejectsNullButDoesNotStrictifyLazyData() throws Exception {
        eachBackend(fixtures(), (backend, p) -> {
            var boxed = call(p, "box", Long.MAX_VALUE); var fn = call(p, "add", 3_000_000_017L); var literal = ManagedAddress.fromHex("ff");
            for (var pair : list(new NamedValue("strictData", boxed), new NamedValue("strictClosure", fn), new NamedValue("strictAddress", literal))) {
                for (int i = 0; i < 30; i++) assertEquals(7L, call(p, pair.name(), pair.value(), 0L), backend);
                compile(p.entryTarget(pair.name())); assertThrows(RuntimeFault.class, () -> call(p, pair.name(), null, 0L)); assertEquals(7L, call(p, pair.name(), pair.value(), 0L), backend);
            }
            // DATA without an entry obligation must still accept a thunk.
            var bottom = new Thunk(new RootNode(null) {
                @Override public Object execute(VirtualFrame frame) { throw new RuntimeFault("captured bottom"); }
                @Override public String getName() { return "reference formal bottom"; }
            }.getCallTarget(), null);
            for (int i = 0; i < 30; i++) assertEquals(7L, call(p, "lazy", bottom, 0L), backend);
            compile(p.entryTarget("lazy")); assertEquals(0, bottom.getState(), backend); assertThrows(RuntimeFault.class, () -> call(p, "lazy", bottom, 1L)); assertEquals(3, bottom.getState(), backend);
        });
    }
}
