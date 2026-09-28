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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.io.IOAccess;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import thc.CoreModules;
import thc.Json;
import thc.Language;
import static org.junit.jupiter.api.Assertions.*;

@EnabledOnOs(OS.LINUX)
@EnabledIfSystemProperty(named = "os.arch", matches = "amd64|x86_64")
@SuppressWarnings("unchecked")
public class OriginalSigsetTest {
    private final File root = new File(System.getProperty("thc.projectRoot")); private final String prefix = "build/original-sigset";
    private final List<String> names = List.of("originalSigEmpty", "originalSigAdd"); private final List<OriginalStdioOp> operations = List.of(OriginalStdioOp.SIGEMPTYSET, OriginalStdioOp.SIGADDSET);
    private Map<String, Object> json(String path) throws Exception { return (Map<String, Object>) Json.parse(Files.readString(new File(root, path).toPath())); }
    private Map<String, Object> module(String stage) throws Exception { var modules = new ArrayList<Map<String, Object>>(); for (var name : List.of("OriginalSigsetAudit", "THC.InterfaceClosure")) modules.add(json(prefix + "/" + stage + "/core/" + name + ".json")); return CoreModules.merge(modules); }
    private Object copy(Object value) { return Json.parse(Json.stringify(value)); }
    private Map<String, Object> document() throws Exception { try (var stream = Objects.requireNonNull(SigsetImage.class.getResourceAsStream("/thc/native/sigset-abi.json"))) { return (Map<String, Object>) Json.parse(new String(stream.readAllBytes(), StandardCharsets.UTF_8)); } }
    private SigsetImage parse(Object value) { return SigsetImage.parse(value, System.getProperty("os.name"), System.getProperty("os.arch")); }
    private Context context() { return Context.newBuilder("thc").allowIO(IOAccess.NONE).allowExperimentalOptions(true).option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw").build(); }
    private void valid(RootCallTarget target) throws Exception { assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target)); }
    private List<RootCallTarget> targets(RootCallTarget entry) { Set<RootCallTarget> seen = Collections.newSetFromMap(new IdentityHashMap<>()); var result = new ArrayList<RootCallTarget>(); visit(entry, seen, result); return result; }
    private void visit(RootCallTarget target, Set<RootCallTarget> seen, List<RootCallTarget> result) {
        if (!seen.add(target)) return; var body = target.getRootNode(); var nodes = new ArrayList<Node>(); nodes.add(body);
        if (body instanceof BytecodeRoot bytecode) for (var instruction : bytecode.getBytecodeNode().getInstructions()) for (var argument : instruction.getArguments())
            if (argument.getKind() == Instruction.Argument.Kind.NODE_PROFILE) { var cached = argument.asCachedNode(); if (cached != null) nodes.add(cached); }
        for (var node : nodes) for (var call : NodeUtil.findAllNodeInstances(node, DirectCallNode.class)) if (call.getCurrentCallTarget() instanceof RootCallTarget callee && callee.getRootNode() instanceof GuestRoot) visit(callee, seen, result); result.add(target);
    }
    private OriginalStdioOp validate(List<Object> call) { var arguments = new ArrayList<Object>(); for (var argument : (List<List<Object>>) call.get(2)) { var metadata = CoreRepresentations.metadata(argument); arguments.add(metadata == null ? null : metadata.get("rep")); } return CoreOriginalStdio.validate(call.get(6), arguments, (List<?>) call.get(3), ((Map<?, ?>) call.get(6)).get("rep")); }
    private byte[] filled(int count, int value) { var bytes = new byte[count]; Arrays.fill(bytes, (byte) value); return bytes; }
    private ExecutableProgram load(Language language, String backend, Map<String, Object> module) { return backend.equals("ast") ? new Program(language, module) : new BytecodeProgram(language, module); }
    @Test public void nativeImagesErrnoAndExactFirstInstalledTargetsMatchBothBackends() throws Exception {
        var manifest = json(prefix + "/manifest.json"); assertEquals(1L, manifest.get("schema")); assertEquals("linux", manifest.get("platform")); assertEquals(true, manifest.get("supported")); assertEquals(names, manifest.get("entries"));
        assertEquals(true, manifest.get("strictAccepted")); assertEquals(false, manifest.get("runtimeVerified")); assertEquals(false, manifest.get("installedArtifactsHashed")); assertEquals(532L, manifest.get("nativeRows"));
        OriginalStdioChecks.hashes(root, manifest.get("inputHashes"), Set.of("t/fixtures/compiler/OriginalSigsetAudit.hs", "t/fixtures/compiler/OriginalSigsetNative.hs", "t/haskell-fixtures/OriginalSigsetFixtures.hs", "bin/audit-core.py", "bin/core_original_foreign.py", "bin/core-capabilities.json"), null);
        var required = new LinkedHashSet<>(List.of(prefix + "/oracle.json", prefix + "/native/oracle"));
        for (var stage : List.of("pre", "post")) { for (var name : names) required.add(prefix + "/" + stage + "/" + name + ".audit.json"); required.add(prefix + "/" + stage + "/core/OriginalSigsetAudit.json"); required.add(prefix + "/" + stage + "/core/THC.InterfaceClosure.json"); }
        OriginalStdioChecks.hashes(root, manifest.get("artifactHashes"), required, prefix + "/");
        var oracle = json(prefix + "/oracle.json"); int size = ((Long) oracle.get("size")).intValue(); var rows = (List<List<Object>>) oracle.get("rows"); assertEquals(532, rows.size());
        var signals = new ArrayList<Long>(); signals.add((long) Integer.MIN_VALUE); signals.add(-1L); for (long signal = 0; signal <= 128; signal++) signals.add(signal); signals.add((long) Integer.MAX_VALUE);
        var cases = new ArrayList<List<Long>>(); for (long fill : new long[]{0L, 90L, 165L, 255L}) { cases.add(List.of(0L, fill, 0L)); for (long signal : signals) cases.add(List.of(1L, fill, signal)); }
        var actualCases = new ArrayList<List<Object>>(); for (var row : rows) actualCases.add(row.subList(0, Math.min(3, row.size()))); assertEquals(cases, actualCases, "missing, duplicate or reordered native cases");
        var doc = document(); var layout = (Map<?, ?>) doc.get("sigset"); assertEquals(oracle.get("size"), layout.get("size"));
        assertEquals(OriginalStdioChecks.hex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(new File(root, "src/main/c/sigset-abi-probe.c").toPath()))), doc.get("sourceSha256"));
        var bits = (List<Long>) layout.get("signalBits");
        for (int signal = 1; signal <= bits.size(); signal++) {
            List<Object> selected = null; for (var row : rows) if (Long.valueOf(1).equals(row.get(0)) && Long.valueOf(0).equals(row.get(1)) && Long.valueOf(signal).equals(row.get(2))) { if (selected != null) throw new IllegalArgumentException("Multiple rows"); selected = row; }
            if (selected == null) throw new java.util.NoSuchElementException(); assertEquals(bits.get(signal - 1) < 0 ? -1L : 0L, selected.get(3));
        }
        for (var stage : List.of("pre", "post")) for (var backend : List.of("ast", "bytecode")) try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); var stdio = Language.currentState(null).getStdio();
                for (int index = 0; index < names.size(); index++) {
                    final int selectedIndex = index; var name = names.get(index); var audit = json(prefix + "/" + stage + "/" + name + ".audit.json"); assertEquals(true, audit.get("accepted")); assertEquals(List.of(), audit.get("issues")); assertEquals(List.of(), audit.get("missingGlobals"));
                    var symbols = new ArrayList<>(); for (var call : (List<Map<?, ?>>) audit.get("foreignCalls")) symbols.add(call.get("symbol")); assertEquals(List.of(operations.get(index).getSymbol()), symbols);
                    var linked = new LinkedHashMap<>(CoreModules.reachable(module(stage), name)); linked.put("instrument", true); var bindings = (List<Map<String, Object>>) linked.get("bindings"); if (bindings.size() != 1) throw new IllegalArgumentException("Expected one binding"); var binding = bindings.getFirst();
                    var foreignCalls = OriginalStdioChecks.foreignCalls(binding.get("expr")); if (foreignCalls.size() != 1) throw new IllegalArgumentException("Expected one call"); assertEquals(operations.get(index), validate(foreignCalls.getFirst()));
                    int lambdas = 0; for (var node : OriginalStdioChecks.nodes(binding.get("expr"))) if (!node.isEmpty() && "lam".equals(node.getFirst())) lambdas++; assertEquals(2, lambdas);
                    var program = load(language, backend, linked); var entry = program.entryTarget(name);
                    class Runner {
                        List<RootCallTarget> retained = List.of();
                        void exercise(boolean compiled) throws Exception {
                            for (var row : rows) if (Long.valueOf(selectedIndex).equals(row.get(0))) {
                                var bytes = filled(size + 16, 77); Arrays.fill(bytes, 8, 8 + size, ((Long) row.get(1)).byteValue()); var address = ManagedAddress.fromByteArray(bytes).plus(8);
                                var args = selectedIndex == 0 ? new Object[]{0L, address} : new Object[]{0L, address, row.get(2)}; stdio.captureForeignErrno(0);
                                for (int repeat = 0; repeat < 2; repeat++) {
                                    long before = ((Number) program.diagnostics().get("compiledEntries")).longValue(); assertEquals(row.get(3), Calls.target(entry, args), stage + "/" + backend + "/" + name + "/" + row.subList(0, Math.min(5, row.size()))); assertEquals(row.get(4), stdio.errno());
                                    var expected = (List<Long>) row.get(5); var expectedBytes = new byte[expected.size()]; for (int i = 0; i < expectedBytes.length; i++) expectedBytes[i] = expected.get(i).byteValue(); assertArrayEquals(expectedBytes, bytes);
                                    if (compiled) { assertEquals(before + 2, ((Number) program.diagnostics().get("compiledEntries")).longValue()); assertEquals(retained, targets(entry)); for (var target : retained) valid(target); }
                                    var handoff = language.getHandoffState().get(); assertEquals(0, handoff.getArguments().getDepth()); assertEquals(0, handoff.getResults().getDepth()); assertEquals(0, handoff.getArguments().retainedReferences()); assertEquals(0, handoff.getResults().retainedReferences());
                                }
                            }
                        }
                    }
                    var runner = new Runner(); runner.exercise(false); runner.retained = targets(entry); assertEquals(2, runner.retained.size()); for (var target : runner.retained) { target.getClass().getMethod("compile", boolean.class).invoke(target, true); valid(target); } runner.exercise(true);
                }
            } finally { context.leave(); }
        }
    }
    @Test public void strictOriginalProofsAndMemoryPreflightsRejectBeforeImageOrErrnoEffects() throws Exception {
        var source = module("pre"); var calls = OriginalStdioChecks.foreignCalls(source); var observed = new LinkedHashSet<OriginalStdioOp>(); for (var call : calls) observed.add(Objects.requireNonNull(validate(call))); assertEquals(new LinkedHashSet<>(operations), observed);
        for (var backend : List.of("ast", "bytecode")) try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); var stdio = Language.currentState(null).getStdio(); var abi = parse(document());
                for (var call : calls) {
                    var operation = Objects.requireNonNull(validate(call)); var raw = OriginalStdioChecks.rawModule(call, source, null); var target = load(language, backend, raw).entryTarget("entry");
                    var bytes = filled((int) abi.getSize() + 16, 90); var address = ManagedAddress.fromByteArray(bytes).plus(8);
                    class Runner {
                        Object invoke(ManagedAddress pointer) { return invoke(pointer, 1L, thc.runtime.Unit.INSTANCE); }
                        Object invoke(ManagedAddress pointer, long signal) { return invoke(pointer, signal, thc.runtime.Unit.INSTANCE); }
                        Object invoke(ManagedAddress pointer, long signal, Object state) { return Calls.target(target, operation == OriginalStdioOp.SIGADDSET ? new Object[]{0L, pointer, signal, state} : new Object[]{0L, pointer, state}); }
                    }
                    var runner = new Runner(); stdio.captureForeignErrno(123); assertThrows(RuntimeFault.class, () -> runner.invoke(address, 1L, 9L));
                    for (int index = 0; index < operation.getArguments().size(); index++) { final int selected = index; assertThrows(RuntimeFault.class, () -> load(language, backend, OriginalStdioChecks.rawModule(call, source, selected))); }
                    for (var bad : List.of(ManagedAddress.nullAddress(), address.plus(abi.getSize()), ManagedAddress.fromByteArray(new byte[(int) abi.getSize() - 1]), ManagedAddress.fromHex("00".repeat((int) abi.getSize())))) assertThrows(RuntimeFault.class, () -> runner.invoke(bad));
                    var allocation = PinnedMemory.allocate(abi.getSize(), 8); var pinned = ManagedAddress.fromAllocation(allocation); pinned.writeAddressElementIndex(0, address); assertThrows(RuntimeFault.class, () -> runner.invoke(pinned)); assertSame(address, pinned.readAddressElementIndex(0));
                    var writablePinned = ManagedAddress.fromAllocation(PinnedMemory.allocate(abi.getSize(), 8)); for (long i = 0; i < abi.getSize(); i++) writablePinned.writeWord8(i, 90);
                    if (operation == OriginalStdioOp.SIGADDSET) for (long bad : new long[]{1L << 32, (long) Integer.MIN_VALUE - 1, Long.MIN_VALUE, Long.MAX_VALUE}) assertThrows(RuntimeFault.class, () -> runner.invoke(address, bad));
                    assertEquals(123L, stdio.errno()); for (byte value : bytes) assertTrue(value == (byte) 90); assertEquals(0L, runner.invoke(address)); assertEquals(123L, stdio.errno()); // success preserves sticky errno
                    assertEquals(0L, runner.invoke(writablePinned)); assertEquals(123L, stdio.errno()); for (long i = 0; i < abi.getSize(); i++) assertEquals(address.readWord8(i), writablePinned.readWord8(i));
                    if (operation == OriginalStdioOp.SIGADDSET) { var saved = bytes.clone(); assertEquals(-1L, runner.invoke(address, 0)); assertEquals(((Map<?, ?>) document().get("sigset")).get("invalidErrno"), stdio.errno()); assertArrayEquals(saved, bytes); long error = stdio.errno(); assertEquals(0L, runner.invoke(address, 1)); assertEquals(error, stdio.errno()); }
                    for (var wrong : List.of("IntRep", "Word32Rep")) {
                        var forged = (List<Object>) copy(call); var meta = (Map<String, Object>) forged.get(6); var descriptor = (Map<String, Object>) meta.get("foreignCall");
                        for (var record : List.of(meta.get("rep"), descriptor.get("resultRep"))) { var tuple = (Map<String, Object>) record; tuple.put("primReps", List.of(wrong)); ((List<Map<String, Object>>) tuple.get("components")).get(1).put("primReps", List.of(wrong)); }
                        assertThrows(RuntimeFault.class, () -> validate(forged));
                    }
                    var malformed = (Map<String, Object>) copy(raw); var malformedCalls = OriginalStdioChecks.foreignCalls(malformed); if (malformedCalls.size() != 1) throw new IllegalArgumentException("Expected one call"); ((List<Object>) malformedCalls.getFirst().get(1)).set(1, 17L); assertThrows(RuntimeFault.class, () -> load(language, backend, malformed));
                }
            } finally { context.leave(); }
        }
        try (var second = context()) { second.initialize("thc"); second.enter(); try { assertEquals(0L, Language.currentState(null).getStdio().errno()); } finally { second.leave(); } }
    }
    private record Mutation(String key, Object value) {}
    private Map<String, Object> plus(Map<String, ?> source, String key, Object value) { var result = new LinkedHashMap<String, Object>(source); result.put(key, value); return result; }
    @Test public void closedNativeImageTableRejectsWrongHostMissingAndMalformedFields() throws Exception {
        var doc = document(); parse(doc);
        for (var item : List.of(new Mutation("schema", true), new Mutation("schema", 1.0), new Mutation("system", "Darwin"), new Mutation("architecture", "aarch64"), new Mutation("target", "x86_64-unknown-linux-gnux32"), new Mutation("extra", 0L))) assertThrows(RuntimeFault.class, () -> parse(plus(doc, item.key(), item.value())));
        var layout = (Map<String, Object>) doc.get("sigset");
        for (var key : layout.keySet()) {
            assertThrows(RuntimeFault.class, () -> { var missing = new LinkedHashMap<>(layout); missing.remove(key); parse(plus(doc, "sigset", missing)); });
            for (var bad : Arrays.asList(null, true, 1.0, "1")) assertThrows(RuntimeFault.class, () -> parse(plus(doc, "sigset", plus(layout, key, bad))));
        }
        var tooMany = new ArrayList<Long>(); for (long i = 0; i < 129; i++) tooMany.add(i);
        for (var item : List.of(new Mutation("size", 0L), new Mutation("size", 4097L), new Mutation("alignment", 4L), new Mutation("invalidErrno", 0L),
                new Mutation("clearBytes", List.of(0L, 0L)), new Mutation("clearBytes", List.of(1L, 0L)), new Mutation("clearBytes", List.of(-1L)), new Mutation("clearBytes", List.of(4096L)), new Mutation("clearBytes", List.of()),
                new Mutation("signalBits", List.of(-1L)), new Mutation("signalBits", List.of(-2L)), new Mutation("signalBits", List.of(0L, 0L)), new Mutation("signalBits", List.of(32768L)), new Mutation("signalBits", tooMany), new Mutation("signalBits", List.of())))
            assertThrows(RuntimeFault.class, () -> parse(plus(doc, "sigset", plus(layout, item.key(), item.value()))));
        var image = plus(plus(layout, "clearBytes", List.of(0L)), "signalBits", List.of(8L)); assertThrows(RuntimeFault.class, () -> parse(plus(doc, "sigset", image)));
    }
}
