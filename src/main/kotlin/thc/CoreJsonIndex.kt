// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * A structural index of an owned, immutable UTF-8 snapshot, not a parsed JSON tree.
 *
 * Only containers contribute balanced-parenthesis pairs. One monotone Elias--Fano
 * sequence maps every opening AND closing event to its byte position. Containers
 * can be skipped without rescanning their contents. Keys/scalars require local
 * source scanning, but no conversion of unused values or strings. No index entry
 * is an object, token, or pair of full-width offsets.
 *
 * The topology follows the standard cursor described in rust-works/succinctly
 * (MIT), revision 6ee3210413d1f180fd6a93ab30c5bc6aaad29b78, json/standard.rs and
 * json/light.rs. Sparse positions are the approach used by Ottaviano's semi_index
 * (Apache-2.0), revision f00811737917707896cce8fb40be5d07ea42956f. This independent
 * Kotlin implementation indexes only container boundaries; it is not either project's wire
 * format and makes no claim to their space or throughput results.
 * https://github.com/rust-works/succinctly / https://github.com/ot/semi_index
 *
 * Building checks container grammar, matching delimiters and quoted boundaries.
 * It does NOT decode strings/numbers, check nested duplicate keys, or validate
 * Core semantics. [validateDocument] explicitly invokes the existing full JSON
 * parser. [Span.decode] validates only its selected value and memoizes its exact
 * result or exception. Closing invalidates cursors and drops source/index/cache
 * ownership; values already returned to a caller remain ordinary caller-owned values.
 */
internal class CoreJsonIndex private constructor(private var storage: Storage?) : AutoCloseable {
    enum class Kind { OBJECT, ARRAY, STRING, ATOM }
    data class Statistics(val sourceByteSize: Int, val indexByteSize: Long,
        val structuralBytesScanned: Long, val decodedSpanCount: Long, val decodedByteCount: Long,
        val navigationByteReads: Long, val balancedParenthesisBitsExamined: Long)
    data class Member(val name: String, val value: Span)

    private val decoded = HashMap<Int, Result<Any?>>()
    private var decodeCount = 0L
    private var decodeBytes = 0L
    private var navigationBytes = 0L
    private var bpExamined = 0L
    private fun live(): Storage = checkNotNull(storage) { "JSON index is closed" }

    val root: Span get() = synchronized(this) { val s = live(); Span(this, s.rootStart, s.rootEnd) }
    fun statistics(): Statistics = synchronized(this) {
        val s = live()
        Statistics(s.bytes.size, s.indexBytes, s.scanBytes, decodeCount, decodeBytes, navigationBytes, bpExamined)
    }
    /** Hashes the exact original bytes, never normalized or reserialized JSON. */
    fun sha256(): String = synchronized(this) { live().hash.joinToString("") { "%02x".format(it) } }
    fun validateDocument(): Any? = root.decode()
    override fun close() = synchronized(this) { decoded.clear(); storage = null }

    class Span internal constructor(private val owner: CoreJsonIndex, internal val begin: Int, internal val end: Int) {
        val kind: Kind get() = owner.kind(begin)
        val start: Int get() = synchronized(owner) { owner.live(); begin }
        val endExclusive: Int get() = synchronized(owner) { owner.live(); end }
        fun decode(): Any? = owner.decode(this)
        fun stringEquals(expected: String): Boolean = owner.stringEquals(this, expected)
        fun elements(): List<Span> = owner.elements(this)
        /** Object names are decoded on demand, never their values; duplicates are rejected here. */
        fun members(): List<Member> = owner.members(this)
        /** Projection checks duplicate occurrences of this name, without decoding other names/values. */
        fun member(name: String): Span? = owner.member(this, name)
        /** Explicit inspection copy; callers cannot mutate the pinned source. */
        fun bytes(): ByteArray = owner.bytes(this)
    }

    private fun read(s: Storage, at: Int): Int { navigationBytes++; return s.bytes[at].toInt() and 255 }
    private fun kind(start: Int): Kind = synchronized(this) {
        when (read(live(), start)) {
            123 -> Kind.OBJECT
            91 -> Kind.ARRAY
            34 -> Kind.STRING
            else -> Kind.ATOM
        }
    }
    private fun bytes(span: Span): ByteArray = synchronized(this) {
        live().bytes.copyOfRange(span.begin, span.end)
    }
    private fun decode(span: Span): Any? = synchronized(this) {
        val s = live()
        decoded[span.begin]?.let { return@synchronized it.getOrThrow() }
        decodeCount++; decodeBytes += span.end - span.begin
        val result = try {
            val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(s.bytes, span.begin, span.end - span.begin)).toString()
            Result.success(Json.parse(text))
        } catch (failure: Exception) { Result.failure(failure) }
        decoded[span.begin] = result
        result.getOrThrow()
    }

    /** Source is structurally validated; container positions are exact, scalar boundaries are scanned. */
    private fun endAt(s: Storage, start: Int, limit: Int): Int = when (read(s, start)) {
        123, 91 -> {
            val event = s.positions.lowerBound(start)
            check(event < s.positions.count && s.positions.select(event) == start && s.bp.bits[event])
            s.positions.select(s.bp.close(event) { bpExamined++ }) + 1
        }
        34 -> {
            var at = start + 1
            while (at < limit) {
                when (read(s, at++)) {
                    34 -> break
                    92 -> { check(at < limit); read(s, at++ ) }
                }
            }
            at
        }
        else -> {
            var at = start + 1
            while (at < limit && !delimiter(read(s, at))) at++
            at
        }
    }

    private fun children(span: Span, expected: Kind): List<Span> = synchronized(this) {
        require(kind(span.begin) == expected) { "Expected JSON $expected" }
        object : AbstractList<Span>() {
            override val size: Int get() {
                var count = 0
                for (ignored in this) count++
                return count
            }
            override fun get(index: Int): Span {
                if (index < 0) throw IndexOutOfBoundsException("JSON child $index")
                val cursor = iterator()
                repeat(index) { if (!cursor.hasNext()) throw IndexOutOfBoundsException("JSON child $index"); cursor.next() }
                if (!cursor.hasNext()) throw IndexOutOfBoundsException("JSON child $index")
                return cursor.next()
            }
            // Default AbstractList iteration repeatedly invokes get(index); keep wide arrays linear.
            override fun iterator(): Iterator<Span> = object : Iterator<Span> {
                var next = span.begin + 1
                private fun advance(s: Storage) {
                    while (next < span.end - 1) {
                        val c = read(s, next)
                        if (!whitespace(c) && c != 44 && c != 58) break
                        next++
                    }
                }
                override fun hasNext(): Boolean = synchronized(this@CoreJsonIndex) {
                    advance(live()); next < span.end - 1
                }
                override fun next(): Span = synchronized(this@CoreJsonIndex) {
                    val s = live(); advance(s)
                    if (next >= span.end - 1) throw NoSuchElementException()
                    val end = endAt(s, next, span.end - 1)
                    val result = Span(this@CoreJsonIndex, next, end)
                    next = end
                    result
                }
            }
        }
    }
    private fun elements(span: Span): List<Span> = children(span, Kind.ARRAY)
    private fun members(span: Span): List<Member> = synchronized(this) {
        val children = children(span, Kind.OBJECT).iterator()
        val seen = HashSet<String>(); val result = ArrayList<Member>()
        while (children.hasNext()) {
            val key = children.next().decode() as String
            require(seen.add(key)) { "Duplicate JSON key: $key" }
            result.add(Member(key, children.next()))
        }
        result
    }
    private fun member(span: Span, name: String): Span? = synchronized(this) {
        val children = children(span, Kind.OBJECT).iterator()
        var found: Span? = null
        while (children.hasNext()) {
            val key = children.next(); val value = children.next()
            if (key.stringEquals(name)) {
                require(found == null) { "Duplicate JSON key: $name" }
                found = value
            }
        }
        found
    }

    /** Compare UTF-16 code units without allocating or interning the candidate string. */
    private fun stringEquals(span: Span, expected: String): Boolean = synchronized(this) {
        require(kind(span.begin) == Kind.STRING) { "Expected JSON string" }
        val s = live(); val start = span.begin; val end = span.end
        var at = start + 1; var matched = 0
        fun read(): Int { require(at < end - 1) { "Incomplete JSON string" }; navigationBytes++; return s.bytes[at++].toInt() and 255 }
        fun matches(c: Char): Boolean = matched < expected.length && expected[matched++] == c
        while (at < end - 1) {
            var c = read()
            if (c == 92) {
                c = when (val escape = read()) {
                    34, 92, 47 -> escape
                    98 -> 8; 102 -> 12; 110 -> 10; 114 -> 13; 116 -> 9
                    117 -> {
                        var value = 0
                        repeat(4) {
                            val digit = read().toChar().digitToIntOrNull(16)
                            require(digit != null) { "Invalid JSON Unicode escape" }
                            value = (value shl 4) or digit
                        }
                        value
                    }
                    else -> error("Unknown JSON escape")
                }
            } else if (c >= 128) {
                val count = when (c) { in 194..223 -> 1; in 224..239 -> 2; in 240..244 -> 3; else -> error("Invalid UTF-8") }
                var point = c and (127 ushr count)
                repeat(count) { val b = read(); require(b in 128..191) { "Invalid UTF-8" }; point = (point shl 6) or (b and 63) }
                require(point >= (if (count == 1) 128 else if (count == 2) 2048 else 65536) &&
                    point <= 0x10ffff && point !in 0xd800..0xdfff) { "Invalid UTF-8" }
                c = point
            }
            if (c > 0xffff) {
                if (!matches(Character.highSurrogate(c)) || !matches(Character.lowSurrogate(c))) return@synchronized false
            } else if (!matches(c.toChar())) return@synchronized false
        }
        matched == expected.length
    }

    private class Storage(val bytes: ByteArray, val positions: Positions,
                          val bp: Parentheses, val scanBytes: Long) {
        val hash: ByteArray by lazy { MessageDigest.getInstance("SHA-256").digest(bytes) }
        val indexBytes: Long get() = positions.byteSize + bp.byteSize
        val rootStart = bytes.indexOfFirst { !whitespace(it.toInt() and 255) }
        val rootEnd = bytes.indexOfLast { !whitespace(it.toInt() and 255) } + 1
    }
    companion object {
        /** Scalar reference builder for tests/parity; not the production sidecar producer. */
        fun fromBytes(bytes: ByteArray): CoreJsonIndex = build(bytes.copyOf())
        /** Scalar reference read: later replacement/deletion cannot retarget the snapshot. */
        fun read(path: Path): CoreJsonIndex = build(Files.readAllBytes(path))
        private fun build(bytes: ByteArray): CoreJsonIndex {
            var count = 0
            scan(bytes, { count = Math.incrementExact(count) }, { count = Math.incrementExact(count) })
            val positions = Positions.Builder(bytes.size, count)
            val bp = LongArray(words(count)); var bit = 0
            scan(bytes, { positions.add(it); bp[bit ushr 6] = bp[bit ushr 6] or (1L shl (bit and 63)); bit++ },
                { positions.add(it); bit++ })
            check(bit == count)
            return CoreJsonIndex(Storage(bytes, positions.finish(), Parentheses(Bits(bp, bit)), bytes.size.toLong() * 2))
        }

        /** Two passes allocate no token/value objects; scalar contents are never converted. */
        private fun scan(bytes: ByteArray, open: (Int) -> Unit, close: (Int) -> Unit) {
            var at = 0; var depth = 0; var states = ByteArray(32); var root = false
            fun push(state: Int) {
                if (depth == states.size) states = states.copyOf(Math.multiplyExact(states.size, 2))
                states[depth++] = state.toByte()
            }
            fun quoted() {
                at++
                while (at < bytes.size) {
                    val c = bytes[at++].toInt() and 255
                    if (c == 34) return
                    require(c >= 32) { "Control character in JSON string at ${at - 1}" }
                    if (c == 92) { require(at < bytes.size) { "Incomplete JSON escape" }; at++ }
                }
                error("Unterminated JSON string")
            }
            fun value() {
                require(at < bytes.size) { "Missing JSON value" }
                when (val c = bytes[at].toInt() and 255) {
                    123 -> { open(at++); push(0) }
                    91 -> { open(at++); push(5) }
                    34 -> quoted()
                    else -> {
                        require(c == 45 || c in 48..57 || c == 116 || c == 102 || c == 110) { "Invalid JSON value boundary at $at" }
                        at++
                        while (at < bytes.size && !delimiter(bytes[at].toInt() and 255)) at++
                    }
                }
            }
            while (true) {
                while (at < bytes.size && whitespace(bytes[at].toInt() and 255)) at++
                if (depth == 0) {
                    if (root) { require(at == bytes.size) { "Trailing JSON at $at" }; return }
                    root = true; value(); continue
                }
                require(at < bytes.size) { "Unclosed JSON container" }
                val c = bytes[at].toInt() and 255
                when (states[depth - 1].toInt()) {
                    0, 1 -> if (c == 125 && states[depth - 1].toInt() == 0) { depth--; close(at++) }
                        else { require(c == 34) { "Expected JSON object key at $at" }; states[depth - 1] = 2; quoted() }
                    2 -> { require(c == 58) { "Expected JSON colon at $at" }; at++; states[depth - 1] = 3 }
                    3 -> { states[depth - 1] = 4; value() }
                    4 -> when (c) {
                        125 -> { depth--; close(at++) }
                        44 -> { at++; states[depth - 1] = 1 }
                        else -> error("Expected JSON comma or object end at $at")
                    }
                    5, 6 -> if (c == 93 && states[depth - 1].toInt() == 5) { depth--; close(at++) }
                        else { states[depth - 1] = 7; value() }
                    7 -> when (c) {
                        93 -> { depth--; close(at++) }
                        44 -> { at++; states[depth - 1] = 6 }
                        else -> error("Expected JSON comma or array end at $at")
                    }
                }
            }
        }
        private fun whitespace(c: Int) = c == 32 || c == 9 || c == 10 || c == 13
        private fun delimiter(c: Int) = whitespace(c) || c == 123 || c == 125 || c == 91 || c == 93 || c == 44 || c == 58 || c == 34
    }
}

private fun words(bits: Int): Int = ((bits.toLong() + 63) / 64).toInt()

/** Select bisects rank at 512-bit boundaries, then scans at most eight words. */
private class Bits(val data: LongArray, val size: Int) {
    private val directory = JsonRankDirectory.build(data, size.toLong())
    val byteSize: Long get() = data.size.toLong() * 8 + directory.directoryBytes
    val ones: Int get() = rank(size)
    operator fun get(bit: Int): Boolean = data[bit ushr 6] and (1L shl (bit and 63)) != 0L
    fun rank(end: Int): Int = Math.toIntExact(directory.rank1(end.toLong()))
    fun select(ordinal: Int): Int {
        require(ordinal in 0 until ones)
        var lo = 0; var hi = (data.size + 7) / 8
        while (lo + 1 < hi) { val mid = (lo + hi) ushr 1; if (rank(mid * 512) <= ordinal) lo = mid else hi = mid }
        var remaining = ordinal - rank(lo * 512); var word = lo * 8
        while (true) {
            var value = data[word]; val count = java.lang.Long.bitCount(value)
            if (remaining < count) {
                repeat(remaining) { value = value and (value - 1) }
                return word * 64 + java.lang.Long.numberOfTrailingZeros(value)
            }
            remaining -= count; word++
        }
    }
}

/** Monotone offsets: packed low bits plus unary high bits; no full-width offset per node. */
private class Positions(val count: Int, val lowBits: Int, val low: LongArray, val high: Bits) {
    val byteSize: Long get() = low.size.toLong() * 8 + high.byteSize
    fun lowerBound(value: Int): Int {
        var lo = 0; var hi = count
        while (lo < hi) { val mid = (lo + hi) ushr 1; if (select(mid) < value) lo = mid + 1 else hi = mid }
        return lo
    }
    fun select(ordinal: Int): Int {
        val bit = ordinal.toLong() * lowBits; val word = (bit ushr 6).toInt(); val shift = (bit and 63).toInt()
        var lower = if (lowBits == 0) 0L else low[word] ushr shift
        if (shift + lowBits > 64) lower = lower or (low[word + 1] shl (64 - shift))
        lower = lower and ((1L shl lowBits) - 1)
        return (((high.select(ordinal) - ordinal).toLong() shl lowBits) or lower).toInt()
    }
    class Builder(universe: Int, val count: Int) {
        private val width = if (count == 0) 0 else 31 - Integer.numberOfLeadingZeros(maxOf(1, universe / count))
        private val low = LongArray(words(Math.toIntExact(count.toLong() * width)))
        private val highSize = if (count == 0) 0 else Math.toIntExact(((universe - 1).toLong() ushr width) + count + 1)
        private val high = LongArray(words(highSize))
        private var next = 0
        fun add(value: Int) {
            val bit = next.toLong() * width; val word = (bit ushr 6).toInt(); val shift = (bit and 63).toInt()
            val lower = value.toLong() and ((1L shl width) - 1)
            if (width != 0) low[word] = low[word] or (lower shl shift)
            if (shift + width > 64) low[word + 1] = low[word + 1] or (lower ushr (64 - shift))
            val upper = (value ushr width) + next
            high[upper ushr 6] = high[upper ushr 6] or (1L shl (upper and 63))
            next++
        }
        fun finish(): Positions { check(next == count); return Positions(count, width, low, Bits(high, highSize)) }
    }
}

/** Range-minimum tree over 512-bit blocks; a skipped subtree costs at most two blocks plus a tree search. */
private class Parentheses(val bits: Bits) {
    private val blocks = ((bits.size.toLong() + 511) / 512).toInt()
    private val leaves = Integer.highestOneBit(maxOf(1, blocks - 1)) * 2
    private val minimum = IntArray(leaves * 2) { Int.MAX_VALUE }
    init {
        var excess = 0
        for (i in 0 until bits.size) {
            excess += if (bits[i]) 1 else -1
            val leaf = leaves + i / 512
            minimum[leaf] = minOf(minimum[leaf], excess)
        }
        for (i in leaves - 1 downTo 1) minimum[i] = minOf(minimum[i * 2], minimum[i * 2 + 1])
    }
    val byteSize: Long get() = bits.byteSize + minimum.size.toLong() * 4
    fun close(open: Int, examined: () -> Unit): Int {
        val target = bits.rank(open) * 2 - open
        var excess = target + 1
        val blockEnd = minOf(bits.size.toLong(), ((open / 512 + 1).toLong() * 512)).toInt()
        for (i in open + 1 until blockEnd) { examined(); excess += if (bits[i]) 1 else -1; if (excess == target) return i }
        fun first(node: Int, left: Int, right: Int, from: Int): Int {
            if (right <= from || minimum[node] > target) return -1
            if (right - left == 1) return left
            val middle = (left + right) ushr 1
            val earlier = first(node * 2, left, middle, from)
            return if (earlier >= 0) earlier else first(node * 2 + 1, middle, right, from)
        }
        val block = first(1, 0, leaves, open / 512 + 1)
        check(block in 0 until blocks) { "Unbalanced JSON index" }
        val start = block * 512
        excess = bits.rank(start) * 2 - start
        for (i in start until minOf(bits.size.toLong(), start.toLong() + 512).toInt()) {
            examined(); excess += if (bits[i]) 1 else -1; if (excess == target) return i
        }
        error("Unbalanced JSON index block")
    }
}
