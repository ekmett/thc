// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime;

/** Unsigned 64-bit conversion with one rounding at the destination precision. */
final class WordFloatingConversions {
    private WordFloatingConversions() {}

    // Halve a top-bit-set word into the signed range, retaining the discarded
    // bit as sticky. It is below either format's rounding position. Scaling by
    // two is exact; Float must never go through an intermediate Double.
    static float toFloat(long x) {
        return x >= 0 ? (float) x : (float) ((x >>> 1) | (x & 1)) * 2.0f;
    }

    static double toDouble(long x) {
        return x >= 0 ? (double) x : (double) ((x >>> 1) | (x & 1)) * 2.0;
    }
}
