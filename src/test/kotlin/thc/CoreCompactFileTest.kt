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
    private val path get() = directory.resolve("module.cbd")
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun md5(id: String) = MessageDigest.getInstance("MD5").digest(id.toByteArray(Charsets.UTF_8))

    @Test fun standardZipCbdHeaderIsAccepted() {
        val bytes = CoreCbdTestSupport.archive(CoreCbdTestSupport.header(byteArrayOf(42)))
        Files.write(path, bytes)
        CoreFileMappings(0, 0).use { cache ->
            CoreCompactFile(path, sha(bytes), mappings = cache).use { file ->
                assertEquals(0L, file.header().bindingCount)
                assertEquals(42, file.facts { it.byte() })
            }
        }
    }

    private fun fixture(ids: List<String> = listOf("unit:M.f"), invalidOffset: Boolean = false): ByteArray {
        val strings = "é😀".toByteArray(Charsets.UTF_8)
        val out = ByteBuffer.allocate(ids.size * 24).order(ByteOrder.LITTLE_ENDIAN)
        ids.mapIndexed { index, id -> md5(id) to index.toLong() }
            .sortedWith { a, b -> java.util.Arrays.compareUnsigned(a.first, b.first) }
            .forEach { (digest, offset) -> out.put(digest).putLong(if (invalidOffset) Long.MAX_VALUE else offset) }
        return CoreCbdTestSupport.archive(CoreCbdTestSupport.header(byteArrayOf(42), ids.size.toLong(), debug = 7),
            listOf(ByteArray(ids.size) { (it and 127).toByte() }, strings, byteArrayOf(-1), byteArrayOf(-1),
                byteArrayOf(-1), out.array()))
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
                assertEquals(32L, counts.headerBytesRead)
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
        val bytes = fixture(invalidOffset = true)
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
