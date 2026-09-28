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

/** Ordinary closure inputs/results use the invoking closure's existing program owner. */
class ReusableHigherOrderTest {
    private Map<String,Object> word() { return map("kind", "long", "evaluated", true, "primReps", list("IntRep")); }
    private Map<String,Object> closure(boolean evaluated) {
        return map("kind", "closure", "evaluated", evaluated, "primReps", list("BoxedRep (Just Lifted)"));
    }
    private Map<String,Object> data(boolean evaluated) {
        return map("kind", "data", "evaluated", evaluated, "primReps", list("BoxedRep (Just Lifted)"));
    }
    private Map<String,Object> dataParameter(String id) {
        return map("id", id, "name", id, "type", "Box", "lifted", true, "rep", data(false));
    }
    private Map<String,Object> parameter(String id, boolean function) {
        return map("id", id, "name", id, "type", function ? "Int# -> Int#" : "Int#", "lifted", function,
            "rep", function ? closure(false) : word());
    }
    private List<Object> variable(String id) { return list("var", id); }
    private List<Object> literal(long n) { return list("lit", "int", Long.toString(n)); }
    private List<Object> application(List<Object> f, List<List<Object>> arguments, Map<String,Object> result) {
        return list("app", f, arguments, Collections.nCopies(arguments.size(), false), null, null, map("rep", result));
    }
    private List<Object> plus(List<Object> a, List<Object> b) { return application(list("prim", "+#"), list(a, b), word()); }
    private List<Object> lambda(List<Map<String,Object>> args, List<Object> body, Map<String,Object> result) {
        return list("lam", args, body, map("rep", closure(true), "resultRep", result));
    }
    private Map<String,Object> binding(String id, List<Object> body) {
        return map("id", id, "name", id, "lifted", true, "expr", body);
    }
    private Map<String,Object> function(String id, List<Map<String,Object>> args, List<Object> body, Map<String,Object> result) {
        var value = binding(id, lambda(args, body, result));
        value.put("arity", args.size()); value.put("rep", closure(true)); return value;
    }
    private List<Object> returningJoin(String id, List<Object> body, Map<String,Object> result) {
        var join = binding(id, body); join.put("rep", result);
        join.put("joinValueArity", 0); join.put("joinResultRep", result);
        return list("let", false, list(join), variable(id), map("rep", result));
    }
    @SuppressWarnings("unchecked") private Map<String,Object> module(boolean fields, boolean joins) {
        var sum = plus(variable("shared"), plus(variable("seed"), variable("x")));
        var choice = list("case", variable("which"), "tag", list(
            list("lit", list("int", "0"), list(), variable("f")),
            list("default", null, list(), application(variable("partial"), list(literal(17)), closure(true)))),
            map("rep", closure(false), "binder", parameter("tag", false)));
        if (joins) choice = returningJoin("chosen", choice, closure(false));
        var lazy = binding("lazyFunction", application(variable("capture"), list(literal(3)), closure(true)));
        lazy.put("rep", closure(false));
        var module = map("schema", 1, "ghc", "9.14.1", "module", "Synthetic.ReusableHigherOrder", "instrument", true,
            "constructors", list(), "bindings", list(
                binding("shared", plus(literal(17), literal(25))),
                function("add", list(parameter("seed", false), parameter("x", false)), sum, word()),
                function("capture", list(parameter("seed", false)), lambda(list(parameter("x", false)), sum, word()), closure(true)),
                function("partial", list(parameter("seed", false)), application(variable("add"), list(variable("seed")), closure(true)), closure(true)),
                function("apply", list(parameter("f", true), parameter("x", false)),
                    application(variable("f"), list(variable("x")), word()), word()),
                function("choose", list(parameter("f", true), parameter("which", false)), choice, closure(false)),
                lazy, binding("untouched", list("unsupported-never-selected"))));
        if (fields) {
            module.put("constructors", list(map("id", "Box", "name", "Box", "kind", "boxed", "arity", 3, "tag", 1,
                "strictFields", list(true, false, false), "fieldLifted", list(true, true, true),
                "fieldReps", Collections.nCopies(3, list("BoxedRep (Just Lifted)")),
                "fieldTypes", list(closure(true), closure(false), data(false)))));
            var bindings = new ArrayList<>((List<Map<String,Object>>)module.get("bindings"));
            var box = list("app", list("con", "Box", 3), list(
                application(variable("capture"), list(variable("seed")), closure(true)),
                application(variable("partial"), list(plus(variable("seed"), literal(1))), closure(false)),
                variable("neighbour")), list(true, true, true), null, null, map("rep", data(true)));
            var strict = parameter("strict", true); strict.put("rep", closure(true));
            var unbox = list("case", variable("box"), "whole", list(
                list("data", "Box", list("strict", "lazy", "next"), variable("strict"),
                    map("binders", list(strict, parameter("lazy", true), dataParameter("next"))))),
                map("rep", closure(true), "binder", dataParameter("whole")));
            bindings.add(function("box", list(parameter("seed", false)),
                joins ? returningJoin("boxed", box, data(true)) : box, data(true)));
            bindings.add(function("unbox", list(dataParameter("box")),
                joins ? returningJoin("unboxed", unbox, closure(true)) : unbox, closure(true)));
            if (joins) bindings.add(function("hold", list(dataParameter("value")),
                returningJoin("held", variable("value"), data(false)), data(false)));
            var neighbour = binding("neighbour", variable("neighbour")); neighbour.put("rep", data(false));
            bindings.add(neighbour);
            module.put("bindings", bindings);
        }
        return module;
    }
    private Object call(Closure function, Object... arguments) {
        Object[] packet = new Object[2 + arguments.length];
        packet[0] = 0L; packet[1] = function.environment;
        System.arraycopy(arguments, 0, packet, 2, arguments.length);
        return Calls.target(function.target, packet);
    }
    private Object call(Program program, String id, Object... arguments) { return call((Closure)program.entryValue(id), arguments); }
    private long count(Program program, String name) { return ((Number)program.diagnostics().get(name)).longValue(); }

    @SuppressWarnings("unchecked") private void checks(boolean compiled, boolean fields, boolean joins) throws Exception {
        try (var engine = Engine.newBuilder().allowExperimentalOptions(true).option("engine.Compilation", Boolean.toString(compiled))
                .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
                .option("engine.CompilationFailureAction", "Throw").build()) {
            Program.PreparedCode code;
            try (var preparation = Context.newBuilder("thc").engine(engine).build()) {
                preparation.initialize("thc"); preparation.enter();
                try { code = Program.prepareCode(TruffleLanguage.LanguageReference.create(Language.class).get(null),
                    module(fields, joins), joins ? List.of("apply", "capture", "partial", "choose", "lazyFunction", "box", "unbox", "hold") :
                        fields ? List.of("apply", "capture", "partial", "choose", "lazyFunction", "box", "unbox") :
                        List.of("apply", "capture", "partial", "choose", "lazyFunction")); }
                finally { preparation.leave(); }
            }
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
            String previous = System.getProperty("thc.requireCompiledCode");
            if (compiled) System.setProperty("thc.requireCompiledCode", "true");
            try {
                Closure escaped = null;
                for (int i = 0; i < 2; i++) try (var context = Context.newBuilder("thc").engine(engine).build()) {
                    context.initialize("thc"); context.enter();
                    try {
                        var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                        var first = code.newInstance(language); var second = code.newInstance(language);
                        var firstCaf = (Thunk)first.entryValue("shared"); var secondCaf = (Thunk)second.entryValue("shared");
                        assertNotSame(firstCaf, secondCaf); assertEquals(0, firstCaf.getState()); assertEquals(0, secondCaf.getState());
                        var capture = (Closure)first.entryValue("capture");
                        assertSame(capture.target, ((Closure)second.entryValue("capture")).target);
                        if (compiled) {
                            var type = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget");
                            Truffle.getRuntime().getClass().getMethod("bypassedInstalledCode", type).invoke(Truffle.getRuntime(), capture.target);
                        }
                        var captured = assertInstanceOf(Closure.class, call(first, "capture", 3L));
                        var partial = assertInstanceOf(Closure.class, call(first, "partial", 4L));
                        assertSame(first, captured.environment.getProgram()); assertSame(first, partial.environment.getProgram());
                        assertEquals(1, partial.suppliedCount); assertEquals(1L, count(first, "papAllocations"));
                        assertEquals(0L, count(first, "thunkEvaluations"));
                        assertEquals(47L, call(second, "apply", captured, 2L));
                        assertEquals(-25L, call(second, "apply", partial, -71L));
                        assertEquals(1L, count(first, "thunkEvaluations")); assertEquals(0L, count(second, "thunkEvaluations"));
                        assertEquals(2, firstCaf.getState()); assertEquals(0, secondCaf.getState());
                        assertSame(captured, call(first, "choose", captured, 0L));
                        var chosen = assertInstanceOf(Closure.class, call(first, "choose", captured, 1L));
                        assertEquals(59L, call(second, "apply", chosen, 0L));
                        var lazy = (Thunk)first.entryValue("lazyFunction"); assertEquals(0, lazy.getState());
                        if (joins) {
                            // The join retains a lazy result proof; the enclosing
                            // FunctionBody demands that result at its boundary.
                            var returned = assertInstanceOf(Closure.class, call(first, "choose", lazy, 0L));
                            assertSame(lazy.getValue(), returned);
                            assertSame(first, returned.environment.getProgram());
                            assertEquals(2, lazy.getState());
                            assertEquals(2L, count(first, "thunkEvaluations"));
                            assertEquals(3L, count(first, "localJoinTransfers"));
                            assertEquals(0L, count(second, "localJoinTransfers"));
                        }
                        assertEquals(47L, call(first, "apply", lazy, 2L));
                        assertEquals(2, lazy.getState()); assertEquals(2L, count(first, "thunkEvaluations"));
                        assertEquals(0, ((Thunk)second.entryValue("lazyFunction")).getState());
                        assertEquals(0L, count(second, "papAllocations"));
                        assertEquals(0L, count(first, "loweredRootCount")); assertEquals(0L, count(second, "loweredRootCount"));
                        assertThrows(UnsupportedCore.class, () -> first.entryValue("untouched"));
                        if (compiled) { code.requireInstalledCode(); assertTrue(count(first, "compiledEntries") > 0); assertTrue(count(second, "compiledEntries") > 0); }
                        if (fields) {
                            var owner = code.newInstance(language); var receiver = code.newInstance(language);
                            var ownCaf = (Thunk)owner.entryValue("shared"); var otherCaf = (Thunk)receiver.entryValue("shared");
                            var value = assertInstanceOf(DataValue.class, call(owner, "box", 3L));
                            var layout = owner.constructorLayout("Box"); var otherLayout = receiver.constructorLayout("Box");
                            assertTrue(layout.matches(value)); assertFalse(otherLayout.matches(value));
                            var strictField = assertInstanceOf(Closure.class, layout.read(value, 0));
                            var lazyField = assertInstanceOf(Thunk.class, layout.read(value, 1));
                            var neighbour = assertInstanceOf(Thunk.class, layout.read(value, 2));
                            assertSame(owner, strictField.environment.getProgram());
                            assertSame(owner, lazyField.getEnvironment().getProgram());
                            assertSame(owner.entryValue("neighbour"), neighbour);
                            if (joins) {
                                assertSame(value, call(owner, "hold", value));
                                assertEquals(0, neighbour.getState(), "Returning a box must not evaluate its lazy neighbour");
                            }
                            assertEquals(0, ownCaf.getState()); assertEquals(0, lazyField.getState()); assertEquals(0, neighbour.getState());
                            assertSame(strictField, call(owner, "unbox", value));
                            assertEquals(47L, call(receiver, "apply", strictField, 2L));
                            assertEquals(2, ownCaf.getState()); assertEquals(0, otherCaf.getState());
                            long before = count(owner, "thunkEvaluations");
                            assertEquals(-25L, call(receiver, "apply", lazyField, -71L));
                            // Demand counters belong to the forcing root; the thunk's
                            // body and returned PAP retain their captured program.
                            assertEquals(before, count(owner, "thunkEvaluations"));
                            assertEquals(1L, count(receiver, "thunkEvaluations"));
                            var storedPartial = assertInstanceOf(Closure.class, lazyField.getValue());
                            assertSame(owner, storedPartial.environment.getProgram());
                            assertEquals(1L, count(owner, "papAllocations"));
                            assertEquals(0L, count(receiver, "papAllocations"));
                            assertEquals(-25L, call(receiver, "apply", lazyField, -71L));
                            assertEquals(before, count(owner, "thunkEvaluations"));
                            assertEquals(2, lazyField.getState()); assertEquals(0, neighbour.getState());
                            assertEquals(1L, count(receiver, "thunkEvaluations"));
                            assertEquals(0L, count(owner, "loweredRootCount")); assertEquals(0L, count(receiver, "loweredRootCount"));
                            if (joins) {
                                assertEquals(3L, count(owner, "localJoinTransfers"));
                                assertEquals(0L, count(receiver, "localJoinTransfers"));
                            }
                            if (compiled) { code.requireInstalledCode(); assertTrue(count(owner, "compiledEntries") > 0); }
                            if (i == 1) assertThrows(RuntimeFault.class, () -> call(receiver, "unbox", value));
                        }
                        if (escaped != null) {
                            var foreign = escaped;
                            assertThrows(RuntimeFault.class, () -> call(second, "apply", foreign, 0L));
                            assertEquals(0, secondCaf.getState());
                        }
                        escaped = captured;
                    } finally { context.leave(); }
                }
            } finally { if (previous == null) System.clearProperty("thc.requireCompiledCode"); else System.setProperty("thc.requireCompiledCode", previous); }
        }
    }
    @Test void higherOrderCallsKeepCapturedAndPartialOwnersAcrossInstances() throws Exception { checks(false, false, false); }
    @Test void firstCompiledClosureInputsResultsAndLazyFunctionsNeedNoTraining() throws Exception { checks(true, false, false); }
    @Test void constructorFunctionFieldsKeepLazyNeighboursAndCapturedOwners() throws Exception { checks(false, true, false); }
    @Test void firstCompiledConstructorFunctionFieldsNeedNoTraining() throws Exception { checks(true, true, false); }
    @Test void referenceJoinsKeepLazyValuesAndInvocationOwners() throws Exception { checks(false, true, true); }
    @Test void firstCompiledReferenceJoinsNeedNoTraining() throws Exception { checks(true, true, true); }
}
