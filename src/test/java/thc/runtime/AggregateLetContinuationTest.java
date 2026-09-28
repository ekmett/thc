// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleLanguage;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import thc.Language;
import static org.junit.jupiter.api.Assertions.*;
import static thc.runtime.RepresentationTestSupport.*;
import static thc.runtime.ScalarTestCalls.callScalarTestTarget;

/** Completed aggregate lets and a suspended RHS survive a real cross-thread resumption. */
class AggregateLetContinuationTest {
    private static final Map<String, Object> STATE = map("kind", "void", "primReps", list(), "evaluated", true);
    private static final Map<String, Object> REFERENCE = map("kind", "object", "primReps", list("BoxedRep (Just Lifted)"), "evaluated", false);
    private static final Map<String, Object> MVAR = map("kind", "object", "primReps", list("BoxedRep (Just Unlifted)"), "evaluated", true);
    private static final Map<String, Object> INTEGER = map("kind", "long", "primReps", list("IntRep"), "evaluated", true);
    private static final Map<String, Object> PAIR = tuple(STATE, REFERENCE), RESULT = tuple(REFERENCE, REFERENCE), NESTED = tuple(PAIR, REFERENCE);
    private static final Map<String, Object> SUM = map("kind", "unknown", "evaluated", true, "aggregate", "unboxed-sum",
        "primReps", list("WordRep", "BoxedRep (Just Lifted)", "WordRep"), "alternatives", list(PAIR, INTEGER),
        "tagSlot", 0, "alternativeSlots", list(list(1), list(2)));
    @SafeVarargs private static Map<String, Object> tuple(Map<String, Object>... fields) {
        var reps = new ArrayList<Object>(); for (var field : fields) reps.addAll((List<?>) field.get("primReps"));
        return map("kind", "unknown", "primReps", reps, "evaluated", true, "aggregate", "unboxed-tuple", "components", Arrays.asList(fields));
    }
    private static Map<String, Object> binder(String id, Map<String, Object> proof) {
        return map("id", id, "name", id, "rep", proof, "lifted", proof == REFERENCE);
    }
    private static List<Object> variable(String id, Map<String, Object> proof) { return list("var", id, map("rep", proof)); }
    private static List<Object> take(String cell) {
        return list("app", list("prim", "takeMVar#"), list(variable(cell, MVAR), list("void", map("rep", STATE))),
            list(false, false), false, false, map("rep", PAIR));
    }
    private static List<Object> casePair(List<Object> value, String name, List<Object> body) {
        return list("case", value, name + "Pair", list(list("data", "Pair", list(name + "State", name), body,
            map("binders", list(binder(name + "State", STATE), binder(name, REFERENCE))))),
            map("rep", RESULT, "binder", binder(name + "Pair", PAIR)));
    }
    private static List<Object> let(String id, Map<String, Object> proof, List<Object> rhs, List<Object> body) {
        return list("let", false, list(with(binder(id, proof), "expr", rhs)), body, map("rep", RESULT));
    }
    private static Map<String, Object> module(boolean sum, boolean nested) {
        var result = list("app", list("con", "Result", 2), list(variable(nested ? "trailing" : "before", REFERENCE), variable("after", REFERENCE)),
            list(true, true), false, false, map("rep", RESULT));
        var suffix = casePair(variable(sum || nested ? "payload" : "second", PAIR), "after", result);
        var rightResult = list("app", list("con", "Result", 2), list(variable("before", REFERENCE), variable("before", REFERENCE)),
            list(true, true), false, false, map("rep", RESULT));
        if (sum) suffix = list("case", variable("second", SUM), "sum", list(
            list("data", "Left", list("payload"), suffix, map("binders", list(binder("payload", PAIR)))),
            list("data", "Right", list("unused"), rightResult, map("binders", list(binder("unused", INTEGER))))),
            map("rep", RESULT, "binder", binder("sum", SUM)));
        if (nested) suffix = list("case", variable("second", NESTED), "nested", list(
            list("data", "Nested", list("payload", "trailing"), suffix, map("binders", list(binder("payload", PAIR), binder("trailing", REFERENCE))))),
            map("rep", RESULT, "binder", binder("nested", NESTED)));
        var rhs = sum ? list("app", list("con", "Left", 1), list(take("blocked")), list(false), false, false, map("rep", SUM))
            : nested ? list("app", list("con", "Nested", 2), list(take("blocked"), variable("before", REFERENCE)), list(false, true), false, false, map("rep", NESTED))
            : take("blocked");
        var body = let("first", PAIR, take("prefix"), casePair(variable("first", PAIR), "before",
            let("second", sum ? SUM : nested ? NESTED : PAIR, rhs, suffix)));
        return map("schema", 1, "ghc", "9.14.1", "module", "AggregateLetContinuation", "instrument", true,
            "bindings", list(map("id", "entry", "name", "entry", "lifted", true, "expr",
                list("lam", list(binder("prefix", MVAR), binder("blocked", MVAR)), body, map("resultRep", RESULT)))),
            "constructors", list(map("id", "Pair", "kind", "unboxed-tuple", "arity", 2),
                map("id", "Result", "kind", "unboxed-tuple", "arity", 2),
                map("id", "Nested", "kind", "unboxed-tuple", "arity", 2),
                map("id", "Left", "kind", "unboxed-sum", "arity", 1, "sumArity", 2, "tag", 1),
                map("id", "Right", "kind", "unboxed-sum", "arity", 1, "sumArity", 2, "tag", 2)));
    }
    private static void checkResult(Object result, TupleShape shape, Object before, Object after) {
        var values = TupleResults.ownedTupleResult(result, shape);
        assertSame(before, shape.getLayout().getObject(values, 0)); assertSame(after, shape.getLayout().getObject(values, 1));
    }
    @ParameterizedTest @ValueSource(strings = {"ast-tuple", "ast-sum", "ast-nested", "bytecode-tuple", "bytecode-sum", "bytecode-nested"})
    void compiledLetRhsResumesWithoutReplayingCompletedBinding(String mode) throws Exception {
        boolean ast = mode.startsWith("ast"), sum = mode.endsWith("sum"), nested = mode.endsWith("nested");
        try (var context = Context.newBuilder("thc").allowExperimentalOptions(true).option("engine.BackgroundCompilation", "false")
            .option("engine.MultiTier", "false").option("engine.Splitting", "false").option("engine.CompilationFailureAction", "Throw").build()) {
            context.initialize("thc"); context.enter();
            final Language language; final Language.State owner; final ExecutableProgram program; final RootCallTarget target; final TupleShape shape;
            try {
                language = TruffleLanguage.LanguageReference.create(Language.class).get(null); owner = Language.currentState();
                program = ast ? new Program(language, module(sum, nested), true) : new BytecodeProgram(language, module(sum, nested), true);
                target = program.entryTarget("entry"); shape = Objects.requireNonNull(((GuestRoot) target.getRootNode()).getTupleResult());
                for (int i = 0; i < 5; i++) {
                    var prefix = new ManagedMVar(); var blocked = new ManagedMVar(); var a = new Object(); var b = new Object();
                    assertTrue(prefix.tryPut(a)); assertTrue(blocked.tryPut(b));
                    checkResult(callScalarTestTarget(target, new Object[]{0L, prefix, blocked}), shape, a, b);
                }
                target.getClass().getMethod("compile", boolean.class).invoke(target, true);
                assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target));
                var runtime = Truffle.getRuntime(); runtime.getClass().getMethod("bypassedInstalledCode", Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget")).invoke(runtime, target);
            } finally { context.leave(); }
            var prefix = new ManagedMVar(); var blocked = new ManagedMVar(); var before = new Object(); var after = new Object();
            assertTrue(prefix.tryPut(before)); long compiled = ((Number) program.diagnostics().get("compiledEntries")).longValue();
            var answer = new CompletableFuture<SavedGuestContinuation>();
            var worker = new Thread(() -> {
                context.enter(); owner.getThreads().enterCurrent(null, false, true, null);
                try {
                    var saved = Objects.requireNonNull(SavedGuestContinuations.savedGuestContinuation(callScalarTestTarget(target, new Object[]{0L, prefix, blocked})));
                    Objects.requireNonNull(saved.asyncRequest()).acknowledge(); answer.complete(saved);
                } catch (Throwable failure) { answer.completeExceptionally(failure); }
                finally { owner.getThreads().leaveCurrent(GuestThreadStatus.FINISHED); context.leave(); }
            });
            worker.start();
            try {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (blocked.pendingCounts().getTakers() != 1 && !answer.isDone() && System.nanoTime() < deadline) Thread.sleep(1);
                if (answer.isCompletedExceptionally()) answer.get(1, TimeUnit.SECONDS);
                assertEquals(1, blocked.pendingCounts().getTakers()); assertTrue(prefix.isEmpty());
                owner.getThreads().send(Objects.requireNonNull(owner.getThreads().pollState(worker).getCurrent()).getIdentity(), "aggregate let cut");
                var saved = answer.get(10, TimeUnit.SECONDS); worker.join(5000); assertFalse(worker.isAlive());
                assertEquals(compiled + 1, ((Number) program.diagnostics().get("compiledEntries")).longValue());
                var retained = target.getClass().getMethod("isValidLastTier").invoke(target);
                var resumed = new CompletableFuture<Unit>();
                var resumer = new Thread(() -> {
                    context.enter();
                    try {
                        assertTrue(blocked.tryPut(after)); checkResult(saved.continueWith(Unit.INSTANCE), shape, before, after);
                        assertTrue(prefix.isEmpty(), "A completed aggregate let must not be replayed"); assertTrue(blocked.isEmpty());
                        var handoff = language.getHandoffState().get(); assertNull(handoff.getPending());
                        assertEquals(0, handoff.getArguments().getDepth()); assertEquals(0, handoff.getResults().getDepth());
                        assertEquals(0, handoff.getArguments().retainedReferences()); assertEquals(0, handoff.getResults().retainedReferences());
                        assertSame(target, program.entryTarget("entry")); assertEquals(true, retained, "The first blocking cut retains installed code");
                        assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target), "Resumption retains installed code"); resumed.complete(Unit.INSTANCE);
                    } catch (Throwable failure) { resumed.completeExceptionally(failure); } finally { context.leave(); }
                });
                resumer.start();
                try { resumed.get(10, TimeUnit.SECONDS); } finally { if (!resumed.isDone()) context.close(true); resumer.join(5000); }
                assertFalse(resumer.isAlive());
            } finally { if (worker.isAlive()) context.close(true); worker.join(5000); }
        }
    }
}
