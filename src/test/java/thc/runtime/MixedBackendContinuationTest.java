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

/** Real alternating Closure calls, with an interruptible MVar cut after each prefix. */
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
    private static Map<String, Object> module(int level, boolean tuple, boolean typed, boolean tail) {
        var result = tuple ? PAIR : LONG;
        var params = new ArrayList<Object>();
        if (level < 2) params.add(arg("next", CLOSURE));
        params.add(arg("prefix", CELL)); params.add(arg("suffix", CELL));
        params.add(arg("blocked", CELL));
        if (typed && level > 0) params.add(arg("input", PAIR));
        else { params.add(arg("n", LONG)); params.add(arg("marker", REF)); }
        List<Object> answer;
        if (level == 2) {
            answer = sequence(prim("takeMVar#", list(v("blocked", CELL), state()), READ), "taken", READ,
                    tuple ? pack(v("n", LONG), v("marker", REF)) : v("n", LONG), result);
        } else {
            answer = app(v("next", CLOSURE), typed
                    ? list(v("blocked", CELL), pack(v("n", LONG), v("marker", REF)))
                    : list(v("blocked", CELL), v("n", LONG), v("marker", REF)), result);
        }
        var put = prim("putMVar#", list(v("suffix", CELL), v("marker", REF), state()), VOID);
        var done = sequence(put, "wrote", VOID,
                tuple ? pack(v("number", LONG), v("reference", REF)) : v("answer", LONG), result);
        var body = tuple ? unpack(answer, done, result) : sequence(answer, "answer", LONG, done, result);
        if (tail && level == 1) body = sequence(put, "beforeTail", VOID, answer, result);
        body = sequence(prim("takeMVar#", list(v("prefix", CELL), state()), READ), "prefixRead", READ, body, result);
        if (typed && level > 0) body = list("case", v("input", PAIR), "inputPair",
                list(list("data", "Pair", list("n", "marker"), body,
                        map("binders", list(arg("n", LONG), arg("marker", REF))))),
                map("rep", result, "binder", arg("inputPair", PAIR)));
        return map("instrument", true, "constructors", list(map("id", "Pair", "name", "Pair", "kind", "unboxed-tuple", "arity", 2)),
                "bindings", list(map("id", "run", "name", "run", "lifted", true,
                        "expr", list("lam", params, body, map("resultRep", result)))));
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
    private static Map<String, Object> ioModule(int level, boolean handler, boolean caught) {
        var params = new ArrayList<Object>();
        if (level < 2) params.add(arg("next", CLOSURE));
        params.addAll(list(arg("prefix", CELL), arg("suffix", CELL), arg("blocked", CELL), arg("marker", REF)));
        if (handler && level == 1) params.add(arg("exception", REF));
        params.add(arg("state", VOID));
        var action = level == 2 ? prim("takeMVar#", list(v("blocked", CELL), state()), READ)
                : level == 0 ? prim("maskAsyncExceptions#", list(v("next", CLOSURE), state()), READ)
                : prim("annotateStack#", list(v("marker", REF), v("next", CLOSURE), state()), READ);
        if (handler && level == 0) {
            var raising = list("lam", list(arg("s", VOID)), prim("raiseIO#", list(v("marker", REF), state()), READ), map("rep", CLOSURE, "resultRep", READ));
            action = prim("catch#", list(raising, v("next", CLOSURE), state()), READ);
        }
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
    private static Map<String, Object> regionModule(boolean tuple) {
        var input = module(1, tuple, false, true);
        var constructors = new ArrayList<Object>((List<?>) input.get("constructors"));
        var alternatives = new ArrayList<Object>();
        var binding = (Map<String, Object>) ((List<?>) input.get("bindings")).getFirst();
        var lambda = (List<Object>) binding.get("expr");
        var data = map("kind", "data", "primReps", list("BoxedRep (Just Lifted)"), "evaluated", true);
        for (int i = 0; i < 64; i++) {
            constructors.add(map("id", "C" + i, "name", "C" + i, "arity", 0, "tag", i + 1,
                    "kind", "boxed", "strictFields", list(), "fieldLifted", list(), "fieldReps", list()));
            alternatives.add(list("data", "C" + i, list(), lambda.get(2)));
        }
        input.put("constructors", constructors);
        lambda.set(2, list("case", list("con", "C63", 0, map("rep", data)), "selected", alternatives,
                map("rep", tuple ? PAIR : LONG, "binder", arg("selected", data))));
        return input;
    }
    private static void clear(Language language) {
        var handoff = language.getHandoffState().get(); assertNull(handoff.getPending());
        assertEquals(0, handoff.getArguments().getDepth()); assertEquals(0, handoff.getResults().getDepth());
        assertEquals(0, handoff.getArguments().retainedReferences()); assertEquals(0, handoff.getResults().retainedReferences());
    }

    private static Map<String, Object> typedStrictModule(boolean caller, boolean pap, String mode) {
        boolean scalar = mode.equals("scalar"), tail = mode.equals("tail");
        var result = scalar ? LONG : PAIR;
        var params = caller ? list(arg("fn", CLOSURE), arg("left", REF), arg("right", REF),
                arg("entered", CELL), arg("observed", CELL), arg("suffix", CELL), arg("n", LONG), arg("marker", REF))
            : list(arg("left", REF), arg("right", REF), arg("entered", CELL), arg("observed", CELL), arg("input", PAIR));
        List<Object> body;
        if (caller) {
            var actuals = new ArrayList<Object>();
            if (!pap) actuals.addAll(list(v("left", REF), v("right", REF)));
            actuals.addAll(list(v("entered", CELL), v("observed", CELL), pack(v("n", LONG), v("marker", REF))));
            var call = app(v("fn", CLOSURE), actuals, result);
            body = tail ? call : scalar ? sequence(call, "answer", LONG,
                sequence(prim("putMVar#", list(v("suffix", CELL), v("marker", REF), state()), VOID), "done", VOID,
                    v("answer", LONG), LONG), LONG) : unpack(call,
                sequence(prim("putMVar#", list(v("suffix", CELL), v("marker", REF), state()), VOID), "done", VOID,
                    pack(v("number", LONG), v("reference", REF)), PAIR), PAIR);
        } else body = sequence(prim("putMVar#", list(v("entered", CELL), v("left", REF), state()), VOID), "entry", VOID,
                sequence(prim("putMVar#", list(v("observed", CELL), v("right", REF), state()), VOID), "second", VOID,
                    scalar ? unpack(v("input", PAIR), v("number", LONG), LONG) : v("input", PAIR), result), result);
        var strict = caller ? Collections.nCopies(params.size(), false) : list(true, true, false, false, false);
        return map("instrument", true, "constructors", list(map("id", "Pair", "name", "Pair", "kind", "unboxed-tuple", "arity", 2)),
            "bindings", list(map("id", "run", "name", "run", "lifted", true, "entryStrict", strict,
                "expr", list("lam", params, body, map("resultRep", result, "entryStrict", strict)))));
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
    @ParameterizedTest @CsvSource({"true,false,tuple,false", "true,true,tuple,false", "false,false,tuple,false", "false,true,tuple,false", "true,false,scalar,false", "true,true,scalar,false", "false,false,scalar,false", "false,true,scalar,false", "true,false,tail,false", "true,true,tail,false", "false,false,tail,false", "false,true,tail,false", "true,false,tuple,true", "true,true,tuple,true", "false,false,tuple,true", "false,true,tuple,true", "true,false,bounce,false", "false,false,bounce,false", "true,true,bounce,false", "false,true,bounce,false"})
    void typedStrictInputsKeepTheUnenteredCallAcrossRepeatedCuts(boolean ast, boolean pap, String mode, boolean compiled) throws Exception {
        try (var context = Context.newBuilder("thc").allowExperimentalOptions(true)
                .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
                .option("engine.Splitting", "false").option("engine.CompilationFailureAction", "Throw").build()) {
            context.initialize("thc"); context.enter();
            Language language; Language.State owner; ExecutableProgram caller, maker; TupleShape shape; RootCallTarget resume;
            var targets = new ExecutableProgram[4];
            var bounced = new RootCallTarget[4];
            var leafPrefixes = new ManagedMVar[4]; var leafBlocked = new ManagedMVar[4]; var leafSuffixes = new ManagedMVar[4];
            try {
                language = TruffleLanguage.LanguageReference.create(Language.class).get(null); owner = Language.currentState();
                caller = ast ? new Program(language, typedStrictModule(true, pap, mode), true) : new BytecodeProgram(language, typedStrictModule(true, pap, mode), true);
                maker = ast ? new BytecodeProgram(language, strictThunkModule(), true) : new Program(language, strictThunkModule(), true);
                for (int i = 0; i < targets.length; i++) {
                    targets[i] = new BytecodeProgram(language, typedStrictModule(false, false, mode), false);
                    var root = (BytecodeRoot) targets[i].entryTarget("run").getRootNode();
                    assertFalse(root.isAsyncEnabled()); assertNotNull(root.getTypedInput());
                    assertArrayEquals(new int[]{0, 1}, TypedInputs.strictInputPositions(root, root.getTypedInput()));
                    if (mode.equals("bounce")) {
                        int index = i;
                        leafPrefixes[i] = new ManagedMVar(); leafBlocked[i] = new ManagedMVar(); leafSuffixes[i] = new ManagedMVar();
                        var leaf = new Program(language, module(2, true, false, false), true).entryTarget("run");
                        var wrapper = new GuestRoot(language, new FrameLayout().build()) {
                            @Override public long bloom(VirtualFrame frame) { return 0L; }
                            @Override public Object execute(VirtualFrame frame) {
                                var input = getTypedInput(); var storage = input.take(frame.getArguments()); var layout = input.getPacket();
                                Object[] arguments;
                                try {
                                    assertTrue(((ManagedMVar) layout.getObject(storage, 3)).tryPut(layout.getObject(storage, 1)));
                                    assertTrue(((ManagedMVar) layout.getObject(storage, 4)).tryPut(layout.getObject(storage, 2)));
                                    arguments = new Object[]{0L, leafPrefixes[index], leafSuffixes[index], leafBlocked[index],
                                        layout.getLong(storage, 5), layout.getObject(storage, 6)};
                                } finally { input.release(storage); }
                                throw new TailCall(leaf, arguments);
                            }
                        };
                        wrapper.configureEntry(root.getEntryStrict(), false); wrapper.configureInput(root.getInputLayout());
                        wrapper.configureTypedInput(root.getTypedInput()); wrapper.configureTupleResult(root.getTupleResult());
                        bounced[i] = wrapper.getCallTarget();
                    }
                }
                shape = ((GuestRoot) caller.entryTarget("run").getRootNode()).getTupleResult();
                resume = new RootNode(language) {
                    @Child private Force force = new Force(new Metrics(false), true);
                    @Override public Object execute(VirtualFrame frame) { return force.execute(frame, frame.getArguments()[0]); }
                }.getCallTarget();
                if (compiled) {
                    var target = caller.entryTarget("run");
                    assertEquals(true, target.getClass().getMethod("compile", boolean.class).invoke(target, true));
                    assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target));
                    var runtime = Truffle.getRuntime();
                    runtime.getClass().getMethod("bypassedInstalledCode", Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget")).invoke(runtime, target);
                }
            } finally { context.leave(); }
            for (int i = 0; i < targets.length + (compiled ? 1 : 0); i++) {
                int targetIndex = Math.min(i, targets.length - 1);
                var left = new Object(); var right = new Object(); var marker = new Object();
                var entered = new ManagedMVar(); var observed = new ManagedMVar(); var suffix = new ManagedMVar();
                var first = new ManagedMVar(); var second = new ManagedMVar();
                var prefixes = new ManagedMVar[]{new ManagedMVar(), new ManagedMVar()};
                for (var prefix : prefixes) assertTrue(prefix.tryPut(marker));
                Object a = left, b = right; Closure target;
                long compiledBefore = 0;
                boolean interrupt = i == 0 || i == 3; // Same call site reaches direct and megamorphic dispatch.
                context.enter();
                try {
                    if (compiled && i == targets.length) {
                        // The cold-entry control above does not prove the observed generic path compiles.
                        var callTarget = caller.entryTarget("run");
                        assertEquals(true, callTarget.getClass().getMethod("compile", boolean.class).invoke(callTarget, true));
                        assertEquals(true, callTarget.getClass().getMethod("isValidLastTier").invoke(callTarget));
                        var runtime = Truffle.getRuntime();
                        runtime.getClass().getMethod("bypassedInstalledCode", Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget")).invoke(runtime, callTarget);
                        compiledBefore = (Long) caller.diagnostics().get("compiledEntries");
                    }
                    if (interrupt) {
                        var box1 = (DataValue) Calls.target(maker.entryTarget("make"), new Object[]{0L, prefixes[0], first});
                        var box2 = (DataValue) Calls.target(maker.entryTarget("make"), new Object[]{0L, prefixes[1], second});
                        a = assertInstanceOf(Thunk.class, box1.getLayout().read(box1, 0));
                        b = assertInstanceOf(Thunk.class, box2.getLayout().read(box2, 0));
                    }
                    target = mode.equals("bounce") ? new Closure(null, 5, bounced[i]) : (Closure) targets[targetIndex].entryValue("run");
                    if (mode.equals("bounce")) {
                        assertTrue(leafPrefixes[i].tryPut(marker));
                        if (!interrupt) assertTrue(leafBlocked[i].tryPut(marker));
                    }
                    if (pap) {
                        target = (Closure) Calls.target(targets[targetIndex].hostEntryTarget(2), new Object[]{target, new Object[]{a, b}});
                        assertNotNull(target.typedSupplied);
                    }
                } finally { context.leave(); }
                Object[] packet = {0L, target, a, b, entered, observed, suffix, 37L, marker};
                SavedGuestContinuation cut = null; Thunk parked = null;
                if (interrupt) {
                    cut = interruptStrict(context, owner, language, first, null, () -> Calls.target(caller.entryTarget("run"), packet));
                    assertTrue(entered.isEmpty()); assertTrue(observed.isEmpty()); assertTrue(suffix.isEmpty());
                    assertTrue(prefixes[0].isEmpty()); assertFalse(prefixes[1].isEmpty());
                    if (compiled && i == 0) assertEquals(1L, caller.diagnostics().get("compiledEntries"), "first entry ran installed code before the cut");
                    // Preserve acknowledged cuts, rather than drainStack's deliberate delivery through saved handlers.
                    parked = new Thunk(((GuestRoot) cut.getSourceRoot()).getCallTarget(), null);
                    parked.setValue(cut.getIdentity()); parked.setState(5);
                    var current = parked;
                    cut = interruptStrict(context, owner, language, first, current, () -> Calls.target(resume, new Object[]{current}));
                    assertTrue(entered.isEmpty()); assertTrue(first.tryPut(left));
                    cut = interruptStrict(context, owner, language, second, current, () -> Calls.target(resume, new Object[]{current}));
                    assertTrue(entered.isEmpty()); assertTrue(observed.isEmpty()); assertTrue(suffix.isEmpty());
                    assertTrue(prefixes[0].isEmpty()); assertTrue(prefixes[1].isEmpty()); assertTrue(second.tryPut(right));
                    if (mode.equals("bounce")) {
                        cut = interruptStrict(context, owner, language, leafBlocked[i], current, () -> Calls.target(resume, new Object[]{current}));
                        assertFalse(entered.isEmpty()); assertFalse(observed.isEmpty()); assertTrue(suffix.isEmpty());
                        assertTrue(leafPrefixes[i].isEmpty()); assertTrue(leafSuffixes[i].isEmpty());
                        assertTrue(leafBlocked[i].tryPut(marker));
                    }
                }
                context.enter(); owner.getThreads().enterCurrent(null, false, true, null);
                try {
                    SynchronousMasking.set(null, MaskingState.MASKED_INTERRUPTIBLE);
                    Object output = cut == null ? Calls.target(caller.entryTarget("run"), packet) : Calls.target(resume, new Object[]{parked});
                    if (compiled && i == targets.length) assertEquals(compiledBefore + 1, caller.diagnostics().get("compiledEntries"));
                    if (mode.equals("scalar")) assertEquals(37L, output);
                    else {
                        var value = TupleResults.ownedTupleResult(output, shape);
                        assertEquals(37L, shape.getLayout().getLong(value, 0)); assertSame(marker, shape.getLayout().getObject(value, 1));
                    }
                    assertSame(left, entered.tryTake().getValue()); assertSame(right, observed.tryTake().getValue());
                    if (!mode.equals("tail")) assertSame(marker, suffix.tryTake().getValue());
                    if (mode.equals("bounce")) { assertTrue(leafPrefixes[i].isEmpty()); assertSame(marker, leafSuffixes[i].tryTake().getValue()); }
                    assertTrue(entered.isEmpty()); assertTrue(observed.isEmpty()); assertTrue(suffix.isEmpty());
                    if (interrupt) { assertEquals(2, ((Thunk) a).getState()); assertEquals(2, ((Thunk) b).getState()); }
                    assertEquals(MaskingState.MASKED_INTERRUPTIBLE, SynchronousMasking.current(null));
                    assertSame(StackAnnotationState.EMPTY, StackAnnotations.current(null)); clear(language);
                } finally { owner.getThreads().leaveCurrent(GuestThreadStatus.FINISHED); context.leave(); }
            }
        }
    }
    @ParameterizedTest
    @CsvSource({"false,scalar,false", "true,scalar,false", "false,tuple,false", "true,tuple,false",
                "false,scalar,true", "true,scalar,true", "false,tuple,true", "true,tuple,true",
                "false,typed,false", "true,typed,false", "false,typed,true", "true,typed,true",
                "false,tail,false", "true,tail,false", "false,tail,true", "true,tail,true",
                "false,io,false", "true,io,false", "false,io,true", "true,io,true",
                "false,handler,false", "true,handler,false", "false,handler,true", "true,handler,true",
                "true,region,false", "true,region,true", "true,regionScalar,false", "true,regionScalar,true",
                "false,caught,false", "true,caught,false", "false,caught,true", "true,caught,true"})
    void alternatingCallsResumeOnAnotherThread(boolean astOuter, String mode, boolean compiled) throws Exception {
        boolean region = mode.startsWith("region"), handler = mode.equals("handler"), caught = mode.equals("caught");
        boolean tuple = !mode.equals("scalar") && !mode.equals("regionScalar"), typed = mode.equals("typed"),
                tail = mode.equals("tail") || region, io = mode.equals("io") || handler || caught;
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
                    var source = io ? ioModule(i, handler, caught) : region && i == 1 ? regionModule(tuple) : module(i, tuple, typed, tail);
                    programs[i] = ast ? new Program(language, source, true) : new BytecodeProgram(language, source, true);
                    targets[i] = programs[i].entryTarget("run"); closures[i] = (Closure) programs[i].entryValue("run");
                    assertEquals(ast, targets[i].getRootNode() instanceof FunctionRoot);
                    if (region && i == 1) assertEquals(1, ((BytecodeRoot) targets[i].getRootNode()).prepareGraphBudgetRetry(0),
                            "explicit recovered-side transport control, not a simulated compiler failure");
                }
                for (int i = 2; i >= 0; i--) {
                    Object[] prefix = i == 2 ? new Object[]{prefixes[i], suffixes[i]}
                            : new Object[]{closures[i + 1], prefixes[i], suffixes[i]};
                    if (io) { var extended = new ArrayList<>(Arrays.asList(prefix)); extended.add(blocked); extended.add(marker); prefix = extended.toArray(); }
                    if (typed && i > 0) {
                        assertNotNull(((GuestRoot) targets[i].getRootNode()).getTypedInput());
                        closures[i] = (Closure) Calls.target(programs[i].hostEntryTarget(prefix.length), new Object[]{closures[i], prefix});
                        assertNotNull(closures[i].typedSupplied, "actual typed PAP prefix, not an Object[] surrogate");
                    } else closures[i] = closures[i].pap(prefix);
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
                for (int i = 0; i < 3; i++) assertEquals(!(tail && i == 1), suffixes[i].isEmpty(), "only an explicit tail prefix runs before the leaf");
                var request = owner.getThreads().send(identity.get(), caught ? marker : "mixed cut");
                Object output = answer.get(15, TimeUnit.SECONDS); worker.join(5000); assertFalse(worker.isAlive());
                if (caught) {
                    assertEquals(AsyncRequestState.ACKNOWLEDGED, request.getState());
                    assertSame(marker, request.getPayload());
                    assertInstanceOf(DataValue.class, suffixes[0].tryTake().getValue());
                    assertTrue(suffixes[1].isEmpty()); assertTrue(suffixes[2].isEmpty());
                    assertEquals(0, blocked.pendingCounts().getTakers());
                    for (var prefix : prefixes) assertTrue(prefix.isEmpty());
                    if (compiled) for (var program : programs) assertEquals(1L, program.diagnostics().get("compiledEntries"));
                    return;
                }
                var cut = saved(output);
                assertSame(request, cut.asyncRequest()); assertEquals(AsyncRequestState.ACKNOWLEDGED, request.getState());
                var segments = new ArrayList<CallSegment>();
                var cursor = cut;
                for (int i = 0; i < 3; i++) {
                    if (tail && !region && astOuter && i == 1) continue; // Bytecode exact tail forwards its actual AST callee witness.
                    assertSame(targets[i].getRootNode(), cursor.getSourceRoot(), "original source at each alternating edge");
                    assertSame(request, cursor.asyncRequest());
                    if (i < 2) {
                        var suspended = assertInstanceOf(CallSegmentSuspended.class, cursor.getYielded());
                        var segment = suspended.getSegment(); segments.add(segment);
                        assertEquals(5, segment.getState()); assertNull(segment.getOwner());
                        assertEquals(MaskingState.MASKED_INTERRUPTIBLE, segment.getLogicalMask());
                        assertEquals(MaskingState.MASKED_INTERRUPTIBLE, segment.getCallerMask());
                        cursor = saved(segment.getValue()); assertSame(cursor.getIdentity(), segment.getValue());
                    } else assertSame(request, cursor.getYielded());
                }
                if (compiled) for (var program : programs) assertEquals(1L, program.diagnostics().get("compiledEntries"), "each alternating root really entered installed code");
                var resumed = new CompletableFuture<Object>();
                var resumer = new Thread(() -> {
                    context.enter(); owner.getThreads().enterCurrent(null, false, true, null);
                    try {
                    // drainStack is entered under the logical caller's original mask.
                    SynchronousMasking.set(targets[0].getRootNode(), io ? MaskingState.UNMASKED : MaskingState.MASKED_INTERRUPTIBLE);
                    assertTrue(blocked.tryPut(marker));
                    Object result = Calls.target(resume, new Object[]{cut});
                    if (tuple) {
                        var value = TupleResults.ownedTupleResult(result, shape);
                        if (!io) assertEquals(37L, shape.getLayout().getLong(value, 0));
                        assertSame(marker, shape.getLayout().getObject(value, io ? 0 : 1));
                    } else assertEquals(37L, result);
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
                    for (var segment : segments) { assertEquals(2, segment.getState()); assertNull(segment.getOwner()); }
                    assertTrue(blocked.isEmpty()); assertEquals(io ? MaskingState.UNMASKED : MaskingState.MASKED_INTERRUPTIBLE, SynchronousMasking.current(targets[0].getRootNode()));
                    assertSame(StackAnnotationState.EMPTY, StackAnnotations.current(targets[0].getRootNode()));
                    clear(language);
                    for (int i = 0; i < 3; i++) assertSame(targets[i], closures[i].target);
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
