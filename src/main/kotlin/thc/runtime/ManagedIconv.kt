// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
import com.oracle.truffle.api.interop.InteropLibrary
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicLong

/** Actual host iconv, not a charset emulation. Native pointers never reach Core.
 * Parsing happens outside our lock; short, callback-free native calls and handle
 * lifetime transitions are serialized within this guest context. */
internal class ManagedIconv(private val cbits: () -> SulongCbits, private val stdio: ManagedStdio,
    private val threads: GuestThreads) {
    private val lock = Any()
    private val interop = InteropLibrary.getUncached()
    private val handles = HashMap<Long, Any>()
    private var library: Any? = null
    private var locale: Any? = null
    private var name: ManagedAddress? = null
    private var disposed = false

    private inline fun <T> foreign(action: () -> T): T {
        val previous = threads.enterForeign()
        try { return action() } finally { threads.leaveForeign(previous) }
    }
    private fun call(symbol: String, vararg arguments: Any): Any = foreign {
        interop.execute(interop.readMember(library!!, symbol), *arguments)
    }
    private fun initialize(native: Any) {
        if (disposed) fault("Iconv context is closed")
        stdio.nativeError(0) // Validate the target C ABI before allocating native state.
        if (library == null) library = native
        if (locale == null) {
            val created = call("thc_iconv_locale_new")
            if (interop.isNull(created)) fault("Native LC_CTYPE initialization from environment failed")
            locale = created
        }
    }
    private fun bytes(address: ManagedAddress, count: Long): ByteArray {
        address.requireByteRegion(count)
        return ByteArray(count.toInt()) { address.readWord8(it.toLong()).toByte() }
    }
    private fun cstring(address: ManagedAddress): ByteArray {
        val available = address.availableBytes()
        var count = 0L
        while (count < available) {
            if (address.readWord8(count++) == 0L) return bytes(address, count)
        }
        fault("Unterminated iconv encoding name")
    }
    private class Result(size: Int) {
        val bytes = ByteArray(size * 8)
        val buffer = CbitsBuffer(bytes, true)
        private val words = ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder())
        operator fun get(index: Int): Long = words.getLong(index * 8)
    }

    @TruffleBoundary fun localeEncoding(): ManagedAddress {
        val native = foreign { cbits().iconvLibrary() }
        return synchronized(lock) {
            initialize(native)
            name ?: run {
                val size = interop.asLong(call("thc_iconv_locale_size", locale!!))
                if (size !in 1..Int.MAX_VALUE.toLong()) fault("Invalid native locale encoding name")
                val bytes = ByteArray(size.toInt())
                call("thc_iconv_locale_copy", locale!!, CbitsBuffer(bytes, true))
                if (bytes.last() != 0.toByte()) fault("Unterminated native locale encoding name")
                ManagedAddress.fromAllocation(ManagedAllocation.immutable(bytes, 8)).also { name = it }
            }
        }
    }

    @TruffleBoundary fun open(to: ManagedAddress, from: ManagedAddress): Long {
        val toBytes = cstring(to)
        val fromBytes = cstring(from)
        val native = foreign { cbits().iconvLibrary() }
        return synchronized(lock) {
            initialize(native)
            // Reserve before native allocation, so exhaustion cannot leak a handle.
            val id = ids.getAndUpdate { if (it == Long.MAX_VALUE) it else it + 1 }
            if (id == Long.MAX_VALUE) fault("Iconv handle registry exhausted")
            val error = Result(1)
            val handle = call("thc_iconv_open", locale!!, CbitsBuffer(toBytes, false), toBytes.size.toLong(),
                CbitsBuffer(fromBytes, false), fromBytes.size.toLong(), error.buffer)
            stdio.nativeError(error[0])
            if (interop.isNull(handle)) -1L else { handles[id] = handle; id }
        }
    }

    @TruffleBoundary fun close(id: Long): Long = synchronized(lock) {
        val handle = handles.remove(id) ?: fault("Unknown, closed or cross-context iconv handle")
        val error = Result(1)
        val result = interop.asLong(call("thc_iconv_close", handle, error.buffer))
        stdio.nativeError(error[0])
        result
    }

    @TruffleBoundary fun convert(id: Long, inputCell: ManagedAddress, inputCount: ManagedAddress,
        outputCell: ManagedAddress, outputCount: ManagedAddress): Long = synchronized(lock) {
        val handle = handles[id] ?: fault("Unknown, closed or cross-context iconv handle")
        fun pointer(cell: ManagedAddress): ManagedAddress {
            cell.requireRange(0, 8, writable = true)
            if (cell.cbitsOwner()?.addressWidth != 8 || cell.cbitsOffset() % 8 != 0L)
                fault("Iconv pointer cell requires aligned native LP64 storage")
            return cell.readAddressElementIndex(0)
        }
        val input = if (inputCell === ManagedAddress.nullAddress()) ManagedAddress.nullAddress() else pointer(inputCell)
        val reset = input === ManagedAddress.nullAddress()
        val output = if (outputCell === ManagedAddress.nullAddress()) ManagedAddress.nullAddress() else pointer(outputCell)
        val discard = output === ManagedAddress.nullAddress()
        if (discard && !reset) fault("Iconv output pointer is null outside reset")
        fun count(cell: ManagedAddress): Long {
            cell.requireByteRegion(8, writable = true)
            if (cell.cbitsOffset() % 8 != 0L) fault("Iconv count cell requires native size_t alignment")
            val count = ManagedAddressRead.WORD64.read(cell, 0)
            if (count < 0 || count > Int.MAX_VALUE) fault("Iconv count exceeds managed buffer capacity")
            return count
        }
        val inSize = if (reset) 0L else count(inputCount)
        val outSize = if (discard) 0L else count(outputCount)
        if (!reset) input.requireByteRegion(inSize)
        if (!discard) output.requireByteRegion(outSize, writable = true)
        // The bounded bridge rejects overlapping cells/buffers before native
        // state changes; copying them would otherwise invent alias semantics.
        val regions = mutableListOf<Pair<ManagedAddress, Long>>()
        if (!reset) regions.addAll(listOf(inputCell to 8L, inputCount to 8L, input to inSize))
        if (!discard) regions.addAll(listOf(outputCell to 8L, outputCount to 8L, output to outSize))
        for (i in regions.indices) for (j in 0 until i)
            if (regions[i].first.overlaps(0, regions[i].second, regions[j].first, 0, regions[j].second))
                fault("Iconv requires disjoint pointer cells, counts and byte regions")
        val inBytes = if (reset) ByteArray(0) else bytes(input, inSize)
        val outBytes = ByteArray(outSize.toInt())
        val result = Result(4)
        call("thc_iconv_convert", handle, CbitsBuffer(inBytes, false), inSize,
            CbitsBuffer(outBytes, true), outSize, if (reset) 1 else 0, if (discard) 1 else 0, result.buffer)
        val inLeft = result[2]; val outLeft = result[3]
        if (inLeft !in 0..inSize || outLeft !in 0..outSize) fault("Invalid native iconv cursor writeback")
        if (!discard) {
            for (i in 0 until (outSize - outLeft).toInt()) output.writeWord8(i.toLong(), outBytes[i].toLong())
            outputCell.writeAddressElementIndex(0, output.plus(outSize - outLeft))
            outputCount.writeNativeScalar(0, 8, outLeft)
        }
        if (!reset) {
            inputCell.writeAddressElementIndex(0, input.plus(inSize - inLeft))
            inputCount.writeNativeScalar(0, 8, inLeft)
        }
        stdio.nativeError(result[1])
        result[0]
    }

    /** Called during language finalization while LLVM guest calls remain legal. */
    @TruffleBoundary fun dispose() = synchronized(lock) {
        if (!disposed) {
            disposed = true
            for (handle in handles.values) call("thc_iconv_close", handle, Result(1).buffer)
            handles.clear()
            locale?.let { call("thc_iconv_locale_free", it) }
            locale = null; library = null; name = null
        }
    }
    internal fun liveHandles(): Int = synchronized(lock) { handles.size }
    companion object { private val ids = AtomicLong(1) }
}
