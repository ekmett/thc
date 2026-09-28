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
class SimdWord32ByteArrayTest {
    private final File root = new File(System.getProperty("thc.projectRoot"));
    private final File directory = new File(root, "build/simd-word32x4-bytearray");
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
        assertEquals(9666L, integer(provenance.get(field), field), field + " must be exactly 9666");
    }
    private void validateMode(Map<String, Object> provenance, String architecture) {
        var fields = List.of("stages", "modelRows", "modelByteOrder", "nativeRows", "nativeByteOrder", "modelMatched");
        assertTrue(provenance.keySet().containsAll(fields), "Missing explicit Word32X4 preparation mode fields");
        rowsCount(provenance, "modelRows");
        assertEquals("little", provenance.get("modelByteOrder"), "Word32X4 model byte order");
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
        SimdByteArrayEvidence.inventory(root, "word32x4", provenance);
        new SimdByteArrayCorpus("word32x4").verify(root, provenance);
        assertEquals("word32x4-bytearray", provenance.get("vector"), "Exact unsigned memory provenance");
        assertEquals(ByteOrder.LITTLE_ENDIAN, ByteOrder.nativeOrder(), "Word32X4 bounded corpus requires a little-endian host");
        assertEquals(true, provenance.get("positiveAuditsAccepted"));
        var files = new ArrayList<>((List<Map<String, String>>) provenance.get("sources"));
        files.addAll((List<Map<String, String>>) provenance.get("artifacts"));
        for (var file : files) {
            var hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(new File(root, file.get("path")).toPath())));
            assertEquals(file.get("sha256"), hash, "Stale Word32X4 memory source/artifact: " + file.get("path"));
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
        new SimdByteArrayCorpus("word32x4").controls(root);
        SimdByteArrayEvidence.controls(root, "word32x4", manifest);
    }
    @Test void nativeMemoryCoreHasExactCompiledEntriesWithInlining() throws Exception { nativeCore(true); }
    @Test void nativeMemoryCoreHasExactCompiledEntriesWithoutInlining() throws Exception { nativeCore(false); }
    private record Change(String field, Object value) {}
    @Test void preparationModeRejectsCorruptionAndNativeDowngrades() {
        Map<String, Object> nativeMode = Map.of("stages", List.of("pre", "post"), "modelRows", 9666L,
            "modelByteOrder", "little", "nativeRows", 9666L, "nativeByteOrder", "little", "modelMatched", true);
        var exported = with(nativeMode, "stages", List.of("pre"), "nativeRows", null, "nativeByteOrder", null, "modelMatched", null);
        var nativeCorruptions = List.of(new Change("stages", List.of()), new Change("stages", List.of("pre")), new Change("stages", List.of("post", "pre")),
            new Change("stages", List.of("pre", "post", "post")), new Change("nativeRows", null), new Change("nativeRows", 9665L),
            new Change("nativeRows", 9666.0), new Change("nativeRows", 9666.5), new Change("nativeRows", true), new Change("nativeRows", "9666"), new Change("nativeByteOrder", null),
            new Change("nativeByteOrder", "big"), new Change("modelMatched", null), new Change("modelMatched", false));
        var exportCorruptions = List.of(new Change("stages", List.of()), new Change("stages", List.of("pre", "post")),
            new Change("stages", List.of("pre", "pre")), new Change("nativeRows", 0L), new Change("nativeRows", 9666L),
            new Change("nativeByteOrder", "little"), new Change("modelMatched", true), new Change("modelMatched", false));
        var commonCorruptions = List.of(new Change("modelRows", 9665L), new Change("modelRows", 9666.0), new Change("modelRows", 9666.5), new Change("modelRows", true),
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
        for (var stage : (List<String>) provenance.get("stages")) for (var backend : List.of("ast", "bytecode")) withLanguage(true, language -> {
            var module = (Map<String, Object>) Json.parse(Files.readString(new File(directory, stage + "-core/SimdWord32X4ByteArray.json").toPath()));
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
    private void checkFault(PolyglotException error, boolean diagnostic, String label) {
        var message = error.getMessage() == null ? "" : error.getMessage();
        assertTrue(message.contains("unboxed-tuple (host result)"), label + ": " + message);
        assertEquals(diagnostic, message.contains("Diagnostic unsupported path reached:"), label);
    }
    private record Operation(String name, int arity) {}
    @Test void publicHostTupleResultsRejectAtLoadOrTrapBeforeArgumentNormalization() throws Exception {
        var provenance = provenance();
        for (var stage : (List<String>) provenance.get("stages")) for (var backend : List.of("ast", "bytecode")) try (var context = context(true)) {
            var module = (Map<String, Object>) Json.parse(Files.readString(new File(directory, stage + "-core/SimdWord32X4ByteArray.json").toPath()));
            for (var family : List.of("vector", "scalar")) for (var operation : List.of(new Operation("Read", 3), new Operation("Write", 7))) {
                var name = family + operation.name + "Worker";
                for (boolean diagnostic : List.of(false, true)) {
                    var label = stage + "/" + backend + "/" + name + "/diagnostic=" + diagnostic;
                    var request = Json.stringify(Map.of("entry", name, "backend", backend, "diagnosticUnsupported", diagnostic, "modules", List.of(module)));
                    if (!diagnostic) checkFault(assertThrows(PolyglotException.class, () -> context.eval("thc", request), label), diagnostic, label);
                    else {
                        var entry = context.eval("thc", request);
                        assertTrue(entry.canExecute(), label);
                        // The deferred host-result fault must precede guest carrier checks,
                        // host argument normalization, and even the host arity check.
                        var valid = new Object[operation.arity]; Arrays.fill(valid, 0L);
                        var invalid = new Object[operation.arity]; Arrays.fill(invalid, 0L); invalid[0] = "invalid host argument";
                        for (var input : List.of(valid, invalid, new Object[0]))
                            checkFault(assertThrows(PolyglotException.class, () -> entry.execute(input), label), diagnostic, label);
                    }
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
    private void put(byte[] bytes, int at, long value) {
        for (int octet = 0; octet <= 3; octet++) bytes[at + octet] = (byte) (value >>> (8 * octet));
    }
    private long checksum(byte[] bytes, int address) {
        long result = 0;
        long[] weights = {3, 5, 7, 11};
        for (int lane = 0; lane <= 3; lane++) {
            long value = 0;
            for (int octet = 0; octet <= 3; octet++) value |= (bytes[address + 4 * lane + octet] & 255L) << (8 * octet);
            result += value * weights[lane];
        }
        return result;
    }
    /** Explicit little-endian bytes; no fixture-model/runtime-carrier import. */
    private long independentExpected(String name, List<Long> input) {
        int stride = name.startsWith("vector") ? 16 : 4;
        int address = (int) (input.get(0) * stride);
        var bytes = new byte[64];
        if (name.endsWith("UnitCase")) {
            long seed = input.get(1);
            long[] values = {seed, seed + 17, seed * 3 - 29, seed ^ 0x55aa55aaL};
            for (int lane = 0; lane <= 3; lane++) put(bytes, address + 4 * lane, values[lane]);
            long before = checksum(bytes, address);
            if (name.equals("vectorUnitCase")) put(bytes, address + 4, seed ^ 0x80000000L);
            else bytes[address + 7] = (byte) (seed + 101);
            return before + 15 * checksum(bytes, address);
        }
        for (int word = 0; word <= 15; word++) put(bytes, 4 * word, 0x89abcdefL + word * 0x01030507L);
        for (int lane = 0; lane <= 3; lane++) put(bytes, address + 4 * lane, input.get(lane + 1));
        long score = checksum(bytes, address);
        return name.endsWith("WriteCase") || name.endsWith("StoreCase") ? score * 257 + (bytes[input.get(5).intValue()] & 255L) : score;
    }
    private void nativeCore(boolean inlining) throws Exception {
        var provenance = provenance();
        var stages = (List<String>) provenance.get("stages");
        var expected = rows("expected.tsv");
        assertEquals(9666, expected.size());
        for (var row : expected.entrySet()) assertEquals(independentExpected(row.getKey().name(), row.getKey().arguments()), row.getValue(), "Independent bytes: " + row.getKey());
        if (provenance.get("nativeRows") != null) {
            assertEquals(expected, rows("oracle.tsv")); assertEquals((long) expected.size(), integer(provenance.get("nativeRows"), "nativeRows"));
        }
        var entries = (List<Map<String, Object>>) provenance.get("entries");
        var names = new LinkedHashSet<String>();
        for (var family : List.of("vector", "scalar")) for (var op : List.of("Unit", "Index", "Read", "Write", "Store")) names.add(family + op + "Case");
        var actualNames = new LinkedHashSet<Object>(); for (var entry : entries) actualNames.add(entry.get("name"));
        assertEquals(10, entries.size()); assertEquals(names, actualNames);
        var declared = new ArrayList<IntegerSimdModel.Input>();
        for (var entry : entries) for (var row : (List<List<Object>>) entry.get("cases")) {
            var input = new ArrayList<Long>(); for (var value : row) input.add(integer(value, "declared input"));
            declared.add(new IntegerSimdModel.Input((String) entry.get("name"), input));
        }
        assertEquals(declared.size(), new LinkedHashSet<>(declared).size());
        assertEquals(new LinkedHashSet<>(declared), expected.keySet());
        var counts = (Map<String, Object>) provenance.get("expectedGuestCallsByEntry");
        for (var stage : stages) for (var backend : List.of("ast", "bytecode")) withLanguage(inlining, language -> {
            var module = (Map<String, Object>) Json.parse(Files.readString(new File(directory, stage + "-core/SimdWord32X4ByteArray.json").toPath()));
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
                boolean unit = name.endsWith("UnitCase");
                boolean store = name.endsWith("WriteCase") || name.endsWith("StoreCase");
                assertEquals(unit ? 2 : store ? 6 : 5, arity, name + " exact arity");
                assertEquals(unit ? offsets.size() * 18 : store ? 36 * 64 : 36, cases.size(), name + " exact rows");
                var actualOffsets = new LinkedHashSet<Long>(); for (var row : cases) actualOffsets.add(row.getFirst());
                assertEquals(offsets, actualOffsets, name + " safe offsets");
                if (!unit) {
                    boolean unsigned = true;
                    for (var row : cases) {
                        boolean valid = true;
                        for (var value : row.subList(1, 5)) if (value < 0 || value > 0xffff_ffffL) { valid = false; break; }
                        if (!valid) { unsigned = false; break; }
                    }
                    assertTrue(unsigned, name + " unsigned lanes");
                    var boundaries = Set.of(0L, 1L, 65535L, 65536L, 0x7fff_ffffL, 0x8000_0000L, 0x8000_0001L, 0xffff_fffeL, 0xffff_ffffL);
                    for (int lane = 1; lane <= 4; lane++) {
                        var actualBoundaries = new LinkedHashSet<Long>();
                        for (var row : cases) if (boundaries.contains(row.get(lane))) actualBoundaries.add(row.get(lane));
                        assertEquals(boundaries, actualBoundaries, name + " lane" + lane + " boundaries");
                    }
                    if (store) {
                        var groups = new LinkedHashMap<List<Long>, List<List<Long>>>();
                        for (var row : cases) groups.computeIfAbsent(row.subList(0, 5), ignored -> new ArrayList<>()).add(row);
                        for (var group : groups.values()) {
                            var allSelectors = new LinkedHashSet<Long>(); for (long selector = 0; selector <= 63; selector++) allSelectors.add(selector);
                            var selected = new LinkedHashSet<Long>(); for (var row : group) selected.add(row.get(5));
                            assertEquals(allSelectors, selected, name + " full-byte selectors");
                        }
                    }
                }
                var linked = new LinkedHashMap<>(CoreModules.reachable(module, name)); linked.put("instrument", true);
                ExecutableProgram p = backend.equals("ast") ? new Program(language, linked) : new BytecodeProgram(language, linked);
                var host = p.hostEntryTarget(arity); var closure = p.entryValue(name); var target = p.entryTarget(name);
                long callCount = integer(counts.get(name), name + " guest count");
                // Exact immediate State# bodies stay in-frame; worker wrappers
                // additionally call their one opaque worker. Counts come from Core.
                assertEquals(List.of("vectorUnitCase", "scalarUnitCase").contains(name) ? 1L : 2L, callCount);
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

