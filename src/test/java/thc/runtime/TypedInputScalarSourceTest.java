// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.NodeUtil;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import thc.EntryValue;
import thc.Language;
import static org.junit.jupiter.api.Assertions.*;

/** Legacy unknown scalar locals can generalize while exact tuple leaves stay typed. */
class TypedInputScalarSourceTest {
    private final Map<String, Object> integral = Map.of("kind", "long", "primReps", List.of("IntRep"), "evaluated", true);
    private final Map<String, Object> single = Map.of("kind", "float", "primReps", List.of("FloatRep"), "evaluated", true);
    private final Map<String, Object> real = Map.of("kind", "double", "primReps", List.of("DoubleRep"), "evaluated", true);
    private final Map<String, Object> unknown = unknownProof();
    private final Map<String, Object> closure = Map.of("kind", "closure", "primReps", List.of("BoxedRep (Just Lifted)"), "evaluated", true);
    private final Map<String, Object> pair = Map.of("kind", "unknown", "aggregate", "unboxed-tuple", "components", List.of(integral, integral),
        "primReps", List.of("IntRep", "IntRep"), "evaluated", true);
    private static Map<String, Object> unknownProof() {
        var map = new LinkedHashMap<String, Object>(); map.put("kind", "unknown"); map.put("primReps", null); map.put("evaluated", false); return map;
    }
    private List<Object> v(String id, Map<String, Object> rep) { return List.of("var", id, Map.of("rep", rep)); }
    private Map<String, Object> arg(String id, Map<String, Object> rep) { return Map.of("id", id, "name", id, "rep", rep, "lifted", rep.equals(closure)); }
    private List<Object> app(List<Object> fn, List<List<Object>> args) { return app(fn, args, integral); }
    private List<Object> app(List<Object> fn, List<List<Object>> args, Map<String, Object> rep) {
        return List.of("app", fn, args, Collections.nCopies(args.size(), false), false, false, Map.of("rep", rep));
    }
    private List<Object> n(int value) { return List.of("lit", "int", Integer.toString(value), Map.of("rep", integral)); }
    private List<Object> plus(List<Object> a, List<Object> b) { return app(List.of("prim", "+#"), List.of(a, b)); }
    private Map<String, Object> bind(String name, List<Map<String, Object>> args, List<Object> body) {
        return Map.of("id", name, "name", name, "rep", closure, "lifted", true, "expr", List.of("lam", args, body,
            Map.of("rep", closure, "resultRep", integral, "entryStrict", Collections.nCopies(args.size(), false))));
    }
    private Map<String, Object> worker(String name, Map<String, Object> rep, String conversion) {
        var scalar = conversion == null ? v("x", rep) : app(List.of("prim", conversion), List.of(v("x", rep)));
        return bind(name, List.of(arg("p", pair), arg("x", rep)), List.of("case", v("p", pair), "whole", List.of(
            List.of("data", "Pair", List.of("a", "b"), plus(plus(v("a", integral), v("b", integral)), scalar),
                Map.of("binders", List.of(arg("a", integral), arg("b", integral))))), Map.of("rep", integral, "binder", arg("whole", pair))));
    }
    private void valid(RootCallTarget target, String label) throws Exception { assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target), label); }
    private Set<RootCallTarget> active(RootCallTarget host, RootCallTarget original) {
        var result = new LinkedHashSet<RootCallTarget>();
        for (var call : NodeUtil.findAllNodeInstances(host.getRootNode(), DirectCallNode.class))
            if (call.getCallTarget() == original) result.add((RootCallTarget) call.getCurrentCallTarget());
        return result;
    }
    private record Row(String entry, Number value) {
        @Override public String toString() { return "(" + entry + ", " + value + ")"; }
    }
    private Object invoke(ExecutableProgram p, RootCallTarget host, Row row, String backend, boolean inline, String phase) {
        try { return Calls.target(host, new Object[]{p.entryValue("entry"), new Object[]{p.entryValue(row.entry), row.value}}); }
        catch (Throwable failure) { throw new AssertionError(backend + "/inline=" + inline + "/" + phase + "/" + row, failure); }
    }
    private void clear(Language language) {
        var state = language.getHandoffState().get();
        assertEquals(0, state.getArguments().getDepth()); assertEquals(0, state.getArguments().retainedReferences());
        assertEquals(0, state.getResults().getDepth()); assertEquals(0, state.getResults().retainedReferences());
    }
    @Test void unknownScalarBesideTupleSurvivesLongFloatDoubleLocalGeneralization() throws Exception {
        for (var backend : List.of("ast", "bytecode")) for (boolean inline : List.of(true, false))
            try (var context = Context.newBuilder("thc").allowExperimentalOptions(true)
                .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
                .option("engine.CompilationFailureAction", "Throw").option("compiler.Inlining", Boolean.toString(inline)).build()) {
                context.initialize("thc"); context.enter();
                try {
                    var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                    var tuple = app(List.of("con", "Pair", 2), List.of(n(1), n(2)), pair);
                    var entry = bind("entry", List.of(arg("f", closure), arg("x", unknown)), app(v("f", closure), List.of(tuple, v("x", unknown))));
                    Map<String, Object> module = Map.of("instrument", true, "constructors", List.of(
                        Map.of("id", "Pair", "name", "Pair", "kind", "unboxed-tuple", "arity", 2)), "bindings", List.of(
                        worker("long", integral, null), worker("float", single, "float2Int#"), worker("double", real, "double2Int#"), entry));
                    ExecutableProgram p = backend.equals("ast") ? new Program(language, module) : new BytecodeProgram(language, module);
                    var rows = List.of(new Row("long", Long.MAX_VALUE), new Row("float", 1.5f), new Row("double", -2.75),
                        new Row("long", Long.MIN_VALUE), new Row("float", 4097f), new Row("double", 0.0), new Row("long", 7L));
                    var host = p.hostEntryTarget(2); var original = p.entryTarget("entry");
                    for (var row : rows) {
                        assertEquals(row.value.longValue() + 3L, invoke(p, host, row, backend, inline, "interpreted"), backend + "/" + inline + "/" + row + "/interpreted");
                        clear(language);
                    }
                    assertTrue(context.asValue(new EntryValue(p, "entry", 2)).invokeMember("compile").asBoolean());
                    var observed = active(host, original); assertTrue(!observed.isEmpty());
                    for (var row : rows.reversed()) {
                        var label = backend + "/" + inline + "/" + row + "/compiled";
                        long before = (Long) p.diagnostics().get("compiledEntries");
                        assertEquals(row.value.longValue() + 3L, invoke(p, host, row, backend, inline, "compiled"), label);
                        assertTrue((Long) p.diagnostics().get("compiledEntries") > before, label);
                        valid(host, label); valid(original, label); assertEquals(observed, active(host, original), label);
                        for (var target : observed) valid(target, label);
                        clear(language);
                    }
                } finally { context.leave(); }
            }
    }
}
