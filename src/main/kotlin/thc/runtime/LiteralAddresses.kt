// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
import com.oracle.truffle.api.frame.VirtualFrame
import thc.Language
import java.lang.ref.Reference
import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference
import java.nio.ByteOrder
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout

/** An Addr# carrier with managed storage or an unowned numeric bit pattern.
 * Immutable storage may acquire a real, context-owned native image. Storage-backed
 * addresses have exactly one final backing reference. Literal contents are immutable compilation constants;
 * mutable contents are ordinary array elements, even after unsafeFreezeByteArray#.
 * Each derived address strongly retains its allocation without exposing it. */
internal class ManagedAddress private constructor(
    @field:CompilationFinal(dimensions = 1) private val literalBytes: ByteArray?,
    private val mutableBytes: ByteArray?,
    private val offset: Long,
    private val owner: ManagedAllocation? = null,
    private val stable: StablePointers.Handle? = null,
    private val numeric: Long? = null,
    private val finalizer: CFinalizerFunction? = null,
    private val native: ManagedNativeAllocations.Owner? = null,
    private val capabilities: GuestThreads? = null
) {
    /** This is an RTS data label, not a projection of a JVM or native pointer. */
    internal fun readCapabilitiesWord32(elementOffset: Long, width: Int): Long? {
        val threads = capabilities ?: return null
        if (Language.currentState(null).threads !== threads)
            fault("RTS data label belongs to another THC context")
        if (width != 4 || elementOffset != 0L)
            fault("enabled_capabilities permits only an aligned Word32 read at offset zero")
        return maxOf(1L, threads.capabilityCount()).also {
            if (it > 0xffff_ffffL) fault("enabled_capabilities exceeds Word32")
        }
    }
    internal fun nativeAllocation(): ManagedNativeAllocations.Owner? = native
    internal fun isNativeBase(): Boolean = native != null && offset == 0L
    /** Keep native storage alive across a complete operation, including calls
     * whose native pointer outlives an individual checked byte access. */
    internal fun <T> withNativeBorrow(body: () -> T): T =
        if (native == null) body() else native.borrow().use { body() }
    internal fun <T> withNativeSegment(body: (MemorySegment) -> T): T =
        (native ?: fault("Address has no owned native allocation")).access { body(it.asSlice(offset)) }
    internal fun <T> withNativeBorrows(other: ManagedAddress, body: () -> T): T {
        if (native == null) return other.withNativeBorrow(body)
        if (other.native == null || native === other.native) return withNativeBorrow(body)
        // Ordered acquisition also prevents two copies from deadlocking behind
        // queued frees while each already holds the other's source allocation.
        fun forward() = withNativeBorrow { other.withNativeBorrow(body) }
        return when (Integer.compareUnsigned(System.identityHashCode(native), System.identityHashCode(other.native))) {
            -1 -> forward()
            1 -> other.withNativeBorrow { withNativeBorrow(body) }
            else -> synchronized(NATIVE_BORROW_TIE) { forward() }
        }
    }
    internal fun stableHandle(): StablePointers.Handle? = stable
    internal fun finalizerFunction(): CFinalizerFunction? = finalizer
    private fun requireBytes() {
        if (capabilities != null) fault("RTS data label is not byte-addressable")
        if (stable != null) fault("Opaque StablePtr# is not byte-addressable")
        if (finalizer != null) fault("Opaque C function label is not byte-addressable")
        if (numeric != null) fault("Unowned numeric Addr# is not byte-addressable")
    }
    /** Only immutable storage can be materialized without breaking existing aliases. */
    internal fun nativeImageKey(): Any? = literalBytes ?: owner?.takeIf { !it.isWritable }
    internal fun nativeImageBytes(): ByteArray = owner?.takeIf { !it.isWritable }?.let { it.copyBytesOut(0, it.size) }
        ?: literalBytes?.copyOf() ?: fault("Native image requires immutable byte storage")
    fun toNativeBits(): Long {
        if (capabilities != null) fault("RTS data label has no numeric guest address")
        if (finalizer != null) fault("Opaque C function label has no numeric guest address")
        native?.let { return it.access { segment -> segment.address() + offset } }
        return if (this === NULL) 0L else numeric ?: NativeAddresses.current(null).project(this)
    }
    // Mutable views preserve aliases. Immutable sources return snapshots so a
    // writable JVM array cannot escape and diverge from their native image.
    internal fun rawBacking(): ByteArray { requireBytes()
        if (native != null) fault("Owned native storage cannot be exposed as a JVM byte array")
        return owner?.rawBytesIfPointerFree()
        ?: literalBytes?.copyOf() ?: mutableBytes ?: fault("Null Addr# has no backing storage")
    }
    internal fun cbitsBacking(): ByteArray { requireBytes(); return owner?.exposeToNative() ?: rawBacking() }
    internal fun cbitsWritable(): Boolean { requireBytes(); native?.requireLive(); return native != null || (owner?.isWritable ?: (mutableBytes != null)) }
    internal fun cbitsOffset(): Long { size(); return offset }
    internal fun cbitsOwner(): ManagedAllocation? = owner
    internal fun cbitsSize(): Long = size()
    internal fun availableBytes(): Long { requireRange(0, 0); return size() - offset }

    /** Validate byte-only transport without exposing allocation storage. */
    internal fun requireByteRegion(count: Long, writable: Boolean = false) {
        requireRange(0, count, writable)
        owner?.requireByteRegion(offset, count, writable)
    }

    private fun size(): Long { requireBytes(); native?.let { it.requireLive(); return it.size }
        return owner?.size ?: (literalBytes ?: mutableBytes)?.size?.toLong()
        ?: fault("Null Addr# has no backing storage")
    }

    /** GHC pointer equality compares allocation identity and byte offset. */
    fun sameLocation(other: ManagedAddress): Boolean {
        if (capabilities != null || other.capabilities != null) {
            val current = Language.currentState(null).threads
            if (capabilities != null && capabilities !== current ||
                other.capabilities != null && other.capabilities !== current)
                fault("RTS data label belongs to another THC context")
            return capabilities != null && capabilities === other.capabilities
        }
        native?.requireLive(); other.native?.requireLive()
        return if (finalizer != null || other.finalizer != null) {
            val provider = thc.Language.currentState(null).cbits()
            finalizer?.requireOwner(provider)
            other.finalizer?.requireOwner(provider)
            finalizer != null && finalizer === other.finalizer
        } else if (stable != null) {
            val registry = StablePointers.current(null)
            registry.validate(this)
            if (other.stable != null) registry.equal(this, other) else false
        } else if (other.stable != null) {
            StablePointers.current(null).validate(other)
            false
        }
        else if (numeric != null || other.numeric != null) toNativeBits() == other.toNativeBits()
        else offset == other.offset && when {
            this === NULL || other === NULL -> this === other
            native != null || other.native != null -> native != null && native === other.native
            owner != null && other.owner != null -> owner === other.owner
            owner != null -> other.mutableBytes?.let(owner::ownsStorage) == true
            other.owner != null -> mutableBytes?.let(other.owner::ownsStorage) == true
            literalBytes != null -> literalBytes === other.literalBytes
            else -> mutableBytes != null && mutableBytes === other.mutableBytes
        }
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
        if (capabilities != null || other.capabilities != null)
            fault("RTS data label has no address ordering")
        native?.requireLive(); other.native?.requireLive()
        if (finalizer != null || other.finalizer != null) fault("Opaque C function label has no address ordering")
        if (stable != null || other.stable != null) fault("Opaque StablePtr# has no address ordering")
        if (numeric != null || other.numeric != null)
            return java.lang.Long.compareUnsigned(toNativeBits(), other.toNativeBits())
        if (this === NULL || other === NULL) {
            if (this === other) return 0
            fault("Ordered Addr# comparison requires the same managed allocation")
        }
        val shared = when {
            native != null || other.native != null -> native != null && native === other.native
            owner != null -> owner === other.owner
            literalBytes != null -> literalBytes === other.literalBytes
            else -> mutableBytes != null && mutableBytes === other.mutableBytes
        }
        if (!shared) fault("Ordered Addr# comparison requires the same managed allocation")
        return offset.compareTo(other.offset)
    }

    /** Arithmetic retains allocation identity even outside its accessible range.
     * Original bytestring unpacking uses a before-start sentinel; only a memory
     * access must lie inside storage. A managed origin must not overflow. */
    fun plus(displacement: Long): ManagedAddress {
        if (capabilities != null) {
            readCapabilitiesWord32(0, 4)
            if (displacement != 0L) fault("RTS data label cannot be offset")
            return this
        }
        if (numeric != null) return NativeAddresses.current(null).recover(numeric + displacement)
        requireBytes()
        if (this === NULL) {
            if (displacement == 0L) return this
            fault("Cannot offset null Addr#")
        }
        size() // Preserve native lifetime/context validation, including plus zero.
        return if (displacement == 0L) this else ManagedAddress(literalBytes, mutableBytes,
            displacedOffset(displacement), owner, native = native)
    }

    private fun displacedOffset(displacement: Long): Long = try {
        Math.addExact(offset, displacement)
    } catch (_: ArithmeticException) {
        fault("Managed Addr# offset overflow")
    }

    /** Relative pointers need no JVM address projection. Native/numeric pointers
     * retain machine arithmetic; managed storage uses its allocation-local origin. */
    fun difference(other: ManagedAddress): Long {
        native?.requireLive(); other.native?.requireLive()
        if (numeric != null || other.numeric != null || native != null && other.native != null)
            return toNativeBits() - other.toNativeBits()
        if (this === NULL && other === NULL) return 0L
        requireBytes(); other.requireBytes()
        val shared = when {
            owner != null && other.owner != null -> owner === other.owner
            owner != null -> other.mutableBytes?.let(owner::ownsStorage) == true
            other.owner != null -> mutableBytes?.let(other.owner::ownsStorage) == true
            literalBytes != null -> literalBytes === other.literalBytes || literalBytes === other.mutableBytes
            else -> mutableBytes != null && (mutableBytes === other.mutableBytes || mutableBytes === other.literalBytes)
        }
        if (!shared) fault("minusAddr# requires related managed pointers or numeric/native addresses")
        return offset - other.offset
    }

    fun remainder(divisor: Long): Long {
        if (divisor == 0L) fault("remAddr# divisor is zero")
        // GHC 9.14.1 lowers AddrRemOp to unsigned machine-word remainder.
        val bits = if (this === NULL || numeric != null || native != null) toNativeBits()
            else { requireBytes(); size(); offset }
        return java.lang.Long.remainderUnsigned(bits, divisor)
    }

    private fun index(displacement: Long): Int {
        requireRange(displacement, 1)
        return (offset + displacement).toInt()
    }

    /** indexCharOffAddr# reads an unsigned eight-bit byte, not a UTF-8 code point. */
    fun indexChar(displacement: Long): Long = readWord8(displacement)

    /** The polyglot text ABI reads a checked NUL-terminated UTF-8 region. */
    @TruffleBoundary
    fun utf8(): String {
        requireRange(0, 1)
        val bytes = rawBacking()
        val start = offset.toInt()
        val limit = size().toInt()
        if (start < 0 || start >= limit) fault("UTF-8 address outside its backing storage")
        var end = start
        while (end < limit && bytes[end] != 0.toByte()) end++
        if (end == limit) fault("Unterminated polyglot UTF-8 address")
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
        native?.let { allocation -> return allocation.access { segment ->
            requireRange(displacement, 1)
            segment.get(ValueLayout.JAVA_BYTE, offset + displacement).toLong() and 255L
        } }
        val index = index(displacement)
        owner?.let { return it.readByte(index.toLong()) }
        // Keep the immutable and mutable loads distinct: only the former may fold.
        val literal = literalBytes
        return (if (literal != null) literal[index] else mutableBytes!![index]).toLong() and 0xffL
    }

    /** Original libc strlen over a live, bounded byte address. The native
     * allocation cannot be freed and an owner cannot shrink while scanning. */
    @TruffleBoundary
    fun cStringLength(): Long {
        native?.let { allocation -> return allocation.access { segment ->
            requireRange(0, 0)
            val limit = segment.byteSize() - offset
            var length = 0L
            while (length < limit) {
                if (segment.get(ValueLayout.JAVA_BYTE, offset + length) == 0.toByte()) return@access length
                length++
            }
            fault("Unterminated original C string inside managed Addr#")
        } }
        val scan = scan@{
            val limit = availableBytes()
            var length = 0L
            while (length < limit) {
                if (readWord8(length) == 0L) return@scan length
                length++
            }
            fault("Unterminated original C string inside managed Addr#")
        }
        val allocation = owner
        return if (allocation == null) scan() else synchronized(allocation) { scan() }
    }

    /** The caller evaluates State# before reaching storage. Invalid writes have
     * no effect; the value contributes only its low eight bits, like writeWord8Array#. */
    fun writeWord8(displacement: Long, value: Long) {
        native?.let { allocation -> allocation.access { segment ->
            requireRange(displacement, 1, writable = true)
            segment.set(ValueLayout.JAVA_BYTE, offset + displacement, value.toByte())
        }; return }
        owner?.let { it.writeByte(index(displacement).toLong(), value); return }
        val bytes = mutableBytes ?: fault("Cannot write through an immutable literal Addr#")
        val index = index(displacement)
        bytes[index] = value.toByte()
    }

    /** Native-endian scalar stores validate the full element before mutation.
     * Pointer-bearing allocations retain references except for a complete
     * overwrite of a pointer cell. */
    @JvmOverloads fun writeNativeScalar(elementOffset: Long, width: Int, value: Long, byteOffset: Boolean = false) {
        if (width != 2 && width != 4 && width != 8) fault("Unsupported managed Addr# scalar width")
        val stride = if (byteOffset) 1 else width
        if (elementOffset < Long.MIN_VALUE / stride || elementOffset > Long.MAX_VALUE / stride)
            fault("Managed Addr# element offset overflow")
        val displacement = elementOffset * stride
        requireRange(displacement, width.toLong(), writable = true)
        val little = ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN
        native?.let { allocation -> allocation.access { segment ->
            for (index in 0 until width) {
                val shift = (if (little) index else width - 1 - index) * 8
                segment.set(ValueLayout.JAVA_BYTE, offset + displacement + index, (value ushr shift).toByte())
            }
        }; return }
        owner?.let { it.writeNativeScalarByteOffset(offset + displacement, width, value, little); return }
        val bytes = mutableBytes ?: fault("Cannot write through an immutable literal Addr#")
        val start = (offset + displacement).toInt()
        for (index in 0 until width) {
            val shift = (if (little) index else width - 1 - index) * 8
            bytes[start + index] = (value ushr shift).toByte()
        }
    }

    fun writeWord16(elementOffset: Long, value: Long) = writeNativeScalar(elementOffset, 2, value)

    /** Keep owner checks, alias synchronization and the complete read/modify/write
     * together. This does not expose or permanently mark the backing as raw. */
    internal inline fun <T> withAtomicBytes(width: Int, writable: Boolean,
        action: (ByteArray, Int) -> T): T {
        requireRange(0, width.toLong(), writable)
        if (offset % width != 0L) fault("Misaligned atomic Addr#")
        owner?.let { return it.accessAtomicByteRange(offset, width, writable, action) }
        val bytes = literalBytes ?: mutableBytes ?: fault("Atomic Addr# has no byte storage")
        return synchronized(bytes) { action(bytes, offset.toInt()) }
    }

    internal fun atomicPointer(expected: ManagedAddress?, desired: ManagedAddress): ManagedAddress {
        val allocation = owner ?: fault("Pointer atomic requires managed pointer cells or owned native storage")
        while (true) {
            val old = synchronized(allocation) {
                requireRange(0, allocation.addressWidth.toLong(), writable = true)
                if (offset % allocation.addressWidth != 0L) fault("Misaligned atomic pointer Addr#")
                allocation.readAddressByteOffset(offset)
            }
            // Referent validation may take native lifetime or context registry
            // locks. Never hold a pointer-cell owner across those acquisitions:
            // a native copy can already hold its borrow while waiting for this
            // owner, with a free queued ahead of our new native read lock.
            // These checks do not retain referent allocations through commit;
            // this operation compares pointer values, not referent memory.
            desired.sameLocation(desired)
            expected?.sameLocation(expected)
            old.sameLocation(old)
            val matches = expected == null || old.sameLocation(expected)
            synchronized(allocation) {
                requireRange(0, allocation.addressWidth.toLong(), writable = true)
                if (allocation.readAddressByteOffset(offset) === old) {
                    if (matches) allocation.writeAddressByteOffset(offset, desired)
                    return old
                }
            }
            // A concurrent writer changed the observed cell. Revalidate that
            // new reference outside the monitor before attempting the commit.
        }
    }

    /** Validate a complete byte region before any effect. An empty region may
     * start one past the allocation; an immutable destination is never writable. */
    fun requireRange(displacement: Long, count: Long, writable: Boolean = false) {
        if (writable && owner == null && mutableBytes == null && native == null)
            fault("Cannot write through an immutable literal Addr#")
        if (writable && owner != null && !owner.isWritable)
            fault("Cannot write through an immutable managed allocation")
        val start = displacedOffset(displacement)
        val limit = size()
        if (count < 0 || start < 0 || start > limit || count > limit - start)
            fault("Managed Addr# range outside its backing storage")
    }

    /** Exact overlap of two checked byte regions, including independently made
     * addresses of the same array. Adjacent and empty regions do not overlap. */
    fun overlaps(displacement: Long, count: Long, other: ManagedAddress,
        otherDisplacement: Long, otherCount: Long): Boolean {
        requireRange(displacement, count)
        other.requireRange(otherDisplacement, otherCount)
        if (count == 0L || otherCount == 0L) return false
        val shared = when {
            native != null || other.native != null -> native != null && native === other.native
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

    /** memmove returns its destination address, including an interior view.
     * Owned pointer cells move as references, never as fabricated address bits. */
    fun moveTo(destination: ManagedAddress, count: Long): ManagedAddress {
        copyTo(destination, count, allowOverlap = true)
        return destination
    }

    /** copyAddrToAddrNonOverlapping# keeps its stronger disjointness contract. */
    fun copyNonOverlappingTo(destination: ManagedAddress, count: Long) =
        copyTo(destination, count, allowOverlap = false)

    /** The three array/address primops forbid an address into the array, not
     * merely intersecting copied ranges. Raw aliases retain this identity too. */
    private fun requireDistinctArray(array: Any?) {
        val same = when (array) {
            is ManagedAllocation -> owner === array || mutableBytes?.let(array::ownsStorage) == true ||
                literalBytes?.let(array::ownsStorage) == true
            is ByteArray -> mutableBytes === array || literalBytes === array || owner?.ownsStorage(array) == true
            else -> fault("Expected a managed ByteArray#")
        }
        if (same) fault("Array/address copy requires distinct backing allocations")
    }

    /** Keep native lifetime and pointer-cell transport inside the existing
     * storage boundaries; no temporary Addr# view or raw owner alias escapes. */
    fun copyToByteArray(destination: Any?, destinationOffset: Long, count: Long) = withNativeBorrow {
        requireRange(0, count)
        requireDistinctArray(destination)
        val destinationSize = ManagedByteArray.sizeGuest(destination)
        if (destinationOffset < 0 || destinationOffset > destinationSize || count > destinationSize - destinationOffset)
            fault("ByteArray# copy range outside its backing storage")
        if (destination is ManagedAllocation && !destination.isWritable)
            fault("Cannot write through an immutable managed allocation")
        // An empty range may lie inside a pointer cell: it transports no bits
        // and must not ask the pointer-copy boundary to interpret that cell.
        if (count == 0L) return@withNativeBorrow
        if (native != null) native.access { source ->
            if (destination is ManagedAllocation)
                destination.copyBytesIn(source.asSlice(offset, count).toArray(ValueLayout.JAVA_BYTE), 0, destinationOffset, count)
            else MemorySegment.copy(source, offset, MemorySegment.ofArray(destination as ByteArray), destinationOffset, count)
        } else ManagedByteArray.copyGuest(owner ?: literalBytes ?: mutableBytes,
            offset, destination, destinationOffset, count, mutable = false)
    }

    fun copyFromByteArray(source: Any?, sourceOffset: Long, count: Long) = withNativeBorrow {
        requireRange(0, count, writable = true)
        requireDistinctArray(source)
        val sourceSize = ManagedByteArray.sizeGuest(source)
        if (sourceOffset < 0 || sourceOffset > sourceSize || count > sourceSize - sourceOffset)
            fault("ByteArray# copy range outside its backing storage")
        if (count == 0L) return@withNativeBorrow
        if (native != null) native.access { target ->
            // Managed pointer references cannot become fabricated native bits.
            val bytes = if (source is ManagedAllocation) source.copyBytesOut(sourceOffset, count) else source as ByteArray
            MemorySegment.copy(MemorySegment.ofArray(bytes), if (source is ManagedAllocation) 0 else sourceOffset,
                target, offset, count)
        } else ManagedByteArray.copyGuest(source, sourceOffset, owner ?: mutableBytes,
            offset, count, mutable = false)
    }

    fun fill(count: Long, value: Long) = withNativeBorrow {
        requireRange(0, count, writable = true)
        when {
            native != null -> native.access { it.asSlice(offset, count).fill(value.toByte()); Unit }
            owner != null -> owner.fill(offset, count, value)
            else -> java.util.Arrays.fill(mutableBytes!!, offset.toInt(), (offset + count).toInt(), value.toByte())
        }
    }

    private fun copyTo(destination: ManagedAddress, count: Long, allowOverlap: Boolean) = withNativeBorrows(destination) copy@ {
        requireRange(0, count)
        destination.requireRange(0, count, writable = true)
        if (!allowOverlap && overlaps(0, count, destination, 0, count))
            fault("copyAddrToAddrNonOverlapping# requires disjoint regions")
        if (count == 0L) return@copy
        val sourceOwner = owner
        val destinationOwner = destination.owner
        when {
            native != null && destination.native != null -> native.access { source -> destination.native.access { target ->
                MemorySegment.copy(source, offset, target, destination.offset, count)
            } }
            native != null -> native.access { source ->
                if (destinationOwner != null) {
                    // The target's checked JVM capacity bounds this narrow transport.
                    val bytes = source.asSlice(offset, count).toArray(ValueLayout.JAVA_BYTE)
                    destinationOwner.copyBytesIn(bytes, 0, destination.offset, count)
                } else MemorySegment.copy(source, offset, MemorySegment.ofArray(destination.mutableBytes!!), destination.offset, count)
            }
            destination.native != null -> destination.native.access { target ->
                val bytes = if (sourceOwner != null) sourceOwner.copyBytesOut(offset, count)
                    else literalBytes ?: mutableBytes ?: fault("Null Addr# has no backing storage")
                MemorySegment.copy(MemorySegment.ofArray(bytes), if (sourceOwner == null) offset else 0, target, destination.offset, count)
            }
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
        else if (capabilities != null) "Addr#(enabled_capabilities)"
        else if (numeric != null) "Addr#(unowned numeric address)"
        else "Addr#(${if (literalBytes != null) "literal" else "managed"}+$offset)"

    /** Managed cells retain references; owned native cells contain actual
     * pointer bits. Unknown recovered bits remain opaque and non-dereferenceable. */
    @JvmOverloads fun readAddressElementIndex(elementOffset: Long, byteOffset: Boolean = false): ManagedAddress {
        if (native != null) {
            val stride = if (byteOffset) 1L else 8L
            if (elementOffset < Long.MIN_VALUE / stride || elementOffset > Long.MAX_VALUE / stride)
                fault("Native Addr# element offset overflow")
            val displacement = elementOffset * stride
            val bits = native.access { segment ->
                requireRange(displacement, 8)
                segment.get(ValueLayout.JAVA_LONG_UNALIGNED, offset + displacement)
            }
            return Language.currentState().nativeAllocations.recoverAddress(bits)
                ?: NativeAddresses.current(null).recover(bits)
        }
        val allocation = owner ?: fault("Addr# has no allocation-owned pointer cells")
        val width = allocation.addressWidth.toLong()
        val stride = if (byteOffset) 1L else width
        if (elementOffset < Long.MIN_VALUE / stride || elementOffset > Long.MAX_VALUE / stride)
            fault("Managed Addr# element offset overflow")
        val displacement = elementOffset * stride
        requireRange(displacement, width)
        return allocation.readAddressByteOffset(offset + displacement)
    }

    @JvmOverloads fun writeAddressElementIndex(elementOffset: Long, value: ManagedAddress, byteOffset: Boolean = false) {
        if (native != null) {
            // A real native pointer cell cannot retain an arbitrary JVM object.
            // Projection remains restricted to existing native/immutable owners.
            writeNativeScalar(elementOffset, 8, value.toNativeBits(), byteOffset)
            return
        }
        val allocation = owner ?: fault("Addr# has no allocation-owned pointer cells")
        val width = allocation.addressWidth.toLong()
        val stride = if (byteOffset) 1L else width
        if (elementOffset < Long.MIN_VALUE / stride || elementOffset > Long.MAX_VALUE / stride)
            fault("Managed Addr# element offset overflow")
        val displacement = elementOffset * stride
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
        private val NATIVE_BORROW_TIE = Any()
        private val NULL = ManagedAddress(null, null, 0L)
        fun nullAddress(): ManagedAddress = NULL
        internal fun fromNativeAllocation(owner: ManagedNativeAllocations.Owner): ManagedAddress =
            ManagedAddress(null, null, 0L, native = owner)
        internal fun unownedNumeric(bits: Long): ManagedAddress = if (bits == 0L) NULL
            else ManagedAddress(null, null, 0L, numeric = bits)
        internal fun fromNativeImageSource(source: Any, offset: Long): ManagedAddress = when (source) {
            is ManagedAllocation -> fromAllocation(source).plus(offset)
            is ByteArray -> ManagedAddress(source, null, offset)
            else -> fault("Invalid native image source")
        }
        internal fun fromStableHandle(handle: StablePointers.Handle): ManagedAddress =
            ManagedAddress(null, null, 0L, stable = handle)
        internal fun fromCFinalizer(function: CFinalizerFunction): ManagedAddress =
            ManagedAddress(null, null, 0L, finalizer = function)
        internal fun enabledCapabilities(threads: GuestThreads): ManagedAddress =
            ManagedAddress(null, null, 0L, capabilities = threads)

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
