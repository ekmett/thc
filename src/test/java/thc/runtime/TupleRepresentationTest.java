// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleLanguage;
import java.util.*;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.junit.jupiter.api.Test;
import thc.Language;
import static org.junit.jupiter.api.Assertions.*;

class TupleRepresentationTest {
    private static Map<String, Object> record(Object... fields) {
        var result = new LinkedHashMap<String, Object>(); for (int i = 0; i < fields.length; i += 2) result.put((String) fields[i], fields[i + 1]); return result;
    }
    @SafeVarargs private static <T> List<T> values(T... values) { return Arrays.asList(values); }
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
        try (var engine = Engine.create()) {
            var previous = new TupleShape[1];
            withLanguage(engine, language -> {
                java.util.function.BiConsumer<CoreRepresentation, CoreRepresentation> distinct = (left, right) ->
                    assertFalse(new TupleShape(left, language).matches(new TupleShape(right, language)));
                distinct.accept(s.tuple(), s.tuple(state));
                distinct.accept(s.tuple(), s.tuple(s.tuple()));
                distinct.accept(s.tuple(integer), s.tuple(word));
                distinct.accept(s.tuple(state, integer), s.tuple(integer, state));
                distinct.accept(s.tuple(integer), s.tuple(s.tuple(integer)));
                distinct.accept(s.tuple(integer, word), s.tuple(word, integer));
                distinct.accept(s.tuple(lifted), s.tuple(unlifted));
                distinct.accept(s.tuple(floatProof), s.tuple(doubleProof));
                distinct.accept(floatVector, s.tuple(floatVector));
                distinct.accept(floatVector, s.vector(2, "DoubleElemRep"));
                distinct.accept(s.vector(4, "Int32ElemRep"), s.vector(4, "Word32ElemRep"));
                distinct.accept(s.sum(integer, word), s.sum(word, integer));
                distinct.accept(s.sum(integer, word), s.sum(integer, integer));
                distinct.accept(s.sum(integer, integer), s.sum(s.tuple(integer), integer));
                assertTrue(new TupleShape(s.tuple(lifted), language).matches(new TupleShape(s.tuple(lifted.copy(CoreKind.DATA, true,
                    lifted.getPresent(), lifted.getPrimReps(), null, null, null, null, null)), language)), "WHNF/kind refinement preserves boxed shape");
                previous[0] = new TupleShape(s.tuple(lifted, integer, state), language);
            });
            withLanguage(engine, language -> {
                var fresh = new TupleShape(s.tuple(lifted, integer, state), language);
                assertTrue(fresh.matches(previous[0]), "same logical metadata across contexts");
                assertNotSame(previous[0].getLayout(), fresh.getLayout(), "storage layouts remain context-owned");
            });
        }
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
            var completion = source.finish(frame, new int[]{slot}); var owned = TupleResults.ownedTupleResult(completion, source); assertSame(marker, source.getLayout().getObject(owned, 0)); assertEquals(0, language.getHandoffState().get().getResults().getDepth()); assertEquals(0, language.getHandoffState().get().getResults().retainedReferences());
            var malformed = source.finish(frame, new int[]{slot}); assertThrows(IllegalStateException.class, () -> TupleResults.ownedTupleResult(malformed, wrong)); assertEquals(0, language.getHandoffState().get().getResults().getDepth()); assertEquals(0, language.getHandoffState().get().getResults().retainedReferences());
        });
    }
    @Test void scalarHandoffCannotClaimSingletonReferenceTupleResults() throws Exception {
        String previous = System.getProperty(Handoff.HANDOFF_PROPERTY); System.setProperty(Handoff.HANDOFF_PROPERTY, "true");
        try { withLanguage(language -> {
            var ref = new CoreRepresentation(CoreKind.OBJECT, false, true, List.of("BoxedRep (Just Lifted)"), null, null, null, null, null); var result = new CoreRepresentation(CoreKind.UNKNOWN, true, true, ref.getPrimReps(), List.of(ref), null, null, null, null);
            assertNull(HandoffEntry.create(language, new FrameLayout(), List.of(), result, false));
        }); } finally { if (previous == null) System.clearProperty(Handoff.HANDOFF_PROPERTY); else System.setProperty(Handoff.HANDOFF_PROPERTY, previous); }
    }
    @Test void ordinaryUnknownMetadataRemainsOptionalAndDoesNotInferAggregateShape() {
        assertFalse(CoreRepresentations.parse(null).getPresent());
        for (var reps : values(null, values(), values("IntRep"), values("IntRep", "IntRep"), values("BoxedRep (Just Lifted)"), values("FloatRep"))) {
            var proof = record("kind", "unknown", "primReps", reps, "evaluated", true);
            assertEquals(CoreKind.UNKNOWN, CoreRepresentations.parse(proof).getKind());
            for (String aggregate : values("unboxed-tuple", "unboxed-sum")) {
                var error = assertThrows(UnsupportedCore.class, () -> CoreRepresentations.parse(record("kind", "unknown", "primReps", reps, "evaluated", true, "aggregate", aggregate)));
                assertTrue(Objects.toString(error.getMessage(), "").startsWith("Unsupported Core aggregate representation: " + aggregate));
            }
            assertThrows(RuntimeFault.class, () -> CoreRepresentations.parse(record("kind", "unknown", "primReps", reps, "evaluated", true, "aggregate", "guessed")));
        }
    }
}
