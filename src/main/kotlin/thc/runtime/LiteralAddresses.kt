// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
import com.oracle.truffle.api.frame.VirtualFrame
import java.lang.ref.Reference
import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference
import java.nio.ByteOrder

/** A managed Addr#, never a native pointer. Non-null addresses have exactly one
 * final backing reference. Literal contents are immutable compilation constants;
 * mutable contents are ordinary array elements, even after unsafeFreezeByteArray#.
 * Each derived address strongly retains its allocation without exposing it. */
internal class ManagedAddress private constructor(
    @field:CompilationFinal(dimensions = 1) private val literalBytes: ByteArray?,
    private val mutableBytes: ByteArray?,
    private val offset: Long,
    private val owner: ManagedAllocation? = null,
    private val stable: StablePointers.Handle? = null
) {
    internal fun stableHandle(): StablePointers.Handle? = stable
    private fun requireBytes() { if (stable != null) fault("Opaque StablePtr# is not byte-addressable") }
    // Package-internal views for original C bitcode; callers never obtain a
    // process pointer and the byte storage is not copied or replaced.
    internal fun rawBacking(): ByteArray { requireBytes(); return owner?.rawBytesIfPointerFree()
        ?: literalBytes ?: mutableBytes ?: fault("Null Addr# has no backing storage")
    }
    internal fun cbitsBacking(): ByteArray { requireBytes(); return owner?.exposeToNative() ?: rawBacking() }
    internal fun cbitsWritable(): Boolean { requireBytes(); return owner?.isWritable ?: (mutableBytes != null) }
    internal fun cbitsOffset(): Long { size(); return offset }

    private fun size(): Long { requireBytes(); return owner?.size ?: (literalBytes ?: mutableBytes)?.size?.toLong()
        ?: fault("Null Addr# has no backing storage")
    }

    /** GHC pointer equality compares allocation identity and byte offset. */
    fun sameLocation(other: ManagedAddress): Boolean =
        if (stable != null) {
            val registry = StablePointers.current(null)
            registry.validate(this)
            if (other.stable != null) registry.equal(this, other) else false
        } else if (other.stable != null) {
            StablePointers.current(null).validate(other)
            false
        }
        else offset == other.offset && when {
            this === NULL || other === NULL -> this === other
            owner != null -> owner === other.owner
            literalBytes != null -> literalBytes === other.literalBytes
            else -> mutableBytes != null && mutableBytes === other.mutableBytes
        }

    /** Weak allocation/offset index. Aliases keep entries alive without exposing
     * an owner; values must not retain addresses into their indexed allocation.
     * The owning service serializes access. */
    internal class WeakLocations<V> {
        private class Key(owner: ManagedAllocation, val offset: Long, queue: ReferenceQueue<ManagedAllocation>?) :
            WeakReference<ManagedAllocation>(owner, queue) {
            private val hash = 31 * System.identityHashCode(owner) + offset.hashCode()
            override fun hashCode() = hash
            override fun equals(other: Any?): Boolean = this === other ||
                other is Key && offset == other.offset && get()?.let { it === other.get() } == true
        }
        private val queue = ReferenceQueue<ManagedAllocation>()
        private val entries = HashMap<Key, V>()
        private fun reap() {
            while (true) entries.remove(queue.poll() ?: return)
        }
        val size: Int get() { reap(); return entries.size }
        operator fun get(address: ManagedAddress): V? {
            reap()
            val owner = address.owner ?: return null
            return try { entries[Key(owner, address.offset, null)] }
                finally { Reference.reachabilityFence(address) }
        }
        operator fun set(address: ManagedAddress, value: V) {
            reap()
            val owner = address.owner ?: fault("Weak location registration requires an allocation-owned Addr#")
            try { entries[Key(owner, address.offset, queue)] = value }
            finally { Reference.reachabilityFence(address) }
        }
        fun clear() { entries.clear(); reap() }
    }

    /** Only offsets within one allocation have a portable managed ordering.
     * Comparing unrelated native pointer values would invent host addresses. */
    fun compareWithinAllocation(other: ManagedAddress): Int {
        if (stable != null || other.stable != null) fault("Opaque StablePtr# has no address ordering")
        if (this === NULL || other === NULL) {
            if (this === other) return 0
            fault("Ordered Addr# comparison requires the same managed allocation")
        }
        val shared = when {
            owner != null -> owner === other.owner
            literalBytes != null -> literalBytes === other.literalBytes
            else -> mutableBytes != null && mutableBytes === other.mutableBytes
        }
        if (!shared) fault("Ordered Addr# comparison requires the same managed allocation")
        return offset.compareTo(other.offset)
    }

    /** Like pointer arithmetic within this allocation, including its one-past address. */
    fun plus(displacement: Long): ManagedAddress {
        requireBytes()
        if (this === NULL) {
            if (displacement == 0L) return this
            fault("Cannot offset null Addr#")
        }
        // Check before adding so even Long.MIN/MAX_VALUE cannot wrap into range.
        if (displacement < -offset || displacement > size() - offset)
            fault("Managed Addr# offset outside its backing storage")
        return if (displacement == 0L) this else ManagedAddress(literalBytes, mutableBytes, offset + displacement, owner)
    }

    private fun index(displacement: Long): Int {
        if (displacement < -offset || displacement >= size() - offset)
            fault("Managed Addr# access outside its backing storage")
        return (offset + displacement).toInt()
    }

    /** indexCharOffAddr# reads an unsigned eight-bit byte, not a UTF-8 code point. */
    fun indexChar(displacement: Long): Long = readWord8(displacement)

    /** The polyglot text ABI reads a checked NUL-terminated UTF-8 region. */
    @TruffleBoundary
    fun utf8(): String {
        val bytes = rawBacking()
        val start = offset.toInt()
        var end = start
        while (end < bytes.size && bytes[end] != 0.toByte()) end++
        if (end == bytes.size) fault("Unterminated polyglot UTF-8 address")
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes, start, end - start)).toString()
        } catch (_: java.nio.charset.CharacterCodingException) {
            fault("Invalid UTF-8 at the polyglot boundary")
        }
    }

    /** Both backing variants use byte offsets and return zero-extended Word8#. */
    fun readWord8(displacement: Long): Long {
        val index = index(displacement)
        owner?.let { return it.readByte(index.toLong()) }
        // Keep the immutable and mutable loads distinct: only the former may fold.
        val literal = literalBytes
        return (if (literal != null) literal[index] else mutableBytes!![index]).toLong() and 0xffL
    }

    /** The caller evaluates State# before reaching storage. Invalid writes have
     * no effect; the value contributes only its low eight bits, like writeWord8Array#. */
    fun writeWord8(displacement: Long, value: Long) {
        owner?.let { it.writeByte(index(displacement).toLong(), value); return }
        val bytes = mutableBytes ?: fault("Cannot write through an immutable literal Addr#")
        val index = index(displacement)
        bytes[index] = value.toByte()
    }

    /** Native-endian scalar stores validate the full element before mutation.
     * Pointer-bearing allocations retain references except for a complete
     * overwrite of a pointer cell. */
    fun writeNativeScalar(elementOffset: Long, width: Int, value: Long) {
        if (width != 2 && width != 4 && width != 8) fault("Unsupported managed Addr# scalar width")
        if (elementOffset < Long.MIN_VALUE / width || elementOffset > Long.MAX_VALUE / width)
            fault("Managed Addr# element offset overflow")
        val displacement = elementOffset * width
        requireRange(displacement, width.toLong(), writable = true)
        val little = ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN
        owner?.let { it.writeNativeScalarByteOffset(offset + displacement, width, value, little); return }
        val bytes = mutableBytes ?: fault("Cannot write through an immutable literal Addr#")
        val start = (offset + displacement).toInt()
        for (index in 0 until width) {
            val shift = (if (little) index else width - 1 - index) * 8
            bytes[start + index] = (value ushr shift).toByte()
        }
    }

    fun writeWord16(elementOffset: Long, value: Long) = writeNativeScalar(elementOffset, 2, value)

    /** Validate a complete byte region before any effect. An empty region may
     * start one past the allocation; an immutable destination is never writable. */
    fun requireRange(displacement: Long, count: Long, writable: Boolean = false) {
        if (writable && owner == null && mutableBytes == null)
            fault("Cannot write through an immutable literal Addr#")
        if (writable && owner != null && !owner.isWritable)
            fault("Cannot write through an immutable managed allocation")
        if (count < 0 || displacement < -offset || displacement > size() - offset)
            fault("Managed Addr# range outside its backing storage")
        val start = offset + displacement
        if (count > size() - start) fault("Managed Addr# range outside its backing storage")
    }

    /** Exact overlap of two checked byte regions, including independently made
     * addresses of the same array. Adjacent and empty regions do not overlap. */
    fun overlaps(displacement: Long, count: Long, other: ManagedAddress,
        otherDisplacement: Long, otherCount: Long): Boolean {
        requireRange(displacement, count)
        other.requireRange(otherDisplacement, otherCount)
        if (count == 0L || otherCount == 0L) return false
        val shared = when {
            owner != null && other.owner != null -> owner === other.owner
            owner != null -> other.mutableBytes?.let(owner::ownsStorage) == true
            other.owner != null -> mutableBytes?.let(other.owner::ownsStorage) == true
            literalBytes != null -> literalBytes === other.literalBytes || literalBytes === other.mutableBytes
            else -> mutableBytes != null && (mutableBytes === other.mutableBytes || mutableBytes === other.literalBytes)
        }
        if (!shared) return false
        val start = offset + displacement
        val otherStart = other.offset + otherDisplacement
        return start < otherStart + otherCount && otherStart < start + count
    }

    /** copyAddrToAddrNonOverlapping# counts bytes. Validate both complete
     * regions and aliasing before changing storage; owner-to-owner copies keep
     * managed pointer references instead of fabricating their byte values. */
    fun copyNonOverlappingTo(destination: ManagedAddress, count: Long) {
        requireRange(0, count)
        destination.requireRange(0, count, writable = true)
        if (overlaps(0, count, destination, 0, count))
            fault("copyAddrToAddrNonOverlapping# requires disjoint regions")
        if (count == 0L) return
        val sourceOwner = owner
        val destinationOwner = destination.owner
        when {
            sourceOwner != null && destinationOwner != null ->
                destinationOwner.copyFrom(sourceOwner, offset, destination.offset, count)
            sourceOwner != null -> {
                val source = sourceOwner.copyBytesOut(offset, count)
                System.arraycopy(source, 0, destination.mutableBytes!!, destination.offset.toInt(), count.toInt())
            }
            destinationOwner != null -> destinationOwner.copyBytesIn(
                literalBytes ?: mutableBytes ?: fault("Null Addr# has no backing storage"),
                offset.toInt(), destination.offset, count)
            else -> System.arraycopy(
                literalBytes ?: mutableBytes ?: fault("Null Addr# has no backing storage"), offset.toInt(),
                destination.mutableBytes!!, destination.offset.toInt(), count.toInt())
        }
    }

    @TruffleBoundary
    override fun toString(): String = if (stable != null) "Addr#(opaque StablePtr)" else if (this === NULL) "Addr#(null)"
        else "Addr#(${if (literalBytes != null) "literal" else "managed"}+$offset)"

    /** Pointer cells contain references, not process address bits. */
    fun readAddressElementIndex(elementOffset: Long): ManagedAddress {
        val allocation = owner ?: fault("Addr# has no allocation-owned pointer cells")
        val width = allocation.addressWidth.toLong()
        if (elementOffset < Long.MIN_VALUE / width || elementOffset > Long.MAX_VALUE / width)
            fault("Managed Addr# element offset overflow")
        val displacement = elementOffset * width
        requireRange(displacement, width)
        return allocation.readAddressByteOffset(offset + displacement)
    }

    fun writeAddressElementIndex(elementOffset: Long, value: ManagedAddress) {
        val allocation = owner ?: fault("Addr# has no allocation-owned pointer cells")
        val width = allocation.addressWidth.toLong()
        if (elementOffset < Long.MIN_VALUE / width || elementOffset > Long.MAX_VALUE / width)
            fault("Managed Addr# element offset overflow")
        val displacement = elementOffset * width
        requireRange(displacement, width, writable = true)
        allocation.writeAddressByteOffset(offset + displacement, value)
    }

    /** Commit an allocation image to this byte-addressed view in one checked copy.
     * The owner validates all pointer-cell overlaps and raw aliases before mutation. */
    internal fun copyFromAllocationBytes(source: ManagedAllocation, sourceByteOffset: Long, countBytes: Long) {
        val allocation = owner ?: fault("Addr# has no allocation-owned pointer cells")
        requireRange(0, countBytes, writable = true)
        allocation.copyFrom(source, sourceByteOffset, offset, countBytes)
    }

    companion object {
        private val NULL = ManagedAddress(null, null, 0L)
        fun nullAddress(): ManagedAddress = NULL
        internal fun fromStableHandle(handle: StablePointers.Handle): ManagedAddress =
            ManagedAddress(null, null, 0L, stable = handle)

        /** Logical pinning means stable managed backing and a strong lifetime,
         * not physical pinning or a process address. Do not copy: views must alias. */
        fun fromByteArray(bytes: ByteArray): ManagedAddress = ManagedAddress(null, bytes, 0L)
        fun fromAllocation(allocation: ManagedAllocation): ManagedAddress = ManagedAddress(null, null, 0L, allocation)
        fun fromGuestByteArray(value: Any?): ManagedAddress = when (value) {
            is ManagedAllocation -> fromAllocation(value)
            is ByteArray -> fromByteArray(value)
            else -> fault("Expected a managed ByteArray#")
        }

        /** GHC's LitString stores raw bytes; the static allocation adds a final NUL. */
        @TruffleBoundary
        fun fromHex(hex: String): ManagedAddress {
            if (hex.length % 2 != 0) throw RuntimeFault("Malformed string-bytes literal")
            val bytes = ByteArray(hex.length / 2 + 1)
            for (index in 0 until bytes.size - 1) {
                val high = digit(hex[index * 2])
                val low = digit(hex[index * 2 + 1])
                bytes[index] = ((high shl 4) or low).toByte()
            }
            return ManagedAddress(bytes, null, 0L)
        }

        private fun digit(char: Char): Int = when (char) {
            in '0'..'9' -> char - '0'
            in 'a'..'f' -> char - 'a' + 10
            in 'A'..'F' -> char - 'A' + 10
            else -> throw RuntimeFault("Malformed string-bytes literal")
        }
    }
}

internal class PlusManagedAddress(@field:Child private var address: Expr,
                                  @field:Child private var displacement: Expr) : Expr() {
    override fun execute(frame: VirtualFrame): ManagedAddress {
        val value = address.executeRequiredAddress(frame)
        return value.plus(displacement.executeRequiredLong(frame))
    }
    override fun executeAddress(frame: VirtualFrame): ManagedAddress = execute(frame)
}

internal class IndexManagedByte(private val signed: Boolean, @field:Child private var address: Expr,
                                @field:Child private var displacement: Expr) : Expr() {
    override fun execute(frame: VirtualFrame): Any = executeLong(frame)
    override fun executeLong(frame: VirtualFrame): Long {
        val value = address.executeRequiredAddress(frame)
        val byte = value.readWord8(displacement.executeRequiredLong(frame))
        return if (signed) byte.toByte().toLong() else byte
    }
}

internal class IndexManagedScalarAddress(private val operation: ManagedAddressRead,
    @field:Child private var address: Expr, @field:Child private var element: Expr) : Expr() {
    override fun execute(frame: VirtualFrame): Any = executeLong(frame)
    override fun executeLong(frame: VirtualFrame): Long = operation.read(
        address.executeRequiredAddress(frame), element.executeRequiredLong(frame))
}
