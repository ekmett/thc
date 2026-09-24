// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

/** Prototype of the allocation owned by a ByteArray# and all of its Addr# views.
 * Pointer cells retain managed references; their bytes are deliberately not
 * synthetic process addresses. Raw byte access to a live pointer cell faults.
 */
internal class ManagedAllocation private constructor(
    private val bytes: ByteArray, private val writable: Boolean, private val pointerBytes: Int
) {
    init { if (pointerBytes != 4 && pointerBytes != 8) fault("Unsupported target pointer width") }
    // Ordinary byte arrays pay only for this nullable field, not a map.
    private var pointers: MutableMap<Int, ManagedAddress>? = null
    // Ordinary arrays need neither a map nor monitor traffic. Once an array
    // holds a pointer, all later accesses use the owning monitor.
    @Volatile private var pointerCapable = false
    val size: Long get() = bytes.size.toLong()

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

    fun readByte(offset: Long): Long {
        val start = range(offset, 1)
        if (!pointerCapable) return bytes[start].toLong() and 255L
        return synchronized(this) {
            if (intersectsPointer(start, 1)) fault("Cannot expose managed pointer bits as a byte")
            bytes[start].toLong() and 255L
        }
    }

    fun writeByte(offset: Long, value: Long) {
        mutable()
        val start = range(offset, 1)
        if (!pointerCapable) { bytes[start] = value.toByte(); return }
        synchronized(this) {
            invalidate(start, 1)
            bytes[start] = value.toByte()
        }
    }

    @Synchronized fun writeAddress(offset: Long, value: ManagedAddress) {
        mutable()
        val start = range(offset, pointerBytes.toLong())
        invalidate(start, pointerBytes)
        pointerCapable = true
        bytes.fill(0, start, start + pointerBytes)
        cells()[start] = value
    }

    @Synchronized fun readAddress(offset: Long): ManagedAddress {
        val start = range(offset, pointerBytes.toLong())
        return pointers?.get(start) ?: fault("No managed pointer cell at this address")
    }

    fun fill(offset: Long, count: Long, value: Long) {
        mutable()
        val start = range(offset, count)
        if (!pointerCapable) { bytes.fill(value.toByte(), start, start + count.toInt()); return }
        synchronized(this) {
            invalidate(start, count.toInt())
            bytes.fill(value.toByte(), start, start + count.toInt())
        }
    }

    /** Snapshot cells before memmove, including a copy within this allocation. */
    fun copyFrom(source: ManagedAllocation, sourceOffset: Long, destinationOffset: Long, count: Long) {
        if (!source.pointerCapable && !pointerCapable) {
            mutable()
            if (pointerBytes != source.pointerBytes) fault("Cannot copy between different target pointer widths")
            val from = source.range(sourceOffset, count)
            val to = range(destinationOffset, count)
            System.arraycopy(source.bytes, from, bytes, to, count.toInt())
            return
        }
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
            System.arraycopy(source.bytes, from, bytes, to, width)
            invalidate(to, width)
            if (copied.isNotEmpty()) {
                pointerCapable = true
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
