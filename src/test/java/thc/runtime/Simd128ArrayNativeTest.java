// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

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
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import static org.junit.jupiter.api.Assertions.*;

/** Original GHC LLVM/Core bytes compared with a separate scalar-byte model. */
@SuppressWarnings("unchecked")
class Simd128ArrayNativeTest {
    private final File root = new File(System.getProperty("thc.projectRoot"));
    private final String directory = "build/simd128-arrays";
    private final List<String> shapes = List.of("int8X16", "word8X16", "int16X8", "word16X8", "int64X2", "word64X2");
    private final List<String> entries = entries();
    private List<String> entries() {
        var result = new ArrayList<String>();
        for (var shape : shapes) for (var op : List.of("Index", "Read", "Write"))
            for (var mode : List.of("Packed", "Scalar")) result.add(shape + op + mode);
        return result;
    }
    private final List<Long> seeds = List.of(Long.MIN_VALUE, -4294967297L, -65537L, -32769L, -129L, -1L, 0L, 1L,
        127L, 128L, 255L, 256L, 32767L, 65535L, 4294967297L, Long.MAX_VALUE);
    private final Pattern pattern = Pattern.compile("(int|word)(8|16|64)X(16|8|2)(Index|Read|Write)(Packed|Scalar)");
    private record Input(String entry, long seed, long offset) {}
    private Matcher fields(String entry) {
        var matcher = pattern.matcher(entry);
        if (!matcher.matches()) throw new IllegalStateException("Unmatched vector entry: " + entry);
        return matcher;
    }
    private int width(String entry) { return Integer.parseInt(fields(entry).group(2)) / 8; }
    private final List<Input> requests = requests();
    private List<Input> requests() {
        var result = new ArrayList<Input>();
        for (var entry : entries) {
            long end = 32L / width(entry);
            for (long seed : seeds) for (long offset : entry.endsWith("Scalar") ? List.of(0L, 1L, 2L, end - 1, end) : List.of(0L, 1L, 2L))
                result.add(new Input(entry, seed, offset));
        }
        return result;
    }
    // No Vector API or THC primitive implementation participates in this model.
    private long expected(Input input) {
        var match = fields(input.entry);
        int width = Integer.parseInt(match.group(2)) / 8;
        int lanes = Integer.parseInt(match.group(3));
        int offset = (int) (input.offset * (match.group(5).equals("Scalar") ? width : 16));
        var bytes = new byte[48];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) (input.seed + i * 37);
        if (match.group(4).equals("Write")) {
            for (int lane = 0; lane < lanes; lane++) {
                long value = input.seed * 23 + lane * 97;
                for (int octet = 0; octet < width; octet++) bytes[offset + lane * width + octet] = (byte) (value >>> (octet * 8));
            }
            long result = 0;
            for (int i = 0; i < bytes.length; i++) result += (bytes[i] & 255L) * (2L * i + 1);
            return result;
        }
        long result = 0;
        for (int lane = 0; lane < lanes; lane++) {
            long raw = 0;
            for (int octet = 0; octet < width; octet++) raw |= (bytes[offset + lane * width + octet] & 255L) << (octet * 8);
            long value = match.group(1).equals("word") || width == 8 ? raw : raw << (64 - width * 8) >> (64 - width * 8);
            result += value * (2L * lane + 1);
        }
        return result;
    }
    private record Row(Input input, long value) {}
    private List<Row> oracle(String text) {
        var rows = new ArrayList<Row>();
        for (var line : text.split("\\r\\n|\\n|\\r", -1)) if (!line.isEmpty()) {
            var fields = line.split("\t", -1); require(fields.length == 4, "Failed requirement.");
            rows.add(new Row(new Input(fields[0], Long.parseLong(fields[1]), Long.parseLong(fields[2])), Long.parseLong(fields[3])));
        }
        var domain = new ArrayList<Input>();
        for (var row : rows) domain.add(row.input);
        require(domain.equals(requests), "Changed SIMD128 native row domain/order");
        for (var row : rows) require(expected(row.input) == row.value, "Native/model mismatch: " + row.input + ": " + row.value);
        return rows;
    }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalArgumentException(message); }
    private Map<String, Object> read(String path) throws Exception {
        return (Map<String, Object>) Json.parse(Files.readString(new File(root, path).toPath()));
    }
    private String digest(String path) throws Exception {
        var file = root.toPath().resolve(path).normalize();
        require(!new File(path).isAbsolute() && file.startsWith(root.toPath()), "Failed requirement.");
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }
    private Map<String, Object> evidence() throws Exception {
        assertEquals(ByteOrder.LITTLE_ENDIAN, ByteOrder.nativeOrder(), "Pinned SIMD128 oracle platform");
        var proof = read(directory + "/manifest.json");
        assertEquals(1L, proof.get("schema")); assertEquals("9.14.1", proof.get("ghc"));
        assertEquals(entries, proof.get("entries")); assertEquals(2304L, proof.get("requests"));
        boolean arm = List.of("aarch64", "arm64").contains(System.getProperty("os.arch").toLowerCase(Locale.ROOT));
        assertEquals(arm ? null : 2304L, proof.get("nativeRows"), "Native evidence cannot be downgraded by a manifest flag");
        for (var kind : List.of("inputHashes", "artifactHashes")) {
            var files = (Map<String, String>) proof.get(kind); assertTrue(!files.isEmpty());
            for (var item : files.entrySet()) assertEquals(item.getValue(), digest(item.getKey()), "Stale " + kind + ": " + item.getKey());
        }
        var inputs = (Map<String, String>) proof.get("inputHashes");
        for (var path : List.of("test/fixtures/compiler/Simd128ArrayAudit.hs", "test/fixtures/compiler/Simd128ArrayNative.hs",
                "test/haskell-fixtures/Simd128ArrayFixtures.hs", "test/haskell-fixtures/FixtureSupport.hs",
                "test/haskell-fixtures/Main.hs", "thc.cabal", "bin/export-core.sh", "bin/core_vector_memory.py",
                "bin/core_vectors.py", "bin/simd-families.json", "bin/core-capabilities.json"))
            assertTrue(inputs.containsKey(path), "Unfingerprinted source: " + path);
        var artifacts = (Map<String, String>) proof.get("artifactHashes");
        assertTrue(artifacts.containsKey(directory + "/inputs.tsv"));
        var recordedInputs = new ArrayList<Input>();
        for (var line : Files.readAllLines(new File(root, directory + "/inputs.tsv").toPath())) {
            var fields = line.split("\t", -1);
            recordedInputs.add(new Input(fields[0], Long.parseLong(fields[1]), Long.parseLong(fields[2])));
        }
        assertEquals(requests, recordedInputs);
        if (!arm) {
            assertTrue(artifacts.containsKey(directory + "/oracle.tsv")); assertTrue(artifacts.containsKey(directory + "/native/oracle"));
            oracle(Files.readString(new File(root, directory + "/oracle.tsv").toPath()));
        }
        var stages = (Map<String, Map<String, Object>>) proof.get("stages");
        assertEquals(arm ? Set.of("pre") : Set.of("pre", "post"), stages.keySet());
        for (var stage : stages.entrySet()) {
            var core = (String) stage.getValue().get("core");
            assertTrue(artifacts.containsKey(core));
            assertEquals(stage.getKey().equals("pre") ? "optimized-Core-before-Tidy" : "optimized-Core-after-Tidy-before-CorePrep", read(core).get("boundary"));
            var records = (Map<String, Map<String, Object>>) stage.getValue().get("entries");
            assertEquals(new LinkedHashSet<>(entries), records.keySet());
            for (var item : records.entrySet()) {
                var name = item.getKey(); var record = item.getValue();
                var path = (String) record.get("audit"); assertTrue(artifacts.containsKey(path));
                var audit = read(path);
                assertEquals(List.of("main:Simd128ArrayAudit." + name), audit.get("roots"));
                assertEquals(true, audit.get("accepted"));
                assertEquals(List.of(), audit.get("issues")); assertEquals(List.of(), audit.get("missingGlobals"));
                var shape = (Map<String, Object>) record.get("structure");
                assertEquals(name.contains("Write") ? 4L : 3L, shape.get("guestCalls"));
                assertEquals(1L, shape.get("immediateLambdas"));
                var functions = new ArrayList<>(List.of("main:Simd128ArrayAudit." + name, "main:Simd128ArrayAudit.initialize"));
                if (name.contains("Write")) functions.add("main:Simd128ArrayAudit.checksum");
                assertEquals(functions, shape.get("globalFunctions"));
            }
        }
        return proof;
    }
    @Test void nativeCorpusMatchesIndependentModelAndRejectsMissingReorderedAndAlteredRows() throws Exception {
        evidence();
        var lines = new ArrayList<String>();
        for (var input : requests) lines.add(input.entry + "\t" + input.seed + "\t" + input.offset + "\t" + expected(input));
        assertEquals(requests.size(), oracle(String.join("\n", lines)).size());
        var duplicate = new ArrayList<>(lines); duplicate.add(lines.getFirst());
        var altered = new ArrayList<>(lines);
        altered.set(0, lines.getFirst().substring(0, lines.getFirst().lastIndexOf('\t')) + "\t1234567");
        for (var bad : List.of(lines.subList(1, lines.size()), lines.reversed(), duplicate, altered))
            assertThrows(IllegalArgumentException.class, () -> oracle(String.join("\n", bad)));
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
    @Test void nativeOriginalCoreHasExactFirstInstalledEntriesWithoutInlining() throws Exception { execute(false); }
    @Test void nativeOriginalCoreHasExactFirstInstalledEntriesWithInlining() throws Exception { execute(true); }
    private RootCallTarget selected(RootCallTarget host, RootCallTarget original) {
        var matches = new ArrayList<DirectCallNode>();
        for (var call : NodeUtil.findAllNodeInstances(host.getRootNode(), DirectCallNode.class))
            if (call.getCallTarget() == original) matches.add(call);
        assertEquals(1, matches.size());
        return (RootCallTarget) matches.getFirst().getCurrentCallTarget();
    }
    private long count(ExecutableProgram p) { return ((Number) p.diagnostics().get("compiledEntries")).longValue(); }
    private void valid(RootCallTarget target, String label) throws Exception {
        assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target), label);
    }
    private void call(Input input, RootCallTarget host, Object closure, Language language, String stage, String backend, boolean inlining) {
        assertEquals(expected(input), Calls.target(host, new Object[]{closure, new Object[]{input.seed, input.offset}}),
            stage + "/" + backend + "/" + input + "/inlining=" + inlining);
        var pools = language.getHandoffState().get();
        assertEquals(0, pools.getArguments().getDepth()); assertEquals(0, pools.getResults().getDepth());
        assertEquals(0, pools.getArguments().retainedReferences()); assertEquals(0, pools.getResults().retainedReferences());
    }
    private void execute(boolean inlining) throws Exception {
        var proof = evidence();
        for (var stageEntry : ((Map<String, Map<String, Object>>) proof.get("stages")).entrySet())
            for (var backend : List.of("ast", "bytecode"))
                try (var context = Context.newBuilder("thc").allowExperimentalOptions(true).option("compiler.Inlining", Boolean.toString(inlining))
                        .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
                        .option("engine.SingleTierCompilationThreshold", "10000000").option("engine.CompilationFailureAction", "Throw").build()) {
                    var stage = stageEntry.getKey(); var data = stageEntry.getValue();
                    context.initialize("thc"); context.enter();
                    try {
                        var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                        var core = read((String) data.get("core"));
                        for (var entry : entries) {
                            var linked = new LinkedHashMap<>(CoreModules.reachable(core, entry)); linked.put("instrument", true);
                            ExecutableProgram p = backend.equals("ast") ? new Program(language, linked) : new BytecodeProgram(language, linked);
                            var host = p.hostEntryTarget(2); var closure = p.entryValue(entry); var original = p.entryTarget(entry);
                            var cases = new ArrayList<Input>();
                            for (var request : requests) if (request.entry.equals(entry)) cases.add(request);
                            for (var input : cases) call(input, host, closure, language, stage, backend, inlining);
                            var target = selected(host, original);
                            var targets = activeTargets(target);
                            int expectedCalls = entry.contains("Write") ? 4 : 3;
                            assertEquals(expectedCalls, targets.size(), stage + "/" + backend + "/" + entry + " root shape");
                            var installedTargets = new ArrayList<>(targets); installedTargets.add(host);
                            for (var installed : installedTargets) {
                                installed.getClass().getMethod("compile", boolean.class).invoke(installed, true);
                                valid(installed, "initial " + entry);
                            }
                            var runtime = Truffle.getRuntime();
                            runtime.getClass().getMethod("bypassedInstalledCode", Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget")).invoke(runtime, host);
                            for (var input : cases.reversed()) {
                                var label = stage + "/" + backend + "/" + input + "/inlining=" + inlining;
                                long before = count(p);
                                call(input, host, closure, language, stage, backend, inlining);
                                assertEquals((long) expectedCalls, count(p) - before, label + " exact compiled entries");
                                assertSame(target, selected(host, original), label);
                                var active = activeTargets(target);
                                assertEquals(targets.size(), active.size(), label);
                                boolean identities = true;
                                for (var candidate : active) {
                                    boolean found = false;
                                    for (var prior : targets) if (prior == candidate) { found = true; break; }
                                    if (!found) { identities = false; break; }
                                }
                                assertTrue(identities, label);
                                for (var installed : installedTargets) valid(installed, label);
                            }
                            for (var counter : List.of("unsupportedTraps", "blackholes"))
                                assertEquals(0L, ((Number) p.diagnostics().get(counter)).longValue());
                        }
                    } finally { context.leave(); }
                }
    }
}
