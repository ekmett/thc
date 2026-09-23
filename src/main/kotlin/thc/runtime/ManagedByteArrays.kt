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
    FREEZE("unsafeFreezeByteArray#", listOf(listOf(BYTE_ARRAY_REP), emptyList()), true),
    SIZE("sizeofByteArray#", listOf(listOf(BYTE_ARRAY_REP))),
    INDEX("indexWord8Array#", listOf(listOf(BYTE_ARRAY_REP), listOf("IntRep"))),
    READ_INT("readIntArray#", listOf(listOf(BYTE_ARRAY_REP), listOf("IntRep"), emptyList()), true),
    WRITE_INT("writeIntArray#", listOf(listOf(BYTE_ARRAY_REP), listOf("IntRep"), listOf("IntRep"), emptyList())),
    INDEX_INT("indexIntArray#", listOf(listOf(BYTE_ARRAY_REP), listOf("IntRep")));

    fun validate(actual: List<CoreRepresentation>, flags: List<*>, result: CoreRepresentation) {
        if (actual.size != arguments.size) throw RuntimeFault("Primitive arity mismatch: $primitive")
        fun scalar(proof: CoreRepresentation, registers: List<String>): Boolean = !proof.isTuple &&
            proof.primReps == registers && proof.kind == when (registers.singleOrNull()) {
                null -> CoreKind.VOID; BYTE_ARRAY_REP -> CoreKind.OBJECT; else -> CoreKind.LONG
            }
        if (flags != List(arguments.size) { false } || actual.indices.any { !scalar(actual[it], arguments[it]) })
            throw RuntimeFault("ByteArray primitive argument representation mismatch: $primitive")
        val payload = if (this == READ_INT) listOf("IntRep") else listOf(BYTE_ARRAY_REP)
        val valid = if (tuple) result.isTuple && result.kind == CoreKind.UNKNOWN && result.components!!.size == 2 &&
            scalar(result.components[0], emptyList()) && scalar(result.components[1], payload) &&
            result.primReps == payload
        else scalar(result, when (this) {
            WRITE, WRITE_INT -> emptyList(); SIZE, INDEX_INT -> listOf("IntRep"); else -> listOf("Word8Rep")
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
        ByteArrayOp.SIZE -> SizeByteArrayExpression(operands[0])
        ByteArrayOp.INDEX -> IndexByteArrayExpression(operands[0], operands[1])
        ByteArrayOp.READ_INT -> ReadIntArrayExpression(operands[0], operands[1], operands[2])
        ByteArrayOp.WRITE_INT -> WriteIntArrayExpression(operands[0], operands[1], operands[2], operands[3])
        ByteArrayOp.INDEX_INT -> IndexIntArrayExpression(operands[0], operands[1])
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
