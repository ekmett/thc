// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc

import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir

@Timeout(30)
class CoreFileMappingsTest {
    @TempDir lateinit var directory: Path
    private fun file(name: String, text: String): Path = directory.resolve(name).also { Files.writeString(it, text) }
    private fun text(segment: MemorySegment) = String(segment.toArray(ValueLayout.JAVA_BYTE), Charsets.UTF_8)

    @Test fun jsonAndIndexMappingsShareBytesButClosingOneConsumerKeepsTheOtherAlive() {
        val json = file("unit.jsons", "{\"id\":\"unit:Module.value\"}\n")
        val index = file("unit.symbols", "unit:Module.value 0\n")
        CoreFileMappings(1024, 2).use { cache ->
            val firstJson = cache.acquire(json, "json-v1")
            val firstIndex = cache.acquire(index, "index-v1")
            val otherJson = cache.acquire(json, "json-v1")
            val otherIndex = cache.acquire(index, "index-v1")
            try {
                assertTrue(firstJson.opened && firstIndex.opened)
                assertFalse(otherJson.opened || otherIndex.opened)
                assertSame(firstJson.bytes, otherJson.bytes)
                assertSame(firstIndex.bytes, otherIndex.bytes)
                assertTrue(otherJson.bytes.isReadOnly)
                assertThrows(IllegalArgumentException::class.java) { otherJson.bytes.set(ValueLayout.JAVA_BYTE, 0, 0.toByte()) }
                firstJson.close(); firstIndex.close()
                assertThrows(IllegalStateException::class.java) { firstJson.bytes }
                assertEquals("{\"id\":\"unit:Module.value\"}\n", text(otherJson.bytes))
                assertEquals("unit:Module.value 0\n", text(otherIndex.bytes))
                assertEquals(2L, cache.statistics().mappingOpens)
                assertEquals(2L, cache.statistics().mappingHits)
                assertEquals(2L, cache.statistics().activeMappings)
                assertEquals(2L, cache.statistics().activeLeases)
                assertEquals(0, cache.statistics().idleMappings)
            } finally { firstJson.close(); firstIndex.close(); otherJson.close(); otherIndex.close() }
            assertEquals(0L, cache.statistics().activeLeases)
            assertEquals(2, cache.statistics().idleMappings)
        }
    }

    @Test fun normalizedPathAndSequentialIdentityReuseNeedNoNewOpenOrExistenceProbe() {
        val source = file("unit.jsons", "original")
        CoreFileMappings(1024, 2).use { cache ->
            val bytes = cache.acquire(source, "v1").use { it.bytes }
            // This nonexistent intermediate path disappears by lexical normalization.
            val alias = directory.resolve("absent/../unit.jsons")
            Files.move(source, directory.resolve("moved.jsons"))
            cache.acquire(alias, "v1").use { lease ->
                assertFalse(lease.opened)
                assertSame(bytes, lease.bytes)
                assertEquals("original", text(lease.bytes))
            }
            assertEquals(1L, cache.statistics().mappingOpens)
            assertEquals(1, cache.statistics().idleMappings)
        }
    }

    @Test fun changedProducerIdentityMapsReplacementWithoutAffectingPinnedOldBytes() {
        val source = file("unit.jsons", "original")
        CoreFileMappings(1024, 2).use { cache ->
            cache.acquire(source, "v1").use { first ->
                val replacement = file("replacement.jsons", "replacement")
                Files.move(replacement, source, StandardCopyOption.REPLACE_EXISTING)
                cache.acquire(source, "v2").use { second ->
                    assertTrue(second.opened)
                    assertNotSame(first.bytes, second.bytes)
                    assertEquals("original", text(first.bytes))
                    assertEquals("replacement", text(second.bytes))
                    assertEquals(2L, cache.statistics().mappingOpens)
                }
            }
        }
    }

    @Test fun idleByteLimitNeverEvictsAnActiveLease() {
        val a = file("a", "aaaa")
        val b = file("b", "bbbb")
        val c = file("c", "cccc")
        CoreFileMappings(6, 10).use { cache ->
            cache.acquire(a, "a").use { active ->
                val old = cache.acquire(b, "b").use { it.bytes }
                val recent = cache.acquire(c, "c").use { it.bytes }
                assertFalse(old.scope().isAlive)
                assertTrue(recent.scope().isAlive)
                assertEquals("aaaa", text(active.bytes))
                assertEquals(1L, cache.statistics().activeMappings)
                assertEquals(1, cache.statistics().idleMappings)
                assertEquals(4L, cache.statistics().idleBytes)
            }
            assertEquals(0L, cache.statistics().activeMappings)
            assertEquals(4L, cache.statistics().idleBytes)
        }
    }

    @Test fun idleEntryLimitUsesLastReleaseOrderAndReacquisitionRefreshesIt() {
        val a = file("a", "a")
        val b = file("b", "b")
        val c = file("c", "c")
        CoreFileMappings(1024, 2).use { cache ->
            val first = cache.acquire(a, "a").use { it.bytes }
            val oldest = cache.acquire(b, "b").use { it.bytes }
            cache.acquire(a, "a").use { assertSame(first, it.bytes) }
            val latest = cache.acquire(c, "c").use { it.bytes }
            assertTrue(first.scope().isAlive)
            assertFalse(oldest.scope().isAlive)
            assertTrue(latest.scope().isAlive)
            assertEquals(2, cache.statistics().idleMappings)
            assertEquals(1L, cache.statistics().mappingCloses)
        }
    }

    @Test fun oversizedAndZeroEntryBudgetsReleaseAtLastClose() {
        val source = file("large", "four")
        for (cache in listOf(CoreFileMappings(3, 10), CoreFileMappings(1024, 0))) cache.use {
            val first = cache.acquire(source, "v1")
            val second = cache.acquire(source, "v1")
            val bytes = second.bytes
            first.close()
            assertEquals("four", text(bytes))
            second.close(); second.close()
            assertFalse(bytes.scope().isAlive)
            assertEquals(1L, cache.statistics().mappingCloses)
            assertEquals(0, cache.statistics().idleMappings)
            assertEquals(0L, cache.statistics().activeLeases)
        }
    }

    @Test fun emptyMappingsConsumeAnIdleEntryEvenWithZeroByteBudget() {
        val a = file("a", "")
        val b = file("b", "")
        CoreFileMappings(0, 1).use { cache ->
            cache.acquire(a, "a").use { assertEquals(0L, it.size) }
            cache.acquire(b, "b").use { assertEquals(0L, it.size) }
            assertEquals(1, cache.statistics().idleMappings)
            assertEquals(0L, cache.statistics().idleBytes)
            assertEquals(1L, cache.statistics().mappingCloses)
        }
    }

    @Test fun failedOpenIsNotCachedAndCannotLeaveAnActiveOrIdleEntry() {
        val source = directory.resolve("later")
        CoreFileMappings(1024, 2).use { cache ->
            assertThrows(NoSuchFileException::class.java) { cache.acquire(source, "v1") }
            assertEquals(1L, cache.statistics().failedOpens)
            assertEquals(0L, cache.statistics().mappingOpens)
            assertEquals(0L, cache.statistics().activeLeases)
            assertEquals(0, cache.statistics().idleMappings)
            Files.writeString(source, "now present")
            cache.acquire(source, "v1").use { assertEquals("now present", text(it.bytes)) }
            assertEquals(1L, cache.statistics().mappingOpens)
        }
    }

    @Test fun closingCacheDropsIdleButPreservesActiveLeasesUntilTheirOwnClose() {
        val source = file("unit", "data")
        val cache = CoreFileMappings(1024, 2)
        val active = cache.acquire(source, "v1")
        val bytes = active.bytes
        val idle = cache.acquire(source, "other-producer-version").use { it.bytes }
        cache.close(); cache.close()
        assertFalse(idle.scope().isAlive)
        assertEquals("data", text(active.bytes))
        assertThrows(IllegalStateException::class.java) { cache.acquire(source, "v1") }
        active.close()
        assertFalse(bytes.scope().isAlive)
        assertThrows(IllegalStateException::class.java) { active.bytes }
        assertEquals(0L, cache.statistics().activeLeases)
        assertEquals(2L, cache.statistics().mappingCloses)
    }

    @Test fun missingIdentityUsesIndependentUncachedLifetimes() {
        val source = file("unit", "data")
        CoreFileMappings(1024, 2).use { cache ->
            assertThrows(IllegalArgumentException::class.java) { cache.acquire(source, "") }
            val first = cache.acquireUncached(source)
            val second = cache.acquireUncached(source)
            val bytes = first.bytes
            try {
                assertTrue(first.opened && second.opened)
                assertNotSame(bytes, second.bytes)
                first.close()
                assertFalse(bytes.scope().isAlive)
                assertEquals("data", text(second.bytes))
                assertEquals(0, cache.statistics().idleMappings)
            } finally { first.close(); second.close() }
            assertEquals(2L, cache.statistics().mappingCloses)
            assertEquals(0L, cache.statistics().mappingHits)
        }
    }

    @Test fun concurrentFirstAcquisitionOpensOnceAndSupportsCrossThreadRelease() {
        val source = file("unit", "shared")
        val workers = Executors.newFixedThreadPool(2)
        try {
            CoreFileMappings(1024, 2).use { cache ->
                val start = CountDownLatch(1)
                val futures = List(2) { workers.submit<CoreFileMappings.Lease> { start.await(); cache.acquire(source, "v1") } }
                start.countDown()
                val leases = futures.map { it.get(5, TimeUnit.SECONDS) }
                try {
                    assertEquals(1, leases.count { it.opened })
                    assertSame(leases[0].bytes, leases[1].bytes)
                    workers.submit { leases[0].close() }.get(5, TimeUnit.SECONDS)
                    assertEquals("shared", workers.submit<String> { text(leases[1].bytes) }.get(5, TimeUnit.SECONDS))
                    assertEquals(1L, cache.statistics().mappingOpens)
                    assertEquals(1L, cache.statistics().activeLeases)
                } finally { leases.forEach { it.close() } }
            }
        } finally { workers.shutdownNow() }
    }
}
