// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.TruffleLanguage;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import thc.CoreModules;
import thc.Json;
import thc.Language;
import java.io.File;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings("unchecked")
class SimdVectorTest {
    private static final String PREFIX = "main:SimdInt64X2.";
    private final File root = new File(System.getProperty("thc.projectRoot"));
    private final long[] inputs = {Long.MIN_VALUE, -3000000001L, -1, 0, 1, 9000000003L, Long.MAX_VALUE};
    private Map<String, Object> module() throws Exception { return module("pre"); }
    private Map<String, Object> module(String stage) throws Exception { return thc.CoreCbdFixtures.read(new File(root, "build/simd/" + stage + "-core/SimdInt64X2.cbd").toPath()); }
    private Map<String, Object> map(Object value) { return (Map<String, Object>) value; }
    private List<Object> list(Object value) { return (List<Object>) value; }
    private Map<String, Object> m(Object... pairs) { var result = new LinkedHashMap<String, Object>(); for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]); return result; }
    private Map<String, Object> with(Map<String, Object> value, String key, Object item) { var result = new LinkedHashMap<>(value); result.put(key, item); return result; }
    private Map<String, Object> without(Map<String, Object> value, String key) { var result = new LinkedHashMap<>(value); result.remove(key); return result; }
    private List<Object> l(Object... values) { return new ArrayList<>(Arrays.asList(values)); }
    private <T> T single(List<T> values) { assertEquals(1, values.size()); return values.getFirst(); }
    private Map<String, Object> binding(Map<String, Object> module, String name) {
        var matches = new ArrayList<Map<String, Object>>(); for (var value : list(module.get("bindings"))) if (Objects.equals(map(value).get("id"), PREFIX + name)) matches.add(map(value));
        return single(matches);
    }
    private Context context() { return Context.newBuilder("thc").allowExperimentalOptions(true).option("engine.BackgroundCompilation", "false")
        .option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw").option("engine.SingleTierCompilationThreshold", "10000000").build(); }
    @FunctionalInterface private interface Action { void run(Language language) throws Exception; }
    private void withLanguage(Action action) throws Exception {
        try (var context = context()) { context.initialize("thc"); context.enter();
            try { action.run(TruffleLanguage.LanguageReference.create(Language.class).get(null)); } finally { context.leave(); } }
    }
    private ExecutableProgram program(Language language, String backend, Map<String, Object> module, String entry) {
        var linked = CoreModules.reachable(module, PREFIX + entry); return backend.equals("ast") ? new Program(language, linked) : new BytecodeProgram(language, linked);
    }
    private Map<String, Object> record(CoreRepresentation expected, Object count) {
        var vector = Objects.requireNonNull(expected.getVector());
        return m("kind", "vector", "primReps", expected.getPrimReps(), "evaluated", true, "vector", m("lanes", count, "element", vector.getElement()));
    }
    @Test void exactVectorMetadataAndDurableStorageAreDistinctFromTuples() {
        var metadata = m("kind", "vector", "primReps", List.of("VecRep 2 Int64ElemRep"), "evaluated", true, "vector", m("lanes", 2L, "element", "Int64ElemRep"));
        var proof = CoreRepresentations.parse(metadata);
        assertTrue(proof.isVector()); assertFalse(proof.isTuple()); assertFalse(proof.isLong()); assertFalse(TupleShape.compatible(proof, CoreVectors.unpacked));
        assertThrows(RuntimeFault.class, () -> CoreRepresentations.parse(without(metadata, "vector")));
        assertThrows(RuntimeFault.class, () -> CoreRepresentations.parse(with(metadata, "kind", "unknown")));
        assertThrows(RuntimeFault.class, () -> CoreRepresentations.parse(with(metadata, "primReps", List.of("Int64Rep", "Int64Rep"))));
        assertEquals(new CoreVector(4, "Int64ElemRep"), CoreRepresentations.parse(with(with(metadata, "primReps", List.of("VecRep 4 Int64ElemRep")), "vector", m("lanes", 4L, "element", "Int64ElemRep"))).getVector());
        assertThrows(UnsupportedCore.class, () -> CoreRepresentations.parse(with(with(metadata, "primReps", List.of("VecRep 3 Int64ElemRep")), "vector", m("lanes", 3L, "element", "Int64ElemRep"))));
        assertThrows(RuntimeFault.class, () -> CoreVectors.proof.refine(CoreVectors.unpacked));
        assertThrows(RuntimeFault.class, () -> CoreVectors.validate("packInt64X2#", List.of(CoreVectors.proof), CoreVectors.proof));
        assertEquals(CoreVectors.proof, CoreVectors.caseResult(List.of(CoreVectors.proof, CoreVectors.proof)));
        assertThrows(RuntimeFault.class, () -> CoreVectors.caseResult(List.of(CoreVectors.proof, CoreVectors.unpacked)));
        assertThrows(RuntimeFault.class, () -> CoreVectors.caseResult(List.of(CoreVectors.proof, CoreRepresentation.UNKNOWN)));
        // Every supported family enters through the same parser, before lane-count
        // normalization. Genuine JSON integer counts keep their existing identity.
        for (var expected : List.of(CoreVectors.proof, CoreVectors.proof32, CoreVectors.proof16, CoreVectors.proof8, CoreVectors.proofWord8,
            CoreVectors.proofWord16, CoreVectors.proofWord32, CoreVectors.proofFloat, CoreVectors.proofDouble)) {
            var vector = Objects.requireNonNull(expected.getVector());
            for (var count : List.<Object>of(vector.getLanes(), (long) vector.getLanes())) {
                assertEquals(expected, CoreRepresentations.parse(record(expected, count)));
                assertEquals(expected, CoreRepresentations.parse(Json.parse(Json.stringify(record(expected, count)))));
            }
            for (var count : l((double) vector.getLanes(), vector.getLanes() + 0.5, true, Integer.toString(vector.getLanes()), null))
                assertThrows(RuntimeFault.class, () -> CoreRepresentations.parse(record(expected, count)), vector + " lanes=" + count);
        }
    }
    @Test void vectorCallsLoadButConflictingResultAndHiddenFormalProofsFail() throws Exception {
        withLanguage(language -> {
            for (var backend : List.of("ast", "bytecode")) {
                assertNotNull(program(language, backend, module(), "branchCase"));
                var m = new LinkedHashMap<>(module()); var binding = binding(m, "vectorCase"); var expression = new ArrayList<>(list(binding.get("expr")));
                var vector = m("kind", "vector", "primReps", List.of("VecRep 2 Int64ElemRep"), "evaluated", true, "vector", m("lanes", 2L, "element", "Int64ElemRep"));
                expression.set(3, with(map(expression.get(3)), "resultRep", vector)); m.put("bindings", List.of(with(binding, "expr", expression)));
                var error = assertThrows(RuntimeFault.class, () -> program(language, backend, m, "vectorCase"));
                assertTrue(Objects.toString(error.getMessage(), "").contains("Conflicting Core vector representation proofs"), backend + ": " + error.getMessage());
                // An occurrence proof cannot smuggle a vector through an untyped formal.
                var hidden = new LinkedHashMap<>(module()); var hBinding = binding(hidden, "vectorCase"); var hExpression = list(hBinding.get("expr"));
                var hArguments = list(hExpression.get(1)); var first = map(hArguments.getFirst()); first.remove("rep");
                var outerCase = list(hExpression.get(2)); var unpack = list(outerCase.get(1));
                unpack.set(2, List.of(List.of("var", first.get("id"), m("rep", vector)))); hidden.put("bindings", List.of(hBinding));
                assertThrows(RuntimeFault.class, () -> program(language, backend, hidden, "vectorCase"));
            }
        });
    }
    private Object inline(Object value, Object vectorId, Object expression) {
        if (value instanceof List<?> values) {
            if (!values.isEmpty() && Objects.equals(values.getFirst(), "var") && values.size() > 1 && Objects.equals(values.get(1), vectorId)) return expression;
            var result = new ArrayList<Object>(); for (var item : values) result.add(inline(item, vectorId, expression)); return result;
        }
        if (value instanceof Map<?, ?> values) { var result = new LinkedHashMap<Object, Object>(); for (var entry : values.entrySet()) result.put(entry.getKey(), inline(entry.getValue(), vectorId, expression)); return result; }
        return value;
    }
    @Test void vectorJoinFormalLoadsWithoutAnEarlierVectorLet() throws Exception {
        withLanguage(language -> {
            var m = new LinkedHashMap<>(module()); var binding = binding(m, "branchCase"); var expression = new ArrayList<>(list(binding.get("expr")));
            var outer = list(expression.get(2)); var vectorBinding = map(single(list(outer.get(2))));
            // Inline only the ordinary vector let; retain GHC's genuine join and its formal.
            expression.set(2, inline(outer.get(3), vectorBinding.get("id"), vectorBinding.get("expr")));
            m.put("bindings", List.of(with(binding, "expr", expression)));
            for (var backend : List.of("ast", "bytecode")) assertNotNull(program(language, backend, m, "branchCase"));
        });
    }
    @Test void inferredVectorCaseResultRetainsItsShapeAndCannotBecomeAScalar() throws Exception {
        withLanguage(language -> {
            var m = new LinkedHashMap<>(module()); var binding = binding(m, "vectorCase"); var expression = new ArrayList<>(list(binding.get("expr")));
            var unpack = list(list(expression.get(2)).get(1)); var vector = list(single(list(unpack.get(2)))); var first = map(list(expression.get(1)).getFirst());
            var scalar = List.of("var", first.get("id"), m("rep", first.get("rep")));
            var hiddenCase = l("case", scalar, "vector-result-scrutinee", l(l("default", null, List.of(), vector)));
            expression.set(3, without(map(expression.get(3)), "resultRep"));
            var argument = l("app", List.of("prim", "+#"), l(hiddenCase, scalar), l(false, false), false, true, m("rep", first.get("rep")));
            var join = l("let", false, List.of(m("id", "vector-join", "name", "vector-join", "lifted", false, "joinValueArity", 0L, "expr", hiddenCase)), List.of("var", "vector-join"));
            for (var backend : List.of("ast", "bytecode")) {
                // The branch supplies an exact vector proof even without an outer
                // case/lambda result record. Guest returns now preserve that shape.
                expression.set(2, hiddenCase); m.put("bindings", List.of(with(binding, "expr", new ArrayList<>(expression))));
                var inferred = program(language, backend, m, "vectorCase");
                var tupleResult = ((GuestRoot) inferred.entryTarget(PREFIX + "vectorCase").getRootNode()).getTupleResult();
                var resultProof = tupleResult == null ? null : tupleResult.getProof(); assertNotNull(resultProof, backend);
                assertTrue(TupleShape.compatible(CoreVectors.proof, resultProof), backend);
                var bodies = List.of(argument, join); var messages = List.of("Unsupported Core vector boundary: argument", "Conflicting logical tuple representation proofs");
                for (int i = 0; i < bodies.size(); i++) {
                    expression.set(2, bodies.get(i)); m.put("bindings", List.of(with(binding, "expr", new ArrayList<>(expression))));
                    var error = assertThrows(RuntimeFault.class, () -> program(language, backend, m, "vectorCase"));
                    assertTrue(Objects.toString(error.getMessage(), "").startsWith(messages.get(i)), backend + ": " + error.getMessage());
                }
            }
        });
    }
    private void check(ExecutableProgram program) {
        for (long a : List.of(Long.MIN_VALUE, 123L, Long.MAX_VALUE)) {
            long b = -4097L; assertEquals(((a + a + 91) * 7) ^ ((b + a + 91) * 11),
                Calls.target(program.hostEntryTarget(2), new Object[]{program.entryValue(PREFIX + "vectorCase"), new Object[]{a, b}}));
        }
    }
    @Test void exactVectorCaseStaysLocalAndExecutesCompiled() throws Exception {
        withLanguage(language -> {
            var m = new LinkedHashMap<>(module()); var binding = binding(m, "vectorCase"); var expression = list(binding.get("expr"));
            var unpack = list(list(expression.get(2)).get(1)); var vector = list(single(list(unpack.get(2)))); var first = map(list(expression.get(1)).getFirst());
            unpack.set(2, l(l("case", l("var", first.get("id"), m("rep", first.get("rep"))), "vector-local-case",
                l(l("default", null, List.of(), vector)), m("rep", map(vector.get(6)).get("rep"))))); m.put("bindings", List.of(binding));
            for (var backend : List.of("ast", "bytecode")) {
                var program = program(language, backend, m, "vectorCase"); check(program); check(program);
                var target = program.entryTarget(PREFIX + "vectorCase"); target.getClass().getMethod("compile", boolean.class).invoke(target, true); check(program);
                assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target), backend);
            }
        });
    }
    private record Key(String entry, long a, long b) {}
    private void checkRows(ExecutableProgram program, String stage, String backend, String entry, Map<Key, Long> oracle, boolean requireCompiledEntry) {
        for (long a : inputs) for (long b : inputs) {
            long expected = entry.equals("vectorCase") ? ((a + a + 91) * 7) ^ ((b + a + 91) * 11) : ((a + b - 19) * 13) ^ ((b + b - 19) * 17);
            if (oracle != null) assertEquals(expected, oracle.get(new Key(entry, a, b)));
            long before = ((Number) program.diagnostics().get("compiledEntries")).longValue();
            var result = Calls.target(program.hostEntryTarget(2), new Object[]{program.entryValue(PREFIX + entry), new Object[]{a, b}});
            assertEquals(expected, result, stage + "/" + backend + "/" + entry + "/" + a + "/" + b);
            if (requireCompiledEntry) assertEquals(before + 1, ((Number) program.diagnostics().get("compiledEntries")).longValue(),
                stage + "/" + backend + "/" + entry + "/" + a + "/" + b + " must enter compiled code exactly once");
        }
    }
    @Test void realCoreVectorArithmeticRemainsInstalledAfterCompiledExecution() throws Exception {
        var provenance = map(Json.parse(Files.readString(new File(root, "build/simd/provenance.json").toPath())));
        var stages = (List<String>) provenance.get("stages"); assertTrue(stages.contains("pre"), "Run bin/prepare-simd-audit.py first");
        for (var artifact : (List<Map<String, String>>) provenance.get("artifacts")) {
            var bytes = Files.readAllBytes(new File(root, artifact.get("path")).toPath());
            var digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); assertEquals(artifact.get("sha256"), digest, "Stale SIMD artifact");
        }
        Map<Key, Long> oracle = provenance.get("nativeRows") == null ? null : new LinkedHashMap<>();
        if (oracle != null) for (var row : Files.readAllLines(new File(root, "build/simd/oracle.tsv").toPath())) {
            var v = row.split("\t", -1); oracle.put(new Key(v[0], Long.parseLong(v[1]), Long.parseLong(v[2])), Long.parseLong(v[3]));
        }
        for (var stage : stages) for (var backend : List.of("ast", "bytecode")) withLanguage(language -> {
            for (var entry : List.of("vectorCase", "subtractCase")) {
                var program = program(language, backend, module(stage), entry); checkRows(program, stage, backend, entry, oracle, false); checkRows(program, stage, backend, entry, oracle, false);
                var target = program.entryTarget(PREFIX + entry); target.getClass().getMethod("compile", boolean.class).invoke(target, true);
                checkRows(program, stage, backend, entry, oracle, true);
                assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target), stage + "/" + backend + "/" + entry + " after execution");
                assertEquals(0, language.getHandoffState().get().getResults().getDepth());
            }
        });
    }
}
