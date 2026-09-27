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

    @Test fun sharedHeaderAndFooterLocateOpaquePayloadWithoutScanningIt() {
        val header = hex(Files.readString(directory.resolve("header-v1.hex")))
        val footer = hex(Files.readString(directory.resolve("footer-v1.hex")))
        assertEquals(24, header.size)
        assertEquals(128, footer.size)
        val bytes = header + ByteArray(29) { 255.toByte() } + footer
        assertEquals(181, bytes.size)
        val format = CoreCompactFormat.read(MemorySegment.ofArray(bytes))
        assertEquals(CoreCompactFormat.Span(24, 0), format.facts)
        assertEquals(CoreCompactFormat.Span(24, 2), format[CoreCompactFormat.Segment.DATA])
        assertEquals(CoreCompactFormat.Span(26, 3), format[CoreCompactFormat.Segment.STRINGS])
        assertEquals(CoreCompactFormat.Span(29, 24), format[CoreCompactFormat.Segment.SYMBOLS])
        assertEquals(1L, format.bindingCount)
        assertEquals(10, format.summaries)
        assertEquals(0, format.debug)
    }
}
