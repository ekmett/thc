// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

@file:Suppress("UNCHECKED_CAST")
package thc.runtime
import com.oracle.truffle.api.CompilerDirectives

/* Indexed frames, selective captures, rooted application and self-tail frame
 * restoration follow Cadenza. See NOTICE.md and LICENSE.txt. Haskell thunks
 * supply the additional lazy update/blackhole protocol. Async-capable AST
 * roots spill bounded non-tail activation chains to saved continuations. */
internal fun fault(message: String): Nothing {
    CompilerDirectives.transferToInterpreterAndInvalidate()
    throw RuntimeFault(message)
}
/** Machine-word narrowing retains the Word# Long carrier. */
internal fun narrowWordPrimitiveMask(name: String): Long = when (name) {
    "narrow8Word#" -> 0xffL
    "narrow16Word#" -> 0xffffL
    "narrow32Word#" -> 0xffff_ffffL
    else -> 0L
}
internal fun narrowWordLiteral(kind: String, value: String): Int {
    val maximum = when (kind) {
        "word8" -> 0xffL; "word16" -> 0xffffL; "word32" -> 0xffff_ffffL
        else -> throw RuntimeFault("Invalid narrow word literal kind: $kind")
    }
    val number = value.toLongOrNull()
    if (number == null || number !in 0L..maximum || number.toString() != value)
        throw RuntimeFault("Invalid $kind literal: $value")
    return number.toInt()
}
/** Narrow literals have canonical source spellings and an Int computation carrier. */
internal fun int8Literal(value: String): Int {
    val number = value.toLongOrNull()
    if (number == null || number !in Byte.MIN_VALUE.toLong()..Byte.MAX_VALUE.toLong() || number.toString() != value)
        throw RuntimeFault("Invalid int8 literal: $value")
    return number.toInt()
}
internal fun int16Literal(value: String): Int {
    val number = value.toLongOrNull()
    if (number == null || number !in Short.MIN_VALUE.toLong()..Short.MAX_VALUE.toLong() || number.toString() != value)
        throw RuntimeFault("Invalid int16 literal: $value")
    return number.toInt()
}
internal fun int32Literal(value: String): Int {
    val number = value.toLongOrNull()
    if (number == null || number !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() || number.toString() != value)
        throw RuntimeFault("Invalid int32 literal: $value")
    return number.toInt()
}
/** Int64 literals are canonical decimal signed 64-bit carriers, including both endpoints. */
internal fun int64Literal(value: String): Long {
    val number = value.toLongOrNull()
    if (number == null || number.toString() != value) throw RuntimeFault("Invalid int64 literal: $value")
    return number
}
/** Replace only the successfully forced link; aliases may already have updated this cell. */
internal fun updateForcedCell(cell: RecCell, original: Thunk, result: Any?) {
    synchronized(cell) {
        if (cell.initialized && cell.value === original) cell.value = result
    }
}
