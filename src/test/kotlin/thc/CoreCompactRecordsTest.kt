// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import thc.runtime.CoreFloatingLiteral

class CoreCompactRecordsTest {
    @TempDir lateinit var directory: Path
    private fun bytes(vararg values: Int) = values.map(Int::toByte).toByteArray()
    private val id = "unit:M.f".toByteArray()
    private fun module(data: ByteArray, action: (CoreCompactRecords, CoreCompactFile) -> Unit) {
        val out = ByteBuffer.allocate(24 + data.size + id.size + 24 + 128).order(ByteOrder.LITTLE_ENDIAN)
        out.put(bytes(84, 72, 67, 67, 77, 80, 0, 0)).putShort(1).putShort(0).putInt(0).putLong(0)
        out.put(data).put(id).put(MessageDigest.getInstance("MD5").digest(id)).putLong(0)
        out.put(bytes(84, 72, 67, 67, 69, 78, 68, 49))
        var at = 24L
        for (size in listOf(data.size, id.size, 0, 0, 0, 24)) { out.putLong(at).putLong(size.toLong()); at += size }
        out.putLong(1).putInt(0).putInt(0).putLong(0)
        val encoded = out.array()
        val path = directory.resolve("module.thc")
        Files.write(path, encoded)
        val sha = MessageDigest.getInstance("SHA-256").digest(encoded).joinToString("") { "%02x".format(it) }
        CoreFileMappings(1024 * 1024, 1).use { cache ->
            CoreCompactFile(path, sha, mappings = cache).use { file -> action(CoreCompactRecords(file, sha), file) }
        }
    }
    private fun literal(kind: Int, payload: ByteArray) = bytes(2) + ByteArray(10) + bytes(kind) + payload
    private fun binding(expression: ByteArray) = bytes(0, 0, id.size, 0, 2, 1, 0, 0, 0, 0, 0, 0, 0) + expression

    @Test fun explicitLiteralRecordsPreserveRawBytesIntegersAndIeeeBits() {
        val cases = listOf(
            Triple(0, bytes(83), "int" to "-42"),
            Triple(9, bytes(255, 255, 255, 255, 255, 255, 255, 255, 255, 1), "word64" to "18446744073709551615"),
            Triple(10, bytes(3, 0, 0, 1), "bignat" to "65536"),
            Triple(12, bytes(4, 0, 255, 192, 128), "string-bytes" to "00ffc080"),
            Triple(13, bytes(0x34, 0x12, 0xc0, 0x7f), "float" to CoreFloatingLiteral.Single(0x7fc01234)),
            Triple(14, bytes(0, 0, 0, 0, 0, 0, 0, 128), "double" to CoreFloatingLiteral.Double(Long.MIN_VALUE)))
        for ((kind, payload, expected) in cases) module(binding(literal(kind, payload))) { records, file ->
            val selected = records.binding(0)
            assertEquals("unit:M.f", selected["id"])
            val expression = selected["expr"] as List<*>
            assertEquals(listOf("lit", expected.first, expected.second), expression.take(3))
            assertEquals(0L, file.counters.statistics().debugBytesRead)
            assertTrue(selected["compactOrigin"] is CoreCompactRecords.Origin)
        }
    }

    @Test fun exactShapeReferencesDoNotShareOccurrenceEvaluatedness() {
        val body = ByteArrayOutputStream()
        body.write(bytes(0, 0, id.size, 0, 2, 1, 0, 2)) // Binding with known rep.
        val definition = body.size()
        body.write(bytes(0, 0, 2, 1, 0, 0, 0, 0, 0, 0, 0)) // ShapeUse inline; long/[IntRep].
        body.write(bytes(2, 0)) // First occurrence is not evaluated.
        body.write(ByteArray(5)) // Remaining binding optional fields.
        body.write(bytes(2, 2, 1, definition, 2, 1)) // lit Meta.rep uses same shape, evaluated.
        body.write(ByteArray(9))
        body.write(bytes(0, 84)) // int42.
        module(body.toByteArray()) { records, _ ->
            val selected = records.binding(0)
            val bindingRep = selected["rep"] as Map<*, *>
            val expression = selected["expr"] as List<*>
            val occurrenceRep = (expression.last() as Map<*, *>)["rep"] as Map<*, *>
            assertEquals(false, bindingRep["evaluated"])
            assertEquals(true, occurrenceRep["evaluated"])
            assertEquals(bindingRep - "evaluated", occurrenceRep - "evaluated")
        }
    }

    @Test fun malformedLiteralBoundariesAndUnknownTypedTagsRejectLocally() {
        for (expression in listOf(literal(2, bytes(128, 2)), literal(6, bytes(128, 2)),
            literal(10, bytes(1, 0)), literal(12, bytes(127)), literal(19, byteArrayOf()), bytes(255) + ByteArray(10))) {
            module(binding(expression)) { records, _ -> assertThrows(RuntimeException::class.java) { records.binding(0) } }
        }
    }

    @Test fun sharedManualNestedShapesKeepEveryChildOccurrenceEvaluationDistinct() {
        val hex = Files.readString(Path.of("test/compact-core/golden/nested-shared-rep-v1.hex"))
        val bytes = hex.filterNot(Char::isWhitespace).chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        assertEquals(62, bytes.size)
        fun leaf(evaluated: Boolean) = mapOf("kind" to "long", "primReps" to listOf("IntRep"), "evaluated" to evaluated)
        fun tuple(evaluated: Boolean, vararg children: Map<String, Any?>): Map<String, Any?> = mapOf(
            "kind" to "unknown", "primReps" to List(children.size) { "IntRep" }, "aggregate" to "unboxed-tuple",
            "components" to children.toList(), "evaluated" to evaluated)
        val firstExpected = tuple(false, tuple(true, leaf(false)), tuple(false, leaf(true)))
        val secondExpected = tuple(true, tuple(false, leaf(true)), tuple(true, leaf(false)))
        module(bytes) { records, file ->
            val first = records.representation(0)
            assertEquals(firstExpected, first)
            assertEquals(50L, file.counters.statistics().dataBytesRead)
            val second = records.representation(50)
            assertEquals(secondExpected, second)
            assertEquals(62L, file.counters.statistics().dataBytesRead, "The prior shape must be reused, not decoded again")
            assertSame(first["primReps"], second["primReps"])
            assertNotSame(first["components"], second["components"])
            val children = first["components"] as List<*>
            assertNotSame(children[0], children[1])
            assertEquals(firstExpected, first, "Decoding the second occurrence must not alter the first tree")
            assertEquals(0L, file.counters.statistics().debugBytesRead)
        }
    }
}
