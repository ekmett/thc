// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.TruffleLanguage;
import java.util.*;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import thc.Language;
import static org.junit.jupiter.api.Assertions.*;
import static thc.runtime.RepresentationTestSupport.*;

/** Malformed Core boundary controls paired across both lowering backends. */
class TypedTupleFieldLevityTest {
    private Map<String, Object> scalar(String kind, String rep) { return map("kind", kind, "primReps", list(rep), "evaluated", true); }
    private final Map<String, Object> integer = scalar("long", "IntRep"), int32 = scalar("long", "Int32Rep"), closure = scalar("closure", "BoxedRep (Just Lifted)");
    private final Map<String, Object> state = map("kind", "void", "primReps", list(), "evaluated", true);
    private final Map<String, Object> vector = map("kind", "vector", "evaluated", true, "primReps", list("VecRep 4 Int32ElemRep"), "vector", map("lanes", 4, "element", "Int32ElemRep"));
    private final Map<String, Object> sum = map("kind", "unknown", "evaluated", true, "aggregate", "unboxed-sum", "alternatives", Collections.nCopies(4, integer),
        "primReps", list("WordRep", "WordRep"), "tagSlot", 0, "alternativeSlots", Collections.nCopies(4, list(1)));
    @SafeVarargs private final Map<String, Object> tuple(Map<String, Object>... fields) {
        var reps = new ArrayList<Object>(); for (var field : fields) reps.addAll((List<?>) field.get("primReps"));
        return map("kind", "unknown", "evaluated", true, "aggregate", "unboxed-tuple", "components", list(fields), "primReps", reps);
    }
    private List<Object> variable(String id) { return variable(id, integer); }
    private List<Object> variable(String id, Map<String, Object> rep) { return list("var", id, map("rep", rep)); }
    private Map<String, Object> parameter(String id) { return parameter(id, integer); }
    private Map<String, Object> parameter(String id, Map<String, Object> rep) { return map("id", id, "name", id, "lifted", false, "rep", rep); }
    private List<Object> number(long x) { return list("lit", "int", Long.toString(x), map("rep", integer)); }
    private List<Object> application(List<Object> fn, List<List<Object>> args, Map<String, Object> rep) { return application(fn, args, rep, Collections.nCopies(args.size(), false)); }
    private List<Object> application(List<Object> fn, List<List<Object>> args, Map<String, Object> rep, List<?> flags) { return list("app", fn, args, flags, false, false, map("rep", rep)); }
    private List<Object> primitive(String id, List<List<Object>> args) { return primitive(id, args, integer); }
    private List<Object> primitive(String id, List<List<Object>> args, Map<String, Object> rep) { return application(list("prim", id), args, rep); }
    @SafeVarargs private final List<Object> pack(Map<String, Object> rep, List<Object>... fields) { return fields.length == 0 ? list("con", "T0", 0, map("rep", rep)) : application(list("con", "T" + fields.length, fields.length), list(fields), rep); }
    private List<Object> unpack(List<Object> value, Map<String, Object> rep, List<String> ids, List<Object> body) {
        var binders = new ArrayList<Map<String, Object>>(); var components = objects(rep.get("components"));
        for (int i = 0; i < Math.min(ids.size(), components.size()); i++) binders.add(parameter(ids.get(i), components.get(i)));
        return list("case", value, "whole" + ids.getFirst(), list(list("data", "T" + ids.size(), ids, body, map("binders", binders))),
            map("rep", integer, "binder", parameter("whole" + ids.getFirst(), rep)));
    }
    private List<Object> consumeSum(List<Object> value) { return list("case", value, "wholeSum", list(list("data", "S4", list("sumPayload"), variable("sumPayload"), map("binders", list(parameter("sumPayload"))))), map("rep", integer, "binder", parameter("wholeSum", sum))); }
    private record Shape(Map<String, Object> proof, List<Object> payload, List<Object> consumed) {}
    private Map<String, Object> fixture(String kind, Object flag) {
        var sumValue = application(list("con", "S4", 1), list(number(17)), sum); var nested = tuple(sum, state, tuple());
        var shape = switch (kind) {
            case "vector" -> {
                var payload = primitive("broadcastInt32X4#", list(primitive("intToInt32#", list(number(17)), int32)), vector); var lanes = tuple(int32, int32, int32, int32);
                var consumed = unpack(primitive("unpackInt32X4#", list(variable("payload", vector)), lanes), lanes, list("lane0", "lane1", "lane2", "lane3"), primitive("int32ToInt#", list(variable("lane0", int32))));
                yield new Shape(vector, payload, consumed);
            }
            case "sum" -> new Shape(sum, sumValue, consumeSum(variable("payload", sum)));
            case "nestedTuple" -> new Shape(nested, pack(nested, sumValue, list("void", map("rep", state)), pack(tuple())),
                unpack(variable("payload", nested), nested, list("innerSum", "state", "empty"), consumeSum(variable("innerSum", sum))));
            default -> throw new IllegalStateException("Unknown test shape: " + kind);
        };
        var pair = tuple(shape.proof(), integer); var value = application(list("con", "T2", 2), list(shape.payload(), variable("input")), pair, list(flag, false));
        var body = unpack(value, pair, list("payload", "neighbour"), primitive("+#", list(shape.consumed(), variable("neighbour"))));
        var lambda = list("lam", list(parameter("input")), body, map("rep", closure, "resultRep", integer, "entryStrict", list(false)));
        var constructors = new ArrayList<Map<String, Object>>();
        for (int i = 0; i <= 4; i++) constructors.add(map("id", "T" + i, "name", "T" + i, "kind", "unboxed-tuple", "arity", i));
        for (int i = 1; i <= 4; i++) constructors.add(map("id", "S" + i, "name", "S" + i, "kind", "unboxed-sum", "arity", 1, "sumArity", 4, "tag", i));
        return map("constructors", constructors, "bindings", list(map("id", "entry", "name", "entry", "lifted", true, "rep", closure, "expr", lambda)));
    }
    private void execute(Language language, String backend, String kind, Object flag) {
        var module = fixture(kind, flag); ExecutableProgram program = backend.equals("ast") ? new Program(language, module) : new BytecodeProgram(language, module);
        // Resolve and execute: deferred loading must not hide malformed bodies.
        for (long input : new long[]{Long.MIN_VALUE, 0L, Long.MAX_VALUE}) assertEquals(input + 17L, Calls.target(program.hostEntryTarget(1), new Object[]{program.entryValue("entry"), new Object[]{input}}));
    }
    private record Flag(String name, Object value) {}
    @TestFactory List<DynamicTest> typedTupleFieldsRequireBooleanFalse() {
        var tests = new ArrayList<DynamicTest>();
        for (var backend : list("ast", "bytecode")) for (var kind : list("vector", "sum", "nestedTuple"))
            for (var flag : list(new Flag("false", false), new Flag("true", true), new Flag("nonBoolean", "false")))
                tests.add(DynamicTest.dynamicTest(backend + "/" + kind + "/" + flag.name(), () -> {
                    try (var context = Context.newBuilder("thc").build()) {
                        context.initialize("thc"); context.enter();
                        try {
                            var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                            if (Boolean.FALSE.equals(flag.value())) execute(language, backend, kind, flag.value());
                            else { var fault = assertThrows(RuntimeFault.class, () -> execute(language, backend, kind, flag.value())); assertEquals("Typed tuple field cannot be lifted", fault.getMessage()); }
                        } finally { context.leave(); }
                    }
                }));
        return tests;
    }
}
