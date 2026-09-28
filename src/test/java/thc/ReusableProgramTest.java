// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.Truffle;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import thc.runtime.*;
import static org.junit.jupiter.api.Assertions.*;
import static thc.CoreExecutionTestSupport.*;

/** Real AST lowerer ownership checks; not persisted-code or cross-context acceptance. */
class ReusableProgramTest {
    private Map<String, Object> binding(String id, List<Object> body, boolean lifted) {
        return map("id", id, "name", id, "type", "Synthetic", "lifted", lifted, "expr", body);
    }
    private List<Object> variable(String id) { return list("var", id); }
    private List<Object> literal(long n) { return list("lit", "int", Long.toString(n)); }
    private List<Object> plus(List<Object> a, List<Object> b) {
        return list("app", list("prim", "+#"), list(a, b), list(false, false));
    }
    private List<Object> lambda(List<Object> body) {
        return list("lam", list(map("id", "x", "name", "x", "type", "Int#", "lifted", false, "coercion", false)), body);
    }
    private Object call(ExecutableProgram program, Object value, Object... arguments) {
        return Calls.target(program.hostEntryTarget(arguments.length), new Object[]{value, arguments});
    }
    private long count(ExecutableProgram program, String name) { return ((Number) program.diagnostics().get(name)).longValue(); }
    private Map<String, Object> module(List<Map<String, Object>> bindings) {
        return map("schema", 1, "ghc", "9.14.1", "module", "Synthetic.Reusable", "instrument", true,
            "constructors", list(), "bindings", bindings);
    }

    @Test void admissionRejectsUnconvertedOwnershipAndConflictingCarrierProofs() {
        try (var context = Main.executionContext(false)) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var foreign = module(list(binding("read", lambda(variable("x")), true)));
                foreign.put("selectedForeignExceptionBridge", map("unit", "u", "box", "b", "project", "p"));
                var narrow = map("id", "x", "name", "x", "type", "Int#", "lifted", false, "coercion", false,
                    "rep", map("kind", "long", "evaluated", true, "primReps", list("Int32Rep")));
                var conflicting = module(list(binding("read", list("lam", list(narrow), variable("x")), true)));
                var nested = module(list(binding("read", lambda(lambda(variable("x"))), true)));
                assertAll(
                    () -> assertThrows(UnsupportedCore.class, () -> Program.prepareCode(language, foreign, List.of("read"))),
                    () -> assertThrows(UnsupportedCore.class, () -> Program.prepareCode(language, conflicting, List.of("read"))),
                    () -> assertThrows(UnsupportedCore.class, () -> Program.prepareCode(language, nested, List.of("read"))));
            } finally { context.leave(); }
        }
    }

    @Test void installedSharedRootUsesFreshOwnersCafAndMetricsOnItsFirstCall() throws Exception {
        try (var context = Main.executionContext(false)) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var code = Program.prepareCode(language, module(list(
                    binding("shared", plus(literal(17), literal(25)), true),
                    binding("read", lambda(plus(variable("shared"), variable("x"))), true))), List.of("read"));
                var first = code.newInstance(language);
                var second = code.newInstance(language);
                var reader = (Closure) first.entryValue("read");
                var secondReader = (Closure) second.entryValue("read");
                var secondCaf = (Thunk) second.entryValue("shared");
                assertSame(reader.target, secondReader.target);
                // One declared call establishes the ordinary JIT profiles using the FIRST owner only.
                // The second owner and its CAF have never executed when shared code is installed.
                assertEquals(47L, Calls.target(reader.target, new Object[]{0L, reader.environment, 5L}));
                assertEquals(0, secondCaf.getState());
                var target = reader.target;
                var type = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget");
                type.getMethod("compile", boolean.class).invoke(target, true);
                assertEquals(true, type.getMethod("isValidLastTier").invoke(target));
                var runtime = Truffle.getRuntime();
                runtime.getClass().getMethod("bypassedInstalledCode", type).invoke(runtime, target);
                long firstEntries = count(first, "compiledEntries");
                long secondEntries = count(second, "compiledEntries");
                assertEquals(51L, Calls.target(target, new Object[]{0L, secondReader.environment, 9L}));
                assertSame(target, secondReader.target);
                assertEquals(true, type.getMethod("isValidLastTier").invoke(target), "the first second-owner call must retain installed code");
                assertTrue(count(second, "compiledEntries") > secondEntries);
                assertEquals(firstEntries, count(first, "compiledEntries"));
                assertEquals(2, secondCaf.getState());
                assertEquals(1L, count(first, "thunkEvaluations"));
                assertEquals(1L, count(second, "thunkEvaluations"));
            } finally { context.leave(); }
        }
    }

    @Test void realLoweredPapRetainsItsOwnerAcrossOtherInstanceCalls() {
        var x = map("id", "x", "name", "x", "type", "Int#", "lifted", false, "coercion", false);
        var y = map("id", "y", "name", "y", "type", "Int#", "lifted", false, "coercion", false);
        try (var context = Main.executionContext(false)) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var code = Program.prepareCode(language, module(list(
                    binding("shared", plus(literal(17), literal(25)), true),
                    binding("read", list("lam", list(x, y), plus(variable("shared"), plus(variable("x"), variable("y")))), true))), List.of("read"));
                var first = code.newInstance(language); var second = code.newInstance(language);
                var firstPap = (Closure) call(first, first.entryValue("read"), 3L);
                var secondPap = (Closure) call(second, second.entryValue("read"), 11L);
                assertSame(first, firstPap.environment.getProgram()); assertSame(second, secondPap.environment.getProgram());
                assertSame(firstPap.target, secondPap.target);
                assertEquals(55L, call(second, secondPap, 2L));
                assertEquals(0, ((Thunk) first.entryValue("shared")).getState());
                assertEquals(50L, call(first, firstPap, 5L));
                assertEquals(1L, count(first, "thunkEvaluations")); assertEquals(1L, count(second, "thunkEvaluations"));
            } finally { context.leave(); }
        }
    }

    @Test void wrongCodeAndMissingOwnerAreRejectedBeforeCafEffects() {
        try (var context = Main.executionContext(false)) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var data = module(list(binding("shared", plus(literal(17), literal(25)), true),
                    binding("read", lambda(plus(variable("shared"), variable("x"))), true)));
                var first = Program.prepareCode(language, data, List.of("read")).newInstance(language);
                var other = Program.prepareCode(language, data, List.of("read")).newInstance(language);
                var reader = (Closure) first.entryValue("read");
                var layout = reader.environment.getLayout();
                for (var environment : List.of(layout.captureValues(new Object[0]), layout.captureValues(new Object[0], other))) {
                    assertThrows(RuntimeFault.class, () -> Calls.target(reader.target, new Object[]{0L, environment, 5L}));
                    assertEquals(0, ((Thunk) first.entryValue("shared")).getState());
                    assertEquals(0, ((Thunk) other.entryValue("shared")).getState());
                    assertEquals(0L, count(first, "thunkEvaluations")); assertEquals(0L, count(other, "thunkEvaluations"));
                }
            } finally { context.leave(); }
        }
    }

    @Test void realLoweredRootsShareCodeButNotCafUpdatesFailuresOrMetrics() {
        var untouched = new AtomicInteger();
        var lazy = new CoreBindingBody(new CoreBindingBody.Header(2, Map.of(0, "var", 1, "unused"), false), null,
            () -> { untouched.incrementAndGet(); throw new AssertionError("untouched definition was decoded"); });
        var module = map("schema", 1, "ghc", "9.14.1", "module", "Synthetic.Reusable",
            "instrument", true, "constructors", list(), "bindings", list(
                binding("shared", plus(literal(17), literal(25)), true),
                binding("read", lambda(plus(variable("shared"), variable("x"))), true),
                binding("readAgain", lambda(plus(variable("shared"), variable("x"))), true),
                binding("bottom", variable("bottom"), true), binding("unused", lazy, true)));
        try (var context = Main.executionContext(false)) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var code = Program.prepareCode(language, module, List.of("read", "readAgain", "bottom"));
                var first = code.newInstance(language);
                var second = code.newInstance(language);
                assertEquals(0, untouched.get());
                assertEquals(0L, count(first, "thunkEvaluations"));
                assertEquals(0L, count(second, "thunkEvaluations"));
                var firstReader = (Closure) first.entryValue("read");
                var secondReader = (Closure) second.entryValue("read");
                assertSame(firstReader.target, secondReader.target, "the actual lowered root must be reused");
                assertNotSame(firstReader, secondReader);
                assertSame(first, firstReader.environment.getProgram());
                assertSame(second, secondReader.environment.getProgram());
                var firstCaf = (Thunk) first.entryValue("shared");
                var secondCaf = (Thunk) second.entryValue("shared");
                assertNotSame(firstCaf, secondCaf);
                assertSame(firstCaf.getTarget(), secondCaf.getTarget());
                assertEquals(0, firstCaf.getState()); assertEquals(0, secondCaf.getState());
                assertEquals(47L, call(first, firstReader, 5L));
                assertEquals(2, firstCaf.getState()); assertEquals(0, secondCaf.getState());
                assertEquals(1L, count(first, "thunkEvaluations")); assertEquals(0L, count(second, "thunkEvaluations"));
                assertEquals(49L, call(first, first.entryValue("readAgain"), 7L));
                assertEquals(1L, count(first, "thunkEvaluations"));
                assertEquals(51L, call(second, secondReader, 9L));
                assertEquals(1L, count(second, "thunkEvaluations"));
                var firstBottom = (Thunk) first.entryValue("bottom");
                var secondBottom = (Thunk) second.entryValue("bottom");
                assertEquals(0, firstBottom.getState()); assertEquals(0, secondBottom.getState());
                var firstFailure = assertThrows(RuntimeFault.class, () -> call(first, firstBottom));
                assertSame(firstFailure, assertThrows(RuntimeFault.class, () -> call(first, firstBottom)));
                assertEquals(0, secondBottom.getState());
                var secondFailure = assertThrows(RuntimeFault.class, () -> call(second, secondBottom));
                assertSame(secondFailure, assertThrows(RuntimeFault.class, () -> call(second, secondBottom)));
                assertNotSame(firstFailure, secondFailure);
                assertEquals(2L, count(first, "thunkEvaluations")); assertEquals(2L, count(second, "thunkEvaluations"));
                assertEquals(0L, count(first, "loweredRootCount")); assertEquals(0L, count(second, "loweredRootCount"));
                assertEquals(0, untouched.get());
            } finally { context.leave(); }
        }
    }
}
