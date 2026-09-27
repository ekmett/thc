// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause AND BSD-2-Clause

package thc

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.security.DigestInputStream

/**
 * A structural index of an owned, immutable UTF-8 snapshot, not a parsed JSON tree.
 *
 * Full SimpleBP topology is paired with source-backed interest rank/select.
 * Interest masks are regenerated from saved lexer states for at most512 source
 * bytes; no input-sized bitmap or position sequence is retained. Containers can
 * be skipped without scanning their contents. Keys/scalars require local source
 * handling, but no conversion of unused values or strings.
 *
 * The topology follows the Simple Cursor described in rust-works/succinctly
 * (MIT), revision6ee3210413d1f180fd6a93ab30c5bc6aaad29b78, json/simple.rs.
 * Rank support uses the supplied Everett port, revision
 * eaa5ff3ccdb970cd684d8a01fe5fcea2d3bc23ca, include/everett/rank.h;
 * see third-party-licenses/everett-BSD-2-Clause.txt. The source-backed extension
 * retains its exact Poppy prefix layout. This is THC's wire format, not an
 * upstream compatibility or performance claim.
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
    /** Index bytes count retained primitive arrays, not object headers or the source snapshot. */
    data class Statistics(val sourceByteSize: Int, val indexByteSize: Long,
        val serializedByteSize: Long?, val sourceFileBytesRead: Long, val sourceSnapshotBytesCopied: Long,
        val sourceHashBytesScanned: Long,
        val structuralBytesScanned: Long, val decodedSpanCount: Long, val decodedByteCount: Long,
        val navigationByteReads: Long, val balancedParenthesisBitsExamined: Long,
        val interestDirectoryBytes: Long, val lexerCheckpointBytes: Long, val topologyBytes: Long,
        val topologyNavigationBytes: Long, val scratchBytes: Long, val indexSourceBytesScanned: Long,
        val regeneratedSourceBytes: Long, val regeneratedBlockCount: Long)
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
        Statistics(s.bytes.size, s.indexBytes, s.serializedBytes, s.fileBytesRead, s.snapshotBytesCopied,
            s.hashBytesScanned, s.scanBytes, decodeCount, decodeBytes, navigationBytes, bpExamined,
            s.interest.directoryBytes, s.interest.checkpointBytes, s.bp.bits.data.size.toLong() * 8,
            s.bp.byteSize - s.bp.bits.data.size.toLong() * 8, s.interest.scratchBytes,
            s.interest.indexSourceBytesScanned, s.interest.regeneratedSourceBytes, s.interest.regeneratedBlockCount)
    }
    /** Hashes the exact original bytes, never normalized or reserialized JSON. */
    fun sha256(): String = synchronized(this) { live().hash.joinToString("") { "%02x".format(it) } }
    fun validateDocument(): Any? = root.decode()
    /** Structural coordinates, primarily for producer/reader parity and diagnostics. */
    internal fun rankInterest(endExclusive: Int): Int = synchronized(this) { live().interest.rank1(endExclusive) }
    internal fun selectInterest(ordinal: Int): Int = synchronized(this) { live().interest.select(ordinal) }
    override fun close() = synchronized(this) { decoded.clear(); storage = null }

    class Span internal constructor(private val owner: CoreJsonIndex, internal val begin: Int, internal val end: Int) {
        val kind: Kind get() = owner.kind(begin)
        val start: Int get() = synchronized(owner) { owner.live(); begin }
        val endExclusive: Int get() = synchronized(owner) { owner.live(); end }
        fun decode(): Any? = owner.decode(this)
        /** The adapter may canonicalize and memoize the result without retaining a duplicate here. */
        fun decodeUncached(): Any? = owner.decodeUncached(this)
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
        live()
        decoded[span.begin]?.let { return@synchronized it.getOrThrow() }
        val result = try {
            Result.success(decodeUncached(span))
        } catch (failure: Exception) { Result.failure(failure) }
        decoded[span.begin] = result
        result.getOrThrow()
    }
    private fun decodeUncached(span: Span): Any? = synchronized(this) {
        val s = live()
        decodeCount++; decodeBytes += span.end - span.begin
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(s.bytes, span.begin, span.end - span.begin)).toString()
        Json.parse(text)
    }

    /** Validated grammar makes the next marker a scalar's delimiter; only intervening whitespace is scanned. */
    private fun endAt(s: Storage, start: Int, limit: Int): Int = when (read(s, start)) {
        123, 91 -> {
            val marker = s.interest.rank1(start)
            val open = Math.multiplyExact(marker, 2)
            check(open < s.bp.bits.size && s.bp.bits[open] && s.bp.bits[open + 1])
            val close = s.bp.close(open) { bpExamined++ }
            Math.addExact(s.interest.select(close / 2), 1)
        }
        else -> {
            val marker = s.interest.rank1(start)
            var end = s.interest.select(marker)
            check(end in (start + 1)..limit)
            while (end > start && whitespace(read(s, end - 1))) end--
            end
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

    private class Storage(val bytes: ByteArray, val interest: SourceInterest,
                          val bp: Parentheses, val scanBytes: Long, val rootStart: Int, val rootEnd: Int,
                          val fileBytesRead: Long, val snapshotBytesCopied: Long,
                          val serializedBytes: Long? = null, initialHash: ByteArray? = null) {
        private var sourceHash: ByteArray? = initialHash
        var hashBytesScanned = if (initialHash == null) 0L else bytes.size.toLong()
            private set
        val hash: ByteArray get() {
            sourceHash?.let { return it }
            return MessageDigest.getInstance("SHA-256").digest(bytes).also {
                sourceHash = it; hashBytesScanned += bytes.size
            }
        }
        val indexBytes: Long get() = interest.byteSize + bp.byteSize
    }
    companion object {
        /** Load a verified navigation cache over a defensive snapshot; the caller owns [input]. */
        fun loadSidecar(bytes: ByteArray, input: InputStream): CoreJsonIndex =
            loadOwned(bytes.copyOf(), input, 0, bytes.size.toLong())

        /** Pin the file contents once; later pathname replacement cannot redirect any span. */
        fun loadSidecar(path: Path, input: InputStream): CoreJsonIndex {
            val bytes = Files.readAllBytes(path)
            return loadOwned(bytes, input, bytes.size.toLong(), 0)
        }

        private fun loadOwned(bytes: ByteArray, input: InputStream, fileBytesRead: Long, copiedBytes: Long): CoreJsonIndex {
            val digest = MessageDigest.getInstance("SHA-256")
            val checked = DigestInputStream(input, digest)
            val header = checked.readNBytes(64)
            require(header.size == 64) { "Truncated JSON index header" }
            require(header.copyOfRange(0, 8).contentEquals("THCJSIX1".toByteArray(Charsets.US_ASCII))) { "Unknown JSON index magic" }
            val fields = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            fields.position(8)
            require(fields.int == 2 && fields.int == 0) { "Unsupported JSON index version or flags" }
            val sourceLength = fields.long
            val markerCount = fields.long
            require(sourceLength == bytes.size.toLong()) { "JSON index source length mismatch" }
            val shape = JsonIndexShape(sourceLength, markerCount)
            val count = Math.toIntExact(markerCount)
            val sourceHash = MessageDigest.getInstance("SHA-256").digest(bytes)
            require(MessageDigest.isEqual(header.copyOfRange(32, 64), sourceHash)) { "JSON index source identity mismatch" }
            val chunk = ByteArray(8192)
            fun sectionWords(wordCount: Int): LongArray {
                val result = LongArray(wordCount)
                var word = 0
                while (word < result.size) {
                    val words = minOf(chunk.size / 8, result.size - word)
                    val size = words * 8
                    require(checked.readNBytes(chunk, 0, size) == size) { "Truncated JSON index section" }
                    val buffer = ByteBuffer.wrap(chunk, 0, size).order(ByteOrder.LITTLE_ENDIAN)
                    repeat(words) { result[word++] = buffer.long }
                }
                return result
            }
            fun sectionInts(intCount: Int): IntArray {
                val result = IntArray(intCount)
                var word = 0
                while (word < result.size) {
                    val words = minOf(chunk.size / 4, result.size - word)
                    val size = words * 4
                    require(checked.readNBytes(chunk, 0, size) == size) { "Truncated JSON index directory" }
                    val buffer = ByteBuffer.wrap(chunk, 0, size).order(ByteOrder.LITTLE_ENDIAN)
                    repeat(words) { result[word++] = buffer.int }
                }
                return result
            }
            val supers = sectionWords(shape.epochCount)
            val directory = sectionInts(Math.multiplyExact(shape.blockCount, 2))
            val states = sectionWords(words(shape.checkpointBits))
            val bpWords = sectionWords(words(shape.bpBits))
            checkPadding(states, shape.checkpointBits)
            checkPadding(bpWords, shape.bpBits)
            val expectedDigest = digest.digest()
            val trailer = input.readNBytes(32)
            require(trailer.size == 32 && MessageDigest.isEqual(trailer, expectedDigest)) { "JSON index integrity mismatch" }
            require(input.read() == -1) { "Trailing JSON index bytes" }

            val interest = SourceInterest(bytes, count, directory, supers, states)
            val bp = Bits(bpWords, shape.bpBits)
            interest.verify(bp)
            val (start, end) = scan(bytes, {}, {})
            return CoreJsonIndex(Storage(bytes, interest, Parentheses(bp), bytes.size.toLong(), start, end,
                fileBytesRead, copiedBytes, shape.serializedBytes, sourceHash))
        }

        /** Scalar reference builder for tests/parity; not the production sidecar producer. */
        fun fromBytes(bytes: ByteArray): CoreJsonIndex = build(bytes.copyOf(), 0, bytes.size.toLong())
        /** Scalar reference read: later replacement/deletion cannot retarget the snapshot. */
        fun read(path: Path): CoreJsonIndex {
            val bytes = Files.readAllBytes(path)
            return build(bytes, bytes.size.toLong(), 0)
        }
        private fun build(bytes: ByteArray, fileBytesRead: Long, copiedBytes: Long): CoreJsonIndex {
            val (start, end) = scan(bytes, {}, {})
            val (interest, bp) = SourceInterest.build(bytes)
            return CoreJsonIndex(Storage(bytes, interest, Parentheses(bp),
                bytes.size.toLong(), start, end, fileBytesRead, copiedBytes))
        }

        /** Iterative structural grammar check; scalar contents are never converted. */
        private fun scan(bytes: ByteArray, open: (Int) -> Unit, close: (Int) -> Unit): Pair<Int, Int> {
            var at = 0; var depth = 0; var states = ByteArray(32); var root = false; var rootStart = 0
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
                val beforeWhitespace = at
                while (at < bytes.size && whitespace(bytes[at].toInt() and 255)) at++
                if (depth == 0) {
                    if (root) { require(at == bytes.size) { "Trailing JSON at $at" }; return rootStart to beforeWhitespace }
                    root = true; rootStart = at; value(); continue
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

/** All derived extents are checked before narrowing or allocating a section. */
internal class JsonIndexShape(universe: Long, count: Long) {
    init {
        require(universe in 0..Int.MAX_VALUE.toLong() && count in 0..universe) { "Unsupported JSON index shape" }
    }
    val epochCount = Math.toIntExact((universe + 0xffffffffL) ushr 32)
    val blockCount = Math.toIntExact((universe + 2047) ushr 11)
    val quarterCount = Math.toIntExact((universe + 511) ushr 9)
    val checkpointBits = Math.toIntExact(quarterCount.toLong() * 2)
    val bpBits = Math.toIntExact(count * 2)
    val serializedBytes = 96L + epochCount.toLong() * 8 + blockCount.toLong() * 8 +
        words(checkpointBits).toLong() * 8 + words(bpBits).toLong() * 8
}

private fun checkPadding(data: LongArray, bits: Int) {
    if (bits and 63 != 0) require(data.last() ushr (bits and 63) == 0L) { "Nonzero JSON index padding" }
}

/** Supplied Poppy rank support over retained topology, not an input-sized interest bitmap. */
private class Bits(val data: LongArray, val size: Int) {
    private val directory = JsonRankDirectory.build(data, size.toLong())
    val byteSize: Long get() = data.size.toLong() * 8 + directory.directoryBytes
    operator fun get(bit: Int): Boolean = data[bit ushr 6] and (1L shl (bit and 63)) != 0L
    fun rank(end: Int): Int = Math.toIntExact(directory.rank1(end.toLong()))
}

/**
 * Original-byte interest masks are regenerated only in the selected512-byte block.
 * All calls after publication occur under the CoreJsonIndex owner monitor. The
 * fixed scratch mask cache is included in resident primitive-array accounting.
 */
private class SourceInterest(
    private val source: ByteArray,
    private var count: Int,
    private val directory: IntArray,
    private val supers: LongArray,
    private val states: LongArray,
) {
    private val quarters = ((source.size.toLong() + 511) / 512).toInt()
    private val masks = LongArray(8)
    private val opens = LongArray(8)
    private val closes = LongArray(8)
    private var cachedQuarter = -1
    var indexSourceBytesScanned = 0L
        private set
    var regeneratedSourceBytes = 0L
        private set
    var regeneratedBlockCount = 0L
        private set
    val directoryBytes: Long get() = directory.size.toLong() * 4 + supers.size.toLong() * 8
    val checkpointBytes: Long get() = states.size.toLong() * 8
    val scratchBytes: Long get() = (masks.size + opens.size + closes.size).toLong() * 8
    val byteSize: Long get() = directoryBytes + checkpointBytes + scratchBytes

    private fun state(quarter: Int): Int = ((states[quarter ushr 5] ushr ((quarter and 31) * 2)) and 3).toInt()
    private fun prefix(quarter: Int): Int = if (quarter == quarters) count else
        Math.toIntExact(jsonRankQuarterPrefix(directory, supers, quarter.toLong() * 512))

    private fun regenerate(quarter: Int) {
        if (cachedQuarter == quarter) return
        val start = Math.multiplyExact(quarter, 512)
        val length = minOf(512, source.size - start)
        jsonMaskBlock(source, start, length, state(quarter), masks, opens, closes)
        cachedQuarter = quarter
        regeneratedSourceBytes += length
        regeneratedBlockCount++
    }
    fun rank1(end: Int): Int {
        require(end in 0..source.size)
        if (end == source.size) return count
        val quarter = end ushr 9
        val before = prefix(quarter)
        val bits = end and 511
        if (bits == 0) return before
        regenerate(quarter)
        return Math.addExact(before, jsonRankPrefix512(masks, 0, bits))
    }
    fun select(ordinal: Int): Int {
        require(ordinal in 0 until count)
        var lo = 0; var hi = quarters
        while (lo + 1 < hi) {
            val mid = (lo + hi) ushr 1
            if (prefix(mid) <= ordinal) lo = mid else hi = mid
        }
        var remaining = ordinal - prefix(lo)
        regenerate(lo)
        for (word in masks.indices) {
            var value = masks[word]
            val population = java.lang.Long.bitCount(value)
            if (remaining < population) {
                repeat(remaining) { value = value and (value - 1) }
                val position = lo.toLong() * 512 + word * 64 + java.lang.Long.numberOfTrailingZeros(value)
                require(position < source.size) { "Invalid JSON interest position" }
                return Math.toIntExact(position)
            }
            remaining -= population
        }
        error("Missing JSON interest bit")
    }

    /** One complete classifier pass, separate from grammar, hashing, and demand navigation. */
    private inline fun walk(visit: (quarter: Int, initialState: Int) -> Unit) {
        var lexer = 0
        for (quarter in 0 until quarters) {
            val start = quarter * 512
            val before = lexer
            lexer = jsonMaskBlock(source, start, minOf(512, source.size - start), lexer, masks, opens, closes)
            visit(quarter, before)
        }
        indexSourceBytesScanned += source.size
        cachedQuarter = -1
    }
    private inline fun markers(visit: (opening: Boolean, closing: Boolean) -> Unit) {
        for (word in masks.indices) {
            var value = masks[word]
            while (value != 0L) {
                val bit = value and -value
                visit(opens[word] and bit != 0L, closes[word] and bit != 0L)
                value = value and (value - 1)
            }
        }
    }

    /** Imported directory contents are verified against immutable source, never trusted by shape/hash alone. */
    fun verify(bp: Bits) {
        val cursor = JsonRankDirectoryCursor()
        var total = 0L; var packed = 0
        walk { quarter, before ->
            require(state(quarter) == before) { "JSON lexer checkpoint mismatch" }
            val block = quarter ushr 2; val run = quarter and 3
            if (run == 0) {
                packed = 0
                val relative = cursor.before(block.toLong(), total)
                require(directory[block * 2] == relative) { "JSON relative rank mismatch" }
                if (JsonRankDirectoryCursor.startsEpoch(block.toLong()))
                    require(supers[block ushr 21] == cursor.epochBase) { "JSON epoch rank mismatch" }
            }
            val population = jsonRankPrefix512(masks, 0, 512)
            if (run < 3) packed = packed or (population shl (run * 11))
            markers { opening, closing ->
                require(total < count) { "Extra JSON source marker" }
                val bit = Math.toIntExact(total * 2)
                val expected = if (opening) 3L else if (closing) 0L else 2L
                require((bp.data[bit ushr 6] ushr (bit and 63)) and 3L == expected) { "JSON topology mismatch" }
                total++
            }
            if (run == 3 || quarter == quarters - 1)
                require(directory[block * 2 + 1] == packed) { "JSON quarter rank mismatch" }
        }
        require(total == count.toLong()) { "JSON marker count mismatch" }
    }

    companion object {
        fun build(source: ByteArray): Pair<SourceInterest, Bits> {
            val shape = JsonIndexShape(source.size.toLong(), 0)
            val result = SourceInterest(source, 0, IntArray(shape.blockCount * 2),
                LongArray(shape.epochCount), LongArray(words(shape.checkpointBits)))
            val cursor = JsonRankDirectoryCursor()
            var total = 0L
            result.walk { quarter, before ->
                result.states[quarter ushr 5] = result.states[quarter ushr 5] or (before.toLong() shl ((quarter and 31) * 2))
                val block = quarter ushr 2; val run = quarter and 3
                if (run == 0) {
                    result.directory[block * 2] = cursor.before(block.toLong(), total)
                    if (JsonRankDirectoryCursor.startsEpoch(block.toLong())) result.supers[block ushr 21] = cursor.epochBase
                }
                val population = jsonRankPrefix512(result.masks, 0, 512)
                if (run < 3) result.directory[block * 2 + 1] = result.directory[block * 2 + 1] or (population shl (run * 11))
                total += population
            }
            result.count = Math.toIntExact(total)
            val bitCount = Math.toIntExact(total * 2)
            val bp = LongArray(words(bitCount))
            var marker = 0
            result.walk { _, _ ->
                result.markers { opening, closing ->
                    val pair = if (opening) 3L else if (closing) 0L else 2L
                    val bit = marker++ * 2
                    bp[bit ushr 6] = bp[bit ushr 6] or (pair shl (bit and 63))
                }
            }
            check(marker == result.count)
            return result to Bits(bp, bitCount)
        }
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
