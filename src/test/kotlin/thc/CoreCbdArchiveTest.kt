// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc

import java.lang.foreign.ValueLayout
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class CoreCbdArchiveTest {
    @TempDir lateinit var directory: Path
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun source(data: ByteArray = byteArrayOf(42), compressed: Set<String> = emptySet(), reverse: Boolean = false) =
        CoreCbdTestSupport.archive(segments = listOf(data, byteArrayOf(65), byteArrayOf(), byteArrayOf(), byteArrayOf(), byteArrayOf()),
            deflated = compressed, reverse = reverse)
    private fun write(bytes: ByteArray) = directory.resolve("module.cbd").also { Files.write(it, bytes) }

    @Test fun zip64SmallMembersUseCheckedLongSizesAndOffsets() {
        val bytes = CoreCbdTestSupport.zip64()
        CoreFileMappings(0, 0).use { maps -> CoreCbdArchive.open(maps.acquire(write(bytes), sha(bytes))).use { archive ->
            archive.read("data").use { assertEquals(42, it.bytes.get(ValueLayout.JAVA_BYTE, 0).toInt()) }
            assertEquals(32L, archive.member("header").length)
        } }
        // No allocation or narrowing can precede rejection of an unsigned size
        // outside the JVM's supported positive Long address space.
        for (at in listOf(40, bytes.size - 98 + 40, bytes.size - 98 + 48)) {
            val invalid = bytes.clone(); ByteBuffer.wrap(invalid).order(ByteOrder.LITTLE_ENDIAN).putLong(at, Long.MIN_VALUE)
            CoreFileMappings(0, 0).use { maps ->
                assertThrows(IllegalArgumentException::class.java) { CoreCbdArchive.open(maps.acquire(write(invalid), sha(invalid))).close() }
            }
        }
    }

    @Test fun offsetOnlyZip64CentralEntryAllowsOrdinarySmallLocalMember() {
        val bytes = CoreCbdTestSupport.zip64(offsetsOnly = true)
        CoreFileMappings(0, 0).use { maps -> CoreCbdArchive.open(maps.acquire(write(bytes), sha(bytes))).use { archive ->
            archive.read("data").use { assertEquals(42, it.bytes.get(ValueLayout.JAVA_BYTE, 0).toInt()) }
        } }
    }

    @Test fun allDeflatedIncludingEmptyMembersValidateStreamsAndDeclaredLengths() {
        val bytes = source(compressed = CoreCbdArchive.NAMES)
        CoreFileMappings(0, 0).use { maps -> CoreCbdSlabs(0, 0).use { slabs ->
            CoreCbdArchive.open(maps.acquire(write(bytes), sha(bytes)), slabs).use { archive ->
                for (name in CoreCbdArchive.NAMES) archive.read(name).use { assertEquals(archive.member(name).length, it.bytes.byteSize()) }
                assertEquals(7L, slabs.statistics().inflations)
            }
        } }
    }

    @Test fun storedAndCompressedHandlesOutliveArchiveAndCachesUntilTheirOwnClose() {
        for (compressed in listOf(emptySet(), setOf("data"))) {
            val bytes = source(compressed = compressed, reverse = true)
            val path = write(bytes)
            val mappings = CoreFileMappings(0, 0)
            val slabs = CoreCbdSlabs(0, 0)
            val archive = CoreCbdArchive.open(mappings.acquire(path, sha(bytes)), slabs)
            val handle = archive.read("data")
            val view = handle.bytes
            assertTrue(view.isReadOnly)
            archive.close(); mappings.close(); slabs.close()
            assertEquals(42, view.get(ValueLayout.JAVA_BYTE, 0).toInt())
            assertThrows(IllegalStateException::class.java) { archive.read("data") }
            handle.close(); handle.close()
            assertThrows(IllegalStateException::class.java) { handle.bytes }
            assertThrows(IllegalStateException::class.java) { view.get(ValueLayout.JAVA_BYTE, 0) }
            assertEquals(0L, mappings.statistics().activeLeases)
            assertEquals(0L, slabs.statistics().activeLeases)
            assertEquals(1L, mappings.statistics().mappingCloses)
        }
    }
    @Test fun retainPinsSameMappingEvenAfterCacheCloseWithoutNamedAcquisition() {
        val bytes = source()
        val mappings = CoreFileMappings(0, 0)
        val lease = mappings.acquire(write(bytes), sha(bytes))
        mappings.close()
        val retained = lease.retain()
        assertSame(lease.snapshot, retained.snapshot)
        assertFalse(retained.opened)
        lease.close()
        assertEquals(bytes.size.toLong(), retained.size)
        assertEquals(1L, mappings.statistics().mappingOpens)
        assertEquals(0L, mappings.statistics().mappingHits)
        retained.close()
        assertEquals(1L, mappings.statistics().mappingCloses)
    }
    @Test fun mixedMembersInflateOnlyOnDemandAndSequentialContextsReuseSlabs() {
        val segments = listOf(ByteArray(1000) { 42 }, byteArrayOf(65), byteArrayOf(-1), byteArrayOf(-1), byteArrayOf(-1), byteArrayOf())
        val bytes = CoreCbdTestSupport.archive(CoreCbdTestSupport.header(debug = 7), segments,
            setOf("data", "names", "filenames", "line-columns"))
        val path = write(bytes)
        CoreFileMappings(100000, 1).use { maps -> CoreCbdSlabs(100000, 7).use { slabs ->
            repeat(2) { iteration -> CoreCompactFile(path, sha(bytes), mappings = maps, slabs = slabs).use { file ->
                file.header()
                assertEquals(0L, file.counters.statistics().memberInflations)
                assertEquals(42, file.data(0) { it.readByte() })
                assertEquals(1L, file.counters.statistics().dataBytesRead)
                assertEquals(if (iteration == 0) 1000L else 0L, file.counters.statistics().inflatedBytes)
                assertEquals(iteration.toLong(), file.counters.statistics().slabCacheHits)
                assertEquals(0L, file.counters.statistics().debugBytesRead)
                assertEquals(0L, file.counters.statistics().hashBytesRead)
            } }
            assertEquals(1L, slabs.statistics().inflations)
            assertEquals(1L, maps.statistics().mappingOpens)
        } }
    }
    @Test fun simultaneousFirstDemandPublishesOneSharedReadOnlySlab() {
        val bytes = source(ByteArray(1024 * 1024) { (it and 255).toByte() }, setOf("data"))
        val path = write(bytes)
        CoreFileMappings(100000, 1).use { maps -> CoreCbdSlabs(0, 0).use { slabs ->
            val archives = List(8) { CoreCbdArchive.open(maps.acquire(path, sha(bytes)), slabs) }
            val start = CountDownLatch(1)
            Executors.newFixedThreadPool(8).use { executor ->
                val jobs = archives.map { archive -> executor.submit(Callable { start.await(); archive.read("data") }) }
                start.countDown()
                val handles = jobs.map { it.get(10, TimeUnit.SECONDS) }
                try {
                    assertEquals(1L, slabs.statistics().inflations)
                    assertEquals(7L, slabs.statistics().hits)
                    assertEquals(8L, slabs.statistics().activeLeases)
                    assertEquals(1, handles.count { it.inflated })
                    handles.forEach { assertEquals(handles.first().bytes.address(), it.bytes.address()) }
                    archives.forEach { it.close() }
                    handles.forEach { assertEquals(-1, it.bytes.get(ValueLayout.JAVA_BYTE, 255).toInt()) }
                } finally { handles.forEach { it.close() }; archives.forEach { it.close() } }
                assertEquals(1L, slabs.statistics().closes)
            }
        } }
    }
    private fun central(bytes: ByteArray): List<Int> {
        val view = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var at = view.getInt(bytes.size - 6)
        return List(7) {
            val current = at
            at += 46 + (view.getShort(at + 28).toInt() and 65535) + (view.getShort(at + 30).toInt() and 65535) +
                (view.getShort(at + 32).toInt() and 65535)
            current
        }
    }
    @Test fun crcFailureIsUnpublishedAndRetryDoesNotReusePoisonedSlab() {
        val bytes = source(compressed = setOf("data"))
        val view = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val data = central(bytes)[1]
        val local = view.getInt(data + 42)
        val payload = local + 30 + (view.getShort(local + 26).toInt() and 65535) + (view.getShort(local + 28).toInt() and 65535)
        val descriptor = payload + view.getInt(data + 20)
        val badCrc = view.getInt(data + 16) xor 1
        view.putInt(data + 16, badCrc); view.putInt(descriptor + 4, badCrc)
        CoreFileMappings(0, 0).use { maps -> CoreCbdSlabs(1000, 1).use { slabs ->
            CoreCbdArchive.open(maps.acquire(write(bytes), sha(bytes)), slabs).use { archive ->
                repeat(2) { assertThrows(IllegalArgumentException::class.java) { archive.read("data") } }
                assertEquals(2L, slabs.statistics().failures)
                assertEquals(0L, slabs.statistics().inflations)
                assertEquals(0L, slabs.statistics().activeLeases)
                assertEquals(0, slabs.statistics().idleEntries)
                archive.read("strings").use { assertEquals(65, it.bytes.get(ValueLayout.JAVA_BYTE, 0).toInt()) }
            }
        } }
    }
    @Test fun malformedDirectoryLocalHeadersAndExtentsRejectWithoutPayloadReads() {
        val original = source()
        val offsets = central(original)
        val mutations = listOf<(ByteBuffer) -> Unit>(
            { it.putShort(offsets[0] + 10, 12) }, // Unsupported method.
            { it.putShort(offsets[0] + 8, 1) }, // Encryption.
            { it.putShort(4, 46) }, // Unsupported local extraction version.
            { it.putInt(offsets[0] + 42, Int.MAX_VALUE) },
            { it.putInt(offsets[0] + 24, Int.MAX_VALUE) },
            { it.putInt(offsets[0] + 16, 0) }, // Local/central CRC differs.
            { it.putShort(26, 12) }, // Local/central names differ.
            { it.putShort(original.size - 12, 6) },
            { it.putShort(original.size - 18, 1) },
            { it.putInt(original.size - 6, -1) },
            { it.putInt(offsets[1] + 42, 0) })
        val candidates = mutations.map { edit -> original.clone().also { edit(ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN)) } } +
            listOf(original.copyOf(original.size - 1), original.copyOf(20))
        for (bytes in candidates) CoreFileMappings(0, 0).use { maps ->
            assertThrows(IllegalArgumentException::class.java) { CoreCbdArchive.open(maps.acquire(write(bytes), sha(bytes))).close() }
            assertEquals(0L, maps.statistics().activeLeases)
        }
    }
    @Test fun storedCrcIsCheckedOnlyByExplicitFreshVerification() {
        val bytes = source()
        val view = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val data = central(bytes)[1]
        val local = view.getInt(data + 42)
        view.putInt(data + 16, 0); view.putInt(local + 14, 0)
        val path = write(bytes)
        CoreFileMappings(10000, 2).use { maps ->
            CoreCompactFile(path, sha(bytes), mappings = maps).use { file ->
                assertEquals(42, file.data(0) { it.readByte() })
                assertEquals(0L, file.counters.statistics().verifiedStoredBytes)
                assertEquals(0L, file.counters.statistics().hashBytesRead)
            }
            CoreCompactFile(path, sha(bytes), true, maps).use { file ->
                assertThrows(IllegalArgumentException::class.java) { file.header() }
                assertEquals(bytes.size.toLong(), file.counters.statistics().hashBytesRead)
                assertEquals(1L, file.counters.statistics().physicalOpens)
            }
            assertEquals(2L, maps.statistics().mappingOpens)
        }
    }

    @Test fun verifiedInflationUsesFreshSnapshotRatherThanTrustedCachedSlabs() {
        val bytes = source(compressed = CoreCbdArchive.NAMES)
        val path = write(bytes)
        CoreFileMappings(100000, 4).use { maps -> CoreCbdSlabs(100000, 32).use { slabs ->
            CoreCompactFile(path, sha(bytes), mappings = maps, slabs = slabs).use { it.header() }
            repeat(2) { CoreCompactFile(path, sha(bytes), true, maps, slabs).use { file ->
                file.header()
                val counters = file.counters.statistics()
                assertEquals(7L, counters.memberInflations)
                assertEquals(0L, counters.slabCacheHits)
                assertEquals(bytes.size.toLong(), counters.hashBytesRead)
            } }
            assertEquals(15L, slabs.statistics().inflations)
            assertEquals(3L, maps.statistics().mappingOpens)
        } }
    }

    @Test fun duplicateRequiredNamesAndWrongInflatedLengthsReject() {
        val duplicate = source()
        val view = ByteBuffer.wrap(duplicate).order(ByteOrder.LITTLE_ENDIAN)
        val entry = central(duplicate)[2] // strings and symbols have equal name widths.
        val local = view.getInt(entry + 42)
        "symbols".toByteArray().copyInto(duplicate, entry + 46)
        "symbols".toByteArray().copyInto(duplicate, local + 30)
        CoreFileMappings(0, 0).use { maps ->
            assertThrows(IllegalArgumentException::class.java) { CoreCbdArchive.open(maps.acquire(write(duplicate), sha(duplicate))).close() }
        }
        for (length in listOf(0, 2)) {
            val bytes = source(compressed = setOf("data"))
            val fields = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val data = central(bytes)[1]
            val at = fields.getInt(data + 42)
            val payload = at + 30 + (fields.getShort(at + 26).toInt() and 65535) + (fields.getShort(at + 28).toInt() and 65535)
            val descriptor = payload + fields.getInt(data + 20)
            fields.putInt(data + 24, length); fields.putInt(descriptor + 12, length)
            CoreFileMappings(0, 0).use { maps -> CoreCbdSlabs(0, 0).use { slabs ->
                CoreCbdArchive.open(maps.acquire(write(bytes), sha(bytes)), slabs).use { archive ->
                    assertThrows(IllegalArgumentException::class.java) { archive.read("data") }
                    assertEquals(0L, slabs.statistics().activeLeases)
                    assertEquals(0L, slabs.statistics().inflations)
                }
            } }
        }
    }
}
