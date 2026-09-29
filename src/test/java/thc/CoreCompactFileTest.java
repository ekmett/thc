// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class CoreCompactFileTest {
    @TempDir Path directory;
    private Path path() { return directory.resolve("module.cbd"); }
    private String sha(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    private byte[] md5(String id) throws Exception { return MessageDigest.getInstance("MD5").digest(id.getBytes(StandardCharsets.UTF_8)); }
    @Test void standardZipCbdHeaderIsAccepted() throws Throwable {
        var bytes = CoreCbdTestSupport.archive(CoreCbdTestSupport.header(new byte[]{42}, 0, 0, 0),
            Collections.nCopies(6, new byte[0]), Set.of(), false);
        Files.write(path(), bytes);
        try (var cache = new CoreFileMappings(0, 0); var file = new CoreCompactFile(path(), sha(bytes), false, cache)) {
            assertEquals(0L, file.header().bindingCount());
            assertEquals(42, file.facts(CoreCompactCursor::readByte).intValue());
        }
    }
    private record Row(byte[] digest, long offset) {}
    private byte[] fixture() throws Exception { return fixture(List.of("unit:M.f"), false); }
    private byte[] fixture(List<String> ids, boolean invalidOffset) throws Exception {
        var strings = "é😀".getBytes(StandardCharsets.UTF_8);
        var out = ByteBuffer.allocate(ids.size() * 24).order(ByteOrder.LITTLE_ENDIAN);
        var rows = new ArrayList<Row>();
        for (int index = 0; index < ids.size(); index++) rows.add(new Row(md5(ids.get(index)), index));
        rows.sort((a, b) -> Arrays.compareUnsigned(a.digest(), b.digest()));
        for (var row : rows) out.put(row.digest()).putLong(invalidOffset ? Long.MAX_VALUE : row.offset());
        byte[] data = new byte[ids.size()];
        for (int i = 0; i < data.length; i++) data[i] = (byte) (i & 127);
        return CoreCbdTestSupport.archive(CoreCbdTestSupport.header(new byte[]{42}, ids.size(), 0, 7),
            List.of(data, strings, new byte[]{-1}, new byte[]{-1}, new byte[]{-1}, out.array()), Set.of(), false);
    }
    @Test void constructionIsColdAndDigestLookupDoesNotDecodeBodiesStringsOrDebug() throws Throwable {
        var ids = new ArrayList<String>();
        for (int i = 0; i < 1024; i++) ids.add("unit:M.f" + i);
        var bytes = fixture(ids, false);
        try (var cache = new CoreFileMappings(1024 * 1024, 4); var file = new CoreCompactFile(path(), sha(bytes), false, cache)) {
            assertEquals(0L, cache.statistics().mappingOpens());
            Files.write(path(), bytes);
            assertEquals(250L, file.lookup("unit:M.f250"));
            var counts = file.getCounters().statistics();
            assertEquals(1L, counts.acquisitions());
            assertEquals(40L, counts.headerBytesRead());
            assertTrue(counts.lookupComparisons() <= 11);
            assertTrue(counts.lookupBytesRead() <= 16 * 11 + 8);
            assertEquals(0L, counts.hashBytesRead()); assertEquals(0L, counts.dataBytesRead());
            assertEquals(0L, counts.stringBytesRead()); assertEquals(0L, counts.debugBytesRead());
            assertNull(file.lookup("unit:M.missing"));
            assertEquals(122L, file.data(250, CoreCompactCursor::unsigned).longValue());
            assertEquals("é😀", file.string(0, 6));
            assertEquals(42L, file.facts(it -> { long result = it.unsigned(); it.expectEnd(); return result; }).longValue());
            assertEquals(0L, file.getCounters().statistics().debugBytesRead());
        }
    }
    @Test void activeAndSequentialLoadsReuseOneMappingButOwnCountersAndLifetimes() throws Throwable {
        var bytes = fixture(); Files.write(path(), bytes);
        try (var cache = new CoreFileMappings(1024 * 1024, 4)) {
            try (var first = new CoreCompactFile(path(), sha(bytes), false, cache);
                 var second = new CoreCompactFile(path(), sha(bytes), false, cache)) {
                assertEquals(0L, first.lookup("unit:M.f")); assertEquals(0L, second.lookup("unit:M.f"));
                assertEquals(1L, cache.statistics().mappingOpens());
                assertEquals(1L, second.getCounters().statistics().cacheHits());
                first.close();
                assertEquals(0L, second.data(0, CoreCompactCursor::unsigned).longValue());
                assertThrows(IllegalStateException.class, first::header);
                assertThrows(IllegalStateException.class, () -> first.lookup("unit:M.f"));
            }
            try (var file = new CoreCompactFile(path(), sha(bytes), false, cache)) {
                assertEquals(0L, file.lookup("unit:M.f"));
                assertEquals(1L, file.getCounters().statistics().cacheHits());
                assertEquals(1L, cache.statistics().mappingOpens());
            }
        }
    }
    @Test void explicitVerificationUsesFreshMappingEvenAfterTrustedAdmission() throws Exception {
        var bytes = fixture(); Files.write(path(), bytes);
        try (var cache = new CoreFileMappings(1024 * 1024, 4)) {
            try (var file = new CoreCompactFile(path(), sha(bytes), false, cache)) { file.header(); }
            for (int i = 0; i < 2; i++) try (var verified = new CoreCompactFile(path(), sha(bytes), true, cache)) {
                assertEquals(0L, verified.lookup("unit:M.f"));
                assertEquals(bytes.length, verified.getCounters().statistics().hashBytesRead());
                assertEquals(0L, verified.getCounters().statistics().cacheHits());
                assertEquals(1L, verified.getCounters().statistics().physicalOpens());
            }
            try (var file = new CoreCompactFile(path(), "0".repeat(64), true, cache)) {
                assertThrows(IllegalArgumentException.class, file::header);
            }
            assertEquals(0L, cache.statistics().activeLeases());
        }
    }
    @Test void unidentifiedLooseArtifactsUseFreshMappingsWithoutHashingOrIdleRetention() throws Exception {
        try (var cache = new CoreFileMappings(1024 * 1024, 4)) {
            assertThrows(IllegalArgumentException.class, () -> new CoreCompactFile(path(), "", true, cache));
            for (int count = 1; count <= 2; count++) {
                Files.write(path(), fixture(Collections.nCopies(count, "unit:M.f"), false));
                try (var file = new CoreCompactFile(path(), "", false, cache)) {
                    assertEquals(count, file.header().bindingCount());
                    assertEquals(0L, file.getCounters().statistics().hashBytesRead());
                    assertEquals(0L, file.getCounters().statistics().cacheHits());
                    assertEquals(1L, file.getCounters().statistics().physicalOpens());
                }
                assertEquals(0L, cache.statistics().activeLeases());
                assertEquals(count, cache.statistics().mappingCloses());
            }
            assertEquals(2L, cache.statistics().mappingOpens());
        }
    }
    @Test void failedOpenCanBeRetriedAndClosedOwnerCannotDecodeCachedBytes() throws Exception {
        var bytes = fixture();
        try (var cache = new CoreFileMappings(1024 * 1024, 4)) {
            try (var file = new CoreCompactFile(path(), sha(bytes), false, cache)) {
                assertThrows(NoSuchFileException.class, file::header);
                Files.write(path(), bytes);
                assertEquals(1L, file.header().bindingCount()); file.close();
                assertThrows(IllegalStateException.class, () -> file.string(0, 1));
                assertThrows(IllegalStateException.class, () -> file.data(0, CoreCompactCursor::readByte));
            }
            assertEquals(1L, cache.statistics().failedOpens());
        }
    }
    @Test void invalidSelectedOffsetsAndLocalRecordBoundsNeverEscapeSegments() throws Exception {
        var bytes = fixture(List.of("unit:M.f"), true); Files.write(path(), bytes);
        try (var cache = new CoreFileMappings(1024 * 1024, 4); var file = new CoreCompactFile(path(), sha(bytes), false, cache)) {
            assertThrows(IllegalArgumentException.class, () -> file.lookup("unit:M.f"));
            assertThrows(IllegalArgumentException.class, () -> file.string(5, 2));
            assertThrows(IllegalArgumentException.class, () -> file.metadataString(0, 1));
            assertThrows(IllegalArgumentException.class, () -> file.data(1, CoreCompactCursor::readByte));
            assertThrows(IllegalArgumentException.class, () -> file.debug(CoreCompactFormat.Segment.DATA, CoreCompactCursor::readByte));
        }
    }
    @Test void decoderFailuresRetainIdentityAndAccountConsumedBytes() throws Throwable {
        var bytes = fixture(); Files.write(path(), bytes);
        var failure = new IOException("original decoder failure");
        try (var cache = new CoreFileMappings(0, 0)) {
            try (var file = new CoreCompactFile(path(), sha(bytes), false, cache)) {
                assertSame(failure, assertThrows(IOException.class, () -> file.data(0, it -> { it.readByte(); throw failure; })));
                assertSame(failure, assertThrows(IOException.class, () -> file.facts(it -> { it.readByte(); throw failure; })));
                assertSame(failure, assertThrows(IOException.class, () -> file.debug(CoreCompactFormat.Segment.NAMES, it -> { it.readByte(); throw failure; })));
                var counts = file.getCounters().statistics();
                assertEquals(1L, counts.dataBytesRead()); assertEquals(41L, counts.headerBytesRead()); assertEquals(1L, counts.debugBytesRead());
                assertEquals(0L, file.data(0, CoreCompactCursor::unsigned).longValue());
            }
            try (var file = new CoreCompactFile(path(), sha(bytes), true, cache)) {
                int[] visits = {0};
                assertSame(failure, assertThrows(IOException.class, () -> file.verifyBindingOffsets(offset -> {
                    assertEquals(0L, offset); visits[0]++; throw failure;
                })));
                assertEquals(1, visits[0]); assertEquals(24L, file.getCounters().statistics().lookupBytesRead());
            }
            assertEquals(0L, cache.statistics().activeLeases());
        }
    }
    @Test void closeWaitsForDecoderHoldingTheCounterMonitorAndMemberLease() throws Exception {
        var bytes = fixture(); Files.write(path(), bytes);
        try (var cache = new CoreFileMappings(0, 0); var file = new CoreCompactFile(path(), sha(bytes), false, cache)) {
            var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
            var decoded = new CompletableFuture<Long>(); var closed = new CompletableFuture<Void>();
            var reader = new Thread(() -> {
                try { decoded.complete(file.data(0, it -> {
                    assertTrue(Thread.holdsLock(file.getCounters())); entered.countDown();
                    if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Check failed.");
                    return it.unsigned();
                })); } catch (Throwable failure) { decoded.completeExceptionally(failure); }
            });
            var closer = new Thread(() -> {
                try { file.close(); closed.complete(null); }
                catch (Throwable failure) { closed.completeExceptionally(failure); }
            });
            reader.start();
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS)); closer.start();
                var threads = ManagementFactory.getThreadMXBean();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (closer.getState() != Thread.State.BLOCKED && !closed.isDone() && System.nanoTime() < deadline) Thread.onSpinWait();
                var blocked = threads.getThreadInfo(closer.threadId());
                assertEquals(Thread.State.BLOCKED, blocked == null ? null : blocked.getThreadState());
                assertEquals(reader.threadId(), blocked == null ? null : blocked.getLockOwnerId());
                assertEquals(System.identityHashCode(file.getCounters()), blocked == null || blocked.getLockInfo() == null ? null : blocked.getLockInfo().getIdentityHashCode());
                assertFalse(closed.isDone()); assertTrue(cache.statistics().activeLeases() > 0);
            } finally {
                release.countDown(); reader.join(5000);
                if (closer.getState() != Thread.State.NEW) closer.join(5000);
            }
            assertEquals(0L, decoded.get(5, TimeUnit.SECONDS)); closed.get(5, TimeUnit.SECONDS);
            assertEquals(1L, file.getCounters().statistics().dataBytesRead());
            assertEquals(0L, cache.statistics().activeLeases());
            assertThrows(IllegalStateException.class, file::header);
        }
    }
}
