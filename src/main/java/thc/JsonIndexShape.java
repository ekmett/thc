// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause AND BSD-2-Clause
package thc;

/** All derived extents are checked before narrowing or allocating a section. */
public final class JsonIndexShape {
    private final int epochCount, blockCount, quarterCount, checkpointBits, bpBits;
    public JsonIndexShape(long universe, long count) {
        if (universe < 0 || universe > Integer.MAX_VALUE || count < 0 || count > universe)
            throw new IllegalArgumentException("Unsupported JSON index shape");
        epochCount = Math.toIntExact((universe + 0xffffffffL) >>> 32);
        blockCount = Math.toIntExact((universe + 2047) >>> 11);
        quarterCount = Math.toIntExact((universe + 511) >>> 9);
        checkpointBits = Math.toIntExact((long) quarterCount * 2);
        bpBits = Math.toIntExact(count * 2);

    }
    public int getEpochCount() { return epochCount; }
    public int getBlockCount() { return blockCount; }
    public int getQuarterCount() { return quarterCount; }
    public int getCheckpointBits() { return checkpointBits; }
    public int getBpBits() { return bpBits; }
}
