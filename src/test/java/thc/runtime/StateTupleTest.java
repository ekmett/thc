// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import thc.CoreModules;
import thc.Json;
import thc.Language;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings("unchecked")
class StateTupleTest {
    private final Path root = Path.of(System.getProperty("thc.projectRoot"));
    @BeforeEach void verifyEvidence() throws Exception {
        var evidence = (Map<String, Object>) Json.parse(Files.readString(root.resolve("build/state-tuple/provenance.json")));
        var sources = (List<Map<String, String>>) evidence.get("sources");
        var artifacts = (List<Map<String, String>>) evidence.get("artifacts");
        var items = new ArrayList<>(sources); items.addAll(artifacts);
        for (var item : items) {
            var path = item.get("path");
            var actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(root.resolve(path))));
            assertEquals(item.get("sha256"), actual, "Stale State# evidence: " + path);
        }
        var required = new ArrayList<>(List.of("scripts/audit-core.py", "scripts/core-capabilities.json"));
        for (var file : Objects.requireNonNull(root.resolve("scripts").toFile().listFiles()))
            if (file.getName().startsWith("core_") && file.getName().endsWith(".py")) required.add(root.relativize(file.toPath()).toString());
        for (var path : List.of("src/main/resources/thc/scalar-primop-signatures.json", "tools/primops/PrimopTools.hs")) if (Files.exists(root.resolve(path))) required.add(path);
        var paths = new ArrayList<String>(); for (var source : sources) paths.add(source.get("path"));
        assertTrue(paths.containsAll(required), "Missing current auditor input hashes");
        for (var stage : List.of("pre", "post")) {
            var audit = (Map<String, Object>) Json.parse(Files.readString(root.resolve("build/state-tuple/" + stage + "-audit.json")));
            assertEquals(true, audit.get("accepted"), "State# strict audit: " + stage);
        }
    }
    private Map<String, Object> module(String stage) throws Exception {
        return (Map<String, Object>) Json.parse(Files.readString(root.resolve("build/state-tuple/" + stage + "-core/StateTupleAudit.json")));
    }
    private static Context context(boolean inlining) {
        return Context.newBuilder("thc").allowExperimentalOptions(true).option("compiler.Inlining", Boolean.toString(inlining))
            .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw").build();
    }
    private static void valid(RootCallTarget target, String label) throws Exception { assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target), label); }
    private static void compile(RootCallTarget target) throws Exception { target.getClass().getMethod("compile", boolean.class).invoke(target, true); valid(target, "initial compilation"); }
    private static void released(Language language) {
        var state = language.getHandoffState().get();
        assertEquals(0, state.getResults().getDepth()); assertEquals(0, state.getResults().retainedReferences());
        assertEquals(0, state.getArguments().getDepth()); assertEquals(0, state.getArguments().retainedReferences());
    }
    private static ExecutableProgram program(Language language, Map<String, Object> module, String backend) { return backend.equals("ast") ? new Program(language, module) : new BytecodeProgram(language, module); }
    private static Object call(ExecutableProgram program, String entry, long x) { return Calls.target(program.hostEntryTarget(1), new Object[]{program.entryValue(entry), new Object[]{x}}); }
    private static List<Map<String, Object>> bindings(Map<String, Object> module) { return (List<Map<String, Object>>) module.get("bindings"); }
    private static Map<String, Object> named(List<Map<String, Object>> bindings, String name) {
        Map<String, Object> found = null;
        for (var binding : bindings) if (name.equals(binding.get("name"))) {
            if (found != null) throw new IllegalArgumentException("Collection contains more than one matching element.");
            found = binding;
        }
        if (found == null) throw new NoSuchElementException("Collection contains no element matching the predicate.");
        return found;
    }
    private static <T> T single(T[] values) {
        if (values.length == 0) throw new NoSuchElementException("Array is empty.");
        if (values.length != 1) throw new IllegalArgumentException("Array has more than one element.");
        return values[0];
    }
    @Test void nativeStateTuplesExecuteInlined() throws Exception { checkNative(true); }
    @Test void nativeStateTuplesExecuteAcrossResidualCalls() throws Exception { checkNative(false); }
    private static void checkRows(List<String[]> rows, String entry, String stage, String backend, boolean inlining,
                                  ExecutableProgram program, RootCallTarget target, Language language, boolean compiled) throws Exception {
        for (var row : rows) if (row[0].equals(entry)) {
            var label = stage + "/" + backend + "/" + entry + "/" + row[1] + "/inlining=" + inlining;
            long before = ((Number) program.diagnostics().get("compiledEntries")).longValue();
            assertEquals(Long.parseLong(row[2]), call(program, entry, Long.parseLong(row[1])), label);
            if (compiled) {
                assertTrue(((Number) program.diagnostics().get("compiledEntries")).longValue() > before, label); valid(target, label);
            }
            released(language);
        }
    }
    private void checkNative(boolean inlining) throws Exception {
        var rows = new ArrayList<String[]>();
        for (var line : Files.readAllLines(root.resolve("build/state-tuple/oracle.tsv"))) rows.add(line.split("\t", -1));
        for (var stage : List.of("pre", "post")) for (var backend : List.of("ast", "bytecode")) try (var context = context(inlining)) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var entries = new LinkedHashSet<String>(); for (var row : rows) entries.add(row[0]);
                for (var entry : entries) {
                    var linked = CoreModules.reachable(module(stage), entry); var bindings = bindings(linked);
                    var program = program(language, linked, backend); var target = program.entryTarget((String) named(bindings, entry).get("id"));
                    checkRows(rows, entry, stage, backend, inlining, program, target, language, false);
                    for (var binding : bindings) if (((List<?>) binding.get("expr")).get(0).equals("lam")) compile(program.entryTarget((String) binding.get("id")));
                    checkRows(rows, entry, stage, backend, inlining, program, target, language, true);
                }
            } finally { context.leave(); }
        }
    }
    @Test void ignoredStateFieldStillExecutesAndThrowsBeforeTupleCompletion() throws Exception {
        for (var stage : List.of("pre", "post")) for (var backend : List.of("ast", "bytecode")) try (var context = context(true)) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var linked = CoreModules.reachable(module(stage), "effectCase"); var program = program(language, linked, backend);
                var target = program.entryTarget((String) named(bindings(linked), "effectCase").get("id"));
                assertEquals(7L, call(program, "effectCase", 7L));
                assertThrows(GuestException.class, () -> call(program, "effectCase", -1L)); released(language);
                compile(target); assertEquals(9L, call(program, "effectCase", 9L)); valid(target, stage + "/" + backend);
                assertThrows(GuestException.class, () -> call(program, "effectCase", -7L)); released(language);
            } finally { context.leave(); }
        }
    }
    private static TupleShape shape(List<Map<String, Object>> bindings, String name, Language language) {
        return new TupleShape(CoreRepresentations.parse(((Map<?, ?>) ((List<?>) named(bindings, name).get("expr")).get(3)).get("resultRep")), language);
    }
    @Test void logicalStateAndNestedEmptyTupleFieldsHaveNoPhysicalSlots() throws Exception {
        try (var context = context(true)) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); var bindings = bindings(module("pre"));
                var pair = shape(bindings, "pair", language);
                assertEquals(2, pair.getComponents().length); assertEquals(1, pair.getWidth()); assertArrayEquals(new int[]{0, 0}, pair.getOffsets());
                var zero = shape(bindings, "zero", language);
                assertEquals(3, zero.getComponents().length); assertEquals(0, zero.getWidth()); assertEquals(List.of(), zero.getLayout().getReps());
                assertFalse(TupleShape.compatible(zero.getComponents()[0], zero.getComponents()[1])); assertArrayEquals(new int[]{0, 0, 0}, zero.getOffsets());
                var bytes = shape(bindings, "byteShape", language);
                assertEquals(1, bytes.getWidth()); assertEquals(CoreKind.OBJECT, single(bytes.getLeaves()).getKind());
                assertEquals(List.of("BoxedRep (Just Unlifted)"), single(bytes.getLeaves()).getPrimReps()); assertEquals(List.of("reference"), bytes.getLayout().getReps());
                var lazy = shape(bindings, "lazyPair", language); assertEquals(1, lazy.getWidth()); assertFalse(single(lazy.getLeaves()).getEvaluated());
            } finally { context.leave(); }
        }
    }
    @Test void zeroWidthStateCannotBeReinterpretedAsAnEmptyTuple() throws Exception {
        for (var backend : List.of("ast", "bytecode")) try (var context = context(true)) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); var module = module("pre");
                var pair = (List<?>) named(bindings(module), "pair").get("expr"); var proof = (Map<String, Object>) ((Map<?, ?>) pair.get(3)).get("resultRep");
                var fields = (List<Object>) proof.get("components");
                fields.set(0, Map.of("kind", "unknown", "evaluated", true, "primReps", List.of(), "aggregate", "unboxed-tuple", "components", List.of()));
                assertThrows(RuntimeFault.class, () -> program(language, CoreModules.reachable(module, "pairCase"), backend));
            } finally { context.leave(); }
        }
    }
    @Test void legacyOperandWithoutProofMustStillProduceTheVoidCarrier() throws Exception {
        for (var backend : List.of("ast", "bytecode")) try (var context = context(true)) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); var module = module("pre");
                var producer = (List<?>) named(bindings(module), "effectPair").get("expr"); var constructor = (List<?>) producer.get(2);
                var operands = (List<Object>) constructor.get(2); operands.set(0, List.of("lit", "int", "123"));
                var program = program(language, CoreModules.reachable(module, "effectCase"), backend);
                var failure = assertThrows(RuntimeFault.class, () -> call(program, "effectCase", 9L));
                assertTrue(Objects.toString(failure.getMessage(), "").contains("zero-width scalar carrier"), failure.getMessage()); released(language);
            } finally { context.leave(); }
        }
    }
}
