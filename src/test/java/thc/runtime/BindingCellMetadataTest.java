// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import java.util.*;
import org.junit.jupiter.api.Test;
import thc.Language;
import thc.MainKt;
import static org.junit.jupiter.api.Assertions.*;
import static thc.runtime.RepresentationTestSupport.*;

/** Physical recursive cells and published guest values have different lifetimes. */
class BindingCellMetadataTest {
    private List<Object> variable(String id) { return list("var", id); }
    private List<Object> integer(long value) { return list("lit", "int", Long.toString(value)); }
    private Map<String, Object> parameter(String id) { return parameter(id, false); }
    private Map<String, Object> parameter(String id, boolean lifted) { return map("id", id, "name", id, "type", "Synthetic", "lifted", lifted, "coercion", false); }
    private List<Object> lambda(List<Map<String, Object>> parameters, List<Object> body) { return list("lam", parameters, body); }
    private List<Object> lambda(String id, List<Object> body) { return lambda(list(parameter(id)), body); }
    private Map<String, Object> binding(String id, List<Object> body) { return with(parameter(id, true), "expr", body, "arity", body.getFirst().equals("lam") ? ((List<?>) body.get(1)).size() : 0); }
    private List<Object> apply(List<Object> function, List<List<Object>> arguments) { return apply(function, arguments, Collections.nCopies(arguments.size(), false)); }
    private List<Object> apply(List<Object> function, List<List<Object>> arguments, List<Boolean> lifted) { return list("app", function, arguments, lifted); }
    @SafeVarargs private final List<Object> call(String id, List<Object>... arguments) { return apply(variable(id), list(arguments)); }
    @SafeVarargs private final List<Object> primitive(String name, List<Object>... arguments) { return apply(list("prim", name), list(arguments)); }
    private List<Object> add(List<Object> left, List<Object> right) { return primitive("+#", left, right); }
    private List<Object> local(List<Map<String, Object>> group, List<Object> body, boolean recursive) { return list("let", recursive, group, body); }
    private List<Object> local(String id, List<Object> rhs, List<Object> body) { return local(list(binding(id, rhs)), body, false); }
    private List<Object> box(List<Object> value) { return list("app", list("con", "Box", 1), list(value), list(false), true, true); }
    private List<Object> unbox(List<Object> value, String binder, String field, List<Object> body) { return list("case", value, binder, list(list("data", "Box", list(field), body))); }
    private List<Object> chooseZero(List<Object> value, List<Object> zero, List<Object> other) { return list("case", value, "choice", list(list("lit", list("int", "0"), list(), zero), list("default", null, list(), other))); }
    private Map<String, Object> module(List<Object> body) { return map("schema", 1, "ghc", "9.14.1", "module", "Synthetic.BindingCells", "instrument", true,
        "bindings", list(binding("entry", lambda("input", body))), "constructors", list(map("id", "Box", "name", "Box", "arity", 1, "tag", 1,
            "kind", "boxed", "strictFields", list(false), "fieldLifted", list(false), "fieldReps", list(list("IntRep"))))); }
    private void eachBackend(List<Object> body, CheckedBiConsumer<String, ExecutableProgram> action) throws Exception {
        try (var context = MainKt.executionContext(false)) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                for (var backend : list("ast", "bytecode")) {
                    var data = module(body);
                    action.accept(backend, backend.equals("ast") ? new Program(language, data) : new BytecodeProgram(language, data));
                }
            } finally { context.leave(); }
        }
    }
    private Object call(ExecutableProgram program, Object function, long input) { return Calls.target(program.hostEntryTarget(1), new Object[]{function, new Object[]{input}}); }
    private Object run(ExecutableProgram program, long input) { return call(program, program.entryValue("entry"), input); }
    private long count(ExecutableProgram program, String name) { return ((Number) program.diagnostics().get(name)).longValue(); }
    private void compile(RootCallTarget target) throws Exception {
        var type = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget"); type.getMethod("compile", boolean.class).invoke(target, true);
        assertEquals(true, type.getMethod("isValidLastTier").invoke(target));
    }
    @Test void ordinaryObjectParametersFieldsAndCapturesNeedNoRecursiveCellReads() throws Exception {
        // The argument, case alias and later capture are values without an exported Long proof.
        var captured = lambda("delta", unbox(variable("alias"), "again", "saved", add(add(variable("saved"), variable("delta")), variable("extra"))));
        var worker = lambda(list(parameter("tree", true), parameter("extra")), unbox(variable("tree"), "matched", "field",
            local("alias", variable("matched"), local("captured", captured, call("captured", variable("field"))))));
        var body = local("boxed", box(variable("input")), local("worker", worker, apply(variable("worker"), list(variable("boxed"), variable("input")), list(true, false))));
        eachBackend(body, (backend, program) -> {
            if (program instanceof BytecodeProgram bytecode) assertFalse(bytecode.bytecodeDump().contains("c.ReadCellIfNeeded"), "Ordinary locals and captures must not emit recursive-cell resolution");
            for (int i = 0; i < 30; i++) assertEquals(21L, run(program, 7L), backend);
            compile(program.entryTarget("entry")); long compiled = count(program, "compiledEntries");
            for (long n : new long[]{3_000_000_017L, Long.MIN_VALUE, Long.MAX_VALUE, 0L}) assertEquals(n * 3, run(program, n), backend);
            assertTrue(count(program, "compiledEntries") > compiled, backend); assertEquals(0L, count(program, "blackholes"), backend);
        });
    }
    private List<Object> worker(String other) { return lambda("remaining", chooseZero(variable("remaining"), unbox(variable("shared"), "answer", "value", variable("value")), call(other, primitive("-#", variable("remaining"), integer(1))))); }
    private void checkRecursive(ExecutableProgram program, String backend, long n) {
        long evaluations = count(program, "thunkEvaluations"); assertEquals(n * 3 + 2, run(program, n), backend);
        assertEquals(evaluations + 1, count(program, "thunkEvaluations"), backend + " shares the recursive payload");
    }
    @Test void recursiveCapturesKeepCellsWhileClosuresCreatedAfterPublicationCaptureValues() throws Exception {
        var group = list(binding("shared", box(add(variable("input"), integer(1)))), binding("left", worker("right")), binding("right", worker("left")));
        var later = lambda("extra", unbox(variable("shared"), "lateBox", "lateValue", add(variable("lateValue"), variable("extra"))));
        var depth = chooseZero(variable("input"), integer(0), integer(10_001));
        var body = local(group, local("later", later, add(call("left", depth), call("later", variable("input")))), true);
        eachBackend(body, (backend, program) -> {
            for (int i = 0; i < 30; i++) checkRecursive(program, backend, 0L);
            compile(program.entryTarget("entry")); long compiled = count(program, "compiledEntries"); checkRecursive(program, backend, 0L);
            assertTrue(count(program, "compiledEntries") > compiled, backend);
            // The recursive branch is cold when the entry is first compiled.
            for (long n : new long[]{3_000_000_017L, Long.MIN_VALUE, Long.MAX_VALUE, 7L}) checkRecursive(program, backend, n);
            assertEquals(0L, count(program, "blackholes"), backend);
        });
    }
    @Test void escapingRecursiveClosuresRetainCellsAfterCaseRefinementAndAcrossInvocations() throws Exception {
        // The second closure captures the cell before publication; case WHNF does not replace it.
        var nested = lambda("delta", unbox(variable("shared"), "again", "saved", add(variable("saved"), variable("delta"))));
        var second = lambda("extra", unbox(variable("shared"), "evaluated", "field", local("nested", nested, call("nested", variable("extra")))));
        var body = local(list(binding("shared", box(variable("input"))), binding("first", lambda("extra", call("second", variable("extra")))), binding("second", second)), variable("first"), true);
        eachBackend(body, (backend, program) -> {
            var a = (Closure) run(program, 3_000_000_017L); var b = (Closure) run(program, -7_000_000_003L);
            assertEquals(0L, count(program, "thunkEvaluations"), backend + " leaves escaped payloads lazy");
            for (int i = 0; i < 30; i++) {
                assertEquals(3_000_000_017L + i, call(program, a, i), backend); assertEquals(-7_000_000_003L + i, call(program, b, i), backend);
            }
            assertEquals(2L, count(program, "thunkEvaluations"), backend + " evaluates each retained payload once");
            compile(a.target); long compiled = count(program, "compiledEntries");
            for (long n : new long[]{Long.MIN_VALUE, Long.MAX_VALUE, 0L}) {
                assertEquals(3_000_000_017L + n, call(program, a, n), backend); assertEquals(-7_000_000_003L + n, call(program, b, n), backend);
            }
            assertTrue(count(program, "compiledEntries") > compiled, backend); assertEquals(2L, count(program, "thunkEvaluations"), backend); assertEquals(0L, count(program, "blackholes"), backend);
        });
    }
}
