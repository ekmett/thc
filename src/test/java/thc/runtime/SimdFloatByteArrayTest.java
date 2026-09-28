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
class SimdFloatByteArrayTest {
    private final File root = new File(System.getProperty("thc.projectRoot"));
    private final File directory = new File(root, "build/simd-floatx4-bytearray");
    private long integer(Object value, String label) {
        assertTrue(value instanceof Integer || value instanceof Long, label + " must have an integral JSON carrier: " + value);
        return ((Number) value).longValue();
    }
    private Context context(boolean inlining) {
        return Context.newBuilder("thc").allowExperimentalOptions(true).option("compiler.Inlining", Boolean.toString(inlining))
            .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw").build();
    }
    @FunctionalInterface private interface Action { void run(Language language) throws Exception; }
    private void withLanguage(boolean inlining, Action action) throws Exception {
        try (var context = context(inlining)) {
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
        assertEquals(6720L, integer(provenance.get(field), field), field + " must be exactly 6720");
    }
    private void validateMode(Map<String, Object> provenance, String architecture) {
        var fields = List.of("stages", "modelRows", "modelByteOrder", "nativeRows", "nativeByteOrder", "modelMatched");
        assertTrue(provenance.keySet().containsAll(fields), "Missing explicit FloatX4 preparation mode fields");
        rowsCount(provenance, "modelRows");
        assertEquals("little", provenance.get("modelByteOrder"), "FloatX4 model byte order");
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
        SimdByteArrayEvidence.inventory(root, "floatx4", provenance);
        new SimdByteArrayCorpus("floatx4").verify(root, provenance);
        assertEquals("floatx4-bytearray", provenance.get("vector"), "Exact Float memory provenance");
        assertEquals(ByteOrder.LITTLE_ENDIAN, ByteOrder.nativeOrder(), "FloatX4 bounded corpus requires a little-endian host");
        assertEquals(true, provenance.get("positiveAuditsAccepted"));
        var files = new ArrayList<>((List<Map<String, String>>) provenance.get("sources"));
        files.addAll((List<Map<String, String>>) provenance.get("artifacts"));
        for (var file : files) {
            var hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(new File(root, file.get("path")).toPath())));
            assertEquals(file.get("sha256"), hash, "Stale FloatX4 memory source/artifact: " + file.get("path"));
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
        new SimdByteArrayCorpus("floatx4").controls(root);
        SimdByteArrayEvidence.controls(root, "floatx4", manifest);
    }
    @Test void nativeMemoryCoreHasExactCompiledEntriesWithInlining() throws Exception { nativeCore(true); }
    @Test void nativeMemoryCoreHasExactCompiledEntriesWithoutInlining() throws Exception { nativeCore(false); }
    private record Change(String field, Object value) {}
    @Test void preparationModeRejectsCorruptionAndNativeDowngrades() {
        Map<String, Object> nativeMode = Map.of("stages", List.of("pre", "post"), "modelRows", 6720L,
            "modelByteOrder", "little", "nativeRows", 6720L, "nativeByteOrder", "little", "modelMatched", true);
        var exported = with(nativeMode, "stages", List.of("pre"), "nativeRows", null, "nativeByteOrder", null, "modelMatched", null);
        var nativeCorruptions = List.of(new Change("stages", List.of()), new Change("stages", List.of("pre")), new Change("stages", List.of("post", "pre")),
            new Change("stages", List.of("pre", "post", "post")), new Change("nativeRows", null), new Change("nativeRows", 6719L),
            new Change("nativeRows", 6720.0), new Change("nativeRows", 6720.5), new Change("nativeRows", true), new Change("nativeRows", "6720"), new Change("nativeByteOrder", null),
            new Change("nativeByteOrder", "big"), new Change("modelMatched", null), new Change("modelMatched", false));
        var exportCorruptions = List.of(new Change("stages", List.of()), new Change("stages", List.of("pre", "post")),
            new Change("stages", List.of("pre", "pre")), new Change("nativeRows", 0L), new Change("nativeRows", 6720L),
            new Change("nativeByteOrder", "little"), new Change("modelMatched", true), new Change("modelMatched", false));
        var commonCorruptions = List.of(new Change("modelRows", 6719L), new Change("modelRows", 6720.0), new Change("modelRows", 6720.5), new Change("modelRows", true),
            new Change("modelRows", "6720"), new Change("modelByteOrder", "big"));
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
        for (var stage : (List<String>) provenance.get("stages")) for (var backend : List.of("ast", "bytecode")) withLanguage(true, language -> {
            var module = (Map<String, Object>) Json.parse(Files.readString(new File(directory, stage + "-core/SimdFloatX4ByteArray.json").toPath()));
            for (var name : List.of("vectorArgument", "readVectorEscape")) {
                var linked = CoreModules.reachable(module, name);
                assertNotNull(backend.equals("ast") ? new Program(language, linked) : new BytecodeProgram(language, linked), stage + "/" + backend + "/" + name);
            }
            var directRead = CoreModules.reachable(module, "readTupleEscape");
            var error = assertThrows(UnsupportedCore.class, () -> {
                if (backend.equals("ast")) new Program(language, directRead); else new BytecodeProgram(language, directRead);
            });
            assertEquals("Vector memory read requires an immediate exact case", error.getMessage());
        });
    }
    private record Operation(String name, int arity) {}
    @Test void publicHostTupleResultsRetainStateAndRejectInvalidCarriers() throws Exception {
        var provenance = provenance();
        for (var stage : (List<String>) provenance.get("stages")) for (var backend : List.of("ast", "bytecode")) try (var context = context(true)) {
            var module = (Map<String, Object>) Json.parse(Files.readString(new File(directory, stage + "-core/SimdFloatX4ByteArray.json").toPath()));
            for (var family : List.of("vector", "scalar")) for (var operation : List.of(new Operation("Read", 4), new Operation("Write", 7))) {
                var name = family + operation.name + "Worker";
                for (boolean diagnostic : List.of(false, true)) {
                    var label = stage + "/" + backend + "/" + name + "/diagnostic=" + diagnostic;
                    var request = Json.stringify(Map.of("entry", name, "backend", backend, "diagnosticUnsupported", diagnostic, "modules", List.of(module)));
                    var entry = context.eval("thc", request);
                    assertTrue(entry.canExecute(), label);
                    var bytes = HostByteArrayTestValues.allocate(context, backend);
                    // Initialize through the genuine guest writer, never assume fresh native memory is zero.
                    var writer = context.eval("thc", Json.stringify(Map.of("entry", family + "WriteWorker",
                        "backend", backend, "diagnosticUnsupported", diagnostic, "modules", List.of(module))));
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
            var values = new ArrayList<Long>();
            for (int i = 1; i < parts.length; i++) {
                long value = Long.parseLong(parts[i]);
                assertEquals(parts[i], Long.toString(value), "Canonical oracle integer"); values.add(value);
            }
            arguments.addAll(values.subList(0, values.size() - 1));
            result.put(new IntegerSimdModel.Input(parts[0], arguments), values.getLast());
        }
        assertEquals(lines.size(), result.size(), "Duplicate corpus rows");
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
    private final List<Long> rawBits = List.of(0L, 0x80000000L, 1L, 0x80000001L, 0x007fffffL, 0x807fffffL,
        0x00800000L, 0x80800000L, 0x3f800000L, 0xbf800000L, 0x7f7fffffL, 0xff7fffffL,
        0x7f800000L, 0xff800000L, 0x7fc12345L, 0xffc54321L);
    private final List<Long> finiteEdges = List.of(-65536L, -257L, -1L, 0L, 1L, 255L, 256L, 65535L);
    private final List<String> operations = List.of("Unit", "Index", "Read", "Write", "GraphIndex", "GraphStore");
    private String operation(String name) {
        var matches = new ArrayList<String>();
        for (var op : operations) if (name.equals("vector" + op + "Case") || name.equals("scalar" + op + "Case")) matches.add(op);
        assertEquals(1, matches.size()); return matches.getFirst();
    }
    /** Independent exact input inventory; native-only sNaNs cannot enter this set. */
    private Set<List<Long>> expectedCases(String name) {
        var op = operation(name);
        int offsetCount = name.startsWith("vector") ? 4 : 13;
        var patterns = new ArrayList<List<Long>>();
        if (op.startsWith("Graph")) {
            for (int lane = 0; lane <= 3; lane++) for (var value : finiteEdges) {
                var pattern = new ArrayList<>(List.of(-31L, 127L, -1024L, 4096L)); pattern.set(lane, value); patterns.add(pattern);
            }
        } else for (int index = 0; index < rawBits.size(); index++) {
            var pattern = new ArrayList<Long>();
            for (int lane = 0; lane <= 3; lane++) pattern.add(rawBits.get((index + 5 * lane) % rawBits.size()));
            patterns.add(pattern);
        }
        var inputs = new ArrayList<List<Long>>();
        for (int index = 0; index < patterns.size(); index++) {
            var row = new ArrayList<Long>(); row.add((long) (index % offsetCount)); row.addAll(patterns.get(index)); inputs.add(row);
        }
        int selectors = switch (op) { case "Unit" -> 8; case "Index", "Read" -> 4; case "Write", "GraphStore" -> 64; default -> 0; };
        var result = new LinkedHashSet<List<Long>>();
        if (selectors == 0) result.addAll(inputs);
        else for (var row : inputs) for (int selector = 0; selector < selectors; selector++) {
            var expanded = new ArrayList<>(row); expanded.add((long) selector); result.add(expanded);
        }
        return result;
    }
    private void put(byte[] bytes, int at, long value) {
        for (int octet = 0; octet <= 3; octet++) bytes[at + octet] = (byte) (value >>> (8 * octet));
    }
    private long word(byte[] bytes, int at) {
        long result = 0;
        for (int octet = 0; octet <= 3; octet++) result |= (bytes[at + octet] & 255L) << (8 * octet);
        return result;
    }
    /** Explicit little-endian byte movement, no model/runtime carrier import. */
    private long independentExpected(String name, List<Long> input) {
        var op = operation(name);
        boolean graph = op.startsWith("Graph");
        int address = (int) (input.get(0) * (name.startsWith("vector") ? 16 : 4));
        var bytes = new byte[64];
        for (int index = 0; index <= 15; index++) put(bytes, 4 * index, 0x89abcdefL + index * 0x01030507L);
        var values = input.subList(1, 5);
        var bits = new ArrayList<Long>();
        for (var value : values) bits.add(graph ? Float.floatToRawIntBits(value.floatValue()) & 0xffffffffL : value);
        for (int lane = 0; lane <= 3; lane++) put(bytes, address + 4 * lane, bits.get(lane));
        if (op.equals("Unit")) {
            int selector = input.get(5).intValue();
            long before = word(bytes, address + 4 * (selector % 4));
            return before ^ (selector == 5 ? 0x80000000L : 0L);
        }
        if (op.equals("Index") || op.equals("Read")) return word(bytes, address + 4 * input.get(5).intValue());
        long[] weights = {3, 5, 7, 11};
        long score = 0;
        for (int i = 0; i < values.size(); i++) score += values.get(i) * weights[i];
        if (graph) {
            long magnitude = 0;
            for (int i = 0; i < values.size(); i++) magnitude += Math.abs(values.get(i) * weights[i]);
            assertTrue(magnitude < (1L << 24));
            float fp = values.get(0).floatValue() * (float) weights[0];
            for (int lane = 1; lane <= 3; lane++) fp += values.get(lane).floatValue() * (float) weights[lane];
            assertEquals(score, (long) fp, "Finite checksum Float32 exactness");
        }
        return op.equals("GraphIndex") ? score : score * 257 + (bytes[input.get(5).intValue()] & 255L);
    }
    private void nativeCore(boolean inlining) throws Exception {
        var provenance = provenance();
        var stages = (List<String>) provenance.get("stages");
        var expected = rows("expected.tsv");
        assertEquals(6720, expected.size());
        for (var row : expected.entrySet()) assertEquals(independentExpected(row.getKey().name(), row.getKey().arguments()), row.getValue(), "Independent bytes: " + row.getKey());
        if (provenance.get("nativeRows") != null) {
            assertEquals(expected, rows("oracle.tsv")); assertEquals((long) expected.size(), integer(provenance.get("nativeRows"), "nativeRows"));
        }
        var entries = (List<Map<String, Object>>) provenance.get("entries");
        var names = new LinkedHashSet<String>();
        for (var family : List.of("vector", "scalar")) for (var op : operations) names.add(family + op + "Case");
        var actualNames = new LinkedHashSet<Object>(); for (var entry : entries) actualNames.add(entry.get("name"));
        assertEquals(12, entries.size()); assertEquals(names, actualNames);
        var declared = new ArrayList<IntegerSimdModel.Input>();
        for (var entry : entries) for (var row : (List<List<Object>>) entry.get("cases")) {
            var input = new ArrayList<Long>(); for (var value : row) input.add(integer(value, "declared input"));
            declared.add(new IntegerSimdModel.Input((String) entry.get("name"), input));
        }
        assertEquals(declared.size(), new LinkedHashSet<>(declared).size());
        assertEquals(new LinkedHashSet<>(declared), expected.keySet());
        var counts = (Map<String, Object>) provenance.get("expectedGuestCallsByEntry");
        for (var stage : stages) for (var backend : List.of("ast", "bytecode")) withLanguage(inlining, language -> {
            var module = (Map<String, Object>) Json.parse(Files.readString(new File(directory, stage + "-core/SimdFloatX4ByteArray.json").toPath()));
            for (var entry : entries) {
                var name = (String) entry.get("name");
                long rawArity = integer(entry.get("arity"), name + " arity");
                assertTrue(rawArity >= 1 && rawArity <= 7); int arity = (int) rawArity;
                var cases = new ArrayList<List<Long>>();
                for (var row : (List<List<Number>>) entry.get("cases")) {
                    var input = new ArrayList<Long>(); for (var value : row) input.add(integer(value, name + " input")); cases.add(input);
                }
                assertTrue(!cases.isEmpty());
                boolean correctArity = true; for (var input : cases) if (input.size() != arity) { correctArity = false; break; }
                assertTrue(correctArity);
                var offsets = new LinkedHashSet<Long>();
                for (long offset = 0; offset <= (name.startsWith("vector") ? 3 : 12); offset++) offsets.add(offset);
                var op = operation(name);
                assertEquals(op.equals("GraphIndex") ? 5 : 6, arity, name + " exact arity");
                var wanted = expectedCases(name);
                assertEquals(wanted.size(), cases.size(), name + " exact rows");
                assertEquals(wanted, new LinkedHashSet<>(cases), name + " exact portable domain (sNaNs excluded)");
                var actualOffsets = new LinkedHashSet<Long>(); for (var row : cases) actualOffsets.add(row.getFirst());
                assertEquals(offsets, actualOffsets, name + " safe offsets");
                var linked = new LinkedHashMap<>(CoreModules.reachable(module, name)); linked.put("instrument", true);
                ExecutableProgram p = backend.equals("ast") ? new Program(language, linked) : new BytecodeProgram(language, linked);
                var host = p.hostEntryTarget(arity); var closure = p.entryValue(name); var target = p.entryTarget(name);
                long callCount = integer(counts.get(name), name + " guest count");
                // Counts are structural Core evidence, not guessed from global bindings:
                // exact runRW applications in both roots execute in-frame.
                assertEquals(op.equals("Unit") ? 1L : 2L, callCount);
                assertEquals(callCount, integer(((Map<String, Object>) provenance.get("checkedGuestCallsByStage")).get(stage + "/" + name), stage + "/" + name + " checked count"));
                check(false, cases, expected, p, host, closure, target, List.of(), callCount, language, stage, backend, name, inlining);
                var targets = activeTargets(target);
                assertEquals((int) callCount, targets.size(), stage + "/" + backend + "/" + name + " guest roots");
                var targetClass = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget");
                var callCounter = targetClass.getMethod("getCallCount");
                var beforeInstall = p.diagnostics().get("compiledEntries");
                var callsBeforeInstall = new ArrayList<Object>();
                for (var t : targets) callsBeforeInstall.add(callCounter.invoke(t));
                for (var t : targets) {
                    t.getClass().getMethod("compile", boolean.class).invoke(t, true);
                    valid(t, "initial installation");
                    // Match EntryValue.compile: restore HotSpot's shared entry
                    // stub before measuring the first call, without invoking it.
                    var runtime = Truffle.getRuntime();
                    runtime.getClass().getMethod("bypassedInstalledCode", targetClass).invoke(runtime, t);
                    valid(t, "installation after boundary restoration");
                }
                assertEquals(beforeInstall, p.diagnostics().get("compiledEntries"), "Installation cannot execute guest code");
                var callsAfterInstall = new ArrayList<Object>();
                for (var t : targets) callsAfterInstall.add(callCounter.invoke(t));
                assertEquals(callsBeforeInstall, callsAfterInstall, "Installation cannot settle interpreted calls");
                check(true, cases, expected, p, host, closure, target, targets, callCount, language, stage, backend, name, inlining);
                for (var counter : List.of("unsupportedTraps", "blackholes")) assertEquals(0L, ((Number) p.diagnostics().get(counter)).longValue(), counter);
            }
        });
    }
}
