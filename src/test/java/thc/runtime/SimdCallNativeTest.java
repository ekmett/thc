// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.TruffleLanguage;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import thc.CoreModules;
import thc.EntryValue;
import thc.Json;
import thc.Language;
import java.io.File;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings("unchecked")
class SimdCallNativeTest {
    private final File root = new File(System.getProperty("thc.projectRoot")), directory = new File(root, "build/simd-calls");
    private long model(String name, long x) {
        if (name.startsWith("keepAliveThrow")) return x;
        if (name.equals("overCase")) return (short) x == 0 ? 30L : 44L;
        if (!name.equals("chainCase") && !name.equals("loopCase")) return (long) (short) x + 13L;
        long answer = 0;
        for (int lane = 0; lane < 8; lane++) {
            long seed = (short) (x + lane * 17), result;
            if (name.equals("chainCase")) result = (short) ((seed + 7) * 3 - seed);
            else { result = seed; for (int remaining = (int) ((x & 7) + 1); remaining >= 1; remaining--) result = (short) ((result + remaining) * 3); }
            answer += result * (lane + 1);
        }
        return answer;
    }
    private void valid(Object target) throws Exception { assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target)); }
    private Object at(Object value, int index) { return value instanceof List<?> list && index < list.size() ? list.get(index) : null; }
    private Object field(Object value, String key) { return value instanceof Map<?, ?> map ? map.get(key) : null; }
    private List<?> list(Object value) { return value instanceof List<?> list ? list : List.of(); }
    private List<Map<String, Object>> maps(Object value) { var result = new ArrayList<Map<String, Object>>(); for (var item : list(value)) if (item instanceof Map<?, ?> map) result.add((Map<String, Object>) map); return result; }
    private List<List<?>> nodes(Object value) {
        var result = new ArrayList<List<?>>();
        if (value instanceof List<?> list) { result.add(list); for (var item : list) result.addAll(nodes(item)); }
        else if (value instanceof Map<?, ?> map) for (var item : map.values()) result.addAll(nodes(item));
        return result;
    }
    private String binderId(Object value) { return field(value, "id") instanceof String id ? id : null; }
    private String vectorBinder(Object value) { return Objects.equals(field(field(value, "rep"), "kind"), "vector") ? binderId(value) : null; }
    private Set<String> extended(Set<String> bound, Collection<String> ids) { var result = new LinkedHashSet<>(bound); result.addAll(ids); return result; }
    private Set<String> binderIds(Object value, boolean vectors) {
        var result = new LinkedHashSet<String>(); for (var item : list(value)) { var id = vectors ? vectorBinder(item) : binderId(item); if (id != null) result.add(id); } return result;
    }
    private Set<String> freeVectorIds(Object value) { return freeVectorIds(value, Set.of()); }
    private Set<String> freeVectorIds(Object value, Set<String> bound) {
        var result = new LinkedHashSet<String>(); if (!(value instanceof List<?> node)) return result;
        var tag = at(node, 0);
        if (Objects.equals(tag, "var")) {
            if (at(node, 1) instanceof String id && !bound.contains(id) && Objects.equals(field(field(at(node, 2), "rep"), "kind"), "vector")) result.add(id);
        } else if (Objects.equals(tag, "lam")) result.addAll(freeVectorIds(at(node, 2), extended(bound, binderIds(at(node, 1), false))));
        else if (Objects.equals(tag, "let")) {
            var group = maps(at(node, 2)); var ids = binderIds(group, false);
            for (var binding : group) result.addAll(freeVectorIds(binding.get("expr"), Objects.equals(at(node, 1), true) ? extended(bound, ids) : bound));
            result.addAll(freeVectorIds(at(node, 3), extended(bound, ids)));
        } else if (Objects.equals(tag, "case")) {
            result.addAll(freeVectorIds(at(node, 1), bound));
            for (var alternative : list(at(node, 3))) if (alternative instanceof List<?> arm) {
                var ids = new LinkedHashSet<String>(); if (at(node, 2) instanceof String id) ids.add(id);
                for (var item : list(at(arm, 2))) if (item instanceof String id) ids.add(id);
                result.addAll(freeVectorIds(at(arm, 3), extended(bound, ids)));
            }
        } else if (Objects.equals(tag, "app")) {
            result.addAll(freeVectorIds(at(node, 1), bound)); for (var argument : list(at(node, 2))) result.addAll(freeVectorIds(argument, bound));
        }
        return result;
    }
    private record Captures(boolean closure, boolean thunk) {}
    private boolean intersects(Set<String> ids, Set<String> outer) { for (var id : ids) if (outer.contains(id)) return true; return false; }
    private Captures retainedVectorCaptures(Object value) { return walk(value, Set.of()); }
    private Captures walk(Object expr, Set<String> outerVectors) {
        if (!(expr instanceof List<?> current)) return new Captures(false, false);
        var tag = at(current, 0);
        if (Objects.equals(tag, "lam")) {
            var formals = maps(at(current, 1)); var body = at(current, 2);
            boolean captured = intersects(freeVectorIds(body, binderIds(formals, false)), outerVectors);
            var nested = walk(body, extended(outerVectors, binderIds(formals, true)));
            return new Captures(captured || nested.closure, nested.thunk);
        }
        if (Objects.equals(tag, "let")) {
            var group = maps(at(current, 2)); var localVectors = binderIds(group, true);
            boolean liftedCapture = false;
            for (var binding : group) if (Objects.equals(binding.get("lifted"), true) && intersects(freeVectorIds(binding.get("expr")), outerVectors)) { liftedCapture = true; break; }
            var rhs = new ArrayList<Captures>(); for (var binding : group) rhs.add(walk(binding.get("expr"), extended(outerVectors, localVectors)));
            var body = walk(at(current, 3), extended(outerVectors, localVectors));
            boolean closure = body.closure, thunk = liftedCapture || body.thunk;
            for (var found : rhs) { closure |= found.closure; thunk |= found.thunk; }
            return new Captures(closure, thunk);
        }
        if (Objects.equals(tag, "case")) {
            var scrutinee = walk(at(current, 1), outerVectors); var caseVector = vectorBinder(field(at(current, 4), "binder")); var arms = new ArrayList<Captures>();
            for (var alternative : list(at(current, 3))) if (alternative instanceof List<?> arm) {
                var ids = new LinkedHashSet<String>(); if (caseVector != null) ids.add(caseVector); ids.addAll(binderIds(field(at(arm, 4), "binders"), true));
                arms.add(walk(at(arm, 3), extended(outerVectors, ids)));
            }
            boolean closure = scrutinee.closure, thunk = scrutinee.thunk; for (var found : arms) { closure |= found.closure; thunk |= found.thunk; }
            return new Captures(closure, thunk);
        }
        if (Objects.equals(tag, "app")) {
            var parts = new ArrayList<Object>(); parts.add(at(current, 1)); parts.addAll(list(at(current, 2)));
            var found = new ArrayList<Captures>(); for (var part : parts) found.add(walk(part, outerVectors));
            boolean closure = false, thunk = false; for (var capture : found) { closure |= capture.closure; thunk |= capture.thunk; } return new Captures(closure, thunk);
        }
        return new Captures(false, false);
    }
    /** GHC may pass a lifted thunk directly instead of naming it with a let. */
    private boolean retainedLazyVectorArgument(Object value, String consumerId) {
        for (var node : nodes(value)) if (Objects.equals(at(node, 0), "case")) {
            var vector = vectorBinder(field(at(node, 4), "binder")); if (vector == null) continue;
            for (var alternative : list(at(node, 3))) for (var call : nodes(at(alternative, 3))) {
                var head = at(call, 1); var arguments = at(call, 2); var demand = field(at(call, 6), "callDemand"); var delayed = at(arguments, 1);
                var delayedList = list(delayed); var proof = field(delayedList.isEmpty() ? null : delayedList.getLast(), "rep");
                if (Objects.equals(at(call, 0), "app") && Objects.equals(at(head, 0), "var") && Objects.equals(at(head, 1), consumerId) &&
                    arguments instanceof List<?> args && args.size() == 2 && Objects.equals(at(call, 3), List.of(false, true)) &&
                    field(demand, "arity") instanceof Number arity && arity.intValue() == 2 && Objects.equals(field(demand, "strictArgs"), List.of(true, false)) &&
                    Objects.equals(field(proof, "kind"), "data") && Objects.equals(field(proof, "primReps"), List.of("BoxedRep (Just Lifted)")) &&
                    Objects.equals(field(proof, "evaluated"), false) && freeVectorIds(delayed).contains(vector)) return true;
            }
        }
        return false;
    }
    private Map<String, Object> m(Object... pairs) { var result = new LinkedHashMap<String, Object>(); for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]); return result; }
    private List<Object> l(Object... values) { return Arrays.asList(values); }
    private List<Object> exported(List<Boolean> flags, List<Boolean> strict, Map<String, Object> box, String captured) {
        var vector = m("kind", "vector", "primReps", List.of("VecRep 8 Int16ElemRep"), "evaluated", true);
        var delayed = l("case", l("var", captured, m("rep", vector)), "inner", l(l("default", null, List.of(), l("con", "I#", 1), m("binders", List.of()))), m("rep", box));
        var call = l("app", l("var", "selectBox"), l(l("var", "x"), delayed), flags, false, false, m("callDemand", m("arity", 2, "strictArgs", strict)));
        return l("case", l("var", "source"), "vector", l(l("default", null, List.of(), call, m("binders", List.of()))), m("binder", m("id", "vector", "rep", vector)));
    }
    @Test void exportedLazyVectorArgumentRequiresExactDemandAndOuterCapture() {
        var lazyBox = m("kind", "data", "primReps", List.of("BoxedRep (Just Lifted)"), "evaluated", false);
        assertTrue(retainedLazyVectorArgument(exported(List.of(false, true), List.of(true, false), lazyBox, "vector"), "selectBox"));
        assertFalse(retainedLazyVectorArgument(exported(List.of(false, false), List.of(true, false), lazyBox, "vector"), "selectBox"));
        assertFalse(retainedLazyVectorArgument(exported(List.of(false, true), List.of(true, true), lazyBox, "vector"), "selectBox"));
        var evaluated = new LinkedHashMap<>(lazyBox); evaluated.put("evaluated", true);
        assertFalse(retainedLazyVectorArgument(exported(List.of(false, true), List.of(true, false), evaluated, "vector"), "selectBox"));
        assertFalse(retainedLazyVectorArgument(exported(List.of(false, true), List.of(true, false), lazyBox, "unrelated"), "selectBox"));
    }
    private Map<String, Object> named(List<Map<String, Object>> values, String name) {
        var matches = new ArrayList<Map<String, Object>>(); for (var value : values) if (Objects.equals(value.get("id"), entryId(name))) matches.add(value);
        assertEquals(1, matches.size()); return matches.getFirst();
    }
    private static String entryId(String name) { return "main:SimdCallAudit." + name; }
    private boolean constructorPap(List<List<?>> expressions, Object partialId, Object heapId) {
        for (var call : expressions) if (Objects.equals(at(call, 0), "app") && Objects.equals(at(at(call, 1), 1), partialId)) {
            var arguments = list(at(call, 2)); var argument = arguments.size() == 1 ? arguments.getFirst() : null;
            for (var closure : nodes(argument)) if (Objects.equals(at(closure, 0), "lam") && at(closure, 1) instanceof List<?> formals && formals.size() == 1) {
                var formal = binderId(formals.getFirst()); if (formal == null) continue;
                for (var construction : nodes(at(closure, 2))) if (Objects.equals(at(construction, 0), "app") &&
                    Objects.equals(at(at(construction, 1), 0), "con") && Objects.equals(at(at(construction, 1), 1), heapId) &&
                    at(construction, 2) instanceof List<?> fields && fields.size() == 2 && !freeVectorIds(fields.get(0)).isEmpty() &&
                    Objects.equals(at(fields.get(1), 0), "var") && Objects.equals(at(fields.get(1), 1), formal)) return true;
            }
        }
        return false;
    }
    /** Inspect exported Core structure, not the Haskell source spelling. */
    private void retainedHeapCore(Map<String, Object> source) {
        var constructors = (List<Map<String, Object>>) source.get("constructors"); var heap = named(constructors, "Heap"); var heapId = heap.get("id");
        assertEquals(2L, ((Number) heap.get("arity")).longValue()); assertEquals(List.of("VecRep 8 Int16ElemRep"), ((List<?>) heap.get("fieldReps")).get(0));
        var bindings = (List<Map<String, Object>>) source.get("bindings"); var heapPapRoot = named(bindings, "heapPapCase"); var expressions = nodes(heapPapRoot.get("expr"));
        // GHC Core saturates data constructors: the source-level Heap vector PAP is a one-argument closure retaining that vector, then making Heap.
        var partial = named(bindings, "applyHeapPartial"); var partialId = partial.get("id");
        assertTrue(constructorPap(expressions, partialId, heapId) && retainedVectorCaptures(heapPapRoot.get("expr")).closure,
            "Exported Core lacks the vector-retaining constructor partial application");
        var formals = (List<?>) ((List<?>) partial.get("expr")).get(1); assertEquals(1, formals.size()); var partialFormal = binderId(formals.getFirst());
        boolean applied = false;
        for (var node : nodes(partial.get("expr"))) if (Objects.equals(at(node, 0), "app") && Objects.equals(at(at(node, 1), 0), "var") &&
            Objects.equals(at(at(node, 1), 1), partialFormal) && at(node, 2) instanceof List<?> args && args.size() == 1) { applied = true; break; }
        assertTrue(applied, "Exported Core never applies the retained constructor closure");
        var consumer = named(bindings, "consumeHeap"); boolean matched = false;
        for (var node : nodes(consumer.get("expr"))) if (Objects.equals(at(node, 0), "data") && Objects.equals(at(node, 1), heapId)) { matched = true; break; }
        assertTrue(matched, "Exported Core lacks the real vector constructor case");
        var closureRoot = named(bindings, "capturedCase"); var thunkRoot = named(bindings, "thunkCase"); var thunkConsumer = named(bindings, "selectBox");
        assertTrue(retainedVectorCaptures(closureRoot.get("expr")).closure, "Exported capturedCase lacks a free vector from an outer lexical binder");
        assertTrue(retainedVectorCaptures(thunkRoot.get("expr")).thunk || retainedLazyVectorArgument(thunkRoot.get("expr"), (String) thunkConsumer.get("id")),
            "Exported thunkCase lacks a lazy lifted argument capturing an outer vector");
    }
    private record Input(String name, long x) {}
    private record Row(String name, long x, long want) {}
    @Test void nativeVectorCallsPapAndJoinsKeepCompiledAstAndBytecodeResults() throws Exception {
        checkEntries(null);
    }
    @Test void nativeKeepAliveVectorContinuationsReturnAndThrowInCompiledAstAndBytecode() throws Exception {
        checkEntries(Set.of("keepAliveCase", "keepAliveThrowCase"));
    }
    @Test void nativeEmptySumCasesPropagateKeepAliveThrowsInCompiledAstAndBytecode() throws Exception {
        checkEntries(Set.of("keepAliveThrowSumCase"));
    }
    private void checkEntries(Set<String> wantedEntries) throws Exception {
        var manifest = (Map<String, Object>) Json.parse(Files.readString(new File(directory, "manifest.json").toPath())); assertEquals(1L, manifest.get("schema"));
        var hashes = new LinkedHashMap<>((Map<String, String>) manifest.get("inputHashes")); hashes.putAll((Map<String, String>) manifest.get("artifactHashes"));
        for (var item : hashes.entrySet()) {
            var hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(new File(root, item.getKey()).toPath())));
            assertEquals(item.getValue(), hash, "Stale SIMD call artifact " + item.getKey());
        }
        var entries = (List<String>) manifest.get("entries");
        assertEquals(Set.of("directCase", "papCase", "nestedTupleCase", "joinCase", "overCase", "heapCase", "heapPapCase", "capturedCase", "thunkCase", "chainCase", "loopCase", "keepAliveCase", "keepAliveThrowCase", "keepAliveThrowSumCase"), new LinkedHashSet<>(entries));
        var cases = new ArrayList<Input>(); for (var name : entries) for (var input : (List<Number>) manifest.get("inputs")) cases.add(new Input(name, input.longValue()));
        assertEquals(126, cases.size()); var nativeRows = (Number) manifest.get("nativeRows"); var rows = new ArrayList<Row>();
        if (nativeRows != null) for (var line : Files.readAllLines(new File(directory, "oracle.tsv").toPath())) {
            var parts = line.split("\t", -1); assertEquals(3, parts.length); rows.add(new Row(parts[0], Long.parseLong(parts[1]), Long.parseLong(parts[2])));
        } else for (var input : cases) rows.add(new Row(input.name, input.x, model(input.name, input.x)));
        var rowInputs = new ArrayList<Input>(); for (var row : rows) rowInputs.add(new Input(row.name, row.x));
        assertEquals(cases, rowInputs); if (nativeRows != null) assertEquals((long) rows.size(), nativeRows.longValue());
        for (var row : rows) assertEquals(model(row.name, row.x), row.want, (nativeRows == null ? "Model" : "Native") + " " + row.name + "/" + row.x);
        for (var stage : (List<String>) manifest.get("stages")) for (var backend : List.of("ast", "bytecode"))
            try (var context = Context.newBuilder("thc").allowExperimentalOptions(true).option("engine.BackgroundCompilation", "false")
                .option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw").build()) {
                context.initialize("thc"); context.enter();
                try {
                    var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                    var source = thc.CoreCbdFixtures.read(new File(directory, stage + "-core/SimdCallAudit.cbd").toPath()); retainedHeapCore(source);
                    for (var name : entries) {
                        if (wantedEntries == null ? name.startsWith("keepAlive") : !wantedEntries.contains(name)) continue;
                        var linked = CoreModules.reachable(source, entryId(name));
                        if (name.equals("keepAliveThrowSumCase")) assertTrue(nodes(linked).stream().anyMatch(node ->
                            Objects.equals(at(node, 0), "case") && Objects.equals(at(node, 3), List.of()) &&
                            Objects.equals(field(field(field(at(node, 4), "binder"), "rep"), "aggregate"), "unboxed-sum")),
                            "Genuine empty sum case must remain in exported Core");
                        else if (wantedEntries != null) assertTrue(nodes(linked).stream().anyMatch(node ->
                            Objects.equals(at(node, 0), "app") && Objects.equals(at(at(node, 1), 0), "prim") &&
                            Objects.equals(at(at(node, 1), 1), "keepAlive#") &&
                            Objects.equals(field(field(at(node, 6), "rep"), "kind"), "vector")), "Genuine direct-vector keepAlive# must remain in exported Core");
                        ExecutableProgram program = backend.equals("ast") ? new Program(language, linked) : new BytecodeProgram(language, linked);
                        var function = context.asValue(new EntryValue(program, entryId(name), 1)); var selected = new ArrayList<Row>(); for (var row : rows) if (row.name.equals(name)) selected.add(row);
                        for (var row : selected) assertEquals(row.want, function.execute(row.x).asLong(), stage + "/" + backend + "/" + name + "/" + row.x + " interpreted");
                        assertTrue(function.invokeMember("compile").asBoolean(), stage + "/" + backend + "/" + name + " compile"); var target = program.entryTarget(entryId(name));
                        for (var row : selected) {
                            long before = ((Number) program.diagnostics().get("compiledEntries")).longValue();
                            assertEquals(row.want, function.execute(row.x).asLong(), stage + "/" + backend + "/" + name + "/" + row.x + " compiled");
                            assertTrue(((Number) program.diagnostics().get("compiledEntries")).longValue() > before); valid(target);
                            assertEquals(0, language.getHandoffState().get().getArguments().getDepth()); assertEquals(0, language.getHandoffState().get().getResults().getDepth());
                            assertEquals(0, language.getHandoffState().get().getArguments().retainedReferences());
                            assertEquals(0, language.getHandoffState().get().getResults().retainedReferences());
                            assertNull(language.getHandoffState().get().getPending());
                        }
                    }
                } finally { context.leave(); }
            }
    }
}
