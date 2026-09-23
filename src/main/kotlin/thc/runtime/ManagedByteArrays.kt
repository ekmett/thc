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
    WRITE("writeWord8Array#", listOf(listOf(BYTE_ARRAY_REP), listOf("IntRep"), listOf("Word8Rep"), emptyList())),
    COPY("copyByteArray#", listOf(listOf(BYTE_ARRAY_REP), listOf("IntRep"), listOf(BYTE_ARRAY_REP),
        listOf("IntRep"), listOf("IntRep"), emptyList())),
    FREEZE("unsafeFreezeByteArray#", listOf(listOf(BYTE_ARRAY_REP), emptyList()), true),
    SIZE("sizeofByteArray#", listOf(listOf(BYTE_ARRAY_REP))),
    INDEX("indexWord8Array#", listOf(listOf(BYTE_ARRAY_REP), listOf("IntRep"))),
    READ_INT("readIntArray#", listOf(listOf(BYTE_ARRAY_REP), listOf("IntRep"), emptyList()), true),
    WRITE_INT("writeIntArray#", listOf(listOf(BYTE_ARRAY_REP), listOf("IntRep"), listOf("IntRep"), emptyList())),
    INDEX_INT("indexIntArray#", listOf(listOf(BYTE_ARRAY_REP), listOf("IntRep"))),
    READ_DOUBLE("readDoubleArray#", listOf(listOf(BYTE_ARRAY_REP), listOf("IntRep"), emptyList()), true),
    WRITE_DOUBLE("writeDoubleArray#", listOf(listOf(BYTE_ARRAY_REP), listOf("IntRep"), listOf("DoubleRep"), emptyList())),
    INDEX_DOUBLE("indexDoubleArray#", listOf(listOf(BYTE_ARRAY_REP), listOf("IntRep"))),
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
        fun scalar(proof: CoreRepresentation, registers: List<String>): Boolean = !proof.isTuple &&
            proof.primReps == registers && proof.kind == when (registers.singleOrNull()) {
                null -> CoreKind.VOID; BYTE_ARRAY_REP -> CoreKind.OBJECT
                "DoubleRep" -> CoreKind.DOUBLE; "FloatRep" -> CoreKind.FLOAT; else -> CoreKind.LONG
            }
        if (flags != List(arguments.size) { false } || actual.indices.any { !scalar(actual[it], arguments[it]) })
            throw RuntimeFault("ByteArray primitive argument representation mismatch: $primitive")
        val payload = listOf(when (this) {
            READ_INT -> "IntRep"; READ_DOUBLE -> "DoubleRep"
            READ_INT32 -> "Int32Rep"; READ_WORD32 -> "Word32Rep"
            READ_FLOAT -> "FloatRep"; READ_WORD -> "WordRep"; else -> BYTE_ARRAY_REP
        })
        val valid = if (tuple) result.isTuple && result.kind == CoreKind.UNKNOWN && result.components!!.size == 2 &&
            scalar(result.components[0], emptyList()) && scalar(result.components[1], payload) &&
            result.primReps == payload
        else scalar(result, when (this) {
            WRITE, WRITE_INT, WRITE_DOUBLE, WRITE_INT32, WRITE_WORD32, WRITE_FLOAT, WRITE_WORD, COPY -> emptyList()
            SIZE, INDEX_INT -> listOf("IntRep")
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
        ByteArrayOp.FREEZE -> FreezeByteArrayExpression(operands[0], operands[1])
        ByteArrayOp.WRITE -> WriteByteArrayExpression(operands[0], operands[1], operands[2], operands[3])
        ByteArrayOp.COPY -> CopyByteArrayExpression(operands[0], operands[1], operands[2], operands[3], operands[4], operands[5])
        ByteArrayOp.SIZE -> SizeByteArrayExpression(operands[0])
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
