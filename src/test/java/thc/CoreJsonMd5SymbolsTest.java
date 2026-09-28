// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import java.io.ByteArrayOutputStream;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static thc.CoreFormatTestSupport.*;

class CoreJsonMd5SymbolsTest {
    @TempDir Path directory;
    @AfterEach void releaseIdleMappings() { CoreFileMappings.shared.evictIdleBelow(directory); }
    private Path source() { return directory.resolve("core.jsons"); }
    private Path symbols() { return directory.resolve("core.symbols"); }
    private byte[] md5(String id) throws Exception { return MessageDigest.getInstance("MD5").digest(id.getBytes(StandardCharsets.UTF_8)); }
    private String sha(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    private byte[] row(byte[] digest, long offset) { return ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN).put(digest).putLong(offset).array(); }
    private CoreJsonSymbols reader() { return reader(false, null, null, CoreFileMappings.shared); }
    private CoreJsonSymbols reader(boolean verify, String sourceHash, String symbolHash, CoreFileMappings cache) {
        return new CoreJsonSymbols(source(), symbols(), verify, sourceHash, symbolHash, cache, CoreJsonSymbols.Format.MD5_UTF8_U64LE);
    }
    /** Independent test packaging, never an implicit runtime index builder. */
    private void fixture(List<String> ids, int padding) throws Exception {
        var out = new ByteArrayOutputStream();
        out.writeBytes(" ".repeat(padding).getBytes(StandardCharsets.UTF_8));
        var rows = new ArrayList<byte[]>();
        for (int index = 0; index < ids.size(); index++) {
            String id = ids.get(index); long offset = out.size();
            out.writeBytes(Json.stringify(map("id", id, "expr", List.of("lit", "int", Integer.toString(index)),
                "unused", "quoted } [ \\\" é😀")).getBytes(StandardCharsets.UTF_8));
            out.write(10); rows.add(row(md5(id), offset));
        }
        rows.sort((a, b) -> Arrays.compareUnsigned(a, 0, 16, b, 0, 16));
        Files.write(source(), out.toByteArray());
        var directoryBytes = new ByteArrayOutputStream();
        for (var row : rows) directoryBytes.writeBytes(row);
        Files.write(symbols(), directoryBytes.toByteArray());
    }
    @Test void independentDigestVectorsAndLittleEndianOffsetsUseExactUtf8() throws Exception {
        var vectors = List.of(Map.entry("", "d41d8cd98f00b204e9800998ecf8427e"),
            Map.entry("abc", "900150983cd24fb0d6963f7d28e17f72"), Map.entry("main:M.é😀", "23415231b60de428eeaf32979e1cb8ce"));
        for (var vector : vectors) {
            String id = vector.getKey(); byte[] expected = HexFormat.of().parseHex(vector.getValue());
            assertArrayEquals(expected, md5(id));
            Files.writeString(source(), " ".repeat(257) + Json.stringify(map("id", id, "value", 42)));
            var encoded = new ByteArrayOutputStream(); encoded.writeBytes(expected); encoded.writeBytes(new byte[]{1, 1, 0, 0, 0, 0, 0, 0});
            Files.write(symbols(), encoded.toByteArray());
            try (var reader = reader()) {
                assertEquals(0L, reader.statistics().getDirectoryOpens());
                assertEquals(42L, Objects.requireNonNull(reader.binding(id, null)).get("value"));
                assertEquals(24L, reader.statistics().getDirectoryMappedBytes());
                assertEquals(0L, reader.statistics().getHashBytesScanned());
            }
        }
    }
    @Test void fixedRecordsFindOnlySelectedObjectsInDigestNotSourceOrder() throws Exception {
        var ids = List.of("unit:M.z", "unit:M.😀", "unit:M.a space name", "unit:M.\ue000", "unit:M.a", "unit:M.λ");
        fixture(ids, 1024 * 1024);
        try (var index = reader()) {
            for (int position = 0; position < ids.size(); position++) {
                String id = ids.get(position); var binding = Objects.requireNonNull(index.binding(id, null));
                assertEquals(Integer.toString(position), ((List<?>) binding.get("expr")).get(2)); assertSame(binding, index.binding(id, null));
            }
            assertFalse(index.containsSymbol("unit:M.{foreign :: Addr#\n -> State# RealWorld}"));
            assertNull(index.binding("unit:M.absent", null));
            assertEquals(ids.size(), index.statistics().getDecodedBindings());
            assertEquals(1L, index.statistics().getSourceOpens()); assertTrue(index.statistics().getSourceByteReads() < 4096);
        }
    }
    @Test void missesEmptyAndMalformedDirectoriesNeverOpenJson() throws Exception {
        Files.write(symbols(), new byte[0]);
        try (var reader = reader()) {
            assertFalse(reader.containsSymbol("unit:M.missing")); assertEquals(0L, reader.statistics().getSourceOpens());
        }
        Files.write(symbols(), new byte[23]);
        try (var reader = reader()) {
            assertThrows(IllegalArgumentException.class, () -> reader.binding("unit:M.missing", null));
            assertEquals(0L, reader.statistics().getSourceOpens());
        }
        Files.write(symbols(), row(md5("f"), Long.MIN_VALUE));
        try (var reader = reader()) {
            assertTrue(Objects.toString(assertThrows(IllegalArgumentException.class, () -> reader.binding("f", null)).getMessage(), "").contains("JVM address range"));
            assertEquals(0L, reader.statistics().getSourceOpens());
        }
    }
    @Test void selectedOffsetHasFullLongWidthAndLocalObjectBounds() throws Exception {
        long offset = 0x0102030405060708L;
        Files.writeString(source(), "{}"); Files.write(symbols(), row(md5("f"), offset));
        try (var reader = reader()) {
            assertTrue(Objects.toString(assertThrows(IllegalArgumentException.class, () -> reader.binding("f", null)).getMessage(), "").contains(Long.toString(offset)));
        }
        Files.writeString(source(), "{\"id\":\"f\",\"expr\":["); Files.write(symbols(), row(md5("f"), 0));
        try (var reader = reader()) {
            var first = assertThrows(IllegalArgumentException.class, () -> reader.binding("f", null));
            long count = reader.statistics().getSourceByteReads();
            assertSame(first, assertThrows(IllegalArgumentException.class, () -> reader.binding("f", null)));
            assertEquals(count, reader.statistics().getSourceByteReads());
        }
    }
    @Test void oneDemandUsesLogarithmicFixedRecordReadsAndNoOtherBody() throws Exception {
        for (int size : new int[]{512, 1024}) {
            var ids = new ArrayList<String>();
            for (int i = size - 1; i >= 0; i--) ids.add("unit:M.f" + String.format("%05d", i));
            fixture(ids, 0);
            try (var reader = reader()) {
                assertEquals("unit:M.f00250", Objects.requireNonNull(reader.binding("unit:M.f00250", null)).get("id"));
                var counts = reader.statistics();
                assertEquals(1L, counts.getDecodedBindings()); assertTrue(counts.getLookupComparisons() <= 11);
                assertTrue(counts.getDirectoryByteReads() <= 16 * 11 + 8); assertEquals(24L * size, counts.getDirectoryMappedBytes());
                assertTrue(counts.getSourceByteReads() < 512);
            }
        }
    }
    @Test void sharedMappingsAndExplicitFreshVerificationKeepTheirExistingLifetime() throws Exception {
        fixture(List.of("unit:M.f"), 0);
        String sourceHash = sha(Files.readAllBytes(source())), symbolHash = sha(Files.readAllBytes(symbols()));
        try (var cache = new CoreFileMappings(1024 * 1024, 4)) {
            try (var first = reader(false, sourceHash, symbolHash, cache)) {
                var initial = first.binding("unit:M.f", null);
                try (var second = reader(false, sourceHash, symbolHash, cache)) {
                    assertEquals(initial, second.binding("unit:M.f", null)); assertEquals(2L, second.statistics().getMappingCacheHits());
                    first.close(); assertNotNull(second.binding("unit:M.f", null));
                }
            }
            try (var reader = reader(true, sourceHash, symbolHash, cache)) {
                assertNull(reader.binding("absent", null)); assertEquals(Files.size(symbols()), reader.statistics().getHashBytesScanned());
                assertNotNull(reader.binding("unit:M.f", null));
                assertEquals(Files.size(source()) + Files.size(symbols()), reader.statistics().getHashBytesScanned());
                assertEquals(0L, reader.statistics().getMappingCacheHits());
            }
        }
    }
}
