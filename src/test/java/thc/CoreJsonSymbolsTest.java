// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import java.io.ByteArrayOutputStream;
import java.nio.charset.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static thc.CoreFormatTestSupport.*;

class CoreJsonSymbolsTest {
    @TempDir Path directory;
    @AfterEach void releaseIdleMappings() { CoreFileMappings.shared.evictIdleBelow(directory); }
    private Path source() { return directory.resolve("module.json"); }
    private Path symbols() { return directory.resolve("module.json.symbols"); }
    private String hash(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    private final Comparator<String> byteOrder = (left, right) -> {
        byte[] a = left.getBytes(StandardCharsets.UTF_8), b = right.getBytes(StandardCharsets.UTF_8);
        int result = 0;
        for (int i = 0; i < Math.min(a.length, b.length); i++) {
            result = (a[i] & 255) - (b[i] & 255); if (result != 0) break;
        }
        return result == 0 ? Integer.compare(a.length, b.length) : result;
    };
    private CoreJsonSymbols reader() { return reader(false, null, null, CoreFileMappings.shared); }
    private CoreJsonSymbols reader(boolean verify, String jsonHash, String symbolHash, CoreFileMappings cache) {
        return new CoreJsonSymbols(source(), symbols(), verify, jsonHash, symbolHash, cache, CoreJsonSymbols.Format.TEXT);
    }
    /** Independent test writer, not a runtime producer or index-building fallback. */
    private void fixture(List<Map<String, Object>> bindings, int padding) throws Exception {
        var bytes = new ByteArrayOutputStream();
        bytes.writeBytes(("{\"unused\":\"" + "x".repeat(padding) + "\",\"bindings\":[").getBytes(StandardCharsets.UTF_8));
        var positions = new LinkedHashMap<String, Long>();
        for (int i = 0; i < bindings.size(); i++) {
            if (i != 0) bytes.write(',');
            var binding = bindings.get(i);
            positions.put((String) binding.get("id"), (long) bytes.size());
            bytes.writeBytes(Json.stringify(binding).getBytes(StandardCharsets.UTF_8));
        }
        bytes.writeBytes("]}".getBytes(StandardCharsets.UTF_8)); Files.write(source(), bytes.toByteArray());
        var names = new ArrayList<>(positions.keySet()); names.sort(byteOrder);
        var rows = new StringBuilder();
        for (String name : names) rows.append(name).append(' ').append(positions.get(name)).append('\n');
        Files.writeString(symbols(), rows);
    }
    private Map<String, Object> binding(String id) { return binding(id, 7); }
    private Map<String, Object> binding(String id, long value) {
        return map("id", id, "name", "same display name", "expr", List.of("lit", "int", Long.toString(value)),
            "annotation", "} [ \\\" λ😀");
    }
    @Test void binarySearchUsesRawUtf8OrderAndSelectedObjectNotNextSortedPosition() throws Exception {
        var ids = List.of("unit:M.z", "unit:M.😀", "unit:M.a space name", "unit:M.\ue000", "unit:M.a", "unit:M.λ");
        var bindings = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < ids.size(); i++) bindings.add(binding(ids.get(i), i));
        fixture(bindings, 1024 * 1024);
        try (var reader = reader()) {
            assertEquals(0L, reader.statistics().getDirectoryOpens());
            for (int i = 0; i < ids.size(); i++) {
                String id = ids.get(i); var selected = Objects.requireNonNull(reader.binding(id, null));
                assertEquals(id, selected.get("id"));
                assertEquals(Integer.toString(i), ((List<?>) selected.get("expr")).get(2));
                assertSame(selected, reader.binding(id, null));
            }
            assertNull(reader.binding("unit:M.absent", null)); var counts = reader.statistics();
            assertEquals(1L, counts.getDirectoryOpens()); assertEquals(1L, counts.getSourceOpens());
            assertEquals(ids.size(), counts.getDecodedBindings()); assertEquals(0L, counts.getHashBytesScanned());
            assertTrue(counts.getSourceByteReads() < 10000, "unused megabyte is mapped, never decoded or scanned");
            assertEquals(Files.size(source()), counts.getSourceMappedBytes());
        }
    }
    @Test void missesDoNotOpenTheJsonAndEmptyDirectoryHasNoRows() throws Exception {
        Files.writeString(symbols(), "unit:M.f 100\n");
        try (var reader = reader()) { assertNull(reader.binding("unit:M.absent", null)); assertEquals(0L, reader.statistics().getSourceOpens()); }
        Files.writeString(symbols(), "");
        try (var reader = reader()) { assertNull(reader.binding("unit:M.f", null)); assertEquals(0L, reader.statistics().getSourceOpens()); }
    }
    @Test void foreignDeclarationNamesOutsideTheRowAlphabetAreAbsentWithoutOpeningFiles() throws Exception {
        try (var reader = reader()) {
            for (String id : List.of("", "unit:M.{foreign :: Addr#\n -> State# RealWorld}", "unit:M.f\r")) {
                assertFalse(reader.containsSymbol(id)); assertThrows(IllegalArgumentException.class, () -> reader.binding(id, null));
            }
            assertEquals(0L, reader.statistics().getDirectoryOpens()); assertEquals(0L, reader.statistics().getSourceOpens());
        }
    }
    @Test void exactIdentityBoundsUtf8AndFailuresAreCheckedOnlyAtDemand() throws Exception {
        fixture(List.of(binding("actual")), 0);
        String text = Files.readString(symbols()), offset = text.substring(text.lastIndexOf(' ') + 1).trim();
        Files.writeString(symbols(), "different " + offset + "\n");
        try (var reader = reader()) {
            var first = assertThrows(IllegalArgumentException.class, () -> reader.binding("different", null));
            assertTrue(first.getMessage().contains("identity mismatch"));
            assertSame(first, assertThrows(IllegalArgumentException.class, () -> reader.binding("different", null)));
            assertEquals(1L, reader.statistics().getDecodedBindings());
        }
        for (String row : List.of("actual -1\n", "actual 9223372036854775808\n", "actual 99999999\n",
                "actual 0\n", "actual x\n", "actual 1", "actual \n")) {
            Files.writeString(symbols(), row);
            try (var reader = reader()) { assertThrows(Exception.class, () -> reader.binding("actual", null)); }
        }
        Files.write(source(), new byte[]{123, 34, 105, 100, 34, 58, 34, -1, 34, 125}); Files.writeString(symbols(), "actual 0\n");
        try (var reader = reader()) { assertThrows(CharacterCodingException.class, () -> reader.binding("actual", null)); }
    }
    @Test void exactFileHashesAreOptInAndSourceHashIsNotPaidForAMiss() throws Exception {
        fixture(List.of(binding("f")), 0);
        String jsonHash = hash(Files.readAllBytes(source())), directoryHash = hash(Files.readAllBytes(symbols()));
        try (var reader = reader(true, jsonHash, directoryHash, CoreFileMappings.shared)) {
            assertNull(reader.binding("missing", null)); assertEquals(Files.size(symbols()), reader.statistics().getHashBytesScanned());
            assertNotNull(reader.binding("f", null)); assertEquals(Files.size(symbols()) + Files.size(source()), reader.statistics().getHashBytesScanned());
        }
        try (var reader = reader(true, "0".repeat(64), directoryHash, CoreFileMappings.shared)) {
            assertThrows(IllegalArgumentException.class, () -> reader.binding("f", null)); assertEquals(1L, reader.statistics().getSourceOpens());
        }
        try (var reader = reader(false, "0".repeat(64), "0".repeat(64), CoreFileMappings.shared)) {
            assertNotNull(reader.binding("f", null)); assertEquals(0L, reader.statistics().getHashBytesScanned());
        }
    }
    @Test void concurrentDemandSharesOneDecodeAndCloseReleasesTheFileBeforeReplacement() throws Exception {
        fixture(List.of(binding("first"), binding("second")), 0);
        var reader = reader(); var pool = Executors.newFixedThreadPool(2);
        try {
            var jobs = new ArrayList<Callable<Map<String, Object>>>();
            for (int i = 0; i < 8; i++) jobs.add(() -> reader.binding("first", null));
            var results = new ArrayList<Map<String, Object>>();
            for (var future : pool.invokeAll(jobs)) results.add(future.get());
            assertTrue(results.stream().allMatch(it -> it == results.getFirst()));
            assertEquals(1L, reader.statistics().getDecodedBindings()); assertEquals("second", Objects.requireNonNull(reader.binding("second", null)).get("id"));
            reader.close();
            var replacement = directory.resolve("replacement.json"); Files.writeString(replacement, "null");
            Files.move(replacement, source(), StandardCopyOption.REPLACE_EXISTING);
            assertEquals("first", Objects.requireNonNull(results.getFirst()).get("id"));
            assertThrows(IllegalStateException.class, () -> reader.binding("first", null));
        } finally { reader.close(); pool.shutdownNow(); }
    }
    @Test void explicitVerificationObservesReplacementEvenWithAnIdleTrustedMapping() throws Exception {
        fixture(List.of(binding("f")), 0);
        String jsonHash = hash(Files.readAllBytes(source())), directoryHash = hash(Files.readAllBytes(symbols()));
        try (var cache = new CoreFileMappings(1024 * 1024, 4)) {
            try (var reader = reader(false, jsonHash, directoryHash, cache)) { assertNotNull(reader.binding("f", null)); }
            try (var reader = reader(false, jsonHash, directoryHash, cache)) {
                assertNotNull(reader.binding("f", null)); assertEquals(2L, reader.statistics().getMappingCacheHits());
            }
            // Replacement, not in-place mutation of an immutable mapping.
            // Release the idle mapping first so replacement is portable to
            // Windows. Verification still checks the newly opened path twice.
            assertEquals(1, cache.evictIdleBelow(source()));
            var replacement = directory.resolve("changed.json"); Files.writeString(replacement, "{}");
            Files.move(replacement, source(), StandardCopyOption.REPLACE_EXISTING);
            for (int i = 0; i < 2; i++) try (var reader = reader(true, jsonHash, directoryHash, cache)) {
                var failure = assertThrows(IllegalArgumentException.class, () -> reader.binding("f", null));
                assertTrue(Objects.toString(failure.getMessage(), "").contains("hash mismatch"));
                assertEquals(0L, reader.statistics().getMappingCacheHits());
                assertEquals(Files.size(symbols()) + Files.size(source()), reader.statistics().getHashBytesScanned());
            }
            assertEquals(1, cache.evictIdleBelow(symbols()));
        }
    }
    @Test void oneLookupDoesNotCreateAHeapRowIndexForNOrTwiceN() throws Exception {
        for (int size : new int[]{512, 1024}) {
            var bindings = new ArrayList<Map<String, Object>>();
            for (int i = size - 1; i >= 0; i--) bindings.add(binding("unit:M.f" + String.format("%05d", i)));
            fixture(bindings, 0);
            try (var reader = reader()) {
                assertEquals("unit:M.f00250", Objects.requireNonNull(reader.binding("unit:M.f00250", null)).get("id"));
                var counts = reader.statistics(); assertEquals(1L, counts.getDecodedBindings());
                assertTrue(counts.getLookupComparisons() <= 12); assertTrue(counts.getDirectoryByteReads() < Files.size(symbols()) / 4);
                assertTrue(counts.getSourceByteReads() < 512);
            }
        }
    }
    @Test void moduleMetadataSkipsBindingsAndOtherModulesWithoutOpeningTheSymbolDirectory() throws Exception {
        String prefix = "{\"module\":\"Aλ\",\"bindings\":";
        String body = "[" + Json.stringify(with(binding("unit:A.huge"), "unused", "x".repeat(1024 * 1024))) + "]";
        String suffix = ",\"constructors\":[],\"source\":\"after binding body\"}";
        String leading = "{\"module\":\"B\",\"bindings\":[unparsed invalid cold body]}\n";
        long start = leading.getBytes(StandardCharsets.UTF_8).length;
        long bindingsStart = start + prefix.getBytes(StandardCharsets.UTF_8).length;
        long bindingsEnd = bindingsStart + body.getBytes(StandardCharsets.UTF_8).length;
        long end = bindingsEnd + suffix.getBytes(StandardCharsets.UTF_8).length;
        Files.writeString(source(), leading + prefix + body + suffix + "\n{unparsed module C}");
        var span = new CoreJsonSymbols.ModuleSpan(start, end, bindingsStart, bindingsEnd);
        var reader = reader(); var counts = reader.getCounters();
        try (reader) {
            var module = reader.moduleMetadata(span);
            assertEquals("Aλ", module.get("module")); assertEquals(List.of(), module.get("bindings"));
            assertEquals("after binding body", module.get("source")); assertSame(module, reader.moduleMetadata(span));
            var statistics = counts.statistics();
            assertEquals(0L, statistics.getDirectoryOpens()); assertEquals(1L, statistics.getSourceOpens());
            assertEquals(1L, statistics.getDecodedModules()); assertEquals(0L, statistics.getDecodedBindings());
            assertEquals((prefix + suffix).getBytes(StandardCharsets.UTF_8).length, statistics.getMetadataBytes());
            assertTrue(statistics.getSourceByteReads() < 200);
            assertThrows(IllegalArgumentException.class, () -> reader.moduleMetadata(new CoreJsonSymbols.ModuleSpan(start, end, bindingsStart, end + 1)));
            assertThrows(IllegalArgumentException.class, () -> reader.moduleMetadata(new CoreJsonSymbols.ModuleSpan(start, end, bindingsStart + 1, bindingsEnd)));
        }
        assertEquals(1L, counts.statistics().getDecodedModules());
        assertTrue(Arrays.stream(counts.getClass().getDeclaredFields()).allMatch(it -> it.getType() == long.class),
            "detached counters must not retain source owners, caches, or closures");
        assertThrows(IllegalStateException.class, () -> reader.moduleMetadata(span));
    }
}
