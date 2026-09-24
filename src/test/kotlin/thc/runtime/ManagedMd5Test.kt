// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import java.io.File
import java.nio.ByteOrder
import java.security.MessageDigest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.Json

/** Direct checked C ABI tests, not public Haskell Fingerprint/FFI execution. */
class ManagedMd5Test {
    private lateinit var cbitsContext: org.graalvm.polyglot.Context
    @org.junit.jupiter.api.BeforeEach fun enterCbitsContext() {
        cbitsContext = org.graalvm.polyglot.Context.newBuilder("thc").allowNativeAccess(true).build()
        cbitsContext.initialize("thc")
        cbitsContext.enter()
    }
    @org.junit.jupiter.api.AfterEach fun closeCbitsContext() {
        cbitsContext.leave()
        cbitsContext.close()
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
    private fun unhex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun address(bytes: ByteArray, offset: Int = 0) = ManagedAddress.fromByteArray(bytes).plus(offset.toLong())
    private fun word(bytes: ByteArray, offset: Int, value: Long) {
        for (byte in 0..3) {
            val shift = 8 * if (ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN) byte else 3 - byte
            bytes[offset + byte] = (value ushr shift).toByte()
        }
    }

    @Test fun publishedDigestVectorsAndLiteralInputUseOnlyVisibleContext() {
        val vectors = linkedMapOf(
            "" to "d41d8cd98f00b204e9800998ecf8427e",
            "a" to "0cc175b9c0f1b6a831c399e269772661",
            "abc" to "900150983cd24fb0d6963f7d28e17f72",
            "message digest" to "f96b697d7cb7938d525a2f31aaf161d0",
            "abcdefghijklmnopqrstuvwxyz" to "c3fcd3d76192e4007dfb496cca67e13b",
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789" to "d174ab98d277d9f5a5611c2c9f419d9f",
            "12345678901234567890123456789012345678901234567890123456789012345678901234567890" to
                "57edf4a22be3c955ac49da2e2107b67a")
        for ((text, expected) in vectors) for (split in 0..text.length) {
            val backing = ByteArray(96) { 0xa5.toByte() }
            val output = ByteArray(24) { 0xd3.toByte() }
            val context = address(backing, 4)
            ManagedMd5.init(context)
            assertArrayEquals(ByteArray(64) { 0xa5.toByte() }, backing.copyOfRange(28, 92))
            val input = ManagedAddress.fromHex(hex(text.toByteArray(Charsets.US_ASCII)))
            ManagedMd5.update(context, input, split.toLong())
            // Copying all context bytes suffices to resume; no hidden identity state.
            val resumed = backing.copyOf()
            ManagedMd5.update(address(resumed, 4), input.plus(split.toLong()), (text.length - split).toLong())
            ManagedMd5.finish(address(output, 3), address(resumed, 4))
            assertEquals(expected, hex(output.copyOfRange(3, 19)), "text=$text/split=$split")
            assertArrayEquals(ByteArray(88), resumed.copyOfRange(4, 92))
            assertTrue(resumed.take(4).all { it == 0xa5.toByte() })
            assertTrue(resumed.drop(92).all { it == 0xa5.toByte() })
            assertTrue(output.take(3).all { it == 0xd3.toByte() })
            assertTrue(output.drop(19).all { it == 0xd3.toByte() })
        }
    }

    private fun unchanged(vararg arrays: ByteArray, action: () -> Unit) {
        val before = arrays.map { it.copyOf() }
        assertThrows(RuntimeFault::class.java) { action() }
        for (index in arrays.indices) assertArrayEquals(before[index], arrays[index], "failed call storage $index")
    }

    @Test fun malformedLengthsRangesAndLiteralDestinationsFailBeforeAnyEffect() {
        val bytes = ByteArray(88) { 0xa5.toByte() }
        val input = ByteArray(64) { (it * 73 + 128).toByte() }
        val output = ByteArray(16) { 0xd3.toByte() }
        val context = address(bytes)
        for (length in listOf(Long.MIN_VALUE, -1L, Int.MAX_VALUE.toLong() + 1, Long.MAX_VALUE, 65L))
            unchanged(bytes, input) { ManagedMd5.update(context, address(input), length) }
        val short = ByteArray(87) { 0x6b.toByte() }
        unchanged(short) { ManagedMd5.init(address(short)) }
        unchanged(short, input) { ManagedMd5.update(address(short), address(input), 0) }
        unchanged(short, output) { ManagedMd5.finish(address(output), address(short)) }
        unchanged(bytes) { ManagedMd5.init(context.plus(1)) }
        unchanged(bytes, input) { ManagedMd5.update(context.plus(1), address(input), 0) }
        unchanged(bytes, output) { ManagedMd5.finish(address(output).plus(1), context) }
        unchanged(bytes, output) { ManagedMd5.finish(address(output), context.plus(1)) }
        // This address is in bounds, but a cast to GHC's four-byte-aligned
        // MD5Context would violate the native ABI.
        val unaligned = ByteArray(96) { 0xa5.toByte() }
        for (offset in listOf(1, 2, 3, 5)) {
            val bad = address(unaligned, offset)
            unchanged(unaligned) { ManagedMd5.init(bad) }
            unchanged(unaligned, input) { ManagedMd5.update(bad, address(input), 0) }
            unchanged(unaligned, output) { ManagedMd5.finish(address(output), bad) }
        }
        val literal = ManagedAddress.fromHex("a5".repeat(88))
        val literalBefore = (0L..88L).map { literal.readWord8(it) }
        assertThrows(RuntimeFault::class.java) { ManagedMd5.init(literal) }
        unchanged(input) { ManagedMd5.update(literal, address(input), 0) }
        unchanged(output) { ManagedMd5.finish(address(output), literal) }
        unchanged(bytes) { ManagedMd5.finish(literal, context) }
        assertEquals(literalBefore, (0L..88L).map { literal.readWord8(it) })
        ManagedMd5.init(context)
        val before = bytes.copyOf()
        ManagedMd5.update(context, address(input).plus(64), 0)
        assertArrayEquals(before, bytes, "zero-length one-past input")
        val empty = address(ByteArray(0))
        ManagedMd5.update(context, empty, 0)
        assertArrayEquals(before, bytes, "zero-length empty input")
    }

    @Test fun everyMemcpyOverlapIsRejectedBeforeEvenEarlierDisjointCopies() {
        val backing = ByteArray(256) { (it * 17 + 53).toByte() }
        val context = address(backing, 64)
        ManagedMd5.init(context)
        unchanged(backing) { ManagedMd5.update(context, address(backing, 88), 1) }
        // First copy: source[0,64), destination[88,152), disjoint.
        // Second copy: source[64,128), destination[88,152), overlapping.
        unchanged(backing) { ManagedMd5.update(context, address(backing), 128) }
        for (offset in listOf(49, 56, 64, 65, 79))
            unchanged(backing) { ManagedMd5.finish(address(backing, offset), context) }
        ManagedMd5.update(context, address(backing, 88), 0) // Empty memcpy has no overlap.
    }

    @Test fun exactNativeContextSnapshotsAndDefinedAliasesMatchPinnedC() {
        val root = File(System.getProperty("thc.projectRoot"))
        val directory = File(root, "build/managed-md5-native")
        val manifest = Json.parse(File(directory, "provenance.json").readText()) as Map<String, Any?>
        assertEquals(1L, (manifest["schema"] as Number).toLong())
        assertEquals(88L, (manifest["contextSize"] as Number).toLong())
        assertEquals(4L, (manifest["contextAlignment"] as Number).toLong())
        assertEquals(listOf(0L, 16L, 24L), (manifest["contextOffsets"] as List<Number>).map { it.toLong() })
        assertEquals(if (ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN) "little" else "big", manifest["byteOrder"])
        assertEquals(true, manifest["independentModelMatched"])
        assertEquals(mapOf("md5.c" to "4fa83bda7aacc8a1656d7e2d78251bbe70a04b56",
            "md5.h" to "a87296687a2f3dc6748264ff2a8a0c919518db55"), manifest["referenceGitBlobs"])
        val sources = manifest["sources"] as List<Map<String, String>>
        assertTrue(sources.any { it["path"] == "src/main/kotlin/thc/runtime/ManagedMd5.kt" })
        assertTrue(sources.any { it["path"] == "src/main/kotlin/thc/runtime/LiteralAddresses.kt" })
        for (record in sources + (manifest["artifacts"] as List<Map<String, String>>) +
            (manifest["tools"] as List<Map<String, String>>)) {
            val path = File(record.getValue("path"))
            val file = if (path.isAbsolute) path else File(root, record.getValue("path"))
            assertEquals(record["sha256"], hex(MessageDigest.getInstance("SHA-256").digest(file.readBytes())), file.path)
        }
        val inventory = mutableListOf<List<Long>>()
        for (length in listOf(0, 1, 2, 7, 15, 16, 31, 55, 56, 57, 63, 64, 65, 95, 119, 120, 127, 128, 129, 255, 256, 257, 1024))
            for (split in listOf(0, 1, length / 2, 55, 56, 63, 64, length).map { minOf(it, length) }.distinct())
                for (seed in listOf(0, 1, 127, 255)) inventory.add(listOf(length.toLong(), split.toLong(), seed.toLong(), 0, 0))
        for (high in listOf(0L, 0xffff_ffffL)) for (length in listOf(17L, 64L, 129L)) for (split in listOf(0L, 8L, 16L))
            inventory.add(listOf(length, split, 127L, 0xffff_fff0L, high))
        assertEquals(inventory.size.toLong(), (manifest["cases"] as Number).toLong())
        assertEquals(4L * inventory.size, (manifest["caseRows"] as Number).toLong())
        assertEquals((inventory.size - 18).toLong(), (manifest["hashlibCases"] as Number).toLong())
        assertEquals(5L, (manifest["aliasCases"] as Number).toLong())
        assertEquals(15L, (manifest["aliasRows"] as Number).toLong())
        val lines = File(directory, "native.stdout").readLines()
        assertEquals(listOf("layout", "88", "0", "16", "24", "4", manifest["byteOrder"]), lines.first().split('\t'))
        var cursor = 1
        for ((id, metadata) in inventory.withIndex()) {
            val (length, split, seed, low, high) = metadata
            val co = id % 3 * 4; val io = listOf(0, 1, 7)[id % 3]; val oo = listOf(0, 3, 11)[id % 3]
            val bytes = ByteArray(104) { 0xa5.toByte() }
            val input = ByteArray(length.toInt() + 16) { 0x6b.toByte() }
            val output = ByteArray(32) { 0xd3.toByte() }
            for (i in 0 until length.toInt()) input[io + i] = (i * 73L + seed * 19 + (i shr 3)).toByte()
            val before = input.copyOf()
            val context = address(bytes, co)
            for (phase in 0..3) {
                when (phase) {
                    0 -> { ManagedMd5.init(context); word(bytes, co + 16, low); word(bytes, co + 20, high) }
                    1 -> ManagedMd5.update(context, address(input, io), split)
                    2 -> ManagedMd5.update(context, address(input, io).plus(split), length - split)
                    3 -> ManagedMd5.finish(address(output, oo), context)
                }
                val fields = lines[cursor++].split('\t')
                assertEquals(13, fields.size)
                assertEquals(listOf("case", id, length, split, seed, co, io, oo, low, high, phase).map { it.toString() }, fields.take(11))
                assertArrayEquals(unhex(fields[11]), bytes, "native context case=$id/phase=$phase")
                assertArrayEquals(unhex(fields[12]), output, "native output case=$id/phase=$phase")
                assertArrayEquals(before, input, "native input case=$id/phase=$phase")
            }
        }
        val aliasCases = listOf(Triple(32, 16, 160), Triple(48, 8, 160), Triple(112, 8, 160),
            Triple(128, 65, 56), Triple(128, 65, 112))
        for ((id, metadata) in aliasCases.withIndex()) {
            val (io, length, oo) = metadata
            val bytes = ByteArray(256) { (it * 17 + 0x35).toByte() }
            val context = address(bytes, 32)
            for (phase in 0..2) {
                when (phase) {
                    0 -> ManagedMd5.init(context)
                    1 -> ManagedMd5.update(context, address(bytes, io), length.toLong())
                    2 -> ManagedMd5.finish(address(bytes, oo), context)
                }
                val fields = lines[cursor++].split('\t')
                assertEquals(7, fields.size)
                assertEquals(listOf("alias", id, io, length, oo, phase).map { it.toString() }, fields.take(6))
                assertArrayEquals(unhex(fields[6]), bytes, "native alias=$id/phase=$phase")
            }
        }
        assertEquals(lines.size, cursor, "no omitted or surplus native rows")
    }
}
