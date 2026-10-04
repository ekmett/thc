// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.bytecode.ContinuationResult;
import java.io.File;
import java.lang.ref.Reference;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import thc.CoreModules;
import thc.EntryValue;
import thc.Json;
import thc.Language;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(45)
@SuppressWarnings("unchecked")
public class ThreadSchedulingTest {
    private final File root = new File(System.getProperty("thc.projectRoot")); private final File directory = new File(root, "build/thread-scheduling");
    private final List<String> entries = List.of("emptySpark", "lazyPar", "lazySpark", "sparkValue", "currentCounter", "negativeCounter", "pinnedFork", "otherCounter", "timedDelay");
    private Context context() { return Context.newBuilder("thc").allowExperimentalOptions(true).allowCreateThread(true).allowNativeAccess(true)
        .option("compiler.Inlining", "false").option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
        .option("engine.SingleTierCompilationThreshold", "10000000").option("engine.CompilationFailureAction", "Throw").build(); }
    private boolean valid(RootCallTarget target) throws Exception { return Boolean.TRUE.equals(target.getClass().getMethod("isValidLastTier").invoke(target)); }
    private void compile(RootCallTarget target) throws Exception { target.getClass().getMethod("compile", boolean.class).invoke(target, true); assertTrue(valid(target)); }
    private Map<String, Object> delayModule() {
        var integer = Map.of("kind", "long", "primReps", List.of("IntRep"), "evaluated", true); var state = Map.of("kind", "void", "primReps", List.of(), "evaluated", true);
        var closure = Map.of("kind", "closure", "primReps", List.of("BoxedRep (Just Lifted)"), "evaluated", true);
        var parameters = List.of(Map.of("id", "duration", "lifted", false, "rep", integer), Map.of("id", "s", "lifted", false, "rep", state));
        var call = List.of("app", List.of("prim", "delay#"), List.of(List.of("var", "duration", Map.of("rep", integer)), List.of("var", "s", Map.of("rep", state))), List.of(false, false), false, false, Map.of("rep", state));
        return Map.of("schema", 1, "ghc", "9.14.1", "module", "Test.Delay", "constructors", List.of(), "instrument", true,
            "bindings", List.of(Map.of("id", "wait", "name", "wait", "arity", 2, "lifted", true, "rep", closure, "expr", List.of("lam", parameters, call, Map.of("rep", closure, "resultRep", state)))));
    }
    private void provenance() throws Exception {
        var manifest = (Map<String, Object>) Json.parse(Files.readString(new File(directory, "manifest.json").toPath())); assertEquals(entries, manifest.get("entries")); assertEquals(List.of("pre", "post"), manifest.get("stages"));
        assertEquals(List.of(1L, 1L, 1L, 1L, 1L, 1L, 11L, 11L, 2000L), manifest.get("native")); assertEquals(List.of("1", "1", "1", "1", "1", "1", "11", "11", "2000"), Files.readAllLines(new File(directory, "oracle.txt").toPath()));
        var hashes = new LinkedHashMap<>((Map<String, String>) manifest.get("inputHashes")); hashes.putAll((Map<String, String>) manifest.get("artifactHashes"));
        for (var item : hashes.entrySet()) { var hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(new File(root, item.getKey()).toPath()))); assertEquals(item.getValue(), hash, "Stale scheduling fixture: " + item.getKey()); }
        var operations = new LinkedHashSet<String>();
        for (var stage : List.of("pre", "post")) for (var entry : entries) {
            var audit = (Map<String, Object>) Json.parse(Files.readString(new File(directory, stage + "/" + entry + "-audit.json").toPath()));
            assertEquals(true, audit.get("accepted")); assertEquals(List.of(), audit.get("issues")); assertEquals(List.of(), audit.get("missingGlobals"));
            for (var primitive : (List<Map<String, Object>>) audit.get("primitives")) operations.add((String) primitive.get("name"));
        }
        assertTrue(operations.containsAll(List.of("par#", "spark#", "getSpark#", "numSparks#", "forkOn#", "delay#", "setThreadAllocationCounter#", "setOtherThreadAllocationCounter#")));
    }
    @Test public void nativeExamplesAndFirstCompiledStraightLineEntriesAgreeOnBothBackends() throws Exception {
        provenance();
        for (var stage : List.of("pre", "post")) for (var backend : List.of("ast", "bytecode")) for (var entry : entries) try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); var module = (Map<String, Object>) thc.CoreCbdFixtures.read(new File(directory, stage + "/core/ThreadScheduling.cbd").toPath());
                var linked = new LinkedHashMap<>(CoreModules.reachable(module, "main:ThreadScheduling." + entry)); linked.put("instrument", true);
                ExecutableProgram program = backend.equals("ast") ? new Program(language, linked) : new BytecodeProgram(language, linked, Set.of("pinnedFork", "otherCounter").contains(entry));
                var function = context.asValue(new EntryValue(program, "main:ThreadScheduling." + entry, 1)); long argument = switch (entry) { case "currentCounter" -> 4096L; case "timedDelay" -> 2000L; default -> 0L; };
                long expected = switch (entry) { case "pinnedFork", "otherCounter" -> 11L; case "timedDelay" -> 2000L; default -> 1L; };
                var threads = Language.currentState().getThreads(); threads.enterCurrent();
                try {
                    assertEquals(expected, function.execute(argument).asLong(), stage + "/" + backend + "/" + entry);
                    if (!Set.of("pinnedFork", "otherCounter").contains(entry) && stage.equals("pre")) {
                        var target = program.entryTarget("main:ThreadScheduling." + entry); compile(target); long before = ((Number) program.diagnostics().get("compiledEntries")).longValue();
                        assertEquals(expected, function.execute(argument).asLong(), backend + "/" + entry + " first installed call"); assertTrue(((Number) program.diagnostics().get("compiledEntries")).longValue() > before); assertTrue(valid(target), backend + "/" + entry + " retained its first installation");
                    }
                    for (var value : threads.snapshot()) if (value instanceof GuestThreadId identity && identity.getForked()) {
                        var carrier = identity.getCarrier().get(); if (carrier != null) { carrier.join(5000); assertFalse(carrier.isAlive()); }
                        assertEquals(GuestThreadStatus.FINISHED, threads.status(identity)); if (threads.getCpuAffinity().getMode() == CpuAffinityMode.PINNED) assertTrue(identity.getAffinityApplied(), "Native forkOn request was accepted");
                    }
                    assertEquals(0, language.getHandoffState().get().getArguments().getDepth()); assertEquals(0, language.getHandoffState().get().getResults().getDepth());
                } finally { threads.leaveCurrent(); }
            } finally { context.leave(); }
        }
    }
    private GuestThreads threads() { return new GuestThreads(ThreadLocal.withInitial(() -> MaskingState.UNMASKED), _ -> {}); }
    /** Shared native receipt, invoked by the Loom suite without unsupported counters. */
    void nativePinnedForkOnBothBackends() throws Exception {
        provenance();
        for (var stage : List.of("pre", "post")) for (var backend : List.of("ast", "bytecode")) try (var context = context()) {
            var core = new File(directory, stage + "/core/ThreadScheduling.cbd");
            var entry = context.eval("thc", CoreModules.request(List.of(core.getPath()), "main:ThreadScheduling.pinnedFork", true, false, backend, true, false, null, true));
            assertEquals(11L, entry.execute(0L).asLong(), stage + "/" + backend + " native forkOn");
            assertTrue(entry.invokeMember("compile").asBoolean());
            long before = ((Number) ((Map<?, ?>) Json.parse(entry.getMember("diagnostics").asString())).get("compiledEntries")).longValue();
            assertEquals(11L, entry.execute(0L).asLong(), stage + "/" + backend + " first installed forkOn");
            assertTrue(((Number) ((Map<?, ?>) Json.parse(entry.getMember("diagnostics").asString())).get("compiledEntries")).longValue() > before);
        }
    }
    @Test public void realAllocationCountersResetAndExcludeOutsideEntryWork() {
        var threads = threads(); var foreign = threads(); threads.enterCurrent(); var identity = threads.currentIdentity();
        try {
            assertTrue(threads.allocationCounter() <= 0L); threads.setAllocationCounter(10_000_000L); long before = threads.allocationCounter(); var bytes = new byte[128 * 1024]; long after = threads.allocationCounter();
            assertTrue(before - after >= bytes.length); Reference.reachabilityFence(bytes); threads.setAllocationCounter(-17L); assertTrue(threads.allocationCounter() <= -17L); foreign.enterCurrent();
            try { assertThrows(RuntimeFault.class, () -> threads.setAllocationCounter(123L, foreign.currentIdentity())); } finally { foreign.leaveCurrent(); foreign.close(); }
        } finally { threads.leaveCurrent(); }
        long saved = identity.getAllocationRemaining(); var outside = new byte[4 * 1024 * 1024]; threads.enterCurrent();
        try { assertTrue(threads.allocationCounter() > saved - 1024 * 1024, "Host allocation outside an entry is not charged"); Reference.reachabilityFence(outside); } finally { threads.leaveCurrent(); threads.close(); }
    }
    @Test public void anotherLiveThreadReceivesItsOwnCounterAndLogicalCapability() throws Exception {
        var threads = threads(); var ready = new CountDownLatch(1); var release = new CountDownLatch(1); var target = new AtomicReference<GuestThreadId>(); var remaining = new AtomicReference<Long>(); var failure = new AtomicReference<Throwable>(); threads.enterCurrent();
        var child = new Thread(() -> {
            threads.enterCurrent(null, true, true, 7L);
            try { target.set(threads.currentIdentity()); ready.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)); remaining.set(threads.allocationCounter()); }
            catch (Throwable error) { failure.set(error); } finally { threads.leaveCurrent(); }
        });
        try {
            child.start(); assertTrue(ready.await(5, TimeUnit.SECONDS)); assertTrue(target.get().getCapabilityLocked()); assertEquals(7L % threads.capabilityCount(), target.get().getCapability());
            threads.setAllocationCounter(1_000_000L, target.get()); release.countDown(); child.join(5000); assertFalse(child.isAlive());
            if (failure.get() != null) throw new AssertionError("counter child failed", failure.get()); assertTrue(remaining.get() >= 900_000L && remaining.get() <= 1_000_000L); assertTrue(threads.allocationCounter() <= 0L, "The parent's counter was not reset");
        } finally { release.countDown(); child.join(5000); threads.leaveCurrent(); threads.close(); }
    }
    @Test public void unavailableAccountingCannotBreakGuestThreadCleanup() {
        var bean = (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean(); var threads = threads(); threads.enterCurrent(); var identity = threads.currentIdentity(); boolean entered = true;
        try {
            bean.setThreadAllocatedMemoryEnabled(false); assertThrows(RuntimeFault.class, threads::allocationCounter); assertDoesNotThrow(() -> { threads.leaveCurrent(); }); entered = false;
            assertThrows(RuntimeFault.class, threads::currentIdentity); assertTrue(identity.getAllocationUnavailable());
        } finally { bean.setThreadAllocatedMemoryEnabled(true); if (entered) { threads.leaveCurrent(); threads.close(); } }
        threads.enterCurrent();
        try { assertThrows(RuntimeFault.class, threads::allocationCounter); threads.setAllocationCounter(1_000_000L); long counter = threads.allocationCounter(); assertTrue(counter >= 900_000L && counter <= 1_000_000L); }
        finally { threads.leaveCurrent(); threads.close(); }
    }
    @Test public void nonpositiveAndTimedDelaysKeepFirstInstalledEntries() throws Exception {
        for (var backend : List.of("ast", "bytecode")) try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); ExecutableProgram program = backend.equals("ast") ? new Program(language, delayModule()) : new BytecodeProgram(language, delayModule()); var target = program.entryTarget("wait");
                for (long duration : new long[]{Long.MIN_VALUE, -1L, 0L, 2000L}) assertSame(thc.runtime.Unit.INSTANCE, Calls.target(target, new Object[]{0L, duration, thc.runtime.Unit.INSTANCE}));
                compile(target); long before = ((Number) program.diagnostics().get("compiledEntries")).longValue(); long start = System.nanoTime(); assertSame(thc.runtime.Unit.INSTANCE, Calls.target(target, new Object[]{0L, 10_000L, thc.runtime.Unit.INSTANCE}));
                assertTrue(System.nanoTime() - start >= 10_000_000L); assertTrue(((Number) program.diagnostics().get("compiledEntries")).longValue() > before); assertTrue(valid(target));
            } finally { context.leave(); }
        }
    }
    @Test public void compiledDelayCapturesOnceAndResumesItsOriginalDeadline() throws Exception {
        var context = context(); var worker = Executors.newSingleThreadExecutor();
        try {
            context.initialize("thc"); context.enter(); final Language.State state; final BytecodeProgram program; final RootCallTarget target;
            try { state = Language.currentState(); var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); program = new BytecodeProgram(language, delayModule(), true); target = program.entryTarget("wait"); assertSame(thc.runtime.Unit.INSTANCE, Calls.target(target, new Object[]{0L, 0L, thc.runtime.Unit.INSTANCE})); compile(target); }
            finally { context.leave(); }
            var identity = new AtomicReference<GuestThreadId>();
            class Runner {
                Future<Object> start(long duration, MaskingState mask) { return worker.submit(() -> {
                    context.enter(); state.getThreads().enterCurrent(mask);
                    try {
                        identity.set(state.getThreads().currentIdentity()); var answer = Calls.target(target, new Object[]{0L, duration, thc.runtime.Unit.INSTANCE});
                        if (answer instanceof ContinuationResult continuation) { var request = AsyncContinuations.request(continuation); if (request != null) request.acknowledge(); }
                        else { state.getMaskingState().set(MaskingState.UNMASKED); var request = state.getThreads().poll(target.getRootNode(), true); if (request != null) request.acknowledge(); }
                        return answer;
                    } finally { state.getThreads().leaveCurrent(); context.leave(); }
                }); }
                void awaitBlocked(Future<?> future) throws Exception {
                    long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                    while ((identity.get() == null || identity.get().getStatus() != GuestThreadStatus.DELAY) && !future.isDone() && System.nanoTime() < until) Thread.sleep(1);
                    if (future.isDone()) future.get(); assertEquals(GuestThreadStatus.DELAY, identity.get() == null ? null : identity.get().getStatus());
                }
            }
            var runner = new Runner(); long before = ((Number) program.diagnostics().get("compiledEntries")).longValue(); var first = runner.start(300_000L, MaskingState.MASKED_INTERRUPTIBLE); runner.awaitBlocked(first);
            var request = state.getThreads().send(identity.get(), "wake delay"); var saved = (ContinuationResult) first.get(5, TimeUnit.SECONDS);
            assertSame(request, AsyncContinuations.request(saved)); assertTrue(request.compiledCapture); assertEquals(AsyncRequestState.ACKNOWLEDGED, request.getState()); assertEquals(before + 1, ((Number) program.diagnostics().get("compiledEntries")).longValue());
            Thread.sleep(400); context.enter();
            try { long resumed = System.nanoTime(); assertSame(thc.runtime.Unit.INSTANCE, saved.continueWith(thc.runtime.Unit.INSTANCE)); assertTrue(System.nanoTime() - resumed < 250_000_000L, "Resume must not restart the original duration"); }
            finally { context.leave(); }
            identity.set(null); var huge = runner.start(Long.MAX_VALUE, MaskingState.UNMASKED); runner.awaitBlocked(huge); // Saturation must not turn a huge positive delay into an immediate return.
            var hugeRequest = state.getThreads().send(identity.get(), "cancel huge delay"); assertSame(hugeRequest, AsyncContinuations.request((ContinuationResult) huge.get(5, TimeUnit.SECONDS)));
            identity.set(null); long maskedStart = System.nanoTime(); var masked = runner.start(150_000L, MaskingState.MASKED_UNINTERRUPTIBLE); runner.awaitBlocked(masked);
            var pending = state.getThreads().send(identity.get(), "masked delay"); assertEquals(AsyncRequestState.PENDING, pending.getState()); assertSame(thc.runtime.Unit.INSTANCE, masked.get(5, TimeUnit.SECONDS));
            assertTrue(System.nanoTime() - maskedStart >= 150_000_000L); assertEquals(AsyncRequestState.ACKNOWLEDGED, pending.getState());
        } finally { context.close(true); worker.shutdownNow(); assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS)); }
    }
}
