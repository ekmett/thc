// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
import com.oracle.truffle.api.CompilerDirectives.transferToInterpreter
import com.oracle.truffle.api.frame.VirtualFrame
import thc.Language
import java.lang.ref.Reference
import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference
import jdk.incubator.vector.ByteVector
import jdk.incubator.vector.VectorShape
import java.nio.ByteOrder
import java.nio.ByteBuffer
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.util.function.ToLongFunction

/** An Addr# carrier with managed storage or an unowned numeric bit pattern.
 * Static literals may acquire a real, context-owned native image. Storage-backed
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
    private val capabilities: GuestThreads? = null,
    private val heap: HeapAddresses.Handle? = null,
    private val compiler: CompilerRts? = null,
    private val foreign: PackageReturnedAddress? = null,
    private val rtsFlags: CompilerRts? = null
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
    internal fun returnedAddress(): PackageReturnedAddress? = foreign
    internal fun numericBits(): Long? = numeric
    internal fun hasNativeStorage(): Boolean = native != null || owner?.isPinned == true
    internal fun isNativeBase(): Boolean = native != null && offset == 0L
    /** Keep native storage alive across a complete operation, including calls
     * whose native pointer outlives an individual checked byte access. */
    internal inline fun <T> withNativeBorrow(body: () -> T): T =
        native?.borrow().use { body() }
    internal fun <T> withNativeSegment(body: (MemorySegment) -> T): T =
        if (native != null) native.access { body(it.asSlice(offset)) }
        else synchronized(owner ?: fault("Address has no owned native allocation")) {
            body((owner.nativeSegment() ?: fault("Address has no owned native allocation")).asSlice(offset))
        }
    internal fun withNativeSegmentInt(body: java.util.function.ToIntFunction<MemorySegment>): Int =
        if (native != null) native.accessInt { body.applyAsInt(it.asSlice(offset)) }
        else synchronized(owner ?: fault("Address has no owned native allocation")) {
            body.applyAsInt((owner.nativeSegment() ?: fault("Address has no owned native allocation")).asSlice(offset))
        }
    internal fun withNativeSegmentLong(body: ToLongFunction<MemorySegment>): Long =
        if (native != null) native.accessLong { body.applyAsLong(it.asSlice(offset)) }
        else synchronized(owner ?: fault("Address has no owned native allocation")) {
            body.applyAsLong((owner.nativeSegment() ?: fault("Address has no owned native allocation")).asSlice(offset))
        }
    internal inline fun <T> withNativeBorrows(other: ManagedAddress, body: () -> T): T {
        if (native == null) return other.withNativeBorrow(body)
        if (other.native == null || native === other.native) return withNativeBorrow(body)
        // Ordered acquisition also prevents two copies from deadlocking behind
        // queued frees while each already holds the other's source allocation.
        return when (Integer.compareUnsigned(System.identityHashCode(native), System.identityHashCode(other.native))) {
            -1 -> withNativeBorrow { other.withNativeBorrow(body) }
            1 -> other.withNativeBorrow { withNativeBorrow(body) }
            else -> synchronized(NATIVE_BORROW_TIE) { withNativeBorrow { other.withNativeBorrow(body) } }
        }
    }
    internal fun stableHandle(): StablePointers.Handle? = stable
    internal fun heapHandle(): HeapAddresses.Handle? = heap
    internal fun finalizerFunction(): CFinalizerFunction? = finalizer
    private fun requireBytes() {
        compiler?.requireCurrent()
        foreign?.requireCurrent()
        rtsFlags?.let { it.requireCurrent(); fault("RtsFlags permits only supported read-only fields") }
        if (heap != null) fault("Opaque guest heap address is not byte-addressable")
        if (capabilities != null) fault("RTS data label is not byte-addressable")
        if (stable != null) fault("Opaque StablePtr# is not byte-addressable")
        if (finalizer != null) fault("Opaque C function label is not byte-addressable")
        if (numeric != null) fault("Unowned numeric Addr# is not byte-addressable")
    }
    /** Static literals and runtime metadata have a native image. Dynamic heap arrays never
     * acquire a second allocation merely because their address escapes. */
    internal fun nativeImageKey(): Any? = literalBytes ?: owner?.takeIf { it.isStaticImage }
    internal fun nativeImageBytes(): ByteArray = literalBytes ?: owner?.takeIf { it.isStaticImage }?.let { it.copyBytesOut(0, it.size) }
        ?: fault("Native image requires static literal or runtime metadata storage")
    fun toNativeBits(): Long {
        compiler?.requireCurrent()
        rtsFlags?.let { it.requireCurrent(); fault("RtsFlags has no numeric guest address") }
        foreign?.let { return it.bits() }
        if (heap != null) fault("Opaque guest heap address has no native pointer bits")
        if (capabilities != null) fault("RTS data label has no numeric guest address")
        if (finalizer != null) fault("Opaque C function label has no numeric guest address")
        if (stable != null) return StablePointers.current(null).nativeToken(this)
        native?.let { return it.accessLong { segment -> segment.address() + offset } }
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
    internal fun cbitsSegment(): MemorySegment {
        requireBytes()
        return owner?.exposeSegment() ?: literalBytes?.let { MemorySegment.ofArray(it).asReadOnly() }
            ?: mutableBytes?.let(MemorySegment::ofArray) ?: fault("Address has no managed buffer storage")
    }
    internal fun cbitsBuffer(): ByteBuffer = cbitsSegment().asByteBuffer()
    internal fun cbitsStorageKey(): Any = owner?.storageKey() ?: literalBytes ?: mutableBytes
        ?: fault("Address has no managed buffer storage")
    internal fun cbitsWritable(): Boolean { requireBytes(); native?.requireLive(); return native != null || (owner?.isWritable ?: (mutableBytes != null)) }
    internal fun cbitsOffset(): Long { size(); return offset }
    internal fun cbitsOwner(): ManagedAllocation? = owner
    internal fun cbitsSize(): Long = size()
    fun availableBytes(): Long {
        if (externalPointer() != null) fault("External C pointer has no known allocation extent")
        requireRange(0, 0); return size() - offset
    }

    private fun externalPointer(): PackageReturnedAddress? = foreign?.takeIf { it.backing == null }
    internal fun hasExternalStorage(): Boolean = externalPointer() != null
    internal fun hasNativeIOStorage(): Boolean = hasNativeStorage() || externalPointer()?.isNative() == true
    internal fun <T> withNativeIOWindow(count: Long, writable: Boolean, body: (MemorySegment) -> T): T =
        externalPointer()?.withNativeWindow(count, writable, body) ?: withNativeSegment(body)

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
        compiler?.requireCurrent(); other.compiler?.requireCurrent()
        foreign?.requireCurrent(); other.foreign?.requireCurrent()
        if (rtsFlags != null || other.rtsFlags != null) {
            rtsFlags?.requireCurrent(); other.rtsFlags?.requireCurrent()
            native?.requireLive(); other.native?.requireLive()
            return rtsFlags != null && rtsFlags === other.rtsFlags && offset == other.offset
        }
        // Known returned aliases already carry their backing's identity fields
        // (fromReturnedAddress). Compare those directly, without recursive
        // unwrapping during partial evaluation; only unknown C storage needs C.
        externalPointer()?.let { return it.compare(other, "equal") != 0L }
        other.externalPointer()?.let { return it.compare(this, "equal") != 0L }
        if (heap != null || other.heap != null) {
            val registry = HeapAddresses.current()
            heap?.let(registry::require); other.heap?.let(registry::require)
            return heap != null && heap === other.heap
        }
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
        foreign?.requireCurrent(); other.foreign?.requireCurrent()
        if (rtsFlags != null || other.rtsFlags != null) {
            rtsFlags?.requireCurrent(); other.rtsFlags?.requireCurrent()
            fault("RtsFlags has no address ordering")
        }
        if (foreign?.backing != null || other.foreign?.backing != null)
            return (foreign?.backing ?: this).compareWithinAllocation(other.foreign?.backing ?: other)
        foreign?.let { return it.compare(other, "compare").toInt() }
        other.foreign?.let { return -it.compare(this, "compare").toInt() }
        if (heap != null || other.heap != null) fault("Opaque guest heap addresses have no ordering")
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
        rtsFlags?.let {
            it.requireCurrent()
            return if (displacement == 0L) this
                else ManagedAddress(null, null, displacedOffset(displacement), rtsFlags = it)
        }
        if (heap != null) {
            HeapAddresses.current().require(heap)
            if (displacement != 0L) fault("Opaque guest heap address cannot be offset")
            return this
        }
        if (capabilities != null) {
            readCapabilitiesWord32(0, 4)
            if (displacement != 0L) fault("RTS data label cannot be offset")
            return this
        }
        if (foreign != null) return fromReturnedAddress(foreign.plus(displacement))
        if (numeric != null) return NativeAddresses.current(null).recover(numeric + displacement)
        requireBytes()
        if (this === NULL) {
            if (displacement == 0L) return this
            fault("Cannot offset null Addr#")
        }
        size() // Preserve native lifetime/context validation, including plus zero.
        return if (displacement == 0L) this else ManagedAddress(literalBytes, mutableBytes,
            displacedOffset(displacement), owner, native = native, compiler = compiler)
    }

    private fun displacedOffset(displacement: Long): Long = try {
        Math.addExact(offset, displacement)
    } catch (_: ArithmeticException) {
        fault("Managed Addr# offset overflow")
    }

    /** Relative pointers need no JVM address projection. Native/numeric pointers
     * retain machine arithmetic; managed storage uses its allocation-local origin. */
    fun difference(other: ManagedAddress): Long {
        foreign?.requireCurrent(); other.foreign?.requireCurrent()
        if (foreign?.backing != null || other.foreign?.backing != null)
            return (foreign?.backing ?: this).difference(other.foreign?.backing ?: other)
        foreign?.let { return it.compare(other, "difference") }
        other.foreign?.let { return -it.compare(this, "difference") }
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
        val bits = if (this === NULL || numeric != null || foreign != null || hasNativeStorage()) toNativeBits()
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
        externalPointer()?.let { pointer ->
            val bytes = pointer.copyOut(pointer.cStringLength())
            return try { Charsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
            } catch (_: java.nio.charset.CharacterCodingException) { fault("Invalid UTF-8 at the polyglot boundary") }
        }
        requireRange(0, 1)
        val bytes = cbitsBuffer()
        val start = offset.toInt()
        val limit = size().toInt()
        if (start < 0 || start >= limit) fault("UTF-8 address outside its backing storage")
        var end = start
        while (end < limit && bytes.get(end) != 0.toByte()) end++
        if (end == limit) fault("Unterminated polyglot UTF-8 address")
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(bytes.slice(start, end - start)).toString()
        } catch (_: java.nio.charset.CharacterCodingException) {
            fault("Invalid UTF-8 at the polyglot boundary")
        }
    }

    /** Both backing variants use byte offsets and return zero-extended Word8#. */
    fun readWord8(displacement: Long): Long = readWord8Int(displacement).toLong()

    /** Guest Word8# uses Int; the host byte API above has a machine-word boundary. */
    fun readWord8Int(displacement: Long): Int {
        rtsFlags?.let { return it.readFlagByte(displacedOffset(displacement)).toInt() }
        externalPointer()?.let { return it.read(displacement, 1).toInt() }
        native?.let { allocation -> return allocation.accessInt { segment ->
            requireRange(displacement, 1)
            segment.get(ValueLayout.JAVA_BYTE, offset + displacement).toInt() and 255
        } }
        val index = index(displacement)
        owner?.let { return it.readByteInt(index.toLong()) }
        // Keep the immutable and mutable loads distinct: only the former may fold.
        val literal = literalBytes
        return (if (literal != null) literal[index] else {
            val mutable = mutableBytes
            if (mutable == null) transferToInterpreter()
            mutable!![index]
        }).toInt() and 0xff
    }

    /** Original libc strlen over a live, bounded byte address. The native
     * allocation cannot be freed and an owner cannot shrink while scanning. */
    @TruffleBoundary
    fun cStringLength(): Long {
        foreign?.let { return it.cStringLength() }
        native?.let { allocation -> return allocation.accessLong { segment ->
            requireRange(0, 0)
            val limit = segment.byteSize() - offset
            var length = 0L
            while (length < limit) {
                if (segment.get(ValueLayout.JAVA_BYTE, offset + length) == 0.toByte()) return@accessLong length
                length++
            }
            fault("Unterminated original C string inside managed Addr#")
        } }
        val scan = scan@{
            val limit = availableBytes()
            var length = 0L
            while (length < limit) {
                if (readWord8Int(length) == 0) return@scan length
                length++
            }
            fault("Unterminated original C string inside managed Addr#")
        }
        val allocation = owner
        return if (allocation == null) scan() else synchronized(allocation) { scan() }
    }

    /** Original memcmp compares unsigned bytes; only the result sign is a C
     * contract. No snapshot, native pointer projection or temporary pin. */
    @TruffleBoundary
    fun compareBytes(other: ManagedAddress, count: Long): Long = withNativeBorrows(other) {
        if (this !== NULL || count != 0L) requireByteRegion(count)
        if (other !== NULL || count != 0L) other.requireByteRegion(count)
        var index = 0L
        while (index < count) {
            val difference = readWord8Int(index) - other.readWord8Int(index)
            if (difference != 0) return@withNativeBorrows difference.toLong()
            index++
        }
        0L
    }

    /** Return the first interior view or null. The view retains exactly the
     * source owner, offset, mutability and context/lifetime checks. */
    @TruffleBoundary
    fun findByte(value: Long, count: Long): ManagedAddress = withNativeBorrow {
        if (this !== NULL || count != 0L) requireByteRegion(count)
        val needle = value.toInt() and 255
        var index = 0L
        while (index < count) {
            if (readWord8Int(index) == needle) return@withNativeBorrow plus(index)
            index++
        }
        NULL
    }

    /** The caller evaluates State# before reaching storage. Invalid writes have
     * no effect; the value contributes only its low eight bits, like writeWord8Array#. */
    fun writeWord8(displacement: Long, value: Long) = writeWord8Int(displacement, value.toInt())

    fun writeWord8Int(displacement: Long, value: Int) {
        externalPointer()?.let { it.write(displacement, 1, value.toLong()); return }
        native?.let { allocation -> allocation.access { segment ->
            requireRange(displacement, 1, writable = true)
            segment.set(ValueLayout.JAVA_BYTE, offset + displacement, value.toByte())
        }; return }
        owner?.let { it.writeByteInt(index(displacement).toLong(), value); return }
        val bytes = mutableBytes ?: fault("Cannot write through an immutable literal Addr#")
        val index = index(displacement)
        bytes[index] = value.toByte()
    }

    /** Native-endian scalar stores validate the full element before mutation.
     * Pointer-bearing allocations retain references except for a complete
     * overwrite of a pointer cell. */
    @JvmOverloads fun writeNativeInt(elementOffset: Long, width: Int, value: Int, byteOffset: Boolean = false) {
        if (width != 2 && width != 4) fault("Unsupported managed Addr# scalar width")
        val stride = if (byteOffset) 1 else width
        if (elementOffset < Long.MIN_VALUE / stride || elementOffset > Long.MAX_VALUE / stride)
            fault("Managed Addr# element offset overflow")
        val displacement = elementOffset * stride
        // The existing C helper takes a machine carrier and stores only width
        // bytes. Preserve raw narrow bits without inventing a managed backing.
        externalPointer()?.let { it.write(displacement, width, value.toLong()); return }
        requireRange(displacement, width.toLong(), writable = true)
        val little = ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN
        native?.let { allocation -> allocation.access { segment ->
            for (index in 0 until width) {
                val shift = (if (little) index else width - 1 - index) * 8
                segment.set(ValueLayout.JAVA_BYTE, offset + displacement + index, (value ushr shift).toByte())
            }
        }; return }
        owner?.let { it.writeNativeIntByteOffset(offset + displacement, width, value, little); return }
        val bytes = mutableBytes ?: fault("Cannot write through an immutable literal Addr#")
        val start = (offset + displacement).toInt()
        for (index in 0 until width) {
            val shift = (if (little) index else width - 1 - index) * 8
            bytes[start + index] = (value ushr shift).toByte()
        }
    }

    @JvmOverloads fun writeNativeScalar(elementOffset: Long, width: Int, value: Long, byteOffset: Boolean = false) {
        if (width != 2 && width != 4 && width != 8) fault("Unsupported managed Addr# scalar width")
        val stride = if (byteOffset) 1 else width
        if (elementOffset < Long.MIN_VALUE / stride || elementOffset > Long.MAX_VALUE / stride)
            fault("Managed Addr# element offset overflow")
        val displacement = elementOffset * stride
        externalPointer()?.let { it.write(displacement, width, value); return }
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

    /** A vector is one checked full-width access. The owner monitor/native borrow
     * spans validation and transfer: no raw backing escape or per-byte partial store. */
    fun readVectorBytes(elementOffset: Long, stride: Int, vectorBytes: Int = 16): ByteVector {
        if (vectorBytes != 16 && vectorBytes != 32 && vectorBytes != 64) fault("Unsupported Addr# vector width")
        val species = ByteVector.SPECIES_128.withShape(VectorShape.forBitSize(vectorBytes * 8))
        val displacement = vectorDisplacement(elementOffset, stride)
        externalPointer()?.let { return ByteVector.fromArray(species, it.plus(displacement).copyOut(vectorBytes.toLong()), 0) }
        native?.let { allocation -> return allocation.access { segment ->
            requireRange(displacement, vectorBytes.toLong())
            ByteVector.fromMemorySegment(species, segment, offset + displacement, ByteOrder.nativeOrder())
        } }
        requireRange(displacement, vectorBytes.toLong())
        val start = offset + displacement
        owner?.let { allocation -> return synchronized(allocation) {
            val segment = allocation.vectorSegment(start, true, 1, false, vectorBytes)
            ByteVector.fromMemorySegment(species, segment, start, ByteOrder.nativeOrder())
        } }
        return ByteVector.fromArray(species,
            literalBytes ?: mutableBytes ?: fault("Null Addr# has no backing storage"), start.toInt())
    }

    fun writeVectorBytes(elementOffset: Long, stride: Int, value: ByteVector, vectorBytes: Int = 16) {
        if (vectorBytes != 16 && vectorBytes != 32 && vectorBytes != 64) fault("Unsupported Addr# vector width")
        val vector = CoreVectors.requireByte(value, ByteVector.SPECIES_128.withShape(VectorShape.forBitSize(vectorBytes * 8)))
        val displacement = vectorDisplacement(elementOffset, stride)
        externalPointer()?.let { it.plus(displacement).copyIn(vector.toArray()); return }
        native?.let { allocation -> allocation.access { segment ->
            requireRange(displacement, vectorBytes.toLong(), writable = true)
            vector.intoMemorySegment(segment, offset + displacement, ByteOrder.nativeOrder())
        }; return }
        requireRange(displacement, vectorBytes.toLong(), writable = true)
        val start = offset + displacement
        owner?.let { allocation -> synchronized(allocation) {
            val segment = allocation.vectorSegment(start, true, 1, true, vectorBytes)
            vector.intoMemorySegment(segment, start, ByteOrder.nativeOrder())
        }; return }
        vector.intoArray(mutableBytes ?: fault("Cannot write through an immutable literal Addr#"), start.toInt())
    }

    private fun vectorDisplacement(elementOffset: Long, stride: Int): Long {
        if (stride != 1 && stride != 2 && stride != 4 && stride != 8 && stride != 16 && stride != 32 && stride != 64)
            fault("Unsupported Addr# vector stride")
        if (elementOffset < Long.MIN_VALUE / stride || elementOffset > Long.MAX_VALUE / stride)
            fault("Managed Addr# vector offset overflow")
        return elementOffset * stride
    }

    fun writeWord16(elementOffset: Long, value: Long) = writeNativeScalar(elementOffset, 2, value)

    /** Keep owner checks, alias synchronization and the complete read/modify/write
     * together. This does not expose or permanently mark the backing as raw. */
    internal inline fun <T> withAtomicBytes(width: Int, writable: Boolean,
        action: (MemorySegment, Int) -> T): T {
        requireRange(0, width.toLong(), writable)
        if (offset % width != 0L) fault("Misaligned atomic Addr#")
        owner?.let { allocation -> return synchronized(allocation) {
            val segment = allocation.atomicSegment(offset, width, writable)
            synchronized(allocation.storageKey()) { action(segment, offset.toInt()) }
        } }
        val bytes = literalBytes ?: mutableBytes ?: fault("Atomic Addr# has no byte storage")
        return synchronized(bytes) { action(MemorySegment.ofArray(bytes), offset.toInt()) }
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
        externalPointer()?.let { it.requireRange(displacement, count, writable); return }
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
        if (externalPointer() != null || other.externalPointer() != null) {
            externalPointer()?.let { return it.plus(displacement).overlaps(count, other.plus(otherDisplacement), otherCount) }
            return other.externalPointer()!!.plus(otherDisplacement).overlaps(otherCount, plus(displacement), count)
        }
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
        // Empty ByteString uses nullAddr#. Its ordinary toShort path still
        // emits a zero-byte copy, which does not read or need source storage.
        // Retain all destination checks and every nonempty source check.
        if (this !== NULL || count != 0L) requireRange(0, count)
        requireDistinctArray(destination)
        val destinationSize = ManagedByteArray.sizeGuest(destination)
        if (destinationOffset < 0 || destinationOffset > destinationSize || count > destinationSize - destinationOffset)
            fault("ByteArray# copy range outside its backing storage")
        if (destination is ManagedAllocation && !destination.isWritable)
            fault("Cannot write through an immutable managed allocation")
        // An empty range may lie inside a pointer cell: it transports no bits
        // and must not ask the pointer-copy boundary to interpret that cell.
        if (count == 0L) return@withNativeBorrow
        externalPointer()?.let { pointer ->
            if (destination is ByteArray) pointer.copyOutTo(destination, destinationOffset, count)
            else if (destination is ManagedAllocation) {
                if (pointer.isNative()) pointer.withNativeWindow(count, false) {
                    destination.copyBytesIn(it, 0, destinationOffset, count)
                } else destination.copyBytesIn(pointer.copyOut(count), 0, destinationOffset, count)
            }
            return@withNativeBorrow
        }
        if (native != null) native.access { source ->
            if (destination is ManagedAllocation)
                destination.copyBytesIn(source, offset, destinationOffset, count)
            else MemorySegment.copy(source, offset, MemorySegment.ofArray(destination as ByteArray), destinationOffset, count)
        } else ManagedByteArray.copyGuest(owner ?: literalBytes ?: mutableBytes,
            offset, destination, destinationOffset, count, false)
    }

    fun copyFromByteArray(source: Any?, sourceOffset: Long, count: Long) = withNativeBorrow {
        requireRange(0, count, writable = true)
        requireDistinctArray(source)
        val sourceSize = ManagedByteArray.sizeGuest(source)
        if (sourceOffset < 0 || sourceOffset > sourceSize || count > sourceSize - sourceOffset)
            fault("ByteArray# copy range outside its backing storage")
        if (count == 0L) return@withNativeBorrow
        externalPointer()?.let { pointer ->
            if (source is ByteArray) pointer.copyInFrom(source, sourceOffset, count)
            else if (source is ManagedAllocation && pointer.isNative()) pointer.withNativeWindow(count, true) {
                source.copyBytesTo(sourceOffset, it, 0, count)
            } else {
                val bytes = ByteArray(count.toInt())
                ManagedByteArray.copyGuest(source, sourceOffset, bytes, 0, count, false)
                pointer.copyIn(bytes)
            }
            return@withNativeBorrow
        }
        if (native != null) native.access { target ->
            // Managed pointer references cannot become fabricated native bits.
            if (source is ManagedAllocation) source.copyBytesTo(sourceOffset, target, offset, count)
            else MemorySegment.copy(MemorySegment.ofArray(source as ByteArray), sourceOffset, target, offset, count)
        } else ManagedByteArray.copyGuest(source, sourceOffset, owner ?: mutableBytes,
            offset, count, false)
    }

    fun fill(count: Long, value: Long) = withNativeBorrow {
        requireRange(0, count, writable = true)
        when {
            externalPointer() != null -> externalPointer()!!.fill(count, value)
            native != null -> native.access { it.asSlice(offset, count).fill(value.toByte()); Unit }
            owner != null -> owner.fill(offset, count, value)
            else -> java.util.Arrays.fill(mutableBytes!!, offset.toInt(), (offset + count).toInt(), value.toByte())
        }
    }

    // Keep the complete ordered-borrow and FFM/Sulong bulk transfer outside
    // partial evaluation. Expanding all storage/cleanup paths can exceed the
    // native code installation limit even for a pinned-to-pinned copy.
    @TruffleBoundary
    private fun copyTo(destination: ManagedAddress, count: Long, allowOverlap: Boolean) = withNativeBorrows(destination) copy@ {
        requireRange(0, count)
        destination.requireRange(0, count, writable = true)
        if (!allowOverlap && overlaps(0, count, destination, 0, count))
            fault("copyAddrToAddrNonOverlapping# requires disjoint regions")
        if (count == 0L) return@copy
        if (externalPointer() != null || destination.externalPointer() != null) {
            if ((externalPointer() != null || hasNativeStorage()) &&
                (destination.externalPointer() != null || destination.hasNativeStorage())) {
                val library = (externalPointer() ?: destination.externalPointer())!!.owner.packageCbits
                library.memory("copy", library.transport(destination), library.transport(this), count)
            } else {
                if (externalPointer() != null) copyToByteArray(
                    destination.owner ?: destination.mutableBytes ?: fault("Missing copy destination storage"), destination.offset, count)
                else destination.copyFromByteArray(owner ?: literalBytes ?: mutableBytes
                    ?: fault("Missing copy source storage"), offset, count)
            }
            return@copy
        }
        val sourceOwner = owner
        val destinationOwner = destination.owner
        when {
            native != null && destination.native != null -> native.access { source -> destination.native.access { target ->
                MemorySegment.copy(source, offset, target, destination.offset, count)
            } }
            native != null -> native.access { source ->
                if (destinationOwner != null) {
                    destinationOwner.copyBytesIn(source, offset, destination.offset, count)
                } else MemorySegment.copy(source, offset, MemorySegment.ofArray(destination.mutableBytes!!), destination.offset, count)
            }
            destination.native != null -> destination.native.access { target ->
                if (sourceOwner != null) sourceOwner.copyBytesTo(offset, target, destination.offset, count)
                else MemorySegment.copy(MemorySegment.ofArray(literalBytes ?: mutableBytes
                    ?: fault("Null Addr# has no backing storage")), offset, target, destination.offset, count)
            }
            sourceOwner != null && destinationOwner != null ->
                destinationOwner.copyFrom(sourceOwner, offset, destination.offset, count)
            sourceOwner != null -> {
                sourceOwner.copyBytesTo(offset, MemorySegment.ofArray(destination.mutableBytes!!), destination.offset, count)
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
    override fun toString(): String = if (heap != null) "Addr#(opaque guest heap)"
        else if (stable != null) "Addr#(opaque StablePtr)" else if (this === NULL) "Addr#(null)"
        else if (capabilities != null) "Addr#(enabled_capabilities)"
        else if (rtsFlags != null) "Addr#(RtsFlags+$offset)"
        else if (foreign != null) "Addr#(returned C pointer)"
        else if (numeric != null) "Addr#(unowned numeric address)"
        else "Addr#(${if (literalBytes != null) "literal" else "managed"}+$offset)"

    /** Managed cells retain references; owned native cells contain actual
     * pointer bits. Unknown recovered bits remain opaque and non-dereferenceable. */
    @JvmOverloads fun readAddressElementIndex(elementOffset: Long, byteOffset: Boolean = false): ManagedAddress {
        externalPointer()?.let { pointer ->
            val stride = if (byteOffset) 1L else 8L
            val displacement = try { Math.multiplyExact(elementOffset, stride) }
                catch (_: ArithmeticException) { fault("Native Addr# element offset overflow") }
            return pointer.readAddress(displacement)
        }
        if (native != null) {
            val stride = if (byteOffset) 1L else 8L
            if (elementOffset < Long.MIN_VALUE / stride || elementOffset > Long.MAX_VALUE / stride)
                fault("Native Addr# element offset overflow")
            val displacement = elementOffset * stride
            val bits = native.access { segment ->
                requireRange(displacement, 8)
                segment.get(ValueLayout.JAVA_LONG_UNALIGNED, offset + displacement)
            }
            return StablePointers.current(null).recoverToken(bits)
                ?: Language.currentState().nativeAllocations.recoverAddress(bits)
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
        externalPointer()?.let { pointer ->
            val stride = if (byteOffset) 1L else 8L
            val displacement = try { Math.multiplyExact(elementOffset, stride) }
                catch (_: ArithmeticException) { fault("Native Addr# element offset overflow") }
            pointer.writeAddress(displacement, value)
            return
        }
        if (native != null) {
            // A real native pointer cell cannot retain an arbitrary JVM object.
            // Projection requires a native/immutable owner or opaque StablePtr identity.
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
        /** Hold every distinct native owner for one synchronous multi-pointer call. */
        internal fun <T> withNativeBorrows(addresses: List<ManagedAddress>, body: java.util.function.Supplier<T>): T {
            val owners = addresses.mapNotNull { it.native ?: it.foreign?.backing?.nativeAllocation() }.distinct().sortedWith { first, second ->
                Integer.compareUnsigned(System.identityHashCode(first), System.identityHashCode(second))
            }
            fun acquire(index: Int): T = if (index == owners.size) body.get()
                else owners[index].borrow().use { acquire(index + 1) }
            val collision = owners.zipWithNext().any { (a, b) -> System.identityHashCode(a) == System.identityHashCode(b) }
            return if (collision) synchronized(NATIVE_BORROW_TIE) { acquire(0) } else acquire(0)
        }
        private val NULL = ManagedAddress(null, null, 0L)
        fun nullAddress(): ManagedAddress = NULL
        internal fun fromNativeAllocation(owner: ManagedNativeAllocations.Owner): ManagedAddress =
            ManagedAddress(null, null, 0L, native = owner)
        internal fun unownedNumeric(bits: Long): ManagedAddress = if (bits == 0L) NULL
            else ManagedAddress(null, null, 0L, numeric = bits)
        internal fun fromReturnedAddress(address: PackageReturnedAddress): ManagedAddress {
            val backing = address.backing
            return if (backing == null) ManagedAddress(null, null, 0L, foreign = address)
            else ManagedAddress(backing.literalBytes, backing.mutableBytes, backing.offset, backing.owner,
                backing.stable, backing.numeric, backing.finalizer, backing.native, backing.capabilities,
                backing.heap, backing.compiler, address)
        }
        internal fun fromNativeImageSource(source: Any, offset: Long): ManagedAddress = when (source) {
            is ManagedAllocation -> fromAllocation(source).plus(offset)
            is ByteArray -> ManagedAddress(source, null, offset)
            else -> fault("Invalid native image source")
        }
        internal fun fromStableHandle(handle: StablePointers.Handle): ManagedAddress =
            ManagedAddress(null, null, 0L, stable = handle)
        internal fun fromHeapHandle(handle: HeapAddresses.Handle): ManagedAddress =
            ManagedAddress(null, null, 0L, heap = handle)
        internal fun fromCFinalizer(function: CFinalizerFunction): ManagedAddress =
            ManagedAddress(null, null, 0L, finalizer = function)
        internal fun enabledCapabilities(threads: GuestThreads): ManagedAddress =
            ManagedAddress(null, null, 0L, capabilities = threads)
        internal fun rtsFlags(compiler: CompilerRts): ManagedAddress =
            ManagedAddress(null, null, 0L, rtsFlags = compiler)

        /** Views retain their existing storage, including native pinned arrays.
         * Creating an address never copies or promotes a moving heap array. */
        fun fromByteArray(bytes: ByteArray): ManagedAddress = ManagedAddress(null, bytes, 0L)
        internal fun compilerCell(bytes: ByteArray, compiler: CompilerRts): ManagedAddress =
            ManagedAddress(null, bytes, 0L, compiler = compiler)
        fun fromAllocation(allocation: ManagedAllocation): ManagedAddress = ManagedAddress(null, null, 0L, allocation)
        /** Runtime info tables are static images, not movable guest arrays.
         * Copy once into private read-only bytes; unlike LitString, add no NUL.
         * Numeric projection then uses the existing context-owned static image. */
        internal fun fromStaticBytes(bytes: ByteArray, pointerBytes: Int = 8): ManagedAddress =
            fromAllocation(ManagedAllocation.immutable(bytes, pointerBytes, true))
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
    override fun execute(frame: VirtualFrame): Any = executeInt(frame)
    override fun executeInt(frame: VirtualFrame): Int {
        val value = address.executeRequiredAddress(frame)
        val byte = value.readWord8Int(displacement.executeRequiredLong(frame))
        return if (signed) byte.toByte().toInt() else byte
    }
}

internal class IndexManagedScalarAddress(private val operation: ManagedAddressRead,
    @field:Child private var address: Expr, @field:Child private var element: Expr) : Expr() {
    override fun execute(frame: VirtualFrame): Any = if (operation.isInt) executeInt(frame) else executeLong(frame)
    override fun executeInt(frame: VirtualFrame): Int = operation.readInt(
        address.executeRequiredAddress(frame), element.executeRequiredLong(frame))
    override fun executeLong(frame: VirtualFrame): Long = operation.read(
        address.executeRequiredAddress(frame), element.executeRequiredLong(frame))
}
