// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import jdk.incubator.vector.IntVector
import jdk.incubator.vector.ByteVector
import jdk.incubator.vector.ShortVector
import jdk.incubator.vector.LongVector
import jdk.incubator.vector.FloatVector
import jdk.incubator.vector.DoubleVector

import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandles
import java.nio.ByteOrder

/** Host-supplied byte arrays retain their raw fast path. Guest allocations use
 * one owner so shrink can change logical size without replacing the backing.
 * The JVM initializes bytes; guests must still initialize before reading. */
internal object ManagedByteArray {
    private val ints = MethodHandles.byteArrayViewVarHandle(LongArray::class.java, ByteOrder.nativeOrder())
    private val int16s = MethodHandles.byteArrayViewVarHandle(ShortArray::class.java, ByteOrder.nativeOrder())
    private val int32s = MethodHandles.byteArrayViewVarHandle(IntArray::class.java, ByteOrder.nativeOrder())
    private val floats = MethodHandles.byteArrayViewVarHandle(FloatArray::class.java, ByteOrder.nativeOrder())
    private val doubles = MethodHandles.byteArrayViewVarHandle(DoubleArray::class.java, ByteOrder.nativeOrder())

    private fun elementOffset(bytes: ByteArray, index: Long, width: Int, representation: String): Int {
        if (index < 0 || index >= bytes.size / width)
            fault("ByteArray# $representation index outside its backing storage")
        return index.toInt() * width
    }
    // Word8ArrayAs* counts bytes and permits unaligned starts.
    private fun byteOffset(bytes: ByteArray, offset: Long, width: Int, representation: String): Int {
        if (offset < 0 || offset > bytes.size.toLong() - width)
            fault("ByteArray# $representation byte offset outside its backing storage")
        return offset.toInt()
    }

    // Plain native-endian views; Int#/Word# share the same raw 64-bit carrier.
    @JvmStatic fun readInt(bytes: ByteArray, index: Long): Long =
        ints.get(bytes, elementOffset(bytes, index, 8, "Int")) as Long
    @JvmStatic fun writeInt(bytes: ByteArray, index: Long, value: Long) {
        ints.set(bytes, elementOffset(bytes, index, 8, "Int"), value)
    }
    // Narrow reads widen with the requested signedness; writes keep the low bits.
    @JvmStatic fun readInt16(bytes: ByteArray, index: Long): Long =
        (int16s.get(bytes, elementOffset(bytes, index, 2, "16-bit")) as Short).toLong()
    @JvmStatic fun readWord16(bytes: ByteArray, index: Long): Long =
        java.lang.Short.toUnsignedLong(int16s.get(bytes, elementOffset(bytes, index, 2, "16-bit")) as Short)
    @JvmStatic fun writeInt16(bytes: ByteArray, index: Long, value: Long) {
        int16s.set(bytes, elementOffset(bytes, index, 2, "16-bit"), value.toShort())
    }
    @JvmStatic fun readInt16ByteOffset(bytes: ByteArray, offset: Long): Long =
        (int16s.get(bytes, byteOffset(bytes, offset, 2, "16-bit")) as Short).toLong()
    @JvmStatic fun readWord16ByteOffset(bytes: ByteArray, offset: Long): Long =
        java.lang.Short.toUnsignedLong(int16s.get(bytes, byteOffset(bytes, offset, 2, "16-bit")) as Short)
    @JvmStatic fun writeInt16ByteOffset(bytes: ByteArray, offset: Long, value: Long) {
        int16s.set(bytes, byteOffset(bytes, offset, 2, "16-bit"), value.toShort())
    }
    @JvmStatic fun readInt32(bytes: ByteArray, index: Long): Long =
        (int32s.get(bytes, elementOffset(bytes, index, 4, "32-bit")) as Int).toLong()
    @JvmStatic fun readWord32(bytes: ByteArray, index: Long): Long =
        Integer.toUnsignedLong(int32s.get(bytes, elementOffset(bytes, index, 4, "32-bit")) as Int)
    @JvmStatic fun writeInt32(bytes: ByteArray, index: Long, value: Long) {
        int32s.set(bytes, elementOffset(bytes, index, 4, "32-bit"), value.toInt())
    }
    @JvmStatic fun readInt32ByteOffset(bytes: ByteArray, offset: Long): Long =
        (int32s.get(bytes, byteOffset(bytes, offset, 4, "32-bit")) as Int).toLong()
    @JvmStatic fun readWord32ByteOffset(bytes: ByteArray, offset: Long): Long =
        Integer.toUnsignedLong(int32s.get(bytes, byteOffset(bytes, offset, 4, "32-bit")) as Int)
    @JvmStatic fun writeInt32ByteOffset(bytes: ByteArray, offset: Long, value: Long) {
        int32s.set(bytes, byteOffset(bytes, offset, 4, "32-bit"), value.toInt())
    }
    // Floating accesses preserve defined raw bits; signaling NaNs have no portable bit-copy promise.
    @JvmStatic fun readFloat(bytes: ByteArray, index: Long): Float =
        floats.get(bytes, elementOffset(bytes, index, 4, "Float")) as Float
    @JvmStatic fun writeFloat(bytes: ByteArray, index: Long, value: Float) {
        floats.set(bytes, elementOffset(bytes, index, 4, "Float"), value)
    }
    @JvmStatic fun readFloatByteOffset(bytes: ByteArray, offset: Long): Float =
        floats.get(bytes, byteOffset(bytes, offset, 4, "Float")) as Float
    @JvmStatic fun writeFloatByteOffset(bytes: ByteArray, offset: Long, value: Float) {
        floats.set(bytes, byteOffset(bytes, offset, 4, "Float"), value)
    }
    @JvmStatic fun readDouble(bytes: ByteArray, index: Long): Double =
        doubles.get(bytes, elementOffset(bytes, index, 8, "Double")) as Double
    @JvmStatic fun writeDouble(bytes: ByteArray, index: Long, value: Double) {
        doubles.set(bytes, elementOffset(bytes, index, 8, "Double"), value)
    }
    @JvmStatic fun readDoubleByteOffset(bytes: ByteArray, offset: Long): Double =
        doubles.get(bytes, byteOffset(bytes, offset, 8, "Double")) as Double
    @JvmStatic fun writeDoubleByteOffset(bytes: ByteArray, offset: Long, value: Double) {
        doubles.set(bytes, byteOffset(bytes, offset, 8, "Double"), value)
    }

    @JvmStatic fun size(bytes: ByteArray): Long = bytes.size.toLong()
    private fun index(bytes: ByteArray, offset: Long): Int {
        if (offset < 0 || offset >= bytes.size.toLong()) fault("ByteArray# index outside its backing storage")
        return offset.toInt()
    }
    @JvmStatic fun read(bytes: ByteArray, offset: Long): Long = bytes[index(bytes, offset)].toLong() and 255L
    @JvmStatic fun readSigned(bytes: ByteArray, offset: Long): Long = bytes[index(bytes, offset)].toLong()
    @JvmStatic fun write(bytes: ByteArray, offset: Long, value: Long) { bytes[index(bytes, offset)] = value.toByte() }
    /** GHC requires distinct immutable/mutable arrays and fully contained ranges.
     * Subtraction checks every full-width range before any narrowing or write. */
    @JvmStatic fun copy(source: ByteArray, sourceOffset: Long, destination: ByteArray, destinationOffset: Long, count: Long) {
        if (source === destination) fault("copyByteArray# requires distinct source and destination arrays")
        fun contained(size: Long, offset: Long) = offset >= 0 && offset <= size && count >= 0 && count <= size - offset
        if (!contained(source.size.toLong(), sourceOffset) || !contained(destination.size.toLong(), destinationOffset))
            fault("ByteArray# copy range outside its backing storage")
        System.arraycopy(source, sourceOffset.toInt(), destination, destinationOffset.toInt(), count.toInt())
    }
    /** Mutable copy has memmove semantics. The separate non-overlapping primop
     * allows one backing array only when the two contained regions are disjoint. */
    @JvmStatic fun copyMutable(source: ByteArray, sourceOffset: Long, destination: ByteArray,
        destinationOffset: Long, count: Long, nonOverlapping: Boolean) {
        fun contained(size: Long, offset: Long) = offset >= 0 && offset <= size && count >= 0 && count <= size - offset
        if (!contained(source.size.toLong(), sourceOffset) || !contained(destination.size.toLong(), destinationOffset))
            fault("ByteArray# copy range outside its backing storage")
        if (nonOverlapping && source === destination && count > 0 &&
            sourceOffset < destinationOffset + count && destinationOffset < sourceOffset + count)
            fault("copyMutableByteArrayNonOverlapping# requires disjoint regions")
        System.arraycopy(source, sourceOffset.toInt(), destination, destinationOffset.toInt(), count.toInt())
    }
    /** GHC lowers the fill to memset: only the low eight bits of Int# are stored. */
    @JvmStatic fun fill(bytes: ByteArray, offset: Long, count: Long, value: Long) {
        val size = bytes.size.toLong()
        if (offset < 0 || offset > size || count < 0 || count > size - offset)
            fault("ByteArray# fill range outside its backing storage")
        java.util.Arrays.fill(bytes, offset.toInt(), (offset + count).toInt(), value.toByte())
    }
    /** GHC promises only the sign of the unsigned lexicographic comparison.
     * Validate both full-width ranges before narrowing; aliases are harmless. */
    @JvmStatic fun compare(first: ByteArray, firstOffset: Long, second: ByteArray, secondOffset: Long, count: Long): Long {
        fun contained(size: Long, offset: Long) = offset >= 0 && offset <= size && count >= 0 && count <= size - offset
        if (!contained(first.size.toLong(), firstOffset) || !contained(second.size.toLong(), secondOffset))
            fault("ByteArray# comparison range outside its backing storage")
        return java.util.Arrays.compareUnsigned(first, firstOffset.toInt(), (firstOffset + count).toInt(),
            second, secondOffset.toInt(), (secondOffset + count).toInt()).toLong()
    }
    /** GHC forbids accessing the original reference after resize. A replacement
     * preserves the prefix while keeping the JVM array length exact for all views.
     * Newly grown storage is unspecified to guests despite JVM zero initialization. */
    @JvmStatic fun resize(bytes: ByteArray, size: Long): ByteArray {
        if (size < 0 || size > Int.MAX_VALUE.toLong()) fault("ByteArray# size outside the managed allocation domain")
        if (size == bytes.size.toLong()) return bytes
        return bytes.copyOf(size.toInt())
    }
    /** Unsafe freeze changes the static type, not the array or its identity. */
    @JvmStatic fun freeze(bytes: ByteArray): ByteArray = bytes
    @JvmStatic fun freezeGuest(value: Any?): Any = when (value) {
        is ManagedAllocation -> value
        is ByteArray -> value
        else -> fault("Expected a managed ByteArray#")
    }
    @JvmStatic fun resizeGuest(value: Any?, size: Long): Any = when (value) {
        is ManagedAllocation -> value.resized(size)
        is ByteArray -> resize(value, size)
        else -> fault("Expected a managed ByteArray#")
    }
    @JvmStatic fun shrinkGuest(value: Any?, size: Long) {
        val allocation = value as? ManagedAllocation
            ?: fault("shrinkMutableByteArray# requires an owned MutableByteArray#")
        allocation.shrink(size)
    }
    @JvmStatic fun sizeGuest(value: Any?): Long = when (value) {
        is ManagedAllocation -> value.size
        is ByteArray -> size(value)
        else -> fault("Expected a managed ByteArray#")
    }
    @JvmStatic fun writeGuest(value: Any?, offset: Long, byteValue: Long) = when (value) {
        is ManagedAllocation -> value.writeByte(offset, byteValue)
        else -> write(require(value), offset, byteValue)
    }
    @JvmStatic fun readGuest(value: Any?, offset: Long, unsigned: Boolean): Long {
        val byte = if (value is ManagedAllocation) value.readByte(offset) else read(require(value), offset)
        return if (unsigned) byte else byte.toByte().toLong()
    }
    private inline fun <T> withElement(value: Any?, index: Long, width: Int,
        writable: Boolean, action: (ByteArray) -> T): T =
        if (value is ManagedAllocation) value.accessElement(index, width, writable, action)
        else action(require(value))

    private inline fun <T> withByteRange(value: Any?, offset: Long, width: Int,
        writable: Boolean, action: (ByteArray) -> T): T =
        if (value is ManagedAllocation) value.accessByteRange(offset, width, writable, action)
        else action(require(value))

    @JvmStatic @JvmOverloads fun readIntGuest(value: Any?, index: Long, byteOffset: Boolean = false): Long =
        if (byteOffset) withByteRange(value, index, 8, writable = false) {
            ints.get(it, byteOffset(it, index, 8, "Int")) as Long
        } else withElement(value, index, 8, writable = false) { readInt(it, index) }
    @JvmStatic @JvmOverloads fun writeIntGuest(value: Any?, index: Long, integer: Long, byteOffset: Boolean = false) =
        if (byteOffset) withByteRange(value, index, 8, writable = true) {
            ints.set(it, byteOffset(it, index, 8, "Int"), integer)
        } else withElement(value, index, 8, writable = true) { writeInt(it, index, integer) }
    @JvmStatic fun readDoubleGuest(value: Any?, index: Long): Double =
        withElement(value, index, 8, writable = false) { readDouble(it, index) }
    @JvmStatic fun writeDoubleGuest(value: Any?, index: Long, number: Double) =
        withElement(value, index, 8, writable = true) { writeDouble(it, index, number) }
    @JvmStatic fun readDoubleByteOffsetGuest(value: Any?, offset: Long): Double =
        withByteRange(value, offset, 8, writable = false) { readDoubleByteOffset(it, offset) }
    @JvmStatic fun writeDoubleByteOffsetGuest(value: Any?, offset: Long, number: Double) =
        withByteRange(value, offset, 8, writable = true) { writeDoubleByteOffset(it, offset, number) }
    @JvmStatic fun readFloatGuest(value: Any?, index: Long): Float =
        withElement(value, index, 4, writable = false) { readFloat(it, index) }
    @JvmStatic fun writeFloatGuest(value: Any?, index: Long, number: Float) =
        withElement(value, index, 4, writable = true) { writeFloat(it, index, number) }
    @JvmStatic fun readFloatByteOffsetGuest(value: Any?, offset: Long): Float =
        withByteRange(value, offset, 4, writable = false) { readFloatByteOffset(it, offset) }
    @JvmStatic fun writeFloatByteOffsetGuest(value: Any?, offset: Long, number: Float) =
        withByteRange(value, offset, 4, writable = true) { writeFloatByteOffset(it, offset, number) }
    @JvmStatic fun readInt16Guest(value: Any?, index: Long, unsigned: Boolean): Long =
        withElement(value, index, 2, writable = false) { if (unsigned) readWord16(it, index) else readInt16(it, index) }
    @JvmStatic fun writeInt16Guest(value: Any?, index: Long, integer: Long) =
        withElement(value, index, 2, writable = true) { writeInt16(it, index, integer) }
    @JvmStatic fun readInt16ByteOffsetGuest(value: Any?, offset: Long, unsigned: Boolean): Long =
        withByteRange(value, offset, 2, writable = false) { if (unsigned) readWord16ByteOffset(it, offset) else readInt16ByteOffset(it, offset) }
    @JvmStatic fun writeInt16ByteOffsetGuest(value: Any?, offset: Long, integer: Long) =
        withByteRange(value, offset, 2, writable = true) { writeInt16ByteOffset(it, offset, integer) }
    @JvmStatic fun readInt32Guest(value: Any?, index: Long, unsigned: Boolean): Long =
        withElement(value, index, 4, writable = false) { if (unsigned) readWord32(it, index) else readInt32(it, index) }
    @JvmStatic fun writeInt32Guest(value: Any?, index: Long, integer: Long) =
        withElement(value, index, 4, writable = true) { writeInt32(it, index, integer) }
    @JvmStatic fun readInt32ByteOffsetGuest(value: Any?, offset: Long, unsigned: Boolean): Long =
        withByteRange(value, offset, 4, writable = false) { if (unsigned) readWord32ByteOffset(it, offset) else readInt32ByteOffset(it, offset) }
    @JvmStatic fun writeInt32ByteOffsetGuest(value: Any?, offset: Long, integer: Long) =
        withByteRange(value, offset, 4, writable = true) { writeInt32ByteOffset(it, offset, integer) }
    @JvmStatic fun fillGuest(value: Any?, offset: Long, count: Long, byteValue: Long) = when (value) {
        is ManagedAllocation -> value.fill(offset, count, byteValue)
        else -> fill(require(value), offset, count, byteValue)
    }
    @JvmStatic fun copyGuest(source: Any?, sourceOffset: Long, destination: Any?,
        destinationOffset: Long, count: Long, mutable: Boolean, nonOverlapping: Boolean = false) {
        if (source is ByteArray && destination is ByteArray) {
            if (mutable) copyMutable(source, sourceOffset, destination, destinationOffset, count, nonOverlapping)
            else copy(source, sourceOffset, destination, destinationOffset, count)
            return
        }
        copyPinned(source, sourceOffset, destination, destinationOffset, count, mutable, nonOverlapping)
    }
    @TruffleBoundary
    private fun copyPinned(source: Any?, sourceOffset: Long, destination: Any?,
        destinationOffset: Long, count: Long, mutable: Boolean, nonOverlapping: Boolean) {
        val fromSize = sizeGuest(source)
        val toSize = sizeGuest(destination)
        fun contained(size: Long, offset: Long) = offset >= 0 && offset <= size &&
            count >= 0 && count <= size - offset
        if (!contained(fromSize, sourceOffset) || !contained(toSize, destinationOffset))
            fault("ByteArray# copy range outside its backing storage")
        if (!mutable && source === destination) fault("copyByteArray# requires distinct source and destination arrays")
        if (nonOverlapping && source === destination && count > 0 &&
            sourceOffset < destinationOffset + count && destinationOffset < sourceOffset + count)
            fault("copyMutableByteArrayNonOverlapping# requires disjoint regions")
        when {
            source is ManagedAllocation && destination is ManagedAllocation ->
                destination.copyFrom(source, sourceOffset, destinationOffset, count)
            source is ManagedAllocation -> {
                val bytes = source.copyBytesOut(sourceOffset, count)
                System.arraycopy(bytes, 0, require(destination), destinationOffset.toInt(), count.toInt())
            }
            destination is ManagedAllocation ->
                destination.copyBytesIn(require(source), sourceOffset.toInt(), destinationOffset, count)
            else -> fault("Expected a managed ByteArray#")
        }
    }
    @JvmStatic fun compareGuest(first: Any?, firstOffset: Long, second: Any?, secondOffset: Long,
        count: Long): Long {
        if (first is ByteArray && second is ByteArray)
            return compare(first, firstOffset, second, secondOffset, count)
        fun segment(value: Any?, offset: Long): ByteArray {
            val length = sizeGuest(value)
            if (offset < 0 || offset > length || count < 0 || count > length - offset)
                fault("ByteArray# comparison range outside its backing storage")
            return if (value is ManagedAllocation) value.copyBytesOut(offset, count)
            else require(value).copyOfRange(offset.toInt(), (offset + count).toInt())
        }
        return java.util.Arrays.compareUnsigned(segment(first, firstOffset), segment(second, secondOffset)).toLong()
    }
    @JvmStatic fun allocate(size: Long): ByteArray {
        if (size < 0 || size > Int.MAX_VALUE.toLong()) fault("ByteArray# size outside the managed allocation domain")
        return ByteArray(size.toInt())
    }
    @JvmStatic fun allocateGuest(size: Long): ManagedAllocation =
        ManagedAllocation.mutable(size, ValueLayout.ADDRESS.byteSize().toInt())
    private inline fun <T> vectorGuest(value: Any?, index: Long, scalarOffset: Boolean,
        scalarWidth: Int, writable: Boolean, vectorBytes: Int, action: (ByteArray) -> T): T =
        if (value is ManagedAllocation)
            value.accessVector(index, scalarOffset, scalarWidth, writable, vectorBytes, action)
        else action(require(value))
    @JvmStatic @JvmOverloads fun readByteVectorGuest(value: Any?, index: Long, scalarOffset: Boolean, vectorBytes: Int = 16): ByteVector =
        vectorGuest(value, index, scalarOffset, 1, false, vectorBytes) { readByteVectorArray(it, index, scalarOffset, vectorBytes) }
    @JvmStatic @JvmOverloads fun writeByteVectorGuest(value: Any?, index: Long, vector: ByteVector, scalarOffset: Boolean, vectorBytes: Int = 16) =
        vectorGuest(value, index, scalarOffset, 1, true, vectorBytes) { writeByteVectorArray(it, index, vector, scalarOffset, vectorBytes) }
    @JvmStatic @JvmOverloads fun readShortVectorGuest(value: Any?, index: Long, scalarOffset: Boolean, vectorBytes: Int = 16): ShortVector =
        vectorGuest(value, index, scalarOffset, 2, false, vectorBytes) { readShortVectorArray(it, index, scalarOffset, vectorBytes) }
    @JvmStatic @JvmOverloads fun writeShortVectorGuest(value: Any?, index: Long, vector: ShortVector, scalarOffset: Boolean, vectorBytes: Int = 16) =
        vectorGuest(value, index, scalarOffset, 2, true, vectorBytes) { writeShortVectorArray(it, index, vector, scalarOffset, vectorBytes) }
    @JvmStatic @JvmOverloads fun readLongVectorGuest(value: Any?, index: Long, scalarOffset: Boolean, vectorBytes: Int = 16): LongVector =
        vectorGuest(value, index, scalarOffset, 8, false, vectorBytes) { readLongVectorArray(it, index, scalarOffset, vectorBytes) }
    @JvmStatic @JvmOverloads fun writeLongVectorGuest(value: Any?, index: Long, vector: LongVector, scalarOffset: Boolean, vectorBytes: Int = 16) =
        vectorGuest(value, index, scalarOffset, 8, true, vectorBytes) { writeLongVectorArray(it, index, vector, scalarOffset, vectorBytes) }
    @JvmStatic @JvmOverloads fun readInt32VectorGuest(value: Any?, index: Long, scalarOffset: Boolean, vectorBytes: Int = 16): IntVector =
        vectorGuest(value, index, scalarOffset, 4, false, vectorBytes) { readIntVectorArray(it, index, scalarOffset, "Int32X4", vectorBytes) }
    @JvmStatic @JvmOverloads fun writeInt32VectorGuest(value: Any?, index: Long, vector: IntVector, scalarOffset: Boolean, vectorBytes: Int = 16) =
        vectorGuest(value, index, scalarOffset, 4, true, vectorBytes) { writeIntVectorArray(it, index, vector, scalarOffset, "Int32X4", vectorBytes) }
    @JvmStatic @JvmOverloads fun readWord32VectorGuest(value: Any?, index: Long, scalarOffset: Boolean, vectorBytes: Int = 16): IntVector =
        vectorGuest(value, index, scalarOffset, 4, false, vectorBytes) { readIntVectorArray(it, index, scalarOffset, "Word32X4", vectorBytes) }
    @JvmStatic @JvmOverloads fun writeWord32VectorGuest(value: Any?, index: Long, vector: IntVector, scalarOffset: Boolean, vectorBytes: Int = 16) =
        vectorGuest(value, index, scalarOffset, 4, true, vectorBytes) { writeIntVectorArray(it, index, vector, scalarOffset, "Word32X4", vectorBytes) }
    @JvmStatic @JvmOverloads fun readFloatVectorGuest(value: Any?, index: Long, scalarOffset: Boolean, vectorBytes: Int = 16): FloatVector =
        vectorGuest(value, index, scalarOffset, 4, false, vectorBytes) { readFloatVectorArray(it, index, scalarOffset, vectorBytes) }
    @JvmStatic @JvmOverloads fun writeFloatVectorGuest(value: Any?, index: Long, vector: FloatVector, scalarOffset: Boolean, vectorBytes: Int = 16) =
        vectorGuest(value, index, scalarOffset, 4, true, vectorBytes) { writeFloatVectorArray(it, index, vector, scalarOffset, vectorBytes) }
    @JvmStatic @JvmOverloads fun readDoubleVectorGuest(value: Any?, index: Long, scalarOffset: Boolean, vectorBytes: Int = 16): DoubleVector =
        vectorGuest(value, index, scalarOffset, 8, false, vectorBytes) { readDoubleVectorArray(it, index, scalarOffset, vectorBytes) }
    @JvmStatic @JvmOverloads fun writeDoubleVectorGuest(value: Any?, index: Long, vector: DoubleVector, scalarOffset: Boolean, vectorBytes: Int = 16) =
        vectorGuest(value, index, scalarOffset, 8, true, vectorBytes) { writeDoubleVectorArray(it, index, vector, scalarOffset, vectorBytes) }
    @JvmStatic fun require(value: Any?): ByteArray = when (value) {
        is ByteArray -> value
        is ManagedAllocation -> value.wholeBytesForPrimitive()
        else -> fault("Expected a managed ByteArray#")
    }
    @JvmStatic fun requireState(value: Any?) = requireVoidCarrier(value)
}

private const val BYTE_ARRAY_REP = "BoxedRep (Just Unlifted)"

/** No compact-region or RTS-large-object allocation mode exists here. Explicit
 * logical pinning supplies both guarantees; ordinary allocations promise neither. */
private class PinnedByteArrayExpression(@field:Child private var array: Expr) : Expr() {
    override fun execute(frame: VirtualFrame): Long = executeLong(frame)
    override fun executeLong(frame: VirtualFrame): Long = when (val value = array.execute(frame)) {
        is ManagedAllocation -> if (value.isPinned) 1L else 0L
        is ByteArray -> 0L
        else -> fault("Expected a managed ByteArray#")
    }
}

/** Exact primitive representation contracts, including the logical State# slot. */
internal enum class ByteArrayOp(val primitive: String, private val arguments: List<List<String>>, val tuple: Boolean = false) {
    NEW("newByteArray#", listOf(listOf("IntRep"), emptyList()), true),
    RESIZE("resizeMutableByteArray#", listOf(listOf(BYTE_ARRAY_REP), listOf("IntRep"), emptyList()), true),
    SHRINK("shrinkMutableByteArray#", listOf(listOf(BYTE_ARRAY_REP), listOf("IntRep"), emptyList())),
    WRITE("writeWord8Array#", listOf(listOf(BYTE_ARRAY_REP), listOf("IntRep"), listOf("Word8Rep"), emptyList())),
    WRITE_CHAR("writeCharArray#", listOf(listOf(BYTE_ARRAY_REP), listOf("IntRep"), listOf("WordRep"), emptyList())),
    COPY("copyByteArray#", listOf(listOf(BYTE_ARRAY_REP), listOf("IntRep"), listOf(BYTE_ARRAY_REP),
        listOf("IntRep"), listOf("IntRep"), emptyList())),
    SET("setByteArray#", listOf(listOf(BYTE_ARRAY_REP), listOf("IntRep"), listOf("IntRep"), listOf("IntRep"), emptyList())),
    COPY_MUTABLE("copyMutableByteArray#", listOf(listOf(BYTE_ARRAY_REP), listOf("IntRep"), listOf(BYTE_ARRAY_REP),
        listOf("IntRep"), listOf("IntRep"), emptyList())),
    COPY_MUTABLE_NON_OVERLAPPING("copyMutableByteArrayNonOverlapping#", listOf(listOf(BYTE_ARRAY_REP), listOf("IntRep"), listOf(BYTE_ARRAY_REP),
        listOf("IntRep"), listOf("IntRep"), emptyList())),
    COMPARE("compareByteArrays#", listOf(listOf(BYTE_ARRAY_REP), listOf("IntRep"), listOf(BYTE_ARRAY_REP),
        listOf("IntRep"), listOf("IntRep"))),
    FREEZE("unsafeFreezeByteArray#", listOf(listOf(BYTE_ARRAY_REP), emptyList()), true),
    UNSAFE_THAW("unsafeThawByteArray#", listOf(listOf(BYTE_ARRAY_REP), emptyList()), true),
    IS_PINNED("isByteArrayPinned#", listOf(listOf(BYTE_ARRAY_REP))),
    IS_MUTABLE_PINNED("isMutableByteArrayPinned#", listOf(listOf(BYTE_ARRAY_REP))),
    IS_WEAKLY_PINNED("isByteArrayWeaklyPinned#", listOf(listOf(BYTE_ARRAY_REP))),
    IS_MUTABLE_WEAKLY_PINNED("isMutableByteArrayWeaklyPinned#", listOf(listOf(BYTE_ARRAY_REP))),
    SIZE("sizeofByteArray#", listOf(listOf(BYTE_ARRAY_REP))),
    SIZE_MUTABLE("sizeofMutableByteArray#", listOf(listOf(BYTE_ARRAY_REP))),
    GET_SIZE_MUTABLE("getSizeofMutableByteArray#", listOf(listOf(BYTE_ARRAY_REP), emptyList()), true),
    INDEX("indexWord8Array#", listOf(listOf(BYTE_ARRAY_REP), listOf("IntRep"))),
    INDEX_CHAR("indexCharArray#", listOf(listOf(BYTE_ARRAY_REP), listOf("IntRep"))),
    READ_INT("readIntArray#", listOf(listOf(BYTE_ARRAY_REP), listOf("IntRep"), emptyList()), true),
    WRITE_INT("writeIntArray#", listOf(listOf(BYTE_ARRAY_REP), listOf("IntRep"), listOf("IntRep"), emptyList())),
    INDEX_INT("indexIntArray#", listOf(listOf(BYTE_ARRAY_REP), listOf("IntRep"))),
    READ_INT64("readInt64Array#", listOf(listOf(BYTE_ARRAY_REP), listOf("IntRep"), emptyList()), true),
    WRITE_INT64("writeInt64Array#", listOf(listOf(BYTE_ARRAY_REP), listOf("IntRep"), listOf("Int64Rep"), emptyList())),
    INDEX_INT64("indexInt64Array#", listOf(listOf(BYTE_ARRAY_REP), listOf("IntRep"))),
    READ_WORD64("readWord64Array#", listOf(listOf(BYTE_ARRAY_REP), listOf("IntRep"), emptyList()), true),
    WRITE_WORD64("writeWord64Array#", listOf(listOf(BYTE_ARRAY_REP), listOf("IntRep"), listOf("Word64Rep"), emptyList())),
    INDEX_WORD64("indexWord64Array#", listOf(listOf(BYTE_ARRAY_REP), listOf("IntRep"))),
    READ_DOUBLE("readDoubleArray#", listOf(listOf(BYTE_ARRAY_REP), listOf("IntRep"), emptyList()), true),
    WRITE_DOUBLE("writeDoubleArray#", listOf(listOf(BYTE_ARRAY_REP), listOf("IntRep"), listOf("DoubleRep"), emptyList())),
    INDEX_DOUBLE("indexDoubleArray#", listOf(listOf(BYTE_ARRAY_REP), listOf("IntRep"))),
    READ_WORD8_AS_DOUBLE("readWord8ArrayAsDouble#", listOf(listOf(BYTE_ARRAY_REP), listOf("IntRep"), emptyList()), true),
    WRITE_WORD8_AS_DOUBLE("writeWord8ArrayAsDouble#", listOf(listOf(BYTE_ARRAY_REP), listOf("IntRep"), listOf("DoubleRep"), emptyList())),
    INDEX_WORD8_AS_DOUBLE("indexWord8ArrayAsDouble#", listOf(listOf(BYTE_ARRAY_REP), listOf("IntRep"))),
    READ_INT8("readInt8Array#", listOf(listOf(BYTE_ARRAY_REP), listOf("IntRep"), emptyList()), true),
    WRITE_INT8("writeInt8Array#", listOf(listOf(BYTE_ARRAY_REP), listOf("IntRep"), listOf("Int8Rep"), emptyList())),
    INDEX_INT8("indexInt8Array#", listOf(listOf(BYTE_ARRAY_REP), listOf("IntRep"))),
    READ_WORD8("readWord8Array#", listOf(listOf(BYTE_ARRAY_REP), listOf("IntRep"), emptyList()), true),
    READ_CHAR("readCharArray#", listOf(listOf(BYTE_ARRAY_REP), listOf("IntRep"), emptyList()), true),
    READ_INT16("readInt16Array#", listOf(listOf(BYTE_ARRAY_REP), listOf("IntRep"), emptyList()), true),
    WRITE_INT16("writeInt16Array#", listOf(listOf(BYTE_ARRAY_REP), listOf("IntRep"), listOf("Int16Rep"), emptyList())),
    INDEX_INT16("indexInt16Array#", listOf(listOf(BYTE_ARRAY_REP), listOf("IntRep"))),
    READ_WORD16("readWord16Array#", listOf(listOf(BYTE_ARRAY_REP), listOf("IntRep"), emptyList()), true),
    WRITE_WORD16("writeWord16Array#", listOf(listOf(BYTE_ARRAY_REP), listOf("IntRep"), listOf("Word16Rep"), emptyList())),
    INDEX_WORD16("indexWord16Array#", listOf(listOf(BYTE_ARRAY_REP), listOf("IntRep"))),
    READ_WORD8_AS_INT16("readWord8ArrayAsInt16#", listOf(listOf(BYTE_ARRAY_REP), listOf("IntRep"), emptyList()), true),
    WRITE_WORD8_AS_INT16("writeWord8ArrayAsInt16#", listOf(listOf(BYTE_ARRAY_REP), listOf("IntRep"), listOf("Int16Rep"), emptyList())),
    INDEX_WORD8_AS_INT16("indexWord8ArrayAsInt16#", listOf(listOf(BYTE_ARRAY_REP), listOf("IntRep"))),
    READ_WORD8_AS_WORD16("readWord8ArrayAsWord16#", listOf(listOf(BYTE_ARRAY_REP), listOf("IntRep"), emptyList()), true),
    WRITE_WORD8_AS_WORD16("writeWord8ArrayAsWord16#", listOf(listOf(BYTE_ARRAY_REP), listOf("IntRep"), listOf("Word16Rep"), emptyList())),
    INDEX_WORD8_AS_WORD16("indexWord8ArrayAsWord16#", listOf(listOf(BYTE_ARRAY_REP), listOf("IntRep"))),
    READ_INT32("readInt32Array#", listOf(listOf(BYTE_ARRAY_REP), listOf("IntRep"), emptyList()), true),
    WRITE_INT32("writeInt32Array#", listOf(listOf(BYTE_ARRAY_REP), listOf("IntRep"), listOf("Int32Rep"), emptyList())),
    INDEX_INT32("indexInt32Array#", listOf(listOf(BYTE_ARRAY_REP), listOf("IntRep"))),
    READ_WORD32("readWord32Array#", listOf(listOf(BYTE_ARRAY_REP), listOf("IntRep"), emptyList()), true),
    WRITE_WORD32("writeWord32Array#", listOf(listOf(BYTE_ARRAY_REP), listOf("IntRep"), listOf("Word32Rep"), emptyList())),
    INDEX_WORD32("indexWord32Array#", listOf(listOf(BYTE_ARRAY_REP), listOf("IntRep"))),
    READ_WORD8_AS_INT32("readWord8ArrayAsInt32#", listOf(listOf(BYTE_ARRAY_REP), listOf("IntRep"), emptyList()), true),
    WRITE_WORD8_AS_INT32("writeWord8ArrayAsInt32#", listOf(listOf(BYTE_ARRAY_REP), listOf("IntRep"), listOf("Int32Rep"), emptyList())),
    INDEX_WORD8_AS_INT32("indexWord8ArrayAsInt32#", listOf(listOf(BYTE_ARRAY_REP), listOf("IntRep"))),
    READ_WORD8_AS_WORD32("readWord8ArrayAsWord32#", listOf(listOf(BYTE_ARRAY_REP), listOf("IntRep"), emptyList()), true),
    WRITE_WORD8_AS_WORD32("writeWord8ArrayAsWord32#", listOf(listOf(BYTE_ARRAY_REP), listOf("IntRep"), listOf("Word32Rep"), emptyList())),
    INDEX_WORD8_AS_WORD32("indexWord8ArrayAsWord32#", listOf(listOf(BYTE_ARRAY_REP), listOf("IntRep"))),
    READ_FLOAT("readFloatArray#", listOf(listOf(BYTE_ARRAY_REP), listOf("IntRep"), emptyList()), true),
    WRITE_FLOAT("writeFloatArray#", listOf(listOf(BYTE_ARRAY_REP), listOf("IntRep"), listOf("FloatRep"), emptyList())),
    INDEX_FLOAT("indexFloatArray#", listOf(listOf(BYTE_ARRAY_REP), listOf("IntRep"))),
    READ_WORD8_AS_FLOAT("readWord8ArrayAsFloat#", listOf(listOf(BYTE_ARRAY_REP), listOf("IntRep"), emptyList()), true),
    WRITE_WORD8_AS_FLOAT("writeWord8ArrayAsFloat#", listOf(listOf(BYTE_ARRAY_REP), listOf("IntRep"), listOf("FloatRep"), emptyList())),
    INDEX_WORD8_AS_FLOAT("indexWord8ArrayAsFloat#", listOf(listOf(BYTE_ARRAY_REP), listOf("IntRep"))),
    READ_WORD("readWordArray#", listOf(listOf(BYTE_ARRAY_REP), listOf("IntRep"), emptyList()), true),
    WRITE_WORD("writeWordArray#", listOf(listOf(BYTE_ARRAY_REP), listOf("IntRep"), listOf("WordRep"), emptyList())),
    INDEX_WORD("indexWordArray#", listOf(listOf(BYTE_ARRAY_REP), listOf("IntRep")));

    fun validate(actual: List<CoreRepresentation>, flags: List<*>, result: CoreRepresentation) {
        if (actual.size != arguments.size) throw RuntimeFault("Primitive arity mismatch: $primitive")
        fun scalar(proof: CoreRepresentation, registers: List<String>): Boolean = !proof.isAggregate && !proof.isVector &&
            (proof.kind == CoreKind.LONG || proof.primReps == registers) && proof.kind == when (registers.singleOrNull()) {
                null -> CoreKind.VOID; BYTE_ARRAY_REP -> CoreKind.OBJECT
                "DoubleRep" -> CoreKind.DOUBLE; "FloatRep" -> CoreKind.FLOAT; else -> CoreKind.LONG
            }
        if (flags != List(arguments.size) { false } || actual.indices.any { !scalar(actual[it], arguments[it]) })
            throw RuntimeFault("ByteArray primitive argument representation mismatch: $primitive")
        val payload = listOf(when (this) {
            READ_INT, GET_SIZE_MUTABLE -> "IntRep"; READ_DOUBLE, READ_WORD8_AS_DOUBLE -> "DoubleRep"
            READ_INT64 -> "Int64Rep"; READ_WORD64 -> "Word64Rep"
            READ_INT8 -> "Int8Rep"; READ_WORD8 -> "Word8Rep"
            READ_INT16, READ_WORD8_AS_INT16 -> "Int16Rep"; READ_WORD16, READ_WORD8_AS_WORD16 -> "Word16Rep"
            READ_INT32, READ_WORD8_AS_INT32 -> "Int32Rep"; READ_WORD32, READ_WORD8_AS_WORD32 -> "Word32Rep"
            READ_FLOAT, READ_WORD8_AS_FLOAT -> "FloatRep"; READ_WORD, READ_CHAR -> "WordRep"; else -> BYTE_ARRAY_REP
        })
        val valid = if (tuple) result.isTuple && result.kind == CoreKind.UNKNOWN && result.components!!.size == 2 &&
            scalar(result.components[0], emptyList()) && scalar(result.components[1], payload) &&
            (result.components[1].kind == CoreKind.LONG || result.primReps == payload)
        else scalar(result, when (this) {
            WRITE_INT8, WRITE_INT16, WRITE_WORD16, WRITE_WORD8_AS_INT16, WRITE_WORD8_AS_WORD16,
            WRITE, WRITE_CHAR, WRITE_INT, WRITE_DOUBLE, WRITE_INT32, WRITE_WORD32, WRITE_WORD8_AS_INT32, WRITE_WORD8_AS_WORD32, WRITE_FLOAT, WRITE_WORD,
            WRITE_WORD8_AS_DOUBLE, WRITE_WORD8_AS_FLOAT, WRITE_INT64, WRITE_WORD64, COPY, SET, COPY_MUTABLE, COPY_MUTABLE_NON_OVERLAPPING,
            SHRINK -> emptyList()
            SIZE, SIZE_MUTABLE, INDEX_INT, COMPARE, IS_PINNED, IS_MUTABLE_PINNED,
            IS_WEAKLY_PINNED, IS_MUTABLE_WEAKLY_PINNED -> listOf("IntRep")
            INDEX_INT8 -> listOf("Int8Rep")
            INDEX_INT16, INDEX_WORD8_AS_INT16 -> listOf("Int16Rep")
            INDEX_WORD16, INDEX_WORD8_AS_WORD16 -> listOf("Word16Rep")
            INDEX_INT32, INDEX_WORD8_AS_INT32 -> listOf("Int32Rep"); INDEX_WORD32, INDEX_WORD8_AS_WORD32 -> listOf("Word32Rep")
            INDEX_INT64 -> listOf("Int64Rep"); INDEX_WORD64 -> listOf("Word64Rep")
            INDEX_FLOAT, INDEX_WORD8_AS_FLOAT -> listOf("FloatRep"); INDEX_WORD, INDEX_CHAR -> listOf("WordRep")
            INDEX_DOUBLE, INDEX_WORD8_AS_DOUBLE -> listOf("DoubleRep"); else -> listOf("Word8Rep")
        })
        if (!valid) throw RuntimeFault("ByteArray primitive result representation mismatch: $primitive")
    }
    companion object {
        fun named(name: String): ByteArrayOp? = when (name) {
            "indexWideCharArray#" -> INDEX_WORD32
            "readWideCharArray#" -> READ_WORD32
            "writeWideCharArray#" -> WRITE_WORD32
            "indexWord8ArrayAsInt#", "indexWord8ArrayAsWord#", "indexWord8ArrayAsInt64#", "indexWord8ArrayAsWord64#" -> INDEX_INT
            "indexWord8ArrayAsChar#" -> INDEX_CHAR
            "indexWord8ArrayAsWideChar#" -> INDEX_WORD8_AS_WORD32
            "readWord8ArrayAsInt#", "readWord8ArrayAsWord#", "readWord8ArrayAsInt64#", "readWord8ArrayAsWord64#" -> READ_INT
            "readWord8ArrayAsChar#" -> READ_CHAR
            "readWord8ArrayAsWideChar#" -> READ_WORD8_AS_WORD32
            "writeWord8ArrayAsInt#", "writeWord8ArrayAsWord#", "writeWord8ArrayAsInt64#", "writeWord8ArrayAsWord64#" -> WRITE_INT
            "writeWord8ArrayAsChar#" -> WRITE_CHAR
            "writeWord8ArrayAsWideChar#" -> WRITE_WORD8_AS_WORD32
            else -> entries.firstOrNull { it.primitive == name }
        }
    }
}

internal fun byteArrayExpression(operation: ByteArrayOp, proof: CoreRepresentation, operands: Array<Expr>,
    byteOffset: Boolean = false): Expr =
    when (operation) {
        ByteArrayOp.NEW -> NewByteArrayExpression(operands[0], operands[1])
        ByteArrayOp.RESIZE -> ResizeByteArrayExpression(operands[0], operands[1], operands[2])
        ByteArrayOp.SHRINK -> ShrinkByteArrayExpression(operands[0], operands[1], operands[2])
        ByteArrayOp.FREEZE, ByteArrayOp.UNSAFE_THAW -> FreezeByteArrayExpression(operands[0], operands[1])
        ByteArrayOp.IS_PINNED, ByteArrayOp.IS_MUTABLE_PINNED,
        ByteArrayOp.IS_WEAKLY_PINNED, ByteArrayOp.IS_MUTABLE_WEAKLY_PINNED -> PinnedByteArrayExpression(operands[0])
        ByteArrayOp.WRITE, ByteArrayOp.WRITE_INT8, ByteArrayOp.WRITE_CHAR -> WriteByteArrayExpression(operands[0], operands[1], operands[2], operands[3])
        ByteArrayOp.COPY -> CopyByteArrayExpression(operands[0], operands[1], operands[2], operands[3], operands[4], operands[5])
        ByteArrayOp.SET -> SetByteArrayExpression(operands[0], operands[1], operands[2], operands[3], operands[4])
        ByteArrayOp.COPY_MUTABLE, ByteArrayOp.COPY_MUTABLE_NON_OVERLAPPING -> CopyMutableByteArrayExpression(
            operation == ByteArrayOp.COPY_MUTABLE_NON_OVERLAPPING, operands[0], operands[1], operands[2], operands[3], operands[4], operands[5])
        ByteArrayOp.COMPARE -> CompareByteArraysExpression(operands[0], operands[1], operands[2], operands[3], operands[4])
        ByteArrayOp.SIZE, ByteArrayOp.SIZE_MUTABLE -> SizeByteArrayExpression(operands[0])
        ByteArrayOp.GET_SIZE_MUTABLE -> GetSizeMutableByteArrayExpression(operands[0], operands[1])
        ByteArrayOp.INDEX, ByteArrayOp.INDEX_CHAR -> IndexByteArrayExpression(operands[0], operands[1])
        ByteArrayOp.READ_INT, ByteArrayOp.READ_WORD, ByteArrayOp.READ_INT64, ByteArrayOp.READ_WORD64 ->
            ReadIntArrayExpression(byteOffset, operands[0], operands[1], operands[2])
        ByteArrayOp.WRITE_INT, ByteArrayOp.WRITE_WORD, ByteArrayOp.WRITE_INT64, ByteArrayOp.WRITE_WORD64 ->
            WriteIntArrayExpression(byteOffset, operands[0], operands[1], operands[2], operands[3])
        ByteArrayOp.INDEX_INT, ByteArrayOp.INDEX_WORD, ByteArrayOp.INDEX_INT64, ByteArrayOp.INDEX_WORD64 ->
            IndexIntArrayExpression(byteOffset, operands[0], operands[1])
        ByteArrayOp.READ_DOUBLE, ByteArrayOp.READ_WORD8_AS_DOUBLE -> ReadDoubleArrayExpression(
            operation == ByteArrayOp.READ_WORD8_AS_DOUBLE, operands[0], operands[1], operands[2])
        ByteArrayOp.WRITE_DOUBLE, ByteArrayOp.WRITE_WORD8_AS_DOUBLE -> WriteDoubleArrayExpression(
            operation == ByteArrayOp.WRITE_WORD8_AS_DOUBLE, operands[0], operands[1], operands[2], operands[3])
        ByteArrayOp.INDEX_DOUBLE, ByteArrayOp.INDEX_WORD8_AS_DOUBLE -> IndexDoubleArrayExpression(
            operation == ByteArrayOp.INDEX_WORD8_AS_DOUBLE, operands[0], operands[1])
        ByteArrayOp.READ_FLOAT, ByteArrayOp.READ_WORD8_AS_FLOAT -> ReadFloatArrayExpression(
            operation == ByteArrayOp.READ_WORD8_AS_FLOAT, operands[0], operands[1], operands[2])
        ByteArrayOp.WRITE_FLOAT, ByteArrayOp.WRITE_WORD8_AS_FLOAT -> WriteFloatArrayExpression(
            operation == ByteArrayOp.WRITE_WORD8_AS_FLOAT, operands[0], operands[1], operands[2], operands[3])
        ByteArrayOp.INDEX_FLOAT, ByteArrayOp.INDEX_WORD8_AS_FLOAT -> IndexFloatArrayExpression(
            operation == ByteArrayOp.INDEX_WORD8_AS_FLOAT, operands[0], operands[1])
        ByteArrayOp.READ_INT8, ByteArrayOp.READ_WORD8, ByteArrayOp.READ_CHAR -> ReadByteArrayExpression(
            operation != ByteArrayOp.READ_INT8, operands[0], operands[1], operands[2])
        ByteArrayOp.INDEX_INT8 -> IndexSignedByteArrayExpression(operands[0], operands[1])
        ByteArrayOp.READ_INT16, ByteArrayOp.READ_WORD16, ByteArrayOp.READ_WORD8_AS_INT16, ByteArrayOp.READ_WORD8_AS_WORD16 ->
            ReadInt16ArrayExpression(operation == ByteArrayOp.READ_WORD16 || operation == ByteArrayOp.READ_WORD8_AS_WORD16,
                operation == ByteArrayOp.READ_WORD8_AS_INT16 || operation == ByteArrayOp.READ_WORD8_AS_WORD16,
                operands[0], operands[1], operands[2])
        ByteArrayOp.WRITE_INT16, ByteArrayOp.WRITE_WORD16, ByteArrayOp.WRITE_WORD8_AS_INT16, ByteArrayOp.WRITE_WORD8_AS_WORD16 ->
            WriteInt16ArrayExpression(operation == ByteArrayOp.WRITE_WORD8_AS_INT16 || operation == ByteArrayOp.WRITE_WORD8_AS_WORD16,
                operands[0], operands[1], operands[2], operands[3])
        ByteArrayOp.INDEX_INT16, ByteArrayOp.INDEX_WORD16, ByteArrayOp.INDEX_WORD8_AS_INT16, ByteArrayOp.INDEX_WORD8_AS_WORD16 ->
            IndexInt16ArrayExpression(operation == ByteArrayOp.INDEX_WORD16 || operation == ByteArrayOp.INDEX_WORD8_AS_WORD16,
                operation == ByteArrayOp.INDEX_WORD8_AS_INT16 || operation == ByteArrayOp.INDEX_WORD8_AS_WORD16,
                operands[0], operands[1])
        ByteArrayOp.READ_INT32, ByteArrayOp.READ_WORD32, ByteArrayOp.READ_WORD8_AS_INT32, ByteArrayOp.READ_WORD8_AS_WORD32 -> ReadInt32ArrayExpression(
            operation == ByteArrayOp.READ_WORD32 || operation == ByteArrayOp.READ_WORD8_AS_WORD32,
            operation == ByteArrayOp.READ_WORD8_AS_INT32 || operation == ByteArrayOp.READ_WORD8_AS_WORD32,
            operands[0], operands[1], operands[2])
        ByteArrayOp.WRITE_INT32, ByteArrayOp.WRITE_WORD32, ByteArrayOp.WRITE_WORD8_AS_INT32, ByteArrayOp.WRITE_WORD8_AS_WORD32 -> WriteInt32ArrayExpression(
            operation == ByteArrayOp.WRITE_WORD8_AS_INT32 || operation == ByteArrayOp.WRITE_WORD8_AS_WORD32,
            operands[0], operands[1], operands[2], operands[3])
        ByteArrayOp.INDEX_INT32, ByteArrayOp.INDEX_WORD32, ByteArrayOp.INDEX_WORD8_AS_INT32, ByteArrayOp.INDEX_WORD8_AS_WORD32 -> IndexInt32ArrayExpression(
            operation == ByteArrayOp.INDEX_WORD32 || operation == ByteArrayOp.INDEX_WORD8_AS_WORD32,
            operation == ByteArrayOp.INDEX_WORD8_AS_INT32 || operation == ByteArrayOp.INDEX_WORD8_AS_WORD32,
            operands[0], operands[1])
    }.proven(proof.copy(evaluated = true))

private class NewByteArrayExpression(@field:Child private var size: Expr,
    @field:Child private var state: Expr) : Expr() {
    override fun execute(frame: VirtualFrame): Nothing = fault("Tuple primitive requires a destination")
    override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Any? {
        val count = size.executeRequiredLong(frame)
        ManagedByteArray.requireState(state.execute(frame))
        // The logical State# component has no slot. Publish only after the effect.
        FrameAccess.write(frame, slots[offset], ManagedByteArray.allocateGuest(count))
        return null
    }
}
private class ResizeByteArrayExpression(@field:Child private var array: Expr,
    @field:Child private var size: Expr, @field:Child private var state: Expr) : Expr() {
    override fun execute(frame: VirtualFrame): Nothing = fault("Tuple primitive requires a destination")
    override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Any? {
        val bytes = array.execute(frame)
        val count = size.executeRequiredLong(frame)
        ManagedByteArray.requireState(state.execute(frame))
        FrameAccess.write(frame, slots[offset], ManagedByteArray.resizeGuest(bytes, count))
        return null
    }
}

private class ShrinkByteArrayExpression(@field:Child private var array: Expr,
    @field:Child private var size: Expr, @field:Child private var state: Expr) : Expr() {
    override fun execute(frame: VirtualFrame): Any {
        val bytes = array.execute(frame)
        val count = size.executeRequiredLong(frame)
        ManagedByteArray.requireState(state.execute(frame))
        ManagedByteArray.shrinkGuest(bytes, count)
        return Unit
    }
}

private class FreezeByteArrayExpression(@field:Child private var array: Expr,
    @field:Child private var state: Expr) : Expr() {
    override fun execute(frame: VirtualFrame): Nothing = fault("Tuple primitive requires a destination")
    override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Any? {
        val bytes = array.execute(frame)
        ManagedByteArray.requireState(state.execute(frame))
        FrameAccess.write(frame, slots[offset], ManagedByteArray.freezeGuest(bytes))
        return null
    }
}
private class WriteByteArrayExpression(@field:Child private var array: Expr,
    @field:Child private var index: Expr, @field:Child private var value: Expr,
    @field:Child private var state: Expr) : Expr() {
    override fun execute(frame: VirtualFrame): Any {
        val bytes = array.execute(frame)
        val offset = index.executeRequiredLong(frame)
        val byte = value.executeRequiredLong(frame)
        ManagedByteArray.requireState(state.execute(frame))
        ManagedByteArray.writeGuest(bytes, offset, byte)
        return Unit
    }
}
/** State is evaluated and checked before observing length or publishing a result. */
private class GetSizeMutableByteArrayExpression(@field:Child private var array: Expr,
    @field:Child private var state: Expr) : Expr() {
    override fun execute(frame: VirtualFrame): Nothing = fault("Tuple primitive requires a destination")
    override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Any? {
        val bytes = array.execute(frame)
        ManagedByteArray.requireState(state.execute(frame))
        FrameAccess.writeLong(frame, slots[offset], ManagedByteArray.sizeGuest(bytes))
        return null
    }
}
private class SizeByteArrayExpression(@field:Child private var array: Expr) : Expr() {
    override fun execute(frame: VirtualFrame): Any = executeLong(frame)
    override fun executeLong(frame: VirtualFrame): Long = ManagedByteArray.sizeGuest(array.execute(frame))
}
private class IndexByteArrayExpression(@field:Child private var array: Expr,
    @field:Child private var index: Expr) : Expr() {
    override fun execute(frame: VirtualFrame): Any = executeLong(frame)
    override fun executeLong(frame: VirtualFrame): Long {
        val bytes = array.execute(frame)
        val offset = index.executeRequiredLong(frame)
        return ManagedByteArray.readGuest(bytes, offset, true)
    }
}

private class ReadIntArrayExpression(private val byteOffset: Boolean, @field:Child private var array: Expr,
    @field:Child private var index: Expr, @field:Child private var state: Expr) : Expr() {
    override fun execute(frame: VirtualFrame): Nothing = fault("Tuple primitive requires a destination")
    override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Any? {
        val bytes = array.execute(frame)
        val element = index.executeRequiredLong(frame)
        ManagedByteArray.requireState(state.execute(frame))
        FrameAccess.writeLong(frame, slots[offset], ManagedByteArray.readIntGuest(bytes, element, byteOffset))
        return null
    }
}
private class WriteIntArrayExpression(private val byteOffset: Boolean, @field:Child private var array: Expr,
    @field:Child private var index: Expr, @field:Child private var value: Expr,
    @field:Child private var state: Expr) : Expr() {
    override fun execute(frame: VirtualFrame): Any {
        val bytes = array.execute(frame)
        val element = index.executeRequiredLong(frame)
        val integer = value.executeRequiredLong(frame)
        ManagedByteArray.requireState(state.execute(frame))
        ManagedByteArray.writeIntGuest(bytes, element, integer, byteOffset)
        return Unit
    }
}
private class IndexIntArrayExpression(private val byteOffset: Boolean, @field:Child private var array: Expr,
    @field:Child private var index: Expr) : Expr() {
    override fun execute(frame: VirtualFrame): Any = executeLong(frame)
    override fun executeLong(frame: VirtualFrame): Long {
        val bytes = array.execute(frame)
        val element = index.executeRequiredLong(frame)
        return ManagedByteArray.readIntGuest(bytes, element, byteOffset)
    }
}

private class ReadDoubleArrayExpression(private val byteOffset: Boolean, @field:Child private var array: Expr,
    @field:Child private var index: Expr, @field:Child private var state: Expr) : Expr() {
    override fun execute(frame: VirtualFrame): Nothing = fault("Tuple primitive requires a destination")
    override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Any? {
        val bytes = array.execute(frame)
        val element = index.executeRequiredLong(frame)
        ManagedByteArray.requireState(state.execute(frame))
        FrameAccess.writeDouble(frame, slots[offset], if (byteOffset) ManagedByteArray.readDoubleByteOffsetGuest(bytes, element)
            else ManagedByteArray.readDoubleGuest(bytes, element))
        return null
    }
}
private class WriteDoubleArrayExpression(private val byteOffset: Boolean, @field:Child private var array: Expr,
    @field:Child private var index: Expr, @field:Child private var value: Expr,
    @field:Child private var state: Expr) : Expr() {
    override fun execute(frame: VirtualFrame): Any {
        val bytes = array.execute(frame)
        val element = index.executeRequiredLong(frame)
        val number = value.executeRequiredDouble(frame)
        ManagedByteArray.requireState(state.execute(frame))
        if (byteOffset) ManagedByteArray.writeDoubleByteOffsetGuest(bytes, element, number)
        else ManagedByteArray.writeDoubleGuest(bytes, element, number)
        return Unit
    }
}
private class IndexDoubleArrayExpression(private val byteOffset: Boolean, @field:Child private var array: Expr,
    @field:Child private var index: Expr) : Expr() {
    override fun execute(frame: VirtualFrame): Any = executeDouble(frame)
    override fun executeDouble(frame: VirtualFrame): Double {
        val bytes = array.execute(frame)
        val element = index.executeRequiredLong(frame)
        return if (byteOffset) ManagedByteArray.readDoubleByteOffsetGuest(bytes, element)
            else ManagedByteArray.readDoubleGuest(bytes, element)
    }
}

private class ReadFloatArrayExpression(private val byteOffset: Boolean, @field:Child private var array: Expr,
    @field:Child private var index: Expr, @field:Child private var state: Expr) : Expr() {
    override fun execute(frame: VirtualFrame): Nothing = fault("Tuple primitive requires a destination")
    override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Any? {
        val bytes = array.execute(frame)
        val element = index.executeRequiredLong(frame)
        ManagedByteArray.requireState(state.execute(frame))
        FrameAccess.writeFloat(frame, slots[offset], if (byteOffset) ManagedByteArray.readFloatByteOffsetGuest(bytes, element)
            else ManagedByteArray.readFloatGuest(bytes, element))
        return null
    }
}
private class WriteFloatArrayExpression(private val byteOffset: Boolean, @field:Child private var array: Expr,
    @field:Child private var index: Expr, @field:Child private var value: Expr,
    @field:Child private var state: Expr) : Expr() {
    override fun execute(frame: VirtualFrame): Any {
        val bytes = array.execute(frame)
        val element = index.executeRequiredLong(frame)
        val number = value.executeRequiredFloat(frame)
        ManagedByteArray.requireState(state.execute(frame))
        if (byteOffset) ManagedByteArray.writeFloatByteOffsetGuest(bytes, element, number)
        else ManagedByteArray.writeFloatGuest(bytes, element, number)
        return Unit
    }
}
private class IndexFloatArrayExpression(private val byteOffset: Boolean, @field:Child private var array: Expr,
    @field:Child private var index: Expr) : Expr() {
    override fun execute(frame: VirtualFrame): Any = executeFloat(frame)
    override fun executeFloat(frame: VirtualFrame): Float {
        val bytes = array.execute(frame)
        val element = index.executeRequiredLong(frame)
        return if (byteOffset) ManagedByteArray.readFloatByteOffsetGuest(bytes, element)
            else ManagedByteArray.readFloatGuest(bytes, element)
    }
}

private class ReadInt16ArrayExpression(private val unsigned: Boolean, private val byteOffset: Boolean,
    @field:Child private var array: Expr, @field:Child private var index: Expr,
    @field:Child private var state: Expr) : Expr() {
    override fun execute(frame: VirtualFrame): Nothing = fault("Tuple primitive requires a destination")
    override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Any? {
        val bytes = array.execute(frame)
        val element = index.executeRequiredLong(frame)
        ManagedByteArray.requireState(state.execute(frame))
        val value = if (byteOffset) ManagedByteArray.readInt16ByteOffsetGuest(bytes, element, unsigned)
            else ManagedByteArray.readInt16Guest(bytes, element, unsigned)
        FrameAccess.writeLong(frame, slots[offset], value)
        return null
    }
}
private class WriteInt16ArrayExpression(private val byteOffset: Boolean, @field:Child private var array: Expr,
    @field:Child private var index: Expr, @field:Child private var value: Expr,
    @field:Child private var state: Expr) : Expr() {
    override fun execute(frame: VirtualFrame): Any {
        val bytes = array.execute(frame)
        val element = index.executeRequiredLong(frame)
        val integer = value.executeRequiredLong(frame)
        ManagedByteArray.requireState(state.execute(frame))
        if (byteOffset) ManagedByteArray.writeInt16ByteOffsetGuest(bytes, element, integer)
        else ManagedByteArray.writeInt16Guest(bytes, element, integer)
        return Unit
    }
}
private class IndexInt16ArrayExpression(private val unsigned: Boolean, private val byteOffset: Boolean,
    @field:Child private var array: Expr, @field:Child private var index: Expr) : Expr() {
    override fun execute(frame: VirtualFrame): Any = executeLong(frame)
    override fun executeLong(frame: VirtualFrame): Long {
        val bytes = array.execute(frame)
        val element = index.executeRequiredLong(frame)
        return if (byteOffset) ManagedByteArray.readInt16ByteOffsetGuest(bytes, element, unsigned)
            else ManagedByteArray.readInt16Guest(bytes, element, unsigned)
    }
}

private class ReadInt32ArrayExpression(private val unsigned: Boolean, private val byteOffset: Boolean,
    @field:Child private var array: Expr, @field:Child private var index: Expr,
    @field:Child private var state: Expr) : Expr() {
    override fun execute(frame: VirtualFrame): Nothing = fault("Tuple primitive requires a destination")
    override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Any? {
        val bytes = array.execute(frame)
        val element = index.executeRequiredLong(frame)
        ManagedByteArray.requireState(state.execute(frame))
        val value = if (byteOffset) ManagedByteArray.readInt32ByteOffsetGuest(bytes, element, unsigned)
            else ManagedByteArray.readInt32Guest(bytes, element, unsigned)
        FrameAccess.writeLong(frame, slots[offset], value)
        return null
    }
}
private class WriteInt32ArrayExpression(private val byteOffset: Boolean, @field:Child private var array: Expr,
    @field:Child private var index: Expr, @field:Child private var value: Expr,
    @field:Child private var state: Expr) : Expr() {
    override fun execute(frame: VirtualFrame): Any {
        val bytes = array.execute(frame)
        val element = index.executeRequiredLong(frame)
        val integer = value.executeRequiredLong(frame)
        ManagedByteArray.requireState(state.execute(frame))
        if (byteOffset) ManagedByteArray.writeInt32ByteOffsetGuest(bytes, element, integer)
        else ManagedByteArray.writeInt32Guest(bytes, element, integer)
        return Unit
    }
}
private class IndexInt32ArrayExpression(private val unsigned: Boolean, private val byteOffset: Boolean,
    @field:Child private var array: Expr, @field:Child private var index: Expr) : Expr() {
    override fun execute(frame: VirtualFrame): Any = executeLong(frame)
    override fun executeLong(frame: VirtualFrame): Long {
        val bytes = array.execute(frame)
        val element = index.executeRequiredLong(frame)
        return if (byteOffset) ManagedByteArray.readInt32ByteOffsetGuest(bytes, element, unsigned)
            else ManagedByteArray.readInt32Guest(bytes, element, unsigned)
    }
}

private class CopyByteArrayExpression(@field:Child private var source: Expr,
    @field:Child private var sourceOffset: Expr, @field:Child private var destination: Expr,
    @field:Child private var destinationOffset: Expr, @field:Child private var count: Expr,
    @field:Child private var state: Expr) : Expr() {
    override fun execute(frame: VirtualFrame): Any {
        val from = source.execute(frame)
        val fromOffset = sourceOffset.executeRequiredLong(frame)
        val to = destination.execute(frame)
        val toOffset = destinationOffset.executeRequiredLong(frame)
        val length = count.executeRequiredLong(frame)
        ManagedByteArray.requireState(state.execute(frame))
        ManagedByteArray.copyGuest(from, fromOffset, to, toOffset, length, false)
        return Unit
    }
}

private class CompareByteArraysExpression(@field:Child private var first: Expr,
    @field:Child private var firstOffset: Expr, @field:Child private var second: Expr,
    @field:Child private var secondOffset: Expr, @field:Child private var count: Expr) : Expr() {
    override fun execute(frame: VirtualFrame): Any = executeLong(frame)
    override fun executeLong(frame: VirtualFrame): Long {
        val left = first.execute(frame)
        val from = firstOffset.executeRequiredLong(frame)
        val right = second.execute(frame)
        val to = secondOffset.executeRequiredLong(frame)
        val length = count.executeRequiredLong(frame)
        return ManagedByteArray.compareGuest(left, from, right, to, length)
    }
}

private class ReadByteArrayExpression(private val unsigned: Boolean,
    @field:Child private var array: Expr, @field:Child private var index: Expr,
    @field:Child private var state: Expr) : Expr() {
    override fun execute(frame: VirtualFrame): Nothing = fault("Tuple primitive requires a destination")
    override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Any? {
        val bytes = array.execute(frame)
        val element = index.executeRequiredLong(frame)
        ManagedByteArray.requireState(state.execute(frame))
        val value = ManagedByteArray.readGuest(bytes, element, unsigned)
        FrameAccess.writeLong(frame, slots[offset], value)
        return null
    }
}
private class IndexSignedByteArrayExpression(@field:Child private var array: Expr,
    @field:Child private var index: Expr) : Expr() {
    override fun execute(frame: VirtualFrame): Any = executeLong(frame)
    override fun executeLong(frame: VirtualFrame): Long {
        val bytes = array.execute(frame)
        val element = index.executeRequiredLong(frame)
        return ManagedByteArray.readGuest(bytes, element, false)
    }
}

private class SetByteArrayExpression(@field:Child private var array: Expr, @field:Child private var offset: Expr,
    @field:Child private var count: Expr, @field:Child private var value: Expr, @field:Child private var state: Expr) : Expr() {
    override fun execute(frame: VirtualFrame): Any {
        val bytes = array.execute(frame)
        val start = offset.executeRequiredLong(frame)
        val length = count.executeRequiredLong(frame)
        val byte = value.executeRequiredLong(frame)
        ManagedByteArray.requireState(state.execute(frame))
        ManagedByteArray.fillGuest(bytes, start, length, byte)
        return Unit
    }
}
private class CopyMutableByteArrayExpression(private val nonOverlapping: Boolean,
    @field:Child private var source: Expr, @field:Child private var sourceOffset: Expr,
    @field:Child private var destination: Expr, @field:Child private var destinationOffset: Expr,
    @field:Child private var count: Expr, @field:Child private var state: Expr) : Expr() {
    override fun execute(frame: VirtualFrame): Any {
        val from = source.execute(frame)
        val start = sourceOffset.executeRequiredLong(frame)
        val to = destination.execute(frame)
        val target = destinationOffset.executeRequiredLong(frame)
        val length = count.executeRequiredLong(frame)
        ManagedByteArray.requireState(state.execute(frame))
        ManagedByteArray.copyGuest(from, start, to, target, length, true, nonOverlapping)
        return Unit
    }
}
