// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Primitive consumers must retain Core case semantics across profiling and compilation. */
public final class CasePrimitiveTest {
    private List<Object> variable(String id) { return List.of("var", id); }
    private List<Object> integer(long value) { return List.of("lit", "int", Long.toString(value)); }
    private List<Object> apply(List<Object> function, List<List<Object>> arguments, List<Boolean> lifted) {
        return List.of("app", function, arguments, lifted);
    }
    @SafeVarargs private final List<Object> primitive(String name, List<Object>... arguments) {
        return apply(List.of("prim", name), Arrays.asList(arguments), Collections.nCopies(arguments.length, false));
    }
    private Map<String, Object> binding(String id, List<Object> expression, boolean lifted, int arity) {
        return Map.of("id", id, "name", id, "type", lifted ? "Synthetic" : "Int#", "lifted", lifted, "arity", arity, "expr", expression);
    }
    private Map<String, Object> binding(String id, List<Object> expression) { return binding(id, expression, true, 0); }
    private List<Object> lambda(List<Object> body) {
        return List.of("lam", List.of(Map.of("id", "input", "name", "input", "type", "Int#", "lifted", false, "coercion", false)), body);
    }
    private Map<String, Object> constructor(String id, int fields, int tag) {
        return Map.of("id", id, "name", id, "arity", fields, "tag", tag, "kind", "boxed",
            "strictFields", Collections.nCopies(fields, false), "fieldLifted", Collections.nCopies(fields, false),
            "fieldReps", Collections.nCopies(fields, List.of("IntRep")));
    }
    @SafeVarargs private final List<Object> construct(String id, List<Object>... fields) {
        return fields.length == 0 ? List.of("con", id, 0)
            : apply(List.of("con", id, fields.length), Arrays.asList(fields), Collections.nCopies(fields.length, false));
    }
    private List<Object> literalArm(long value, List<Object> body) { return List.of("lit", List.of("int", Long.toString(value)), List.of(), body); }
    private List<Object> defaultArm(List<Object> body) { return Arrays.asList("default", null, List.of(), body); }
    private List<Object> dataArm(String id, List<String> fields, List<Object> body) { return List.of("data", id, fields, body); }
    @SafeVarargs private final List<Object> caseOf(List<Object> scrutinee, String binder, List<Object>... alternatives) {
        return List.of("case", scrutinee, binder, Arrays.asList(alternatives));
    }
    private String request(List<Object> body, List<Map<String, Object>> constructors, List<Map<String, Object>> bindings) {
        List<Map<String, Object>> allBindings = new ArrayList<>(bindings);
        allBindings.add(binding("entry", lambda(body), true, 1));
        return Json.INSTANCE.stringify(Map.of("entry", "entry", "backend", "ast", "instrument", true,
            "modules", List.of(Map.of("schema", 1, "ghc", "9.14.1", "module", "Synthetic.PrimitiveCases",
                "constructors", constructors, "bindings", allBindings))));
    }
    private Map<?, ?> diagnostics(Value function) { return (Map<?, ?>) Json.INSTANCE.parse(function.getMember("diagnostics").asString()); }
    private long count(Value function, String key) { return ((Number) diagnostics(function).get(key)).longValue(); }
    private long evaluations(Value function, String label) {
        Object value = ((Map<?, ?>) diagnostics(function).get("thunkEvaluationsByLabel")).get(label);
        return value instanceof Number number ? number.longValue() : 0;
    }
    private void compileWarmArm(Value function, long input, long expected) {
        assertEquals("ast", diagnostics(function).get("backend"));
        for (int i = 0; i < 40; i++) assertEquals(expected, function.execute(input).asLong());
        assertTrue(function.invokeMember("compile").asBoolean());
        long before = count(function, "compiledEntries");
        assertEquals(expected, function.execute(input).asLong());
        assertTrue(count(function, "compiledEntries") > before, "The warm case arm must execute installed guest code");
    }
    private long expectedNested(long input) { return input == 0 || input == Long.MIN_VALUE ? Long.MIN_VALUE + 18 : input - 16; }
    @Test void nestedLiteralAndDefaultCasesFeedPrimitiveOperandsThroughLetAndEvaluation() {
        var inner = caseOf(variable("input"), "original", literalArm(0, integer(Long.MIN_VALUE)), defaultArm(variable("original")));
        var selected = caseOf(inner, "selected", literalArm(Long.MIN_VALUE, primitive("+#", variable("selected"), integer(17))),
            defaultArm(primitive("-#", variable("selected"), integer(17))));
        List<Object> throughLet = List.of("let", false, List.of(binding("saved", selected, false, 0)),
            caseOf(variable("saved"), "again", defaultArm(variable("again"))));
        var body = primitive("+#", throughLet, integer(1));
        try (Context context = MainKt.executionContext(false)) {
            var function = context.eval("thc", request(body, List.of(), List.of()));
            compileWarmArm(function, 0, expectedNested(0));
            List<Long> inputs = List.of(1L, Long.MAX_VALUE, Long.MIN_VALUE, 3_000_000_000L, -3_000_000_000L, 128L, -129L, 0L);
            for (long input : inputs) assertEquals(expectedNested(input), function.execute(input).asLong(), "Cold/default input " + input);
            assertTrue(function.invokeMember("compile").asBoolean());
            long before = count(function, "compiledEntries");
            for (long input : inputs) assertEquals(expectedNested(input), function.execute(input).asLong(), "Recompiled input " + input);
            assertTrue(count(function, "compiledEntries") > before);
        }
    }
    private long expectedConstructor(long input, long mask) { return (input == 0 ? 99L : input == 1 ? 7L : input * 5L) ^ mask; }
    @Test void dataAndDefaultArmsPreserveFieldsAndWholeScrutineeBindingsInPrimitiveContext() {
        var constructors = List.of(constructor("Empty", 0, 1), constructor("Pair", 2, 2), constructor("Other", 0, 3));
        var value = caseOf(variable("input"), "choice", literalArm(0, construct("Empty")), literalArm(1, construct("Other")),
            defaultArm(construct("Pair", variable("input"), primitive("*#", variable("input"), integer(3)))));
        var rematched = caseOf(variable("whole"), "sameWhole", dataArm("Pair", List.of("againLeft", "againRight"),
            primitive("+#", primitive("+#", variable("left"), variable("right")), variable("againLeft"))));
        var selected = caseOf(value, "whole", dataArm("Empty", List.of(), integer(99)),
            dataArm("Pair", List.of("left", "right"), rematched), defaultArm(integer(7)));
        long mask = 0x5555_5555_5555_5555L;
        var body = primitive("xor#", selected, integer(mask));
        try (Context context = MainKt.executionContext(false)) {
            var function = context.eval("thc", request(body, constructors, List.of()));
            compileWarmArm(function, 0, expectedConstructor(0, mask));
            List<Long> inputs = List.of(1L, 2L, Long.MIN_VALUE, Long.MAX_VALUE, 3_000_000_000L, -3_000_000_000L, 0L);
            for (long input : inputs) assertEquals(expectedConstructor(input, mask), function.execute(input).asLong(), "Cold constructor/default input " + input);
            assertTrue(function.invokeMember("compile").asBoolean());
            long before = count(function, "compiledEntries");
            for (long input : inputs) assertEquals(expectedConstructor(input, mask), function.execute(input).asLong());
            assertTrue(count(function, "compiledEntries") > before);
        }
    }
    private void checkShared(Value function, long input, boolean compiled) {
        long compiledBefore = count(function, "compiledEntries"), entered = evaluations(function, "shared"), hits = count(function, "thunkHits");
        assertEquals(input * 4L + 2L, function.execute(input).asLong());
        assertEquals(entered + 1L, evaluations(function, "shared"), "One evaluation despite two scrutinee demands");
        assertEquals(hits, count(function, "thunkHits"), "The second case reads the answer written back to its local binding");
        if (compiled) assertTrue(count(function, "compiledEntries") > compiledBefore,
            "Repeated demand of a fresh shared thunk must keep entering installed code");
    }
    @Test void primitiveCaseForcesSharedScrutineesOnceAndKeepsUnselectedFailureLazy() {
        // A lifted case RHS is a fresh shared thunk on each entry. Both later cases demand it.
        var delayed = caseOf(variable("input"), "delayedInput", defaultArm(construct("Box", variable("delayedInput"))));
        var first = caseOf(variable("shared"), "firstBox", dataArm("Box", List.of("firstValue"), variable("firstValue")));
        var second = caseOf(variable("shared"), "secondBox", dataArm("Box", List.of("secondValue"), primitive("+#", variable("secondValue"), integer(1))));
        var failure = caseOf(variable("bottom"), "failedBox", dataArm("Box", List.of("failedValue"), variable("failedValue")));
        var selected = caseOf(variable("input"), "selector", literalArm(-1, failure), defaultArm(primitive("+#", first, second)));
        List<Object> body = List.of("let", false, List.of(binding("shared", delayed)), primitive("*#", selected, integer(2)));
        try (Context context = MainKt.executionContext(false)) {
            var function = context.eval("thc", request(body, List.of(constructor("Box", 1, 1)), List.of(binding("bottom", variable("bottom")))));
            checkShared(function, 0, false);
            compileWarmArm(function, 0, 2);
            for (int i = 0; i < 8; i++) checkShared(function, 0, true);
            for (long input : List.of(3_000_000_000L, Long.MIN_VALUE, Long.MAX_VALUE, 0L)) checkShared(function, input, true);
            assertEquals(0L, count(function, "blackholes"));
            assertEquals(0L, evaluations(function, "bottom"), "The unselected failure must remain untouched");
            long sharedBeforeFailure = evaluations(function, "shared");
            for (int i = 0; i < 2; i++) {
                var error = assertThrows(PolyglotException.class, () -> function.execute(-1L));
                assertTrue(error.getMessage() != null && error.getMessage().contains("Blackhole"), error.getMessage());
            }
            assertEquals(1L, evaluations(function, "bottom"), "The selected failing CAF is entered only once");
            assertEquals(1L, count(function, "blackholes"));
            assertEquals(sharedBeforeFailure, evaluations(function, "shared"), "The failing arm must not demand the other arm's scrutinee");
            checkShared(function, 7, false);
        }
    }
}
