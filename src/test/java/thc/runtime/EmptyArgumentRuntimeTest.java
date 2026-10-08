// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.VirtualFrame;
import java.util.*;
import org.junit.jupiter.api.Test;
import thc.Language;
import static org.junit.jupiter.api.Assertions.*;
import static thc.Main.executionContext;
import static thc.runtime.ScalarValueTestSupport.*;

/** Fixture-free typed Core tests for logical empty inputs and lazy tuple results.
 * Inputs are constructed modules; outputs are values, effects and released loans. */
class EmptyArgumentRuntimeTest {
    private final Map<String, Object> integer = map("kind", "long", "primReps", list("IntRep"), "evaluated", true);
    private final Map<String, Object> closure = map("kind", "closure", "primReps", list("BoxedRep (Just Lifted)"), "evaluated", true);
    private final Map<String, Object> reference = map("kind", "object", "primReps", list("BoxedRep (Just Lifted)"), "evaluated", false);
    private final Map<String, Object> empty = map("kind", "unknown", "primReps", list(), "evaluated", true, "aggregate", "unboxed-tuple", "components", list());
    private List<Object> v(String id) { return v(id, integer); }
    private List<Object> v(String id, Map<String, Object> rep) { return list("var", id, map("rep", rep)); }
    private List<Object> n(long value) { return list("lit", "int", Long.toString(value), map("rep", integer)); }
    private List<Object> zero() { return list("con", "E", 0, map("rep", empty)); }
    private Map<String, Object> arg(String id) { return arg(id, integer); }
    private Map<String, Object> arg(String id, Map<String, Object> rep) {
        return map("id", id, "name", id, "lifted", rep.equals(closure) || rep.equals(reference), "rep", rep);
    }
    private List<Object> app(List<Object> fn, List<List<Object>> args) { return app(fn, args, integer); }
    private List<Object> app(List<Object> fn, List<List<Object>> args, Map<String, Object> rep) { return app(fn, args, rep, Collections.nCopies(args.size(), false)); }
    private List<Object> app(List<Object> fn, List<List<Object>> args, Map<String, Object> rep, List<Boolean> flags) {
        return list("app", fn, args, flags, false, false, map("rep", rep));
    }
    private List<Object> call(String id, List<List<Object>> args) { return call(id, args, integer); }
    private List<Object> call(String id, List<List<Object>> args, Map<String, Object> rep) { return app(v(id, closure), args, rep); }
    private List<Object> call(String id, List<List<Object>> args, Map<String, Object> rep, List<Boolean> flags) { return app(v(id, closure), args, rep, flags); }
    @SafeVarargs private final List<Object> prim(String id, List<Object>... args) { return app(list("prim", id), list(args)); }
    private List<Object> lam(List<Map<String, Object>> args, List<Object> body) { return lam(args, body, integer); }
    private List<Object> lam(List<Map<String, Object>> args, List<Object> body, Map<String, Object> result) { return lam(args, body, result, Collections.nCopies(args.size(), false)); }
    private List<Object> lam(List<Map<String, Object>> args, List<Object> body, Map<String, Object> result, List<Boolean> strict) {
        return list("lam", args, body, map("rep", closure, "resultRep", result, "entryStrict", strict));
    }
    private Map<String, Object> bind(String id, List<Object> expr) { return map("id", id, "name", id, "lifted", true, "rep", closure, "expr", expr); }
    @SafeVarargs private final Map<String, Object> module(Map<String, Object>... bindings) { return module(list(bindings)); }
    private Map<String, Object> module(List<Map<String, Object>> bindings) {
        return map("bindings", bindings, "instrument", true, "constructors", list(
            map("id", "E", "name", "E", "kind", "unboxed-tuple", "arity", 0), map("id", "T", "name", "T", "kind", "unboxed-tuple", "arity", 2)));
    }
    private static ExecutableProgram program(Language language, String backend, Map<String, Object> data) {
        return backend.equals("ast") ? new Program(language, data) : new BytecodeProgram(language, data);
    }
    private static Object run(ExecutableProgram p, String name, Object... args) { return Calls.target(p.hostEntryTarget(args.length), new Object[]{p.entryValue(name), args}); }
    private static void withLanguage(CheckedConsumer<Language> action) throws Exception {
        try (var context = executionContext()) {
            context.initialize("thc"); context.enter();
            try { action.accept(TruffleLanguage.LanguageReference.create(Language.class).get(null)); } finally { context.leave(); }
        }
    }
    private static void compile(RootCallTarget target) throws Exception { target.getClass().getMethod("compile", boolean.class).invoke(target, true); target.getClass().getMethod("waitForCompilation").invoke(target); valid(target); }
    private static void valid(RootCallTarget target) throws Exception { assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target)); }
    private static void released(Language language) {
        var state = language.getHandoffState().get();
        assertEquals(0, state.getArguments().getDepth()); assertEquals(0, state.getArguments().retainedReferences()); assertNull(state.getPending());
        assertEquals(0, state.getResults().getDepth()); assertEquals(0, state.getResults().retainedReferences());
    }
    @Test void emptyOnlyPapPrefixesAndOverapplicationKeepLogicalArityWithoutPayloadSlots() throws Exception {
        withLanguage(language -> {
            var parameters = list(arg("u", empty), arg("x"), arg("v", empty), arg("y"), arg("w", empty));
            var worker = bind("worker", lam(parameters, prim("+#", v("x"), v("y"))));
            var data = module(worker,
                bind("prefix", lam(list(arg("x")), call("worker", list(zero(), v("x")), closure), closure)),
                bind("zeroPrefix", lam(list(arg("ignored")), call("worker", list(zero()), closure), closure)),
                bind("exact", lam(list(arg("x")), call("worker", list(zero(), v("x"), zero(), n(17), zero())))),
                bind("pap", lam(list(arg("x")), app(call("worker", list(zero(), v("x")), closure), list(zero(), n(17), zero())))),
                bind("make", lam(list(arg("u", empty), arg("x")), lam(list(arg("v", empty), arg("y"), arg("w", empty)), prim("+#", v("x"), v("y"))), closure)),
                bind("over", lam(list(arg("x")), call("make", list(zero(), v("x"), zero(), n(17), zero())))));
            for (var backend : list("ast", "bytecode")) {
                var p = program(language, backend, data); var prefix = (Closure) run(p, "prefix", 31L);
                assertEquals(2, prefix.suppliedCount); assertEquals(3, prefix.arity); assertArrayEquals(new Object[]{31L}, prefix.supplied);
                var noPayload = (Closure) run(p, "zeroPrefix", 0L);
                assertEquals(1, noPayload.suppliedCount); assertEquals(4, noPayload.arity); assertEquals(0, noPayload.supplied.length);
                var root = (GuestRoot) p.entryTarget("worker").getRootNode();
                assertEquals(5, root.getInputLayout().getLogicalArity()); assertEquals(2, root.getInputLayout().getPhysicalArity());
                if (root instanceof FunctionRoot function && function.getHandoff() != null)
                    assertEquals(list("long", "long", "long"), function.getHandoff().getArguments().getReps());
                for (var name : list("exact", "pap", "over")) {
                    for (long i = 0; i < 10; i++) assertEquals(i + 17L, run(p, name, i));
                    compile(p.entryTarget(name));
                    for (long x : new long[]{Long.MIN_VALUE, -4097L, 0L, Long.MAX_VALUE}) {
                        long before = (Long) p.diagnostics().get("compiledEntries");
                        assertEquals(x + 17L, run(p, name, x), backend + "/" + name + "/" + x);
                        assertTrue((Long) p.diagnostics().get("compiledEntries") > before); valid(p.entryTarget(name)); released(language);
                    }
                }
            }
        });
    }
    private static final class EffectRoot extends GuestRoot {
        private final List<Long> events; private final TupleShape empty;
        EffectRoot(Language language, List<Long> events, TupleShape empty) {
            super(language, new FrameLayout().build()); this.events = events; this.empty = empty;
            configureEntry(new boolean[]{false}, false); configureTupleResult(empty);
        }
        @Override public long bloom(VirtualFrame frame) { return 0L; }
        @Override public Object execute(VirtualFrame frame) {
            long value = (Long) frame.getArguments()[1]; events.add(empty == null ? value + 100L : value);
            if (value < 0) throw new GuestException(value, this);
            return empty == null ? value : empty.finish(frame, ArgumentLayout.EMPTY_TUPLE_SLOTS);
        }
    }
    @Test void emptyComputationsRunBeforeLaterOperandsAndPapPublicationAndReleaseResultsOnThrow() throws Exception {
        withLanguage(language -> {
            var data = module(bind("worker", lam(list(arg("u", empty), arg("x")), v("x"))),
                bind("entry", lam(list(arg("effect", closure), arg("later", closure), arg("x")),
                    call("worker", list(call("effect", list(v("x")), empty), call("later", list(v("x"))))))),
                bind("pap", lam(list(arg("effect", closure), arg("x")), call("worker", list(call("effect", list(v("x")), empty)), closure), closure)));
            for (var backend : list("ast", "bytecode")) {
                var p = program(language, backend, data); var events = new ArrayList<Long>();
                var effect = new Closure(null, 1, new EffectRoot(language, events, new TupleShape(CoreRepresentations.parse(empty), language)).getCallTarget());
                var later = new Closure(null, 1, new EffectRoot(language, events, null).getCallTarget());
                assertEquals(7L, run(p, "entry", effect, later, 7L)); assertEquals(list(7L, 107L), events); released(language);
                events.clear(); var pap = (Closure) run(p, "pap", effect, 11L);
                assertEquals(list(11L), events); assertEquals(1, pap.suppliedCount); assertEquals(0, pap.supplied.length); released(language);
                events.clear(); var allocations = p.diagnostics().get("papAllocations");
                assertThrows(GuestException.class, () -> run(p, "pap", effect, -3L));
                assertEquals(list(-3L), events); assertEquals(allocations, p.diagnostics().get("papAllocations")); released(language);
                events.clear(); assertThrows(GuestException.class, () -> run(p, "entry", effect, later, -5L));
                assertEquals(list(-5L), events); released(language);
                assertEquals(13L, run(p, "entry", effect, later, 13L)); released(language);
            }
        });
    }
    private Map<String, Object> tailWorker(String id, String next, boolean middle, boolean nextMiddle, long increment) {
        var parameters = middle ? list(arg("n"), arg("u", empty), arg("acc")) : list(arg("u", empty), arg("n"), arg("acc"));
        var remaining = prim("-#", v("n"), n(1)); var accumulator = prim("+#", v("acc"), n(increment));
        var arguments = nextMiddle ? list(remaining, v("u", empty), accumulator) : list(v("u", empty), remaining, accumulator);
        return bind(id, lam(parameters, list("case", prim("<=#", v("n"), n(0)), "condition", list(
            list("lit", list("int", "1"), list(), v("acc")),
            list("default", null, list(), call(next, arguments))), map("rep", integer, "binder", arg("condition")))));
    }
    @Test void selfAndMutualTailCallsForwardUsedEmptyFormalsWithoutHostStackGrowth() throws Exception {
        withLanguage(language -> {
            // A and B move the empty logical argument while carrying two independent scalar payloads.
            var data = module(tailWorker("self", "self", false, false, 3), tailWorker("a", "b", false, true, 2), tailWorker("b", "a", true, false, 5),
                bind("entry", lam(list(arg("n"), arg("x")), prim("+#",
                    call("self", list(zero(), v("n"), v("x"))), call("a", list(zero(), v("n"), v("x")))))));
            for (var backend : list("ast", "bytecode")) {
                var p = program(language, backend, data);
                for (long count : new long[]{0L, 1L, 32L, 20_000L}) {
                    assertEquals(6_000_000_000L + 3 * count + 7 * (count / 2) + 2 * (count % 2), run(p, "entry", count, 3_000_000_000L), backend);
                    released(language);
                }
                compile(p.entryTarget("entry")); long before = (Long) p.diagnostics().get("compiledEntries");
                assertEquals(-6_000_000_000L + 3 * 20_001L + 7 * 10_000L + 2, run(p, "entry", 20_001L, -3_000_000_000L));
                assertTrue((Long) p.diagnostics().get("compiledEntries") > before, "First installed call carries the independent accumulator");
                valid(p.entryTarget("entry")); released(language);
                assertTrue((Long) p.diagnostics().get("selfTailReentries") > 0);
            }
        });
    }
    @Test void ignoredScalarStateTupleFieldExecutesBeforeLaterWorkAndRejectsInvalidCarrier() throws Exception {
        withLanguage(language -> {
            var state = map("kind", "void", "primReps", list(), "evaluated", true);
            var pair = with(empty, "components", list(state, integer), "primReps", list("IntRep"));
            var parameters = list(arg("effect", closure), arg("later", closure), arg("x"));
            // An ignored scalar State# field still executes its ordinary producer before later fields.
            // This synthetic module does not qualify GHC export of arbitrary State# producers.
            var produceState = bind("produceState", lam(list(arg("effect", closure), arg("x")), call("effect", list(v("x")), state), state));
            var laterField = call("later", list(v("x")));
            var producePair = bind("producePair", lam(parameters, app(list("con", "T", 2), list(
                call("produceState", list(v("effect", closure), v("x")), state, list(true, false)), laterField), pair), pair));
            var body = list("case", call("producePair", list(v("effect", closure), v("later", closure), v("x")), pair, list(true, true, false)),
                "answer", list(list("data", "T", list("ignored", "value"), v("value"), map("binders", list(arg("ignored", state), arg("value"))))),
                map("rep", integer, "binder", arg("answer", pair)));
            var entry = bind("entry", lam(parameters, body));
            for (var backend : list("ast", "bytecode")) {
                var p = program(language, backend, module(produceState, producePair, entry)); var events = new ArrayList<Long>();
                var effect = new Closure(null, 1, new GuestRoot(language, new FrameLayout().build()) {
                    { configureEntry(new boolean[]{false}, false); }
                    @Override public long bloom(VirtualFrame frame) { return 0L; }
                    @Override public Object execute(VirtualFrame frame) {
                        long x = (Long) frame.getArguments()[1]; events.add(x);
                        if (x < 0) throw new GuestException(x, this);
                        return Unit.INSTANCE;
                    }
                }.getCallTarget());
                var later = new Closure(null, 1, new EffectRoot(language, events, null).getCallTarget());
                assertEquals(7L, run(p, "entry", effect, later, 7L)); assertEquals(list(7L, 107L), events); released(language);
                compile(p.entryTarget("entry")); events.clear(); long before = (Long) p.diagnostics().get("compiledEntries");
                assertEquals(3_000_000_000L, run(p, "entry", effect, later, 3_000_000_000L));
                assertEquals(list(3_000_000_000L, 3_000_000_100L), events);
                assertTrue((Long) p.diagnostics().get("compiledEntries") > before); valid(p.entryTarget("entry")); released(language);
                events.clear(); var failure = assertThrows(GuestException.class, () -> run(p, "entry", effect, later, -7L));
                assertEquals(-7L, failure.getPayload()); assertEquals(list(-7L), events); released(language);
                events.clear(); assertEquals(19L, run(p, "entry", effect, later, 19L)); assertEquals(list(19L, 119L), events); released(language);
                // A legacy operand without representation metadata still has to return the scalar Unit carrier.
                // Malformed-carrier validation may follow operand materialization.
                var invalid = bind("producePair", lam(parameters, app(list("con", "T", 2), list(list("lit", "int", "123"), laterField), pair), pair));
                var bad = program(language, backend, module(invalid, entry)); events.clear();
                var wrongCarrier = assertThrows(RuntimeFault.class, () -> run(bad, "entry", effect, later, 23L));
                assertTrue(Objects.toString(wrongCarrier.getMessage(), "").contains("zero-width scalar carrier"), wrongCarrier.getMessage());
                released(language);
            }
        });
    }
    @Test void dynamicMasksRejectScalarStateAndBoxedUnitWithoutInventingEmptyProofs() throws Exception {
        withLanguage(language -> {
            var state = map("kind", "void", "primReps", list(), "evaluated", true);
            var data = module(bind("empty", lam(list(arg("u", empty)), n(3))), bind("state", lam(list(arg("s", state)), n(5))),
                bind("boxed", lam(list(arg("p", reference)), n(7))), bind("emptyCaller", lam(list(arg("f", closure)), call("f", list(zero())))),
                bind("stateCaller", lam(list(arg("f", closure)), call("f", list(list("void", map("rep", state)))))));
            for (var backend : list("ast", "bytecode")) {
                var p = program(language, backend, data);
                assertEquals(3L, run(p, "emptyCaller", p.entryValue("empty"))); assertEquals(5L, run(p, "stateCaller", p.entryValue("state")));
                for (var target : list("state", "boxed")) assertThrows(RuntimeFault.class, () -> run(p, "emptyCaller", p.entryValue(target)));
                assertThrows(RuntimeFault.class, () -> run(p, "stateCaller", p.entryValue("empty"))); released(language);
            }
        });
    }
    @Test void mixedTargetPrefixesAndOverapplicationUsePhysicalSlicesAfterThePicBecomesGeneric() throws Exception {
        withLanguage(language -> {
            var bindings = new ArrayList<Map<String, Object>>();
            for (int i = 0; i <= 4; i++) {
                var prefix = new ArrayList<>(i % 2 == 0 ? list(arg("prefixEmpty", empty), arg("prefix")) : list(arg("prefix")));
                var prefixValues = i % 2 == 0 ? list(zero(), n(i * 100L)) : list(n(i * 100L));
                prefix.addAll(list(arg("u", empty), arg("x")));
                bindings.add(bind("worker" + i, lam(prefix, lam(list(arg("v", empty), arg("y")), prim("+#", prim("+#", v("prefix"), v("x")), v("y"))), closure)));
                bindings.add(bind("make" + i, lam(list(arg("ignored")), call("worker" + i, prefixValues, closure), closure)));
            }
            bindings.add(bind("apply", lam(list(arg("f", closure), arg("x")), call("f", list(zero(), v("x"), zero(), n(17))))));
            for (var backend : list("ast", "bytecode")) {
                var p = program(language, backend, module(bindings)); var functions = new ArrayList<Closure>();
                for (int i = 0; i <= 4; i++) functions.add((Closure) run(p, "make" + i, 0L));
                for (int repeat = 0; repeat < 10; repeat++) for (int i = 0; i < functions.size(); i++) assertEquals(i * 100L + 24L, run(p, "apply", functions.get(i), 7L));
                compile(p.entryTarget("apply"));
                for (long x : new long[]{Long.MIN_VALUE, 0L, Long.MAX_VALUE}) for (int i = 0; i < functions.size(); i++) {
                    long before = (Long) p.diagnostics().get("compiledEntries");
                    assertEquals(x + i * 100L + 17L, run(p, "apply", functions.get(i), x), backend);
                    assertTrue((Long) p.diagnostics().get("compiledEntries") > before);
                    assertEquals(true, p.entryTarget("apply").getClass().getMethod("isValidLastTier").invoke(p.entryTarget("apply")), backend + "/" + x + "/target" + i);
                    released(language);
                }
                assertTrue((Long) p.diagnostics().get("indirectCalls") > 0);
            }
        });
    }
    private Map<String, Object> aliases(Map<String, Object> rep) {
        return module(bind("entry", lam(list(arg("x")), list("let", true, list(bind("alias", v("f", closure)),
            bind("f", lam(list(arg("a", rep)), n(9)))), call("alias", list(zero())), map("rep", integer)))));
    }
    @Test void rawLiftedEmptyFlagCannotBeHiddenByStrictDemandAndRecursiveAliasesKeepInputProofs() throws Exception {
        withLanguage(language -> {
            var state = map("kind", "void", "primReps", list(), "evaluated", true);
            for (var backend : list("ast", "bytecode")) {
                var worker = bind("worker", lam(list(arg("e", empty)), n(3), integer, list(true)));
                var forged = module(worker, bind("entry", lam(list(arg("x")), call("worker", list(zero()), integer, list(true)))));
                assertThrows(RuntimeFault.class, () -> program(language, backend, forged));
                var correct = module(worker, bind("entry", lam(list(arg("x")), call("worker", list(zero())))));
                assertEquals(3L, run(program(language, backend, correct), "entry", 0L));
                var missingProof = module(worker, bind("entry", lam(list(arg("x")), call("worker", list(list("lit", "int", "1"))))));
                assertThrows(RuntimeFault.class, () -> program(language, backend, missingProof));
                var lexicalProof = module(worker, bind("forward", lam(list(arg("e", empty)), call("worker", list(list("var", "e"))))),
                    bind("entry", lam(list(arg("x")), call("forward", list(zero())))));
                assertEquals(3L, run(program(language, backend, lexicalProof), "entry", 0L));
                assertThrows(RuntimeFault.class, () -> program(language, backend, aliases(state)));
                assertEquals(9L, run(program(language, backend, aliases(empty)), "entry", 0L));
            }
        });
    }
    @Test void exactEmptyInputsRemainDistinctFromStateContractsAndSupportedNestedZeroWidthTuples() throws Exception {
        withLanguage(language -> {
            var state = map("kind", "void", "primReps", list(), "evaluated", true);
            var nested = with(empty, "components", list(empty)); var singletonState = with(empty, "components", list(state));
            for (var backend : list("ast", "bytecode")) {
                for (var supported : list(nested, singletonState)) {
                    var worker = bind("worker", lam(list(arg("u", supported)), n(0))); var p = program(language, backend, module(worker));
                    var layout = ((GuestRoot) p.entryTarget("worker").getRootNode()).getInputLayout();
                    assertEquals(1, layout.getLogicalArity()); assertEquals(0, layout.getPhysicalArity());
                    assertFalse(layout.isEmpty(0)); assertTrue(layout.getRequiresTyped());
                    // Equal zero payload widths do not make these logical shapes (# #).
                    assertThrows(RuntimeFault.class, () -> program(language, backend, module(worker, bind("entry", lam(list(), call("worker", list(zero())))))));
                }
                assertThrows(RuntimeFault.class, () -> program(language, backend, module(bind("worker", lam(list(arg("u", with(empty, "components", null))), n(0))))));
                // Statically contradictory formals, including a known PAP prefix.
                var stateWorker = bind("worker", lam(list(arg("x"), arg("s", state)), n(0)));
                for (var fn : list(v("worker", closure), call("worker", list(n(3)), closure))) {
                    var args = fn.getFirst().equals("var") ? list(n(3), zero()) : list(zero());
                    assertThrows(RuntimeFault.class, () -> program(language, backend, module(stateWorker, bind("entry", lam(list(), app(fn, args))))));
                }
                assertThrows(RuntimeFault.class, () -> program(language, backend, module(bind("entry", lam(list(), prim("+#", zero(), n(1)))))));
                // A join keeps the empty logical slot even though it has no payload.
                var join = with(bind("finish", lam(list(arg("u", empty), arg("n")), v("n"))),
                    "joinValueArity", 2, "joinResultRep", integer);
                for (var invalid : list(call("finish", list(n(3))),
                        call("finish", list(list("void", map("rep", state)), n(3))))) {
                    var region = list("let", false, list(join), invalid, map("rep", integer));
                    assertThrows(RuntimeFault.class, () -> program(language, backend, module(bind("entry", lam(list(), region)))).entryValue("entry"));
                }
                var boxed = v("payload", reference);
                var newResult = with(empty, "components", list(state, map("kind", "object", "evaluated", true, "primReps", list("BoxedRep (Just Unlifted)"))), "primReps", list("BoxedRep (Just Unlifted)"));
                var invalidNew = app(list("prim", "newMutVar#"), list(boxed, zero()), newResult, list(true, false));
                assertThrows(RuntimeFault.class, () -> program(language, backend, module(bind("entry", lam(list(arg("payload", reference)), invalidNew, newResult)))));
            }
        });
    }
    @Test void tupleResultJoinsCaptureEvaluatedAliasesAndRespectRecursiveShadowing() throws Exception {
        withLanguage(language -> {
            var pair = with(empty, "components", list(integer, reference), "primReps", list("IntRep", "BoxedRep (Just Lifted)"));
            var packed = app(list("con", "T", 2), list(prim("+#", v("x"), n(11)), v("ref", reference)), pair, list(false, true));
            var poison = new Thunk(new com.oracle.truffle.api.nodes.RootNode(language) {
                @Override public Object execute(VirtualFrame frame) { throw new AssertionError("Tuple join forced its lazy leaf"); }
            }.getCallTarget(), null);
            for (var backend : list("ast", "bytecode")) for (boolean capture : new boolean[]{true, false}) {
                var returned = app(list("con", "T", 2), list(v("value"), v("ref", reference)), pair, list(false, true));
                var recursive = list("case", prim("<=#", v("remaining"), n(0)), "done", list(
                    list("lit", list("int", "1"), list(), returned, map("binders", list())),
                    list("default", null, list(), call("held", list(prim("-#", v("remaining"), n(1)),
                        prim("+#", v("value"), n(3))), pair), map("binders", list()))), map("rep", pair, "binder", arg("done")));
                var join = capture
                    ? with(arg("finish", pair), "expr", v("held", pair), "joinValueArity", 0, "joinResultRep", pair)
                    : with(bind("held", lam(list(arg("remaining"), arg("value")), recursive, pair)),
                        "joinValueArity", 2, "joinResultRep", pair);
                var region = list("let", !capture, list(join), capture ? v("finish", pair) : call("held", list(n(3), v("x")), pair), map("rep", pair));
                // The zero-arity join reads the evaluated outer alias. The recursive
                // join has the same source name as that alias and must resolve locally.
                var result = list("case", packed, "held", list(list("default", null, list(), region, map("binders", list()))),
                    map("rep", pair, "binder", arg("held", pair)));
                var body = list("case", result, "answer", list(list("data", "T", list("number", "pointer"), v("number"),
                    map("binders", list(arg("number"), arg("pointer", reference))))), map("rep", integer, "binder", arg("answer", pair)));
                var data = module(bind("entry", lam(list(arg("x"), arg("ref", reference)), body)));
                var p = program(language, backend, data);
                assertEquals(capture ? 18L : 16L, run(p, "entry", 7L, poison)); released(language);
                compile(p.entryTarget("entry")); long before = (Long) p.diagnostics().get("compiledEntries");
                assertEquals(capture ? 3_000_000_011L : 3_000_000_009L, run(p, "entry", 3_000_000_000L, poison));
                assertTrue((Long) p.diagnostics().get("compiledEntries") > before, backend + " first installed tuple-result join");
                valid(p.entryTarget("entry")); released(language); assertEquals(0, poison.getState());
                // Equal register width cannot replace one scalar component with a nested tuple.
                join.put("joinResultRep", with(pair, "components", list(with(empty, "components", list(integer), "primReps", list("IntRep")), reference)));
                assertThrows(RuntimeFault.class, () -> program(language, backend, data)); released(language);
            }
        });
    }
    @Test void emptyInputAndLazyReferenceTupleResultUseSeparateLoansAndRecoverAfterThrow() throws Exception {
        withLanguage(language -> {
            var pair = with(empty, "components", list(integer, reference), "primReps", list("IntRep", "BoxedRep (Just Lifted)"));
            var packed = app(list("con", "T", 2), list(v("x"), v("ref", reference)), pair, list(false, true));
            for (boolean local : new boolean[]{false, true}) {
                // The local variant captures an empty value from the same activation.
                var returned = local ? list("case", v("held", empty), "forced", list(list("default", null, list(), packed)),
                    map("rep", pair, "binder", arg("forced", empty))) : packed;
                var producer = bind("producer", lam(list(arg("u", empty), arg("x"), arg("ref", reference)), returned, pair));
                var result = call("producer", list(call("effect", list(v("x")), empty), call("later", list(v("x"))), v("ref", reference)), pair, list(false, false, true));
                if (local) {
                    producer = with(producer, "joinValueArity", 3, "joinResultRep", pair);
                    result = list("case", zero(), "held", list(list("default", null, list(),
                        list("let", false, list(producer), result, map("rep", pair)))), map("rep", pair, "binder", arg("held", empty)));
                }
                var body = list("case", result, "tuple", list(list("data", "T", list("a", "b"), v("a"),
                    map("binders", list(arg("a"), arg("b", reference))))), map("rep", integer, "binder", arg("tuple", pair)));
                var entry = bind("entry", lam(list(arg("effect", closure), arg("later", closure), arg("x"), arg("ref", reference)), body));
                var data = local ? module(entry) : module(producer, entry);
                for (var backend : list("ast", "bytecode")) {
                    var p = program(language, backend, data); var events = new ArrayList<Long>();
                    var effect = new Closure(null, 1, new EffectRoot(language, events, new TupleShape(CoreRepresentations.parse(empty), language)).getCallTarget());
                    var later = new Closure(null, 1, new EffectRoot(language, events, null).getCallTarget());
                    var bottom = new Thunk(new EffectRoot(language, events, null).getCallTarget(), null);
                    assertEquals(17L, run(p, "entry", effect, later, 17L, bottom)); assertEquals(list(17L, 117L), events); released(language);
                    if (!local) assertTrue(language.getHandoffState().get().getResults().getAllocations() > 0);
                    assertEquals(0, bottom.getState(), "Copying a lifted tuple leaf must not force it");
                    compile(p.entryTarget("entry")); events.clear(); long before = (Long) p.diagnostics().get("compiledEntries");
                    assertEquals(3_000_000_000L, run(p, "entry", effect, later, 3_000_000_000L, bottom));
                    assertEquals(list(3_000_000_000L, 3_000_000_100L), events);
                    assertTrue((Long) p.diagnostics().get("compiledEntries") > before); valid(p.entryTarget("entry")); released(language);
                    long transfers = (Long) p.diagnostics().get("localJoinTransfers");
                    if (local) assertTrue(transfers > 0, backend + " must execute the local join");
                    events.clear(); assertThrows(GuestException.class, () -> run(p, "entry", effect, later, -7L, bottom));
                    assertEquals(list(-7L), events); assertEquals(transfers, p.diagnostics().get("localJoinTransfers")); released(language);
                    events.clear(); assertEquals(19L, run(p, "entry", effect, later, 19L, bottom));
                    assertEquals(list(19L, 119L), events); released(language); assertEquals(0, bottom.getState());
                }
            }
        });
    }

}
