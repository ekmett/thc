// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause AND BSD-2-Clause
package thc;

import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;

import static thc.CoreJsonMasks.jsonMaskBlock;
import static thc.CoreJsonRank.*;

/**
 * Structural index of an owned immutable UTF-8 snapshot, not a parsed tree.
 * Full SimpleBP topology is paired with source-backed interest rank/select.
 * At most 512 source bytes regenerate a mask; no input-sized bitmap is retained.
 *
 * Topology follows rust-works/succinctly (MIT), revision
 * 6ee3210413d1f180fd6a93ab30c5bc6aaad29b78, json/simple.rs. Rank follows Everett,
 * eaa5ff3ccdb970cd684d8a01fe5fcea2d3bc23ca, include/everett/rank.h;
 * see third-party/licenses/everett-BSD-2-Clause.txt. This is THC's in-memory representation,
 * not an upstream compatibility or performance claim.
 *
 * Building checks container grammar and quoted boundaries, not scalar decoding,
 * duplicate keys or Core semantics. Closing drops source/index/cache ownership;
 * already returned decoded values remain owned by their caller.
 */
public final class CoreJsonIndex implements AutoCloseable {
    public enum Kind { OBJECT, ARRAY, STRING, ATOM }
    public record Statistics(int sourceByteSize, long indexByteSize, long sourceFileBytesRead, long sourceSnapshotBytesCopied, long sourceHashBytesScanned, long structuralBytesScanned, long decodedSpanCount, long decodedByteCount, long navigationByteReads, long balancedParenthesisBitsExamined, long interestDirectoryBytes, long lexerCheckpointBytes, long topologyBytes, long topologyNavigationBytes, long scratchBytes, long sourceIdentityBytes, long indexSourceBytesScanned, long regeneratedSourceBytes, long regeneratedBlockCount) {
        public int getSourceByteSize() { return sourceByteSize; }
        public long getIndexByteSize() { return indexByteSize; }
        public long getSourceFileBytesRead() { return sourceFileBytesRead; }
        public long getSourceSnapshotBytesCopied() { return sourceSnapshotBytesCopied; }
        public long getSourceHashBytesScanned() { return sourceHashBytesScanned; }
        public long getStructuralBytesScanned() { return structuralBytesScanned; }
        public long getDecodedSpanCount() { return decodedSpanCount; }
        public long getDecodedByteCount() { return decodedByteCount; }
        public long getNavigationByteReads() { return navigationByteReads; }
        public long getBalancedParenthesisBitsExamined() { return balancedParenthesisBitsExamined; }
        public long getInterestDirectoryBytes() { return interestDirectoryBytes; }
        public long getLexerCheckpointBytes() { return lexerCheckpointBytes; }
        public long getTopologyBytes() { return topologyBytes; }
        public long getTopologyNavigationBytes() { return topologyNavigationBytes; }
        public long getScratchBytes() { return scratchBytes; }
        public long getSourceIdentityBytes() { return sourceIdentityBytes; }
        public long getIndexSourceBytesScanned() { return indexSourceBytesScanned; }
        public long getRegeneratedSourceBytes() { return regeneratedSourceBytes; }
        public long getRegeneratedBlockCount() { return regeneratedBlockCount; }
    }
    public static final class Counters {
        public int sourceByteSize;
        public long indexByteSize;
        public long sourceFileBytesRead;
        public long sourceSnapshotBytesCopied;
        public long sourceHashBytesScanned;
        public long structuralBytesScanned;
        public long decodedSpanCount;
        public long decodedByteCount;
        public long navigationByteReads;
        public long balancedParenthesisBitsExamined;
        public long interestDirectoryBytes;
        public long lexerCheckpointBytes;
        public long topologyBytes;
        public long topologyNavigationBytes;
        public long scratchBytes;
        public long sourceIdentityBytes;
        public long indexSourceBytesScanned;
        public long regeneratedSourceBytes;
        public long regeneratedBlockCount;
        public synchronized Statistics statistics() { return new Statistics(sourceByteSize, indexByteSize, sourceFileBytesRead, sourceSnapshotBytesCopied, sourceHashBytesScanned, structuralBytesScanned, decodedSpanCount, decodedByteCount, navigationByteReads, balancedParenthesisBitsExamined, interestDirectoryBytes, lexerCheckpointBytes, topologyBytes, topologyNavigationBytes, scratchBytes, sourceIdentityBytes, indexSourceBytesScanned, regeneratedSourceBytes, regeneratedBlockCount); }
    }

    public record Member(String name, Span value) {
        public String getName() { return name; }
        public Span getValue() { return value; }
        public String component1() { return name; }
        public Span component2() { return value; }
    }
    private Storage storage;
    private final Counters counters;
    private final Map<Integer, Decoded> decoded = new HashMap<>();
    private record Decoded(Object value, Exception failure) {
        Object get() { return failure == null ? value : rethrow(failure); }
    }
    private CoreJsonIndex(Storage storage) { this.storage = storage; this.counters = storage.counters; }
    public Counters getCounters() { return counters; }
    private Storage live() {
        if (storage == null) throw new IllegalStateException("JSON index is closed");
        return storage;
    }
    public Span getRoot() { synchronized (counters) { var s = live(); return new Span(this, s.rootStart, s.rootEnd); } }
    public Statistics statistics() { synchronized (counters) { live(); return counters.statistics(); } }
    public String sha256() { synchronized (counters) { return HexFormat.of().formatHex(live().hash()); } }
    public Object validateDocument() { return getRoot().decode(); }
    public int rankInterest(int endExclusive) { synchronized (counters) { return live().interest.rank1(endExclusive); } }
    public int selectInterest(int ordinal) { synchronized (counters) { return live().interest.select(ordinal); } }
    @Override public void close() { synchronized (counters) { decoded.clear(); storage = null; } }

    public static final class Span {
        private final CoreJsonIndex owner;
        private final int begin, end;
        public Span(CoreJsonIndex owner, int begin, int end) { this.owner = owner; this.begin = begin; this.end = end; }
        public Kind getKind() { return owner.kind(begin); }
        public int getStart() { synchronized (owner.counters) { owner.live(); return begin; } }
        public int getEndExclusive() { synchronized (owner.counters) { owner.live(); return end; } }
        public int getBegin() { return begin; }
        public int getEnd() { return end; }
        public Object decode() { return owner.decode(this); }
        public Object decodeUncached() { return owner.decodeUncached(this); }
        public boolean stringEquals(String expected) { return owner.stringEquals(this, expected); }
        public List<Span> elements() { return owner.children(this, Kind.ARRAY); }
        public List<Member> members() { return owner.members(this); }
        public Span member(String name) { return owner.member(this, name); }
        public byte[] bytes() { synchronized (owner.counters) { return Arrays.copyOfRange(owner.live().bytes, begin, end); } }
    }
    private int read(Storage s, int at) { counters.navigationByteReads++; return s.bytes[at] & 255; }
    private Kind kind(int start) {
        synchronized (counters) {
            return switch (read(live(), start)) { case 123 -> Kind.OBJECT; case 91 -> Kind.ARRAY; case 34 -> Kind.STRING; default -> Kind.ATOM; };
        }
    }
    private Object decode(Span span) {
        synchronized (counters) {
            live();
            Decoded previous = decoded.get(span.begin);
            if (previous != null) return previous.get();
            Decoded result;
            try { result = new Decoded(decodeUncached(span), null); }
            catch (Exception failure) { result = new Decoded(null, failure); }
            decoded.put(span.begin, result);
            return result.get();
        }
    }
    private Object decodeUncached(Span span) {
        synchronized (counters) {
            var s = live();
            counters.decodedSpanCount++; counters.decodedByteCount += span.end - span.begin;
            try {
                String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(s.bytes, span.begin, span.end - span.begin)).toString();
                return Json.parse(text);
            } catch (CharacterCodingException failure) { return rethrow(failure); }
        }
    }
    private int endAt(Storage s, int start, int limit) {
        int initial = read(s, start);
        if (initial == 123 || initial == 91) {
            int marker = s.interest.rank1(start), open = Math.multiplyExact(marker, 2);
            check(open < s.bp.bits.size && s.bp.bits.get(open) && s.bp.bits.get(open + 1), "Check failed.");
            int close = s.bp.close(open, () -> counters.balancedParenthesisBitsExamined++);
            int end = Math.addExact(s.interest.select(close / 2), 1);
            require(end >= start + 2 && end <= limit && read(s, end - 1) == (s.bytes[start] == 123 ? 125 : 93),
                    "Invalid JSON container extent");
            return end;
        }
        int marker = s.interest.rank1(start), end = s.interest.select(marker);
        check(end >= start + 1 && end <= limit, "Check failed.");
        while (end > start && whitespace(read(s, end - 1))) end--;
        return end;
    }
    private List<Span> children(Span span, Kind expected) {
        synchronized (counters) {
            require(kind(span.begin) == expected, "Expected JSON " + expected);
            return new AbstractList<>() {
                @Override public int size() { int count = 0; for (var ignored : this) count++; return count; }
                @Override public Span get(int index) {
                    if (index < 0) throw new IndexOutOfBoundsException("JSON child " + index);
                    var cursor = iterator();
                    for (int i = 0; i < index; i++) {
                        if (!cursor.hasNext()) throw new IndexOutOfBoundsException("JSON child " + index);
                        cursor.next();
                    }
                    if (!cursor.hasNext()) throw new IndexOutOfBoundsException("JSON child " + index);
                    return cursor.next();
                }
                @Override public Iterator<Span> iterator() {
                    return new Iterator<>() {
                        int next = span.begin + 1;
                        private void advance(Storage s) {
                            while (next < span.end - 1) {
                                int c = read(s, next);
                                if (!whitespace(c) && c != 44 && c != 58) break;
                                next++;
                            }
                        }
                        public boolean hasNext() { synchronized (counters) { advance(live()); return next < span.end - 1; } }
                        public Span next() {
                            synchronized (counters) {
                                var s = live(); advance(s);
                                if (next >= span.end - 1) throw new NoSuchElementException();
                                int end = endAt(s, next, span.end - 1);
                                var result = new Span(CoreJsonIndex.this, next, end);
                                next = end;
                                return result;
                            }
                        }
                    };
                }
            };
        }
    }
    private List<Member> members(Span span) {
        synchronized (counters) {
            var children = children(span, Kind.OBJECT).iterator();
            Set<String> seen = new HashSet<>();
            List<Member> result = new ArrayList<>();
            while (children.hasNext()) {
                String key = (String) children.next().decode();
                require(seen.add(key), "Duplicate JSON key: " + key);
                result.add(new Member(key, children.next()));
            }
            return result;
        }
    }
    private Span member(Span span, String name) {
        synchronized (counters) {
            var children = children(span, Kind.OBJECT).iterator();
            Span found = null;
            while (children.hasNext()) {
                Span key = children.next(), value = children.next();
                if (key.stringEquals(name)) {
                    require(found == null, "Duplicate JSON key: " + name);
                    found = value;
                }
            }
            return found;
        }
    }
    private boolean stringEquals(Span span, String expected) {
        synchronized (counters) {
            require(kind(span.begin) == Kind.STRING, "Expected JSON string");
            var s = live();
            class Cursor {
                int at = span.begin + 1, matched;
                int read() {
                    require(at < span.end - 1, "Incomplete JSON string");
                    counters.navigationByteReads++;
                    return s.bytes[at++] & 255;
                }
                boolean matches(char c) { return matched < expected.length() && expected.charAt(matched++) == c; }
            }
            var cursor = new Cursor();
            while (cursor.at < span.end - 1) {
                int c = cursor.read();
                if (c == 92) {
                    c = switch (cursor.read()) {
                        case 34 -> 34; case 92 -> 92; case 47 -> 47;
                        case 98 -> 8; case 102 -> 12; case 110 -> 10; case 114 -> 13; case 116 -> 9;
                        case 117 -> {
                            int value = 0;
                            for (int i = 0; i < 4; i++) {
                                int digit = Character.digit((char) cursor.read(), 16);
                                require(digit >= 0, "Invalid JSON Unicode escape");
                                value = (value << 4) | digit;
                            }
                            yield value;
                        }
                        default -> throw new IllegalStateException("Unknown JSON escape");
                    };
                } else if (c >= 128) {
                    int count;
                    if (c >= 194 && c <= 223) count = 1;
                    else if (c >= 224 && c <= 239) count = 2;
                    else if (c >= 240 && c <= 244) count = 3;
                    else throw new IllegalStateException("Invalid UTF-8");
                    int point = c & (127 >>> count);
                    for (int i = 0; i < count; i++) {
                        int b = cursor.read();
                        require(b >= 128 && b <= 191, "Invalid UTF-8");
                        point = (point << 6) | (b & 63);
                    }
                    require(point >= (count == 1 ? 128 : count == 2 ? 2048 : 65536) &&
                            point <= 0x10ffff && !(point >= 0xd800 && point <= 0xdfff), "Invalid UTF-8");
                    c = point;
                }
                if (c > 0xffff) {
                    if (!cursor.matches(Character.highSurrogate(c)) || !cursor.matches(Character.lowSurrogate(c))) return false;
                } else if (!cursor.matches((char) c)) return false;
            }
            return cursor.matched == expected.length();
        }
    }
    private static final class Storage {
        final byte[] bytes;
        final SourceInterest interest;
        final Parentheses bp;
        final int rootStart, rootEnd;
        final Counters counters;
        private byte[] sourceHash;
        Storage(byte[] bytes, SourceInterest interest, Parentheses bp, long scanBytes, int rootStart, int rootEnd,
                long fileBytesRead, long snapshotBytesCopied) {
            this.bytes = bytes; this.interest = interest; this.bp = bp; this.rootStart = rootStart; this.rootEnd = rootEnd;
            counters = interest.counters;
            counters.sourceByteSize = bytes.length;
            counters.indexByteSize = interest.byteSize() + bp.byteSize();
            counters.sourceFileBytesRead = fileBytesRead;
            counters.sourceSnapshotBytesCopied = snapshotBytesCopied;
            counters.structuralBytesScanned = scanBytes;
            counters.interestDirectoryBytes = interest.directoryBytes();
            counters.lexerCheckpointBytes = interest.checkpointBytes();
            counters.topologyBytes = (long) bp.bits.data.length * 8;
            counters.topologyNavigationBytes = bp.byteSize() - counters.topologyBytes;
            counters.scratchBytes = interest.scratchBytes();
        }
        byte[] hash() {
            if (sourceHash != null) return sourceHash;
            sourceHash = digest().digest(bytes);
            counters.sourceHashBytesScanned += bytes.length;
            counters.sourceIdentityBytes = sourceHash.length;
            counters.indexByteSize += sourceHash.length;
            return sourceHash;
        }
    }
    public static CoreJsonIndex fromBytes(byte[] bytes) { return build(bytes.clone(), 0, bytes.length); }
    public static CoreJsonIndex read(Path path) {
        try { byte[] bytes = Files.readAllBytes(path); return build(bytes, bytes.length, 0); }
        catch (IOException failure) { return rethrow(failure); }
    }
    private static CoreJsonIndex build(byte[] bytes, long fileBytesRead, long copiedBytes) {
        int[] extent = new Scanner(bytes).scan();
        var built = SourceInterest.build(bytes);
        return new CoreJsonIndex(new Storage(bytes, built.interest, new Parentheses(built.bp), bytes.length,
                extent[0], extent[1], fileBytesRead, copiedBytes));
    }
    /** Iterative structural grammar check without scalar conversion. */
    private static final class Scanner {
        final byte[] bytes;
        int at, depth;
        byte[] states = new byte[32];
        Scanner(byte[] bytes) { this.bytes = bytes; }
        void push(int state) {
            if (depth == states.length) states = Arrays.copyOf(states, Math.multiplyExact(states.length, 2));
            states[depth++] = (byte) state;
        }
        void quoted() {
            at++;
            while (at < bytes.length) {
                int c = bytes[at++] & 255;
                if (c == 34) return;
                require(c >= 32, "Control character in JSON string at " + (at - 1));
                if (c == 92) { require(at < bytes.length, "Incomplete JSON escape"); at++; }
            }
            throw new IllegalStateException("Unterminated JSON string");
        }
        void value() {
            require(at < bytes.length, "Missing JSON value");
            int c = bytes[at] & 255;
            switch (c) {
                case 123 -> { at++; push(0); }
                case 91 -> { at++; push(5); }
                case 34 -> quoted();
                default -> {
                    require(c == 45 || c >= 48 && c <= 57 || c == 116 || c == 102 || c == 110, "Invalid JSON value boundary at " + at);
                    at++;
                    while (at < bytes.length && !delimiter(bytes[at] & 255)) at++;
                }
            }
        }
        int[] scan() {
            boolean root = false;
            int rootStart = 0;
            while (true) {
                int beforeWhitespace = at;
                while (at < bytes.length && whitespace(bytes[at] & 255)) at++;
                if (depth == 0) {
                    if (root) { require(at == bytes.length, "Trailing JSON at " + at); return new int[] {rootStart, beforeWhitespace}; }
                    root = true; rootStart = at; value(); continue;
                }
                require(at < bytes.length, "Unclosed JSON container");
                int c = bytes[at] & 255;
                switch (states[depth - 1]) {
                    case 0, 1 -> {
                        if (c == 125 && states[depth - 1] == 0) { depth--; at++; }
                        else { require(c == 34, "Expected JSON object key at " + at); states[depth - 1] = 2; quoted(); }
                    }
                    case 2 -> { require(c == 58, "Expected JSON colon at " + at); at++; states[depth - 1] = 3; }
                    case 3 -> { states[depth - 1] = 4; value(); }
                    case 4 -> {
                        if (c == 125) { depth--; at++; }
                        else if (c == 44) { at++; states[depth - 1] = 1; }
                        else throw new IllegalStateException("Expected JSON comma or object end at " + at);
                    }
                    case 5, 6 -> {
                        if (c == 93 && states[depth - 1] == 5) { depth--; at++; }
                        else { states[depth - 1] = 7; value(); }
                    }
                    case 7 -> {
                        if (c == 93) { depth--; at++; }
                        else if (c == 44) { at++; states[depth - 1] = 6; }
                        else throw new IllegalStateException("Expected JSON comma or array end at " + at);
                    }
                }
            }
        }
    }
    static int words(int bits) { return (int) (((long) bits + 63) / 64); }

    private static boolean whitespace(int c) { return c == 32 || c == 9 || c == 10 || c == 13; }
    private static boolean delimiter(int c) { return whitespace(c) || c == 123 || c == 125 || c == 91 || c == 93 || c == 44 || c == 58 || c == 34; }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalArgumentException(message); }
    private static void check(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException failure) { return rethrow(failure); }
    }
    @SuppressWarnings("unchecked")
    private static <T, E extends Throwable> T rethrow(Throwable failure) throws E { throw (E) failure; }

    private static final class Bits {
        final long[] data;
        final int size;
        final JsonRankDirectory directory;
        Bits(long[] data, int size) { this.data = data; this.size = size; directory = JsonRankDirectory.build(data, size); }
        long byteSize() { return (long) data.length * 8 + directory.getDirectoryBytes(); }
        boolean get(int bit) { return (data[bit >>> 6] & (1L << (bit & 63))) != 0; }
        int rank(int end) { return Math.toIntExact(directory.rank1(end)); }
    }
    /** Source masks are regenerated only for the selected 512-byte block. */
    private static final class SourceInterest {
        final byte[] source;
        int count;
        final int[] directory;
        final long[] supers, states;
        final int quarters;
        final long[] masks = new long[8], opens = new long[8], closes = new long[8];
        int cachedQuarter = -1;
        final Counters counters = new Counters();
        SourceInterest(byte[] source, int count, int[] directory, long[] supers, long[] states) {
            this.source = source; this.count = count; this.directory = directory; this.supers = supers; this.states = states;
            quarters = (int) (((long) source.length + 511) / 512);
        }
        long directoryBytes() { return (long) directory.length * 4 + (long) supers.length * 8; }
        long checkpointBytes() { return (long) states.length * 8; }
        long scratchBytes() { return (long) (masks.length + opens.length + closes.length) * 8; }
        long byteSize() { return directoryBytes() + checkpointBytes() + scratchBytes(); }
        int state(int quarter) { return (int) ((states[quarter >>> 5] >>> ((quarter & 31) * 2)) & 3); }
        int prefix(int quarter) { return quarter == quarters ? count : Math.toIntExact(jsonRankQuarterPrefix(directory, supers, (long) quarter * 512)); }
        void regenerate(int quarter) {
            if (cachedQuarter == quarter) return;
            int start = Math.multiplyExact(quarter, 512), length = Math.min(512, source.length - start);
            jsonMaskBlock(source, start, length, state(quarter), masks, opens, closes);
            cachedQuarter = quarter;
            counters.regeneratedSourceBytes += length;
            counters.regeneratedBlockCount++;
        }
        int rank1(int end) {
            require(end >= 0 && end <= source.length, "Failed requirement.");
            if (end == source.length) return count;
            int quarter = end >>> 9, before = prefix(quarter), bits = end & 511;
            if (bits == 0) return before;
            regenerate(quarter);
            return Math.addExact(before, jsonRankPrefix512(masks, 0, bits));
        }
        int select(int ordinal) {
            require(ordinal >= 0 && ordinal < count, "Failed requirement.");
            int lo = 0, hi = quarters;
            while (lo + 1 < hi) {
                int mid = (lo + hi) >>> 1;
                if (prefix(mid) <= ordinal) lo = mid; else hi = mid;
            }
            int remaining = ordinal - prefix(lo);
            regenerate(lo);
            for (int word = 0; word < masks.length; word++) {
                long value = masks[word];
                int population = Long.bitCount(value);
                if (remaining < population) {
                    for (int i = 0; i < remaining; i++) value &= value - 1;
                    long position = (long) lo * 512 + word * 64 + Long.numberOfTrailingZeros(value);
                    require(position < source.length, "Invalid JSON interest position");
                    return Math.toIntExact(position);
                }
                remaining -= population;
            }
            throw new IllegalStateException("Missing JSON interest bit");
        }
        @FunctionalInterface interface QuarterVisitor { void visit(int quarter, int initialState); }
        void walk(QuarterVisitor visit) {
            int lexer = 0;
            for (int quarter = 0; quarter < quarters; quarter++) {
                int start = quarter * 512, before = lexer;
                lexer = jsonMaskBlock(source, start, Math.min(512, source.length - start), lexer, masks, opens, closes);
                visit.visit(quarter, before);
            }
            counters.indexSourceBytesScanned += source.length;
            cachedQuarter = -1;
        }
        @FunctionalInterface interface MarkerVisitor { void visit(boolean opening, boolean closing); }
        void markers(MarkerVisitor visit) {
            for (int word = 0; word < masks.length; word++) {
                long value = masks[word];
                while (value != 0) {
                    long bit = value & -value;
                    visit.visit((opens[word] & bit) != 0, (closes[word] & bit) != 0);
                    value &= value - 1;
                }
            }
        }
        record Built(SourceInterest interest, Bits bp) {}
        static Built build(byte[] source) {
            var shape = new JsonIndexShape(source.length, 0);
            var result = new SourceInterest(source, 0, new int[shape.getBlockCount() * 2],
                    new long[shape.getEpochCount()], new long[words(shape.getCheckpointBits())]);
            var cursor = new JsonRankDirectoryCursor();
            long[] total = {0};
            result.walk((quarter, before) -> {
                result.states[quarter >>> 5] |= (long) before << ((quarter & 31) * 2);
                int block = quarter >>> 2, run = quarter & 3;
                if (run == 0) {
                    result.directory[block * 2] = cursor.before(block, total[0]);
                    if (JsonRankDirectoryCursor.startsEpoch(block)) result.supers[block >>> 21] = cursor.getEpochBase();
                }
                int population = jsonRankPrefix512(result.masks, 0, 512);
                if (run < 3) result.directory[block * 2 + 1] |= population << (run * 11);
                total[0] += population;
            });
            result.count = Math.toIntExact(total[0]);
            int bitCount = Math.toIntExact(total[0] * 2);
            long[] bp = new long[words(bitCount)];
            int[] marker = {0};
            result.walk((quarter, before) -> result.markers((opening, closing) -> {
                long pair = opening ? 3 : closing ? 0 : 2;
                int bit = marker[0]++ * 2;
                bp[bit >>> 6] |= pair << (bit & 63);
            }));
            check(marker[0] == result.count, "Check failed.");
            return new Built(result, new Bits(bp, bitCount));
        }
    }
    /** Range-minimum tree over 512-bit blocks; a skipped subtree costs at most two blocks plus a tree search. */
    private static final class Parentheses {
        final Bits bits;
        final int blocks, leaves;
        final int[] minimum;
        Parentheses(Bits bits) {
            this.bits = bits;
            blocks = (int) (((long) bits.size + 511) / 512);
            leaves = Integer.highestOneBit(Math.max(1, blocks - 1)) * 2;
            minimum = new int[leaves * 2];
            Arrays.fill(minimum, Integer.MAX_VALUE);
            int excess = 0;
            for (int i = 0; i < bits.size; i++) {
                excess += bits.get(i) ? 1 : -1;
                int leaf = leaves + i / 512;
                minimum[leaf] = Math.min(minimum[leaf], excess);
            }
            for (int i = leaves - 1; i >= 1; i--) minimum[i] = Math.min(minimum[i * 2], minimum[i * 2 + 1]);
        }
        long byteSize() { return bits.byteSize() + (long) minimum.length * 4; }
        int first(int node, int left, int right, int from, int target) {
            if (right <= from || minimum[node] > target) return -1;
            if (right - left == 1) return left;
            int middle = (left + right) >>> 1;
            int earlier = first(node * 2, left, middle, from, target);
            return earlier >= 0 ? earlier : first(node * 2 + 1, middle, right, from, target);
        }
        int close(int open, Runnable examined) {
            int target = bits.rank(open) * 2 - open, excess = target + 1;
            int blockEnd = (int) Math.min(bits.size, (long) (open / 512 + 1) * 512);
            for (int i = open + 1; i < blockEnd; i++) {
                examined.run(); excess += bits.get(i) ? 1 : -1; if (excess == target) return i;
            }
            int block = first(1, 0, leaves, open / 512 + 1, target);
            check(block >= 0 && block < blocks, "Unbalanced JSON index");
            int start = block * 512;
            excess = bits.rank(start) * 2 - start;
            for (int i = start; i < (int) Math.min(bits.size, (long) start + 512); i++) {
                examined.run(); excess += bits.get(i) ? 1 : -1; if (excess == target) return i;
            }
            throw new IllegalStateException("Unbalanced JSON index block");
        }
    }
}
