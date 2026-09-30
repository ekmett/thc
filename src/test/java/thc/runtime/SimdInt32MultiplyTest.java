// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import jdk.incubator.vector.IntVector;
import com.oracle.truffle.api.RootCallTarget;
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
class SimdInt32MultiplyTest {
    private static final String PREFIX = "main:SimdInt32X4Multiply.";
    private final File root = new File(System.getProperty("thc.projectRoot"));
    private final File directory = new File(root, "build/simd-int32x4-multiply");
    private Map<String, Object> module() throws Exception { return module("pre"); }
    private Map<String, Object> module(String stage) throws Exception {
        return thc.CoreCbdFixtures.read(new File(directory, stage + "-core/SimdInt32X4Multiply.cbd").toPath());
    }
    private Map<String, Object> metadata() {
        return Map.of("kind", "vector", "evaluated", true, "primReps", List.of("VecRep 4 Int32ElemRep"),
            "vector", Map.of("lanes", 4L, "element", "Int32ElemRep"));
    }
    @FunctionalInterface private interface Action { void run(Language language) throws Exception; }
    private void withLanguage(Action action) throws Exception { withLanguage(true, action); }
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
        var linked = new LinkedHashMap<>(CoreModules.reachable(input, entry.equals("root") ? entry : PREFIX + entry));
        linked.put("instrument", true); linked.put("diagnosticUnsupported", diagnostic);
        return backend.equals("ast") ? new Program(language, linked) : new BytecodeProgram(language, linked);
    }
    private List<Long> lanes(IntVector value) {
        var result = new ArrayList<Long>();
        for (int lane = 0; lane < 4; lane++) result.add((long) value.lane(lane));
        return result;
    }
    private IntVector pack(List<Long> values) {
        var result = IntVector.broadcast(IntVector.SPECIES_128, values.getFirst().intValue());
        for (int lane = 1; lane < 4; lane++) result = result.withLane(lane, values.get(lane).intValue());
        return result;
    }
    private long signed(long value) { return ((value & 0xffff_ffffL) ^ 0x8000_0000L) - 0x8000_0000L; }
    private CoreRepresentation copy(CoreRepresentation proof, CoreKind kind, List<String> primReps, List<CoreRepresentation> components) {
        return new CoreRepresentation(kind, proof.getEvaluated(), proof.getPresent(), primReps, components, proof.getVector(),
            proof.getAlternatives(), proof.getTagSlot(), proof.getAlternativeSlots());
    }
    private Map<String, Object> with(Map<String, Object> original, Object... changes) {
        var result = new LinkedHashMap<>(original);
        for (int i = 0; i < changes.length; i += 2) result.put((String) changes[i], changes[i + 1]);
        return result;
    }
    private List<Object> list(Object... values) { return Arrays.asList(values); }

    @Test void multiplicationKeepsExactSignedFourLaneContract() {
        var proof = CoreRepresentations.parse(metadata());
        assertEquals(CoreVectors.proof32, proof);
        assertEquals(7, CoreVectors.operations32.size());
        assertTrue(CoreVectors.operations32.contains("timesInt32X4#"));
        assertEquals(Collections.nCopies(4, "Int32Rep"), CoreVectors.unpacked32.getPrimReps());
        CoreVectors.validate("timesInt32X4#", List.of(proof, proof), proof);
        for (int arity : List.of(0, 1, 3)) assertThrows(RuntimeFault.class,
            () -> CoreVectors.validate("timesInt32X4#", Collections.nCopies(arity, proof), proof));
        for (var wrong : List.of(CoreVectors.proofWord32, CoreVectors.proof16, CoreVectors.proofWord16,
                CoreVectors.proof8, CoreVectors.proofWord8, CoreVectors.proof, CoreVectors.proofFloat, CoreVectors.unpacked32)) {
            for (int index = 0; index < 2; index++) {
                int selected = index;
                assertThrows(RuntimeFault.class, () -> {
                    var arguments = new ArrayList<CoreRepresentation>();
                    for (int i = 0; i < 2; i++) arguments.add(i == selected ? wrong : proof);
                    CoreVectors.validate("timesInt32X4#", arguments, proof);
                });
            }
            assertThrows(RuntimeFault.class, () -> CoreVectors.validate("timesInt32X4#", List.of(proof, proof), wrong));
        }
        assertThrows(RuntimeFault.class, () -> CoreVectors.validate("timesWord32X4#", List.of(CoreVectors.proofWord32, proof), CoreVectors.proofWord32));
        for (int lane = 0; lane < 4; lane++) for (var wrong : List.of("Word32Rep", "IntRep", "Int16Rep")) {
            var components = new ArrayList<CoreRepresentation>();
            for (int i = 0; i < CoreVectors.unpacked32.getComponents().size(); i++) {
                var p = CoreVectors.unpacked32.getComponents().get(i);
                components.add(i == lane ? copy(p, p.getKind(), List.of(wrong), p.getComponents()) : p);
            }
            var bad = copy(CoreVectors.unpacked32, CoreVectors.unpacked32.getKind(), CoreVectors.unpacked32.getPrimReps(), components);
            assertThrows(RuntimeFault.class, () -> CoreVectors.validate("packInt32X4#", List.of(bad), proof));
        }
    }
    private void checkProduct(List<Long> a, List<Long> b, boolean independent) {
        assertEquals(a, lanes(pack(a))); assertEquals(b, lanes(pack(b)));
        var expected = new ArrayList<Long>();
        for (int i = 0; i < a.size(); i++)
            expected.add(independent ? signed(java.math.BigInteger.valueOf(a.get(i)).multiply(java.math.BigInteger.valueOf(b.get(i)))
                .and(java.math.BigInteger.valueOf(0xffff_ffffL)).longValue()) : signed(a.get(i) * b.get(i)));
        assertEquals(expected, lanes(pack(a).mul(pack(b))));
    }
    @Test void productsKeepLowBitsThenSignExtendWithoutSaturationOrHighProduct() {
        // Both halves visit every 16-bit encoding in every lane. The constructed
        // samples are not exhaustive coverage of 2^32 words or 2^64 operand pairs.
        for (long bits = 0; bits <= 65535; bits++) {
            var a = new ArrayList<Long>(); var b = new ArrayList<Long>();
            for (int i = 0; i < 4; i++) {
                a.add(signed(((bits + i * 7919L) & 65535L) | (((bits * 40503L + i * 3571L) & 65535L) << 16)));
                b.add(signed((bits * (2 * i + 1) + 32767L) * 65537L + i * 1000000007L));
            }
            checkProduct(a, b, false);
        }
        var edges = new LinkedHashSet<>(List.of(-2147483648L, -2147483647L, -65536L, -65535L, -1L, 0L, 1L, 2L,
            65535L, 65536L, 1073741824L, 2147483646L, 2147483647L));
        for (int bit = 0; bit < 31; bit++) for (long delta = -1; delta <= 1; delta++) {
            edges.add((1L << bit) + delta); edges.add(-((1L << bit) + delta));
        }
        for (int lane = 0; lane < 4; lane++) for (long x : edges) for (long y : edges) {
            var a = new ArrayList<Long>(); var b = new ArrayList<Long>();
            for (int i = 0; i < 4; i++) { a.add(signed(0x8000_0001L + i * 1000000007L)); b.add(signed(0xffff_fffeL - i * 591558727L)); }
            a.set(lane, x); b.set(lane, y); checkProduct(a, b, true);
        }
        assertEquals(Collections.nCopies(4, -2147483648L), lanes(pack(Collections.nCopies(4, -2147483648L)).mul(pack(Collections.nCopies(4, -1L)))));
        assertEquals(Collections.nCopies(4, -2L), lanes(pack(Collections.nCopies(4, 2147483647L)).mul(pack(Collections.nCopies(4, 2L)))));
    }
    private List<Object> broadcast(List<Object> value, Map<String, Object> closure) {
        return list("app", list("prim", "broadcastInt32X4#", Map.of("rep", closure)), list(value), list(false), false, true, Map.of("rep", metadata()));
    }
    private Map<String, Object> multiplyModule(List<Object> literal) { return multiplyModule(literal, list(false, false)); }
    private Map<String, Object> multiplyModule(List<Object> literal, List<Object> flags) {
        Map<String, Object> closure = Map.of("kind", "closure", "primReps", List.of("BoxedRep (Just Lifted)"), "evaluated", true);
        var word = Map.of("kind", "long", "primReps", List.of("IntRep"), "evaluated", true);
        var lane = Map.of("kind", "long", "primReps", List.of("Int32Rep"), "evaluated", true);
        var product = list("app", list("prim", "timesInt32X4#", Map.of("rep", closure)),
            list(broadcast(literal, closure), broadcast(list("lit", "int32", "-1", Map.of("rep", lane)), closure)),
            flags, false, true, Map.of("rep", metadata()));
        var body = list("case", product, "v", list(list("default", null, List.of(),
            list("lit", "int", "1", Map.of("rep", word)), Map.of("binders", List.of()))),
            Map.of("rep", word, "binder", Map.of("id", "v", "lifted", false, "rep", metadata())));
        return Map.of("schema", 1, "ghc", "9.14.1", "constructors", List.of(), "bindings", list(
            Map.of("id", "root", "name", "root", "arity", 1, "lifted", true, "rep", closure,
                "expr", list("lam", list(Map.of("id", "unused", "name", "unused", "lifted", false, "rep", word)),
                    body, Map.of("rep", closure, "resultRep", word)))));
    }
    @Test void bothLoadersRequireUnliftedOperandsAndCanonicalSignedLiterals() throws Exception {
        withLanguage(language -> {
            Map<String, Object> lane = Map.of("kind", "long", "primReps", List.of("Int32Rep"), "evaluated", true);
            var unconstrained = with(Map.of(), "kind", "unknown", "primReps", null, "evaluated", false);
            var exact = list("lit", "int32", "-2147483648", Map.of("rep", lane));
            for (var backend : List.of("ast", "bytecode")) for (boolean diagnostic : List.of(false, true)) {
                for (var literal : List.of(exact, list("lit", "int32", "2147483647"),
                        list("lit", "int32", "-1", Map.of("rep", unconstrained)))) {
                    var p = program(language, backend, multiplyModule(literal), "root", diagnostic);
                    assertEquals(1L, Calls.target(p.hostEntryTarget(1), new Object[]{p.entryValue("root"), new Object[]{0L}}));
                }
                for (int index = 0; index < 2; index++) for (var flag : list(true, null, 0L, "false")) {
                    int selected = index;
                    assertThrows(RuntimeFault.class, () -> {
                        var flags = new ArrayList<Object>();
                        for (int i = 0; i < 2; i++) flags.add(i == selected ? flag : false);
                        program(language, backend, multiplyModule(exact, flags), "root", diagnostic);
                    });
                }
                for (var text : List.of("-2147483649", "2147483648", "", "+1", "01", "-0", " 1", "1.0"))
                    assertThrows(RuntimeFault.class, () -> program(language, backend,
                        multiplyModule(list("lit", "int32", text, Map.of("rep", lane))), "root", diagnostic));
                // Duplicate integral annotations share Long; the literal tag supplies narrowing.
                for (var shared : List.of("Word32Rep", "Int16Rep")) {
                    var literal = list("lit", "int32", "-1", Map.of("rep", with(lane, "primReps", List.of(shared))));
                    var p = program(language, backend, multiplyModule(literal), "root", diagnostic);
                    assertEquals(1L, Calls.target(p.hostEntryTarget(1), new Object[]{p.entryValue("root"), new Object[]{0L}}));
                }
                for (var wrong : List.of(with(lane, "kind", "unknown"),
                        with(lane, "kind", "float", "primReps", List.of("FloatRep")),
                        with(lane, "kind", "double", "primReps", List.of("DoubleRep")),
                        with(lane, "kind", "closure", "primReps", List.of("BoxedRep (Just Lifted)")), metadata()))
                    assertThrows(RuntimeFault.class, () -> program(language, backend,
                        multiplyModule(list("lit", "int32", "-1", Map.of("rep", wrong))), "root", diagnostic));
                for (var literal : List.of(list("lit", "word32", "4294967295"), list("lit", "word32", "4294967295", Map.of("rep", lane))))
                    assertThrows(RuntimeFault.class, () -> program(language, backend, multiplyModule(literal), "root", diagnostic));
            }
        });
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
    @Test void bothLoadersRetainSignednessAndCallingFrontier() throws Exception {
        withLanguage(language -> {
            for (var backend : List.of("ast", "bytecode")) {
                assertNotNull(program(language, backend, module(), "vectorArgument"));
                for (boolean diagnostic : List.of(false, true)) {
                    var missing = (Map<String, Object>) rewrite(module(), original -> {
                        var result = new LinkedHashMap<>(original); result.remove("vector"); return result;
                    });
                    assertThrows(RuntimeFault.class, () -> program(language, backend, missing, "timesCase", diagnostic));
                    var unsigned = (Map<String, Object>) rewrite(module(), original -> with(original, "primReps", List.of("VecRep 4 Word32ElemRep"),
                        "vector", Map.of("lanes", 4L, "element", "Word32ElemRep")));
                    assertThrows(RuntimeFault.class, () -> program(language, backend, unsigned, "timesCase", diagnostic));
                    var width = (Map<String, Object>) rewrite(module(), original -> with(original, "primReps", List.of("VecRep 8 Int16ElemRep"),
                        "vector", Map.of("lanes", 8L, "element", "Int16ElemRep")));
                    assertThrows(RuntimeFault.class, () -> program(language, backend, width, "timesCase", diagnostic));
                }
            }
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
    private void valid(RootCallTarget target, String label) throws Exception {
        assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target), label);
    }
    @Test void nativeCoreHasExactCompiledEntriesWithInlining() throws Exception { nativeCore(true); }
    @Test void nativeCoreHasExactCompiledEntriesWithoutInlining() throws Exception { nativeCore(false); }
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
    private void check(boolean compiled, List<List<Long>> cases, Map<IntegerSimdModel.Input, Long> expected,
            ExecutableProgram p, RootCallTarget host, Object closure, RootCallTarget target,
            List<RootCallTarget> targets, long callCount, Language language, String stage, String backend, String name, boolean inlining) throws Exception {
        for (var input : cases) {
            var label = stage + "/" + backend + "/" + name + "/" + input + "/inlining=" + inlining;
            long before = ((Number) p.diagnostics().get("compiledEntries")).longValue();
            assertEquals(expected.get(new IntegerSimdModel.Input(name, input)), Calls.target(host, new Object[]{closure, input.toArray()}), label);
            if (compiled) {
                assertEquals(before + callCount, ((Number) p.diagnostics().get("compiledEntries")).longValue(), label + " compiled guest entries");
                var active = activeTargets(target);
                assertEquals(targets.size(), active.size(), label + " active target count");
                boolean identities = true;
                for (var candidate : active) {
                    boolean found = false; for (var prior : targets) if (prior == candidate) { found = true; break; }
                    if (!found) { identities = false; break; }
                }
                assertTrue(identities, label + " active identities");
                for (var installed : targets) valid(installed, label);
                var calls = new ArrayList<DirectCallNode>();
                for (var call : NodeUtil.findAllNodeInstances(host.getRootNode(), DirectCallNode.class))
                    if (call.getCallTarget() == target) calls.add(call);
                assertTrue(!calls.isEmpty(), label + " selected entry");
                for (var call : calls) assertSame(target, call.getCurrentCallTarget(), label + " selected identity");
            }
            var state = language.getHandoffState().get();
            assertEquals(0, state.getResults().getDepth()); assertEquals(0, state.getResults().retainedReferences());
            assertEquals(0, state.getArguments().getDepth()); assertEquals(0, state.getArguments().retainedReferences());
        }
    }
    private void nativeCore(boolean inlining) throws Exception {
        var provenance = (Map<String, Object>) Json.parse(Files.readString(new File(directory, "provenance.json").toPath()));
        var stages = (List<String>) provenance.get("stages");
        assertTrue(stages.contains("pre")); assertEquals(true, provenance.get("positiveAuditsAccepted"));
        var files = new ArrayList<>((List<Map<String, String>>) provenance.get("sources"));
        files.addAll((List<Map<String, String>>) provenance.get("artifacts"));
        for (var file : files) {
            var hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(new File(root, file.get("path")).toPath())));
            assertEquals(file.get("sha256"), hash, "Stale Int32X4 multiply source/artifact: " + file.get("path"));
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
        var counts = (Map<String, Number>) provenance.get("expectedGuestCallsByEntry");
        for (var stage : stages) for (var backend : List.of("ast", "bytecode")) withLanguage(inlining, language -> {
            for (var entry : entries) {
                var name = (String) entry.get("name"); int arity = ((Number) entry.get("arity")).intValue();
                var cases = new ArrayList<List<Long>>();
                for (var row : (List<List<Number>>) entry.get("cases")) {
                    var input = new ArrayList<Long>(); for (var value : row) input.add(value.longValue()); cases.add(input);
                }
                assertTrue(!cases.isEmpty());
                boolean correctArity = true; for (var input : cases) if (input.size() != arity) { correctArity = false; break; }
                assertTrue(correctArity);
                var p = program(language, backend, module(stage), name);
                var host = p.hostEntryTarget(arity); var closure = p.entryValue(PREFIX + name); var target = p.entryTarget(PREFIX + name);
                long callCount = counts.get(name).longValue();
                assertEquals(List.of("scalarHelperCase", "tupleHelperCase").contains(name) ? 2L : 1L, callCount);
                assertEquals(callCount, ((Map<String, Number>) provenance.get("checkedGuestCallsByStage")).get(stage + "/" + name).longValue());
                check(false, cases, expected, p, host, closure, target, List.of(), callCount, language, stage, backend, name, inlining);
                var targets = activeTargets(target);
                assertEquals((int) callCount, targets.size(), stage + "/" + backend + "/" + name + " guest roots");
                for (var t : targets) { t.getClass().getMethod("compile", boolean.class).invoke(t, true); valid(t, "initial installation"); }
                check(true, cases, expected, p, host, closure, target, targets, callCount, language, stage, backend, name, inlining);
                for (var counter : List.of("unsupportedTraps", "blackholes")) assertEquals(0L, ((Number) p.diagnostics().get(counter)).longValue(), counter);
            }
        });
    }
}
