// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc

import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction

/** Bounded, segment-relative reads. The owner keeps the mapping leased while
 * this cursor is in use; decoding never acquires a per-byte cache lock. */
internal class CoreCompactCursor(private val bytes: MemorySegment, start: Long = 0,
                                 private val end: Long = bytes.byteSize()) {
    var position: Long = start
        private set
    val remaining: Long get() = end - position

    init {
        require(start >= 0 && end >= start && end <= bytes.byteSize()) { "Invalid compact Core record extent" }
    }

    private fun take(length: Long): Long {
        require(length >= 0 && length <= remaining) { "Truncated compact Core record at $position" }
        val at = position
        position += length
        return at
    }

    fun byte(): Int = bytes.get(ValueLayout.JAVA_BYTE, take(1)).toInt() and 255

    fun boolean(): Boolean = when (val value = byte()) {
        0 -> false
        1 -> true
        else -> throw IllegalArgumentException("Invalid compact Core Boolean: $value")
    }

    /** All 64 unsigned bits, carried without narrowing in a JVM Long. */
    fun unsignedBits(): Long {
        var result = 0L
        for (index in 0..9) {
            val value = byte()
            val payload = value and 127
            require(index != 9 || payload <= 1 && value < 128) { "Overflowing compact Core ULEB128" }
            result = result or (payload.toLong() shl (index * 7))
            if (value < 128) {
                require(index == 0 || payload != 0) { "Noncanonical compact Core ULEB128" }
                return result
            }
        }
        error("Unreachable compact Core ULEB128 termination")
    }

    fun unsigned(): Long = unsignedBits().also {
        require(it >= 0) { "Compact Core value exceeds JVM address range" }
    }

    fun signed(): Long {
        val bits = unsignedBits()
        return (bits ushr 1) xor -(bits and 1)
    }

    /** Check encoded extent before allocating a count-sized JVM carrier. */
    fun count(minimumElementBytes: Int = 1): Int {
        require(minimumElementBytes > 0)
        val value = unsigned()
        require(value <= Int.MAX_VALUE && value <= remaining / minimumElementBytes) {
            "Compact Core collection exceeds its record extent"
        }
        return value.toInt()
    }

    fun u16(): Int = bytes.get(ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN), take(2)).toInt() and 65535

    fun u32(): Long = bytes.get(ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN), take(4)).toLong() and 0xffff_ffffL

    fun fixedBits(): Long = bytes.get(ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN), take(8))

    fun offset(): Long = fixedBits().also {
        require(it >= 0) { "Compact Core offset exceeds JVM address range" }
    }

    fun bytes(length: Int): ByteArray {
        val at = take(length.toLong())
        return bytes.asSlice(at, length.toLong()).toArray(ValueLayout.JAVA_BYTE)
    }

    fun expectEnd() { require(position == end) { "Trailing bytes in compact Core record" } }

    companion object {
        fun slice(bytes: MemorySegment, start: Long, length: Long): MemorySegment {
            require(start >= 0 && length >= 0 && start <= bytes.byteSize() && length <= bytes.byteSize() - start) {
                "Invalid compact Core segment span"
            }
            return bytes.asSlice(start, length)
        }

        /** Decode only the requested span; cold strings remain mapped bytes. */
        fun utf8(bytes: MemorySegment, start: Long, length: Long): String {
            val selected = slice(bytes, start, length)
            require(length <= Int.MAX_VALUE) { "Compact Core string exceeds JVM carrier size" }
            return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(selected.asByteBuffer()).toString()
        }
    }
}
