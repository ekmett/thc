// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.nodes.DirectCallNode;
import java.lang.reflect.InvocationTargetException;
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

    private static List<Object> capacityCalls(int count, int arm, List<Object> body, Map<String, Object> result) {
        for (int call = count - 1; call >= 0; call--) {
            var argument = call == 0 ? number(arm * 1000L) : variable("value" + (call - 1), LONG);
            var application = node("app", variable("step", CLOSURE), List.of(argument),
                    List.of(false), false, false, Map.of("rep", LONG));
            body = node("case", application, "value" + call,
                    List.of(node("default", null, List.of(), body)),
                    Map.of("rep", result, "binder", binder("value" + call, LONG, false)));
        }
        return body;
    }

    @Test void capacityExtractionKeepsAClosedJoinPrefixWithItsSelectedCase() throws Exception {
        var finish = node("app", variable("later", CLOSURE), List.of(variable("value63", LONG)),
                List.of(false), false, false, Map.of("rep", LONG));
        var input = new LinkedHashMap<>(decision(64,
                List.of(binder("step", CLOSURE, true), binder("pick", CLOSURE, true)), LONG,
                arm -> capacityCalls(64, arm, finish, LONG)));
        var bindings = new ArrayList<Map<String, Object>>((List<Map<String, Object>>) input.get("bindings"));
        var binding = new LinkedHashMap<>(bindings.getFirst());
        var lambda = new ArrayList<>((List<Object>) binding.get("expr"));
        var choice = new ArrayList<>((List<Object>) lambda.get(2));
        choice.set(1, node("app", variable("pick", CLOSURE), List.of(variable("x", DATA)),
                List.of(true), false, false, Map.of("rep", DATA)));
        var first = new LinkedHashMap<>(binder("first", CLOSURE, true));
        first.put("joinValueArity", 1); first.put("joinResultRep", LONG);
        first.put("expr", node("lam", List.of(binder("a", LONG, false)),
                node("app", node("prim", "+#"), List.of(variable("a", LONG), variable("prefix", LONG)),
                        List.of(false, false), false, false, Map.of("rep", LONG)), Map.of("resultRep", LONG)));
        var later = new LinkedHashMap<>(binder("later", CLOSURE, true));
        later.put("joinValueArity", 1); later.put("joinResultRep", LONG);
        later.put("expr", node("lam", List.of(binder("b", LONG, false)),
                node("app", variable("first", CLOSURE), List.of(
                        node("app", node("prim", "+#"), List.of(variable("b", LONG), number(1)),
                                List.of(false, false), false, false, Map.of("rep", LONG))),
                        List.of(false), false, false, Map.of("rep", LONG)), Map.of("resultRep", LONG)));
        var region = node("let", false, List.of(first),
                node("let", false, List.of(later), choice, Map.of("rep", LONG)), Map.of("rep", LONG));
        var prefix = new LinkedHashMap<>(binder("prefix", LONG, false));
        prefix.put("expr", node("app", variable("step", CLOSURE), List.of(number(1000000)),
                List.of(false), false, false, Map.of("rep", LONG)));
        lambda.set(2, node("let", false, List.of(prefix), node("app", node("prim", "+#"),
                List.of(region, number(99)), List.of(false, false), false, false, Map.of("rep", LONG)), Map.of("rep", LONG)));
        binding.put("expr", lambda); bindings.set(0, binding); input.put("bindings", bindings);
        try (var context = context()) {
            context.initialize("thc"); context.enter();
            var threads = Language.currentState().getThreads(); threads.enterCurrent();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var program = new BytecodeProgram(language, input, true);
                var target = program.entryTarget("entry"); var root = (BytecodeRoot) target.getRootNode();
                assertEquals(0, root.prepareGraphBudgetRetry(0), "actual prepublication capacity recovery");
                var original = instructions(root);
                assertTrue(original.values().stream().noneMatch(value -> value.contains("InlineCaseRegions")));
                assertTrue(compile(target)); bypass(target); assertEquals(0, entries(program));
                var calls = new java.util.concurrent.atomic.AtomicInteger();
                var picks = new java.util.concurrent.atomic.AtomicInteger();
                var step = new Closure(null, 1, new RootNode(language) {
                    @Override public Object execute(VirtualFrame frame) {
                        int call = calls.getAndIncrement();
                        assertEquals(call == 0 ? 1000000L : 63000L + call - 1, frame.getArguments()[1]);
                        assertEquals(call == 0 ? 0 : 1, picks.get());
                        return (Long) frame.getArguments()[1] + 1;
                    }
                }.getCallTarget());
                var pick = new Closure(null, 1, new RootNode(language) {
                    @Override public Object execute(VirtualFrame frame) {
                        assertEquals(1, calls.get()); assertEquals(0, picks.getAndIncrement());
                        return frame.getArguments()[1];
                    }
                }.getCallTarget());
                assertEquals(1063165L, Calls.target(target, new Object[]{0L, program.entryValue("chosen"), step, pick}));
                assertEquals(65, calls.get()); assertEquals(1, picks.get()); assertEquals(1, entries(program));
                assertEquals(2L, ((Number) program.diagnostics().get("localJoinTransfers")).longValue());
                assertSame(target, program.entryTarget("entry"));
                assertEquals(original.keySet(), instructions(root).keySet());
                var executed = instructions(root); root.getRootNodes().ensureSourceInformation();
                assertEquals(executed, instructions(root), "source replay preserves the published instruction stream");
            } finally { threads.leaveCurrent(); context.leave(); }
        }
    }

    @Test void onlyThePinnedLocalIdOverflowCanSelectCapacityRecovery() throws Exception {
        try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var check = BytecodeProgram.class.getDeclaredMethod("localIndexOverflow",
                        com.oracle.truffle.api.bytecode.BytecodeEncodingException.class);
                check.setAccessible(true);
                var local = assertThrows(com.oracle.truffle.api.bytecode.BytecodeEncodingException.class,
                        () -> BytecodeRootGen.create(language, com.oracle.truffle.api.bytecode.BytecodeConfig.DEFAULT, b -> {
                            b.beginRoot();
                            for (int i = 0; i <= 65536; i++) { b.beginBlock(); b.createLocal(null, null); b.endBlock(); }
                            b.beginReturn(); b.emitLoadConstant(Unit.INSTANCE); b.endReturn(); b.endRoot();
                        }));
                assertEquals(true, check.invoke(null, local));
                var trace = local.getStackTrace();
                assertEquals("create", trace[0].getMethodName());
                // Native Image omits the exception factory, retaining the exact
                // allocator path observed in a genuine GHC local-ID overflow.
                local.setStackTrace(java.util.Arrays.copyOfRange(trace, 1, trace.length));
                assertEquals(true, check.invoke(null, local));
                local.setStackTrace(java.util.Arrays.copyOfRange(trace, 1, 3));
                assertEquals(false, check.invoke(null, local), "incomplete allocation path");
                local.setStackTrace(new StackTraceElement[0]);
                assertEquals(false, check.invoke(null, local), "missing allocation path");
                var unknownPrefix = trace.clone();
                unknownPrefix[0] = new StackTraceElement("unknown.Factory", "create", null, -1);
                local.setStackTrace(unknownPrefix);
                assertEquals(false, check.invoke(null, local), "unknown prefix before allocator");
                var argument = assertThrows(com.oracle.truffle.api.bytecode.BytecodeEncodingException.class,
                        () -> BytecodeRootGen.create(language, com.oracle.truffle.api.bytecode.BytecodeConfig.DEFAULT, b -> {
                            b.beginRoot(); b.beginReturn(); b.emitLoadArgument(65536); b.endReturn(); b.endRoot();
                        }));
                assertEquals(false, check.invoke(null, argument));
                argument.setStackTrace(java.util.Arrays.copyOfRange(argument.getStackTrace(), 1, argument.getStackTrace().length));
                assertEquals(false, check.invoke(null, argument), "argument overflow without factory frame");
                assertEquals(false, check.invoke(null,
                        com.oracle.truffle.api.bytecode.BytecodeEncodingException.create(local.getMessage())));
            } finally { context.leave(); }
        }
    }

    @Test void capacitySideEntrySpillPreservesTheFirstInstalledCallerAndTransaction() throws Exception {
        var input = new LinkedHashMap<>(decision(64,
                List.of(binder("step", CLOSURE, true), binder("prefix", MUTABLE, false)), LONG,
                arm -> capacityCalls(64, arm, variable("value63", LONG), LONG)));
        var bindings = new ArrayList<Map<String, Object>>((List<Map<String, Object>>) input.get("bindings"));
        var binding = new LinkedHashMap<>(bindings.getFirst());
        var lambda = new ArrayList<>((List<Object>) binding.get("expr"));
        lambda.set(2, afterTake("prefix", "prefixValue", (List<Object>) lambda.get(2), LONG));
        binding.put("expr", lambda); bindings.set(0, binding); bindings.add(blockedMVarDependency());
        input.put("bindings", bindings); constructors(input, tupleConstructor("Pair", 2));
        try (var context = context()) {
            context.initialize("thc"); context.enter();
            var owner = Language.currentState(); owner.getThreads().enterCurrent(null, false, true, null);
            var transaction = owner.stm.begin();
            var ambient = new ManagedSTM.Transaction();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var program = new BytecodeProgram(language, input, true);
                var target = program.entryTarget("entry"); var root = (BytecodeRoot) target.getRootNode();
                assertEquals(0, root.prepareGraphBudgetRetry(0));
                var original = instructions(root);
                assertTrue(compile(target)); assertTrue(valid(target)); bypass(target);
                assertEquals(0, entries(program));
                var calls = new java.util.concurrent.atomic.AtomicInteger();
                var step = new Closure(null, 1, new RootNode(language) {
                    @Override public Object execute(VirtualFrame frame) {
                        assertSame(transaction, owner.stm.currentTransaction());
                        assertEquals(MaskingState.MASKED_INTERRUPTIBLE, SynchronousMasking.current(this));
                        calls.incrementAndGet(); return (Long) frame.getArguments()[1] + 1;
                    }
                }.getCallTarget());
                var prefix = new ManagedMVar(); assertTrue(prefix.tryPut(new Object()));
                owner.getMaskingState().set(MaskingState.MASKED_INTERRUPTIBLE);
                var stack = owner.getThreadPollState().get().getAstStack();
                long spills = stack.getSpills();
                // The caller enters below the limit; the extracted side alone
                // reaches it. This changes depth, not compilation/profile state.
                stack.setDepth(AstStackScope.MAX_DEPTH - 2); stack.setDriving(true);
                SavedGuestContinuation saved;
                try {
                    saved = SavedGuestContinuations.savedGuestContinuation(Calls.target(target,
                            new Object[]{0L, program.entryValue("chosen"), step, prefix}));
                } finally { stack.setDepth(0); stack.setDriving(false); }
                assertNotNull(saved); assertTrue(saved.stackSpill()); assertNull(saved.asyncRequest());
                assertSame(root, saved.getSourceRoot());
                assertEquals(spills + 1, stack.getSpills());
                assertTrue(prefix.isEmpty()); assertEquals(0, calls.get());
                assertEquals(1, entries(program));
                var parked = assertOnlyFirstEntryQuickening(original, instructions(root));
                root.getRootNodes().ensureSourceInformation();
                assertEquals(parked, instructions(root), "source replay must preserve parked PCs and operands");
                var cloneMethod = root.getClass().getDeclaredMethod("cloneUninitialized"); cloneMethod.setAccessible(true);
                var clone = (BytecodeRoot) cloneMethod.invoke(root);
                assertUninitializedClone(original, parked, instructions(clone));
                assertEquals(0, clone.getGraphBudgetGeneration());
                assertEquals(1, entries(program));
                owner.stm.restore(ambient);
                var resume = new RootNode(language) {
                    @Child private Force force = new Force(new Metrics(false), true);
                    @Override public Object execute(VirtualFrame frame) {
                        return force.drainStack((SavedGuestContinuation) frame.getArguments()[0]);
                    }
                }.getCallTarget();
                assertEquals(63064L, Calls.target(resume, new Object[]{saved}));
                assertEquals(64, calls.get()); assertTrue(prefix.isEmpty());
                assertSame(ambient, owner.stm.currentTransaction());
                assertTrue(transaction.active()); assertTrue(ambient.active());
                assertEquals(0, stack.getDepth()); assertFalse(stack.getDriving());
                assertSame(target, program.entryTarget("entry"));
                assertEquals(1, entries(program));
                // First interpreter resumption specializes ChildResume and quickens
                // its dead-result pop. These are adaptive bytes, not moved guest PCs.
                // Assert exactly those two changes; do not normalize other operands.
                var resumedInstructions = new LinkedHashMap<>(parked);
                var resumeSites = parked.entrySet().stream()
                        .filter(entry -> entry.getValue().equals("c.ResumeApplication[state_0(0)]")).toList();
                assertEquals(1, resumeSites.size());
                int resumeBci = resumeSites.getFirst().getKey();
                int popBci = parked.entrySet().stream()
                        .filter(entry -> entry.getKey() > resumeBci && entry.getValue().equals("pop[child0(ffffffff)]"))
                        .mapToInt(Map.Entry::getKey).findFirst().orElseThrow();
                resumedInstructions.put(resumeBci, "c.ResumeApplication[state_0(2)]");
                resumedInstructions.put(popBci, "pop$generic[child0(ffffffff)]");
                assertEquals(resumedInstructions, instructions(root));
                assertEquals(0, root.getGraphBudgetGeneration());
                var handoff = language.getHandoffState().get(); assertNull(handoff.getPending());
                assertEquals(0, handoff.getArguments().getDepth()); assertEquals(0, handoff.getResults().getDepth());
                assertEquals(0, handoff.getArguments().retainedReferences()); assertEquals(0, handoff.getResults().retainedReferences());
            } finally {
                owner.stm.retire(transaction); owner.stm.retire(ambient); owner.stm.restore(null);
                owner.getThreads().leaveCurrent(); context.leave();
            }
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void capacityRecoveryExtractsBothSiblingRegionsBeforePublication(boolean second) throws Exception {
        var input = new LinkedHashMap<>(decision(64, List.of(binder("step", CLOSURE, true), binder("y", DATA, true)), LONG,
                arm -> capacityCalls(64, arm, variable("value63", LONG), LONG)));
        var bindings = new ArrayList<Map<String, Object>>((List<Map<String, Object>>) input.get("bindings"));
        var entry = new LinkedHashMap<>(bindings.getFirst());
        var lambda = new ArrayList<>((List<Object>) entry.get("expr"));
        var first = new ArrayList<>((List<Object>) lambda.get(2));
        var later = new ArrayList<>(first);
        later.set(1, variable("y", DATA));
        later.set(2, "later"); later.set(4, Map.of("rep", LONG, "binder", binder("later", DATA, true)));
        var branches = node("app", node("prim", "+#"), List.of(first, later),
                List.of(false, false), false, false, Map.of("rep", LONG));
        // Genuine return work remains in the original root after the side call.
        lambda.set(2, node("app", node("prim", "+#"), List.of(branches, number(99)),
                List.of(false, false), false, false, Map.of("rep", LONG)));
        entry.put("expr", lambda); bindings.set(0, entry); input.put("bindings", bindings);
        try (var context = context()) {
            context.initialize("thc"); context.enter();
            var threads = Language.currentState().getThreads(); threads.enterCurrent();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var program = new BytecodeProgram(language, input, true);
                var target = program.entryTarget("entry"); var root = (BytecodeRoot) target.getRootNode();
                assertEquals(0, root.prepareGraphBudgetRetry(0));
                var original = instructions(root);
                assertTrue(original.values().stream().noneMatch(value -> value.contains("InlineCaseRegions")));
                assertEquals(0, entries(program));
                assertTrue(compile(target)); bypass(target); assertTrue(valid(target));
                var calls = new java.util.concurrent.atomic.AtomicInteger();
                var step = new Closure(null, 1, new RootNode(language) {
                    @Override public Object execute(VirtualFrame frame) {
                        int call = calls.getAndIncrement();
                        int arm = (call < 64) != second ? 63 : 17;
                        assertEquals(arm * 1000L + call % 64, frame.getArguments()[1]);
                        return (Long) frame.getArguments()[1] + 1;
                    }
                }.getCallTarget());
                var high = program.entryValue("chosen"); var low = program.constructorLayout("C17").allocate();
                assertEquals(80227L, Calls.target(target, new Object[]{0L, second ? low : high, step, second ? high : low}));
                assertEquals(128, calls.get()); assertEquals(1, entries(program));
                assertSame(target, program.entryTarget("entry"));
                var executed = assertOnlyFirstEntryQuickening(original, instructions(root));
                root.getRootNodes().ensureSourceInformation(); assertEquals(executed, instructions(root));
                var cloneMethod = root.getClass().getDeclaredMethod("cloneUninitialized"); cloneMethod.setAccessible(true);
                var clone = (BytecodeRoot) cloneMethod.invoke(root);
                assertUninitializedClone(original, executed, instructions(clone));
                assertEquals(0, clone.getGraphBudgetGeneration());
                var handoff = language.getHandoffState().get(); assertNull(handoff.getPending());
                assertEquals(0, handoff.getArguments().getDepth()); assertEquals(0, handoff.getResults().getDepth());
                assertEquals(0, handoff.getArguments().retainedReferences()); assertEquals(0, handoff.getResults().retainedReferences());
            } finally { threads.leaveCurrent(); context.leave(); }
        }
    }

    private static Map<String, Object> siblingCapacityInput(int... repetitions) {
        var regions = new ArrayList<List<Object>>();
        Map<String, Object> input = null;
        List<Object> lambda = null;
        for (int region = 0; region < repetitions.length; region++) {
            int count = repetitions[region];
            input = new LinkedHashMap<>(decision(64, List.of(binder("step", CLOSURE, true)), LONG,
                    arm -> count == 0 ? number(arm) : capacityCalls(count, arm, variable("value" + (count - 1), LONG), LONG)));
            var entry = ((List<Map<String, Object>>) input.get("bindings")).getFirst();
            lambda = new ArrayList<>((List<Object>) entry.get("expr"));
            var part = new ArrayList<>((List<Object>) lambda.get(2));
            String id = "region" + region;
            part.set(2, id); part.set(4, Map.of("rep", LONG, "binder", binder(id, DATA, true)));
            regions.add(part);
        }
        // A constructor selector around these cases would eagerly outline its
        // arms, giving each region a separate owner and bypassing the per-root cap.
        List<Object> sum = number(0);
        for (var region : regions)
            sum = node("app", node("prim", "+#"), List.of(sum, region),
                    List.of(false, false), false, false, Map.of("rep", LONG));
        lambda.set(2, sum);
        var bindings = new ArrayList<Map<String, Object>>((List<Map<String, Object>>) input.get("bindings"));
        var entry = new LinkedHashMap<>(bindings.getFirst()); entry.put("expr", lambda); bindings.set(0, entry);
        input.put("bindings", bindings); return input;
    }

    @Test void aLaterSmallRegionCannotRestoreAnUnencodableInlineCopy() throws Exception {
        try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var program = new BytecodeProgram(language, siblingCapacityInput(64, 0), true);
                var root = (BytecodeRoot) program.entryTarget("entry").getRootNode();
                assertEquals(0, root.prepareGraphBudgetRetry(0));
                assertTrue(instructions(root).values().stream().noneMatch(value -> value.contains("InlineCaseRegions")));
                assertEquals(0, entries(program));
            } finally { context.leave(); }
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void theFiniteRegionCapDoesNotRetryAnUncoveredCapacityOverflow(boolean overflow) throws Exception {
        int[] calls = new int[BytecodeCaseRegion.MAX_REGIONS + 1];
        calls[BytecodeCaseRegion.MAX_REGIONS] = overflow ? 64 : 0;
        try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                if (overflow) {
                    var failure = assertThrows(com.oracle.truffle.api.bytecode.BytecodeEncodingException.class,
                            () -> new BytecodeProgram(language, siblingCapacityInput(calls), true));
                    var check = BytecodeProgram.class.getDeclaredMethod("localIndexOverflow",
                            com.oracle.truffle.api.bytecode.BytecodeEncodingException.class);
                    check.setAccessible(true); assertEquals(true, check.invoke(null, failure));
                } else {
                    var program = new BytecodeProgram(language, siblingCapacityInput(calls), true);
                    var target = program.entryTarget("entry"); var root = (BytecodeRoot) target.getRootNode();
                    var regions = BytecodeRoot.class.getDeclaredField("caseRegions"); regions.setAccessible(true);
                    assertEquals(BytecodeCaseRegion.MAX_REGIONS, ((BytecodeCaseRegion[]) regions.get(root)).length);
                    var original = instructions(root);
                    assertEquals(0, root.getGraphBudgetGeneration());
                    assertEquals(1, root.prepareGraphBudgetRetry(0)); // Explicit plan control, not a compiler bailout.
                    assertEquals(1, root.prepareGraphBudgetRetry(0)); assertEquals(1, root.prepareGraphBudgetRetry(1));
                    root.getRootNodes().ensureSourceInformation(); assertEquals(original, instructions(root));
                    var cloneMethod = root.getClass().getDeclaredMethod("cloneUninitialized"); cloneMethod.setAccessible(true);
                    var clone = (BytecodeRoot) cloneMethod.invoke(root);
                    assertEquals(original, instructions(clone)); assertEquals(1, clone.getGraphBudgetGeneration());
                    assertSame(target, program.entryTarget("entry")); assertEquals(0, entries(program));
                }
            } finally { context.leave(); }
        }
    }

    @Test void failedNonrecursiveSideKeepsNestedCaseCapacityRecovery() throws Exception {
        var input = new LinkedHashMap<>(decision(64, List.of(binder("step", CLOSURE, true)), LONG,
                arm -> capacityCalls(64, arm, variable("value63", LONG), LONG)));
        var bindings = new ArrayList<Map<String, Object>>((List<Map<String, Object>>) input.get("bindings"));
        var binding = new LinkedHashMap<>(bindings.getFirst());
        var lambda = new ArrayList<>((List<Object>) binding.get("expr"));
        var worker = localJoin("worker", List.of(), (List<Object>) lambda.get(2), LONG);
        lambda.set(2, node("let", false, List.of(worker), joinedCall("worker", List.of(), LONG), Map.of("rep", LONG)));
        binding.put("expr", lambda); bindings.set(0, binding); input.put("bindings", bindings);
        try (var context = context()) {
            context.initialize("thc"); context.enter();
            var threads = Language.currentState().getThreads(); threads.enterCurrent();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var program = new BytecodeProgram(language, input, true);
                var target = program.entryTarget("entry"); var root = (BytecodeRoot) target.getRootNode();
                assertEquals(1L, ((Number) program.diagnostics().get("localJoinCount")).longValue());
                assertEquals(0, root.prepareGraphBudgetRetry(0), "capacity selection happens before publication");
                var original = instructions(root);
                root.getRootNodes().ensureSourceInformation(); assertEquals(original, instructions(root));
                var cloneMethod = root.getClass().getDeclaredMethod("cloneUninitialized"); cloneMethod.setAccessible(true);
                var clone = (BytecodeRoot) cloneMethod.invoke(root);
                assertEquals(original, instructions(clone));
                assertEquals(0, clone.getGraphBudgetGeneration());
                assertTrue(compile(target)); assertTrue(valid(target)); bypass(target);
                assertEquals(0, entries(program));
                var calls = new java.util.concurrent.atomic.AtomicInteger();
                var step = new Closure(null, 1, new RootNode(language) {
                    @Override public Object execute(VirtualFrame frame) {
                        assertEquals(63000L + calls.getAndIncrement(), frame.getArguments()[1]);
                        return (Long) frame.getArguments()[1] + 1;
                    }
                }.getCallTarget());
                assertEquals(63064L, Calls.target(target, new Object[]{0L, program.entryValue("chosen"), step}));
                assertEquals(64, calls.get()); assertEquals(1, entries(program));
                assertSame(target, program.entryTarget("entry"));
                var executed = instructions(root);
                var stores = new LinkedHashMap<Integer, String>();
                // The original NonRec join-result accesses quicken to their Long
                // specialization; its local index, offset and operand PC stay exact.
                executed.forEach((pc, instruction) -> stores.put(pc, instruction
                        .replace("store.local$Long[", "store.local[").replace("load.local$Long[", "load.local[")));
                assertOnlyFirstEntryQuickening(original, stores);
                root.getRootNodes().ensureSourceInformation(); assertEquals(executed, instructions(root));
            } finally { threads.leaveCurrent(); context.leave(); }
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void localEncodingCapacityNarrowsSidesBeforePublishingTheOriginalTarget(boolean parentOnly) throws Exception {
        // The pinned emitter limits local IDs to 65535. Sixteen scalar calls
        // in each of 256 arms cross that capacity while each 32-arm side fits.
        int arms = parentOnly ? 256 : 64, repetitions = parentOnly ? 16 : 64;
        var input = decision(arms, List.of(binder("step", CLOSURE, true)), LONG,
                arm -> capacityCalls(repetitions, arm, variable("value" + (repetitions - 1), LONG), LONG));
        try (var context = context()) {
            context.initialize("thc"); context.enter();
            var threads = Language.currentState().getThreads(); threads.enterCurrent();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var program = new BytecodeProgram(language, input, true);
                var closure = (Closure) program.entryValue("entry");
                var target = closure.target; var root = (BytecodeRoot) target.getRootNode();
                assertSame(closure, program.entryValue("entry"));
                assertEquals(0, entries(program));
                assertEquals(0, root.prepareGraphBudgetRetry(0), "capacity-only extraction is not a graph bailout");
                assertTrue(instructions(root).values().stream().noneMatch(value -> value.contains("InlineCaseRegions")),
                        "the unencodable inline body must not be emitted");
                var beforeInstructions = instructions(root);
                assertTrue(compile(target)); assertTrue(valid(target)); bypass(target);
                var calls = new java.util.concurrent.atomic.AtomicInteger();
                var step = new Closure(null, 1, new RootNode(language) {
                    @Override public Object execute(VirtualFrame frame) {
                        calls.incrementAndGet(); return (Long) frame.getArguments()[1] + 1;
                    }
                }.getCallTarget());
                assertEquals((arms - 1) * 1000L + repetitions,
                        Calls.target(target, new Object[]{0L, program.entryValue("chosen"), step}));
                assertEquals(repetitions, calls.get(), "no preparation, retry or branch replay may execute a call");
                assertEquals(1, entries(program));
                assertSame(target, ((Closure) program.entryValue("entry")).target);
                var executed = assertOnlyFirstEntryQuickening(beforeInstructions, instructions(root));
                root.getRootNodes().ensureSourceInformation();
                assertEquals(executed, instructions(root));
                assertEquals(0, root.getGraphBudgetGeneration());
                var cloneMethod = root.getClass().getDeclaredMethod("cloneUninitialized"); cloneMethod.setAccessible(true);
                var clone = (BytecodeRoot) cloneMethod.invoke(root);
                assertUninitializedClone(beforeInstructions, executed, instructions(clone));
                assertEquals(0, clone.getGraphBudgetGeneration());
                var state = language.getHandoffState().get();
                assertNull(state.getPending()); assertEquals(0, state.getArguments().getDepth());
                assertEquals(0, state.getResults().getDepth());
            } finally { threads.leaveCurrent(); context.leave(); }
        }
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
                assertTrue(compile(target)); bypass(target); assertTrue(valid(target));
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
                assertEquals(before + 1, entries(program));
                var state = language.getHandoffState().get();
                assertNull(state.getPending()); assertEquals(0, state.getArguments().getDepth()); assertEquals(0, state.getResults().getDepth());
            } finally { context.leave(); }
        }
    }
    @ParameterizedTest @ValueSource(ints = {0, 2, 3})
    void complexConstructorArmsUseTheirRealFirstCompiledSide(int chosen) throws Exception {
        var resultProof = tuple(REFERENCE, LONG);
        var result = node("app", node("con", "Result", 2),
                List.of(variable("payload", REFERENCE), variable("value2", LONG)),
                List.of(true, true), false, false, Map.of("rep", resultProof));
        var input = new LinkedHashMap<>(decision(3,
                List.of(binder("step", CLOSURE, true), binder("payload", REFERENCE, true)), resultProof,
                arm -> capacityCalls(3, arm, result, resultProof)));
        constructors(input, tupleConstructor("Result", 2),
                Map.of("id", "C3", "name", "C3", "arity", 0, "tag", 4, "kind", "boxed",
                        "strictFields", List.of(), "fieldLifted", List.of(), "fieldReps", List.of()));
        var bindings = (List<Map<String, Object>>) input.get("bindings");
        var lambda = (List<Object>) bindings.getFirst().get("expr");
        var alternatives = (List<List<Object>>) ((List<Object>) lambda.get(2)).get(3);
        alternatives.add(node("default", null, List.of(), node("app", node("con", "Result", 2),
                List.of(variable("payload", REFERENCE), number(-1)), List.of(true, true), false, false,
                Map.of("rep", resultProof))));
        try (var context = context()) {
            context.initialize("thc"); context.enter();
            var threads = Language.currentState().getThreads(); threads.enterCurrent();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var program = new BytecodeProgram(language, input, true);
                var target = program.entryTarget("entry"); var root = (BytecodeRoot) target.getRootNode();
                var original = instructions(root); var sides = regionSides(root);
                assertEquals(3, sides.size()); assertEquals(0, root.prepareGraphBudgetRetry(0));
                assertTrue(original.values().stream().noneMatch(value -> value.contains("InlineCaseRegions")));
                for (var side : sides) { assertTrue(compile(side)); assertTrue(valid(side)); bypass(side); }
                assertTrue(compile(target)); assertTrue(valid(target)); bypass(target);
                for (var side : sides) assertTrue(valid(side), "Caller compilation invalidated a prepared side");
                assertEquals(0, entries(program));
                var calls = new java.util.concurrent.atomic.AtomicInteger();
                var step = new Closure(null, 1, new RootNode(language) {
                    @Override public Object execute(VirtualFrame frame) {
                        assertEquals(chosen * 1000L + calls.getAndIncrement(), frame.getArguments()[1]);
                        return (Long) frame.getArguments()[1] + 1;
                    }
                }.getCallTarget());
                var payload = new Thunk(new RootNode(language) {
                    @Override public Object execute(VirtualFrame frame) { throw new AssertionError("Lazy case payload forced"); }
                }.getCallTarget(), null);
                var value = TupleResults.ownedTupleResult(Calls.target(target,
                        new Object[]{0L, program.constructorLayout("C" + chosen).allocate(), step, payload}), root.getTupleResult());
                var layout = root.getTupleResult().getLayout();
                assertSame(payload, layout.getObject(value, 0)); assertEquals(0, payload.getState());
                assertEquals(chosen == 3 ? -1 : chosen * 1000L + 3, layout.getLong(value, 1));
                assertEquals(chosen == 3 ? 0 : 3, calls.get());
                assertEquals(2, entries(program));
                assertSame(target, program.entryTarget("entry"));
                var executed = instructions(root); root.getRootNodes().ensureSourceInformation();
                assertEquals(executed, instructions(root));
                var cloneMethod = root.getClass().getDeclaredMethod("cloneUninitialized"); cloneMethod.setAccessible(true);
                var clone = (BytecodeRoot) cloneMethod.invoke(root);
                assertEquals(original.keySet(), instructions(clone).keySet()); assertEquals(0, clone.getGraphBudgetGeneration());
                var handoff = language.getHandoffState().get(); assertNull(handoff.getPending());
                assertEquals(0, handoff.getArguments().getDepth()); assertEquals(0, handoff.getResults().getDepth());
                assertEquals(0, handoff.getArguments().retainedReferences()); assertEquals(0, handoff.getResults().retainedReferences());
            } finally { threads.leaveCurrent(); context.leave(); }
        }
    }

    private static Context context() { return context(100000); }
    private static Context context(int graphLimit) {
        return Context.newBuilder("thc").allowExperimentalOptions(true)
                .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
                .option("engine.SingleTierCompilationThreshold", "10000000")
                .option("engine.CompilationFailureAction", "Throw")
                .option("compiler.DiagnoseFailure", "false")
                .option("compiler.MaximumGraalGraphSize", Integer.toString(graphLimit))
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

    /** Stock first-entry specialization may change these adaptive bytes, never PCs or operands. */
    private static Map<Integer, String> assertOnlyFirstEntryQuickening(
            Map<Integer, String> before, Map<Integer, String> after) {
        assertEquals(before.keySet(), after.keySet());
        before.forEach((bci, old) -> {
            if (old.equals(after.get(bci))) return;
            int operands = old.indexOf('[');
            String name = old.substring(0, operands);
            String expected = switch (name) {
                case "branch.false", "load.constant" -> name + "$Boolean" + old.substring(operands);
                case "c.StackLimit", "c.PollAsync", "c.MatchData" -> name + "$unboxed" + old.substring(operands);
                case "c.ForceLocal" -> old.replace("state_0(0)", "state_0(8)");
                case "branch.backward" -> old.replaceAll(
                        "loop_header_branch_profile\\((\\d+):never executed\\)", "loop_header_branch_profile($1:1.00)");
                default -> old;
            };
            assertEquals(expected, after.get(bci), "unexpected instruction change at " + bci);
        });
        return after;
    }

    private static void assertUninitializedClone(Map<Integer, String> original,
            Map<Integer, String> executed, Map<Integer, String> clone) {
        var expected = new LinkedHashMap<>(original);
        // cloneUninitialized resets instruction quickening and branch profiles,
        // while the precreated ForceLocal child's specialization state is copied.
        original.forEach((bci, value) -> {
            if (value.startsWith("c.ForceLocal[")) expected.put(bci, executed.get(bci));
        });
        assertEquals(expected, clone);
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

    private static List<Object> joinedCall(String name, List<List<Object>> arguments, Map<String, Object> result) {
        return node("app", variable(name, CLOSURE), arguments,
                java.util.Collections.nCopies(arguments.size(), false), false, false, Map.of("rep", result));
    }

    private static Map<String, Object> localJoin(String name, List<Map<String, Object>> parameters,
            List<Object> body, Map<String, Object> result) {
        var binding = new LinkedHashMap<>(binder(name, CLOSURE, true));
        binding.put("joinValueArity", parameters.size()); binding.put("joinResultRep", result);
        binding.put("expr", parameters.isEmpty() ? body : node("lam", parameters, body, Map.of("resultRep", result)));
        return binding;
    }

    private static List<Object> regionChainSum(String prefix, int depth) {
        List<Object> body = number(0);
        for (int i = depth - 1; i >= 0; i--) {
            String value = prefix + "Value" + i, next = prefix + "Next" + i;
            body = node("app", node("prim", "+#"), List.of(variable(value, LONG), body),
                    List.of(false, false), false, false, Map.of("rep", LONG));
            body = node("case", variable(i == 0 ? "list" : prefix + "Next" + (i - 1), DATA), prefix + "Link" + i,
                    List.of(node("data", "Link", List.of(value, next), body,
                            Map.of("binders", List.of(binder(value, LONG, false), binder(next, DATA, true))))),
                    Map.of("rep", LONG, "binder", binder(prefix + "Link" + i, DATA, true)));
        }
        return body;
    }

    private static List<Object> joinBodySum() {
        List<Object> value = variable("argument", LONG);
        for (int i = 0; i < 32; i++)
            value = node("app", node("prim", "+#"), List.of(value, variable("bias", LONG)),
                    List.of(false, false), false, false, Map.of("rep", LONG));
        return value;
    }

    private static Map<String, Object> closedNonrecursiveBody(boolean outerExit) {
        var result = outerExit ? LONG : tuple(STATE, REFERENCE, LONG);
        var answer = outerExit ? joinedCall("finish", List.of(joinBodySum()), LONG)
                : node("app", node("con", "RegionResult", 3),
                    List.of(node("void", Map.of("rep", STATE)), variable("payload", REFERENCE), joinBodySum()),
                    List.of(false, true, false), false, false, Map.of("rep", result));
        var worker = localJoin("worker", List.of(binder("argument", LONG, false)), answer, result);
        List<Object> body = node("let", false, List.of(worker),
                joinedCall("worker", List.of(variable("seed", LONG)), result), Map.of("rep", result));
        if (outerExit) body = node("let", false,
                List.of(localJoin("finish", List.of(binder("value", LONG, false)), variable("value", LONG), LONG)),
                body, Map.of("rep", LONG));
        return Map.of("constructors", List.of(tupleConstructor("RegionResult", 3)), "bindings", List.of(
                Map.of("id", "entry", "name", "entry", "lifted", true, "rep", CLOSURE,
                    "expr", node("lam", List.of(binder("seed", LONG, false), binder("bias", LONG, false),
                            binder("payload", REFERENCE, true)), body,
                        Map.of("rep", CLOSURE, "resultRep", result, "entryStrict", List.of(false, false, false))))));
    }

    private static List<RootCallTarget> regionSides(BytecodeRoot root) throws Exception {
        var field = BytecodeRoot.class.getDeclaredField("caseRegions"); field.setAccessible(true);
        var sidesField = BytecodeCaseRegion.class.getDeclaredField("sides"); sidesField.setAccessible(true);
        var result = new ArrayList<RootCallTarget>();
        for (var region : (BytecodeCaseRegion[]) field.get(root)) for (var side : (Object[]) sidesField.get(region)) {
            var target = side.getClass().getDeclaredField("target"); target.setAccessible(true);
            result.add((RootCallTarget) target.get(side));
        }
        return result;
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void closedNonrecursiveBodyPreservesColdTypedCapturesAndSourceReplay(boolean recovered) throws Exception {
        try (var context = context()) {
            context.initialize("thc"); context.enter();
            var threads = Language.currentState().getThreads(); threads.enterCurrent();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var program = new BytecodeProgram(language, closedNonrecursiveBody(false), true);
                var target = program.entryTarget("entry"); var root = (BytecodeRoot) target.getRootNode();
                var sides = regionSides(root);
                var original = instructions(root);
                if (recovered) assertEquals(1, root.prepareGraphBudgetRetry(0), "explicit transport control, not a bailout");
                assertTrue(compile(target)); assertTrue(valid(target)); bypass(target);
                assertTrue(compile(sides.getFirst())); assertTrue(valid(sides.getFirst())); bypass(sides.getFirst());
                assertEquals(com.oracle.truffle.api.bytecode.BytecodeTier.CACHED, root.getBytecodeNode().getTier());
                assertEquals(com.oracle.truffle.api.bytecode.BytecodeTier.CACHED,
                        ((BytecodeRoot) sides.getFirst().getRootNode()).getBytecodeNode().getTier());
                assertEquals(0, entries(program));
                var payload = new Thunk(new RootNode(language) {
                    @Override public Object execute(VirtualFrame frame) { throw new AssertionError("Lazy capture was forced"); }
                }.getCallTarget(), null);
                var value = TupleResults.ownedTupleResult(Calls.target(target, new Object[]{0L, 5L, 3L, payload}), root.getTupleResult());
                assertSame(payload, root.getTupleResult().getLayout().getObject(value, 0));
                assertEquals(101L, root.getTupleResult().getLayout().getLong(value, 1));
                assertEquals(0, payload.getState()); assertEquals(recovered ? 2 : 1, entries(program));
                assertSame(target, program.entryTarget("entry")); assertEquals(original.keySet(), instructions(root).keySet());
                var executed = instructions(root); root.getRootNodes().ensureSourceInformation();
                assertEquals(executed, instructions(root));
                var cloneMethod = root.getClass().getDeclaredMethod("cloneUninitialized"); cloneMethod.setAccessible(true);
                var clone = (BytecodeRoot) cloneMethod.invoke(root);
                assertEquals(original.keySet(), instructions(clone).keySet());
                assertEquals(recovered ? 1 : 0, clone.getGraphBudgetGeneration());
                assertEquals(recovered ? 1 : 0, root.prepareGraphBudgetRetry(1), "a mismatched generation cannot select a side");
            } finally { threads.leaveCurrent(); context.leave(); }
        }
    }

    @Test void nonrecursiveBodyWithAnAmbientJoinExitStaysInItsActivation() throws Exception {
        try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var program = new BytecodeProgram(language, closedNonrecursiveBody(true));
                var target = program.entryTarget("entry"); var root = (BytecodeRoot) target.getRootNode();
                assertEquals(0, root.prepareGraphBudgetRetry(0));
                assertEquals(101L, Calls.target(target, new Object[]{0L, 5L, 3L, new Object()}));
                assertEquals(2L, ((Number) program.diagnostics().get("localJoinTransfers")).longValue());
            } finally { context.leave(); }
        }
    }

    @Test void closedNonrecursiveBodySpillKeepsStrictArgumentsAndTheOriginalFrame() throws Exception {
        var result = tuple(STATE, REFERENCE, LONG);
        var answer = node("app", node("con", "RegionResult", 3),
                List.of(node("void", Map.of("rep", STATE)), variable("value", REFERENCE), joinBodySum()),
                List.of(false, true, false), false, false, Map.of("rep", result));
        var inner = localJoin("inner", List.of(), answer, result);
        var body = node("let", false, List.of(inner), joinedCall("inner", List.of(), result), Map.of("rep", result));
        var worker = localJoin("worker", List.of(binder("argument", LONG, false), binder("value", REFERENCE, true)), body, result);
        worker.put("expr", node("lam", List.of(binder("argument", LONG, false), binder("value", REFERENCE, true)), body,
                Map.of("resultRep", result, "entryStrict", List.of(false, true))));
        var entry = node("let", false, List.of(worker),
                joinedCall("worker", List.of(variable("seed", LONG), variable("payload", REFERENCE)), result), Map.of("rep", result));
        var input = Map.<String, Object>of("constructors", List.of(tupleConstructor("RegionResult", 3), tupleConstructor("Pair", 2)),
                "bindings", List.of(Map.of("id", "entry", "name", "entry", "lifted", true, "rep", CLOSURE,
                    "expr", node("lam", List.of(binder("seed", LONG, false), binder("bias", LONG, false),
                            binder("payload", REFERENCE, true), binder("prefix", MUTABLE, false)),
                        afterTake("prefix", "consumed", entry, result),
                        Map.of("resultRep", result, "entryStrict", List.of(false, false, false, false)))),
                    blockedMVarDependency()));
        try (var context = context()) {
            context.initialize("thc"); context.enter();
            var owner = Language.currentState(); owner.getThreads().enterCurrent(null, false, true, null);
            var transaction = owner.stm.begin(); var ambient = new ManagedSTM.Transaction();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var program = new BytecodeProgram(language, input, true);
                var target = program.entryTarget("entry"); var root = (BytecodeRoot) target.getRootNode();
                assertEquals(1, root.prepareGraphBudgetRetry(0)); // Transport control, not a compiler bailout.
                assertTrue(compile(target)); assertTrue(valid(target)); bypass(target);
                assertEquals(0, entries(program));
                var forces = new java.util.concurrent.atomic.AtomicInteger(); var marker = new Object();
                var payload = new Thunk(new RootNode(language) {
                    @Override public Object execute(VirtualFrame frame) {
                        assertSame(transaction, owner.stm.currentTransaction());
                        assertEquals(MaskingState.MASKED_INTERRUPTIBLE, SynchronousMasking.current(this));
                        forces.incrementAndGet(); return marker;
                    }
                }.getCallTarget(), null);
                var prefix = new ManagedMVar(); assertTrue(prefix.tryPut(new Object()));
                owner.getMaskingState().set(MaskingState.MASKED_INTERRUPTIBLE);
                var stack = owner.getThreadPollState().get().getAstStack();
                stack.setDepth(AstStackScope.MAX_DEPTH - 2); stack.setDriving(true);
                SavedGuestContinuation saved;
                try { saved = SavedGuestContinuations.savedGuestContinuation(
                        Calls.target(target, new Object[]{0L, 5L, 3L, payload, prefix})); }
                finally { stack.setDepth(0); stack.setDriving(false); }
                assertNotNull(saved); assertTrue(saved.stackSpill()); assertNull(saved.asyncRequest());
                assertSame(root, saved.getSourceRoot()); assertTrue(prefix.isEmpty()); assertEquals(1, forces.get());
                assertEquals(1, entries(program));
                var identity = saved.getIdentity();
                var continuation = assertInstanceOf(com.oracle.truffle.api.bytecode.ContinuationResult.class, identity);
                var savedFrame = continuation.getFrame(); var location = continuation.getContinuationRootNode().getLocation();
                var parked = instructions(root); root.getRootNodes().ensureSourceInformation();
                assertEquals(parked, instructions(root)); assertSame(savedFrame, continuation.getFrame());
                // Source replay may replace the metadata node, not the owner, frame or saved PC.
                assertSame(root, continuation.getContinuationRootNode().getSourceRootNode());
                assertEquals(location.getBytecodeIndex(), continuation.getContinuationRootNode().getLocation().getBytecodeIndex());
                owner.stm.restore(ambient);
                var resume = new RootNode(language) {
                    @Child private Force force = new Force(new Metrics(false), true);
                    @Override public Object execute(VirtualFrame frame) {
                        return force.drainStack((SavedGuestContinuation) frame.getArguments()[0], root.getTupleResult());
                    }
                }.getCallTarget();
                var value = TupleResults.ownedTupleResult(Calls.target(resume, new Object[]{saved}), root.getTupleResult());
                assertSame(marker, root.getTupleResult().getLayout().getObject(value, 0));
                assertEquals(101L, root.getTupleResult().getLayout().getLong(value, 1));
                assertTrue(prefix.isEmpty()); assertEquals(1, forces.get()); assertSame(identity, saved.getIdentity());
                assertSame(ambient, owner.stm.currentTransaction()); assertTrue(transaction.active());
                assertEquals(MaskingState.MASKED_INTERRUPTIBLE, owner.getMaskingState().get());
                assertEquals(0, stack.getDepth()); assertFalse(stack.getDriving());
                assertSame(target, program.entryTarget("entry")); assertEquals(parked.keySet(), instructions(root).keySet());
                var handoff = language.getHandoffState().get(); assertNull(handoff.getPending());
                assertEquals(0, handoff.getArguments().getDepth()); assertEquals(0, handoff.getResults().getDepth());
                assertEquals(0, handoff.getArguments().retainedReferences()); assertEquals(0, handoff.getResults().retainedReferences());
            } finally {
                owner.stm.retire(transaction); owner.stm.retire(ambient); owner.stm.restore(null);
                owner.getThreads().leaveCurrent(); context.leave();
            }
        }
    }

    @Test void closedNonrecursiveBodyKeepsTypedJoinFormals() throws Exception {
        var narrow = Map.<String, Object>of("kind", "long", "primReps", List.of("Int8Rep"), "evaluated", true);
        var floating = Map.<String, Object>of("kind", "float", "primReps", List.of("FloatRep"), "evaluated", true);
        var doubleRep = Map.<String, Object>of("kind", "double", "primReps", List.of("DoubleRep"), "evaluated", true);
        var proof = tuple(STATE, narrow, LONG, floating, doubleRep, REFERENCE);
        var body = node("case", joinBodySum(), "calculated", List.of(node("default", null, List.of(), variable("packet", proof))),
                Map.of("rep", proof, "binder", binder("calculated", LONG, false)));
        var worker = localJoin("worker", List.of(binder("packet", proof, false)), body, proof);
        var input = Map.<String, Object>of("constructors", List.of(), "bindings", List.of(
                Map.of("id", "entry", "name", "entry", "lifted", true, "rep", CLOSURE,
                    "expr", node("lam", List.of(binder("input", proof, false), binder("argument", LONG, false), binder("bias", LONG, false)),
                        node("let", false, List.of(worker), joinedCall("worker", List.of(variable("input", proof)), proof), Map.of("rep", proof)),
                        Map.of("resultRep", proof, "entryStrict", List.of(false, false, false))))));
        try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var program = new BytecodeProgram(language, input, true);
                var target = program.entryTarget("entry"); var root = (BytecodeRoot) target.getRootNode();
                assertEquals(1, root.prepareGraphBudgetRetry(0));
                assertTrue(compile(target)); assertTrue(valid(target)); bypass(target);
                var poison = new Thunk(new RootNode(language) {
                    @Override public Object execute(VirtualFrame frame) { throw new AssertionError("Typed capture forced"); }
                }.getCallTarget(), null);
                var entry = root.getTypedInput(); var packet = entry.getPacket();
                var storage = entry.state().getArguments().acquire(packet); storage.setInputMode(1);
                Object answer;
                try {
                    packet.setLong(storage, 0, 0L); packet.setInt(storage, 1, -127); packet.setLong(storage, 2, 0x7123456789abcdefL);
                    packet.setFloat(storage, 3, -0.0f); packet.setDouble(storage, 4, Double.longBitsToDouble(0x7ff8000000000017L));
                    packet.setObject(storage, 5, poison); packet.setLong(storage, 6, 5L); packet.setLong(storage, 7, 3L);
                    answer = TypedInputs.invokeTypedInput(entry, storage, args -> Calls.target(target, args));
                } finally { entry.releaseChecked(storage); }
                var value = TupleResults.ownedTupleResult(answer, root.getTupleResult()); var layout = root.getTupleResult().getLayout();
                assertEquals(-127, layout.getInt(value, 0)); assertEquals(0x7123456789abcdefL, layout.getLong(value, 1));
                assertEquals(0x80000000, Float.floatToRawIntBits(layout.getFloat(value, 2)));
                assertEquals(0x7ff8000000000017L, Double.doubleToRawLongBits(layout.getDouble(value, 3)));
                assertSame(poison, layout.getObject(value, 4)); assertEquals(0, poison.getState()); assertEquals(1, entries(program));
                var handoff = language.getHandoffState().get(); assertNull(handoff.getPending());
                assertEquals(0, handoff.getArguments().getDepth()); assertEquals(0, handoff.getResults().getDepth());
            } finally { context.leave(); }
        }
    }

    private static List<Object> regionEffect(int stage, List<Object> body, Map<String, Object> result) {
        return node("case", joinedCall("step", List.of(number(stage)), LONG), "effect" + stage,
                List.of(node("default", null, List.of(), body)),
                Map.of("rep", result, "binder", binder("effect" + stage, LONG, false)));
    }

    private static Map<String, Object> closedRecursiveRegion(int depth) {
        return closedRecursiveRegion(depth, false, false);
    }

    private static Map<String, Object> closedRecursiveRegion(int depth, boolean nestedOwner, boolean shadowPayload) {
        var result = tuple(STATE, REFERENCE, LONG);
        var answer = node("app", node("con", "RegionResult", 3),
                List.of(node("void", Map.of("rep", STATE)), variable("payload", REFERENCE), variable("answer", LONG)),
                List.of(false, true, false), false, false, Map.of("rep", result));
        var finish = localJoin("finish", List.of(binder("answer", LONG, false)), regionEffect(2, answer, result), result);
        var next = node("app", node("prim", "-#"), List.of(variable("remaining", LONG), number(1)),
                List.of(false, false), false, false, Map.of("rep", LONG));
        var sum = node("app", node("prim", "+#"), List.of(variable("sum", LONG), regionChainSum("worker", depth)),
                List.of(false, false), false, false, Map.of("rep", LONG));
        var workerBody = node("case", variable("remaining", LONG), "remainingCase", List.of(
                node("lit", List.of("int", "0"), List.of(), joinedCall("finish", List.of(variable("sum", LONG)), result)),
                node("default", null, List.of(), regionEffect(1, joinedCall("worker", List.of(next, sum), result), result))),
                Map.of("rep", result, "binder", binder("remainingCase", LONG, false)));
        if (nestedOwner) {
            var inner = localJoin("inner", List.of(),
                    joinedCall("worker", List.of(number(0), sum), result), result);
            workerBody = node("case", variable("remaining", LONG), "remainingCase", List.of(
                    node("lit", List.of("int", "0"), List.of(), joinedCall("finish", List.of(variable("sum", LONG)), result)),
                    node("default", null, List.of(), node("let", true, List.of(inner),
                            joinedCall("inner", List.of(), result), Map.of("rep", result)))),
                    Map.of("rep", result, "binder", binder("remainingCase", LONG, false)));
        }
        var worker = localJoin("worker", List.of(binder("remaining", LONG, false), binder("sum", LONG, false)), workerBody, result);
        var region = node("let", true, List.of(worker),
                joinedCall("worker", List.of(number(1), variable("prefixSum", LONG)), result), Map.of("rep", result));
        if (shadowPayload) region = node("case", variable("payload", REFERENCE), "payload",
                List.of(node("default", null, List.of(), region)),
                Map.of("rep", result, "binder", binder("payload", REFERENCE, true)));
        var prefix = node("case", regionChainSum("prefix", depth), "prefixSum", List.of(node("default", null, List.of(), region)),
                Map.of("rep", result, "binder", binder("prefixSum", LONG, false)));
        var body = node("let", false, List.of(finish), regionEffect(0, prefix, result), Map.of("rep", result));
        var input = new LinkedHashMap<>(smallDecision(1));
        input.put("bindings", List.of(Map.of("id", "entry", "name", "entry", "lifted", true, "rep", CLOSURE,
                "expr", node("lam", List.of(binder("list", DATA, true), binder("step", CLOSURE, true),
                        binder("payload", REFERENCE, true)), body,
                        Map.of("rep", CLOSURE, "resultRep", result, "entryStrict", List.of(false, false, false))))));
        constructors(input, tupleConstructor("RegionResult", 3));
        return input;
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void recursiveRegionRetainsInlineForReboundCapturesAndActiveOwners(boolean nestedOwner) throws Exception {
        try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var program = new BytecodeProgram(language, closedRecursiveRegion(48, nestedOwner, true));
                var root = (BytecodeRoot) program.entryTarget("entry").getRootNode();
                // The exit owns the original payload local. In the nested variant,
                // the inner candidate also transfers to the still-live Rec owner.
                assertEquals(0, root.prepareGraphBudgetRetry(0));
                var step = new Closure(null, 1, new RootNode(language) {
                    @Override public Object execute(VirtualFrame frame) { return frame.getArguments()[1]; }
                }.getCallTarget());
                var payload = new Object();
                var answer = TupleResults.ownedTupleResult(Calls.target(root.getCallTarget(),
                        new Object[]{0L, chain(program, 48), step, payload}), root.getTupleResult());
                assertSame(payload, root.getTupleResult().getLayout().getObject(answer, 0));
                assertEquals(96L, root.getTupleResult().getLayout().getLong(answer, 1));
            } finally { context.leave(); }
        }
    }

    @Test void explicitClosedRecursivePreparationPreservesPayloadAndStageOrder() throws Exception {
        try (var context = context(10000)) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var program = new BytecodeProgram(language, closedRecursiveRegion(24));
                var target = program.entryTarget("entry"); var root = (BytecodeRoot) target.getRootNode();
                var stages = new ArrayList<Long>();
                var step = new Closure(null, 1, new GuestRoot(language, null) {
                    @Override public long bloom(VirtualFrame frame) { return (Long) frame.getArguments()[0]; }
                    @Override public Object execute(VirtualFrame frame) {
                        long stage = (Long) frame.getArguments()[1]; stages.add(stage); return stage;
                    }
                }.getCallTarget());
                var payload = new Thunk(new RootNode(language) {
                    @Override public Object execute(VirtualFrame frame) { throw new AssertionError("Region capture forced"); }
                }.getCallTarget(), null);
                var list = chain(program, 24);
                java.util.function.Consumer<Object> check = raw -> {
                    var result = TupleResults.ownedTupleResult(raw, root.getTupleResult());
                    assertSame(payload, root.getTupleResult().getLayout().getObject(result, 0));
                    assertEquals(0, payload.getState());
                    assertEquals(48L, root.getTupleResult().getLayout().getLong(result, 1));
                };
                var original = instructions(root);
                assertEquals(0, root.getGraphBudgetGeneration()); assertEquals(0, entries(program));
                // Explicit preparation checks transport, not whether a synthetic graph
                // happens to fit a particular compiler budget.
                assertEquals(1, root.prepareGraphBudgetRetry(0));
                assertEquals(1, root.prepareGraphBudgetRetry(0));
                assertEquals(1, root.prepareGraphBudgetRetry(1));
                assertEquals(1, root.getGraphBudgetGeneration());
                assertEquals(original, instructions(root));
                assertSame(target, program.entryTarget("entry"));
                assertTrue(stages.isEmpty()); assertEquals(0, payload.getState()); assertEquals(0, entries(program));
                check.accept(Calls.target(target, new Object[]{0L, list, step, payload}));
                assertEquals(List.of(0L, 1L, 2L), stages);
                assertEquals(original.keySet(), instructions(root).keySet());
                assertSame(target, program.entryTarget("entry"));
                assertEquals(1, root.prepareGraphBudgetRetry(1), "no unused structural boundary remains");
                assertEquals(List.of(0L, 1L, 2L), stages, "retry preparation must not replay guest effects");
            } finally { context.leave(); }
        }
    }

    private static Object chain(BytecodeProgram program, int depth) {
        Object value = program.constructorLayout("End").allocate();
        var layout = program.constructorLayout("Link");
        for (int i = 0; i < depth; i++) {
            var next = layout.allocate(); layout.initializeLong(next, 0, 1L); layout.initialize(next, 1, value); value = next;
        }
        return value;
    }

    private static Map<String, Object> defaultSuffix(int halfDepth, List<Object> scrutinee) {
        var input = new LinkedHashMap<>(smallDecision(1));
        List<Object> body = variable("selected", LONG);
        for (int i = 2 * halfDepth - 1; i >= 0; i--) {
            String value = "value" + i, next = "next" + i;
            body = node("app", node("prim", "+#"), List.of(variable(value, LONG), body),
                    List.of(false, false), false, false, Map.of("rep", LONG));
            body = node("case", variable(i == 0 ? "list" : "next" + (i - 1), DATA), "link" + i,
                    List.of(node("data", "Link", List.of(value, next), body,
                            Map.of("binders", List.of(binder(value, LONG, false), binder(next, DATA, true))))),
                    Map.of("rep", LONG, "binder", binder("link" + i, DATA, true)));
            if (i == halfDepth) body = node("case", scrutinee, "selected",
                    List.of(node("default", null, List.of(), body)),
                    Map.of("rep", LONG, "binder", binder("selected", LONG, false)));
        }
        var entry = new LinkedHashMap<>(((List<Map<String, Object>>) input.get("bindings")).getFirst());
        var lambda = new ArrayList<>((List<Object>) entry.get("expr"));
        lambda.set(2, body); entry.put("expr", lambda); input.put("bindings", List.of(entry));
        return input;
    }

    private static Map<String, Object> broadDefaultSuffix(int depth) {
        return broadDefaultSuffix(depth, depth);
    }

    private static Map<String, Object> broadDefaultSuffix(int prefixDepth, int suffixDepth) {
        var input = new LinkedHashMap<>(smallDecision(suffixDepth));
        var entry = new LinkedHashMap<>(((List<Map<String, Object>>) input.get("bindings")).getFirst());
        var lambda = new ArrayList<>((List<Object>) entry.get("expr"));
        var alternatives = (List<List<Object>>) ((List<Object>) lambda.get(2)).get(3);
        var part = (List<Object>) alternatives.getFirst().get(3);
        var prefixEntry = ((List<Map<String, Object>>) smallDecision(prefixDepth).get("bindings")).getFirst();
        var prefixLambda = (List<Object>) prefixEntry.get("expr");
        var prefixAlternatives = (List<List<Object>>) ((List<Object>) prefixLambda.get(2)).get(3);
        var prefixPart = (List<Object>) prefixAlternatives.getFirst().get(3);
        List<Object> prefix = number(0), suffix = variable("selected", LONG);
        for (int i = 0; i < 2; i++) {
            prefix = node("app", node("prim", "+#"), List.of(prefixPart, prefix), List.of(false, false), false, false, Map.of("rep", LONG));
            suffix = node("app", node("prim", "+#"), List.of(part, suffix), List.of(false, false), false, false, Map.of("rep", LONG));
        }
        lambda.set(2, node("case", prefix, "selected", List.of(node("default", null, List.of(), suffix)),
                Map.of("rep", LONG, "binder", binder("selected", LONG, false))));
        entry.put("expr", lambda); input.put("bindings", List.of(entry));
        return input;
    }

    @Test void defaultSuffixUsesTheExistingFiniteTransportWithoutReplayingItsScrutinee() throws Exception {
        var input = defaultSuffix(24, afterTake("prefix", "before", variable("selector", LONG), LONG));
        var entry = new LinkedHashMap<>(((List<Map<String, Object>>) input.get("bindings")).getFirst());
        var lambda = new ArrayList<>((List<Object>) entry.get("expr"));
        lambda.set(1, List.of(binder("selector", LONG, false), binder("list", DATA, true), binder("prefix", MUTABLE, false)));
        lambda.set(3, Map.of("rep", CLOSURE, "resultRep", LONG, "entryStrict", List.of(false, false, false)));
        entry.put("expr", lambda); input.put("bindings", List.of(entry, blockedMVarDependency()));
        constructors(input, tupleConstructor("Pair", 2));
        try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var program = new BytecodeProgram(language, input, true); var target = program.entryTarget("entry");
                var root = (BytecodeRoot) target.getRootNode(); var original = instructions(root);
                assertEquals(1, root.prepareGraphBudgetRetry(0), "transport control, not a synthetic compiler failure");
                root.getRootNodes().ensureSourceInformation(); assertEquals(original, instructions(root));
                var cloneMethod = root.getClass().getDeclaredMethod("cloneUninitialized"); cloneMethod.setAccessible(true);
                var clone = (BytecodeRoot) cloneMethod.invoke(root);
                assertEquals(1, clone.getGraphBudgetGeneration()); assertEquals(original, instructions(clone));
                assertTrue(compile(target)); bypass(target); assertEquals(0, entries(program));
                var prefix = new ManagedMVar(); assertTrue(prefix.tryPut(new Object()));
                assertEquals(65L, Calls.target(target, new Object[]{0L, 17L, chain(program, 48), prefix}));
                assertTrue(prefix.isEmpty()); assertEquals(1, entries(program));
                assertOnlyFirstEntryQuickening(original, instructions(root)); assertSame(target, program.entryTarget("entry"));
            } finally { context.leave(); }
        }
    }

    @Test void defaultSuffixRequiresALargeBodyNotJustALargeScrutinee() {
        var input = broadDefaultSuffix(24);
        var entry = new LinkedHashMap<>(((List<Map<String, Object>>) input.get("bindings")).getFirst());
        var lambda = new ArrayList<>((List<Object>) entry.get("expr"));
        var expression = new ArrayList<>((List<Object>) lambda.get(2));
        expression.set(3, List.of(node("default", null, List.of(), number(73))));
        lambda.set(2, expression); entry.put("expr", lambda); input.put("bindings", List.of(entry));
        try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var program = new BytecodeProgram(language, input); var root = (BytecodeRoot) program.entryTarget("entry").getRootNode();
                assertEquals(0, root.prepareGraphBudgetRetry(0)); assertEquals(0, entries(program));
            } finally { context.leave(); }
        }
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

    @Test void coldOversizedDecisionRunsCorrectlyWithoutExpandingUnobservedArms() throws Exception {
        try (Context context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var program = new BytecodeProgram(language, decision(768, List.of(binder("step", CLOSURE, true)), LONG,
                        arm -> node("app", variable("step", CLOSURE), List.of(number(arm * 3L + 17)),
                                List.of(false), false, false, Map.of("rep", LONG))));
                var effects = new java.util.concurrent.atomic.AtomicInteger();
                var step = new Closure(null, 1, new GuestRoot(language, null) {
                    @Override public long bloom(VirtualFrame frame) { return (Long) frame.getArguments()[0]; }
                    @Override public Object execute(VirtualFrame frame) {
                        effects.incrementAndGet(); return frame.getArguments()[1];
                    }
                }.getCallTarget());
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
                assertEquals(2318L, Calls.target(target, new Object[]{0L, chosen, step}));
                assertEquals(0, root.getGraphBudgetGeneration(), "cold arms need not enter the compiled graph");
                assertSame(target, ((Closure) program.entryValue("entry")).target);
                assertEquals(1, effects.get(), "the first cold case executes its selected effect exactly once");
            } finally { context.leave(); }
        }
    }

    @Test void disjointRegionsRecoverOnFreshEntryWithoutChangingPublishedPcs() throws Exception {
        var input = new LinkedHashMap<>(decision(768));
        var bindings = new ArrayList<Map<String, Object>>((List<Map<String, Object>>) input.get("bindings"));
        var entry = new LinkedHashMap<>(bindings.getFirst());
        var lambda = new ArrayList<>((List<Object>) entry.get("expr"));
        var first = (List<Object>) lambda.get(2);
        var second = new ArrayList<>(first);
        second.set(2, "later"); second.set(4, Map.of("rep", LONG, "binder", binder("later", DATA, true)));
        lambda.set(2, node("app", node("prim", "+#"), List.of(first, second),
                List.of(false, false), false, false, Map.of("rep", LONG)));
        entry.put("expr", lambda); bindings.set(0, entry); input.put("bindings", bindings);
        try (var context = context(10000)) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var program = new BytecodeProgram(language, input); var target = program.entryTarget("entry");
                var root = (BytecodeRoot) target.getRootNode();
                var field = BytecodeRoot.class.getDeclaredField("caseRegions"); field.setAccessible(true);
                assertEquals(2, ((BytecodeCaseRegion[]) field.get(root)).length);
                var uninitialized = instructions(root);
                // Ordinary branch profiles intentionally prune cold arms. Observe
                // each pure arm before asking the compiler to compile that graph.
                for (int arm = 0; arm < 768; arm++)
                    assertEquals(2L * (arm * 3L + 17), Calls.target(target,
                            new Object[]{0L, program.constructorLayout("C" + arm).allocate()}));
                var original = instructions(root);
                assertEquals(0, root.getGraphBudgetGeneration()); assertEquals(0, entries(program));
                InvocationTargetException failure = assertThrows(InvocationTargetException.class, () -> compile(target));
                assertTrue(failure.getCause().toString().contains("GraphTooBigBailoutException"));
                assertTrue(root.compilationFailureObserved);
                assertEquals(0, root.getGraphBudgetGeneration(), "failure cannot rewrite the retired physical root");
                assertEquals(0, entries(program)); assertEquals(original, instructions(root));
                assertFalse(valid(target)); assertFalse(compile(target), "the failed target cannot try again");
                assertEquals(4636L, Calls.target(target, new Object[]{0L, program.entryValue("chosen")}));
                var redirectField = BytecodeRoot.class.getDeclaredField("recoveredEntry"); redirectField.setAccessible(true);
                var replacement = (RootCallTarget) ((DirectCallNode) redirectField.get(root)).getCallTarget();
                var replacementRoot = (BytecodeRoot) replacement.getRootNode();
                assertNotSame(target, replacement);
                assertEquals(1, replacementRoot.getGraphBudgetGeneration(), "both regions belong to a smaller fresh root");
                assertEquals(2, ((BytecodeCaseRegion[]) field.get(replacementRoot)).length);
                assertEquals(0, entries(program), "the recovery entry ran in the interpreter");
                assertEquals(original, instructions(root));
                assertEquals(0, root.prepareGraphBudgetRetry(0));
                assertSame(target, program.entryTarget("entry"));
                assertTrue(compile(replacement)); assertTrue(valid(replacement)); bypass(replacement);
                long before = entries(program);
                assertEquals(4636L, Calls.target(target, new Object[]{0L, program.entryValue("chosen")}));
                assertEquals(before + 1, entries(program), "the first installed replacement call executes once");
                assertTrue(valid(replacement)); assertFalse(valid(target));
                root.getRootNodes().ensureSourceInformation(); assertEquals(original, instructions(root));
                var cloneMethod = root.getClass().getDeclaredMethod("cloneUninitialized"); cloneMethod.setAccessible(true);
                var clone = (BytecodeRoot) cloneMethod.invoke(root);
                assertUninitializedClone(uninitialized, original, instructions(clone));
                assertEquals(0, clone.getGraphBudgetGeneration(), "the retired source keeps its original PC layout");
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
                assertEquals(1, root.prepareGraphBudgetRetry(0)); // Preparation control; real failure selects a fresh target.
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
        // An ordinary let keeps finish outside the movable join-only prefix.
        var barrier = new LinkedHashMap<>(binder("barrier", LONG, false));
        barrier.put("expr", number(1));
        lambda.set(2, node("let", false, List.of(join),
                node("let", false, List.of(barrier), lambda.get(2), Map.of("rep", LONG)), Map.of("rep", LONG)));
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

    /** Deliberate bottom declares the lazy RTS dependency for synthetic
     * continuation transport. The real BlockedOwners module retains its GHC CAF. */
    private static Map<String, Object> blockedMVarDependency() {
        return Map.of("id", CoreBlockedExceptions.MVAR, "name", CoreBlockedExceptions.MVAR,
                "type", "SomeException", "lifted", true, "arity", 0, "rep", DATA,
                "expr", node("var", CoreBlockedExceptions.MVAR, Map.of("rep", DATA)));
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
    @Test void capacityExtractionPreservesTheFirstAsyncCutAndTupleOwnership() throws Exception {
        checkRegionCut(false, true);
    }
    @Test void complexConstructorFirstCompiledSidePreservesTheAsyncCut() throws Exception {
        checkRegionCut(false, false, false, true);
    }
    private void checkRegionCut(boolean parkBeforeRecovery) throws Exception {
        checkRegionCut(parkBeforeRecovery, false);
    }
    @Test void closedRecursiveRegionResumesPendingJoinOperandsAcrossThreads() throws Exception {
        checkRegionCut(false, false, true);
    }
    private void checkRegionCut(boolean parkBeforeRecovery, boolean capacity) throws Exception {
        checkRegionCut(parkBeforeRecovery, capacity, false);
    }
    private void checkRegionCut(boolean parkBeforeRecovery, boolean capacity, boolean recursiveRegion) throws Exception {
        checkRegionCut(parkBeforeRecovery, capacity, recursiveRegion, false);
    }
    private void checkRegionCut(boolean parkBeforeRecovery, boolean capacity, boolean recursiveRegion, boolean eager) throws Exception {
        var resultProof = tuple(REFERENCE, REFERENCE);
        var result = node("app", node("con", "Result", 2), List.of(variable("before", REFERENCE), variable("after", REFERENCE)),
                List.of(true, true), false, false, Map.of("rep", resultProof));
        var input = new LinkedHashMap<>(decision(eager ? 3 : 64,
                List.of(binder("prefix", MUTABLE, false), binder("blocked", MUTABLE, false)), resultProof,
                ignored -> capacity || eager ? capacityCalls(eager ? 3 : 64, 0,
                        afterTake("blocked", "after", result, resultProof), resultProof)
                        : afterTake("blocked", "after", result, resultProof)));
        var bindings = new ArrayList<Map<String, Object>>((List<Map<String, Object>>) input.get("bindings"));
        var entry = new LinkedHashMap<>(bindings.getFirst());
        var lambda = new ArrayList<>((List<Object>) entry.get("expr"));
        var decision = new ArrayList<>((List<Object>) lambda.get(2));
        // The selector is closed: this control isolates region suspension, not lazy-formal forcing.
        decision.set(1, node("con", eager ? "C2" : "C63", 0, Map.of("rep", DATA)));
        var arguments = new ArrayList<>(List.of(binder("prefix", MUTABLE, false), binder("blocked", MUTABLE, false)));
        if (capacity || recursiveRegion || eager) arguments.add(binder("step", CLOSURE, true));
        if (recursiveRegion) {
            var finishBody = node("app", node("con", "Result", 2),
                    List.of(variable("first", REFERENCE), variable("second", REFERENCE)),
                    List.of(true, true), false, false, Map.of("rep", resultProof));
            var finish = localJoin("finish", List.of(binder("first", REFERENCE, true), binder("second", REFERENCE, true)),
                    finishBody, resultProof);
            var transfer = joinedCall("finish", List.of(variable("before", REFERENCE),
                    afterTake("blocked", "after", variable("after", REFERENCE), REFERENCE)), resultProof);
            var workerBody = node("case", variable("remaining", LONG), "seen", List.of(
                    node("lit", List.of("int", "0"), List.of(), transfer),
                    node("default", null, List.of(), capacityCalls(64, 0,
                            joinedCall("worker", List.of(number(0)), resultProof), resultProof))),
                    Map.of("rep", resultProof, "binder", binder("seen", LONG, false)));
            var worker = localJoin("worker", List.of(binder("remaining", LONG, false)), workerBody, resultProof);
            decision = new ArrayList<>(node("let", false, List.of(finish),
                    node("let", true, List.of(worker), joinedCall("worker", List.of(number(1)), resultProof),
                            Map.of("rep", resultProof)), Map.of("rep", resultProof)));
        }
        lambda.set(1, arguments);
        lambda.set(2, afterTake("prefix", "before", decision, resultProof));
        lambda.set(3, Map.of("rep", CLOSURE, "resultRep", resultProof,
                "entryStrict", java.util.Collections.nCopies(arguments.size(), false)));
        entry.put("expr", lambda); bindings.set(0, entry);
        bindings.add(blockedMVarDependency());
        input.put("bindings", bindings);
        constructors(input, tupleConstructor("Pair", 2), tupleConstructor("Result", 2));
        try (var context = context()) {
            context.initialize("thc"); context.enter();
            final Language language; final Language.State owner; final BytecodeProgram program; final RootCallTarget target;
            final BytecodeRoot root; final RootCallTarget resume; final Closure step;
            var calls = new java.util.concurrent.atomic.AtomicInteger();
            try {
                language = TruffleLanguage.LanguageReference.create(Language.class).get(null); owner = Language.currentState();
                program = new BytecodeProgram(language, input, true); target = program.entryTarget("entry");
                root = (BytecodeRoot) target.getRootNode();
                step = new Closure(null, 1, new RootNode(language) {
                    @Override public Object execute(VirtualFrame frame) {
                        calls.incrementAndGet(); return (Long) frame.getArguments()[1] + 1;
                    }
                }.getCallTarget());
                resume = new RootNode(language) {
                    @Child private Force force = new Force(new Metrics(false), true);
                    @Override public Object execute(VirtualFrame frame) {
                        return force.drainStack((SavedGuestContinuation) frame.getArguments()[0], root.getTupleResult());
                    }
                }.getCallTarget();
                if (!parkBeforeRecovery) {
                    assertEquals(capacity || eager ? 0 : 1, root.prepareGraphBudgetRetry(0)); // Transport control, not a graph bailout.
                    if (eager) for (var side : regionSides(root)) {
                        assertTrue(compile(side)); assertTrue(valid(side)); bypass(side);
                    }
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
                            Calls.target(target, capacity || recursiveRegion || eager ? new Object[]{0L, prefix, blocked, step} : new Object[]{0L, prefix, blocked})));
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
                assertEquals(eager ? 3 : capacity || recursiveRegion ? 64 : 0, calls.get());
                owner.getThreads().send(java.util.Objects.requireNonNull(owner.getThreads().pollState(worker).getCurrent()).getIdentity(), "case region cut");
                var saved = answer.get(10, TimeUnit.SECONDS); worker.join(5000); assertFalse(worker.isAlive());
                assertEquals(compiled + (parkBeforeRecovery ? 0 : eager ? 2 : 1), entries(program));
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
                var completed = new CompletableFuture<Unit>();
                var resumer = new Thread(() -> {
                    context.enter();
                    try {
                        assertTrue(blocked.tryPut(after));
                        var value = TupleResults.ownedTupleResult(Calls.target(resume, new Object[]{saved}), root.getTupleResult());
                        var layout = root.getTupleResult().getLayout();
                        assertSame(before, layout.getObject(value, 0)); assertSame(after, layout.getObject(value, 1));
                        assertTrue(prefix.isEmpty(), "Completed caller effect must not replay"); assertTrue(blocked.isEmpty());
                        assertEquals(eager ? 3 : capacity || recursiveRegion ? 64 : 0, calls.get(), "completed side calls must not replay");
                        assertEquals(MaskingState.UNMASKED, SynchronousMasking.current(root));
                        var handoff = language.getHandoffState().get(); assertNull(handoff.getPending());
                        assertEquals(0, handoff.getArguments().getDepth()); assertEquals(0, handoff.getResults().getDepth());
                        assertEquals(0, handoff.getArguments().retainedReferences()); assertEquals(0, handoff.getResults().retainedReferences());
                        assertSame(target, program.entryTarget("entry"));
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
