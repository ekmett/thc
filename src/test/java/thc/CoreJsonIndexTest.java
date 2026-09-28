// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import java.nio.file.*;
import java.util.*;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static java.nio.charset.StandardCharsets.*;
import static org.junit.jupiter.api.Assertions.*;
import static thc.CoreFormatTestSupport.*;
import static thc.CoreJsonMasks.jsonMaskBlock;
import static thc.CoreJsonRank.jsonRankRunPrefix;

class CoreJsonIndexTest {
    private CoreJsonIndex index(String text) { return CoreJsonIndex.fromBytes(text.getBytes(UTF_8)); }
    private record Event(int position, int kind) {}
    private List<Event> events(String text) {
        var bytes = text.getBytes(UTF_8); var events = new ArrayList<Event>();
        boolean quoted = false, escaped = false;
        for (int position = 0; position < bytes.length; position++) {
            int c = bytes[position] & 255;
            if (quoted) { if (escaped) escaped = false; else if (c == 92) escaped = true; else if (c == 34) quoted = false; }
            else if (c == 34) quoted = true;
            else switch (c) { case 123, 91 -> events.add(new Event(position, 3)); case 125, 93 -> events.add(new Event(position, 0)); case 44, 58 -> events.add(new Event(position, 2)); }
        }
        return events;
    }
    private String text(CoreJsonIndex.Span span) { return new String(span.bytes(), UTF_8); }
    @Test void exactByteSpansAndClosingOrderDifferFromOpeningOrder() {
        var text = " \n{\"first\":[1,{\"λ\":\"😀\"},3],\"second\":{\"empty\":[]},\"last\":false}\t";
        try (var source = index(text)) {
            var root = source.getRoot(); assertEquals(2, root.getStart()); assertEquals(text.getBytes(UTF_8).length - 1, root.getEndExclusive());
            var first = Objects.requireNonNull(root.member("first")); var values = first.elements(); assertEquals(3, values.size());
            assertEquals("[1,{\"λ\":\"😀\"},3]", text(first)); assertEquals("{\"λ\":\"😀\"}", text(values.get(1))); assertEquals("😀", values.get(1).member("λ").decode());
            assertEquals("[]", text(root.member("second").member("empty"))); assertEquals(false, root.member("last").decode()); assertEquals(Json.parse(text), source.validateDocument());
        }
    }
    @Test void quoteEscapesBackslashRunsUnicodeAndChunkTails() {
        var strings = List.of("", "[{}]:,", "\\", "\\\"", "\\\\\"[]", "\"\\\"", "λ😀", "a\u0000\nb");
        for (int prefix = 0; prefix <= 130; prefix++) for (var value : strings) {
            var text = " ".repeat(prefix) + "[" + Json.stringify(value) + ",42]" + " ".repeat(prefix % 65);
            try (var source = index(text)) { var elements = source.getRoot().elements(); assertEquals(2, elements.size()); assertTrue(elements.get(0).stringEquals(value), "prefix=" + prefix + " value=" + value); assertFalse(elements.get(0).stringEquals(value + "x")); assertEquals(value, elements.get(0).decode()); assertEquals(42L, elements.get(1).decode()); }
        }
        try (var source = index("[\"\\u0078\\uD83D\\uDE00\",\"\\uD800\",\"\\\\u0078\"]")) {
            var values = source.getRoot().elements(); assertTrue(values.get(0).stringEquals("x😀")); assertTrue(values.get(1).stringEquals("\uD800")); assertTrue(values.get(2).stringEquals("\\u0078")); assertEquals(0L, source.statistics().getDecodedSpanCount());
        }
    }
    @Test void rootScalarsAndEmptyContainers() {
        for (var value : List.of("0", "-42", "1.25e+2", "true", "false", "null", "\"\"", "[]", "{}")) try (var source = index(" \t" + value + "\r\n")) { assertEquals(value, text(source.getRoot())); assertEquals(Json.parse(value), source.getRoot().decode()); }
        try (var source = index("[[],{}]")) { assertTrue(source.getRoot().elements().get(0).elements().isEmpty()); assertTrue(source.getRoot().elements().get(1).members().isEmpty()); }
    }
    @Test void malformedStructuralBoundariesNeverProduceAnIndex() {
        for (var value : List.of("", " ", "[", "{", "[}", "{]", "[1,]", "{\"a\":}", "{\"a\" 1}", "{1:2}", "{\"a\":1,}", "[1 2]", "[] []", "\"unterminated", "\"escape\\", "[\"raw\ncontrol\"]", "[true{}]", "{\"a\":1]")) assertThrows(Exception.class, () -> index(value).close(), value);
    }
    @Test void deferredScalarValidationAndFailuresAreMemoized() {
        try (var source = index("{\"ok\":42,\"untouched\":[1e999,\"\\q\",truely]}")) {
            assertEquals(0L, source.statistics().getDecodedSpanCount()); assertEquals(42L, source.getRoot().member("ok").decode()); assertEquals(1L, source.statistics().getDecodedSpanCount());
            var bad = source.getRoot().member("untouched").elements().get(0); var first = assertThrows(Exception.class, bad::decode); assertSame(first, assertThrows(Exception.class, bad::decode)); assertEquals(2L, source.statistics().getDecodedSpanCount()); assertThrows(Exception.class, source::validateDocument);
        }
    }
    @Test void selectedDuplicateNamesAndExplicitWholeValidation() {
        try (var source = index("{\"ok\":42,\"unused\":{\"x\":1,\"\\u0078\":2}}")) { assertEquals(42L, source.getRoot().member("ok").decode()); assertThrows(IllegalArgumentException.class, source::validateDocument); }
        try (var source = index("{\"other\":1,\"\\u006fther\":2,\"ok\":42}")) { assertEquals(42L, source.getRoot().member("ok").decode()); assertThrows(IllegalArgumentException.class, () -> source.getRoot().member("other")); assertThrows(IllegalArgumentException.class, () -> source.getRoot().members()); }
    }
    @Test void demandProjectionDoesNotDecodeOtherKeysOrValues() {
        try (var source = index("{\"skip\":[{\"invalid\":1e999}],\"\\u0074ake\":7,\"last\":false}")) {
            assertEquals(7L, source.getRoot().member("take").decode()); assertEquals(1L, source.statistics().getDecodedSpanCount()); assertEquals(1L, source.statistics().getDecodedByteCount()); assertNull(source.getRoot().member("missing")); assertEquals(1L, source.statistics().getDecodedSpanCount());
            assertEquals(List.of("skip", "take", "last"), source.getRoot().members().stream().map(it -> it.getName()).toList()); assertEquals(4L, source.statistics().getDecodedSpanCount(), "members decodes immediate keys, not values");
        }
    }
    @Test void hugeUnusedStringUsesLocalInterestMasksWithoutDecode() {
        var bytes = ("[\"" + "payload { [ \\\" \\\\ λ ".repeat(100_000) + "\",42]").getBytes(UTF_8);
        try (var source = CoreJsonIndex.fromBytes(bytes)) {
            var before = source.statistics(); var values = source.getRoot().elements(); assertEquals(42L, values.get(1).decode()); var after = source.statistics();
            assertEquals((long) bytes.length, after.getStructuralBytesScanned()); assertEquals(bytes.length * 2L, after.getIndexSourceBytesScanned()); assertEquals(1L, after.getDecodedSpanCount()); assertEquals(2L, after.getDecodedByteCount());
            assertTrue(after.getNavigationByteReads() - before.getNavigationByteReads() < 32); assertTrue(after.getRegeneratedSourceBytes() - before.getRegeneratedSourceBytes() <= 1024); assertTrue(after.getIndexByteSize() < bytes.length / 100, "source-backed index must not retain a dense input-sized bitmap");
        }
    }
    @Test void scalarBoundaryWhitespaceScansAreNotHidden() {
        try (var source = index("[\"skip\"" + " ".repeat(100_000) + ",42]")) { var before = source.statistics(); assertEquals(42L, source.getRoot().elements().get(1).decode()); assertTrue(source.statistics().getNavigationByteReads() - before.getNavigationByteReads() >= 100_000); assertEquals(1L, source.statistics().getDecodedSpanCount()); }
    }
    @Test void farNestedSubtreeUsesBoundedBpNavigation() {
        var skipped = new StringJoiner(",", "[", "]"); for (int i = 0; i < 20_000; i++) skipped.add("[" + i + ",{\"x\":[true,false,null]}]");
        try (var source = index("[" + skipped + ",42]")) { var before = source.statistics(); var values = source.getRoot().elements(); assertEquals(42L, values.get(1).decode()); var after = source.statistics();
            assertEquals(1L, after.getDecodedSpanCount()); assertTrue(after.getBalancedParenthesisBitsExamined() - before.getBalancedParenthesisBitsExamined() < 4096, "subtree skipping must not linearly scan the whole BP tree"); assertTrue(after.getNavigationByteReads() - before.getNavigationByteReads() < 32); assertTrue(after.getRegeneratedSourceBytes() - before.getRegeneratedSourceBytes() <= 1024, "rank and select may each regenerate only one bounded512-byte block"); }
    }
    @Test void deepStructureDoesNotRecurseOnTheJvmStack() {
        int depth = 20_000; try (var source = index("[".repeat(depth) + "0" + "]".repeat(depth))) { assertEquals(depth * 2 + 1, source.getRoot().getEndExclusive()); assertEquals(depth * 2, source.getRoot().elements().get(0).getEndExclusive()); assertEquals(0L, source.statistics().getDecodedSpanCount()); }
    }
    @Test void wideArrayIterationDoesNotRestartFromTheFirstSibling() {
        var text = new StringJoiner(",", "[", "]"); for (int i = 0; i < 5000; i++) text.add(Integer.toString(i));
        try (var source = index(text.toString())) { long before = source.statistics().getBalancedParenthesisBitsExamined(); int count = 0; for (var span : source.getRoot().elements()) { assertEquals((long) count, span.decode()); count++; } assertEquals(5000, count); assertTrue(source.statistics().getBalancedParenthesisBitsExamined() - before < 25_000); }
    }
    @Test void snapshotCloseAndAlreadyPublishedValues(@TempDir Path directory) throws Exception {
        var bytes = "[1,{\"x\":2}]".getBytes(UTF_8); var source = CoreJsonIndex.fromBytes(bytes); Arrays.fill(bytes, (byte) 32); var retained = source.getRoot().elements().get(1); var value = retained.decode(); assertSame(value, retained.decode());
        var path = directory.resolve("input.json"); Files.writeString(path, "[7]"); try (var fromPath = CoreJsonIndex.read(path)) { Files.writeString(path, "[9]"); Files.delete(path); assertEquals(7L, fromPath.getRoot().elements().get(0).decode()); }
        source.close(); source.close(); assertEquals(map("x", 2L), value); assertThrows(IllegalStateException.class, retained::decode); assertThrows(IllegalStateException.class, retained::getStart); assertThrows(IllegalStateException.class, source::getRoot);
    }
    @Test void originalByteIdentityNotReserializedIdentity() {
        try (var compact = index("{\"x\":1}"); var spaced = index("{ \"x\" : 1 }")) { assertEquals(compact.getRoot().decode(), spaced.getRoot().decode()); assertNotEquals(compact.sha256(), spaced.sha256()); var copy = compact.getRoot().bytes(); Arrays.fill(copy, (byte) 32); assertEquals("{\"x\":1}", text(compact.getRoot())); }
    }
    private void verify(CoreJsonIndex.Span span, Object expected, String name, boolean checkStrings) {
        assertEquals(expected, Json.parse(text(span)), name);
        if (expected instanceof List<?> list) { var children = span.elements(); assertEquals(list.size(), children.size(), name); for (int i = 0; i < list.size(); i++) verify(children.get(i), list.get(i), name, checkStrings); }
        else if (expected instanceof Map<?, ?> map) { var members = span.members(); assertEquals(new ArrayList<>(map.keySet()), members.stream().map(it -> it.getName()).toList(), name); for (var member : members) verify(member.getValue(), map.get(member.getName()), name, checkStrings); }
        else { if (checkStrings && expected instanceof String string) assertTrue(span.stringEquals(string), name); assertEquals(expected, span.decode(), name); }
    }
    @Test void derivedExtentsAreCheckedBeforeAllocationOrNarrowing() {
        assertThrows(ArithmeticException.class, () -> new JsonIndexShape(Integer.MAX_VALUE, (long) Integer.MAX_VALUE - 1));
        for (var pair : List.of(new long[]{0, 1}, new long[]{-1, 0}, new long[]{4, 6}, new long[]{Long.MAX_VALUE, 2})) assertThrows(IllegalArgumentException.class, () -> new JsonIndexShape(pair[0], pair[1]));
        var sparse = new JsonIndexShape(Integer.MAX_VALUE, 2); assertEquals(1, sparse.getEpochCount()); assertEquals(1048576, sparse.getBlockCount()); assertEquals(8388608, sparse.getCheckpointBits()); assertEquals(4, sparse.getBpBits());
        var empty = new JsonIndexShape(0, 0); assertEquals(0, empty.getEpochCount()); assertEquals(0, empty.getBlockCount()); assertEquals(0, empty.getCheckpointBits()); assertEquals(0, empty.getBpBits()); assertEquals(6, new JsonIndexShape(4, 3).getBpBits(), "marker count may be odd");
    }
    @Test void loadedOwnershipChecksCachedReadsAndRetainedIterators(@TempDir Path directory) throws Exception {
        var text = "[1,{\"x\":2}]"; var bytes = text.getBytes(UTF_8);
        var loaded = CoreJsonIndex.fromBytes(bytes); Arrays.fill(bytes, (byte) 32);
        var cursor = loaded.getRoot().elements().iterator(); var first = cursor.next(); assertEquals(1L, first.decode()); loaded.close(); assertThrows(IllegalStateException.class, first::decode); assertThrows(IllegalStateException.class, cursor::hasNext); assertThrows(IllegalStateException.class, cursor::next);
        var path = directory.resolve("input.json"); Files.writeString(path, text);
        try (var pinned = CoreJsonIndex.read(path)) { Files.writeString(path, "null"); Files.delete(path); assertEquals(text, text(pinned.getRoot())); assertEquals((long) text.length(), pinned.statistics().getSourceFileBytesRead()); assertEquals(0L, pinned.statistics().getSourceSnapshotBytesCopied()); }
    }
    @Test void sourceBackedRankSelectMatchesEveryMarkerAndBoundedBlockWork() throws Exception {
        var text = "[\"" + "x".repeat(509) + "\\\"[],:" + "\\\\".repeat(800) + "λ😀\",{\"x\":[0,1]},true]"; var expected = new ArrayList<Integer>();
        events(text).forEach(it -> expected.add(it.position)); var bytes = text.getBytes(UTF_8);
        try (var source = CoreJsonIndex.fromBytes(bytes)) { int rank = 0;
            for (int position = 0; position <= bytes.length; position++) { var before = source.statistics(); assertEquals(rank, source.rankInterest(position), "rank at " + position); var after = source.statistics(); assertTrue(after.getRegeneratedSourceBytes() - before.getRegeneratedSourceBytes() <= 512); assertTrue(after.getRegeneratedBlockCount() - before.getRegeneratedBlockCount() <= 1); if (rank < expected.size() && expected.get(rank) == position) rank++; }
            for (int ordinal = expected.size() - 1; ordinal >= 0; ordinal--) { var before = source.statistics(); assertEquals(expected.get(ordinal).intValue(), source.selectInterest(ordinal)); assertTrue(source.statistics().getRegeneratedSourceBytes() - before.getRegeneratedSourceBytes() <= 512); }
            assertThrows(IllegalArgumentException.class, () -> source.rankInterest(-1)); assertThrows(IllegalArgumentException.class, () -> source.rankInterest(bytes.length + 1)); assertThrows(IllegalArgumentException.class, () -> source.selectInterest(expected.size())); assertEquals(Json.parse(text), source.validateDocument()); }
        assertThrows(IllegalArgumentException.class, () -> CoreJsonIndex.fromBytes(new byte[0]));
    }
    @Test void sourceMaskTranscriptionPreservesAllIncomingStatesAndTails() {
        byte[] alphabet = {34, 92, 123, 125, 91, 93, 44, 58, 32, 0, -1, 120};
        for (int initial = 0; initial <= 2; initial++) for (int length : new int[]{0, 1, 2, 31, 32, 33, 63, 64, 65, 511, 512}) {
            var source = new byte[length + 6]; for (int i = 0; i < source.length; i++) source[i] = alphabet[i % alphabet.length];
            var actual = new long[8]; var opens = new long[8]; var closes = new long[8]; int end = jsonMaskBlock(source, 3, length, initial, actual, opens, closes); boolean quoted = initial != 0, escaped = initial == 2;
            for (int at = 0; at < length; at++) { int c = source[at + 3] & 255; boolean marker = !quoted && (c == 123 || c == 125 || c == 91 || c == 93 || c == 44 || c == 58); long bit = 1L << (at & 63);
                assertEquals(marker, (actual[at >>> 6] & bit) != 0); assertEquals(marker && (c == 123 || c == 91), (opens[at >>> 6] & bit) != 0); assertEquals(marker && (c == 125 || c == 93), (closes[at >>> 6] & bit) != 0);
                if (escaped) escaped = false; else if (quoted && c == 92) escaped = true; else if (c == 34) quoted = !quoted;
            }
            assertEquals(escaped ? 2 : quoted ? 1 : 0, end); for (int bit = length; bit < 512; bit++) assertEquals(0L, actual[bit >>> 6] & (1L << (bit & 63)));
        }
    }
    @Test void uncachedDecodeLeavesCanonicalRetentionToTheAdapter() {
        try (var source = index("[\"a sufficiently long demanded string\",1e999]")) { var string = source.getRoot().elements().get(0); var first = string.decodeUncached(); var second = string.decodeUncached(); assertEquals(first, second); assertNotSame(first, second); assertEquals(2L, source.statistics().getDecodedSpanCount()); var cached = string.decode(); assertSame(cached, string.decode()); assertEquals(3L, source.statistics().getDecodedSpanCount());
            var bad = source.getRoot().elements().get(1); var failure = assertThrows(Exception.class, bad::decodeUncached); assertNotSame(failure, assertThrows(Exception.class, bad::decodeUncached)); source.close(); assertThrows(IllegalStateException.class, string::decodeUncached); }
    }
    @Test void referenceHashAllocationAppearsOnlyWhenRequested() {
        try (var source = index("[0]")) { var before = source.statistics(); assertEquals(0L, before.getSourceIdentityBytes()); source.sha256(); assertEquals(32L, source.statistics().getSourceIdentityBytes()); assertEquals(before.getIndexByteSize() + 32, source.statistics().getIndexByteSize()); assertEquals(3L, source.statistics().getSourceHashBytesScanned()); source.sha256(); assertEquals(3L, source.statistics().getSourceHashBytesScanned()); }
    }
    private Object tree(Random random, int depth) {
        if (depth == 0) return switch (random.nextInt(5)) { case 0 -> null; case 1 -> random.nextLong(); case 2 -> random.nextBoolean(); case 3 -> "λ😀[]{}\\\""; default -> random.nextDouble(); };
        int count;
        if (random.nextBoolean()) { count = random.nextInt(7); var result = new ArrayList<>(); for (int i = 0; i < count; i++) result.add(tree(random, depth - 1)); return result; }
        count = random.nextInt(7); var result = new LinkedHashMap<String, Object>(); for (int i = 0; i < count; i++) result.put("key" + i, tree(random, depth - 1)); return result;
    }
    @Test void randomStructuralParityWithExistingJsonDecoder() throws Exception {
        var random = new Random(17029);
        for (int i = 0; i < 100; i++) { var text = Json.stringify(tree(random, 5)); var expected = Json.parse(text); try (var source = index(text)) { verify(source.getRoot(), expected, "", false); } }
    }
    @Test void suppliedRankPortBoundariesAndIndependentQuarterPopulations() {
        for (int length : new int[]{0, 1, 63, 64, 65, 511, 512, 513, 2047, 2048, 2049, 4097}) {
            var random = new Random(length); var words = new long[(length + 63) / 64]; for (int i = 0; i < words.length; i++) words[i] = random.nextLong(); var original = words.clone(); var rank = JsonRankDirectory.build(words, length); long expected = 0;
            for (int position = 0; position <= length; position++) { assertEquals(expected, rank.rank1(position), length + " / " + position); if (position < length && (words[position >>> 6] & (1L << (position & 63))) != 0) expected++; }
            assertEquals(expected, rank.getTotalOnes()); assertArrayEquals(original, words); assertEquals(((length + 2047L) / 2048) * 8 + (length == 0 ? 0 : 8), rank.getDirectoryBytes());
        }
        int packed = 1 | (511 << 11) | (512 << 22); assertEquals(List.of(0, 1, 512, 1024), IntStream.rangeClosed(0, 3).map(it -> jsonRankRunPrefix(packed, it)).boxed().toList());
        var cursor = new JsonRankDirectoryCursor(); assertEquals(-1, cursor.before(1, 0xffffffffL)); assertThrows(ArithmeticException.class, () -> cursor.before(2, 1L << 32)); assertEquals(0, cursor.before(1L << 21, 1L << 32));
    }
}
