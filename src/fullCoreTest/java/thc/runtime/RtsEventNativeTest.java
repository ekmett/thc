// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.NodeUtil;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.Test;
import thc.*;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings("unchecked")
public class RtsEventNativeTest {
    private final File root = new File(System.getProperty("thc.projectRoot"));
    private final File directory = new File(root, "build/rts-event");
    private Map<String, Object> json(File file) throws Exception { return (Map<String, Object>) Json.parse(Files.readString(file.toPath(), StandardCharsets.UTF_8)); }
    private Context context() { return Context.newBuilder("thc").allowExperimentalOptions(true).option("engine.BackgroundCompilation", "false")
        .option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw").build(); }
    private ExecutableProgram program(Language language, String backend, String entry, String stage) throws Exception {
        var module = CoreModules.merge(List.of(json(new File(directory, entry + "-" + stage + ".json")))); return backend.equals("ast") ? new Program(language, module, true) : new BytecodeProgram(language, module);
    }
    private List<List<?>> calls(Object value) {
        var result = new ArrayList<List<?>>();
        if (value instanceof Map<?, ?> map) for (var child : map.values()) result.addAll(calls(child));
        else if (value instanceof List<?> list) {
            if (!list.isEmpty() && "app".equals(list.getFirst()) && list.getLast() instanceof Map<?, ?> metadata && metadata.get("foreignCall") instanceof Map<?, ?>) result.add(list);
            for (var child : list) result.addAll(calls(child));
        }
        return result;
    }
    private Map<String, Object> with(Map<String, Object> source, String key, Object value) { var result = new LinkedHashMap<>(source); result.put(key, value); return result; }
    private List<Object> operands(List<?> call) {
        var result = new ArrayList<Object>(); for (var operand : (List<List<?>>) call.get(2)) {
            var metadata = CoreRepresentations.metadata(operand); result.add(metadata == null ? null : metadata.get("rep"));
        }
        return result;
    }
    private Map<String, String> entries(Object value) { var result = new LinkedHashMap<String, String>(); for (var pair : (List<List<String>>) value) result.put(pair.getFirst(), pair.get(1)); return result; }
    private Map<String, List<List<Object>>> rows(Object value) { var result = new LinkedHashMap<String, List<List<Object>>>(); for (var row : (List<List<Object>>) value) result.computeIfAbsent((String) row.getFirst(), ignored -> new ArrayList<>()).add(row); return result; }
    private int count(Map<String, List<List<Object>>> rows) { int count = 0; for (var group : rows.values()) count += group.size(); return count; }
    private void released(Language language) {
        var state = language.getHandoffState().get(); assertEquals(0, state.getResults().getDepth()); assertEquals(0, state.getResults().retainedReferences());
        assertEquals(0, state.getArguments().getDepth()); assertEquals(0, state.getArguments().retainedReferences());
    }
    private void valid(List<RootCallTarget> installed, String label) throws Exception {
        for (var target : installed) assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target), label + "/" + target.getRootNode().getName() + " remains installed");
    }
    private void invokeDescriptor(List<Object> row, String entry, Value callable, Language language, String label) {
        long input = ((Number) row.get(1)).longValue(), expected = ((Number) row.get(2)).longValue();
        long model = switch (entry) { case "eventfdCycle" -> 2 * input + 5; case "pipeCycle" -> input + 13;
            case "epollCycle", "epollSafeCycle" -> input + 31; case "pollCycle", "pollSafeCycle" -> input + 17; default -> input; };
        assertEquals(model, expected); assertEquals(expected, callable.execute(input).asLong(), label + "/" + input); released(language);
    }
    @Test public void originalPipeAndEventfdLifecyclesMatchNativeAtFirstCompiledEntry() throws Exception {
        var manifest = json(new File(directory, "manifest.json")); var entries = entries(manifest.get("descriptorEntries")); var rows = rows(json(new File(directory, "oracle.json")).get("descriptorRows"));
        assertEquals(Set.of("eventfdCycle", "pipeCycle", "epollCycle", "epollSafeCycle", "pollCycle", "pollSafeCycle", "controlCycle"), entries.keySet()); assertEquals(entries.keySet(), rows.keySet()); assertEquals(21, count(rows));
        for (String stage : List.of("pre", "post")) for (var group : rows.entrySet()) for (String backend : List.of("ast", "bytecode"))
            try (var context = NativeFileProvider.createContext(Set.of(), ContextProfile.SYNCHRONOUS_TEST, FfiMode.NATIVE, false)) {
                String entry = group.getKey(), label = stage + "/" + backend + "/" + entry; var cases = group.getValue(); context.enter();
                try {
                    assertEquals(true, json(new File(directory, entry + "-" + stage + ".audit.json")).get("accepted")); var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                    var module = json(new File(directory, entry + "-" + stage + ".json"));
                    for (var call : calls(module)) {
                        var meta = (Map<String, Object>) call.getLast(); var descriptor = (Map<String, Object>) meta.get("foreignCall"); var target = (Map<String, Object>) descriptor.get("target"); var operands = operands(call);
                        assertNotNull(CoreOriginalStdio.validate(meta, operands, (List<?>) call.get(3), meta.get("rep")));
                        assertThrows(RuntimeFault.class, () -> CoreOriginalStdio.validate(with(meta, "foreignCall", with(descriptor, "target", with(target, "unit", "main"))), operands, (List<?>) call.get(3), meta.get("rep")));
                        if (Objects.requireNonNull(CoreOriginalStdio.validate(meta, operands, (List<?>) call.get(3), meta.get("rep"))).getEventManager()) {
                            for (var bad : List.of(with(descriptor, "arity", 0L), with(descriptor, "safety", "interruptible"), with(descriptor, "target", with(target, "isFunction", false)),
                                with(descriptor, "resultRep", Map.of("kind", "long", "primReps", List.of("IntRep")))))
                                assertThrows(RuntimeFault.class, () -> CoreOriginalStdio.validate(with(meta, "foreignCall", bad), operands, (List<?>) call.get(3), meta.get("rep")));
                            var wrong = new ArrayList<>(operands); wrong.set(0, with((Map<String, Object>) operands.getFirst(), "primReps", List.of("IntRep")));
                            assertThrows(RuntimeFault.class, () -> CoreOriginalStdio.validate(meta, wrong, (List<?>) call.get(3), meta.get("rep")));
                        }
                    }
                    var program = program(language, backend, entry, stage); var callable = context.asValue(new EntryValue(program, entries.get(entry), 1));
                    for (var row : cases) invokeDescriptor(row, entry, callable, language, label); assertTrue(callable.invokeMember("compile").asBoolean());
                    var host = program.hostEntryTarget(1); var original = program.entryTarget(entries.get(entry)); var installed = new ArrayList<RootCallTarget>();
                    // Public compilation follows the active split, not merely
                    // the closure's original call-target identity.
                    for (var call : NodeUtil.findAllNodeInstances(host.getRootNode(), DirectCallNode.class)) if (call.getCallTarget() == original) installed.add((RootCallTarget) call.getCurrentCallTarget());
                    if (installed.isEmpty()) installed.add(original); installed.add(host); valid(installed, label);
                    for (var row : cases) {
                        long before = ((Number) program.diagnostics().get("compiledEntries")).longValue(); invokeDescriptor(row, entry, callable, language, label);
                        assertEquals(before + 1, ((Number) program.diagnostics().get("compiledEntries")).longValue(), label + " must enter its installed root immediately"); valid(installed, label);
                    }
                } finally { context.leave(); }
            }
    }
    @Test public void astSafeCompletionPublishesOnceAndHonorsEveryLogicalMask() throws Exception {
        for (var mask : MaskingState.values()) try (Context context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); var program = program(language, "ast", "capabilities", "post");
                var root = (GuestRoot) program.entryTarget("originalCapabilities").getRootNode(); var leaves = NodeUtil.findAllNodeInstances(root, RtsEventForeignExpression.class); assertEquals(1, leaves.size()); var leaf = leaves.getFirst(); int[] evaluations = {0};
                // Isolate this genuine lowered safe-return cut from entry polls.
                // Replacing its input lets us observe evaluation/replay separately.
                ((Expr) leaf.getChildren().iterator().next()).replace(new Expr() { @Override public Object execute(VirtualFrame frame) { evaluations[0]++; return 3; } });
                var threads = Language.currentState().getThreads(); threads.enterCurrent(null, false, true, null);
                try {
                    SynchronousMasking.set(root, mask); var self = threads.currentIdentity(); var incoming = CompletableFuture.supplyAsync(() -> threads.send(self, "after setter")).get(5, TimeUnit.SECONDS);
                    var frame = Truffle.getRuntime().createVirtualFrame(new Object[0], root.getFrameDescriptor());
                    if (mask == MaskingState.UNMASKED) {
                        var cut = assertThrows(AstCapture.class, () -> leaf.executeTuple(frame, new int[0], 0)); assertSame(incoming, cut.getYielded()); assertEquals(3L, threads.capabilityCount());
                        incoming.acknowledge(); var saved = cut.freeze(root, frame.materialize()); threads.setCapabilityCount(5);
                        assertNull(saved.continueWith(kotlin.Unit.INSTANCE)); assertEquals(5L, threads.capabilityCount(), "Resumption must not replay the completed setter");
                        assertThrows(RuntimeFault.class, () -> saved.continueWith(kotlin.Unit.INSTANCE));
                    } else {
                        assertNull(leaf.executeTuple(frame, new int[0], 0)); assertEquals(3L, threads.capabilityCount()); assertEquals(AsyncRequestState.PENDING, incoming.getState());
                        SynchronousMasking.set(root, MaskingState.UNMASKED); assertSame(incoming, threads.poll(root, false)); incoming.acknowledge();
                    }
                    assertEquals(1, evaluations[0]); assertEquals(AsyncRequestState.ACKNOWLEDGED, incoming.getState());
                } finally { SynchronousMasking.set(root, MaskingState.UNMASKED); threads.leaveCurrent(GuestThreadStatus.FINISHED); }
            } finally { context.leave(); }
        }
    }
    private Object validate(Map<String, Object> metadata, List<Object> operands, List<?> flags) {
        Object result = CoreRtsEventForeign.validate(metadata, operands, flags, metadata.get("rep"));
        if (result == null) result = CoreOriginalStdio.validate(metadata, operands, flags, metadata.get("rep"));
        if (result == null) result = CoreSharedCAFStores.validate(metadata, operands, flags, metadata.get("rep")); return result;
    }
    @Test public void originalInstalledDeclarationsRetainExactAbiAndUnresolvedIdentity() throws Exception {
        var entries = (List<List<String>>) json(new File(directory, "manifest.json")).get("entries"); var seen = new LinkedHashSet<String>();
        for (var pair : entries) for (String stage : List.of("pre", "post")) {
            var actual = calls(json(new File(directory, pair.getFirst() + "-" + stage + ".json"))); assertFalse(actual.isEmpty());
            for (var call : actual) {
                var metadata = (Map<String, Object>) call.getLast(); var descriptor = (Map<String, Object>) metadata.get("foreignCall"); var target = (Map<String, Object>) descriptor.get("target");
                String symbol = (String) target.get("symbol"); seen.add(symbol); var operands = operands(call); assertNotNull(validate(metadata, operands, (List<?>) call.get(3)), symbol);
                for (var bad : List.of(with(descriptor, "target", with(target, "unit", "main")), with(descriptor, "target", with(target, "isFunction", false)),
                    with(descriptor, "safety", "safe".equals(descriptor.get("safety")) ? "unsafe" : "safe"), with(descriptor, "arity", 99L), with(descriptor, "suppliedArity", 0L),
                    with(descriptor, "resultRep", Map.of("kind", "long", "primReps", List.of("IntRep"), "evaluated", false))))
                    assertThrows(RuntimeFault.class, () -> validate(with(metadata, "foreignCall", bad), operands, (List<?>) call.get(3)));
                boolean rts = false; for (var operation : RtsEventForeignOp.values()) if (operation.getSymbol().equals(symbol)) rts = true;
                if (rts) { CoreRtsEventForeign.validateHead((List<?>) call.get(1), false); assertThrows(RuntimeFault.class, () -> CoreRtsEventForeign.validateHead((List<?>) call.get(1), true)); }
            }
        }
        assertEquals(8, seen.size());
    }
    private void invokeEvent(List<Object> row, String entry, long physicalCount, Value callable, GuestThreads threads, Language language, String label) {
        long input = ((Number) row.get(1)).longValue(), answer = ((Number) row.get(2)).longValue(); assertTrue(answer > 0);
        // JVM quotas may differ from the native GHC processor count.
        long expected = entry.equals("processors") ? physicalCount : answer; assertEquals(expected, callable.execute(input).asLong(), label);
        if (entry.equals("capabilities")) {
            assertEquals(input, threads.capabilityCount()); var address = CoreDataLabels.fromCore("enabled_capabilities", new CoreRepresentation(CoreKind.ADDRESS, true, true, List.of("AddrRep"), null, null, null, null, null));
            assertEquals(input, Integer.toUnsignedLong(ManagedAddressRead.WORD32.readInt(address, 0))); assertEquals(physicalCount, (long) threads.getCpuAffinity().getCount());
        }
        released(language);
    }
    @Test public void originalEventLeavesMatchTheirNativeContractFromFirstCompiledInvocation() throws Exception {
        var manifest = json(new File(directory, "manifest.json"));
        for (String group : List.of("inputHashes", "artifactHashes", "interfaceHashes")) for (var hash : ((Map<String, String>) manifest.get(group)).entrySet()) {
            File file = new File(hash.getKey()); if (!file.isAbsolute()) file = new File(root, hash.getKey());
            assertEquals(hash.getValue(), HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file.toPath()))), "Stale RTS event fixture " + hash.getKey());
        }
        var entries = entries(manifest.get("entries")); var rows = rows(json(new File(directory, "oracle.json")).get("rows")); assertEquals(8, rows.size()); assertEquals(24, count(rows));
        for (String stage : List.of("pre", "post")) for (var group : rows.entrySet()) for (String backend : List.of("ast", "bytecode")) try (Context context = context()) {
            String entry = group.getKey(), label = stage + "/" + backend + "/" + entry; var cases = group.getValue(); context.initialize("thc"); context.enter();
            try {
                assertEquals(true, json(new File(directory, entry + "-" + stage + ".audit.json")).get("accepted")); var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var threads = Language.currentState().getThreads(); long physicalCount = threads.getCpuAffinity().getCount(); var program = program(language, backend, entry, stage); String name = entries.get(entry);
                var callable = context.asValue(new EntryValue(program, name, 1)); for (var row : cases) invokeEvent(row, entry, physicalCount, callable, threads, language, label);
                assertTrue(callable.invokeMember("compile").asBoolean()); var target = program.entryTarget(name);
                for (var row : cases.reversed()) {
                    long before = (Long) program.diagnostics().get("compiledEntries"); invokeEvent(row, entry, physicalCount, callable, threads, language, label);
                    assertEquals(before + 1, program.diagnostics().get("compiledEntries"), label + " exact entry"); assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target));
                }
                if (entry.equals("capabilities")) {
                    long previous = threads.capabilityCount(); assertThrows(PolyglotException.class, () -> callable.execute(0L)); assertEquals(previous, threads.capabilityCount()); released(language);
                }
            } finally { context.leave(); }
        }
    }
}
