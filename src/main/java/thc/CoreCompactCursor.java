// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Bounded, segment-relative reads. The owner retains a mapping/member lease
 * while the cursor is in use; decoding takes no per-byte cache lock. */
public final class CoreCompactCursor {
    private final MemorySegment bytes;
    private final long end;
    private long position;

    public CoreCompactCursor(MemorySegment bytes) { this(bytes, 0, bytes.byteSize()); }
    public CoreCompactCursor(MemorySegment bytes, long start) { this(bytes, start, bytes.byteSize()); }
    public CoreCompactCursor(MemorySegment bytes, long start, long end) {
        this.bytes = Objects.requireNonNull(bytes);
        if (start < 0 || end < start || end > bytes.byteSize()) {
            throw new IllegalArgumentException("Invalid compact Core record extent");
        }
        position = start;
        this.end = end;
    }
    public long getPosition() { return position; }
    public long getRemaining() { return end - position; }
    private long take(long length) {
        if (length < 0 || length > getRemaining()) {
            throw new IllegalArgumentException("Truncated compact Core record at " + position);
        }
        long at = position;
        position += length;
        return at;
    }
    /** Borrow a bounded child slice while advancing this cursor past it. */
    public CoreCompactCursor bounded(long length) {
        long start = take(length);
        return new CoreCompactCursor(bytes, start, start + length);
    }
    public int readByte() { return bytes.get(ValueLayout.JAVA_BYTE, take(1)) & 255; }
    public boolean readBoolean() {
        int value = readByte();
        if (value == 0) return false;
        if (value == 1) return true;
        throw new IllegalArgumentException("Invalid compact Core Boolean: " + value);
    }
    /** All 64 unsigned bits, without narrowing, in a JVM long. */
    public long unsignedBits() {
        long result = 0;
        for (int index = 0; index <= 9; index++) {
            int value = readByte(), payload = value & 127;
            if (index == 9 && (payload > 1 || value >= 128)) {
                throw new IllegalArgumentException("Overflowing compact Core ULEB128");
            }
            result |= (long) payload << (index * 7);
            if (value < 128) {
                if (index != 0 && payload == 0) throw new IllegalArgumentException("Noncanonical compact Core ULEB128");
                return result;
            }
        }
        throw new IllegalStateException("Unreachable compact Core ULEB128 termination");
    }
    public long unsigned() {
        long result = unsignedBits();
        if (result < 0) throw new IllegalArgumentException("Compact Core value exceeds JVM address range");
        return result;
    }
    public long signed() {
        long bits = unsignedBits();
        return (bits >>> 1) ^ -(bits & 1);
    }
    public int count() { return count(1); }
    /** Check encoded extent before allocating a count-sized JVM carrier. */
    public int count(int minimumElementBytes) {
        if (minimumElementBytes <= 0) throw new IllegalArgumentException("Failed requirement.");
        long value = unsigned();
        if (value > Integer.MAX_VALUE || value > getRemaining() / minimumElementBytes) {
            throw new IllegalArgumentException("Compact Core collection exceeds its record extent");
        }
        return (int) value;
    }
    public int u16() {
        return bytes.get(ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN), take(2)) & 65535;
    }
    public long u32() {
        return bytes.get(ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN), take(4)) & 0xffffffffL;
    }
    public long fixedBits() {
        return bytes.get(ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN), take(8));
    }
    public long offset() {
        long result = fixedBits();
        if (result < 0) throw new IllegalArgumentException("Compact Core offset exceeds JVM address range");
        return result;
    }
    public byte[] bytes(int length) {
        long at = take(length);
        return bytes.asSlice(at, length).toArray(ValueLayout.JAVA_BYTE);
    }
    public String text(int length) throws CharacterCodingException { return utf8(bytes, take(length), length); }
    public void expectEnd() {
        if (position != end) throw new IllegalArgumentException("Trailing bytes in compact Core record");
    }
    public static MemorySegment slice(MemorySegment bytes, long start, long length) {
        Objects.requireNonNull(bytes);
        if (start < 0 || length < 0 || start > bytes.byteSize() || length > bytes.byteSize() - start) {
            throw new IllegalArgumentException("Invalid compact Core segment span");
        }
        return bytes.asSlice(start, length);
    }
    /** Decode only this span; cold strings remain mapped bytes. */
    public static String utf8(MemorySegment bytes, long start, long length) throws CharacterCodingException {
        MemorySegment selected = slice(bytes, start, length);
        if (length > Integer.MAX_VALUE) throw new IllegalArgumentException("Compact Core string exceeds JVM carrier size");
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(selected.asByteBuffer()).toString();
    }
}
