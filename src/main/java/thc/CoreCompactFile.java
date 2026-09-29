// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.zip.CRC32;

/** One lazily acquired immutable CBD archive. Mapped and inflated bytes are
 * process-wide; cursors, decoded objects and detached counters belong to this load. */
public final class CoreCompactFile implements AutoCloseable {
    private final Path path;
    private final String identity;
    private final boolean verifyArtifacts;
    private final CoreFileMappings mappings;
    private final CoreCbdSlabs slabs;

    public record Statistics(long acquisitions, long physicalOpens, long cacheHits,
            long mappedBytes, long headerBytesRead, long lookupBytesRead,
            long lookupComparisons, long dataBytesRead, long stringBytesRead,
            long debugBytesRead, long hashBytesRead, long decodedBindings, long decodedModules,
            long directoryBytesRead, long memberInflations, long inflatedBytes,
            long compressedBytesRead, long slabCacheHits, long verifiedStoredBytes) {}
    public static final class Counters {
        public long acquisitions, physicalOpens, cacheHits, mappedBytes, headerBytesRead,
                lookupBytesRead, lookupComparisons, dataBytesRead, stringBytesRead,
                debugBytesRead, hashBytesRead, decodedBindings, decodedModules,
                directoryBytesRead, memberInflations, inflatedBytes, compressedBytesRead,
                slabCacheHits, verifiedStoredBytes;
        public synchronized Statistics statistics() {
            return new Statistics(acquisitions, physicalOpens, cacheHits, mappedBytes,
                    headerBytesRead, lookupBytesRead, lookupComparisons, dataBytesRead, stringBytesRead,
                    debugBytesRead, hashBytesRead, decodedBindings, decodedModules, directoryBytesRead,
                    memberInflations, inflatedBytes, compressedBytesRead, slabCacheHits, verifiedStoredBytes);
        }
    }
    @FunctionalInterface public interface Decoder<T> { T decode(CoreCompactCursor cursor) throws Throwable; }
    @FunctionalInterface public interface OffsetVisitor { void visit(long offset) throws Throwable; }

    private final Counters counters;
    private boolean closed;
    private Mapped mapped;
    private MessageDigest digest;

    public CoreCompactFile(Path path, String identity) {
        this(path, identity, false, CoreFileMappings.shared, CoreCbdSlabs.shared);
    }
    public CoreCompactFile(Path path, String identity, boolean verifyArtifacts) {
        this(path, identity, verifyArtifacts, CoreFileMappings.shared, CoreCbdSlabs.shared);
    }
    public CoreCompactFile(Path path, String identity, boolean verifyArtifacts, CoreFileMappings mappings) {
        this(path, identity, verifyArtifacts, mappings, CoreCbdSlabs.shared);
    }
    public CoreCompactFile(Path path, String identity, boolean verifyArtifacts,
            CoreFileMappings mappings, CoreCbdSlabs slabs) {
        Objects.requireNonNull(path);
        Objects.requireNonNull(identity);
        Objects.requireNonNull(mappings);
        Objects.requireNonNull(slabs);
        this.path = path;
        this.identity = identity;
        this.verifyArtifacts = verifyArtifacts;
        this.mappings = mappings;
        this.slabs = slabs;
        counters = new Counters();
        if (!(identity.isEmpty() && !verifyArtifacts) && !identity.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Missing compact Core producer identity");
    }
    public Counters getCounters() { return counters; }

    private static final class Mapped implements AutoCloseable {
        final CoreCbdArchive archive;
        final CoreCompactFormat.Header header;
        final Map<String, CoreCbdArchive.Handle> handles;
        Mapped(CoreCbdArchive archive, CoreCompactFormat.Header header, Map<String, CoreCbdArchive.Handle> handles) {
            this.archive = archive;
            this.header = header;
            this.handles = handles;
        }
        @Override public void close() throws Exception {
            try {
                for (CoreCbdArchive.Handle handle : handles.values()) handle.close();
                handles.clear();
            } finally { archive.close(); }
        }
    }
    private void account(CoreCbdArchive.Handle handle) {
        if (handle.getInflated()) {
            counters.memberInflations++;
            counters.inflatedBytes += handle.getMember().length();
            counters.compressedBytesRead += handle.getMember().compressed();
        }
        if (handle.getCacheHit()) counters.slabCacheHits++;
    }
    private MemorySegment member(Mapped current, String name) {
        CoreCbdArchive.Handle handle = current.handles.get(name);
        if (handle == null) {
            handle = current.archive.read(name);
            account(handle);
            current.handles.put(name, handle);
        }
        return handle.getBytes();
    }
    private Mapped mapping() throws Exception {
        if (closed) throw new IllegalStateException("Compact Core file is closed");
        if (mapped != null) return mapped;
        counters.acquisitions++;
        // Verification observes the currently named file and retains that SAME
        // fresh lease. Only producer-identified loads may share mappings; loose
        // unverified inputs use a fresh snapshot without inventing or hashing an identity.
        CoreFileMappings.Lease lease = verifyArtifacts || identity.isEmpty() ? mappings.acquireUncached(path) : mappings.acquire(path, identity);
        if (lease.getOpened()) counters.physicalOpens++; else counters.cacheHits++;
        CoreCbdArchive archive = null;
        var handles = new LinkedHashMap<String, CoreCbdArchive.Handle>();
        try {
            MemorySegment bytes = lease.getBytes();
            counters.mappedBytes += bytes.byteSize();
            if (verifyArtifacts) {
                MessageDigest sha = MessageDigest.getInstance("SHA-256");
                long at = 0;
                while (at < bytes.byteSize()) {
                    long length = Math.min(8192L, bytes.byteSize() - at);
                    sha.update(bytes.asSlice(at, length).asByteBuffer());
                    at += length;
                    counters.hashBytesRead += length;
                }
                if (!HexFormat.of().formatHex(sha.digest()).equals(identity)) {
                    throw new IllegalArgumentException("Compact Core artifact hash mismatch: " + path);
                }
            }
            archive = CoreCbdArchive.open(lease, slabs);
            counters.directoryBytesRead += archive.getDirectoryBytesRead();
            CoreCbdArchive.Handle head = archive.read("header");
            handles.put("header", head);
            account(head);
            var lengths = new ArrayList<Long>();
            for (CoreCompactFormat.Segment segment : CoreCompactFormat.Segment.values()) {
                lengths.add(archive.member(segment.getMember()).length());
            }
            CoreCompactFormat.Header header = CoreCompactFormat.read(head.getBytes(), lengths);
            counters.headerBytesRead += CoreCompactFormat.HEADER_BYTES + Long.BYTES;
            Mapped result = new Mapped(archive, header, handles);
            if (verifyArtifacts) for (String name : CoreCbdArchive.NAMES) {
                MemorySegment payload = member(result, name);
                CoreCbdArchive.Member entry = archive.member(name);
                if (entry.method() == 0) {
                    CRC32 crc = new CRC32();
                    long at = 0;
                    while (at < payload.byteSize()) {
                        long length = Math.min(65536L, payload.byteSize() - at);
                        crc.update(payload.asSlice(at, length).asByteBuffer());
                        at += length;
                        counters.verifiedStoredBytes += length;
                    }
                    if (crc.getValue() != entry.crc()) throw new IllegalArgumentException("CBD member CRC mismatch: " + name);
                }
            }
            mapped = result;
            return result;
        } catch (Throwable failure) {
            for (CoreCbdArchive.Handle handle : handles.values()) handle.close();
            if (archive != null) archive.close(); else lease.close();
            throw failure;
        }
    }
    public CoreCompactFormat.Header header() throws Exception {
        synchronized (counters) { return mapping().header; }
    }

    /** Fixed-width MD5 search; no strings or executable records are decoded. */
    public Long lookup(String id) throws Exception {
        Objects.requireNonNull(id);
        synchronized (counters) {
            Mapped current = mapping();
            CoreCompactFormat.Span symbols = current.header.get(CoreCompactFormat.Segment.SYMBOLS);
            MemorySegment bytes = member(current, "symbols");
            if (digest == null) digest = MessageDigest.getInstance("MD5");
            byte[] key = digest.digest(id.getBytes(StandardCharsets.UTF_8));
            long low = 0, high = current.header.bindingCount();
            while (low < high) {
                long middle = low + (high - low) / 2;
                long start = symbols.offset() + middle * CoreCompactFormat.SYMBOL_BYTES;
                counters.lookupComparisons++;
                int comparison = 0;
                for (int index = 0; index < key.length; index++) {
                    counters.lookupBytesRead++;
                    comparison = (bytes.get(ValueLayout.JAVA_BYTE, start + index) & 255) - (key[index] & 255);
                    if (comparison != 0) break;
                }
                if (comparison < 0) low = middle + 1;
                else if (comparison > 0) high = middle;
                else {
                    long offset = bytes.get(ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN), start + 16);
                    counters.lookupBytesRead += 8;
                    if (offset < 0 || offset >= current.header.get(CoreCompactFormat.Segment.DATA).length()) {
                        throw new IllegalArgumentException("Invalid compact Core binding offset: " + offset);
                    }
                    return offset;
                }
            }
            return null;
        }
    }

    /** Exhaustive verification only. Ordinary lookup never walks this table. */
    public void verifyBindingOffsets(OffsetVisitor visit) throws Throwable {
        if (!verifyArtifacts) throw new IllegalStateException("Complete compact binding inspection requires explicit verification");
        visitBindingOffsets(visit);
    }
    /** Explicit loose modules enumerate their definitions; package demand still uses lookup. */
    void visitBindingOffsets(OffsetVisitor visit) throws Throwable {
        Objects.requireNonNull(visit);
        synchronized (counters) {
            Mapped current = mapping();
            CoreCompactFormat.Span span = current.header.get(CoreCompactFormat.Segment.SYMBOLS);
            CoreCompactCursor cursor = new CoreCompactCursor(CoreCompactCursor.slice(member(current, "symbols"), span.offset(), span.length()));
            byte[] previous = null;
            try {
                while (cursor.getRemaining() != 0) {
                    byte[] digest = cursor.bytes(16);
                    if (previous != null && Arrays.compareUnsigned(previous, digest) >= 0) {
                        throw new IllegalArgumentException("Unordered or duplicate compact Core fingerprint");
                    }
                    previous = digest;
                    long offset = cursor.offset();
                    if (offset >= current.header.get(CoreCompactFormat.Segment.DATA).length()) {
                        throw new IllegalArgumentException("Invalid compact Core binding offset: " + offset);
                    }
                    visit.visit(offset);
                }
            } finally { counters.lookupBytesRead += cursor.getPosition(); }
        }
    }

    /** The callback must finish while this file owns its member leases. */
    public <T> T data(long offset, Decoder<T> decode) throws Throwable {
        Objects.requireNonNull(decode);
        synchronized (counters) {
            Mapped current = mapping();
            CoreCompactFormat.Span span = current.header.get(CoreCompactFormat.Segment.DATA);
            CoreCompactCursor cursor = new CoreCompactCursor(CoreCompactCursor.slice(member(current, "data"), span.offset(), span.length()), offset);
            try { return decode.decode(cursor); }
            finally { counters.dataBytesRead += cursor.getPosition() - offset; }
        }
    }
    public <T> T facts(Decoder<T> decode) throws Throwable {
        Objects.requireNonNull(decode);
        synchronized (counters) {
            Mapped current = mapping();
            CoreCompactFormat.Span span = current.header.facts();
            CoreCompactCursor cursor = new CoreCompactCursor(CoreCompactCursor.slice(member(current, "header"), span.offset(), span.length()));
            try { return decode.decode(cursor); }
            finally { counters.headerBytesRead += cursor.getPosition(); }
        }
    }
    public String string(long offset, long length) throws Exception {
        synchronized (counters) {
            Mapped current = mapping();
            CoreCompactFormat.Span span = current.header.get(CoreCompactFormat.Segment.STRINGS);
            MemorySegment strings = CoreCompactCursor.slice(member(current, "strings"), span.offset(), span.length());
            // Count only a valid selected range, including a failing UTF8 decode.
            CoreCompactCursor.slice(strings, offset, length);
            counters.stringBytesRead += length;
            return CoreCompactCursor.utf8(strings, offset, length);
        }
    }
    /** Facts use their own final-header pool, never the executable string member. */
    String metadataString(long offset, long length) throws Exception {
        synchronized (counters) {
            Mapped current = mapping();
            var span = current.header.metadataStrings();
            var strings = CoreCompactCursor.slice(member(current, "header"), span.offset(), span.length());
            CoreCompactCursor.slice(strings, offset, length);
            counters.headerBytesRead += length;
            return CoreCompactCursor.utf8(strings, offset, length);
        }
    }
    public <T> T debug(CoreCompactFormat.Segment segment, Decoder<T> decode) throws Throwable {
        Objects.requireNonNull(segment);
        Objects.requireNonNull(decode);
        synchronized (counters) {
            return debugAt(segment, 0, mapping().header.get(segment).length(), decode);
        }
    }
    public <T> T debugAt(CoreCompactFormat.Segment segment, long offset, long length, Decoder<T> decode) throws Throwable {
        Objects.requireNonNull(segment);
        Objects.requireNonNull(decode);
        synchronized (counters) {
            if (segment != CoreCompactFormat.Segment.NAMES && segment != CoreCompactFormat.Segment.FILENAMES
                    && segment != CoreCompactFormat.Segment.LINE_COLUMNS) {
                throw new IllegalArgumentException("Not a compact Core debug segment");
            }
            Mapped current = mapping();
            CoreCompactFormat.Span span = current.header.get(segment);
            MemorySegment selected = CoreCompactCursor.slice(member(current, segment.getMember()), span.offset(), span.length());
            CoreCompactCursor cursor = new CoreCompactCursor(CoreCompactCursor.slice(selected, offset, length));
            try { return decode.decode(cursor); }
            finally { counters.debugBytesRead += cursor.getPosition(); }
        }
    }
    @Override public void close() throws Exception {
        synchronized (counters) {
            if (!closed) {
                closed = true;
                Mapped retained = mapped;
                mapped = null;
                if (retained != null) retained.close();
            }
        }
    }
}
