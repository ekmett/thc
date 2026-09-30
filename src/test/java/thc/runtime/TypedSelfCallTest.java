// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import jdk.incubator.vector.ShortVector;
import org.junit.jupiter.api.Test;
import thc.Language;
import thc.Main;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import static org.junit.jupiter.api.Assertions.*;

/** Exact-Core controls. Native loopCase and compiler graphs remain separate evidence. */
class TypedSelfCallTest {
    private final Map<String, Object> integer = Map.of("kind", "long", "primReps", List.of("IntRep"), "evaluated", true);
    private final Map<String, Object> lane = Map.of("kind", "long", "primReps", List.of("Int16Rep"), "evaluated", true);
    private final Map<String, Object> closure = Map.of("kind", "closure", "primReps", List.of("BoxedRep (Just Lifted)"), "evaluated", true);
    private final Map<String, Object> vector = Map.of("kind", "vector", "primReps", List.of("VecRep 8 Int16ElemRep"),
        "vector", Map.of("lanes", 8, "element", "Int16ElemRep"), "evaluated", true);
    private final Map<String, Object> unpacked = Map.of("kind", "unknown", "aggregate", "unboxed-tuple",
        "primReps", Collections.nCopies(8, "Int16Rep"), "components", Collections.nCopies(8, lane), "evaluated", true);
    private List<Object> variable(String id) { return variable(id, integer); }
    private List<Object> variable(String id, Map<String, Object> rep) { return List.of("var", id, Map.of("rep", rep)); }
    private List<Object> literal(long value) { return List.of("lit", "int", Long.toString(value), Map.of("rep", integer)); }
    private Map<String, Object> formal(String id) { return formal(id, integer); }
    private Map<String, Object> formal(String id, Map<String, Object> rep) { return Map.of("id", id, "name", id, "lifted", false, "rep", rep); }
    private List<Object> application(List<Object> fn, List<List<Object>> args, Map<String, Object> rep) {
        return List.of("app", fn, args, Collections.nCopies(args.size(), false), false, false, Map.of("rep", rep));
    }
    private List<Object> primitive(String name, List<List<Object>> args) { return primitive(name, args, integer); }
    private List<Object> primitive(String name, List<List<Object>> args, Map<String, Object> rep) { return application(List.of("prim", name), args, rep); }
    private List<Object> broadcast(List<Object> value) { return primitive("broadcastInt16X8#", List.of(primitive("intToInt16#", List.of(value), lane)), vector); }
    private List<Object> checksum(List<Object> value) {
        var ids = new ArrayList<String>();
        for (int i = 0; i < 8; i++) ids.add("lane" + i);
        var sum = literal(0);
        for (var id : ids) sum = primitive("+#", List.of(sum, primitive("int16ToInt#", List.of(variable(id, lane)))));
        var binders = new ArrayList<Map<String, Object>>();
        for (var id : ids) binders.add(formal(id, lane));
        return List.of("case", primitive("unpackInt16X8#", List.of(value), unpacked), "whole", List.of(
            List.of("data", "T8", ids, sum, Map.of("binders", binders))), Map.of("rep", integer, "binder", formal("whole", unpacked)));
    }
    private Map<String, Object> module(boolean vectorResult) {
        var result = vectorResult ? vector : integer;
        var left = variable("left", vector); var right = variable("right", vector); var remaining = variable("remaining");
        var finish = vectorResult ? left : primitive("+#", List.of(checksum(primitive("minusInt16X8#", List.of(left, right), vector)),
            primitive("-#", List.of(variable("x"), variable("y")))));
        var recur = application(variable("worker", closure), List.of(
            primitive("plusInt16X8#", List.of(right, broadcast(remaining)), vector),
            primitive("timesInt16X8#", List.of(left, broadcast(literal(3))), vector),
            primitive("-#", List.of(remaining, literal(1))), variable("y"), variable("x")), result);
        var body = List.of("case", remaining, "condition", List.of(
            List.of("lit", List.of("int", "0"), List.of(), finish), Arrays.asList("default", null, List.of(), recur)),
            Map.of("rep", result, "binder", formal("condition")));
        var args = List.of(formal("left", vector), formal("right", vector), formal("remaining"), formal("x"), formal("y"));
        var rhs = List.of("lam", args, body, Map.of("rep", closure, "resultRep", result, "entryStrict", Collections.nCopies(args.size(), false)));
        return Map.of("instrument", true, "constructors", List.of(Map.of("id", "T8", "name", "T8", "kind", "unboxed-tuple", "arity", 8)),
            "bindings", List.of(Map.of("id", "worker", "name", "worker", "lifted", true, "rep", closure, "expr", rhs)));
    }
    private static long count(ExecutableProgram program, String name) { return ((Number) program.diagnostics().get(name)).longValue(); }
    private static void compile(RootCallTarget target) throws Exception {
        target.getClass().getMethod("compile", boolean.class).invoke(target, true);
        target.getClass().getMethod("waitForCompilation").invoke(target);
        assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target));
    }
    @FunctionalInterface private interface Action { void run(String backend, Language language, ExecutableProgram program) throws Exception; }
    private void eachBackend(boolean vectorResult, Action action) throws Exception {
        try (var context = Main.executionContext()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                action.run("ast", language, new Program(language, module(vectorResult)));
                action.run("bytecode", language, new BytecodeProgram(language, module(vectorResult)));
            } finally { context.leave(); }
        }
    }
    private void check(String backend, Language language, ExecutableProgram program, long depth, boolean vectorResult) {
        var target = program.entryTarget("worker");
        var root = (GuestRoot) target.getRootNode();
        var entry = Objects.requireNonNull(root.getTypedInput());
        // Only initial ingress allocates here; every recursive edge must be a local move.
        var input = entry.getPacket().create(); input.setInputMode(3);
        short[] first = {Short.MIN_VALUE, -137, -1, 0, 1, 79, 311, Short.MAX_VALUE};
        short[] second = {31, -71, 101, 907, -1009, 2111, -17003, 27007};
        long x = 3_000_000_017L, y = -7_000_000_003L;
        entry.getPacket().setLong(input, 0, 0L);
        entry.getPacket().setObject(input, 1, ShortVector.fromArray(ShortVector.SPECIES_128, first, 0));
        entry.getPacket().setObject(input, 2, ShortVector.fromArray(ShortVector.SPECIES_128, second, 0));
        entry.getPacket().setLong(input, 3, depth); entry.getPacket().setLong(input, 4, x); entry.getPacket().setLong(input, 5, y);
        var left = first; var right = second;
        for (long remaining = depth; remaining >= 1; remaining--) {
            var nextLeft = new short[8];
            for (int i = 0; i < 8; i++) nextLeft[i] = (short) (right[i] + (short) remaining);
            var nextRight = new short[8];
            for (int i = 0; i < 8; i++) nextRight[i] = (short) (left[i] * 3);
            left = nextLeft; right = nextRight;
        }
        long reentries = count(program, "selfTailReentries"), bounces = count(program, "tailBounces");
        var value = TypedInputs.invokeTypedInput(entry, input, arguments -> Calls.target(target, arguments));
        var label = backend + "/depth=" + depth + "/vectorResult=" + vectorResult;
        if (vectorResult) {
            var shape = Objects.requireNonNull(root.getTupleResult());
            var result = TupleResults.ownedTupleResult(value, shape);
            var actual = (ShortVector) shape.getLayout().getObject(result, 0);
            assertArrayEquals(left, actual.toArray(), label);
        } else {
            long sum = 0;
            for (int i = 0; i < 8; i++) sum += (short) (left[i] - right[i]);
            assertEquals(sum + (depth % 2 == 0L ? x - y : y - x), value, label);
        }
        assertEquals(reentries + depth, count(program, "selfTailReentries"), label);
        assertEquals(bounces, count(program, "tailBounces"), label + " must not construct tail packets");
        assertEquals(0L, count(program, "trampolineIterations"), label);
        var state = language.getHandoffState().get();
        assertNull(state.getPending()); assertEquals(0, state.getArguments().getDepth()); assertEquals(0, state.getArguments().retainedReferences());
        assertEquals(0, state.getResults().getDepth()); assertEquals(0, state.getResults().retainedReferences());
    }
    private void exercise(boolean vectorResult) throws Exception {
        eachBackend(vectorResult, (backend, language, program) -> {
            // Compile a root that has never seen recursion, then exercise its cold backedge.
            for (int i = 0; i < 20; i++) check(backend, language, program, 0, vectorResult);
            compile(program.entryTarget("worker")); check(backend, language, program, 10_001, vectorResult);
            compile(program.entryTarget("worker"));
            long compiled = count(program, "compiledEntries");
            for (long depth : List.of(0L, 1L, 2L, 31L, 10_000L)) check(backend, language, program, depth, vectorResult);
            assertTrue(count(program, "compiledEntries") > compiled, backend);
        });
    }
    @Test void vectorArithmeticAndWideScalarArgumentsUseParallelSelfMoves() throws Exception { exercise(false); }
    @Test void vectorResultsRetainTheirOriginalDestinationAcrossSelfMoves() throws Exception { exercise(true); }
    @Test void strictColdScalarsDoNotQualifyForTypedSelfMoves() {
        var vectorProof = CoreRepresentations.parse(vector);
        var cold = CoreRepresentations.parse(Map.of("kind", "object", "primReps", List.of("BoxedRep (Just Lifted)"), "evaluated", false));
        var formal = Objects.requireNonNull(ArgumentLayout.fromProofs(List.of(vectorProof, cold)));
        assertFalse(TypedInputs.supportsTypedSelf(formal, new boolean[]{false, true}, formal));
        var evaluated = cold.copy(cold.getKind(), true, cold.getPresent(), cold.getPrimReps(), cold.getComponents(),
            cold.getVector(), cold.getAlternatives(), cold.getTagSlot(), cold.getAlternativeSlots());
        assertTrue(TypedInputs.supportsTypedSelf(formal, new boolean[]{false, true}, Objects.requireNonNull(ArgumentLayout.fromProofs(List.of(vectorProof, evaluated)))));
        assertFalse(TypedInputs.supportsTypedSelf(formal, new boolean[]{false, false}, Objects.requireNonNull(ArgumentLayout.fromProofs(List.of(vectorProof)))));
    }
}
