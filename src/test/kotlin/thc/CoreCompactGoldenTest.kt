// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc

import java.lang.foreign.MemorySegment
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.runtime.CoreSources

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

    @Suppress("UNCHECKED_CAST")
    @Test fun nativeProducerStoredDeflatedAndMixedGoldensKeepSelectedRecordsAndLazyDebug() {
        val expected = Json.parse(Files.readString(directory.resolve("cbd-module-v1.json"))) as Map<String, Any?>
        val expectedSection = CoreSources(expected).binding((expected["bindings"] as List<Map<String, Any?>>).first())!!.section!!
        for (encoding in listOf("stored", "deflated", "mixed")) {
            val path = directory.resolve("cbd-module-v1-$encoding.cbd")
            val identity = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))
                .joinToString("") { "%02x".format(it) }
            CoreFileMappings(0, 0).use { mappings -> CoreCbdSlabs(0, 0).use { slabs ->
                CoreCompactFile(path, identity, mappings = mappings, slabs = slabs).use { file ->
                    val records = CoreCompactRecords(file, identity)
                    val facts = records.header()
                    for (key in listOf("schema", "ghc", "unit", "module", "boundary", "constructors"))
                        assertEquals(expected[key], facts[key], "$encoding/$key")
                    assertEquals(0L, file.counters.statistics().dataBytesRead)
                    assertEquals(0L, file.counters.statistics().debugBytesRead)
                    val answer = records.binding(file.lookup("main:CBDGolden.answer")!!)
                    assertEquals(listOf("lit", "int", "42"), (answer["expr"] as List<*>).take(3))
                    val identityBinding = records.binding(file.lookup("main:CBDGolden.identity")!!)
                    val lambda = identityBinding["expr"] as List<*>
                    assertEquals("lam", lambda[0])
                    val formal = (lambda[1] as List<*>).single() as Map<*, *>
                    assertEquals(listOf("var", formal["id"]), (lambda[2] as List<*>).take(2))
                    assertEquals(0L, file.counters.statistics().debugBytesRead)
                    assertEquals(0L, file.counters.statistics().hashBytesRead)
                    if (encoding == "stored") assertEquals(0L, file.counters.statistics().inflatedBytes)
                    else assertTrue(file.counters.statistics().inflatedBytes > 0)
                    val origin = answer["compactOrigin"] as CoreCompactRecords.Origin
                    assertEquals("answer", origin.debug!!.name(origin.bindingOffset, 0))
                    val section = origin.debug.location(origin.dataOffset)!!.section!!
                    assertEquals("CBDGolden.hs", section.source.name)
                    // This original model supplies GHC line/column coordinates,
                    // not character offsets. Preserve the JSON route's exact
                    // unavailable-text policy rather than inventing offsets.
                    assertEquals(expectedSection.source.hasCharacters(), section.source.hasCharacters())
                    assertEquals(expectedSection.characters.toString(), section.characters.toString())
                    assertEquals(listOf(1, 1, 1, 11), listOf(section.startLine, section.startColumn, section.endLine, section.endColumn))
                    assertTrue(file.counters.statistics().debugBytesRead > 0)
                }
            } }
        }
    }
}
