// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime

import com.oracle.truffle.api.CompilerDirectives
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
    private val flags by lazy { ManagedAddress.rtsFlags(this) }
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

    /** The pinned Linux getter reads one CBool, not an opaque zero-filled RTS image.
     * GHC 9.14.1's generated Flags/Test.hs uses TraceFlags +392 and user +11.
     * Other producing layouts need their own original getter/header evidence. */
    fun flagsAddress(layout: TargetLayout?): ManagedAddress {
        requireCurrent()
        if (layout == null || layout.compilerId != "ghc-9.14.1" ||
            layout.compilerAbi != "inplace" || layout.platform != "x86_64-linux" ||
            layout.way != "dynamic-nonprofiling" || layout.wordBytes != 8 ||
            layout.endianness != "little")
            fault("RtsFlags requires the supported GHC 9.14.1 Linux producing layout")
        return flags
    }

    fun readFlagByte(byteOffset: Long): Long {
        requireCurrent()
        if (byteOffset != 403L) {
            CompilerDirectives.transferToInterpreter()
            fault("Unsupported RtsFlags byte field at offset $byteOffset")
        }
        // THC user trace primops always emit to context stderr, independently of
        // the final diagnostics-counter option. This is not native eventlog status.
        return 1L
    }

    fun close() { closed = true }
}
