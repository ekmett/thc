// SPDX-FileCopyrightText: 2025 rust-works
// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: MIT
// Scalar transcription of John Ky's succinctly Simple Cursor state machine,
// rust-works/succinctly 6ee3210413d1f180fd6a93ab30c5bc6aaad29b78, src/json/simple.rs.
// Source-backed JSON masks; see third-party-licenses/succinctly-MIT.txt.
package thc;

import java.util.Arrays;

public final class CoreJsonMasks {
    private CoreJsonMasks() {}
    /** Regenerate at most 512 original bytes of LSB-first masks. State 0 is JSON,
     * 1 is string and 2 consumes one escaped byte. No grammar/UTF-8 validation. */
    public static int jsonMaskBlock(byte[] source, int start, int length, int initialState,
            long[] interest, long[] opens, long[] closes) {
        int words = (length + 63) / 64;
        if (start < 0 || start > source.length || length < 0 || length > 512 || length > source.length - start ||
                initialState < 0 || initialState > 2 || interest.length < words || opens.length < words || closes.length < words ||
                interest == opens || interest == closes || opens == closes) throw new IllegalArgumentException("Failed requirement.");
        Arrays.fill(interest, 0); Arrays.fill(opens, 0); Arrays.fill(closes, 0);
        int state = initialState;
        for (int offset = 0; offset < length; offset++) {
            int c = source[start + offset] & 255;
            if (state == 2) state = 1;
            else if (state == 1) {
                if (c == 34) state = 0;
                else if (c == 92) state = 2;
            } else if (c == 34) state = 1;
            else {
                boolean opening = c == 123 || c == 91, closing = c == 125 || c == 93;
                if (opening || closing || c == 44 || c == 58) {
                    int word = offset >>> 6;
                    long bit = 1L << (offset & 63);
                    interest[word] |= bit;
                    if (opening) opens[word] |= bit;
                    if (closing) closes[word] |= bit;
                }
            }
        }
        return state;
    }
}
