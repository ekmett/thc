// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.bytecode.BytecodeConfig;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import thc.Language;
import static org.junit.jupiter.api.Assertions.*;

/** First blocking cut after ordinary nonblocking calls must not retire installed code or replay the first take. */
class BytecodeColdHandlerTest {
    @Test void handlerPolicyComesFromImmutableCaptureAuthorityAndSurvivesCloning() throws ReflectiveOperationException {
        try (var context = Context.newBuilder("thc").build()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                for (boolean async : new boolean[]{false, true}) for (boolean delimited : new boolean[]{false, true}) {
                    var metrics = new Metrics(true);
                    var root = BytecodeRootGen.create(language, BytecodeConfig.DEFAULT, b -> {
                        b.beginRoot(); b.emitEnterRoot(metrics); b.beginReturn(); b.emitLoadNull(); b.endReturn(); b.endRoot();
                    }).getNode(0);
                    root.configureAsync(async); root.configureDelimited(delimited); root.getCallTarget();
                    var cloneMethod = root.getClass().getDeclaredMethod("cloneUninitialized"); cloneMethod.setAccessible(true); var clone = (BytecodeRoot) cloneMethod.invoke(root);
                    for (var prepared : List.of(root, clone)) {
                        prepared.getCallTarget(); assertEquals(async || delimited, prepared.requiresUnprofiledExceptionHandlers()); assertEquals(async, prepared.isAsyncEnabled()); assertEquals(delimited, prepared.isDelimitedEnabled());
                    }
                    assertEquals(0L, metrics.getCompiledEntries());
                }
            } finally { context.leave(); }
        }
    }
    private static List<Object> list(Object... values) { return Arrays.asList(values); }
    private static Map<String, Object> map(Object... fields) { var result = new LinkedHashMap<String, Object>(); for (int i = 0; i < fields.length; i += 2) result.put((String) fields[i], fields[i + 1]); return result; }
    private final Map<String, Object> stateRep = map("kind", "void", "primReps", list(), "evaluated", true);
    private final Map<String, Object> mvarRep = map("kind", "object", "primReps", list("BoxedRep (Just Unlifted)"), "evaluated", true);
    private final Map<String, Object> dataRep = map("kind", "data", "primReps", list("BoxedRep (Just Lifted)"), "evaluated", false);
    private final List<Map<String, Object>> longs = new ArrayList<>();
    { for (int i = 0; i < 6; i++) longs.add(map("kind", "long", "primReps", list("IntRep"), "evaluated", true)); }
    private final Map<String, Object> pair = map("kind", "unknown", "aggregate", "unboxed-tuple", "primReps", list("BoxedRep (Just Lifted)"), "components", list(stateRep, dataRep), "evaluated", false);
    private final Map<String, Object> resultRep;
    { var components = new ArrayList<Map<String, Object>>(); components.add(stateRep); components.addAll(longs); resultRep = map("kind", "unknown", "aggregate", "unboxed-tuple", "primReps", Collections.nCopies(6, "IntRep"), "components", components, "evaluated", false); }
    private final Object[] values = {-128L, 255L, -32768L, 65535L, -2147483648L, 4294967295L};
    private List<Object> variable(String name, Map<String, Object> rep) { return list("var", name, map("rep", rep)); }
    private Map<String, Object> module() {
        class AfterTake {
            List<Object> apply(String cell, Object suffix) {
                var call = list("app", list("prim", "takeMVar#"), list(variable(cell, mvarRep), list("void", map("rep", stateRep))), list(false, false), false, false, map("rep", pair));
                return list("case", call, cell + "Result", list(list("data", "Pair", list(cell + "State", cell + "Value"), suffix, map("binders", list(map("id", cell + "State", "rep", stateRep), map("id", cell + "Value", "rep", dataRep))))), map("rep", resultRep, "binder", map("id", cell + "Result", "rep", pair)));
            }
        }
        var arguments = new ArrayList<Object>(); arguments.add(list("void", map("rep", stateRep))); for (int i = 0; i < longs.size(); i++) arguments.add(variable("n" + i, longs.get(i)));
        var tuple = list("app", list("con", "Result", 7), arguments, Collections.nCopies(7, false), false, false, map("rep", resultRep));
        var parameters = new ArrayList<Map<String, Object>>();
        for (String id : List.of("prefix", "blocked")) parameters.add(map("id", id, "name", id, "lifted", false, "coercion", false, "rep", mvarRep));
        for (int i = 0; i < longs.size(); i++) parameters.add(map("id", "n" + i, "name", "n" + i, "lifted", false, "coercion", false, "rep", longs.get(i)));
        var take = new AfterTake();
        return map("instrument", true, "bindings", list(map("id", "entry", "name", "entry", "lifted", true, "expr", list("lam", parameters, take.apply("prefix", take.apply("blocked", tuple)), map("resultRep", resultRep)))),
            "constructors", list(map("id", "Pair", "name", "Pair", "kind", "unboxed-tuple", "arity", 2), map("id", "Result", "name", "Result", "kind", "unboxed-tuple", "arity", 7)));
    }
    @Test void firstCompiledBlockingCutRetainsTargetValuesAndCompletedEffects() throws Exception {
        try (var context = Context.newBuilder("thc").allowExperimentalOptions(true).option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false").option("engine.Splitting", "false").option("engine.CompilationFailureAction", "Throw").build()) {
            context.initialize("thc"); context.enter();
            class Fixture {
                Language language; Language.State owner; ExecutableProgram program; RootCallTarget target; TupleShape shape;
                Object[] arguments(ManagedMVar prefix, ManagedMVar blocked) {
                    var result = new Object[values.length + 3]; result[0] = 0L; result[1] = prefix; result[2] = blocked; System.arraycopy(values, 0, result, 3, values.length); return result;
                }
                void valid() throws ReflectiveOperationException { assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target)); }
                void checkResult(Object result) {
                    var tuple = TupleResults.ownedTupleResult(result, shape); assertEquals(6, shape.getWidth());
                    for (int i = 0; i < values.length; i++) { assertTrue(shape.getLayout().isLong(i)); assertEquals(values[i], shape.getLayout().getLong(tuple, i)); assertEquals(List.of("IntRep"), shape.getLeaves()[i].getPrimReps()); }
                }
            }
            var f = new Fixture();
            try {
                f.language = TruffleLanguage.LanguageReference.create(Language.class).get(null); f.owner = Language.currentState(); f.program = new BytecodeProgram(f.language, module(), true); f.target = f.program.entryTarget("entry");
                assertTrue(((BytecodeRoot) f.target.getRootNode()).requiresUnprofiledExceptionHandlers()); f.shape = Objects.requireNonNull(((GuestRoot) f.target.getRootNode()).getTupleResult());
                for (int i = 0; i < 5; i++) {
                    var prefix = new ManagedMVar(); assertTrue(prefix.tryPut("prefix")); var blocked = new ManagedMVar(); assertTrue(blocked.tryPut("suffix"));
                    f.checkResult(Calls.target(f.target, f.arguments(prefix, blocked))); assertTrue(prefix.isEmpty()); assertTrue(blocked.isEmpty());
                }
                f.target.getClass().getMethod("compile", boolean.class).invoke(f.target, true); f.valid();
                var runtime = Truffle.getRuntime(); runtime.getClass().getMethod("bypassedInstalledCode", Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget")).invoke(runtime, f.target); f.valid();
            } finally { context.leave(); }
            var prefix = new ManagedMVar(); assertTrue(prefix.tryPut("once")); var blocked = new ManagedMVar(); long before = ((Number) f.program.diagnostics().get("compiledEntries")).longValue(); var answer = new CompletableFuture<SavedGuestContinuation>();
            var worker = new Thread(() -> {
                context.enter(); f.owner.getThreads().enterCurrent(null, false, true, null);
                try {
                    var captured = Objects.requireNonNull(SavedGuestContinuations.savedGuestContinuation(Calls.target(f.target, f.arguments(prefix, blocked)))); Objects.requireNonNull(captured.asyncRequest()).acknowledge(); answer.complete(captured);
                } catch (Throwable failure) { answer.completeExceptionally(failure); } finally { f.owner.getThreads().leaveCurrent(GuestThreadStatus.FINISHED); context.leave(); }
            });
            worker.start();
            try {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (blocked.pendingCounts().getTakers() != 1 && !answer.isDone() && System.nanoTime() < deadline) Thread.sleep(1);
                if (answer.isCompletedExceptionally()) answer.get(1, TimeUnit.SECONDS);
                assertEquals(1, blocked.pendingCounts().getTakers()); assertTrue(prefix.isEmpty(), "The first effect completed before the blocked cut");
                f.owner.getThreads().send(Objects.requireNonNull(f.owner.getThreads().pollState(worker).getCurrent()).getIdentity(), "wide cut");
                var captured = answer.get(10, TimeUnit.SECONDS); worker.join(5000); assertFalse(worker.isAlive()); assertEquals(before + 1, ((Number) f.program.diagnostics().get("compiledEntries")).longValue());
                var retainedAfterCapture = f.target.getClass().getMethod("isValidLastTier").invoke(f.target); var completed = new CompletableFuture<thc.runtime.Unit>();
                var resumer = new Thread(() -> {
                    context.enter();
                    try {
                        assertTrue(blocked.tryPut("continue")); f.checkResult(captured.continueWith(thc.runtime.Unit.INSTANCE)); assertTrue(prefix.isEmpty(), "Resumption must not replay the completed first take"); assertTrue(blocked.isEmpty()); assertSame(f.target, f.program.entryTarget("entry"));
                        var handoff = f.language.getHandoffState().get(); assertEquals(0, handoff.getArguments().getDepth()); assertEquals(0, handoff.getArguments().retainedReferences()); assertEquals(0, handoff.getResults().getDepth()); assertEquals(0, handoff.getResults().retainedReferences()); assertNull(handoff.getPending());
                        var bytecode = ((BytecodeRoot) f.target.getRootNode()).getBytecodeNode(); var field = bytecode.getClass().getDeclaredField("exceptionProfiles_"); field.setAccessible(true); var profiles = (boolean[]) field.get(bytecode);
                        boolean none = true; for (boolean profile : profiles) if (profile) { none = false; break; }
                        assertTrue(none, "Capture must not manufacture observed exception profiles"); assertEquals(true, retainedAfterCapture, "The first blocking cut retains installed code"); f.valid(); completed.complete(thc.runtime.Unit.INSTANCE);
                    } catch (Throwable failure) { completed.completeExceptionally(failure); } finally { context.leave(); }
                });
                resumer.start();
                try { completed.get(10, TimeUnit.SECONDS); } finally { if (!completed.isDone()) context.close(true); resumer.join(5000); }
                assertFalse(resumer.isAlive());
            } finally { if (worker.isAlive()) context.close(true); worker.join(5000); }
        }
    }
}
