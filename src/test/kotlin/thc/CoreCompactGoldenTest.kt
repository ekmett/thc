// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc

import java.lang.foreign.MemorySegment
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/** These same manually specified bytes are checked by the native producer.
 * They test framing/primitives, not a fabricated executable Core module. */
class CoreCompactGoldenTest {
    private val directory = Path.of("test/compact-core/golden")
    private fun hex(text: String): ByteArray = text.filterNot(Char::isWhitespace)
        .chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test fun sharedUnsignedAndSignedVectorsDecodeIndependently() {
        val vectors = Json.parse(Files.readString(directory.resolve("integers-v1.json"))) as List<*>
        assertEquals(16, vectors.size)
        for (raw in vectors) {
            val vector = raw as Map<*, *>
            val cursor = CoreCompactCursor(MemorySegment.ofArray(hex(vector["hex"] as String)))
            val expected = vector["value"] as String
            when (vector["encoding"]) {
                "uvar" -> assertEquals(java.lang.Long.parseUnsignedLong(expected), cursor.unsignedBits())
                "svar" -> assertEquals(expected.toLong(), cursor.signed())
                else -> fail<Unit>("Unknown shared primitive vector")
            }
            cursor.expectEnd()
        }
    }

    @Test fun sharedCbdHeaderDescribesMemberRelativePayloadWithoutScanningIt() {
        val header = hex(Files.readString(directory.resolve("cbd-header-v1.hex")))
        assertEquals(32, header.size)
        val format = CoreCompactFormat.read(MemorySegment.ofArray(header), listOf(2, 3, 0, 0, 0, 24).map(Int::toLong))
        assertEquals(CoreCompactFormat.Span(32, 0), format.facts)
        assertEquals(CoreCompactFormat.Span(0, 2), format[CoreCompactFormat.Segment.DATA])
        assertEquals(CoreCompactFormat.Span(0, 3), format[CoreCompactFormat.Segment.STRINGS])
        assertEquals(CoreCompactFormat.Span(0, 24), format[CoreCompactFormat.Segment.SYMBOLS])
        assertEquals(1L, format.bindingCount)
        assertEquals(10, format.summaries)
        assertEquals(0, format.debug)
    }
}
