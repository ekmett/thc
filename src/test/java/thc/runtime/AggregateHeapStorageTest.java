// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.zip.CRC32;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import thc.Language;
import static org.junit.jupiter.api.Assertions.*;

class AggregateHeapStorageTest {
    private final Map<String, Object> longRep =
        Map.of("kind", "long", "evaluated", true, "primReps", List.of("IntRep"));
    private final Map<String, Object> reference =
        Map.of("kind", "data", "evaluated", false, "primReps", List.of("BoxedRep (Just Lifted)"));
    private final Map<String, Object> empty = Map.of("kind", "unknown", "evaluated", true, "aggregate", "unboxed-tuple",
        "primReps", List.of(), "components", List.of());
    private final Map<String, Object> sum = Map.of("kind", "unknown", "evaluated", true, "aggregate", "unboxed-sum",
        "primReps", List.of("WordRep", "BoxedRep (Just Lifted)"), "tagSlot", 0, "alternatives",
        List.of(empty, reference), "alternativeSlots", List.of(List.of(), List.of(1)));
    @SafeVarargs
    private final Map<String, Object> metadata(Map<String, Object>... fields) {
        var reps = new ArrayList<>();
        var lifted = new ArrayList<Boolean>();
        var strict = new ArrayList<Boolean>();
        for (var field : fields) {
            reps.add(field.get("primReps"));
            lifted.add(false);
            strict.add(true);
        }
        return Map.of("id", "test:Aggregate", "kind", "boxed", "arity", fields.length, "fieldTypes",
            Arrays.asList(fields), "fieldReps", reps, "fieldLifted", lifted, "strictFields", strict);
    }
    private Map<String, Object> changed(Map<String, Object> original, String key, Object value) {
        var result = new LinkedHashMap<>(original);
        result.put(key, value);
        return result;
    }
    private void inLanguage(Consumer<Language> action) {
        for (var strategy : List.of("field-based", "array-based"))
            try (var context = Context.newBuilder("thc")
                     .allowExperimentalOptions(true)
                     .option("engine.StaticObjectStorageStrategy", strategy)
                     .build()) {
                context.initialize("thc");
                context.enter();
                try {
                    action.accept(TruffleLanguage.LanguageReference.create(Language.class).get(null));
                } finally {
                    context.leave();
                }
            }
    }
    @Test
    void logicalFieldsFlattenWithoutLosingEmptyFieldsAndExactShapeChecks() {
        inLanguage(language -> {
            var fields = new CoreFields(metadata(longRep, empty, sum, longRep));
            assertArrayEquals(new int[] {0, 1, 1, 3, 4}, fields.getOffsets());
            var layout = DataLayout.fromFields(language, "test:Aggregate", "Aggregate", fields);
            assertEquals(4, layout.getLogicalArity());
            assertEquals(4, layout.getArity());
            assertEquals(0, layout.logicalWidth(1));
            assertEquals(2, layout.logicalWidth(2));
            for (var wrong : List.of(changed(metadata(sum), "fieldReps", List.of(List.of("WordRep"))),
                     changed(metadata(sum), "fieldLifted", List.of(true)), metadata(changed(sum, "evaluated", false)),
                     metadata(changed(sum, "alternativeSlots", List.of(List.of(), List.of(0))))))
                assertThrows(RuntimeFault.class, () -> new CoreFields(wrong));
            assertThrows(UnsupportedCore.class,
                () -> new CoreFields(changed(metadata(longRep), "fieldReps", List.of(List.of("IntRep", "IntRep")))));
        });
    }
    private static class Copier extends RootNode {
        @Child private CompactCopyNode copy;
        Copier(Language language) {
            super(language);
            var failures = new GlobalBinding[3];
            for (int i = 0; i < failures.length; i++) {
                failures[i] = new GlobalBinding("failure" + i);
                failures[i].initialize((long) i);
            }
            copy = new CompactCopyNode(new Metrics(false), failures);
        }
        @Override
        public Object execute(VirtualFrame frame) {
            return copy.execute(frame, (ManagedCompact) frame.getArguments()[0], frame.getArguments()[1], true);
        }
    }
    private CompactImages.Fixed restore(Language.State state, DataValue value, byte[] bytes) {
        var block = state.compactImages.allocate(bytes.length, ManagedAddress.nullAddress());
        block.copyFromByteArray(bytes, 0, bytes.length);
        return state.compactImages.fixup(block, state.heapAddresses.address(value));
    }
    @Test
    void inactiveSumReferencesAreNotRootsWhileActivePayloadsRemainLazy() {
        inLanguage(language -> {
            var layout = DataLayout.fromFields(language, "test:Sum", "Sum", new CoreFields(metadata(sum)));
            var child =
                new DataLayout(language, "test:Leaf", "Leaf", new String[] {"IntRep"}).create(new Object[] {91L});
            var inactive = layout.create(new Object[] {1L, null});
            var active = layout.create(new Object[] {2L, child});
            var thunk = new Thunk(new RootNode(language) {
                @Override
                public Object execute(VirtualFrame frame) {
                    throw new IllegalStateException("Lazy sum payload was forced");
                }
            }.getCallTarget(), null);
            var lazy = layout.create(new Object[] {2L, thunk});
            assertTrue(layout.inactiveSumReference(inactive, 1));
            assertFalse(layout.inactiveSumReference(active, 1));
            assertArrayEquals(new Object[0], ClosureInspection.image(inactive).getPointers());
            assertArrayEquals(new Object[] {child}, ClosureInspection.image(active).getPointers());
            assertArrayEquals(new Object[] {thunk}, ClosureInspection.image(lazy).getPointers());
            assertEquals(0, thunk.getState());
            var copy = new Copier(language).getCallTarget();
            for (var source : List.of(inactive, active)) {
                var state = Language.currentState();
                var region = new ManagedCompact(state.compactRegions, 4096);
                var value = (DataValue) copy.call(region, source);
                assertNotSame(source, value);
                assertEquals(layout.readLong(source, 0), layout.readLong(value, 0));
                if (source == inactive)
                    assertNull(layout.read(value, 1));
                else {
                    var copied = (DataValue) layout.read(value, 1);
                    assertNotSame(child, copied);
                    assertEquals(91L, copied.getLayout().readLong(copied, 0));
                }
                var image = state.compactImages.first(region);
                var bytes = new byte[(int) image.availableBytes()];
                image.copyToByteArray(bytes, 0, bytes.length);
                var restored = restore(state, value, bytes);
                var decoded = (DataValue) state.heapAddresses.dereference(restored.getRoot());
                assertEquals(layout.readLong(value, 0), layout.readLong(decoded, 0));
                if (source == inactive) {
                    assertNull(layout.read(decoded, 1));
                    // Header28, node identity8/tag1/layout8, then the sum tag at45.
                    var malformed = bytes.clone();
                    var buffer = ByteBuffer.wrap(malformed);
                    buffer.putLong(45, 7L);
                    var crc = new CRC32();
                    crc.update(malformed, 0, malformed.length - 8);
                    buffer.putLong(malformed.length - 8, crc.getValue());
                    assertSame(ManagedAddress.nullAddress(), restore(state, value, malformed).getRoot());
                } else {
                    var result = (DataValue) layout.read(decoded, 1);
                    assertEquals(91L, result.getLayout().readLong(result, 0));
                }
            }
        });
    }
}
