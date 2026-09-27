// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
import java.lang.ref.Reference
import java.nio.ByteBuffer
import java.util.function.LongSupplier
import thc.Language

/** Synchronous borrowed views of the existing ByteArray# storage. No staging
 * copy, native address projection, pinning, or substitution of the C algorithm. */
internal object ManagedText {
    /** Original text promises valid UTF-8 and allocates a distinct output array.
     * Borrow both owners until the synchronous C call has finished; never retain
     * a writable buffer after dropping the allocation's shrink/pointer lock. */
    @JvmStatic @TruffleBoundary
    fun reverse(destination: Any?, source: Any?, offset: Long, length: Long) {
        val owner = Language.currentState()
        val cbits = owner.cbits()
        val function = cbits.textFunction(TextForeignOp.REVERSE)
        fun size(bytes: Any?): Long = when (bytes) {
            is ByteArray -> bytes.size.toLong()
            is ManagedAllocation -> bytes.size
            else -> fault("Text reverse requires ByteArray# carriers")
        }
        fun key(bytes: Any?): Any? = if (bytes is ManagedAllocation) bytes.storageKey() else bytes
        fun view(bytes: Any?, writable: Boolean, size: Long): CbitsBuffer {
            val buffer = when (bytes) {
                is ByteArray -> ByteBuffer.wrap(bytes)
                is ManagedAllocation -> bytes.exposeSegment().asByteBuffer()
                else -> fault("Text reverse requires ByteArray# carriers")
            }
            return CbitsBuffer(if (writable) buffer else buffer.asReadOnlyBuffer(), writable, LongSupplier { size })
        }
        fun call() {
            val sourceSize = size(source)
            val destinationSize = size(destination)
            if (offset < 0 || length < 0 || offset > sourceSize || length > sourceSize - offset || length > destinationSize)
                fault("Text reverse range outside its backing storage")
            if (destination is ManagedAllocation && !destination.isWritable)
                fault("Text reverse requires mutable output storage")
            if (length != 0L && key(destination) === key(source))
                fault("Text reverse requires distinct input and output storage")
            val input = view(source, false, sourceSize)
            val output = view(destination, true, length)
            val previous = owner.threads.enterForeign()
            try {
                // The empty original call writes nothing; avoid forming dst - 1.
                if (length != 0L) cbits.textReverse(function, output, input, offset, length)
            } finally {
                owner.threads.leaveForeign(previous)
                Reference.reachabilityFence(source)
                Reference.reachabilityFence(destination)
                Reference.reachabilityFence(input)
                Reference.reachabilityFence(output)
            }
        }
        if (destination is ManagedAllocation && source is ManagedAllocation)
            destination.withOrderedLocks(source) { call() }
        else if (destination is ManagedAllocation) synchronized(destination) { call() }
        else if (source is ManagedAllocation) synchronized(source) { call() }
        else call()
    }

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
