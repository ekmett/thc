// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import java.io.File;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import thc.CoreModules;
import thc.EntryValue;
import thc.Json;
import thc.Language;
import static org.junit.jupiter.api.Assertions.*;

/** Real Core, a running Java target, and ordinary catch#: no manually armed checkpoints. */
@SuppressWarnings("unchecked")
public class LiveAsyncNativeTest {
    private final File root = new File(System.getProperty("thc.projectRoot"));
    private final List<String> entries = List.of("forceShared", "takeReady", "takeRunning", "releaseGate", "prefixCount", "warmLoop", "asyncPayload");
    private Map<String, Object> fixture(String stage) throws Exception {
        var receipt = (Map<String, Object>) Json.parse(Files.readString(new File(root, "build/live-async/manifest.json").toPath()));
        for (var kind : List.of("inputHashes", "artifactHashes")) for (var item : ((Map<String, String>) receipt.get(kind)).entrySet()) {
            var bytes = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(new File(root, item.getKey()).toPath()));
            assertEquals(item.getValue(), HexFormat.of().formatHex(bytes), "Stale " + item.getKey());
        }
        assertEquals(List.of("1007", "-1", "10000008", "1", "1031"), Files.readAllLines(new File(root, "build/live-async/oracle.txt").toPath()));
        for (var entry : entries) {
            var audit = (Map<String, Object>) Json.parse(Files.readString(new File(root, "build/live-async/" + stage + "/" + entry + "-audit.json").toPath()));
            assertEquals(true, audit.get("accepted"), entry); assertEquals(List.of(), audit.get("missingGlobals"), entry);
        }
        var module = thc.CoreCbdFixtures.read(new File(root, "build/live-async/" + stage + "/core/LiveAsyncAudit.cbd").toPath());
        var bindings = new LinkedHashMap<Object, Map<String, Object>>();
        for (var entry : entries) for (var binding : (List<Map<String, Object>>) CoreModules.reachable(module, "main:LiveAsyncAudit." + entry).get("bindings")) bindings.putIfAbsent(binding.get("id"), binding);
        var result = new LinkedHashMap<>(module); result.put("bindings", new ArrayList<>(bindings.values())); result.put("instrument", true); return result;
    }
    private void compile(RootCallTarget target) throws Exception {
        target.getClass().getMethod("compile", boolean.class).invoke(target, true);
        assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target));
    }
    @FunctionalInterface private interface Action<T> { T run() throws Exception; }
    private <T> T entered(Context context, Action<T> action) throws Exception {
        context.enter(); try { return action.run(); } finally { context.leave(); }
    }
    private void awaitBoundary(Thread thread, CompletableFuture<Long> result, String method) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (result.isDone()) fail("Target returned before " + method + ": " + result.get());
            if (thread.getState() == Thread.State.WAITING) for (var frame : thread.getStackTrace()) if (frame.getMethodName().equals(method)) return;
            Thread.sleep(1);
        }
        fail("Target did not reach " + method + ": " + Arrays.asList(thread.getStackTrace()));
    }
    private record Target(Thread thread, CompletableFuture<Long> answer) {}
    private void exercise(String backend, String stage, boolean running, boolean ownerWait, boolean repeat) throws Exception {
        var module = fixture(stage);
        try (var context = Context.newBuilder("thc").allowExperimentalOptions(true)
                .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false").option("engine.Splitting", "false")
                .option("engine.CompilationFailureAction", "Throw").build()) {
            context.initialize("thc");
            class Session {
                ExecutableProgram program; Language.State state; final Map<String, Value> functions = new LinkedHashMap<>();
                final List<Thread> targets = new ArrayList<>();
                long call(String name) { return call(name, 0L); }
                long call(String name, long argument) { return functions.get(name).execute(argument).asLong(); }
                Target startTarget() {
                    var answer = new CompletableFuture<Long>();
                    var thread = new Thread(() -> {
                        try { answer.complete(call("forceShared")); }
                        catch (Throwable failure) { answer.completeExceptionally(failure); }
                    }, "thc-" + backend + "-" + stage + "-async-target");
                    thread.setDaemon(true); targets.add(thread); thread.start(); return new Target(thread, answer);
                }
            }
            var session = new Session();
            entered(context, () -> {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); session.state = Language.currentState();
                // All entry values must share one program and its CAFs. Separate
                // public parser loads intentionally create independent programs;
                // ThreadAsyncNativeTest exercises that loader on both backends.
                session.program = backend.equals("ast") ? new Program(language, module, true) : new BytecodeProgram(language, module, true);
                assertEquals(backend, session.program.diagnostics().get("backend"));
                for (var entry : entries) if (!entry.equals("asyncPayload")) session.functions.put(entry, context.asValue(new EntryValue(session.program, "main:LiveAsyncAudit." + entry, 1)));
                return null;
            });
            var program = session.program; var state = session.state;
            var shared = (Thunk) program.entryValue("main:LiveAsyncAudit." + "shared"); var loop = program.entryTarget("main:LiveAsyncAudit." + "longLoop");
            // Warm every loop branch without entering the shared CAF. Drain the
            // two signals so the next run synchronizes with the actual target.
            assertEquals(1L, session.call("releaseGate")); assertEquals(9999007L, session.call("warmLoop", 9999000L));
            assertEquals(7L, session.call("takeReady")); assertEquals(7L, session.call("takeRunning"));
            assertEquals(0, shared.getState()); assertEquals(0L, session.call("prefixCount"));
            entered(context, () -> { compile(loop); return null; });
            if (running) assertEquals(1L, session.call("releaseGate"));
            var first = session.startTarget(); var target = first.thread(); var result = first.answer();
            try {
                assertEquals(1007L, CompletableFuture.supplyAsync(() -> session.call("takeReady")).get(10, TimeUnit.SECONDS));
                if (running) assertEquals(1007L, CompletableFuture.supplyAsync(() -> session.call("takeRunning")).get(10, TimeUnit.SECONDS));
                assertFalse(result.isDone(), "Interruption must target the live computation"); assertSame(loop, program.entryTarget("main:LiveAsyncAudit." + "longLoop"));
                assertEquals(true, loop.getClass().getMethod("isValidLastTier").invoke(loop), "The actual loop remains compiled before delivery");
                var delivery = ownerWait ? session.startTarget() : first;
                if (ownerWait) awaitBoundary(delivery.thread(), delivery.answer(), "awaitOwner");
                var victim = delivery.thread(); var answer = delivery.answer();
                var request = state.getThreads().send(state.getThreads().pollState(victim).getCurrent().getIdentity(), program.entryValue("main:LiveAsyncAudit." + "asyncPayload"));
                assertEquals(-1L, answer.get(15, TimeUnit.SECONDS), "Original catch# must handle delivery");
                victim.join(5000); assertFalse(victim.isAlive()); assertEquals(AsyncRequestState.ACKNOWLEDGED, request.getState());
                if (running) assertTrue(request.compiledCapture, "The executing compiled loop must claim the exception");
                assertSame(loop, program.entryTarget("main:LiveAsyncAudit." + "longLoop"));
                assertEquals(ownerWait ? 1 : 5, shared.getState(), "An interrupted waiter must not change the other thread's ownership");
                assertEquals(1L, session.call("prefixCount"));
                if (repeat) {
                    var retry = session.startTarget(); awaitBoundary(retry.thread(), retry.answer(), "await");
                    var second = state.getThreads().send(state.getThreads().pollState(retry.thread()).getCurrent().getIdentity(), program.entryValue("main:LiveAsyncAudit." + "asyncPayload"));
                    assertEquals(-1L, retry.answer().get(15, TimeUnit.SECONDS)); retry.thread().join(5000); assertFalse(retry.thread().isAlive());
                    assertEquals(AsyncRequestState.ACKNOWLEDGED, second.getState()); assertEquals(5, shared.getState()); assertEquals(1L, session.call("prefixCount"));
                }
                if (!running) assertEquals(1L, session.call("releaseGate"));
                if (ownerWait) { assertEquals(10000007L, result.get(15, TimeUnit.SECONDS)); target.join(5000); assertFalse(target.isAlive()); }
                // This call uses a different Java thread from the original owner.
                assertEquals(10000008L, session.call("forceShared", 1)); assertEquals(2, shared.getState());
                assertEquals(1L, session.call("prefixCount"), "Resumption must not replay the effectful prefix");
                assertSame(loop, program.entryTarget("main:LiveAsyncAudit." + "longLoop"));
                assertNull(shared.getTarget()); assertNull(shared.getEnvironment());
                assertEquals(0L, ((Number) program.diagnostics().get("unsupportedTraps")).longValue());
            } finally {
                // Context cancellation is only a failed-test escape hatch; it is
                // not how asynchronous guest delivery is implemented or tested.
                for (var thread : session.targets) if (thread.isAlive()) { context.close(true); break; }
            }
        }
    }
    @ParameterizedTest @ValueSource(strings = {"bytecode", "ast"})
    public void blockedMVarPreservesSharedThunk(String backend) throws Exception { for (var stage : List.of("pre", "post")) exercise(backend, stage, false, false, false); }
    @ParameterizedTest @ValueSource(strings = {"bytecode", "ast"})
    public void compiledLoopPreservesSharedThunk(String backend) throws Exception { for (var stage : List.of("pre", "post")) exercise(backend, stage, true, false, false); }
    @ParameterizedTest @ValueSource(strings = {"bytecode", "ast"})
    public void interruptedWaiterLeavesTheOtherThunkOwnerIntact(String backend) throws Exception { for (var stage : List.of("pre", "post")) exercise(backend, stage, false, true, false); }
    @ParameterizedTest @ValueSource(strings = {"bytecode", "ast"})
    public void resumedThunkCanBeInterruptedAgain(String backend) throws Exception { for (var stage : List.of("pre", "post")) exercise(backend, stage, false, false, true); }
}
