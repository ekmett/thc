// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CoreCompactFormatTest {
    private final List<Long> lengths = List.of(3L, 6L, 0L, 0L, 0L, 24L);
    private byte[] header() {
        byte[] facts = new byte[5]; Arrays.fill(facts, (byte) -1);
        return CoreCbdTestSupport.header(facts, 1, 15, 0);
    }
    private CoreCompactFormat.Header read(byte[] bytes) { return read(bytes, lengths); }
    private CoreCompactFormat.Header read(byte[] bytes, List<Long> sizes) {
        return CoreCompactFormat.read(MemorySegment.ofArray(bytes), sizes);
    }
    @Test void headerUsesUncompressedMemberLengthsWithoutReadingTheirContents() {
        var value = read(header());
        assertEquals(new CoreCompactFormat.Span(32, 5), value.facts());
        assertEquals(new CoreCompactFormat.Span(0, 3), value.get(CoreCompactFormat.Segment.DATA));
        assertEquals(new CoreCompactFormat.Span(0, 6), value.get(CoreCompactFormat.Segment.STRINGS));
        assertEquals(new CoreCompactFormat.Span(0, 24), value.get(CoreCompactFormat.Segment.SYMBOLS));
        assertEquals(1L, value.bindingCount());
        assertTrue(value.getContainsDelimitedControl() && value.getRegistrationObligations()
            && value.getMainAlias() && value.getPackageScalarDeclarations());
    }
    @Test void emptyAndIndependentDebugMembersRequireExactFlags() {
        assertEquals(0L, read(CoreCbdTestSupport.header(new byte[0], 0, 0, 0), Collections.nCopies(6, 0L)).bindingCount());
        assertEquals(2, read(CoreCbdTestSupport.header(new byte[0], 0, 0, 2), List.of(0L, 0L, 0L, 1L, 0L, 0L)).debug());
        assertThrows(IllegalArgumentException.class, () ->
            read(CoreCbdTestSupport.header(new byte[0], 0, 0, 7), Collections.nCopies(6, 0L)));
    }
    @Test void versionMagicReservedFlagsAndTruncationRejectBeforePayloadDecoding() {
        for (int position : new int[]{0, 8, 10, 12, 24, 28}) {
            byte[] bytes = header(); bytes[position] = -1;
            assertThrows(IllegalArgumentException.class, () -> read(bytes));
        }
        for (int size = 0; size < 32; size++) {
            byte[] bytes = new byte[size];
            assertThrows(IllegalArgumentException.class, () -> read(bytes));
        }
    }
    @Test void countOverflowUnknownLengthsAndSymbolWidthMismatchReject() {
        for (long count : new long[]{-1, Long.MAX_VALUE, 2}) {
            byte[] bytes = header(); ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putLong(16, count);
            assertThrows(IllegalArgumentException.class, () -> read(bytes));
        }
        assertThrows(IllegalArgumentException.class, () -> read(header(), List.of(3L, 6L, 0L, 0L, 0L, 23L)));
        assertThrows(IllegalArgumentException.class, () -> read(header(), List.of(-1L, 6L, 0L, 0L, 0L, 24L)));
        assertThrows(IllegalArgumentException.class, () -> read(header(), lengths.subList(1, lengths.size())));
    }
}
