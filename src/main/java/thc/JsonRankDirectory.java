// SPDX-FileCopyrightText: 2026 Edward Kmett <ekmett@gmail.com>
// SPDX-License-Identifier: BSD-2-Clause
// Adapted from Everett include/everett/rank.h at
// eaa5ff3ccdb970cd684d8a01fe5fcea2d3bc23ca. See third-party/licenses/everett-BSD-2-Clause.txt.
package thc;

/** Exclusive rank over a borrowed immutable LSB-first bitmap. Every 2048-bit
 * block has an epoch-relative count and three independent 10-bit populations
 * separated into 11-bit lanes. Each 2^32-bit epoch has an absolute count. */
public final class JsonRankDirectory {
    private final long[] bitmap, supers;
    private final int[] directory;
    private final long bitLength, totalOnes;
    private JsonRankDirectory(long[] bitmap, long bitLength, int[] directory, long[] supers, long totalOnes) {
        this.bitmap = bitmap; this.bitLength = bitLength; this.directory = directory;
        this.supers = supers; this.totalOnes = totalOnes;
    }
    public long getBitLength() { return bitLength; }
    public long getTotalOnes() { return totalOnes; }
    public long getDirectoryBytes() { return (long) directory.length * Integer.BYTES + (long) supers.length * Long.BYTES; }
    public long rank1(long end) {
        if (end < 0 || end > bitLength) throw new IllegalArgumentException("rank position is outside the bitmap");
        if (end == bitLength) return totalOnes;
        long result = CoreJsonRank.jsonRankQuarterPrefix(directory, supers, end);
        int firstWord = (int) ((end >>> 9) << 3);
        return result + CoreJsonRank.jsonRankPrefix512(bitmap, firstWord, (int) (end & 511));
    }
    public long rank1(int end) { return rank1((long) end); }
    public long rank0(long end) { return end - rank1(end); }
    public long rank0(int end) { return rank0((long) end); }
    public static JsonRankDirectory build(long[] bitmap, int bitLength) { return build(bitmap, (long) bitLength); }
    public static JsonRankDirectory build(long[] bitmap, long bitLength) {
        if (bitLength < 0) throw new IllegalArgumentException("bitmap length is negative");
        long requiredWords = (bitLength >>> 6) + ((bitLength & 63) == 0 ? 0 : 1);
        if (bitmap.length != requiredWords) throw new IllegalArgumentException("bitmap shape differs from its length");
        int wordCount = bitmap.length;
        int blockCount = (int) ((bitLength >>> 11) + ((bitLength & 2047) == 0 ? 0 : 1));
        int[] directory = new int[blockCount * 2];
        long[] supers = new long[(int) ((bitLength >>> 32) + ((bitLength & 0xffffffffL) == 0 ? 0 : 1))];
        var cursor = new JsonRankDirectoryCursor();
        int tailBits = (int) (bitLength & 63);
        long tailMask = tailBits == 0 ? -1L : (1L << tailBits) - 1;
        long total = 0;
        for (int block = 0; block < blockCount; block++) {
            directory[block * 2] = cursor.before(block, total);
            if (JsonRankDirectoryCursor.startsEpoch(block)) supers[block >>> 21] = cursor.getEpochBase();
            int packed = 0;
            for (int quarter = 0; quarter < 4; quarter++) {
                int population = 0;
                int startWord = (block << 5) + (quarter << 3);
                int endWord = startWord + Math.min(8, wordCount - startWord);
                for (int word = startWord; word < endWord; word++) {
                    long value = word == wordCount - 1 ? bitmap[word] & tailMask : bitmap[word];
                    population += Long.bitCount(value);
                }
                if (quarter < 3) packed |= population << (quarter * 11);
                total += population;
            }
            directory[block * 2 + 1] = packed;
        }
        return new JsonRankDirectory(bitmap, bitLength, directory, supers, total);
    }
}
