// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc

import java.lang.foreign.MemorySegment

/** The fixed envelope is located from the two ends of one immutable mapping.
 * Reading it neither decodes a binding nor inspects an optional debug segment. */
internal object CoreCompactFormat {
    const val NAME = "thc-compact-core-v1"
    const val PREFIX_BYTES = 24L
    const val FOOTER_BYTES = 128L
    const val SYMBOL_BYTES = 24L
    private val MAGIC = byteArrayOf(84, 72, 67, 67, 77, 80, 0, 0)
    private val END_MAGIC = byteArrayOf(84, 72, 67, 67, 69, 78, 68, 49)

    enum class Segment { DATA, STRINGS, NAMES, FILENAMES, LINE_COLUMNS, SYMBOLS }
    data class Span(val offset: Long, val length: Long)
    data class Header(val facts: Span, val segments: List<Span>, val bindingCount: Long,
                      val summaries: Int, val debug: Int) {
        operator fun get(segment: Segment): Span = segments[segment.ordinal]
        val containsDelimitedControl: Boolean get() = summaries and 1 != 0
        val registrationObligations: Boolean get() = summaries and 2 != 0
        val mainAlias: Boolean get() = summaries and 4 != 0
        val packageScalarDeclarations: Boolean get() = summaries and 8 != 0
    }

    fun read(bytes: MemorySegment): Header {
        val size = bytes.byteSize()
        require(size >= PREFIX_BYTES + FOOTER_BYTES) { "Truncated compact Core container" }
        val prefix = CoreCompactCursor(bytes, 0, PREFIX_BYTES)
        require(prefix.bytes(8).contentEquals(MAGIC)) { "Invalid compact Core magic" }
        require(prefix.u16() == 1 && prefix.u16() == 0) { "Unsupported compact Core version" }
        require(prefix.u32() == 0L) { "Reserved compact Core header flags" }
        val factsLength = prefix.offset()
        require(factsLength <= size - PREFIX_BYTES - FOOTER_BYTES) { "Invalid compact Core header extent" }
        prefix.expectEnd()

        val footerAt = size - FOOTER_BYTES
        val footer = CoreCompactCursor(bytes, footerAt, size)
        require(footer.bytes(8).contentEquals(END_MAGIC)) { "Invalid compact Core footer magic" }
        var next = PREFIX_BYTES + factsLength
        val segments = Segment.entries.map {
            val span = Span(footer.offset(), footer.offset())
            require(span.offset == next && span.offset <= footerAt && span.length <= footerAt - span.offset) {
                "Invalid compact Core segment extent: $it"
            }
            next += span.length
            span
        }
        require(next == footerAt) { "Trailing compact Core segment bytes" }
        val count = footer.offset()
        val summaries = footer.u32()
        val debug = footer.u32()
        require(summaries and 15L.inv() == 0L && debug and 7L.inv() == 0L && footer.fixedBits() == 0L) {
            "Reserved compact Core footer flags"
        }
        footer.expectEnd()
        val symbols = segments[Segment.SYMBOLS.ordinal]
        require(symbols.length % SYMBOL_BYTES == 0L && count == symbols.length / SYMBOL_BYTES) {
            "Invalid compact Core symbol count"
        }
        for ((bit, segment) in listOf(Segment.NAMES, Segment.FILENAMES, Segment.LINE_COLUMNS).withIndex()) {
            require((debug and (1L shl bit) != 0L) == (segments[segment.ordinal].length != 0L)) {
                "Compact Core debug flag disagrees with segment: $segment"
            }
        }
        return Header(Span(PREFIX_BYTES, factsLength), segments, count, summaries.toInt(), debug.toInt())
    }
}
