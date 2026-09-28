// SPDX-FileCopyrightText: 2026 Edward Kmett <ekmett@gmail.com>
// SPDX-License-Identifier: BSD-2-Clause
// Adapted from Everett include/everett/rank.h at
// eaa5ff3ccdb970cd684d8a01fe5fcea2d3bc23ca. See third-party/licenses/everett-BSD-2-Clause.txt.
package thc;

/** Epoch accounting independent of payload allocation, including the 2^32-bit boundary. */
public final class JsonRankDirectoryCursor {
    private long epochBase;
    public long getEpochBase() { return epochBase; }
    public int before(long block, long total) {
        if (block < 0 || total < 0) throw new IllegalArgumentException("negative rank directory coordinate");
        if (startsEpoch(block)) epochBase = total;
        if (total < epochBase || total - epochBase > 0xffffffffL) throw new ArithmeticException("rank relative count");
        return (int) (total - epochBase);
    }
    public static boolean startsEpoch(long block) { return (block & ((1L << 21) - 1)) == 0; }
}
