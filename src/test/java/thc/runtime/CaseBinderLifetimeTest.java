// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.FrameSlotKind;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeUtil;
import com.oracle.truffle.api.nodes.ExecutionSignature;
import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import thc.Language;
import static org.junit.jupiter.api.Assertions.*;
import static thc.Main.executionContext;

/** Observe actual Core case lowering, including its first compiled arm entry. */
class CaseBinderLifetimeTest {
    private static final Map<String, Object> LONG = Map.of("kind", "long", "primReps", List.of("IntRep"), "evaluated", true);
    private static final Map<String, Object> CLOSURE = Map.of("kind", "closure", "primReps", List.of("BoxedRep (Just Lifted)"), "evaluated", true);
    private static List<Object> node(Object... values) { return Arrays.asList(values); }
    private static Map<String, Object> binder(String id) { return Map.of("id", id, "name", id, "lifted", false, "rep", LONG); }
    private static List<Object> variable(String id) { return node("var", id, Map.of("rep", LONG)); }
    private static Evaluate scrutinee(Case selection) {
        return NodeUtil.findAllNodeInstances(selection, Evaluate.class).stream()
            .filter(node -> node.getParent() instanceof LocalBinding && node.getParent().getParent() == selection)
            .findFirst().orElseThrow();
    }
    private static Map<String, Object> module(boolean used, boolean defaults) {
        return module(used, defaults, false);
    }
    private static Map<String, Object> module(boolean used, boolean defaults, boolean deadScratch) {
        var body = used ? variable("seen") : node("lit", "int", "42", Map.of("rep", LONG));
        if (deadScratch) body = node("case", node("lit", "int", "0", Map.of("rep", LONG)), "scratch",
                List.of(node("default", null, List.of(), body)), Map.of("rep", LONG, "binder", binder("scratch")));
        var fallback = node("default", null, List.of(), body);
        var alternatives = defaults ? List.of(fallback) : List.of(node("lit", node("int", "0"), List.of(), body));
        var expression = node("case", variable("input"), "seen", alternatives,
                Map.of("rep", LONG, "binder", binder("seen")));
        return Map.of("bindings", List.of(Map.of("id", "entry", "name", "entry", "lifted", true,
                "rep", CLOSURE, "expr", node("lam", List.of(binder("input")), expression,
                        Map.of("rep", CLOSURE, "resultRep", LONG)))));
    }
    private static final class Observe extends Expr {
        @Child private Expr body;
        private final int slot;
        private final int[] observations;
        private final byte expectedTag;
        Observe(Expr body, int slot, int[] observations, FrameSlotKind expectedKind) {
            this.body = body; this.slot = slot; this.observations = observations; expectedTag = expectedKind.tag;
            setRepresentation(body.getRepresentation());
        }
        @Override public Object execute(VirtualFrame frame) { return executeLong(frame); }
        @Override public long executeLong(VirtualFrame frame) {
            observations[0]++;
            if (frame.getTag(slot) == expectedTag) observations[1]++;
            if (CompilerDirectives.inCompiledCode()) observations[2]++;
            return body.executeRequiredLong(frame);
        }
    }
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void unusedBinderIsReleasedBeforeTheSelectedCompiledArm(boolean defaults) throws Exception { check(false, defaults); }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void usedBinderRemainsAvailableToTheSelectedCompiledArm(boolean defaults) throws Exception { check(true, defaults); }

    @Test void scalarSiblingsReuseSlotsWithoutChangingCapturedValues() {
        var left = capturedScalar("left", 11, true);
        var right = capturedScalar("right", 22, false);
        var expression = node("case", variable("choice"), "selected", List.of(
            node("lit", node("int", "0"), List.of(), left),
            node("default", null, List.of(), right)),
            Map.of("rep", CLOSURE, "binder", binder("selected")));
        var entry = node("lam", List.of(binder("choice"), binder("outer")), expression,
            Map.of("rep", CLOSURE, "resultRep", CLOSURE));
        var data = Map.<String, Object>of("bindings", List.of(Map.of("id", "entry", "name", "entry",
            "lifted", true, "rep", CLOSURE, "expr", entry)));

        try (var context = executionContext(false)) {
            context.initialize("thc");
            context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var target = new Program(language, data, false).entryTarget("entry");
                var first = assertInstanceOf(Closure.class,
                    ScalarTestCalls.callScalarTestTarget(target, new Object[]{0L, 0L, 100L}));
                var second = assertInstanceOf(Closure.class,
                    ScalarTestCalls.callScalarTestTarget(target, new Object[]{0L, 1L, 200L}));
                assertEquals(111L, ScalarTestCalls.callScalarTestTarget(first.target,
                    new Object[]{0L, first.environment, 0L}));
                assertEquals(222L, ScalarTestCalls.callScalarTestTarget(second.target,
                    new Object[]{0L, second.environment, 0L}));

                var selection = NodeUtil.findFirstNodeInstance(target.getRootNode(), Case.class);
                var firstArm = NodeUtil.findFirstNodeInstance(selection.alternatives[0].getBody(), Case.class);
                var secondArm = NodeUtil.findFirstNodeInstance(selection.alternatives[1].getBody(), Case.class);
                assertNotEquals(selection.binderSlot, firstArm.binderSlot);
                assertEquals(firstArm.binderSlot, secondArm.binderSlot, "Disjoint scalar arms share a slot");
            } finally {
                context.leave();
            }
        }
    }

    private static List<Object> capturedScalar(String name, int value, boolean nested) {
        var result = node("app", node("prim", "+#"), List.of(variable("outer"), variable(name)),
            List.of(false, false), false, false, Map.of("rep", LONG));
        var closure = node("lam", List.of(binder(name + "Unused")), result,
            Map.of("rep", CLOSURE, "resultRep", LONG));
        var body = nested ? node("case", node("lit", "int", "99"), "extra",
            List.of(node("default", null, List.of(), closure)),
            Map.of("rep", CLOSURE, "binder", binder("extra"))) : closure;
        return node("case", node("lit", "int", Integer.toString(value)), name,
            List.of(node("default", null, List.of(), body)),
            Map.of("rep", CLOSURE, "binder", binder(name)));
    }

    @Test void ordinaryUninitializedLocalsStillReadNull() {
        var layout = new FrameLayout();
        int ordinary = layout.bind("ordinary");
        var frame = Truffle.getRuntime().createVirtualFrame(new Object[0], layout.build());
        assertNull(FrameAccess.read(frame, ordinary));
        assertNull(FrameAccess.read(frame, FrameLayout.TAIL_RESULT));
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void descriptorClearsStillRejectLiveInputAliases(boolean captureAlias) {
        try (var context = executionContext(false)) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var layout = FrameLayout.forProgramBody();
                int argument = layout.bind("argument", FrameSlotKind.Object);
                int capture = layout.bind("capture", FrameSlotKind.Object);
                layout.clearInitially(captureAlias ? capture : argument);
                var root = new FunctionRoot(language, layout.build(), "scratch validation",
                        new CaptureLayout(language, new boolean[]{false}), new int[]{capture},
                        new int[]{argument}, new int[]{0}, new Literal(42L), new Metrics(false));
                assertTrue(layout.usesIllegalDefault());
                assertThrows(IllegalArgumentException.class, () ->
                        root.configureInitialClears(layout.firstLocalSlot(), layout.initialClears(), layout.usesIllegalDefault()));
            } finally { context.leave(); }
        }
    }

    @ParameterizedTest @CsvSource({"false, false", "true, false", "true, true"})
    void caseBinderHasTheExpectedTagsOnFirstCompiledEntry(boolean used, boolean deadScratch) throws Exception {
        try (var context = executionContext(false)) {
            context.initialize("thc"); context.enter();
            try {
                Language language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var program = new Program(language, module(used, true, deadScratch), false);
                var target = program.entryTarget("entry");
                var cases = NodeUtil.findAllNodeInstances(target.getRootNode(), Case.class);
                assertEquals(deadScratch ? 2 : 1, cases.size());
                var selection = cases.getFirst();
                var scrutinee = scrutinee(selection);
                byte freshTag = (used && !deadScratch ? FrameSlotKind.Object : FrameSlotKind.Illegal).tag;
                int[] observations = new int[3], armObservations = new int[3];
                Expr observed = new Expr() {
                    @Child private Expr value = scrutinee;
                    @Override public Object execute(VirtualFrame frame) { return executeLong(frame); }
                    @Override public long executeLong(VirtualFrame frame) {
                        observations[0]++;
                        if (frame.getTag(selection.binderSlot) == freshTag) observations[1]++;
                        if (CompilerDirectives.inCompiledCode()) observations[2]++;
                        return value.executeRequiredLong(frame);
                    }
                };
                observed.setRepresentation(scrutinee.getRepresentation());
                scrutinee.replace(observed);
                Expr arm = selection.alternatives[0].getBody();
                arm.replace(new Observe(arm, selection.binderSlot, armObservations, used ? FrameSlotKind.Long : FrameSlotKind.Illegal));
                target.getClass().getMethod("compile", boolean.class).invoke(target, true);
                target.getClass().getMethod("waitForCompilation").invoke(target);
                assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target));
                assertEquals(used ? 7L : 42L, ScalarTestCalls.callScalarTestTarget(target, new Object[]{0L, 7L}));
                assertArrayEquals(new int[]{1, 1, 1}, observations);
                assertArrayEquals(new int[]{1, 1, 1}, armObservations);
                assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target));
            } finally { context.leave(); }
        }
    }

    private static final class ObserveFreshSlots extends Expr {
        @CompilerDirectives.CompilationFinal(dimensions = 1) private final int[] slots;
        private final int[] compiled;
        ObserveFreshSlots(int[] slots, int[] compiled) { this.slots = slots; this.compiled = compiled; }
        @Override public boolean needsCallState() { return false; }
        @Override public Object execute(VirtualFrame frame) { return executeLong(frame); }
        @com.oracle.truffle.api.nodes.ExplodeLoop
        @Override public long executeLong(VirtualFrame frame) {
            for (int slot : slots) if (frame.getTag(slot) != FrameSlotKind.Illegal.tag) return -1L;
            if (CompilerDirectives.inCompiledCode()) compiled[0]++;
            return 42L;
        }
    }
    @Test void wideFreshScratchStartsClearedOnItsFirstCompiledEntry() throws Exception {
        List<Object> body = node("lit", "int", "42", Map.of("rep", LONG));
        for (int i = 0; i < 27; i++) body = node("case", variable("input"), "dead" + i,
                List.of(node("default", null, List.of(), body)), Map.of("rep", LONG, "binder", binder("dead" + i)));
        var data = Map.<String, Object>of("bindings", List.of(Map.of("id", "entry", "name", "entry", "lifted", true,
                "rep", CLOSURE, "expr", node("lam", List.of(binder("input")), body,
                        Map.of("rep", CLOSURE, "resultRep", LONG)))));
        try (var context = executionContext(false)) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var program = new Program(language, data, false);
                var target = (com.oracle.truffle.runtime.OptimizedCallTarget) program.entryTarget("entry");
                var cases = NodeUtil.findAllNodeInstances(target.getRootNode(), Case.class);
                int[] slots = cases.stream().mapToInt(selection -> selection.binderSlot).distinct().toArray();
                assertEquals(27, slots.length);
                int[] compiled = {0};
                var observation = new ObserveFreshSlots(slots, compiled);
                observation.setRepresentation(((GuestRoot) target.getRootNode()).getScalarResultProof());
                cases.getFirst().replace(observation);
                // Inspect the fresh activation without executing or training its original body.
                target.compile(true); target.waitForCompilation(); assertTrue(target.isValidLastTier());
                assertEquals(42L, ScalarTestCalls.callScalarTestTarget(target, new Object[]{0L, 7L}));
                assertEquals(1, compiled[0]); assertTrue(target.isValidLastTier());
            } finally { context.leave(); }
        }
    }

    @Test void freshProgramFrameStartsWithBloomAndClearsSelfTransferScratch() throws Exception {
        var recurse = node("app", node("var", "entry"), List.of(variable("input")), List.of(false));
        var expression = node("case", variable("input"), "seen", List.of(
                node("lit", node("int", "0"), List.of(), node("lit", "int", "42", Map.of("rep", LONG))),
                node("default", null, List.of(), recurse)), Map.of("rep", LONG, "binder", binder("seen")));
        var data = Map.<String, Object>of("bindings", List.of(Map.of("id", "entry", "name", "entry", "lifted", true,
                "rep", CLOSURE, "expr", node("lam", List.of(binder("input")), expression,
                        Map.of("rep", CLOSURE, "resultRep", LONG)))));
        try (var context = executionContext(false)) {
            context.initialize("thc"); context.enter();
            try {
                Language language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var program = new Program(language, data, false);
                var target = program.entryTarget("entry");
                var root = (FunctionRoot) target.getRootNode();
                var descriptor = root.getFrameDescriptor();
                assertEquals("<TCO Bloom Filter>", descriptor.getSlotName(FrameLayout.BLOOM_FILTER));
                assertEquals("input", descriptor.getSlotName(FrameLayout.BLOOM_FILTER + 1));
                long expectedBloom = root.entryBloom(0L);
                var scratch = new java.util.ArrayList<Integer>();
                for (int slot = 0; slot < descriptor.getNumberOfSlots(); slot++)
                    if (String.valueOf(descriptor.getSlotName(slot)).startsWith("<self argument ")) scratch.add(slot);
                assertEquals(1, scratch.size(), "This original lowerer must select the self-transfer path");
                int scratchSlot = scratch.getFirst();
                var selection = NodeUtil.findAllNodeInstances(target.getRootNode(), Case.class).getFirst();
                int[] observations = new int[3];
                // Observe the already-lowered root's entry state without training
                // the unrelated multi-alternative condition profile in this body.
                Expr observed = new Expr() {
                    @Override public Object execute(VirtualFrame frame) { return executeLong(frame); }
                    @Override public long executeLong(VirtualFrame frame) {
                        if (frame.getTag(scratchSlot) == FrameSlotKind.Illegal.tag) observations[0]++;
                        if (frame.isLong(FrameLayout.BLOOM_FILTER) && frame.getLong(FrameLayout.BLOOM_FILTER) == expectedBloom) observations[1]++;
                        if (CompilerDirectives.inCompiledCode()) observations[2]++;
                        return 42L;
                    }
                };
                observed.setRepresentation(((GuestRoot) target.getRootNode()).getScalarResultProof());
                selection.replace(observed);
                target.getClass().getMethod("compile", boolean.class).invoke(target, true);
                target.getClass().getMethod("waitForCompilation").invoke(target);
                assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target));
                assertEquals(42L, ScalarTestCalls.callScalarTestTarget(target, new Object[]{0L, 0L}));
                assertArrayEquals(new int[]{1, 1, 1}, observations);
                assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target));
            } finally { context.leave(); }
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void resumedScrutineeCompletesItsWriteAndSelectionWithoutReplay(boolean used) {
        try (var context = executionContext(false)) {
            context.initialize("thc"); context.enter();
            try {
                Language language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var program = new Program(language, module(used, true), true);
                var target = program.entryTarget("entry");
                var selection = NodeUtil.findAllNodeInstances(target.getRootNode(), Case.class).getFirst();
                int[] entries = new int[1], observations = new int[3];
                var scrutinee = scrutinee(selection);
                Expr interrupted = new Expr() {
                    @Override public Object execute(VirtualFrame frame) { return executeLong(frame); }
                    @Override public long executeLong(VirtualFrame frame) {
                        entries[0]++;
                        throw new AstCapture(Unit.INSTANCE, MaskingState.MASKED_INTERRUPTIBLE);
                    }
                };
                interrupted.setRepresentation(scrutinee.getRepresentation());
                scrutinee.replace(interrupted);
                Expr body = selection.alternatives[0].getBody();
                body.replace(new Observe(body, selection.binderSlot, observations, FrameSlotKind.Illegal));
                var saved = assertInstanceOf(AstContinuation.class,
                        ScalarTestCalls.callScalarTestTarget(target, new Object[]{0L, 7L}));
                assertEquals(1, entries[0]);
                assertEquals(0, observations[0]);
                assertEquals(used ? 7L : 42L, saved.continueWith(7L));
                assertEquals(1, entries[0], "The interrupted scrutinee cannot replay");
                assertEquals(1, observations[0]);
                assertEquals(used ? 0 : 1, observations[1]);
                assertSame(target, program.entryTarget("entry"));
                assertThrows(RuntimeFault.class, () -> saved.continueWith(7L));
                assertEquals(MaskingState.UNMASKED, SynchronousMasking.current(target.getRootNode()));
            } finally { context.leave(); }
        }
    }

    private static final class ResultLifetime {
        WeakReference<Object> produced;
        int calls;
    }
    private static boolean collect(WeakReference<Object> reference) {
        long deadline = System.nanoTime() + 3_000_000_000L;
        do {
            System.gc();
            if (reference.refersTo(null)) return true;
            try { Thread.sleep(10); }
            catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
        } while (System.nanoTime() < deadline);
        return reference.refersTo(null);
    }
    private static WeakReference<Object> deadWitness() { return new WeakReference<>(new Object()); }

    @ParameterizedTest @ValueSource(strings = {"ast", "bytecode"})
    void unusedTupleReferenceDiesWhileTheSelectedBodyStillRuns(String backend) { unusedResultReference(backend, false); }

    @ParameterizedTest @ValueSource(strings = {"ast", "bytecode"})
    void unusedConstructorReferenceDiesWhileTheSelectedBodyStillRuns(String backend) { unusedResultReference(backend, true); }

    @ParameterizedTest @ValueSource(strings = {"ast", "bytecode"})
    void unmatchedConstructorReferenceDiesWhileDefaultBodyStillRuns(String backend) { unusedResultReference(backend, true, true); }

    private static void unusedResultReference(String backend, boolean boxed) { unusedResultReference(backend, boxed, false); }
    private static void unusedResultReference(String backend, boolean boxed, boolean unmatched) {
        var object = Map.<String, Object>of("kind", "object", "primReps", List.of("BoxedRep (Just Lifted)"), "evaluated", false);
        var pair = boxed ? Map.<String, Object>of("kind", "data", "primReps", List.of("BoxedRep (Just Lifted)"), "evaluated", true)
                : Map.<String, Object>of("kind", "unknown", "aggregate", "unboxed-tuple",
                        "components", List.of(LONG, object), "primReps", List.of("IntRep", "BoxedRep (Just Lifted)"), "evaluated", true);
        var payload = Map.of("id", "unused", "name", "unused", "lifted", true, "rep", object);
        var produce = node("app", node("var", "produce", Map.of("rep", CLOSURE)),
                List.of(node("lit", "int", "41", Map.of("rep", LONG))), List.of(false), false, false, Map.of("rep", pair));
        var observe = node("app", node("var", "observe", Map.of("rep", CLOSURE)),
                List.of(unmatched ? node("lit", "int", "41", Map.of("rep", LONG)) : variable("number")), List.of(false), false, false, Map.of("rep", LONG));
        var alternatives = unmatched ? List.of(node("data", "Other", List.of(), node("lit", "int", "0", Map.of("rep", LONG))),
                node("default", null, List.of(), observe)) : List.of(node("data", "Pair", List.of("number", "unused"), observe,
                Map.of("binders", List.of(binder("number"), payload))));
        var expression = node("case", produce, "pair", alternatives,
                Map.of("rep", LONG, "binder", Map.of("id", "pair", "lifted", boxed, "rep", pair)));
        var parameters = List.of(Map.of("id", "produce", "lifted", true, "rep", CLOSURE),
                Map.of("id", "observe", "lifted", true, "rep", CLOSURE));
        var module = Map.<String, Object>of("constructors", List.of(Map.of("id", "Pair", "name", "Pair", "kind", boxed ? "boxed" : "unboxed-tuple", "arity", 2,
                "fieldReps", List.of(List.of("IntRep"), List.of("BoxedRep (Just Lifted)")),
                "fieldLifted", List.of(false, true), "strictFields", List.of(false, false)),
                Map.of("id", "Other", "name", "Other", "kind", "boxed", "arity", 0, "fieldReps", List.of(), "fieldLifted", List.of(), "strictFields", List.of())),
                "bindings", List.of(Map.of("id", "entry", "name", "entry", "lifted", true, "rep", CLOSURE,
                        "expr", node("lam", parameters, expression, Map.of("rep", CLOSURE, "resultRep", LONG)))));
        // Interpreter frames expose the lifetime contract without depending on a
        // compiler eliminating the unused reference store as an optimization.
        try (var context = org.graalvm.polyglot.Context.newBuilder("thc").allowExperimentalOptions(true)
                .option("engine.Compilation", "false").build()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var observation = new ResultLifetime();
                ExecutableProgram program = backend.equals("ast") ? new Program(language, module) : new BytecodeProgram(language, module);
                var shape = boxed ? null : new TupleShape(CoreRepresentations.parse(pair), language);
                var constructor = boxed ? program.constructorLayout("Pair") : null;
                var producer = new GuestRoot(language, new FrameLayout().build()) {
                    {
                        if (boxed) configureScalarResult(CoreRepresentations.parse(pair)); else configureTupleResult(shape);
                        configureEntry(new boolean[]{false}, false);
                    }
                    @Override public long bloom(VirtualFrame frame) { return 0L; }
                    @Override public Object execute(VirtualFrame frame) {
                        observation.calls++;
                        Object key = new Object();
                        observation.produced = new WeakReference<>(key);
                        if (boxed) return constructor.create(new Object[]{frame.getArguments()[1], key});
                        var result = shape.getLayout().create();
                        shape.getLayout().setLong(result, 0, (long) frame.getArguments()[1]);
                        shape.getLayout().setObject(result, 1, key);
                        return result;
                    }
                };
                var observer = new GuestRoot(language, new FrameLayout().build()) {
                    { configureEntry(new boolean[]{false}, false); configureScalarResult(CoreRepresentations.parse(LONG)); }
                    @Override public long bloom(VirtualFrame frame) { return 0L; }
                    @Override public Object execute(VirtualFrame frame) {
                        Object live = new Object();
                        var liveReference = new WeakReference<>(live);
                        var witness = deadWitness();
                        try {
                            boolean collected = collect(observation.produced);
                            assertTrue(witness.refersTo(null), "Independent dead key must establish collector progress");
                            assertTrue(liveReference.refersTo(live), "A Java-held key remains live");
                            assertTrue(collected, "Unused " + (boxed ? "constructor" : "tuple") + " payload must die before its case body returns");
                            return (long) frame.getArguments()[1] + 1;
                        } finally { Reference.reachabilityFence(live); }
                    }
                };
                assertEquals(42L, ScalarTestCalls.callScalarTestTarget(program.entryTarget("entry"),
                        new Object[]{0L, new Closure(null, 1, producer.getCallTarget()), new Closure(null, 1, observer.getCallTarget())}));
                assertEquals(1, observation.calls, "The scrutinee executes exactly once");
            } finally { context.leave(); }
        }
    }

    @ParameterizedTest @ValueSource(strings = {"ast", "bytecode"})
    void liveNestedTupleComponentAndWholeCaseAliasesKeepTheirValues(String backend) {
        var object = Map.<String, Object>of("kind", "object", "primReps", List.of("BoxedRep (Just Lifted)"), "evaluated", false);
        var pair = Map.<String, Object>of("kind", "unknown", "aggregate", "unboxed-tuple", "components", List.of(object, LONG),
                "primReps", List.of("BoxedRep (Just Lifted)", "IntRep"), "evaluated", true);
        for (boolean boxed : new boolean[]{false, true}) {
            var outer = boxed ? Map.<String, Object>of("kind", "data", "primReps", List.of("BoxedRep (Just Lifted)"), "evaluated", true)
                    : Map.<String, Object>of("kind", "unknown", "aggregate", "unboxed-tuple", "components", List.of(pair),
                            "primReps", pair.get("primReps"), "evaluated", true);
            var key = node("var", "key", Map.of("rep", object));
            var packed = node("app", node("con", "Pair", 2), List.of(key, node("lit", "int", "41", Map.of("rep", LONG))),
                    List.of(true, false), false, false, Map.of("rep", pair));
            var scrutinee = node("app", node("con", "Outer", 1), List.of(packed), List.of(false), false, false, Map.of("rep", outer));
            var component = node("var", "component", Map.of("rep", pair));
            var alternative = node("data", "Outer", List.of("component"), component,
                    Map.of("binders", List.of(Map.of("id", "component", "lifted", false, "rep", pair))));
            try (var context = executionContext()) {
                context.initialize("thc"); context.enter();
                try {
                    var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                    for (boolean whole : new boolean[]{false, true}) {
                        var body = whole ? node("case", node("var", "whole", Map.of("rep", outer)), "inner", List.of(alternative),
                                Map.of("rep", pair, "binder", Map.of("id", "inner", "lifted", boxed, "rep", outer))) : component;
                        var arm = whole ? node("default", null, List.of(), body) : alternative;
                        var expression = node("case", scrutinee, "whole", List.of(arm),
                                Map.of("rep", pair, "binder", Map.of("id", "whole", "lifted", boxed, "rep", outer)));
                        var module = Map.<String, Object>of("constructors", List.of(
                                Map.of("id", "Pair", "kind", "unboxed-tuple", "arity", 2), Map.of("id", "Outer", "name", "Outer", "kind", boxed ? "boxed" : "unboxed-tuple", "arity", 1,
                                        "fieldTypes", List.of(pair), "fieldReps", List.of(pair.get("primReps")),
                                        "fieldLifted", List.of(false), "strictFields", List.of(false))),
                                "bindings", List.of(Map.of("id", "entry", "lifted", true, "rep", CLOSURE,
                                        "expr", node("lam", List.of(Map.of("id", "key", "lifted", true, "rep", object)), expression,
                                                Map.of("rep", CLOSURE, "resultRep", pair)))));
                        ExecutableProgram program = backend.equals("ast") ? new Program(language, module) : new BytecodeProgram(language, module);
                        Object marker = new Object();
                        var shape = new TupleShape(CoreRepresentations.parse(pair), language);
                        var result = TupleResults.ownedTupleResult(Calls.target(program.entryTarget("entry"), new Object[]{0L, marker}), shape);
                        assertSame(marker, shape.getLayout().getObject(result, 0));
                        assertEquals(41L, shape.getLayout().getLong(result, 1));
                    }
                } finally { context.leave(); }
            }
        }
    }

    private static void check(boolean used, boolean defaults) throws Exception {
        try (var context = executionContext(false)) {
            context.initialize("thc"); context.enter();
            try {
                Language language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var program = new Program(language, module(used, defaults), false);
                var target = program.entryTarget("entry");
                var cases = NodeUtil.findAllNodeInstances(target.getRootNode(), Case.class);
                assertEquals(1, cases.size());
                var selection = cases.getFirst();
                int[] observations = new int[3];
                for (Alternative arm : selection.alternatives) {
                    Expr body = arm.getBody();
                    body.replace(new Observe(body, selection.binderSlot, observations, FrameSlotKind.Illegal));
                }
                target.getClass().getMethod("compile", boolean.class).invoke(target, true);
                target.getClass().getMethod("waitForCompilation").invoke(target);
                assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target));
                for (long input : new long[]{0L, defaults ? 7L : 0L})
                    assertEquals(used ? input : 42L, ScalarTestCalls.callScalarTestTarget(target, new Object[]{0L, input}));
                assertEquals(2, observations[0]);
                assertEquals(used ? 0 : 2, observations[1], "Only a binder unused by every arm may be released");
                assertEquals(2, observations[2], "Both original entries must execute installed code without training");
                assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target));
                assertSame(target, program.entryTarget("entry"));
            } finally { context.leave(); }
        }
    }

    private static final class MatchedArm extends Expr {
        private final int slot, add;
        private final boolean integer;
        private final int[] compiled;
        MatchedArm(int slot, int add, boolean integer, int[] compiled) {
            this.slot = slot; this.add = add; this.integer = integer; this.compiled = compiled;
            setRepresentation(new CoreRepresentation(CoreKind.LONG, true, true,
                List.of(integer ? "Int16Rep" : "IntRep")));
        }
        @Override public Object execute(VirtualFrame frame) { throw new AssertionError("typed arm used generic dispatch"); }
        private long value(VirtualFrame frame) {
            if (CompilerDirectives.inCompiledCode()) compiled[0]++;
            return slot < 0 ? add : frame.getLong(slot) + add;
        }
        @Override public long executeLong(VirtualFrame frame) { if (integer) throw new AssertionError(); return value(frame); }
        @Override public int executeInt(VirtualFrame frame) { if (!integer) throw new AssertionError(); return (int) value(frame); }
    }
    private static final class MatchedCaseRoot extends GuestRoot {
        @Child private Case body;
        private final boolean integer;
        MatchedCaseRoot(Language language, com.oracle.truffle.api.frame.FrameDescriptor descriptor,
                        Case body, boolean integer) {
            super(language, descriptor); this.body = body; this.integer = integer;
        }
        @Override public Object execute(VirtualFrame frame) {
            try {
                if (integer) return body.executeRequiredInt(frame);
                return body.executeRequiredLong(frame);
            } catch (AstCapture cut) { return cut.freeze(this, frame.materialize()); }
        }
        @Override public long bloom(VirtualFrame frame) { return 0L; }
        @Override public String getName() { return "matchedCase"; }
        @Override public String toString() { return getName(); }
    }
    @Test void matchedDataArmsKeepTypedDispatchOnFirstCompiledEntries() throws Exception {
        try (var context = org.graalvm.polyglot.Context.newBuilder("thc").allowExperimentalOptions(true)
                .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
                .option("compiler.MaximumGraalGraphSize", "1000").build()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var left = new DataLayout(language, "left", "left", new String[]{"IntRep"});
                var right = new DataLayout(language, "right", "right", new String[]{"IntRep"});
                var other = new DataLayout(language, "other", "other", new String[]{});
                for (boolean typed : new boolean[]{false, true}) for (boolean integer : new boolean[]{false, true}) {
                    var layout = new FrameLayout(); int binder = layout.bind("whole", FrameSlotKind.Object);
                    int field = layout.bind("field", FrameSlotKind.Long); int[] compiled = {0};
                    var proof = new CoreRepresentation(CoreKind.DATA, true, true);
                    Expr input = new Expr() {
                        { setRepresentation(proof); }
                        @Override public Object execute(VirtualFrame frame) { return frame.getArguments()[1]; }
                    };
                    Alternative[] arms = {
                        new Alternative(Alternative.DATA_ALTERNATIVE, left, new int[]{field}, new MatchedArm(field, 1, integer, compiled), new int[0][], false),
                        new Alternative(Alternative.DATA_ALTERNATIVE, right, new int[]{field}, new MatchedArm(field, 2, integer, compiled), new int[0][], false),
                        new Alternative(Alternative.DEFAULT_ALTERNATIVE, null, new int[0], new MatchedArm(-1, -1, integer, compiled), new int[0][], false)};
                    Case selection = typed ? new DataCase(input, binder, arms, new Metrics(false), proof, false)
                        : new Case(input, binder, arms, new Metrics(false));
                    var target = new MatchedCaseRoot(language, layout.build(), selection.discardUnusedBinder(), integer).getCallTarget();
                    target.getClass().getMethod("compile", boolean.class).invoke(target, true);
                    assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target));
                    Object[] values = {left.createLong(40), right.createLong(40), other.create(new Object[0])};
                    int[] expected = {41, 42, -1};
                    for (int i = 0; i < values.length; i++) {
                        Object result = Calls.target(target, new Object[]{0L, values[i]});
                        if (integer) assertEquals(expected[i], assertInstanceOf(Integer.class, result));
                        else assertEquals((long) expected[i], assertInstanceOf(Long.class, result));
                        assertEquals(i + 1, compiled[0]);
                        assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target));
                    }
                }
            } finally { context.leave(); }
        }
    }

    @CompilerDirectives.TruffleBoundary
    private static void recordCaptureEntry(int[] compiled, boolean installed) {
        if (installed) compiled[0]++;
    }

    @Test void firstCompiledDataCaptureKeepsItsTargetAndSavedWrite() throws Exception {
        try (var context = executionContext(false)) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var layout = new FrameLayout(); int binder = layout.bind("dead", FrameSlotKind.Object);
                int field = layout.bind("field", FrameSlotKind.Long); int[] compiled = {0};
                var data = new DataLayout(language, "captured", "captured", new String[]{"IntRep"});
                var proof = new CoreRepresentation(CoreKind.DATA, true, true);
                var marker = new Object(); var cut = new AstCapture(marker, MaskingState.UNMASKED);
                AstCapture[] observed = {null};
                var direct = data.createLong(42);
                Expr input = new Expr() {
                    { setRepresentation(proof); }
                    @Override public Object execute(VirtualFrame frame) { return executeDataValue(frame); }
                    @Override public DataValue executeDataValue(VirtualFrame frame) {
                        recordCaptureEntry(compiled, CompilerDirectives.inCompiledCode());
                        if (frame.getArguments()[1] == cut) throw cut;
                        return direct;
                    }
                };
                Expr body = new Expr() {
                    @Override public Object execute(VirtualFrame frame) {
                        if (frame.getTag(binder) != FrameSlotKind.Illegal.tag) return -1L;
                        return frame.getLong(field);
                    }
                };
                var selection = new Case(input, binder, new Alternative[]{new Alternative(Alternative.DATA_ALTERNATIVE,
                        data, new int[]{field}, body, new int[0][], false)}, new Metrics(false), proof, false).discardUnusedBinder();
                var root = new GuestRoot(language, layout.build()) {
                    @Child private Case value = selection;
                    @Override protected boolean requiresMaterializableFrame() { return true; }
                    @Override protected ExecutionSignature prepareForAOT() { return ExecutionSignature.GENERIC; }
                    @Override public long bloom(VirtualFrame frame) { return 0L; }
                    @Override public Object execute(VirtualFrame frame) {
                        try { return value.execute(frame); }
                        catch (AstCapture caught) { observed[0] = caught; return caught.freeze(this, frame.materialize()); }
                    }
                };
                var target = (com.oracle.truffle.runtime.OptimizedCallTarget) root.getCallTarget();
                assertTrue(target.prepareForAOT());
                target.compile(true); target.waitForCompilation();
                assertFalse(target.wasExecuted()); assertTrue(target.isValidLastTier());
                var saved = assertInstanceOf(AstContinuation.class, Calls.target(target, new Object[]{0L, cut}));
                assertEquals(1, compiled[0]); assertTrue(target.isValidLastTier());
                assertSame(cut, observed[0]);
                assertSame(marker, saved.getYielded()); assertSame(root, saved.getSourceRoot());
                assertEquals(42L, saved.continueWith(data.createLong(42)));
                assertEquals(1, compiled[0]); assertTrue(target.isValidLastTier());
                assertThrows(RuntimeFault.class, () -> saved.continueWith(data.createLong(42)));
            } finally { context.leave(); }
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void unusedDataBinderDirectAndCapturedPathsKeepOneEvaluation(boolean extracted) throws Exception {
        try (var context = org.graalvm.polyglot.Context.newBuilder("thc").allowExperimentalOptions(true)
                .option("engine.Compilation", "false").build()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var data = new DataLayout(language, "captured", "captured", new String[]{"IntRep"});
                for (int mode = 0; mode < 3; mode++) {
                    final int operation = mode; int[] calls = {0}, bodies = {0};
                    var layout = new FrameLayout(); int binder = layout.bind("dead", FrameSlotKind.Object);
                    int field = layout.bind("field", FrameSlotKind.Long);
                    var proof = new CoreRepresentation(CoreKind.DATA, true, true);
                    Expr input = new Expr() {
                        { setRepresentation(new CoreRepresentation(CoreKind.DATA, !extracted, true)); }
                        @Override public Object execute(VirtualFrame frame) {
                            calls[0]++;
                            if (operation == 1) throw new AstCapture(Unit.INSTANCE, SynchronousMasking.current(this));
                            if (operation == 2) throw new DelimitedCut(new PromptTag(Language.currentState()), null, null,
                                SynchronousMasking.current(this), this);
                            return frame.getArguments()[1];
                        }
                    };
                    Expr body = new Expr() {
                        { setRepresentation(new CoreRepresentation(CoreKind.LONG, true, true, List.of("IntRep"))); }
                        @Override public Object execute(VirtualFrame frame) { return executeLong(frame); }
                        @Override public long executeLong(VirtualFrame frame) {
                            assertEquals(FrameSlotKind.Illegal.tag, frame.getTag(binder)); bodies[0]++;
                            return frame.getLong(field);
                        }
                    };
                    var region = new AstSameFrameArm(body);
                    var arm = new Alternative(Alternative.DATA_ALTERNATIVE, data, new int[]{field}, region, new int[0][], false);
                    var selection = new Case(input, binder, new Alternative[]{arm}, new Metrics(false), proof, true).discardUnusedBinder();
                    var root = new MatchedCaseRoot(language, layout.build(), selection, false);
                    if (extracted) {
                        root.getCallTarget();
                        assertTrue(AstSameFrameArm.extractFresh(root));
                        var targetField = AstSameFrameArm.class.getDeclaredField("targets"); targetField.setAccessible(true);
                        assertNull(targetField.get(region), "Demand must precede the alternative body");
                        var demand = NodeUtil.findFirstNodeInstance(selection.scrutineeBinding(), AstSameFrameArm.class);
                        assertNotNull(demand); assertNotNull(targetField.get(demand));
                        assertFalse(demand.isInstrumentable());
                    }
                    var value = data.createLong(42);
                    if (mode == 0) assertEquals(42L, Calls.target(root.getCallTarget(), new Object[]{0L, value}));
                    else if (mode == 1) {
                        var saved = assertInstanceOf(AstContinuation.class, Calls.target(root.getCallTarget(), new Object[]{0L, value}));
                        assertEquals(0, bodies[0]); assertEquals(42L, saved.continueWith(value));
                        assertThrows(RuntimeFault.class, () -> saved.continueWith(value));
                    } else {
                        var cut = assertThrows(DelimitedCut.class, () -> Calls.target(root.getCallTarget(), new Object[]{0L, value}));
                        assertEquals(2, cut.getFrames().size(), "Saved binder write precedes case selection");
                        for (int invocation = 0; invocation < 2; invocation++) {
                            var copy = Truffle.getRuntime().createMaterializedFrame(new Object[]{0L}, root.getFrameDescriptor());
                            Object answer = value;
                            for (var saved : cut.getFrames()) answer = saved.getStep().resume(copy, new DelimitedResume(answer), MaskingState.UNMASKED, null);
                            assertEquals(42L, answer);
                        }
                    }
                    assertEquals(1, calls[0]); assertEquals(mode == 2 ? 2 : 1, bodies[0]);
                }
            } finally { context.leave(); }
        }
    }

    @Test void nullableTypedCaseResultDoesNotReuseAnOlderBinder() {
        try (var context = org.graalvm.polyglot.Context.newBuilder("thc").allowExperimentalOptions(true)
                .option("engine.Compilation", "false").build()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var data = new DataLayout(language, "stale", "stale", new String[]{});
                var stale = data.create(new Object[0]); var layout = new FrameLayout();
                int binder = layout.bind("dead", FrameSlotKind.Object);
                var proof = new CoreRepresentation(CoreKind.DATA, true, true);
                Expr input = new Expr() {
                    { setRepresentation(proof); }
                    @Override public Object execute(VirtualFrame frame) { return null; }
                    @Override public DataValue executeDataValue(VirtualFrame frame) { return null; }
                };
                var arms = new Alternative[]{
                    new Alternative(Alternative.DATA_ALTERNATIVE, data, new int[0], new Literal(11L), new int[0][], false),
                    new Alternative(Alternative.DEFAULT_ALTERNATIVE, null, new int[0], new Literal(42L), new int[0][], false)};
                var selection = new Case(input, binder, arms, new Metrics(false), proof, false).discardUnusedBinder();
                var root = new GuestRoot(language, layout.build()) {
                    @Child private Case body = selection;
                    @Override public long bloom(VirtualFrame frame) { return 0L; }
                    @Override public Object execute(VirtualFrame frame) {
                        frame.setObject(binder, stale);
                        return body.executeRequiredLong(frame);
                    }
                };
                assertEquals(42L, Calls.target(root.getCallTarget(), new Object[]{0L}));
            } finally { context.leave(); }
        }
    }
}
