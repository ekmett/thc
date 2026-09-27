// SPDX-FileCopyrightText: 2025 rust-works
// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: MIT
// Scalar transcription of John Ky's succinctly Simple Cursor state machine,
// rust-works/succinctly 6ee3210413d1f180fd6a93ab30c5bc6aaad29b78, src/json/simple.rs.
// Matches compiler/json-index/json_index.c; see third-party-licenses/succinctly-MIT.txt.

package thc

/**
 * Regenerate at most512 original bytes of interest/open/close masks. State0 is
 * JSON,1 is string,2 consumes one escaped byte then returns to string. Masks are
 * LSB-first; excess capacity and tail bits are zero. The caller owns the buffers.
 * This classifier does not certify JSON grammar, UTF-8, or scalar semantics.
 */
internal fun jsonMaskBlock(source: ByteArray, start: Int, length: Int, initialState: Int,
                          interest: LongArray, opens: LongArray, closes: LongArray): Int {
    require(start >= 0 && start <= source.size && length in 0..512 && length <= source.size - start)
    require(initialState in 0..2)
    val words = (length + 63) / 64
    require(interest.size >= words && opens.size >= words && closes.size >= words)
    require(interest !== opens && interest !== closes && opens !== closes)
    interest.fill(0L); opens.fill(0L); closes.fill(0L)
    var state = initialState
    for (offset in 0 until length) {
        val c = source[start + offset].toInt() and 255
        if (state == 2) state = 1
        else if (state == 1) {
            if (c == 34) state = 0
            else if (c == 92) state = 2
        } else if (c == 34) state = 1
        else {
            val opening = c == 123 || c == 91
            val closing = c == 125 || c == 93
            if (opening || closing || c == 44 || c == 58) {
                val word = offset ushr 6; val bit = 1L shl (offset and 63)
                interest[word] = interest[word] or bit
                if (opening) opens[word] = opens[word] or bit
                if (closing) closes[word] = closes[word] or bit
            }
        }
    }
    return state
}
