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
    private Map<String,Object> module() {
        var sum = plus(variable("shared"), plus(variable("seed"), variable("x")));
        var choice = list("case", variable("which"), "tag", list(
            list("lit", list("int", "0"), list(), variable("f")),
            list("default", null, list(), application(variable("partial"), list(literal(17)), closure(true)))),
            map("rep", closure(false), "binder", parameter("tag", false)));
        var lazy = binding("lazyFunction", application(variable("capture"), list(literal(3)), closure(true)));
        lazy.put("rep", closure(false));
        return map("schema", 1, "ghc", "9.14.1", "module", "Synthetic.ReusableHigherOrder", "instrument", true,
            "constructors", list(), "bindings", list(
                binding("shared", plus(literal(17), literal(25))),
                function("add", list(parameter("seed", false), parameter("x", false)), sum, word()),
                function("capture", list(parameter("seed", false)), lambda(list(parameter("x", false)), sum, word()), closure(true)),
                function("partial", list(parameter("seed", false)), application(variable("add"), list(variable("seed")), closure(true)), closure(true)),
                function("apply", list(parameter("f", true), parameter("x", false)),
                    application(variable("f"), list(variable("x")), word()), word()),
                function("choose", list(parameter("f", true), parameter("which", false)), choice, closure(false)),
                lazy, binding("untouched", list("unsupported-never-selected"))));
    }
    private Object call(Closure function, Object... arguments) {
        Object[] packet = new Object[2 + arguments.length];
        packet[0] = 0L; packet[1] = function.environment;
        System.arraycopy(arguments, 0, packet, 2, arguments.length);
        return Calls.target(function.target, packet);
    }
    private Object call(Program program, String id, Object... arguments) { return call((Closure)program.entryValue(id), arguments); }
    private long count(Program program, String name) { return ((Number)program.diagnostics().get(name)).longValue(); }

    @SuppressWarnings("unchecked") private void checks(boolean compiled) throws Exception {
        try (var engine = Engine.newBuilder().allowExperimentalOptions(true).option("engine.Compilation", Boolean.toString(compiled))
                .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
                .option("engine.CompilationFailureAction", "Throw").build()) {
            Program.PreparedCode code;
            try (var preparation = Context.newBuilder("thc").engine(engine).build()) {
                preparation.initialize("thc"); preparation.enter();
                try { code = Program.prepareCode(TruffleLanguage.LanguageReference.create(Language.class).get(null),
                    module(), List.of("apply", "capture", "partial", "choose", "lazyFunction")); }
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
                        assertEquals(47L, call(first, "apply", lazy, 2L));
                        assertEquals(2, lazy.getState()); assertEquals(2L, count(first, "thunkEvaluations"));
                        assertEquals(0, ((Thunk)second.entryValue("lazyFunction")).getState());
                        assertEquals(0L, count(second, "papAllocations"));
                        assertEquals(0L, count(first, "loweredRootCount")); assertEquals(0L, count(second, "loweredRootCount"));
                        assertThrows(UnsupportedCore.class, () -> first.entryValue("untouched"));
                        if (compiled) { code.requireInstalledCode(); assertTrue(count(first, "compiledEntries") > 0); assertTrue(count(second, "compiledEntries") > 0); }
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
    @Test void higherOrderCallsKeepCapturedAndPartialOwnersAcrossInstances() throws Exception { checks(false); }
    @Test void firstCompiledClosureInputsResultsAndLazyFunctionsNeedNoTraining() throws Exception { checks(true); }
}
