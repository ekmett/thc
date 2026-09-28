// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.bytecode.Instruction;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeUtil;
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
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

/** Genuine GHC boundaries, including actual PAP and overapplication in both exports. */
@SuppressWarnings("unchecked")
class TupleInputNativeTest {
    private final Path root = Path.of(System.getProperty("thc.projectRoot"));
    private final Path folder = root.resolve("build/tuple-input");
    @BeforeEach void currentEvidence() throws Exception {
        var evidence = (Map<String, Object>) Json.parse(Files.readString(folder.resolve("provenance.json")));
        for (var kind : List.of("sources", "artifacts")) for (var item : (List<Map<String, String>>) evidence.get(kind)) {
            var path = root.resolve(item.get("path"));
            var hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
            assertEquals(item.get("sha256"), hash, "Stale typed-input evidence: " + path);
        }
        for (var stage : List.of("pre", "post")) {
            var audit = (Map<String, Object>) Json.parse(Files.readString(folder.resolve(stage + "-audit.json")));
            assertEquals(true, audit.get("accepted"), stage + " strict native audit");
        }
        var checks = (Map<String, Object>) Json.parse(Files.readString(folder.resolve("checks.json")));
        for (var coverage : (List<Map<String, Object>>) checks.get("coverage")) {
            var converted = new LinkedHashMap<String, Long>();
            for (var entry : ((Map<String, Number>) coverage.get("expectedGuestEntries")).entrySet()) converted.put(entry.getKey(), entry.getValue().longValue());
            assertEquals(entries, converted, "Preserve the original exported source paths, including the immediate State# lambda");
        }
    }
    private static Context context(boolean inlining) {
        return Context.newBuilder("thc").allowExperimentalOptions(true).option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
            .option("engine.CompilationFailureAction", "Throw").option("compiler.Inlining", Boolean.toString(inlining)).build();
    }
    private static void valid(RootCallTarget target, String label) throws Exception { assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target), label); }
    private static void compile(RootCallTarget target) throws Exception { target.getClass().getMethod("compile", boolean.class).invoke(target, true); valid(target, "initial installation: " + target); }
    private static void visit(RootCallTarget target, Set<RootCallTarget> visited, List<RootCallTarget> ordered) {
        if (!visited.add(target)) return;
        var root = target.getRootNode(); var nodes = new ArrayList<Node>(); nodes.add(root);
        if (root instanceof BytecodeRoot bytecodeRoot) for (var instruction : bytecodeRoot.getBytecodeNode().getInstructions()) for (var argument : instruction.getArguments())
            if (argument.getKind() == Instruction.Argument.Kind.NODE_PROFILE) { var node = argument.asCachedNode(); if (node != null) nodes.add(node); }
        var calls = new ArrayList<DirectCallNode>();
        for (var node : nodes) calls.addAll(NodeUtil.findAllNodeInstances(node, DirectCallNode.class));
        for (var call : calls) visit((RootCallTarget) call.getCurrentCallTarget(), visited, ordered);
        ordered.add(target);
    }
    private static List<RootCallTarget> targets(RootCallTarget host) {
        var visited = new LinkedHashSet<RootCallTarget>(); var ordered = new ArrayList<RootCallTarget>(); visit(host, visited, ordered); return ordered;
    }
    private static void released(Language language, String label) {
        var state = language.getHandoffState().get(); assertNull(state.getPending(), label);
        assertEquals(0, state.getArguments().getDepth(), label); assertEquals(0, state.getArguments().retainedReferences(), label);
        assertEquals(0, state.getResults().getDepth(), label); assertEquals(0, state.getResults().retainedReferences(), label);
    }
    // The producer pins exported paths, including stateCase's immediate lambda.
    // Independently prove its in-frame lowering below before counting executable
    // roots. Recur's matching self transfers already reuse its frame.
    private final Map<String, Long> entries = Map.ofEntries(Map.entry("pairCase", 2L), Map.entry("mixedCase", 2L), Map.entry("indirectCase", 3L),
        Map.entry("prefixCase", 2L), Map.entry("papCase", 4L), Map.entry("overCase", 4L), Map.entry("lazyCase", 2L), Map.entry("roundTripCase", 4L),
        Map.entry("selfCase", 2L), Map.entry("stateCase", 3L), Map.entry("nestedCase", 2L), Map.entry("deadCase", 2L), Map.entry("effectCase", 4L),
        Map.entry("selfDepth", 2L), Map.entry("pairInputs", 2L));
    @Test void nativeTypedInputsInline() throws Exception { checkNative(true, 1); }
    @Test void nativeTypedInputsResidual() throws Exception { checkNative(false, 1); }
    @Test void independentTypedInputLanesInlineAndResidual() throws Exception { checkNative(true, 2); checkNative(false, 2); }
    private static Object invoke(String[] row, int arity, RootCallTarget host, Object closure) {
        var inputs = new Object[row.length - 2];
        for (int i = 1; i < row.length - 1; i++) inputs[i - 1] = Long.parseLong(row[i]);
        assertEquals(arity, inputs.length); return Calls.target(host, new Object[]{closure, inputs});
    }
    private void checkNative(boolean inlining, int arity) throws Exception {
        var rows = new LinkedHashMap<String, List<String[]>>();
        for (var line : Files.readAllLines(folder.resolve(arity == 1 ? "oracle.tsv" : "oracle-pairs.tsv"))) {
            var row = line.split("\t", -1); rows.computeIfAbsent(row[0], ignored -> new ArrayList<>()).add(row);
        }
        int total = 0; for (var cases : rows.values()) total += cases.size();
        assertEquals(arity == 1 ? 139 : 7, total); assertEquals(arity == 1 ? 14 : 1, rows.size());
        for (var stage : List.of("pre", "post")) {
            var module = (Map<String, Object>) Json.parse(Files.readString(folder.resolve(stage + "-core/TupleInputAudit.json")));
            for (var rowGroup : rows.entrySet()) for (var backend : List.of("ast", "bytecode")) try (var context = context(inlining)) {
                var name = rowGroup.getKey(); var cases = rowGroup.getValue();
                context.initialize("thc"); context.enter();
                try {
                    var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                    var linked = new LinkedHashMap<>(CoreModules.reachable(module, name)); linked.put("instrument", true);
                    var retainedPath = name.equals("stateCase") ? new ArrayCoreEvidence(module, name).loweredStateFunctionPath("consumeState") : null;
                    long expectedEntries = retainedPath == null ? entries.get(name) : retainedPath.size();
                    ExecutableProgram program = backend.equals("ast") ? new Program(language, linked) : new BytecodeProgram(language, linked);
                    var host = program.hostEntryTarget(arity); var original = program.entryTarget(name); var closure = program.entryValue(name);
                    var label = stage + "/" + backend + "/" + name + "/inlining=" + inlining;
                    for (var row : cases) { assertEquals(Long.parseLong(row[row.length - 1]), invoke(row, arity, host, closure), label + "/interpreted/" + Arrays.asList(row)); released(language, label); }
                    var active = targets(host); assertTrue(active.size() > 1, label + " missing observed guest call target");
                    if (retainedPath != null) {
                        var guestIds = new ArrayList<String>();
                        for (var target : active) if (target != host) { var identity = ((GuestRoot) target.getRootNode()).getCoreIdentity(); guestIds.add(identity == null ? null : identity.getBindingId()); }
                        assertEquals(retainedPath.size(), guestIds.size(), label + " retained guest roots");
                        assertEquals(new LinkedHashSet<>(retainedPath), new LinkedHashSet<>(guestIds), label + " source root identities");
                    }
                    var compiledTargets = new LinkedHashSet<RootCallTarget>(); compiledTargets.add(original); compiledTargets.addAll(active);
                    for (var target : compiledTargets) compile(target);
                    // Repair the existing host entry prerequisite without invoking
                    // guest code, exactly as the production compile operation does.
                    var runtime = com.oracle.truffle.api.Truffle.getRuntime();
                    runtime.getClass().getMethod("bypassedInstalledCode", Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget")).invoke(runtime, host);
                    for (var row : cases.reversed()) {
                        long before = (Long) program.diagnostics().get("compiledEntries");
                        assertEquals(Long.parseLong(row[row.length - 1]), invoke(row, arity, host, closure), label + "/compiled/" + Arrays.asList(row));
                        assertEquals(expectedEntries, (Long) program.diagnostics().get("compiledEntries") - before, label + "/" + Arrays.asList(row) + " compiled guest entries: " + program.diagnostics());
                        valid(original, label + " original"); valid(host, label + " host"); assertEquals(active, targets(host), label + " active target identities");
                        for (var target : active) valid(target, label + " active"); released(language, label);
                    }
                    assertEquals(0L, program.diagnostics().get("unsupportedTraps")); assertEquals(0L, program.diagnostics().get("blackholes"));
                } finally { context.leave(); }
            }
        }
    }
    private static Object call(ExecutableProgram program, long x) { return Calls.target(program.hostEntryTarget(1), new Object[]{program.entryValue("effectCase"), new Object[]{x}}); }
    @Test void nativeZeroWidthFailurePrecedesInputPublication() throws Exception {
        for (var stage : List.of("pre", "post")) for (var backend : List.of("ast", "bytecode")) try (var context = context(false)) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var module = (Map<String, Object>) Json.parse(Files.readString(folder.resolve(stage + "-core/TupleInputAudit.json")));
                var linked = CoreModules.reachable(module, "effectCase");
                ExecutableProgram program = backend.equals("ast") ? new Program(language, linked) : new BytecodeProgram(language, linked);
                assertEquals(26L, call(program, 7L)); assertThrows(GuestException.class, () -> call(program, -1L)); released(language, stage + "/" + backend + "/throw");
                assertEquals(26L, call(program, 7L)); released(language, stage + "/" + backend + "/recovery");
            } finally { context.leave(); }
        }
    }
}
