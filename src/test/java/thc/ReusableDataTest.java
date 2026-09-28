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

/** The actual AST lowerer, with lazy list storage owned by each prepared-code load. */
class ReusableDataTest {
    private Map<String,Object> word() { return map("kind", "long", "evaluated", true, "primReps", list("IntRep")); }
    private Map<String,Object> data(boolean evaluated) { return map("kind", "data", "evaluated", evaluated, "primReps", list("BoxedRep (Just Lifted)")); }
    private Map<String,Object> parameter(String id, boolean lifted) {
        return map("id", id, "name", id, "type", lifted ? "List" : "Int#", "lifted", lifted, "rep", lifted ? data(false) : word());
    }
    private List<Object> variable(String id) { return list("var", id); }
    private List<Object> literal(long value) { return list("lit", "int", Long.toString(value)); }
    private List<Object> arithmetic(String op, List<Object> a, List<Object> b) {
        return list("app", list("prim", op), list(a, b), list(false, false), null, null, map("rep", word()));
    }
    private List<Object> make(List<Object> n) {
        return list("app", variable("make"), list(n), list(false), null, null, map("rep", data(false)));
    }
    private List<Object> fold(List<Object> xs, List<Object> acc) {
        return list("app", variable("fold"), list(xs, acc), list(true, false), null, null, map("rep", word()));
    }
    private Map<String,Object> binding(String id, List<Object> body) {
        return map("id", id, "name", id, "type", "Synthetic", "lifted", true, "expr", body);
    }
    private Map<String,Object> function(String id, List<Map<String,Object>> args, List<Object> body, Map<String,Object> result) {
        var binding = binding(id, list("lam", args, body, map("resultRep", result)));
        binding.put("arity", args.size()); return binding;
    }
    private Map<String,Object> module() {
        var nil = list("con", "Nil", 0, map("rep", data(true)));
        var cons = list("app", list("con", "Cons", 2), list(variable("n"),
            make(arithmetic("-#", variable("n"), literal(1)))), list(false, true), null, null, map("rep", data(true)));
        var makeBody = list("case", variable("n"), "remaining", list(
            list("lit", list("int", "0"), list(), nil), list("default", null, list(), cons)),
            map("rep", data(true), "binder", parameter("remaining", false)));
        var foldBody = list("case", variable("xs"), "list", list(
            list("data", "Nil", list(), variable("acc")),
            list("data", "Cons", list("head", "tail"), fold(variable("tail"), arithmetic("+#", variable("acc"), variable("head"))),
                map("binders", list(parameter("head", false), parameter("tail", true))))),
            map("rep", word(), "binder", parameter("list", true)));
        return map("schema", 1, "ghc", "9.14.1", "module", "Synthetic.ReusableData", "instrument", true,
            "constructors", list(
                map("id", "Nil", "name", "Nil", "kind", "boxed", "arity", 0, "tag", 1,
                    "strictFields", list(), "fieldLifted", list(), "fieldReps", list(), "fieldTypes", list()),
                map("id", "Cons", "name", "Cons", "kind", "boxed", "arity", 2, "tag", 2,
                    "strictFields", list(false, false), "fieldLifted", list(false, true),
                    "fieldReps", list(list("IntRep"), list("BoxedRep (Just Lifted)")), "fieldTypes", list(word(), data(false)))),
            "bindings", list(function("make", list(parameter("n", false)), makeBody, data(true)),
                function("fold", list(parameter("xs", true), parameter("acc", false)), foldBody, word()),
                binding("shared", make(literal(3))), binding("empty", nil),
                function("read", list(parameter("n", false)), arithmetic("+#", fold(variable("shared"), literal(0)),
                    fold(make(variable("n")), literal(0))), word()), binding("untouched", list("unsupported-never-selected"))));
    }
    private Program.PreparedCode prepare(Engine engine) {
        try (var preparation = Context.newBuilder("thc").engine(engine).build()) {
            preparation.initialize("thc"); preparation.enter();
            try { return Program.prepareCode(TruffleLanguage.LanguageReference.create(Language.class).get(null), module(), List.of("read", "empty")); }
            finally { preparation.leave(); }
        }
    }
    private Object call(Program program, long n) {
        var function = (Closure)program.entryValue("read");
        return Calls.target(function.target, new Object[]{0L, function.environment, n});
    }
    private void instances(Engine engine, Program.PreparedCode code, boolean compiled) throws Exception {
        DataValue previous = null;
        for (int i = 0; i < 2; i++) try (var context = Context.newBuilder("thc").engine(engine).build()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var first = code.newInstance(language); var second = code.newInstance(language);
                var a = (Thunk)first.entryValue("shared"); var b = (Thunk)second.entryValue("shared");
                assertNotSame(a, b); assertEquals(0, a.getState()); assertEquals(0, b.getState());
                assertNotSame(first.entryValue("empty"), second.entryValue("empty"));
                assertInstanceOf(DataValue.class, first.entryValue("empty"));
                var reader = (Closure)first.entryValue("read");
                assertSame(reader.target, ((Closure)second.entryValue("read")).target);
                if (compiled) {
                    var type = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget");
                    Truffle.getRuntime().getClass().getMethod("bypassedInstalledCode", type).invoke(Truffle.getRuntime(), reader.target);
                }
                assertEquals(16L, call(first, 4));
                if (compiled) code.requireInstalledCode();
                assertEquals(2, a.getState()); assertEquals(0, b.getState());
                var value = assertInstanceOf(DataValue.class, a.getValue());
                var own = first.constructorLayout("Cons"); var other = second.constructorLayout("Cons");
                assertTrue(own.matches(value)); assertFalse(other.matches(value));
                assertEquals(3L, own.readLong(value, 0));
                assertThrows(RuntimeFault.class, () -> other.readLong(value, 0));
                var make = (Closure) first.entryValue("make");
                var fresh = (DataValue) Calls.target(make.target, new Object[]{0L, make.environment, 2L});
                assertTrue(own.matches(fresh)); assertEquals(2L, own.readLong(fresh, 0));
                assertEquals(0, assertInstanceOf(Thunk.class, own.read(fresh, 1)).getState(), "constructing a list must leave its tail lazy");
                if (previous != null) { assertNotSame(previous, value); assertFalse(own.matches(previous)); }
                previous = value;
                assertEquals(9L, call(second, 2));
                if (compiled) code.requireInstalledCode();
                assertEquals(2, b.getState()); assertNotSame(value, b.getValue());
                assertEquals(6L, call(first, 0)); assertSame(value, a.getValue());
                assertEquals(0, first.diagnostics().get("loweredRootCount"));
                assertThrows(UnsupportedCore.class, () -> first.entryValue("untouched"));
            } finally { context.leave(); }
        }
    }
    @Test void reusableListFoldKeepsLayoutsAndCafsPerInstance() throws Exception {
        try (var engine = Engine.newBuilder().allowExperimentalOptions(true).option("engine.Compilation", "false").build()) {
            instances(engine, prepare(engine), false);
        }
    }
    @Test @SuppressWarnings("unchecked") void firstCompiledListConstructionAndFoldNeedNoTraining() throws Exception {
        try (var engine = Engine.newBuilder().allowExperimentalOptions(true).option("engine.BackgroundCompilation", "false")
                .option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw").build()) {
            var code = prepare(engine);
            var field = Program.PreparedCode.class.getDeclaredField("targets"); field.setAccessible(true);
            var type = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget");
            for (var target : (List<RootCallTarget>)field.get(code)) {
                assertEquals(false, type.getMethod("wasExecuted").invoke(target));
                assertEquals(true, type.getMethod("prepareForAOT").invoke(target));
                type.getMethod("compile", boolean.class).invoke(target, true);
                assertEquals(false, type.getMethod("wasExecuted").invoke(target));
            }
            code.requireInstalledCode();
            String old = System.getProperty("thc.requireCompiledCode"); System.setProperty("thc.requireCompiledCode", "true");
            try { instances(engine, code, true); }
            finally { if (old == null) System.clearProperty("thc.requireCompiledCode"); else System.setProperty("thc.requireCompiledCode", old); }
        }
    }
}
