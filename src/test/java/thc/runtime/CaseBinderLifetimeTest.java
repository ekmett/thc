// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.FrameSlotKind;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeUtil;
import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
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
        var body = used ? variable("seen") : node("lit", "int", "42", Map.of("rep", LONG));
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
        Observe(Expr body, int slot, int[] observations) {
            this.body = body; this.slot = slot; this.observations = observations;
            setRepresentation(body.getRepresentation());
        }
        @Override public Object execute(VirtualFrame frame) { return executeLong(frame); }
        @Override public long executeLong(VirtualFrame frame) {
            observations[0]++;
            if (frame.getTag(slot) == FrameSlotKind.Illegal.tag) observations[1]++;
            if (CompilerDirectives.inCompiledCode()) observations[2]++;
            return body.executeRequiredLong(frame);
        }
    }
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void unusedBinderIsReleasedBeforeTheSelectedCompiledArm(boolean defaults) throws Exception { check(false, defaults); }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void usedBinderRemainsAvailableToTheSelectedCompiledArm(boolean defaults) throws Exception { check(true, defaults); }

    @Test void ordinaryUninitializedLocalsStillReadNull() {
        var layout = new FrameLayout();
        int ordinary = layout.bind("ordinary");
        var frame = Truffle.getRuntime().createVirtualFrame(new Object[0], layout.build());
        assertNull(FrameAccess.read(frame, ordinary));
        assertNull(FrameAccess.read(frame, FrameLayout.TAIL_RESULT));
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void onlyDeadCaseBinderStartsClearedAtFreshCompiledEntry(boolean used) throws Exception {
        try (var context = executionContext(false)) {
            context.initialize("thc"); context.enter();
            try {
                Language language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var program = new Program(language, module(used, true), false);
                var target = program.entryTarget("entry");
                var selection = NodeUtil.findAllNodeInstances(target.getRootNode(), Case.class).getFirst();
                var scrutinee = scrutinee(selection);
                int[] observations = new int[3];
                Expr observed = new Expr() {
                    @Child private Expr value = scrutinee;
                    @Override public Object execute(VirtualFrame frame) { return executeLong(frame); }
                    @Override public long executeLong(VirtualFrame frame) {
                        observations[0]++;
                        if (frame.getTag(selection.binderSlot) == (used ? FrameSlotKind.Object.tag : FrameSlotKind.Illegal.tag)) observations[1]++;
                        if (CompilerDirectives.inCompiledCode()) observations[2]++;
                        return value.executeRequiredLong(frame);
                    }
                };
                observed.setRepresentation(scrutinee.getRepresentation());
                scrutinee.replace(observed);
                target.getClass().getMethod("compile", boolean.class).invoke(target, true);
                target.getClass().getMethod("waitForCompilation").invoke(target);
                assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target));
                assertEquals(used ? 7L : 42L, ScalarTestCalls.callScalarTestTarget(target, new Object[]{0L, 7L}));
                assertArrayEquals(new int[]{1, 1, 1}, observations);
                assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target));
            } finally { context.leave(); }
        }
    }

    @Test void freshSelfTransferScratchIsDeadWithoutChangingReservedNullSlots() throws Exception {
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
                var descriptor = target.getRootNode().getFrameDescriptor();
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
                        if (frame.isObject(FrameLayout.TAIL_RESULT) && frame.getObject(FrameLayout.TAIL_RESULT) == null) observations[1]++;
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
                body.replace(new Observe(body, selection.binderSlot, observations));
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

    private static void unusedResultReference(String backend, boolean boxed) {
        var object = Map.<String, Object>of("kind", "object", "primReps", List.of("BoxedRep (Just Lifted)"), "evaluated", false);
        var pair = boxed ? Map.<String, Object>of("kind", "data", "primReps", List.of("BoxedRep (Just Lifted)"), "evaluated", true)
                : Map.<String, Object>of("kind", "unknown", "aggregate", "unboxed-tuple",
                        "components", List.of(LONG, object), "primReps", List.of("IntRep", "BoxedRep (Just Lifted)"), "evaluated", true);
        var payload = Map.of("id", "unused", "name", "unused", "lifted", true, "rep", object);
        var produce = node("app", node("var", "produce", Map.of("rep", CLOSURE)),
                List.of(node("lit", "int", "41", Map.of("rep", LONG))), List.of(false), false, false, Map.of("rep", pair));
        var observe = node("app", node("var", "observe", Map.of("rep", CLOSURE)),
                List.of(variable("number")), List.of(false), false, false, Map.of("rep", LONG));
        var expression = node("case", produce, "pair", List.of(node("data", "Pair", List.of("number", "unused"), observe,
                Map.of("binders", List.of(binder("number"), payload)))),
                Map.of("rep", LONG, "binder", Map.of("id", "pair", "lifted", boxed, "rep", pair)));
        var parameters = List.of(Map.of("id", "produce", "lifted", true, "rep", CLOSURE),
                Map.of("id", "observe", "lifted", true, "rep", CLOSURE));
        var module = Map.<String, Object>of("constructors", List.of(Map.of("id", "Pair", "name", "Pair", "kind", boxed ? "boxed" : "unboxed-tuple", "arity", 2,
                "fieldReps", List.of(List.of("IntRep"), List.of("BoxedRep (Just Lifted)")),
                "fieldLifted", List.of(false, true), "strictFields", List.of(false, false))),
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
                    body.replace(new Observe(body, selection.binderSlot, observations));
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
}
