// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc

import java.lang.foreign.MemorySegment

/** CBD facts header and uncompressed, member-relative payload extents. */
internal object CoreCompactFormat {
    const val NAME = "thc-cbd-v1"
    const val HEADER_BYTES = 32L
    const val SYMBOL_BYTES = 24L
    private val MAGIC = "THCCBD1\u0000".toByteArray(Charsets.US_ASCII)
    enum class Segment(val member: String) {
        DATA("data"), STRINGS("strings"), NAMES("names"), FILENAMES("filenames"),
        LINE_COLUMNS("line-columns"), SYMBOLS("symbols")
    }
    data class Span(val offset: Long, val length: Long)
    data class Header(val facts: Span, val segments: List<Span>, val bindingCount: Long,
                      val summaries: Int, val debug: Int) {
        operator fun get(segment: Segment): Span = segments[segment.ordinal]
        val containsDelimitedControl: Boolean get() = summaries and 1 != 0
        val registrationObligations: Boolean get() = summaries and 2 != 0
        val mainAlias: Boolean get() = summaries and 4 != 0
        val packageScalarDeclarations: Boolean get() = summaries and 8 != 0
    }
    fun read(bytes: MemorySegment, lengths: List<Long>): Header {
        require(bytes.byteSize() >= HEADER_BYTES) { "Truncated CBD header" }
        require(lengths.size == Segment.entries.size && lengths.all { it >= 0 }) { "Invalid CBD member lengths" }
        val cursor = CoreCompactCursor(bytes, 0, HEADER_BYTES)
        require(cursor.bytes(8).contentEquals(MAGIC)) { "Invalid CBD header magic" }
        require(cursor.u16() == 1 && cursor.u16() == 0) { "Unsupported CBD version" }
        val summaries = cursor.u32()
        val count = cursor.offset()
        val debug = cursor.u32()
        require(summaries and 15L.inv() == 0L && debug and 7L.inv() == 0L && cursor.u32() == 0L) {
            "Reserved CBD header flags"
        }
        cursor.expectEnd()
        val symbols = lengths[Segment.SYMBOLS.ordinal]
        require(symbols % SYMBOL_BYTES == 0L && count == symbols / SYMBOL_BYTES) { "Invalid CBD symbol count" }
        for ((bit, segment) in listOf(Segment.NAMES, Segment.FILENAMES, Segment.LINE_COLUMNS).withIndex()) {
            require((debug and (1L shl bit) != 0L) == (lengths[segment.ordinal] != 0L)) {
                "CBD debug flag disagrees with member: $segment"
            }
        }
        return Header(Span(HEADER_BYTES, bytes.byteSize() - HEADER_BYTES), lengths.map { Span(0, it) },
            count, summaries.toInt(), debug.toInt())
    }
}
