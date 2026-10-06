// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** CBD facts header and uncompressed member-relative payload extents. */
public final class CoreCompactFormat {
    public static final String NAME = "thc-cbd-v1";
    public static final long HEADER_BYTES = 32, SYMBOL_BYTES = 24;
    private static final byte[] MAGIC = "THCCBD1\0".getBytes(StandardCharsets.US_ASCII);
    private CoreCompactFormat() {}

    public enum Segment {
        DATA("data"), STRINGS("strings"), NAMES("names"), FILENAMES("filenames"),
        LINE_COLUMNS("line-columns"), SYMBOLS("symbols");
        private final String member;
        Segment(String member) { this.member = member; }
        public String getMember() { return member; }
    }
    public record Span(long offset, long length) {}
    public record Header(Span facts, Span metadataStrings, List<Span> segments, long bindingCount, int summaries, int debug) {
        public Header { Objects.requireNonNull(facts); Objects.requireNonNull(metadataStrings); Objects.requireNonNull(segments); }
        public Span get(Segment segment) { return segments.get(segment.ordinal()); }
        public boolean getContainsDelimitedControl() { return (summaries & 1) != 0; }
        public boolean getRegistrationObligations() { return (summaries & 2) != 0; }
        public boolean getMainAlias() { return (summaries & 4) != 0; }
        public boolean getPackageScalarDeclarations() { return (summaries & 8) != 0; }
        public boolean getContainsRecoveryFacts() { return (summaries & 32) != 0; }
        public boolean getContainsHostSignatures() { return (summaries & 16) != 0; }
    }
    public static Header read(MemorySegment bytes, List<Long> lengths) {
        Objects.requireNonNull(bytes);
        Objects.requireNonNull(lengths);
        if (bytes.byteSize() < HEADER_BYTES + Long.BYTES) throw new IllegalArgumentException("Truncated CBD header");
        if (lengths.size() != Segment.values().length || lengths.stream().anyMatch(value -> value < 0)) {
            throw new IllegalArgumentException("Invalid CBD member lengths");
        }
        CoreCompactCursor cursor = new CoreCompactCursor(bytes, 0, HEADER_BYTES + Long.BYTES);
        if (!Arrays.equals(cursor.bytes(8), MAGIC)) throw new IllegalArgumentException("Invalid CBD header magic");
        if (cursor.u16() != 1 || cursor.u16() != 2) throw new IllegalArgumentException("Unsupported CBD version");
        long summaries = cursor.u32(), count = cursor.offset(), debug = cursor.u32();
        if ((summaries & ~63L) != 0 || (debug & ~7L) != 0 || cursor.u32() != 0) {
            throw new IllegalArgumentException("Reserved CBD header flags");
        }
        long metadataLength = cursor.offset();
        cursor.expectEnd();
        long metadataStart = HEADER_BYTES + Long.BYTES;
        CoreCompactCursor.slice(bytes, metadataStart, metadataLength);
        long factsStart = metadataStart + metadataLength;
        long symbols = lengths.get(Segment.SYMBOLS.ordinal());
        if (symbols % SYMBOL_BYTES != 0 || count != symbols / SYMBOL_BYTES) {
            throw new IllegalArgumentException("Invalid CBD symbol count");
        }
        Segment[] debugSegments = {Segment.NAMES, Segment.FILENAMES, Segment.LINE_COLUMNS};
        for (int bit = 0; bit < debugSegments.length; bit++) {
            Segment segment = debugSegments[bit];
            if (((debug & (1L << bit)) != 0) != (lengths.get(segment.ordinal()) != 0)) {
                throw new IllegalArgumentException("CBD debug flag disagrees with member: " + segment);
            }
        }
        var spans = new ArrayList<Span>(lengths.size());
        for (long length : lengths) spans.add(new Span(0, length));
        return new Header(new Span(factsStart, bytes.byteSize() - factsStart), new Span(metadataStart, metadataLength),
                spans, count, (int) summaries, (int) debug);
    }
}
