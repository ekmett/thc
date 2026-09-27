// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc

import java.lang.foreign.MemorySegment
import java.nio.charset.CharacterCodingException
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CoreCompactCursorTest {
    private fun bytes(vararg values: Int) = MemorySegment.ofArray(values.map(Int::toByte).toByteArray())
    private fun cursor(vararg values: Int) = CoreCompactCursor(bytes(*values))

    @Test fun canonicalUnsignedManualVectorsCoverEveryWidth() {
        val input = cursor(0, 1, 127, 128, 1, 255, 127, 128, 128, 1,
            255, 255, 255, 255, 255, 255, 255, 255, 127,
            255, 255, 255, 255, 255, 255, 255, 255, 255, 1)
        for (expected in listOf(0L, 1L, 127L, 128L, 16383L, 16384L, Long.MAX_VALUE, -1L))
            assertEquals(expected, input.unsignedBits())
        input.expectEnd()
    }

    @Test fun signedZigzagPreservesBothLongExtremes() {
        val input = cursor(0, 1, 2, 3,
            254, 255, 255, 255, 255, 255, 255, 255, 255, 1,
            255, 255, 255, 255, 255, 255, 255, 255, 255, 1)
        for (expected in listOf(0L, -1L, 1L, -2L, Long.MAX_VALUE, Long.MIN_VALUE))
            assertEquals(expected, input.signed())
        input.expectEnd()
    }

    @Test fun noncanonicalTruncatedAndOverflowingIntegersRejectLocally() {
        for (invalid in listOf(intArrayOf(128, 0), intArrayOf(129, 0), intArrayOf(128),
            IntArray(10) { 128 }, IntArray(10) { if (it == 9) 2 else 255 }))
            assertThrows(IllegalArgumentException::class.java) { cursor(*invalid).unsignedBits() }
        assertThrows(IllegalArgumentException::class.java) {
            cursor(255, 255, 255, 255, 255, 255, 255, 255, 255, 1).unsigned()
        }
    }

    @Test fun fixedLittleEndianFieldsAndRecordBoundsAreExact() {
        val input = cursor(0x34, 0x12, 0x78, 0x56, 0x34, 0x12, 8, 7, 6, 5, 4, 3, 2, 1)
        assertEquals(0x1234, input.u16())
        assertEquals(0x12345678L, input.u32())
        assertEquals(0x0102030405060708L, input.offset())
        input.expectEnd()
        assertThrows(IllegalArgumentException::class.java) { input.byte() }
        assertThrows(IllegalArgumentException::class.java) { cursor(0, 0, 0, 0, 0, 0, 0, 128).offset() }
        assertThrows(IllegalArgumentException::class.java) { CoreCompactCursor(bytes(0), 1, 2) }
        assertThrows(IllegalArgumentException::class.java) { cursor(0).expectEnd() }
    }

    @Test fun countsAndBooleansDoNotAllocateFromUnboundedInput() {
        assertEquals(0, cursor(0).count())
        assertEquals(2, cursor(2, 0, 1).count())
        assertEquals(2, cursor(2, 0, 1, 2, 3).count(2))
        assertThrows(IllegalArgumentException::class.java) { cursor(3, 0).count() }
        assertThrows(IllegalArgumentException::class.java) { cursor(2, 0, 1).count(2) }
        assertFalse(cursor(0).boolean())
        assertTrue(cursor(1).boolean())
        assertThrows(IllegalArgumentException::class.java) { cursor(2).boolean() }
    }

    @Test fun utf8UsesOnlySelectedSpanAndRejectsMalformedSequences() {
        val encoded = MemorySegment.ofArray(byteArrayOf(255.toByte()) + "é😀".toByteArray() + byteArrayOf(255.toByte()))
        assertEquals("é😀", CoreCompactCursor.utf8(encoded, 1, 6))
        assertThrows(CharacterCodingException::class.java) { CoreCompactCursor.utf8(encoded, 0, 1) }
        assertThrows(IllegalArgumentException::class.java) { CoreCompactCursor.slice(encoded, Long.MAX_VALUE, 2) }
        assertThrows(IllegalArgumentException::class.java) { CoreCompactCursor.slice(encoded, 1, Long.MAX_VALUE) }
        assertEquals(0L, CoreCompactCursor.slice(encoded, encoded.byteSize(), 0).byteSize())
    }
}
