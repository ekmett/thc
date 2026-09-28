// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.TruffleLanguage;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.junit.jupiter.api.Test;
import thc.EntryValue;
import thc.Json;
import thc.Language;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Small exact-Core controls; native SIMD call coverage uses the separate exported fixture. */
@SuppressWarnings("unchecked")
class SimdAstTransportTest {
    private Map<String, Object> m(Object... pairs) { var result = new LinkedHashMap<String, Object>(); for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]); return result; }
    private List<Object> l(Object... values) { return Arrays.asList(values); }
    private Map<String, Object> with(Map<String, Object> value, Object... pairs) { var result = new LinkedHashMap<>(value); result.putAll(m(pairs)); return result; }
    private Map<String, Object> without(Map<String, Object> value, String key) { var result = new LinkedHashMap<>(value); result.remove(key); return result; }
    private final Map<String, Object> integer = m("kind", "long", "primReps", List.of("IntRep"), "evaluated", true),
        int16 = m("kind", "long", "primReps", List.of("Int16Rep"), "evaluated", true),
        closure = m("kind", "closure", "primReps", List.of("BoxedRep (Just Lifted)"), "evaluated", true),
        vector = m("kind", "vector", "primReps", List.of("VecRep 8 Int16ElemRep"), "vector", m("lanes", 8L, "element", "Int16ElemRep"), "evaluated", true),
        unpacked = m("kind", "unknown", "aggregate", "unboxed-tuple", "primReps", Collections.nCopies(8, "Int16Rep"), "components", Collections.nCopies(8, int16), "evaluated", true),
        nested = m("kind", "unknown", "aggregate", "unboxed-tuple", "primReps", List.of("VecRep 8 Int16ElemRep", "IntRep"), "components", List.of(vector, integer), "evaluated", true);
    private List<Object> variable(String id) { return variable(id, integer); }
    private List<Object> variable(String id, Map<String, Object> rep) { return l("var", id, m("rep", rep)); }
    private List<Object> literal(long value) { return l("lit", "int", Long.toString(value), m("rep", integer)); }
    private Map<String, Object> formal(String id) { return formal(id, integer); }
    private Map<String, Object> formal(String id, Map<String, Object> rep) { return m("id", id, "name", id, "lifted", false, "rep", rep); }
    private List<Object> app(List<Object> fn, List<List<Object>> args, Map<String, Object> result) { return l("app", fn, args, Collections.nCopies(args.size(), false), false, false, m("rep", result)); }
    private List<Object> call(String id, List<List<Object>> args) { return call(id, args, integer); }
    private List<Object> call(String id, List<List<Object>> args, Map<String, Object> result) { return app(variable(id, closure), args, result); }
    private List<Object> prim(String name, List<List<Object>> args, Map<String, Object> result) { return app(l("prim", name, m("rep", closure)), args, result); }
    private List<Object> broadcast(List<Object> x) { return prim("broadcastInt16X8#", List.of(prim("intToInt16#", List.of(x), int16)), vector); }
    private List<Object> unpack(List<Object> value, List<Object> body) {
        var ids = new ArrayList<String>(); var binders = new ArrayList<Map<String, Object>>(); for (int i = 0; i < 8; i++) { ids.add("lane" + i); binders.add(formal("lane" + i, int16)); }
        return l("case", prim("unpackInt16X8#", List.of(value), unpacked), "whole", l(l("data", "T8", ids, body, m("binders", binders))), m("rep", integer, "binder", formal("whole", unpacked)));
    }
    private List<Object> lam(List<Map<String, Object>> args, List<Object> body) { return lam(args, body, integer); }
    private List<Object> lam(List<Map<String, Object>> args, List<Object> body, Map<String, Object> result) { return l("lam", args, body, m("rep", closure, "resultRep", result, "entryStrict", Collections.nCopies(args.size(), false))); }
    private Map<String, Object> binding(String id, List<Object> body) { return m("id", id, "name", id, "lifted", true, "arity", ((List<?>) body.get(1)).size(), "rep", closure, "expr", body); }
    private Map<String, Object> module() {
        var v = variable("v", vector); var first = unpack(v, prim("int16ToInt#", List.of(variable("lane0", int16)), integer));
        var nestedValue = app(l("con", "T2", 2), List.of(broadcast(variable("x")), literal(13)), nested);
        var nestedCase = l("case", nestedValue, "pair", l(l("data", "T2", l("v", "z"), call("score", List.of(variable("v", vector), variable("z"))),
            m("binders", List.of(formal("v", vector), formal("z"))))), m("rep", integer, "binder", formal("pair", nested)));
        return m("schema", 1, "ghc", "9.14.1", "instrument", true,
            "constructors", List.of(m("id", "T8", "name", "T8", "kind", "unboxed-tuple", "arity", 8), m("id", "T2", "name", "T2", "kind", "unboxed-tuple", "arity", 2)),
            "bindings", List.of(binding("identity", lam(List.of(formal("v", vector)), v, vector)),
                binding("returnVector", lam(List.of(formal("x")), broadcast(variable("x")), vector)),
                binding("score", lam(List.of(formal("v", vector), formal("z")), prim("+#", List.of(first, variable("z")), integer))),
                binding("direct", lam(List.of(formal("x")), call("score", List.of(call("identity", List.of(broadcast(variable("x"))), vector), literal(13))))),
                binding("pap", lam(List.of(formal("x")), app(call("score", List.of(broadcast(variable("x"))), closure), List.of(literal(13)), integer))),
                binding("nested", lam(List.of(formal("x")), nestedCase))));
    }
    private List<Object> vectorLet(List<Object> body) { return l("let", false, List.of(m("id", "saved", "name", "saved", "lifted", false, "rep", vector, "expr", broadcast(variable("x")))), body, m("rep", integer)); }
    private List<Object> selectHeap(List<Object> value, Map<String, Object> boxed) {
        return l("case", value, "whole", l(l("data", "Heap", l("v", "bias"), call("score", List.of(variable("v", vector), variable("bias"))),
            m("binders", List.of(formal("v", vector), formal("bias"))))), m("rep", integer, "binder", formal("whole", boxed)));
    }
    private Map<String, Object> heapModule() {
        var boxed = m("kind", "data", "primReps", List.of("BoxedRep (Just Lifted)"), "evaluated", true);
        var heap = m("id", "Heap", "name", "Heap", "kind", "boxed", "arity", 2, "fieldReps", List.of(List.of("VecRep 8 Int16ElemRep"), List.of("IntRep")),
            "fieldTypes", List.of(vector, integer), "fieldLifted", List.of(false, false), "strictFields", List.of(false, false));
        var boxedInt = m("id", "BoxedInt", "name", "BoxedInt", "kind", "boxed", "arity", 1, "fieldReps", List.of(List.of("IntRep")),
            "fieldTypes", List.of(integer), "fieldLifted", List.of(false), "strictFields", List.of(false));
        var heapDirect = app(l("con", "Heap", 2), List.of(broadcast(variable("x")), literal(13)), boxed);
        var heapPap = app(app(l("con", "Heap", 2), List.of(broadcast(variable("x"))), closure), List.of(literal(13)), boxed);
        var captured = vectorLet(app(lam(List.of(formal("bias")), call("score", List.of(variable("saved", vector), variable("bias")))), List.of(literal(13)), integer));
        var thunk = vectorLet(l("let", false, List.of(m("id", "suspended", "name", "suspended", "lifted", true, "rep", with(boxed, "evaluated", false),
            "expr", app(l("con", "BoxedInt", 1), List.of(call("score", List.of(variable("saved", vector), literal(13)))), boxed))),
            l("case", variable("suspended", with(boxed, "evaluated", false)), "whole",
                l(l("data", "BoxedInt", l("answer"), variable("answer"), m("binders", List.of(formal("answer"))))), m("rep", integer, "binder", formal("whole", boxed))), m("rep", integer)));
        var base = module(); var constructors = new ArrayList<Object>((List<?>) base.get("constructors")); constructors.add(heap); constructors.add(boxedInt);
        var bindings = new ArrayList<Object>((List<?>) base.get("bindings")); bindings.addAll(List.of(
            binding("heapDirect", lam(List.of(formal("x")), selectHeap(heapDirect, boxed))), binding("heapPap", lam(List.of(formal("x")), selectHeap(heapPap, boxed))),
            binding("captured", lam(List.of(formal("x")), captured)), binding("thunk", lam(List.of(formal("x")), thunk))));
        return with(base, "constructors", constructors, "bindings", bindings);
    }
    private record Mutation(String label, Map<String, Object> changed, String reason) {}
    @Test void publicLoadRejectsContradictoryVectorConstructorMetadata() {
        var source = heapModule(); var constructors = (List<Map<String, Object>>) source.get("constructors"); var matches = new ArrayList<Map<String, Object>>();
        for (var constructor : constructors) if (Objects.equals(constructor.get("id"), "Heap")) matches.add(constructor); assertEquals(1, matches.size()); var heap = matches.getFirst();
        var wrongKind = with(vector, "kind", "long"); var wrongRep = with(vector, "primReps", List.of("VecRep 8 Word16ElemRep"), "vector", m("lanes", 8L, "element", "Word16ElemRep"));
        var malformed = List.of(
            new Mutation("missing types", without(heap, "fieldTypes"), "Vector constructor field requires exact logical metadata"),
            new Mutation("short types", with(heap, "fieldTypes", List.of(vector)), "Constructor field type count mismatch"),
            new Mutation("missing levity", without(heap, "fieldLifted"), "Missing constructor representation metadata"),
            new Mutation("short levity", with(heap, "fieldLifted", List.of(false)), "Constructor field type count mismatch"),
            new Mutation("lifted vector", with(heap, "fieldLifted", List.of(true, false)), "Constructor field levity disagrees"),
            new Mutation("missing strictness", without(heap, "strictFields"), "Missing constructor strictness metadata"),
            new Mutation("invalid strictness", with(heap, "strictFields", List.of("false", false)), "Unknown constructor field strictness"),
            new Mutation("unevaluated vector", with(heap, "fieldTypes", List.of(with(vector, "evaluated", false), integer)), "Constructor field evaluatedness lacks a worker obligation"),
            new Mutation("wrong kind", with(heap, "fieldTypes", List.of(wrongKind, integer)), "Core Long proof lacks a supported primitive representation"),
            new Mutation("wrong representation", with(heap, "fieldTypes", List.of(wrongRep, integer)), "Constructor field type disagrees with its primitive representation"));
        assertEquals(CoreRepresentations.parse(vector), new CoreFields(heap).getVectorProofs()[0]);
        for (var mutation : malformed) {
            var failure = assertThrows(RuntimeFault.class, () -> new CoreFields(mutation.changed));
            assertTrue(Objects.toString(failure.getMessage(), "").contains(mutation.reason), "CoreFields/" + mutation.label + ": " + failure.getMessage());
        }
        for (var backend : List.of("ast", "bytecode")) {
            try (var context = Context.newBuilder("thc").allowExperimentalOptions(true).build()) {
                var request = Json.stringify(m("entry", "heapDirect", "backend", backend, "modules", List.of(source)));
                assertEquals(14L, context.eval("thc", request).execute(1L).asLong(), backend + " valid metadata");
            }
            for (var mutation : malformed) try (var context = Context.newBuilder("thc").allowExperimentalOptions(true).build()) {
                var changed = new ArrayList<Map<String, Object>>(); for (var constructor : constructors) changed.add(Objects.equals(constructor.get("id"), "Heap") ? mutation.changed : constructor);
                var request = Json.stringify(m("entry", "heapDirect", "backend", backend, "modules", List.of(with(source, "constructors", changed))));
                var failure = assertThrows(PolyglotException.class, () -> context.eval("thc", request));
                // Known-input validation can reject the constructor application before layout construction reaches CoreFields; both enforce the exact proof.
                var reasons = new ArrayList<>(List.of(mutation.reason, "Missing or conflicting exact vector argument proof"));
                if (mutation.label.equals("short levity")) reasons.add("Constructor metadata length mismatch: Heap");
                boolean matched = false; for (var reason : reasons) if (Objects.toString(failure.getMessage(), "").contains(reason)) { matched = true; break; }
                assertTrue(matched, backend + "/" + mutation.label + ": " + failure.getMessage());
            }
        }
    }
    @Test void publicHostStillRejectsVectorIngressAndResult() {
        var entries = new LinkedHashMap<String, String>(); entries.put("identity", "host argument"); entries.put("returnVector", "host result");
        for (var backend : List.of("ast", "bytecode")) for (var entry : entries.entrySet()) try (var context = Context.newBuilder("thc").allowExperimentalOptions(true).build()) {
            var request = Json.stringify(m("entry", entry.getKey(), "backend", backend, "modules", List.of(module())));
            var failure = assertThrows(PolyglotException.class, () -> context.eval("thc", request));
            assertTrue(Objects.toString(failure.getMessage(), "").contains("Unsupported Core vector boundary: " + entry.getValue()), backend + "/" + entry.getKey() + ": " + failure.getMessage());
        }
    }
    private Context compiledContext(Boolean inlining) {
        var builder = Context.newBuilder("thc").allowExperimentalOptions(true);
        if (inlining != null) builder.option("compiler.Inlining", inlining.toString());
        return builder.option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw").build();
    }
    private void compiledEntries(Context context, boolean bytecode, Map<String, Object> module, List<String> entries) throws Exception {
        context.initialize("thc"); context.enter();
        try {
            var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
            ExecutableProgram program = bytecode ? new BytecodeProgram(language, module) : new Program(language, module);
            for (var entry : entries) {
                var function = context.asValue(new EntryValue(program, entry, 1));
                for (long x : List.of(-32768L, -1L, 0L, 32767L)) assertEquals((long) (short) x + 13L, function.execute(x).asLong(), entry + "/" + x + " interpreted");
                assertTrue(function.invokeMember("compile").asBoolean(), entry + " first installed compilation"); var target = program.entryTarget(entry);
                for (long x : List.of(32767L, 0L, -1L, -32768L)) {
                    long before = ((Number) program.diagnostics().get("compiledEntries")).longValue();
                    assertEquals((long) (short) x + 13L, function.execute(x).asLong(), entry + "/" + x + " compiled");
                    assertTrue(((Number) program.diagnostics().get("compiledEntries")).longValue() > before);
                    assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target));
                    assertEquals(0, language.getHandoffState().get().getArguments().getDepth()); assertEquals(0, language.getHandoffState().get().getResults().getDepth());
                }
            }
        } finally { context.leave(); }
    }
    @Test void vectorArgumentsResultsPapAndNestedTupleKeepExactLanesAfterCompilation() throws Exception {
        for (boolean inlining : List.of(true, false)) try (var context = compiledContext(inlining)) { compiledEntries(context, false, module(), List.of("direct", "pap", "nested")); }
    }
    @Test void astHeapVectorsOwnLanesAcrossClosureThunkConstructorAndPap() throws Exception {
        try (var context = compiledContext(null)) { compiledEntries(context, false, heapModule(), List.of("heapDirect", "heapPap", "captured", "thunk")); }
    }
    @Test void bytecodeHeapConstructorFieldsAndPapRetainPrimitiveLanes() throws Exception {
        var source = heapModule(); var bindings = new ArrayList<Map<String, Object>>();
        for (var binding : (List<Map<String, Object>>) source.get("bindings")) if (!Set.of("captured", "thunk").contains(binding.get("id"))) bindings.add(binding);
        var constructorModule = with(source, "bindings", bindings);
        for (boolean inlining : List.of(true, false)) try (var context = compiledContext(inlining)) { compiledEntries(context, true, constructorModule, List.of("heapDirect", "heapPap")); }
    }
    @Test void bytecodeHeapClosureAndThunkCaptureRetainsOwnedLanes() throws Exception {
        for (boolean inlining : List.of(true, false)) try (var context = compiledContext(inlining)) { compiledEntries(context, true, heapModule(), List.of("captured", "thunk")); }
    }
}
