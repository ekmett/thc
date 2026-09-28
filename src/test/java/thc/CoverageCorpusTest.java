// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.LongConsumer;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import static org.junit.jupiter.api.Assertions.*;
import static thc.CoreBackendTestSupport.*;

/** Ordinary optimized Haskell, compared with native GHC across both execution backends. */
class CoverageCorpusTest {
    private final Path root = Path.of(System.getProperty("thc.projectRoot"));
    private Map<String, Object> diagnostics(Value function) { return object(Json.parse(function.getMember("diagnostics").asString())); }
    private long count(Value function, String key) { return ((Number) diagnostics(function).get(key)).longValue(); }
    private String hash(Path path) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))); }
    private long labelCount(Value function, String label) { var value = object(diagnostics(function).get("thunkEvaluationsByLabel")).get(label); return value instanceof Number number ? number.longValue() : 0L; }
    @TestFactory List<DynamicTest> nativeOracleBeforeCompilationOnColdPathsAndAfterRecompilation() throws Exception {
        var corpus = object(Json.parse(Files.readString(root.resolve("build/corpus/corpus.json"))));
        assertEquals(hash(root.resolve("examples/coverage.json")), corpus.get("sourceManifestSha256"), "Run bin/prepare-tests.sh after changing the corpus");
        var hashes = new LinkedHashMap<>(object(corpus.get("inputHashes"))); hashes.putAll(object(corpus.get("artifactHashes")));
        for (var item : hashes.entrySet()) assertEquals(item.getValue(), hash(root.resolve(item.getKey())), "Stale native corpus input/artifact: " + item.getKey() + "; run bin/prepare-tests.sh");
        var allEntries = objects(corpus.get("entries")); assertFalse(allEntries.isEmpty(), "Coverage corpus must not be empty");
        var selectedEntry = System.getProperty("thc.corpusEntry"); var selectedBackend = System.getProperty("thc.corpusBackend");
        var entries = selectedEntry == null ? allEntries : allEntries.stream().filter(entry -> selectedEntry.equals(entry.get("id"))).toList();
        if (selectedEntry != null) assertEquals(1, entries.size(), "Unknown or duplicate corpus entry: " + selectedEntry);
        var backends = list("ast", "bytecode"); assertTrue(selectedBackend == null || backends.contains(selectedBackend), "Unknown corpus backend: " + selectedBackend);
        var tests = new ArrayList<DynamicTest>();
        for (String backend : backends) if (selectedBackend == null || selectedBackend.equals(backend)) for (var entry : entries) {
            String id = (String) entry.get("id");
            var modules = ((List<?>) entry.get("modules")).stream().map(path -> root.resolve((String) path).toString()).toList();
            var warm = ((List<?>) entry.get("warmInputs")).stream().map(value -> ((Number) value).longValue()).toList();
            var cold = ((List<?>) entry.get("coldInputs")).stream().map(value -> ((Number) value).longValue()).toList();
            var expected = object(entry.get("expected"));
            var shared = entry.get("sharedBindings") instanceof List<?> values ? values.stream().map(value -> (String) value).toList() : List.<String>of();
            tests.add(DynamicTest.dynamicTest(backend + " " + id + ": " + entry.get("focus"), () -> {
                try (var context = Main.executionContext(false)) {
                    var function = context.eval("thc", CoreModules.request(modules, (String) entry.get("name"), true, false, backend, true, false, null, null, false, false));
                    assertEquals(backend, diagnostics(function).get("backend"));
                    LongConsumer check = n -> {
                        var evaluations = new LinkedHashMap<String, Long>(); for (var label : shared) evaluations.put(label, labelCount(function, label));
                        assertEquals(((Number) Objects.requireNonNull(expected.get(Long.toString(n)))).longValue(), function.execute(n).asLong(), backend + " " + id + "(" + n + ")");
                        for (var label : shared) assertEquals(1L, labelCount(function, label) - evaluations.get(label), backend + " " + id + "(" + n + "): shared " + label + " must be evaluated exactly once");
                    };
                    warm.forEach(check::accept); for (int i = 0; i < 40; i++) check.accept(warm.get(i % warm.size()));
                    assertTrue(function.invokeMember("compile").asBoolean(), backend + " " + id + " compilation");
                    long before = count(function, "compiledEntries"); warm.forEach(check::accept);
                    assertTrue(count(function, "compiledEntries") > before, backend + " " + id + " installed guest code");
                    // These inputs are withheld until after the first compilation; deoptimization is allowed.
                    cold.forEach(check::accept); var all = new ArrayList<>(warm); all.addAll(cold);
                    for (int i = 0; i < 40; i++) check.accept(all.get(i % all.size()));
                    assertTrue(function.invokeMember("compile").asBoolean(), backend + " " + id + " recompilation");
                    for (long n : all.reversed()) { before = count(function, "compiledEntries"); check.accept(n); assertTrue(count(function, "compiledEntries") > before, backend + " " + id + "(" + n + ") must enter installed recompiled code"); }
                    assertEquals(0L, count(function, "unsupportedTraps"), backend + " " + id + " unsupported paths"); assertEquals(0L, count(function, "blackholes"), backend + " " + id + " unintended strictness");
                }
            }));
        }
        return tests;
    }
}
