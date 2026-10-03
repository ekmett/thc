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
        assertEquals(hash(root.resolve("t/fixtures/core/coverage.json")), corpus.get("sourceManifestSha256"), "Fixture quarantined: see docs/fixture-quarantine.log");
        var hashes = new LinkedHashMap<>(object(corpus.get("inputHashes"))); hashes.putAll(object(corpus.get("artifactHashes")));
        for (var item : hashes.entrySet()) assertEquals(item.getValue(), hash(root.resolve(item.getKey())), "Stale native corpus input/artifact: " + item.getKey() + "; fixture quarantined: see docs/fixture-quarantine.log");
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
            var sharedPaths = object(entry.get("sharedBindingPaths"));
            assertEquals(new LinkedHashSet<>(shared), sharedPaths.keySet(), id + " sharing label inventory");
            var runtimeLabels = new LinkedHashMap<String, String>();
            if (!shared.isEmpty()) {
                var decoded = new ArrayList<Map<String, Object>>();
                for (var path : modules) decoded.addAll(objects(CoreCbdFixtures.read(Path.of(path)).get("bindings")));
                var roots = decoded.stream().filter(b -> entry.get("entry").equals(b.get("id"))).toList();
                assertEquals(1, roots.size(), id + " exact sharing root");
                for (var label : shared) {
                    var proof = object(sharedPaths.get(label)); Object binder = roots.getFirst();
                    for (var step : (List<?>) proof.get("path")) binder = step instanceof Number number
                        ? ((List<?>) binder).get(number.intValue()) : object(binder).get((String) step);
                    var fields = object(binder); var compactId = (String) proof.get("compactId");
                    assertTrue(compactId.startsWith("@local/"), id + " local sharing identity");
                    var expectedId = "\u0000compact-local:" + compactId.substring("@local/".length());
                    assertEquals(expectedId, fields.get("id"), id + " exact shared binder " + label);
                    assertEquals(expectedId, fields.get("name"), id + " actual decoded label " + label);
                    assertEquals(0, ((Number) fields.get("arity")).intValue(), id + " shared thunk arity");
                    runtimeLabels.put(label, expectedId);
                }
                assertEquals(shared.size(), new HashSet<>(runtimeLabels.values()).size(), id + " shared identity collisions");
            }
            tests.add(DynamicTest.dynamicTest(backend + " " + id + ": " + entry.get("focus"), () -> {
                try (var context = Main.executionContext(false)) {
                    var function = context.eval("thc", CoreModules.request(modules, (String) entry.get("entry"), true, false, backend, true, false, null, null, false));
                    assertEquals(backend, diagnostics(function).get("backend"));
                    LongConsumer check = n -> {
                        var evaluations = new LinkedHashMap<String, Long>(); for (var label : shared) evaluations.put(label, labelCount(function, runtimeLabels.get(label)));
                        assertEquals(((Number) Objects.requireNonNull(expected.get(Long.toString(n)))).longValue(), function.execute(n).asLong(), backend + " " + id + "(" + n + ")");
                        for (var label : shared) assertEquals(1L, labelCount(function, runtimeLabels.get(label)) - evaluations.get(label), backend + " " + id + "(" + n + "): shared " + label + " must be evaluated exactly once");
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
