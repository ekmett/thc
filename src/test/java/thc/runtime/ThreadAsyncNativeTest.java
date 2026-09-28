// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.frame.VirtualFrame;
import java.io.File;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.junit.jupiter.api.Test;
import thc.CoreModules;
import thc.EntryValue;
import thc.Json;
import thc.Language;
import static org.junit.jupiter.api.Assertions.*;

/** Both sender and target are exported Haskell running through the public parser. */
@SuppressWarnings("unchecked")
public class ThreadAsyncNativeTest {
    private final File root = new File(System.getProperty("thc.projectRoot"));
    @Test public void yieldRequiresOneExactStateToken() {
        var state = new CoreRepresentation(CoreKind.VOID, false, false, List.of()); var wrong = new CoreRepresentation(CoreKind.LONG, false, false, List.of("IntRep"));
        CoreYield.validate(List.of(state), List.of(false), state);
        assertThrows(RuntimeFault.class, () -> CoreYield.validate(List.of(wrong), List.of(false), state));
        assertThrows(RuntimeFault.class, () -> CoreYield.validate(List.of(state), List.of(true), state));
        assertThrows(RuntimeFault.class, () -> CoreYield.validate(List.of(state), List.of(false), wrong));
    }
    @Test public void maskedYieldDefersAnExternalRequestAndUnmaskedYieldCapturesOnce() throws Exception {
        try (var context = Context.newBuilder("thc").allowExperimentalOptions(true).option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw").build()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); var threads = Language.currentState().getThreads(); var effects = new AtomicInteger(); var compiled = new AtomicInteger();
                var stateProof = new CoreRepresentation(CoreKind.VOID, false, false, List.of());
                var root = new GuestRoot(language, new FrameLayout().build()) {
                    @Child private YieldThread yielding = new YieldThread(new Expr() { @Override public Object execute(VirtualFrame frame) { effects.incrementAndGet(); if (CompilerDirectives.inCompiledCode()) compiled.incrementAndGet(); return thc.runtime.Unit.INSTANCE; } }, true, stateProof);
                    @Override public long bloom(VirtualFrame frame) { return 0L; }
                    @Override public Object execute(VirtualFrame frame) { try { return yielding.execute(frame); } catch (AstCapture cut) { CompilerDirectives.transferToInterpreter(); return cut.freeze(this, frame.materialize()); } }
                };
                var target = root.getCallTarget(); long id = threads.enterCurrent();
                try {
                    for (int i = 0; i < 5; i++) assertSame(thc.runtime.Unit.INSTANCE, Calls.target(target, new Object[]{0L}));
                    target.getClass().getMethod("compile", boolean.class).invoke(target, true); assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target));
                    var sent = new AtomicReference<AsyncRequest>(); var sender = new Thread(() -> sent.set(threads.send(id, "external"))); sender.start(); sender.join(5000); assertFalse(sender.isAlive()); var request = sent.get();
                    SynchronousMasking.set(root, MaskingState.MASKED_INTERRUPTIBLE); assertSame(thc.runtime.Unit.INSTANCE, Calls.target(target, new Object[]{0L}));
                    assertEquals(AsyncRequestState.PENDING, request.getState(), "yield# is an ordinary, noninterruptible guest poll");
                    SynchronousMasking.set(root, MaskingState.UNMASKED); var cut = (AstContinuation) Calls.target(target, new Object[]{0L}); assertSame(request, cut.getYielded());
                    assertEquals(AsyncRequestState.CLAIMED, request.getState()); assertTrue(compiled.get() >= 2, "Both masked and unmasked effects entered compiled AST"); assertEquals(7, effects.get(), "The yield effect ran once per entry");
                    request.acknowledge(); assertSame(thc.runtime.Unit.INSTANCE, cut.continueWith(thc.runtime.Unit.INSTANCE)); assertEquals(7, effects.get(), "Resume must not replay the yield effect");
                    assertThrows(RuntimeFault.class, () -> cut.continueWith(thc.runtime.Unit.INSTANCE));
                } finally { SynchronousMasking.set(root, MaskingState.UNMASKED); threads.leaveCurrent(); }
            } finally { context.leave(); }
        }
    }
    private void checkReceipt() throws Exception {
        var receipt = (Map<String, Object>) Json.parse(Files.readString(new File(root, "build/thread-async/manifest.json").toPath()));
        for (var kind : List.of("inputHashes", "artifactHashes")) for (var item : ((Map<String, String>) receipt.get(kind)).entrySet()) {
            var bytes = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(new File(root, item.getKey()).toPath())); assertEquals(item.getValue(), HexFormat.of().formatHex(bytes), "Stale " + item.getKey());
        }
        assertEquals(List.of("43", "44"), Files.readAllLines(new File(root, "build/thread-async/oracle.txt").toPath()));
        assertEquals(List.of("5", "-1", "-1", "-1", "-1"), Files.readAllLines(new File(root, "build/thread-async/extra-oracle.txt").toPath()));
        assertEquals(List.of("220102", "220103", "220102", "220103", "220102", "220103", "110102", "110103"), Files.readAllLines(new File(root, "build/thread-async/saved-oracle.txt").toPath()));
        assertEquals(List.of("22122122", "22122123"), Files.readAllLines(new File(root, "build/thread-async/external-saved-oracle.txt").toPath()));
        assertEquals(List.of("96202106490", "97204107494"), Files.readAllLines(new File(root, "build/thread-async/scheduled-saved-oracle.txt").toPath()));
        assertEquals(List.of("37", "39"), Files.readAllLines(new File(root, "build/thread-async/yield-oracle.txt").toPath()));
        assertEquals(List.of("52", "53"), Files.readAllLines(new File(root, "build/thread-async/lazy-oracle.txt").toPath()));
    }
    private void exercise(String name, List<Long> expected) throws Exception { exercise(name, expected, "ThreadAsyncAudit", "bytecode", true); }
    private void exercise(String name, List<Long> expected, String backend, boolean asyncExceptions) throws Exception { exercise(name, expected, "ThreadAsyncAudit", backend, asyncExceptions); }
    private void exercise(String name, List<Long> expected, String module, String backend, boolean asyncExceptions) throws Exception {
        exercise(name, expected, module, backend, asyncExceptions, false);
    }
    private void exercise(String name, List<Long> expected, String module, String backend, boolean asyncExceptions, boolean cold) throws Exception {
        checkReceipt();
        for (var stage : List.of("pre", "post")) {
            var context = Context.newBuilder("thc").allowExperimentalOptions(true).allowCreateThread(true).option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false").option("engine.Splitting", "false").option("engine.CompilationFailureAction", "Throw").build();
            var executor = Executors.newSingleThreadExecutor(task -> { var thread = new Thread(task, "thc-public-thread-test"); thread.setDaemon(true); return thread; });
            try {
                var core = new File(root, "build/thread-async/" + stage + "/core/" + module + ".json");
                var entry = context.eval("thc", CoreModules.request(List.of(core.getPath()), name, true, false, backend, true, false, null, asyncExceptions));
                var result = executor.submit(() -> {
                    if (cold) {
                        assertEquals(0L, ((Number) ((Map<?, ?>) Json.parse(entry.getMember("diagnostics").asString())).get("compiledEntries")).longValue());
                        assertTrue(entry.invokeMember("compile").asBoolean());
                        var results = new java.util.ArrayList<Long>();
                        for (long token = 0; token < 2; token++) {
                            var before = (Number) ((Map<?, ?>) Json.parse(entry.getMember("diagnostics").asString())).get("compiledEntries");
                            results.add(entry.execute(token).asLong());
                            var diagnostics = (Map<?, ?>) Json.parse(entry.getMember("diagnostics").asString());
                            var after = (Number) diagnostics.get("compiledEntries");
                            assertTrue(after.longValue() > before.longValue(), stage + " " + backend + " " + name + " cold installed call " + token);
                            var installed = (Map<?, ?>) diagnostics.get("explicitCompilation");
                            assertEquals(true, installed.get("sameTargets"), stage + " " + backend + " retained targets " + token);
                            assertEquals(true, installed.get("validLastTier"), stage + " " + backend + " valid installed targets " + token);
                        }
                        return results;
                    }
                    long interpreted = entry.execute(0L).asLong(); assertTrue(entry.invokeMember("compile").asBoolean());
                    var before = (Number) ((Map<?, ?>) Json.parse(entry.getMember("diagnostics").asString())).get("compiledEntries"); long installed = entry.execute(1L).asLong();
                    var after = (Number) ((Map<?, ?>) Json.parse(entry.getMember("diagnostics").asString())).get("compiledEntries"); assertTrue(after.longValue() > before.longValue(), stage + " " + backend + " " + name + " first installed call");
                    return List.of(interpreted, installed);
                });
                assertEquals(expected, result.get(30, TimeUnit.SECONDS), stage + " " + backend + " " + name);
                var diagnostics = (Map<?, ?>) Json.parse(entry.getMember("diagnostics").asString()); assertEquals(0L, ((Number) diagnostics.get("unsupportedTraps")).longValue());
            } finally { context.close(true); executor.shutdownNow(); }
        }
    }
    @Test public void publicForkAndThrowResumeTheSharedThunk() throws Exception { exercise("forkAndThrow", List.of(43L, 44L)); }
    @Test public void astPublicForkAndThrowResumeTheSharedThunk() throws Exception { exercise("forkAndThrow", List.of(43L, 44L), "ast", true); }
    @Test public void uncaughtChildDeliveryReleasesTheSender() throws Exception { exercise("killUncaught", List.of(5L, 6L)); }
    @Test public void astUncaughtChildDeliveryReleasesTheSender() throws Exception { exercise("killUncaught", List.of(5L, 6L), "ast", true); }
    @Test public void selfDirectedThrowEntersTheOriginalHandler() throws Exception { exercise("selfThrow", List.of(-1L, 0L)); }
    @Test public void outerUninterruptibleMaskStillCapturesCalleeThatUnmasksAndSelfThrows() throws Exception { exercise("maskedUnmaskSelf", List.of(-1L, 0L)); }
    @Test public void astSelfDirectedThrowReachesTheOriginalHandlerWithoutPoisoningTheAction() throws Exception { exercise("selfThrow", List.of(-1L, 0L), "ast", true); }
    @Test public void astSelfDirectedThrowBypassesTheOuterUninterruptibleMask() throws Exception { exercise("maskedUnmaskSelf", List.of(-1L, 0L), "ast", true); }
    @Test public void synchronousSelfDeliveryReachesTheOriginalHandler() throws Exception { exercise("selfThrow", List.of(-1L, 0L), "bytecode", false); }
    @Test public void synchronousSelfDeliveryRestoresTheOuterMask() throws Exception { exercise("maskedUnmaskSelf", List.of(-1L, 0L), "bytecode", false); }
    @Test public void astSynchronousSelfDeliveryReachesTheOriginalHandler() throws Exception { exercise("selfThrow", List.of(-1L, 0L), "ast", false); }
    @Test public void astSynchronousSelfDeliveryRestoresTheOuterMask() throws Exception { exercise("maskedUnmaskSelf", List.of(-1L, 0L), "ast", false); }
    @Test public void delimitedSelfDeliveryReachesTheOriginalHandler() throws Exception { exercise("promptSelfThrow", List.of(-1L, 0L), "bytecode", false); }
    @Test public void delimitedSelfDeliveryRestoresTheOuterMask() throws Exception { exercise("promptMaskedUnmaskSelf", List.of(-1L, 0L), "bytecode", false); }
    @Test public void astDelimitedSelfDeliveryReachesTheOriginalHandler() throws Exception { exercise("promptSelfThrow", List.of(-1L, 0L), "ast", false); }
    @Test public void astDelimitedSelfDeliveryRestoresTheOuterMask() throws Exception { exercise("promptMaskedUnmaskSelf", List.of(-1L, 0L), "ast", false); }
    @Test public void savedSelfDeliveryAcknowledgesEachResumption() throws Exception { exercise("savedSelfThrow", List.of(220102L, 220103L), "bytecode", false); }
    @Test public void savedExternalDeliveryOwnsEachInterruptedInvocation() throws Exception { exercise("externalSaved", List.of(22122122L, 22122123L)); }
    @Test public void savedSchedulingOwnsEachInvocation() throws Exception { exercise("scheduledSaved", List.of(96202106490L, 97204107494L)); }
    @Test public void astSavedSchedulingOwnsEachInvocation() throws Exception { exercise("scheduledSaved", List.of(96202106490L, 97204107494L), "ast", true); }
    // The native answer encodes two image invocations, all deep prefix/suffix
    // effects and mask restoration. No interpreter entry prepares these roots.
    @Test public void firstInstalledSavedSchedulingOwnsEachInvocation() throws Exception { exercise("scheduledSaved", List.of(96202106490L, 97204107494L), "ThreadAsyncAudit", "bytecode", true, true); }
    @Test public void astFirstInstalledSavedSchedulingOwnsEachInvocation() throws Exception { exercise("scheduledSaved", List.of(96202106490L, 97204107494L), "ThreadAsyncAudit", "ast", true, true); }
    @Test public void astSavedExternalDeliveryOwnsEachInterruptedInvocation() throws Exception { exercise("externalSaved", List.of(22122122L, 22122123L), "ast", true); }
    @Test public void astSavedSelfDeliveryAcknowledgesEachResumption() throws Exception { exercise("savedSelfThrow", List.of(220102L, 220103L), "ast", false); }
    @Test public void savedSelfDeliveryUnwindsMasks() throws Exception { exercise("savedMaskedSelf", List.of(220102L, 220103L), "bytecode", false); }
    @Test public void astSavedSelfDeliveryUnwindsMasks() throws Exception { exercise("savedMaskedSelf", List.of(220102L, 220103L), "ast", false); }
    @Test public void savedSelfDeliveryFromSuffixReachesSavedCatch() throws Exception { exercise("savedSuffixSelf", List.of(220102L, 220103L), "bytecode", false); }
    @Test public void astSavedSelfDeliveryFromSuffixReachesSavedCatch() throws Exception { exercise("savedSuffixSelf", List.of(220102L, 220103L), "ast", false); }
    @Test public void savedSelfDeliveryRestoresMaskBeforeSavedHandler() throws Exception { exercise("savedMaskCatchSelf", List.of(110102L, 110103L), "bytecode", false); }
    @Test public void astSavedSelfDeliveryRestoresMaskBeforeSavedHandler() throws Exception { exercise("savedMaskCatchSelf", List.of(110102L, 110103L), "ast", false); }
    @Test public void forkedChildOwnsAndResumesTheSharedLazyActionHead() throws Exception { exercise("lazyFork", List.of(52L, 53L), "LazyForkAudit", "bytecode", true); }
    @Test public void astForkedChildOwnsAndResumesTheSharedLazyActionHead() throws Exception { exercise("lazyFork", List.of(52L, 53L), "LazyForkAudit", "ast", true); }
    @Test public void publicRequestValidatesAndSelectsSynchronousOrAsyncExecution() throws Exception {
        checkReceipt(); var core = new File(root, "build/thread-async/post/core/ThreadAsyncAudit.json");
        for (var backend : List.of("ast", "bytecode")) for (var mode : Arrays.asList(null, false, true)) try (var context = Context.newBuilder("thc").allowExperimentalOptions(true)
                .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw").build()) {
            var request = CoreModules.request(List.of(core.getPath()), "yieldProbe", true, false, backend, true, false, null, mode); var entry = context.eval("thc", request);
            assertTrue(entry.invokeMember("compile").asBoolean()); assertEquals(37L, entry.execute(0L).asLong(), backend + "/" + mode + " first installed entry");
            var diagnostics = (Map<?, ?>) Json.parse(entry.getMember("diagnostics").asString()); assertEquals(backend, diagnostics.get("backend")); assertEquals(mode == null ? backend.equals("bytecode") : mode, diagnostics.get("asyncExceptions"));
            var document = (Map<String, Object>) Json.parse(request);
            var failure = assertThrows(PolyglotException.class, () -> { var changed = new LinkedHashMap<>(document); changed.put("asyncExceptions", "true"); context.eval("thc", Json.stringify(changed)); });
            assertTrue(failure.getMessage().contains("asyncExceptions must be a Boolean"));
        }
    }
    private record YieldCase(String name, long base) {}
    @Test public void yieldPreservesStateAndMaskInCompiledAstAndBytecode() throws Exception {
        checkReceipt();
        for (var stage : List.of("pre", "post")) for (var backend : List.of("ast", "bytecode")) {
            var module = CoreModules.merge(List.of((Map<String, Object>) Json.parse(Files.readString(new File(root, "build/thread-async/" + stage + "/core/ThreadAsyncAudit.json").toPath()))));
            for (var row : List.of(new YieldCase("yieldProbe", 37L), new YieldCase("yieldMasked", 39L))) try (var context = Context.newBuilder("thc").allowExperimentalOptions(true)
                    .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw").build()) {
                context.initialize("thc"); context.enter();
                try {
                    var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); var linked = new LinkedHashMap<>(CoreModules.reachable(module, row.name())); linked.put("instrument", true);
                    ExecutableProgram program = backend.equals("ast") ? new Program(language, linked) : new BytecodeProgram(language, linked); var function = context.asValue(new EntryValue(program, row.name(), 1));
                    assertEquals(row.base(), function.execute(0L).asLong(), stage + "/" + backend + "/" + row.name()); var target = program.entryTarget(row.name());
                    target.getClass().getMethod("compile", boolean.class).invoke(target, true); assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target)); assertTrue(function.invokeMember("compile").asBoolean());
                    long before = ((Number) program.diagnostics().get("compiledEntries")).longValue(); assertEquals(row.base() + 1, function.execute(1L).asLong(), stage + "/" + backend + "/" + row.name() + " compiled");
                    assertTrue(((Number) program.diagnostics().get("compiledEntries")).longValue() > before); assertEquals(MaskingState.UNMASKED, SynchronousMasking.current(target.getRootNode()));
                } finally { context.leave(); }
            }
        }
    }
}
