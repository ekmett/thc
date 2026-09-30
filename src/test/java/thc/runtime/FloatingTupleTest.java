// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleLanguage;
import java.io.File;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import thc.CoreModules;
import thc.CoreCbdFixtures;
import thc.Json;
import thc.Language;
import static org.junit.jupiter.api.Assertions.*;
import static thc.runtime.ScalarValueTestSupport.*;

class FloatingTupleTest {
    private static String id(String name) { return "main:FloatingTupleAudit." + name; }
    private final File root = new File(System.getProperty("thc.projectRoot"));
    private String read(String path) throws Exception { return Files.readString(new File(root, path).toPath()); }
    @BeforeEach void verifyEvidence() throws Exception {
        var provenance = object(Json.parse(read("build/floating-tuple/provenance.json")));
        var sources = objects(provenance.get("sources"));
        var records = new ArrayList<>(sources); records.addAll(objects(provenance.get("artifacts")));
        for (var record : records) {
            var file = new File(root, (String) record.get("path"));
            var hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file.toPath())));
            assertEquals(record.get("sha256"), hash, "Stale floating tuple evidence: " + file);
        }
        assertTrue(sources.stream().map(record -> record.get("path")).toList().containsAll(list(
            "bin/audit-core.py", "bin/core-capabilities.json", "src/main/resources/thc/scalar-primop-signatures.json", "src/tools/primops/PrimopTools.hs")));
        for (var stage : list("pre", "post")) assertEquals(true,
            object(Json.parse(read("build/floating-tuple/" + stage + "-audit.json"))).get("accepted"));
    }
    private Map<String, Object> module() throws Exception { return module("pre"); }
    private Map<String, Object> module(String stage) throws Exception {
        return CoreCbdFixtures.read(new File(root, "build/floating-tuple/" + stage + "-core/FloatingTupleAudit.cbd").toPath());
    }
    private static Context context(boolean inlining) {
        return Context.newBuilder("thc").allowExperimentalOptions(true).option("compiler.Inlining", Boolean.toString(inlining))
            .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
            .option("engine.CompilationFailureAction", "Throw").option("engine.SingleTierCompilationThreshold", "10000000").build();
    }
    private static void withLanguage(boolean inlining, CheckedConsumer<Language> action) throws Exception {
        try (var context = context(inlining)) {
            context.initialize("thc"); context.enter();
            try { action.accept(TruffleLanguage.LanguageReference.create(Language.class).get(null)); }
            finally { context.leave(); }
        }
    }
    private static void valid(RootCallTarget target, String label) throws Exception {
        assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target), label);
    }
    private static void compile(RootCallTarget target) throws Exception {
        target.getClass().getMethod("compile", boolean.class).invoke(target, true); valid(target, "initial compilation");
    }
    private static ExecutableProgram program(Language language, String backend, Map<String, Object> linked) {
        return backend.equals("ast") ? new Program(language, linked) : new BytecodeProgram(language, linked);
    }
    private static long count(ExecutableProgram program) { return ((Number) program.diagnostics().get("compiledEntries")).longValue(); }
    private static void released(Language language) {
        var state = language.getHandoffState().get();
        assertEquals(0, state.getResults().getDepth()); assertEquals(0, state.getResults().retainedReferences());
        assertEquals(0, state.getArguments().getDepth()); assertEquals(0, state.getArguments().retainedReferences());
    }
    private static Map<String, Object> binding(List<Map<String, Object>> bindings, String name) {
        var matches = bindings.stream().filter(binding -> id(name).equals(binding.get("id"))).toList();
        assertEquals(1, matches.size()); return matches.getFirst();
    }
    private TupleShape shape(Language language, String name) throws Exception {
        return new TupleShape(CoreRepresentations.lambdaResult(expression(binding(objects(module().get("bindings")), name).get("expr"))), language);
    }
    @Test void nativeComplexAndMixedResultsExecuteInlined() throws Exception { nativeResults(true); }
    @Test void nativeComplexAndMixedResultsExecuteAcrossResidualCalls() throws Exception { nativeResults(false); }
    private void nativeResults(boolean inlining) throws Exception {
        var rows = new LinkedHashMap<String, List<String[]>>();
        for (var line : Files.readAllLines(new File(root, "build/floating-tuple/oracle.tsv").toPath())) {
            var fields = line.split("\t"); rows.computeIfAbsent(fields[0], ignored -> new ArrayList<>()).add(fields);
        }
        assertEquals(44, rows.values().stream().mapToInt(List::size).sum());
        // Entry plus every guest invocation, inlined or residual; same-frame joins add none.
        var expectedCalls = Map.of("complexFloatCase", 2L, "complexDoubleCase", 2L, "mixedCase", 4L, "joinedCase", 2L, "ieeeCase", 2L);
        for (var stage : list("pre", "post")) for (var backend : list("ast", "bytecode")) withLanguage(inlining, language -> {
            for (var entry : rows.entrySet()) {
                var name = entry.getKey(); var inputs = entry.getValue();
                var linked = CoreModules.reachable(module(stage), id(name));
                var bindings = objects(linked.get("bindings"));
                var program = program(language, backend, linked);
                var target = program.entryTarget((String) binding(bindings, name).get("id"));
                CheckedConsumer<Boolean> checkRows = compiled -> {
                    for (var row : inputs) {
                        var label = stage + "/" + backend + "/" + name + "/" + row[1] + "/inlining=" + inlining;
                        long before = count(program);
                        assertEquals(Long.parseLong(row[2]), Calls.target(target, new Object[]{0L, Long.parseLong(row[1])}), label);
                        if (compiled) {
                            valid(target, label);
                            assertEquals(expectedCalls.get(name).longValue(), count(program) - before, label + " installed entries " + program.diagnostics());
                        }
                        released(language);
                    }
                };
                checkRows.accept(false);
                for (var binding : bindings) if (expression(binding.get("expr")).getFirst().equals("lam"))
                    compile(program.entryTarget((String) binding.get("id")));
                long allocations = language.getHandoffState().get().getResults().getAllocations();
                checkRows.accept(true);
                assertEquals(allocations, language.getHandoffState().get().getResults().getAllocations(), backend + "/" + name + " pooled reuse");
                assertEquals(0L, ((Number) program.diagnostics().get("unsupportedTraps")).longValue());
                if (name.equals("joinedCase")) assertTrue(((Number) program.diagnostics().get("localJoinTransfers")).longValue() > 0);
            }
        });
    }
    @Test void compiledResidualProducerPreservesNativeIeeeBits() throws Exception {
        var rows = Files.readAllLines(new File(root, "build/floating-tuple/bits.tsv").toPath()).stream().map(line -> line.split("\t")).toList();
        for (var backend : list("ast", "bytecode")) withLanguage(false, language -> {
            var linked = CoreModules.reachable(module(), id("ieeePair"));
            var program = program(language, backend, linked);
            var target = program.entryTarget((String) binding(objects(linked.get("bindings")), "ieeePair").get("id"));
            var shape = shape(language, "ieeePair");
            var layout = new FrameLayout(); int[] slots = {layout.bind("float"), layout.bind("double")};
            var descriptor = layout.build();
            CheckedConsumer<Boolean> checkRows = compiled -> {
                for (var row : rows) {
                    var frame = Truffle.getRuntime().createVirtualFrame(new Object[0], descriptor);
                    long before = count(program);
                    shape.consume(frame, Calls.target(target, new Object[]{0L, Long.parseLong(row[0])}), slots, 0);
                    assertTrue(frame.isFloat(slots[0])); assertTrue(frame.isDouble(slots[1]));
                    if (row[0].equals("6")) {
                        assertTrue(Float.isNaN(frame.getFloat(slots[0]))); assertTrue(Double.isNaN(frame.getDouble(slots[1])));
                    } else {
                        assertEquals((int) Long.parseUnsignedLong(row[1]), Float.floatToRawIntBits(frame.getFloat(slots[0])), backend + "/" + row[0]);
                        assertEquals(Long.parseUnsignedLong(row[2]), Double.doubleToRawLongBits(frame.getDouble(slots[1])), backend + "/" + row[0]);
                    }
                    if (compiled) { assertEquals(1L, count(program) - before); valid(target, backend + "/" + row[0]); }
                    released(language);
                }
            };
            checkRows.accept(false); compile(target); checkRows.accept(true);
        });
    }
    @Test void concreteFieldsPreserveNanPayloadsAndReleaseOnlyReferences() throws Exception {
        withLanguage(true, language -> {
            var shape = shape(language, "mixed");
            assertEquals(list("float", "double", "reference"), shape.getLayout().getReps());
            assertEquals(3, shape.getWidth()); assertArrayEquals(new int[]{0, 0, 2}, shape.getOffsets());
            assertFalse(TupleShape.compatible(shape.getComponents()[0], shape.getComponents()[1].getComponents().get(1)));
            var storage = shape.getLayout().create();
            var types = new ArrayList<Class<?>>();
            for (var field : storage.getClass().getDeclaredFields()) if (!Modifier.isStatic(field.getModifiers())) types.add(field.getType());
            assertTrue(types.contains(float.class)); assertTrue(types.contains(double.class));
            assertFalse(types.stream().anyMatch(type -> type.isArray() || type == Float.class || type == Double.class));
            var layout = new FrameLayout(); int[] from = new int[3], to = new int[3];
            for (int i = 0; i < 3; i++) from[i] = layout.bind("from" + i);
            for (int i = 0; i < 3; i++) to[i] = layout.bind("to" + i);
            var descriptor = layout.build(); var pointer = new Object();
            record Floating(float f, double d) {}
            for (var value : list(new Floating(-0.0f, -0.0), new Floating(Float.MIN_VALUE, Double.MIN_VALUE),
                    new Floating(Float.intBitsToFloat(0x7fc01234), Double.longBitsToDouble(0x7ff8000000005678L)))) {
                float f = value.f; double d = value.d;
                var frame = Truffle.getRuntime().createVirtualFrame(new Object[0], descriptor);
                FrameAccess.writeFloat(frame, from[0], f); FrameAccess.writeDouble(frame, from[1], d); FrameAccess.write(frame, from[2], pointer);
                new TupleLocalRead(shape, from).executeTuple(frame, to, 0);
                var completion = shape.finish(frame, to);
                assertEquals(1, language.getHandoffState().get().getResults().getDepth());
                shape.consume(frame, completion, from, 0);
                assertEquals(Float.floatToRawIntBits(f), Float.floatToRawIntBits(frame.getFloat(from[0])));
                assertEquals(Double.doubleToRawLongBits(d), Double.doubleToRawLongBits(frame.getDouble(from[1])));
                assertSame(pointer, frame.getObject(from[2])); released(language);
                // A materialized fresh carrier owns no pool loan.
                shape.getLayout().setFloat(storage, 0, f); shape.getLayout().setDouble(storage, 1, d); shape.getLayout().setObject(storage, 2, pointer);
                shape.consume(frame, storage, to, 0);
                assertEquals(Float.floatToRawIntBits(f), Float.floatToRawIntBits(frame.getFloat(to[0])));
                assertEquals(Double.doubleToRawLongBits(d), Double.doubleToRawLongBits(frame.getDouble(to[1])));
                assertSame(pointer, frame.getObject(to[2])); released(language);
            }
            var wrong = shape(language, "ieeePair");
            var frame = Truffle.getRuntime().createVirtualFrame(new Object[0], descriptor);
            FrameAccess.writeFloat(frame, from[0], 1.0f); FrameAccess.writeDouble(frame, from[1], 2.0); FrameAccess.write(frame, from[2], pointer);
            var result = shape.finish(frame, from);
            assertThrows(IllegalStateException.class, () -> wrong.consume(frame, result, to, 0)); released(language);
        });
    }
    @Test void floatingTupleInputsKeepTheirExactHostAggregateShape() throws Exception {
        withLanguage(true, language -> {
            for (var backend : list("ast", "bytecode")) {
                var p = program(language, backend, CoreModules.reachable(module(), id("floatingTupleArgument")));
                var input = ((GuestRoot) p.entryTarget(id("floatingTupleArgument")).getRootNode()).getInputLayout();
                assertNotNull(input); assertTrue(input.getRequiresTyped());
                assertEquals(1, input.getLogicalArity()); assertEquals(2, input.getPhysicalArity());
                assertEquals(list(CoreKind.FLOAT, CoreKind.DOUBLE), Arrays.stream(input.getPhysicalProofs()).map(CoreRepresentation::getKind).toList());
                // Scalar host values cannot stand in for the exact logical guest tuple.
                assertThrows(RuntimeFault.class, () -> Calls.target(p.hostEntryTarget(1), new Object[]{p.entryValue(id("floatingTupleArgument")), new Object[]{0L}}));
                released(language);
            }
        });
    }
    @Test void equalPhysicalWidthsCannotEraseNestedTupleIdentity() throws Exception {
        withLanguage(true, language -> {
            for (var backend : list("ast", "bytecode")) {
                var module = module();
                var sourceCase = expression(expression(binding(objects(module.get("bindings")), "complexFloatCase").get("expr")).get(2));
                assertEquals("case", sourceCase.getFirst());
                var producerCall = expression(sourceCase.get(1)); assertEquals("app", producerCall.getFirst());
                var producer = expression(producerCall.get(1)); assertEquals("var", producer.getFirst());
                var workers = objects(module.get("bindings")).stream().filter(value -> producer.get(1).equals(value.get("id"))).toList();
                assertEquals(1, workers.size()); var worker = workers.getFirst();
                var proof = object(object(expression(worker.get("expr")).get(3)).get("resultRep"));
                var components = expression(proof.get("components"));
                components.set(0, map("kind", "unknown", "primReps", list("FloatRep"), "evaluated", true,
                    "aggregate", "unboxed-tuple", "components", list(components.get(0))));
                assertThrows(RuntimeFault.class, () -> program(language, backend, CoreModules.reachable(module, id("complexFloatCase"))));
            }
        });
    }
    @Test void wholeNestedTupleCaseBinderCopiesFloatingSlotsOnBothBackends() throws Exception {
        withLanguage(true, language -> {
            for (var backend : list("ast", "bytecode")) {
                var module = module(); var bindings = objects(module.get("bindings"));
                var forward = expression(binding(bindings, "mixedForward").get("expr"));
                var proof = object(forward.get(3)).get("resultRep");
                // Retain the valid identity Core case GHC optimizes away, exercising
                // lexical whole-tuple reads rather than reconstruction.
                var binder = "whole-floating-tuple";
                forward.set(2, list("case", forward.get(2), binder,
                    list(list("default", null, list(), list("var", binder, map("rep", proof)))),
                    map("rep", proof, "binder", map("id", binder, "rep", proof))));
                var linked = CoreModules.reachable(module, id("mixedCase"));
                var program = program(language, backend, linked);
                var target = program.entryTarget((String) binding(bindings, "mixedCase").get("id"));
                for (long input : new long[]{-7L, 0L, 7L}) assertEquals(20L * input - 23, Calls.target(target, new Object[]{0L, input}));
                // Compile producers too: whole tuple copies must remain compiled across residual calls.
                for (var binding : objects(linked.get("bindings"))) if (expression(binding.get("expr")).getFirst().equals("lam"))
                    compile(program.entryTarget((String) binding.get("id")));
                for (long input : new long[]{-7L, 0L, 7L}) {
                    long before = count(program);
                    assertEquals(20L * input - 23, Calls.target(target, new Object[]{0L, input}));
                    valid(target, backend); assertEquals(4L, count(program) - before); released(language);
                }
            }
        });
    }
}
