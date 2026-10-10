// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
class CoreFileMappingsTest {
    @TempDir Path directory;
    private Path file(String name, String text) throws Exception { return Files.writeString(directory.resolve(name), text); }
    private String text(MemorySegment segment) { return new String(segment.toArray(ValueLayout.JAVA_BYTE), StandardCharsets.UTF_8); }
    @Test void checkpointRejectsLiveReadersThenDropsIdleArenasWithoutDisablingRestore() throws Exception {
        var source = file("checkpoint.cbd", "retained bytes");
        try (var cache = new CoreFileMappings(1024, 2)) {
            MemorySegment bytes;
            try (var lease = cache.acquire(source, "v1")) {
                bytes = lease.getBytes();
                assertThrows(IllegalStateException.class, cache::prepareCheckpoint);
                assertEquals("retained bytes", text(bytes));
            }
            cache.prepareCheckpoint();
            assertFalse(bytes.scope().isAlive());
            assertEquals(0, cache.statistics().idleMappings());
            try (var restored = cache.acquire(source, "v1")) {
                assertEquals("retained bytes", text(restored.getBytes()));
                assertTrue(restored.getOpened());
            }
        }
    }
    @Test void artifactMappingsShareBytesButClosingOneConsumerKeepsTheOtherAlive() throws Exception {
        var first = file("unit.cbd", "first artifact\n"); var second = file("other.cbd", "other artifact\n");
        try (var cache = new CoreFileMappings(1024, 2)) {
            var firstLease = cache.acquire(first, "first-v1"); var secondLease = cache.acquire(second, "second-v1");
            var otherLease = cache.acquire(first, "first-v1"); var otherSecond = cache.acquire(second, "second-v1");
            try {
                assertTrue(firstLease.getOpened() && secondLease.getOpened()); assertFalse(otherLease.getOpened() || otherSecond.getOpened());
                assertSame(firstLease.getBytes(), otherLease.getBytes()); assertSame(secondLease.getBytes(), otherSecond.getBytes());
                assertTrue(otherLease.getBytes().isReadOnly());
                assertThrows(IllegalArgumentException.class, () -> otherLease.getBytes().set(ValueLayout.JAVA_BYTE, 0, (byte) 0));
                firstLease.close(); secondLease.close(); assertThrows(IllegalStateException.class, firstLease::getBytes);
                assertEquals("first artifact\n", text(otherLease.getBytes())); assertEquals("other artifact\n", text(otherSecond.getBytes()));
                assertEquals(2L, cache.statistics().mappingOpens()); assertEquals(2L, cache.statistics().mappingHits());
                assertEquals(2L, cache.statistics().activeMappings()); assertEquals(2L, cache.statistics().activeLeases());
                assertEquals(0, cache.statistics().idleMappings());
            } finally { firstLease.close(); secondLease.close(); otherLease.close(); otherSecond.close(); }
            assertEquals(0L, cache.statistics().activeLeases()); assertEquals(2, cache.statistics().idleMappings());
        }
    }
    @Test void normalizedPathAndSequentialIdentityReuseNeedNoNewMapping() throws Exception {
        var source = file("unit.cbd", "original");
        try (var cache = new CoreFileMappings(1024, 2)) {
            MemorySegment bytes; try (var it = cache.acquire(source, "v1")) { bytes = it.getBytes(); }
            // This nonexistent intermediate path disappears by lexical normalization.
            var alias = directory.resolve("absent/../unit.cbd");
            try (var lease = cache.acquire(alias, "v1")) {
                assertFalse(lease.getOpened()); assertSame(bytes, lease.getBytes()); assertEquals("original", text(lease.getBytes()));
            }
            assertEquals(1L, cache.statistics().mappingOpens()); assertEquals(1, cache.statistics().idleMappings());
        }
    }
    @Test void explicitIdleEvictionAllowsSamePathReplacementWithChangedIdentity() throws Exception {
        var source = file("unit.cbd", "original");
        try (var cache = new CoreFileMappings(1024, 2)) {
            MemorySegment prior; try (var it = cache.acquire(source, "v1")) { prior = it.getBytes(); }
            assertEquals(1, cache.evictIdleBelow(source)); assertFalse(prior.scope().isAlive());
            var replacement = file("replacement.cbd", "replacement"); Files.move(replacement, source, StandardCopyOption.REPLACE_EXISTING);
            try (var second = cache.acquire(source, "v2")) {
                assertTrue(second.getOpened()); assertNotSame(prior, second.getBytes()); assertEquals("replacement", text(second.getBytes()));
                assertEquals(2L, cache.statistics().mappingOpens());
            }
        }
    }
    @Test void versionedPathsKeepOldAndNewProducerBytesAliveAtTheSameTime() throws Exception {
        var oldPath = file("unit-v1.cbd", "original"); var newPath = file("unit-v2.cbd", "replacement");
        try (var cache = new CoreFileMappings(1024, 2); var first = cache.acquire(oldPath, "v1"); var second = cache.acquire(newPath, "v2")) {
            assertTrue(second.getOpened()); assertNotSame(first.getBytes(), second.getBytes());
            assertEquals("original", text(first.getBytes())); assertEquals("replacement", text(second.getBytes()));
            assertEquals(0, cache.evictIdleBelow(directory), "active views are never forcibly unmapped");
            assertEquals("original", text(first.getBytes())); assertEquals("replacement", text(second.getBytes()));
        }
    }
    @Test void producerIdentitySeparatesSamePathMappingsAndIdleEvictionSkipsTheLiveVersion() throws Exception {
        var source = file("unit.cbd", "published");
        try (var cache = new CoreFileMappings(1024, 2); var active = cache.acquire(source, "v1")) {
            MemorySegment other;
            try (var it = cache.acquire(source, "v2")) { assertTrue(it.getOpened()); assertNotSame(active.getBytes(), it.getBytes()); other = it.getBytes(); }
            assertEquals(1, cache.evictIdleBelow(source)); assertFalse(other.scope().isAlive()); assertEquals("published", text(active.getBytes()));
            try (var it = cache.acquire(source, "v1")) { assertFalse(it.getOpened()); assertSame(active.getBytes(), it.getBytes()); }
            assertEquals(2L, cache.statistics().mappingOpens());
        }
    }
    @Test void scopedIdleEvictionAllowsTemporaryCleanupWithoutDisturbingOtherOrActiveMappings() throws Exception {
        var temporary = Files.createDirectory(directory.resolve("temporary")); var source = Files.writeString(temporary.resolve("unit.cbd"), "temporary");
        var sibling = file("temporary-other", "sibling"); var pinned = file("active", "pinned");
        try (var cache = new CoreFileMappings(1024, 8)) {
            MemorySegment old, other;
            try (var it = cache.acquire(source, "v1")) { old = it.getBytes(); }
            try (var it = cache.acquire(sibling, "v1")) { other = it.getBytes(); }
            try (var active = cache.acquire(pinned, "v1")) {
                assertEquals(0, cache.evictIdleBelow(pinned)); assertEquals(1, cache.evictIdleBelow(temporary.resolve("absent/..")));
                assertFalse(old.scope().isAlive()); assertTrue(other.scope().isAlive(), "path components, not string-prefix matching");
                assertEquals("pinned", text(active.getBytes())); Files.delete(source); Files.delete(temporary);
                assertEquals(1, cache.statistics().idleMappings()); assertEquals(1L, cache.statistics().activeMappings());
                assertEquals(0, cache.evictIdleBelow(temporary));
            }
        }
    }
    @Test void idleByteLimitNeverEvictsAnActiveLease() throws Exception {
        var a = file("a", "aaaa"); var b = file("b", "bbbb"); var c = file("c", "cccc");
        try (var cache = new CoreFileMappings(6, 10)) {
            try (var active = cache.acquire(a, "a")) {
                MemorySegment old, recent;
                try (var it = cache.acquire(b, "b")) { old = it.getBytes(); }
                try (var it = cache.acquire(c, "c")) { recent = it.getBytes(); }
                assertFalse(old.scope().isAlive()); assertTrue(recent.scope().isAlive()); assertEquals("aaaa", text(active.getBytes()));
                assertEquals(1L, cache.statistics().activeMappings()); assertEquals(1, cache.statistics().idleMappings()); assertEquals(4L, cache.statistics().idleBytes());
            }
            assertEquals(0L, cache.statistics().activeMappings()); assertEquals(4L, cache.statistics().idleBytes());
        }
    }
    @Test void idleEntryLimitUsesLastReleaseOrderAndReacquisitionRefreshesIt() throws Exception {
        var a = file("a", "a"); var b = file("b", "b"); var c = file("c", "c");
        try (var cache = new CoreFileMappings(1024, 2)) {
            MemorySegment first, oldest, latest;
            try (var it = cache.acquire(a, "a")) { first = it.getBytes(); }
            try (var it = cache.acquire(b, "b")) { oldest = it.getBytes(); }
            try (var it = cache.acquire(a, "a")) { assertSame(first, it.getBytes()); }
            try (var it = cache.acquire(c, "c")) { latest = it.getBytes(); }
            assertTrue(first.scope().isAlive()); assertFalse(oldest.scope().isAlive()); assertTrue(latest.scope().isAlive());
            assertEquals(2, cache.statistics().idleMappings()); assertEquals(1L, cache.statistics().mappingCloses());
        }
    }
    @Test void oversizedAndZeroEntryBudgetsReleaseAtLastClose() throws Exception {
        var source = file("large", "four");
        for (var cache : List.of(new CoreFileMappings(3, 10), new CoreFileMappings(1024, 0))) try (cache) {
            var first = cache.acquire(source, "v1"); var second = cache.acquire(source, "v1"); var bytes = second.getBytes();
            first.close(); assertEquals("four", text(bytes)); second.close(); second.close(); assertFalse(bytes.scope().isAlive());
            assertEquals(1L, cache.statistics().mappingCloses()); assertEquals(0, cache.statistics().idleMappings()); assertEquals(0L, cache.statistics().activeLeases());
        }
    }
    @Test void emptyMappingsConsumeAnIdleEntryEvenWithZeroByteBudget() throws Exception {
        var a = file("a", ""); var b = file("b", "");
        try (var cache = new CoreFileMappings(0, 1)) {
            try (var it = cache.acquire(a, "a")) { assertEquals(0L, it.getSize()); }
            try (var it = cache.acquire(b, "b")) { assertEquals(0L, it.getSize()); }
            assertEquals(1, cache.statistics().idleMappings()); assertEquals(0L, cache.statistics().idleBytes()); assertEquals(1L, cache.statistics().mappingCloses());
        }
    }
    @Test void failedOpenIsNotCachedAndCannotLeaveAnActiveOrIdleEntry() throws Exception {
        var source = directory.resolve("later");
        try (var cache = new CoreFileMappings(1024, 2)) {
            assertThrows(NoSuchFileException.class, () -> cache.acquire(source, "v1"));
            assertEquals(1L, cache.statistics().failedOpens()); assertEquals(0L, cache.statistics().mappingOpens());
            assertEquals(0L, cache.statistics().activeLeases()); assertEquals(0, cache.statistics().idleMappings());
            Files.writeString(source, "now present"); try (var it = cache.acquire(source, "v1")) { assertEquals("now present", text(it.getBytes())); }
            assertEquals(1L, cache.statistics().mappingOpens());
        }
    }
    @Test void closingCacheDropsIdleButPreservesActiveLeasesUntilTheirOwnClose() throws Exception {
        var source = file("unit", "data"); var cache = new CoreFileMappings(1024, 2);
        var active = cache.acquire(source, "v1"); var bytes = active.getBytes(); MemorySegment idle;
        try (var it = cache.acquire(source, "other-producer-version")) { idle = it.getBytes(); }
        cache.close(); cache.close(); assertFalse(idle.scope().isAlive()); assertEquals("data", text(active.getBytes()));
        assertThrows(IllegalStateException.class, () -> cache.acquire(source, "v1")); active.close(); assertFalse(bytes.scope().isAlive());
        assertThrows(IllegalStateException.class, active::getBytes); assertEquals(0L, cache.statistics().activeLeases()); assertEquals(2L, cache.statistics().mappingCloses());
    }
    @Test void missingIdentityUsesIndependentUncachedLifetimes() throws Exception {
        var source = file("unit", "data");
        try (var cache = new CoreFileMappings(1024, 2)) {
            assertThrows(IllegalArgumentException.class, () -> cache.acquire(source, ""));
            var first = cache.acquireUncached(source); var second = cache.acquireUncached(source); var bytes = first.getBytes();
            try {
                assertTrue(first.getOpened() && second.getOpened()); assertNotSame(bytes, second.getBytes());
                first.close(); assertFalse(bytes.scope().isAlive()); assertEquals("data", text(second.getBytes())); assertEquals(0, cache.statistics().idleMappings());
            } finally { first.close(); second.close(); }
            assertEquals(2L, cache.statistics().mappingCloses()); assertEquals(0L, cache.statistics().mappingHits());
        }
    }
    @Test void concurrentFirstAcquisitionOpensOnceAndSupportsCrossThreadRelease() throws Exception {
        var source = file("unit", "shared"); var workers = Executors.newFixedThreadPool(2);
        try {
            try (var cache = new CoreFileMappings(1024, 2)) {
                var start = new CountDownLatch(1); var futures = new ArrayList<Future<CoreFileMappings.Lease>>();
                for (int i = 0; i < 2; i++) futures.add(workers.submit(() -> { start.await(); return cache.acquire(source, "v1"); }));
                start.countDown(); var leases = new ArrayList<CoreFileMappings.Lease>();
                for (var future : futures) leases.add(future.get(5, TimeUnit.SECONDS));
                try {
                    assertEquals(1L, leases.stream().filter(CoreFileMappings.Lease::getOpened).count()); assertSame(leases.get(0).getBytes(), leases.get(1).getBytes());
                    workers.submit(() -> leases.get(0).close()).get(5, TimeUnit.SECONDS);
                    assertEquals("shared", workers.submit(() -> text(leases.get(1).getBytes())).get(5, TimeUnit.SECONDS));
                    assertEquals(1L, cache.statistics().mappingOpens()); assertEquals(1L, cache.statistics().activeLeases());
                } finally { for (var lease : leases) lease.close(); }
            }
        } finally { workers.shutdownNow(); }
    }
}
