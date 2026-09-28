// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import jdk.incubator.vector.FloatVector;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.bytecode.Instruction;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeUtil;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import thc.CoreModules;
import thc.Json;
import thc.Language;
import java.io.File;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.UnaryOperator;
import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings("unchecked")
class SimdFloatVectorTest {
    private final File root = new File(System.getProperty("thc.projectRoot"));
    private final File directory = new File(root, "build/simd-floatx4");
    private Map<String, Object> module() throws Exception { return module("pre"); }
    private Map<String, Object> module(String stage) throws Exception {
        return (Map<String, Object>) Json.parse(Files.readString(new File(directory, stage + "-core/SimdFloatX4.json").toPath()));
    }
    private Map<String, Object> metadata() {
        return Map.of("kind", "vector", "evaluated", true, "primReps", List.of("VecRep 4 FloatElemRep"),
            "vector", Map.of("lanes", 4L, "element", "FloatElemRep"));
    }
    @FunctionalInterface private interface Action { void run(Language language) throws Exception; }
    private void withLanguage(Action action) throws Exception { withLanguage(false, action); }
    private void withLanguage(boolean inlining, Action action) throws Exception {
        try (var context = Context.newBuilder("thc").allowExperimentalOptions(true)
                .option("compiler.Inlining", Boolean.toString(inlining)).option("engine.BackgroundCompilation", "false")
                .option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw")
                .option("engine.SingleTierCompilationThreshold", "10000000").build()) {
            context.initialize("thc"); context.enter();
            try { action.run(TruffleLanguage.LanguageReference.create(Language.class).get(null)); } finally { context.leave(); }
        }
    }
    private ExecutableProgram program(Language language, String backend, Map<String, Object> input, String entry) {
        return program(language, backend, input, entry, false);
    }
    private ExecutableProgram program(Language language, String backend, Map<String, Object> input, String entry, boolean diagnostic) {
        var linked = new LinkedHashMap<>(CoreModules.reachable(input, entry));
        linked.put("instrument", true); linked.put("diagnosticUnsupported", diagnostic);
        return backend.equals("ast") ? new Program(language, linked) : new BytecodeProgram(language, linked);
    }
    private Map<String, Object> with(Map<String, Object> original, Object... changes) {
        var result = new LinkedHashMap<>(original);
        for (int i = 0; i < changes.length; i += 2) result.put((String) changes[i], changes[i + 1]);
        return result;
    }
    private List<Object> list(Object... values) { return Arrays.asList(values); }
    private void sameFloat(float expected, float actual, String label) {
        if (Float.isNaN(expected)) assertTrue(Float.isNaN(actual), label);
        else assertEquals(Float.floatToRawIntBits(expected), Float.floatToRawIntBits(actual), label);
    }
    @Test void exactShapeAndVectorBackedStorageRemainDistinctFromFloatTuple() throws Exception {
        var proof = CoreRepresentations.parse(metadata());
        assertEquals(CoreVectors.proofFloat, proof);
        assertFalse(proof.isTuple()); assertFalse(proof.isFloat());
        assertFalse(TupleShape.compatible(proof, CoreVectors.unpackedFloat));
        assertFalse(TupleShape.compatible(proof, CoreVectors.proof32));
        assertEquals(Collections.nCopies(4, "FloatRep"), CoreVectors.unpackedFloat.getPrimReps());
        boolean allFloat = true;
        for (var component : CoreVectors.unpackedFloat.getComponents()) if (!component.isFloat()) { allFloat = false; break; }
        assertTrue(allFloat);
        assertEquals(float.class, FloatVector.class.getMethod("lane", int.class).getReturnType());
        assertThrows(RuntimeFault.class, () -> proof.refine(CoreVectors.unpackedFloat));
        assertThrows(RuntimeFault.class, () -> proof.refine(CoreVectors.proof32));
        assertThrows(RuntimeFault.class, () -> CoreVectors.caseResult(List.of(proof, CoreVectors.proof32)));
        assertThrows(RuntimeFault.class, () -> { var missing = new LinkedHashMap<>(metadata()); missing.remove("vector"); CoreRepresentations.parse(missing); });
        assertThrows(RuntimeFault.class, () -> CoreRepresentations.parse(with(metadata(), "primReps", Collections.nCopies(4, "FloatRep"))));
        assertThrows(RuntimeFault.class, () -> CoreVectors.validate("timesFloatX4#", List.of(proof, CoreVectors.proof32), proof));
        assertThrows(RuntimeFault.class, () -> CoreVectors.validate("packFloatX4#", List.of(CoreVectors.unpacked32), proof));
        assertThrows(RuntimeFault.class, () -> CoreVectors.validate("packFloatX4#", CoreVectors.unpackedFloat.getComponents(), proof));
        assertThrows(RuntimeFault.class, () -> CoreVectors.validate("broadcastFloatX4#",
            List.of(new CoreRepresentation(CoreKind.UNKNOWN, true, true, List.of("FloatRep"), null, null, null, null, null)), proof));
        // FloatVector is supported; a three-lane vector still has no supported species.
        assertThrows(UnsupportedCore.class, () -> CoreRepresentations.parse(with(metadata(),
            "primReps", List.of("VecRep 3 FloatElemRep"), "vector", Map.of("lanes", 3L, "element", "FloatElemRep"))));
    }
    @Test void primitiveLanesPreserveMovementBitsAndBinary32Arithmetic() {
        float[] values = {0.0f, -0.0f, Float.MIN_VALUE, -Float.MIN_VALUE,
            Float.intBitsToFloat(3), -Float.intBitsToFloat(3), Float.MIN_NORMAL, Float.MAX_VALUE,
            Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.intBitsToFloat(0x7fc01234),
            0.5f, -1.0f, 1.0f, 16777216.0f, 3.0f};
        for (int i = 0; i < values.length; i++) for (int j = 0; j < values.length; j++) {
            float a = values[i], b = values[j];
            float[] lanes = {a, values[(i + 3) % values.length], values[(j + 7) % values.length], b};
            var packed = FloatVector.broadcast(FloatVector.SPECIES_128, lanes[0]).withLane(1, lanes[1]).withLane(2, lanes[2]).withLane(3, lanes[3]);
            var broadcast = FloatVector.broadcast(FloatVector.SPECIES_128, b);
            for (int lane = 0; lane <= 3; lane++) {
                assertEquals(Float.floatToRawIntBits(lanes[lane]), Float.floatToRawIntBits(packed.lane(lane)), "movement " + i + "/" + j + "/" + lane);
                assertEquals(Float.floatToRawIntBits(b), Float.floatToRawIntBits(broadcast.lane(lane)), "broadcast " + i + "/" + j + "/" + lane);
                sameFloat(lanes[lane] + b, packed.add(broadcast).lane(lane), "add " + i + "/" + j + "/" + lane);
                sameFloat(lanes[lane] - b, packed.sub(broadcast).lane(lane), "subtract " + i + "/" + j + "/" + lane);
                sameFloat(lanes[lane] * b, packed.mul(broadcast).lane(lane), "multiply " + i + "/" + j + "/" + lane);
            }
        }
        var product = FloatVector.broadcast(FloatVector.SPECIES_128, Float.intBitsToFloat(0x3f800001)).mul(FloatVector.broadcast(FloatVector.SPECIES_128, Float.intBitsToFloat(0x3f7ffffe)));
        sameFloat(0.0f, product.add(FloatVector.broadcast(FloatVector.SPECIES_128, -1.0f)).lane(0), "separate rounding, not FMA");
    }
    private Object rewrite(Object value, UnaryOperator<Map<String, Object>> mutate) {
        if (value instanceof Map<?, ?> raw) {
            var map = (Map<String, Object>) raw;
            if (Objects.equals(map.get("kind"), "vector")) return mutate.apply(map);
            var result = new LinkedHashMap<String, Object>();
            for (var entry : map.entrySet()) result.put(entry.getKey(), rewrite(entry.getValue(), mutate));
            return result;
        }
        if (value instanceof List<?> list) {
            var result = new ArrayList<Object>();
            for (var item : list) result.add(rewrite(item, mutate));
            return result;
        }
        return value;
    }
    @Test void bothLoadersAcceptVectorFormalsAndRejectForgedProofs() throws Exception {
        withLanguage(language -> {
            var input = module();
            List<UnaryOperator<Map<String, Object>>> mutations = List.of(
                original -> { var result = new LinkedHashMap<>(original); result.remove("vector"); return result; },
                original -> with(original, "primReps", Collections.nCopies(4, "FloatRep")),
                original -> with(original, "primReps", List.of("VecRep 4 Int32ElemRep"), "vector", Map.of("lanes", 4L, "element", "Int32ElemRep")));
            for (var backend : List.of("ast", "bytecode")) {
                assertNotNull(program(language, backend, input, "vectorArgument"));
                var unsupported = (Map<String, Object>) rewrite(input, original -> with(original,
                    "primReps", List.of("VecRep 3 FloatElemRep"), "vector", Map.of("lanes", 3L, "element", "FloatElemRep")));
                assertThrows(UnsupportedCore.class, () -> program(language, backend, unsupported, "plusCase"));
                assertTrue(!((Collection<?>) program(language, backend, unsupported, "plusCase", true).diagnostics().get("deferredUnsupported")).isEmpty());
                for (boolean diagnostic : List.of(false, true)) for (var mutation : mutations) {
                    var modified = (Map<String, Object>) rewrite(input, mutation);
                    assertThrows(RuntimeFault.class, () -> program(language, backend, modified, "plusCase", diagnostic));
                }
            }
        });
    }
    @Test void unknownLaneKindCannotHideDoubleLiteralInVectorBroadcast() throws Exception {
        withLanguage(language -> {
            var closure = Map.of("kind", "closure", "primReps", List.of("BoxedRep (Just Lifted)"), "evaluated", true);
            var word = Map.of("kind", "long", "primReps", List.of("IntRep"), "evaluated", true);
            var forged = Map.of("kind", "unknown", "primReps", List.of("FloatRep"), "evaluated", true);
            var operand = list("lit", "double", "1.0", Map.of("rep", forged));
            var vector = list("app", list("prim", "broadcastFloatX4#", Map.of("rep", closure)),
                list(operand), list(false), false, true, Map.of("rep", metadata()));
            var body = list("case", vector, "v", list(list("default", null, List.of(),
                list("lit", "int", "1", Map.of("rep", word)), Map.of("binders", List.of()))),
                Map.of("rep", word, "binder", Map.of("id", "v", "lifted", false, "rep", metadata())));
            Map<String, Object> input = Map.of("schema", 1, "ghc", "9.14.1", "constructors", List.of(), "bindings", list(
                Map.of("id", "root", "name", "root", "arity", 0, "lifted", true, "rep", closure,
                    "expr", list("lam", List.of(), body, Map.of("rep", closure, "resultRep", word)))));
            for (var backend : List.of("ast", "bytecode")) for (boolean diagnostic : List.of(false, true))
                assertThrows(RuntimeFault.class, () -> program(language, backend, input, "root", diagnostic));
        });
    }
    private Map<IntegerSimdModel.Input, Long> rows(String filename) throws Exception {
        var result = new LinkedHashMap<IntegerSimdModel.Input, Long>();
        for (var row : Files.readAllLines(new File(directory, filename).toPath())) {
            var parts = row.split("\t", -1);
            var arguments = new ArrayList<Long>();
            for (int i = 1; i < parts.length - 1; i++) arguments.add(Long.parseLong(parts[i]));
            result.put(new IntegerSimdModel.Input(parts[0], arguments), Long.parseLong(parts[parts.length - 1]));
        }
        return result;
    }
    private void checkRows(boolean compiled, List<List<Long>> cases, Map<IntegerSimdModel.Input, Long> expected,
            ExecutableProgram program, RootCallTarget host, Object closure, RootCallTarget target, HandoffState handoff,
            long argumentAllocations, long resultAllocations, String stage, String backend, String name) throws Exception {
        for (var input : cases) {
            long before = ((Number) program.diagnostics().get("compiledEntries")).longValue();
            var actual = Calls.target(host, new Object[]{closure, input.toArray()});
            var label = stage + "/" + backend + "/" + name + "/" + input;
            assertEquals(expected.get(new IntegerSimdModel.Input(name, input)), actual, label);
            // Exactly the selected root is installed. Scalar helper
            // roots remain interpreted with Truffle inlining disabled.
            if (compiled) {
                assertEquals(before + 1, ((Number) program.diagnostics().get("compiledEntries")).longValue(), label);
                assertEquals(argumentAllocations, handoff.getArguments().getAllocations(), label);
                assertEquals(resultAllocations, handoff.getResults().getAllocations(), label);
                assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target), label);
            }
            assertEquals(0, handoff.getArguments().getDepth(), label);
            assertEquals(0, handoff.getArguments().retainedReferences(), label);
            assertEquals(0, handoff.getResults().getDepth(), label);
            assertEquals(0, handoff.getResults().retainedReferences(), label);
            assertNull(handoff.getPending(), label);
        }
    }
    @Test void genuineCoreMatchesNativeAndEntersCompiledCodeForEveryInput() throws Exception {
        var provenance = (Map<String, Object>) Json.parse(Files.readString(new File(directory, "provenance.json").toPath()));
        var stages = (List<String>) provenance.get("stages");
        assertTrue(stages.contains("pre")); assertEquals(true, provenance.get("positiveAuditsAccepted"));
        var files = new ArrayList<>((List<Map<String, String>>) provenance.get("sources"));
        files.addAll((List<Map<String, String>>) provenance.get("artifacts"));
        for (var file : files) {
            var hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(new File(root, file.get("path")).toPath())));
            assertEquals(file.get("sha256"), hash, "Stale FloatX4 source/artifact: " + file.get("path"));
        }
        var expected = rows("expected.tsv");
        if (provenance.get("nativeRows") != null) {
            assertEquals(expected, rows("oracle.tsv")); assertEquals((long) expected.size(), ((Number) provenance.get("nativeRows")).longValue());
        }
        var entries = (List<Map<String, Object>>) provenance.get("entries");
        var declared = new LinkedHashSet<IntegerSimdModel.Input>();
        for (var entry : entries) for (var row : (List<List<Number>>) entry.get("cases")) {
            var input = new ArrayList<Long>(); for (var value : row) input.add(value.longValue());
            declared.add(new IntegerSimdModel.Input((String) entry.get("name"), input));
        }
        assertEquals(declared, expected.keySet());
        for (var stage : stages) for (var backend : List.of("ast", "bytecode")) withLanguage(language -> {
            for (var entry : entries) {
                var name = (String) entry.get("name"); int arity = ((Number) entry.get("arity")).intValue();
                var cases = new ArrayList<List<Long>>();
                for (var row : (List<List<Number>>) entry.get("cases")) {
                    var input = new ArrayList<Long>(); for (var value : row) input.add(value.longValue()); cases.add(input);
                }
                assertTrue(!cases.isEmpty());
                boolean correctArity = true; for (var input : cases) if (input.size() != arity) { correctArity = false; break; }
                assertTrue(correctArity);
                var program = program(language, backend, module(stage), name);
                var host = program.hostEntryTarget(arity); var closure = program.entryValue(name); var target = program.entryTarget(name);
                var handoff = language.getHandoffState().get();
                checkRows(false, cases, expected, program, host, closure, target, handoff, 0, 0, stage, backend, name);
                checkRows(false, cases, expected, program, host, closure, target, handoff, 0, 0, stage, backend, name);
                long beforeSetup = ((Number) program.diagnostics().get("compiledEntries")).longValue();
                assertEquals(0L, beforeSetup, "The full corpus ran interpreted before installation");
                var beforeCalls = target.getClass().getMethod("getCallCount").invoke(target);
                long argumentAllocations = handoff.getArguments().getAllocations();
                long resultAllocations = handoff.getResults().getAllocations();
                target.getClass().getMethod("compile", boolean.class).invoke(target, true);
                assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target), stage + "/" + backend + "/" + name + " installed");
                var runtime = Truffle.getRuntime();
                runtime.getClass().getMethod("bypassedInstalledCode", Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget")).invoke(runtime, target);
                assertEquals(beforeSetup, ((Number) program.diagnostics().get("compiledEntries")).longValue());
                assertEquals(beforeCalls, target.getClass().getMethod("getCallCount").invoke(target));
                checkRows(true, cases, expected, program, host, closure, target, handoff, argumentAllocations, resultAllocations, stage, backend, name);
                assertEquals(beforeCalls, target.getClass().getMethod("getCallCount").invoke(target), "No interpreted entry after installation");
                assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target), stage + "/" + backend + "/" + name + " after execution");
                var diagnostics = program.diagnostics();
                assertEquals(0L, ((Number) diagnostics.get("unsupportedTraps")).longValue());
                assertEquals(0L, ((Number) diagnostics.get("blackholes")).longValue());
                assertEquals(0, language.getHandoffState().get().getResults().getDepth());
            }
        });
    }
}

