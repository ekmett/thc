// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime

import thc.Language
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Original RTS unique-supply cells, shared by compiler sessions in one THC context.
 * Managed address atomics synchronize on the actual backing array, including aliases.
 * No native GHC RTS is loaded and no JVM/native pointer is manufactured. */
internal class CompilerRts {
    private val counter = ManagedAddress.compilerCell(ByteArray(8), this)
    private val increment = ManagedAddress.compilerCell(
        ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).putLong(1L).array(), this)
    @Volatile private var closed = false

    fun requireCurrent() {
        if (closed || Language.currentState(null).compilerRts !== this)
            fault("Compiler RTS cell belongs to another or closed THC context")
    }

    fun address(symbol: String): ManagedAddress {
        requireCurrent()
        return when (symbol) {
            "ghc_unique_counter64" -> counter
            "ghc_unique_inc" -> increment
            else -> fault("Unknown compiler RTS data label $symbol")
        }
    }

    fun close() { closed = true }
}
