// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.frame.VirtualFrame

/** The primitive JVM byte[] itself is the one unlifted guest reference. No
 * wrapper or per-byte boxing. Its mutable elements are never CompilationFinal.
 * The JVM initializes allocations; guests must still initialize before reading.
 */
internal object ManagedByteArray {
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
    @JvmStatic fun allocate(size: Long): ByteArray {
        if (size < 0 || size > Int.MAX_VALUE.toLong()) fault("ByteArray# size outside the managed allocation domain")
        return ByteArray(size.toInt())
    }
    @JvmStatic fun require(value: Any?): ByteArray = value as? ByteArray ?: fault("Expected a managed ByteArray#")
    @JvmStatic fun requireState(value: Any?) = requireVoidCarrier(value)
}

private const val BYTE_ARRAY_REP = "BoxedRep (Just Unlifted)"

/** Exact primitive representation contracts, including the logical State# slot. */
internal enum class ByteArrayOp(val primitive: String, private val arguments: List<List<String>>, val tuple: Boolean = false) {
    NEW("newByteArray#", listOf(listOf("IntRep"), emptyList()), true),
    RESIZE("resizeMutableByteArray#", listOf(listOf(BYTE_ARRAY_REP), listOf("IntRep"), emptyList()), true),
    WRITE("writeWord8Array#", listOf(listOf(BYTE_ARRAY_REP), listOf("IntRep"), listOf("Word8Rep"), emptyList())),
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
    SIZE("sizeofByteArray#", listOf(listOf(BYTE_ARRAY_REP))),
    SIZE_MUTABLE("sizeofMutableByteArray#", listOf(listOf(BYTE_ARRAY_REP))),
    GET_SIZE_MUTABLE("getSizeofMutableByteArray#", listOf(listOf(BYTE_ARRAY_REP), emptyList()), true),
    INDEX("indexWord8Array#", listOf(listOf(BYTE_ARRAY_REP), listOf("IntRep"))),
    READ_INT("readIntArray#", listOf(listOf(BYTE_ARRAY_REP), listOf("IntRep"), emptyList()), true),
    WRITE_INT("writeIntArray#", listOf(listOf(BYTE_ARRAY_REP), listOf("IntRep"), listOf("IntRep"), emptyList())),
    INDEX_INT("indexIntArray#", listOf(listOf(BYTE_ARRAY_REP), listOf("IntRep"))),
    READ_DOUBLE("readDoubleArray#", listOf(listOf(BYTE_ARRAY_REP), listOf("IntRep"), emptyList()), true),
    WRITE_DOUBLE("writeDoubleArray#", listOf(listOf(BYTE_ARRAY_REP), listOf("IntRep"), listOf("DoubleRep"), emptyList())),
    INDEX_DOUBLE("indexDoubleArray#", listOf(listOf(BYTE_ARRAY_REP), listOf("IntRep"))),
    READ_INT8("readInt8Array#", listOf(listOf(BYTE_ARRAY_REP), listOf("IntRep"), emptyList()), true),
    WRITE_INT8("writeInt8Array#", listOf(listOf(BYTE_ARRAY_REP), listOf("IntRep"), listOf("Int8Rep"), emptyList())),
    INDEX_INT8("indexInt8Array#", listOf(listOf(BYTE_ARRAY_REP), listOf("IntRep"))),
    READ_WORD8("readWord8Array#", listOf(listOf(BYTE_ARRAY_REP), listOf("IntRep"), emptyList()), true),
    READ_INT16("readInt16Array#", listOf(listOf(BYTE_ARRAY_REP), listOf("IntRep"), emptyList()), true),
    WRITE_INT16("writeInt16Array#", listOf(listOf(BYTE_ARRAY_REP), listOf("IntRep"), listOf("Int16Rep"), emptyList())),
    INDEX_INT16("indexInt16Array#", listOf(listOf(BYTE_ARRAY_REP), listOf("IntRep"))),
    READ_WORD16("readWord16Array#", listOf(listOf(BYTE_ARRAY_REP), listOf("IntRep"), emptyList()), true),
    WRITE_WORD16("writeWord16Array#", listOf(listOf(BYTE_ARRAY_REP), listOf("IntRep"), listOf("Word16Rep"), emptyList())),
    INDEX_WORD16("indexWord16Array#", listOf(listOf(BYTE_ARRAY_REP), listOf("IntRep"))),
    READ_INT32("readInt32Array#", listOf(listOf(BYTE_ARRAY_REP), listOf("IntRep"), emptyList()), true),
    WRITE_INT32("writeInt32Array#", listOf(listOf(BYTE_ARRAY_REP), listOf("IntRep"), listOf("Int32Rep"), emptyList())),
    INDEX_INT32("indexInt32Array#", listOf(listOf(BYTE_ARRAY_REP), listOf("IntRep"))),
    READ_WORD32("readWord32Array#", listOf(listOf(BYTE_ARRAY_REP), listOf("IntRep"), emptyList()), true),
    WRITE_WORD32("writeWord32Array#", listOf(listOf(BYTE_ARRAY_REP), listOf("IntRep"), listOf("Word32Rep"), emptyList())),
    INDEX_WORD32("indexWord32Array#", listOf(listOf(BYTE_ARRAY_REP), listOf("IntRep"))),
    READ_FLOAT("readFloatArray#", listOf(listOf(BYTE_ARRAY_REP), listOf("IntRep"), emptyList()), true),
    WRITE_FLOAT("writeFloatArray#", listOf(listOf(BYTE_ARRAY_REP), listOf("IntRep"), listOf("FloatRep"), emptyList())),
    INDEX_FLOAT("indexFloatArray#", listOf(listOf(BYTE_ARRAY_REP), listOf("IntRep"))),
    READ_WORD("readWordArray#", listOf(listOf(BYTE_ARRAY_REP), listOf("IntRep"), emptyList()), true),
    WRITE_WORD("writeWordArray#", listOf(listOf(BYTE_ARRAY_REP), listOf("IntRep"), listOf("WordRep"), emptyList())),
    INDEX_WORD("indexWordArray#", listOf(listOf(BYTE_ARRAY_REP), listOf("IntRep")));

    fun validate(actual: List<CoreRepresentation>, flags: List<*>, result: CoreRepresentation) {
        if (actual.size != arguments.size) throw RuntimeFault("Primitive arity mismatch: $primitive")
        fun scalar(proof: CoreRepresentation, registers: List<String>): Boolean = !proof.isAggregate &&
            proof.primReps == registers && proof.kind == when (registers.singleOrNull()) {
                null -> CoreKind.VOID; BYTE_ARRAY_REP -> CoreKind.OBJECT
                "DoubleRep" -> CoreKind.DOUBLE; "FloatRep" -> CoreKind.FLOAT; else -> CoreKind.LONG
            }
        if (flags != List(arguments.size) { false } || actual.indices.any { !scalar(actual[it], arguments[it]) })
            throw RuntimeFault("ByteArray primitive argument representation mismatch: $primitive")
        val payload = listOf(when (this) {
            READ_INT, GET_SIZE_MUTABLE -> "IntRep"; READ_DOUBLE -> "DoubleRep"
            READ_INT8 -> "Int8Rep"; READ_WORD8 -> "Word8Rep"
            READ_INT16 -> "Int16Rep"; READ_WORD16 -> "Word16Rep"
            READ_INT32 -> "Int32Rep"; READ_WORD32 -> "Word32Rep"
            READ_FLOAT -> "FloatRep"; READ_WORD -> "WordRep"; else -> BYTE_ARRAY_REP
        })
        val valid = if (tuple) result.isTuple && result.kind == CoreKind.UNKNOWN && result.components!!.size == 2 &&
            scalar(result.components[0], emptyList()) && scalar(result.components[1], payload) &&
            result.primReps == payload
        else scalar(result, when (this) {
            WRITE_INT8, WRITE_INT16, WRITE_WORD16, WRITE, WRITE_INT, WRITE_DOUBLE, WRITE_INT32, WRITE_WORD32, WRITE_FLOAT, WRITE_WORD, COPY, SET, COPY_MUTABLE, COPY_MUTABLE_NON_OVERLAPPING -> emptyList()
            SIZE, SIZE_MUTABLE, INDEX_INT, COMPARE -> listOf("IntRep")
            INDEX_INT8 -> listOf("Int8Rep")
            INDEX_INT16 -> listOf("Int16Rep"); INDEX_WORD16 -> listOf("Word16Rep")
            INDEX_INT32 -> listOf("Int32Rep"); INDEX_WORD32 -> listOf("Word32Rep")
            INDEX_FLOAT -> listOf("FloatRep"); INDEX_WORD -> listOf("WordRep")
            INDEX_DOUBLE -> listOf("DoubleRep"); else -> listOf("Word8Rep")
        })
        if (!valid) throw RuntimeFault("ByteArray primitive result representation mismatch: $primitive")
    }
    companion object {
        fun named(name: String): ByteArrayOp? = entries.firstOrNull { it.primitive == name }
    }
}

internal fun byteArrayExpression(operation: ByteArrayOp, proof: CoreRepresentation, operands: Array<Expr>): Expr =
    when (operation) {
        ByteArrayOp.NEW -> NewByteArrayExpression(operands[0], operands[1])
        ByteArrayOp.RESIZE -> ResizeByteArrayExpression(operands[0], operands[1], operands[2])
        ByteArrayOp.FREEZE -> FreezeByteArrayExpression(operands[0], operands[1])
        ByteArrayOp.WRITE, ByteArrayOp.WRITE_INT8 -> WriteByteArrayExpression(operands[0], operands[1], operands[2], operands[3])
        ByteArrayOp.COPY -> CopyByteArrayExpression(operands[0], operands[1], operands[2], operands[3], operands[4], operands[5])
        ByteArrayOp.SET -> SetByteArrayExpression(operands[0], operands[1], operands[2], operands[3], operands[4])
        ByteArrayOp.COPY_MUTABLE, ByteArrayOp.COPY_MUTABLE_NON_OVERLAPPING -> CopyMutableByteArrayExpression(
            operation == ByteArrayOp.COPY_MUTABLE_NON_OVERLAPPING, operands[0], operands[1], operands[2], operands[3], operands[4], operands[5])
        ByteArrayOp.COMPARE -> CompareByteArraysExpression(operands[0], operands[1], operands[2], operands[3], operands[4])
        ByteArrayOp.SIZE, ByteArrayOp.SIZE_MUTABLE -> SizeByteArrayExpression(operands[0])
        ByteArrayOp.GET_SIZE_MUTABLE -> GetSizeMutableByteArrayExpression(operands[0], operands[1])
        ByteArrayOp.INDEX -> IndexByteArrayExpression(operands[0], operands[1])
        ByteArrayOp.READ_INT, ByteArrayOp.READ_WORD -> ReadIntArrayExpression(operands[0], operands[1], operands[2])
        ByteArrayOp.WRITE_INT, ByteArrayOp.WRITE_WORD -> WriteIntArrayExpression(operands[0], operands[1], operands[2], operands[3])
        ByteArrayOp.INDEX_INT, ByteArrayOp.INDEX_WORD -> IndexIntArrayExpression(operands[0], operands[1])
        ByteArrayOp.READ_DOUBLE -> ReadDoubleArrayExpression(operands[0], operands[1], operands[2])
        ByteArrayOp.WRITE_DOUBLE -> WriteDoubleArrayExpression(operands[0], operands[1], operands[2], operands[3])
        ByteArrayOp.INDEX_DOUBLE -> IndexDoubleArrayExpression(operands[0], operands[1])
        ByteArrayOp.READ_FLOAT -> ReadFloatArrayExpression(operands[0], operands[1], operands[2])
        ByteArrayOp.WRITE_FLOAT -> WriteFloatArrayExpression(operands[0], operands[1], operands[2], operands[3])
        ByteArrayOp.INDEX_FLOAT -> IndexFloatArrayExpression(operands[0], operands[1])
        ByteArrayOp.READ_INT8, ByteArrayOp.READ_WORD8 -> ReadByteArrayExpression(
            operation == ByteArrayOp.READ_WORD8, operands[0], operands[1], operands[2])
        ByteArrayOp.INDEX_INT8 -> IndexSignedByteArrayExpression(operands[0], operands[1])
        ByteArrayOp.READ_INT16, ByteArrayOp.READ_WORD16 -> ReadInt16ArrayExpression(
            operation == ByteArrayOp.READ_WORD16, operands[0], operands[1], operands[2])
        ByteArrayOp.WRITE_INT16, ByteArrayOp.WRITE_WORD16 -> WriteInt16ArrayExpression(
            operands[0], operands[1], operands[2], operands[3])
        ByteArrayOp.INDEX_INT16, ByteArrayOp.INDEX_WORD16 -> IndexInt16ArrayExpression(
            operation == ByteArrayOp.INDEX_WORD16, operands[0], operands[1])
        ByteArrayOp.READ_INT32, ByteArrayOp.READ_WORD32 -> ReadInt32ArrayExpression(
            operation == ByteArrayOp.READ_WORD32, operands[0], operands[1], operands[2])
        ByteArrayOp.WRITE_INT32, ByteArrayOp.WRITE_WORD32 -> WriteInt32ArrayExpression(
            operands[0], operands[1], operands[2], operands[3])
        ByteArrayOp.INDEX_INT32, ByteArrayOp.INDEX_WORD32 -> IndexInt32ArrayExpression(
            operation == ByteArrayOp.INDEX_WORD32, operands[0], operands[1])
    }.proven(proof.copy(evaluated = true))

private class NewByteArrayExpression(@field:Child private var size: Expr,
    @field:Child private var state: Expr) : Expr() {
    override fun execute(frame: VirtualFrame): Nothing = fault("Tuple primitive requires a destination")
    override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Any? {
        val count = size.executeRequiredLong(frame)
        ManagedByteArray.requireState(state.execute(frame))
        // The logical State# component has no slot. Publish only after the effect.
        FrameAccess.write(frame, slots[offset], ManagedByteArray.allocate(count))
        return null
    }
}
private class ResizeByteArrayExpression(@field:Child private var array: Expr,
    @field:Child private var size: Expr, @field:Child private var state: Expr) : Expr() {
    override fun execute(frame: VirtualFrame): Nothing = fault("Tuple primitive requires a destination")
    override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Any? {
        val bytes = ManagedByteArray.require(array.execute(frame))
        val count = size.executeRequiredLong(frame)
        ManagedByteArray.requireState(state.execute(frame))
        FrameAccess.write(frame, slots[offset], ManagedByteArray.resize(bytes, count))
        return null
    }
}

private class FreezeByteArrayExpression(@field:Child private var array: Expr,
    @field:Child private var state: Expr) : Expr() {
    override fun execute(frame: VirtualFrame): Nothing = fault("Tuple primitive requires a destination")
    override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Any? {
        val bytes = ManagedByteArray.require(array.execute(frame))
        ManagedByteArray.requireState(state.execute(frame))
        FrameAccess.write(frame, slots[offset], ManagedByteArray.freeze(bytes))
        return null
    }
}
private class WriteByteArrayExpression(@field:Child private var array: Expr,
    @field:Child private var index: Expr, @field:Child private var value: Expr,
    @field:Child private var state: Expr) : Expr() {
    override fun execute(frame: VirtualFrame): Any {
        val bytes = ManagedByteArray.require(array.execute(frame))
        val offset = index.executeRequiredLong(frame)
        val byte = value.executeRequiredLong(frame)
        ManagedByteArray.requireState(state.execute(frame))
        ManagedByteArray.write(bytes, offset, byte)
        return Unit
    }
}
/** State is evaluated and checked before observing length or publishing a result. */
private class GetSizeMutableByteArrayExpression(@field:Child private var array: Expr,
    @field:Child private var state: Expr) : Expr() {
    override fun execute(frame: VirtualFrame): Nothing = fault("Tuple primitive requires a destination")
    override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Any? {
        val bytes = ManagedByteArray.require(array.execute(frame))
        ManagedByteArray.requireState(state.execute(frame))
        FrameAccess.writeLong(frame, slots[offset], ManagedByteArray.size(bytes))
        return null
    }
}
private class SizeByteArrayExpression(@field:Child private var array: Expr) : Expr() {
    override fun execute(frame: VirtualFrame): Any = executeLong(frame)
    override fun executeLong(frame: VirtualFrame): Long = ManagedByteArray.size(ManagedByteArray.require(array.execute(frame)))
}
private class IndexByteArrayExpression(@field:Child private var array: Expr,
    @field:Child private var index: Expr) : Expr() {
    override fun execute(frame: VirtualFrame): Any = executeLong(frame)
    override fun executeLong(frame: VirtualFrame): Long {
        val bytes = ManagedByteArray.require(array.execute(frame))
        val offset = index.executeRequiredLong(frame)
        return ManagedByteArray.read(bytes, offset)
    }
}

private class ReadIntArrayExpression(@field:Child private var array: Expr,
    @field:Child private var index: Expr, @field:Child private var state: Expr) : Expr() {
    override fun execute(frame: VirtualFrame): Nothing = fault("Tuple primitive requires a destination")
    override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Any? {
        val bytes = ManagedByteArray.require(array.execute(frame))
        val element = index.executeRequiredLong(frame)
        ManagedByteArray.requireState(state.execute(frame))
        FrameAccess.writeLong(frame, slots[offset], ManagedIntArray.read(bytes, element))
        return null
    }
}
private class WriteIntArrayExpression(@field:Child private var array: Expr,
    @field:Child private var index: Expr, @field:Child private var value: Expr,
    @field:Child private var state: Expr) : Expr() {
    override fun execute(frame: VirtualFrame): Any {
        val bytes = ManagedByteArray.require(array.execute(frame))
        val element = index.executeRequiredLong(frame)
        val integer = value.executeRequiredLong(frame)
        ManagedByteArray.requireState(state.execute(frame))
        ManagedIntArray.write(bytes, element, integer)
        return Unit
    }
}
private class IndexIntArrayExpression(@field:Child private var array: Expr,
    @field:Child private var index: Expr) : Expr() {
    override fun execute(frame: VirtualFrame): Any = executeLong(frame)
    override fun executeLong(frame: VirtualFrame): Long {
        val bytes = ManagedByteArray.require(array.execute(frame))
        val element = index.executeRequiredLong(frame)
        return ManagedIntArray.read(bytes, element)
    }
}

private class ReadDoubleArrayExpression(@field:Child private var array: Expr,
    @field:Child private var index: Expr, @field:Child private var state: Expr) : Expr() {
    override fun execute(frame: VirtualFrame): Nothing = fault("Tuple primitive requires a destination")
    override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Any? {
        val bytes = ManagedByteArray.require(array.execute(frame))
        val element = index.executeRequiredLong(frame)
        ManagedByteArray.requireState(state.execute(frame))
        FrameAccess.writeDouble(frame, slots[offset], ManagedDoubleArray.read(bytes, element))
        return null
    }
}
private class WriteDoubleArrayExpression(@field:Child private var array: Expr,
    @field:Child private var index: Expr, @field:Child private var value: Expr,
    @field:Child private var state: Expr) : Expr() {
    override fun execute(frame: VirtualFrame): Any {
        val bytes = ManagedByteArray.require(array.execute(frame))
        val element = index.executeRequiredLong(frame)
        val number = value.executeRequiredDouble(frame)
        ManagedByteArray.requireState(state.execute(frame))
        ManagedDoubleArray.write(bytes, element, number)
        return Unit
    }
}
private class IndexDoubleArrayExpression(@field:Child private var array: Expr,
    @field:Child private var index: Expr) : Expr() {
    override fun execute(frame: VirtualFrame): Any = executeDouble(frame)
    override fun executeDouble(frame: VirtualFrame): Double {
        val bytes = ManagedByteArray.require(array.execute(frame))
        val element = index.executeRequiredLong(frame)
        return ManagedDoubleArray.read(bytes, element)
    }
}

private class ReadFloatArrayExpression(@field:Child private var array: Expr,
    @field:Child private var index: Expr, @field:Child private var state: Expr) : Expr() {
    override fun execute(frame: VirtualFrame): Nothing = fault("Tuple primitive requires a destination")
    override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Any? {
        val bytes = ManagedByteArray.require(array.execute(frame))
        val element = index.executeRequiredLong(frame)
        ManagedByteArray.requireState(state.execute(frame))
        FrameAccess.writeFloat(frame, slots[offset], ManagedFloatArray.read(bytes, element))
        return null
    }
}
private class WriteFloatArrayExpression(@field:Child private var array: Expr,
    @field:Child private var index: Expr, @field:Child private var value: Expr,
    @field:Child private var state: Expr) : Expr() {
    override fun execute(frame: VirtualFrame): Any {
        val bytes = ManagedByteArray.require(array.execute(frame))
        val element = index.executeRequiredLong(frame)
        val number = value.executeRequiredFloat(frame)
        ManagedByteArray.requireState(state.execute(frame))
        ManagedFloatArray.write(bytes, element, number)
        return Unit
    }
}
private class IndexFloatArrayExpression(@field:Child private var array: Expr,
    @field:Child private var index: Expr) : Expr() {
    override fun execute(frame: VirtualFrame): Any = executeFloat(frame)
    override fun executeFloat(frame: VirtualFrame): Float {
        val bytes = ManagedByteArray.require(array.execute(frame))
        val element = index.executeRequiredLong(frame)
        return ManagedFloatArray.read(bytes, element)
    }
}

private class ReadInt16ArrayExpression(private val unsigned: Boolean,
    @field:Child private var array: Expr, @field:Child private var index: Expr,
    @field:Child private var state: Expr) : Expr() {
    override fun execute(frame: VirtualFrame): Nothing = fault("Tuple primitive requires a destination")
    override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Any? {
        val bytes = ManagedByteArray.require(array.execute(frame))
        val element = index.executeRequiredLong(frame)
        ManagedByteArray.requireState(state.execute(frame))
        val value = if (unsigned) ManagedInt16Array.readUnsigned(bytes, element) else ManagedInt16Array.readSigned(bytes, element)
        FrameAccess.writeLong(frame, slots[offset], value)
        return null
    }
}
private class WriteInt16ArrayExpression(@field:Child private var array: Expr,
    @field:Child private var index: Expr, @field:Child private var value: Expr,
    @field:Child private var state: Expr) : Expr() {
    override fun execute(frame: VirtualFrame): Any {
        val bytes = ManagedByteArray.require(array.execute(frame))
        val element = index.executeRequiredLong(frame)
        val integer = value.executeRequiredLong(frame)
        ManagedByteArray.requireState(state.execute(frame))
        ManagedInt16Array.write(bytes, element, integer)
        return Unit
    }
}
private class IndexInt16ArrayExpression(private val unsigned: Boolean,
    @field:Child private var array: Expr, @field:Child private var index: Expr) : Expr() {
    override fun execute(frame: VirtualFrame): Any = executeLong(frame)
    override fun executeLong(frame: VirtualFrame): Long {
        val bytes = ManagedByteArray.require(array.execute(frame))
        val element = index.executeRequiredLong(frame)
        return if (unsigned) ManagedInt16Array.readUnsigned(bytes, element) else ManagedInt16Array.readSigned(bytes, element)
    }
}

private class ReadInt32ArrayExpression(private val unsigned: Boolean,
    @field:Child private var array: Expr, @field:Child private var index: Expr,
    @field:Child private var state: Expr) : Expr() {
    override fun execute(frame: VirtualFrame): Nothing = fault("Tuple primitive requires a destination")
    override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Any? {
        val bytes = ManagedByteArray.require(array.execute(frame))
        val element = index.executeRequiredLong(frame)
        ManagedByteArray.requireState(state.execute(frame))
        val value = if (unsigned) ManagedInt32Array.readUnsigned(bytes, element) else ManagedInt32Array.readSigned(bytes, element)
        FrameAccess.writeLong(frame, slots[offset], value)
        return null
    }
}
private class WriteInt32ArrayExpression(@field:Child private var array: Expr,
    @field:Child private var index: Expr, @field:Child private var value: Expr,
    @field:Child private var state: Expr) : Expr() {
    override fun execute(frame: VirtualFrame): Any {
        val bytes = ManagedByteArray.require(array.execute(frame))
        val element = index.executeRequiredLong(frame)
        val integer = value.executeRequiredLong(frame)
        ManagedByteArray.requireState(state.execute(frame))
        ManagedInt32Array.write(bytes, element, integer)
        return Unit
    }
}
private class IndexInt32ArrayExpression(private val unsigned: Boolean,
    @field:Child private var array: Expr, @field:Child private var index: Expr) : Expr() {
    override fun execute(frame: VirtualFrame): Any = executeLong(frame)
    override fun executeLong(frame: VirtualFrame): Long {
        val bytes = ManagedByteArray.require(array.execute(frame))
        val element = index.executeRequiredLong(frame)
        return if (unsigned) ManagedInt32Array.readUnsigned(bytes, element) else ManagedInt32Array.readSigned(bytes, element)
    }
}

private class CopyByteArrayExpression(@field:Child private var source: Expr,
    @field:Child private var sourceOffset: Expr, @field:Child private var destination: Expr,
    @field:Child private var destinationOffset: Expr, @field:Child private var count: Expr,
    @field:Child private var state: Expr) : Expr() {
    override fun execute(frame: VirtualFrame): Any {
        val from = ManagedByteArray.require(source.execute(frame))
        val fromOffset = sourceOffset.executeRequiredLong(frame)
        val to = ManagedByteArray.require(destination.execute(frame))
        val toOffset = destinationOffset.executeRequiredLong(frame)
        val length = count.executeRequiredLong(frame)
        ManagedByteArray.requireState(state.execute(frame))
        ManagedByteArray.copy(from, fromOffset, to, toOffset, length)
        return Unit
    }
}

private class CompareByteArraysExpression(@field:Child private var first: Expr,
    @field:Child private var firstOffset: Expr, @field:Child private var second: Expr,
    @field:Child private var secondOffset: Expr, @field:Child private var count: Expr) : Expr() {
    override fun execute(frame: VirtualFrame): Any = executeLong(frame)
    override fun executeLong(frame: VirtualFrame): Long {
        val left = ManagedByteArray.require(first.execute(frame))
        val from = firstOffset.executeRequiredLong(frame)
        val right = ManagedByteArray.require(second.execute(frame))
        val to = secondOffset.executeRequiredLong(frame)
        val length = count.executeRequiredLong(frame)
        return ManagedByteArray.compare(left, from, right, to, length)
    }
}

private class ReadByteArrayExpression(private val unsigned: Boolean,
    @field:Child private var array: Expr, @field:Child private var index: Expr,
    @field:Child private var state: Expr) : Expr() {
    override fun execute(frame: VirtualFrame): Nothing = fault("Tuple primitive requires a destination")
    override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Any? {
        val bytes = ManagedByteArray.require(array.execute(frame))
        val element = index.executeRequiredLong(frame)
        ManagedByteArray.requireState(state.execute(frame))
        val value = if (unsigned) ManagedByteArray.read(bytes, element) else ManagedByteArray.readSigned(bytes, element)
        FrameAccess.writeLong(frame, slots[offset], value)
        return null
    }
}
private class IndexSignedByteArrayExpression(@field:Child private var array: Expr,
    @field:Child private var index: Expr) : Expr() {
    override fun execute(frame: VirtualFrame): Any = executeLong(frame)
    override fun executeLong(frame: VirtualFrame): Long {
        val bytes = ManagedByteArray.require(array.execute(frame))
        val element = index.executeRequiredLong(frame)
        return ManagedByteArray.readSigned(bytes, element)
    }
}

private class SetByteArrayExpression(@field:Child private var array: Expr, @field:Child private var offset: Expr,
    @field:Child private var count: Expr, @field:Child private var value: Expr, @field:Child private var state: Expr) : Expr() {
    override fun execute(frame: VirtualFrame): Any {
        val bytes = ManagedByteArray.require(array.execute(frame))
        val start = offset.executeRequiredLong(frame)
        val length = count.executeRequiredLong(frame)
        val byte = value.executeRequiredLong(frame)
        ManagedByteArray.requireState(state.execute(frame))
        ManagedByteArray.fill(bytes, start, length, byte)
        return Unit
    }
}
private class CopyMutableByteArrayExpression(private val nonOverlapping: Boolean,
    @field:Child private var source: Expr, @field:Child private var sourceOffset: Expr,
    @field:Child private var destination: Expr, @field:Child private var destinationOffset: Expr,
    @field:Child private var count: Expr, @field:Child private var state: Expr) : Expr() {
    override fun execute(frame: VirtualFrame): Any {
        val from = ManagedByteArray.require(source.execute(frame))
        val start = sourceOffset.executeRequiredLong(frame)
        val to = ManagedByteArray.require(destination.execute(frame))
        val target = destinationOffset.executeRequiredLong(frame)
        val length = count.executeRequiredLong(frame)
        ManagedByteArray.requireState(state.execute(frame))
        ManagedByteArray.copyMutable(from, start, to, target, length, nonOverlapping)
        return Unit
    }
}
