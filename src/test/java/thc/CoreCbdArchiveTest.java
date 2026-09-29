// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import java.lang.foreign.ValueLayout;
import java.nio.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class CoreCbdArchiveTest {
    @TempDir Path directory;
    private String sha(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    private byte[] source() throws Exception { return source(new byte[]{42}, Set.of(), false); }
    private byte[] source(Set<String> compressed) throws Exception { return source(new byte[]{42}, compressed, false); }
    private byte[] source(byte[] data, Set<String> compressed, boolean reverse) throws Exception {
        return CoreCbdTestSupport.archive(CoreCbdTestSupport.header(), List.of(data, new byte[]{65}, new byte[0], new byte[0], new byte[0], new byte[0]), compressed, reverse);
    }
    private Path write(byte[] bytes) throws Exception { return Files.write(directory.resolve("module.cbd"), bytes); }
    @Test void zip64SmallMembersUseCheckedLongSizesAndOffsets() throws Exception {
        var bytes = CoreCbdTestSupport.zip64(false);
        try (var maps = new CoreFileMappings(0, 0); var archive = CoreCbdArchive.open(maps.acquire(write(bytes), sha(bytes)))) {
            try (var it = archive.read("data")) { assertEquals(42, it.getBytes().get(ValueLayout.JAVA_BYTE, 0)); }
            assertEquals(40L, archive.member("header").length());
        }
        // Reject unsigned sizes outside positive Long before allocation or narrowing.
        for (int at : new int[]{38, bytes.length - 98 + 40, bytes.length - 98 + 48}) {
            var invalid = bytes.clone(); ByteBuffer.wrap(invalid).order(ByteOrder.LITTLE_ENDIAN).putLong(at, Long.MIN_VALUE);
            try (var maps = new CoreFileMappings(0, 0)) {
                assertThrows(IllegalArgumentException.class, () -> CoreCbdArchive.open(maps.acquire(write(invalid), sha(invalid))).close());
            }
        }
    }
    @Test void offsetOnlyZip64CentralEntryAllowsOrdinarySmallLocalMember() throws Exception {
        var bytes = CoreCbdTestSupport.zip64(true);
        try (var maps = new CoreFileMappings(0, 0); var archive = CoreCbdArchive.open(maps.acquire(write(bytes), sha(bytes)))) {
            try (var it = archive.read("data")) { assertEquals(42, it.getBytes().get(ValueLayout.JAVA_BYTE, 0)); }
        }
    }
    @Test void allDeflatedIncludingEmptyMembersValidateStreamsAndDeclaredLengths() throws Exception {
        var bytes = source(CoreCbdArchive.NAMES);
        try (var maps = new CoreFileMappings(0, 0); var slabs = new CoreCbdSlabs(0, 0);
                var archive = CoreCbdArchive.open(maps.acquire(write(bytes), sha(bytes)), slabs)) {
            for (var name : CoreCbdArchive.NAMES) try (var it = archive.read(name)) { assertEquals(archive.member(name).length(), it.getBytes().byteSize()); }
            assertEquals(7L, slabs.statistics().inflations());
        }
    }
    @Test void storedAndCompressedHandlesOutliveArchiveAndCachesUntilTheirOwnClose() throws Exception {
        for (var compressed : List.of(Set.<String>of(), Set.of("data"))) {
            var bytes = source(new byte[]{42}, compressed, true); var path = write(bytes);
            var mappings = new CoreFileMappings(0, 0); var slabs = new CoreCbdSlabs(0, 0);
            var archive = CoreCbdArchive.open(mappings.acquire(path, sha(bytes)), slabs);
            var handle = archive.read("data"); var view = handle.getBytes(); assertTrue(view.isReadOnly());
            archive.close(); mappings.close(); slabs.close();
            assertEquals(42, view.get(ValueLayout.JAVA_BYTE, 0));
            assertThrows(IllegalStateException.class, () -> archive.read("data"));
            handle.close(); handle.close(); assertThrows(IllegalStateException.class, handle::getBytes);
            assertThrows(IllegalStateException.class, () -> view.get(ValueLayout.JAVA_BYTE, 0));
            assertEquals(0L, mappings.statistics().activeLeases()); assertEquals(0L, slabs.statistics().activeLeases());
            assertEquals(1L, mappings.statistics().mappingCloses());
        }
    }
    @Test void retainPinsSameMappingEvenAfterCacheCloseWithoutNamedAcquisition() throws Exception {
        var bytes = source(); var mappings = new CoreFileMappings(0, 0); var lease = mappings.acquire(write(bytes), sha(bytes));
        mappings.close(); var retained = lease.retain(); assertSame(lease.getSnapshot(), retained.getSnapshot());
        assertFalse(retained.getOpened()); lease.close(); assertEquals((long) bytes.length, retained.getSize());
        assertEquals(1L, mappings.statistics().mappingOpens()); assertEquals(0L, mappings.statistics().mappingHits());
        retained.close(); assertEquals(1L, mappings.statistics().mappingCloses());
    }
    @Test void mixedMembersInflateOnlyOnDemandAndSequentialContextsReuseSlabs() throws Throwable {
        var data = new byte[1000]; Arrays.fill(data, (byte) 42);
        var segments = List.of(data, new byte[]{65}, new byte[]{-1}, new byte[]{-1}, new byte[]{-1}, new byte[0]);
        var bytes = CoreCbdTestSupport.archive(CoreCbdTestSupport.header(new byte[0], 0, 0, 7), segments,
            Set.of("data", "names", "filenames", "line-columns"), false); var path = write(bytes);
        try (var maps = new CoreFileMappings(100000, 1); var slabs = new CoreCbdSlabs(100000, 7)) {
            for (int iteration = 0; iteration < 2; iteration++) try (var file = new CoreCompactFile(path, sha(bytes), false, maps, slabs)) {
                file.header(); assertEquals(0L, file.getCounters().statistics().memberInflations());
                assertEquals(42, file.<Integer>data(0, it -> it.readByte()).intValue());
                assertEquals(1L, file.getCounters().statistics().dataBytesRead());
                assertEquals(iteration == 0 ? 1000L : 0L, file.getCounters().statistics().inflatedBytes());
                assertEquals((long) iteration, file.getCounters().statistics().slabCacheHits());
                assertEquals(0L, file.getCounters().statistics().debugBytesRead()); assertEquals(0L, file.getCounters().statistics().hashBytesRead());
            }
            assertEquals(1L, slabs.statistics().inflations()); assertEquals(1L, maps.statistics().mappingOpens());
        }
    }
    @Test void simultaneousFirstDemandPublishesOneSharedReadOnlySlab() throws Exception {
        var data = new byte[1024 * 1024]; for (int i = 0; i < data.length; i++) data[i] = (byte) (i & 255);
        var bytes = source(data, Set.of("data"), false); var path = write(bytes);
        try (var maps = new CoreFileMappings(100000, 1); var slabs = new CoreCbdSlabs(0, 0)) {
            var archives = new ArrayList<CoreCbdArchive>();
            for (int i = 0; i < 8; i++) archives.add(CoreCbdArchive.open(maps.acquire(path, sha(bytes)), slabs));
            var start = new CountDownLatch(1);
            try (var executor = Executors.newFixedThreadPool(8)) {
                var jobs = new ArrayList<Future<CoreCbdArchive.Handle>>();
                for (var archive : archives) jobs.add(executor.submit(() -> { start.await(); return archive.read("data"); }));
                start.countDown(); var handles = new ArrayList<CoreCbdArchive.Handle>();
                for (var job : jobs) handles.add(job.get(10, TimeUnit.SECONDS));
                try {
                    assertEquals(1L, slabs.statistics().inflations()); assertEquals(7L, slabs.statistics().hits());
                    assertEquals(8L, slabs.statistics().activeLeases()); assertEquals(1L, handles.stream().filter(CoreCbdArchive.Handle::getInflated).count());
                    for (var handle : handles) assertEquals(handles.getFirst().getBytes().address(), handle.getBytes().address());
                    for (var archive : archives) archive.close();
                    for (var handle : handles) assertEquals(-1, handle.getBytes().get(ValueLayout.JAVA_BYTE, 255));
                } finally { for (var handle : handles) handle.close(); for (var archive : archives) archive.close(); }
                assertEquals(1L, slabs.statistics().closes());
            }
        }
    }
    private List<Integer> central(byte[] bytes) {
        var view = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN); int at = view.getInt(bytes.length - 6);
        var offsets = new ArrayList<Integer>();
        for (int i = 0; i < 7; i++) {
            offsets.add(at); at += 46 + (view.getShort(at + 28) & 65535) + (view.getShort(at + 30) & 65535) + (view.getShort(at + 32) & 65535);
        }
        return offsets;
    }
    @Test void finalHeaderMustBeLastInBothPhysicalAndCentralOrder() throws Exception {
        var original = source();
        var offsets = central(original);
        var view = ByteBuffer.wrap(original).order(ByteOrder.LITTLE_ENDIAN);
        int directoryAt = offsets.getFirst(), headerEntry = offsets.getLast();
        int directoryEnd = original.length - 22, headerLocal = view.getInt(headerEntry + 42);
        var wrongCentral = original.clone();
        int headerEntrySize = directoryEnd - headerEntry;
        System.arraycopy(original, headerEntry, wrongCentral, directoryAt, headerEntrySize);
        System.arraycopy(original, directoryAt, wrongCentral, directoryAt + headerEntrySize, headerEntry - directoryAt);
        var wrongPhysical = original.clone();
        int headerLocalSize = directoryAt - headerLocal;
        System.arraycopy(original, headerLocal, wrongPhysical, 0, headerLocalSize);
        System.arraycopy(original, 0, wrongPhysical, headerLocalSize, headerLocal);
        var shifted = ByteBuffer.wrap(wrongPhysical).order(ByteOrder.LITTLE_ENDIAN);
        for (int entry : offsets) shifted.putInt(entry + 42, entry == headerEntry ? 0 : view.getInt(entry + 42) + headerLocalSize);
        for (byte[] invalid : List.of(wrongCentral, wrongPhysical)) try (var maps = new CoreFileMappings(0, 0)) {
            assertThrows(IllegalArgumentException.class, () -> CoreCbdArchive.open(maps.acquire(write(invalid), sha(invalid))).close());
            assertEquals(0L, maps.statistics().activeLeases());
        }
    }
    @Test void crcFailureIsUnpublishedAndRetryDoesNotReusePoisonedSlab() throws Exception {
        var bytes = source(Set.of("data")); var view = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int data = central(bytes).get(0), local = view.getInt(data + 42);
        int payload = local + 30 + (view.getShort(local + 26) & 65535) + (view.getShort(local + 28) & 65535);
        int descriptor = payload + view.getInt(data + 20), badCrc = view.getInt(data + 16) ^ 1;
        view.putInt(data + 16, badCrc); view.putInt(descriptor + 4, badCrc);
        try (var maps = new CoreFileMappings(0, 0); var slabs = new CoreCbdSlabs(1000, 1);
                var archive = CoreCbdArchive.open(maps.acquire(write(bytes), sha(bytes)), slabs)) {
            for (int i = 0; i < 2; i++) assertThrows(IllegalArgumentException.class, () -> archive.read("data"));
            assertEquals(2L, slabs.statistics().failures()); assertEquals(0L, slabs.statistics().inflations());
            assertEquals(0L, slabs.statistics().activeLeases()); assertEquals(0, slabs.statistics().idleEntries());
            try (var it = archive.read("strings")) { assertEquals(65, it.getBytes().get(ValueLayout.JAVA_BYTE, 0)); }
        }
    }
    @Test void malformedDirectoryLocalHeadersAndExtentsRejectWithoutPayloadReads() throws Exception {
        var original = source(); var offsets = central(original);
        List<Consumer<ByteBuffer>> mutations = List.of(
            it -> it.putShort(offsets.get(0) + 10, (short) 12), // Unsupported method.
            it -> it.putShort(offsets.get(0) + 8, (short) 1), // Encryption.
            it -> it.putShort(4, (short) 46), // Unsupported local extraction version.
            it -> it.putInt(offsets.get(0) + 42, Integer.MAX_VALUE), it -> it.putInt(offsets.get(0) + 24, Integer.MAX_VALUE),
            it -> it.putInt(offsets.get(0) + 16, 0), // Local/central CRC differs.
            it -> it.putShort(26, (short) 12), // Local/central names differ.
            it -> it.putShort(original.length - 12, (short) 6), it -> it.putShort(original.length - 18, (short) 1),
            it -> it.putInt(original.length - 6, -1), it -> it.putInt(offsets.get(1) + 42, 0));
        var candidates = new ArrayList<byte[]>();
        for (var edit : mutations) { var bytes = original.clone(); edit.accept(ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)); candidates.add(bytes); }
        candidates.add(Arrays.copyOf(original, original.length - 1)); candidates.add(Arrays.copyOf(original, 20));
        for (var bytes : candidates) try (var maps = new CoreFileMappings(0, 0)) {
            assertThrows(IllegalArgumentException.class, () -> CoreCbdArchive.open(maps.acquire(write(bytes), sha(bytes))).close());
            assertEquals(0L, maps.statistics().activeLeases());
        }
    }
    @Test void storedCrcIsCheckedOnlyByExplicitFreshVerification() throws Throwable {
        var bytes = source(); var view = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int data = central(bytes).get(0), local = view.getInt(data + 42);
        view.putInt(data + 16, 0); view.putInt(local + 14, 0); var path = write(bytes);
        try (var maps = new CoreFileMappings(10000, 2)) {
            try (var file = new CoreCompactFile(path, sha(bytes), false, maps)) {
                assertEquals(42, file.<Integer>data(0, it -> it.readByte()).intValue());
                assertEquals(0L, file.getCounters().statistics().verifiedStoredBytes()); assertEquals(0L, file.getCounters().statistics().hashBytesRead());
            }
            try (var file = new CoreCompactFile(path, sha(bytes), true, maps)) {
                assertThrows(IllegalArgumentException.class, file::header);
                assertEquals((long) bytes.length, file.getCounters().statistics().hashBytesRead()); assertEquals(1L, file.getCounters().statistics().physicalOpens());
            }
            assertEquals(2L, maps.statistics().mappingOpens());
        }
    }
    @Test void verifiedInflationUsesFreshSnapshotRatherThanTrustedCachedSlabs() throws Throwable {
        var bytes = source(CoreCbdArchive.NAMES); var path = write(bytes);
        try (var maps = new CoreFileMappings(100000, 4); var slabs = new CoreCbdSlabs(100000, 32)) {
            try (var file = new CoreCompactFile(path, sha(bytes), false, maps, slabs)) { file.header(); }
            for (int i = 0; i < 2; i++) try (var file = new CoreCompactFile(path, sha(bytes), true, maps, slabs)) {
                file.header(); var counters = file.getCounters().statistics(); assertEquals(7L, counters.memberInflations());
                assertEquals(0L, counters.slabCacheHits()); assertEquals((long) bytes.length, counters.hashBytesRead());
            }
            assertEquals(15L, slabs.statistics().inflations()); assertEquals(3L, maps.statistics().mappingOpens());
        }
    }
    @Test void duplicateRequiredNamesAndWrongInflatedLengthsReject() throws Exception {
        var duplicate = source(); var view = ByteBuffer.wrap(duplicate).order(ByteOrder.LITTLE_ENDIAN);
        int entry = central(duplicate).get(1), local = view.getInt(entry + 42); // strings and symbols have equal widths.
        var name = "symbols".getBytes(StandardCharsets.UTF_8);
        System.arraycopy(name, 0, duplicate, entry + 46, name.length); System.arraycopy(name, 0, duplicate, local + 30, name.length);
        try (var maps = new CoreFileMappings(0, 0)) {
            assertThrows(IllegalArgumentException.class, () -> CoreCbdArchive.open(maps.acquire(write(duplicate), sha(duplicate))).close());
        }
        for (int length : new int[]{0, 2}) {
            var bytes = source(Set.of("data")); var fields = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            int data = central(bytes).get(0), at = fields.getInt(data + 42);
            int payload = at + 30 + (fields.getShort(at + 26) & 65535) + (fields.getShort(at + 28) & 65535);
            int descriptor = payload + fields.getInt(data + 20);
            fields.putInt(data + 24, length); fields.putInt(descriptor + 12, length);
            try (var maps = new CoreFileMappings(0, 0); var slabs = new CoreCbdSlabs(0, 0);
                    var archive = CoreCbdArchive.open(maps.acquire(write(bytes), sha(bytes)), slabs)) {
                assertThrows(IllegalArgumentException.class, () -> archive.read("data"));
                assertEquals(0L, slabs.statistics().activeLeases()); assertEquals(0L, slabs.statistics().inflations());
            }
        }
    }
}
