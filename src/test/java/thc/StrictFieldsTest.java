// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import com.oracle.truffle.api.TruffleLanguage;
import java.nio.file.*;
import java.util.*;
import org.graalvm.polyglot.*;
import org.junit.jupiter.api.Test;
import thc.runtime.*;
import static org.junit.jupiter.api.Assertions.*;
import static thc.CoreExecutionTestSupport.*;

/** Isolate constructor-worker CBV obligations before CorePrep inserts cases. */
class StrictFieldsTest {
    private List<Object> variable(String id) { return list("var", id); }
    private List<Object> integer(long value) { return list("lit", "int", Long.toString(value)); }
    private List<Object> constructorValue(String id, int arity) { return list("con", id, arity); }
    private List<Object> apply(List<Object> function, List<List<Object>> args, List<Boolean> lifted) { return list("app", function, args, lifted); }
    private Map<String, Object> binding(String id, List<Object> expression) { return binding(id, expression, 0); }
    private Map<String, Object> binding(String id, List<Object> expression, int arity) {
        return map("id", id, "name", id, "type", "Synthetic", "lifted", true, "arity", arity, "expr", expression);
    }
    private List<Object> lambda(String id, List<Object> body) { return list("lam", list(map("id", id, "name", id, "type", "Int#", "lifted", false, "coercion", false)), body); }
    private Map<String, Object> constructor(String id, List<Boolean> strict, List<Boolean> lifted) {
        return map("id", id, "name", id, "arity", strict.size(), "tag", 1, "kind", "boxed", "strictFields", strict, "fieldLifted", lifted,
            "fieldReps", lifted.stream().map(item -> list(Boolean.FALSE.equals(item) ? "IntRep" : "BoxedRep (Just Lifted)")).toList());
    }
    private Map<String, Object> module(List<Map<String, Object>> constructors, List<Map<String, Object>> bindings) {
        return map("schema", 1, "ghc", "9.14.1", "module", "Synthetic.StrictFields", "constructors", constructors, "bindings", bindings);
    }
    private String request(Map<String, Object> module) { return requestModules(list(module)); }
    private String requestModules(List<Map<String, Object>> modules) { return Json.stringify(map("entry", "entry", "modules", modules)); }
    private List<Object> ignore(List<Object> scrutinee) { return list("case", scrutinee, "ignored", list(list("default", null, list(), integer(41)))); }
    private long count(Value value, String key) { return ((Number) object(Json.parse(value.getMember("diagnostics").asString())).get(key)).longValue(); }
    private void assertBlackhole(Value function) {
        var error = assertThrows(PolyglotException.class, () -> function.execute());
        assertTrue(Objects.toString(error.getMessage(), "").contains("Blackhole"), error.getMessage()); assertEquals(1L, count(function, "blackholes"));
    }
    private Map<String, Object> strictExample(String id, boolean firstClass, Map<String, Object> strict, Map<String, Object> lazy) {
        var function = firstClass ? variable("worker") : constructorValue(id, 1);
        return module(list(strict, lazy), list(binding("bottom", variable("bottom")), binding("worker", constructorValue(id, 1), 1),
            binding("entry", ignore(apply(function, list(variable("bottom")), list(true))))));
    }
    @Test void strictWorkerForcesIgnoredFieldsInDirectAndFirstClassApplications() {
        var strict = constructor("Strict", list(true), list(true)); var lazy = constructor("Lazy", list(false), list(true));
        try (var context = Main.executionContext(false)) {
            for (boolean firstClass : list(false, true)) {
                var lazyFunction = context.eval("thc", request(strictExample("Lazy", firstClass, strict, lazy)));
                assertEquals(41L, lazyFunction.execute().asLong()); assertEquals(0L, count(lazyFunction, "blackholes"));
                var strictFunction = context.eval("thc", request(strictExample("Strict", firstClass, strict, lazy)));
                assertBlackhole(strictFunction); assertBlackhole(strictFunction);
            }
        }
    }
    private Map<String, Object> partialExample(boolean saturate, Map<String, Object> pair, Map<String, Object> box, List<Object> partial) {
        var continuation = saturate ? ignore(apply(variable("pap"), list(apply(constructorValue("Box", 1), list(integer(7)), list(false))), list(true))) : integer(41);
        List<Object> body = list("case", partial, "pap", list(list("default", null, list(), continuation)));
        return module(list(pair, box), list(binding("bottom", variable("bottom")), binding("entry", body)));
    }
    @Test void partialConstructorRetainsStrictArgumentLazilyUntilItIsSaturated() {
        var pair = constructor("Pair", list(true, false), list(true, true)); var box = constructor("Box", list(false), list(false));
        var partial = apply(constructorValue("Pair", 2), list(variable("bottom")), list(true));
        try (var context = Main.executionContext(false)) {
            var partialFunction = context.eval("thc", request(partialExample(false, pair, box, partial)));
            assertEquals(41L, partialFunction.execute().asLong()); assertTrue(count(partialFunction, "papAllocations") > 0);
            assertEquals(0L, count(partialFunction, "blackholes"));
            var saturated = context.eval("thc", request(partialExample(true, pair, box, partial)));
            assertBlackhole(saturated); assertTrue(count(saturated, "papAllocations") > 0);
        }
    }
    @Test void strictFieldsShareTheirWhnfWhileAnOrdinaryFieldKeepsItsUnevaluatedThunk() {
        var box = constructor("Box", list(false), list(false)); var mixed = constructor("Mixed", list(true, false, true), list(true, true, true));
        var shared = apply(constructorValue("Box", 1), list(integer(3_000_000_000L)), list(false));
        var built = apply(constructorValue("Mixed", 3), list(variable("shared"), variable("bottom"), variable("shared")), list(true, true, true));
        var data = module(list(box, mixed), list(binding("shared", shared), binding("bottom", variable("bottom")), binding("entry", built)));
        try (var context = Main.executionContext(false)) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var program = new Program(language, data, false, false);
                var result = (DataValue) Calls.target(program.hostEntryTarget(0), new Object[]{program.entryValue("entry"), new Object[0]});
                var first = result.getLayout().read(result, 0); var second = result.getLayout().read(result, 2);
                assertTrue(first instanceof DataValue, "Strict fields store WHNF, rather than an updated thunk wrapper");
                assertSame(first, second, "Forcing a shared thunk must preserve its value identity");
                var boxed = (DataValue) first; assertEquals(3_000_000_000L, boxed.getLayout().readLong(boxed, 0));
                var ordinary = result.getLayout().read(result, 1); assertTrue(ordinary instanceof Thunk);
                assertEquals(0, ((Thunk) ordinary).getState(), "Ordinary lifted fields remain unentered");
                var metrics = program.diagnostics(); assertEquals(1L, ((Map<?, ?>) metrics.get("thunkEvaluationsByLabel")).get("shared"));
                assertEquals(0L, metrics.get("blackholes"));
            } finally { context.leave(); }
        }
    }
    private String id(List<Map<String, Object>> rows, String name) {
        var matches = rows.stream().filter(row -> name.equals(row.get("name"))).toList(); assertEquals(1, matches.size()); return (String) matches.getFirst().get("id");
    }
    @Test void realGhcStrictConstructorPreservesPrimitiveValuesBeforeAndAfterCompilation() throws Exception {
        var root = Path.of(System.getProperty("thc.projectRoot"));
        var exported = object(Json.parse(Files.readString(root.resolve("build/core/StrictFields.json"))));
        String functionId = id(objects(exported.get("bindings")), "strictConstruct");
        String strictId = id(objects(exported.get("constructors")), "Strict"), boxId = id(objects(exported.get("constructors")), "Box");
        var value = apply(constructorValue(boxId, 1), list(variable("input")), list(false)); var strict = apply(variable(functionId), list(value), list(true));
        var unbox = list("case", variable("boxed"), "inner", list(list("data", boxId, list("number"), variable("number"))));
        List<Object> body = list("case", strict, "outer", list(list("data", strictId, list("boxed"), unbox)));
        var driver = module(list(), list(binding("entry", lambda("input", body), 1)));
        try (var context = Main.executionContext(false)) {
            var function = context.eval("thc", requestModules(list(exported, driver)));
            long[] inputs = {3_000_000_000L, -3_000_000_000L, Long.MIN_VALUE, Long.MAX_VALUE, 0};
            for (int i = 0; i < 8; i++) for (long input : inputs) assertEquals(input, function.execute(input).asLong());
            assertTrue(function.invokeMember("compile").asBoolean()); long before = count(function, "compiledEntries");
            for (long input : inputs) assertEquals(input, function.execute(input).asLong());
            assertTrue(count(function, "compiledEntries") > before);
        }
    }
    @Test void directSaturatedStrictOperandsDoNotAllocateAnImmediatelyForcedThunk() {
        var box = constructor("Box", list(false), list(false)); var strict = constructor("Strict", list(true), list(true));
        var makeBox = binding("makeBox", lambda("number", apply(constructorValue("Box", 1), list(variable("number")), list(false))), 1);
        var operand = apply(variable("makeBox"), list(variable("input")), list(false));
        var constructed = apply(constructorValue("Strict", 1), list(operand), list(true));
        var unbox = list("case", variable("boxed"), "inner", list(list("data", "Box", list("value"), variable("value"))));
        List<Object> body = list("case", constructed, "outer", list(list("data", "Strict", list("boxed"), unbox)));
        var data = module(list(box, strict), list(makeBox, binding("entry", lambda("input", body), 1)));
        try (var context = Main.executionContext(false)) {
            var function = context.eval("thc", request(data));
            for (long input : new long[]{0, 3_000_000_000L, -3_000_000_000L}) assertEquals(input, function.execute(input).asLong());
            assertEquals(0L, count(function, "thunkEvaluations"), "A strict constructor operand must use direct CBV evaluation");
        }
    }
    @Test void unknownStrictFieldLevityStillFailsClosed() {
        var uncertain = constructor("Uncertain", list(true), Collections.singletonList(null));
        var data = module(list(uncertain), list(binding("bottom", variable("bottom")), binding("entry", ignore(apply(constructorValue("Uncertain", 1), list(variable("bottom")), list(true))))));
        try (var context = Main.executionContext(false)) {
            var error = assertThrows(PolyglotException.class, () -> context.eval("thc", request(data)));
            assertTrue(Objects.toString(error.getMessage(), "").contains("Unknown strict constructor field levity"), error.getMessage());
        }
    }
}
