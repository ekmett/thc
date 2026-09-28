// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.Truffle;
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
class SimdInt32VectorTest {
    private final File root = new File(System.getProperty("thc.projectRoot"));
    private final long[] inputs = {Long.MIN_VALUE, -2147483649L, -2147483648L, -1, 0, 1, 2147483647L, 2147483648L, Long.MAX_VALUE};
    private Map<String, Object> module() throws Exception { return module("pre"); }
    private Map<String, Object> module(String stage) throws Exception {
        return (Map<String, Object>) Json.parse(Files.readString(new File(root, "build/simd-int32x4/" + stage + "-core/SimdInt32X4.json").toPath()));
    }
    @FunctionalInterface private interface Action { void run(Language language) throws Exception; }
    private void withLanguage(Action action) throws Exception {
        try (var context = Context.newBuilder("thc").allowExperimentalOptions(true)
                .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
                .option("engine.CompilationFailureAction", "Throw").option("engine.SingleTierCompilationThreshold", "10000000").build()) {
            context.initialize("thc"); context.enter();
            try { action.run(TruffleLanguage.LanguageReference.create(Language.class).get(null)); }
            finally { context.leave(); }
        }
    }
    private ExecutableProgram program(Language language, String backend, Map<String, Object> module, String entry) {
        var linked = CoreModules.reachable(module, entry);
        return backend.equals("ast") ? new Program(language, linked) : new BytecodeProgram(language, linked);
    }

    @Test void exactInt32VectorIdentityAndDenseDurableStorage() {
        Map<String, Object> metadata = Map.of("kind", "vector", "primReps", List.of("VecRep 4 Int32ElemRep"), "evaluated", true,
            "vector", Map.of("lanes", 4L, "element", "Int32ElemRep"));
        var proof = CoreRepresentations.INSTANCE.parse(metadata);
        assertEquals(CoreVectors.INSTANCE.getProof32(), proof);
        assertFalse(proof.isTuple()); assertFalse(proof.isLong());
        assertFalse(TupleShape.compatible(proof, CoreVectors.INSTANCE.getUnpacked32()));
        assertThrows(RuntimeFault.class, () -> proof.refine(CoreVectors.INSTANCE.getProof()));
        assertThrows(RuntimeFault.class, () -> CoreVectors.INSTANCE.caseResult(List.of(proof, CoreVectors.INSTANCE.getProof())));
        assertThrows(RuntimeFault.class, () -> CoreVectors.INSTANCE.validate("plusInt32X4#", List.of(proof, CoreVectors.INSTANCE.getProof()), proof));
        assertThrows(RuntimeFault.class, () -> CoreVectors.INSTANCE.validate("packInt32X4#", List.of(CoreVectors.INSTANCE.getUnpacked()), proof));
        var missing = new LinkedHashMap<>(metadata); missing.remove("vector");
        assertThrows(RuntimeFault.class, () -> CoreRepresentations.INSTANCE.parse(missing));
        // IntVector is supported; a three-lane vector still has no supported species.
        var unsupported = new LinkedHashMap<>(metadata);
        unsupported.put("primReps", List.of("VecRep 3 Int32ElemRep"));
        unsupported.put("vector", Map.of("lanes", 3L, "element", "Int32ElemRep"));
        assertThrows(UnsupportedCore.class, () -> CoreRepresentations.INSTANCE.parse(unsupported));
    }

    private Object inline(Object value, Map<String, Object> vectorBinding) {
        if (value instanceof List<?> list) {
            if (!list.isEmpty() && Objects.equals(list.getFirst(), "var") &&
                    Objects.equals(list.size() > 1 ? list.get(1) : null, vectorBinding.get("id"))) return vectorBinding.get("expr");
            var result = new ArrayList<Object>();
            for (var element : list) result.add(inline(element, vectorBinding));
            return result;
        }
        if (value instanceof Map<?, ?> map) {
            var result = new LinkedHashMap<Object, Object>();
            for (var entry : map.entrySet()) result.put(entry.getKey(), inline(entry.getValue(), vectorBinding));
            return result;
        }
        return value;
    }

    @Test void vectorFormalJoinLoadsWithExactLaneProof() throws Exception {
        withLanguage(language -> {
            var m = new LinkedHashMap<>(module());
            var matches = new ArrayList<Map<String, Object>>();
            for (var candidate : (List<Map<String, Object>>) m.get("bindings"))
                if (Objects.equals(candidate.get("name"), "branchCase")) matches.add(candidate);
            assertEquals(1, matches.size());
            var binding = matches.getFirst();
            var expression = new ArrayList<>((List<Object>) binding.get("expr"));
            var outer = (List<Object>) expression.get(2);
            var vectorBindings = (List<Map<String, Object>>) outer.get(2);
            assertEquals(1, vectorBindings.size());
            expression.set(2, inline(outer.get(3), vectorBindings.getFirst()));
            var replacement = new LinkedHashMap<>(binding); replacement.put("expr", expression);
            m.put("bindings", List.of(replacement));
            for (var backend : List.of("ast", "bytecode")) {
                assertNotNull(program(language, backend, module(), "branchCase"));
                assertNotNull(program(language, backend, m, "branchCase"));
            }
        });
    }

    private record Input(String entry, List<Long> values) {}
    private void checkRows(ExecutableProgram program, HandoffState handoff, Map<Input, Long> oracle,
            String stage, String backend, String entry, boolean compiled, long argumentAllocations, long resultAllocations) {
        for (int i = 0; i < inputs.length; i++) for (int j = 0; j < inputs.length; j++) {
            long a = inputs[i], b = inputs[j];
            var values = List.of(a, b, inputs[(i + 3*j) % 9], inputs[(3*i + j + 1) % 9]);
            long bias = entry.equals("vectorCase") ? a + 91 : b - 19;
            var weights = entry.equals("vectorCase") ? List.of(7, 11, 13, 17) : List.of(13, 17, 19, 23);
            long expected = 0;
            for (int k = 0; k < values.size(); k++) expected ^= (long) (int) (values.get(k) + bias) * weights.get(k);
            if (oracle != null) assertEquals(expected, oracle.get(new Input(entry, values)));
            long before = ((Number) program.diagnostics().get("compiledEntries")).longValue();
            var result = Calls.target(program.hostEntryTarget(4), new Object[]{program.entryValue(entry), values.toArray()});
            var label = stage + "/" + backend + "/" + entry + "/" + values;
            assertEquals(expected, result, label);
            if (compiled) {
                assertEquals(before + 1, ((Number) program.diagnostics().get("compiledEntries")).longValue(), label);
                assertEquals(argumentAllocations, handoff.getArguments().getAllocations(), label);
                assertEquals(resultAllocations, handoff.getResults().getAllocations(), label);
            }
            assertEquals(0, handoff.getArguments().getDepth(), label);
            assertEquals(0, handoff.getArguments().retainedReferences(), label);
            assertEquals(0, handoff.getResults().getDepth(), label);
            assertEquals(0, handoff.getResults().retainedReferences(), label);
            assertNull(handoff.getPending(), label);
        }
    }

    @Test void realInt32CorePreservesSignedLanesAndEntersCompiledCodeForEveryRow() throws Exception {
        var provenance = (Map<String, Object>) Json.parse(Files.readString(new File(root, "build/simd-int32x4/provenance.json").toPath()));
        var stages = (List<String>) provenance.get("stages");
        assertTrue(stages.contains("pre"));
        for (var artifact : (List<Map<String, String>>) provenance.get("artifacts")) {
            var hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(new File(root, artifact.get("path")).toPath())));
            assertEquals(artifact.get("sha256"), hash, "Stale Int32X4 artifact");
        }
        Map<Input, Long> oracle = provenance.get("nativeRows") == null ? null : new LinkedHashMap<>();
        if (oracle != null) for (var row : Files.readAllLines(new File(root, "build/simd-int32x4/oracle.tsv").toPath())) {
            var values = row.split("\t", -1);
            var arguments = new ArrayList<Long>();
            for (int i = 1; i < 5; i++) arguments.add(Long.parseLong(values[i]));
            oracle.put(new Input(values[0], arguments), Long.parseLong(values[5]));
        }
        for (var stage : stages) for (var backend : List.of("ast", "bytecode")) withLanguage(language -> {
            for (var entry : List.of("vectorCase", "subtractCase")) {
                var program = program(language, backend, module(stage), entry);
                var handoff = language.getHandoffState().get();
                checkRows(program, handoff, oracle, stage, backend, entry, false, 0, 0);
                checkRows(program, handoff, oracle, stage, backend, entry, false, 0, 0);
                var target = program.entryTarget(entry);
                long beforeSetup = ((Number) program.diagnostics().get("compiledEntries")).longValue();
                assertEquals(0L, beforeSetup, "The full corpus ran interpreted before installation");
                var beforeCalls = target.getClass().getMethod("getCallCount").invoke(target);
                long argumentAllocations = handoff.getArguments().getAllocations();
                long resultAllocations = handoff.getResults().getAllocations();
                target.getClass().getMethod("compile", boolean.class).invoke(target, true);
                assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target), stage + "/" + backend + "/" + entry + " installed");
                var runtime = Truffle.getRuntime();
                runtime.getClass().getMethod("bypassedInstalledCode", Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget")).invoke(runtime, target);
                assertEquals(beforeSetup, ((Number) program.diagnostics().get("compiledEntries")).longValue());
                assertEquals(beforeCalls, target.getClass().getMethod("getCallCount").invoke(target));
                checkRows(program, handoff, oracle, stage, backend, entry, true, argumentAllocations, resultAllocations);
                assertEquals(beforeCalls, target.getClass().getMethod("getCallCount").invoke(target), "No interpreted entry after installation");
                assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target), stage + "/" + backend + "/" + entry + " after execution");
                assertEquals(0, language.getHandoffState().get().getResults().getDepth());
            }
        });
    }
}
