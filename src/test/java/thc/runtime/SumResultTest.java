// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeUtil;
import com.oracle.truffle.api.bytecode.Instruction;
import com.oracle.truffle.api.TruffleLanguage;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import thc.CoreModules;
import thc.CoreCbdFixtures;
import java.nio.file.Path;
import thc.Language;
import java.io.File;
import java.nio.file.Files;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Native GHC values and installed result transport, without cached acceptance or root-count policy. */
@SuppressWarnings("unchecked")
class SumResultTest {
    private final File root = new File(System.getProperty("thc.projectRoot"));
    private Path artifact(String stage, boolean extended) {
        return new File(root, "build/" + (extended ? "sum-result" : "sum-layout") + "/" + stage + "-core/" + (extended ? "SumResultAudit" : "SumLayoutAudit") + ".cbd").toPath();
    }
    private Map<String, Object> module(String stage, boolean extended) throws Exception { return CoreCbdFixtures.read(artifact(stage, extended)); }
    private String entry(String name, boolean extended) {
        return "main:" + (extended ? "SumResultAudit" : "SumLayoutAudit") + "." + name;
    }
    private Context context(boolean inlining) {
        return Context.newBuilder("thc").allowExperimentalOptions(true).option("compiler.Inlining", Boolean.toString(inlining)).option("engine.BackgroundCompilation", "false")
            .option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw").option("engine.SingleTierCompilationThreshold", "10000000").build();
    }
    private void valid(RootCallTarget target, String label) throws ReflectiveOperationException { assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target), label); }
    private void compile(RootCallTarget target) throws ReflectiveOperationException {
        target.getClass().getMethod("compile", boolean.class).invoke(target, true); valid(target, "initial compilation: " + target);
    }
    private List<RootCallTarget> activeTargets(RootCallTarget host) {
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
        new Visit().target(host); return targets;
    }
    private long count(ExecutableProgram program) { return ((Number) program.diagnostics().get("compiledEntries")).longValue(); }
    private String bindingId(List<Map<String, Object>> bindings, String name) {
        Map<String, Object> result = null;
        for (var binding : bindings) if (Objects.equals(binding.get("id"), name)) { if (result != null) throw new IllegalArgumentException("Collection contains more than one matching element."); result = binding; }
        if (result == null) throw new NoSuchElementException("Collection contains no element matching the predicate.");
        return (String) result.get("id");
    }
    private static final Set<String> RESULT_OBSERVERS = Set.of("forwardCase", "outstandingCase", "pairedCase", "lazyLeafCase", "mixedCase",
        "singletonBoxCase", "selfCase", "mutualCase", "effectStateCase", "effectEmptyCase", "throwCase");
    private static final Set<String> LAYOUT_OBSERVERS = Set.of("sumCase", "directCase", "nestedCase", "lazyCase", "zeroCase", "unitCase",
        "boxedKindsCase", "floatDoubleCase", "narrowWideCase", "threeWayCase", "boxedSumLiftedUse", "boxedSumUnliftedUse", "boxedNestedUse");
    private static final Set<String> THROWING_OBSERVERS = Set.of("effectStateCase", "effectEmptyCase", "throwCase");
    private List<List<String>> nativeRows(Path path, Set<String> expected, boolean paired) throws Exception {
        var rows = new ArrayList<List<String>>(); var keys = new HashSet<String>(); var names = new HashSet<String>();
        for (String line : Files.readAllLines(path)) {
            var row = Arrays.asList(line.split("\t", -1)); assertEquals(paired ? 4 : 3, row.size(), line);
            assertTrue(expected.contains(row.getFirst()), "Unknown native observer: " + line);
            long x = Long.parseLong(row.get(1)); row.set(1, Long.toString(x));
            if (paired) row.set(2, Long.toString(Long.parseLong(row.get(2))));
            String key = row.getFirst() + ":" + x + (paired ? ":" + Long.parseLong(row.get(2)) : "");
            assertTrue(keys.add(key), "Duplicate native input: " + line); names.add(row.getFirst());
            if (row.getLast().equals("throws")) assertTrue(!paired && THROWING_OBSERVERS.contains(row.getFirst()), line);
            else Long.parseLong(row.getLast());
            rows.add(row);
        }
        assertEquals(expected, names, "Missing native observers: " + path);
        if (!paired) for (String name : expected) {
            var inputs = rows.stream().filter(row -> row.getFirst().equals(name)).toList();
            assertTrue(inputs.stream().anyMatch(row -> Long.parseLong(row.get(1)) < Integer.MIN_VALUE)
                && inputs.stream().anyMatch(row -> Long.parseLong(row.get(1)) == 0)
                && inputs.stream().anyMatch(row -> Long.parseLong(row.get(1)) > 0xffffffffL), "Missing sign/zero/wide native inputs: " + name);
            if (THROWING_OBSERVERS.contains(name)) assertTrue(inputs.stream().anyMatch(row -> row.getLast().equals("throws"))
                && inputs.stream().anyMatch(row -> !row.getLast().equals("throws")), "Missing native throw/recovery control: " + name);
        }
        return rows;
    }
    @Test void nativeResultsInline() throws Exception { nativeResults(true, false, false); }
    @Test void nativeResultsResidual() throws Exception { nativeResults(false, false, false); }
    @Test void extendedNativeResultsInline() throws Exception { nativeResults(true, true, false); }
    @Test void extendedNativeResultsResidual() throws Exception { nativeResults(false, true, false); }
    @Test void expandedLayoutResultsInline() throws Exception { nativeResults(true, false, true); }
    @Test void expandedLayoutResultsResidual() throws Exception { nativeResults(false, false, true); }
    // Commit samples heterogeneous leaves and zero-width effect/throw recovery on real post-Tidy Core.
    // Scheduled methods retain the full observer, compiler-stage and inlining matrix.
    @Test void representativeExtendedNativeResultsResidual() throws Exception {
        nativeResults(false, true, false, Set.of("mixedCase", "effectEmptyCase"), List.of("post"));
    }
    private void nativeResults(boolean inlining, boolean extended, boolean expanded) throws Exception {
        nativeResults(inlining, extended, expanded, Set.of(), List.of("pre", "post"));
    }
    private void nativeResults(boolean inlining, boolean extended, boolean expanded, Set<String> selected, List<String> stages) throws Exception {
        var supported = expanded ? Set.of("narrowWideCase", "threeWayCase", "nestedCase") : Set.of("sumCase", "directCase", "lazyCase", "zeroCase", "unitCase", "boxedKindsCase", "floatDoubleCase");
        var rows = new LinkedHashMap<String, List<List<String>>>();
        for (var row : nativeRows(new File(root, "build/" + (extended ? "sum-result" : "sum-layout") + "/oracle.tsv").toPath(),
                extended ? RESULT_OBSERVERS : LAYOUT_OBSERVERS, false)) {
            if ((extended || supported.contains(row.getFirst())) && (selected.isEmpty() || selected.contains(row.getFirst()))) rows.computeIfAbsent(row.getFirst(), ignored -> new ArrayList<>()).add(row);
        }
        for (String stage : stages) for (String backend : List.of("ast", "bytecode")) try (var context = context(inlining)) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                for (var group : rows.entrySet()) {
                    String name = group.getKey(); var inputs = group.getValue();
                    var artifact = artifact(stage, extended);
                    var source = CoreCbdFixtures.read(artifact);
                    assertEquals("9.14.1", source.get("ghc"));
                    assertEquals(stage.equals("pre") ? "optimized-Core-before-Tidy" : "optimized-Core-after-Tidy-before-CorePrep", source.get("boundary"));
                    var id = entry(name, extended);
                    var linked = CoreModules.reachable(source, id); var bindings = (List<Map<String, Object>>) linked.get("bindings");
                    if (name.equals("singletonBoxCase")) {
                        var producer = ((List<Map<String, Object>>) source.get("bindings")).stream()
                            .filter(binding -> entry("singletonBox", true).equals(binding.get("id"))).findFirst().orElseThrow();
                        var metadata = (Map<?, ?>) ((List<?>) producer.get("expr")).getLast();
                        var singleton = CoreRepresentations.parse(metadata.get("resultRep")).getAlternatives().getFirst();
                        assertTrue(singleton.isTuple()); assertEquals(1, singleton.getComponents().size());
                        var leaf = singleton.getComponents().getFirst();
                        assertEquals(List.of("BoxedRep (Just Lifted)"), leaf.getPrimReps()); assertEquals(false, leaf.getEvaluated());
                    }
                    ExecutableProgram program = backend.equals("ast") ? new Program(language, linked) : new BytecodeProgram(language, linked);
                    var target = program.entryTarget(bindingId(bindings, id)); var host = program.hostEntryTarget(1); var entry = program.entryValue(id);
                    class Check {
                        Object invoke(long x) { return Calls.target(host, new Object[]{entry, new Object[]{x}}); }
                        void run(boolean compiled) throws ReflectiveOperationException {
                            for (var row : inputs) {
                                String label = stage + "/" + backend + "/" + name + "/" + row.get(1) + "/inline=" + inlining;
                                if (compiled && row.get(2).equals("throws")) continue;
                                long before = count(program);
                                if (row.get(2).equals("throws")) assertThrows(GuestException.class, () -> invoke(Long.parseLong(row.get(1))));
                                else assertEquals(Long.parseLong(row.get(2)), invoke(Long.parseLong(row.get(1))), label);
                                if (compiled) {
                                    valid(target, label); valid(host, label);
                                    assertTrue(count(program) > before, label + ": installed call must enter compiled code");
                                }
                                var state = language.getHandoffState().get();
                                assertEquals(0, state.getResults().getDepth(), label); assertEquals(0, state.getResults().retainedReferences(), label);
                                assertEquals(0, state.getArguments().getDepth(), label); assertEquals(0, state.getArguments().retainedReferences(), label);
                            }
                        }
                    }
                    Check check = new Check(); check.run(false);
                    var compileTargets = new LinkedHashSet<RootCallTarget>(); compileTargets.add(target); compileTargets.add(host);
                    compileTargets.addAll(activeTargets(host)); for (var active : compileTargets) compile(active);
                    check.run(true);
                    for (var row : inputs) if (row.get(2).equals("throws")) {
                        assertThrows(GuestException.class, () -> check.invoke(Long.parseLong(row.get(1))));
                        assertEquals(0, language.getHandoffState().get().getResults().getDepth());
                        assertEquals(0, language.getHandoffState().get().getResults().retainedReferences());
                        List<String> recovery = null; for (var input : inputs) if (!input.get(2).equals("throws")) { recovery = input; break; }
                        if (recovery == null) throw new NoSuchElementException("Collection contains no element matching the predicate.");
                        assertEquals(Long.parseLong(recovery.get(2)), check.invoke(Long.parseLong(recovery.get(1)))); break;
                    }
                }
            } finally { context.leave(); }
        }
    }
    @Test void independentPairInputsInline() throws Exception { paired(true); }
    @Test void independentPairInputsResidual() throws Exception { paired(false); }
    private void paired(boolean inlining) throws Exception {
        var rows = nativeRows(new File(root, "build/sum-result/oracle-pairs.tsv").toPath(), Set.of("pairedInputs"), true);
        assertTrue(rows.stream().anyMatch(row -> row.get(1).equals(row.get(2))), "Missing equal independent inputs");
        assertTrue(rows.stream().anyMatch(row -> !row.get(1).equals(row.get(2)) && rows.stream().anyMatch(other ->
            row.get(1).equals(other.get(2)) && row.get(2).equals(other.get(1)))), "Missing swapped independent inputs");
        assertTrue(rows.stream().anyMatch(row -> (Long.parseLong(row.get(1)) < Integer.MIN_VALUE || Long.parseLong(row.get(1)) > Integer.MAX_VALUE)
            && (Long.parseLong(row.get(2)) < Integer.MIN_VALUE || Long.parseLong(row.get(2)) > Integer.MAX_VALUE)), "Missing wide independent inputs");
        for (String stage : List.of("pre", "post")) for (String backend : List.of("ast", "bytecode")) try (var context = context(inlining)) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); var linked = CoreModules.reachable(module(stage, true), "main:SumResultAudit.pairedInputs"); var bindings = (List<Map<String, Object>>) linked.get("bindings");
                ExecutableProgram program = backend.equals("ast") ? new Program(language, linked) : new BytecodeProgram(language, linked);
                var original = program.entryTarget(bindingId(bindings, "main:SumResultAudit.pairedInputs")); var host = program.hostEntryTarget(2); var entry = program.entryValue("main:SumResultAudit.pairedInputs");
                java.util.function.Function<List<String>, Object> invoke = row -> Calls.target(host, new Object[]{entry, new Object[]{Long.parseLong(row.get(1)), Long.parseLong(row.get(2))}});
                for (var row : rows) assertEquals(Long.parseLong(row.get(3)), invoke.apply(row));
                var compileTargets = new LinkedHashSet<RootCallTarget>(); compileTargets.add(original); compileTargets.add(host);
                compileTargets.addAll(activeTargets(host)); for (var target : compileTargets) compile(target);
                for (var row : rows) {
                    String label = stage + "/" + backend + "/pairedInputs/" + row + "/inline=" + inlining; long before = count(program);
                    assertEquals(Long.parseLong(row.get(3)), invoke.apply(row), label); assertTrue(count(program) > before, label + ": installed call must enter compiled code");
                    valid(original, label); valid(host, label);
                    var state = language.getHandoffState().get();
                    assertEquals(0, state.getResults().getDepth(), label); assertEquals(0, state.getResults().retainedReferences(), label);
                    assertEquals(0, state.getArguments().getDepth(), label); assertEquals(0, state.getArguments().retainedReferences(), label);
                }
            } finally { context.leave(); }
        }
    }
}
