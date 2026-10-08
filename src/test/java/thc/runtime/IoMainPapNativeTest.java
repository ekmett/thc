// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.TruffleLanguage;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import thc.Json;
import thc.CoreModules;
import thc.CoreCbdFixtures;
import org.junit.jupiter.api.io.TempDir;
import thc.Language;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import static org.junit.jupiter.api.Assertions.*;

/** Run once with each value of -Dthc.handoffSlabs; no global property mutation. */
@SuppressWarnings("unchecked")
class IoMainPapNativeTest {
    private final Path root = Path.of(System.getProperty("thc.projectRoot"));
    private final Path folder = root.resolve("build/io-main-pap");
    @TempDir Path temporary;
    private int fixtureSequence;
    private final String prefix = "main:IoMainPapAudit.";
    private static Map<String, Object> read(Path file) throws Exception { return (Map<String, Object>) Json.parse(Files.readString(file)); }
    private List<Map<String, Object>> modules(String stage) throws Exception {
        var result = new ArrayList<Map<String, Object>>();
        for (var path : paths(stage)) result.add(CoreCbdFixtures.read(Path.of(path)));
        return result;
    }
    private List<String> paths(String stage) {
        return List.of("IoMainPapAudit.cbd", "THC.InterfaceClosure.cbd").stream().map(file -> folder.resolve(stage + "/core/" + file).toString()).toList();
    }
    private static List<Map<String, Object>> bindings(List<Map<String, Object>> modules) {
        var result = new ArrayList<Map<String, Object>>();
        for (var module : modules) result.addAll((List<Map<String, Object>>) module.get("bindings"));
        return result;
    }
    private static Map<String, Object> named(List<Map<String, Object>> bindings, String id) {
        Map<String, Object> found = null;
        for (var binding : bindings) if (id.equals(binding.get("id"))) {
            if (found != null) throw new IllegalArgumentException("Collection contains more than one matching element.");
            found = binding;
        }
        if (found == null) throw new NoSuchElementException("Collection contains no element matching the predicate.");
        return found;
    }
    private List<Object> worker(List<Map<String, Object>> modules) { return (List<Object>) named(bindings(modules), prefix + "worker").get("expr"); }
    private List<Object> pap(List<Map<String, Object>> modules) {
        var globals = new LinkedHashMap<Object, Map<String, Object>>();
        for (var binding : bindings(modules)) globals.put(binding.get("id"), binding);
        var expr = (List<Object>) Objects.requireNonNull(globals.get(prefix + "goodMain")).get("expr");
        var seen = new LinkedHashSet<Object>();
        while (expr.get(0).equals("var")) {
            assertTrue(seen.add(expr.get(1)), "Unexpected cyclic genuine alias");
            expr = (List<Object>) Objects.requireNonNull(globals.get(expr.get(1))).get("expr");
        }
        assertEquals("app", expr.get(0));
        assertEquals(2, ((List<?>) expr.get(2)).size(), "The genuine fixture must retain its two-argument PAP"); return expr;
    }
    private String request(String stage, String name, String backend) {
        return CoreModules.request(paths(stage), prefix + name, true, false, backend, true, true);
    }
    private String request(List<Map<String, Object>> modules, String name, String backend) throws Exception {
        var paths = new ArrayList<String>();
        for (var module : modules) {
            // Decoder-only ownership metadata is not part of the CBD model.
            module.remove("bindingOrigins");
            paths.add(CoreCbdFixtures.write(temporary.resolve("control-" + fixtureSequence++ + ".cbd"), module).toString());
        }
        return CoreModules.request(paths, prefix + name, true, false, backend, true, true);
    }
    private static Context context() {
        return Context.newBuilder("thc").allowExperimentalOptions(true).option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
            .option("engine.SingleTierCompilationThreshold", "10000000").option("engine.CompilationFailureAction", "Throw").build();
    }
    @BeforeEach void currentNativeEvidence() throws Exception {
        var evidence = read(folder.resolve("provenance.json")); assertEquals("9.14.1", evidence.get("ghc"));
        for (var kind : List.of("sources", "artifacts")) {
            var records = (List<Map<String, String>>) evidence.get(kind); assertTrue(!records.isEmpty(), "Missing " + kind + " fingerprints");
            for (var record : records) {
                var path = record.get("path");
                var actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(root.resolve(path))));
                assertEquals(record.get("sha256"), actual, "Stale IO-main PAP evidence: " + path);
            }
        }
        assertEquals(List.of("goodMain\tcompleted", "badMain\tthrows", "nonUnitMain\tcompleted", "unitBottomMain\tcompleted", "functionMain\tcompleted", "lazyMain\tcompleted"), Files.readAllLines(folder.resolve("oracle.tsv")));
    }
    @Test void genuinePreAndPostTidyPapsPassTheStrictIoAudit() throws Exception {
        assertEquals(true, read(folder.resolve("provenance.json")).get("accepted"));
        for (var stage : List.of("pre", "post")) for (var name : List.of("goodMain", "badMain", "nonUnitMain", "unitBottomMain", "functionMain", "lazyMain")) {
            var report = read(folder.resolve(stage + "/" + name + "-audit.json"));
            assertEquals(true, report.get("accepted"), stage + "/" + name + ": " + report.get("issues")); assertEquals(List.of(), report.get("missingGlobals"));
        }
    }
    private static void released(Language language) {
        var state = language.getHandoffState().get();
        assertEquals(0, state.getArguments().getDepth()); assertEquals(0, state.getArguments().retainedReferences()); assertNull(state.getPending());
        assertEquals(0, state.getResults().getDepth()); assertEquals(0, state.getResults().retainedReferences());
    }
    @ParameterizedTest @CsvSource({"pre, ast", "post, ast", "pre, bytecode", "post, bytecode"})
    void nativeActionsActuallyRunRatherThanMerelyLoad(String stage, String backend) throws Exception {
        var modules = modules(stage); pap(modules);
        try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var good = context.eval("thc", request(stage, "goodMain", backend)); var bad = context.eval("thc", request(stage, "badMain", backend));
                for (var action : List.of(good, bad)) { assertFalse(action.canExecute(), "IO actions must only expose runIO"); assertTrue(action.canInvokeMember("runIO")); }
                // A cached PAP is reusable, but its action must execute each time.
                for (int i = 0; i < 3; i++) {
                    assertTrue(good.invokeMember("runIO").asBoolean()); released(language);
                    var failure = assertThrows(PolyglotException.class, () -> bad.invokeMember("runIO"));
                    assertTrue(failure.isGuestException()); assertFalse(failure.isHostException()); released(language);
                    assertTrue(good.invokeMember("runIO").asBoolean(), "Failure must not poison later IO"); released(language);
                }
                for (var action : List.of(good, bad)) {
                    var metrics = (Map<String, Object>) Json.parse(action.getMember("diagnostics").asString());
                    assertTrue(((Number) metrics.get("papAllocations")).longValue() > 0, "Genuine PAP must execute");
                    assertEquals(0L, metrics.get("blackholes")); assertEquals(0L, metrics.get("unsupportedTraps"));
                }
            } finally { context.leave(); }
        }
    }
    @ParameterizedTest @CsvSource({"pre, ast", "post, ast", "pre, bytecode", "post, bytecode"})
    void forgedRemainingStateAndResultMetadataStayRejected(String stage, String backend) throws Exception {
        for (var mutation : List.of("state-type", "state-rep", "state-result", "unit-result", "under-applied", "saturated")) {
            var modules = modules(stage); var lam = worker(modules); var formals = (List<Map<String, Object>>) lam.get(1);
            var result = (Map<String, Object>) ((Map<String, Object>) lam.getLast()).get("resultRep"); var components = (List<Object>) result.get("components");
            switch (mutation) {
                case "state-type" -> formals.getLast().put("type", "State# s");
                case "state-rep" -> formals.getLast().put("rep", formals.getFirst().get("rep"));
                case "state-result" -> { components.set(0, formals.getFirst().get("rep")); result.put("primReps", List.of("IntRep", "BoxedRep (Just Lifted)")); }
                case "unit-result" -> { components.set(1, formals.getFirst().get("rep")); result.put("primReps", List.of("IntRep")); }
                default -> {
                    var app = pap(modules); var arguments = (List<Object>) app.get(2); var lifted = (List<Object>) app.get(3);
                    if (mutation.equals("under-applied")) { arguments.remove(1); lifted.remove(1); }
                    else { arguments.add(arguments.get(0)); lifted.add(false); }
                }
            }
            try (var context = context()) {
                var error = assertThrows(PolyglotException.class, () -> context.eval("thc", request(modules, "goodMain", backend)), stage + "/" + backend + "/" + mutation);
                var message = Objects.toString(error.getMessage(), "");
                assertTrue(message.contains("IO main"), stage + "/" + backend + "/" + mutation + ": " + error.getMessage());
            }
        }
    }
    @ParameterizedTest @CsvSource({"pre, ast", "post, ast", "pre, bytecode", "post, bytecode"})
    void deferredActionsAuthenticateTheirActualClosureBeforeEntering(String stage, String backend) throws Exception {
        for (var malformed : List.of("arity", "result")) {
            var modules = modules(stage);
            var binding = named(bindings(modules), prefix + "lazyMain");
            Object body;
            if (malformed.equals("arity")) body = List.of("var", prefix + "worker", Map.of("rep", binding.get("rep")));
            else {
                var lam = worker(modules);
                var formal = ((List<Map<String,Object>>) lam.get(1)).getLast();
                var tuple = (Map<String,Object>) ((Map<String,Object>) lam.getLast()).get("resultRep");
                var answer = ((List<?>) tuple.get("components")).getLast();
                body = List.of("lam", List.of(formal),
                    List.of("con", "ghc-internal:GHC.Internal.Tuple.()", 0, Map.of("rep", answer)),
                    Map.of("rep", binding.get("rep"), "resultRep", answer));
            }
            // A let keeps admission unresolved, as it can be for a genuine lazy IO head.
            var local = Map.of("id", "malformed-action", "name", "malformed-action", "lifted", true,
                "rep", binding.get("rep"), "arity", malformed.equals("arity") ? 3 : 1, "expr", body);
            binding.put("expr", List.of("let", false, List.of(local),
                List.of("var", "malformed-action", Map.of("rep", binding.get("rep"))), Map.of("rep", binding.get("rep"))));
            try (var context = context()) {
                var action = context.eval("thc", request(modules, "lazyMain", backend));
                assertThrows(PolyglotException.class, () -> action.invokeMember("runIO"));
            }
        }
    }
    @ParameterizedTest @CsvSource({"pre, ast", "post, ast", "pre, bytecode", "post, bytecode"})
    void executableAnswersAreDiscardedWithoutForcing(String stage, String backend) throws Exception {
        try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                for (var name : List.of("unitBottomMain", "nonUnitMain", "functionMain", "lazyMain")) {
                    var action = context.eval("thc", request(stage, name, backend));
                    assertTrue(action.invokeMember("runIO").asBoolean(), name);
                    released(language);
                }
            } finally { context.leave(); }
        }
    }
}
