// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.bytecode.Instruction;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeUtil;
import java.io.File;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.Test;
import thc.*;
import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings("unchecked")
public class ManagedMVarNativeTest {
    private final File root = new File(System.getProperty("thc.projectRoot"));
    private final File directory = new File(root, "build/managed-mvars");
    private final List<String> names = List.of("transitions", "lazyPayload", "aliasRoundTrip", "unliftedPayload", "closurePayload");
    private Context context(boolean inlining) { return Context.newBuilder("thc").allowExperimentalOptions(true)
        .option("compiler.Inlining", Boolean.toString(inlining)).option("engine.BackgroundCompilation", "false")
        .option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw").build(); }
    private Map<String, Object> manifest() throws Exception {
        var manifest = (Map<String, Object>) Json.parse(Files.readString(new File(directory, "manifest.json").toPath()));
        assertEquals(false, manifest.get("installedArtifactsHashed"));
        for (var key : List.of("inputHashes", "artifactHashes")) for (var item : ((Map<String, String>) manifest.get(key)).entrySet()) {
            var actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(new File(root, item.getKey()).toPath())));
            assertEquals(item.getValue(), actual, "Stale MVar source/artifact: " + item.getKey());
        }
        boolean accepted = true; for (var status : ((Map<String, String>) manifest.get("auditStatus")).values()) accepted &= "accepted".equals(status);
        assertTrue(accepted); return manifest;
    }
    private long model(String name, long x) {
        return switch (name) {
            case "transitions", "unliftedPayload" -> x * (1 + 257 + 65537) + (x + 17) * 16777259 + 96;
            case "lazyPayload" -> x + 30;
            case "aliasRoundTrip" -> (x + (x < 0 ? 1 : 0)) * 257 + (x + (x >= 0 ? 1 : 0)) * 65537 + 28 * x;
            case "closurePayload" -> 4 * x + 17;
            default -> throw new IllegalStateException(name);
        };
    }
    private void valid(RootCallTarget target, String label) throws Exception { assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target), label); }
    private void compile(RootCallTarget target) throws Exception { target.getClass().getMethod("compile", boolean.class).invoke(target, true); valid(target, "installed"); }
    private List<RootCallTarget> activeTargets(RootCallTarget entry) {
        var seen = Collections.newSetFromMap(new IdentityHashMap<RootCallTarget, Boolean>()); var result = new ArrayList<RootCallTarget>();
        visit(entry, result, seen); return result;
    }
    private void visit(RootCallTarget target, List<RootCallTarget> result, Set<RootCallTarget> seen) {
        if (!seen.add(target)) return;
        var body = target.getRootNode(); var nodes = new ArrayList<Node>(); nodes.add(body);
        if (body instanceof BytecodeRoot bytecode) for (var instruction : bytecode.getBytecodeNode().getInstructions()) for (var argument : instruction.getArguments())
            if (argument.getKind() == Instruction.Argument.Kind.NODE_PROFILE) { var cached = argument.asCachedNode(); if (cached != null) nodes.add(cached); }
        var calls = new ArrayList<DirectCallNode>(); for (var node : nodes) calls.addAll(NodeUtil.findAllNodeInstances(node, DirectCallNode.class));
        for (var call : calls) if (call.getCurrentCallTarget() instanceof RootCallTarget active && active.getRootNode() instanceof GuestRoot) visit(active, result, seen);
        result.add(target);
    }
    private void released(Language language) {
        var state = language.getHandoffState().get();
        assertEquals(0, state.getArguments().getDepth()); assertEquals(0, state.getArguments().retainedReferences());
        assertEquals(0, state.getResults().getDepth()); assertEquals(0, state.getResults().retainedReferences()); assertNull(state.getPending());
    }
    @Test public void nativeMVarsWithInlining() throws Exception { nativeCases(true); }
    @Test public void nativeMVarsAcrossResidualCalls() throws Exception { nativeCases(false); }
    private record Row(long input, long expected) {}
    private void check(Value function, Language language, String label, Row row) {
        assertEquals(row.expected(), function.execute(row.input()).asLong(), label + "/" + row.input()); released(language);
    }
    private void nativeCases(boolean inlining) throws Exception {
        var manifest = manifest(); assertEquals(names, manifest.get("entryNames"));
        var rows = new LinkedHashMap<String, List<Row>>();
        for (var line : Files.readAllLines(new File(directory, "oracle.tsv").toPath())) {
            var parts = line.split("\t", -1); rows.computeIfAbsent(parts[0], ignored -> new ArrayList<>()).add(new Row(Long.parseLong(parts[1]), Long.parseLong(parts[2])));
        }
        assertEquals(new LinkedHashSet<>(names), rows.keySet());
        int count = 0; for (var values : rows.values()) count += values.size();
        assertEquals(845, count); assertEquals(845, ((Number) manifest.get("nativeRows")).intValue());
        var stages = (Map<String, List<String>>) manifest.get("stages"); assertEquals(Set.of("pre", "post"), stages.keySet());
        for (var stageRecord : stages.entrySet()) {
            var stage = stageRecord.getKey(); var modules = new ArrayList<Map<String, Object>>();
            for (var path : stageRecord.getValue()) modules.add(CoreCbdFixtures.read(new File(root, path).toPath()));
            var module = CoreModules.merge(modules);
            for (var name : names) {
                var cases = rows.get(name); var expectedInputs = new ArrayList<Long>(); var actualInputs = new ArrayList<Long>();
                for (var input : (List<Number>) manifest.get("inputs")) expectedInputs.add(input.longValue());
                for (var row : cases) actualInputs.add(row.input());
                assertEquals(expectedInputs, actualInputs); assertEquals(cases.size(), new LinkedHashSet<>(actualInputs).size());
                for (var row : cases) assertEquals(model(name, row.input()), row.expected(), "Native " + name + "/" + row.input());
                for (var backend : List.of("ast", "bytecode")) try (var context = context(inlining)) {
                    context.initialize("thc"); context.enter();
                    try {
                        var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                        var linked = new LinkedHashMap<>(CoreModules.reachable(module, "main:ManagedMVarAudit." + name)); linked.put("instrument", true);
                        ExecutableProgram program = backend.equals("ast") ? new Program(language, linked) : new BytecodeProgram(language, linked);
                        var entry = program.entryTarget("main:ManagedMVarAudit." + name); var host = program.hostEntryTarget(1); var function = context.asValue(new EntryValue(program, "main:ManagedMVarAudit." + name, 1));
                        var label = stage + "/" + backend + "/" + name + "/inlining=" + inlining;
                        // Retain original normal compilation policy during warmup.
                        for (var row : cases) check(function, language, label, row);
                        var targets = activeTargets(host); var installed = new LinkedHashSet<>(targets); installed.add(entry);
                        for (var target : installed) if (target != host) compile(target);
                        assertTrue(function.invokeMember("compile").asBoolean(), label + " host installation");
                        for (var row : cases) {
                            var before = ((Number) program.diagnostics().get("compiledEntries")).longValue();
                            check(function, language, label, row);
                            var after = ((Number) program.diagnostics().get("compiledEntries")).longValue();
                            assertTrue(after > before, label + "/" + row.input() + " entered compiled guest code");
                            valid(entry, label + " original entry"); assertEquals(targets, activeTargets(host), label + " active target identities");
                            for (var target : targets) valid(target, label + " active target");
                        }
                        for (var counter : List.of("unsupportedTraps", "blackholes")) assertEquals(0L, ((Number) program.diagnostics().get(counter)).longValue(), label + "/" + counter);
                    } finally { context.leave(); }
                }
            }
        }
    }
}
