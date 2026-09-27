// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
import java.lang.ref.Reference
import java.nio.ByteBuffer
import java.util.function.LongSupplier
import thc.Language

/** Synchronous read-only borrowing; the original C validator never gets an
 * unowned native pointer or a writable view, including for malloc storage. */
internal object ManagedByteStringUtf8 {
    @JvmStatic @TruffleBoundary
    fun validate(address: ManagedAddress, length: Long): Long {
        val state = Language.currentState()
        val cbits = state.cbits()
        // Language loading may block; do it before taking storage locks.
        val function = cbits.utf8Function()
        fun call(bytes: Any): Long {
            val previous = state.threads.enterForeign()
            try { return cbits.utf8Validate(function, bytes, length) }
            finally {
                state.threads.leaveForeign(previous)
                Reference.reachabilityFence(address)
                Reference.reachabilityFence(bytes)
            }
        }
        // The original function returns true before inspecting the pointer.
        if (address === ManagedAddress.nullAddress() && length == 0L) return call(0L)
        fun view(buffer: ByteBuffer, offset: Long): Long = call(CbitsBuffer(
            buffer.asReadOnlyBuffer(), false, LongSupplier { offset + length }, offset))
        if (address.nativeAllocation() != null) return address.withNativeBorrow {
            address.requireByteRegion(length)
            address.withNativeSegment { view(it.asByteBuffer(), 0) }
        }
        fun managed(): Long {
            address.requireByteRegion(length)
            return view(address.cbitsBuffer(), address.cbitsOffset())
        }
        return address.cbitsOwner()?.let { synchronized(it) { managed() } } ?: managed()
    }
}
