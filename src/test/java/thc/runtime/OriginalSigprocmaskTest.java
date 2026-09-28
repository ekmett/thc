// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.bytecode.Instruction;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeUtil;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import thc.ContextProfile;
import thc.CoreModules;
import thc.Json;
import thc.Language;
import thc.Main;
import static org.junit.jupiter.api.Assertions.*;

@EnabledOnOs(OS.LINUX)
@EnabledIfSystemProperty(named = "os.arch", matches = "amd64|x86_64")
@Timeout(90)
@SuppressWarnings("unchecked")
public class OriginalSigprocmaskTest {
    private final File root = new File(System.getProperty("thc.projectRoot")); private final String prefix = "build/original-sigprocmask";
    private ManagedAddress nil() { return ManagedAddress.nullAddress(); }
    private Map<String, Object> json(String path) throws Exception { return (Map<String, Object>) Json.parse(Files.readString(new File(root, path).toPath())); }
    private Map<String, Object> module(String stage) throws Exception {
        var modules = new ArrayList<Map<String, Object>>(); for (var name : List.of("OriginalSigprocmaskAudit", "THC.InterfaceClosure")) modules.add(json(prefix + "/" + stage + "/core/" + name + ".json")); return CoreModules.merge(modules);
    }
    private Context context() { return Main.withContextProfile(Context.newBuilder("thc").allowNativeAccess(true), ContextProfile.SYNCHRONOUS_TEST).build(); }
    private int size() { return (int) TermiosImage.scalar(OriginalStdioOp.SIZEOF_SIGSET, nil(), 0); }
    private long constant(OriginalStdioOp operation) { return TermiosImage.scalar(operation, nil(), 0); }
    private ManagedAddress address(byte[] bytes) { return ManagedAddress.fromByteArray(bytes); }
    @FunctionalInterface private interface Action { void run() throws Exception; }
    @FunctionalInterface private interface EnteredAction { void run(Language language) throws Exception; }
    private void platform(Action body) throws Exception {
        var task = new FutureTask<Void>(() -> { body.run(); return null; }); Thread.ofPlatform().name("thc-sigttou-control").start(task); task.get(75, TimeUnit.SECONDS);
    }
    private void entered(Context context, EnteredAction body) throws Exception {
        context.initialize("thc"); context.enter(); try { body.run(TruffleLanguage.LanguageReference.create(Language.class).get(null)); } finally { context.leave(); }
    }
    private ExecutableProgram load(Language language, String backend, Map<String, Object> source) { return backend.equals("ast") ? new Program(language, source) : new BytecodeProgram(language, source); }
    private List<RootCallTarget> targets(RootCallTarget entry) {
        Set<RootCallTarget> seen = Collections.newSetFromMap(new IdentityHashMap<>()); var result = new ArrayList<RootCallTarget>(); visit(entry, seen, result); return result;
    }
    private void visit(RootCallTarget target, Set<RootCallTarget> seen, List<RootCallTarget> result) {
        if (!seen.add(target)) return; var body = target.getRootNode(); var nodes = new ArrayList<Node>(); nodes.add(body);
        if (body instanceof BytecodeRoot bytecode) for (var instruction : bytecode.getBytecodeNode().getInstructions()) for (var argument : instruction.getArguments())
            if (argument.getKind() == Instruction.Argument.Kind.NODE_PROFILE) { var cached = argument.asCachedNode(); if (cached != null) nodes.add(cached); }
        for (var node : nodes) for (var call : NodeUtil.findAllNodeInstances(node, DirectCallNode.class))
            if (call.getCurrentCallTarget() instanceof RootCallTarget next && next.getRootNode() instanceof GuestRoot) visit(next, seen, result);
        result.add(target);
    }
    @BeforeEach public void verifyFixture() throws Exception {
        var manifest = json(prefix + "/manifest.json"); assertEquals(true, manifest.get("supported")); assertEquals(8L, manifest.get("nativeRows"));
        OriginalStdioChecks.hashes(root, manifest.get("inputHashes"), Set.of("t/fixtures/compiler/OriginalSigprocmaskAudit.hs", "t/fixtures/compiler/OriginalSigprocmaskNative.hs", "t/haskell-fixtures/OriginalSigprocmaskFixtures.hs"), null);
        var required = new LinkedHashSet<>(List.of(prefix + "/oracle.json", prefix + "/native/oracle"));
        for (var stage : List.of("pre", "post")) required.add(prefix + "/" + stage + "/originalSigprocmask.audit.json");
        OriginalStdioChecks.hashes(root, manifest.get("artifactHashes"), required, prefix + "/");
    }
    private byte[] snapshot(ManagedSignalMask service) { var result = new byte[size()]; assertEquals(0L, service.call(-1, nil(), address(result))); return result; } // NULL set ignores invalid how.
    private byte[] token(long signal) { var result = new byte[size()]; assertEquals(0L, SigsetImage.execute(OriginalStdioOp.SIGADDSET, address(result), signal, Language.currentState().getStdio())); return result; }
    private void toggle(byte[] bytes, byte[] bit) { for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) (bytes[i] ^ bit[i]); }
    private byte[] filled(int count, int value) { var bytes = new byte[count]; Arrays.fill(bytes, (byte) value); return bytes; }
    private byte[] canaries() { var bytes = filled(size() + 16, 77); Arrays.fill(bytes, 8, size() + 8, (byte) 165); return bytes; }
    private record Invocation(long how, ManagedAddress set, boolean output) {}
    @Test public void originalNativeSequenceMatchesBothBackendsAndFirstInstalledEntries() throws Exception { platform(() -> {
        var rows = (List<List<Object>>) json(prefix + "/oracle.json").get("rows"); assertEquals(8, rows.size());
        var before = List.of(0L, 0L, 0L, 1L, 1L, 0L, 1L, 1L); var after = List.of(0L, 0L, 1L, 1L, 0L, 1L, 1L, 0L);
        for (int i = 0; i < rows.size(); i++) {
            var row = rows.get(i); assertEquals((long) i, row.get(0)); assertEquals(i == 6 ? -1L : 0L, row.get(1));
            assertEquals(i == 6 ? StdioHostAbi.load().error(5) : 0L, row.get(2)); assertEquals(before.get(i), row.get(3)); assertEquals(after.get(i), row.get(4)); assertEquals(List.of(true, true, true), row.subList(5, row.size()));
        }
        for (var stage : List.of("pre", "post")) {
            var audit = json(prefix + "/" + stage + "/originalSigprocmask.audit.json"); assertEquals(true, audit.get("accepted")); assertEquals(List.of(), audit.get("issues")); assertEquals(List.of(), audit.get("missingGlobals"));
            var symbols = new ArrayList<>(); for (var call : (List<Map<?, ?>>) audit.get("foreignCalls")) symbols.add(call.get("symbol")); assertEquals(List.of(OriginalStdioOp.SIGPROCMASK.getSymbol()), symbols);
            for (var backend : List.of("ast", "bytecode")) try (var context = context()) { entered(context, language -> {
                var service = Language.currentState().getSignalMask(); var baseline = snapshot(service); var bit = token(constant(OriginalStdioOp.SIGTTOU)); var normal = baseline.clone();
                for (int i = 0; i < normal.length; i++) normal[i] = (byte) (normal[i] & ~bit[i]);
                long setmask = constant(OriginalStdioOp.SIG_SETMASK), block = constant(OriginalStdioOp.SIG_BLOCK);
                var source = new LinkedHashMap<>(CoreModules.reachable(module(stage), "originalSigprocmask", true)); source.put("instrument", true);
                var program = load(language, backend, source); var entry = program.entryTarget("originalSigprocmask");
                try {
                    class Runner { void exercise(boolean compiled) {
                        assertEquals(0L, service.call(setmask, address(normal), nil())); var changed = normal.clone(); toggle(changed, bit);
                        var calls = List.of(new Invocation(-1L, nil(), true), new Invocation(-1L, nil(), false),
                            new Invocation(block, address(bit), true), new Invocation(block, address(bit), true),
                            new Invocation(1L, address(bit), true), // Selected Linux SIG_UNBLOCK.
                            new Invocation(setmask, address(changed), true), new Invocation(-1L, address(bit), true), new Invocation(setmask, address(normal), true));
                        for (int index = 0; index < calls.size(); index++) {
                            var call = calls.get(index); var current = snapshot(service); var bytes = canaries(); var old = call.output() ? address(bytes).plus(8) : nil();
                            var io = Language.currentState().getStdio(); assertEquals(-1L, io.close(-1)); long sticky = io.errno(); long count = ((Number) program.diagnostics().get("compiledEntries")).longValue();
                            assertEquals(rows.get(index).get(1), Calls.target(entry, new Object[]{0L, call.how(), call.set(), old}));
                            if (compiled) assertTrue(((Number) program.diagnostics().get("compiledEntries")).longValue() > count);
                            assertEquals(index == 6 ? rows.get(index).get(2) : sticky, io.errno()); var expected = canaries();
                            if (index != 6 && call.output()) System.arraycopy(current, 0, expected, 8, 8);
                            assertArrayEquals(expected, bytes, "libc oldset padding and canaries"); var next = normal.clone(); if (after.get(index) == 1L) toggle(next, bit);
                            assertArrayEquals(next, snapshot(service), "all non-SIGTTOU mask bits preserved"); assertEquals(0, language.getHandoffState().get().getArguments().getDepth()); assertEquals(0, language.getHandoffState().get().getResults().getDepth());
                        }
                    } }
                    var runner = new Runner(); runner.exercise(false); var cls = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget");
                    for (var target : targets(entry)) { cls.getMethod("compile", boolean.class).invoke(target, true); assertEquals(true, cls.getMethod("isValidLastTier").invoke(target)); }
                    var runtime = Truffle.getRuntime(); runtime.getClass().getMethod("bypassedInstalledCode", cls).invoke(runtime, entry); runner.exercise(true); assertEquals(0L, program.diagnostics().get("unsupportedTraps"));
                } finally { assertEquals(0L, service.call(setmask, address(baseline), nil())); }
            }); }
        }
    }); }
    @Test public void fabricatedRestoresBadCarriersAndBadProofsRejectBeforeEffects() throws Exception { platform(() -> {
        try (var context = context()) { entered(context, language -> {
            var service = Language.currentState().getSignalMask(); var baseline = snapshot(service); var source = module("pre"); var calls = OriginalStdioChecks.foreignCalls(source);
            if (calls.size() != 1) throw new IllegalArgumentException("Expected one call"); var original = calls.getFirst(); var output = filled(size(), 90); var valid = address(output);
            var other = baseline.clone(); toggle(other, token(10)); // Actual Linux SIGUSR1 delta.
            try {
                for (var backend : List.of("ast", "bytecode")) {
                    var target = load(language, backend, OriginalStdioChecks.rawModule(original, source, null)).entryTarget("entry");
                    class Runner {
                        Object invoke(long how, ManagedAddress set, ManagedAddress old) { return invoke(how, set, old, thc.runtime.Unit.INSTANCE); }
                        Object invoke(long how, ManagedAddress set, ManagedAddress old, Object state) { return Calls.target(target, new Object[]{0L, how, set, old, state}); }
                    }
                    var runner = new Runner();
                    // Existing non-SIGTTOU bits and unmaskable signals are
                    // legitimate no-ops: reject effective changes, not bytes.
                    assertEquals(0L, runner.invoke(constant(OriginalStdioOp.SIG_BLOCK), address(baseline), nil()));
                    for (long signal : new long[]{9L, 19L}) assertEquals(0L, runner.invoke(constant(OriginalStdioOp.SIG_BLOCK), address(token(signal)), nil()));
                    assertEquals(0L, runner.invoke(constant(OriginalStdioOp.SIG_SETMASK), ManagedAddress.fromHex(OriginalStdioChecks.hex(baseline)), nil()));
                    assertThrows(RuntimeFault.class, () -> runner.invoke(0, nil(), valid, 9L)); assertThrows(RuntimeFault.class, () -> runner.invoke(2147483648L, nil(), valid));
                    assertThrows(RuntimeFault.class, () -> runner.invoke(constant(OriginalStdioOp.SIG_SETMASK), address(other), valid));
                    var pointer = ManagedAddress.fromAllocation(PinnedMemory.allocate(size(), 8)); pointer.writeAddressElementIndex(0, valid);
                    for (var bad : List.of(address(new byte[size() - 1]), pointer, ManagedAddress.fromHex("00".repeat(size())))) assertThrows(RuntimeFault.class, () -> runner.invoke(0, nil(), bad));
                    assertThrows(RuntimeFault.class, () -> runner.invoke(0, valid, valid));
                    for (int index = 0; index <= 3; index++) { final int selected = index; assertThrows(RuntimeFault.class, () -> load(language, backend, OriginalStdioChecks.rawModule(original, source, selected))); }
                    assertArrayEquals(filled(size(), 90), output); assertArrayEquals(baseline, snapshot(service));
                }
            } finally { assertEquals(0L, service.call(constant(OriginalStdioOp.SIG_SETMASK), address(baseline), nil())); }
        }); }
    }); }
    private byte[] bytes(ManagedAddress base, int count) { var result = new byte[count + 16]; for (int i = 0; i < result.length; i++) result[i] = (byte) base.readWord8(i); return result; }
    private void seed(ManagedAddress base, int count) { for (int i = 0; i < count + 16; i++) base.writeWord8(i, 90); }
    @Test public void ownedNativeImagesQueryCopyBackAndRejectBeforeMaskEffects() throws Exception { platform(() -> {
        try (var context = context()) { entered(context, _ -> {
            var state = Language.currentState(); var service = state.getSignalMask(); var registry = state.getNativeAllocations(); int count = size();
            var inputBase = registry.malloc(count + 16L); var outputBase = registry.malloc(count + 16L); var input = inputBase.plus(8); var output = outputBase.plus(8); var baseline = snapshot(service);
            try {
                seed(inputBase, count); seed(outputBase, count); var expected = filled(count + 16, 90); assertEquals(-1L, state.getStdio().close(-1)); long sticky = state.getStdio().errno();
                assertEquals(0L, service.call(-1, nil(), address(expected).plus(8))); assertEquals(0L, service.call(-1, nil(), output));
                assertArrayEquals(expected, bytes(outputBase, count), "native query preserves padding and canaries"); assertEquals(sticky, state.getStdio().errno());
                address(baseline).copyNonOverlappingTo(input, count); seed(outputBase, count);
                // Exercise two distinct owned native allocations without
                // changing the mask: restore its exact current image.
                assertEquals(0L, service.call(constant(OriginalStdioOp.SIG_SETMASK), input, output)); assertArrayEquals(expected, bytes(outputBase, count)); assertArrayEquals(baseline, snapshot(service));
                seed(outputBase, count); var before = bytes(outputBase, count); assertThrows(RuntimeFault.class, () -> service.call(0, input, input));
                assertThrows(RuntimeFault.class, () -> service.call(0, nil(), outputBase.plus(17))); var unsupported = baseline.clone(); toggle(unsupported, token(10)); // SIGUSR1.
                address(unsupported).copyNonOverlappingTo(input, count); assertThrows(RuntimeFault.class, () -> service.call(constant(OriginalStdioOp.SIG_SETMASK), input, output));
                assertArrayEquals(before, bytes(outputBase, count), "rejection preserves the entire output image"); assertArrayEquals(baseline, snapshot(service)); assertEquals(sticky, state.getStdio().errno());
            } finally { registry.free(inputBase); registry.free(outputBase); }
        }); }
    }); }
    @Test public void nativeAuthorityPlatformThreadAndContextOwnershipAreMandatory() throws Exception {
        try (var context = Context.create("thc")) { entered(context, _ -> {
            var failure = assertThrows(RuntimeFault.class, () -> Language.currentState().getSignalMask().call(0, nil(), nil())); assertTrue(failure.getMessage().contains("native access"));
        }); }
        try (var context = context()) {
            var bytes = filled(128, 90); var virtual = new FutureTask<Void>(() -> { entered(context, _ -> {
                assertTrue(Thread.currentThread().isVirtual());
                // These invalid regions would fail preflight if it ran. The
                // platform check precedes memory access and native acquisition.
                var failure = assertThrows(RuntimeFault.class, () -> Language.currentState().getSignalMask().call(0, address(new byte[]{1}), address(bytes)));
                assertTrue(failure.getMessage().contains("platform thread")); assertArrayEquals(filled(128, 90), bytes);
            }); return null; });
            Thread.ofVirtual().start(virtual); virtual.get(15, TimeUnit.SECONDS); var service = new ManagedSignalMask[1]; entered(context, _ -> service[0] = Language.currentState().getSignalMask());
            try (var other = context()) { entered(other, _ -> assertThrows(RuntimeFault.class, () -> service[0].call(0, nil(), nil()))); }
        }
    }
}
