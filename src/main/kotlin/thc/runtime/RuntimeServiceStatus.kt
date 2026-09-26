// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime

/** Private v1 bridge codes. Public Haskell callers receive typed availability,
 * never a fabricated zero when a host service cannot supply a measurement. */
internal object RuntimeServiceStatus {
    const val UNSUPPORTED = -1L
    const val DISABLED = -2L
    const val DENIED = -3L
    const val UNAVAILABLE = -4L

    /** Strings cross the scalar bridge as Unicode codepoints, not UTF-16 units.
     * Metadata is small and infrequently queried; no native buffer is borrowed. */
    fun text(value: String, offset: Long): Long {
        val length = value.codePointCount(0, value.length)
        if (offset == -1L) return length.toLong()
        if (offset < 0 || offset >= length) fault("Runtime service text offset out of bounds")
        return value.codePointAt(value.offsetByCodePoints(0, offset.toInt())).toLong()
    }
}
