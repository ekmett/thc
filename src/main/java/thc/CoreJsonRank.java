// SPDX-FileCopyrightText: 2026 Edward Kmett <ekmett@gmail.com>
// SPDX-License-Identifier: BSD-2-Clause
// Adapted from Everett include/everett/rank.h at
// eaa5ff3ccdb970cd684d8a01fe5fcea2d3bc23ca. See nih/licenses/everett-BSD-2-Clause.txt.
package thc;

public final class CoreJsonRank {
    private CoreJsonRank() {}
    public static long jsonRankQuarterPrefix(int[] directory, long[] supers, long position) {
        int header = (int) (position >>> 11) << 1;
        int quarter = (int) ((position >>> 9) & 3);
        return supers[(int) (position >>> 32)] + (directory[header] & 0xffffffffL) + jsonRankRunPrefix(directory[header + 1], quarter);
    }
    public static int jsonRankRunPrefix(int packed, int quarter) {
        long selected = (packed & 0xffffffffL) & ((1L << (11 * quarter)) - 1);
        return (int) (((selected * 0x400801L) >>> 22) & 2047);
    }
    public static int jsonRankPrefix512(long[] words, int firstWord, int bits) {
        int result = 0, word = 0;
        while (word < (bits >>> 6)) result += Long.bitCount(words[firstWord + word++]);
        if ((bits & 63) != 0) result += Long.bitCount(words[firstWord + word] & ((1L << (bits & 63)) - 1));
        return result;
    }
}
