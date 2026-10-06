// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.bytecode.Instruction;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeUtil;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleLanguage;
import java.io.File;
import java.nio.file.Files;
import java.util.*;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import thc.CoreModules;
import thc.CoreCbdFixtures;
import thc.Language;
import static org.junit.jupiter.api.Assertions.*;
import static thc.runtime.ScalarValueTestSupport.*;

class FloatingTupleTest {
    private static String id(String name) { return "main:FloatingTupleAudit." + name; }
    private final File root = new File(System.getProperty("thc.projectRoot"));
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
    private Map<String, List<long[]>> nativeRows() throws Exception {
        var expected = Set.of("complexFloatCase", "complexDoubleCase", "mixedCase", "joinedCase", "ieeeCase");
        var rows = new LinkedHashMap<String, List<long[]>>(); var keys = new HashSet<String>();
        for (var line : Files.readAllLines(new File(root, "build/floating-tuple/oracle.tsv").toPath())) {
            var fields = line.split("\t", -1); assertEquals(3, fields.length, line);
            assertTrue(expected.contains(fields[0]), "Unknown native observer: " + line);
            long input = Long.parseLong(fields[1]), value = Long.parseLong(fields[2]);
            assertTrue(keys.add(fields[0] + "/" + input), "Duplicate native input: " + line);
            rows.computeIfAbsent(fields[0], ignored -> new ArrayList<>()).add(new long[]{input, value});
        }
        assertEquals(expected, rows.keySet(), "Missing native observer");
        for (var entry : rows.entrySet()) if (!entry.getKey().equals("ieeeCase")) {
            var inputs = entry.getValue();
            assertTrue(inputs.stream().anyMatch(row -> row[0] < 0) && inputs.stream().anyMatch(row -> row[0] == 0)
                && inputs.stream().anyMatch(row -> row[0] > 0), "Missing arithmetic branch: " + entry.getKey());
        }
        assertEquals(nativeBits().stream().map(row -> row[0]).collect(java.util.stream.Collectors.toSet()),
            rows.get("ieeeCase").stream().map(row -> row[0]).collect(java.util.stream.Collectors.toSet()), "IEEE observers must cover the same inputs");
        return rows;
    }
    private static String ieeeClass(double value, double minimumNormal) {
        if (Double.isNaN(value)) return "nan";
        return (Math.copySign(1.0, value) < 0 ? "-" : "+") + (value == 0 ? "zero" : Double.isInfinite(value)
            ? "infinity" : Math.abs(value) < minimumNormal ? "subnormal" : "normal");
    }
    private List<long[]> nativeBits() throws Exception {
        var rows = new ArrayList<long[]>(); var inputs = new HashSet<Long>();
        var floats = new HashSet<String>(); var doubles = new HashSet<String>();
        for (var line : Files.readAllLines(new File(root, "build/floating-tuple/bits.tsv").toPath())) {
            var fields = line.split("\t", -1); assertEquals(3, fields.length, line);
            long input = Long.parseLong(fields[0]), f = Long.parseUnsignedLong(fields[1]), d = Long.parseUnsignedLong(fields[2]);
            assertTrue(Long.compareUnsigned(f, 0xffffffffL) <= 0, "Float bits exceed Word32: " + line);
            assertTrue(inputs.add(input), "Duplicate native bit input: " + line);
            floats.add(ieeeClass(Float.intBitsToFloat((int) f), Float.MIN_NORMAL));
            doubles.add(ieeeClass(Double.longBitsToDouble(d), Double.MIN_NORMAL)); rows.add(new long[]{input, f, d});
        }
        var classes = Set.of("+zero", "-zero", "+subnormal", "-subnormal", "+infinity", "-infinity", "nan");
        for (var observed : List.of(floats, doubles)) assertTrue(observed.containsAll(classes)
            && (observed.contains("+normal") || observed.contains("-normal")), "Missing native IEEE class: " + observed);
        return rows;
    }
    // Compile observed residual callees, including bytecode node profiles, without
    // freezing the exporter root inventory or generated worker names.
    private static List<RootCallTarget> activeTargets(RootCallTarget entry) {
        var visited = new LinkedHashSet<RootCallTarget>(); var targets = new ArrayList<RootCallTarget>();
        class Visit {
            void target(RootCallTarget target) {
                if (!visited.add(target)) return;
                var root = target.getRootNode(); var nodes = new ArrayList<Node>(); nodes.add(root);
                if (root instanceof BytecodeRoot bytecode) for (var instruction : bytecode.getBytecodeNode().getInstructions()) for (var argument : instruction.getArguments())
                    if (argument.getKind() == Instruction.Argument.Kind.NODE_PROFILE) { var node = argument.asCachedNode(); if (node != null) nodes.add(node); }
                for (var node : nodes) for (var call : NodeUtil.findAllNodeInstances(node, DirectCallNode.class)) target((RootCallTarget) call.getCurrentCallTarget());
                targets.add(target);
            }
        }
        new Visit().target(entry); return targets;
    }
    // Scheduled matrix covers complex observers across compiler stages and inlining.
    // Commit retains the independent IEEE-bit and nested whole-tuple installed-call witnesses below.
    @Test void nativeComplexAndMixedResultsExecuteInlined() throws Exception { nativeResults(true); }
    @Test void nativeComplexAndMixedResultsExecuteAcrossResidualCalls() throws Exception { nativeResults(false); }
    private void nativeResults(boolean inlining) throws Exception {
        var rows = nativeRows();
        for (var stage : list("pre", "post")) for (var backend : list("ast", "bytecode")) withLanguage(inlining, language -> {
            for (var entry : rows.entrySet()) {
                var name = entry.getKey(); var inputs = entry.getValue();
                var linked = CoreModules.reachable(module(stage), id(name));
                var bindings = objects(linked.get("bindings"));
                var program = program(language, backend, linked);
                var target = program.entryTarget((String) binding(bindings, name).get("id"));
                CheckedConsumer<Boolean> checkRows = compiled -> {
                    for (var row : inputs) {
                        var label = stage + "/" + backend + "/" + name + "/" + row[0] + "/inlining=" + inlining;
                        long before = count(program);
                        assertEquals(row[1], Calls.target(target, new Object[]{0L, row[0]}), label);
                        if (compiled) {
                            valid(target, label);
                            assertTrue(count(program) > before, label + ": first installed call must enter compiled code");
                        }
                        released(language);
                    }
                };
                checkRows.accept(false);
                var targets = activeTargets(target); for (var active : targets) compile(active);
                checkRows.accept(true);
                for (var active : targets) valid(active, stage + "/" + backend + "/" + name);
            }
        });
    }
    @Test void compiledResidualProducerPreservesNativeIeeeBits() throws Exception {
        var rows = nativeBits();
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
                    shape.consume(frame, Calls.target(target, new Object[]{0L, row[0]}), slots, 0);
                    assertTrue(frame.isFloat(slots[0])); assertTrue(frame.isDouble(slots[1]));
                    // Arithmetic NaNs may differ in payload/sign; other bits are exact native transport.
                    if (Float.isNaN(Float.intBitsToFloat((int) row[1]))) assertTrue(Float.isNaN(frame.getFloat(slots[0])));
                    else assertEquals((int) row[1], Float.floatToRawIntBits(frame.getFloat(slots[0])), backend + "/" + row[0]);
                    if (Double.isNaN(Double.longBitsToDouble(row[2]))) assertTrue(Double.isNaN(frame.getDouble(slots[1])));
                    else assertEquals(row[2], Double.doubleToRawLongBits(frame.getDouble(slots[1])), backend + "/" + row[0]);
                    if (compiled) { assertTrue(count(program) > before); valid(target, backend + "/" + row[0]); }
                    released(language);
                }
            };
            checkRows.accept(false); compile(target); checkRows.accept(true);
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
                for (long input : new long[]{-7L, 0L, 7L}) { assertEquals(20L * input - 23, Calls.target(target, new Object[]{0L, input})); released(language); }
                // Whole tuple copies must remain installed across observed residual calls.
                var targets = activeTargets(target); for (var active : targets) compile(active);
                for (long input : new long[]{-7L, 0L, 7L}) {
                    long before = count(program);
                    assertEquals(20L * input - 23, Calls.target(target, new Object[]{0L, input}));
                    valid(target, backend); assertTrue(count(program) > before); released(language);
                }
                for (var active : targets) valid(active, backend);
            }
        });
    }
}
