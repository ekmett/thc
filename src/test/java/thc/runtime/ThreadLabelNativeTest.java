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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import thc.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(180)
@SuppressWarnings("unchecked")
public class ThreadLabelNativeTest {
    private final File root = new File(System.getProperty("thc.projectRoot"));
    private final File directory = new File(root, "build/thread-label");
    private boolean valid(RootCallTarget target) throws Exception { return Boolean.TRUE.equals(target.getClass().getMethod("isValidLastTier").invoke(target)); }
    private List<RootCallTarget> targets(RootCallTarget entry) {
        var found = new ArrayList<RootCallTarget>();
        var seen = Collections.newSetFromMap(new IdentityHashMap<RootCallTarget, Boolean>());
        visit(entry, found, seen); return found;
    }
    private void visit(RootCallTarget target, List<RootCallTarget> found, Set<RootCallTarget> seen) {
        if (!seen.add(target)) return;
        var node = target.getRootNode(); var nodes = new ArrayList<Node>(); nodes.add(node);
        if (node instanceof BytecodeRoot bytecode) for (var instruction : bytecode.getBytecodeNode().getInstructions()) for (var argument : instruction.getArguments())
            if (argument.getKind() == Instruction.Argument.Kind.NODE_PROFILE) { var cached = argument.asCachedNode(); if (cached != null) nodes.add(cached); }
        var calls = new ArrayList<DirectCallNode>(); for (var item : nodes) calls.addAll(NodeUtil.findAllNodeInstances(item, DirectCallNode.class));
        var callees = new ArrayList<RootCallTarget>();
        for (var call : calls) if (call.getCurrentCallTarget() instanceof RootCallTarget callee && callee.getRootNode() instanceof GuestRoot) callees.add(callee);
        for (var callee : callees) visit(callee, found, seen);
        found.add(target);
    }
    private long checksum(long seed) { long result = 4; for (long value : new long[]{206, 187, 0, seed & 127L}) result = result * 31 + value; return result; }
    private long expected(String entry, long seed) { return switch (entry) { case "emptyLabel" -> 0L; case "overwriteLabel" -> checksum(seed + 1); default -> checksum(seed); }; }
    @Test public void nativeLabelsMatchAndFirstInstalledSetterGetterUseExactTupleInBothBackends() throws Exception {
        var manifest = (Map<?, ?>) Json.parse(Files.readString(new File(directory, "manifest.json").toPath()));
        for (var kind : List.of("inputHashes", "artifactHashes")) for (var item : ((Map<?, ?>) manifest.get(kind)).entrySet()) {
            var path = (String) item.getKey();
            var actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(new File(root, path).toPath())));
            assertEquals(item.getValue(), actual, "Stale thread label fixture: " + path);
        }
        var cases = List.of("selfLabel", "overwriteLabel", "emptyLabel", "deadLabel", "deadOverwrite");
        var nativeRows = new ArrayList<String>(); for (long seed : new long[]{0, 65, 127}) for (var entry : cases) nativeRows.add(Long.toString(expected(entry, seed)));
        assertEquals(nativeRows, Files.readAllLines(new File(directory, "oracle.txt").toPath()));
        for (var stage : List.of("pre", "post")) for (var backend : List.of("ast", "bytecode")) for (var entry : cases) {
            try (var context = Context.newBuilder("thc").allowExperimentalOptions(true).allowCreateThread(true)
                .option("compiler.Inlining", "false").option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
                .option("engine.SingleTierCompilationThreshold", "10000000").option("engine.CompilationFailureAction", "Throw").build()) {
                context.initialize("thc"); context.enter();
                try {
                    var module = (Map<String, Object>) Json.parse(Files.readString(new File(directory, stage + "/core/ThreadLabelAudit.json").toPath()));
                    var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                    var linked = new LinkedHashMap<>(CoreModules.reachable(module, entry)); linked.put("instrument", true);
                    ExecutableProgram program = backend.equals("ast") ? new Program(language, linked) : new BytecodeProgram(language, linked, true);
                    var function = context.asValue(new EntryValue(program, entry, 1));
                    for (int i = 0; i < 3; i++) assertEquals(expected(entry, 0L), function.execute(0L).asLong(), stage + "/" + backend + "/" + entry);
                    var active = targets(program.entryTarget(entry));
                    var observe = program.entryTarget(module.get("unit") + ":ThreadLabelAudit.observe");
                    var identity = ((GuestRoot) observe.getRootNode()).getCoreIdentity();
                    assertNotNull(identity, "The exported observe binding has a Core identity");
                    // Follow Core identity, since AST labels and split targets are not binding identities.
                    var observations = new ArrayList<RootCallTarget>(); var identities = new ArrayList<Object>();
                    for (var target : active) {
                        var id = ((GuestRoot) target.getRootNode()).getCoreIdentity();
                        if (Objects.equals(id, identity)) observations.add(target);
                        identities.add(id == null ? target.getRootNode().getName() : id);
                    }
                    assertTrue(!observations.isEmpty(), "The actual guest graph includes " + identity + ": " + identities);
                    var setters = new ArrayList<RootCallTarget>();
                    if (!entry.startsWith("dead")) {
                        var writer = program.entryTarget(module.get("unit") + ":ThreadLabelAudit.setLabel");
                        var writerId = ((GuestRoot) writer.getRootNode()).getCoreIdentity(); assertNotNull(writerId);
                        for (var target : active) if (Objects.equals(((GuestRoot) target.getRootNode()).getCoreIdentity(), writerId)) setters.add(target);
                        assertTrue(!setters.isEmpty(), "The retained setter is actually called");
                    }
                    for (var target : active) { target.getClass().getMethod("compile", boolean.class).invoke(target, true); assertTrue(valid(target)); }
                    assertTrue(function.invokeMember("compile").asBoolean());
                    var before = ((Number) program.diagnostics().get("compiledEntries")).longValue();
                    assertEquals(expected(entry, 65L), function.execute(65L).asLong(), stage + "/" + backend + "/" + entry + " first installed observation");
                    assertTrue(((Number) program.diagnostics().get("compiledEntries")).longValue() - before >= 2, "Entry and retained threadLabel# body enter installed code");
                    var checked = new ArrayList<>(observations); checked.addAll(setters);
                    for (var target : checked) assertTrue(valid(target), "First observation preserves its compiled primitive body");
                    assertEquals(0L, program.diagnostics().get("unsupportedTraps"));
                } finally { context.leave(); }
            }
        }
    }
}
