// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
    private static List<Object> variable(String id, Map<String, Object> proof) { return node("var", id, Map.of("rep", proof)); }
    private static Map<String, Object> decision(int count) {
        return decision(count, List.of(), LONG, i -> number(i * 3L + 17));
    }
    private static Map<String, Object> decision(int count, List<Map<String, Object>> parameters,
            Map<String, Object> result, java.util.function.IntFunction<List<Object>> body) {
        var constructors = new ArrayList<Map<String, Object>>();
        var alternatives = new ArrayList<List<Object>>();
        for (int i = 0; i < count; i++) {
            String id = "C" + i;
            constructors.add(Map.of("id", id, "name", id, "arity", 0, "tag", i + 1,
                    "kind", "boxed", "strictFields", List.of(), "fieldLifted", List.of(), "fieldReps", List.of()));
            alternatives.add(node("data", id, List.of(), body.apply(i)));
        }
        var expression = node("case", node("var", "x", Map.of("rep", DATA)), "seen", alternatives,
                Map.of("rep", result, "binder", binder("seen", DATA, true)));
        var arguments = new ArrayList<Map<String, Object>>(); arguments.add(binder("x", DATA, true)); arguments.addAll(parameters);
        var strict = new ArrayList<Boolean>(); strict.add(true); for (var ignored : parameters) strict.add(false);
        return Map.of("constructors", constructors, "bindings", List.of(
                Map.of("id", "entry", "name", "entry", "lifted", true, "rep", CLOSURE,
                        "expr", node("lam", arguments, expression,
                                Map.of("rep", CLOSURE, "resultRep", result, "entryStrict", strict))),
                Map.of("id", "chosen", "name", "chosen", "lifted", false, "rep", DATA,
                        "expr", node("con", "C" + (count - 1), 0, Map.of("rep", DATA)))));
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void recoveredCaptureKeepsExactScalarLeavesAndUnforcedReferenceIdentity(boolean aggregate) throws Exception {
        var narrow = Map.<String, Object>of("kind", "long", "primReps", List.of("Int8Rep"), "evaluated", true);
        var floating = Map.<String, Object>of("kind", "float", "primReps", List.of("FloatRep"), "evaluated", true);
        var doubleRep = Map.<String, Object>of("kind", "double", "primReps", List.of("DoubleRep"), "evaluated", true);
        var lazy = Map.<String, Object>of("kind", "object", "primReps", List.of("BoxedRep (Just Lifted)"), "evaluated", false);
        var fields = List.of(narrow, LONG, floating, doubleRep, lazy);
        var reps = new ArrayList<Object>(); for (var proof : fields) reps.addAll((List<?>) proof.get("primReps"));
        var tuple = Map.<String, Object>of("kind", "unknown", "aggregate", "unboxed-tuple", "evaluated", true,
                "primReps", reps, "components", fields);
        var parameters = new ArrayList<Map<String, Object>>();
        var values = new ArrayList<List<Object>>();
        for (int i = 0; i < fields.size(); i++) {
            parameters.add(binder("payload" + i, fields.get(i), i == 4));
            values.add(variable("payload" + i, fields.get(i)));
        }
        var input = new LinkedHashMap<>(decision(64, aggregate ? List.of(binder("payload", tuple, false)) : parameters, tuple, ignored ->
                aggregate ? variable("payload", tuple) : node("app", node("con", "Result", 5), values,
                        List.of(false, false, false, false, true), false, false, Map.of("rep", tuple))));
        var constructors = new ArrayList<Object>((List<?>) input.get("constructors"));
        constructors.add(Map.of("id", "Result", "name", "Result", "kind", "unboxed-tuple", "arity", 5));
        input.put("constructors", constructors);
        try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var program = new BytecodeProgram(language, input);
                var target = ((Closure) program.entryValue("entry")).target;
                var root = (BytecodeRoot) target.getRootNode();
                var poison = new Thunk(new RootNode(language) {
                    @Override public Object execute(VirtualFrame frame) { throw new AssertionError("Lazy captured payload forced"); }
                }.getCallTarget(), null);
                assertEquals(1, root.prepareGraphBudgetRetry(0)); // Transport control, not a fabricated bailout.
                assertTrue(compile(target)); bypass(target);
                long before = entries(program);
                var entry = root.getTypedInput();
                var packet = entry.getPacket(); var inputStorage = entry.state().getArguments().acquire(packet);
                inputStorage.setInputMode(1);
                Object answer;
                try {
                    packet.setLong(inputStorage, 0, 0L); packet.setObject(inputStorage, 1, program.entryValue("chosen"));
                    packet.setInt(inputStorage, 2, -127); packet.setLong(inputStorage, 3, 0x7123456789abcdefL);
                    packet.setFloat(inputStorage, 4, -0.0f); packet.setDouble(inputStorage, 5, Double.longBitsToDouble(0x7ff8000000000017L));
                    packet.setObject(inputStorage, 6, poison);
                    answer = TypedInputs.invokeTypedInput(entry, inputStorage, args -> Calls.target(target, args));
                } finally { entry.releaseChecked(inputStorage); }
                var value = TupleResults.ownedTupleResult(answer, root.getTupleResult());
                var layout = root.getTupleResult().getLayout();
                assertEquals(-127, layout.getInt(value, 0));
                assertEquals(0x7123456789abcdefL, layout.getLong(value, 1));
                assertEquals(0x80000000, Float.floatToRawIntBits(layout.getFloat(value, 2)));
                assertEquals(0x7ff8000000000017L, Double.doubleToRawLongBits(layout.getDouble(value, 3)));
                assertSame(poison, layout.getObject(value, 4)); assertEquals(0, poison.getState());
                assertEquals(before + 1, entries(program)); assertTrue(valid(target));
                var state = language.getHandoffState().get();
                assertNull(state.getPending()); assertEquals(0, state.getArguments().getDepth()); assertEquals(0, state.getResults().getDepth());
            } finally { context.leave(); }
        }
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
    private static Map<Integer, String> instructions(BytecodeRoot root) {
        var result = new java.util.LinkedHashMap<Integer, String>();
        for (var instruction : root.getBytecodeNode().getInstructions()) {
            var operands = new ArrayList<String>();
            for (var argument : instruction.getArguments()) {
                String value = argument.toString();
                // Cache/profile display is not the instruction encoding or a guest PC.
                if (!value.startsWith("node(") && !value.startsWith("branch_profile(")) operands.add(value);
            }
            result.put(instruction.getBytecodeIndex(), instruction.getName() + operands);
        }
        return result;
    }

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
                var originalInstructions = instructions(root);
                assertEquals(0, entries(program));
                assertEquals(0, root.getGraphBudgetGeneration());
                try { assertTrue(compile(target)); }
                catch (Exception failure) {
                    throw new AssertionError("Original target compile failed at generation " + root.getGraphBudgetGeneration(), failure);
                }
                assertEquals(0, entries(program), "compilation cannot run the guest");
                var preparedInstructions = instructions(root);
                assertEquals(originalInstructions.keySet(), preparedInstructions.keySet(), "parked bytecode PCs must not change");
                for (var instruction : originalInstructions.entrySet())
                    assertEquals(instruction.getValue(), preparedInstructions.get(instruction.getKey()),
                            "instruction at " + instruction.getKey());
                var chosen = program.entryValue("chosen");
                bypass(target);
                long before = entries(program);
                assertEquals(2318L, Calls.target(target, new Object[]{0L, chosen}));
                assertAll(
                    () -> assertEquals(1, root.getGraphBudgetGeneration(), "a real graph bailout must select a smaller finite case region"),
                    () -> assertEquals(before + 1, entries(program), "the original first call enters its installed target"),
                    () -> assertSame(target, ((Closure) program.entryValue("entry")).target),
                    () -> assertTrue(valid(target), "no settling call or target replacement"));
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

    @Test void recoveredChoiceSurvivesSourceReplayAndCloningWithoutGuestTraining() throws Exception {
        try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var program = new BytecodeProgram(language, decision(96));
                var target = ((Closure) program.entryValue("entry")).target;
                var root = (BytecodeRoot) target.getRootNode();
                var original = instructions(root);
                assertEquals(1, root.prepareGraphBudgetRetry(0));
                root.getRootNodes().ensureSourceInformation();
                assertEquals(original, instructions(root));
                var method = root.getClass().getDeclaredMethod("cloneUninitialized"); method.setAccessible(true);
                var clone = (BytecodeRoot) method.invoke(root);
                var cloneTarget = clone.getCallTarget();
                assertNotSame(root, clone); assertNotSame(target, cloneTarget);
                assertEquals(1, clone.getGraphBudgetGeneration());
                assertEquals(original, instructions(clone));
                assertEquals(0, entries(program));
                for (var selected : List.of(target, cloneTarget)) {
                    assertTrue(compile(selected)); bypass(selected);
                    long before = entries(program);
                    assertEquals(302L, Calls.target(selected, new Object[]{0L, program.entryValue("chosen")}));
                    assertEquals(before + 1, entries(program)); assertTrue(valid(selected));
                }
                assertSame(target, ((Closure) program.entryValue("entry")).target);
            } finally { context.leave(); }
        }
    }
}
