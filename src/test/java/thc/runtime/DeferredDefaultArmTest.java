// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.nodes.NodeUtil;
import com.oracle.truffle.api.nodes.RootNode;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import thc.Language;
import java.lang.reflect.InvocationTargetException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** The control is actual Core lowering, not a handwritten Truffle arithmetic root. */
class DeferredDefaultArmTest {
    private static final Map<String, Object> LONG = Map.of("kind", "long", "primReps", List.of("IntRep"), "evaluated", true);
    private static final Map<String, Object> CLOSURE = Map.of("kind", "closure", "primReps", List.of("BoxedRep (Just Lifted)"), "evaluated", true);
    private static List<Object> node(Object... fields) { return Arrays.asList(fields); }
    private static List<Object> variable(String id) { return node("var", id, Map.of("rep", LONG)); }
    private static List<Object> integer(long value) { return node("lit", "int", Long.toString(value), Map.of("rep", LONG)); }
    private static List<Object> primitive(String name, List<Object> left, List<Object> right) {
        return node("app", node("prim", name), List.of(left, right), List.of(false, false), false, false, Map.of("rep", LONG));
    }
    private static List<Object> body(String input, int depth, long index) {
        if (depth == 0) return primitive("xorI#", variable(input), integer(index));
        return primitive("+#", primitive("*#", body(input, depth - 1, index * 2),
                body(input, depth - 1, index * 2 + 1)), integer(17));
    }
    private static Map<String, Object> parameter(String id) {
        return Map.of("id", id, "name", id, "lifted", false, "rep", LONG);
    }
    private static Map<String, Object> module(boolean arm) {
        List<Object> expression = body(arm ? "seen" : "x", 9, 1);
        if (arm) expression = node("case", variable("x"), "seen",
                List.of(node("default", null, List.of(), expression)),
                Map.of("rep", LONG, "binder", parameter("seen")));
        return module(expression, parameter("x"), LONG);
    }
    private static Map<String, Object> module(List<Object> expression, Map<String, Object> parameter, Map<String, Object> result) {
        return Map.of("sourceFiles", List.of(Map.of("id", "control", "path", "DeferredDefaultArm.hs")),
                "sourceSpans", List.of(Map.of("id", "body", "file", "control", "startLine", 1,
                        "startColumn", 1, "endLine", 1, "endColumn", 2)),
                "bindings", List.of(Map.of("id", "entry", "name", "entry", "lifted", true, "source", "body",
                "rep", CLOSURE, "expr", node("lam", List.of(parameter), expression,
                        Map.of("rep", CLOSURE, "resultRep", result)))));
    }
    private static Context context() {
        return Context.newBuilder("thc").allowExperimentalOptions(true)
                .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
                .option("engine.SingleTierCompilationThreshold", "10000000")
                .option("engine.CompilationFailureAction", "Throw")
                .option("compiler.DiagnoseFailure", "false")
                .option("compiler.MaximumGraalGraphSize", "10000")
                .option("compiler.CompilationTimeout", "30").build();
    }
    private static Program program(Language language, boolean arm) throws Exception {
        return program(language, module(arm));
    }
    private static Program program(Language language, Map<String, Object> module) {
        return new Program(language, module, false, false, true);
    }
    private static int deferredArmCount(RootNode root) {
        return NodeUtil.findAllNodeInstances(root, AstDeferredArm.class).size();
    }
    private static boolean compile(RootCallTarget target) throws Exception {
        return (Boolean) target.getClass().getMethod("compile", boolean.class).invoke(target, true);
    }
    private static boolean valid(RootCallTarget target) throws Exception {
        return (Boolean) target.getClass().getMethod("isValidLastTier").invoke(target);
    }
    private static void bypass(RootCallTarget target) throws Exception {
        Object runtime = Truffle.getRuntime();
        runtime.getClass().getMethod("bypassedInstalledCode", Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget"))
                .invoke(runtime, target);
    }
    private static long count(Program program) { return ((Number) program.diagnostics().get("compiledEntries")).longValue(); }

    @Test void identicalOversizedBodyWithoutAnEligibleDefaultArmKeepsItsBudgetFailure() throws Exception {
        try (Context context = context()) {
            context.initialize("thc"); context.enter();
            try {
                Language language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                Program program = program(language, false);
                RootCallTarget target = program.entryTarget("entry");
                InvocationTargetException error = assertThrows(InvocationTargetException.class, () -> compile(target));
                assertTrue(error.getCause().toString().contains("GraphTooBigBailoutException"));
                assertEquals(0, ((FunctionRoot) target.getRootNode()).getGraphBudgetGeneration());
                assertEquals(0, count(program));
                assertFalse(valid(target));
            } finally { context.leave(); }
        }
    }

    @ParameterizedTest @ValueSource(strings = {"int", "long", "float", "double"})
    void smallArmStaysInlineAndExplicitExtractionPreservesScalarCarriersAndCallerClones(String carrier) throws Exception {
        Map<String, Object> proof = switch (carrier) {
            case "int" -> Map.of("kind", "long", "primReps", List.of("Int32Rep"), "evaluated", true);
            case "float" -> Map.of("kind", "float", "primReps", List.of("FloatRep"), "evaluated", true);
            case "double" -> Map.of("kind", "double", "primReps", List.of("DoubleRep"), "evaluated", true);
            default -> LONG;
        };
        Object value = switch (carrier) {
            case "int" -> Integer.MIN_VALUE;
            case "float" -> Float.intBitsToFloat(0xffc01234);
            case "double" -> Double.longBitsToDouble(0xfff8000000001234L);
            default -> Long.MIN_VALUE;
        };
        Object second = switch (carrier) {
            case "int" -> 71;
            case "float" -> -0.0f;
            case "double" -> -0.0d;
            default -> 9000000001L;
        };
        Map<String, Object> argument = Map.of("id", "x", "name", "x", "lifted", false, "rep", proof);
        // The arm captures the original formal slot, not its adjacent case binder.
        List<Object> expression = node("case", node("var", "x", Map.of("rep", proof)), "seen",
                List.of(node("default", null, List.of(), node("var", "x", Map.of("rep", proof)))),
                Map.of("rep", proof, "binder", Map.of("id", "seen", "name", "seen", "lifted", false, "rep", proof)));
        try (Context context = context()) {
            context.initialize("thc"); context.enter();
            try {
                Language language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                Program program = program(language, module(expression, argument, proof));
                RootCallTarget original = program.entryTarget("entry");
                FunctionRoot clone = NodeUtil.cloneNode((FunctionRoot) original.getRootNode());
                assertTrue(compile(original));
                assertEquals(0, ((FunctionRoot) original.getRootNode()).getGraphBudgetGeneration(), "small body stays inline");
                assertEquals(1, deferredArmCount(original.getRootNode()));
                bypass(original);
                long before = count(program);
                assertCarrier(value, ScalarTestCalls.callScalarTestTarget(original, new Object[]{0L, value}));
                assertEquals(before + 1, count(program));
                assertTrue(valid(original));
                // Explicit callback unit control, not evidence of a real budget failure.
                // StockGraphRecoveryTest exercises the actual failed-target lifecycle.
                assertEquals(1, clone.prepareGraphBudgetRetry(0));
                assertEquals(0, ((FunctionRoot) original.getRootNode()).getGraphBudgetGeneration());
                RootCallTarget extracted = clone.getCallTarget();
                assertTrue(compile(extracted));
                bypass(extracted);
                before = count(program);
                assertCarrier(second, ScalarTestCalls.callScalarTestTarget(extracted, new Object[]{0L, second}));
                assertEquals(before + 1, count(program));
                assertTrue(valid(extracted));
                AstCaseArm call = NodeUtil.findAllNodeInstances(clone, AstCaseArm.class).getFirst();
                assertEquals(original.getRootNode().getSourceSection(), call.getTarget().getRootNode().getSourceSection());
                assertFalse(call.getTarget().getRootNode().isCloningAllowed());
                RootCallTarget after = NodeUtil.cloneNode(clone).getCallTarget();
                assertEquals(1, ((FunctionRoot) after.getRootNode()).getGraphBudgetGeneration());
                assertTrue(compile(after));
                bypass(after);
                before = count(program);
                assertCarrier(value, ScalarTestCalls.callScalarTestTarget(after, new Object[]{0L, value}));
                assertEquals(before + 1, count(program));
                assertTrue(valid(after));
                assertEquals(1, clone.prepareGraphBudgetRetry(1), "finite extraction is exhausted");
            } finally { context.leave(); }
        }
    }

    private static void assertCarrier(Object expected, Object actual) {
        assertEquals(expected.getClass(), actual.getClass());
        if (expected instanceof Float value) assertEquals(Float.floatToRawIntBits(value), Float.floatToRawIntBits((Float) actual));
        else if (expected instanceof Double value) assertEquals(Double.doubleToRawLongBits(value), Double.doubleToRawLongBits((Double) actual));
        else assertEquals(expected, actual);
    }

    @Test void multipleArmsAndNestedChoicesDoNotInventAnExtractionPlan() throws Exception {
        List<Object> choice = node("case", variable("x"), "seen", List.of(
                node("lit", List.of("int", "0"), List.of(), integer(71)),
                node("default", null, List.of(), variable("seen"))), Map.of("rep", LONG, "binder", parameter("seen")));
        try (Context context = context()) {
            context.initialize("thc"); context.enter();
            try {
                Language language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                for (List<Object> expression : List.of(choice, primitive("+#", choice, integer(17)))) {
                    Program program = program(language, module(expression, parameter("x"), LONG));
                    FunctionRoot root = (FunctionRoot) program.entryTarget("entry").getRootNode();
                    assertTrue(deferredArmCount(root) == 0);
                    assertEquals(0, root.prepareGraphBudgetRetry(0));
                    assertEquals(0, count(program));
                }
            } finally { context.leave(); }
        }
    }

    @Test void preparedSideTargetsBelongToTheirLanguageContext() throws Exception {
        RootCallTarget previous = null;
        for (long value : new long[]{11, 9000000001L}) {
            try (Context context = context()) {
                context.initialize("thc"); context.enter();
                try {
                    Language language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                    List<Object> expression = node("case", variable("x"), "seen",
                            List.of(node("default", null, List.of(), primitive("+#", variable("seen"), integer(17)))),
                            Map.of("rep", LONG, "binder", parameter("seen")));
                    Program program = program(language, module(expression, parameter("x"), LONG));
                    RootCallTarget target = program.entryTarget("entry");
                    assertEquals(1, ((FunctionRoot) target.getRootNode()).prepareGraphBudgetRetry(0));
                    AstCaseArm arm = NodeUtil.findAllNodeInstances(target.getRootNode(), AstCaseArm.class).getFirst();
                    assertNotSame(previous, arm.getTarget());
                    previous = arm.getTarget();
                    assertTrue(compile(target));
                    assertEquals(0, count(program));
                    bypass(target);
                    assertEquals(value + 17, Calls.target(target, new Object[]{0L, value}));
                    assertEquals(1, count(program));
                    assertTrue(valid(target));
                } finally { context.leave(); }
            }
        }
    }
}
