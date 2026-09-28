// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleLanguage;
import java.io.File;
import java.nio.file.Files;
import java.util.*;
import java.util.function.Consumer;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.junit.jupiter.api.Test;
import thc.CoreModules;
import thc.Json;
import thc.Language;
import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings("unchecked")
class TupleRepresentationTest {
    private static Map<String, Object> record(Object... fields) {
        var result = new LinkedHashMap<String, Object>(); for (int i = 0; i < fields.length; i += 2) result.put((String) fields[i], fields[i + 1]); return result;
    }
    private static List<Object> values(Object... values) { return Arrays.asList(values); }
    @Test void emptyTupleProofOwnsParsedListsAndRecomputesOnCopyAndRefinement() {
        var reps = new ArrayList<String>(); var components = new ArrayList<Object>();
        var input = record("kind", "unknown", "evaluated", true, "primReps", reps, "aggregate", "unboxed-tuple", "components", components);
        var empty = CoreRepresentations.parse(input); assertTrue(empty.isEmptyTuple()); assertNotSame(reps, empty.getPrimReps()); assertNotSame(components, empty.getComponents());
        reps.add("IntRep"); components.add(record("kind", "long", "evaluated", true, "primReps", List.of("IntRep"))); var nonempty = CoreRepresentations.parse(input); assertFalse(nonempty.isEmptyTuple());
        reps.clear(); components.clear(); assertEquals(List.of("IntRep"), nonempty.getPrimReps()); assertEquals(1, Objects.requireNonNull(nonempty.getComponents()).size());
        assertTrue(empty.isEmptyTuple()); assertTrue(Objects.requireNonNull(empty.getPrimReps()).isEmpty()); assertTrue(Objects.requireNonNull(empty.getComponents()).isEmpty());
        assertFalse(empty.copy(empty.getKind(), empty.getEvaluated(), empty.getPresent(), empty.getPrimReps(), List.of(empty), empty.getVector(), empty.getAlternatives(), empty.getTagSlot(), empty.getAlternativeSlots()).isEmptyTuple(), "nested empty tuple has one logical component");
        assertFalse(empty.copy(empty.getKind(), empty.getEvaluated(), empty.getPresent(), null, empty.getComponents(), empty.getVector(), empty.getAlternatives(), empty.getTagSlot(), empty.getAlternativeSlots()).isEmptyTuple());
        assertFalse(empty.copy(empty.getKind(), empty.getEvaluated(), empty.getPresent(), empty.getPrimReps(), null, empty.getVector(), empty.getAlternatives(), empty.getTagSlot(), empty.getAlternativeSlots()).isEmptyTuple());
        assertFalse(empty.copy(empty.getKind(), empty.getEvaluated(), false, empty.getPrimReps(), empty.getComponents(), empty.getVector(), empty.getAlternatives(), empty.getTagSlot(), empty.getAlternativeSlots()).isEmptyTuple());
        assertFalse(empty.copy(CoreKind.VOID, empty.getEvaluated(), empty.getPresent(), empty.getPrimReps(), empty.getComponents(), empty.getVector(), empty.getAlternatives(), empty.getTagSlot(), empty.getAlternativeSlots()).isEmptyTuple());
        assertTrue(nonempty.copy(nonempty.getKind(), nonempty.getEvaluated(), nonempty.getPresent(), List.of(), List.of(), nonempty.getVector(), nonempty.getAlternatives(), nonempty.getTagSlot(), nonempty.getAlternativeSlots()).isEmptyTuple());
        assertTrue(CoreRepresentation.UNKNOWN.refine(empty).isEmptyTuple()); assertTrue(empty.refine(CoreRepresentation.UNKNOWN).isEmptyTuple());
        assertEquals(empty, empty.copy(empty.getKind(), empty.getEvaluated(), empty.getPresent(), empty.getPrimReps(), empty.getComponents(), empty.getVector(), empty.getAlternatives(), empty.getTagSlot(), empty.getAlternativeSlots()), "derived proof does not change structural equality");
        assertTrue(empty.copy(empty.getKind(), false, empty.getPresent(), empty.getPrimReps(), empty.getComponents(), empty.getVector(), empty.getAlternatives(), empty.getTagSlot(), empty.getAlternativeSlots()).isEmptyTuple());
    }
    private final File root = new File(System.getProperty("thc.projectRoot"));
    private Map<String, Object> map(Object value) { return (Map<String, Object>) value; }
    private List<Object> list(Object value) { return (List<Object>) value; }
    private Map<String, Object> wrap(Object proof) { return record("kind", "unknown", "evaluated", true, "primReps", map(proof).get("primReps"), "aggregate", "unboxed-tuple", "components", new ArrayList<>(values(proof))); }
    private Map<String, Object> module() throws Exception { return map(Json.parse(Files.readString(new File(root, "build/aggregate-core/AggregateFrontier.json").toPath()))); }
    private Map<String, Object> binding(Map<String, Object> module, String name) {
        Map<String, Object> found = null;
        for (var binding : (List<Map<String, Object>>) module.get("bindings")) if (Objects.equals(binding.get("name"), name)) {
            if (found != null) throw new IllegalArgumentException("Collection contains more than one matching element."); found = binding;
        }
        if (found == null) throw new NoSuchElementException("Collection contains no element matching the predicate."); return found;
    }
    private List<Object> expression(Map<String, Object> module, String name) { return list(binding(module, name).get("expr")); }
    @FunctionalInterface private interface Action { void run(Language language) throws Exception; }
    private void withLanguage(Action action) throws Exception { withLanguage(null, action); }
    private void withLanguage(Engine engine, Action action) throws Exception {
        var builder = Context.newBuilder("thc"); if (engine != null) builder.engine(engine);
        try (var context = builder.build()) {
            context.initialize("thc"); context.enter(); try { action.run(TruffleLanguage.LanguageReference.create(Language.class).get(null)); } finally { context.leave(); }
        }
    }
    @Test void canonicalShapeKeysPreserveLogicalIdentityWithoutSharingContextLayouts() throws Exception {
        var state = new CoreRepresentation(CoreKind.VOID, true, true, List.of(), null, null, null, null, null);
        var integer = new CoreRepresentation(CoreKind.LONG, true, true, List.of("IntRep"), null, null, null, null, null);
        var word = integer.copy(integer.getKind(), integer.getEvaluated(), integer.getPresent(), List.of("WordRep"), integer.getComponents(), integer.getVector(), integer.getAlternatives(), integer.getTagSlot(), integer.getAlternativeSlots());
        var lifted = new CoreRepresentation(CoreKind.OBJECT, false, true, List.of("BoxedRep (Just Lifted)"), null, null, null, null, null);
        var unlifted = lifted.copy(lifted.getKind(), true, lifted.getPresent(), List.of("BoxedRep (Just Unlifted)"), lifted.getComponents(), lifted.getVector(), lifted.getAlternatives(), lifted.getTagSlot(), lifted.getAlternativeSlots());
        var floatProof = new CoreRepresentation(CoreKind.FLOAT, true, true, List.of("FloatRep"), null, null, null, null, null);
        var doubleProof = new CoreRepresentation(CoreKind.DOUBLE, true, true, List.of("DoubleRep"), null, null, null, null, null);
        class Shapes {
            CoreRepresentation tuple(CoreRepresentation... fields) {
                var reps = new ArrayList<String>(); for (var field : fields) reps.addAll(Objects.requireNonNull(field.getPrimReps())); return new CoreRepresentation(CoreKind.UNKNOWN, true, true, reps, Arrays.asList(fields), null, null, null, null);
            }
            CoreRepresentation vector(int lanes, String element) { return new CoreRepresentation(CoreKind.VECTOR, true, true, List.of("VecRep " + lanes + " " + element), null, new CoreVector(lanes, element), null, null, null); }
            CoreRepresentation sum(CoreRepresentation first, CoreRepresentation second) {
                var proof = new CoreRepresentation(CoreKind.UNKNOWN, true, true, List.of("WordRep", "WordRep"), null, null, List.of(first, second), 0, List.of(List.of(1), List.of(1))); SumShape.validate(proof); return proof;
            }
        }
        var s = new Shapes(); var floatVector = s.vector(4, "FloatElemRep");
        var proofs = List.of(s.tuple(), s.tuple(state), s.tuple(s.tuple()), s.tuple(integer), s.tuple(word), s.tuple(state, integer), s.tuple(integer, state), s.tuple(s.tuple(integer)), s.tuple(integer, word), s.tuple(word, integer),
            s.tuple(lifted), s.tuple(lifted.copy(CoreKind.DATA, true, lifted.getPresent(), lifted.getPrimReps(), lifted.getComponents(), lifted.getVector(), lifted.getAlternatives(), lifted.getTagSlot(), lifted.getAlternativeSlots())), s.tuple(unlifted),
            s.tuple(floatProof), s.tuple(doubleProof), floatVector, s.tuple(floatVector), s.vector(2, "DoubleElemRep"), s.vector(4, "Int32ElemRep"), s.vector(4, "Word32ElemRep"), s.sum(integer, word), s.sum(word, integer), s.sum(integer, integer), s.sum(s.tuple(integer), integer));
        try (var engine = Engine.create()) {
            var previous = new ArrayList<TupleShape>();
            withLanguage(engine, language -> {
                for (var proof : proofs) previous.add(new TupleShape(proof, language));
                for (int left = 0; left < proofs.size(); left++) for (int right = 0; right < proofs.size(); right++) assertEquals(TupleShape.compatible(proofs.get(left), proofs.get(right)), previous.get(left).matches(previous.get(right)), "logical shape " + left + " / " + right);
                assertTrue(previous.get(10).matches(previous.get(11)), "WHNF/kind refinements do not change boxed representation");
                for (int[] pair : new int[][]{{0, 1}, {0, 2}, {3, 4}, {5, 6}, {3, 7}, {8, 9}, {10, 12}, {13, 14}, {15, 16}, {15, 17}, {18, 19}, {20, 21}, {20, 22}, {22, 23}})
                    assertFalse(previous.get(pair[0]).matches(previous.get(pair[1])), "distinct logical shapes " + pair[0] + " / " + pair[1]);
            });
            withLanguage(engine, language -> {
                for (int index = 0; index < proofs.size(); index++) {
                    var fresh = new TupleShape(proofs.get(index), language); assertTrue(fresh.matches(previous.get(index)), "same metadata across contexts");
                    assertNotSame(previous.get(index).getLanguage(), fresh.getLanguage(), "THC's EXCLUSIVE context policy remains unchanged"); assertNotSame(previous.get(index).getLayout(), fresh.getLayout(), "storage layouts remain context-owned");
                }
            });
        }
    }
    @Test void equalPhysicalVectorsDoNotEraseLogicalTupleBoundaries() throws Exception {
        List<Consumer<Map<String, Object>>> mutations = List.of(
            m -> { var p = map(map(expression(m, "pair").get(3)).get("resultRep")); var c = list(p.get("components")); c.set(0, wrap(c.get(0))); },
            m -> { var p = map(map(list(expression(m, "pair").get(2)).get(6)).get("rep")); var c = list(p.get("components")); c.set(0, wrap(c.get(0))); },
            m -> { var p = map(map(map(list(expression(m, "tupleOutstanding").get(2)).get(4)).get("binder")).get("rep")); var c = list(p.get("components")); c.set(0, wrap(c.get(0))); },
            m -> { var alt = list(list(list(expression(m, "tupleOutstanding").get(2)).get(3)).get(0)); var b = map(list(map(alt.get(4)).get("binders")).get(0)); b.put("rep", wrap(b.get("rep"))); },
            m -> { var app = list(expression(m, "pair").get(2)); var con = list(app.get(1)); con.set(2, 3L); },
            m -> { var app = list(expression(m, "pair").get(2)); var arg = list(list(app.get(2)).get(0)); map(map(arg.get(6)).get("rep")).put("primReps", List.of("WordRep")); },
            m -> { var alt = list(list(list(expression(m, "tupleOutstanding").get(2)).get(3)).get(0)); var b = map(list(map(alt.get(4)).get("binders")).get(0)); map(b.get("rep")).put("primReps", List.of("WordRep")); },
            m -> { var pair = expression(m, "pair"); var app = list(pair.get(2)); var p = map(map(app.get(6)).get("rep")); list(p.get("components")).set(0, record("kind", "unknown", "primReps", List.of("IntRep"), "evaluated", true)); });
        withLanguage(language -> {
            for (int index = 0; index < mutations.size(); index++) for (String backend : List.of("ast", "bytecode")) {
                var m = module(); mutations.get(index).accept(m); var linked = CoreModules.reachable(m, "tupleOutstanding");
                assertThrows(RuntimeFault.class, () -> { if (backend.equals("ast")) new Program(language, linked); else new BytecodeProgram(language, linked); }, backend + " mutation " + index);
            }
        });
        var empty = CoreRepresentations.parse(record("kind", "unknown", "primReps", List.of(), "evaluated", true, "aggregate", "unboxed-tuple", "components", List.of()));
        var nested = empty.copy(empty.getKind(), empty.getEvaluated(), empty.getPresent(), empty.getPrimReps(), List.of(empty), empty.getVector(), empty.getAlternatives(), empty.getTagSlot(), empty.getAlternativeSlots());
        assertFalse(TupleShape.compatible(empty, nested)); assertFalse(TupleShape.compatible(empty, new CoreRepresentation(CoreKind.VOID, true, true, List.of(), null, null, null, null, null))); assertThrows(RuntimeFault.class, () -> empty.refine(nested));
        var generic = new CoreRepresentation(CoreKind.OBJECT, false, true, List.of("BoxedRep (Just Lifted)"), null, null, null, null, null);
        assertTrue(TupleShape.compatible(empty.copy(empty.getKind(), empty.getEvaluated(), empty.getPresent(), generic.getPrimReps(), List.of(generic), empty.getVector(), empty.getAlternatives(), empty.getTagSlot(), empty.getAlternativeSlots()),
            empty.copy(empty.getKind(), empty.getEvaluated(), empty.getPresent(), generic.getPrimReps(), List.of(generic.copy(CoreKind.DATA, true, generic.getPresent(), generic.getPrimReps(), generic.getComponents(), generic.getVector(), generic.getAlternatives(), generic.getTagSlot(), generic.getAlternativeSlots())), empty.getVector(), empty.getAlternatives(), empty.getTagSlot(), empty.getAlternativeSlots())));
    }
    @Test void completedReferenceLoanIsReleasedEvenWhenTheConsumerShapeIsWrong() throws Exception {
        withLanguage(language -> {
            var reference = new CoreRepresentation(CoreKind.OBJECT, false, true, List.of("BoxedRep (Just Lifted)"), null, null, null, null, null); var integer = new CoreRepresentation(CoreKind.LONG, true, true, List.of("IntRep"), null, null, null, null, null);
            var source = new TupleShape(new CoreRepresentation(CoreKind.UNKNOWN, true, true, reference.getPrimReps(), List.of(reference), null, null, null, null), language);
            var wrong = new TupleShape(new CoreRepresentation(CoreKind.UNKNOWN, true, true, integer.getPrimReps(), List.of(integer), null, null, null, null), language);
            var layout = new FrameLayout(); int slot = layout.bind("field"); var frame = Truffle.getRuntime().createVirtualFrame(new Object[0], layout.build()); var marker = new Object(); FrameAccess.write(frame, slot, marker);
            var token = source.finish(frame, new int[]{slot}); assertEquals(1, language.getHandoffState().get().getResults().getDepth());
            assertThrows(IllegalStateException.class, () -> wrong.consume(frame, token, new int[]{slot}, 0)); assertEquals(0, language.getHandoffState().get().getResults().getDepth()); assertEquals(0, language.getHandoffState().get().getResults().retainedReferences());
            // A deoptimized fresh carrier owns no loan and retains raw pointer identity.
            var fresh = source.getLayout().create(); source.getLayout().setObject(fresh, 0, marker); source.consume(frame, fresh, new int[]{slot}, 0); assertSame(marker, frame.getObject(slot)); assertEquals(0, language.getHandoffState().get().getResults().getDepth());
            var completion = source.finish(frame, new int[]{slot}); var owned = TupleResultsKt.ownedTupleResult(completion, source); assertSame(marker, source.getLayout().getObject(owned, 0)); assertEquals(0, language.getHandoffState().get().getResults().getDepth()); assertEquals(0, language.getHandoffState().get().getResults().retainedReferences());
            var malformed = source.finish(frame, new int[]{slot}); assertThrows(IllegalStateException.class, () -> TupleResultsKt.ownedTupleResult(malformed, wrong)); assertEquals(0, language.getHandoffState().get().getResults().getDepth()); assertEquals(0, language.getHandoffState().get().getResults().retainedReferences());
        });
    }
    @Test void forcingAConsumedLazyFieldCanBlackholeWithoutRetainingTheOutputLoan() throws Exception {
        withLanguage(language -> {
            for (String backend : List.of("ast", "bytecode")) {
                var m = module(); var outer = list(expression(m, "tupleZeroLazy").get(2)); var outerAlt = list(list(outer.get(3)).get(0)); var choice = list(outerAlt.get(3));
                var alternatives = new ArrayList<List<Object>>(); for (var raw : list(choice.get(3))) alternatives.add(list(raw));
                List<Object> defaultArm = null, literalArm = null;
                for (var alternative : alternatives) {
                    if (Objects.equals(alternative.getFirst(), "default")) { if (defaultArm != null) throw new IllegalArgumentException("Collection contains more than one matching element."); defaultArm = alternative; }
                    if (Objects.equals(alternative.getFirst(), "lit")) { if (literalArm != null) throw new IllegalArgumentException("Collection contains more than one matching element."); literalArm = alternative; }
                }
                Objects.requireNonNull(literalArm).set(3, Objects.requireNonNull(defaultArm).get(3)); var linked = CoreModules.reachable(m, "tupleZeroLazy"); ExecutableProgram program = backend.equals("ast") ? new Program(language, linked) : new BytecodeProgram(language, linked);
                for (int i = 0; i < 2; i++) {
                    var error = assertThrows(RuntimeFault.class, () -> Calls.target(program.hostEntryTarget(1), new Object[]{program.entryValue("tupleZeroLazy"), new Object[]{-1L}})); assertTrue(Objects.toString(error.getMessage(), "").contains("Blackhole"));
                    assertEquals(0, language.getHandoffState().get().getResults().getDepth()); assertEquals(0, language.getHandoffState().get().getResults().retainedReferences());
                }
                assertEquals(4 * 4097L + 2554L, Calls.target(program.hostEntryTarget(1), new Object[]{program.entryValue("tupleZeroLazy"), new Object[]{4097L}}));
            }
        });
    }
    @Test void lexicalScalarAndJoinBindingsShadowTupleNames() throws Exception {
        withLanguage(language -> {
            var longRep = record("kind", "long", "primReps", List.of("IntRep"), "evaluated", true);
            java.util.function.LongFunction<List<Object>> literal = value -> values("lit", "int", Long.toString(value), record("rep", longRep));
            for (String kind : List.of("let", "case", "join")) for (String backend : List.of("ast", "bytecode")) {
                var m = module(); var outer = list(expression(m, "tupleOutstanding").get(2)); String id = (String) outer.get(2); var use = values("var", id, record("rep", longRep)); List<Object> body;
                if (kind.equals("case")) body = values("case", literal.apply(12345L), id, values(values("default", null, List.of(), use)), record("rep", longRep, "binder", record("id", id, "rep", longRep)));
                else {
                    var binder = record("id", id, "name", id, "lifted", false, "coercion", false, "rep", longRep, "expr", literal.apply(12345L));
                    if (kind.equals("join")) { binder.put("joinValueArity", 0L); binder.put("joinResultRep", longRep); } body = values("let", false, values(binder), use, record("rep", longRep));
                }
                list(list(outer.get(3)).get(0)).set(3, body); var linked = CoreModules.reachable(m, "tupleOutstanding"); ExecutableProgram program = backend.equals("ast") ? new Program(language, linked) : new BytecodeProgram(language, linked);
                assertEquals(12345L, Calls.target(program.hostEntryTarget(1), new Object[]{program.entryValue("tupleOutstanding"), new Object[]{8193L}}), backend + "/" + kind);
            }
        });
    }
    @Test void scalarHandoffCannotClaimSingletonReferenceTupleResults() throws Exception {
        String previous = System.getProperty(HandoffKt.HANDOFF_PROPERTY); System.setProperty(HandoffKt.HANDOFF_PROPERTY, "true");
        try { withLanguage(language -> {
            var ref = new CoreRepresentation(CoreKind.OBJECT, false, true, List.of("BoxedRep (Just Lifted)"), null, null, null, null, null); var result = new CoreRepresentation(CoreKind.UNKNOWN, true, true, ref.getPrimReps(), List.of(ref), null, null, null, null);
            assertNull(HandoffEntry.create(language, new FrameLayout(), List.of(), result, false));
        }); } finally { if (previous == null) System.clearProperty(HandoffKt.HANDOFF_PROPERTY); else System.setProperty(HandoffKt.HANDOFF_PROPERTY, previous); }
    }
}
