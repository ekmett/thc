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
        for (var name : scalarNames) result.add("scalar" + name);
        result.addAll(list("boxedData", "boxedClosure")); result.addAll(shapes); return result;
    }
    private Map<String, Object> json(String file) throws Exception { return object(Json.parse(Files.readString(root.resolve(prefix + "/" + file)))); }
    private Map<String, Object> cbd(String file) throws Exception { return CoreCbdFixtures.read(root.resolve(prefix + "/" + file)); }
    private String entry(Map<String, Object> module, String name) { return module.get("unit") + ":" + module.get("module") + "." + name; }
    private String hash(Path file) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))); }
    private void verifyEvidence() throws Exception {
        var manifest = json("manifest.json");
        assertEquals(1L, manifest.get("schema")); assertEquals(names, manifest.get("entries")); assertEquals((names.size() + shapes.size()) * 7L, manifest.get("nativeRows"));
        assertEquals(shapes.stream().map(name -> name + "Return").toList(), manifest.get("returns"));
        assertEquals(shapes.stream().map(name -> name + "Producer").toList(), manifest.get("producers"));
        var inputs = object(manifest.get("inputHashes"));
        var expectedInputs = new HashSet<>(list("t/fixtures/compiler/RubbishLiteralAudit.hs", "t/haskell-fixtures/RubbishLiteralFixtures.hs",
            "t/haskell-fixtures/FixtureSupport.hs", "t/haskell-fixtures/Main.hs",
            "cabal.project", "thc.cabal", "bin/audit-core.py", "bin/core-capabilities.json"));
        try (var files = Files.list(root.resolve("src/compiler/THC"))) {
            files.filter(path -> path.getFileName().toString().endsWith(".hs")).forEach(path -> expectedInputs.add(root.relativize(path).toString()));
        }
        try (var files = Files.list(root.resolve("bin"))) {
            files.filter(path -> path.getFileName().toString().startsWith("core_") && path.getFileName().toString().endsWith(".py"))
                .forEach(path -> expectedInputs.add(root.relativize(path).toString()));
        }
        assertEquals(expectedInputs, inputs.keySet());
        var artifacts = object(manifest.get("artifactHashes"));
        String nativeInfo = Files.readString(root.resolve(prefix + "/logs/info.stdout"));
        var target = java.util.regex.Pattern.compile("\\(\"Target platform\",\\s*\"([^\"]+)\"\\)").matcher(nativeInfo);
        var host = java.util.regex.Pattern.compile("\\(\"Host platform\",\\s*\"([^\"]+)\"\\)").matcher(nativeInfo);
        assertTrue(target.find()); assertTrue(host.find()); assertEquals(host.group(1), target.group(1));
        boolean nativeLLVM = target.group(1).startsWith("aarch64-");
        var expectedArtifacts = new HashSet<String>();
        for (var file : list("pre.cbd", "post.cbd", "oracle.json", "native.s", "native.o", "native-codegen.json", "pre.audit.json", "post.audit.json"))
            expectedArtifacts.add(prefix + "/" + file);
        for (var command : list("version", "info", "libdir", "native-assemble", "pre-audit", "post-audit"))
            for (var suffix : list("stdout", "stderr", "command.json")) expectedArtifacts.add(prefix + "/logs/" + command + "." + suffix);
        if (nativeLLVM) {
            expectedArtifacts.add(prefix + "/native.ll");
            for (var suffix : list("stdout", "stderr", "command.json"))
                expectedArtifacts.add(prefix + "/logs/native-llvm." + suffix);
        }
        assertEquals(expectedArtifacts, artifacts.keySet());
        var nativeCode = json("native-codegen.json");
        assertEquals(nativeLLVM ? "llvm" : "native", nativeCode.get("backend"));
        if (nativeLLVM) {
            var ir = object(nativeCode.get("llvmIR"));
            assertEquals(prefix + "/native.ll", ir.get("path"));
            assertEquals(artifacts.get(prefix + "/native.ll"), ir.get("sha256"));
        } else assertFalse(nativeCode.containsKey("llvmIR"));
        assertEquals(prefix + "/native.s", nativeCode.get("assembly"));
        assertEquals(artifacts.get(prefix + "/native.s"), nativeCode.get("sha256"));
        var nativeNames = new ArrayList<>(names);
        nativeNames.addAll(shapes.stream().map(name -> name + "Return").toList());
        assertEquals(nativeNames.stream().map(name -> list(name, name + "Native")).toList(), nativeCode.get("entries"));

        var hashes = new LinkedHashMap<>(inputs); hashes.putAll(artifacts);
        for (var entry : hashes.entrySet()) assertEquals(entry.getValue(), hash(root.resolve(entry.getKey())), "Stale rubbish fixture: " + entry.getKey());
        for (var stage : list("pre", "post")) {
            var audit = json(stage + ".audit.json"); assertEquals(true, audit.get("accepted"));
            assertEquals(list(), audit.get("issues")); assertEquals(list(), audit.get("missingGlobals"));
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
    @Test void typedNativeContinuationsMatchInterpreted() throws Exception { nativeValues(false); }
    @Test void typedNativeContinuationsMatchFirstInstalledEntry() throws Exception { nativeValues(true); }
    private void nativeValues(boolean compiled) throws Exception {
        verifyEvidence(); var rows = expression(json("oracle.json").get("rows")); assertEquals((names.size() + shapes.size()) * 7, rows.size());
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
                            assertTrue(((Number) program.diagnostics().get("compiledEntries")).longValue() > before, "Must enter compiled guest code");
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
                    case LONG -> { if (proof.isInt()) assertInstanceOf(Integer.class, value); else assertInstanceOf(Long.class, value); }
                    case FLOAT -> assertInstanceOf(Float.class, value);
                    case DOUBLE -> assertInstanceOf(Double.class, value);
                    case ADDRESS -> assertInstanceOf(ManagedAddress.class, value);
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
        var original = with(decoded, "bindings", CoreModules.reachable(decoded, entry(decoded, "scalarIntRep")).get("bindings"));
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
                    for (var target : list(producer, continuation)) {
                        target.getClass().getMethod("compile", boolean.class).invoke(target, true);
                        assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target));
                    }
                    for (var row : selected) {
                        long before = ((Number) program.diagnostics().get("compiledEntries")).longValue();
                        assertEquals(row.get(2), Calls.target(continuation, new Object[]{0L, row.get(1)}), stage + "/" + backend + "/" + name);
                        assertTrue(((Number) program.diagnostics().get("compiledEntries")).longValue() > before, "Must enter compiled guest code");
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
        assertFalse(values.isEmpty(), name); return values.getFirst();
    }
    private Map<String,Object> literalFunction(String id, List<Object> body, Map<String,Object> result) {
        var word = map("kind", "long", "evaluated", true, "primReps", list("IntRep"));
        var closure = map("kind", "closure", "evaluated", true, "primReps", list("BoxedRep (Just Lifted)"));
        return map("id", id, "name", id, "arity", 1, "lifted", true, "rep", closure,
            "expr", list("lam", list(map("id", "unused", "name", "unused", "lifted", false, "rep", word)),
                body, map("rep", closure, "resultRep", result)));
    }
    @SuppressWarnings("unchecked") private void compileUntouchedRubbish(Program.PreparedCode code) throws Exception {
        var field = Program.PreparedCode.class.getDeclaredField("targets"); field.setAccessible(true);
        var targets = (List<OptimizedCallTarget>) field.get(code);
        assertFalse(targets.isEmpty());
        for (var target : targets) {
            assertFalse(target.wasExecuted()); assertTrue(target.prepareForAOT()); target.compile(true);
            assertFalse(target.wasExecuted(), "AOT preparation must not train the guest root");
        }
        code.requireInstalledCode();
    }
    private long compiledEntries(Program program) { return ((Number) program.diagnostics().get("compiledEntries")).longValue(); }
    private Object firstCompiledCall(Program program, Closure function) {
        long before = compiledEntries(program);
        var result = Calls.target(function.target, new Object[]{0L, function.environment, 42L});
        assertTrue(compiledEntries(program) > before, "Must enter compiled guest code");
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
            compileUntouchedRubbish(code);
            String previous = System.getProperty("thc.requireCompiledCode");
            System.setProperty("thc.requireCompiledCode", "true");
            var owners = new LinkedHashMap<String,List<Object>>();
            try {
                for (int load = 0; load < 2; load++) try (var context = Context.newBuilder("thc").engine(engine).build()) {
                    entered(context, language -> {
                        var first = code.newInstance(language); var second = code.newInstance(language);
                        long secondInitialEntries = compiledEntries(second);
                        for (var program : list(first, second)) {
                            for (var name : names) {
                                var function = (Closure) program.entryValue(entry(module, name));
                                assertEquals(59L, firstCompiledCall(program, function), name); code.requireInstalledCode();
                            }
                            for (var name : shapes) {
                                var producer = (Closure) program.entryValue(entry(module, name + "Producer"));
                                var shape = ((GuestRoot) producer.target.getRootNode()).getTupleResult(); assertNotNull(shape, name);
                                checkCarrier(TupleResults.ownedTupleResult(firstCompiledCall(program, producer), shape), shape);
                                var continuation = (Closure) program.entryValue(entry(module, name + "Return"));
                                assertEquals(59L, firstCompiledCall(program, continuation), name); code.requireInstalledCode();
                            }
                            for (var name : observers) {
                                var function = (Closure) program.entryValue(entry(module, "observe" + name));
                                Object value = firstCompiledCall(program, function);
                                if (name.equals("boxedClosure")) assertInstanceOf(Closure.class, value); else assertInstanceOf(DataValue.class, value);
                                freshOwner(owners, name, value);
                                assertSame(value, firstCompiledCall(program, function)); code.requireInstalledCode();
                            }
                            assertEquals(0, language.getHandoffState().get().getArguments().getDepth());
                            assertEquals(0, language.getHandoffState().get().getResults().getDepth());
                            if (program == first) assertEquals(secondInitialEntries, compiledEntries(second),
                                "Invoking one instance must not enter its sibling");
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
                } finally { preparation.leave(); }
            }
            compileUntouchedRubbish(code);
            String previous = System.getProperty("thc.requireCompiledCode");
            System.setProperty("thc.requireCompiledCode", "true");
            try {
                for (int load = 0; load < 2; load++) try (var context = Context.newBuilder("thc").engine(engine).build()) {
                    entered(context, language -> {
                        var first = code.newInstance(language); var second = code.newInstance(language);
                        long secondInitialEntries = compiledEntries(second);
                        for (var program : list(first, second)) {
                            for (var name : kinds) {
                                var proof = proofs.get(name); Object value = program.entryValue(name);
                                var function = (Closure) program.entryValue("read" + name);
                                Object returned = firstCompiledCall(program, function);
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
                            assertEquals(0, language.getHandoffState().get().getArguments().getDepth());
                            assertEquals(0, language.getHandoffState().get().getResults().getDepth());
                            if (program == first) assertEquals(secondInitialEntries, compiledEntries(second),
                                "Invoking one instance must not enter its sibling");
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
        var original = with(decoded, "bindings", CoreModules.reachable(decoded, entry(decoded, "scalarIntRep")).get("bindings")); var bad = object(changeAlternative(original));
        auditRejected(bad, directory, "pattern");
        for (var backend : list("ast", "bytecode")) try (var context = context()) { entered(context, language ->
            assertThrows(RuntimeFault.class, () -> load(language, backend, bad).entryTarget(entry(bad, "scalarIntRep")))); }
    }
}
