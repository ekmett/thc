// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc

import java.lang.foreign.MemorySegment
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CoreCompactFormatTest {
    private val lengths = listOf(3L, 6L, 0L, 0L, 0L, 24L)
    private fun header() = CoreCbdTestSupport.header(ByteArray(5) { -1 }, 1, 15)
    private fun read(bytes: ByteArray, sizes: List<Long> = lengths) = CoreCompactFormat.read(MemorySegment.ofArray(bytes), sizes)

    @Test fun headerUsesUncompressedMemberLengthsWithoutReadingTheirContents() {
        val value = read(header())
        assertEquals(CoreCompactFormat.Span(32, 5), value.facts)
        assertEquals(CoreCompactFormat.Span(0, 3), value[CoreCompactFormat.Segment.DATA])
        assertEquals(CoreCompactFormat.Span(0, 6), value[CoreCompactFormat.Segment.STRINGS])
        assertEquals(CoreCompactFormat.Span(0, 24), value[CoreCompactFormat.Segment.SYMBOLS])
        assertEquals(1L, value.bindingCount)
        assertTrue(value.containsDelimitedControl && value.registrationObligations && value.mainAlias && value.packageScalarDeclarations)
    }
    @Test fun emptyAndIndependentDebugMembersRequireExactFlags() {
        assertEquals(0L, read(CoreCbdTestSupport.header(), List(6) { 0L }).bindingCount)
        assertEquals(2, read(CoreCbdTestSupport.header(debug = 2), listOf(0, 0, 0, 1, 0, 0).map(Int::toLong)).debug)
        assertThrows(IllegalArgumentException::class.java) { read(CoreCbdTestSupport.header(debug = 7), List(6) { 0L }) }
    }
    @Test fun versionMagicReservedFlagsAndTruncationRejectBeforePayloadDecoding() {
        for (position in listOf(0, 8, 10, 12, 24, 28)) {
            val bytes = header(); bytes[position] = -1
            assertThrows(IllegalArgumentException::class.java) { read(bytes) }
        }
        for (size in 0 until 32) assertThrows(IllegalArgumentException::class.java) { read(ByteArray(size)) }
    }
    @Test fun countOverflowUnknownLengthsAndSymbolWidthMismatchReject() {
        for (count in listOf(-1L, Long.MAX_VALUE, 2L)) {
            val bytes = header(); ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putLong(16, count)
            assertThrows(IllegalArgumentException::class.java) { read(bytes) }
        }
        assertThrows(IllegalArgumentException::class.java) { read(header(), lengths.dropLast(1) + 23L) }
        assertThrows(IllegalArgumentException::class.java) { read(header(), listOf(-1L) + lengths.drop(1)) }
        assertThrows(IllegalArgumentException::class.java) { read(header(), lengths.drop(1)) }
    }
}
