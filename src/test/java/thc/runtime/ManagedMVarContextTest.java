// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.TruffleLanguage;
import java.io.File;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import thc.CoreModules;
import thc.Json;
import thc.Language;
import static org.junit.jupiter.api.Assertions.*;

/** One entered guest executor, with host-only cell operations/cancellation on the test thread. */
@Timeout(90)
@SuppressWarnings("unchecked")
public class ManagedMVarContextTest {
    private final File root = new File(System.getProperty("thc.projectRoot"));
    private final List<String> names = List.of("waitTake", "waitRead", "waitPut", "makeBox");
    private final ManagedMVar.PendingCounts noWaiters = new ManagedMVar.PendingCounts(0, 0, 0);
    private record Oracle(String name, long input, long result) {}
    private record Fixture(Map<String, Map<String, Object>> modules, List<Oracle> oracle) {}
    private record Published(ManagedMVar cell, Object original, Object offered) {}
    private record Completed(long result, Long decodedPut) {}
    private record Key(String name, long input) {}
    private record Started(Published ready, Future<Completed> future) {}
    private Fixture fixture() throws Exception {
        var path = new File(root, "build/managed-mvars/manifest.json"); assertTrue(path.isFile(), "Run scripts/prepare-managed-mvars.py for genuine GHC MVar fixtures");
        var manifest = (Map<String, Object>) Json.parse(Files.readString(path.toPath())); assertEquals("9.14.1", manifest.get("ghc"));
        assertEquals(new LinkedHashSet<>(names), new LinkedHashSet<>((List<String>) manifest.get("contextEntryNames")));
        for (var kind : List.of("inputHashes", "artifactHashes")) for (var item : ((Map<String, String>) manifest.get(kind)).entrySet()) {
            var actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(new File(root, item.getKey()).toPath())));
            assertEquals(item.getValue(), actual, "Stale managed MVar fixture: " + item.getKey());
        }
        var rows = new ArrayList<Oracle>();
        for (var line : Files.readAllLines(new File(root, "build/managed-mvars/context-oracle.tsv").toPath())) {
            var fields = line.split("\t", -1); assertEquals(3, fields.length); var name = fields[0].startsWith("native") ? fields[0].substring(6) : fields[0];
            if (!name.isEmpty()) name = name.substring(0, 1).toLowerCase(Locale.ROOT) + name.substring(1);
            rows.add(new Oracle(name, Long.parseLong(fields[1]), Long.parseLong(fields[2])));
        }
        assertEquals(((Number) manifest.get("nativeContextRows")).intValue(), rows.size()); var observedNames = new LinkedHashSet<String>(); var keys = new LinkedHashSet<Key>();
        for (var row : rows) { observedNames.add(row.name()); keys.add(new Key(row.name(), row.input())); }
        assertEquals(new LinkedHashSet<>(names.subList(0, names.size() - 1)), observedNames); assertEquals(rows.size(), keys.size());
        var stages = (Map<String, List<String>>) manifest.get("stages"); assertEquals(Set.of("pre", "post"), stages.keySet()); var modules = new LinkedHashMap<String, Map<String, Object>>();
        for (var stage : stages.entrySet()) {
            var sources = new ArrayList<Map<String, Object>>(); for (var file : stage.getValue()) sources.add((Map<String, Object>) Json.parse(Files.readString(new File(root, file).toPath())));
            var module = CoreModules.merge(sources);
            // Merge root-specific closures, preserving constructor/source metadata and
            // deduplicating only shared identical bindings, never replacing their bodies.
            var seen = new LinkedHashSet<String>(); var reachedModules = new ArrayList<Map<String, Object>>();
            for (var name : names) {
                var reached = CoreModules.reachable(module, name); var bindings = new ArrayList<Map<String, Object>>();
                for (var binding : (List<Map<String, Object>>) reached.get("bindings")) if (seen.add((String) binding.get("id"))) bindings.add(binding);
                var selected = new LinkedHashMap<>(reached); selected.put("bindings", bindings); reachedModules.add(selected);
            }
            var merged = new LinkedHashMap<>(CoreModules.merge(reachedModules)); merged.put("instrument", true); modules.put(stage.getKey(), merged);
        }
        return new Fixture(modules, rows);
    }
    private Context context() { return Context.newBuilder("thc").allowExperimentalOptions(true).option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw").build(); }
    private ExecutableProgram program(Language language, Map<String, Object> module, String backend) { return backend.equals("ast") ? new Program(language, module) : new BytecodeProgram(language, module); }
    private long call(Context context, ExecutableProgram program, String name, Object... arguments) { return context.asValue(new ManagedMVarContextCall(program, name, arguments.clone())).execute().asLong(); }
    private Object box(ExecutableProgram program, long value) { return Calls.target(program.hostEntryTarget(1), new Object[]{program.entryValue("makeBox"), new Object[]{value}}); }
    private void released(Language language, String label) {
        var handoff = language.getHandoffState().get(); assertEquals(0, handoff.getArguments().getDepth(), label + " argument depth"); assertEquals(0, handoff.getResults().getDepth(), label + " result depth");
        assertEquals(0, handoff.getArguments().retainedReferences(), label + " argument references"); assertEquals(0, handoff.getResults().retainedReferences(), label + " result references"); assertNull(handoff.getPending(), label + " pending transfer");
    }
    @FunctionalInterface private interface Entered<T> { T run(Language language) throws Exception; }
    private <T> T entered(Context context, String label, Entered<T> action) throws Exception {
        context.initialize("thc"); context.enter(); Language language = null;
        try { language = TruffleLanguage.LanguageReference.create(Language.class).get(null); return action.run(language); }
        finally { try { if (language != null) released(language, label); } finally { context.leave(); } }
    }
    @FunctionalInterface private interface ExecutorAction { void run(Context context, ExecutorService executor) throws Exception; }
    private void withExecutor(ExecutorAction action) throws Exception {
        var context = context(); var executor = Executors.newSingleThreadExecutor();
        try { action.run(context, executor); }
        finally { try { context.close(true); } finally { executor.shutdownNow(); assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS), "Guest executor did not terminate"); } }
    }
    @FunctionalInterface private interface Variant { void run(Map<String, Object> module, String backend, String label, Fixture fixture) throws Exception; }
    private void variants(Variant action) throws Exception {
        var fixture = fixture(); var old = System.getProperty(HandoffKt.HANDOFF_PROPERTY);
        try {
            for (boolean handoff : new boolean[]{false, true}) { System.setProperty(HandoffKt.HANDOFF_PROPERTY, Boolean.toString(handoff));
                for (var stage : fixture.modules().entrySet()) for (var backend : List.of("ast", "bytecode")) action.run(stage.getValue(), backend, stage.getKey() + "/" + backend + "/handoff=" + handoff, fixture);
            }
        } finally { if (old == null) System.clearProperty(HandoffKt.HANDOFF_PROPERTY); else System.setProperty(HandoffKt.HANDOFF_PROPERTY, old); }
    }
    @Test public void readyOperationsMatchEveryNativeContextOracleRow() throws Exception { variants((module, backend, label, fixture) -> withExecutor((context, executor) -> {
        executor.submit(() -> entered(context, label, language -> {
            var program = program(language, module, backend);
            for (var row : fixture.oracle()) {
                var name = label + "/" + row.name() + "/" + row.input(); var cell = new ManagedMVar(); var value = box(program, row.input());
                if (!row.name().equals("waitPut")) assertTrue(cell.tryPut(value));
                long result = row.name().equals("waitPut") ? call(context, program, row.name(), cell, row.input(), kotlin.Unit.INSTANCE) : call(context, program, row.name(), cell, kotlin.Unit.INSTANCE);
                assertEquals(row.result(), result, name); assertEquals(row.name().equals("waitPut") ? row.input() + 17 : row.input(), row.result(), name + " model");
                switch (row.name()) { case "waitTake" -> assertTrue(cell.isEmpty(), name); case "waitRead" -> assertSame(value, cell.tryRead().getValue(), name); case "waitPut" -> assertEquals(row.input(), call(context, program, "waitRead", cell, kotlin.Unit.INSTANCE), name); }
                assertEquals(noWaiters, cell.pendingCounts(), name); released(language, name);
            }
            return null;
        })).get(60, TimeUnit.SECONDS);
    })); }
    private ManagedMVar.PendingCounts expectedWaiters(String name) { return switch (name) {
        case "waitTake" -> new ManagedMVar.PendingCounts(1, 0, 0); case "waitRead" -> new ManagedMVar.PendingCounts(0, 1, 0); case "waitPut" -> new ManagedMVar.PendingCounts(0, 0, 1); default -> throw new IllegalStateException(name);
    }; }
    private void awaitPending(ManagedMVar cell, String name, Future<?> future, String label) throws Exception {
        var expected = expectedWaiters(name); long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!cell.pendingCounts().equals(expected)) {
            if (future.isDone()) { future.get(1, TimeUnit.SECONDS); fail(label + " returned without registering the blocked operation"); }
            if (System.nanoTime() >= deadline) fail(label + " did not register " + expected); Thread.yield();
        }
        assertFalse(future.isDone(), label + " unexpectedly completed while still queued");
    }
    private Started start(Context context, ExecutorService executor, Map<String, Object> module, String backend, String name, long input, String label) throws Exception {
        var published = new CompletableFuture<Published>();
        Future<Completed> future = executor.submit(() -> {
            try {
                return entered(context, label, language -> {
                    var program = program(language, module, backend); var original = box(program, input ^ Long.MIN_VALUE); var offered = box(program, input); var cell = new ManagedMVar();
                    if (name.equals("waitPut")) assertTrue(cell.tryPut(original)); published.complete(new Published(cell, original, offered));
                    long result = name.equals("waitPut") ? call(context, program, name, cell, input, kotlin.Unit.INSTANCE) : call(context, program, name, cell, kotlin.Unit.INSTANCE);
                    return new Completed(result, name.equals("waitPut") ? call(context, program, "waitRead", cell, kotlin.Unit.INSTANCE) : null);
                });
            } catch (Throwable failure) { published.completeExceptionally(failure); throw failure; }
        });
        var ready = published.get(10, TimeUnit.SECONDS); awaitPending(ready.cell(), name, future, label); return new Started(ready, future);
    }
    @Test public void blockedOperationsWakeFromExternalHostCellOperations() throws Exception { variants((module, backend, variant, fixture) -> {
        for (var name : names.subList(0, names.size() - 1)) withExecutor((context, executor) -> {
            Oracle selected = null; for (var row : fixture.oracle()) if (row.name().equals(name) && row.input() == Long.MAX_VALUE) { selected = row; break; }
            if (selected == null) throw new java.util.NoSuchElementException(name); var row = selected; var label = variant + "/" + name;
            var started = start(context, executor, module, backend, name, row.input(), label); var ready = started.ready(); var future = started.future();
            if (name.equals("waitPut")) { var old = ready.cell().tryTake(); assertTrue(old.getPresent(), label); assertSame(ready.original(), old.getValue(), label); }
            else assertTrue(ready.cell().tryPut(ready.offered()), label);
            var completed = future.get(10, TimeUnit.SECONDS); assertEquals(row.result(), completed.result(), label);
            switch (name) { case "waitTake" -> assertTrue(ready.cell().isEmpty(), label); case "waitRead" -> assertSame(ready.offered(), ready.cell().tryRead().getValue(), label); case "waitPut" -> assertEquals(row.input(), completed.decodedPut(), label); }
            assertEquals(noWaiters, ready.cell().pendingCounts(), label);
        });
    }); }
    @Test public void contextCloseCancelsPendingOperationsWithoutCommittingThem() throws Exception { cancel(false); }
    @Test public void contextInterruptCancelsPendingOperationsWithoutCommittingThem() throws Exception { cancel(true); }
    private void cancel(boolean interrupt) throws Exception { variants((module, backend, variant, _) -> {
        for (var name : names.subList(0, names.size() - 1)) withExecutor((context, executor) -> {
            var label = variant + "/" + name + "/interrupt=" + interrupt; var started = start(context, executor, module, backend, name, 77L, label); var ready = started.ready(); var future = started.future();
            if (interrupt) context.interrupt(Duration.ofSeconds(10)); else context.close(true);
            var failure = assertThrows(ExecutionException.class, () -> future.get(10, TimeUnit.SECONDS), label).getCause(); var guestFailure = assertInstanceOf(PolyglotException.class, failure, label);
            assertTrue(interrupt ? guestFailure.isInterrupted() : guestFailure.isCancelled(), label + " must unwind through the actual Polyglot cancellation boundary: " + guestFailure);
            assertEquals(noWaiters, ready.cell().pendingCounts(), label);
            if (name.equals("waitPut")) {
                var remaining = ready.cell().tryTake(); assertTrue(remaining.getPresent(), label); assertSame(ready.original(), remaining.getValue(), label + " must preserve the original full cell");
                assertTrue(ready.cell().isEmpty(), label + " cancelled put must not publish after a later take");
            } else {
                assertTrue(ready.cell().isEmpty(), label); var probe = new Object(); assertTrue(ready.cell().tryPut(probe), label);
                assertSame(probe, ready.cell().tryRead().getValue(), label + " must not leave a waiter to steal later input");
            }
            if (interrupt) {
                // Polyglot interrupt is not permanent context closure. This fresh,
                // independent call proves cleanup; it does not resume a cancelled thunk.
                var result = executor.submit(() -> entered(context, label + "/reuse", language -> {
                    var program = program(language, module, backend); var cell = new ManagedMVar(); assertTrue(cell.tryPut(box(program, -913L))); return call(context, program, "waitTake", cell, kotlin.Unit.INSTANCE);
                })).get(10, TimeUnit.SECONDS); assertEquals(-913L, result, label + " reusable context");
            }
        });
    }); }
}
