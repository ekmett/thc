// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

/** Decode-time literal payload, never a guest carrier. JSON still supplies its
 * original decimal strings; compact IEEE bits avoid decimal NaN canonicalization. */
internal sealed interface CoreFloatingLiteral {
    data class Single(val bits: Int) : CoreFloatingLiteral
    data class Double(val bits: Long) : CoreFloatingLiteral

    fun decode(kind: String): Any = when (this) {
        is Single -> {
            if (kind != "float") throw UnsupportedCore("Mismatched compact floating literal")
            Float.fromBits(bits)
        }
        is Double -> {
            if (kind != "double") throw UnsupportedCore("Mismatched compact floating literal")
            kotlin.Double.fromBits(bits)
        }
    }
}
