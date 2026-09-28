// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import java.util.*;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static thc.CoreExecutionTestSupport.*;

/** Typed expression paths remain correct across the Object-valued guest call boundary. */
class TypedApplicationTest {
    private List<Object> variable(String id) { return list("var", id); }
    private List<Object> integer(long value) { return list("lit", "int", Long.toString(value)); }
    private Map<String, Object> parameter(String id) { return parameter(id, false); }
    private Map<String, Object> parameter(String id, boolean lifted) { return map("id", id, "name", id, "type", lifted ? "a" : "Int#", "lifted", lifted, "coercion", false); }
    private List<Object> lambda(List<Map<String, Object>> parameters, List<Object> body) { return list("lam", parameters, body); }
    private List<Object> lambda(String id, List<Object> body) { return lambda(list(parameter(id)), body); }
    private List<Object> apply(List<Object> function, List<List<Object>> arguments, List<Boolean> lifted) { return apply(function, arguments, lifted, false); }
    private List<Object> apply(List<Object> function, List<List<Object>> arguments, List<Boolean> lifted, boolean knownWhnf) {
        return knownWhnf ? list("app", function, arguments, lifted, true, true) : list("app", function, arguments, lifted);
    }
    @SafeVarargs private final List<Object> primitive(String name, List<Object>... arguments) {
        return apply(list("prim", name), Arrays.asList(arguments), Collections.nCopies(arguments.length, false));
    }
    private Map<String, Object> binding(String id, List<Object> expression) { return binding(id, expression, 0, true); }
    private Map<String, Object> binding(String id, List<Object> expression, int arity) { return binding(id, expression, arity, true); }
    private Map<String, Object> binding(String id, List<Object> expression, int arity, boolean lifted) {
        return map("id", id, "name", id, "type", lifted ? "Synthetic" : "Int#", "lifted", lifted, "arity", arity, "expr", expression);
    }
    private List<Object> literalArm(long value, List<Object> body) { return list("lit", list("int", Long.toString(value)), list(), body); }
    private List<Object> defaultArm(List<Object> body) { return list("default", null, list(), body); }
    @SafeVarargs private final List<Object> caseOf(List<Object> scrutinee, String name, List<Object>... alternatives) { return list("case", scrutinee, name, Arrays.asList(alternatives)); }
    private String request(List<Object> body) { return request(body, list(), list()); }
    private String request(List<Object> body, List<Map<String, Object>> bindings) { return request(body, bindings, list()); }
    private String request(List<Object> body, List<Map<String, Object>> bindings, List<Map<String, Object>> constructors) {
        var all = new ArrayList<>(bindings); all.add(binding("entry", lambda("input", body), 1));
        return Json.stringify(map("entry", "entry", "backend", "ast", "instrument", true,
            "modules", list(map("schema", 1, "ghc", "9.14.1", "module", "Synthetic.TypedApplications", "constructors", constructors, "bindings", all))));
    }
    private long count(Value function, String name) { return ((Number) object(Json.parse(function.getMember("diagnostics").asString())).get(name)).longValue(); }
    private void warmAndCompile(Value function, long input, long expected) {
        for (int i = 0; i < 40; i++) assertEquals(expected, function.execute(input).asLong());
        assertTrue(function.invokeMember("compile").asBoolean()); long before = count(function, "compiledEntries");
        assertEquals(expected, function.execute(input).asLong()); assertTrue(count(function, "compiledEntries") > before);
    }
    private final List<Long> wideInputs = list(1L, -1L, 3_000_000_000L, -3_000_000_000L, Long.MIN_VALUE, Long.MAX_VALUE, 128L, 0L);
    private long capturedExpected(long input) { return input == 0 ? Long.MIN_VALUE + 17L : input * 3L; }
    @Test void closedAndCapturedLambdasForwardNumericCaseAndLetResultsToPrimitiveConsumers() {
        var directBody = caseOf(variable("direct"), "directValue", literalArm(0, integer(Long.MIN_VALUE)), defaultArm(variable("directValue")));
        var direct = apply(lambda("direct", directBody), list(variable("input")), list(false));
        var selected = caseOf(variable("delta"), "deltaValue",
            literalArm(0, primitive("+#", variable("input"), integer(17))), defaultArm(primitive("+#", variable("input"), variable("deltaValue"))));
        List<Object> capturedBody = list("let", false, list(binding("saved", selected, 0, false)), caseOf(variable("saved"), "result", defaultArm(variable("result"))));
        var captured = binding("captured", lambda("delta", capturedBody), 1);
        var call = apply(variable("captured"), list(variable("input")), list(false));
        List<Object> body = list("let", false, list(captured), primitive("+#", direct, call));
        try (var context = Main.executionContext(false)) {
            var function = context.eval("thc", request(body)); warmAndCompile(function, 0L, capturedExpected(0));
            for (long input : wideInputs) assertEquals(capturedExpected(input), function.execute(input).asLong(), "Cold arm/capture " + input);
            assertTrue(function.invokeMember("compile").asBoolean()); long before = count(function, "compiledEntries");
            for (long input : wideInputs.reversed()) assertEquals(capturedExpected(input), function.execute(input).asLong(), "Fresh capture " + input);
            assertTrue(count(function, "compiledEntries") > before);
        }
    }
    private long partialExpected(long input) { return input == 0 ? 8L : input * 2L + 6L; }
    @Test void partialAndOverapplicationReachTheNumericResultAfterAnIntermediateCapturedFunction() {
        var numeric = caseOf(variable("extra"), "extraValue",
            literalArm(0, primitive("+#", variable("base"), variable("adjustment"))),
            defaultArm(primitive("+#", primitive("-#", variable("base"), variable("adjustment")), variable("extraValue"))));
        var maker = binding("maker", lambda(list(parameter("base"), parameter("adjustment")), lambda("extra", numeric)), 2);
        var partial = apply(variable("maker"), list(variable("input")), list(false), true);
        var over = apply(variable("partial"), list(integer(1), variable("input")), list(false, false));
        List<Object> body = list("let", false, list(binding("partial", partial)), primitive("+#", over, integer(7)));
        try (var context = Main.executionContext(false)) {
            var function = context.eval("thc", request(body, list(maker))); warmAndCompile(function, 0L, partialExpected(0));
            assertTrue(count(function, "papAllocations") > 0, "The test must exercise an actual supplied prefix");
            for (long input : wideInputs) assertEquals(partialExpected(input), function.execute(input).asLong(), "Surplus argument " + input);
            assertTrue(function.invokeMember("compile").asBoolean()); long before = count(function, "compiledEntries");
            for (long input : wideInputs.reversed()) assertEquals(partialExpected(input), function.execute(input).asLong(), "Preserved prefix/captures " + input);
            assertTrue(count(function, "compiledEntries") > before);
        }
    }
    private long polymorphicExpected(long input) { return input == 0 ? 12L : input * 2L + 1L; }
    @Test void aPolymorphicLiftedResultCanChangeFromDataToClosureAfterCompilation() {
        // Box and function are legitimate lifted instantiations; the final result remains Int#.
        var identity = binding("identity", lambda(list(parameter("value", true)), variable("value")), 1);
        var box = map("id", "Box", "name", "Box", "arity", 1, "tag", 1, "kind", "boxed",
            "strictFields", list(false), "fieldLifted", list(false), "fieldReps", list(list("IntRep")));
        var boxed = apply(list("con", "Box", 1), list(variable("input")), list(false));
        var throughIdentity = apply(variable("identity"), list(boxed), list(true));
        var unboxed = caseOf(throughIdentity, "box", list("data", "Box", list("payload"), primitive("+#", variable("payload"), integer(11))));
        var captured = lambda("argument", primitive("+#", variable("input"), variable("argument")));
        var returnedFunction = apply(variable("identity"), list(captured), list(true));
        var applied = apply(returnedFunction, list(variable("input")), list(false));
        var selected = caseOf(variable("input"), "choice", literalArm(0, unboxed), defaultArm(applied));
        var body = primitive("+#", selected, integer(1));
        try (var context = Main.executionContext(false)) {
            var function = context.eval("thc", request(body, list(identity), list(box))); warmAndCompile(function, 0, polymorphicExpected(0));
            for (long input : wideInputs) assertEquals(polymorphicExpected(input), function.execute(input).asLong(), "Cold result category " + input);
            assertTrue(function.invokeMember("compile").asBoolean()); long before = count(function, "compiledEntries");
            for (int i = 0; i < 4; i++) {
                assertEquals(12L, function.execute(0L).asLong());
                assertEquals(polymorphicExpected(3_000_000_000L + i), function.execute(3_000_000_000L + i).asLong());
            }
            assertTrue(count(function, "compiledEntries") > before);
        }
    }
}
