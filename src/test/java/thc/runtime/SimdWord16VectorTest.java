// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import jdk.incubator.vector.ShortVector;
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
class SimdWord16VectorTest {
    private final File root = new File(System.getProperty("thc.projectRoot"));
    private final File directory = new File(root, "build/simd-word16x8");
    private Map<String, Object> module() throws Exception { return module("pre"); }
    private Map<String, Object> module(String stage) throws Exception {
        return (Map<String, Object>) Json.parse(Files.readString(new File(directory, stage + "-core/SimdWord16X8.json").toPath()));
    }
    private Map<String, Object> metadata() {
        return Map.of("kind", "vector", "evaluated", true, "primReps", List.of("VecRep 8 Word16ElemRep"),
            "vector", Map.of("lanes", 8L, "element", "Word16ElemRep"));
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
        var linked = new LinkedHashMap<>(CoreModules.reachable(input, entry));
        linked.put("instrument", true); linked.put("diagnosticUnsupported", diagnostic);
        return backend.equals("ast") ? new Program(language, linked) : new BytecodeProgram(language, linked);
    }
    private List<Long> lanes(ShortVector value) {
        var result = new ArrayList<Long>();
        for (int lane = 0; lane < 8; lane++) result.add(value.lane(lane) & 0xffffL);
        return result;
    }
    private ShortVector pack(List<Long> values) {
        var result = ShortVector.broadcast(ShortVector.SPECIES_128, values.getFirst().shortValue());
        for (int lane = 1; lane < 8; lane++) result = result.withLane(lane, values.get(lane).shortValue());
        return result;
    }
    private long unsigned(long value) { return value & 0xffffL; }
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

    @Test void exactShapeRequiresEightWord16TupleLanes() {
        var proof = CoreRepresentations.parse(metadata());
        assertEquals(CoreVectors.proofWord16, proof);
        assertFalse(proof.isTuple()); assertFalse(proof.isLong());
        assertEquals(Collections.nCopies(8, "Word16Rep"), CoreVectors.unpackedWord16.getPrimReps());
        boolean allInt = true;
        for (var component : CoreVectors.unpackedWord16.getComponents()) if (!component.isInt()) { allInt = false; break; }
        assertTrue(allInt);
        assertEquals(6, CoreVectors.operationsWord16.size());
        assertFalse(CoreVectors.operations.contains("negateWord16X8#"));
        boolean negate = false;
        for (var method : ShortVector.class.getDeclaredMethods()) if (method.getName().equals("negate")) { negate = true; break; }
        assertFalse(negate);
        for (var wrong : List.of(CoreVectors.proof, CoreVectors.proof8, CoreVectors.proof16, CoreVectors.proofWord8, CoreVectors.proof32, CoreVectors.proofFloat, CoreVectors.proofDouble, CoreVectors.unpackedWord16)) {
            assertFalse(TupleShape.compatible(proof, wrong));
            assertThrows(RuntimeFault.class, () -> proof.refine(wrong));
        }
        assertThrows(RuntimeFault.class, () -> CoreVectors.validate("packWord16X8#", CoreVectors.unpackedWord16.getComponents(), proof));
        assertThrows(RuntimeFault.class, () -> CoreVectors.validate("plusWord16X8#", List.of(proof), proof));
        assertThrows(RuntimeFault.class, () -> CoreVectors.validate("timesWord16X8#", List.of(proof, CoreVectors.proof32), proof));
        assertThrows(RuntimeFault.class, () -> CoreVectors.validate("unpackWord16X8#", List.of(proof), proof));
        for (var wrong : List.of("IntRep", "WordRep", "Int16Rep", "Int8Rep", "Word8Rep", "Int32Rep")) for (int index = 0; index < 8; index++) {
            var components = new ArrayList<CoreRepresentation>();
            for (int i = 0; i < CoreVectors.unpackedWord16.getComponents().size(); i++) {
                var lane = CoreVectors.unpackedWord16.getComponents().get(i);
                components.add(i == index ? copy(lane, lane.getKind(), List.of(wrong), lane.getComponents()) : lane);
            }
            var bad = copy(CoreVectors.unpackedWord16, CoreVectors.unpackedWord16.getKind(), CoreVectors.unpackedWord16.getPrimReps(), components);
            assertThrows(RuntimeFault.class, () -> CoreVectors.validate("packWord16X8#", List.of(bad), proof));
        }
        for (var name : List.of("plus", "minus", "times")) {
            assertThrows(RuntimeFault.class, () -> CoreVectors.validate(name + "Word16X8#", List.of(proof, CoreVectors.proof16), proof));
            assertThrows(RuntimeFault.class, () -> CoreVectors.validate(name + "Int16X8#", List.of(CoreVectors.proof16, proof), CoreVectors.proof16));
        }
        var components = new ArrayList<CoreRepresentation>();
        for (var lane : CoreVectors.unpackedWord16.getComponents()) components.add(copy(lane, CoreKind.UNKNOWN, lane.getPrimReps(), lane.getComponents()));
        var bad = copy(CoreVectors.unpackedWord16, CoreVectors.unpackedWord16.getKind(), CoreVectors.unpackedWord16.getPrimReps(), components);
        assertThrows(RuntimeFault.class, () -> CoreVectors.validate("packWord16X8#", List.of(bad), proof));
        assertThrows(RuntimeFault.class, () -> { var missing = new LinkedHashMap<>(metadata()); missing.remove("vector"); CoreRepresentations.parse(missing); });
        assertThrows(RuntimeFault.class, () -> CoreRepresentations.parse(with(metadata(), "primReps", Collections.nCopies(8, "Word16Rep"))));
        assertThrows(UnsupportedCore.class, () -> CoreRepresentations.parse(with(metadata(), "primReps", List.of("VecRep 4 Word16ElemRep"),
            "vector", Map.of("lanes", 4L, "element", "Word16ElemRep"))));
    }

    private void checkArithmetic(List<Long> a, List<Long> b, long broadcast) {
        var left = pack(a); var right = pack(b);
        assertEquals(a, lanes(left));
        assertEquals(Collections.nCopies(8, unsigned(broadcast)), lanes(ShortVector.broadcast(ShortVector.SPECIES_128, (short) broadcast)));
        var plus = new ArrayList<Long>(); var minus = new ArrayList<Long>(); var times = new ArrayList<Long>();
        for (int lane = 0; lane < 8; lane++) {
            plus.add(unsigned(a.get(lane) + b.get(lane))); minus.add(unsigned(a.get(lane) - b.get(lane)));
            times.add(unsigned(a.get(lane) * b.get(lane)));
        }
        assertEquals(plus, lanes(left.add(right))); assertEquals(minus, lanes(left.sub(right))); assertEquals(times, lanes(left.mul(right)));
    }
    @Test void allBitPatternsAndBoundaryPairsWrapWithoutSaturationOrHighProduct() {
        // All 65,536 encodings in every lane; arithmetic samples are correlated,
        // not a claim of exhaustive coverage of all 2^32 binary operand pairs.
        for (long bits = 0; bits <= 65535; bits++) {
            var a = new ArrayList<Long>(); var b = new ArrayList<Long>();
            for (int lane = 0; lane < 8; lane++) {
                a.add(unsigned(bits + lane * 7919L));
                b.add(unsigned(bits * (2 * lane + 1) + 32767L - lane * 3571L));
            }
            checkArithmetic(a, b, bits);
        }
        // Independent Cartesian boundary pairs at every selected lane, with
        // other lanes remaining distinct to expose misordered pack/unpack.
        var edges = List.of(0L, 1L, 2L, 255L, 256L, 16383L, 32767L, 32768L, 32769L, 65534L, 65535L);
        for (int lane = 0; lane < 8; lane++) for (long x : edges) for (long y : edges) {
            var a = new ArrayList<Long>(); var b = new ArrayList<Long>();
            for (int i = 0; i < 8; i++) { a.add(unsigned(40009L + i * 7919L)); b.add(unsigned(50021L - i * 3571L)); }
            a.set(lane, x); b.set(lane, y); checkArithmetic(a, b, x);
        }
    }
    private Map<String, Object> broadcastModule(List<Object> operand) { return broadcastModule(operand, false); }
    private Map<String, Object> broadcastModule(List<Object> operand, Object flag) {
        var closure = Map.of("kind", "closure", "primReps", List.of("BoxedRep (Just Lifted)"), "evaluated", true);
        var word = Map.of("kind", "long", "primReps", List.of("IntRep"), "evaluated", true);
        var vector = list("app", list("prim", "broadcastWord16X8#", Map.of("rep", closure)),
            list(operand), list(flag), false, true, Map.of("rep", metadata()));
        var body = list("case", vector, "v", list(list("default", null, List.of(),
            list("lit", "int", "1", Map.of("rep", word)), Map.of("binders", List.of()))),
            Map.of("rep", word, "binder", Map.of("id", "v", "lifted", false, "rep", metadata())));
        return Map.of("schema", 1, "ghc", "9.14.1", "constructors", List.of(), "bindings", list(
            Map.of("id", "root", "name", "root", "arity", 1, "lifted", true, "rep", closure,
                "expr", list("lam", list(Map.of("id", "unused", "name", "unused", "lifted", false, "rep", word)),
                    body, Map.of("rep", closure, "resultRep", word)))));
    }
    @Test void literalCarriersAndUnliftedFlagsAreCheckedBeforeExecution() throws Exception {
        withLanguage(language -> {
            Map<String, Object> lane = Map.of("kind", "long", "primReps", List.of("Word16Rep"), "evaluated", true);
            var exact = list("lit", "word16", "65535", Map.of("rep", lane));
            var unconstrained = with(Map.of(), "kind", "unknown", "primReps", null, "evaluated", false);
            for (var backend : List.of("ast", "bytecode")) for (boolean diagnostic : List.of(false, true)) {
                for (var operand : List.of(exact, list("lit", "word16", "1"), list("lit", "word16", "1", Map.of("rep", unconstrained)))) {
                    var p = program(language, backend, broadcastModule(operand), "root", diagnostic);
                    assertEquals(1L, Calls.target(p.hostEntryTarget(1), new Object[]{p.entryValue("root"), new Object[]{0L}}));
                }
                for (var flag : list(true, null, 0L, "false")) assertThrows(RuntimeFault.class,
                    () -> program(language, backend, broadcastModule(exact, flag), "root", diagnostic));
                // Lowering shares Int across narrow integral reps; the literal tag supplies narrowing.
                for (var shared : List.of("Int16Rep", "Int32Rep")) {
                    var operand = list("lit", "word16", "1", Map.of("rep", with(lane, "primReps", List.of(shared))));
                    var p = program(language, backend, broadcastModule(operand), "root", diagnostic);
                    assertEquals(1L, Calls.target(p.hostEntryTarget(1), new Object[]{p.entryValue("root"), new Object[]{0L}}));
                }
                for (var wrong : List.of(with(lane, "kind", "unknown"),
                        with(lane, "primReps", List.of("IntRep")), with(lane, "primReps", List.of("Word64Rep")),
                        with(lane, "kind", "float", "primReps", List.of("FloatRep")), with(lane, "kind", "double", "primReps", List.of("DoubleRep"))))
                    assertThrows(RuntimeFault.class, () -> program(language, backend,
                        broadcastModule(list("lit", "word16", "1", Map.of("rep", wrong))), "root", diagnostic));
                for (var operand : List.of(list("lit", "int16", "32767"),
                        list("lit", "int16", "32767", Map.of("rep", lane)),
                        list("lit", "int16", "32767", Map.of("rep", unconstrained))))
                    assertThrows(RuntimeFault.class, () -> program(language, backend, broadcastModule(operand), "root", diagnostic));
                for (var wrong : List.of(
                        with(unconstrained, "primReps", List.of(), "aggregate", "unboxed-tuple", "components", List.of()),
                        with(lane, "aggregate", "unboxed-tuple", "components", list(lane))))
                    assertThrows(RuntimeFault.class, () -> program(language, backend,
                        broadcastModule(list("lit", "word16", "1", Map.of("rep", wrong))), "root", diagnostic));
                var unresolved = with(unconstrained, "aggregate", "unboxed-tuple", "components", List.of());
                var input = broadcastModule(list("lit", "word16", "1", Map.of("rep", unresolved)));
                var reason = "Unsupported Core aggregate representation: unboxed-tuple has unresolved fields";
                if (!diagnostic) assertEquals(reason, assertThrows(UnsupportedCore.class,
                    () -> program(language, backend, input, "root")).getMessage());
                else {
                    // Existing diagnostic policy defers unresolved aggregates, but
                    // cannot execute them as an unsigned scalar or silently erase them.
                    var p = program(language, backend, input, "root", true);
                    assertTrue(((List<?>) p.diagnostics().get("deferredUnsupported")).contains(reason));
                    assertEquals(0L, ((Number) p.diagnostics().get("unsupportedTraps")).longValue());
                    var failure = assertThrows(RuntimeFault.class,
                        () -> Calls.target(p.hostEntryTarget(1), new Object[]{p.entryValue("root"), new Object[]{0L}}));
                    assertEquals("Diagnostic unsupported path reached: " + reason, failure.getMessage());
                    assertEquals(1L, ((Number) p.diagnostics().get("unsupportedTraps")).longValue());
                    var state = language.getHandoffState().get();
                    assertEquals(0, state.getResults().getDepth()); assertEquals(0, state.getResults().retainedReferences());
                    assertEquals(0, state.getArguments().getDepth()); assertEquals(0, state.getArguments().retainedReferences());
                }
            }
        });
    }

    private Map<String, Object> alternative(String text, Map<String, Object> lane, Map<String, Object> word) {
        var base = broadcastModule(list("lit", "word16", "0"));
        var bindings = (List<Map<String, Object>>) base.get("bindings"); assertEquals(1, bindings.size());
        var binding = bindings.getFirst();
        var lambda = new ArrayList<>((List<Object>) binding.get("expr"));
        var unsignedWord = Map.of("kind", "long", "primReps", List.of("WordRep"), "evaluated", true);
        var converted = list("app", list("prim", "int2Word#"), list(list("var", "unused", Map.of("rep", word))),
            list(false), false, true, Map.of("rep", unsignedWord));
        var narrowed = list("app", list("prim", "wordToWord16#"), list(converted),
            list(false), false, true, Map.of("rep", lane));
        lambda.set(2, list("case", narrowed, "x", list(
            list("lit", list("word16", text), List.of(), list("lit", "int", "1", Map.of("rep", word))),
            list("default", null, List.of(), list("lit", "int", "0", Map.of("rep", word)))),
            Map.of("rep", word, "binder", Map.of("id", "x", "lifted", false, "rep", lane))));
        return with(base, "bindings", list(with(binding, "expr", lambda)));
    }
    @Test void canonicalWord16LiteralsAndAlternativesRejectOutOfRangeAndNoncanonicalText() throws Exception {
        withLanguage(language -> {
            Map<String, Object> lane = Map.of("kind", "long", "primReps", List.of("Word16Rep"), "evaluated", true);
            Map<String, Object> word = Map.of("kind", "long", "primReps", List.of("IntRep"), "evaluated", true);
            var malformed = List.of("-1", "-32768", "65536", "", "+1", "01", "-0", " 1", "1.0", "18446744073709551616");
            for (long value = 0; value <= 65535; value++) assertEquals((int) value, ScalarLiterals.narrowWordLiteral("word16", Long.toString(value)));
            for (var text : malformed) assertThrows(RuntimeFault.class, () -> ScalarLiterals.narrowWordLiteral("word16", text));
            for (var backend : List.of("ast", "bytecode")) for (boolean diagnostic : List.of(false, true)) {
                for (var text : malformed)
                    for (var input : List.of(broadcastModule(list("lit", "word16", text, Map.of("rep", lane))), alternative(text, lane, word)))
                        assertThrows(RuntimeFault.class, () -> program(language, backend, input, "root", diagnostic));
                for (long value : List.of(0L, 1L, 32767L, 32768L, 65535L)) {
                    var p = program(language, backend, alternative(Long.toString(value), lane, word), "root", diagnostic);
                    assertEquals(1L, Calls.target(p.hostEntryTarget(1), new Object[]{p.entryValue("root"), new Object[]{value}}));
                }
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
    @Test void bothLoadersRetainVectorBoundaryAndRejectForgedShapes() throws Exception {
        withLanguage(language -> {
            for (var backend : List.of("ast", "bytecode")) {
                assertNotNull(program(language, backend, module(), "vectorArgument"));
                for (boolean diagnostic : List.of(false, true)) {
                    var modified = (Map<String, Object>) rewrite(module(), original -> {
                        var result = new LinkedHashMap<>(original); result.remove("vector"); return result;
                    });
                    assertThrows(RuntimeFault.class, () -> program(language, backend, modified, "plusCase", diagnostic));
                    var wrong = (Map<String, Object>) rewrite(module(), original -> with(original, "primReps", List.of("VecRep 4 Int32ElemRep"),
                        "vector", Map.of("lanes", 4L, "element", "Int32ElemRep")));
                    assertThrows(RuntimeFault.class, () -> program(language, backend, wrong, "plusCase", diagnostic));
                    var signed = (Map<String, Object>) rewrite(module(), original -> with(original, "primReps", List.of("VecRep 8 Int16ElemRep"),
                        "vector", Map.of("lanes", 8L, "element", "Int16ElemRep")));
                    assertThrows(RuntimeFault.class, () -> program(language, backend, signed, "plusCase", diagnostic));
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
            assertEquals(file.get("sha256"), hash, "Stale Word16X8 source/artifact: " + file.get("path"));
        }
        var expected = new IntegerSimdModel("word16x8").checkedRows(Files.readString(new File(directory, "expected.tsv").toPath()));
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
                var host = p.hostEntryTarget(arity); var closure = p.entryValue(name); var target = p.entryTarget(name);
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
