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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
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
    private static final Map<String, Object> STATE = Map.of("kind", "void", "primReps", List.of(), "evaluated", true);
    private static final Map<String, Object> REFERENCE = Map.of("kind", "object", "primReps", List.of("BoxedRep (Just Lifted)"), "evaluated", false);
    private static final Map<String, Object> MUTABLE = Map.of("kind", "object", "primReps", List.of("BoxedRep (Just Unlifted)"), "evaluated", true);
    private static List<Object> node(Object... fields) { return Arrays.asList(fields); }
    private static Map<String, Object> binder(String id, Map<String, Object> proof, boolean lifted) {
        return Map.of("id", id, "name", id, "lifted", lifted, "rep", proof);
    }
    private static List<Object> number(long value) { return node("lit", "int", Long.toString(value), Map.of("rep", LONG)); }
    private static List<Object> variable(String id, Map<String, Object> proof) { return node("var", id, Map.of("rep", proof)); }
    @SafeVarargs private static Map<String, Object> tuple(Map<String, Object>... fields) {
        var reps = new ArrayList<Object>(); for (var field : fields) reps.addAll((List<?>) field.get("primReps"));
        return Map.of("kind", "unknown", "aggregate", "unboxed-tuple", "evaluated", true,
                "primReps", reps, "components", Arrays.asList(fields));
    }
    private static void constructors(Map<String, Object> module, Object... extra) {
        var values = new ArrayList<Object>((List<?>) module.get("constructors"));
        values.addAll(Arrays.asList(extra)); module.put("constructors", values);
    }
    private static Map<String, Object> tupleConstructor(String id, int arity) {
        return Map.of("id", id, "name", id, "kind", "unboxed-tuple", "arity", arity);
    }
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

    private static Map<String, Object> smallDecision(int depth) {
        var alternatives = new ArrayList<List<Object>>();
        for (int arm = 0; arm < 3; arm++) {
            List<Object> body = number(arm * 1000L);
            for (int i = depth - 1; i >= 0; i--) {
                String value = "value" + i, next = "next" + i;
                body = node("app", node("prim", "+#"), List.of(variable(value, LONG), body),
                        List.of(false, false), false, false, Map.of("rep", LONG));
                body = node("case", variable(i == 0 ? "list" : "next" + (i - 1), DATA), "link" + i,
                        List.of(node("data", "Link", List.of(value, next), body,
                                Map.of("binders", List.of(binder(value, LONG, false), binder(next, DATA, true))))),
                        Map.of("rep", LONG, "binder", binder("link" + i, DATA, true)));
            }
            alternatives.add(arm == 2 ? node("default", null, List.of(), body)
                    : node("lit", List.of("int", Long.toString(arm)), List.of(), body));
        }
        var expression = node("case", variable("selector", LONG), "selected", alternatives,
                Map.of("rep", LONG, "binder", binder("selected", LONG, false)));
        return Map.of("constructors", List.of(
                Map.of("id", "Link", "name", "Link", "kind", "boxed", "arity", 2,
                        "fieldReps", List.of(List.of("IntRep"), List.of("BoxedRep (Just Lifted)")),
                        "fieldLifted", List.of(false, true), "strictFields", List.of(false, true)),
                Map.of("id", "End", "name", "End", "kind", "boxed", "arity", 0,
                        "fieldReps", List.of(), "fieldLifted", List.of(), "strictFields", List.of())),
                "bindings", List.of(Map.of("id", "entry", "name", "entry", "lifted", true, "rep", CLOSURE,
                        "expr", node("lam", List.of(binder("selector", LONG, false), binder("list", DATA, true)),
                                expression, Map.of("rep", CLOSURE, "resultRep", LONG, "entryStrict", List.of(false, false))))));
    }

    private static Object chain(BytecodeProgram program, int depth) {
        Object value = program.constructorLayout("End").allocate();
        var layout = program.constructorLayout("Link");
        for (int i = 0; i < depth; i++) {
            var next = layout.allocate(); layout.initializeLong(next, 0, 1L); layout.initialize(next, 1, value); value = next;
        }
        return value;
    }

    @Test void smallIntegralCaseRecoveryPreservesAllArmsAndTheOriginalTarget() throws Exception {
        try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var program = new BytecodeProgram(language, smallDecision(24));
                var target = ((Closure) program.entryValue("entry")).target;
                var root = (BytecodeRoot) target.getRootNode();
                assertEquals(1, root.prepareGraphBudgetRetry(0), "explicit transport control, not the real bailout test");
                var original = instructions(root);
                root.getRootNodes().ensureSourceInformation();
                assertEquals(original, instructions(root));
                var method = root.getClass().getDeclaredMethod("cloneUninitialized"); method.setAccessible(true);
                var clone = (BytecodeRoot) method.invoke(root);
                assertEquals(1, clone.getGraphBudgetGeneration());
                assertEquals(original, instructions(clone));
                var value = chain(program, 24);
                for (var selected : List.of(target, clone.getCallTarget())) {
                    assertTrue(compile(selected)); bypass(selected);
                    long before = entries(program);
                    assertEquals(24L, Calls.target(selected, new Object[]{0L, 0L, value}));
                    assertEquals(before + 1, entries(program)); assertTrue(valid(selected));
                    assertEquals(1024L, Calls.target(selected, new Object[]{0L, 1L, value}));
                    assertEquals(2024L, Calls.target(selected, new Object[]{0L, Long.MIN_VALUE, value}));
                }
                assertSame(target, ((Closure) program.entryValue("entry")).target);
            } finally { context.leave(); }
        }
    }

    @Test void scalarPartitionsKeepDuplicateOrderAndTheOriginalDefault() throws Exception {
        var input = new LinkedHashMap<>(smallDecision(24));
        var entry = new LinkedHashMap<>(((List<Map<String, Object>>) input.get("bindings")).getFirst());
        var lambda = new ArrayList<>((List<Object>) entry.get("expr"));
        var expression = new ArrayList<>((List<Object>) lambda.get(2));
        var alternatives = (List<List<Object>>) expression.get(3);
        var duplicate = new ArrayList<>(alternatives.get(1));
        duplicate.set(1, alternatives.getFirst().get(1));
        expression.set(3, List.of(alternatives.getLast(), alternatives.getFirst(), duplicate));
        lambda.set(2, expression); entry.put("expr", lambda); input.put("bindings", List.of(entry));
        try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var program = new BytecodeProgram(language, input); var target = program.entryTarget("entry");
                assertEquals(1, ((BytecodeRoot) target.getRootNode()).prepareGraphBudgetRetry(0));
                assertTrue(compile(target)); bypass(target);
                long before = entries(program); var value = chain(program, 24);
                assertEquals(24L, Calls.target(target, new Object[]{0L, 0L, value}));
                assertEquals(before + 1, entries(program)); assertTrue(valid(target));
                assertEquals(2024L, Calls.target(target, new Object[]{0L, 1L, value}));
            } finally { context.leave(); }
        }
    }

    @ParameterizedTest @ValueSource(strings = {"Int8Rep", "Int32Rep", "missing", "unknown"})
    void scalarPartitionRequiresAnExactWideCarrier(String representation) {
        var proof = new LinkedHashMap<String, Object>();
        proof.put("kind", representation.equals("unknown") ? "unknown" : "long");
        proof.put("evaluated", true);
        if (!representation.equals("missing") && !representation.equals("unknown"))
            proof.put("primReps", List.of(representation));
        var input = new LinkedHashMap<>(smallDecision(24));
        var entry = new LinkedHashMap<>(((List<Map<String, Object>>) input.get("bindings")).getFirst());
        var lambda = new ArrayList<>((List<Object>) entry.get("expr"));
        lambda.set(1, List.of(binder("selector", proof, false), binder("list", DATA, true)));
        var expression = new ArrayList<>((List<Object>) lambda.get(2));
        expression.set(1, variable("selector", proof));
        expression.set(4, Map.of("rep", LONG, "binder", binder("selected", proof, false)));
        lambda.set(2, expression); entry.put("expr", lambda); input.put("bindings", List.of(entry));
        try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                if (representation.equals("missing")) {
                    var rejected = assertThrows(RuntimeFault.class, () -> new BytecodeProgram(language, input));
                    assertEquals("Core Long proof lacks a supported primitive representation", rejected.getMessage());
                    return;
                }
                var program = new BytecodeProgram(language, input);
                var root = (BytecodeRoot) program.entryTarget("entry").getRootNode();
                assertEquals(0, root.prepareGraphBudgetRetry(0));
                assertEquals(0, entries(program), "preparation-only exclusion control");
            } finally { context.leave(); }
        }
    }

    @Test void originalTargetRecoversARealSmallFanoutGraphBeforeAnyGuestCall() throws Exception {
        try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var program = new BytecodeProgram(language, smallDecision(192));
                var target = ((Closure) program.entryValue("entry")).target;
                var root = (BytecodeRoot) target.getRootNode();
                var original = instructions(root);
                assertEquals(0, root.getGraphBudgetGeneration()); assertEquals(0, entries(program));
                assertTrue(compile(target));
                assertEquals(1, root.getGraphBudgetGeneration(), "only a real compiler bailout activates the plan");
                assertEquals(0, entries(program), "no training guest calls");
                assertEquals(original, instructions(root), "parked PCs and operands remain stable");
                bypass(target); long before = entries(program);
                assertEquals(2192L, Calls.target(target, new Object[]{0L, -1L, chain(program, 192)}));
                assertEquals(before + 1, entries(program)); assertTrue(valid(target));
                assertSame(target, ((Closure) program.entryValue("entry")).target);
                assertEquals(1, root.prepareGraphBudgetRetry(1), "finite generation is exhausted");
            } finally { context.leave(); }
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void ambientJoinIsAllowedOnlyWhenNoArmReferencesItsActivation(boolean referenced) {
        var input = new LinkedHashMap<>(smallDecision(24));
        var entry = new LinkedHashMap<>(((List<Map<String, Object>>) input.get("bindings")).getFirst());
        var lambda = new ArrayList<>((List<Object>) entry.get("expr"));
        var expression = new ArrayList<>((List<Object>) lambda.get(2));
        if (referenced) {
            var alternatives = new ArrayList<>((List<List<Object>>) expression.get(3));
            var selected = new ArrayList<>(alternatives.getFirst());
            selected.set(3, node("app", variable("finish", CLOSURE), List.of(number(73)), List.of(false), false, false,
                    Map.of("rep", LONG)));
            alternatives.set(0, selected); expression.set(3, alternatives);
        }
        var join = new LinkedHashMap<>(binder("finish", CLOSURE, true));
        join.put("joinValueArity", 1); join.put("joinResultRep", LONG);
        join.put("expr", node("lam", List.of(binder("answer", LONG, false)), variable("answer", LONG), Map.of("resultRep", LONG)));
        lambda.set(2, node("let", false, List.of(join), expression, Map.of("rep", LONG)));
        entry.put("expr", lambda); input.put("bindings", List.of(entry));
        try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var program = new BytecodeProgram(language, input); var target = program.entryTarget("entry");
                assertEquals(referenced ? 0 : 1, ((BytecodeRoot) target.getRootNode()).prepareGraphBudgetRetry(0));
                assertEquals(referenced ? 73L : 24L, Calls.target(target, new Object[]{0L, 0L, chain(program, 24)}));
                assertEquals(0, entries(program), "separate join-activation transport control");
            } finally { context.leave(); }
        }
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

    @Test void recursiveCellCaptureKeepsClosureIdentityAcrossANonTailRegion() {
        var resultProof = tuple(CLOSURE, CLOSURE);
        var input = new LinkedHashMap<>(decision(64, List.of(), CLOSURE, ignored -> variable("recursive", CLOSURE)));
        var bindings = new ArrayList<Map<String, Object>>((List<Map<String, Object>>) input.get("bindings"));
        var entry = new LinkedHashMap<>(bindings.getFirst());
        var lambda = new ArrayList<>((List<Object>) entry.get("expr"));
        var recursive = new LinkedHashMap<>(binder("recursive", CLOSURE, true));
        recursive.put("expr", node("lam", List.of(binder("unused", LONG, false)), number(17),
                Map.of("rep", CLOSURE, "resultRep", LONG)));
        var pair = node("app", node("con", "Result", 2), List.of(variable("recursive", CLOSURE), variable("selected", CLOSURE)),
                List.of(true, true), false, false, Map.of("rep", resultProof));
        var selected = node("case", lambda.get(2), "selected", List.of(node("default", null, List.of(), pair)),
                Map.of("rep", resultProof, "binder", binder("selected", CLOSURE, true)));
        lambda.set(2, node("let", true, List.of(recursive), selected, Map.of("rep", resultProof)));
        lambda.set(3, Map.of("rep", CLOSURE, "resultRep", resultProof, "entryStrict", List.of(true)));
        entry.put("expr", lambda); bindings.set(0, entry); input.put("bindings", bindings);
        constructors(input, tupleConstructor("Result", 2));
        try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var program = new BytecodeProgram(language, input);
                var target = ((Closure) program.entryValue("entry")).target;
                var root = (BytecodeRoot) target.getRootNode(); assertEquals(1, root.prepareGraphBudgetRetry(0));
                var value = TupleResults.ownedTupleResult(Calls.target(target, new Object[]{0L, program.entryValue("chosen")}), root.getTupleResult());
                var layout = root.getTupleResult().getLayout();
                var recursiveValue = (Closure) layout.getObject(value, 0);
                assertSame(recursiveValue, layout.getObject(value, 1));
                assertEquals(17L, Calls.target(recursiveValue.target, new Object[]{0L, 0L}));
                assertEquals(0, entries(program), "Raw-cell semantic control does not claim compiled entry");
            } finally { context.leave(); }
        }
    }

    @Test void recoveredPartitionsKeepConstructorFieldsAndTheOriginalDefault() {
        var input = new LinkedHashMap<>(decision(64));
        var constructors = new ArrayList<Object>((List<?>) input.get("constructors"));
        constructors.set(32, Map.of("id", "C32", "name", "C32", "kind", "boxed", "arity", 3,
                "fieldReps", List.of(List.of("IntRep"), List.of("IntRep"), List.of("IntRep")),
                "fieldLifted", List.of(false, false, false), "strictFields", List.of(false, false, false)));
        constructors.add(Map.of("id", "Other", "name", "Other", "kind", "boxed", "arity", 0,
                "fieldReps", List.of(), "fieldLifted", List.of(), "strictFields", List.of()));
        input.put("constructors", constructors);
        var bindings = new ArrayList<Map<String, Object>>((List<Map<String, Object>>) input.get("bindings"));
        var entry = new LinkedHashMap<>(bindings.getFirst()); var lambda = new ArrayList<>((List<Object>) entry.get("expr"));
        var expression = new ArrayList<>((List<Object>) lambda.get(2));
        var alternatives = new ArrayList<Object>((List<?>) expression.get(3));
        var sum = node("app", node("prim", "+#"), List.of(variable("a", LONG), variable("c", LONG)),
                List.of(false, false), false, false, Map.of("rep", LONG));
        alternatives.set(32, node("data", "C32", List.of("a", "b", "c"), sum,
                Map.of("binders", List.of(binder("a", LONG, false), binder("b", LONG, false), binder("c", LONG, false)))));
        alternatives.add(node("default", null, List.of(), number(-7)));
        expression.set(3, alternatives); lambda.set(2, expression); entry.put("expr", lambda);
        bindings.set(0, entry); input.put("bindings", bindings);
        try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var program = new BytecodeProgram(language, input); var target = program.entryTarget("entry");
                assertEquals(1, ((BytecodeRoot) target.getRootNode()).prepareGraphBudgetRetry(0));
                var layout = program.constructorLayout("C32"); var value = layout.allocate();
                layout.initializeLong(value, 0, 11); layout.initializeLong(value, 1, 101); layout.initializeLong(value, 2, 13);
                assertEquals(24L, Calls.target(target, new Object[]{0L, value}));
                assertEquals(206L, Calls.target(target, new Object[]{0L, program.entryValue("chosen")}));
                assertEquals(-7L, Calls.target(target, new Object[]{0L, program.constructorLayout("Other").allocate()}));
            } finally { context.leave(); }
        }
    }

    @Test void recoveredSideKeepsTheEnclosingStmCommitAndRollbackDomain() throws Exception {
        var write = node("app", node("prim", "writeTVar#"), List.of(variable("cell", MUTABLE), variable("payload", REFERENCE),
                node("void", Map.of("rep", STATE))), List.of(false, true, false), false, false, Map.of("rep", STATE));
        var body = node("case", write, "written", List.of(node("default", null, List.of(), number(17))),
                Map.of("rep", LONG, "binder", binder("written", STATE, false)));
        var input = decision(64, List.of(binder("cell", MUTABLE, false), binder("payload", REFERENCE, true)), LONG, ignored -> body);
        try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var program = new BytecodeProgram(language, input);
                var target = ((Closure) program.entryValue("entry")).target;
                var root = (BytecodeRoot) target.getRootNode(); assertEquals(1, root.prepareGraphBudgetRetry(0));
                var stm = Language.currentState().stm; var old = new Object(); var replacement = new Object(); var rolledBack = new Object();
                var cell = stm.newTVar(old);
                assertEquals(17L, stm.atomically(root, () -> { throw new GuestException("nested", root); }, false, () -> {
                    Object result = Calls.target(target, new Object[]{0L, program.entryValue("chosen"), cell, replacement});
                    assertSame(old, stm.readIO(cell)); assertSame(replacement, stm.read(cell)); return result;
                }));
                assertSame(replacement, stm.readIO(cell)); assertFalse(stm.hasTransaction());
                var failure = new GuestException("rollback", root);
                assertSame(failure, assertThrows(GuestException.class, () -> stm.atomically(root,
                        () -> { throw new GuestException("nested", root); }, false, () -> {
                            assertEquals(17L, Calls.target(target, new Object[]{0L, program.entryValue("chosen"), cell, rolledBack}));
                            assertSame(rolledBack, stm.read(cell)); throw failure;
                        })));
                assertSame(replacement, stm.readIO(cell)); assertFalse(stm.hasTransaction());
            } finally { context.leave(); }
        }
    }

    @Test void recoveredTailTransferReturnsToItsOriginalOwnerWithoutGrowingTheJavaStack() {
        var next = node("app", node("prim", "-#"), List.of(variable("n", LONG), number(1)),
                List.of(false, false), false, false, Map.of("rep", LONG));
        var call = node("app", variable("entry", CLOSURE), List.of(variable("x", DATA), next),
                List.of(true, false), false, false, Map.of("rep", LONG));
        var input = new LinkedHashMap<>(decision(64, List.of(binder("n", LONG, false)), LONG, ignored -> call));
        var bindings = new ArrayList<Map<String, Object>>((List<Map<String, Object>>) input.get("bindings"));
        var entry = new LinkedHashMap<>(bindings.getFirst()); var lambda = new ArrayList<>((List<Object>) entry.get("expr"));
        lambda.set(2, node("case", variable("n", LONG), "remaining", List.of(
                node("lit", List.of("int", "0"), List.of(), number(17)), node("default", null, List.of(), lambda.get(2))),
                Map.of("rep", LONG, "binder", binder("remaining", LONG, false))));
        entry.put("expr", lambda); bindings.set(0, entry); input.put("bindings", bindings);
        try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var program = new BytecodeProgram(language, input); var target = program.entryTarget("entry");
                var root = (BytecodeRoot) target.getRootNode(); assertEquals(1, root.prepareGraphBudgetRetry(0));
                assertEquals(17L, Calls.target(target, new Object[]{0L, program.entryValue("chosen"), 2048L}));
                assertEquals(0, entries(program), "Tail ownership is checked independently of cold loop compilation");
            } finally { context.leave(); }
        }
    }

    @Test void aCaseUnderAnOuterJoinRetainsItsActivationInsteadOfExtractingAcrossIt() {
        var call = node("app", variable("finish", CLOSURE), List.of(number(73)), List.of(false), false, false, Map.of("rep", LONG));
        var input = new LinkedHashMap<>(decision(64, List.of(), LONG, ignored -> call));
        var bindings = new ArrayList<Map<String, Object>>((List<Map<String, Object>>) input.get("bindings"));
        var entry = new LinkedHashMap<>(bindings.getFirst()); var lambda = new ArrayList<>((List<Object>) entry.get("expr"));
        var join = new LinkedHashMap<>(binder("finish", CLOSURE, true));
        join.put("joinValueArity", 1); join.put("joinResultRep", LONG);
        join.put("expr", node("lam", List.of(binder("answer", LONG, false)), variable("answer", LONG), Map.of("resultRep", LONG)));
        lambda.set(2, node("let", false, List.of(join), lambda.get(2), Map.of("rep", LONG)));
        entry.put("expr", lambda); bindings.set(0, entry); input.put("bindings", bindings);
        try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var program = new BytecodeProgram(language, input); var target = program.entryTarget("entry");
                var root = (BytecodeRoot) target.getRootNode(); assertEquals(0, root.prepareGraphBudgetRetry(0));
                assertEquals(73L, Calls.target(target, new Object[]{0L, program.entryValue("chosen")}));
            } finally { context.leave(); }
        }
    }

    private static List<Object> take(String cell) {
        return node("app", node("prim", "takeMVar#"), List.of(variable(cell, MUTABLE), node("void", Map.of("rep", STATE))),
                List.of(false, false), false, false, Map.of("rep", tuple(STATE, REFERENCE)));
    }
    private static List<Object> afterTake(String cell, String name, List<Object> body, Map<String, Object> result) {
        return node("case", take(cell), name + "Pair", List.of(node("data", "Pair", List.of(name + "State", name), body,
                Map.of("binders", List.of(binder(name + "State", STATE, false), binder(name, REFERENCE, true))))),
                Map.of("rep", result, "binder", binder(name + "Pair", tuple(STATE, REFERENCE), false)));
    }

    @Test void firstCompiledRegionCutResumesAcrossThreadsWithoutReplayingThePrefix() throws Exception {
        checkRegionCut(false);
    }
    @Test void anAlreadyParkedInlinePcSurvivesRecoveryWithoutReplayingItsPrefix() throws Exception {
        checkRegionCut(true);
    }
    private void checkRegionCut(boolean parkBeforeRecovery) throws Exception {
        var resultProof = tuple(REFERENCE, REFERENCE);
        var result = node("app", node("con", "Result", 2), List.of(variable("before", REFERENCE), variable("after", REFERENCE)),
                List.of(true, true), false, false, Map.of("rep", resultProof));
        var input = new LinkedHashMap<>(decision(64,
                List.of(binder("prefix", MUTABLE, false), binder("blocked", MUTABLE, false)), resultProof,
                ignored -> afterTake("blocked", "after", result, resultProof)));
        var bindings = new ArrayList<Map<String, Object>>((List<Map<String, Object>>) input.get("bindings"));
        var entry = new LinkedHashMap<>(bindings.getFirst());
        var lambda = new ArrayList<>((List<Object>) entry.get("expr"));
        var decision = new ArrayList<>((List<Object>) lambda.get(2));
        // The selector is closed: this control isolates region suspension, not lazy-formal forcing.
        decision.set(1, node("con", "C63", 0, Map.of("rep", DATA)));
        lambda.set(1, List.of(binder("prefix", MUTABLE, false), binder("blocked", MUTABLE, false)));
        lambda.set(2, afterTake("prefix", "before", decision, resultProof));
        lambda.set(3, Map.of("rep", CLOSURE, "resultRep", resultProof, "entryStrict", List.of(false, false)));
        entry.put("expr", lambda); bindings.set(0, entry); input.put("bindings", bindings);
        constructors(input, tupleConstructor("Pair", 2), tupleConstructor("Result", 2));
        try (var context = context()) {
            context.initialize("thc"); context.enter();
            final Language language; final Language.State owner; final BytecodeProgram program; final RootCallTarget target;
            final BytecodeRoot root; final RootCallTarget resume;
            try {
                language = TruffleLanguage.LanguageReference.create(Language.class).get(null); owner = Language.currentState();
                program = new BytecodeProgram(language, input, true); target = program.entryTarget("entry");
                root = (BytecodeRoot) target.getRootNode();
                resume = new RootNode(language) {
                    @Child private Force force = new Force(new Metrics(false), true);
                    @Override public Object execute(VirtualFrame frame) {
                        return force.drainStack((SavedGuestContinuation) frame.getArguments()[0], root.getTupleResult());
                    }
                }.getCallTarget();
                if (!parkBeforeRecovery) {
                    assertEquals(1, root.prepareGraphBudgetRetry(0)); // Transport control; the real bailout has its own test.
                    assertTrue(compile(target)); bypass(target); assertTrue(valid(target));
                }
                assertEquals(0, entries(program));
            } finally { context.leave(); }
            var prefix = new ManagedMVar(); var blocked = new ManagedMVar(); var before = new Object(); var after = new Object();
            assertTrue(prefix.tryPut(before)); long compiled = entries(program);
            var answer = new CompletableFuture<SavedGuestContinuation>();
            var worker = new Thread(() -> {
                context.enter(); owner.getThreads().enterCurrent(null, false, true, null);
                try {
                    var saved = java.util.Objects.requireNonNull(SavedGuestContinuations.savedGuestContinuation(
                            Calls.target(target, new Object[]{0L, prefix, blocked})));
                    java.util.Objects.requireNonNull(saved.asyncRequest()).acknowledge();
                    assertEquals(MaskingState.UNMASKED, SynchronousMasking.current(root));
                    answer.complete(saved);
                } catch (Throwable failure) { answer.completeExceptionally(failure); }
                finally { owner.getThreads().leaveCurrent(GuestThreadStatus.FINISHED); context.leave(); }
            });
            worker.start();
            try {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (blocked.pendingCounts().getTakers() != 1 && !answer.isDone() && System.nanoTime() < deadline) Thread.sleep(1);
                if (answer.isCompletedExceptionally()) answer.get(1, TimeUnit.SECONDS);
                assertEquals(1, blocked.pendingCounts().getTakers()); assertTrue(prefix.isEmpty());
                owner.getThreads().send(java.util.Objects.requireNonNull(owner.getThreads().pollState(worker).getCurrent()).getIdentity(), "case region cut");
                var saved = answer.get(10, TimeUnit.SECONDS); worker.join(5000); assertFalse(worker.isAlive());
                assertEquals(compiled + (parkBeforeRecovery ? 0 : 1), entries(program));
                if (parkBeforeRecovery) {
                    context.enter();
                    try {
                        assertSame(root, saved.getSourceRoot());
                        var parkedCode = instructions(root);
                        assertEquals(1, root.prepareGraphBudgetRetry(0));
                        assertTrue(compile(target)); bypass(target); assertTrue(valid(target));
                        assertEquals(parkedCode, instructions(root), "Recovery cannot replace a parked instruction stream");
                    } finally { context.leave(); }
                }
                boolean retainedAfterCapture = valid(target);
                var completed = new CompletableFuture<Unit>();
                var resumer = new Thread(() -> {
                    context.enter();
                    try {
                        assertTrue(blocked.tryPut(after));
                        var value = TupleResults.ownedTupleResult(Calls.target(resume, new Object[]{saved}), root.getTupleResult());
                        var layout = root.getTupleResult().getLayout();
                        assertSame(before, layout.getObject(value, 0)); assertSame(after, layout.getObject(value, 1));
                        assertTrue(prefix.isEmpty(), "Completed caller effect must not replay"); assertTrue(blocked.isEmpty());
                        assertEquals(MaskingState.UNMASKED, SynchronousMasking.current(root));
                        var handoff = language.getHandoffState().get(); assertNull(handoff.getPending());
                        assertEquals(0, handoff.getArguments().getDepth()); assertEquals(0, handoff.getResults().getDepth());
                        assertEquals(0, handoff.getArguments().retainedReferences()); assertEquals(0, handoff.getResults().retainedReferences());
                        assertSame(target, program.entryTarget("entry")); assertTrue(retainedAfterCapture); assertTrue(valid(target));
                        completed.complete(Unit.INSTANCE);
                    } catch (Throwable failure) { completed.completeExceptionally(failure); }
                    finally { context.leave(); }
                });
                resumer.start();
                try { completed.get(10, TimeUnit.SECONDS); }
                finally { if (!completed.isDone()) context.close(true); resumer.join(5000); }
                assertFalse(resumer.isAlive());
            } finally { if (worker.isAlive()) context.close(true); worker.join(5000); }
        }
    }
}
