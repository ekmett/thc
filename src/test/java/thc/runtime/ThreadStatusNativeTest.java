// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.bytecode.Instruction;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeUtil;
import com.oracle.truffle.api.source.Source;
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
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import thc.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(180)
@SuppressWarnings("unchecked")
public class ThreadStatusNativeTest {
    private final File root = new File(System.getProperty("thc.projectRoot"));
    private final File directory = new File(root, "build/thread-status");
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
    private void checkReceipt() throws Exception {
        var manifest = (Map<?, ?>) Json.parse(Files.readString(new File(directory, "manifest.json").toPath()));
        for (var kind : List.of("inputHashes", "artifactHashes")) for (var item : ((Map<?, ?>) manifest.get(kind)).entrySet()) {
            var path = (String) item.getKey();
            var actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(new File(root, path).toPath())));
            assertEquals(item.getValue(), actual, "Stale thread status fixture: " + path);
        }
        assertEquals(List.of("0", "0", "16", "17", "1", "14"), Files.readAllLines(new File(directory, "oracle.txt").toPath()));
    }
    private long execute(Value function, Long reader, long token) { return reader == null ? function.execute(token).asLong() : function.execute(reader, token).asLong(); }
    private record Case(String entry, Long reader, long expected) {}
    @ParameterizedTest @ValueSource(booleans = {false, true})
    public void nativeStatusesMatchAndFirstInstalledObservationUsesTypedTupleInBothBackends(boolean asyncExceptions) throws Exception {
        checkReceipt();
        var cases = List.of(new Case("selfStatus", null, 0L), new Case("maskedStatus", null, 0L),
            new Case("finishedStatus", null, 16L), new Case("diedStatus", null, 17L), new Case("blockedStatus", 0L, 1L), new Case("blockedStatus", 1L, 14L));
        for (var stage : List.of("pre", "post")) for (var backend : List.of("ast", "bytecode")) for (var item : cases) {
            var entry = item.entry(); var reader = item.reader(); long expected = item.expected();
            try (var context = Context.newBuilder("thc").allowExperimentalOptions(true).allowCreateThread(true)
                .option("compiler.Inlining", "false").option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
                .option("engine.SingleTierCompilationThreshold", "10000000").option("engine.CompilationFailureAction", "Throw").build()) {
                context.initialize("thc"); context.enter();
                try {
                    var module = (Map<String, Object>) thc.CoreCbdFixtures.read(new File(directory, stage + "/core/ThreadStatusAudit.cbd").toPath());
                    var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                    var linked = new LinkedHashMap<>(CoreModules.reachable(module, "main:ThreadStatusAudit." + entry)); linked.put("instrument", true);
                    ExecutableProgram program = backend.equals("ast") ? new Program(language, linked, asyncExceptions) : new BytecodeProgram(language, linked, asyncExceptions);
                    var function = context.asValue(new EntryValue(program, "main:ThreadStatusAudit." + entry, reader == null ? 1 : 2));
                    var label = stage + "/" + backend + "/async=" + asyncExceptions + "/" + entry;
                    for (int i = 0; i < 3; i++) assertEquals(expected, execute(function, reader, 0L), label);
                    var active = targets(program.entryTarget("main:ThreadStatusAudit." + entry));
                    var observe = program.entryTarget(module.get("unit") + ":ThreadStatusAudit.observe");
                    var identity = ((GuestRoot) observe.getRootNode()).getCoreIdentity();
                    assertNotNull(identity, "The exported observe binding has a Core identity");
                    // Core identities survive argument-based AST labels and target splitting.
                    var observations = new ArrayList<RootCallTarget>(); var identities = new ArrayList<Object>();
                    for (var target : active) {
                        var id = ((GuestRoot) target.getRootNode()).getCoreIdentity();
                        if (Objects.equals(id, identity)) observations.add(target);
                        identities.add(id == null ? target.getRootNode().getName() : id);
                    }
                    assertTrue(!observations.isEmpty(), "The actual guest graph includes " + identity + ": " + identities);
                    for (var target : active) { target.getClass().getMethod("compile", boolean.class).invoke(target, true); assertTrue(valid(target)); }
                    assertTrue(function.invokeMember("compile").asBoolean());
                    var before = ((Number) program.diagnostics().get("compiledEntries")).longValue();
                    assertEquals(expected + 1L, execute(function, reader, 1L), label + " first installed observation");
                    assertTrue(((Number) program.diagnostics().get("compiledEntries")).longValue() - before >= 2, "Entry and retained threadStatus# body enter installed code");
                    for (var target : observations) assertTrue(valid(target), "First observation preserves its compiled primitive body");
                    assertEquals(0L, program.diagnostics().get("unsupportedTraps"));
                } finally { context.leave(); }
            }
        }
    }
    @ParameterizedTest @ValueSource(booleans = {false, true})
    public void publicForkCompletionAndGuestFailureStatusesRespectAsyncMode(boolean asyncExceptions) throws Exception {
        checkReceipt();
        for (var stage : List.of("pre", "post")) for (var backend : List.of("ast", "bytecode"))
            for (var item : List.of(new Case("finishedStatus", null, 16L), new Case("diedStatus", null, 17L))) {
                var entry = item.entry(); long expected = item.expected();
                var label = stage + "/" + backend + "/async=" + asyncExceptions + "/" + entry;
                try (var context = Context.newBuilder("thc").allowExperimentalOptions(true).allowCreateThread(true)
                    .option("compiler.Inlining", "false").option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
                    .option("engine.SingleTierCompilationThreshold", "10000000").option("engine.CompilationFailureAction", "Throw").build()) {
                    var source = new File(directory, stage + "/core/ThreadStatusAudit.cbd");
                    context.initialize("thc"); context.enter();
                    EntryValue loaded;
                    try {
                        // Inspect the public parser's actual graph, not reconstructed Programs or independent CAFs.
                        loaded = (EntryValue) Language.currentState().getEnv().parsePublic(Source.newBuilder("thc",
                            CoreModules.request(List.of(source.getPath()), entry, true, false, backend, true, false, null, asyncExceptions), "thread-status-request").build()).call();
                    } finally { context.leave(); }
                    var function = context.asValue(loaded);
                    var field = EntryValue.class.getDeclaredField("guestTarget"); field.setAccessible(true);
                    var host = (RootCallTarget) field.get(loaded);
                    for (int i = 0; i < 3; i++) assertEquals(expected, function.execute(0L).asLong(), label);
                    context.enter(); List<RootCallTarget> active;
                    try {
                        active = targets(host);
                        for (var target : active) { target.getClass().getMethod("compile", boolean.class).invoke(target, true); assertTrue(valid(target), label); }
                    } finally { context.leave(); }
                    var observations = new ArrayList<RootCallTarget>();
                    for (var target : active) {
                        var identity = target.getRootNode() instanceof GuestRoot guest ? guest.getCoreIdentity() : null;
                        if (identity != null && "ThreadStatusAudit".equals(identity.moduleName()) && "observe".equals(identity.occurrence())) observations.add(target);
                    }
                    assertTrue(!observations.isEmpty(), label + " retains the real threadStatus# body");
                    // Install entry and bridge after residual callees, with no intervening guest invocation.
                    assertTrue(function.invokeMember("compile").asBoolean(), label);
                    var before = (Map<?, ?>) Json.parse(function.getMember("diagnostics").asString());
                    assertEquals(backend, before.get("backend"), label); assertEquals(asyncExceptions, before.get("asyncExceptions"), label);
                    assertEquals(expected + 1L, function.execute(1L).asLong(), label + " first installed observation");
                    var after = (Map<?, ?>) Json.parse(function.getMember("diagnostics").asString());
                    assertTrue(((Number) after.get("compiledEntries")).longValue() - ((Number) before.get("compiledEntries")).longValue() >= 2,
                        label + " entry and retained threadStatus# body enter installed code");
                    for (var target : observations) assertTrue(valid(target), label + " first call preserves " + target.getRootNode().getName());
                    assertEquals(0L, after.get("unsupportedTraps"), label);
                }
            }
    }
}
