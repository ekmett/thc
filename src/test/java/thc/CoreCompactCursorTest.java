// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import java.lang.foreign.MemorySegment;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CoreCompactCursorTest {
    private MemorySegment bytes(int... values) {
        byte[] result = new byte[values.length];
        for (int i = 0; i < values.length; i++) result[i] = (byte) values[i];
        return MemorySegment.ofArray(result);
    }
    private CoreCompactCursor cursor(int... values) { return new CoreCompactCursor(bytes(values)); }

    @Test void canonicalUnsignedManualVectorsCoverEveryWidth() {
        var input = cursor(0, 1, 127, 128, 1, 255, 127, 128, 128, 1,
            255, 255, 255, 255, 255, 255, 255, 255, 127,
            255, 255, 255, 255, 255, 255, 255, 255, 255, 1);
        for (long expected : new long[]{0, 1, 127, 128, 16383, 16384, Long.MAX_VALUE, -1})
            assertEquals(expected, input.unsignedBits());
        input.expectEnd();
    }
    @Test void signedZigzagPreservesBothLongExtremes() {
        var input = cursor(0, 1, 2, 3,
            254, 255, 255, 255, 255, 255, 255, 255, 255, 1,
            255, 255, 255, 255, 255, 255, 255, 255, 255, 1);
        for (long expected : new long[]{0, -1, 1, -2, Long.MAX_VALUE, Long.MIN_VALUE})
            assertEquals(expected, input.signed());
        input.expectEnd();
    }
    @Test void noncanonicalTruncatedAndOverflowingIntegersRejectLocally() {
        int[] continuation = new int[10], overflow = new int[10];
        Arrays.fill(continuation, 128); Arrays.fill(overflow, 255); overflow[9] = 2;
        for (int[] invalid : List.of(new int[]{128, 0}, new int[]{129, 0}, new int[]{128}, continuation, overflow))
            assertThrows(IllegalArgumentException.class, () -> cursor(invalid).unsignedBits());
        assertThrows(IllegalArgumentException.class, () ->
            cursor(255, 255, 255, 255, 255, 255, 255, 255, 255, 1).unsigned());
    }
    @Test void fixedLittleEndianFieldsAndRecordBoundsAreExact() {
        var input = cursor(0x34, 0x12, 0x78, 0x56, 0x34, 0x12, 8, 7, 6, 5, 4, 3, 2, 1);
        assertEquals(0x1234, input.u16());
        assertEquals(0x12345678L, input.u32());
        assertEquals(0x0102030405060708L, input.offset());
        input.expectEnd();
        assertThrows(IllegalArgumentException.class, input::readByte);
        assertThrows(IllegalArgumentException.class, () -> cursor(0, 0, 0, 0, 0, 0, 0, 128).offset());
        assertThrows(IllegalArgumentException.class, () -> new CoreCompactCursor(bytes(0), 1, 2));
        assertThrows(IllegalArgumentException.class, () -> cursor(0).expectEnd());
    }
    @Test void countsAndBooleansDoNotAllocateFromUnboundedInput() {
        assertEquals(0, cursor(0).count());
        assertEquals(2, cursor(2, 0, 1).count());
        assertEquals(2, cursor(2, 0, 1, 2, 3).count(2));
        assertThrows(IllegalArgumentException.class, () -> cursor(3, 0).count());
        assertThrows(IllegalArgumentException.class, () -> cursor(2, 0, 1).count(2));
        assertFalse(cursor(0).readBoolean());
        assertTrue(cursor(1).readBoolean());
        assertThrows(IllegalArgumentException.class, () -> cursor(2).readBoolean());
    }
    @Test void utf8UsesOnlySelectedSpanAndRejectsMalformedSequences() throws Exception {
        byte[] text = "é😀".getBytes(StandardCharsets.UTF_8);
        byte[] bytes = new byte[text.length + 2];
        bytes[0] = -1; bytes[bytes.length - 1] = -1;
        System.arraycopy(text, 0, bytes, 1, text.length);
        var encoded = MemorySegment.ofArray(bytes);
        assertEquals("é😀", CoreCompactCursor.utf8(encoded, 1, 6));
        assertThrows(CharacterCodingException.class, () -> CoreCompactCursor.utf8(encoded, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> CoreCompactCursor.slice(encoded, Long.MAX_VALUE, 2));
        assertThrows(IllegalArgumentException.class, () -> CoreCompactCursor.slice(encoded, 1, Long.MAX_VALUE));
        assertEquals(0L, CoreCompactCursor.slice(encoded, encoded.byteSize(), 0).byteSize());
    }
}
