// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
import java.io.Closeable
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.util.IdentityHashMap
import thc.Language

/** Original Unix directory streams over actual libc DIR objects. Opaque guest
 * addresses are allocation identities, never guest-supplied native pointers.
 * Short callback-free operations and retirement share one context lock. */
internal class NativeDirectoryStreams(private val directory: NativeDirectoryOwner) : Closeable {
    private val context = Language.currentState()
    private val streams = IdentityHashMap<ManagedAllocation, Stream>()
    private val entries = IdentityHashMap<ManagedAllocation, Entry>()
    private var disposed = false

    private class Stream : Closeable {
        val arena = Arena.ofShared()
        val slot = arena.allocate(ValueLayout.ADDRESS)
        val address = token()
        var entry: Entry? = null
        private var closed = false
        fun closeResult(): Int {
            if (closed) return 0
            closed = true
            return try { NativeDirectoryApi.closeStream(slot) } finally { arena.close() }
        }
        override fun close() { closeResult() }
    }
    private class Entry(val stream: Stream, val address: ManagedAddress, val name: ManagedAllocation)

    private fun current() {
        if (Language.currentState() !== context) fault("Directory stream belongs to another context")
        if (disposed) fault("Directory stream registry is closed")
    }
    private fun key(address: ManagedAddress): ManagedAllocation {
        if (address.cbitsOffset() != 0L) fault("Directory handle requires its exact opaque base")
        return address.cbitsOwner() ?: fault("Directory handle has no managed identity")
    }
    private fun stream(address: ManagedAddress): Stream {
        current()
        return streams[key(address)] ?: fault("Unknown, closed or cross-context directory stream")
    }
    private inline fun <T> foreign(action: () -> T): T {
        val previous = context.threads.enterForeign()
        try { return action() } finally { context.threads.leaveForeign(previous) }
    }
    private fun retireEntry(stream: Stream) {
        stream.entry?.let {
            entries.remove(key(it.address))
            // All derived managed byte views retain this allocation. Shrinking
            // invalidates their ranges when libc may reuse its dirent buffer.
            it.name.shrink(0)
        }
        stream.entry = null
    }
    private fun acquire(action: (Stream) -> Unit): ManagedAddress {
        current()
        val stream = Stream()
        try {
            foreign { action(stream) }
            streams[key(stream.address)] = stream
            return stream.address
        } catch (failure: Throwable) {
            try { stream.close() } catch (closing: Throwable) { failure.addSuppressed(closing) }
            throw failure
        }
    }

    @Synchronized @TruffleBoundary fun open(path: ByteArray): ManagedAddress =
        acquire { stream -> directory.borrow().use { NativeDirectoryApi.openStream(it.descriptor, path, stream.slot) } }

    /** The duplicate already belongs to this call; failed fdopendir preserves
     * the original guest descriptor and closes only this private duplicate. */
    @Synchronized @TruffleBoundary fun fromDescriptor(resource: NativeFileResource): ManagedAddress =
        NativeFileLease().use { lease ->
            val slot = lease.openSlot()
            slot.set(ValueLayout.JAVA_INT, 0, resource.duplicateDescriptor())
            acquire { NativeDirectoryApi.fdOpenStream(slot, it.slot) }
        }

    @Synchronized @TruffleBoundary fun read(address: ManagedAddress, output: ManagedAddress): Long {
        val stream = stream(address)
        val allocation = output.cbitsOwner()
        fun readChecked(): Long {
            if (allocation != null && allocation === stream.entry?.name)
                fault("Directory output pointer cell overlaps the active directory-entry image")
            output.requireRange(0, 8, writable = true)
            if (output.cbitsOffset() % 8 != 0L) fault("Directory entry output requires an aligned pointer cell")
            if (allocation != null) {
                if (allocation.addressWidth != 8) fault("Directory entry output requires an LP64 pointer cell")
                allocation.requireAddressCell(output.cbitsOffset())
            } else output.requireByteRegion(8, writable = true)
            val token = token()
            // Native cells store real projections; reserve that projection before
            // advancing the directory, so publication cannot discover bad storage.
            if (output.nativeAllocation() != null) token.toNativeBits()
            retireEntry(stream)
            var status = -1
            foreign {
                NativeDirectoryApi.readStream(stream.slot.get(ValueLayout.ADDRESS, 0), context.stdio.errno().toInt()) { result, error, name ->
                    status = result
                    val entryAddress = if (name == null) ManagedAddress.nullAddress() else {
                        val bytes = ManagedAllocation.mutable(name.size.toLong(), 8)
                        for (i in name.indices) bytes.writeByte(i.toLong(), name[i].toLong())
                        val entry = Entry(stream, token, bytes)
                        entries[key(token)] = entry
                        stream.entry = entry
                        token
                    }
                    output.writeAddressElementIndex(0, entryAddress)
                    context.stdio.captureForeignErrno(error.toLong())
                }
            }
            return status.toLong()
        }
        return output.withNativeBorrow {
            if (allocation == null) readChecked() else synchronized(allocation) { readChecked() }
        }
    }

    @Synchronized @TruffleBoundary fun name(address: ManagedAddress): ManagedAddress {
        current()
        val entry = entries[key(address)] ?: fault("Directory entry is expired or belongs to another context")
        return ManagedAddress.fromAllocation(entry.name)
    }
    @Synchronized @TruffleBoundary fun freeEntry(address: ManagedAddress) {
        current()
        if (address !== ManagedAddress.nullAddress() && !entries.containsKey(key(address)))
            fault("Directory entry is expired or belongs to another context")
        // Genuine Unix/glibc __hscore_free_dirent is a no-op. Only the next read
        // or closedir invalidates this selected platform's reusable entry image.
    }
    @Synchronized @TruffleBoundary fun closeStream(address: ManagedAddress): Long {
        val stream = stream(address)
        streams.remove(key(address))
        retireEntry(stream)
        val error = foreign { stream.closeResult() }
        context.stdio.nativeError(error.toLong())
        return if (error == 0) 0 else -1
    }
    @Synchronized internal fun abandon(address: ManagedAddress) {
        val stream = streams.remove(key(address)) ?: return
        retireEntry(stream)
        stream.close()
    }
    @Synchronized override fun close() {
        if (disposed) return
        disposed = true
        var failure: Throwable? = null
        for (stream in streams.values) try { retireEntry(stream); stream.close() }
        catch (error: Throwable) { if (failure == null) failure = error else failure.addSuppressed(error) }
        streams.clear(); entries.clear()
        failure?.let { throw it }
    }
    @Synchronized internal fun liveCount(): Int = streams.size
    companion object {
        private fun token(): ManagedAddress = ManagedAddress.fromAllocation(
            ManagedAllocation.immutable(ByteArray(0), 8, staticImage = true))
    }
}
