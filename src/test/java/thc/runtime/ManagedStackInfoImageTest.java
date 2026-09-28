// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class ManagedStackInfoImageTest {
    private final String endian = StackInfoTestLayout.endian;
    private Map<String, Object> fields() { return StackInfoTestLayout.fields(); }
    private Map<String, Object> document(Map<String, Object> fields) { return StackInfoTestLayout.document(fields); }
    private TargetLayout layout() { return layout(Map.of()); }
    private TargetLayout layout(Map<String, ?> changes) { return StackInfoTestLayout.layout(changes); }
    private final List<Function<TargetLayout, ManagedStackInfoImage>> factories = List.of(ManagedStackInfoImage.Companion::stack, ManagedStackInfoImage.Companion::frame);
    @Test public void exactStackAndZeroPayloadDiagnosticFrameBytes() {
        var layout = layout();
        for (int i = 0; i < factories.size(); i++) {
            var ordinal = i == 0 ? 53 : 30; var image = factories.get(i).apply(layout);
            var expected = new byte[16]; expected[endian.equals("little") ? 8 : 11] = (byte) ordinal;
            assertEquals(16, image.getByteSize()); assertArrayEquals(expected, image.copyBytes());
        }
    }
    @Test public void targetOffsetsAndIntegerWidthsAreAppliedExactlyOnce() {
        for (int width : new int[]{1, 2, 4, 8}) {
            var changes = new LinkedHashMap<String, Object>(); changes.put("infoTableBytes", 4 * width + 4);
            var names = List.of("Srt", "Type", "Ptrs", "Nptrs");
            for (int index = 0; index < names.size(); index++) {
                changes.put("infoTable" + names.get(index) + "Offset", 2 + index * width);
                changes.put("infoTable" + names.get(index) + "Bytes", width);
            }
            var layout = layout(changes);
            for (int i = 0; i < factories.size(); i++) {
                var ordinal = i == 0 ? 53 : 30; var expected = new byte[4 * width + 4];
                expected[2 + width + (endian.equals("little") ? 0 : width - 1)] = (byte) ordinal;
                assertArrayEquals(expected, factories.get(i).apply(layout).copyBytes(), "width=" + width + "/" + ordinal);
            }
        }
    }
    @Test public void returnedBytesNeverExposeOrShareImageStorage() {
        for (var factory : factories) {
            var first = factory.apply(layout()); var second = factory.apply(layout());
            var expected = first.copyBytes(); var changed = first.copyBytes();
            assertNotSame(expected, changed); Arrays.fill(changed, (byte) 0x7f);
            assertArrayEquals(expected, first.copyBytes()); assertArrayEquals(expected, second.copyBytes());
        }
    }
    @Test public void nonTncUnsupportedWidthsAndEveryFieldOverlapReject() {
        for (var factory : factories) {
            assertTrue(assertThrows(IllegalArgumentException.class, () -> factory.apply(layout(Map.of("tablesNextToCode", false)))).getMessage().contains("tables-next-to-code"));
            var names = List.of("Ptrs", "Nptrs", "Type", "Srt");
            for (var name : names) for (int width : new int[]{3, 5, 6, 7, 9, 16}) {
                var changes = new LinkedHashMap<String, Object>();
                for (int index = 0; index < names.size(); index++) changes.put("infoTable" + names.get(index) + "Offset", index * 16);
                changes.put("infoTableBytes", 64); changes.put("infoTable" + name + "Bytes", width);
                assertTrue(assertThrows(IllegalArgumentException.class, () -> factory.apply(layout(changes))).getMessage().contains("width"));
            }
            for (int i = 0; i < names.size(); i++) for (int j = i + 1; j < names.size(); j++) for (int displacement : new int[]{0, 3}) {
                var changes = Map.of("infoTable" + names.get(j) + "Offset", 4 * i + displacement);
                assertTrue(assertThrows(IllegalArgumentException.class, () -> factory.apply(layout(changes))).getMessage().contains("Overlapping"));
            }
        }
    }
    @Test public void missingLayoutAndAlreadyInvalidTypedFieldsFailAtTheCallerBoundary() {
        assertNull(TargetLayout.Companion.fromReceipts(Map.of(), Map.of()));
        assertThrows(RuntimeException.class, () -> TargetLayout.Companion.fromDocument(null));
        for (var missing : List.of("tablesNextToCode", "infoTableTypeBytes", "infoTableTypeOffset")) assertThrows(RuntimeException.class, () -> {
            var fields = fields(); fields.remove(missing); TargetLayout.Companion.fromDocument(document(fields));
        });
        for (var changes : List.of(Map.of("tablesNextToCode", "true"), Map.of("infoTableTypeBytes", 0), Map.of("infoTableTypeOffset", 16),
            Map.of("closureStack", 256), Map.of("closureRetSmall", -1), Map.of("wordBytes", 4), Map.of("endianness", endian.equals("little") ? "big" : "little")))
            assertThrows(RuntimeException.class, () -> layout(changes));
    }
}
