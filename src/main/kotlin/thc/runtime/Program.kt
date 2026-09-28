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
