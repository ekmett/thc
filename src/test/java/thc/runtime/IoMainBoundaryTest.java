// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.Test;
import thc.Json;
import thc.Main;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic boundary adversaries; genuine optimized Core has a separate native fixture. */
@SuppressWarnings("unchecked")
class IoMainBoundaryTest {
    private final Map<String, Object> state = Map.of("kind", "void", "primReps", List.of(), "evaluated", true);
    private final Map<String, Object> integer = Map.of("kind", "long", "primReps", List.of("IntRep"), "evaluated", true);
    private final Map<String, Object> unit = Map.of("kind", "data", "primReps", List.of("BoxedRep (Just Lifted)"), "evaluated", true);
    private final Map<String, Object> closure = plus(unit, "kind", "closure");
    private final String unitId = "ghc-internal:GHC.Internal.Tuple.()";
    private static Map<String, Object> plus(Map<String, Object> source, String key, Object value) {
        var result = new LinkedHashMap<>(source); result.put(key, value); return result;
    }
    @SafeVarargs private static Map<String, Object> tuple(Map<String, Object>... fields) {
        var reps = new ArrayList<String>();
        for (var field : fields) reps.addAll((List<String>) field.get("primReps"));
        return Map.of("kind", "unknown", "aggregate", "unboxed-tuple", "components", Arrays.asList(fields), "primReps", reps, "evaluated", true);
    }
    private final Map<String, Object> result = tuple(state, unit);
    private List<Object> v(String id) { return List.of("var", id, Map.of("rep", closure)); }
    private List<Object> n(int value) { return List.of("lit", "int", Integer.toString(value), Map.of("rep", integer)); }
    private List<Object> app(List<Object> function, List<List<Object>> args) {
        return List.of("app", function, args, Collections.nCopies(args.size(), false), false, false, Map.of("rep", closure));
    }
    private Map<String, Object> binding(String id, List<Object> expression) {
        return Map.of("id", id, "name", id, "type", "IO ()", "arity", 0, "lifted", true, "rep", closure, "expr", expression);
    }
    private Map<String, Object> fixture() { return fixture(2); }
    private Map<String, Object> fixture(int prefix) { return fixture(prefix, "State# RealWorld", state, result, prefix, unitId); }
    private Map<String, Object> fixture(int prefix, String type, Map<String, Object> input, Map<String, Object> output,
                                       int supplied, String returnedConstructor) {
        var arguments = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < prefix; i++) arguments.add(Map.of("id", "x" + i, "name", "x" + i,
            "type", "Int#", "lifted", false, "rep", integer));
        var last = new LinkedHashMap<String, Object>();
        last.put("id", "s"); last.put("name", "s"); last.put("type", type); last.put("lifted", false); last.put("rep", input);
        arguments.add(last);
        var body = List.of("app", List.of("con", "StateUnit", 2, Map.of("rep", closure)),
            List.of(List.of("void", Map.of("rep", state)), List.of("con", returnedConstructor, 0, Map.of("rep", unit))),
            List.of(false, true), true, true, Map.of("rep", result));
        var worker = binding("worker", List.of("lam", arguments, body, Map.of("rep", closure, "resultRep", output)));
        List<Object> expression;
        if (prefix == 0 && supplied == 0) expression = v("worker");
        else {
            var values = new ArrayList<List<Object>>();
            for (int i = 0; i < supplied; i++) values.add(n(i));
            expression = app(v("worker"), values);
        }
        return Map.of("schema", 1, "ghc", "9.14.1", "bindings", List.of(binding("main", expression), worker),
            "constructors", List.of(Map.of("id", "StateUnit", "name", "StateUnit", "kind", "unboxed-tuple", "arity", 2),
                Map.of("id", returnedConstructor, "name", "()", "kind", "boxed", "arity", 0,
                    "strictFields", List.of(), "fieldLifted", List.of(), "fieldReps", List.of())));
    }
    private Value load(Context context, String backend, Map<String, Object> module) { return load(context, backend, module, false, null); }
    private Value load(Context context, String backend, Map<String, Object> module, boolean diagnostic, String shutdown) {
        var document = new LinkedHashMap<String, Object>();
        document.put("entry", "main"); document.put("ioMain", true); document.put("backend", backend);
        document.put("diagnosticUnsupported", diagnostic); document.put("modules", List.of(module));
        if (shutdown != null) document.put("shutdownEntry", shutdown);
        return context.eval("thc", Json.stringify(document));
    }
    private static List<Map<String, Object>> bindings(Map<String, Object> module) {
        return (List<Map<String, Object>>) module.get("bindings");
    }
    private Map<String, Object> mainExpression(Map<String, Object> module, List<Object> expression) {
        var bindings = new ArrayList<Map<String, Object>>();
        for (var binding : bindings(module)) bindings.add("main".equals(binding.get("id")) ? plus(binding, "expr", expression) : binding);
        return plus(module, "bindings", bindings);
    }
    private static String message(Throwable failure) { return failure.getMessage() == null ? "" : failure.getMessage(); }
    private void rejects(Map<String, Object> module) {
        for (var backend : List.of("ast", "bytecode")) try (var context = Main.executionContext()) {
            var error = assertThrows(PolyglotException.class, () -> load(context, backend, module));
            assertTrue(message(error).contains("IO main"), backend + ": " + error.getMessage());
        }
    }
    @Test void exactStateLambdaAndPapAliasesRunThroughBothBackends() {
        for (var backend : List.of("ast", "bytecode")) for (int prefix : List.of(0, 1, 3)) try (var context = Main.executionContext()) {
            var direct = fixture(prefix);
            var alias = plus(plus(bindings(direct).getFirst(), "id", "alias"), "name", "alias");
            var main = plus(bindings(direct).getFirst(), "expr", v("alias"));
            var definitions = new ArrayList<Map<String, Object>>(); definitions.add(main);
            definitions.addAll(bindings(direct).subList(1, bindings(direct).size())); definitions.add(alias);
            var action = load(context, backend, plus(direct, "bindings", definitions));
            assertFalse(action.canExecute());
            for (int i = 0; i < 2; i++) assertTrue(action.invokeMember("runIO").asBoolean());
            var diagnostics = (Map<String, Object>) Json.parse(action.getMember("diagnostics").asString());
            assertEquals(0L, ((Number) diagnostics.get("unsupportedTraps")).longValue());
        }
    }
    @Test void executableShutdownKeepsBothIoRootsAndRunsOnlyOnce() {
        var original = fixture();
        var shutdown = binding("shutdown", app(v("worker"), List.of(n(2), n(3))));
        var definitions = new ArrayList<>(bindings(original)); definitions.add(shutdown);
        var module = plus(original, "bindings", definitions);
        for (var backend : List.of("ast", "bytecode")) try (var context = Main.executionContext()) {
            var action = load(context, backend, module, false, "shutdown");
            assertTrue(action.invokeMember("runIO").asBoolean());
            var repeated = assertThrows(PolyglotException.class, () -> action.invokeMember("runIO"));
            assertTrue(message(repeated).contains("already started"));
            // A raw --run-io action remains reusable for low-level fixtures.
            var raw = load(context, backend, module);
            for (int i = 0; i < 2; i++) assertTrue(raw.invokeMember("runIO").asBoolean());
            var missing = assertThrows(PolyglotException.class, () -> load(context, backend, module, false, "missing"));
            assertTrue(message(missing).contains("Missing or ambiguous entry"));
            var wrongBindings = new ArrayList<>(bindings(original)); wrongBindings.add(plus(shutdown, "type", "IO Int"));
            var wrong = plus(module, "bindings", wrongBindings);
            var invalid = assertThrows(PolyglotException.class, () -> load(context, backend, wrong, false, "shutdown"));
            assertTrue(message(invalid).contains("IO ()"));
        }
    }
    @Test void nestedPapsConsumeLogicalZeroWidthPrefixWithoutLosingRealStateBinder() {
        var original = fixture(); var worker = bindings(original).get(1);
        var expression = (List<Object>) worker.get("expr");
        var formals = (List<Map<String, Object>>) expression.get(1);
        var newFormals = new ArrayList<Map<String, Object>>();
        newFormals.add(plus(plus(formals.get(0), "type", "State# OtherWorld"), "rep", state));
        newFormals.addAll(formals.subList(1, formals.size()));
        var newExpression = new ArrayList<>(expression); newExpression.set(1, newFormals);
        var newWorker = plus(worker, "expr", newExpression);
        var partial = binding("partial", app(v("worker"), List.of(List.of("void", Map.of("rep", state)))));
        var module = plus(original, "bindings", List.of(binding("main", app(v("partial"), List.of(n(1)))), newWorker, partial));
        for (var backend : List.of("ast", "bytecode")) try (var context = Main.executionContext()) {
            assertTrue(load(context, backend, module).invokeMember("runIO").asBoolean());
        }
    }
    @Test void zeroWidthImpostorsAndWrongStateRepresentationsRemainRejected() {
        for (var type : Arrays.asList(null, "Proxy# a", "State# s", "(# #)", "Coercion#")) rejects(fixture(2, type, state, result, 2, unitId));
        rejects(fixture(2, "State# RealWorld", tuple(), result, 2, unitId));
        rejects(fixture(2, "State# RealWorld", integer, result, 2, unitId));
    }
    @Test void saturatedOversaturatedUnknownAndCyclicHeadsCannotInventRemainingFormals() {
        for (int supplied : List.of(0, 1, 3, 4)) rejects(fixture(2, "State# RealWorld", state, result, supplied, unitId));
        rejects(mainExpression(fixture(), app(v("unknown"), List.of(n(1), n(2)))));
        var original = fixture(); var worker = plus(bindings(original).get(1), "expr", v("main"));
        rejects(plus(original, "bindings", List.of(bindings(original).get(0), worker)));
    }
    @Test void exactDeclaredIoAndStateUnitResultChecksRemainInForce() {
        for (var output : List.of(unit, tuple(unit), tuple(integer, unit), tuple(state, integer), tuple(state, state, unit)))
            rejects(fixture(2, "State# RealWorld", state, output, 2, unitId));
        var original = fixture(); var wrongMain = plus(bindings(original).get(0), "type", "IO Int");
        for (var backend : List.of("ast", "bytecode")) try (var context = Main.executionContext()) {
            assertThrows(PolyglotException.class, () -> load(context, backend, plus(original, "bindings", List.of(wrongMain, bindings(original).get(1)))));
            assertThrows(PolyglotException.class, () -> load(context, backend, original, true, null));
        }
    }
    @Test void aBoxedResultImpostorStillFailsTheRuntimeUnitConstructorCheck() {
        for (var backend : List.of("ast", "bytecode")) try (var context = Main.executionContext()) {
            var action = load(context, backend, fixture(2, "State# RealWorld", state, result, 2, "NotUnit"));
            var error = assertThrows(PolyglotException.class, () -> action.invokeMember("runIO"));
            assertTrue(message(error).contains("did not return boxed unit"));
        }
    }
    @Test void consumedPrefixProofsStillReceiveStrictWholeProgramValidation() {
        var original = fixture(); var worker = bindings(original).get(1);
        var expression = (List<Object>) worker.get("expr");
        var formals = (List<Map<String, Object>>) expression.get(1);
        var corrupt = plus(formals.get(0), "rep", plus(integer, "primReps", List.of("FloatRep")));
        var changedFormals = new ArrayList<Map<String, Object>>(); changedFormals.add(corrupt); changedFormals.addAll(formals.subList(1, formals.size()));
        var changedExpression = new ArrayList<>(expression); changedExpression.set(1, changedFormals);
        var changed = plus(worker, "expr", changedExpression);
        var module = plus(original, "bindings", List.of(bindings(original).get(0), changed));
        for (var backend : List.of("ast", "bytecode")) try (var context = Main.executionContext()) {
            assertThrows(PolyglotException.class, () -> load(context, backend, module));
        }
    }
}
