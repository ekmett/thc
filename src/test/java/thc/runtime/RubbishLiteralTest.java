// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.TruffleLanguage;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import com.oracle.truffle.runtime.OptimizedCallTarget;
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
        var output = directory.resolve(label + ".audit.json");
        var process = new ProcessBuilder("python3", root.resolve("bin/audit-core.py").toString(), "--entry", entry(module, "scalarIntRep"), "--output", output.toString(), "-")
            .directory(root.toFile()).redirectErrorStream(true).start();
        try (var input = process.getOutputStream()) { input.write(Json.stringify(module).getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
        var text = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(process.waitFor(30, TimeUnit.SECONDS), text); assertNotEquals(0, process.exitValue(), text);
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
            var bad = object(changeLiterals(original, change.getValue())); auditRejected(bad, directory, change.getKey());
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
            var value = new Rubbish(proof, new RubbishLiterals(language), null, -1).execute(null);
            assertInstanceOf(DataValue.class, value); assertFalse(value instanceof Thunk);
        }); }
    }
    private Engine reusableEngine() {
        return Engine.newBuilder().allowExperimentalOptions(true).option("engine.BackgroundCompilation", "false")
            .option("engine.MultiTier", "false").option("compiler.Inlining", "false")
            .option("engine.CompilationFailureAction", "Throw").option("engine.SingleTierCompilationThreshold", "10000000").build();
    }
    private List<Object> fixtureLiteral(Map<String,Object> module, String name) {
        var values = literals(CoreModules.reachable(module, entry(module, name)));
        assertEquals(1, values.size(), name); return values.getFirst();
    }
    private Map<String,Object> literalFunction(String id, List<Object> body, Map<String,Object> result) {
        var word = map("kind", "long", "evaluated", true, "primReps", list("IntRep"));
        var closure = map("kind", "closure", "evaluated", true, "primReps", list("BoxedRep (Just Lifted)"));
        return map("id", id, "name", id, "arity", 1, "lifted", true, "rep", closure,
            "expr", list("lam", list(map("id", "unused", "name", "unused", "lifted", false, "rep", word)),
                body, map("rep", closure, "resultRep", result)));
    }
    @SuppressWarnings("unchecked") private void compileUntouchedRubbish(Program.PreparedCode code, boolean dedicatedNodes) throws Exception {
        var field = Program.PreparedCode.class.getDeclaredField("targets"); field.setAccessible(true);
        var targets = (List<OptimizedCallTarget>) field.get(code);
        assertFalse(targets.isEmpty());
        if (dedicatedNodes) assertTrue(targets.stream().anyMatch(target -> !com.oracle.truffle.api.nodes.NodeUtil
            .findAllNodeInstances(target.getRootNode(), Rubbish.class).isEmpty()), "Retain typed don't-care nodes in shared code");
        for (var target : targets) {
            assertFalse(target.wasExecuted()); assertTrue(target.prepareForAOT()); target.compile(true);
            assertFalse(target.wasExecuted(), "AOT preparation must not train the guest root");
        }
        code.requireInstalledCode();
    }
    private long compiledEntries(Program program) { return ((Number) program.diagnostics().get("compiledEntries")).longValue(); }
    private Object firstCompiledCall(Program program, Closure function, long expectedEntries) {
        long before = compiledEntries(program);
        var result = Calls.target(function.target, new Object[]{0L, function.environment, 42L});
        assertEquals(expectedEntries, compiledEntries(program) - before);
        return result;
    }
    private void freshOwner(Map<String,List<Object>> owners, String name, Object value) {
        assertFalse(value instanceof Thunk);
        var previous = owners.computeIfAbsent(name, ignored -> new ArrayList<>());
        for (var old : previous) assertNotSame(old, value, name + " must belong to the invoking Program instance");
        previous.add(value);
    }
    @Test void reusableInlineRubbishKeepsFirstCompiledShapesAndInstanceOwnership() throws Exception {
        var module = cbd("pre.cbd");
        var bindings = new ArrayList<>(objects(module.get("bindings")));
        var observers = list("scalarLifted", "scalarUnlifted", "boxedData", "boxedClosure");
        var entries = new ArrayList<>(names.stream().map(name -> entry(module, name)).toList());
        for (var name : shapes) { entries.add(entry(module, name + "Producer")); entries.add(entry(module, name + "Return")); }
        for (var name : observers) {
            var literal = fixtureLiteral(module, name);
            String id = entry(module, "observe" + name);
            bindings.add(literalFunction(id, literal, object(object(literal.get(3)).get("rep")))); entries.add(id);
        }
        var preparedModule = with(module, "bindings", bindings, "instrument", true);
        try (var engine = reusableEngine()) {
            Program.PreparedCode code;
            try (var preparation = Context.newBuilder("thc").engine(engine).build()) {
                preparation.initialize("thc"); preparation.enter();
                try { code = Program.prepareCode(TruffleLanguage.LanguageReference.create(Language.class).get(null), preparedModule, entries); }
                finally { preparation.leave(); }
            }
            compileUntouchedRubbish(code, true);
            String previous = System.getProperty("thc.requireCompiledCode");
            System.setProperty("thc.requireCompiledCode", "true");
            var owners = new LinkedHashMap<String,List<Object>>();
            try {
                for (int load = 0; load < 2; load++) try (var context = Context.newBuilder("thc").engine(engine).build()) {
                    entered(context, language -> {
                        var first = code.newInstance(language); var second = code.newInstance(language);
                        for (var program : list(first, second)) {
                            for (var name : names) {
                                var function = (Closure) program.entryValue(entry(module, name));
                                assertEquals(59L, firstCompiledCall(program, function, 1), name); code.requireInstalledCode();
                            }
                            for (var name : shapes) {
                                var producer = (Closure) program.entryValue(entry(module, name + "Producer"));
                                var shape = ((GuestRoot) producer.target.getRootNode()).getTupleResult(); assertNotNull(shape, name);
                                checkCarrier(TupleResults.ownedTupleResult(firstCompiledCall(program, producer, 1), shape), shape);
                                var continuation = (Closure) program.entryValue(entry(module, name + "Return"));
                                assertEquals(59L, firstCompiledCall(program, continuation, 2), name); code.requireInstalledCode();
                            }
                            for (var name : observers) {
                                var function = (Closure) program.entryValue(entry(module, "observe" + name));
                                Object value = firstCompiledCall(program, function, 1);
                                if (name.equals("boxedClosure")) assertInstanceOf(Closure.class, value); else assertInstanceOf(DataValue.class, value);
                                freshOwner(owners, name, value);
                                assertSame(value, firstCompiledCall(program, function, 1)); code.requireInstalledCode();
                            }
                            assertEquals(0, program.diagnostics().get("loweredRootCount"));
                            assertEquals(0, language.getHandoffState().get().getArguments().getDepth());
                            assertEquals(0, language.getHandoffState().get().getResults().getDepth());
                            if (program == first) assertEquals(0L, compiledEntries(second));
                        }
                    });
                }
            } finally { if (previous == null) System.clearProperty("thc.requireCompiledCode"); else System.setProperty("thc.requireCompiledCode", previous); }
        }
    }
    @Test void reusableTopLevelRubbishRecipesBelongToEachLoad() throws Exception {
        var source = cbd("pre.cbd");
        var kinds = list("scalarIntRep", "scalarInt8Rep", "scalarFloatRep", "scalarDoubleRep", "scalarAddrRep",
            "scalarLifted", "scalarUnlifted", "boxedData", "boxedClosure", "shapeVector");
        var bindings = new ArrayList<Map<String,Object>>();
        var proofs = new LinkedHashMap<String,CoreRepresentation>();
        var entries = new ArrayList<String>();
        for (var name : kinds) {
            var literal = fixtureLiteral(source, name); var proof = RubbishLiterals.proof(literal); proofs.put(name, proof);
            var rawProof = object(object(literal.get(3)).get("rep"));
            bindings.add(map("id", name, "name", name, "arity", 0, "lifted", proof.hasBoxedPointer() && !name.equals("scalarUnlifted"),
                "rep", rawProof, "expr", literal));
            bindings.add(literalFunction("read" + name, list("var", name, map("rep", rawProof)), rawProof));
            entries.add(name); entries.add("read" + name);
        }
        var module = map("schema", 1, "ghc", "9.14.1", "module", "Synthetic.ReusableRubbish", "instrument", true,
            "constructors", list(), "bindings", bindings);
        var owners = new LinkedHashMap<String,List<Object>>();
        try (var engine = reusableEngine()) {
            Program.PreparedCode code;
            try (var preparation = Context.newBuilder("thc").engine(engine).build()) {
                preparation.initialize("thc"); preparation.enter();
                try {
                    var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                    for (var name : shapes) if (!name.equals("shapeVector")) {
                        var literal = fixtureLiteral(source, name); var proof = object(object(literal.get(3)).get("rep"));
                        var global = map("id", "aggregate", "name", "aggregate", "lifted", false, "rep", proof, "expr", literal);
                        assertThrows(RuntimeFault.class, () -> Program.prepareCode(language, with(module, "bindings", list(global)), list("aggregate")));
                    }
                    code = Program.prepareCode(language, module, entries);
                    var preparationInstance = code.newInstance(language);
                    for (var name : kinds) if (proofs.get(name).hasBoxedPointer()) freshOwner(owners, name, preparationInstance.entryValue(name));
                    assertEquals(0L, compiledEntries(preparationInstance));
                } finally { preparation.leave(); }
            }
            compileUntouchedRubbish(code, false);
            String previous = System.getProperty("thc.requireCompiledCode");
            System.setProperty("thc.requireCompiledCode", "true");
            try {
                for (int load = 0; load < 2; load++) try (var context = Context.newBuilder("thc").engine(engine).build()) {
                    entered(context, language -> {
                        var first = code.newInstance(language); var second = code.newInstance(language);
                        for (var program : list(first, second)) {
                            for (var name : kinds) {
                                var proof = proofs.get(name); Object value = program.entryValue(name);
                                var function = (Closure) program.entryValue("read" + name);
                                Object returned = firstCompiledCall(program, function, 1);
                                if (proof.isVector()) {
                                    new VectorLayout(proof).require(value);
                                    var shape = ((GuestRoot) function.target.getRootNode()).getTupleResult(); assertNotNull(shape);
                                    var carrier = TupleResults.ownedTupleResult(returned, shape); checkCarrier(carrier, shape);
                                    assertSame(value, shape.getLayout().getObject(carrier, 0));
                                } else if (proof.hasBoxedPointer()) {
                                    if (proof.getKind() == CoreKind.CLOSURE) assertInstanceOf(Closure.class, value); else assertInstanceOf(DataValue.class, value);
                                    assertSame(value, returned); freshOwner(owners, name, value);
                                } else {
                                    assertEquals(value.getClass(), returned.getClass()); assertEquals(value, returned);
                                    if (proof.getKind() == CoreKind.ADDRESS) assertInstanceOf(ManagedAddress.class, value);
                                }
                                assertSame(value, program.entryValue(name)); code.requireInstalledCode();
                            }
                            assertEquals(0, program.diagnostics().get("loweredRootCount"));
                            assertEquals(0, language.getHandoffState().get().getArguments().getDepth());
                            assertEquals(0, language.getHandoffState().get().getResults().getDepth());
                            if (program == first) assertEquals(0L, compiledEntries(second));
                        }
                    });
                }
            } finally { if (previous == null) System.clearProperty("thc.requireCompiledCode"); else System.setProperty("thc.requireCompiledCode", previous); }
        }
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
