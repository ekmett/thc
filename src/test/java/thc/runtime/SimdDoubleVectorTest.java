// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import jdk.incubator.vector.DoubleVector;
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
class SimdDoubleVectorTest {
    private final File root = new File(System.getProperty("thc.projectRoot"));
    private final File directory = new File(root, "build/simd-doublex2");
    private Map<String, Object> module() throws Exception { return module("pre"); }
    private Map<String, Object> module(String stage) throws Exception {
        return (Map<String, Object>) Json.parse(Files.readString(new File(directory, stage + "-core/SimdDoubleX2.json").toPath()));
    }
    private Map<String, Object> metadata() {
        return Map.of("kind", "vector", "evaluated", true, "primReps", List.of("VecRep 2 DoubleElemRep"),
            "vector", Map.of("lanes", 2L, "element", "DoubleElemRep"));
    }
    @FunctionalInterface private interface Action { void run(Language language) throws Exception; }
    private void withLanguage(Action action) throws Exception { withLanguage(false, action); }
    private void withLanguage(boolean inlining, Action action) throws Exception {
        try (var context = Context.newBuilder("thc").allowExperimentalOptions(true)
                .option("engine.BackgroundCompilation", "false")
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
    private void sameDouble(double expected, double actual, String label) {
        if (Double.isNaN(expected)) assertTrue(Double.isNaN(actual), label);
        else assertEquals(Double.doubleToRawLongBits(expected), Double.doubleToRawLongBits(actual), label);
    }
    @Test void exactShapeAndVectorBackedStorageRemainDistinctFromDoubleTuple() throws Exception {
        var proof = CoreRepresentations.parse(metadata());
        assertEquals(CoreVectors.proofDouble, proof);
        assertFalse(proof.isTuple()); assertFalse(proof.isDouble());
        assertFalse(TupleShape.compatible(proof, CoreVectors.unpackedDouble));
        assertFalse(TupleShape.compatible(proof, CoreVectors.proof32));
        assertFalse(TupleShape.compatible(proof, CoreVectors.proofFloat));
        assertFalse(TupleShape.compatible(proof, CoreVectors.proof));
        assertEquals(Collections.nCopies(2, "DoubleRep"), CoreVectors.unpackedDouble.getPrimReps());
        boolean allDouble = true;
        for (var component : CoreVectors.unpackedDouble.getComponents()) if (!component.isDouble()) { allDouble = false; break; }
        assertTrue(allDouble);
        assertEquals(double.class, DoubleVector.class.getMethod("lane", int.class).getReturnType());
        assertThrows(RuntimeFault.class, () -> proof.refine(CoreVectors.unpackedDouble));
        assertThrows(RuntimeFault.class, () -> proof.refine(CoreVectors.proof32));
        assertThrows(RuntimeFault.class, () -> CoreVectors.caseResult(List.of(proof, CoreVectors.proof32)));
        assertThrows(RuntimeFault.class, () -> { var missing = new LinkedHashMap<>(metadata()); missing.remove("vector"); CoreRepresentations.parse(missing); });
        assertThrows(RuntimeFault.class, () -> CoreRepresentations.parse(with(metadata(), "primReps", Collections.nCopies(2, "DoubleRep"))));
        assertThrows(RuntimeFault.class, () -> CoreVectors.validate("plusDoubleX2#", List.of(proof), proof));
        assertThrows(RuntimeFault.class, () -> CoreVectors.validate("plusDoubleX2#", List.of(proof, proof), CoreVectors.proof));
        assertThrows(RuntimeFault.class, () -> CoreVectors.validate("packDoubleX2#", List.of(CoreVectors.unpackedFloat), proof));
        var unpacked = CoreVectors.unpackedDouble;
        var nested = new CoreRepresentation(unpacked.getKind(), unpacked.getEvaluated(), unpacked.getPresent(), unpacked.getPrimReps(),
            List.of(new CoreRepresentation(CoreKind.UNKNOWN, true, true, List.of("DoubleRep"), List.of(unpacked.getComponents().get(0)), null, null, null, null),
                unpacked.getComponents().get(1)), unpacked.getVector(), unpacked.getAlternatives(), unpacked.getTagSlot(), unpacked.getAlternativeSlots());
        assertThrows(RuntimeFault.class, () -> CoreVectors.validate("packDoubleX2#", List.of(nested), proof));
        assertThrows(RuntimeFault.class, () -> CoreVectors.validate("timesDoubleX2#", List.of(proof, CoreVectors.proof32), proof));
        assertThrows(RuntimeFault.class, () -> CoreVectors.validate("packDoubleX2#", List.of(CoreVectors.unpacked32), proof));
        assertThrows(RuntimeFault.class, () -> CoreVectors.validate("packDoubleX2#", CoreVectors.unpackedDouble.getComponents(), proof));
        assertThrows(RuntimeFault.class, () -> CoreVectors.validate("broadcastDoubleX2#",
            List.of(new CoreRepresentation(CoreKind.UNKNOWN, true, true, List.of("DoubleRep"), null, null, null, null, null)), proof));
        assertEquals(new CoreVector(8, "DoubleElemRep"), CoreRepresentations.parse(with(metadata(),
            "primReps", List.of("VecRep 8 DoubleElemRep"), "vector", Map.of("lanes", 8L, "element", "DoubleElemRep"))).getVector());
        assertThrows(UnsupportedCore.class, () -> CoreRepresentations.parse(with(metadata(),
            "primReps", List.of("VecRep 3 DoubleElemRep"), "vector", Map.of("lanes", 3L, "element", "DoubleElemRep"))));
    }
    @Test void primitiveLanesPreserveMovementBitsAndBinary64Arithmetic() {
        double[] values = {0.0, -0.0, Double.MIN_VALUE, -Double.MIN_VALUE,
            Double.longBitsToDouble(3), -Double.longBitsToDouble(3), Double.MIN_NORMAL, Double.MAX_VALUE,
            Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.longBitsToDouble(0x7ff8000000001234L),
            0.5, -1.0, 1.0, 9007199254740992.0, 9007199254740994.0};
        for (int i = 0; i < values.length; i++) for (int j = 0; j < values.length; j++) {
            double a = values[i], b = values[j];
            double[] lanes = {a, values[(i + 3) % values.length]};
            var packed = DoubleVector.broadcast(DoubleVector.SPECIES_128, lanes[0]).withLane(1, lanes[1]);
            var broadcast = DoubleVector.broadcast(DoubleVector.SPECIES_128, b);
            for (int lane = 0; lane <= 1; lane++) {
                assertEquals(Double.doubleToRawLongBits(lanes[lane]), Double.doubleToRawLongBits(packed.lane(lane)), "movement " + i + "/" + j + "/" + lane);
                assertEquals(Double.doubleToRawLongBits(b), Double.doubleToRawLongBits(broadcast.lane(lane)), "broadcast " + i + "/" + j + "/" + lane);
                sameDouble(lanes[lane] + b, packed.add(broadcast).lane(lane), "add " + i + "/" + j + "/" + lane);
                sameDouble(lanes[lane] - b, packed.sub(broadcast).lane(lane), "subtract " + i + "/" + j + "/" + lane);
                sameDouble(lanes[lane] * b, packed.mul(broadcast).lane(lane), "multiply " + i + "/" + j + "/" + lane);
            }
        }
        var product = DoubleVector.broadcast(DoubleVector.SPECIES_128, Double.longBitsToDouble(0x3ff0000000000001L)).mul(DoubleVector.broadcast(DoubleVector.SPECIES_128, Double.longBitsToDouble(0x3feffffffffffffeL)));
        sameDouble(0.0, product.add(DoubleVector.broadcast(DoubleVector.SPECIES_128, -1.0)).lane(0), "separate rounding, not FMA");
        var precise = DoubleVector.broadcast(DoubleVector.SPECIES_128, 1.0).add(DoubleVector.broadcast(DoubleVector.SPECIES_128, Math.scalb(1.0, -52)));
        sameDouble(Double.longBitsToDouble(0x3ff0000000000001L), precise.lane(0), "binary64 precision, not binary32");
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
                original -> with(original, "primReps", Collections.nCopies(2, "DoubleRep")),
                original -> with(original, "primReps", List.of("VecRep 2 Int64ElemRep"), "vector", Map.of("lanes", 2L, "element", "Int64ElemRep")),
                original -> with(original, "primReps", List.of("VecRep 8 DoubleElemRep"), "vector", Map.of("lanes", 8L, "element", "DoubleElemRep")));
            for (var backend : List.of("ast", "bytecode")) {
                assertNotNull(program(language, backend, input, "vectorArgument"));
                var unsupported = (Map<String, Object>) rewrite(input, original -> with(original,
                    "primReps", List.of("VecRep 3 DoubleElemRep"), "vector", Map.of("lanes", 3L, "element", "DoubleElemRep")));
                assertThrows(UnsupportedCore.class, () -> program(language, backend, unsupported, "plusCase"));
                assertTrue(!((Collection<?>) program(language, backend, unsupported, "plusCase", true).diagnostics().get("deferredUnsupported")).isEmpty());
                for (boolean diagnostic : List.of(false, true)) for (var mutation : mutations) {
                    var modified = (Map<String, Object>) rewrite(input, mutation);
                    assertThrows(RuntimeFault.class, () -> program(language, backend, modified, "plusCase", diagnostic));
                }
            }
        });
    }
    @Test void unknownLaneKindCannotHideFloatLiteralInVectorBroadcast() throws Exception {
        withLanguage(language -> {
            var closure = Map.of("kind", "closure", "primReps", List.of("BoxedRep (Just Lifted)"), "evaluated", true);
            var word = Map.of("kind", "long", "primReps", List.of("IntRep"), "evaluated", true);
            var forged = Map.of("kind", "unknown", "primReps", List.of("DoubleRep"), "evaluated", true);
            var operand = list("lit", "float", "1.0", Map.of("rep", forged));
            var vector = list("app", list("prim", "broadcastDoubleX2#", Map.of("rep", closure)),
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
    private List<RootCallTarget> activeTargets(RootCallTarget entry) {
        var seen = Collections.newSetFromMap(new IdentityHashMap<RootCallTarget, Boolean>());
        var targets = new ArrayList<RootCallTarget>();
        visit(entry, seen, targets); return targets;
    }
    private void visit(RootCallTarget target, Set<RootCallTarget> seen, List<RootCallTarget> targets) {
        if (!seen.add(target)) return;
        var node = target.getRootNode();
        var nodes = new ArrayList<Node>(); nodes.add(node);
        if (node instanceof BytecodeRoot root) for (var instruction : root.getBytecodeNode().getInstructions())
            for (var argument : instruction.getArguments()) if (argument.getKind() == Instruction.Argument.Kind.NODE_PROFILE) {
                var cached = argument.asCachedNode(); if (cached != null) nodes.add(cached);
            }
        for (var item : nodes) for (var call : NodeUtil.findAllNodeInstances(item, DirectCallNode.class))
            if (call.getCurrentCallTarget() instanceof RootCallTarget active && active.getRootNode() instanceof GuestRoot)
                visit(active, seen, targets);
        targets.add(target);
    }
    private Map<IntegerSimdModel.Input, String> rows(String filename) throws Exception {
        var result = new LinkedHashMap<IntegerSimdModel.Input, String>();
        for (var row : Files.readAllLines(new File(directory, filename).toPath())) {
            var parts = row.split("\t", -1);
            var arguments = new ArrayList<Long>();
            for (int i = 1; i < parts.length - 1; i++) arguments.add(Long.parseLong(parts[i]));
            result.put(new IntegerSimdModel.Input(parts[0], arguments), parts[parts.length - 1]);
        }
        return result;
    }
    private void checkRows(boolean compiled, List<List<Long>> cases, Map<IntegerSimdModel.Input, String> expected,
            ExecutableProgram program, RootCallTarget host, Object closure, RootCallTarget target, HandoffState handoff,
            long argumentAllocations, long resultAllocations, String stage, String backend, String name,
            Map<String, Object> entry, long compiledEntries, List<RootCallTarget> compiledTargets) throws Exception {
        for (var input : cases) {
            long before = ((Number) program.diagnostics().get("compiledEntries")).longValue();
            var actual = Calls.target(host, new Object[]{closure, input.toArray()});
            var label = stage + "/" + backend + "/" + name + "/" + input;
            var wanted = expected.get(new IntegerSimdModel.Input(name, input));
            if (Objects.equals(entry.get("result"), "long")) assertEquals(Long.parseLong(wanted), actual, label);
            else {
                assertInstanceOf(Double.class, actual, label);
                double value = (Double) actual;
                if (wanted.equals("nan")) assertTrue(Double.isNaN(value), label);
                else assertEquals(Long.parseUnsignedLong(wanted), Double.doubleToRawLongBits(value), label);
            }
            if (compiled) {
                assertEquals(before + compiledEntries, ((Number) program.diagnostics().get("compiledEntries")).longValue(), label + " compiled guest entry");
                assertEquals(argumentAllocations, handoff.getArguments().getAllocations(), label);
                assertEquals(resultAllocations, handoff.getResults().getAllocations(), label);
                var calls = new ArrayList<DirectCallNode>();
                for (var call : NodeUtil.findAllNodeInstances(host.getRootNode(), DirectCallNode.class)) if (call.getCallTarget() == target) calls.add(call);
                assertTrue(!calls.isEmpty(), label + " selected guest call");
                for (var call : calls) assertSame(target, call.getCurrentCallTarget(), label + " active guest identity");
                assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target), label + " remains installed");
                assertEquals(compiledTargets, activeTargets(host), label + " active target identities");
                for (var active : compiledTargets)
                    assertEquals(true, active.getClass().getMethod("isValidLastTier").invoke(active), label + " active compiled target");
            }
            assertEquals(0, handoff.getArguments().getDepth(), label);
            assertEquals(0, handoff.getArguments().retainedReferences(), label);
            assertEquals(0, handoff.getResults().getDepth(), label);
            assertEquals(0, handoff.getResults().retainedReferences(), label);
            assertNull(handoff.getPending(), label);
        }
    }
    private List<Object> callCounts(List<RootCallTarget> targets) throws Exception {
        var result = new ArrayList<Object>();
        for (var target : targets) result.add(target.getClass().getMethod("getCallCount").invoke(target));
        return result;
    }
    @Test void genuineCoreMatchesAvailableOracleAndEntersCompiledCodeForEveryInput() throws Exception {
        var provenance = (Map<String, Object>) Json.parse(Files.readString(new File(directory, "provenance.json").toPath()));
        var stages = (List<String>) provenance.get("stages");
        assertTrue(stages.contains("pre")); assertEquals(true, provenance.get("positiveAuditsAccepted"));
        var files = new ArrayList<>((List<Map<String, String>>) provenance.get("sources"));
        files.addAll((List<Map<String, String>>) provenance.get("artifacts"));
        for (var file : files) {
            var hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(new File(root, file.get("path")).toPath())));
            assertEquals(file.get("sha256"), hash, "Stale DoubleX2 source/artifact: " + file.get("path"));
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
                var stageStructure = ((Map<String, Map<String, Object>>) provenance.get("structure")).get(stage);
                long compiledEntries = ((Map<String, Number>) stageStructure.get("compiledEntriesByEntry")).get(name).longValue();
                assertTrue(compiledEntries >= 1);
                List<RootCallTarget> compiledTargets = List.of();
                var handoff = language.getHandoffState().get();
                checkRows(false, cases, expected, program, host, closure, target, handoff, 0, 0, stage, backend, name, entry, compiledEntries, compiledTargets);
                checkRows(false, cases, expected, program, host, closure, target, handoff, 0, 0, stage, backend, name, entry, compiledEntries, compiledTargets);
                // The fixture counts every strict nested guest call. Install the
                // active callees before checking that whole compiled call chain.
                compiledTargets = activeTargets(host);
                assertTrue(compiledTargets.size() > 1, stage + "/" + backend + "/" + name + " active guest targets");
                long beforeSetup = ((Number) program.diagnostics().get("compiledEntries")).longValue();
                assertEquals(0L, beforeSetup, "The full corpus ran interpreted before installation");
                var beforeCalls = callCounts(compiledTargets);
                long argumentAllocations = handoff.getArguments().getAllocations();
                long resultAllocations = handoff.getResults().getAllocations();
                for (var active : compiledTargets) {
                    active.getClass().getMethod("compile", boolean.class).invoke(active, true);
                    assertEquals(true, active.getClass().getMethod("isValidLastTier").invoke(active), stage + "/" + backend + "/" + name + " installed");
                    var runtime = Truffle.getRuntime();
                    runtime.getClass().getMethod("bypassedInstalledCode", Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget")).invoke(runtime, active);
                }
                assertEquals(beforeSetup, ((Number) program.diagnostics().get("compiledEntries")).longValue());
                assertEquals(beforeCalls, callCounts(compiledTargets));
                checkRows(true, cases, expected, program, host, closure, target, handoff, argumentAllocations, resultAllocations, stage, backend, name, entry, compiledEntries, compiledTargets);
                assertEquals(beforeCalls, callCounts(compiledTargets), "No interpreted active target after installation");
                assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target), stage + "/" + backend + "/" + name + " after execution");
                for (var active : compiledTargets)
                    assertEquals(true, active.getClass().getMethod("isValidLastTier").invoke(active), stage + "/" + backend + "/" + name + " active compiled target");
                var diagnostics = program.diagnostics();
                assertEquals(0L, ((Number) diagnostics.get("unsupportedTraps")).longValue());
                assertEquals(0L, ((Number) diagnostics.get("blackholes")).longValue());
                assertEquals(0, language.getHandoffState().get().getResults().getDepth());
            }
        });
    }
}
