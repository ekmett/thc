// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeUtil;
import com.oracle.truffle.api.nodes.RootNode;
import org.junit.jupiter.api.Test;
import thc.*;
import java.io.File;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static thc.Main.executionContext;
import static thc.runtime.ScalarTestCalls.callScalarTestTarget;

@SuppressWarnings("unchecked")
public class CaseArmOutliningTest {
    private final Map<String, Object> wide = Map.of("kind", "long", "primReps", List.of("IntRep"), "evaluated", true);
    private final Map<String, Object> closure = Map.of("kind", "closure", "primReps", List.of("BoxedRep (Just Lifted)"), "evaluated", true);
    private List<Object> variable(String id) { return variable(id, wide); }
    private List<Object> variable(String id, Map<String, Object> rep) { return List.of("var", id, Map.of("rep", rep)); }
    private List<Object> integer(long value) { return List.of("lit", "int", Long.toString(value), Map.of("rep", wide)); }
    private Map<String, Object> parameter(String id) { return parameter(id, wide); }
    private Map<String, Object> parameter(String id, Map<String, Object> rep) { return Map.of("id", id, "name", id, "lifted", rep.equals(closure), "rep", rep); }
    private List<Object> lambda(List<Map<String, Object>> parameters, List<?> body) { return lambda(parameters, body, wide); }
    private List<Object> lambda(List<Map<String, Object>> parameters, List<?> body, Map<String, Object> result) { return List.of("lam", parameters, body, Map.of("rep", closure, "resultRep", result)); }
    private Map<String, Object> binding(String id, List<?> body) { return Map.of("id", id, "name", id, "lifted", true, "rep", closure, "expr", body); }
    @SafeVarargs private final List<Object> app(List<?> function, List<?>... arguments) { return List.of("app", function, Arrays.asList(arguments), Collections.nCopies(arguments.length, false), false, false, Map.of("rep", wide)); }
    @SafeVarargs private final List<Object> prim(String name, List<?>... arguments) { return app(List.of("prim", name), arguments); }
    @SafeVarargs private final List<Object> choice(List<?> value, String id, List<?>... arms) { return choice(wide, value, id, arms); }
    @SafeVarargs private final List<Object> choice(Map<String, Object> result, List<?> value, String id, List<?>... arms) { return List.of("case", value, id, Arrays.asList(arms), Map.of("rep", result, "binder", parameter(id))); }
    private List<Object> fallback(List<?> body) { return Arrays.asList("default", null, List.of(), body); }
    private List<Object> zero(List<?> body) { return List.of("lit", List.of("int", "0"), List.of(), body); }
    @FunctionalInterface private interface Action { void run(Language language) throws Exception; }
    private void withLanguage(Action action) throws Exception {
        try (var context = executionContext()) { context.initialize("thc"); context.enter();
            try { action.run(TruffleLanguage.LanguageReference.create(Language.class).get(null)); } finally { context.leave(); }
        }
    }
    private Program program(Language language, boolean async, List<?> body) { return program(language, async, body, List.of(parameter("x")), Map.of()); }
    private Program program(Language language, boolean async, List<?> body, List<Map<String, Object>> parameters, Map<String, Object> extra) {
        var module = new LinkedHashMap<>(extra); module.put("bindings", List.of(binding("entry", lambda(parameters, body))));
        return new Program(language, module, async, true);
    }
    private long count(Program program, String key) { return ((Number) program.diagnostics().get(key)).longValue(); }
    private void compile(RootCallTarget target) throws Exception {
        target.getClass().getMethod("compile", boolean.class).invoke(target, true); target.getClass().getMethod("waitForCompilation").invoke(target); assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target));
        var runtime = Truffle.getRuntime(); runtime.getClass().getMethod("bypassedInstalledCode", Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget")).invoke(runtime, target);
    }
    private void released(Language language) {
        var state = language.getHandoffState().get(); assertEquals(0, state.getArguments().getDepth()); assertEquals(0, state.getArguments().retainedReferences());
        assertEquals(0, state.getResults().getDepth()); assertEquals(0, state.getResults().retainedReferences());
    }

    @Test @SuppressWarnings("unchecked")
    public void reusableWideCaseKeepsColdArmsAndFreshInvocationOwners() throws Exception {
        var alternatives = new ArrayList<List<?>>();
        for (int i = 0; i < 67; i++) alternatives.add(List.of("lit", List.of("int", Integer.toString(i)),
            List.of(), i == 1 ? app(variable("entry", closure), integer(0), prim("+#", variable("offset"), integer(7)))
                : prim("+#", variable("seen"), variable("offset"))));
        alternatives.add(fallback(prim("+#", variable("seen"), integer(-100))));
        var body = choice(variable("x"), "seen", alternatives.toArray(List<?>[]::new));
        var module = Map.<String,Object>of("instrument", true, "bindings", List.of(binding("entry",
            lambda(List.of(parameter("x"), parameter("offset")), body))));
        try (var engine = org.graalvm.polyglot.Engine.newBuilder().allowExperimentalOptions(true)
                .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
                .option("engine.CompilationFailureAction", "Throw").build()) {
            Program.PreparedCode code;
            try (var context = org.graalvm.polyglot.Context.newBuilder("thc").engine(engine).build()) {
                context.initialize("thc"); context.enter();
                try { code = Program.prepareCode(TruffleLanguage.LanguageReference.create(Language.class).get(null), module, List.of("entry")); }
                finally { context.leave(); }
            }
            var field = Program.PreparedCode.class.getDeclaredField("targets"); field.setAccessible(true);
            var targets = (List<RootCallTarget>) field.get(code);
            var arms = targets.stream().filter(t -> ((FunctionRoot)t.getRootNode()).getRole$org_intelligence_thc() == FunctionRootRole.PASS_THROUGH).toList();
            assertFalse(arms.isEmpty(), "Wide reusable cases need separate cold compilation units");
            for (var target : targets) {
                var root = (FunctionRoot) target.getRootNode();
                assertFalse(root.getStackCapture()); assertTrue(root.getCapturesContinuations$org_intelligence_thc(), "Prepared roots retain continuation capability before thread creation");
                assertEquals(false, target.getClass().getMethod("wasExecuted").invoke(target));
                assertEquals(true, target.getClass().getMethod("prepareForAOT").invoke(target)); compile(target);
                assertEquals(false, target.getClass().getMethod("wasExecuted").invoke(target));
            }
            for (int load = 0; load < 2; load++) try (var context = org.graalvm.polyglot.Context.newBuilder("thc").engine(engine).build()) {
                context.initialize("thc"); context.enter();
                try {
                    var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                    var programs = List.of(code.newInstance(language), code.newInstance(language));
                    for (int owner = 0; owner < 2; owner++) {
                        var program = programs.get(owner); var closure = (Closure) program.entryValue("entry");
                        long before = count(program, "compiledEntries");
                        assertEquals(47L + load + owner, Calls.target(closure.target, new Object[]{0L, closure.environment, 6L, 41L + load + owner}));
                        assertEquals(-25L, Calls.target(closure.target, new Object[]{0L, closure.environment, 75L, 900L}));
                        assertEquals(48L, Calls.target(closure.target, new Object[]{0L, closure.environment, 1L, 41L}));
                        // The recursive edge reuses the owning root's self loop;
                        // only its next selected arm is a new root entry.
                        assertEquals(before + 7, count(program, "compiledEntries"));
                        assertEquals(1L, count(program, "selfTailReentries"));
                        assertEquals(0, count(program, "loweredRootCount"));
                        code.requireInstalledCode(); released(language);
                    }
                } finally { context.leave(); }
            }
        }
    }

    @Test @SuppressWarnings("unchecked")
    public void reusableSubstantialSiblingArmsKeepInternalAndOuterJoinsDistinct() throws Exception {
        var internal = new ArrayList<List<?>>();
        var external = new ArrayList<List<?>>();
        var finish = new LinkedHashMap<>(binding("finish", lambda(List.of(parameter("n")), prim("+#", variable("n"), integer(9)))));
        finish.put("joinValueArity", 1); finish.put("joinResultRep", wide);
        for (int i = 0; i < 7; i++) {
            List<Object> value = variable("seen");
            for (int j = 0; j < 18; j++) value = prim("+#", value, variable("offset"));
            var jump = app(variable("finish", closure), value);
            var ownJoin = List.of("let", false, List.of(finish), jump, Map.of("rep", wide));
            internal.add(i == 6 ? fallback(ownJoin) : List.of("lit", List.of("int", Integer.toString(i)), List.of(), ownJoin));
            external.add(i == 6 ? fallback(jump) : List.of("lit", List.of("int", Integer.toString(i)), List.of(), jump));
        }
        var parameters = List.of(parameter("x"), parameter("offset"));
        var ownBody = choice(variable("x"), "seen", internal.toArray(List<?>[]::new));
        var outerBody = List.of("let", false, List.of(finish),
            choice(variable("x"), "seen", external.toArray(List<?>[]::new)), Map.of("rep", wide));
        var module = Map.<String,Object>of("instrument", true, "bindings", List.of(
            binding("internal", lambda(parameters, ownBody)), binding("external", lambda(parameters, outerBody))));
        try (var engine = org.graalvm.polyglot.Engine.newBuilder().allowExperimentalOptions(true)
                .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
                .option("engine.CompilationFailureAction", "Throw").build()) {
            Program.PreparedCode code;
            try (var context = org.graalvm.polyglot.Context.newBuilder("thc").engine(engine).build()) {
                context.initialize("thc"); context.enter();
                try { code = Program.prepareCode(TruffleLanguage.LanguageReference.create(Language.class).get(null), module, List.of("internal", "external")); }
                finally { context.leave(); }
            }
            var field = Program.PreparedCode.class.getDeclaredField("targets"); field.setAccessible(true);
            var targets = (List<RootCallTarget>) field.get(code);
            assertEquals(7, targets.stream().filter(t -> ((FunctionRoot)t.getRootNode()).getRole$org_intelligence_thc() == FunctionRootRole.PASS_THROUGH).count(),
                "Substantial siblings may carry their internal joins, never an outer join");
            for (var target : targets) {
                assertFalse(((FunctionRoot)target.getRootNode()).getStackCapture());
                assertEquals(false, target.getClass().getMethod("wasExecuted").invoke(target));
                assertEquals(true, target.getClass().getMethod("prepareForAOT").invoke(target)); compile(target);
                assertEquals(false, target.getClass().getMethod("wasExecuted").invoke(target));
            }
            for (int load = 0; load < 2; load++) try (var context = org.graalvm.polyglot.Context.newBuilder("thc").engine(engine).build()) {
                context.initialize("thc"); context.enter();
                try {
                    var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                    for (var program : List.of(code.newInstance(language), code.newInstance(language))) {
                        assertEquals(2L, count(program, "compiledEntries"), "Both compiled initializer roots enter before guest calls");
                        long before = count(program, "compiledEntries");
                        long offset = load + 2L;
                        var own = (Closure)program.entryValue("internal"); var outer = (Closure)program.entryValue("external");
                        assertEquals(7, NodeUtil.findAllNodeInstances(own.target.getRootNode(), AstCaseArm.class).size());
                        assertTrue(NodeUtil.findAllNodeInstances(outer.target.getRootNode(), AstCaseArm.class).isEmpty());
                        assertEquals(3L + 18L * offset + 9L, Calls.target(own.target, new Object[]{0L, own.environment, 3L, offset}));
                        assertEquals(9L + 18L * offset + 9L, Calls.target(outer.target, new Object[]{0L, outer.environment, 9L, offset}));
                        assertEquals(2L, count(program, "localJoinTransfers")); assertEquals(before + 3L, count(program, "compiledEntries"));
                        assertEquals(0, count(program, "loweredRootCount")); code.requireInstalledCode(); released(language);
                    }
                } finally { context.leave(); }
            }
        }
    }

    @Test @SuppressWarnings("unchecked")
    public void reusableWideCaseKeepsOuterJoinInItsOwningFrame() throws Exception {
        withLanguage(language -> {
            var join = new LinkedHashMap<>(binding("finish", lambda(List.of(parameter("n")), prim("+#", variable("n"), integer(9)))));
            join.put("joinValueArity", 1); join.put("joinResultRep", wide);
            var alternatives = new ArrayList<List<?>>();
            for (int i = 0; i < 33; i++) alternatives.add(List.of("lit", List.of("int", Integer.toString(i)), List.of(), app(variable("finish", closure), variable("seen"))));
            alternatives.add(fallback(app(variable("finish", closure), variable("seen"))));
            var body = List.of("let", false, List.of(join), choice(variable("x"), "seen", alternatives.toArray(List<?>[]::new)), Map.of("rep", wide));
            var code = Program.prepareCode(language, Map.of("instrument", true,
                "bindings", List.of(binding("entry", lambda(List.of(parameter("x")), body)))), List.of("entry"));
            var field = Program.PreparedCode.class.getDeclaredField("targets"); field.setAccessible(true);
            for (var target : (List<RootCallTarget>)field.get(code)) {
                assertTrue(NodeUtil.findAllNodeInstances(target.getRootNode(), AstCaseArm.class).isEmpty());
                assertEquals(false, target.getClass().getMethod("wasExecuted").invoke(target));
                assertEquals(true, target.getClass().getMethod("prepareForAOT").invoke(target)); compile(target);
            }
            var program = code.newInstance(language); var entry = (Closure)program.entryValue("entry");
            assertEquals(38L, Calls.target(entry.target, new Object[]{0L, entry.environment, 29L}));
            assertEquals(1L, count(program, "localJoinTransfers"));
            code.requireInstalledCode(); released(language);
        });
    }

    @Test public void coldSelectedArmUsesTypedCapturesAndRetainsFirstCompiledCaller() throws Exception {
        withLanguage(language -> { for (boolean async : new boolean[] {false, true}) {
            var body = choice(variable("x"), "seen", fallback(prim("+#", variable("seen"), integer(17))));
            var p = program(language, async, body); var target = p.entryTarget("entry"); var arms = NodeUtil.findAllNodeInstances(target.getRootNode(), AstCaseArm.class);
            assertEquals(1, arms.size()); var arm = arms.getFirst(); assertTrue(arm.getTailPosition());
            // Install both cold targets without invoking either body before the first call.
            compile(arm.getTarget()); compile(target); long before = count(p, "compiledEntries");
            assertEquals(9000000018L, Calls.target(target, new Object[] {0L, 9000000001L})); assertTrue(count(p, "compiledEntries") > before, "first call enters installed guest code");
            assertSame(target, p.entryTarget("entry")); assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target));
            assertEquals(true, arm.getTarget().getClass().getMethod("isValidLastTier").invoke(arm.getTarget())); released(language);
        } });
    }

    @Test @SuppressWarnings("unchecked")
    public void reusableWideTupleArmsPreserveExactCapturesAndCleanLoans() throws Exception {
        withLanguage(language -> {
            Map<String, Object> narrow = Map.of("kind", "long", "primReps", List.of("Int8Rep"), "evaluated", true);
            Map<String, Object> floating = Map.of("kind", "float", "primReps", List.of("FloatRep"), "evaluated", true);
            Map<String, Object> tuple = Map.of("kind", "unknown", "aggregate", "unboxed-tuple", "evaluated", true,
                "primReps", List.of("Int8Rep", "FloatRep", "BoxedRep (Just Lifted)"), "components", List.of(narrow, floating, closure));
            var construct = List.of("app", List.of("con", "Tuple3", 3),
                List.of(variable("narrow", narrow), variable("floating", floating), variable("marker", closure)),
                List.of(false, false, true), false, false, Map.of("rep", tuple));
            var alternatives = new ArrayList<List<?>>();
            for (int i = 0; i < 33; i++) alternatives.add(List.of("lit", List.of("int", Integer.toString(i)), List.of(), construct));
            alternatives.add(fallback(construct));
            var body = choice(tuple, variable("x"), "seen", alternatives.toArray(List<?>[]::new));
            var module = Map.<String,Object>of("instrument", true,
                "constructors", List.of(Map.of("id", "Tuple3", "name", "(#,,#)", "arity", 3, "tag", 1, "kind", "unboxed-tuple")),
                "bindings", List.of(binding("entry", lambda(List.of(parameter("x"), parameter("narrow", narrow),
                    parameter("floating", floating), parameter("marker", closure)), body, tuple))));
            var code = Program.prepareCode(language, module, List.of("entry"));
            var field = Program.PreparedCode.class.getDeclaredField("targets"); field.setAccessible(true);
            for (var target : (List<RootCallTarget>)field.get(code)) {
                assertEquals(false, target.getClass().getMethod("wasExecuted").invoke(target));
                assertEquals(true, target.getClass().getMethod("prepareForAOT").invoke(target)); compile(target);
            }
            var marker = new Closure(null, 0, new RootNode(language) {
                @Override public Object execute(VirtualFrame frame) { throw new AssertionError("Lazy capture was forced"); }
            }.getCallTarget());
            for (int owner = 0; owner < 2; owner++) {
                var program = code.newInstance(language); var entry = (Closure)program.entryValue("entry");
                assertEquals(1L, count(program, "compiledEntries"), "Compiled initializer enters before the tuple caller");
                long before = count(program, "compiledEntries");
                var shape = Objects.requireNonNull(((GuestRoot)entry.target.getRootNode()).getTupleResult());
                var layout = new FrameLayout(); var slots = new int[3];
                for (int i = 0; i < slots.length; i++) slots[i] = layout.bind("result " + i);
                var frame = Truffle.getRuntime().createVirtualFrame(new Object[0], layout.build());
                shape.consume(frame, callScalarTestTarget(entry.target,
                    new Object[]{0L, entry.environment, owner == 0 ? 0L : 100L, -128, Float.intBitsToFloat(0x80000000), marker}), slots, 0);
                assertEquals(-128, frame.getInt(slots[0])); assertEquals(0x80000000, Float.floatToRawIntBits(frame.getFloat(slots[1])));
                assertSame(marker, frame.getObject(slots[2])); assertEquals(before + 2L, count(program, "compiledEntries"));
                code.requireInstalledCode(); released(language);
            }
        });
    }
    @Test @SuppressWarnings("unchecked")
    public void reusableTypedTailArmTransfersTheSameLivePacketUntilReceiverEntry() throws Exception {
        withLanguage(language -> {
            Map<String, Object> narrow = Map.of("kind", "long", "primReps", List.of("Int8Rep"), "evaluated", true);
            Map<String, Object> tuple = Map.of("kind", "unknown", "aggregate", "unboxed-tuple", "evaluated", true,
                "primReps", List.of("Int8Rep", "BoxedRep (Just Lifted)"), "components", List.of(narrow, closure));
            var construct = List.of("app", List.of("con", "Pair", 2),
                List.of(variable("n", narrow), variable("marker", closure)), List.of(false, true), false, false, Map.of("rep", tuple));
            var recurse = List.of("app", variable("entry", closure),
                List.of(integer(0), variable("n", narrow), variable("marker", closure)),
                List.of(false, false, true), false, false, Map.of("rep", tuple));
            var alternatives = new ArrayList<List<?>>();
            for (int i = 0; i < 33; i++) alternatives.add(List.of("lit", List.of("int", Integer.toString(i)), List.of(), i == 1 ? recurse : construct));
            alternatives.add(fallback(construct));
            var code = Program.prepareCode(language, Map.of("instrument", true,
                "constructors", List.of(Map.of("id", "Pair", "kind", "unboxed-tuple", "arity", 2)),
                "bindings", List.of(binding("entry", lambda(List.of(parameter("x"), parameter("n", narrow), parameter("marker", closure)),
                    choice(tuple, variable("x"), "seen", alternatives.toArray(List<?>[]::new)), tuple)))), List.of("entry"));
            var field = Program.PreparedCode.class.getDeclaredField("targets"); field.setAccessible(true);
            for (var target : (List<RootCallTarget>) field.get(code)) {
                assertEquals(false, target.getClass().getMethod("wasExecuted").invoke(target));
                assertEquals(true, target.getClass().getMethod("prepareForAOT").invoke(target)); compile(target);
            }
            var program = code.newInstance(language); var entry = (Closure) program.entryValue("entry");
            assertEquals(1L, count(program, "compiledEntries"), "Compiled initializer enters before the tail arm");
            long before = count(program, "compiledEntries");
            var root = (FunctionRoot) entry.target.getRootNode(); var typed = Objects.requireNonNull(root.getTypedInput());
            var marker = new Closure(null, 0, new RootNode(language) {
                @Override public Object execute(VirtualFrame frame) { throw new AssertionError("Tail reference was forced"); }
            }.getCallTarget());
            // Enter the genuine lowered arm with the same activation its owning case uses.
            // Stopping at this boundary exposes the outstanding loan before any receiver.
            var caller = Truffle.getRuntime().createVirtualFrame(new Object[0], root.getFrameDescriptor());
            root.buildFrame(new Object[]{0L, entry.environment, 1L, -128, marker}, caller);
            caller.setLong(FrameLayout.BLOOM_FILTER, root.mask);
            var arm = NodeUtil.findAllNodeInstances(root, AstCaseArm.class).get(1);
            var transfer = assertThrows(TailCall.class, () -> arm.execute(caller));
            var input = Objects.requireNonNull(transfer.getInput()); var packet = typed.getPacket();
            long generation = input.getGeneration();
            try {
                assertSame(entry.target, transfer.getTarget()); assertSame(Closure.NO_PAP_ARGUMENTS, transfer.getArgs()); assertSame(packet, input.getLayout());
                assertTrue(input.getInputMode() == 1 || input.getInputMode() == 3);
                assertEquals(input.getInputMode() == 1, input.getLive());
                assertSame(entry.environment, packet.getObject(input, 1));
                assertEquals(0L, packet.getLong(input, typed.getHeader()));
                assertEquals(-128, packet.getInt(input, typed.getHeader() + 1));
                assertSame(marker, packet.getObject(input, typed.getHeader() + 2));
                assertEquals(before + 1L, count(program, "compiledEntries")); code.requireInstalledCode();
                var shape = Objects.requireNonNull(root.getTupleResult());
                var result = TupleResults.ownedTupleResult(Calls.target(transfer.getTarget(), new Object[]{input}), shape);
                assertEquals(-128, shape.getLayout().getInt(result, 0)); assertSame(marker, shape.getLayout().getObject(result, 1));
                assertEquals(0, input.getInputMode()); assertFalse(input.getLive()); assertEquals(generation, input.getGeneration());
                assertNull(packet.getObject(input, 1)); assertNull(packet.getObject(input, typed.getHeader() + 2));
                assertEquals(before + 3L, count(program, "compiledEntries")); code.requireInstalledCode(); released(language);
                assertThrows(RuntimeFault.class, () -> typed.release(input));
            } finally { typed.releaseChecked(input); }
        });
    }
    @Test public void scrutineeIsEvaluatedOnceAndReturningArmKeepsItsNontailSuffix() throws Exception {
        withLanguage(language -> { for (boolean async : new boolean[] {false, true}) {
            var effects = new int[1]; var tick = new Closure(null, 1, new RootNode(language) {
                @Override public Object execute(VirtualFrame frame) { effects[0]++; return Objects.requireNonNull(frame.getArguments()[1]); }
            }.getCallTarget());
            var selected = choice(app(variable("tick", closure), variable("x")), "seen", zero(prim("+#", variable("seen"), integer(31))), fallback(prim("+#", variable("seen"), integer(41))));
            var p = program(language, async, prim("+#", selected, integer(100)), List.of(parameter("x"), parameter("tick", closure)), Map.of());
            var target = p.entryTarget("entry"); var arms = NodeUtil.findAllNodeInstances(target.getRootNode(), AstCaseArm.class);
            assertEquals(2, arms.size()); boolean noneTail = true; for (var arm : arms) if (arm.getTailPosition()) { noneTail = false; break; } assertTrue(noneTail);
            for (long x : new long[] {0L, 2L, 0L}) { int before = effects[0]; assertEquals(100L + x + (x == 0L ? 31L : 41L), Calls.target(target, new Object[] {0L, x, tick})); assertEquals(before + 1, effects[0]); released(language); }
        } });
    }
    @Test public void tailArmPreservesOuterLoopOwnershipAndDoesNotCreateGenericTrampolineLaps() throws Exception {
        withLanguage(language -> { for (boolean async : new boolean[] {false, true}) {
            var body = choice(variable("x"), "seen", zero(integer(73)), fallback(app(variable("entry", closure), prim("-#", variable("seen"), integer(1)))));
            var p = program(language, async, body); assertEquals(73L, Calls.target(p.entryTarget("entry"), new Object[] {0L, 500L}));
            assertEquals(500L, count(p, "selfTailReentries")); assertEquals(0L, count(p, "trampolineIterations")); released(language);
        } });
    }
    @Test public void outerLexicalJoinRemainsInItsOwningFrame() throws Exception {
        withLanguage(language -> { for (boolean async : new boolean[] {false, true}) {
            var join = new LinkedHashMap<>(binding("finish", lambda(List.of(parameter("n")), prim("+#", variable("n"), integer(9)))));
            join.put("joinValueArity", 1); join.put("joinResultRep", wide);
            var choice = choice(variable("x"), "seen", fallback(app(variable("finish", closure), variable("seen"))));
            var body = List.of("let", false, List.of(join), choice, Map.of("rep", wide)); var p = program(language, async, body); var target = p.entryTarget("entry");
            assertTrue(NodeUtil.findAllNodeInstances(target.getRootNode(), AstCaseArm.class).isEmpty()); assertEquals(38L, Calls.target(target, new Object[] {0L, 29L}));
            assertEquals(1L, count(p, "localJoinTransfers"));
        } });
    }
    @Test public void narrowFloatAndReferenceFieldsKeepExactTupleShapeAcrossArm() throws Exception {
        withLanguage(language -> {
            Map<String, Object> narrow = Map.of("kind", "long", "primReps", List.of("Int8Rep"), "evaluated", true), floating = Map.of("kind", "float", "primReps", List.of("FloatRep"), "evaluated", true);
            Map<String, Object> tuple = Map.of("kind", "unknown", "aggregate", "unboxed-tuple", "evaluated", true, "primReps", List.of("Int8Rep", "FloatRep", "BoxedRep (Just Lifted)"), "components", List.of(narrow, floating, closure));
            var fields = List.of(variable("narrow", narrow), variable("floating", floating), variable("marker", closure));
            var construct = List.of("app", List.of("con", "Tuple3", 3), fields, List.of(false, false, true), false, false, Map.of("rep", tuple));
            var body = choice(tuple, integer(0), "seen", fallback(construct));
            Map<String, Object> module = Map.of("constructors", List.of(Map.of("id", "Tuple3", "name", "(#,,#)", "arity", 3, "tag", 1, "kind", "unboxed-tuple")),
                "bindings", List.of(binding("entry", lambda(List.of(parameter("narrow", narrow), parameter("floating", floating), parameter("marker", closure)), body, tuple))));
            var marker = new Closure(null, 0, new RootNode(language) { @Override public Object execute(VirtualFrame frame) { throw new IllegalStateException("Captured reference must not be forced"); } }.getCallTarget());
            for (boolean async : new boolean[] {false, true}) {
                var p = new Program(language, module, async, true); var target = p.entryTarget("entry"); var shape = Objects.requireNonNull(((GuestRoot) target.getRootNode()).getTupleResult());
                var layout = new FrameLayout(); var slots = new int[3]; for (int i = 0; i < slots.length; i++) slots[i] = layout.bind("result " + i);
                var frame = Truffle.getRuntime().createVirtualFrame(new Object[0], layout.build());
                shape.consume(frame, callScalarTestTarget(target, new Object[] {0L, -128, Float.intBitsToFloat(0x80000000), marker}), slots, 0);
                assertEquals(-128, frame.getInt(slots[0])); assertEquals(0x80000000, Float.floatToRawIntBits(frame.getFloat(slots[1]))); assertSame(marker, frame.getObject(slots[2])); released(language);
            }
        });
    }
    private List<Object> afterTake(String id, List<?> suffix, Map<String, Object> mvar, Map<String, Object> state, Map<String, Object> data, Map<String, Object> pair, Map<String, Object> result) {
        var take = List.of("app", List.of("prim", "takeMVar#"), List.of(variable(id, mvar), List.of("void", Map.of("rep", state))), List.of(false, false), false, false, Map.of("rep", pair));
        return List.of("case", take, id + "Pair", List.of(List.of("data", "Pair", List.of(id + "State", id + "Value"), suffix,
            Map.of("binders", List.of(Map.of("id", id + "State", "rep", state), Map.of("id", id + "Value", "rep", data))))),
            Map.of("rep", result, "binder", Map.of("id", id + "Pair", "rep", pair)));
    }
    @Test public void actualBlockingCaptureResumesOutlinedTupleArmWithoutReplayingPrefix() throws Exception {
        Map<String, Object> state = Map.of("kind", "void", "primReps", List.of(), "evaluated", true);
        Map<String, Object> mvar = Map.of("kind", "object", "primReps", List.of("BoxedRep (Just Unlifted)"), "evaluated", true);
        Map<String, Object> data = Map.of("kind", "data", "primReps", List.of("BoxedRep (Just Lifted)"), "evaluated", false);
        Map<String, Object> pair = Map.of("kind", "unknown", "aggregate", "unboxed-tuple", "evaluated", false, "primReps", List.of("BoxedRep (Just Lifted)"), "components", List.of(state, data));
        Map<String, Object> result = Map.of("kind", "unknown", "aggregate", "unboxed-tuple", "evaluated", true, "primReps", List.of("IntRep"), "components", List.of(state, wide));
        var tuple = List.of("app", List.of("con", "Result", 2), List.of(List.of("void", Map.of("rep", state)), variable("x")), List.of(false, false), false, false, Map.of("rep", result));
        Map<String, Object> module = Map.of("bindings", List.of(binding("entry", lambda(List.of(parameter("prefix", mvar), parameter("blocked", mvar), parameter("x")),
            afterTake("prefix", afterTake("blocked", tuple, mvar, state, data, pair, result), mvar, state, data, pair, result), result))),
            "constructors", List.of(Map.of("id", "Pair", "kind", "unboxed-tuple", "arity", 2), Map.of("id", "Result", "kind", "unboxed-tuple", "arity", 2)));
        try (var context = executionContext()) {
            context.initialize("thc"); context.enter(); final Language language; final Language.State owner; final Program p; final RootCallTarget target;
            try {
                language = TruffleLanguage.LanguageReference.create(Language.class).get(null); owner = Language.currentState();
                p = new Program(language, module, true, true); target = p.entryTarget("entry");
            } finally { context.leave(); }
            var prefix = new ManagedMVar(); assertTrue(prefix.tryPut("prefix once")); var blocked = new ManagedMVar(); var answer = new CompletableFuture<SavedGuestContinuation>();
            var worker = new Thread(() -> {
                context.enter(); owner.getThreads().enterCurrent();
                try {
                    var saved = Objects.requireNonNull(SavedGuestContinuations.savedGuestContinuation(Calls.target(target, new Object[] {0L, prefix, blocked, 918273645L})));
                    Objects.requireNonNull(saved.asyncRequest()).acknowledge(); answer.complete(saved);
                } catch (Throwable failure) { answer.completeExceptionally(failure); } finally { owner.getThreads().leaveCurrent(); context.leave(); }
            }); worker.setDaemon(true); worker.start();
            try {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (blocked.pendingCounts().getTakers() != 1 && !answer.isDone() && System.nanoTime() < deadline) Thread.sleep(1);
                if (answer.isCompletedExceptionally()) answer.get(1, TimeUnit.SECONDS);
                assertEquals(1, blocked.pendingCounts().getTakers()); assertTrue(prefix.isEmpty());
                owner.getThreads().send(Objects.requireNonNull(owner.getThreads().pollState(worker).getCurrent()).getIdentity(), "outlined cut");
                var saved = answer.get(10, TimeUnit.SECONDS); worker.join(5000); assertFalse(worker.isAlive()); context.enter();
                try {
                    assertTrue(blocked.tryPut("resume")); var shape = Objects.requireNonNull(((GuestRoot) target.getRootNode()).getTupleResult());
                    // The outer cut owns a suspended child, not a bare MVar request.
                    // Resume that child before supplying ChildResume to the saved outer suffix.
                    var parked = new Thunk(target, null); parked.setValue(saved.getIdentity()); parked.setState(5);
                    var driver = new RootNode(language) {
                        @Child private Force force = new Force(new Metrics(false), true);
                        @Override public Object execute(VirtualFrame frame) { return force.execute(frame, parked); }
                    }.getCallTarget();
                    var completed = TupleResults.ownedTupleResult(Calls.target(driver, new Object[0]), shape);
                    assertEquals(918273645L, shape.getLayout().getLong(completed, 0)); assertTrue(prefix.isEmpty()); assertTrue(blocked.isEmpty());
                    assertThrows(RuntimeFault.class, () -> saved.continueWith(thc.runtime.Unit.INSTANCE)); assertSame(target, p.entryTarget("entry")); released(language);
                } finally { context.leave(); }
            } finally { if (worker.isAlive()) context.close(true); worker.join(5000); }
        }
    }
    @Test public void genuineDelimitedResumptionKeepsTailJoinMaskCatchAndTupleResults() throws Exception {
        var root = new File(System.getProperty("thc.projectRoot")); var manifest = (Map<?, ?>) Json.parse(Files.readString(new File(root, "build/delimited-continuations/manifest.json").toPath()));
        for (var group : List.of("inputHashes", "artifactHashes")) for (var hash : ((Map<?, ?>) manifest.get(group)).entrySet())
            assertEquals(hash.getValue(), HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(new File(root, (String) hash.getKey()).toPath()))), hash.getKey().toString());
        var entries = (List<?>) manifest.get("entries"); var inputs = new ArrayList<Long>(); for (var input : (List<?>) manifest.get("arguments")) inputs.add(((Number) input).longValue());
        var nativeRows = new ArrayList<Long>(); for (var value : (List<?>) manifest.get("native")) nativeRows.add(((Number) value).longValue());
        for (var stage : List.of("pre", "post")) for (boolean async : new boolean[] {false, true}) withLanguage(language -> {
            var source = CoreCbdFixtures.read(new File(root, "build/delimited-continuations/" + stage + "/core/DelimitedContinuations.cbd").toPath());
            for (var entry : List.of("resumeTwice", "resumedTail", "resumedJoin", "resumedScalar", "capturedCatch", "capturedMask")) {
                var p = new Program(language, CoreModules.reachable(source, "main:DelimitedContinuations." + entry), async, true); var function = org.graalvm.polyglot.Context.getCurrent().asValue(new EntryValue(p, "main:DelimitedContinuations." + entry, 1));
                for (int index = 0; index < inputs.size(); index++) {
                    assertEquals(nativeRows.get(index * entries.size() + entries.indexOf(entry)).longValue(), function.execute(inputs.get(index)).asLong(), stage + "/" + async + "/" + entry);
                    assertEquals(MaskingState.UNMASKED, Language.currentState().getMaskingState().get()); released(language);
                }
            }
        });
    }
}
