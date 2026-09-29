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
    private final List<String> shapes = list("shapeEmptyTuple", "shapeSingletonTuple", "shapeSum", "shapeVector", "shapeNestedTuple", "shapeNestedSum");
    private final List<String> names = names();
    private List<String> names() {
        var result = new ArrayList<String>();
        for (var name : list("Lifted", "Unlifted", "IntRep", "Int32Rep")) result.add("original" + name);
        for (var name : scalarNames) result.add("scalar" + name);
        result.addAll(list("boxedData", "boxedClosure", "sequenceLifted")); result.addAll(shapes); return result;
    }
    private Map<String, Object> json(String file) throws Exception { return object(Json.parse(Files.readString(root.resolve(prefix + "/" + file)))); }
    private Map<String, Object> cbd(String file) throws Exception { return CoreCbdFixtures.read(root.resolve(prefix + "/" + file)); }
    private String entry(Map<String, Object> module, String name) { return module.get("unit") + ":" + module.get("module") + "." + name; }
    private String hash(Path file) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))); }
    private void verifyEvidence() throws Exception {
        var manifest = json("manifest.json");
        assertEquals(1L, manifest.get("schema")); assertEquals(names, manifest.get("entries")); assertEquals(238L, manifest.get("nativeRows"));
        assertEquals(shapes.stream().map(name -> name + "Return").toList(), manifest.get("returns"));
        assertEquals(shapes.stream().map(name -> name + "Producer").toList(), manifest.get("producers"));
        var inputs = object(manifest.get("inputHashes"));
        var expectedInputs = new HashSet<>(list("t/fixtures/compiler/RubbishLiteralAudit.hs", "t/haskell-fixtures/RubbishLiteralFixtures.hs",
            "t/haskell-fixtures/FixtureSupport.hs", "t/haskell-fixtures/Main.hs", "thc.cabal", "bin/audit-core.py", "bin/core-capabilities.json"));
        try (var files = Files.list(root.resolve("src/compiler/THC"))) {
            files.filter(path -> path.getFileName().toString().endsWith(".hs")).forEach(path -> expectedInputs.add(root.relativize(path).toString()));
        }
        try (var files = Files.list(root.resolve("bin"))) {
            files.filter(path -> path.getFileName().toString().startsWith("core_") && path.getFileName().toString().endsWith(".py"))
                .forEach(path -> expectedInputs.add(root.relativize(path).toString()));
        }
        assertEquals(expectedInputs, inputs.keySet());
        var artifacts = object(manifest.get("artifactHashes"));
        var expectedArtifacts = new HashSet<String>();
        for (var file : list("pre.cbd", "post.cbd", "Data.Sequence.Internal.cbd", "oracle.json", "originals.json", "native.s", "native.o", "native-codegen.json", "pre.audit.json", "post.audit.json"))
            expectedArtifacts.add(prefix + "/" + file);
        for (var command : list("version", "info", "libdir", "imports-ghc-internal", "imports-containers", "containers-unit", "native-assemble", "pre-audit", "post-audit"))
            for (var suffix : list("stdout", "stderr", "command.json")) expectedArtifacts.add(prefix + "/logs/" + command + "." + suffix);
        assertEquals(expectedArtifacts, artifacts.keySet());
        var nativeCode = json("native-codegen.json");
        assertEquals("native", nativeCode.get("backend"));
        assertEquals(prefix + "/native.s", nativeCode.get("assembly"));
        assertEquals(artifacts.get(prefix + "/native.s"), nativeCode.get("sha256"));
        var nativeNames = new ArrayList<>(names);
        nativeNames.addAll(shapes.stream().map(name -> name + "Return").toList());
        assertEquals(nativeNames.stream().map(name -> list(name, name + "Native")).toList(), nativeCode.get("entries"));

        var hashes = new LinkedHashMap<>(inputs); hashes.putAll(artifacts);
        for (var entry : hashes.entrySet()) assertEquals(entry.getValue(), hash(root.resolve(entry.getKey())), "Stale rubbish fixture: " + entry.getKey());
        var installed = new LinkedHashMap<String, Object>();
        for (var record : objects(manifest.get("installedInterfaces"))) installed.put((String) record.get("path"), record.get("sha256"));
        assertEquals(2, installed.size());
        assertEquals(Set.of("Manager.hi", "Internal.hi"), new HashSet<>(installed.keySet().stream().map(path -> Path.of(path).getFileName().toString()).toList()));
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
            assertEquals(names.size() + shapes.size(), literals(cbd(stage + ".cbd")).size());
        }
    }
    @Test void freshSequenceCaptureHasSupportedEvaluatedLiftedRubbish() throws Exception {
        verifyEvidence();
        var module = cbd("Data.Sequence.Internal.cbd");
        assertEquals("Data.Sequence.Internal", module.get("module"));
        var original = expression(json("originals.json").get("sequenceOccurrences"));
        assertEquals(1, original.size());
        assertEquals("BoxedRep (Just Lifted)", expression(original.getFirst()).get(1));
        var values = literals(module);
        assertEquals(1, values.size(), "The fresh installed Core literal must survive as rubbish, not an unsupported diagnostic");
        var proof = RubbishLiterals.proof(values.getFirst());
        assertTrue(proof.getEvaluated());
        assertEquals(List.of("BoxedRep (Just Lifted)"), proof.getPrimReps());
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
        verifyEvidence(); var rows = expression(json("oracle.json").get("rows")); assertEquals(238, rows.size());
        for (var stage : list("pre", "post")) for (var backend : list("ast", "bytecode")) try (var context = context()) {
            entered(context, language -> {
                var module = with(cbd(stage + ".cbd"), "instrument", true);
                var program = load(language, backend, module);
                for (var name : names) {
                    var target = program.entryTarget(entry(module, name));
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
            for (var literal : literals(cbd("pre.cbd"))) {
                var proof = RubbishLiterals.proof(literal);
                if (proof.isTypedTransport()) continue;
                var value = decoder.decode(proof); assertFalse(value instanceof Thunk);
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
        var input = CoreCbdTestSupport.writeModel(directory.resolve(label + ".cbd"), module);
        var output = directory.resolve(label + ".audit.json");
        var process = new ProcessBuilder("python3", root.resolve("bin/audit-core.py").toString(), "--entry", entry(module, "scalarIntRep"), "--output", output.toString(), input.toString())
            .directory(root.toFile()).redirectErrorStream(true).start();
        process.getOutputStream().close();
        var text = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(process.waitFor(30, TimeUnit.SECONDS), text); assertEquals(1, process.exitValue(), text);
        var result = object(Json.parse(Files.readString(output))); assertEquals(false, result.get("accepted"));
        assertFalse(expression(result.get("issues")).isEmpty());
        assertFalse(objects(result.get("issues")).stream().anyMatch(issue -> "entry-resolution".equals(issue.get("code"))));
    }
private List<Object> replaceProof(List<Object> literal, Map<String,Object> proof) {
        var result = new ArrayList<>(literal);
        result.set(3, with(object(literal.get(3)), "rep", proof)); return result;
    }
    @Test void malformedRubbishProofsAndUnsupportedRepresentationsFailClosed(@TempDir Path directory) throws Exception {
        var decoded = cbd("pre.cbd");
        var original = CoreModules.reachable(decoded, entry(decoded, "scalarIntRep"));
        var changes = new LinkedHashMap<String, UnaryOperator<List<Object>>>();
        changes.put("missing", value -> new ArrayList<>(value.subList(0, 3)));
changes.put("legacy-payload", value -> { var result = new ArrayList<>(value); result.set(2, "IntRep"); return result; });
        changes.put("wrong-register", value -> replaceProof(value, map("kind", "long", "primReps", list("FloatRep"), "evaluated", true)));
        changes.put("opaque-tuple", value -> replaceProof(value, map("kind", "unknown", "aggregate", "unboxed-tuple", "primReps", list("IntRep"), "evaluated", true)));
        changes.put("opaque-sum", value -> replaceProof(value, map("kind", "unknown", "aggregate", "unboxed-sum", "primReps", list("WordRep", "IntRep"), "evaluated", true)));
        changes.put("unknown", value -> replaceProof(value, map("kind", "unknown", "evaluated", true)));
        changes.put("unevaluated", literal -> {
            var result = new ArrayList<>(literal); var metadata = object(literal.get(3));
            result.set(3, with(metadata, "rep", with(object(metadata.get("rep")), "evaluated", false))); return result;
        });
        for (var change : changes.entrySet()) {
            var bad = object(changeLiterals(original, change.getValue()));
            var encodingError = switch (change.getKey()) {
                case "missing" -> "Core expression lacks metadata";
                case "legacy-payload" -> "Unsupported Core literal: \"rubbish\"";
                default -> null;
            };
            if (encodingError == null) auditRejected(bad, directory, change.getKey());
            else {
                // These malformed models have no CBD 1.2 encoding to submit to the auditor.
                var failure = assertThrows(java.io.IOException.class, () ->
                    CoreCbdTestSupport.writeModel(directory.resolve(change.getKey() + ".cbd"), bad));
                assertTrue(failure.getMessage().contains(encodingError), failure.getMessage());
            }
            for (var backend : list("ast", "bytecode")) try (var context = context()) { entered(context, language ->
                assertThrows(RuntimeFault.class, () -> load(language, backend, bad).entryTarget(entry(bad, "scalarIntRep")), backend + "/" + change.getKey())); }
        }
    }
    @Test void typedReturnsPreserveShapesAndFirstInstalledExecution() throws Exception {
        verifyEvidence();
        var rows = expression(json("oracle.json").get("rows"));
        for (var stage : list("pre", "post")) for (var backend : list("ast", "bytecode")) try (var context = context()) {
            entered(context, language -> {
                var module = with(cbd(stage + ".cbd"), "instrument", true);
                var program = load(language, backend, module);
                for (var name : shapes) {
                    var producer = program.entryTarget(entry(module, name + "Producer"));
                    var continuation = program.entryTarget(entry(module, name + "Return"));
                    var shape = ((GuestRoot) producer.getRootNode()).getTupleResult();
                    assertNotNull(shape, name);
                    var selected = rows.stream().map(ScalarValueTestSupport::expression)
                        .filter(row -> (name + "Return").equals(row.get(0))).toList();
                    assertEquals(7, selected.size());
                    for (var row : selected) {
                        checkCarrier(TupleResults.ownedTupleResult(Calls.target(producer, new Object[]{0L, row.get(1)}), shape), shape);
                        assertEquals(row.get(2), Calls.target(continuation, new Object[]{0L, row.get(1)}));
                    }
                    if (backend.equals("ast")) assertFalse(com.oracle.truffle.api.nodes.NodeUtil
                        .findAllNodeInstances(producer.getRootNode(), Rubbish.class).isEmpty(), "Keep the explicit don't-care node");
                    for (var target : list(producer, continuation)) {
                        target.getClass().getMethod("compile", boolean.class).invoke(target, true);
                        assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target));
                    }
                    for (var row : selected) {
                        long before = ((Number) program.diagnostics().get("compiledEntries")).longValue();
                        assertEquals(row.get(2), Calls.target(continuation, new Object[]{0L, row.get(1)}), stage + "/" + backend + "/" + name);
                        assertEquals(2L, ((Number) program.diagnostics().get("compiledEntries")).longValue() - before);
                        for (var target : list(producer, continuation)) assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target));
                        checkCarrier(TupleResults.ownedTupleResult(Calls.target(producer, new Object[]{0L, row.get(1)}), shape), shape);
                        assertEquals(0, language.getHandoffState().get().getArguments().getDepth());
                        assertEquals(0, language.getHandoffState().get().getResults().getDepth());
                    }
                }
            });
        }
    }
    private void checkCarrier(HandoffStorage value, TupleShape shape) {
        var slots = new ArrayList<Integer>();
        for (int i = 0; i < shape.getWidth(); i++) {
            slots.add(i);
            if (!shape.getLayout().isInt(i) && !shape.getLayout().isLong(i) && !shape.getLayout().isFloat(i) && !shape.getLayout().isDouble(i))
                shape.checkedReference(i, shape.getLayout().getObject(value, i));
        }
        checkLogicalCarrier(shape.getProof(), value, shape, slots);
    }
    private void checkLogicalCarrier(CoreRepresentation proof, HandoffStorage value, TupleShape shape, List<Integer> slots) {
        if (proof.isTuple()) {
            int offset = 0;
            for (var field : proof.getComponents()) {
                int width = TupleShape.flatten(field).size();
                checkLogicalCarrier(field, value, shape, slots.subList(offset, offset + width)); offset += width;
            }
        } else if (proof.isSum()) {
            int tag = SumShape.checkedTag(shape.getLayout().getLong(value, slots.getFirst()), proof.getAlternatives().size());
            var selected = SumShape.projection(proof, tag - 1).stream().map(slots::get).toList();
            checkLogicalCarrier(proof.getAlternatives().get(tag - 1), value, shape, selected);
        } else if (proof.isVector()) new VectorLayout(proof).require(shape.getLayout().getObject(value, slots.getFirst()));
        else if (proof.hasBoxedPointer()) assertFalse(shape.getLayout().getObject(value, slots.getFirst()) instanceof Thunk);
        else if (proof.getKind() == CoreKind.ADDRESS) assertInstanceOf(ManagedAddress.class, shape.getLayout().getObject(value, slots.getFirst()));
    }
    @Test void unknownBoxedLevityUsesTheExistingReferenceCarrier() throws Exception {
        var literal = list("lit", "rubbish", null, map("rep", map("kind", "object", "primReps", list("BoxedRep Nothing"), "evaluated", true)));
        var proof = RubbishLiterals.proof(literal);
        assertTrue(proof.hasUnknownBoxedLevity());
        try (var context = context()) { entered(context, language -> {
            var value = new Rubbish(proof, new RubbishLiterals(language), null).execute(null);
            assertInstanceOf(DataValue.class, value); assertFalse(value instanceof Thunk);
        }); }
    }
    private Object changeAlternative(Object value) {
        if (value instanceof Map<?, ?> map) {
            var result = new LinkedHashMap<Object, Object>(); map.forEach((key, item) -> result.put(key, changeAlternative(item))); return result;
        }
        if (value instanceof List<?> values) {
            var result = new ArrayList<Object>(values);
            if (!values.isEmpty() && "default".equals(values.getFirst())) { result.set(0, "lit"); result.set(1, list("rubbish", null)); }
            else result.replaceAll(this::changeAlternative);
            return result;
        }
        return value;
    }
    @Test void rubbishCannotBecomeALiteralAlternative(@TempDir Path directory) throws Exception {
        var decoded = cbd("pre.cbd");
        var original = CoreModules.reachable(decoded, entry(decoded, "scalarIntRep")); var bad = object(changeAlternative(original));
        auditRejected(bad, directory, "pattern");
        for (var backend : list("ast", "bytecode")) try (var context = context()) { entered(context, language ->
            assertThrows(RuntimeFault.class, () -> load(language, backend, bad).entryTarget(entry(bad, "scalarIntRep")))); }
    }
}
