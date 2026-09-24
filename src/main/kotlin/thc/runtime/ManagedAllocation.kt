// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary

/** Allocation owned by a pinned ByteArray# and all of its Addr# views.
 * Pointer cells retain managed references; their bytes are deliberately not
 * synthetic process addresses. Raw byte access to a live pointer cell faults.
 */
internal class ManagedAllocation private constructor(
    private val bytes: ByteArray, private val writable: Boolean, private val pointerBytes: Int
) {
    init { if (pointerBytes != 4 && pointerBytes != 8) fault("Unsupported target pointer width") }
    // Pointer-free pinned arrays pay for the owner, not a per-cell map.
    private var pointers: MutableMap<Int, ManagedAddress>? = null
    private var exposedToNative = false
    // A raw array alias can outlive the call that obtained it. Never install
    // pointer cells after handing one out, even on another guest thread.
    private var exposedAsRawBytes = false
    // Ordinary unpinned arrays never enter this owner. Pinned accesses use its
    // monitor so pointer installation cannot race a scalar byte operation.
    @Volatile private var pointerCapable = false
    val size: Long get() = bytes.size.toLong()
    val addressWidth: Int get() = pointerBytes
    val isWritable: Boolean get() = writable

    /** A raw alias is permitted only before pointer cells are installed; once
     * returned it permanently rules out later pointer installation. */
    @Synchronized fun rawBytesIfPointerFree(): ByteArray {
        if (pointerCapable) fault("Pointer-bearing pinned array cannot be accessed as raw bytes")
        exposedAsRawBytes = true
        return bytes
    }

    /** Sulong holds a raw ByteBuffer view; it cannot track managed references. */
    @Synchronized fun exposeToNative(): ByteArray {
        if (pointerCapable) fault("Pointer-bearing pinned array cannot be passed to native bitcode")
        exposedToNative = true
        return bytes
    }

    private fun cells(): MutableMap<Int, ManagedAddress> = pointers
        ?: mutableMapOf<Int, ManagedAddress>().also { pointers = it }

    private fun range(offset: Long, count: Long): Int {
        if (offset < 0 || count < 0 || offset > size || count > size - offset)
            fault("Managed allocation range outside its backing storage")
        return offset.toInt()
    }

    private fun mutable() {
        if (!writable) fault("Cannot write through an immutable managed allocation")
    }

    private fun intersectsPointer(offset: Int, count: Int): Boolean = count > 0 &&
        pointers?.keys?.any { it.toLong() < offset.toLong() + count && it.toLong() + pointerBytes > offset } == true

    private fun requireWholePointerOverlaps(offset: Int, count: Int) {
        pointers?.keys?.forEach { start ->
            val end = start.toLong() + pointerBytes
            val writeEnd = offset.toLong() + count
            if (start < writeEnd && end > offset && (start < offset || end > writeEnd))
                fault("Partial overwrite of a managed pointer cell")
        }
    }

    private fun invalidate(offset: Int, count: Int) {
        if (count == 0) return
        requireWholePointerOverlaps(offset, count)
        pointers?.keys?.removeIf { it.toLong() < offset.toLong() + count && it.toLong() + pointerBytes > offset }
        if (pointers?.isEmpty() == true) pointers = null
    }

    @Synchronized fun readByte(offset: Long): Long {
        val start = range(offset, 1)
        if (pointerCapable && intersectsPointer(start, 1)) fault("Cannot expose managed pointer bits as a byte")
        return bytes[start].toLong() and 255L
    }

    @Synchronized fun writeByte(offset: Long, value: Long) {
        mutable()
        val start = range(offset, 1)
        if (pointerCapable) invalidate(start, 1)
        bytes[start] = value.toByte()
    }

    @Synchronized fun writeAddressByteOffset(offset: Long, value: ManagedAddress) {
        mutable()
        if (exposedToNative || exposedAsRawBytes)
            fault("Cannot store a managed pointer in a raw-exposed array")
        val start = range(offset, pointerBytes.toLong())
        invalidate(start, pointerBytes)
        pointerCapable = true
        bytes.fill(0, start, start + pointerBytes)
        cells()[start] = value
    }

    @Synchronized fun readAddressByteOffset(offset: Long): ManagedAddress {
        val start = range(offset, pointerBytes.toLong())
        return pointers?.get(start) ?: fault("No managed pointer cell at this address")
    }

    /** Internal scalar array operations keep the backing private and inspect
     * exactly their element range while holding the pointer-cell monitor. */
    @Synchronized fun <T> accessElement(index: Long, width: Int, writable: Boolean,
        action: (ByteArray) -> T): T {
        if (width <= 0 || index < 0 || index > Long.MAX_VALUE / width)
            fault("Managed allocation element outside its backing storage")
        val start = range(index * width, width.toLong())
        if (writable) {
            mutable()
            if (pointerCapable) invalidate(start, width)
        } else if (intersectsPointer(start, width))
            fault("Scalar read overlaps a managed pointer cell")
        return action(bytes)
    }

    @Synchronized fun copyBytesOut(offset: Long, count: Long): ByteArray {
        val start = range(offset, count)
        if (intersectsPointer(start, count.toInt()))
            fault("Raw copy overlaps a managed pointer cell")
        return bytes.copyOfRange(start, start + count.toInt())
    }

    @Synchronized fun copyBytesIn(source: ByteArray, sourceOffset: Int,
        destinationOffset: Long, count: Long) {
        mutable()
        if (sourceOffset < 0 || count < 0 || sourceOffset.toLong() > source.size ||
            count > source.size.toLong() - sourceOffset)
            fault("Raw copy source outside its backing storage")
        val start = range(destinationOffset, count)
        if (pointerCapable) invalidate(start, count.toInt())
        System.arraycopy(source, sourceOffset, bytes, start, count.toInt())
    }

    @Synchronized fun fill(offset: Long, count: Long, value: Long) {
        mutable()
        val start = range(offset, count)
        if (pointerCapable) invalidate(start, count.toInt())
        bytes.fill(value.toByte(), start, start + count.toInt())
    }

    /** Snapshot cells before memmove, including a copy within this allocation. */
    fun copyFrom(source: ManagedAllocation, sourceOffset: Long, destinationOffset: Long, count: Long) {
        fun copyLocked() {
            mutable()
            if (pointerBytes != source.pointerBytes) fault("Cannot copy between different target pointer widths")
            val from = source.range(sourceOffset, count)
            val to = range(destinationOffset, count)
            val width = count.toInt()
            source.requireWholePointerOverlaps(from, width)
            requireWholePointerOverlaps(to, width)
            val copied = source.pointers?.filterKeys {
                it >= from && it.toLong() + pointerBytes <= from.toLong() + width
            }?.mapKeys { (start, _) -> to + start - from } ?: emptyMap()
            if (copied.isNotEmpty() && (exposedToNative || exposedAsRawBytes))
                fault("Cannot copy managed pointers into a raw-exposed array")
            if (copied.isNotEmpty()) pointerCapable = true
            System.arraycopy(source.bytes, from, bytes, to, width)
            invalidate(to, width)
            if (copied.isNotEmpty()) {
                cells().putAll(copied)
            }
        }
        val fromId = System.identityHashCode(source)
        val toId = System.identityHashCode(this)
        when {
            source === this -> synchronized(this) { copyLocked() }
            fromId < toId -> synchronized(source) { synchronized(this) { copyLocked() } }
            fromId > toId -> synchronized(this) { synchronized(source) { copyLocked() } }
            else -> synchronized(COPY_TIE_LOCK) {
                synchronized(source) { synchronized(this) { copyLocked() } }
            }
        }
    }

    /** GHC forbids using the old MutableByteArray# after resize. */
    @Synchronized fun resized(newSize: Long): ManagedAllocation {
        mutable()
        if (newSize < 0 || newSize > Int.MAX_VALUE.toLong()) fault("Managed allocation size outside JVM domain")
        if (newSize == size) return this
        // Ordinary pinned byte arrays still use the typed, pointer-free copy.
        // Keep pointer-map collection code out of partial evaluation even when
        // another byte-array branch is the one exercised by a compiled guest.
        if (!pointerCapable) return ManagedAllocation(bytes.copyOf(newSize.toInt()), true, pointerBytes)
        return resizeWithPointerCells(newSize)
    }

    @TruffleBoundary
    private fun resizeWithPointerCells(newSize: Long): ManagedAllocation {
        if (newSize < size) pointers?.keys?.forEach { start ->
            if (start < newSize && start.toLong() + pointerBytes > newSize)
                fault("Cannot truncate a managed pointer cell")
        }
        return ManagedAllocation(bytes.copyOf(newSize.toInt()), true, pointerBytes).also { replacement ->
            pointers?.filterKeys { it.toLong() + pointerBytes <= newSize }?.takeIf { it.isNotEmpty() }
                ?.let { replacement.cells().putAll(it); replacement.pointerCapable = true }
        }
    }

    companion object {
        private val COPY_TIE_LOCK = Any()
        fun mutable(size: Long, pointerBytes: Int): ManagedAllocation {
            if (size < 0 || size > Int.MAX_VALUE.toLong()) fault("Managed allocation size outside JVM domain")
            return ManagedAllocation(ByteArray(size.toInt()), true, pointerBytes)
        }
        fun immutable(bytes: ByteArray, pointerBytes: Int): ManagedAllocation =
            ManagedAllocation(bytes.copyOf(), false, pointerBytes)
    }
}
