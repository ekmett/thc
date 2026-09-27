// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class CoreCompactFileTest {
    @TempDir lateinit var directory: Path
    private val path get() = directory.resolve("module.thc")
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun md5(id: String) = MessageDigest.getInstance("MD5").digest(id.toByteArray(Charsets.UTF_8))

    private fun fixture(ids: List<String> = listOf("unit:M.f")): ByteArray {
        val strings = "é😀".toByteArray(Charsets.UTF_8)
        val sizes = listOf(ids.size, strings.size, 1, 1, 1, ids.size * 24)
        val out = ByteBuffer.allocate(24 + 1 + sizes.sum() + 128).order(ByteOrder.LITTLE_ENDIAN)
        out.put(byteArrayOf(84, 72, 67, 67, 77, 80, 0, 0)).putShort(1).putShort(0).putInt(0).putLong(1)
        out.put(42) // Independently decodable model header fact.
        repeat(ids.size) { out.put((it and 127).toByte()) }
        out.put(strings).put(255.toByte()).put(255.toByte()).put(255.toByte())
        ids.mapIndexed { index, id -> md5(id) to index.toLong() }
            .sortedWith { a, b -> java.util.Arrays.compareUnsigned(a.first, b.first) }
            .forEach { (digest, offset) -> out.put(digest).putLong(offset) }
        out.put(byteArrayOf(84, 72, 67, 67, 69, 78, 68, 49))
        var at = 25L
        for (size in sizes) { out.putLong(at).putLong(size.toLong()); at += size }
        out.putLong(ids.size.toLong()).putInt(0).putInt(7).putLong(0)
        return out.array()
    }

    @Test fun constructionIsColdAndDigestLookupDoesNotDecodeBodiesStringsOrDebug() {
        val bytes = fixture((0 until 1024).map { "unit:M.f$it" })
        CoreFileMappings(1024 * 1024, 4).use { cache ->
            CoreCompactFile(path, sha(bytes), mappings = cache).use { file ->
                assertEquals(0L, cache.statistics().mappingOpens)
                Files.write(path, bytes)
                assertEquals(250L, file.lookup("unit:M.f250"))
                val counts = file.counters.statistics()
                assertEquals(1L, counts.acquisitions)
                assertEquals(152L, counts.headerBytesRead)
                assertTrue(counts.lookupComparisons <= 11)
                assertTrue(counts.lookupBytesRead <= 16 * 11 + 8)
                assertEquals(0L, counts.hashBytesRead)
                assertEquals(0L, counts.dataBytesRead)
                assertEquals(0L, counts.stringBytesRead)
                assertEquals(0L, counts.debugBytesRead)
                assertNull(file.lookup("unit:M.missing"))
                assertEquals(122L, file.data(250) { it.unsigned() })
                assertEquals("é😀", file.string(0, 6))
                assertEquals(42L, file.facts { it.unsigned().also { _ -> it.expectEnd() } })
                assertEquals(0L, file.counters.statistics().debugBytesRead)
            }
        }
    }

    @Test fun activeAndSequentialLoadsReuseOneMappingButOwnCountersAndLifetimes() {
        val bytes = fixture()
        Files.write(path, bytes)
        CoreFileMappings(1024 * 1024, 4).use { cache ->
            val first = CoreCompactFile(path, sha(bytes), mappings = cache)
            val second = CoreCompactFile(path, sha(bytes), mappings = cache)
            first.use {
                second.use {
                    assertEquals(0L, first.lookup("unit:M.f"))
                    assertEquals(0L, second.lookup("unit:M.f"))
                    assertEquals(1L, cache.statistics().mappingOpens)
                    assertEquals(1L, second.counters.statistics().cacheHits)
                    first.close()
                    assertEquals(0L, second.data(0) { it.unsigned() })
                    assertThrows(IllegalStateException::class.java) { first.header() }
                    assertThrows(IllegalStateException::class.java) { first.lookup("unit:M.f") }
                }
            }
            CoreCompactFile(path, sha(bytes), mappings = cache).use {
                assertEquals(0L, it.lookup("unit:M.f"))
                assertEquals(1L, it.counters.statistics().cacheHits)
                assertEquals(1L, cache.statistics().mappingOpens)
            }
        }
    }

    @Test fun explicitVerificationUsesFreshMappingEvenAfterTrustedAdmission() {
        val bytes = fixture()
        Files.write(path, bytes)
        CoreFileMappings(1024 * 1024, 4).use { cache ->
            CoreCompactFile(path, sha(bytes), mappings = cache).use { it.header() }
            repeat(2) {
                CoreCompactFile(path, sha(bytes), true, cache).use { verified ->
                    assertEquals(0L, verified.lookup("unit:M.f"))
                    assertEquals(bytes.size.toLong(), verified.counters.statistics().hashBytesRead)
                    assertEquals(0L, verified.counters.statistics().cacheHits)
                    assertEquals(1L, verified.counters.statistics().physicalOpens)
                }
            }
            CoreCompactFile(path, "0".repeat(64), true, cache).use {
                assertThrows(IllegalArgumentException::class.java) { it.header() }
            }
            assertEquals(0L, cache.statistics().activeLeases)
        }
    }

    @Test fun failedOpenCanBeRetriedAndClosedOwnerCannotDecodeCachedBytes() {
        val bytes = fixture()
        CoreFileMappings(1024 * 1024, 4).use { cache ->
            CoreCompactFile(path, sha(bytes), mappings = cache).use { file ->
                assertThrows(java.nio.file.NoSuchFileException::class.java) { file.header() }
                Files.write(path, bytes)
                assertEquals(1L, file.header().bindingCount)
                file.close()
                assertThrows(IllegalStateException::class.java) { file.string(0, 1) }
                assertThrows(IllegalStateException::class.java) { file.data(0) { it.byte() } }
            }
            assertEquals(1L, cache.statistics().failedOpens)
        }
    }

    @Test fun invalidSelectedOffsetsAndLocalRecordBoundsNeverEscapeSegments() {
        val bytes = fixture()
        val footer = bytes.size - 128
        val lookup = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getLong(footer + 88).toInt()
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putLong(lookup + 16, Long.MAX_VALUE)
        Files.write(path, bytes)
        CoreFileMappings(1024 * 1024, 4).use { cache ->
            CoreCompactFile(path, sha(bytes), mappings = cache).use { file ->
                assertThrows(IllegalArgumentException::class.java) { file.lookup("unit:M.f") }
                assertThrows(IllegalArgumentException::class.java) { file.string(5, 2) }
                assertThrows(IllegalArgumentException::class.java) { file.data(1) { it.byte() } }
                assertThrows(IllegalArgumentException::class.java) { file.debug(CoreCompactFormat.Segment.DATA) { it.byte() } }
            }
        }
    }
}
