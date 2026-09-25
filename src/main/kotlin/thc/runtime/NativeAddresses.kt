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
import java.lang.ref.Cleaner
import java.lang.ref.Reference
import java.lang.ref.WeakReference
import java.util.TreeMap
import java.util.WeakHashMap
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
    private val arena = Arena.ofShared()
    private val segment = arena.allocate(maxOf(1L, bytes.size.toLong()), 8)
    private val cleanup = CloseArena(arena)
    private val cleanable = cleaner.register(this, cleanup)
    val base: Long = segment.address()
    val size: Long = bytes.size.toLong()
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
