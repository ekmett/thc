// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import com.oracle.truffle.api.TruffleLanguage;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.Test;
import java.io.File;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Native GHC, independent shortest-distance relaxation and actual strict Core must agree. */
@SuppressWarnings("unchecked")
public class GraphWorkloadTest {
    private final File root = new File(System.getProperty("thc.projectRoot"));
    private final String directory = "build/graph-bfs";
    private final List<String> entries = List.of("graphChecksum", "graphReachable", "graphDistanceTotal", "graphDistanceAt", "graphControl");
    private record Request(String entry, long input) {}
    private record Row(String entry, long input, long result) {}
    private record Control(Map<Integer, List<Integer>> graph, int start) {}
    private final List<Request> requests = requests();
    private List<Request> requests() {
        var sizes = new ArrayList<Long>(List.of(Long.MIN_VALUE, -17L, -1L));
        for (long n = 0; n <= 20; n++) sizes.add(n);
        sizes.addAll(List.of(31L, 32L, 63L, 64L, 127L, 128L, 255L, 256L, 257L, 511L, 512L, 513L, Long.MAX_VALUE));
        var result = new ArrayList<Request>();
        for (var entry : entries.subList(0, 3)) for (long n : sizes) result.add(new Request(entry, n));
        var distances = new ArrayList<Long>();
        for (long n = 0; n <= 12; n++) distances.add(n);
        distances.addAll(List.of(31L, 64L, 127L, 256L, 512L));
        for (long n : distances) {
            var indices = new ArrayList<Long>();
            if (n <= 12) for (long i = 0; i <= n + 1; i++) indices.add(i);
            else indices.addAll(List.of(0L, 1L, n / 2, n - n / 4 - 1, n - n / 4, n - 1, n, n + 1));
            for (long i : indices) result.add(new Request("graphDistanceAt", n * 1024 + i));
        }
        for (long test = 0; test <= 9; test++) for (long i = 0; i <= 19; i++) result.add(new Request("graphControl", test * 32 + i));
        return result;
    }
    private Map<Integer, List<Integer>> graph(long input) {
        int n = (int) Math.max(0, Math.min(512, input)), cut = n - n / 4;
        var graph = new LinkedHashMap<Integer, List<Integer>>();
        for (int i = 0; i < n; i++) {
            int first = i < cut ? 0 : cut, limit = i < cut ? cut : n;
            int next = i + 1 < limit ? i + 1 : first, stride = 1 + ((37 * i + 11) & 7);
            int jump = i + stride < limit ? i + stride : first;
            graph.put(2 * i - n, List.of(2 * next - n, 2 * jump - n, 2 * i - n, 2 * next - n));
        }
        return graph;
    }
    // Synchronous Bellman-Ford-style relaxation, independent of the Haskell FIFO.
    // A round never consumes its own newly computed distances.
    private Map<Integer, Integer> distances(Map<Integer, List<Integer>> graph, int start) {
        if (!graph.containsKey(start)) return Map.of();
        Map<Integer, Integer> current = new LinkedHashMap<>(); current.put(start, 0);
        var vertices = new LinkedHashSet<>(graph.keySet());
        for (var edges : graph.values()) vertices.addAll(edges);
        for (int round = 0; round < vertices.size(); round++) {
            var next = new LinkedHashMap<>(current);
            for (var edge : graph.entrySet()) {
                var distance = current.get(edge.getKey()); if (distance == null) continue;
                for (int destination : edge.getValue()) if (distance + 1 < next.getOrDefault(destination, Integer.MAX_VALUE)) next.put(destination, distance + 1);
            }
            if (next.equals(current)) return current;
            current = next;
        }
        throw new IllegalStateException("Positive unit-edge distances did not reach a fixed point");
    }
    private Control controls(int index) {
        return switch (index) {
            case 0 -> new Control(Map.of(), 0);
            case 1 -> new Control(Map.of(0, List.of()), 0);
            case 2 -> new Control(Map.of(0, List.of(1), 1, List.of(2), 2, List.of()), 0);
            case 3 -> new Control(Map.of(0, List.of(1, 2), 1, List.of(3), 2, List.of(3), 3, List.of()), 0);
            case 4 -> new Control(Map.of(-3, List.of(-3, 7, 7), 7, List.of(-3)), -3);
            case 5 -> new Control(Map.of(-1, List.of(2), 2, List.of(), 9, List.of(10), 10, List.of()), -1);
            case 6 -> new Control(Map.of(0, List.of(1)), 0);
            case 7 -> new Control(Map.of(0, List.of(1)), 99);
            case 8 -> new Control(Map.of(0, List.of(1, 3), 1, List.of(2), 2, List.of(3), 3, List.of()), 0);
            case 9 -> new Control(Map.of(0, List.of(1), 1, List.of(2, 0), 2, List.of(0)), 2);
            default -> throw new IllegalStateException("Unknown control graph");
        };
    }
    private long total(Map<Integer, Integer> found) { int result = 0; for (int value : found.values()) result += value; return result; }
    private long checksum(Map<Integer, Integer> found) {
        long result = 0;
        for (var e : found.entrySet()) result += ((long) e.getKey() & 65535) * 257 + (e.getValue() + 1L) * (1 + ((long) e.getKey() & 1023));
        return (result + 17 * found.size() + 31L * total(found)) & 2147483647;
    }
    private long expected(String entry, long input) {
        if (entry.equals("graphControl")) {
            var control = controls((int) (input / 32)); var found = distances(control.graph, control.start);
            int field = (int) (input & 31);
            return switch (field) { case 0 -> found.size(); case 1 -> total(found); case 2 -> checksum(found); default -> found.getOrDefault(field - 6, -1); };
        }
        int n = (int) Math.max(0, Math.min(512, entry.equals("graphDistanceAt") ? input / 1024 : input));
        var found = distances(graph(n), -n);
        return switch (entry) {
            case "graphChecksum" -> checksum(found); case "graphReachable" -> found.size(); case "graphDistanceTotal" -> total(found);
            case "graphDistanceAt" -> found.getOrDefault(2 * (int) (input & 1023) - n, -1);
            default -> throw new IllegalStateException("Unknown graph entry");
        };
    }
    private List<Row> rows(String text) {
        var rows = new ArrayList<Row>(); var actual = new ArrayList<Request>();
        for (String line : text.split("\\R", -1)) if (!line.isEmpty()) {
            var parts = line.split("\t", -1); if (parts.length != 3) throw new IllegalArgumentException();
            var row = new Row(parts[0], Long.parseLong(parts[1]), Long.parseLong(parts[2])); rows.add(row); actual.add(new Request(row.entry, row.input));
        }
        if (!actual.equals(requests)) throw new IllegalArgumentException("Changed graph oracle input order/domain");
        for (var row : rows) if (row.result != expected(row.entry, row.input)) throw new IllegalArgumentException("Native graph/model mismatch: " + row);
        return rows;
    }
    private Map<String, Object> read(String path) throws Exception { return (Map<String, Object>) Json.parse(Files.readString(new File(root, path).toPath())); }
    private String digest(String path) throws Exception {
        var file = root.toPath().resolve(path).normalize();
        if (!file.startsWith(root.toPath()) || new File(path).isAbsolute()) throw new IllegalArgumentException("Nonlocal graph evidence: " + path);
        var digest = MessageDigest.getInstance("SHA-256");
        try (var input = new java.io.BufferedInputStream(Files.newInputStream(file))) {
            byte[] buffer = new byte[65536]; for (int count; (count = input.read(buffer)) >= 0;) digest.update(buffer, 0, count);
        }
        return HexFormat.of().formatHex(digest.digest());
    }
    private Map<String, Object> evidence() throws Exception {
        var manifest = read(directory + "/manifest.json");
        assertEquals(1L, manifest.get("schema")); assertEquals("9.14.1", manifest.get("ghc"));
        assertEquals(entries, manifest.get("entries")); assertEquals((long) requests.size(), manifest.get("nativeRows"));
        for (var kind : List.of("inputHashes", "artifactHashes")) {
            var hashes = (Map<String, String>) manifest.get(kind); assertFalse(hashes.isEmpty());
            for (var e : hashes.entrySet()) assertEquals(e.getValue(), digest(e.getKey()), "Stale graph " + kind + ": " + e.getKey());
        }
        var inputs = (Map<String, String>) manifest.get("inputHashes"); var artifacts = (Map<String, String>) manifest.get("artifactHashes");
        for (var path : List.of("src/examples/THC/GraphWorkload.hs", "src/examples/LibraryOracle.hs", "test/haskell-fixtures/GraphFixtures.hs", "test/haskell-fixtures/FixtureSupport.hs", "test/haskell-fixtures/Main.hs", "thc.cabal", "bin/export-core.sh", "bin/export-boot.py", "bin/audit-core.py", "bin/core-capabilities.json")) assertTrue(inputs.containsKey(path));
        for (var path : List.of(directory + "/oracle.tsv", directory + "/inputs.tsv", directory + "/boot/boot-provenance.json")) assertTrue(artifacts.containsKey(path));
        assertEquals("b1c1127ff57b6f844d0b30cea54a62c01ca146a49ed4953485be1af389a94bd8", artifacts.get("vendor/archives/containers-0.8.tar.gz"));
        var container = (Map<String, Object>) manifest.get("containers");
        assertEquals(artifacts.get("vendor/archives/containers-0.8.tar.gz"), container.get("sha256")); assertEquals(List.of(), container.get("sourcePatches")); assertEquals(false, container.get("sourceNotes"));
        var sources = (Map<String, String>) container.get("sourceHashes"); var sourceRoot = (String) container.get("root");
        for (var path : List.of("LICENSE", "src/Data/Sequence/Internal.hs", "src/Data/IntSet/Internal.hs", "src/Data/IntMap/Strict/Internal.hs")) assertTrue(sources.containsKey(sourceRoot + "/" + path), path);
        for (var e : sources.entrySet()) { assertTrue(e.getKey().startsWith(sourceRoot + "/")); assertEquals(e.getValue(), artifacts.get(e.getKey())); }
        var boot = read(directory + "/boot/boot-provenance.json"); assertEquals(boot, manifest.get("bootProvenance"));
        assertEquals("ghc-9.14.1-release", boot.get("ghcTag")); assertEquals(List.of(), boot.get("sourcePatches"));
        for (var source : (List<Map<String, String>>) boot.get("sources")) assertEquals(source.get("sha256"), inputs.get(source.get("path")));
        var original = (Map<String, Object>) manifest.get("originalInterfaces"); assertEquals(List.of(), original.get("sourcePatches"));
        var originalModules = (List<String>) original.get("modules"); var moduleNames = new ArrayList<Object>();
        for (var path : originalModules) moduleNames.add(read(path).get("module"));
        assertEquals(List.of("GHC.Internal.Classes", "GHC.Internal.List"), moduleNames);
        for (var path : originalModules) { assertTrue(artifacts.containsKey(path)); var module = read(path); assertEquals("ghc-internal", module.get("unit")); assertEquals("optimized-Core-after-Tidy-before-CorePrep", module.get("boundary")); }
        var provenance = (String) original.get("provenance"); assertTrue(artifacts.containsKey(provenance));
        var inventory = (List<Map<String, Object>>) read(provenance).get("interfaces");
        for (var source : (List<Map<String, String>>) original.get("sources")) {
            assertEquals(source.get("sha256"), artifacts.get(source.get("copy"))); boolean found = false;
            for (var item : inventory) if (Objects.equals(item.get("module"), source.get("module")) && Objects.equals(item.get("interface"), source.get("interface")) && Boolean.TRUE.equals(item.get("completeCore"))) found = true;
            assertTrue(found);
        }
        var stages = (List<Map<String, Object>>) manifest.get("stages"); var names = new ArrayList<Object>(); for (var stage : stages) names.add(stage.get("stage")); assertEquals(List.of("post"), names);
        for (var stage : stages) {
            var modules = (List<String>) stage.get("modules"); assertEquals(modules.size(), new HashSet<>(modules).size());
            for (var suffix : List.of("/THC.GraphWorkload.json", "/Data.Sequence.Internal.json", "/Data.IntSet.Internal.json", "/Data.IntMap.Internal.json")) { boolean found = false; for (var module : modules) if (module.endsWith(suffix)) found = true; assertTrue(found); }
            assertTrue(modules.containsAll(originalModules)); for (var module : modules) assertTrue(artifacts.containsKey(module), "Unfingerprinted Core module " + module);
            var audits = (Map<String, String>) stage.get("audits"); assertEquals(new HashSet<>(entries), audits.keySet());
            for (var e : audits.entrySet()) { assertTrue(artifacts.containsKey(e.getValue())); var audit = read(e.getValue()); assertEquals(List.of("main:THC.GraphWorkload." + e.getKey()), audit.get("roots")); assertEquals(true, audit.get("accepted"), "Strict graph frontier retained at " + e.getValue() + ": " + audit.get("missingGlobals") + "; " + audit.get("issues")); assertEquals(List.of(), audit.get("missingGlobals")); assertEquals(List.of(), audit.get("issues")); }
            assertEquals(true, stage.get("strictAccepted"));
        }
        assertEquals(true, manifest.get("strictAccepted")); return manifest;
    }
    @Test public void smallGraphControlsHaveIndependentExactShortestDistances() {
        var expected = List.of(Map.of(), Map.of(0, 0), Map.of(0, 0, 1, 1, 2, 2), Map.of(0, 0, 1, 1, 2, 1, 3, 2), Map.of(-3, 0, 7, 1), Map.of(-1, 0, 2, 1), Map.of(0, 0, 1, 1), Map.of(), Map.of(0, 0, 1, 1, 2, 2, 3, 1), Map.of(2, 0, 0, 1, 1, 2));
        for (int i = 0; i < expected.size(); i++) { var control = controls(i); assertEquals(expected.get(i), distances(control.graph, control.start), "control " + i); }
        for (int n = 0; n <= 512; n++) assertEquals(n - n / 4, distances(graph(n), -n).size());
    }
    @Test public void nativeRowsMatchModelAndRejectMissingReorderedOrAlteredRows() throws Exception {
        evidence(); var text = Files.readString(new File(root, directory + "/oracle.tsv").toPath()); assertEquals(requests.size(), rows(text).size());
        var lines = new ArrayList<>(List.of(text.stripTrailing().split("\\R"))); var reversed = new ArrayList<>(lines); Collections.reverse(reversed);
        var duplicate = new ArrayList<>(lines); duplicate.add(lines.getFirst()); var changed = new ArrayList<>(lines); String first = lines.getFirst(); changed.set(0, first.substring(0, first.lastIndexOf('\t')) + "\t99999999");
        for (var bad : List.of(lines.subList(1, lines.size()), reversed, duplicate, changed)) assertThrows(IllegalArgumentException.class, () -> rows(String.join("\n", bad)));
    }
    private Context context(boolean compiled, boolean inlining) { return Context.newBuilder("thc").allowExperimentalOptions(true).option("engine.Compilation", Boolean.toString(compiled)).option("compiler.Inlining", Boolean.toString(inlining)).option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw").option("engine.SingleTierCompilationThreshold", "10000000").build(); }
    private Map<String, Object> diagnostics(Value function) { return (Map<String, Object>) Json.parse(function.getMember("diagnostics").asString()); }
    private long compiledEntries(Value function) { return ((Number) diagnostics(function).get("compiledEntries")).longValue(); }
    private void clean(Value function, String backend) {
        var state = diagnostics(function); assertEquals(backend, state.get("backend")); assertEquals("reject-at-load", state.get("unsupportedPolicy")); assertEquals(List.of(), state.get("deferredUnsupported"));
        for (var counter : List.of("unsupportedTraps", "blackholes")) assertEquals(0L, ((Number) state.get(counter)).longValue());
        var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); var pools = language.getHandoffState().get();
        assertEquals(0, pools.getArguments().getDepth()); assertEquals(0, pools.getResults().getDepth()); assertEquals(0, pools.getArguments().retainedReferences()); assertEquals(0, pools.getResults().retainedReferences());
    }
    @Test public void strictOriginalClosureExecutesAllNativeRowsOnBothBackends() throws Exception { execute(false, false); }
    @Test public void firstCompiledGraphCallsWithoutInliningMatchNative() throws Exception { execute(true, false); }
    @Test public void firstCompiledGraphCallsWithInliningMatchNative() throws Exception { execute(true, true); }
    private void execute(boolean compiled, boolean inlining) throws Exception {
        var manifest = evidence(); var grouped = new LinkedHashMap<String, List<Row>>(); for (var row : rows(Files.readString(new File(root, directory + "/oracle.tsv").toPath()))) grouped.computeIfAbsent(row.entry, ignored -> new ArrayList<>()).add(row);
        for (var stage : (List<Map<String, Object>>) manifest.get("stages")) for (var backend : List.of("ast", "bytecode")) for (var entry : compiled ? List.of("graphChecksum", "graphControl") : entries) try (var context = context(compiled, inlining)) {
            var modules = new ArrayList<String>(); for (var module : (List<String>) stage.get("modules")) modules.add(new File(root, module).getPath());
            var source = Source.newBuilder("thc", CoreModules.request(modules, entry, true, false, backend), "graph:" + stage.get("stage") + ":" + entry).cached(false).buildLiteral(); context.enter();
            try {
                var function = context.eval(source); var cases = grouped.get(entry);
                for (var row : cases) assertEquals(row.result, function.execute(row.input).asLong(), backend + "/" + row); clean(function, backend);
                if (compiled) {
                    assertTrue(function.invokeMember("compile").asBoolean());
                    // No post-install settling calls or retries; recursive counts depend on the data.
                    for (var row : cases.reversed()) { long before = compiledEntries(function); assertEquals(row.result, function.execute(row.input).asLong(), "compiled " + backend + "/" + row); assertTrue(compiledEntries(function) > before, "First installed graph entry: " + backend + "/" + row); clean(function, backend); }
                } else assertEquals(0L, compiledEntries(function));
            } finally { context.leave(); }
        }
    }
}
