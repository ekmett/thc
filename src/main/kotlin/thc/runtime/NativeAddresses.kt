// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.interop.InteropLibrary
import com.oracle.truffle.api.interop.TruffleObject
import com.oracle.truffle.api.interop.UnsupportedMessageException
import com.oracle.truffle.api.library.ExportLibrary
import com.oracle.truffle.api.library.ExportMessage
import com.oracle.truffle.api.nodes.Node
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.lang.ref.Cleaner
import java.lang.ref.Reference
import java.lang.ref.WeakReference
import java.util.TreeMap
import java.util.WeakHashMap
import java.util.Objects
import thc.Language

/** Real native storage for immutable guest images, never a numeric identity token.
 * The weak-key table's values must not retain their original managed backing.
 * Integers do not root allocations; typed aliases and native transport views do.
 */
internal class NativeAddresses(private val env: TruffleLanguage.Env) {
    private val images = WeakHashMap<Any, NativeReadOnlyImage>()
    private val ranges = TreeMap<Long, WeakReference<NativeReadOnlyImage>>(java.lang.Long::compareUnsigned)
    private var closed = false
    private fun requireOpen() { if (closed) fault("Native address registry is closed") }
    private fun reap() {
        images.size // Drain dead backing keys before resolving their numerical ranges.
        ranges.entries.removeIf { it.value.get()?.hasSource != true }
    }

    @Synchronized @TruffleBoundary
    fun project(address: ManagedAddress): Long {
        requireOpen()
        if (!env.isNativeAccessAllowed) fault("Native address projection requires native access")
        val key = address.nativeImageKey()
            ?: fault("Numeric projection requires immutable byte storage; mutable managed and opaque addresses are unsupported")
        reap()
        val image = images[key] ?: NativeReadOnlyImage(key, address.nativeImageBytes()).also {
            images[key] = it
            ranges[it.base] = WeakReference(it)
        }
        image.requireLive()
        return image.base + address.cbitsOffset()
    }

    @Synchronized @TruffleBoundary
    fun recover(bits: Long): ManagedAddress {
        requireOpen()
        if (bits == 0L) return ManagedAddress.nullAddress()
        StablePointers.current(null).recoverToken(bits)?.let { return it }
        reap()
        val image = ranges.floorEntry(bits)?.value?.get()
        if (image != null) {
            val displacement = bits - image.base
            if (java.lang.Long.compareUnsigned(displacement, image.size) <= 0) {
                image.requireLive()
                image.source()?.let { return ManagedAddress.fromNativeImageSource(it, displacement) }
            }
        }
        // Keeping a bit pattern is safe. It grants no memory or FFI access.
        return ManagedAddress.unownedNumeric(bits)
    }

    /** Existing C calls receive the same native image once its address escaped.
     * The view strongly retains the original backing as well as the native owner.
     */
    @Synchronized @TruffleBoundary
    fun transport(address: ManagedAddress): NativeReadOnlyPointer? {
        requireOpen()
        val key = address.nativeImageKey() ?: return null
        val image = images[key] ?: return null
        image.requireLive()
        return NativeReadOnlyPointer(image, key)
    }

    @Synchronized @TruffleBoundary
    fun close() {
        if (closed) return
        closed = true
        ranges.values.forEach { it.get()?.close() }
        images.clear(); ranges.clear()
    }

    companion object {
        @JvmStatic fun current(node: Node?): NativeAddresses = Language.currentState(node).nativeAddresses
    }
}

internal class NativeReadOnlyImage(source: Any, bytes: ByteArray) {
    private class CloseArena(private val arena: Arena) : Runnable {
        @Volatile var closed = false
        @Synchronized override fun run() { if (!closed) { closed = true; arena.close() } }
    }
    private val source = WeakReference(source)
    val size: Long = bytes.size.toLong()
    private val arena = Arena.ofShared()
    // The inclusive guest range [base, base + size] must belong to this image
    // even if the next allocation is adjacent. Widen the JVM array length
    // before adding: Int.MAX_VALUE + 1 is still a valid positive Long size.
    private val segment = arena.allocate(size + 1L, 8)
    private val cleanup = CloseArena(arena)
    private val cleanable = cleaner.register(this, cleanup)
    val base: Long = segment.address()
    val hasSource: Boolean get() = source.get() != null && !cleanup.closed
    init { MemorySegment.copy(MemorySegment.ofArray(bytes), 0, segment, 0, size) }
    fun source(): Any? = source.get()
    fun requireLive() { if (cleanup.closed || !segment.scope().isAlive) fault("Native address owner is closed") }
    fun close() = cleanable.clean()
    fun isAlive(): Boolean = !cleanup.closed && segment.scope().isAlive
    companion object { private val cleaner = Cleaner.create() }
}

@ExportLibrary(InteropLibrary::class)
internal class NativeReadOnlyPointer(private val image: NativeReadOnlyImage,
    private val source: Any) : TruffleObject {
    @ExportMessage fun isPointer(): Boolean = image.isAlive()
    @ExportMessage fun asPointer(): Long {
        if (!image.isAlive()) throw UnsupportedMessageException.create()
        return try { image.base } finally { Reference.reachabilityFence(source) }
    }
}

/** Native copies owned by one synchronous provider call. Closing the arena is
 * a host operation and never re-enters a cancelled LLVM context to call free.
 * Callers check guest capacities, mutability and aliasing before copying. */
internal class NativeLimbScope : AutoCloseable {
    private val arena = Arena.ofConfined()

    fun allocate(bytes: Long): Pointer {
        require(bytes in 0..Int.MAX_VALUE.toLong() && bytes % Long.SIZE_BYTES == 0L) {
            "Invalid native limb byte count"
        }
        // Empty GMP inputs still receive an aligned non-null address.
        return Pointer(arena.allocate(maxOf(bytes, Long.SIZE_BYTES.toLong()), Long.SIZE_BYTES.toLong()), bytes)
    }

    fun snapshot(bytes: ByteArray, offset: Int, count: Int): Pointer {
        Objects.checkFromIndexSize(offset, count, bytes.size)
        return allocate(count.toLong()).also { it.copyFrom(bytes, offset, count) }
    }

    override fun close() = arena.close()

    /** Sulong transport tied to its arena lifetime, never a guest Addr# value. */
    @ExportLibrary(InteropLibrary::class)
    class Pointer internal constructor(private val segment: MemorySegment, private val capacity: Long) : TruffleObject {
        fun copyTo(destination: ByteArray, offset: Int, count: Int) {
            Objects.checkFromIndexSize(offset, count, destination.size)
            Objects.checkFromIndexSize(0L, count.toLong(), capacity)
            MemorySegment.copy(segment, 0, MemorySegment.ofArray(destination), offset.toLong(), count.toLong())
        }

        fun copyFrom(source: ByteArray, offset: Int, count: Int) {
            Objects.checkFromIndexSize(offset, count, source.size)
            Objects.checkFromIndexSize(0L, count.toLong(), capacity)
            MemorySegment.copy(MemorySegment.ofArray(source), offset.toLong(), segment, 0, count.toLong())
        }

        fun readWord(index: Long): Long {
            Objects.checkIndex(index, capacity / Long.SIZE_BYTES)
            return segment.get(ValueLayout.JAVA_LONG, index * Long.SIZE_BYTES)
        }

        @ExportMessage fun isPointer(): Boolean =
            segment.scope().isAlive && segment.isAccessibleBy(Thread.currentThread())

        @ExportMessage @Throws(UnsupportedMessageException::class)
        fun asPointer(): Long {
            if (!isPointer()) throw UnsupportedMessageException.create()
            return segment.address()
        }
    }
}
