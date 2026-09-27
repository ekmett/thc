// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class CoreJsonMd5SymbolsTest {
    @TempDir lateinit var directory: Path
    @AfterEach fun releaseIdleMappings() { CoreFileMappings.shared.evictIdleBelow(directory) }
    private val source get() = directory.resolve("core.jsons")
    private val symbols get() = directory.resolve("core.symbols")
    private fun md5(id: String) = MessageDigest.getInstance("MD5").digest(id.toByteArray(Charsets.UTF_8))
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun hex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun row(digest: ByteArray, offset: Long) = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        .put(digest).putLong(offset).array()
    private fun reader(verify: Boolean = false, sourceHash: String? = null, symbolHash: String? = null,
                       cache: CoreFileMappings = CoreFileMappings.shared) = CoreJsonSymbols(source, symbols, verify,
        sourceHash, symbolHash, cache, CoreJsonSymbols.Format.MD5_UTF8_U64LE)

    /** Independent test packaging, never an implicit runtime index builder. */
    private fun fixture(ids: List<String>, padding: Int = 0) {
        val out = java.io.ByteArrayOutputStream()
        out.write(" ".repeat(padding).toByteArray())
        val rows = ids.mapIndexed { index, id ->
            val offset = out.size().toLong()
            out.write(Json.stringify(mapOf("id" to id, "expr" to listOf("lit", "int", index.toString()),
                "unused" to "quoted } [ \\\" é😀")).toByteArray(Charsets.UTF_8))
            out.write(10)
            row(md5(id), offset)
        }.sortedWith { a, b -> java.util.Arrays.compareUnsigned(a, 0, 16, b, 0, 16) }
        Files.write(source, out.toByteArray())
        val directoryBytes = java.io.ByteArrayOutputStream()
        rows.forEach(directoryBytes::write)
        Files.write(symbols, directoryBytes.toByteArray())
    }

    @Test fun independentDigestVectorsAndLittleEndianOffsetsUseExactUtf8() {
        val vectors = listOf("" to "d41d8cd98f00b204e9800998ecf8427e",
            "abc" to "900150983cd24fb0d6963f7d28e17f72",
            "main:M.é😀" to "23415231b60de428eeaf32979e1cb8ce")
        for ((id, expected) in vectors) {
            assertArrayEquals(hex(expected), md5(id))
            Files.writeString(source, " ".repeat(257) + Json.stringify(mapOf("id" to id, "value" to 42)))
            Files.write(symbols, hex(expected) + byteArrayOf(1, 1, 0, 0, 0, 0, 0, 0))
            reader().use {
                assertEquals(0L, it.statistics().directoryOpens)
                assertEquals(42L, it.binding(id)!!["value"])
                assertEquals(24L, it.statistics().directoryMappedBytes)
                assertEquals(0L, it.statistics().hashBytesScanned)
            }
        }
    }

    @Test fun fixedRecordsFindOnlySelectedObjectsInDigestNotSourceOrder() {
        val ids = listOf("unit:M.z", "unit:M.😀", "unit:M.a space name", "unit:M.\ue000", "unit:M.a", "unit:M.λ")
        fixture(ids, 1024 * 1024)
        reader().use { index ->
            for ((position, id) in ids.withIndex()) {
                val binding = index.binding(id)!!
                assertEquals(position.toString(), (binding["expr"] as List<*>)[2])
                assertSame(binding, index.binding(id))
            }
            assertFalse(index.containsSymbol("unit:M.{foreign :: Addr#\n -> State# RealWorld}"))
            assertNull(index.binding("unit:M.absent"))
            assertEquals(ids.size.toLong(), index.statistics().decodedBindings)
            assertEquals(1L, index.statistics().sourceOpens)
            assertTrue(index.statistics().sourceByteReads < 4096)
        }
    }

    @Test fun missesEmptyAndMalformedDirectoriesNeverOpenJson() {
        Files.write(symbols, byteArrayOf())
        reader().use {
            assertFalse(it.containsSymbol("unit:M.missing"))
            assertEquals(0L, it.statistics().sourceOpens)
        }
        Files.write(symbols, ByteArray(23))
        reader().use {
            assertThrows(IllegalArgumentException::class.java) { it.binding("unit:M.missing") }
            assertEquals(0L, it.statistics().sourceOpens)
        }
        Files.write(symbols, row(md5("f"), Long.MIN_VALUE))
        reader().use {
            assertTrue(assertThrows(IllegalArgumentException::class.java) { it.binding("f") }
                .message.orEmpty().contains("JVM address range"))
            assertEquals(0L, it.statistics().sourceOpens)
        }
    }

    @Test fun selectedOffsetHasFullLongWidthAndLocalObjectBounds() {
        val offset = 0x0102030405060708L
        Files.writeString(source, "{}")
        Files.write(symbols, row(md5("f"), offset))
        reader().use {
            assertTrue(assertThrows(IllegalArgumentException::class.java) { it.binding("f") }
                .message.orEmpty().contains(offset.toString()))
        }
        Files.writeString(source, "{\"id\":\"f\",\"expr\":[")
        Files.write(symbols, row(md5("f"), 0))
        reader().use {
            val first = assertThrows(IllegalArgumentException::class.java) { it.binding("f") }
            val count = it.statistics().sourceByteReads
            assertSame(first, assertThrows(IllegalArgumentException::class.java) { it.binding("f") })
            assertEquals(count, it.statistics().sourceByteReads)
        }
    }

    @Test fun oneDemandUsesLogarithmicFixedRecordReadsAndNoOtherBody() {
        for (size in listOf(512, 1024)) {
            fixture((0 until size).reversed().map { "unit:M.f${it.toString().padStart(5, '0')}" })
            reader().use {
                assertEquals("unit:M.f00250", it.binding("unit:M.f00250")!!["id"])
                val counts = it.statistics()
                assertEquals(1L, counts.decodedBindings)
                assertTrue(counts.lookupComparisons <= 11)
                assertTrue(counts.directoryByteReads <= 16 * 11 + 8)
                assertEquals(24L * size, counts.directoryMappedBytes)
                assertTrue(counts.sourceByteReads < 512)
            }
        }
    }

    @Test fun sharedMappingsAndExplicitFreshVerificationKeepTheirExistingLifetime() {
        fixture(listOf("unit:M.f"))
        val sourceHash = sha(Files.readAllBytes(source))
        val symbolHash = sha(Files.readAllBytes(symbols))
        CoreFileMappings(1024 * 1024, 4).use { cache ->
            reader(false, sourceHash, symbolHash, cache).use { first ->
                val initial = first.binding("unit:M.f")
                reader(false, sourceHash, symbolHash, cache).use { second ->
                    assertEquals(initial, second.binding("unit:M.f"))
                    assertEquals(2L, second.statistics().mappingCacheHits)
                    first.close()
                    assertNotNull(second.binding("unit:M.f"))
                }
            }
            reader(true, sourceHash, symbolHash, cache).use {
                assertNull(it.binding("absent"))
                assertEquals(Files.size(symbols), it.statistics().hashBytesScanned)
                assertNotNull(it.binding("unit:M.f"))
                assertEquals(Files.size(source) + Files.size(symbols), it.statistics().hashBytesScanned)
                assertEquals(0L, it.statistics().mappingCacheHits)
            }
        }
    }
}
