// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import thc.Language;
import static org.junit.jupiter.api.Assertions.*;

/** Real alternating Closure calls, with an interruptible MVar cut after each prefix.
 * Five representative cases cover typed PAPs, compiled tuple transport and IO delivery.
 * Constructs Core in memory and uses private MVars; consumes and produces no fixture files. */
class MixedBackendContinuationTest {
    private static List<Object> list(Object... values) { return Arrays.asList(values); }
    private static Map<String, Object> map(Object... fields) {
        var result = new LinkedHashMap<String, Object>();
        for (int i = 0; i < fields.length; i += 2) result.put((String) fields[i], fields[i + 1]);
        return result;
    }
    private static final Map<String, Object> LONG = map("kind", "long", "primReps", list("IntRep"), "evaluated", true);
    private static final Map<String, Object> REF = map("kind", "object", "primReps", list("BoxedRep (Just Lifted)"), "evaluated", false);
    private static final Map<String, Object> CELL = map("kind", "object", "primReps", list("BoxedRep (Just Unlifted)"), "evaluated", true);
    private static final Map<String, Object> CLOSURE = map("kind", "closure", "primReps", list("BoxedRep (Just Lifted)"), "evaluated", true);
    private static final Map<String, Object> VOID = map("kind", "void", "primReps", list(), "evaluated", true);
    private static final Map<String, Object> READ = tuple(VOID, REF);
    private static final Map<String, Object> PAIR = tuple(LONG, REF);
    private static Map<String, Object> tuple(Map<String, Object> a, Map<String, Object> b) {
        var reps = new ArrayList<>((List<?>) a.get("primReps")); reps.addAll((List) b.get("primReps"));
        return map("kind", "unknown", "aggregate", "unboxed-tuple", "primReps", reps,
                "components", list(a, b), "evaluated", true);
    }
    private static Map<String, Object> arg(String id, Map<String, Object> rep) {
        return map("id", id, "name", id, "rep", rep, "lifted", rep == REF || rep == CLOSURE);
    }
    private static List<Object> v(String id, Map<String, Object> rep) { return list("var", id, map("rep", rep)); }
    private static List<Object> state() { return list("void", map("rep", VOID)); }
    private static List<Object> app(List<Object> fn, List<Object> args, Map<String, Object> rep) {
        var flags = new ArrayList<Boolean>();
        for (Object value : args) {
            var expression = (List<?>) value;
            var proof = (Map<?, ?>) ((Map<?, ?>) expression.getLast()).get("rep");
            flags.add(List.of("BoxedRep (Just Lifted)").equals(proof.get("primReps")) && !proof.containsKey("aggregate"));
        }
        return list("app", fn, args, flags, false, false, map("rep", rep));
    }
    private static List<Object> prim(String name, List<Object> args, Map<String, Object> rep) {
        return app(list("prim", name), args, rep);
    }
    private static List<Object> sequence(List<Object> action, String id, Map<String, Object> actionRep,
                                         List<Object> body, Map<String, Object> result) {
        return list("case", action, id, list(list("default", null, list(), body)),
                map("rep", result, "binder", arg(id, actionRep)));
    }
    private static List<Object> pack(List<Object> n, List<Object> ref) {
        return app(list("con", "Pair", 2), list(n, ref), PAIR);
    }
    private static List<Object> unpack(List<Object> value, List<Object> body, Map<String, Object> result) {
        return list("case", value, "answer", list(list("data", "Pair", list("number", "reference"), body,
                map("binders", list(arg("number", LONG), arg("reference", REF))))),
                map("rep", result, "binder", arg("answer", PAIR)));
    }
    private static Map<String, Object> module(int level) {
        var params = new ArrayList<Object>();
        if (level < 2) params.add(arg("next", CLOSURE));
        params.addAll(list(arg("prefix", CELL), arg("suffix", CELL), arg("blocked", CELL), arg("n", LONG), arg("marker", REF)));
        var answer = level == 2
            ? sequence(prim("takeMVar#", list(v("blocked", CELL), state()), READ), "taken", READ, pack(v("n", LONG), v("marker", REF)), PAIR)
            : app(v("next", CLOSURE), list(v("blocked", CELL), v("n", LONG), v("marker", REF)), PAIR);
        var put = prim("putMVar#", list(v("suffix", CELL), v("marker", REF), state()), VOID);
        var done = sequence(put, "wrote", VOID, pack(v("number", LONG), v("reference", REF)), PAIR);
        var body = sequence(prim("takeMVar#", list(v("prefix", CELL), state()), READ), "prefixRead", READ, unpack(answer, done, PAIR), PAIR);
        return map("instrument", true, "constructors", list(map("id", "Pair", "name", "Pair", "kind", "unboxed-tuple", "arity", 2)),
            "bindings", list(map("id", "run", "name", "run", "lifted", true,
                "expr", list("lam", params, body, map("resultRep", PAIR)))));
    }
    private static SavedGuestContinuation saved(Object result) {
        return Objects.requireNonNull(SavedGuestContinuations.savedGuestContinuation(
                result instanceof TailYield tail ? tail.getContinuation() :
                result instanceof AstTailYield tail ? tail.getContinuation() : result));
    }
    private static List<Object> snapshot() {
        var declaredState = new LinkedHashMap<>(VOID); declaredState.put("evaluated", false);
        var result = tuple(VOID, CELL); var declaredResult = new LinkedHashMap<>(result); declaredResult.put("evaluated", false);
        return list("app", v("cloneCurrent", CLOSURE), list(state()), list(false), false, false,
                map("rep", result, "foreignCall", map("schema", 1,
                        "target", map("kind", "static", "symbol", "stg_cloneMyStackzh", "unit", "ghc-internal", "isFunction", true),
                        "convention", "prim", "safety", "safe", "arity", 1, "suppliedArity", 1,
                        "argumentReps", list(declaredState), "resultRep", declaredResult)));
    }
    private static Map<String, Object> ioModule(int level, boolean caught) {
        var params = new ArrayList<Object>();
        if (level < 2) params.add(arg("next", CLOSURE));
        params.addAll(list(arg("prefix", CELL), arg("suffix", CELL), arg("blocked", CELL), arg("marker", REF)));
        params.add(arg("state", VOID));
        var action = level == 2 ? prim("takeMVar#", list(v("blocked", CELL), state()), READ)
                : level == 0 ? prim("maskAsyncExceptions#", list(v("next", CLOSURE), state()), READ)
                : prim("annotateStack#", list(v("marker", REF), v("next", CLOSURE), state()), READ);
        if (caught && level == 0) {
            var handling = list("lam", list(arg("payload", REF), arg("s", VOID)),
                    app(list("con", "Pair", 2), list(state(), v("payload", REF)), READ), map("rep", CLOSURE, "resultRep", READ));
            action = prim("catch#", list(v("next", CLOSURE), handling, state()), READ);
        }
        var boxed = map("kind", "data", "primReps", list("BoxedRep (Just Lifted)"), "evaluated", true);
        var observation = app(list("con", "Observation", 3), list(v("mask", LONG), v("snapshot", CELL), v("marker", REF)), boxed);
        var complete = app(list("con", "Pair", 2), list(state(), v(caught && level == 0 ? "delivered" : "marker", REF)), READ);
        var put = sequence(observation, "observation", boxed,
                sequence(prim("putMVar#", list(v("suffix", CELL), v("observation", boxed), state()), VOID), "wrote", VOID, complete, READ), READ);
        // Inspect from a real guest activation: a saved AST suffix is not a live physical stack frame.
        var snap = list("case", app(v("snapshotHelper", CLOSURE), list(state()), tuple(VOID, CELL)), "snapPair", list(list("data", "Pair", list("snapState", "snapshot"), put,
                map("binders", list(arg("snapState", VOID), arg("snapshot", CELL))))), map("rep", READ, "binder", arg("snapPair", tuple(VOID, CELL))));
        var observed = list("case", prim("getMaskingState#", list(state()), tuple(VOID, LONG)), "maskPair",
                list(list("data", "Pair", list("maskState", "mask"), snap,
                        map("binders", list(arg("maskState", VOID), arg("mask", LONG))))),
                map("rep", READ, "binder", arg("maskPair", tuple(VOID, LONG))));
        var body = sequence(action, "actionResult", READ, observed, READ);
        if (caught && level == 0) body = list("case", action, "actionResult",
                list(list("data", "Pair", list("actionState", "delivered"), observed,
                        map("binders", list(arg("actionState", VOID), arg("delivered", REF))))),
                map("rep", READ, "binder", arg("actionResult", READ)));
        body = sequence(prim("takeMVar#", list(v("prefix", CELL), state()), READ), "prefixRead", READ, body, READ);
        return map("instrument", true, "constructors", list(
                map("id", "Pair", "name", "Pair", "kind", "unboxed-tuple", "arity", 2),
                map("id", "Observation", "name", "Observation", "arity", 3,
                        "fieldReps", list(list("IntRep"), list("BoxedRep (Just Unlifted)"), list("BoxedRep (Just Lifted)")),
                        "fieldLifted", list(false, false, true), "strictFields", list(false, false, false))),
                "bindings", list(map("id", "run", "name", "run", "lifted", true,
                        "expr", list("lam", params, body, map("resultRep", READ))),
                        map("id", "snapshotHelper", "name", "snapshotHelper", "lifted", true,
                                "expr", list("lam", list(arg("s", VOID)), snapshot(), map("resultRep", tuple(VOID, CELL))))));
    }
    private static void clear(Language language) {
        var handoff = language.getHandoffState().get(); assertNull(handoff.getPending());
        assertEquals(0, handoff.getArguments().getDepth()); assertEquals(0, handoff.getResults().getDepth());
        assertEquals(0, handoff.getArguments().retainedReferences()); assertEquals(0, handoff.getResults().retainedReferences());
    }

    private static Map<String, Object> typedStrictModule(boolean caller, boolean pap) {
        var params = caller ? list(arg("fn", CLOSURE), arg("left", REF), arg("right", REF),
                arg("entered", CELL), arg("observed", CELL), arg("suffix", CELL), arg("n", LONG), arg("marker", REF))
            : list(arg("left", REF), arg("right", REF), arg("entered", CELL), arg("observed", CELL), arg("input", PAIR));
        List<Object> body;
        if (caller) {
            var actuals = new ArrayList<Object>();
            if (!pap) actuals.addAll(list(v("left", REF), v("right", REF)));
            actuals.addAll(list(v("entered", CELL), v("observed", CELL), pack(v("n", LONG), v("marker", REF))));
            body = unpack(app(v("fn", CLOSURE), actuals, PAIR),
                sequence(prim("putMVar#", list(v("suffix", CELL), v("marker", REF), state()), VOID), "done", VOID,
                    pack(v("number", LONG), v("reference", REF)), PAIR), PAIR);
        } else body = sequence(prim("putMVar#", list(v("entered", CELL), v("left", REF), state()), VOID), "entry", VOID,
                sequence(prim("putMVar#", list(v("observed", CELL), v("right", REF), state()), VOID), "second", VOID,
                    v("input", PAIR), PAIR), PAIR);
        var strict = caller ? Collections.nCopies(params.size(), false) : list(true, true, false, false, false);
        return map("instrument", true, "constructors", list(map("id", "Pair", "name", "Pair", "kind", "unboxed-tuple", "arity", 2)),
            "bindings", list(map("id", "run", "name", "run", "lifted", true, "entryStrict", strict,
                "expr", list("lam", params, body, map("resultRep", PAIR, "entryStrict", strict)))));
    }
    private static Map<String, Object> strictThunkModule() {
        var take = prim("takeMVar#", list(v("blocked", CELL), state()), READ);
        var waiting = list("case", take, "taken", list(list("data", "Read", list("s", "value"), v("value", REF),
                map("binders", list(arg("s", VOID), arg("value", REF))))), map("rep", REF, "binder", arg("taken", READ)));
        waiting = sequence(prim("takeMVar#", list(v("prefix", CELL), state()), READ), "prefixRead", READ, waiting, REF);
        return map("instrument", true, "constructors", list(map("id", "Read", "name", "Read", "kind", "unboxed-tuple", "arity", 2),
                map("id", "Box", "name", "Box", "kind", "boxed", "arity", 1, "fieldTypes", list(REF), "fieldReps", list(REF.get("primReps")), "strictFields", list(false), "fieldLifted", list(true))),
            "bindings", list(map("id", "make", "name", "make", "lifted", true,
                "expr", list("lam", list(arg("prefix", CELL), arg("blocked", CELL)),
                    app(list("con", "Box", 1), list(waiting), REF), map("resultRep", REF)))));
    }
    private record StrictCut(SavedGuestContinuation saved, AsyncRequest request) {}
    private static SavedGuestContinuation interruptStrict(Context context, Language.State owner, Language language,
            ManagedMVar blocked, Thunk parked, java.util.concurrent.Callable<Object> action) throws Exception {
        var answer = new CompletableFuture<StrictCut>(); var identity = new AtomicReference<GuestThreadId>();
        var worker = new Thread(() -> {
            context.enter(); owner.getThreads().enterCurrent(null, false, true, null);
            try {
                identity.set(owner.getThreads().currentIdentity());
                SynchronousMasking.set(null, MaskingState.MASKED_INTERRUPTIBLE);
                SavedGuestContinuation cut; AsyncRequest request;
                try { cut = saved(action.call()); request = cut.asyncRequest(); }
                catch (ThunkSuspended suspended) {
                    if (parked == null) throw suspended;
                    assertSame(parked, suspended.getThunk());
                    cut = saved(parked.getValue()); request = suspended.getAsyncRequest();
                }
                assertEquals(MaskingState.MASKED_INTERRUPTIBLE, SynchronousMasking.current(null));
                assertSame(StackAnnotationState.EMPTY, StackAnnotations.current(null));
                request.acknowledge(); clear(language); answer.complete(new StrictCut(cut, request));
            } catch (Throwable failure) { answer.completeExceptionally(failure); }
            finally { owner.getThreads().leaveCurrent(GuestThreadStatus.FINISHED); context.leave(); }
        }, "typed-strict-capture");
        worker.start();
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (blocked.pendingCounts().getTakers() != 1 && !answer.isDone() && System.nanoTime() < deadline) Thread.sleep(1);
            if (answer.isCompletedExceptionally()) answer.get(1, TimeUnit.SECONDS);
            assertEquals(1, blocked.pendingCounts().getTakers());
            var request = owner.getThreads().send(Objects.requireNonNull(identity.get()), "strict cut");
            var cut = answer.get(10, TimeUnit.SECONDS); assertSame(request, cut.request()); return cut.saved();
        } finally { if (!answer.isDone()) context.close(true); worker.join(5000); assertFalse(worker.isAlive()); }
    }
    // One typed PAP and one direct call cover both cross-backend directions.
    @ParameterizedTest @CsvSource({"true,true,true", "false,false,false"})
    void strictInputsResumeWithoutReplayingTheirPrefixes(boolean ast, boolean pap, boolean compiled) throws Exception {
        try (var context = Context.newBuilder("thc").allowExperimentalOptions(true)
                .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
                .option("engine.Splitting", "false").option("engine.CompilationFailureAction", "Throw").build()) {
            context.initialize("thc"); context.enter();
            Language language; Language.State owner; ExecutableProgram caller; TupleShape shape; RootCallTarget resume;
            var left = new Object(); var right = new Object(); var marker = new Object();
            var entered = new ManagedMVar(); var observed = new ManagedMVar(); var suffix = new ManagedMVar();
            var first = new ManagedMVar(); var second = new ManagedMVar();
            var prefixes = new ManagedMVar[]{new ManagedMVar(), new ManagedMVar()};
            for (var prefix : prefixes) assertTrue(prefix.tryPut(marker));
            Thunk a, b; Closure target;
            try {
                language = TruffleLanguage.LanguageReference.create(Language.class).get(null); owner = Language.currentState();
                caller = ast ? new Program(language, typedStrictModule(true, pap), true) : new BytecodeProgram(language, typedStrictModule(true, pap), true);
                ExecutableProgram maker = ast ? new BytecodeProgram(language, strictThunkModule(), true) : new Program(language, strictThunkModule(), true);
                var callee = new BytecodeProgram(language, typedStrictModule(false, false), false);
                var box1 = (DataValue) Calls.target(maker.entryTarget("make"), new Object[]{0L, prefixes[0], first});
                var box2 = (DataValue) Calls.target(maker.entryTarget("make"), new Object[]{0L, prefixes[1], second});
                a = assertInstanceOf(Thunk.class, box1.getLayout().read(box1, 0));
                b = assertInstanceOf(Thunk.class, box2.getLayout().read(box2, 0));
                target = (Closure) callee.entryValue("run");
                if (pap) target = (Closure) Calls.target(callee.hostEntryTarget(2), new Object[]{target, new Object[]{a, b}});
                shape = ((GuestRoot) caller.entryTarget("run").getRootNode()).getTupleResult();
                resume = new RootNode(language) {
                    @Child private Force force = new Force(new Metrics(false), true);
                    @Override public Object execute(VirtualFrame frame) { return force.execute(frame, frame.getArguments()[0]); }
                }.getCallTarget();
                if (compiled) {
                    var callTarget = caller.entryTarget("run");
                    assertEquals(true, callTarget.getClass().getMethod("compile", boolean.class).invoke(callTarget, true));
                    assertEquals(true, callTarget.getClass().getMethod("isValidLastTier").invoke(callTarget));
                    var runtime = Truffle.getRuntime();
                    runtime.getClass().getMethod("bypassedInstalledCode", Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget")).invoke(runtime, callTarget);
                }
            } finally { context.leave(); }
            Object[] packet = {0L, target, a, b, entered, observed, suffix, 37L, marker};
            var cut = interruptStrict(context, owner, language, first, null, () -> Calls.target(caller.entryTarget("run"), packet));
            assertTrue(entered.isEmpty()); assertTrue(observed.isEmpty()); assertTrue(suffix.isEmpty());
            assertTrue(prefixes[0].isEmpty()); assertFalse(prefixes[1].isEmpty());
            if (compiled) assertTrue(((Number) caller.diagnostics().get("compiledEntries")).longValue() > 0,
                "First installed call reaches the interruptible input");
            var parked = new Thunk(((GuestRoot) cut.getSourceRoot()).getCallTarget(), null);
            parked.setValue(cut.getIdentity()); parked.setState(5);
            interruptStrict(context, owner, language, first, parked, () -> Calls.target(resume, new Object[]{parked}));
            assertTrue(entered.isEmpty()); assertTrue(first.tryPut(left));
            interruptStrict(context, owner, language, second, parked, () -> Calls.target(resume, new Object[]{parked}));
            assertTrue(entered.isEmpty()); assertTrue(observed.isEmpty()); assertTrue(suffix.isEmpty());
            assertTrue(prefixes[0].isEmpty()); assertTrue(prefixes[1].isEmpty()); assertTrue(second.tryPut(right));
            context.enter(); owner.getThreads().enterCurrent(null, false, true, null);
            try {
                SynchronousMasking.set(null, MaskingState.MASKED_INTERRUPTIBLE);
                var value = TupleResults.ownedTupleResult(Calls.target(resume, new Object[]{parked}), shape);
                assertEquals(37L, shape.getLayout().getLong(value, 0)); assertSame(marker, shape.getLayout().getObject(value, 1));
                assertSame(left, entered.tryTake().getValue()); assertSame(right, observed.tryTake().getValue());
                assertSame(marker, suffix.tryTake().getValue());
                assertTrue(entered.isEmpty()); assertTrue(observed.isEmpty()); assertTrue(suffix.isEmpty());
                assertEquals(MaskingState.MASKED_INTERRUPTIBLE, SynchronousMasking.current(null));
                assertSame(StackAnnotationState.EMPTY, StackAnnotations.current(null)); clear(language);
            } finally { owner.getThreads().leaveCurrent(GuestThreadStatus.FINISHED); context.leave(); }
        }
    }
    @ParameterizedTest
    // Compiled tuple transport, interpreted IO/masking, and a caught async payload.
    // The focused stack, tail-call and masking suites own their individual matrices.
    @CsvSource({"false,tuple,true", "true,io,false", "true,caught,false"})
    void alternatingCallsResumeOnAnotherThread(boolean astOuter, String mode, boolean compiled) throws Exception {
        boolean caught = mode.equals("caught"), io = !mode.equals("tuple");
        var prefixes = new ManagedMVar[]{new ManagedMVar(), new ManagedMVar(), new ManagedMVar()};
        var suffixes = new ManagedMVar[]{new ManagedMVar(), new ManagedMVar(), new ManagedMVar()};
        var blocked = new ManagedMVar(); var marker = new Object();
        for (var prefix : prefixes) assertTrue(prefix.tryPut(marker));
        try (var context = Context.newBuilder("thc").allowExperimentalOptions(true)
                .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
                .option("compiler.Inlining", "false")
                .option("engine.Splitting", "false").option("engine.CompilationFailureAction", "Throw").build()) {
            context.initialize("thc"); context.enter();
            Language language; Language.State owner; RootCallTarget resume; TupleShape shape;
            var programs = new ExecutableProgram[3]; var targets = new RootCallTarget[3]; var closures = new Closure[3];
            try {
                language = TruffleLanguage.LanguageReference.create(Language.class).get(null); owner = Language.currentState();
                for (int i = 0; i < 3; i++) {
                    boolean ast = i == 1 ? !astOuter : astOuter;
                    var source = io ? ioModule(i, caught) : module(i);
                    programs[i] = ast ? new Program(language, source, true) : new BytecodeProgram(language, source, true);
                    targets[i] = programs[i].entryTarget("run"); closures[i] = (Closure) programs[i].entryValue("run");
                }
                for (int i = 2; i >= 0; i--) {
                    Object[] prefix = i == 2 ? new Object[]{prefixes[i], suffixes[i]}
                            : new Object[]{closures[i + 1], prefixes[i], suffixes[i]};
                    if (io) { var extended = new ArrayList<>(Arrays.asList(prefix)); extended.add(blocked); extended.add(marker); prefix = extended.toArray(); }
                    closures[i] = closures[i].pap(prefix);
                    assertEquals(0L, programs[i].diagnostics().get("compiledEntries"));
                }
                shape = ((GuestRoot) targets[0].getRootNode()).getTupleResult();
                resume = new RootNode(language) {
                    @Child private Force force = new Force(new Metrics(false), true);
                    @Override public Object execute(VirtualFrame frame) { return force.drainStack((SavedGuestContinuation) frame.getArguments()[0], shape); }
                }.getCallTarget();
                if (compiled) for (var target : targets) {
                    assertEquals(true, target.getClass().getMethod("compile", boolean.class).invoke(target, true));
                    assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target));
                    // Existing test mechanism restores the call-entry stub after explicit compilation;
                    // it executes no guest body and imposes no post-delivery retention policy.
                    var runtime = Truffle.getRuntime();
                    runtime.getClass().getMethod("bypassedInstalledCode", Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget")).invoke(runtime, target);
                }
            } finally { context.leave(); }
            var answer = new CompletableFuture<Object>(); var identity = new AtomicReference<GuestThreadId>();
            var worker = new Thread(() -> {
                context.enter(); owner.getThreads().enterCurrent(null, false, true, null);
                try {
                    identity.set(owner.getThreads().currentIdentity());
                    SynchronousMasking.set(targets[0].getRootNode(), io ? MaskingState.UNMASKED : MaskingState.MASKED_INTERRUPTIBLE);
                    Object output = Calls.target(targets[0], io
                            ? new Object[]{0L, closures[1], prefixes[0], suffixes[0], blocked, marker, Unit.INSTANCE}
                            : new Object[]{0L, closures[1], prefixes[0], suffixes[0], blocked, 37L, marker});
                    if (caught) {
                        output = TupleResults.ownedTupleResult(output, shape);
                        assertSame(marker, shape.getLayout().getObject((HandoffStorage) output, 0), "original lazy async payload reaches the live handler");
                    } else {
                        var cut = saved(output); cut.asyncRequest().acknowledge(); output = cut;
                    }
                    assertEquals(io ? MaskingState.UNMASKED : MaskingState.MASKED_INTERRUPTIBLE, SynchronousMasking.current(targets[0].getRootNode()));
                    assertSame(StackAnnotationState.EMPTY, StackAnnotations.current(targets[0].getRootNode()));
                    clear(language); answer.complete(output);
                } catch (Throwable failure) { answer.completeExceptionally(failure); }
                finally { owner.getThreads().leaveCurrent(GuestThreadStatus.FINISHED); context.leave(); }
            }, "mixed-backend-capture");
            worker.start();
            try {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
                while (blocked.pendingCounts().getTakers() != 1 && !answer.isDone() && System.nanoTime() < deadline) Thread.sleep(1);
                if (answer.isCompletedExceptionally()) answer.get(1, TimeUnit.SECONDS);
                assertEquals(1, blocked.pendingCounts().getTakers());
                for (var prefix : prefixes) assertTrue(prefix.isEmpty(), "every distinct backend prefix has run");
                for (int i = 0; i < 3; i++) assertTrue(suffixes[i].isEmpty(), "suffix waits for the leaf");
                var request = owner.getThreads().send(identity.get(), caught ? marker : "mixed cut");
                Object output = answer.get(15, TimeUnit.SECONDS); worker.join(5000); assertFalse(worker.isAlive());
                if (caught) {
                    assertEquals(AsyncRequestState.ACKNOWLEDGED, request.getState());
                    assertSame(marker, request.getPayload());
                    assertInstanceOf(DataValue.class, suffixes[0].tryTake().getValue());
                    assertTrue(suffixes[1].isEmpty()); assertTrue(suffixes[2].isEmpty());
                    assertEquals(0, blocked.pendingCounts().getTakers());
                    for (var prefix : prefixes) assertTrue(prefix.isEmpty());
                    return;
                }
                var cut = saved(output);
                assertSame(request, cut.asyncRequest()); assertEquals(AsyncRequestState.ACKNOWLEDGED, request.getState());
                if (compiled) for (var program : programs) assertTrue(((Number) program.diagnostics().get("compiledEntries")).longValue() > 0,
                    "First alternating call enters installed guest code");
                var resumed = new CompletableFuture<Object>();
                var resumer = new Thread(() -> {
                    context.enter(); owner.getThreads().enterCurrent(null, false, true, null);
                    try {
                    // drainStack is entered under the logical caller's original mask.
                    SynchronousMasking.set(targets[0].getRootNode(), io ? MaskingState.UNMASKED : MaskingState.MASKED_INTERRUPTIBLE);
                    assertTrue(blocked.tryPut(marker));
                    Object result = Calls.target(resume, new Object[]{cut});
                    {
                        var value = TupleResults.ownedTupleResult(result, shape);
                        if (!io) assertEquals(37L, shape.getLayout().getLong(value, 0));
                        assertSame(marker, shape.getLayout().getObject(value, io ? 0 : 1));
                    }
                    for (var prefix : prefixes) assertTrue(prefix.isEmpty(), "completed prefix cannot replay");
                    for (int i = 0; i < 3; i++) {
                        Object value = suffixes[i].tryTake().getValue();
                        if (!io) assertSame(marker, value, "each saved suffix completes once");
                        else {
                            var observation = assertInstanceOf(DataValue.class, value); var layout = observation.getLayout();
                            assertEquals((i == 0 ? MaskingState.UNMASKED : MaskingState.MASKED_INTERRUPTIBLE).getTag(),
                                    layout.readLong(observation, 0), "actual guest mask after resumed child");
                            var snapshot = assertInstanceOf(ManagedStackSnapshot.class, layout.read(observation, 1));
                            assertEquals(i == 2 ? List.of(marker) : List.of(), snapshot.getAnnotations(), "annotation belongs only to the enclosed leaf");
                            assertSame(marker, layout.read(observation, 2));
                        }
                    }
                    assertTrue(blocked.isEmpty()); assertEquals(io ? MaskingState.UNMASKED : MaskingState.MASKED_INTERRUPTIBLE, SynchronousMasking.current(targets[0].getRootNode()));
                    assertSame(StackAnnotationState.EMPTY, StackAnnotations.current(targets[0].getRootNode()));
                    clear(language);
                    resumed.complete(result);
                    } catch (Throwable failure) { resumed.completeExceptionally(failure); }
                    finally { owner.getThreads().leaveCurrent(GuestThreadStatus.FINISHED); context.leave(); }
                }, "mixed-backend-resume");
                resumer.start();
                try { resumed.get(15, TimeUnit.SECONDS); resumer.join(5000); assertFalse(resumer.isAlive()); }
                finally { if (resumer.isAlive()) context.close(true); resumer.join(5000); }
            } finally { if (worker.isAlive()) context.close(true); worker.join(5000); }
        }
    }
}
