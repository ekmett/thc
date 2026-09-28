// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import thc.Json;
import static org.junit.jupiter.api.Assertions.*;

/** Independently derived closed inventories; producer lists are not authority. */
@SuppressWarnings("unchecked")
final class SimdByteArrayEvidence {
    private SimdByteArrayEvidence() {}
    private static final Map<String, String> shapes = Map.of("int32x4", "Int32X4", "word32x4", "Word32X4", "floatx4", "FloatX4", "doublex2", "DoubleX2");
    private static final Map<String, List<String>> wrong = Map.of(
        "int32x4", List.of("Word32ElemRep"), "word32x4", List.of("Int32ElemRep"),
        "floatx4", List.of("Int32ElemRep", "Word32ElemRep", "DoubleElemRep"),
        "doublex2", List.of("Int64ElemRep", "Int32ElemRep", "Word32ElemRep", "FloatElemRep"));
    private static final List<String> hostEntries = List.of("vectorArgument", "readVectorEscape",
        "vectorReadWorker", "vectorWriteWorker", "scalarReadWorker", "scalarWriteWorker");
    private static final List<String> frontiers = List.of("readTupleEscape");
    private static final List<String> local = List.of("vectorIndex", "vectorRead", "vectorWrite", "scalarIndex", "scalarRead", "scalarWrite");
    private static boolean floating(String family) { return family.equals("floatx4") || family.equals("doublex2"); }
    private static void records(Map<String, Object> manifest, String field, Set<String> required) {
        var rows = (List<Map<String, String>>) manifest.get(field);
        var paths = new HashSet<String>();
        for (var row : rows) paths.add(row.get("path"));
        assertEquals(required, paths, field + " inventory");
        assertEquals(required.size(), rows.size(), field + " duplicates");
        for (var row : rows) {
            assertEquals(Set.of("path", "sha256"), row.keySet());
            assertTrue(Objects.requireNonNull(row.get("sha256")).matches("[0-9a-f]{64}"));
        }
    }
    static void inventory(File root, String family, Map<String, Object> manifest) {
        String directory = "build/simd-" + family + "-bytearray";
        String module = "Simd" + Objects.requireNonNull(shapes.get(family)) + "ByteArray";
        String attempt = (String) manifest.get("attempt");
        assertTrue(attempt.matches(directory + "/prepare-run-[A-Za-z0-9]+"));
        assertEquals(1L, manifest.get("schema"));
        assertEquals(family + "-bytearray", manifest.get("vector"));
        var stages = (List<String>) manifest.get("stages");
        assertTrue(stages.equals(List.of("pre")) || stages.equals(List.of("pre", "post")));
        boolean nativeRows = stages.size() == 2;
        var corpus = new SimdByteArrayCorpus(family);
        assertEquals((long) corpus.rowCount, manifest.get("modelRows"));
        assertEquals(nativeRows ? (Long) (long) corpus.rowCount : null, manifest.get("nativeRows"));
        assertEquals(nativeRows ? Boolean.TRUE : null, manifest.get("modelMatched"));
        assertEquals("little", manifest.get("modelByteOrder"));
        assertEquals(nativeRows ? "little" : null, manifest.get("nativeByteOrder"));
        var entries = corpus.cases().keySet();
        var graphs = new ArrayList<String>();
        for (String offset : List.of("vector", "scalar")) {
            graphs.add(offset + "Index" + (floating(family) ? "Graph" : "Worker"));
            graphs.add(offset + "StoreGraph");
        }
        assertEquals(hostEntries, manifest.get("hostEntries"));
        assertEquals(frontiers, manifest.get("frontiers"));
        var fresh = new ArrayList<String>();
        var names = new ArrayList<>(entries);
        names.addAll(graphs); names.addAll(hostEntries); names.addAll(frontiers);
        for (String stage : stages) for (String name : names) fresh.add(stage + "-" + name);
        var mutations = new ArrayList<String>();
        var allStages = new ArrayList<>(stages);
        allStages.addAll(List.of("retained-pre", "retained-post"));
        for (String stage : allStages) for (String element : wrong.get(family)) for (String operation : local)
            mutations.add(stage + "-wrong-" + element + "-" + operation);
        var retained = new ArrayList<String>();
        for (String stage : List.of("pre", "post")) for (String operation : local) retained.add("retained-" + stage + "-" + operation + "Case");
        var audits = new ArrayList<>(fresh);
        audits.addAll(mutations); audits.addAll(retained);
        var commands = new ArrayList<>(List.of("ghc-version", "ghc-info", "host", "architecture", "system", "compiler-build",
            "retained-provenance", "retained-pre", "retained-post"));
        for (String stage : stages) commands.add(stage + "-export");
        commands.addAll(audits);
        if (nativeRows) {
            commands.addAll(List.of("native-build", "native-oracle"));
            if (floating(family)) commands.add("snan-oracle");
        }
        var artifacts = new ArrayList<>(List.of(directory + "/expected.tsv", directory + "/requests.tsv"));
        for (String stage : stages) {
            artifacts.add(directory + "/" + stage + "-core/" + module + ".json");
            artifacts.add(directory + "/" + stage + "-audit.json");
        }
        for (String audit : audits) artifacts.add(attempt + "/audits/" + audit + ".json");
        for (String mutation : mutations) artifacts.add(attempt + "/mutations/" + mutation + ".json");
        artifacts.addAll(List.of(attempt + "/retained/pre.json", attempt + "/retained/post.json"));
        if (!floating(family)) artifacts.add(attempt + "/retained-original-source.hs");
        for (String command : commands) for (String suffix : List.of("stdout", "stderr", "command.json"))
            artifacts.add(attempt + "/commands/" + command + "." + suffix);
        if (nativeRows) {
            artifacts.addAll(List.of(directory + "/oracle.tsv", directory + "/native/" + family + "-bytearray-oracle"));
            if (floating(family)) for (String file : List.of("snan-expected.tsv", "snan-requests.tsv", "snan-oracle.tsv")) artifacts.add(directory + "/" + file);
        }
        records(manifest, "artifacts", new HashSet<>(artifacts));
        String retainedBase = "bench/experiments/" + family + "-bytearray/evidence-x86_64" + (family.equals("doublex2") ? "/captures/doublex2" : "");
        var sources = new ArrayList<>(List.of("compiler/test-fixtures/" + module + ".hs", "compiler/test-fixtures/" + module + "Native.hs",
            "test/haskell-fixtures/SimdByteArrayFixtures.hs", "test/haskell-fixtures/SimdByteArrayModel.hs",
            "test/haskell-fixtures/FixtureSupport.hs", "test/haskell-fixtures/Main.hs", "thc.cabal",
            "scripts/audit-core.py", "scripts/core-capabilities.json", "src/main/java/thc/runtime/VectorMemoryFamily.java",
            "src/main/java/thc/runtime/VectorMemoryOp.java", "src/main/java/thc/runtime/VectorReadCase.java",
            "src/main/java/thc/runtime/CoreVectorMemory.java", "src/main/java/thc/runtime/VectorByteArrayExpression.java",
            "src/main/resources/thc/scalar-primop-signatures.json", "compiler/build.sh", "compiler/export.sh", "compiler/toolchain.sh", "compiler/plugin.py"));
        if (family.equals("doublex2")) sources.add("src/main/java/thc/runtime/VectorMemory.java");
        for (var file : Objects.requireNonNull(new File(root, "compiler/THC").listFiles()))
            if (file.getName().endsWith(".hs")) sources.add("compiler/THC/" + file.getName());
        for (var file : Objects.requireNonNull(new File(root, "scripts").listFiles()))
            if (file.getName().startsWith("core_") && file.getName().endsWith(".py")) sources.add("scripts/" + file.getName());
        for (String file : List.of("pre-core.json.gz", "post-core.json.gz", floating(family) ? "input-provenance.json.gz" : "native/provenance.json.gz"))
            sources.add(retainedBase + "/" + file);
        records(manifest, "sources", new HashSet<>(sources));
        String controls = floating(family) ? "familyNegativeControls" : family.equals("int32x4") ? "unsignedNegativeControls" : "signedNegativeControls";
        assertEquals(new HashSet<>(stages), ((Map<?, ?>) manifest.get(controls)).keySet());
        assertEquals(Set.of("pre", "post"), ((Map<?, ?>) manifest.get("retainedControls")).keySet());
        assertEquals(commands.size(), ((List<?>) manifest.get("commands")).size());
    }
    private static Map<String, Object> with(Map<String, Object> original, String key, Object value) {
        var copy = new LinkedHashMap<>(original); copy.put(key, value); return copy;
    }
    static void controls(File root, String family, Map<String, Object> manifest) throws Exception {
        String directory = "build/simd-" + family + "-bytearray";
        for (String attempt : List.of("prepare-run-123", directory + "/prepare-run-", directory + "/prepare-run-../escape",
                directory + "/prepare-run-123/child", "/" + directory + "/prepare-run-123", "build/other/prepare-run-123"))
            assertThrows(AssertionError.class, () -> inventory(root, family, with(manifest, "attempt", attempt)), attempt);
        // Metadata-only controls; native test callers hash the actual bytes.
        for (String field : List.of("sources", "artifacts")) {
            var rows = (List<Map<String, String>>) manifest.get(field);
            for (int index = 0; index < rows.size(); index++) {
                var omitted = new ArrayList<>(rows); omitted.remove(index);
                assertThrows(AssertionError.class, () -> inventory(root, family, with(manifest, field, omitted)));
            }
            var duplicate = new ArrayList<>(rows); duplicate.add(rows.getFirst());
            var escape = new ArrayList<>(rows); escape.add(Map.of("path", "../escape", "sha256", "0".repeat(64)));
            var malformed = new ArrayList<>(rows);
            var first = new LinkedHashMap<>(rows.getFirst()); first.put("sha256", "not-a-hash"); malformed.set(0, first);
            for (var bad : List.of(duplicate, escape, malformed))
                assertThrows(AssertionError.class, () -> inventory(root, family, with(manifest, field, bad)));
        }
        var commands = new ArrayList<String>();
        for (var row : (List<Map<String, String>>) manifest.get("artifacts")) {
            String path = Objects.requireNonNull(row.get("path"));
            if (path.endsWith(".command.json")) commands.add(path);
        }
        for (String path : commands) {
            var command = (Map<String, Object>) Json.parse(Files.readString(new File(root, path).toPath()));
            String filename = new File(path).getName();
            String name = filename.substring(0, filename.length() - ".command.json".length());
            boolean negative = name.contains("-wrong-");
            if (!negative) for (String frontier : frontiers) if (name.equals("pre-" + frontier) || name.equals("post-" + frontier)) { negative = true; break; }
            assertEquals(negative ? 1L : 0L, command.get("expectedExit"), path);
            assertEquals(command.get("expectedExit"), command.get("exit"), path);
            assertNull(command.get("timedOut"), path);
        }
    }
}
