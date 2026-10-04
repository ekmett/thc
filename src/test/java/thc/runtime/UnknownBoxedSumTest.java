// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.FrameSlotKind;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;
import java.util.*;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import thc.Language;
import static org.junit.jupiter.api.Assertions.*;
import static thc.runtime.RepresentationTestSupport.*;

/** Unknown levity is a traced pointer, not a native lifted slot or a WHNF proof. */
class UnknownBoxedSumTest {
    private static final String LIFTED = "BoxedRep (Just Lifted)", UNLIFTED = "BoxedRep (Just Unlifted)";
    private static final Map<String,Object> UNKNOWN = scalar("object", "BoxedRep Nothing", false);
    private static final Map<String,Object> L = scalar("object", LIFTED, false);
    private static final Map<String,Object> U = scalar("object", UNLIFTED, true);
    private static final Map<String,Object> INT = scalar("long", "IntRep", true);
    private static final Map<String,Object> CLOSURE = scalar("closure", LIFTED, true);
    private static final Map<String,Object> DATA = scalar("data", LIFTED, true);
    private static final Map<String,Object> SUM = sum(list(UNKNOWN, INT), null, null);
    private static Map<String,Object> scalar(String kind, String rep, boolean evaluated) {
        return map("kind", kind, "primReps", list(rep), "evaluated", evaluated);
    }
    @SafeVarargs private static Map<String,Object> tuple(Map<String,Object>... fields) {
        List<Object> reps = new ArrayList<>();
        for (var field : fields) {
            if (field.get("primReps") == null) { reps = null; break; }
            reps.addAll((List<?>) field.get("primReps"));
        }
        return map("kind", "unknown", "aggregate", "unboxed-tuple", "components", Arrays.asList(fields),
            "primReps", reps, "evaluated", true);
    }
    private static Map<String,Object> sum(List<Map<String,Object>> alternatives, List<String> reps, List<List<Integer>> slots) {
        return map("kind", "unknown", "aggregate", "unboxed-sum", "alternatives", alternatives,
            "primReps", reps, "tagSlot", 0, "alternativeSlots", slots, "evaluated", true);
    }
    @Test void mixedRepeatedPointersShareJvmFieldsWithoutInventingNativeSlots() {
        var unknown = CoreRepresentations.parse(sum(list(tuple(UNKNOWN, L), tuple(U, UNKNOWN)), null, null));
        var lifted = CoreRepresentations.parse(sum(list(tuple(L, L), tuple(U, L)),
            list("WordRep", LIFTED, LIFTED, UNLIFTED), list(list(1, 2), list(3, 1))));
        var unlifted = CoreRepresentations.parse(sum(list(tuple(U, L), tuple(U, U)),
            list("WordRep", LIFTED, UNLIFTED, UNLIFTED), list(list(2, 1), list(2, 3))));
        for (var proof : list(unknown, lifted, unlifted)) {
            var transport = SumShape.transport(proof);
            assertEquals(3, transport.getFields().size());
            assertEquals(list(list(1, 2), list(1, 2)), transport.getProjections());
            for (var field : transport.getFields().subList(1, 3)) {
                assertEquals(CoreKind.OBJECT, field.getKind());
                assertFalse(field.getEvaluated(), "A common pointer slot supplies no WHNF evidence");
            }
        }
        assertNull(unknown.getPrimReps()); assertNull(unknown.getAlternativeSlots());
        assertTrue(TupleShape.compatible(unknown, lifted)); assertTrue(TupleShape.compatible(lifted, unknown));
        assertTrue(TupleShape.compatible(unknown, unlifted)); assertFalse(TupleShape.compatible(lifted, unlifted));
        for (var concrete : list(lifted, unlifted)) {
            for (var refined : list(unknown.refine(concrete), concrete.refine(unknown))) {
                SumShape.validate(refined);
                assertEquals(concrete.getPrimReps(), refined.getPrimReps());
                assertEquals(concrete.getAlternativeSlots(), refined.getAlternativeSlots());
            }
        }
        assertTrue(CoreRepresentations.parse(tuple(SUM, INT)).isTuple());
        assertThrows(RuntimeFault.class, () -> CoreRepresentations.parse(sum(list(UNKNOWN, INT),
            list("WordRep", LIFTED, "WordRep"), list(list(1), list(2)))));
        assertThrows(UnsupportedCore.class, () -> CoreRepresentations.parse(sum(
            list(map("kind", "unknown", "primReps", null, "evaluated", false), INT), null, null)));
        assertThrows(UnsupportedCore.class, () -> CoreRepresentations.parse(sum(list(L, INT), null, null)));
    }
    @Test void inactiveReferencesAndFailedPayloadsReleaseTheSameTransport() throws Exception {
        try (var context = Context.newBuilder("thc").build()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var unknown = CoreRepresentations.parse(sum(list(tuple(UNKNOWN, UNKNOWN), INT), null, null));
                var concrete = CoreRepresentations.parse(sum(list(tuple(L, U), INT),
                    list("WordRep", LIFTED, UNLIFTED, "WordRep"), list(list(1, 2), list(3))));
                var shape = new TupleShape(unknown, language);
                var concreteShape = new TupleShape(concrete, language);
                assertTrue(shape.matches(concreteShape)); assertTrue(concreteShape.matches(shape));
                assertSame(shape.getLayout(), concreteShape.getLayout());
                var unknownArguments = ArgumentLayout.fromProofs(list(unknown));
                var concreteArguments = ArgumentLayout.fromProofs(list(concrete));
                ArgumentLayout.validate(unknownArguments, 0, concreteArguments, 0, 1);
                ArgumentLayout.validate(concreteArguments, 0, unknownArguments, 0, 1);
                var builder = FrameDescriptor.newBuilder();
                int[] slots = new int[shape.getWidth()];
                for (int i = 0; i < slots.length; i++) slots[i] = builder.addSlot(FrameSlotKind.Illegal, "sum " + i, null);
                var frame = Truffle.getRuntime().createVirtualFrame(new Object[0], builder.build());
                var poison = new Thunk(new RootNode(language) {
                    @Override public Object execute(VirtualFrame ignored) { throw new AssertionError("Traced slot forced its pointer"); }
                }.getCallTarget(), null);
                var marker = new Object();
                var payload = new Expr() {
                    @Override public Object execute(VirtualFrame ignored) { throw new AssertionError("Tuple payload needs a destination"); }
                    @Override public Object executeTuple(VirtualFrame target, int[] fields, int offset) {
                        FrameAccess.write(target, fields[offset], poison);
                        FrameAccess.write(target, fields[offset + 1], marker);
                        return null;
                    }
                };
                payload.setRepresentation(unknown.getAlternatives().getFirst());
                new SumConstruct(shape, 1, payload).executeTuple(frame, slots, 0);
                concreteShape.consume(frame, shape.finish(frame, slots), slots, 0);
                assertSame(poison, frame.getObject(slots[1])); assertSame(marker, frame.getObject(slots[2]));
                assertEquals(0, poison.getState());
                var number = new Expr() {
                    @Override public Object execute(VirtualFrame ignored) { return 17L; }
                    @Override public long executeLong(VirtualFrame ignored) { return 17L; }
                };
                number.setRepresentation(CoreRepresentations.parse(INT));
                new SumConstruct(shape, 2, number).executeTuple(frame, slots, 0);
                assertNull(frame.getObject(slots[1])); assertNull(frame.getObject(slots[2]));
                assertEquals(17L, frame.getLong(slots[3]));
                var failure = new RuntimeFault("payload failure identity");
                var throwing = new Expr() {
                    @Override public Object execute(VirtualFrame ignored) { throw failure; }
                    @Override public Object executeTuple(VirtualFrame target, int[] fields, int offset) { throw failure; }
                };
                throwing.setRepresentation(payload.getRepresentation());
                assertSame(failure, assertThrows(RuntimeFault.class, () -> new SumConstruct(shape, 1, throwing).executeTuple(frame, slots, 0)));
                var state = language.getHandoffState().get();
                assertNull(state.getPending());
                assertEquals(0, state.getArguments().getDepth()); assertEquals(0, state.getResults().getDepth());
                assertEquals(0, state.getArguments().retainedReferences()); assertEquals(0, state.getResults().retainedReferences());
            } finally { context.leave(); }
        }
    }
    private static Map<String,Object> binder(String name, Map<String,Object> proof) {
        return map("id", name, "name", name, "lifted", proof == UNKNOWN ? null : proof == DATA || proof == CLOSURE, "rep", proof);
    }
    private static List<Object> variable(String name, Map<String,Object> proof) { return list("var", name, map("rep", proof)); }
    private static List<Object> number() { return list("lit", "int", "0", map("rep", INT)); }
    private static List<Object> call(List<Object> function, Map<String,Object> result, List<List<Object>> args, Object... flags) {
        return list("app", function, args, Arrays.asList(flags), false, false, map("rep", result));
    }
    private static List<Object> lambda(Map<String,Object> result, List<Map<String,Object>> formals, List<Object> body) {
        return list("lam", formals, body, map("rep", CLOSURE, "resultRep", result,
            "entryStrict", Collections.nCopies(formals.size(), false)));
    }
    private static Map<String,Object> binding(String name, List<Object> body) {
        return map("id", name, "name", name, "lifted", true, "arity", ((List<?>) body.get(1)).size(), "rep", CLOSURE, "expr", body);
    }
    private static List<Object> box(List<Object> value) { return call(list("con", "Box", 1), DATA, list(value), (Object) null); }
    private static List<Object> consume(List<Object> value) {
        return list("case", value, "whole", list(
            list("data", "Left", list("payload"), box(variable("payload", UNKNOWN)), map("binders", list(binder("payload", UNKNOWN))))),
            map("rep", DATA, "binder", binder("whole", SUM)));
    }
    private static Map<String,Object> module() {
        var value = call(list("con", "Left", 1), SUM, list(variable("x", UNKNOWN)), (Object) null);
        var identity = binding("identity", lambda(SUM, list(binder("s", SUM)), variable("s", SUM)));
        var worker = binding("worker", lambda(DATA, list(binder("s", SUM), binder("unused", INT)), consume(variable("s", SUM))));
        var direct = binding("direct", lambda(DATA, list(binder("x", UNKNOWN)), consume(value)));
        var ordinary = binding("ordinary", lambda(DATA, list(binder("x", UNKNOWN)), call(variable("worker", CLOSURE), DATA,
            list(call(variable("identity", CLOSURE), SUM, list(value), false), number()), false, false)));
        var pap = binding("pap", lambda(DATA, list(binder("x", UNKNOWN)),
            call(call(variable("worker", CLOSURE), CLOSURE, list(value), false), DATA, list(number()), false)));
        var capture = binding("capture", lambda(DATA, list(binder("x", UNKNOWN)), call(
            call(lambda(CLOSURE, list(binder("s", SUM)), lambda(DATA, list(binder("unused", INT)), consume(variable("s", SUM)))),
                CLOSURE, list(value), false), DATA, list(number()), false)));
        return map("schema", 1, "ghc", "9.14.1", "module", "UnknownBoxedSum", "instrument", true,
            "bindings", list(identity, worker, direct, ordinary, pap, capture), "constructors", list(
                map("id", "Left", "kind", "unboxed-sum", "arity", 1, "sumArity", 2, "tag", 1),
                map("id", "Box", "name", "Box", "kind", "boxed", "arity", 1, "tag", 1, "strictFields", list(false),
                    "fieldLifted", list((Object) null), "fieldReps", list(list("BoxedRep Nothing")), "fieldTypes", list(UNKNOWN))));
    }
    @Test void localSumResultsKeepLazyIdentityAndClearInactiveReferences() throws Exception {
        for (var backend : list("ast", "bytecode")) try (var context = Context.newBuilder("thc").allowExperimentalOptions(true)
                .option("compiler.Inlining", "false").option("engine.BackgroundCompilation", "false")
                .option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw").build()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var proof = sum(list(L, INT), list("WordRep", LIFTED, "WordRep"), list(list(1), list(2)));
                var shape = new TupleShape(CoreRepresentations.parse(proof), language);
                var layout = new FrameLayout(); int[] slots = new int[shape.getWidth()];
                for (int i = 0; i < slots.length; i++) slots[i] = layout.bind("sum result " + i);
                var frame = Truffle.getRuntime().createVirtualFrame(new Object[0], layout.build());
                var poison = new Thunk(new RootNode(language) {
                    @Override public Object execute(VirtualFrame ignored) { throw new AssertionError("Sum join forced its lazy result"); }
                }.getCallTarget(), null);
                for (boolean recursive : new boolean[]{false, true}) {
                    var left = call(list("con", "Left", 1), proof, list(variable("payload", L)), true);
                    var right = call(list("con", "Right", 1), proof, list(variable(recursive ? "answer" : "choice", INT)), false);
                    var selected = list("case", call(list("prim", "<=#"), INT, list(variable("choice", INT), number()), false, false), "side", list(
                        list("lit", list("int", "1"), list(), left, map("binders", list())),
                        list("default", null, list(), right, map("binders", list()))), map("rep", proof, "binder", binder("side", INT)));
                    var finish = with(binder("finish", proof), "expr", selected, "joinValueArity", 0, "joinResultRep", proof);
                    var remaining = variable("remaining", INT);
                    var step = call(list("prim", "-#"), INT, list(remaining, list("lit", "int", "1", map("rep", INT))), false, false);
                    var loop = list("case", call(list("prim", "<=#"), INT, list(remaining, number()), false, false), "done", list(
                        list("lit", list("int", "1"), list(), variable("finish", proof), map("binders", list())),
                        list("default", null, list(), call(variable("go", CLOSURE), proof, list(step,
                            call(list("prim", "+#"), INT, list(variable("answer", INT), list("lit", "int", "2", map("rep", INT))), false, false)), false, false), map("binders", list()))),
                        map("rep", proof, "binder", binder("done", INT)));
                    var go = with(binding("go", lambda(proof, list(binder("remaining", INT), binder("answer", INT)),
                        list("let", false, list(finish), loop, map("rep", proof)))), "joinValueArity", 2, "joinResultRep", proof);
                    var result = recursive ? list("let", true, list(go), call(variable("go", CLOSURE), proof,
                        list(list("lit", "int", "3", map("rep", INT)), variable("choice", INT)), false, false), map("rep", proof))
                        : list("let", false, list(finish), variable("finish", proof), map("rep", proof));
                    var input = map("instrument", true, "bindings", list(binding("entry", lambda(proof,
                        list(with(binder("payload", L), "lifted", true), binder("choice", INT)), result))), "constructors", list(
                        map("id", "Left", "kind", "unboxed-sum", "arity", 1, "sumArity", 2, "tag", 1),
                        map("id", "Right", "kind", "unboxed-sum", "arity", 1, "sumArity", 2, "tag", 2)));
                    ExecutableProgram program = backend.equals("ast") ? new Program(language, input) : new BytecodeProgram(language, input);
                    var target = program.entryTarget("entry");
                    // Reusing the destination exposes a stale reference when the scalar arm follows the lazy arm.
                    for (int phase = 0; phase < 2; phase++) {
                        if (phase == 1) target.getClass().getMethod("compile", boolean.class).invoke(target, true);
                        for (long choice : new long[]{-1, 3_000_000_000L, -2}) {
                            long before = ((Number) program.diagnostics().get("compiledEntries")).longValue();
                            shape.consume(frame, Calls.target(target, new Object[]{0L, poison, choice}), slots, 0);
                            assertEquals(choice <= 0 ? 1L : 2L, frame.getLong(slots[0]));
                            if (choice <= 0) assertSame(poison, frame.getObject(slots[1]));
                            else { assertNull(frame.getObject(slots[1])); assertEquals(choice + (recursive ? 6L : 0L), frame.getLong(slots[2])); }
                            assertEquals(0, poison.getState());
                            if (phase == 1) {
                                assertTrue(((Number) program.diagnostics().get("compiledEntries")).longValue() > before, "First installed sum-result join");
                                assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target));
                            }
                            var state = language.getHandoffState().get();
                            assertNull(state.getPending()); assertEquals(0, state.getArguments().getDepth()); assertEquals(0, state.getResults().getDepth());
                            assertEquals(0, state.getArguments().retainedReferences()); assertEquals(0, state.getResults().retainedReferences());
                        }
                    }
                    // Native projection evidence and logical join/lambda results must agree before execution.
                    finish.put("joinResultRep", with(proof, "alternativeSlots", list(list(2), list(1))));
                    assertThrows(RuntimeFault.class, () -> { if (backend.equals("ast")) new Program(language, input); else new BytecodeProgram(language, input); });
                    finish.put("joinResultRep", proof);
                    if (recursive) {
                        object(expression(go.get("expr")).get(3)).put("resultRep",
                            sum(list(INT, L), list("WordRep", LIFTED, "WordRep"), list(list(2), list(1))));
                        assertThrows(RuntimeFault.class, () -> { if (backend.equals("ast")) new Program(language, input); else new BytecodeProgram(language, input); });
                    }
                }
            } finally { context.leave(); }
        }
    }
    @Test void poisonPointerSurvivesResultsArgumentsPapCaptureCaseAndConstructor() throws Exception {
        for (var backend : list("ast", "bytecode")) try (var context = Context.newBuilder("thc").allowExperimentalOptions(true)
                .option("compiler.Inlining", "false").option("engine.BackgroundCompilation", "false")
                .option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw").build()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                ExecutableProgram program = backend.equals("ast") ? new Program(language, module()) : new BytecodeProgram(language, module());
                var poison = new Thunk(new RootNode(language) {
                    @Override public Object execute(VirtualFrame frame) { throw new AssertionError("Unknown sum payload was forced"); }
                }.getCallTarget(), null);
                for (var name : list("direct", "ordinary", "pap", "capture")) {
                    var target = program.entryTarget(name);
                    for (int phase = 0; phase < 2; phase++) {
                        if (phase == 1) {
                            target.getClass().getMethod("compile", boolean.class).invoke(target, true);
                            assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target));
                        }
                        long before = ((Number) program.diagnostics().get("compiledEntries")).longValue();
                        var result = (DataValue) Calls.target(program.hostEntryTarget(1),
                            new Object[]{program.entryValue(name), new Object[]{poison}});
                        assertSame(poison, program.constructorLayout("Box").read(result, 0), backend + "/" + name);
                        assertEquals(0, poison.getState());
                        if (phase == 1) {
                            assertTrue(((Number) program.diagnostics().get("compiledEntries")).longValue() > before);
                            assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target));
                        }
                        var state = language.getHandoffState().get();
                        assertNull(state.getPending());
                        assertEquals(0, state.getArguments().getDepth()); assertEquals(0, state.getResults().getDepth());
                        assertEquals(0, state.getArguments().retainedReferences()); assertEquals(0, state.getResults().retainedReferences());
                    }
                }
            } finally { context.leave(); }
        }
    }
}
