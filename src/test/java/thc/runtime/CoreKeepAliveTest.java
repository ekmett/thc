// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;
import org.junit.jupiter.api.Test;
import thc.Language;
import thc.Main;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static thc.runtime.ScalarValueTestSupport.*;

/** Independent conformance tests, not native/compiled-entry coverage claims. */
class CoreKeepAliveTest {
    private final Map<String, Object> state = proof("void", list()), integer = proof("long", list("IntRep")),
        word = proof("long", list("WordRep")), word8 = proof("long", list("Word8Rep")),
        owner = proof("object", list("BoxedRep (Just Lifted)"), false), data = proof("data", list("BoxedRep (Just Lifted)"), false),
        closure = proof("closure", list("BoxedRep (Just Lifted)")), lazyClosure = with(closure, "evaluated", false);
    private Map<String, Object> proof(String kind, List<String> reps) { return proof(kind, reps, true); }
    private Map<String, Object> proof(String kind, List<String> reps, boolean evaluated) { return map("kind", kind, "primReps", reps, "evaluated", evaluated); }
    private Map<String, Object> parameter(String id, Map<String, Object> rep) {
        return map("id", id, "name", id, "rep", rep, "lifted", list("BoxedRep (Just Lifted)").equals(rep.get("primReps")), "coercion", false);
    }
    private List<Object> variable(String id, Map<String, Object> rep) { return list("var", id, map("rep", rep)); }
    private List<Object> integer(long value) { return list("lit", "int", Long.toString(value), map("rep", integer)); }
    private List<Object> voidValue() { return list("void", map("rep", state)); }
    private List<Object> lambda(List<?> args, List<?> body, Map<String, Object> result) { return list("lam", args, body, map("rep", closure, "resultRep", result)); }
    private Map<String, Object> binding(String id, List<?> body) { return with(parameter(id, closure), "expr", body, "arity", ((List<?>) body.get(1)).size()); }
    private List<Object> app(List<?> function, List<?> args, List<Boolean> flags, Map<String, Object> result) {
        return list("app", function, args, flags, false, false, map("rep", result));
    }
    private List<Object> keep(List<?> action, Map<String, Object> result) {
        return app(list("prim", "keepAlive#"), list(variable("owner", owner), variable("state", state), action), list(true, false, true), result);
    }
    @SafeVarargs private final Map<String, Object> tuple(Map<String, Object>... fields) {
        var reps = new ArrayList<String>(); for (var field : fields) for (var rep : (List<?>) field.get("primReps")) reps.add((String) rep);
        return map("kind", "unknown", "evaluated", false, "aggregate", "unboxed-tuple", "components", Arrays.asList(fields), "primReps", reps);
    }
    private Map<String, Object> sum() {
        return map("kind", "unknown", "evaluated", false, "aggregate", "unboxed-sum", "alternatives", list(integer, integer),
            "primReps", list("WordRep", "WordRep"), "tagSlot", 0, "alternativeSlots", list(list(1), list(1)));
    }
    private final List<Map<String, Object>> constructors = list(
        map("id", "Tuple2", "name", "Tuple2", "kind", "unboxed-tuple", "arity", 2, "fieldReps", list(null, null), "strictFields", list(false, false), "fieldLifted", list(null, null)),
        map("id", "Left", "name", "Left", "kind", "unboxed-sum", "arity", 1, "sumArity", 2, "tag", 1),
        map("id", "Right", "name", "Right", "kind", "unboxed-sum", "arity", 1, "sumArity", 2, "tag", 2));
    private ExecutableProgram program(Language language, String backend, List<Map<String, Object>> bindings) {
        var module = map("bindings", bindings, "constructors", constructors, "instrument", true);
        return backend.equals("ast") ? new Program(language, module) : new BytecodeProgram(language, module);
    }
    private void eachBackend(CheckedBiConsumer<Language, String> action) throws Exception {
        for (var backend : list("ast", "bytecode")) try (var context = Main.executionContext()) {
            context.initialize("thc"); context.enter();
            try { action.accept(TruffleLanguage.LanguageReference.create(Language.class).get(null), backend); } finally { context.leave(); }
        }
    }
    private Object call(ExecutableProgram program, Object... arguments) {
        return Calls.target(program.hostEntryTarget(arguments.length), new Object[]{program.entryValue("main"), arguments});
    }
    private void released(Language language) {
        var pools = language.getHandoffState().get();
        assertEquals(0, pools.getArguments().getDepth()); assertEquals(0, pools.getResults().getDepth());
        assertEquals(0, pools.getArguments().retainedReferences()); assertEquals(0, pools.getResults().retainedReferences());
    }
    @Test void exactScalarVectorTupleAndSumResultsKeepTheirLogicalIdentity() {
        var inputs = list(owner, state, closure).stream().map(CoreRepresentations::parse).toList();
        var vector = map("kind", "vector", "evaluated", true, "primReps", list("VecRep 8 Int16ElemRep"),
            "vector", map("lanes", 8, "element", "Int16ElemRep"));
        for (var result : list(integer, word8, data, vector, tuple(state, data), tuple(state, word8), sum(), tuple(sum(), integer))) {
            var actual = CoreRepresentations.parse(result);
            assertDoesNotThrow(() -> CoreKeepAlive.validate(inputs, list(true, false, true), actual, list(CoreRepresentations.parse(state)), actual));
        }
        record Mismatch(Map<String, Object> declared, Map<String, Object> actual) {}
        for (var pair : list(new Mismatch(integer, state), new Mismatch(word, word8), new Mismatch(tuple(state, word), tuple(state, word8)),
            new Mismatch(integer, sum()), new Mismatch(tuple(integer, integer), sum())))
            assertThrows(RuntimeFault.class, () -> CoreKeepAlive.validate(inputs, list(true, false, true), CoreRepresentations.parse(pair.declared()),
                list(CoreRepresentations.parse(state)), CoreRepresentations.parse(pair.actual())));
        assertThrows(RuntimeFault.class, () -> CoreKeepAlive.validate(inputs, list(true, false, true),
            CoreRepresentations.parse(map("kind", "unknown", "primReps", null, "evaluated", false)), null, null));
    }
    @Test void bothLoadersRejectWrongKnownContinuationInputsResultsAndPartialApplicationResults() throws Exception {
        eachBackend((language, backend) -> {
            var wrongInput = lambda(list(parameter("notState", integer)), integer(7), integer);
            var wrongResult = lambda(list(parameter("s", state)), voidValue(), state);
            var partial = lambda(list(parameter("s", state), parameter("x", integer)), variable("x", integer), integer);
            var global = binding("callback", wrongInput);
            var alias = with(parameter("alias", closure), "expr", variable("callback", closure));
            var prefixed = binding("prefixed", lambda(list(parameter("prefix", integer), parameter("notState", integer)), integer(7), integer));
            var pap = app(variable("prefixed", closure), list(integer(1)), list(false), closure);
            record Variant(List<Object> action, List<Map<String, Object>> additional) {}
            for (var variant : list(new Variant(wrongInput, list()), new Variant(wrongResult, list()), new Variant(partial, list()),
                new Variant(variable("callback", closure), list(global)), new Variant(variable("alias", closure), list(global, alias)), new Variant(pap, list(prefixed)))) {
                var main = binding("main", lambda(list(parameter("owner", owner), parameter("state", state)), keep(variant.action(), integer), integer));
                var bindings = new ArrayList<>(list(main)); bindings.addAll(variant.additional());
                assertThrows(RuntimeFault.class, () -> program(language, backend, bindings), backend + "/" + variant.action());
            }
        });
    }
    @Test void liftedKeptBottomStaysLazyAndScalarCallbackResultIsReturnedInWhnf() throws Exception {
        eachBackend((language, backend) -> {
            var action = lambda(list(parameter("s", state)), variable("value", data), data);
            var main = binding("main", lambda(list(parameter("owner", owner), parameter("state", state), parameter("value", data)), keep(action, data), data));
            var p = program(language, backend, list(main)); int[] forced = {0};
            var value = new DataLayout(language, "review.Box", "Box", new String[]{"IntRep"}).create(new Object[]{91L});
            var lazyValue = new Thunk(new RootNode(null) {
                @Override public Object execute(VirtualFrame frame) { forced[0]++; return value; }
            }.getCallTarget(), null);
            var bottom = new Thunk(new RootNode(null) {
                @Override public Object execute(VirtualFrame frame) { throw new AssertionError("kept argument was forced"); }
            }.getCallTarget(), null);
            assertSame(value, call(p, bottom, thc.runtime.Unit.INSTANCE, lazyValue), backend);
            assertEquals(1, forced[0]); assertEquals(2, lazyValue.getState()); assertEquals(0, bottom.getState()); released(language);
        });
    }
    @Test void tupleCallbackDoesNotStrengthenLiftedPayloadEvaluatedness() throws Exception {
        eachBackend((language, backend) -> {
            var result = tuple(state, data);
            var pair = app(list("con", "Tuple2", 2), list(voidValue(), variable("value", data)), list(false, true), result);
            var action = lambda(list(parameter("s", state)), pair, result);
            var fields = list(parameter("nextState", state), parameter("unusedValue", data));
            var body = list("case", keep(action, result), "pair", list(list("data", "Tuple2", list("nextState", "unusedValue"), integer(7), map("binders", fields))),
                map("rep", integer, "binder", parameter("pair", with(result, "evaluated", true))));
            var main = binding("main", lambda(list(parameter("owner", owner), parameter("state", state), parameter("value", data)), body, integer));
            var p = program(language, backend, list(main));
            var bottom = new Thunk(new RootNode(null) {
                @Override public Object execute(VirtualFrame frame) { throw new AssertionError("lazy tuple field was forced"); }
            }.getCallTarget(), null);
            assertEquals(7L, call(p, bottom, thc.runtime.Unit.INSTANCE, bottom), backend); assertEquals(0, bottom.getState()); released(language);
        });
    }
    @Test void existingBinarySumResultCanBeConsumedWithoutChangingItsAbi() throws Exception {
        eachBackend((language, backend) -> {
            var result = sum(); var left = app(list("con", "Left", 1), list(integer(77)), list(false), result);
            var action = lambda(list(parameter("s", state)), left, result); var alternatives = new ArrayList<List<Object>>();
            for (var constructor : list("Left", "Right")) alternatives.add(list("data", constructor, list("payload"), variable("payload", integer), map("binders", list(parameter("payload", integer)))));
            var body = list("case", keep(action, result), "sum", alternatives, map("rep", integer, "binder", parameter("sum", with(result, "evaluated", true))));
            var main = binding("main", lambda(list(parameter("owner", owner), parameter("state", state)), body, integer));
            assertEquals(77L, call(program(language, backend, list(main)), new Object(), thc.runtime.Unit.INSTANCE), backend); released(language);
        });
    }
    @Test void partialContinuationReturnsTheRemainingLiftedFunction() throws Exception {
        eachBackend((language, backend) -> {
            var action = lambda(list(parameter("s", state), parameter("x", integer)), variable("x", integer), integer);
            var main = binding("main", lambda(list(parameter("owner", owner), parameter("state", state)), keep(action, closure), closure));
            var p = program(language, backend, list(main)); var result = call(p, new Object(), thc.runtime.Unit.INSTANCE);
            assertTrue(result instanceof Closure, backend); assertEquals(1, ((Closure) result).arity);
            assertEquals(123L, Calls.target(p.hostEntryTarget(1), new Object[]{result, new Object[]{123L}}), backend); released(language);
        });
    }
    @Test void invalidStatePrecedesCallbackThunkForcingAndExceptionsReleaseAllLoans() throws Exception {
        eachBackend((language, backend) -> {
            var main = binding("main", lambda(list(parameter("owner", owner), parameter("state", state), parameter("callback", lazyClosure)), keep(variable("callback", lazyClosure), integer), integer));
            var p = program(language, backend, list(main)); var exceptionPayload = new Object();
            class Counts { int forces, calls, failedForces; boolean throwing = true; }
            var counts = new Counts();
            var callback = new Closure(null, 1, new RootNode(null) {
                @Override public Object execute(VirtualFrame frame) {
                    assertSame(thc.runtime.Unit.INSTANCE, frame.getArguments()[1]); counts.calls++;
                    if (counts.throwing) throw new GuestException(exceptionPayload, this); return 77L;
                }
            }.getCallTarget());
            var lazyCallback = new Thunk(new RootNode(null) {
                @Override public Object execute(VirtualFrame frame) { counts.forces++; return callback; }
            }.getCallTarget(), null);
            var failure = assertThrows(RuntimeFault.class, () -> call(p, new Object(), 1L, lazyCallback));
            assertTrue(Objects.toString(failure.getMessage(), "").contains("zero-width"), backend + ": " + failure);
            assertEquals(0, counts.forces); assertEquals(0, counts.calls); assertEquals(0, lazyCallback.getState()); released(language);
            var failedCallback = new Thunk(new RootNode(null) {
                @Override public Object execute(VirtualFrame frame) { counts.failedForces++; throw new RuntimeFault("callback thunk failed"); }
            }.getCallTarget(), null);
            var stateFailure = assertThrows(RuntimeFault.class, () -> call(p, new Object(), 1L, failedCallback));
            assertTrue(Objects.toString(stateFailure.getMessage(), "").contains("zero-width"), backend + ": " + stateFailure);
            assertEquals(0, counts.failedForces); assertEquals(0, failedCallback.getState());
            var thunkFailure = assertThrows(RuntimeFault.class, () -> call(p, new Object(), thc.runtime.Unit.INSTANCE, failedCallback));
            assertEquals("callback thunk failed", thunkFailure.getMessage()); assertEquals(1, counts.failedForces); released(language);
            var guest = assertThrows(GuestException.class, () -> call(p, new Object(), thc.runtime.Unit.INSTANCE, lazyCallback));
            assertSame(exceptionPayload, guest.getPayload()); assertEquals(1, counts.forces); assertEquals(1, counts.calls); released(language);
            counts.throwing = false; assertEquals(77L, call(p, new Object(), thc.runtime.Unit.INSTANCE, lazyCallback), backend);
            assertEquals(1, counts.forces); assertEquals(2, counts.calls); released(language);
        });
    }
}
