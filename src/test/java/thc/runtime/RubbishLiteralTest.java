// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.TruffleLanguage;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import thc.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;
import static org.junit.jupiter.api.Assertions.*;
import static thc.runtime.ScalarValueTestSupport.*;

class RubbishLiteralTest {
    private final Path root = Path.of(System.getProperty("thc.projectRoot"));
    private final String prefix = "build/rubbish-literals";
    private final List<String> scalarNames = list("IntRep", "Int8Rep", "Int16Rep", "Int32Rep", "Int64Rep",
        "WordRep", "Word8Rep", "Word16Rep", "Word32Rep", "Word64Rep", "FloatRep", "DoubleRep", "AddrRep", "Lifted", "Unlifted");
    private final List<String> names = names();
    private List<String> names() {
        var result = new ArrayList<String>();
        for (var name : list("Lifted", "Unlifted", "IntRep", "Int32Rep")) result.add("original" + name);
        for (var name : scalarNames) result.add("scalar" + name);
        result.addAll(list("boxedData", "boxedClosure")); return result;
    }
    private Map<String, Object> json(String file) throws Exception { return object(Json.parse(Files.readString(root.resolve(prefix + "/" + file)))); }
    private String hash(Path file) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))); }
    private void verifyEvidence() throws Exception {
        var manifest = json("manifest.json");
        assertEquals(1L, manifest.get("schema")); assertEquals(names, manifest.get("entries")); assertEquals(147L, manifest.get("nativeRows"));
        var inputs = object(manifest.get("inputHashes"));
        var expectedInputs = new HashSet<>(list("compiler/test-fixtures/RubbishLiteralAudit.hs", "test/haskell-fixtures/RubbishLiteralFixtures.hs",
            "test/haskell-fixtures/FixtureSupport.hs", "test/haskell-fixtures/Main.hs", "thc.cabal", "scripts/audit-core.py", "scripts/core-capabilities.json"));
        try (var files = Files.list(root.resolve("compiler/THC"))) {
            files.filter(path -> path.getFileName().toString().endsWith(".hs")).forEach(path -> expectedInputs.add(root.relativize(path).toString()));
        }
        try (var files = Files.list(root.resolve("scripts"))) {
            files.filter(path -> path.getFileName().toString().startsWith("core_") && path.getFileName().toString().endsWith(".py"))
                .forEach(path -> expectedInputs.add(root.relativize(path).toString()));
        }
        assertEquals(expectedInputs, inputs.keySet());
        var artifacts = object(manifest.get("artifactHashes"));
        var expectedArtifacts = new HashSet<String>();
        for (var file : list("pre.json", "post.json", "oracle.json", "originals.json", "pre.audit.json", "post.audit.json", "frontiers.json", "frontiers.audit.json"))
            expectedArtifacts.add(prefix + "/" + file);
        for (var command : list("version", "info", "libdir", "imports-ghc-internal", "pre-audit", "post-audit", "frontiers-audit"))
            for (var suffix : list("stdout", "stderr", "command.json")) expectedArtifacts.add(prefix + "/logs/" + command + "." + suffix);
        assertEquals(expectedArtifacts, artifacts.keySet());
        var hashes = new LinkedHashMap<>(inputs); hashes.putAll(artifacts);
        for (var entry : hashes.entrySet()) assertEquals(entry.getValue(), hash(root.resolve(entry.getKey())), "Stale rubbish fixture: " + entry.getKey());
        var installed = new LinkedHashMap<String, Object>();
        for (var record : objects(manifest.get("installedInterfaces"))) installed.put((String) record.get("path"), record.get("sha256"));
        assertEquals(1, installed.size());
        assertEquals(Set.of("Manager.hi"), new HashSet<>(installed.keySet().stream().map(path -> Path.of(path).getFileName().toString()).toList()));
        for (var entry : installed.entrySet()) {
            var path = Path.of(entry.getKey()); assertTrue(path.isAbsolute()); assertEquals(entry.getValue(), hash(path), "Changed original interface: " + path);
        }
        var occurrences = expression(json("originals.json").get("occurrences"));
        assertEquals(manifest.get("originalOccurrences"), (long) occurrences.size());
        assertEquals(Set.of("BoxedRep (Just Lifted)", "BoxedRep (Just Unlifted)", "IntRep", "Int32Rep"),
            new HashSet<>(occurrences.stream().map(value -> expression(value).get(1)).toList()));
        assertTrue(occurrences.stream().anyMatch(value -> "GHC.Internal.Event.Manager.$wstep".equals(expression(value).get(0))));
        assertEquals(6, occurrences.size());
        for (var stage : list("pre", "post")) {
            var audit = json(stage + ".audit.json"); assertEquals(true, audit.get("accepted"));
            assertEquals(list(), audit.get("issues")); assertEquals(list(), audit.get("missingGlobals"));
            assertEquals(names.size(), literals(json(stage + ".json")).size());
        }
    }
    private boolean rubbish(List<?> values) { return values.size() >= 2 && values.subList(0, 2).equals(list("lit", "rubbish")); }
    private List<List<Object>> literals(Object value) {
        var result = new ArrayList<List<Object>>();
        if (value instanceof Map<?, ?> map) for (var item : map.values()) result.addAll(literals(item));
        else if (value instanceof List<?> values) {
            if (rubbish(values)) result.add(expression(values)); else for (var item : values) result.addAll(literals(item));
        }
        return result;
    }
    private Context context() {
        return Context.newBuilder("thc").allowExperimentalOptions(true).option("engine.BackgroundCompilation", "false")
            .option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw")
            .option("engine.SingleTierCompilationThreshold", "10000000").build();
    }
    private void entered(Context context, CheckedConsumer<Language> action) throws Exception {
        context.initialize("thc"); context.enter();
        try { action.accept(TruffleLanguage.LanguageReference.create(Language.class).get(null)); } finally { context.leave(); }
    }
    private ExecutableProgram load(Language language, String backend, Map<String, Object> module) {
        return backend.equals("ast") ? new Program(language, module) : new BytecodeProgram(language, module);
    }
    @Test void originalAndScalarNativeContinuationsMatchInterpreted() throws Exception { nativeValues(false); }
    @Test void originalAndScalarNativeContinuationsMatchFirstInstalledEntry() throws Exception { nativeValues(true); }
    private void nativeValues(boolean compiled) throws Exception {
        verifyEvidence(); var rows = expression(json("oracle.json").get("rows")); assertEquals(147, rows.size());
        for (var stage : list("pre", "post")) for (var backend : list("ast", "bytecode")) try (var context = context()) {
            entered(context, language -> {
                var program = load(language, backend, with(json(stage + ".json"), "instrument", true));
                for (var name : names) {
                    var target = program.entryTarget(name);
                    var selected = rows.stream().map(ScalarValueTestSupport::expression).filter(row -> name.equals(row.get(0))).toList();
                    if (compiled) {
                        // Establish ordinary JIT specialization with checked interpreter
                        // rows, then compile once. No post-compile settling or retry.
                        for (var row : selected) assertEquals(row.get(2), Calls.target(target, new Object[]{0L, row.get(1)}), stage + "/" + backend + "/" + name + " interpreter profile");
                        target.getClass().getMethod("compile", boolean.class).invoke(target, true);
                        assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target), stage + "/" + backend + "/" + name + " first compile");
                    }
                    for (var row : selected) {
                        long before = ((Number) program.diagnostics().get("compiledEntries")).longValue();
                        assertEquals((Long) row.get(1) + 17, row.get(2));
                        assertEquals(row.get(2), Calls.target(target, new Object[]{0L, row.get(1)}), stage + "/" + backend + "/" + name + "/" + row.get(1));
                        if (compiled) {
                            assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target), stage + "/" + backend + "/" + name + "/" + row.get(1) + " remains compiled");
                            assertEquals(1L, ((Number) program.diagnostics().get("compiledEntries")).longValue() - before);
                        }
                        assertEquals(0, language.getHandoffState().get().getArguments().getDepth());
                        assertEquals(0, language.getHandoffState().get().getResults().getDepth());
                    }
                }
                assertEquals(0L, ((Number) program.diagnostics().get("unsupportedTraps")).longValue());
            });
        }
    }
    @Test void fillersUseNonBottomScalarAndCheckedBoxedCarriers() throws Exception {
        try (var context = context()) { entered(context, language -> {
            verifyEvidence(); var decoder = new RubbishLiterals(language);
            for (var literal : literals(json("pre.json"))) {
                var proof = RubbishLiterals.proof(literal); var value = decoder.decode(proof); assertFalse(value instanceof Thunk);
                switch (proof.getKind()) {
                    case LONG -> { if (proof.isInt()) assertEquals(0, value); else assertEquals(0L, value); }
                    case FLOAT -> assertEquals(0, Float.floatToRawIntBits((Float) value));
                    case DOUBLE -> assertEquals(0L, Double.doubleToRawLongBits((Double) value));
                    case ADDRESS -> assertEquals(0L, ((ManagedAddress) value).toNativeBits());
                    case CLOSURE -> assertTrue(value instanceof Closure);
                    case DATA, OBJECT -> assertTrue(value instanceof DataValue);
                    default -> fail("Unexpected rubbish carrier");
                }
                var carrier = proof.referenceCarrier(); if (carrier != null) assertTrue(carrier.isInstance(value));
            }
        }); }
    }
    private Object changeLiterals(Object value, UnaryOperator<List<Object>> replace) {
        if (value instanceof Map<?, ?> map) {
            var result = new LinkedHashMap<Object, Object>(); map.forEach((key, item) -> result.put(key, changeLiterals(item, replace))); return result;
        }
        if (value instanceof List<?> values) {
            if (rubbish(values)) return replace.apply(expression(values));
            var result = new ArrayList<Object>(); for (var item : values) result.add(changeLiterals(item, replace)); return result;
        }
        return value;
    }
    private void auditRejected(Map<String, Object> module, Path directory, String label) throws Exception {
        var input = directory.resolve(label + ".json"); Files.writeString(input, Json.stringify(module));
        var output = directory.resolve(label + ".audit.json");
        var process = new ProcessBuilder("python3", root.resolve("scripts/audit-core.py").toString(), "--entry", "scalarIntRep", "--output", output.toString(), input.toString())
            .directory(root.toFile()).redirectErrorStream(true).start();
        var text = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(process.waitFor(30, TimeUnit.SECONDS), text); assertNotEquals(0, process.exitValue(), text);
        var result = object(Json.parse(Files.readString(output))); assertEquals(false, result.get("accepted"));
        assertFalse(expression(result.get("issues")).isEmpty());
    }
    private List<Object> replaceRep(List<Object> value, String rep) { var result = new ArrayList<>(value); result.set(2, rep); return result; }
    @Test void malformedRubbishProofsAndUnsupportedRepresentationsFailClosed(@TempDir Path directory) throws Exception {
        var original = CoreModules.reachable(json("pre.json"), "scalarIntRep");
        var changes = new LinkedHashMap<String, UnaryOperator<List<Object>>>();
        changes.put("missing", value -> new ArrayList<>(value.subList(0, 3)));
        changes.put("wrong-rep", value -> replaceRep(value, "FloatRep"));
        changes.put("unknown-levity", value -> replaceRep(value, "BoxedRep Nothing"));
        changes.put("tuple", value -> replaceRep(value, "TupleRep '[IntRep]"));
        changes.put("sum", value -> replaceRep(value, "SumRep '[IntRep, WordRep]"));
        changes.put("vector", value -> replaceRep(value, "VecRep 4 Int32ElemRep"));
        changes.put("unevaluated", literal -> {
            var result = new ArrayList<>(literal); var metadata = object(literal.get(3));
            result.set(3, with(metadata, "rep", with(object(metadata.get("rep")), "evaluated", false))); return result;
        });
        for (var change : changes.entrySet()) {
            var bad = object(changeLiterals(original, change.getValue())); auditRejected(bad, directory, change.getKey());
            for (var backend : list("ast", "bytecode")) try (var context = context()) { entered(context, language ->
                assertThrows(RuntimeFault.class, () -> load(language, backend, bad).entryTarget("scalarIntRep"), backend + "/" + change.getKey())); }
        }
    }
    @Test void genuineAggregateAndVectorRubbishRemainExplicitFrontiers() throws Exception {
        verifyEvidence(); var audit = json("frontiers.audit.json"); assertEquals(false, audit.get("accepted"));
        assertEquals(4L, objects(audit.get("issues")).stream().filter(issue -> "unsupported-literal".equals(issue.get("code"))).count());
        for (var name : list("emptyTuple", "singletonTuple", "sum", "vector")) {
            var module = CoreModules.reachable(json("frontiers.json"), name);
            for (var backend : list("ast", "bytecode")) try (var context = context()) { entered(context, language ->
                assertThrows(RuntimeFault.class, () -> load(language, backend, module).entryTarget(name), backend + "/" + name)); }
        }
    }
    private Object changeAlternative(Object value) {
        if (value instanceof Map<?, ?> map) {
            var result = new LinkedHashMap<Object, Object>(); map.forEach((key, item) -> result.put(key, changeAlternative(item))); return result;
        }
        if (value instanceof List<?> values) {
            var result = new ArrayList<Object>(values);
            if (!values.isEmpty() && "default".equals(values.getFirst())) { result.set(0, "lit"); result.set(1, list("rubbish", "IntRep")); }
            else result.replaceAll(this::changeAlternative);
            return result;
        }
        return value;
    }
    @Test void rubbishCannotBecomeALiteralAlternative(@TempDir Path directory) throws Exception {
        var original = CoreModules.reachable(json("pre.json"), "scalarIntRep"); var bad = object(changeAlternative(original));
        auditRejected(bad, directory, "pattern");
        for (var backend : list("ast", "bytecode")) try (var context = context()) { entered(context, language ->
            assertThrows(RuntimeFault.class, () -> load(language, backend, bad).entryTarget("scalarIntRep"))); }
    }
}
