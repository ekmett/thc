// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import thc.*;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings("unchecked")
public class FourWayAggregateStorageTest {
    private final File root = new File(System.getProperty("thc.projectRoot"));
    private Map<String, Map<String, Object>> constructors() throws Exception {
        FourWayEvidence.verify(root);
        var module = (Map<String, Object>) Json.parse(Files.readString(new File(root, "build/fourway-aggregate/pre/core/FourWayAggregateFields.json").toPath(), StandardCharsets.UTF_8));
        var result = new LinkedHashMap<String, Map<String, Object>>(); for (var info : (List<Map<String, Object>>) module.get("constructors")) result.put((String) info.get("name"), info); return result;
    }
    @FunctionalInterface private interface Action { void run(Language language, Map<String, Map<String, Object>> constructors) throws Exception; }
    private void eachLayout(Action action) throws Exception {
        for (String strategy : List.of("field-based", "array-based")) try (Context context = Context.newBuilder("thc").allowExperimentalOptions(true).option("engine.StaticObjectStorageStrategy", strategy).build()) {
            context.initialize("thc"); context.enter();
            try { action.run(TruffleLanguage.LanguageReference.create(Language.class).get(null), constructors()); } finally { context.leave(); }
        }
    }
    @Test public void originalWorkerHasTwoLogicalFieldsAndThreePhysicalFields() throws Exception { eachLayout((language, constructors) -> {
        var info = constructors.get("VirtualRegWithFormat"); var fields = new CoreFields(info);
        var layout = DataLayout.fromFields(language, (String) info.get("id"), "VirtualRegWithFormat", fields);
        assertEquals(2, layout.getLogicalArity()); assertEquals(3, layout.getArity()); assertArrayEquals(new int[] {0, 2, 3}, fields.getOffsets());
        assertTrue(layout.isLong(0)); assertTrue(layout.isLong(1)); assertFalse(layout.isLong(2)); var formatInfo = constructors.get("II64");
        var format = DataLayout.fromFields(language, (String) formatInfo.get("id"), "II64", new CoreFields(formatInfo)).allocate();
        for (long tag = 1; tag <= 4; tag++) for (long bits : new long[] {0L, 1L, 0xffffffffL, 0x100000000L, Long.MIN_VALUE, -1L}) {
            var value = layout.create(new Object[] {tag, bits, format}); assertEquals(tag, layout.readLong(value, 0)); assertEquals(bits, layout.readLong(value, 1)); assertSame(format, layout.read(value, 2));
        }
        var proof = fields.getLogicalProofs()[0]; var kinds = new ArrayList<CoreKind>(); for (var storage : SumShape.storage(proof)) kinds.add(storage.getKind());
        assertEquals(List.of(CoreKind.LONG, CoreKind.LONG), kinds);
        for (long tag : new long[] {Long.MIN_VALUE, -1L, 0L, 5L, 0x100000001L, Long.MAX_VALUE}) assertThrows(RuntimeFault.class, () -> SumShape.checkedTag(tag, 4));
    }); }
    private CoreRepresentation components(CoreRepresentation proof, List<CoreRepresentation> components) {
        return proof.copy(proof.getKind(), proof.getEvaluated(), proof.getPresent(), proof.getPrimReps(), components, proof.getVector(), proof.getAlternatives(), proof.getTagSlot(), proof.getAlternativeSlots());
    }
    @Test public void nestedPhysicalOffsetsDoNotEraseLogicalStateOrEmptyTupleIdentity() throws Exception { eachLayout((language, constructors) -> {
        var fields = new CoreFields(constructors.get("NestedBox")); assertArrayEquals(new int[] {0, 1, 6, 7}, fields.getOffsets());
        var nested = fields.getLogicalProofs()[1]; var shape = new TupleShape(nested, language); assertEquals(5, shape.getWidth()); assertArrayEquals(new int[] {0, 1, 4}, shape.getOffsets());
        var inner = Objects.requireNonNull(nested.getComponents()).get(1); var innerComponents = Objects.requireNonNull(inner.getComponents());
        assertEquals(CoreKind.VOID, innerComponents.getFirst().getKind()); assertTrue(innerComponents.get(1).isEmptyTuple());
        assertEquals(0, TupleShape.flatten(innerComponents.getFirst()).size()); assertEquals(0, TupleShape.flatten(innerComponents.get(1)).size());
        assertEquals(2, TupleShape.flatten(innerComponents.get(2)).size()); assertFalse(innerComponents.get(3).getEvaluated()); var scalar = fields.getLogicalProofs()[0];
        var arguments = Objects.requireNonNull(ArgumentLayout.fromProofs(List.of(scalar, nested, scalar)));
        assertEquals(3, arguments.getLogicalArity()); assertEquals(7, arguments.getPhysicalArity());
        var offsets = new ArrayList<Integer>(); for (int i = 0; i <= 3; i++) offsets.add(arguments.offset(i)); assertEquals(List.of(0, 1, 6, 7), offsets);
        var input = Objects.requireNonNull(TypedInputLayout.create(language, arguments, false)); assertEquals(6, input.prefix(2).getReps().size());
        // A real logical mismatch remains a mismatch even at equal physical width.
        var changedComponents = new ArrayList<>(innerComponents); changedComponents.set(1, innerComponents.getFirst()); var changedInner = components(inner, changedComponents);
        var changedNested = new ArrayList<>(nested.getComponents()); changedNested.set(1, changedInner); var changed = components(nested, changedNested);
        var other = Objects.requireNonNull(ArgumentLayout.fromProofs(List.of(scalar, changed, scalar))); assertEquals(arguments.getPhysicalArity(), other.getPhysicalArity());
        assertThrows(RuntimeFault.class, () -> ArgumentLayout.validate(arguments, 0, other, 0, 3));
    }); }
    private static final class Copier extends RootNode {
        @Child private CompactCopyNode copy;
        Copier(Language language) {
            super(language); GlobalBinding[] failures = new GlobalBinding[3];
            for (int i = 0; i < 3; i++) { failures[i] = new GlobalBinding("failure" + i); failures[i].initialize((long) i); }
            copy = new CompactCopyNode(new Metrics(false), failures);
        }
        @Override public Object execute(VirtualFrame frame) { return copy.execute(frame, (ManagedCompact) frame.getArguments()[0], frame.getArguments()[1], true); }
    }
    @Test public void nestedInactiveReferencesArePaddingNotRoots() throws Exception { eachLayout((language, constructors) -> {
        var info = constructors.get("MixedBox"); var fields = new CoreFields(info); assertArrayEquals(new int[] {0, 6, 7}, fields.getOffsets());
        var layout = DataLayout.fromFields(language, (String) info.get("id"), "MixedBox", fields);
        var child = new DataLayout(language, "test:Leaf", "Leaf", new String[] {"IntRep"}).create(new Object[] {91L});
        var lazy = new Thunk(new RootNode(language) { @Override public Object execute(VirtualFrame frame) { throw new IllegalStateException("Nested lazy neighbour was forced"); } }.getCallTarget(), null);
        for (long tag = 1; tag <= 4; tag++) {
            var value = layout.create(new Object[] {17L, tag, tag == 1 ? child : null, Long.MIN_VALUE, lazy, 19L, 23L});
            assertEquals(tag != 1L, layout.inactiveSumReference(value, 2)); assertFalse(layout.inactiveSumReference(value, 4), "The lazy neighbour is outside the nested sum");
            assertArrayEquals(tag == 1L ? new Object[] {child, lazy} : new Object[] {lazy}, ClosureInspection.image(value).getPointers()); assertEquals(0, lazy.getState());
        }
        for (long tag : new long[] {0L, 5L, Long.MIN_VALUE, Long.MAX_VALUE}) {
            var value = layout.create(new Object[] {17L, tag, null, 0L, child, 19L, 23L}); assertThrows(RuntimeFault.class, () -> layout.inactiveSumReference(value, 2));
        }
        var copy = new Copier(language).getCallTarget();
        for (long tag = 1; tag <= 4; tag++) {
            var source = layout.create(new Object[] {17L, tag, tag == 1 ? child : null, Long.MIN_VALUE, child, 19L, 23L});
            var state = Language.currentState(); var region = new ManagedCompact(state.compactRegions, 4096); var value = (DataValue) copy.call(region, source); assertNotSame(source, value);
            for (int index : new int[] {0, 1, 3, 5, 6}) assertEquals(layout.readLong(source, index), layout.readLong(value, index));
            if (tag == 1L) assertSame(layout.read(value, 2), layout.read(value, 4)); else assertNull(layout.read(value, 2)); assertNotSame(child, layout.read(value, 4));
            var image = state.compactImages.first(region); byte[] bytes = new byte[(int) image.availableBytes()]; image.copyToByteArray(bytes, 0, bytes.length);
            var block = state.compactImages.allocate(bytes.length, ManagedAddress.nullAddress()); block.copyFromByteArray(bytes, 0, bytes.length);
            var fixed = state.compactImages.fixup(block, state.heapAddresses.address(value)); var restored = (DataValue) state.heapAddresses.dereference(fixed.getRoot());
            assertEquals(tag, layout.readLong(restored, 1)); assertEquals(Long.MIN_VALUE, layout.readLong(restored, 3));
            if (tag == 1L) assertSame(layout.read(restored, 2), layout.read(restored, 4)); else assertNull(layout.read(restored, 2));
        }
    }); }
}
