// SPDX-FileCopyrightText: 2026 Edward Kmett <ekmett@gmail.com>
// SPDX-License-Identifier: BSD-2-Clause
// Adapted from Everett include/everett/rank.h at
// eaa5ff3ccdb970cd684d8a01fe5fcea2d3bc23ca. See third-party-licenses/everett-BSD-2-Clause.txt.
package thc

/**
 * Exclusive rank over an immutable, least-significant-bit-first bitmap.
 *
 * Every 2048-bit block has a 32-bit count relative to its 2^32-bit epoch and
 * three 10-bit populations at bit offsets 0, 11, and 22. The zero spacers
 * provide 11-bit lanes for the prefix sum. These are individual populations,
 * not cumulative counts. Each epoch has a 64-bit absolute count.
 *
 * The bitmap is borrowed and must remain
 * unchanged for the lifetime of this directory. Bits beyond [bitLength] are
 * ignored. No bitmap copy is retained. Metadata is constructed by [build]; a
 * future mapped reader must check metadata contents separately from section
 * shapes. This class does not certify arbitrary imported directories.
 */
internal class JsonRankDirectory private constructor(
    private val bitmap: LongArray,
    val bitLength: Long,
    private val directory: IntArray,
    private val supers: LongArray,
    val totalOnes: Long,
) {
    /** Logical directory storage, excluding the borrowed bitmap and array header. */
    val directoryBytes: Long get() = directory.size.toLong() * Int.SIZE_BYTES + supers.size.toLong() * Long.SIZE_BYTES

    /** Count one bits in the half-open interval [0, end). */
    fun rank1(end: Long): Long {
        require(end >= 0 && end <= bitLength) { "rank position is outside the bitmap" }
        if (end == bitLength) return totalOnes

        val header = (end ushr 11).toInt() shl 1
        val quarter = ((end ushr 9) and 3L).toInt()
        val result = supers[(end ushr 32).toInt()] + (directory[header].toLong() and 0xffffffffL) +
            jsonRankRunPrefix(directory[header + 1], quarter)
        val firstWord = ((end ushr 9) shl 3).toInt()
        return result + jsonRankPrefix512(bitmap, firstWord, (end and 511L).toInt())
    }

    fun rank1(end: Int): Long = rank1(end.toLong())

    /** Count zero bits in the half-open interval [0, end). */
    fun rank0(end: Long): Long = end - rank1(end)
    fun rank0(end: Int): Long = rank0(end.toLong())

    companion object {
        fun build(bitmap: LongArray, bitLength: Int): JsonRankDirectory = build(bitmap, bitLength.toLong())

        fun build(bitmap: LongArray, bitLength: Long): JsonRankDirectory {
            require(bitLength >= 0) { "bitmap length is negative" }
            val requiredWords = (bitLength ushr 6) + if ((bitLength and 63L) == 0L) 0 else 1
            require(bitmap.size.toLong() == requiredWords) { "bitmap shape differs from its length" }
            val wordCount = bitmap.size
            val blockCount = ((bitLength ushr 11) + if ((bitLength and 2047L) == 0L) 0 else 1).toInt()
            val directory = IntArray(blockCount * 2)
            val supers = LongArray(((bitLength ushr 32) + if ((bitLength and 0xffffffffL) == 0L) 0 else 1).toInt())
            val cursor = JsonRankDirectoryCursor()
            val tailBits = (bitLength and 63L).toInt()
            val tailMask = if (tailBits == 0) -1L else (1L shl tailBits) - 1L
            var total = 0L
            for (block in 0 until blockCount) {
                directory[block * 2] = cursor.before(block.toLong(), total)
                if (JsonRankDirectoryCursor.startsEpoch(block.toLong())) supers[block ushr 21] = cursor.epochBase
                var packed = 0
                for (quarter in 0 until 4) {
                    var population = 0
                    val startWord = (block shl 5) + (quarter shl 3)
                    val endWord = startWord + minOf(8, wordCount - startWord)
                    var word = startWord
                    while (word < endWord) {
                        val value = if (word == wordCount - 1) bitmap[word] and tailMask else bitmap[word]
                        population += java.lang.Long.bitCount(value)
                        word++
                    }
                    if (quarter < 3) packed = packed or (population shl (quarter * 11))
                    total += population
                }
                directory[block * 2 + 1] = packed
            }
            return JsonRankDirectory(bitmap, bitLength, directory, supers, total)
        }
    }
}

/** Caller supplies a quarter index in 0..3 and three independent populations in 0..512. */
internal fun jsonRankRunPrefix(packed: Int, quarter: Int): Int {
    val selected = (packed.toLong() and 0xffffffffL) and ((1L shl (11 * quarter)) - 1L)
    return (((selected * 0x400801L) ushr 22) and 2047L).toInt()
}

/** Caller supplies 0..512 bits and enough readable words for that prefix. */
internal fun jsonRankPrefix512(words: LongArray, firstWord: Int, bits: Int): Int {
    var result = 0
    var word = 0
    while (word < (bits ushr 6)) {
        result += java.lang.Long.bitCount(words[firstWord + word])
        word++
    }
    if ((bits and 63) != 0) {
        result += java.lang.Long.bitCount(words[firstWord + word] and ((1L shl (bits and 63)) - 1L))
    }
    return result
}

/** Epoch accounting is independent of payload allocation, including at the 2^32-bit boundary. */
internal class JsonRankDirectoryCursor {
    var epochBase: Long = 0L
        private set

    fun before(block: Long, total: Long): Int {
        require(block >= 0 && total >= 0) { "negative rank directory coordinate" }
        if (startsEpoch(block)) epochBase = total
        if (total < epochBase || total - epochBase > 0xffffffffL) throw ArithmeticException("rank relative count")
        return (total - epochBase).toInt()
    }

    companion object {
        fun startsEpoch(block: Long): Boolean = (block and ((1L shl 21) - 1L)) == 0L
    }
}
