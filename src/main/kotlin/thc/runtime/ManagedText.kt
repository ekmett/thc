// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
import java.lang.ref.Reference
import java.nio.ByteBuffer
import java.util.function.LongSupplier
import thc.Language

/** Read-only, synchronous views of the existing ByteArray# storage. No staging
 * copy, native address projection, pinning, or substitution of the C algorithm. */
internal object ManagedText {
    @JvmStatic @TruffleBoundary
    fun invoke(operation: TextForeignOp, bytes: Any?, offset: Long, length: Long, count: Long): Long {
        val owner = Language.currentState()
        val cbits = owner.cbits()
        // Loading another guest language must not hold a ByteArray owner lock.
        val function = cbits.textFunction(operation)
        fun check(size: Long) {
            if (offset < 0 || length < 0 || offset > size || length > size - offset)
                fault("Text ByteArray# range outside its backing storage")
            if (operation == TextForeignOp.MEMCHR && count !in 0..255L)
                fault("Text memchr byte is outside Word8")
        }
        fun call(buffer: ByteBuffer, size: Long): Long {
            val view = CbitsBuffer(buffer.asReadOnlyBuffer(), false, LongSupplier { size })
            val previous = owner.threads.enterForeign()
            try { return cbits.text(function, operation, view, offset, length, count) }
            finally {
                owner.threads.leaveForeign(previous)
                Reference.reachabilityFence(bytes)
                Reference.reachabilityFence(view)
            }
        }
        return when (bytes) {
            is ByteArray -> {
                check(bytes.size.toLong())
                call(ByteBuffer.wrap(bytes), bytes.size.toLong())
            }
            is ManagedAllocation -> synchronized(bytes) {
                // The owner monitor excludes shrink and pointer-cell changes
                // from preflight through the complete original C invocation.
                check(bytes.size)
                call(bytes.exposeSegment().asByteBuffer(), bytes.size)
            }
            else -> fault("Text call requires the ByteArray# carrier")
        }
    }
}
