// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleLanguage;
import java.io.File;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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

/** Real GHC PAP and catch#, with a valid CBV contract on its unused strict formal. */
@SuppressWarnings("unchecked")
public class AsyncStrictEntryNativeTest {
    private final File root = new File(System.getProperty("thc.projectRoot"));
    private final List<String> entries = List.of("strictEntry", "strictCall", "strictWorker", "takeReady", "takeRunning", "releaseGate",
        "forceShared", "prefixCount", "warmLoop", "asyncPayload");
    private Map<String, Object> fixture(String stage) throws Exception {
        var receipt = (Map<String, Object>) Json.parse(Files.readString(new File(root, "build/live-async/manifest.json").toPath()));
        for (var kind : List.of("inputHashes", "artifactHashes")) for (var item : ((Map<String, String>) receipt.get(kind)).entrySet()) {
            var bytes = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(new File(root, item.getKey()).toPath()));
            assertEquals(item.getValue(), HexFormat.of().formatHex(bytes), "Stale " + item.getKey());
        }
        assertEquals(List.of("1007", "-1", "10000008", "1", "1031"), Files.readAllLines(new File(root, "build/live-async/strict-oracle.txt").toPath()));
        for (var entry : entries) {
            var audit = (Map<String, Object>) Json.parse(Files.readString(new File(root, "build/live-async/" + stage + "/" + entry + "-audit.json").toPath()));
            assertEquals(true, audit.get("accepted"), entry); assertEquals(List.of(), audit.get("missingGlobals"), entry);
        }
        var module = (Map<String, Object>) Json.parse(Files.readString(new File(root, "build/live-async/" + stage + "/core/LiveAsyncAudit.json").toPath()));
        var byId = new LinkedHashMap<Object, Map<String, Object>>();
        for (var entry : entries) for (var binding : (List<Map<String, Object>>) CoreModules.reachable(module, entry).get("bindings"))
            byId.putIfAbsent(binding.get("id"), binding);
        var reachable = new ArrayList<>(byId.values());
        Map<String, Object> worker = null;
        for (var binding : reachable) if ("strictWorker".equals(binding.get("name"))) {
            if (worker != null) throw new IllegalArgumentException("Collection contains more than one matching element.");
            worker = binding;
        }
        if (worker == null) throw new java.util.NoSuchElementException("Collection contains no element matching the predicate.");
        var expression = (List<Object>) worker.get("expr");
        var strictCase = (List<Object>) expression.get(2);
        assertEquals("case", strictCase.get(0));
        var alternatives = (List<List<Object>>) strictCase.get(3);
        if (alternatives.size() != 1) throw new IllegalArgumentException("List has more than one element.");
        var value = alternatives.getFirst().get(3);
        assertEquals("var", ((List<?>) value).get(0));
        assertEquals(List.of(false, false), worker.get("entryStrict"));
        // Original bang-pattern case proves this formal strict. Supply the same
        // CBV contract and remove only that now-redundant case, preserving the PAP.
        var contract = List.of(true, false);
        var annotated = new ArrayList<>(expression); annotated.set(2, value);
        var metadata = new LinkedHashMap<>((Map<String, Object>) expression.get(3)); metadata.put("entryStrict", contract); annotated.set(3, metadata);
        var bindings = new ArrayList<Map<String, Object>>();
        for (var binding : reachable) {
            if (binding == worker) {
                var changed = new LinkedHashMap<>(binding); changed.put("expr", annotated); changed.put("entryStrict", contract); bindings.add(changed);
            } else bindings.add(binding);
        }
        var result = new LinkedHashMap<>(module); result.put("bindings", bindings); result.put("instrument", true); return result;
    }
    @ParameterizedTest @ValueSource(strings = {"bytecode", "ast"})
    public void blockedDynamicPapDemandsUnusedStrictFormalInsideOriginalCatch(String backend) throws Exception {
        exercise(backend, false);
    }
    @ParameterizedTest @ValueSource(strings = {"bytecode", "ast"})
    public void firstCompiledDynamicPapPreservesItsStrictEntryAndWorkerAcrossDelivery(String backend) throws Exception {
        exercise(backend, true);
    }
    private void exercise(String backend, boolean compiled) throws Exception {
        for (var stage : List.of("pre", "post")) {
            var module = fixture(stage);
            try (var context = Context.newBuilder("thc").allowExperimentalOptions(true)
                .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
                .option("engine.Splitting", "false").option("engine.CompilationFailureAction", "Throw").build()) {
                context.initialize("thc");
                ExecutableProgram program; Language.State state;
                var functions = new LinkedHashMap<String, Value>();
                context.enter();
                try {
                    var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                    state = Language.currentState();
                    // One graph for the dynamic PAP and synchronizing public entries.
                    program = backend.equals("ast") ? new Program(language, module, true) : new BytecodeProgram(language, module, true);
                    assertEquals(backend, program.diagnostics().get("backend"));
                    assertEquals(true, ((GuestRoot) program.entryTarget("strictWorker").getRootNode()).getEntryStrict()[0]);
                    for (var entry : entries) if (Set.of("strictEntry", "takeReady", "takeRunning", "releaseGate", "forceShared", "prefixCount", "warmLoop").contains(entry))
                        functions.put(entry, context.asValue(new EntryValue(program, entry, 1)));
                } finally { context.leave(); }
                var shared = (Thunk) program.entryValue("shared");
                assertEquals(1L, call(functions, "releaseGate"));
                assertEquals(9999007L, call(functions, "warmLoop", 9999000L));
                assertEquals(7L, call(functions, "takeReady")); assertEquals(7L, call(functions, "takeRunning"));
                assertEquals(0, shared.getState());
                var installed = new LinkedHashMap<String, RootCallTarget>();
                if (compiled) {
                    context.enter();
                    try {
                        for (var name : List.of("strictWorker", "strictEntry", "longLoop")) {
                            var entry = program.entryTarget(name); installed.put(name, entry);
                            assertEquals(true, entry.getClass().getMethod("compile", boolean.class).invoke(entry, true));
                            assertEquals(true, entry.getClass().getMethod("isValidLastTier").invoke(entry), name);
                            var runtime = Truffle.getRuntime();
                            runtime.getClass().getMethod("bypassedInstalledCode", Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget"))
                                    .invoke(runtime, entry);
                        }
                    } finally { context.leave(); }
                    // Delivery in the original running loop records an actual compiled claim.
                    // Neither strictEntry nor strictWorker has executed before installation.
                    assertEquals(1L, call(functions, "releaseGate"));
                }
                long compiledBefore = ((Number) program.diagnostics().get("compiledEntries")).longValue();
                var result = new CompletableFuture<Long>();
                var target = new Thread(() -> {
                    try { result.complete(call(functions, "strictEntry")); } catch (Throwable failure) { result.completeExceptionally(failure); }
                }, "thc-" + backend + "-" + stage + "-strict-entry-target");
                target.setDaemon(true); target.start();
                var ready = new CompletableFuture<Long>();
                var waiter = new Thread(() -> {
                    try { ready.complete(call(functions, "takeReady")); } catch (Throwable failure) { ready.completeExceptionally(failure); }
                }, "thc-" + backend + "-" + stage + "-strict-entry-ready");
                waiter.setDaemon(true); waiter.start();
                try {
                    CompletableFuture.anyOf(result, ready).get(15, TimeUnit.SECONDS);
                    assertTrue(ready.isDone(), "The dynamic worker returned before demanding its strict PAP prefix");
                    assertEquals(1007L, ready.get(1, TimeUnit.SECONDS));
                    assertFalse(result.isDone(), "The PAP prefix must be demanded before the worker returns");
                    if (compiled) {
                        assertEquals(1007L, CompletableFuture.supplyAsync(() -> call(functions, "takeRunning")).get(10, TimeUnit.SECONDS));
                        assertTargetIdentity(program, installed, "before delivery");
                    }
                    var request = state.getThreads().send(Objects.requireNonNull(state.getThreads().pollState(target).getCurrent()).getIdentity(), program.entryValue("asyncPayload"));
                    assertEquals(-1L, result.get(15, TimeUnit.SECONDS), "The original catch# handles delivery");
                    assertEquals(AsyncRequestState.ACKNOWLEDGED, request.getState());
                    if (compiled) {
                        assertTrue(request.compiledCapture, "The original installed loop claims the strict-PAP delivery");
                        assertTrue(((Number) program.diagnostics().get("compiledEntries")).longValue() >= compiledBefore + 3,
                                "The strict entry, strict worker and loop must enter installed code");
                        assertTargetIdentity(program, installed, "after delivery");
                    }
                    assertEquals(5, shared.getState()); assertEquals(1L, call(functions, "prefixCount"));
                    if (!compiled) assertEquals(1L, call(functions, "releaseGate"));
                    assertEquals(10000008L, call(functions, "forceShared", 1));
                    assertEquals(2, shared.getState());
                    assertEquals(1L, call(functions, "prefixCount"), "Resumption must not replay the effectful prefix");
                    if (compiled) assertTargetIdentity(program, installed, "after saved completion");
                } finally {
                    target.join(5000);
                    if (target.isAlive() || waiter.isAlive()) context.close(true);
                }
            }
        }
    }
    private void assertTargetIdentity(ExecutableProgram program, Map<String, RootCallTarget> installed, String phase) throws Exception {
        for (var entry : installed.entrySet()) {
            assertSame(entry.getValue(), program.entryTarget(entry.getKey()), phase + ": " + entry.getKey());
        }
    }
    private long call(Map<String, Value> functions, String name) { return call(functions, name, 0); }
    private long call(Map<String, Value> functions, String name, long input) { return Objects.requireNonNull(functions.get(name)).execute(input).asLong(); }
}
