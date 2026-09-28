// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;
import java.util.*;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import thc.Language;
import static org.junit.jupiter.api.Assertions.*;
import static thc.runtime.RepresentationTestSupport.*;

/** A known pointer with unknown levity stays lazy while moving through guest storage. */
class UnknownBoxedTransportTest {
    private static final Map<String,Object> UNKNOWN = map("kind", "object", "primReps", list("BoxedRep Nothing"), "evaluated", false);
    private static final Map<String,Object> INTEGER = map("kind", "long", "primReps", list("IntRep"), "evaluated", true);
    private static final Map<String,Object> CLOSURE = map("kind", "closure", "primReps", list("BoxedRep (Just Lifted)"), "evaluated", true);
    private static final Map<String,Object> DATA = map("kind", "data", "primReps", list("BoxedRep (Just Lifted)"), "evaluated", true);
    private static final Map<String,Object> STATE = map("kind", "void", "primReps", list(), "evaluated", true);
    private static final Map<String,Object> PAIR = tuple(UNKNOWN, INTEGER);
    @SafeVarargs private static Map<String,Object> tuple(Map<String,Object>... fields) {
        var reps = new ArrayList<Object>(); for (var field : fields) reps.addAll((List<?>) field.get("primReps"));
        return map("kind", "unknown", "primReps", reps, "evaluated", true, "aggregate", "unboxed-tuple", "components", Arrays.asList(fields));
    }
    private static Map<String,Object> binder(String id, Map<String,Object> rep) {
        return map("id", id, "name", id, "lifted", rep == UNKNOWN ? null : rep == CLOSURE || rep == DATA, "rep", rep);
    }
    private static List<Object> variable(String id, Map<String,Object> rep) { return list("var", id, map("rep", rep)); }
    private static List<Object> number(long n) { return list("lit", "int", Long.toString(n), map("rep", INTEGER)); }
    private static List<Object> call(List<Object> function, Map<String,Object> result, List<List<Object>> args, Object... lifted) {
        return list("app", function, args, Arrays.asList(lifted), false, false, map("rep", result));
    }
    private static List<Object> box(List<Object> value) { return call(list("con", "Box", 1), DATA, list(value), (Object) null); }
    private static List<Object> lambda(Map<String,Object> result, List<Map<String,Object>> binders, List<Object> body) {
        return list("lam", binders, body, map("rep", CLOSURE, "resultRep", result, "entryStrict", Collections.nCopies(binders.size(), false)));
    }
    private static Map<String,Object> binding(String id, List<Object> body) {
        return map("id", id, "name", id, "lifted", true, "arity", ((List<?>) body.get(1)).size(), "rep", CLOSURE, "expr", body);
    }
    private static Map<String,Object> module() {
        var pair = call(list("con", "Pair", 2), PAIR, list(variable("x", UNKNOWN), number(17)), null, false);
        var boxPair = list("case", variable("p", PAIR), "whole", list(list("data", "Pair", list("x", "n"), box(variable("x", UNKNOWN)),
            map("binders", list(binder("x", UNKNOWN), binder("n", INTEGER))))), map("rep", DATA, "binder", binder("whole", PAIR)));
        var identity = binding("identity", lambda(PAIR, list(binder("p", PAIR)), variable("p", PAIR)));
        var worker = binding("worker", lambda(DATA, list(binder("p", PAIR), binder("unused", INTEGER)), boxPair));
        var scalar = binding("scalar", lambda(DATA, list(binder("x", UNKNOWN)), box(variable("x", UNKNOWN))));
        var tuple = binding("tuple", lambda(DATA, list(binder("x", UNKNOWN)), call(variable("worker", CLOSURE), DATA,
            list(call(variable("identity", CLOSURE), PAIR, list(pair), false), number(0)), false, false)));
        var pap = binding("pap", lambda(DATA, list(binder("x", UNKNOWN)), call(call(variable("worker", CLOSURE), CLOSURE, list(pair), false), DATA, list(number(0)), false)));
        var capture = binding("capture", lambda(DATA, list(binder("x", UNKNOWN)), call(lambda(DATA, list(binder("unused", INTEGER)), box(variable("x", UNKNOWN))), DATA, list(number(0)), false)));
        var stateResult = tuple(STATE, UNKNOWN);
        var action = lambda(stateResult, list(binder("s", STATE)),
            call(list("con", "Pair", 2), stateResult, list(variable("s", STATE), variable("x", UNKNOWN)), false, null));
        var masked = call(list("prim", "maskAsyncExceptions#"), stateResult,
            list(action, list("void", map("rep", STATE))), true, false);
        var mask = binding("mask", lambda(DATA, list(binder("x", UNKNOWN)), list("case", masked, "masked", list(
            list("data", "Pair", list("s", "value"), box(variable("value", UNKNOWN)), map("binders", list(binder("s", STATE), binder("value", UNKNOWN))))),
            map("rep", DATA, "binder", binder("masked", stateResult)))));
        return map("schema", 1, "ghc", "9.14.1", "module", "UnknownBoxedTransport", "instrument", true,
            "bindings", list(identity, worker, scalar, tuple, pap, capture, mask), "constructors", list(
                map("id", "Pair", "name", "Pair", "arity", 2, "kind", "unboxed-tuple"),
                map("id", "Box", "name", "Box", "arity", 1, "kind", "boxed", "tag", 1, "strictFields", list(false),
                    "fieldLifted", list((Object) null), "fieldReps", list(list("BoxedRep Nothing")), "fieldTypes", list(UNKNOWN))));
    }
    @Test void pointerSurvivesScalarTuplePapCaptureAndConstructorWithoutForcing() throws Exception {
        for (var backend : list("ast", "bytecode")) try (var context = Context.newBuilder("thc").allowExperimentalOptions(true)
            .option("compiler.Inlining", "false").option("engine.BackgroundCompilation", "false")
            .option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw").build()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                ExecutableProgram program = backend.equals("ast") ? new Program(language, module()) : new BytecodeProgram(language, module());
                var poison = new Thunk(new RootNode(language) {
                    @Override public Object execute(VirtualFrame frame) { throw new AssertionError("Transport entered the unknown-levity pointer"); }
                }.getCallTarget(), null);
                for (var name : list("scalar", "tuple", "pap", "capture", "mask")) {
                    var target = program.entryTarget(name);
                    for (int phase = 0; phase < 2; phase++) {
                        if (phase == 1) {
                            target.getClass().getMethod("compile", boolean.class).invoke(target, true);
                            assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target), backend + "/" + name);
                        }
                        long compiled = ((Number) program.diagnostics().get("compiledEntries")).longValue();
                        var result = (DataValue) Calls.target(program.hostEntryTarget(1), new Object[]{program.entryValue(name), new Object[]{poison}});
                        assertSame(poison, program.constructorLayout("Box").read(result, 0), backend + "/" + name);
                        assertEquals(0, poison.getState());
                        if (phase == 1) {
                            assertTrue(((Number) program.diagnostics().get("compiledEntries")).longValue() > compiled, backend + "/" + name);
                            assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target), backend + "/" + name);
                        }
                    }
                }
                var state = language.getHandoffState().get();
                assertNull(state.getPending()); assertEquals(0, state.getArguments().getDepth()); assertEquals(0, state.getResults().getDepth());
                assertEquals(0, state.getArguments().retainedReferences()); assertEquals(0, state.getResults().retainedReferences());
            } finally { context.leave(); }
        }
    }
    @Test void tupleAndExceptionResultsKnowPointerStorageWithoutClaimingWhnf() {
        var unknown = CoreRepresentations.parse(UNKNOWN);
        var pair = CoreRepresentations.parse(PAIR);
        assertDoesNotThrow(() -> CoreRepresentations.requireInput(pair));
        var result = CoreRepresentations.parse(tuple(STATE, UNKNOWN));
        assertDoesNotThrow(() -> CoreSynchronousExceptions.validate("maskAsyncExceptions#",
            list(CoreRepresentations.parse(CLOSURE), CoreRepresentations.parse(STATE)), list(true, false), result));
        assertFalse(unknown.getEvaluated()); assertNull(unknown.referenceCarrier());
        assertEquals("reference", HandoffLayout.fieldKind("BoxedRep Nothing"));
    }
    @Test void instantiatedBoxedTupleLeavesMatchWithoutErasingKnownLevity() {
        var unknown = CoreRepresentations.parse(tuple(UNKNOWN));
        var lifted = CoreRepresentations.parse(tuple(with(UNKNOWN, "primReps", list("BoxedRep (Just Lifted)"))));
        var unlifted = CoreRepresentations.parse(tuple(with(UNKNOWN, "primReps", list("BoxedRep (Just Unlifted)"))));
        assertTrue(TupleShape.compatible(unknown, lifted));
        assertTrue(TupleShape.compatible(unknown, unlifted));
        assertFalse(TupleShape.compatible(lifted, unlifted));
        assertDoesNotThrow(() -> ArgumentLayout.validate(ArgumentLayout.fromProofs(list(unknown)), 0,
            ArgumentLayout.fromProofs(list(lifted)), 0, 1));
        assertEquals(lifted.getComponents().getFirst().getPrimReps(), lifted.refine(unknown).getComponents().getFirst().getPrimReps());
        assertFalse(lifted.refine(unknown).getComponents().getFirst().getEvaluated());
        var refined = unknown.refine(lifted).refine(unknown);
        assertEquals(lifted.getPrimReps(), refined.getPrimReps());
        assertThrows(RuntimeFault.class, () -> refined.refine(unlifted));
    }
}
