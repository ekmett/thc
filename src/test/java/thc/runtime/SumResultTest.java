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
import org.junit.jupiter.api.BeforeEach;
import thc.CoreModules;
import thc.CoreCbdFixtures;
import java.nio.file.Path;
import thc.Language;
import java.io.File;
import java.nio.file.Files;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static thc.runtime.SumEvidence.verifySumResultEvidence;

@SuppressWarnings("unchecked")
class SumResultTest {
    private final File root = new File(System.getProperty("thc.projectRoot"));
    @BeforeEach void verifyEvidence() throws Exception { verifySumResultEvidence(root); }
    private Path artifact(String stage, boolean extended) {
        return new File(root, "build/" + (extended ? "sum-result" : "sum-layout") + "/" + stage + "-core/" + (extended ? "SumResultAudit" : "SumLayoutAudit") + ".cbd").toPath();
    }
    private Map<String, Object> module(String stage, boolean extended) throws Exception { return CoreCbdFixtures.read(artifact(stage, extended)); }
    private String entry(String name, boolean extended, boolean frontier) {
        return "main:" + (frontier ? "AggregateFrontier" : extended ? "SumResultAudit" : "SumLayoutAudit") + "." + name;
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
    @Test void nativeResultsInline() throws Exception { nativeResults(true, false, false, false); }
    @Test void nativeResultsResidual() throws Exception { nativeResults(false, false, false, false); }
    @Test void extendedNativeResultsInline() throws Exception { nativeResults(true, true, false, false); }
    @Test void extendedNativeResultsResidual() throws Exception { nativeResults(false, true, false, false); }
    @Test void originalFrontierResultsInline() throws Exception { nativeResults(true, false, true, false); }
    @Test void originalFrontierResultsResidual() throws Exception { nativeResults(false, false, true, false); }
    @Test void expandedLayoutResultsInline() throws Exception { nativeResults(true, false, false, true); }
    @Test void expandedLayoutResultsResidual() throws Exception { nativeResults(false, false, false, true); }
    private void nativeResults(boolean inlining, boolean extended, boolean frontier, boolean expanded) throws Exception {
        var supported = expanded ? Set.of("narrowWideCase", "threeWayCase", "nestedCase") : Set.of("sumCase", "directCase", "lazyCase", "zeroCase", "unitCase", "boxedKindsCase", "floatDoubleCase");
        var rows = new LinkedHashMap<String, List<List<String>>>();
        for (String line : Files.readAllLines(new File(root, "build/" + (frontier ? "aggregate-native" : extended ? "sum-result" : "sum-layout") + "/oracle.tsv").toPath())) {
            var row = Arrays.asList(line.split("\t", -1));
            if (frontier ? Set.of("sumPayload", "sumZeroLazy", "coldSum").contains(row.getFirst()) : extended || supported.contains(row.getFirst())) rows.computeIfAbsent(row.getFirst(), ignored -> new ArrayList<>()).add(row);
        }
        for (String stage : List.of("pre", "post")) for (String backend : List.of("ast", "bytecode")) try (var context = context(inlining)) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                for (var group : rows.entrySet()) {
                    String name = group.getKey(); var inputs = group.getValue();
                    var artifact = frontier ? new File(root, "build/" + (stage.equals("pre") ? "aggregate-core" : "aggregate-post-core") + "/AggregateFrontier.cbd").toPath() : artifact(stage, extended);
                    var source = CoreCbdFixtures.read(artifact);
                    var id = entry(name, extended, frontier);
                    var linked = CoreModules.reachable(source, id); var bindings = (List<Map<String, Object>>) linked.get("bindings");
                    List<String> retainedPath = switch (name) {
                        case "zeroCase" -> new ArrayCoreEvidence(source, id).loweredStateFunctionPath(entry("zeroSum", extended, frontier), id, List.of(2));
                        case "mixedCase" -> new ArrayCoreEvidence(source, id).loweredStateFunctionPath(entry("mixed", extended, frontier), id, List.of(2));
                        // Only the nonnegative source arm contains runRW, but its
                        // exact State# lambda is in-frame on both lowered paths.
                        case "boxedKindsCase" -> new ArrayCoreEvidence(source, id).loweredStateFunctionPath(entry("boxedKindsSum", extended, frontier), entry("boxedKindsSum", extended, frontier), List.of(2, 3, 0, 3));
                        default -> null;
                    };
                    ExecutableProgram program = backend.equals("ast") ? new Program(language, linked) : new BytecodeProgram(language, linked);
                    var target = program.entryTarget(bindingId(bindings, id)); var host = program.hostEntryTarget(1); var entry = program.entryValue(id);
                    class Check {
                        List<RootCallTarget> active = List.of();
                        Object invoke(long x) { return Calls.target(host, new Object[]{entry, new Object[]{x}}); }
                        void run(boolean compiled) throws ReflectiveOperationException {
                            for (var row : inputs) {
                                String label = stage + "/" + backend + "/" + name + "/" + row.get(1) + "/inline=" + inlining;
                                if (compiled && row.get(2).equals("throws")) continue;
                                long before = count(program), selfBefore = ((Number) program.diagnostics().get("selfTailReentries")).longValue(), bounceBefore = ((Number) program.diagnostics().get("trampolineIterations")).longValue();
                                if (row.get(2).equals("throws")) assertThrows(GuestException.class, () -> invoke(Long.parseLong(row.get(1))));
                                else assertEquals(Long.parseLong(row.get(2)), invoke(Long.parseLong(row.get(1))), label);
                                if (compiled) {
                                    valid(target, label); valid(host, label);
                                    assertEquals(active, activeTargets(host), label + " active target identity");
                                    for (var activeTarget : active) valid(activeTarget, label);
                                    long expected = retainedPath != null ? retainedPath.size() : switch (name) {
                                        case "directCase", "coldSum" -> 1L;
                                        case "sumPayload" -> 2L; // The strict Box Int# is constructed in the producer root.
                                        case "forwardCase", "pairedCase", "selfCase", "effectStateCase", "effectEmptyCase", "throwCase" -> 3L;
                                        case "outstandingCase" -> 5L;
                                        case "mutualCase" -> 6L; // Two A reentries reuse its frame; B enters three times.
                                        default -> 2L;
                                    };
                                    assertEquals(expected, count(program) - before, label + " " + program.diagnostics());
                                    if (name.equals("selfCase") || name.equals("mutualCase")) {
                                        assertEquals(name.equals("selfCase") ? 5L : 2L, ((Number) program.diagnostics().get("selfTailReentries")).longValue() - selfBefore, label);
                                        assertEquals(0L, ((Number) program.diagnostics().get("trampolineIterations")).longValue() - bounceBefore, label);
                                    }
                                }
                                var state = language.getHandoffState().get();
                                assertEquals(0, state.getResults().getDepth(), label); assertEquals(0, state.getResults().retainedReferences(), label);
                                assertEquals(0, state.getArguments().getDepth(), label); assertEquals(0, state.getArguments().retainedReferences(), label);
                            }
                        }
                    }
                    Check check = new Check(); check.run(false); check.active = activeTargets(host);
                    assertTrue(check.active.size() > 1, "Missing observed host guest-call target");
                    if (retainedPath != null) {
                        var guestIds = new ArrayList<String>();
                        for (var active : check.active) if (active != host) { var identity = ((GuestRoot) active.getRootNode()).getCoreIdentity(); guestIds.add(identity == null ? null : identity.bindingId()); }
                        assertEquals(retainedPath.size(), guestIds.size(), stage + "/" + backend + "/" + name + " retained guest roots");
                        assertEquals(new LinkedHashSet<>(retainedPath), new LinkedHashSet<>(guestIds), stage + "/" + backend + "/" + name + " source root identities");
                    }
                    var compileTargets = new LinkedHashSet<RootCallTarget>(); compileTargets.add(target); compileTargets.addAll(check.active); for (var active : compileTargets) compile(active);
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
        var rows = new ArrayList<List<String>>(); for (String line : Files.readAllLines(new File(root, "build/sum-result/oracle-pairs.tsv").toPath())) rows.add(Arrays.asList(line.split("\t", -1)));
        for (String stage : List.of("pre", "post")) for (String backend : List.of("ast", "bytecode")) try (var context = context(inlining)) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); var linked = CoreModules.reachable(module(stage, true), "main:SumResultAudit.pairedInputs"); var bindings = (List<Map<String, Object>>) linked.get("bindings");
                ExecutableProgram program = backend.equals("ast") ? new Program(language, linked) : new BytecodeProgram(language, linked);
                var original = program.entryTarget(bindingId(bindings, "main:SumResultAudit.pairedInputs")); var host = program.hostEntryTarget(2); var entry = program.entryValue("main:SumResultAudit.pairedInputs");
                java.util.function.Function<List<String>, Object> invoke = row -> Calls.target(host, new Object[]{entry, new Object[]{Long.parseLong(row.get(1)), Long.parseLong(row.get(2))}});
                for (var row : rows) assertEquals(Long.parseLong(row.get(3)), invoke.apply(row));
                var active = activeTargets(host); assertTrue(active.size() > 1);
                var compileTargets = new LinkedHashSet<RootCallTarget>(); compileTargets.add(original); compileTargets.addAll(active); for (var target : compileTargets) compile(target);
                for (var row : rows) {
                    String label = stage + "/" + backend + "/pairedInputs/" + row + "/inline=" + inlining; long before = count(program);
                    assertEquals(Long.parseLong(row.get(3)), invoke.apply(row), label); assertEquals(2L, count(program) - before, label);
                    valid(original, label); valid(host, label); assertEquals(active, activeTargets(host), label + " active target identity"); for (var target : active) valid(target, label);
                    var state = language.getHandoffState().get();
                    assertEquals(0, state.getResults().getDepth(), label); assertEquals(0, state.getResults().retainedReferences(), label);
                    assertEquals(0, state.getArguments().getDepth(), label); assertEquals(0, state.getArguments().retainedReferences(), label);
                }
            } finally { context.leave(); }
        }
    }
}
