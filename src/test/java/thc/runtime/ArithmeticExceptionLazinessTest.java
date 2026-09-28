// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.TruffleLanguage;
import java.util.*;
import java.util.stream.Stream;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import thc.*;
import static org.junit.jupiter.api.Assertions.*;
import static thc.runtime.ScalarValueTestSupport.*;

/** Fixture-independent check that implicit original payloads remain lazy. */
class ArithmeticExceptionLazinessTest {
    private Context context() {
        return Context.newBuilder("thc").allowNativeAccess(true).allowExperimentalOptions(true)
            .option("compiler.Inlining", "false").option("engine.BackgroundCompilation", "false")
            .option("engine.MultiTier", "false").option("engine.SingleTierCompilationThreshold", "10000000")
            .option("engine.CompilationFailureAction", "Throw").build();
    }
    private Map<String, Object> binding(String id, List<Object> expression, Map<String, Object> boxed) {
        return map("id", id, "name", id, "type", "SomeException", "lifted", true, "arity", 0, "rep", boxed, "expr", expression);
    }
    @SafeVarargs private static Map<String, Object> tuple(Map<String, Object>... fields) {
        var reps = new ArrayList<Object>();
        for (var field : fields) reps.addAll((List<?>) field.get("primReps"));
        return map("kind", "unknown", "evaluated", true, "aggregate", "unboxed-tuple", "components", list(fields), "primReps", reps);
    }
    private static Stream<Arguments> bottomResults() {
        var integer = map("kind", "long", "primReps", list("IntRep"), "evaluated", true);
        var vector = map("kind", "vector", "evaluated", true, "primReps", list("VecRep 4 Int32ElemRep"),
            "vector", map("lanes", 4, "element", "Int32ElemRep"));
        var sum = map("kind", "unknown", "evaluated", true, "aggregate", "unboxed-sum", "alternatives", list(integer, integer),
            "primReps", list("WordRep", "WordRep"), "tagSlot", 0, "alternativeSlots", list(list(1), list(1)));
        var results = map("vector", vector, "sum", sum, "nestedTuple", tuple(integer, tuple(vector, sum)));
        var cases = Stream.<Arguments>builder();
        for (var name : list("raiseDivZero#", "raiseOverflow#", "raiseUnderflow#"))
            for (var backend : list("ast", "bytecode"))
                for (var result : results.entrySet()) cases.add(Arguments.of(name, backend, result.getKey(), result.getValue()));
        return cases.build();
    }
    @ParameterizedTest(name = "{0}/{1}/{2}") @MethodSource("bottomResults")
    void typedBottomRaisesWithoutProducingAResult(String name, String backend, String shape, Map<String, Object> result) throws Exception {
        var boxed = map("kind", "data", "primReps", list("BoxedRep (Just Lifted)"), "evaluated", false);
        var closure = map("kind", "closure", "primReps", list("BoxedRep (Just Lifted)"), "evaluated", true);
        var integer = map("kind", "long", "primReps", list("IntRep"), "evaluated", true);
        var payloadId = Objects.requireNonNull(CoreArithmeticExceptions.payload(name));
        var body = list("app", list("prim", name), list(list("con", "Empty", 0, map("rep", tuple()))),
            list(false), false, false, map("rep", result));
        var function = list("lam", list(map("id", "input", "name", "input", "lifted", false, "rep", integer)), body,
            map("rep", closure, "resultRep", result));
        var module = map("schema", 1, "ghc", "9.14.1", "module", "Test.ArithmeticBottom",
            "bindings", list(binding(payloadId, list("var", payloadId, map("rep", boxed)), boxed),
                with(binding("entry", function, closure), "arity", 1)),
            "constructors", list(map("id", "Empty", "name", "(# #)", "arity", 0, "kind", "unboxed-tuple", "tag", 1,
                "strictFields", list(), "fieldLifted", list(), "fieldReps", list())));
        try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var linked = CoreModules.reachable(module, "entry", true);
                ExecutableProgram program = backend.equals("ast") ? new Program(language, linked) : new BytecodeProgram(language, linked);
                var target = program.entryTarget("entry");
                var payload = (Thunk) program.entryValue(payloadId);
                for (boolean compiled : new boolean[]{false, true}) {
                    if (compiled) {
                        target.getClass().getMethod("compile", boolean.class).invoke(target, true);
                        assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target));
                    }
                    long before = ((Number) program.diagnostics().get("compiledEntries")).longValue();
                    var failure = assertThrows(GuestException.class, () -> Calls.target(target, new Object[]{0L, 7L}));
                    assertSame(payload, failure.getPayload());
                    assertEquals(0, payload.getState(), "The original exception CAF stays unevaluated");
                    assertEquals(0L, program.diagnostics().get("thunkEvaluations"));
                    if (compiled) {
                        assertTrue(((Number) program.diagnostics().get("compiledEntries")).longValue() > before);
                        assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target));
                    }
                    var state = language.getHandoffState().get();
                    assertNull(state.getPending());
                    assertEquals(0, state.getArguments().getDepth()); assertEquals(0, state.getResults().getDepth());
                    assertEquals(0, state.getArguments().retainedReferences()); assertEquals(0, state.getResults().retainedReferences());
                }
            } finally { context.leave(); }
        }
    }
    @Test void arithmeticRaisersStillRequireOneExactUnliftedEmptyTuple() {
        var empty = CoreRepresentations.parse(tuple());
        var integer = new CoreRepresentation(CoreKind.LONG, true, true, list("IntRep"));
        var state = new CoreRepresentation(CoreKind.VOID, true, true, list());
        for (var name : list("raiseDivZero#", "raiseOverflow#", "raiseUnderflow#")) {
            for (var operands : List.of(List.<CoreRepresentation>of(), list(integer), list(state), list(empty, empty)))
                assertThrows(RuntimeFault.class, () -> CoreArithmeticExceptions.validate(name, operands, list(false), integer));
            for (var flags : List.of(list(true), List.of(), list(false, false)))
                assertThrows(RuntimeFault.class, () -> CoreArithmeticExceptions.validate(name, list(empty), flags, integer));
        }
    }
    @Test void implicitPayloadIsLazyAndFailureMemoizationSharesItInBothBackends() {
        var boxed = map("kind", "data", "primReps", list("BoxedRep (Just Lifted)"), "evaluated", false);
        var empty = map("kind", "unknown", "primReps", list(), "aggregate", "unboxed-tuple", "components", list(), "evaluated", true);
        for (var name : list("raiseDivZero#", "raiseOverflow#", "raiseUnderflow#")) for (var backend : list("ast", "bytecode")) {
            var payloadId = Objects.requireNonNull(CoreArithmeticExceptions.payload(name));
            // This deliberate bottom is a strictness control only; the native
            // fixture validates the actual exported exception dictionaries.
            var module = map("schema", 1, "ghc", "9.14.1", "module", "Test.ArithmeticLaziness",
                "bindings", list(binding(payloadId, list("var", payloadId, map("rep", boxed)), boxed),
                    binding("entry", list("app", list("prim", name), list(list("con", "Empty", 0, map("rep", empty))),
                        list(false), false, false, map("rep", boxed)), boxed)),
                "constructors", list(map("id", "Empty", "name", "(# #)", "arity", 0, "kind", "unboxed-tuple", "tag", 1,
                    "strictFields", list(), "fieldLifted", list(), "fieldReps", list())));
            try (var context = context()) {
                context.initialize("thc"); context.enter();
                try {
                    var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                    var linked = CoreModules.reachable(module, "entry", true);
                    ExecutableProgram program = backend.equals("ast") ? new Program(language, linked) : new BytecodeProgram(language, linked);
                    var payload = (Thunk) program.entryValue(payloadId);
                    var first = assertThrows(GuestException.class, () -> Calls.target(program.hostEntryTarget(), new Object[]{program.entryValue("entry"), new Object[0]}));
                    var second = assertThrows(GuestException.class, () -> Calls.target(program.hostEntryTarget(), new Object[]{program.entryValue("entry"), new Object[0]}));
                    assertNotSame(first, second);
                    assertSame(payload, first.getPayload());
                    assertSame(first.getPayload(), second.getPayload());
                    assertEquals(0, payload.getState(), backend + "/" + name + " never enters its implicit exception CAF");
                    assertEquals(1L, program.diagnostics().get("thunkEvaluations"));
                    assertEquals(0L, program.diagnostics().get("blackholes"));
                } finally { context.leave(); }
            }
        }
    }
}
