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
        target.getClass().getMethod("compile", boolean.class).invoke(target, true); assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target));
        var runtime = Truffle.getRuntime(); runtime.getClass().getMethod("bypassedInstalledCode", Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget")).invoke(runtime, target);
    }
    private void released(Language language) {
        var state = language.getHandoffState().get(); assertEquals(0, state.getArguments().getDepth()); assertEquals(0, state.getArguments().retainedReferences());
        assertEquals(0, state.getResults().getDepth()); assertEquals(0, state.getResults().retainedReferences());
    }

    @Test public void coldSelectedArmUsesTypedCapturesAndRetainsFirstCompiledCaller() throws Exception {
        withLanguage(language -> { for (boolean async : new boolean[] {false, true}) {
            var body = choice(variable("x"), "seen", fallback(prim("+#", variable("seen"), integer(17))));
            var p = program(language, async, body); var target = p.entryTarget("entry"); var arms = NodeUtil.findAllNodeInstances(target.getRootNode(), AstCaseArm.class);
            assertEquals(1, arms.size()); var arm = arms.getFirst(); assertTrue(arm.getTailPosition()); assertEquals(2L, count(p, "loweredRootCount"));
            // Install both cold targets without invoking either body before the first call.
            compile(arm.getTarget()); compile(target); long before = count(p, "compiledEntries");
            assertEquals(9000000018L, Calls.target(target, new Object[] {0L, 9000000001L})); assertEquals(before + 2, count(p, "compiledEntries"));
            assertSame(target, p.entryTarget("entry")); assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target));
            assertEquals(true, arm.getTarget().getClass().getMethod("isValidLastTier").invoke(arm.getTarget())); released(language);
        } });
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
            assertEquals(1L, count(p, "localJoinTransfers")); assertEquals(1L, count(p, "loweredRootCount"));
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
                p = new Program(language, module, true, true); target = p.entryTarget("entry"); assertEquals(3L, count(p, "loweredRootCount"));
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
            var source = (Map<String, Object>) Json.parse(Files.readString(new File(root, "build/delimited-continuations/" + stage + "/core/DelimitedContinuations.json").toPath()));
            for (var entry : List.of("resumeTwice", "resumedTail", "resumedJoin", "resumedScalar", "capturedCatch", "capturedMask")) {
                var p = new Program(language, CoreModules.reachable(source, entry), async, true); var function = org.graalvm.polyglot.Context.getCurrent().asValue(new EntryValue(p, entry, 1));
                for (int index = 0; index < inputs.size(); index++) {
                    assertEquals(nativeRows.get(index * entries.size() + entries.indexOf(entry)).longValue(), function.execute(inputs.get(index)).asLong(), stage + "/" + async + "/" + entry);
                    assertEquals(MaskingState.UNMASKED, Language.currentState().getMaskingState().get()); released(language);
                }
            }
        });
    }
}
