// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/** Checked ordinary ZIP/ZIP64 directory over one immutable mapped snapshot.
 * Opening reads framing only, without payload decoding or CRC scans. Acquired
 * handles expose member-relative bounds and pin their exact mapping or slab. */
public final class CoreCbdArchive implements AutoCloseable {
    public static final Set<String> NAMES = Collections.unmodifiableSet(new LinkedHashSet<>(
            List.of("data", "strings", "names", "filenames", "line-columns", "symbols", "header")));
    private CoreFileMappings.Lease mapping;
    private final CoreCbdSlabs slabs;
    private final Directory directory;
    private final Map<String, Member> members;

    public static CoreCbdArchive open(CoreFileMappings.Lease mapping) {
        return open(mapping, CoreCbdSlabs.shared);
    }
    public static CoreCbdArchive open(CoreFileMappings.Lease mapping, CoreCbdSlabs slabs) {
        Objects.requireNonNull(mapping);
        Objects.requireNonNull(slabs);
        try { return new CoreCbdArchive(mapping, slabs); }
        catch (Throwable failure) {
            try { mapping.close(); } catch (Throwable cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }
    private CoreCbdArchive(CoreFileMappings.Lease mapping, CoreCbdSlabs slabs) {
        this.mapping = mapping;
        this.slabs = slabs;
        directory = new Directory(mapping.getBytes());
        members = directory.read();
    }
    public record Member(String name, int method, long start, long compressed, long length, long crc) {
        public Member { Objects.requireNonNull(name); }
    }
    public static final class Handle implements AutoCloseable {
        private AutoCloseable owner;
        private final MemorySegment view;
        private final Member member;
        private final boolean inflated, cacheHit;
        private Handle(AutoCloseable owner, MemorySegment view, Member member, boolean inflated, boolean cacheHit) {
            this.owner = owner;
            this.view = view;
            this.member = member;
            this.inflated = inflated;
            this.cacheHit = cacheHit;
        }
        public Member getMember() { return member; }
        public boolean getInflated() { return inflated; }
        public boolean getCacheHit() { return cacheHit; }
        public synchronized MemorySegment getBytes() {
            if (owner == null) throw new IllegalStateException("CBD member handle is closed");
            return view;
        }
        @Override public synchronized void close() throws Exception {
            if (owner == null) return;
            AutoCloseable retained = owner;
            owner = null;
            retained.close();
        }
    }
    public long getDirectoryBytesRead() { return directory.reads; }
    public synchronized Member member(String name) {
        Objects.requireNonNull(name);
        if (mapping == null) throw new IllegalStateException("CBD archive is closed");
        Member member = members.get(name);
        require(member != null, "Unknown CBD member: " + name);
        return member;
    }
    public Handle read(String name) {
        Objects.requireNonNull(name);
        final Member member;
        final CoreFileMappings.Lease retained;
        synchronized (this) {
            member = member(name);
            retained = mapping.retain();
        }
        if (member.method == 0) {
            try { return new Handle(retained, retained.getBytes().asSlice(member.start, member.length), member, false, false); }
            catch (Throwable failure) { retained.close(); throw failure; }
        }
        try {
            CoreCbdSlabs.Lease slab = slabs.acquire(new CoreCbdSlabs.Key(retained.getSnapshot(), name),
                    () -> inflate(retained.getBytes().asSlice(member.start, member.compressed), member));
            return new Handle(slab, slab.getBytes(), member, slab.getInflated(), !slab.getInflated());
        } finally { retained.close(); }
    }
    @Override public synchronized void close() {
        CoreFileMappings.Lease retained = mapping;
        mapping = null;
        if (retained != null) retained.close();
    }
    private static CoreCbdSlabs.Slab inflate(MemorySegment source, Member member) throws DataFormatException {
        Arena arena = Arena.ofShared();
        try {
            MemorySegment output = arena.allocate(member.length);
            CRC32 crc = new CRC32();
            try (Inflater inflater = new Inflater(true)) {
                long inputAt = 0, outputAt = 0;
                ByteBuffer overflow = ByteBuffer.allocate(1);
                while (!inflater.finished()) {
                    if (inflater.needsInput() && inputAt < source.byteSize()) {
                        long amount = Math.min(65536L, source.byteSize() - inputAt);
                        inflater.setInput(source.asSlice(inputAt, amount).asByteBuffer());
                        inputAt += amount;
                    }
                    ByteBuffer target = outputAt < member.length
                            ? output.asSlice(outputAt, Math.min(65536L, member.length - outputAt)).asByteBuffer()
                            : overflow.clear();
                    int amount = inflater.inflate(target);
                    require(amount <= member.length - outputAt, "CBD inflated member exceeds declared length: " + member.name);
                    if (amount != 0) {
                        target.flip();
                        crc.update(target);
                        outputAt += amount;
                    } else if (!inflater.finished()) {
                        require(!inflater.needsDictionary(), "CBD member requires a Deflate dictionary");
                        require(inflater.needsInput() && inputAt < source.byteSize(),
                                "Truncated or stalled CBD Deflate stream: " + member.name);
                    }
                }
                require(outputAt == member.length && inflater.getBytesRead() == member.compressed,
                        "CBD Deflate length mismatch: " + member.name);
                require(crc.getValue() == member.crc, "CBD member CRC mismatch: " + member.name);
            }
            return new CoreCbdSlabs.Slab(arena, output.asReadOnly());
        } catch (Throwable failure) {
            try { arena.close(); } catch (Throwable cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
    private record Extent(long start, long end) {}
    private static final class Directory {
        private final MemorySegment bytes;
        private final long size;
        long reads;
        Directory(MemorySegment bytes) { this.bytes = bytes; size = bytes.byteSize(); }
        private long extent(long at, long length) { return extent(at, length, size); }
        private long extent(long at, long length, long end) {
            require(at >= 0 && length >= 0 && at <= end && length <= end - at, "Invalid CBD ZIP extent");
            return at + length;
        }
        private long number(long at, int width) {
            extent(at, width);
            reads += width;
            long value = 0;
            for (int i = 0; i < width; i++) value |= (bytes.get(ValueLayout.JAVA_BYTE, at + i) & 255L) << (8 * i);
            return value;
        }
        private int u16(long at) { return (int) number(at, 2); }
        private long u32(long at) { return number(at, 4); }
        private long u64(long at) {
            long value = number(at, 8);
            require(value >= 0, "CBD ZIP64 value exceeds supported address range");
            return value;
        }
        private String name(long at, int length) {
            require(length >= 1 && length <= 12, "Unknown CBD ZIP member");
            extent(at, length);
            reads += length;
            String name = new String(bytes.asSlice(at, length).toArray(ValueLayout.JAVA_BYTE), StandardCharsets.US_ASCII);
            require(NAMES.contains(name), "Unknown CBD ZIP member: " + name);
            return name;
        }
        private Extent zip64(long at, int length) {
            long end = extent(at, length);
            long cursor = at;
            Extent result = null;
            while (cursor < end) {
                extent(cursor, 4, end);
                int tag = u16(cursor), count = u16(cursor + 2);
                long next = extent(cursor + 4, count, end);
                if (tag == 1) {
                    require(result == null, "Duplicate CBD ZIP64 extra field");
                    result = new Extent(cursor + 4, next);
                }
                cursor = next;
            }
            return result;
        }
        private final class Extra {
            private final Extent extent;
            private long cursor;
            Extra(Extent extent) { this.extent = extent; cursor = extent == null ? 0 : extent.start; }
            long longValue() {
                require(extent != null, "Missing CBD ZIP64 extra field");
                extent(cursor, 8, extent.end);
                long value = u64(cursor);
                cursor += 8;
                return value;
            }
            long disk() {
                require(extent != null, "Required value was null.");
                extent(cursor, 4, extent.end);
                return u32(cursor);
            }
        }
        private Long descriptor(long at, boolean wide, long directoryAt, long crc, long compressed, long length) {
            int width = wide ? 8 : 4;
            long needed = 4L + width * 2;
            if (at > directoryAt || needed > directoryAt - at) return null;
            if (u32(at) != crc) return null;
            long a = wide ? u64(at + 4) : u32(at + 4);
            long b = wide ? u64(at + 12) : u32(at + 8);
            return a == compressed && b == length ? at + needed : null;
        }
        Map<String, Member> read() {
            require(size >= 22, "Truncated CBD ZIP directory");
            long eocd = size - 22;
            long minimum = Math.max(0, size - 65557);
            while (eocd >= minimum) {
                if (u32(eocd) == 0x06054b50L && u16(eocd + 20) == size - eocd - 22) break;
                eocd--;
            }
            require(eocd >= minimum, "Missing CBD ZIP directory");
            require(u16(eocd + 4) == 0 && u16(eocd + 6) == 0, "Multi-disk CBD ZIP is unsupported");
            int diskCount = u16(eocd + 8), totalCount = u16(eocd + 10);
            long size32 = u32(eocd + 12), offset32 = u32(eocd + 16);
            long count = totalCount, directorySize = size32, directoryAt = offset32, directoryEnd = eocd;
            if (eocd >= 20 && u32(eocd - 20) == 0x07064b50L) {
                long locator = eocd - 20;
                require(u32(locator + 4) == 0 && u32(locator + 16) == 1, "Multi-disk CBD ZIP64 is unsupported");
                long record = u64(locator + 8);
                extent(record, 56, locator);
                require(u32(record) == 0x06064b50L, "Invalid CBD ZIP64 directory");
                long recordSize = u64(record + 4);
                require(recordSize >= 44 && extent(record + 12, recordSize, locator) == locator,
                        "Invalid CBD ZIP64 directory extent");
                require(u16(record + 14) == 45 && u32(record + 16) == 0 && u32(record + 20) == 0,
                        "Unsupported CBD ZIP64 directory");
                count = u64(record + 32);
                require(u64(record + 24) == count, "Multi-disk CBD ZIP64 member count");
                directorySize = u64(record + 40);
                directoryAt = u64(record + 48);
                directoryEnd = record;
                require((diskCount == 65535 || diskCount == count) && (totalCount == 65535 || totalCount == count)
                        && (size32 == 0xffffffffL || size32 == directorySize)
                        && (offset32 == 0xffffffffL || offset32 == directoryAt), "CBD ZIP/ZIP64 directory disagreement");
            } else require(diskCount == totalCount && totalCount != 65535 && size32 != 0xffffffffL && offset32 != 0xffffffffL,
                    "Missing CBD ZIP64 directory");
            require(count == NAMES.size() && extent(directoryAt, directorySize, directoryEnd) == directoryEnd,
                    "Invalid CBD ZIP directory count or extent");
            var result = new LinkedHashMap<String, Member>();
            var occupied = new ArrayList<Extent>();
            long cursor = directoryAt, headerStart = -1;
            for (int index = 0; index < NAMES.size(); index++) {
                extent(cursor, 46, directoryEnd);
                require(u32(cursor) == 0x02014b50L, "Invalid CBD ZIP central entry");
                int version = u16(cursor + 6), flags = u16(cursor + 8), method = u16(cursor + 10);
                require(version >= 10 && version <= 45 && (flags & ~0x80e) == 0 && (method == 0 || method == 8)
                        && (method == 8 || (flags & 6) == 0), "Unsupported CBD ZIP method, flags or version");
                long crc = u32(cursor + 16), compressed = u32(cursor + 20), length = u32(cursor + 24);
                int nameLength = u16(cursor + 28), extraLength = u16(cursor + 30), commentLength = u16(cursor + 32);
                long disk = u16(cursor + 34), local = u32(cursor + 42);
                long extraAt = extent(cursor + 46, nameLength, directoryEnd);
                long commentAt = extent(extraAt, extraLength, directoryEnd);
                long next = extent(commentAt, commentLength, directoryEnd);
                String name = name(cursor + 46, nameLength);
                require(!result.containsKey(name), "Duplicate CBD ZIP member: " + name);
                if (name.equals("header")) {
                    require(index == NAMES.size() - 1, "CBD header must be the final directory member");
                }
                Extra extra = new Extra(zip64(extraAt, extraLength));
                boolean central64 = length == 0xffffffffL || compressed == 0xffffffffL || local == 0xffffffffL || disk == 65535;
                require(!central64 || version == 45, "Invalid CBD ZIP64 central version");
                if (length == 0xffffffffL) length = extra.longValue();
                if (compressed == 0xffffffffL) compressed = extra.longValue();
                if (local == 0xffffffffL) local = extra.longValue();
                if (disk == 65535) disk = extra.disk();
                if (name.equals("header")) headerStart = local;
                require(disk == 0 && (method != 0 || compressed == length), "Invalid CBD ZIP member sizes or disk");
                extent(local, 30, directoryAt);
                int localVersion = u16(local + 4);
                // Offset-only central ZIP64 may still have a small version2 local header.
                require(u32(local) == 0x04034b50L && localVersion >= 10 && localVersion <= 45
                        && u16(local + 6) == flags && u16(local + 8) == method, "CBD ZIP local/central header disagreement");
                int localNameLength = u16(local + 26), localExtraLength = u16(local + 28);
                long localExtraAt = extent(local + 30, localNameLength, directoryAt);
                long payload = extent(localExtraAt, localExtraLength, directoryAt);
                require(name(local + 30, localNameLength).equals(name), "CBD ZIP local/central name disagreement");
                long localCompressed = u32(local + 18), localLength = u32(local + 22);
                boolean local64 = localCompressed == 0xffffffffL || localLength == 0xffffffffL;
                require(!local64 || localVersion == 45, "Invalid CBD ZIP64 local version");
                Extent localExtra = zip64(localExtraAt, localExtraLength);
                if (local64) {
                    require(localExtra != null, "Missing local CBD ZIP64 sizes");
                    extent(localExtra.start, 16, localExtra.end);
                    long actualLength = u64(localExtra.start), actualCompressed = u64(localExtra.start + 8);
                    require((localLength == 0xffffffffL || localLength == actualLength)
                            && (localCompressed == 0xffffffffL || localCompressed == actualCompressed), "CBD ZIP64 local size disagreement");
                    localLength = actualLength;
                    localCompressed = actualCompressed;
                }
                long localCrc = u32(local + 14);
                long end = extent(payload, compressed, directoryAt);
                if ((flags & 8) == 0) {
                    require(localCrc == crc && localLength == length && localCompressed == compressed,
                            "CBD ZIP local/central size or CRC disagreement");
                } else {
                    require((localCrc == 0 || localCrc == crc) && (localLength == 0 || localLength == length)
                            && (localCompressed == 0 || localCompressed == compressed), "CBD ZIP descriptor header disagreement");
                    boolean wide = local64 || length >= 0xffffffffL || compressed >= 0xffffffffL;
                    Long descriptorEnd = end <= directoryAt - 4 && u32(end) == 0x08074b50L
                            ? descriptor(end + 4, wide, directoryAt, crc, compressed, length) : null;
                    if (descriptorEnd == null) descriptorEnd = descriptor(end, wide, directoryAt, crc, compressed, length);
                    if (descriptorEnd == null) throw new IllegalArgumentException("Invalid CBD ZIP data descriptor");
                    end = descriptorEnd;
                }
                occupied.add(new Extent(local, end));
                result.put(name, new Member(name, method, payload, compressed, length, crc));
                cursor = next;
            }
            require(cursor == directoryEnd, "Trailing CBD ZIP directory bytes");
            long end = 0;
            occupied.sort(Comparator.comparingLong(Extent::start));
            require(occupied.getLast().start == headerStart, "CBD header must be the final physical member");
            for (Extent span : occupied) {
                require(span.start >= end, "Overlapping CBD ZIP members");
                end = span.end;
            }
            return result;
        }
    }
}
