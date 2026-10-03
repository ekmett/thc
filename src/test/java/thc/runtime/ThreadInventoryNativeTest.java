// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.*;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import thc.*;
import java.io.File;
import java.lang.ref.Reference;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static thc.runtime.ThreadInventoryCoreEvidence.*;

// Native observations cover thread membership, callback masks and cancellation.
// Compilation checks observe the first call and interpreter bypass, not Core shape.
@Timeout(180)
@SuppressWarnings("unchecked")
public class ThreadInventoryNativeTest {
    private final File root = new File(System.getProperty("thc.projectRoot"));
    private final File directory = new File(root, "build/thread-inventory");
    private final List<String> entries = List.of("selfInventory", "boundQuery", "snapshotSize", "forkSnapshot", "lazyFork", "forkMasks", "selfKilledStatus", "parkedFork", "callbackObservation");
    private List<RootCallTarget> forkTargets(ExecutableProgram program, Map<String, Object> module) throws ReflectiveOperationException {
        var seen = Collections.newSetFromMap(new IdentityHashMap<RootCallTarget, Boolean>()); var result = new ArrayList<RootCallTarget>();
        // Seed genuine function bindings without forcing CAFs.
        for (var value : (List<?>) module.get("bindings")) { var binding = (Map<?, ?>) value; if (Objects.equals(((List<?>) binding.get("expr")).get(0), "lam")) for (var target : targets(program.entryTarget((String) binding.get("id")))) if (seen.add(target)) result.add(target); } return result;
    }
    private static void joinCompletedChildren(GuestThreads registry) throws InterruptedException {
        for (var value : registry.snapshot()) if (value instanceof GuestThreadId id && id.getForked()) { var carrier = id.getCarrier().get(); if (carrier != null) { carrier.join(5000); assertFalse(carrier.isAlive(), "Completion signal must be followed by actual carrier termination"); } }
    }
    private void provenance() throws Exception {
        var manifest = (Map<?, ?>) Json.parse(Files.readString(new File(directory, "manifest.json").toPath())); assertEquals(1L, manifest.get("schema")); assertEquals("9.14.1", manifest.get("ghc")); assertEquals(entries, manifest.get("entries")); assertEquals(List.of("pre", "post"), manifest.get("stages")); assertEquals("unbound forkIO, threaded RTS -N2", manifest.get("nativeThread"));
        for (var kind : List.of("inputHashes", "artifactHashes")) for (var item : ((Map<?, ?>) manifest.get(kind)).entrySet()) {
            var actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(new File(root, (String) item.getKey()).toPath()))); assertEquals(item.getValue(), actual, "Stale thread inventory fixture: " + item.getKey());
        }
        assertEquals(List.of(10L, 0L, 111L, 1L, 42L, 210L, 17L, 1L), manifest.get("native")); assertEquals(List.of("10", "0", "111", "1", "42", "210", "17", "1"), Files.readAllLines(new File(directory, "oracle.txt").toPath()));
        for (var stage : List.of("pre", "post")) {
            var audit = (Map<?, ?>) Json.parse(Files.readString(new File(directory, stage + "/audit.json").toPath()));
            assertEquals(entries.stream().map(ThreadInventoryNativeTest::entryId).toList(), audit.get("roots"));
            assertEquals(true, audit.get("accepted")); assertEquals(List.of(), audit.get("issues")); assertEquals(List.of(), audit.get("missingGlobals"));
            var names = new HashSet<Object>(); for (var value : (List<?>) audit.get("primitives")) names.add(((Map<?, ?>) value).get("name"));
            assertTrue(names.containsAll(List.of("isCurrentThreadBound#", "listThreads#", "fork#")));
        }
    }
    private Context context() { return Context.newBuilder("thc").allowExperimentalOptions(true).allowCreateThread(true).option("compiler.Inlining", "false").option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false").option("engine.SingleTierCompilationThreshold", "10000000").option("engine.CompilationFailureAction", "Throw").build(); }
    private Map<String, Object> module(String stage) throws Exception { return CoreCbdFixtures.read(new File(directory, stage + "/core/ThreadInventory.cbd").toPath()); }
    private static String entryId(String name) { return "main:ThreadInventory." + name; }
    private static Map<String, Object> instrument(Map<String, Object> module) { var result = new LinkedHashMap<>(module); result.put("instrument", true); return result; }
    private static long count(ExecutableProgram program) { return ((Number) program.diagnostics().get("compiledEntries")).longValue(); }
    private static boolean allValid(List<RootCallTarget> active) throws ReflectiveOperationException { for (var target : active) if (!valid(target)) return false; return true; }
    @Test void genuineCallbacksStartUnmaskedAndBoundWithoutStealingCallerDelivery() throws Exception {
        provenance(); var expectedNative = new ArrayList<String>(); for (var mask : List.of("Unmasked", "MaskedInterruptible", "MaskedUninterruptible")) { expectedNative.add("(" + mask + ",True,True,Unmasked,True,True,True,Unmasked,True,1,8)"); expectedNative.add("(True,True)"); } expectedNative.add("(True,True,True,True)"); assertEquals(expectedNative, Files.readAllLines(new File(directory, "callback-oracle.txt").toPath()));
        for (var stage : List.of("pre", "post")) for (var backend : List.of("ast", "bytecode")) try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var compact = module(stage);
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); var owner = Language.currentState(); var linked = instrument(CoreModules.reachable(compact, entryId("callbackObservation")));
                ExecutableProgram program = backend.equals("ast") ? new Program(language, linked, true) : new BytecodeProgram(language, linked, true); var function = context.asValue(new EntryValue(program, entryId("callbackObservation"), 1)); var active = targets(program.entryTarget(entryId("callbackObservation")));
                // Existing managed reverse entries; native trampoline is an independent oracle.
                for (int i = 0; i < 3; i++) { var foreign = owner.getThreads().enterForeign(ForeignSafety.SAFE); try { assertEquals(1L, function.execute(0L).asLong()); } finally { owner.getThreads().leaveForeign(foreign); } }
                long beforeInstallation = count(program); var interpretedBefore = interpretedCalls(active); install(active); assertTrue(function.invokeMember("compile").asBoolean()); assertEquals(beforeInstallation, count(program)); assertEquals(interpretedBefore, interpretedCalls(active), "Installation executes no guest calls");
                for (var mask : MaskingState.values()) {
                    var registry = owner.getThreads(); registry.enterCurrent(mask, false, true, null); var caller = registry.currentIdentity();
                    try {
                        var queued = new AtomicReference<AsyncRequest>(); var sender = new Thread(() -> queued.set(registry.send(caller, "suspended caller"))); sender.start(); sender.join(5000); assertFalse(sender.isAlive()); var request = queued.get(); var foreign = registry.enterForeign(ForeignSafety.SAFE);
                        try {
                            long before = count(program); assertEquals(8L, function.execute(7L).asLong(), stage + "/" + backend + "/" + mask + " native callback result"); assertTrue(count(program) > before, "First installed callback executes compiled code"); assertTrue(allValid(active)); assertEquals(interpretedBefore, interpretedCalls(active));
                            assertSame(caller, registry.currentIdentity()); assertEquals(mask, owner.getMaskingState().get()); assertEquals(AsyncRequestState.PENDING, request.getState(), "Callback never claims its caller's mailbox"); registry.enterCurrent(); var callback = registry.currentIdentity();
                            try {
                                owner.getMaskingState().set(MaskingState.MASKED_UNINTERRUPTIBLE); var nestedForeign = registry.enterForeign(ForeignSafety.SAFE);
                                try { long nestedBefore = count(program); assertEquals(8L, function.execute(7L).asLong()); assertTrue(count(program) > nestedBefore, "Nested callback executes compiled code"); assertTrue(allValid(active)); assertEquals(interpretedBefore, interpretedCalls(active)); assertSame(callback, registry.currentIdentity()); assertEquals(MaskingState.MASKED_UNINTERRUPTIBLE, owner.getMaskingState().get()); }
                                finally { registry.leaveForeign(nestedForeign); }
                            } finally { registry.leaveCurrent(); }
                        } finally { registry.leaveForeign(foreign); }
                        assertSame(caller, registry.currentIdentity()); owner.getMaskingState().set(MaskingState.UNMASKED); var node = program.entryTarget(entryId("callbackObservation")).getRootNode(); assertSame(request, registry.poll(node)); request.acknowledge(); released(language);
                    } finally { registry.leaveCurrent(); }
                }
            } finally { context.leave(); }
        }
    }
    @Test void nativeSnapshotsAndBoundnessPreserveFirstCompiledEntriesInBothBackends() throws Exception {
        provenance(); record Expected(String entry, long value) {}
        for (var stage : List.of("pre", "post")) for (var backend : List.of("ast", "bytecode")) for (var row : List.of(new Expected("selfInventory", 10L), new Expected("boundQuery", 0L), new Expected("snapshotSize", 1L), new Expected("forkSnapshot", 111L), new Expected("lazyFork", 42L), new Expected("forkMasks", 210L), new Expected("selfKilledStatus", 17L))) {
            var entry = row.entry(); long expected = row.value();
            try (var context = context()) {
                context.initialize("thc"); context.enter();
                try {
                    var compact = module(stage); var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); assertEquals(Boolean.getBoolean(Handoff.HANDOFF_PROPERTY), language.getHandoffLayouts().getEnabled()); System.out.println("THREAD_INVENTORY_HANDOFF=" + language.getHandoffLayouts().getEnabled());
                    var linked = instrument(CoreModules.reachable(compact, entryId(entry))); ExecutableProgram program = backend.equals("ast") ? new Program(language, linked) : new BytecodeProgram(language, linked, true); var function = context.asValue(new EntryValue(program, entryId(entry), 1));
                    var registry = Language.currentState(null).getThreads(); registry.enterCurrent();
                    try {
                        for (int i = 0; i < 3; i++) { assertEquals(expected, function.execute(0L).asLong(), stage + "/" + backend + "/" + entry); joinCompletedChildren(registry); released(language); }
                        var retained = registry.snapshot(); var active = forkTargets(program, linked);
                        long before = count(program); var calls = interpretedCalls(active); install(active); assertTrue(function.invokeMember("compile").asBoolean()); assertEquals(before, count(program), "Installation executes no guest roots"); assertEquals(calls, interpretedCalls(active), "No interpreted settling call");
                        assertEquals(expected + 1L, function.execute(1L).asLong(), stage + "/" + backend + "/" + entry + " first installed observation"); joinCompletedChildren(registry); long delta = count(program) - before; System.out.println("THREAD_INVENTORY_ENTRIES " + stage + "/" + backend + "/" + entry + " delta=" + delta);
                        assertTrue(delta > 0, stage + "/" + backend + "/" + entry + " first installed execution"); assertEquals(calls, interpretedCalls(active), "No guest root silently interpreted");
                        for (var target : active) assertTrue(valid(target), stage + "/" + backend + "/" + entry + " first observation retired " + target.getRootNode().getName()); assertEquals(0L, program.diagnostics().get("unsupportedTraps")); Reference.reachabilityFence(retained);
                    } finally { released(language); registry.leaveCurrent(); }
                } finally { context.leave(); }
            }
        }
    }
    @Test void managedBlockedChildrenReceiveExternalDeliveryAndRemainingChildIsCancelledByContextClose() throws Exception {
        provenance();
        for (var stage : List.of("pre", "post")) for (var backend : List.of("ast", "bytecode")) {
            var context = context(); var carriers = new ArrayList<Thread>(); final GuestThreads registry;
            try {
                context.initialize("thc"); context.enter();
                try {
                    var compact = module(stage); var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); var linked = instrument(CoreModules.reachable(compact, entryId("parkedFork"))); ExecutableProgram program = backend.equals("ast") ? new Program(language, linked) : new BytecodeProgram(language, linked, true);
                    var function = context.asValue(new EntryValue(program, entryId("parkedFork"), 1)); assertEquals(1L, function.execute(0L).asLong()); var active = forkTargets(program, linked); var calls = interpretedCalls(active); install(active); assertTrue(function.invokeMember("compile").asBoolean()); long before = count(program);
                    assertEquals(2L, function.execute(1L).asLong()); assertTrue(count(program) > before, "First installed parked fork executes compiled code"); assertEquals(calls, interpretedCalls(active)); for (var target : active) assertTrue(valid(target)); assertTrue(valid(program.entryTarget(entryId("parkedFork")))); registry = Language.currentState(null).getThreads(); registry.enterCurrent();
                    try {
                        var self = registry.currentIdentity(); var children = new ArrayList<GuestThreadId>(); for (var value : registry.snapshot()) if (value instanceof GuestThreadId child && child != self) children.add(child); assertEquals(2, children.size());
                        for (var child : children) { assertEquals(GuestThreadStatus.MVAR, registry.status(child)); var carrier = Objects.requireNonNull(child.getCarrier().get()); carriers.add(carrier); assertTrue(carrier.isAlive()); assertNotSame(Thread.currentThread(), carrier);
                        }
                        var delivered = children.getFirst(); var waiting = children.getLast(); var payload = new Object();
                        var request = registry.send(delivered, payload);
                        assertEquals(delivered.getLogicalId(), request.getTargetId()); assertSame(payload, request.getPayload());
                        assertSame(self, registry.currentIdentity()); assertNull(registry.poll(program.entryTarget(entryId("parkedFork")).getRootNode(), false), "Sender cannot steal the child's delivery");
                        var carrier = Objects.requireNonNull(delivered.getCarrier().get()); carrier.join(5000);
                        assertFalse(carrier.isAlive(), "External delivery terminates the selected child");
                        assertEquals(AsyncRequestState.ACKNOWLEDGED, request.getState()); assertEquals(GuestThreadStatus.DIED, registry.status(delivered));
                        assertEquals(GuestThreadStatus.MVAR, registry.status(waiting)); assertTrue(Objects.requireNonNull(waiting.getCarrier().get()).isAlive(), "Context close still has a genuinely blocked child to cancel");
                    } finally { registry.leaveCurrent(); }
                    assertEquals(0L, program.diagnostics().get("unsupportedTraps"));
                } finally { context.leave(); }
            } finally { context.close(true); }
            for (var carrier : carriers) { carrier.join(5000); assertFalse(carrier.isAlive(), stage + "/" + backend + " context close must stop its actual managed children"); } assertThrows(RuntimeFault.class, registry::snapshot);
        }
    }
    private static void retained(List<RootCallTarget> active, RootCallTarget entry, Language language) throws ReflectiveOperationException { for (var target : active) assertTrue(valid(target), "Retained " + target.getRootNode().getName()); released(language); }
    @Test void retiredBoundaryNegativeControlDetectsInterpreterEntry() throws Exception {
        provenance();
        for (var stage : List.of("pre", "post")) for (var backendName : List.of("ast", "bytecode")) try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var compact = module(stage);
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); assertEquals(Boolean.getBoolean(Handoff.HANDOFF_PROPERTY), language.getHandoffLayouts().getEnabled()); System.out.println("THREAD_INVENTORY_BOUNDARY_HANDOFF=" + language.getHandoffLayouts().getEnabled());
                var linked = instrument(CoreModules.reachable(compact, entryId("selfInventory"))); ExecutableProgram program = backendName.equals("ast") ? new Program(language, linked) : new BytecodeProgram(language, linked, true); var entry = program.entryTarget(entryId("selfInventory")); var registry = Language.currentState(null).getThreads(); registry.enterCurrent();
                try {
                    for (int i = 0; i < 3; i++) { assertEquals(10L, Calls.target(entry, new Object[]{0L, 0L})); released(language); }
                    var active = targets(entry); assertEquals(1, registry.snapshot().length); var type = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget"); var jvmci = Class.forName("jdk.vm.ci.runtime.JVMCI").getMethod("getRuntime").invoke(null);
                    var backend = Class.forName("jdk.vm.ci.runtime.JVMCIRuntime").getMethod("getHostJVMCIBackend").invoke(jvmci); var meta = Class.forName("jdk.vm.ci.runtime.JVMCIBackend").getMethod("getMetaAccess").invoke(backend);
                    var boundary = Class.forName("jdk.vm.ci.meta.MetaAccessProvider").getMethod("lookupJavaMethod", java.lang.reflect.Executable.class).invoke(meta, type.getDeclaredMethod("callBoundary", Object[].class)); var reprofile = Class.forName("jdk.vm.ci.meta.ResolvedJavaMethod").getMethod("reprofile"); var hasCode = Class.forName("jdk.vm.ci.hotspot.HotSpotResolvedJavaMethod").getMethod("hasCompiledCode"); install(active);
                    try {
                        reprofile.invoke(boundary); assertEquals(false, hasCode.invoke(boundary)); for (var target : active) rawCompile(target); assertEquals(false, hasCode.invoke(boundary), "Raw installation leaves the boundary retired"); var calls = interpretedCalls(active);
                        assertEquals(11L, Calls.target(entry, new Object[]{0L, 1L})); var interpreted = new ArrayList<Integer>(); var after = interpretedCalls(active); for (int i = 0; i < after.size(); i++) interpreted.add(after.get(i) - calls.get(i));
                        // The deliberately retired boundary enters the interpreter.
                        // The same no-interpreter assertion used by the positive
                        // checks must reject it, independent of generated root counts.
                        assertTrue(interpreted.get(active.indexOf(entry)) > 0, "The outer entry deliberately bypasses installed code");
                        assertThrows(AssertionError.class, () -> assertEquals(calls, interpretedCalls(active), "No guest root silently interpreted"));
                        retained(active, entry, language);
                        reprofile.invoke(boundary); assertEquals(false, hasCode.invoke(boundary)); long beforeInstall = count(program); var callsBeforeInstall = interpretedCalls(active); install(active); assertEquals(true, hasCode.invoke(boundary)); assertEquals(beforeInstall, count(program), "Restoration executes no guest code"); assertEquals(callsBeforeInstall, interpretedCalls(active), "No settling call");
                        assertEquals(12L, Calls.target(entry, new Object[]{0L, 2L})); System.out.println("THREAD_INVENTORY_BOUNDARY " + stage + "/" + backendName + " positive=" + (count(program) - beforeInstall)); assertTrue(count(program) > beforeInstall, "Restored first call executes compiled code"); assertEquals(callsBeforeInstall, interpretedCalls(active)); retained(active, entry, language);
                    } finally { restoreBoundary(entry); }
                } finally { released(language); registry.leaveCurrent(); }
            } finally { context.leave(); }
        }
    }
    @Test void bothBackendsObserveRealConcurrentGuestEntriesButNotOtherContexts() throws Exception {
        provenance();
        for (var backend : List.of("ast", "bytecode")) try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var compact = module("pre"); var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); var linked = CoreModules.reachable(compact, List.of(entryId("snapshotSize"), entryId("selfInventory"))); ExecutableProgram program = backend.equals("ast") ? new Program(language, linked) : new BytecodeProgram(language, linked);
                var size = context.asValue(new EntryValue(program, entryId("snapshotSize"), 1)); var self = context.asValue(new EntryValue(program, entryId("selfInventory"), 1)); assertEquals(1L, size.execute(0L).asLong()); var registry = Language.currentState(null).getThreads(); var ready = new CountDownLatch(1); var release = new CountDownLatch(1); var failure = new AtomicReference<Throwable>();
                var worker = new Thread(() -> { context.enter(); registry.enterCurrent(); try { assertEquals(10L, self.execute(0L).asLong()); ready.countDown(); assertTrue(release.await(20, TimeUnit.SECONDS)); } catch (Throwable error) { failure.set(error); ready.countDown(); } finally { registry.leaveCurrent(); context.leave(); } }); worker.start();
                try {
                    assertTrue(ready.await(20, TimeUnit.SECONDS)); if (failure.get() != null) throw new AssertionError("concurrent guest failed", failure.get()); assertEquals(2L, size.execute(0L).asLong()); assertEquals(10L, self.execute(0L).asLong());
                    try (var other = context()) { other.initialize("thc"); other.enter(); try { var isolated = Language.currentState(null).getThreads(); isolated.enterCurrent(); try { assertEquals(1, isolated.snapshot().length); } finally { isolated.leaveCurrent(); } } finally { other.leave(); } }
                } finally { release.countDown(); worker.join(20000); }
                assertFalse(worker.isAlive()); if (failure.get() != null) throw new AssertionError("concurrent guest failed", failure.get());
            } finally { context.leave(); }
        }
    }
}
