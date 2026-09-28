// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleLanguage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import thc.Language;
import static org.junit.jupiter.api.Assertions.*;

/** Large original-shaped Core decisions, not a fabricated compiler bailout. */
class BytecodeGraphBudgetTest {
    private static final Map<String, Object> LONG = Map.of("kind", "long", "primReps", List.of("IntRep"), "evaluated", true);
    private static final Map<String, Object> DATA = Map.of("kind", "data", "primReps", List.of("BoxedRep (Just Lifted)"), "evaluated", true);
    private static final Map<String, Object> CLOSURE = Map.of("kind", "closure", "primReps", List.of("BoxedRep (Just Lifted)"), "evaluated", true);
    private static List<Object> node(Object... fields) { return Arrays.asList(fields); }
    private static Map<String, Object> binder(String id, Map<String, Object> proof, boolean lifted) {
        return Map.of("id", id, "name", id, "lifted", lifted, "rep", proof);
    }
    private static List<Object> number(long value) { return node("lit", "int", Long.toString(value), Map.of("rep", LONG)); }
    private static Map<String, Object> decision(int count) {
        var constructors = new ArrayList<Map<String, Object>>();
        var alternatives = new ArrayList<List<Object>>();
        for (int i = 0; i < count; i++) {
            String id = "C" + i;
            constructors.add(Map.of("id", id, "name", id, "arity", 0, "tag", i + 1,
                    "kind", "boxed", "strictFields", List.of(), "fieldLifted", List.of(), "fieldReps", List.of()));
            alternatives.add(node("data", id, List.of(), number(i * 3L + 17)));
        }
        var expression = node("case", node("var", "x", Map.of("rep", DATA)), "seen", alternatives,
                Map.of("rep", LONG, "binder", binder("seen", DATA, true)));
        return Map.of("constructors", constructors, "bindings", List.of(
                Map.of("id", "entry", "name", "entry", "lifted", true, "rep", CLOSURE,
                        "expr", node("lam", List.of(binder("x", DATA, true)), expression,
                                Map.of("rep", CLOSURE, "resultRep", LONG, "entryStrict", List.of(true)))),
                Map.of("id", "chosen", "name", "chosen", "lifted", false, "rep", DATA,
                        "expr", node("con", "C" + (count - 1), 0, Map.of("rep", DATA)))));
    }
    private static Context context() {
        return Context.newBuilder("thc").allowExperimentalOptions(true)
                .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
                .option("engine.SingleTierCompilationThreshold", "10000000")
                .option("engine.CompilationFailureAction", "Throw")
                .option("compiler.MaximumGraalGraphSize", "100000")
                .option("compiler.CompilationTimeout", "30").build();
    }
    private static boolean compile(RootCallTarget target) throws Exception {
        return (Boolean) target.getClass().getMethod("compile", boolean.class).invoke(target, true);
    }
    private static boolean valid(RootCallTarget target) throws Exception {
        return (Boolean) target.getClass().getMethod("isValidLastTier").invoke(target);
    }
    private static void bypass(RootCallTarget target) throws Exception {
        var runtime = Truffle.getRuntime();
        runtime.getClass().getMethod("bypassedInstalledCode", Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget"))
                .invoke(runtime, target);
    }
    private static long entries(BytecodeProgram program) { return ((Number) program.diagnostics().get("compiledEntries")).longValue(); }

    @Test void explicitProtocolControlPreservesPreparedSelectionWithoutClaimingACompilerBailout() throws Exception {
        try (Context context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var program = new BytecodeProgram(language, decision(96));
                var target = ((Closure) program.entryValue("entry")).target;
                var root = (BytecodeRoot) target.getRootNode();
                assertEquals(0, entries(program));
                assertEquals(1, root.prepareGraphBudgetRetry(0));
                assertEquals(1, root.prepareGraphBudgetRetry(0));
                assertEquals(302L, Calls.target(target, new Object[]{0L, program.entryValue("chosen")}));
                assertEquals(0, entries(program));
            } finally { context.leave(); }
        }
    }

    @Test void originalTargetRecoversARealOversizedDecisionBeforeItsFirstGuestCall() throws Exception {
        try (Context context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var program = new BytecodeProgram(language, decision(768));
                var target = ((Closure) program.entryValue("entry")).target;
                var root = (BytecodeRoot) target.getRootNode();
                var instructions = root.getBytecodeNode().dump();
                assertEquals(0, entries(program));
                assertEquals(0, root.getGraphBudgetGeneration());
                assertTrue(compile(target));
                assertEquals(1, root.getGraphBudgetGeneration(), "a real graph bailout must select a smaller finite case region");
                assertEquals(0, entries(program), "compilation cannot run the guest");
                assertEquals(instructions, root.getBytecodeNode().dump(), "parked bytecode PCs must not change");
                var chosen = program.entryValue("chosen");
                bypass(target);
                long before = entries(program);
                assertEquals(2318L, Calls.target(target, new Object[]{0L, chosen}));
                assertEquals(before + 1, entries(program), "the original first call enters its installed target");
                assertSame(target, ((Closure) program.entryValue("entry")).target);
                assertTrue(valid(target), "no settling call or target replacement");
                assertEquals(1, root.prepareGraphBudgetRetry(1), "the finite plan is exhausted");
            } finally { context.leave(); }
        }
    }

    @Test void explicitProtocolControlEntersRecoveredCodeOnItsFirstCall() throws Exception {
        try (Context context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var program = new BytecodeProgram(language, decision(96));
                var target = ((Closure) program.entryValue("entry")).target;
                var root = (BytecodeRoot) target.getRootNode();
                assertEquals(1, root.prepareGraphBudgetRetry(0)); // Protocol test, not the real-bailout acceptance above.
                assertTrue(compile(target));
                assertEquals(0, entries(program));
                bypass(target);
                assertEquals(302L, Calls.target(target, new Object[]{0L, program.entryValue("chosen")}));
                assertEquals(1, entries(program));
                assertTrue(valid(target));
            } finally { context.leave(); }
        }
    }
}
