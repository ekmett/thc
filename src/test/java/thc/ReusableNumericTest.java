// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleLanguage;
import java.util.*;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.junit.jupiter.api.Test;
import thc.runtime.*;
import static org.junit.jupiter.api.Assertions.*;
import static thc.CoreExecutionTestSupport.*;

/** Exact existing scalar carriers through the real reusable AST lowerer. */
class ReusableNumericTest {
    private record Scalar(String rep, String literal, String add, Object largest, Object wrapped, Object[] values) {}
    private final List<Scalar> scalars = List.of(
        new Scalar("Int8Rep", "int8", "plusInt8#", 127, -128, new Object[]{-128, 0, 127}),
        new Scalar("Int16Rep", "int16", "plusInt16#", 32767, -32768, new Object[]{-32768, 0, 32767}),
        new Scalar("Int32Rep", "int32", "plusInt32#", Integer.MAX_VALUE, Integer.MIN_VALUE, new Object[]{Integer.MIN_VALUE, 0, Integer.MAX_VALUE}),
        new Scalar("Word8Rep", "word8", "plusWord8#", 255, 0, new Object[]{0, 128, 255}),
        new Scalar("Word16Rep", "word16", "plusWord16#", 65535, 0, new Object[]{0, 32768, 65535}),
        new Scalar("Word32Rep", "word32", "plusWord32#", -1, 0, new Object[]{0, Integer.MIN_VALUE, -1}),
        new Scalar("FloatRep", "float", "plusFloat#", 16777216f, 16777216f,
            new Object[]{-0.0f, Float.MIN_VALUE, Float.POSITIVE_INFINITY, Float.intBitsToFloat(0x7fc01234)}),
        new Scalar("DoubleRep", "double", "+##", 9007199254740992d, 9007199254740992d,
            new Object[]{-0.0d, Double.MIN_VALUE, Double.NEGATIVE_INFINITY, Double.longBitsToDouble(0x7ff8000000001234L)}));
    private Map<String,Object> proof(String rep) {
        return map("kind", rep.equals("FloatRep") ? "float" : rep.equals("DoubleRep") ? "double" : "long",
            "primReps", list(rep), "evaluated", true);
    }
    private Map<String,Object> data() { return map("kind", "data", "primReps", list("BoxedRep (Just Lifted)"), "evaluated", true); }
    private Map<String,Object> parameter(String id, Map<String,Object> proof) {
        return map("id", id, "name", id, "lifted", false, "coercion", false, "rep", proof);
    }
    private List<Object> variable(String id) { return list("var", id); }
    private List<Object> application(List<Object> function, List<List<Object>> args, Map<String,Object> result) {
        return list("app", function, args, Collections.nCopies(args.size(), false), false, false, map("rep", result));
    }
    private Map<String,Object> function(String id, List<Map<String,Object>> args, List<Object> body, Map<String,Object> result) {
        return map("id", id, "name", id, "arity", args.size(), "lifted", true,
            "expr", list("lam", args, body, map("resultRep", result)));
    }
    private List<Object> select(Scalar scalar, List<Object> body) {
        var p = proof(scalar.rep());
        return list("case", application(variable("box"), list(variable("x")), data()), "boxed",
            list(list("data", "Box", list("field"), body, map("binders", list(parameter("field", p))))),
            map("rep", p, "binder", map("id", "boxed", "name", "boxed", "lifted", true, "rep", data())));
    }
    private Map<String,Object> module(Scalar scalar) {
        var p = proof(scalar.rep());
        var sum = application(list("prim", scalar.add()), list(variable("copy"), variable("one")), p);
        var closure = list("lam", list(parameter("unused", proof("IntRep"))), sum, map("resultRep", p));
        var join = function("done", list(parameter("answer", p)), variable("answer"), p);
        join.put("joinValueArity", 1); join.put("joinResultRep", p);
        var joined = list("let", false, list(join), application(variable("done"),
            list(application(closure, list(list("lit", "int", "0")), p)), p), map("rep", p));
        var alternatives = new ArrayList<>(list(list("default", null, list(), joined)));
        if (!scalar.rep().equals("FloatRep") && !scalar.rep().equals("DoubleRep"))
            alternatives.addFirst(list("lit", list(scalar.literal(), "0"), list(), variable("one")));
        var body = list("case", variable("field"), "copy",
            alternatives,
            map("rep", p, "binder", parameter("copy", p)));
        var bindings = new ArrayList<>(list(function("box", list(parameter("x", p)),
                application(list("con", "Box", 1), list(variable("x")), data()), data()),
            function("calculate", list(parameter("x", p)), select(scalar, body), p),
            function("identity", list(parameter("x", p)), select(scalar, variable("field")), p),
            map("id", "one", "name", "one", "arity", 0, "lifted", false, "rep", p,
                "expr", list("lit", scalar.literal(), "1", map("rep", p)))));
        if (scalar.rep().equals("Int16Rep")) bindings.add(function("countDown", list(parameter("n", p)),
            list("case", variable("n"), "remaining", list(
                list("lit", list("int16", "0"), list(), list("lit", "int16", "0")),
                list("default", null, list(), application(variable("countDown"), list(
                    application(list("prim", "subInt16#"), list(variable("remaining"), variable("one")), p)), p))),
                map("rep", p, "binder", parameter("remaining", p))), p));
        return map("schema", 1, "ghc", "9.14.1", "module", "Synthetic.ReusableNumeric", "instrument", true,
            "constructors", list(map("id", "Box", "name", "Box", "kind", "boxed", "arity", 1,
                "strictFields", list(false), "fieldLifted", list(false), "fieldReps", list(list(scalar.rep())), "fieldTypes", list(p))),
            "bindings", bindings);
    }
    private Program.PreparedCode prepare(Engine engine, Map<String,Object> module) {
        try (var context = Context.newBuilder("thc").engine(engine).build()) {
            context.initialize("thc"); context.enter();
            try {
                var entries = new ArrayList<>(List.of("calculate", "identity", "one"));
                if (((List<?>)module.get("bindings")).stream().anyMatch(value -> ((Map<?,?>)value).get("id").equals("countDown")))
                    entries.add("countDown");
                return Program.prepareCode(TruffleLanguage.LanguageReference.create(Language.class).get(null), module, entries);
            }
            finally { context.leave(); }
        }
    }
    private void assertExact(Object expected, Object actual) {
        assertEquals(expected.getClass(), actual.getClass());
        if (expected instanceof Float value) assertEquals(Float.floatToRawIntBits(value), Float.floatToRawIntBits((Float)actual));
        else if (expected instanceof Double value) assertEquals(Double.doubleToRawLongBits(value), Double.doubleToRawLongBits((Double)actual));
        else assertEquals(expected, actual);
    }
    private Object call(Program program, String id, Object value, boolean compiled) throws Exception {
        var closure = (Closure)program.entryValue(id);
        if (compiled) {
            var type = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget");
            Truffle.getRuntime().getClass().getMethod("bypassedInstalledCode", type).invoke(Truffle.getRuntime(), closure.target);
        }
        return Calls.target(program.hostEntryTarget(1), new Object[]{closure, new Object[]{value}});
    }
    @SuppressWarnings("unchecked") private void checks(boolean compiled) throws Exception {
        try (var engine = Engine.newBuilder().allowExperimentalOptions(true)
                .option("engine.Compilation", Boolean.toString(compiled)).option("engine.BackgroundCompilation", "false")
                .option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw").build()) {
            for (var scalar : scalars) {
                var code = prepare(engine, module(scalar));
                if (compiled) {
                    var field = Program.PreparedCode.class.getDeclaredField("targets"); field.setAccessible(true);
                    var type = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget");
                    for (var target : (List<RootCallTarget>)field.get(code)) {
                        assertEquals(false, type.getMethod("wasExecuted").invoke(target));
                        assertEquals(true, type.getMethod("prepareForAOT").invoke(target));
                        type.getMethod("compile", boolean.class).invoke(target, true);
                        assertEquals(false, type.getMethod("wasExecuted").invoke(target));
                    }
                    code.requireInstalledCode();
                }
                for (int instance = 0; instance < 2; instance++) try (var context = Context.newBuilder("thc").engine(engine).build()) {
                    context.initialize("thc"); context.enter();
                    try {
                        var program = code.newInstance(TruffleLanguage.LanguageReference.create(Language.class).get(null));
                        assertExact(scalar.wrapped(), call(program, "calculate", scalar.largest(), compiled));
                        if (compiled) code.requireInstalledCode();
                        for (Object value : scalar.values()) assertExact(value, call(program, "identity", value, compiled));
                        if (scalar.largest() instanceof Integer) assertExact(1, call(program, "calculate", 0, compiled));
                        if (scalar.rep().equals("Int16Rep")) assertExact(0, call(program, "countDown", 2, compiled));
                        assertEquals(0, program.diagnostics().get("loweredRootCount"));
                        if (compiled) code.requireInstalledCode();
                    } finally { context.leave(); }
                }
            }
        }
    }

    @Test void numericFieldsCasesAndCapturedArgumentsKeepTheirExactCarriers() throws Exception { checks(false); }
    @Test void firstCompiledNumericEntriesNeedNoTraining() throws Exception {
        String before = System.getProperty("thc.requireCompiledCode"); System.setProperty("thc.requireCompiledCode", "true");
        try { checks(true); }
        finally { if (before == null) System.clearProperty("thc.requireCompiledCode"); else System.setProperty("thc.requireCompiledCode", before); }
    }
}
