// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.bytecode.Instruction;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeUtil;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import java.nio.ByteOrder;
import com.oracle.truffle.api.Truffle;
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
class SimdInt32ByteArrayTest {
    private final File root = new File(System.getProperty("thc.projectRoot"));
    private final File directory = new File(root, "build/simd-int32x4-bytearray");
    private Context context() {
        return Context.newBuilder("thc").allowExperimentalOptions(true)
            .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw").build();
    }
    @FunctionalInterface private interface Action { void run(Language language) throws Exception; }
    private void withLanguage(Action action) throws Exception {
        try (var context = context()) {
            context.initialize("thc"); context.enter();
            try { action.run(TruffleLanguage.LanguageReference.create(Language.class).get(null)); } finally { context.leave(); }
        }
    }
    private Map<String, Object> with(Map<String, Object> original, Object... changes) {
        var result = new LinkedHashMap<>(original);
        for (int i = 0; i < changes.length; i += 2) result.put((String) changes[i], changes[i + 1]);
        return result;
    }
    private void rowsCount(Map<String, Object> provenance, String field) {
        var count = provenance.get(field) instanceof Number n ? n : null;
        assertTrue(count != null && count.longValue() == 9666L && count.doubleValue() == 9666.0, field + " must be exactly 9666");
    }
    private void validateMode(Map<String, Object> provenance, String architecture) {
        var fields = List.of("stages", "modelRows", "modelByteOrder", "nativeRows", "nativeByteOrder", "modelMatched");
        assertTrue(provenance.keySet().containsAll(fields), "Missing explicit Int32X4 preparation mode fields");
        rowsCount(provenance, "modelRows");
        assertEquals("little", provenance.get("modelByteOrder"), "Int32X4 model byte order");
        // Use the executing host, not a producer architecture supplied by the manifest.
        if (List.of("arm64", "aarch64").contains(architecture.toLowerCase(Locale.ROOT))) {
            assertEquals(List.of("pre"), provenance.get("stages"), "ARM64 export-only Core stages");
            for (var field : List.of("nativeRows", "nativeByteOrder", "modelMatched"))
                assertNull(provenance.get(field), "ARM64 export-only " + field + " must be explicitly null");
        } else {
            assertEquals(List.of("pre", "post"), provenance.get("stages"), "Native Core stages");
            rowsCount(provenance, "nativeRows");
            assertEquals("little", provenance.get("nativeByteOrder"), "Native byte order");
            assertEquals(true, provenance.get("modelMatched"), "Native/model agreement");
        }
    }
    private Map<String, Object> provenance() throws Exception {
        var provenance = (Map<String, Object>) Json.parse(Files.readString(new File(directory, "provenance.json").toPath()));
        validateMode(provenance, System.getProperty("os.arch"));
        SimdByteArrayEvidence.inventory(root, "int32x4", provenance);
        new SimdByteArrayCorpus("int32x4").verify(root, provenance);
        assertEquals(ByteOrder.LITTLE_ENDIAN, ByteOrder.nativeOrder(), "Int32X4 bounded corpus requires a little-endian host");
        assertEquals(true, provenance.get("positiveAuditsAccepted"));
        var files = new ArrayList<>((List<Map<String, String>>) provenance.get("sources"));
        files.addAll((List<Map<String, String>>) provenance.get("artifacts"));
        for (var file : files) {
            var hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(new File(root, file.get("path")).toPath())));
            assertEquals(file.get("sha256"), hash, "Stale Int32X4 memory source/artifact: " + file.get("path"));
        }
        return provenance;
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
    @Test void independentCorpusAndClosedProvenanceControls() throws Exception {
        var manifest = provenance();
        new SimdByteArrayCorpus("int32x4").controls(root);
        SimdByteArrayEvidence.controls(root, "int32x4", manifest);
    }
    @Test void nativeMemoryCoreMatchesOnFirstInstalledCall() throws Exception { nativeCore(); }
    private record Change(String field, Object value) {}
    @Test void preparationModeRejectsCorruptionAndNativeDowngrades() {
        Map<String, Object> nativeMode = Map.of("stages", List.of("pre", "post"), "modelRows", 9666L,
            "modelByteOrder", "little", "nativeRows", 9666L, "nativeByteOrder", "little", "modelMatched", true);
        var exported = with(nativeMode, "stages", List.of("pre"), "nativeRows", null, "nativeByteOrder", null, "modelMatched", null);
        var nativeCorruptions = List.of(new Change("stages", List.of()), new Change("stages", List.of("pre")), new Change("stages", List.of("post", "pre")),
            new Change("stages", List.of("pre", "post", "post")), new Change("nativeRows", null), new Change("nativeRows", 9665L),
            new Change("nativeRows", 9666.5), new Change("nativeRows", "9666"), new Change("nativeByteOrder", null),
            new Change("nativeByteOrder", "big"), new Change("modelMatched", null), new Change("modelMatched", false));
        var exportCorruptions = List.of(new Change("stages", List.of()), new Change("stages", List.of("pre", "post")),
            new Change("stages", List.of("pre", "pre")), new Change("nativeRows", 0L), new Change("nativeRows", 9666L),
            new Change("nativeByteOrder", "little"), new Change("modelMatched", true), new Change("modelMatched", false));
        var commonCorruptions = List.of(new Change("modelRows", 9665L), new Change("modelRows", 9666.5),
            new Change("modelRows", "9666"), new Change("modelByteOrder", "big"));
        for (var architecture : List.of("amd64", "x86_64", "riscv64", "arm64", "aarch64")) {
            boolean arm = List.of("arm64", "aarch64").contains(architecture);
            var accepted = arm ? exported : nativeMode;
            validateMode(accepted, architecture);
            assertThrows(AssertionError.class, () -> validateMode(arm ? nativeMode : exported, architecture),
                architecture + " cannot silently change preparation modes");
            var corruptions = new ArrayList<>(commonCorruptions); corruptions.addAll(arm ? exportCorruptions : nativeCorruptions);
            for (var change : corruptions)
                assertThrows(AssertionError.class, () -> validateMode(with(accepted, change.field, change.value), architecture),
                    architecture + "/" + change.field + "=" + change.value);
            for (var field : accepted.keySet())
                assertThrows(AssertionError.class, () -> {
                    var missing = new LinkedHashMap<>(accepted); missing.remove(field); validateMode(missing, architecture);
                }, architecture + "/missing " + field);
        }
        assertThrows(AssertionError.class, () -> validateMode(with(exported, "toolchain", Map.of("architecture", "aarch64")), "amd64"));
    }
    @Test void genuineVectorCallBindingsLoadBeforePublicHostAdmission() throws Exception {
        var provenance = provenance();
        for (var stage : (List<String>) provenance.get("stages")) for (var backend : List.of("ast", "bytecode")) withLanguage(language -> {
            var module = thc.CoreCbdFixtures.read(new File(directory, stage + "-core/SimdInt32X4ByteArray.cbd").toPath());
            for (var name : List.of("vectorArgument", "readVectorEscape")) {
                var linked = CoreModules.reachable(module, "main:SimdInt32X4ByteArray." + name);
                assertNotNull(backend.equals("ast") ? new Program(language, linked) : new BytecodeProgram(language, linked), stage + "/" + backend + "/" + name);
            }
            var directRead = CoreModules.reachable(module, "main:SimdInt32X4ByteArray.readTupleEscape");
            var error = assertThrows(UnsupportedCore.class, () -> {
                if (backend.equals("ast")) new Program(language, directRead); else new BytecodeProgram(language, directRead);
            });
            assertEquals("Vector memory read requires an immediate exact case", error.getMessage());
        });
    }
    private record Operation(String name, int arity) {}
    @Test void publicHostTupleResultsRetainStateAndRejectInvalidCarriers() throws Exception {
        var provenance = provenance();
        for (var stage : (List<String>) provenance.get("stages")) for (var backend : List.of("ast", "bytecode")) try (var context = context()) {
            var module = thc.CoreCbdFixtures.read(new File(directory, stage + "-core/SimdInt32X4ByteArray.cbd").toPath());
            for (var family : List.of("vector", "scalar")) for (var operation : List.of(new Operation("Read", 3), new Operation("Write", 7))) {
                var name = family + operation.name + "Worker";
                for (boolean diagnostic : List.of(false, true)) {
                    var label = stage + "/" + backend + "/" + name + "/diagnostic=" + diagnostic;
                    var request = CoreModules.request(List.of(new File(directory, stage + "-core/SimdInt32X4ByteArray.cbd").getPath()), "main:SimdInt32X4ByteArray." + name, false, diagnostic, backend);
                    var entry = context.eval("thc", request);
                    assertTrue(entry.canExecute(), label);
                    var bytes = HostByteArrayTestValues.allocate(context, backend);
                    // Initialize through the genuine guest writer, never assume fresh native memory is zero.
                    var writer = context.eval("thc", CoreModules.request(List.of(new File(directory, stage + "-core/SimdInt32X4ByteArray.cbd").getPath()), "main:SimdInt32X4ByteArray." + family + "WriteWorker", false, diagnostic, backend));
                    var initial = new Object[7]; Arrays.fill(initial, 0L);
                    initial[0] = bytes; initial[initial.length - 1] = null;
                    var written = writer.execute(initial);
                    assertEquals(2, written.getArraySize(), label); assertTrue(written.getArrayElement(0).isNull(), label);
                    assertEquals(0L, written.getArrayElement(1).asLong(), label);
                    var valid = new Object[operation.arity]; Arrays.fill(valid, 0L);
                    valid[0] = bytes; valid[valid.length - 1] = null;
                    var invalid = valid.clone(); invalid[0] = "invalid host argument";
                    var badState = valid.clone(); badState[badState.length - 1] = 0L;
                    for (var input : List.of(invalid, badState, new Object[0]))
                        assertThrows(PolyglotException.class, () -> entry.execute(input), label);
                    var result = entry.execute(valid);
                    assertEquals(2, result.getArraySize(), label); assertTrue(result.getArrayElement(0).isNull(), label);
                    assertEquals(0L, result.getArrayElement(1).asLong(), label);
                }
            }
        }
    }
    private Map<IntegerSimdModel.Input, Long> rows(String filename) throws Exception {
        var result = new LinkedHashMap<IntegerSimdModel.Input, Long>();
        var lines = Files.readAllLines(new File(directory, filename).toPath());
        for (var row : lines) {
            var parts = row.split("\t", -1);
            var arguments = new ArrayList<Long>();
            for (int i = 1; i < parts.length - 1; i++) arguments.add(Long.parseLong(parts[i]));
            result.put(new IntegerSimdModel.Input(parts[0], arguments), Long.parseLong(parts[parts.length - 1]));
        }
        assertEquals(lines.size(), result.size(), "Duplicate corpus rows");
        return result;
    }
    private void check(boolean compiled, List<List<Long>> cases, Map<IntegerSimdModel.Input, Long> expected,
            ExecutableProgram p, RootCallTarget host, Object closure, Language language, String stage, String backend, String name) {
        for (var input : cases) {
            var label = stage + "/" + backend + "/" + name + "/" + input;
            long before = ((Number) p.diagnostics().get("compiledEntries")).longValue();
            assertEquals(expected.get(new IntegerSimdModel.Input(name, input)), Calls.target(host, new Object[]{closure, input.toArray()}), label);
            if (compiled) assertTrue(((Number) p.diagnostics().get("compiledEntries")).longValue() > before,
                label + " must enter compiled guest code");
            var state = language.getHandoffState().get();
            assertEquals(0, state.getResults().getDepth()); assertEquals(0, state.getResults().retainedReferences());
            assertEquals(0, state.getArguments().getDepth()); assertEquals(0, state.getArguments().retainedReferences());
        }
    }
    private void nativeCore() throws Exception {
        var provenance = provenance();
        var stages = (List<String>) provenance.get("stages");
        var expected = rows("expected.tsv");
        assertEquals(9666, expected.size());
        if (provenance.get("nativeRows") != null) {
            assertEquals(expected, rows("oracle.tsv")); assertEquals((long) expected.size(), ((Number) provenance.get("nativeRows")).longValue());
        }
        var entries = (List<Map<String, Object>>) provenance.get("entries");
        var declared = new ArrayList<IntegerSimdModel.Input>();
        for (var entry : entries) for (var row : (List<List<Number>>) entry.get("cases")) {
            var input = new ArrayList<Long>(); for (var value : row) input.add(value.longValue());
            declared.add(new IntegerSimdModel.Input((String) entry.get("name"), input));
        }
        assertEquals(declared.size(), new LinkedHashSet<>(declared).size());
        assertEquals(new LinkedHashSet<>(declared), expected.keySet());
        for (var stage : stages) for (var backend : List.of("ast", "bytecode")) withLanguage(language -> {
            var module = thc.CoreCbdFixtures.read(new File(directory, stage + "-core/SimdInt32X4ByteArray.cbd").toPath());
            for (var entry : entries) {
                var name = (String) entry.get("name"); int arity = ((Number) entry.get("arity")).intValue();
                var cases = new ArrayList<List<Long>>();
                for (var row : (List<List<Number>>) entry.get("cases")) {
                    var input = new ArrayList<Long>(); for (var value : row) input.add(value.longValue()); cases.add(input);
                }
                assertTrue(!cases.isEmpty());
                boolean correctArity = true; for (var input : cases) if (input.size() != arity) { correctArity = false; break; }
                assertTrue(correctArity);
                var linked = new LinkedHashMap<>(CoreModules.reachable(module, "main:SimdInt32X4ByteArray." + name)); linked.put("instrument", true);
                ExecutableProgram p = backend.equals("ast") ? new Program(language, linked) : new BytecodeProgram(language, linked);
                var host = p.hostEntryTarget(arity); var closure = p.entryValue("main:SimdInt32X4ByteArray." + name);
                check(false, cases, expected, p, host, closure, language, stage, backend, name);
                var targets = activeTargets(host);
                var targetClass = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget");
                var beforeInstall = p.diagnostics().get("compiledEntries");
                for (var t : targets) {
                    t.getClass().getMethod("compile", boolean.class).invoke(t, true);
                    valid(t, "initial installation");
                }
                // Restore the shared host entry stub without invoking guest code.
                var runtime = Truffle.getRuntime();
                runtime.getClass().getMethod("bypassedInstalledCode", targetClass).invoke(runtime, host);
                assertEquals(beforeInstall, p.diagnostics().get("compiledEntries"), "Installation cannot execute guest code");
                check(true, cases, expected, p, host, closure, language, stage, backend, name);
                for (var counter : List.of("unsupportedTraps", "blackholes")) assertEquals(0L, ((Number) p.diagnostics().get(counter)).longValue(), counter);
            }
        });
    }
}
