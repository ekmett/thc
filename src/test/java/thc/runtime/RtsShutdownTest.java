// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.TruffleContext;
import com.oracle.truffle.api.TruffleLanguage;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.Test;
import thc.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@SuppressWarnings("unchecked")
public class RtsShutdownTest {
    private Map<String, Object> scalar(String kind, List<String> reps) { return Map.of("kind", kind, "primReps", reps, "evaluated", true); }
    private final Map<String, Object> state = scalar("void", List.of()), cint = scalar("long", List.of("Int32Rep")), integer = scalar("long", List.of("IntRep")), closure = scalar("closure", List.of("BoxedRep (Just Lifted)"));
    private final Map<String, Object> boxed = plus(closure, "kind", "data");
    private final Map<String, Object> done = tuple(List.of(state)), io = tuple(List.of(state, boxed));
    private Map<String, Object> plus(Map<String, Object> original, String key, Object value) { var copy = new LinkedHashMap<>(original); copy.put(key, value); return copy; }
    private Map<String, Object> tuple(List<Map<String, Object>> fields) {
        var reps = new ArrayList<String>(); for (var field : fields) reps.addAll((List<String>) field.get("primReps"));
        return Map.of("kind", "unknown", "aggregate", "unboxed-tuple", "primReps", reps, "components", fields, "evaluated", true);
    }
    private List<Object> variable(String id, Map<String, Object> rep) { return List.of("var", id, Map.of("rep", rep)); }
    private Map<String, Object> binder(String id, Map<String, Object> rep) { return binder(id, rep, false); }
    private Map<String, Object> binder(String id, Map<String, Object> rep, boolean lifted) { return Map.of("id", id, "name", id, "lifted", lifted, "rep", rep); }
    private List<Object> lambda(List<Map<String, Object>> args, Object body, Map<String, Object> result) { return List.of("lam", args, body, Map.of("rep", closure, "resultRep", result)); }
    private Map<String, Object> descriptor(RtsShutdownOp op) {
        var arguments = new ArrayList<Map<String, Object>>(); for (var rep : List.of(cint, cint, state)) arguments.add(plus(rep, "evaluated", false));
        return Map.of("schema", 1L, "target", Map.of("kind", "static", "symbol", op.getSymbol(), "unit", "ghc-internal", "isFunction", true),
            "convention", "ccall", "safety", "safe", "arity", 3L, "suppliedArity", 3L, "argumentReps", arguments, "resultRep", plus(done, "evaluated", false));
    }
    private List<Object> call(RtsShutdownOp op, Map<String, Object> proof) {
        return List.of("app", variable("original-shutdown", closure), List.of(variable("code", cint), variable("fast", cint), List.of("void", Map.of("rep", state))),
            List.of(false, false, false), false, false, Map.of("rep", done, "foreignCall", proof));
    }
    private List<Object> caseOf(Object scrutinee, Map<String, Object> rep, Object body, Map<String, Object> result) {
        return List.of("case", scrutinee, "ignored", List.of(Arrays.asList("default", null, List.of(), body)), Map.of("rep", result, "binder", binder("ignored", rep)));
    }
    private Map<String, Object> module(RtsShutdownOp op) { return module(op, descriptor(op)); }
    /** catch# surrounds the real adapter, so hard exit must not become catchable. */
    private Map<String, Object> module(RtsShutdownOp op, Map<String, Object> proof) {
        var unit = List.of("con", "Unit", 0, Map.of("rep", boxed));
        var pair = List.of("app", List.of("con", "Pair", 2), List.of(List.of("void", Map.of("rep", state)), unit), List.of(false, true), false, false, Map.of("rep", io));
        var action = lambda(List.of(binder("s", state)), caseOf(call(op, proof), done, pair, io), io);
        var handler = lambda(List.of(binder("e", boxed, true), binder("t", state)), pair, io);
        var caught = List.of("app", List.of("prim", "catch#"), List.of(action, handler, List.of("void", Map.of("rep", state))), List.of(true, true, false), false, false, Map.of("rep", io));
        var body = caseOf(caught, io, List.of("lit", "int", "99", Map.of("rep", integer)), integer);
        var binding = plus(binder("entry", closure, true), "arity", 2); binding.put("expr", lambda(List.of(binder("code", cint), binder("fast", cint)), body, integer));
        return Map.of("instrument", true, "constructors", List.of(Map.of("id", "Unit", "name", "()", "arity", 0, "tag", 1, "fieldReps", List.of(), "strictFields", List.of(), "fieldLifted", List.of()),
            Map.of("id", "Pair", "name", "(#,#)", "arity", 2, "tag", 1, "kind", "unboxed-tuple")), "bindings", List.of(binding));
    }
    private Context context() { return context(false); }
    private Context context(boolean nativeAccess) { return Context.newBuilder("thc").useSystemExit(false).allowNativeAccess(nativeAccess)
        .allowExperimentalOptions(true).option("compiler.Inlining", "false").option("engine.BackgroundCompilation", "false")
        .option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw").build(); }
    @FunctionalInterface private interface Action<T> { T run(Language language) throws Exception; }
    private <T> T entered(Context context, Action<T> action) throws Exception {
        context.initialize("thc"); context.enter();
        try { return action.run(TruffleLanguage.LanguageReference.create(Language.class).get(null)); } finally { context.leave(); }
    }
    private ExecutableProgram program(Language language, String backend, Map<String, Object> module) { return backend.equals("ast") ? new Program(language, module) : new BytecodeProgram(language, module); }
    private List<Map<String, Object>> cases() throws Exception {
        var root = new File(System.getProperty("thc.projectRoot")); var prefix = "build/rts-shutdown";
        var manifest = (Map<String, Object>) Json.parse(Files.readString(new File(root, prefix + "/manifest.json").toPath()));
        OriginalStdioChecks.hashes(root, manifest.get("inputHashes"), Set.of("compiler/test-fixtures/RtsShutdownNative.hs", "test/haskell-fixtures/RtsShutdownFixtures.hs"), null);
        OriginalStdioChecks.hashes(root, manifest.get("artifactHashes"), Set.of(prefix + "/oracle.json"), prefix + "/");
        return (List<Map<String, Object>>) ((Map<String, Object>) Json.parse(Files.readString(new File(root, prefix + "/oracle.json").toPath()))).get("cases");
    }
    @Test public void nativeStatusContractAndFirstInstalledExitCloseOnlyTheGuestContext() throws Exception {
        var rows = cases(); assertEquals(14, rows.size());
        for (var backend : List.of("ast", "bytecode")) for (boolean compiled : new boolean[]{false, true}) for (var row : rows) {
            var op = "exit".equals(row.get("kind")) ? RtsShutdownOp.EXIT : RtsShutdownOp.SIGNAL;
            if (compiled && !List.of(37L, 15L).contains(row.get("code"))) continue;
            long code = (Long) row.get("code"), fast = (Long) row.get("fast");
            int status = op == RtsShutdownOp.EXIT ? ((Long) row.get("exit")).intValue() : -(int) code;
            var context = context();
            class Setup { ExecutableProgram program; Language.State owner; Value value; TruffleContext handle; long before; }
            var setup = new Setup();
            entered(context, language -> {
                setup.owner = Language.currentState(); setup.handle = setup.owner.getEnv().getContext();
                setup.program = program(language, backend, module(op)); setup.value = context.asValue(new EntryValue(setup.program, "entry", 2));
                // Existing rejected-carrier setup must not install an exit request.
                assertFalse(assertThrows(PolyglotException.class, () -> setup.value.execute(Long.MAX_VALUE, 0L)).isExit()); assertNull(setup.owner.getShutdown().get());
                if (compiled) { assertTrue(setup.value.invokeMember("compile").asBoolean()); setup.before = ((Number) setup.program.diagnostics().get("compiledEntries")).longValue(); }
                return null;
            });
            var failure = assertThrows(PolyglotException.class, () -> { try (context) { setup.value.execute(code, fast); } });
            assertTrue(failure.isExit(), backend + "/" + row + ": " + failure); assertEquals(status, failure.getExitStatus());
            assertEquals(new GuestShutdown(status, fast != 0L), setup.owner.getShutdown().get());
            if (compiled) assertTrue(((Number) setup.program.diagnostics().get("compiledEntries")).longValue() > setup.before);
            assertTrue(setup.handle.isClosed());
            try (var fresh = context()) { entered(fresh, language -> { assertNull(Language.currentState().getShutdown().get()); return null; }); }
        }
    }
    @Test public void malformedProofsAndCarriersRejectWithoutClosingTheContext() throws Exception {
        for (var backend : List.of("ast", "bytecode")) try (var context = context()) { entered(context, language -> {
            for (var op : RtsShutdownOp.values()) {
                Object[][] badFields = {{"safety", "unsafe"}, {"convention", "capi"}, {"arity", 2L}, {"resultRep", state}};
                for (var bad : badFields) assertThrows(RuntimeFault.class, () -> program(language, backend, module(op, plus(descriptor(op), (String) bad[0], bad[1]))));
                var value = context.asValue(new EntryValue(program(language, backend, module(op)), "entry", 2));
                for (var args : new Long[][]{{Long.MAX_VALUE, 0L}, {0L, Long.MIN_VALUE}}) {
                    // Preserve tested correction 5b0be6bf: both ABI boundaries remain negative controls.
                    var direct = assertThrows(RuntimeFault.class, () -> CoreRtsShutdown.shutdown(null, op, args[0], args[1], thc.runtime.Unit.INSTANCE));
                    assertTrue(Objects.toString(direct.getMessage(), "").contains("signed CInt"));
                    var failure = assertThrows(PolyglotException.class, () -> value.execute((Object[]) args));
                    assertFalse(failure.isExit()); assertTrue(Objects.toString(failure.getMessage(), "").contains("Public narrow integer argument is out of range"));
                    assertNull(Language.currentState().getShutdown().get());
                }
            }
            return null;
        }); }
    }
    @Test public void invalidSignedSignalNumbersExitTheContextWithFallbackStatus() throws Exception {
        for (var backend : List.of("ast", "bytecode")) for (long code : new long[]{-1L, 0L, 65L}) {
            var context = context(); var value = entered(context, language -> context.asValue(new EntryValue(program(language, backend, module(RtsShutdownOp.SIGNAL)), "entry", 2)));
            var failure = assertThrows(PolyglotException.class, () -> { try (context) { value.execute(code, 0L); } });
            assertTrue(failure.isExit()); assertEquals(255, failure.getExitStatus());
        }
    }
    @Test public void hardExitDisposesNativeHandlesAndStableRootsInBothModes() throws Exception {
        assumeTrue(System.getProperty("os.name").equals("Linux") && Set.of("amd64", "x86_64").contains(System.getProperty("os.arch")));
        for (long fast : new long[]{0L, 1L}) {
            var context = context(true);
            class Setup { Language.State owner; ManagedAddress root; Value value; } var setup = new Setup();
            entered(context, language -> {
                setup.owner = Language.currentState(); var utf8 = ManagedAddress.fromByteArray("UTF-8\0".getBytes(StandardCharsets.UTF_8));
                assertTrue(setup.owner.getIconv().open(utf8, utf8) > 0);
                setup.owner.getNativeAllocations().malloc(8); setup.root = setup.owner.getStablePointers().make(new Object());
                setup.value = context.asValue(new EntryValue(program(language, "bytecode", module(RtsShutdownOp.EXIT)), "entry", 2)); return null;
            });
            assertTrue(assertThrows(PolyglotException.class, () -> { try (context) { setup.value.execute(37L, fast); } }).isExit());
            assertEquals(0, setup.owner.getIconv().liveHandles()); assertEquals(0, setup.owner.getNativeAllocations().liveCount());
            assertThrows(RuntimeFault.class, () -> setup.owner.getStablePointers().dereference(setup.root));
        }
    }
    @Test public void completedWakeRaceNeverSwallowsThreadDeathInSendOrResume() throws Exception {
        for (boolean resume : new boolean[]{false, true}) {
            var ready = new CountDownLatch(1); var finish = new CountDownLatch(1); var death = new ThreadDeath(); var armed = new boolean[]{!resume};
            // Complete the target while its wake callback propagates the exact control failure.
            var threads = new GuestThreads(ThreadLocal.withInitial(() -> MaskingState.UNMASKED), target -> {
                if (armed[0]) { finish.countDown(); target.join(5000); assertFalse(target.isAlive()); throw death; }
            });
            var worker = new Thread(() -> {
                threads.registerCurrent(); ready.countDown();
                try { if (!finish.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Check failed."); }
                catch (InterruptedException failure) { RtsShutdownTest.<RuntimeException>rethrow(failure); }
                finally { threads.completeCurrent(); }
            });
            worker.start(); assertTrue(ready.await(5, TimeUnit.SECONDS));
            try {
                var request = resume ? threads.send(Objects.requireNonNull(threads.pollState(worker).getCurrent()).getIdentity(), "payload") : null;
                if (resume) assertTrue(threads.pause(request));
                armed[0] = true;
                var thrown = assertThrows(ThreadDeath.class, () -> {
                    if (request == null) threads.send(Objects.requireNonNull(threads.pollState(worker).getCurrent()).getIdentity(), "payload"); else threads.resume(request);
                });
                assertSame(death, thrown);
            } finally { finish.countDown(); worker.join(5000); threads.close(); }
        }
    }
    private static <E extends Throwable> void rethrow(Throwable failure) throws E { throw (E) failure; }
}
