// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.RootNode;
import java.nio.file.*;
import java.util.*;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import thc.Json;
import thc.Language;
import static org.junit.jupiter.api.Assertions.*;
import static thc.runtime.RawVectorTestValues.rawVectorTestValue;
import static thc.runtime.RepresentationTestSupport.*;

/** Transport controls independent of arithmetic and loader optimizations. */
class VectorLayoutTest {
    private final CoreRepresentation integer = new CoreRepresentation(CoreKind.LONG, true, true, list("IntRep"), null, null, null, null, null);
    private List<CoreRepresentation> vectors() throws Exception {
        var catalog = object(Json.INSTANCE.parse(Files.readString(Path.of(System.getProperty("thc.projectRoot"), "scripts/simd-families.json"))));
        var values = new ArrayList<CoreRepresentation>();
        for (var shape : objects(catalog.get("families"))) values.add(CoreRepresentations.INSTANCE.parse(map("kind", "vector", "evaluated", true,
            "primReps", list("VecRep " + shape.get("lanes") + " " + shape.get("element")), "vector", map("lanes", shape.get("lanes"), "element", shape.get("element")))));
        assertEquals(30, values.size()); return values;
    }
    private void withLanguage(CheckedConsumer<Language> action) throws Exception {
        try (var context = Context.newBuilder("thc").allowExperimentalOptions(true).option("engine.BackgroundCompilation", "false").build()) {
            context.initialize("thc"); context.enter();
            try { action.accept(TruffleLanguage.LanguageReference.create(Language.class).get(null)); } finally { context.leave(); }
        }
    }
    private static final class Slots {
        final FrameLayout layout = new FrameLayout(); final int[] slots; final VirtualFrame frame;
        Slots(int count) {
            slots = new int[count]; for (int i = 0; i < count; i++) slots[i] = layout.bind("lane " + i);
            frame = Truffle.getRuntime().createVirtualFrame(new Object[0], layout.build());
        }
    }
    private void fill(VectorLayout layout, Slots slots) { layout.write(slots.frame, slots.slots, 0, rawVectorTestValue(layout.getProof())); }
    private void same(VectorLayout layout, Slots expected, Slots actual) { assertSame(layout.read(expected.frame, expected.slots, 0), layout.read(actual.frame, actual.slots, 0)); }
    @Test void allShapesPreserveBitsAcrossCarriersDenseStorageAndCompletion() throws Exception {
        withLanguage(language -> {
            for (var proof : vectors()) {
                var vector = new VectorLayout(proof); var original = new Slots(1); fill(vector, original); var copied = new Slots(1);
                vector.write(copied.frame, copied.slots, 0, vector.read(original.frame, original.slots, 0)); same(vector, original, copied);
                var shape = new TupleShape(proof, language); assertEquals(1, shape.getWidth()); assertTrue(shape.getLayout().isObject(0)); assertFalse(shape.getLayout().isLong(0));
                var result = shape.finish(copied.frame, copied.slots); assertEquals(1, language.getHandoffState$org_intelligence_thc().get().getResults().getDepth());
                var consumed = new Slots(1); shape.consume(consumed.frame, result, consumed.slots, 0); same(vector, original, consumed);
                assertEquals(0, language.getHandoffState$org_intelligence_thc().get().getResults().getDepth()); assertEquals(0, language.getHandoffState$org_intelligence_thc().get().getResults().retainedReferences());
                // Deoptimized results own storage rather than a live pool loan.
                var fresh = shape.getLayout().create(); assertEquals(vector.getCarrierType(), fresh.getClass().getField("handoff__0").getType());
                var source = new AstInputSource(Objects.requireNonNull(ArgumentLayout.fromProofs(list(proof))), original.slots);
                source.copy(original.frame, new Node() {}, null, 0, fresh, 0, 1); shape.consume(consumed.frame, fresh, consumed.slots, 0); same(vector, original, consumed);
            }
        });
    }
    private Closure append(boolean generic, Closure function, TypedInputLayout input, AstInputSource source, Slots original, Node node) {
        return generic ? GenericTypedInputsKt.genericTypedPap(function, input, source, original.frame, node, null, 1, 0, 1)
            : TypedInputsKt.typedPap(function, input, source, original.frame, node, null, 0, 1, function.suppliedCount, function.arity);
    }
    @Test void papPrefixesOwnImmutableVectorReferencesAfterCallerLocalsAreReused() throws Exception {
        withLanguage(language -> {
            var target = new RootNode(language) { @Override public Object execute(VirtualFrame frame) { throw new IllegalStateException("PAP construction must not enter the callee"); } }.getCallTarget(); var node = new Node() {};
            for (var proof : vectors()) for (boolean generic : new boolean[]{false, true}) {
                var vector = new VectorLayout(proof); var original = new Slots(1); fill(vector, original);
                var source = new AstInputSource(Objects.requireNonNull(ArgumentLayout.fromProofs(list(proof))), original.slots);
                var input = Objects.requireNonNull(TypedInputLayout.create(language, ArgumentLayout.fromProofs(list(proof, proof, integer)), false)); var closure = new Closure(null, 3, target);
                var first = append(generic, closure, input, source, original, node); var second = append(generic, first, input, source, original, node);
                assertEquals(1, first.suppliedCount); assertEquals(2, second.suppliedCount); assertSame(Closure.NO_PAP_ARGUMENTS, first.supplied); assertSame(Closure.NO_PAP_ARGUMENTS, second.supplied); assertNotSame(first.typedSupplied, second.typedSupplied);
                var expected = new Slots(1); vector.write(expected.frame, expected.slots, 0, vector.read(original.frame, original.slots, 0));
                for (int slot : original.slots) original.frame.clear(slot);
                for (var function : list(first, second)) {
                    var storage = Objects.requireNonNull(function.typedSupplied); assertFalse(storage.getLive()); assertEquals(0, storage.getInputMode());
                    for (int part = 0; part < function.suppliedCount; part++) { var restored = new Slots(1); vector.write(restored.frame, restored.slots, 0, storage.getLayout().getObject(storage, part)); same(vector, expected, restored); }
                }
                assertEquals(0, language.getHandoffState$org_intelligence_thc().get().getArguments().getDepth());
            }
        });
    }
    @Test void logicalVectorIdentityCannotBeReplacedByEqualWidthTuplesOrOtherShapes() throws Exception {
        withLanguage(language -> {
            for (var proof : vectors()) {
                var vector = new VectorLayout(proof); var laneReps = Objects.requireNonNull(vector.getLane().getPrimReps()); assertEquals(1, laneReps.size());
                var tuple = new CoreRepresentation(CoreKind.UNKNOWN, true, true, Collections.nCopies(vector.getLanes(), laneReps.getFirst()), Collections.nCopies(vector.getLanes(), vector.getLane()), null, null, null, null);
                var input = Objects.requireNonNull(ArgumentLayout.fromProofs(list(proof))); var wrongs = new ArrayList<CoreRepresentation>();
                for (var other : vectors()) if (!other.equals(proof)) wrongs.add(other); wrongs.add(tuple);
                for (var wrong : wrongs) {
                    assertFalse(TupleShape.compatible(proof, wrong)); assertFalse(new TupleShape(proof, language).matches(new TupleShape(wrong, language)));
                    assertThrows(RuntimeFault.class, () -> TupleShape.requireCompatible(proof, wrong));
                    assertThrows(RuntimeFault.class, () -> ArgumentLayout.validate(input, 0, ArgumentLayout.fromProofs(list(wrong)), 0, 1));
                    assertThrows(RuntimeFault.class, () -> CoreRepresentations.INSTANCE.requireJoinArgument(proof, wrong));
                }
                assertThrows(RuntimeFault.class, () -> ArgumentLayout.validate(input, 0, null, 0, 1));
            }
        });
    }
    @Test void rawSpeciesAreCheckedAndReleasedAsReferencesOnSuccessAndFailure() throws Exception {
        withLanguage(language -> {
            var all = vectors();
            for (var proof : all) {
                var vector = new VectorLayout(proof); var shape = new TupleShape(proof, language); var slots = new Slots(1); var raw = rawVectorTestValue(proof);
                for (var other : all) {
                    var candidate = rawVectorTestValue(other);
                    if (candidate.species().equals(raw.species())) {
                        var checked = vector.require(candidate); assertSame(candidate, checked); assertEquals(vector.getSpecies().vectorType(), checked.getClass()); assertSame(shape.getLayout(), new TupleShape(other, language).getLayout());
                    } else {
                        assertThrows(RuntimeFault.class, () -> vector.write(slots.frame, slots.slots, 0, candidate)); assertThrows(RuntimeFault.class, () -> shape.getLayout().setObject(shape.getLayout().create(), 0, candidate));
                    }
                }
                for (var wrong : list(null, 1L, new long[]{1L}, new Object())) assertThrows(RuntimeFault.class, () -> vector.write(slots.frame, slots.slots, 0, wrong));
                var pool = language.getHandoffState$org_intelligence_thc().get().getArguments(); var loan = pool.acquire(shape.getLayout()); shape.getLayout().setObject(loan, 0, raw);
                assertEquals(1, pool.retainedReferences()); pool.release(loan); assertEquals(0, pool.getDepth()); assertEquals(0, pool.retainedReferences());
                FrameAccess.writeObject(slots.frame, slots.slots[0], new Object()); assertThrows(RuntimeFault.class, () -> shape.finish(slots.frame, slots.slots));
                assertEquals(0, language.getHandoffState$org_intelligence_thc().get().getResults().getDepth()); assertEquals(0, language.getHandoffState$org_intelligence_thc().get().getResults().retainedReferences());
            }
        });
    }
    @Test void compiledExactClassChecksRetainSpeciesAndNullRejectionForEveryShape() throws Exception {
        withLanguage(language -> {
            var all = vectors().stream().map(RawVectorTestValues::rawVectorTestValue).toList();
            for (var proof : vectors()) {
                var vector = new VectorLayout(proof); var target = new RootNode(language) { @Override public Object execute(VirtualFrame frame) { return vector.require(frame.getArguments()[0]); } }.getCallTarget(); var raw = rawVectorTestValue(proof);
                for (int i = 0; i < 20; i++) assertSame(raw, target.call(raw)); target.getClass().getMethod("compile", boolean.class).invoke(target, true);
                assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target)); assertSame(raw, target.call(raw));
                for (var candidate : all) {
                    // Signed/unsigned proofs can share species; physical shapes cannot.
                    boolean sameSpecies = candidate.species().equals(vector.getSpecies()); assertEquals(sameSpecies, vector.getSpecies().vectorType().isInstance(candidate));
                    if (sameSpecies) assertSame(candidate, target.call(candidate)); else assertThrows(RuntimeFault.class, () -> target.call(candidate));
                }
                for (var wrong : list(null, 1L, new long[]{1L}, new Object())) assertThrows(RuntimeFault.class, () -> target.call(wrong));
            }
        });
    }
    @Test void physicalVectorAnnotationDoesNotEraseLogicalTupleIdentity() {
        var scalar = map("kind", "vector", "evaluated", true, "primReps", list("VecRep 4 FloatElemRep"), "vector", map("lanes", 4, "element", "FloatElemRep"));
        var state = map("kind", "void", "evaluated", true, "primReps", list()); var tuple = with(scalar, "kind", "unknown", "aggregate", "unboxed-tuple", "components", list(state, scalar));
        var proof = CoreRepresentations.INSTANCE.parse(tuple); assertTrue(proof.isTuple()); assertFalse(proof.isVector()); assertEquals(1, TupleShape.flatten(proof).size()); assertFalse(TupleShape.compatible(proof, CoreRepresentations.INSTANCE.parse(scalar)));
        for (var wrong : list(map("lanes", 2, "element", "DoubleElemRep"), map("lanes", 4, "element", "Word32ElemRep")))
            assertThrows(RuntimeFault.class, () -> CoreRepresentations.INSTANCE.parse(with(tuple, "vector", wrong)));
    }
}
