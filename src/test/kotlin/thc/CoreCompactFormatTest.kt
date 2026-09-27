// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc

import java.lang.foreign.MemorySegment
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CoreCompactFormatTest {
    /** Independent fixed-field model; deliberately invalid bytes in the
     * payload demonstrate that envelope reading does not parse its records. */
    private fun container(lengths: List<Int> = listOf(3, 6, 0, 0, 0, 24), facts: Int = 5): ByteArray {
        val out = ByteBuffer.allocate(24 + facts + lengths.sum() + 128).order(ByteOrder.LITTLE_ENDIAN)
        out.put(byteArrayOf(84, 72, 67, 67, 77, 80, 0, 0)).putShort(1).putShort(0).putInt(0).putLong(facts.toLong())
        repeat(facts + lengths.sum()) { out.put(255.toByte()) }
        out.put(byteArrayOf(84, 72, 67, 67, 69, 78, 68, 49))
        var at = 24L + facts
        for (length in lengths) {
            out.putLong(at).putLong(length.toLong())
            at += length
        }
        out.putLong(lengths.last() / 24L).putInt(15)
            .putInt((if (lengths[2] > 0) 1 else 0) or (if (lengths[3] > 0) 2 else 0) or (if (lengths[4] > 0) 4 else 0))
            .putLong(0)
        return out.array()
    }
    private fun read(bytes: ByteArray) = CoreCompactFormat.read(MemorySegment.ofArray(bytes))
    private fun mutate(bytes: ByteArray, position: Int, value: Long): ByteArray = bytes.clone().also {
        ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putLong(position, value)
    }

    @Test fun headerAndEndFooterLocateSixSegmentsWithoutReadingTheirContents() {
        val bytes = container()
        val header = read(bytes)
        assertEquals(CoreCompactFormat.Span(24, 5), header.facts)
        assertEquals(CoreCompactFormat.Span(29, 3), header[CoreCompactFormat.Segment.DATA])
        assertEquals(CoreCompactFormat.Span(32, 6), header[CoreCompactFormat.Segment.STRINGS])
        assertEquals(CoreCompactFormat.Span(38, 24), header[CoreCompactFormat.Segment.SYMBOLS])
        assertEquals(1L, header.bindingCount)
        assertEquals(0, header.debug)
        assertTrue(header.containsDelimitedControl && header.registrationObligations && header.mainAlias && header.packageScalarDeclarations)
    }

    @Test fun emptySegmentsAndIndependentOptionalDebugSegmentsHaveExactFlags() {
        assertEquals(0L, read(container(List(6) { 0 }, 0)).bindingCount)
        assertEquals(7, read(container(listOf(0, 0, 1, 1, 1, 0))).debug)
        assertEquals(2, read(container(listOf(0, 0, 0, 1, 0, 0))).debug)
        val bytes = container()
        assertThrows(IllegalArgumentException::class.java) {
            read(mutate(bytes, bytes.size - 128 + 112, 1L shl 32))
        }
    }

    @Test fun versionMagicAndReservedFieldsRejectBeforeSemanticDecoding() {
        for (position in listOf(0, 8, 10, 12)) {
            val bytes = container()
            bytes[position] = (bytes[position] + 1).toByte()
            assertThrows(IllegalArgumentException::class.java) { read(bytes) }
        }
        for (relative in listOf(0, 112, 116, 120)) {
            val bytes = container()
            bytes[bytes.size - 128 + relative] = 255.toByte()
            assertThrows(IllegalArgumentException::class.java) { read(bytes) }
        }
        assertThrows(IllegalArgumentException::class.java) { read(ByteArray(151)) }
    }

    @Test fun segmentOverlapsGapsOverflowAndFingerprintCountMismatchRejectLocally() {
        val bytes = container()
        val footer = bytes.size - 128
        for ((position, value) in listOf(16 to Long.MAX_VALUE, 16 to Long.MIN_VALUE,
            footer + 8 to 28L, footer + 8 to 30L, footer + 16 to Long.MAX_VALUE,
            footer + 16 to Long.MIN_VALUE, footer + 104 to 2L)) {
            assertThrows(IllegalArgumentException::class.java) { read(mutate(bytes, position, value)) }
        }
        assertThrows(IllegalArgumentException::class.java) { read(container(listOf(0, 0, 0, 0, 0, 23))) }
        assertThrows(IllegalArgumentException::class.java) { read(bytes + byteArrayOf(0)) }
    }
}
