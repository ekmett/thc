// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import java.io.ByteArrayOutputStream;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import thc.runtime.CoreSources;
import static org.junit.jupiter.api.Assertions.*;

class CoreCompactDebugTest {
    @TempDir Path directory;
    private static class Bytes extends ByteArrayOutputStream {
        void u(int value) { int n = value; do { write((n & 127) | (n > 127 ? 128 : 0)); n >>>= 7; } while (n != 0); }
        void fixed(long value) { writeBytes(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array()); }
        void text(String value) { var bytes = value.getBytes(StandardCharsets.UTF_8); u(bytes.length); writeBytes(bytes); }
    }
    private void common(Bytes strings, Bytes out, String value) {
        var bytes = value.getBytes(StandardCharsets.UTF_8); out.u(strings.size()); out.u(bytes.length); strings.writeBytes(bytes);
    }
    private void finishTable(Bytes out, long[]... rows) {
        long start = out.size();
        for (var row : rows) { out.fixed(row[0]); out.fixed(row[1]); out.fixed(row[2]); }
        out.fixed(start); out.fixed(rows.length);
    }
    private long note(Bytes lines, boolean badPrimary, String id, int line, int index, int length) {
        long offset = lines.size();
        lines.write(1); lines.u(badPrimary ? 1 : 0); lines.u(1); lines.text(id);
        lines.write(2); lines.text("label:" + id);
        lines.u(line); lines.u(1); lines.u(line); lines.u(length + 1);
        lines.write(2); lines.u(index); lines.write(2); lines.u(length);
        return offset;
    }
    private byte[] fixture(boolean badRange, boolean badPrimary) throws java.io.IOException {
        var strings = new Bytes();
        var files = new Bytes();
        files.write(1); files.u(1); common(strings, files, "original"); common(strings, files, "Actual.hs");
        files.write(2); common(strings, files, "first\nsecond\n");
        long noFile = files.size(); files.write(0);
        finishTable(files, new long[]{0, badRange ? 101 : 30, 0}, new long[]{30, 40, noFile}, new long[]{40, 60, 0});
        var lines = new Bytes();
        long first = note(lines, badPrimary, "first-span", 1, 0, 5);
        long second = note(lines, badPrimary, "second-span", 2, 6, 6);
        long none = lines.size(); lines.write(0);
        finishTable(lines, new long[]{0, 10, first}, new long[]{10, 30, second}, new long[]{30, 40, none},
            new long[]{40, 50, first}, new long[]{50, 60, second});
        var names = new Bytes();
        var a = "outer".getBytes(StandardCharsets.UTF_8);
        var b = "λlocal".getBytes(StandardCharsets.UTF_8);
        var c = "Constructor".getBytes(StandardCharsets.UTF_8);
        names.writeBytes(a); names.writeBytes(b); names.writeBytes(c);
        long start = names.size();
        names.fixed(0); names.fixed(0); names.fixed(0); names.fixed(a.length);
        names.fixed(0); names.fixed(1); names.fixed(a.length); names.fixed(b.length);
        names.fixed(-1); names.fixed(1); names.fixed(a.length + b.length); names.fixed(c.length);
        names.fixed(start); names.fixed(3);
        return CoreCbdTestSupport.archive(CoreCbdTestSupport.header(new byte[0], 0, 0, 7),
            List.of(new byte[100], strings.toByteArray(), names.toByteArray(), files.toByteArray(), lines.toByteArray(), new byte[0]),
            Set.of(), false);
    }
    @FunctionalInterface private interface Action { void run(CoreCompactFile file) throws Exception; }
    private void withFile(byte[] bytes, Action action) throws Exception {
        var path = directory.resolve("debug.cbd"); Files.write(path, bytes);
        var identity = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        try (var cache = new CoreFileMappings(0, 0); var file = new CoreCompactFile(path, identity, false, cache)) { action.run(file); }
    }
    @Test void selectedIntervalsRestoreSourcesAndKeepGapsExplicit() throws Exception {
        withFile(fixture(false, false), file -> {
            var debug = new CoreCompactDebug(file);
            var origin = new CoreCompactRecords.Origin("identity", 20, 0, debug);
            var location = Objects.requireNonNull(new CoreSources(Map.of()).binding(Map.of("compactOrigin", origin), null));
            assertSame(origin, location.getCompactOrigin());
            assertEquals(0L, file.getCounters().statistics().debugBytesRead());
            assertEquals("second", Objects.requireNonNull(location.getSection()).getCharacters().toString());
            assertEquals("Actual.hs", location.getSection().getSource().getName());
            assertEquals(List.of("second-span"), location.getNotes().stream().map(it -> it.getId()).toList());
            long after = file.getCounters().statistics().debugBytesRead();
            assertEquals("second", location.getSection().getCharacters().toString());
            assertEquals(after, file.getCounters().statistics().debugBytesRead());
            assertNull(debug.location(30));
            assertEquals("first", Objects.requireNonNull(Objects.requireNonNull(debug.location(40)).getSection()).getCharacters().toString());
            assertNull(debug.location(60)); assertNull(debug.location(99));
            assertEquals("outer", debug.name(0, 0)); assertEquals("λlocal", debug.name(0, 1));
            assertNull(debug.name(0, 2)); assertNull(debug.name(1, 0));
            assertEquals("Constructor", debug.constructorName(0)); assertNull(debug.constructorName(1));
            assertThrows(IllegalArgumentException.class, () -> debug.name(-1, 1));
            assertEquals(0L, file.getCounters().statistics().dataBytesRead());
            assertEquals(0L, file.getCounters().statistics().hashBytesRead());
        });
    }
    @Test void disabledSourceNotesKeepOriginWithoutReadingDebugPayloads() throws Exception {
        withFile(fixture(false, false), file -> {
            var origin = new CoreCompactRecords.Origin("identity", 10, 0, new CoreCompactDebug(file));
            var location = Objects.requireNonNull(new CoreSources(Map.of("sourceNotesEnabled", false)).binding(Map.of("compactOrigin", origin), null));
            assertSame(origin, location.getCompactOrigin()); assertNull(location.getSection());
            assertTrue(location.getNotes().isEmpty());
            assertEquals(0L, file.getCounters().statistics().acquisitions());
            assertEquals(0L, file.getCounters().statistics().debugBytesRead());
        });
    }
    @Test void selectedMalformedRangesAndPrimaryIndicesFailLocally() throws Exception {
        withFile(fixture(true, false), file -> assertThrows(IllegalArgumentException.class, () -> new CoreCompactDebug(file).location(20)));
        withFile(fixture(false, true), file -> assertThrows(IllegalArgumentException.class, () -> new CoreCompactDebug(file).location(20)));
    }
}
