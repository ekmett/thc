package thc.runtime

import com.oracle.truffle.api.frame.VirtualFrame

/** One JVM Object[] is the actual Array# storage. Elements are mutable guest
 * references, never CompilationFinal and never entered by storage operations. */
internal object ManagedArray {
    @JvmStatic fun allocate(size: Long, initial: Any?): Array<Any?> {
        if (size < 0 || size > Int.MAX_VALUE.toLong()) fault("Array# size outside the managed allocation domain")
        return Array(size.toInt()) { initial }
    }
    @JvmStatic fun require(value: Any?): Array<Any?> {
        if (value !is Array<*> || value.javaClass != Array<Any?>::class.java)
            fault("Expected managed Array# object storage")
        @Suppress("UNCHECKED_CAST")
        return value as Array<Any?>
    }
    private fun index(array: Array<Any?>, index: Long): Int {
        if (index < 0 || index >= array.size.toLong()) fault("Array# index outside its backing storage")
        return index.toInt()
    }
    @JvmStatic fun read(array: Array<Any?>, index: Long): Any? = array[index(array, index)]
    @JvmStatic fun write(array: Array<Any?>, index: Long, value: Any?) { array[index(array, index)] = value }
    /** The managed collector requires no info-table transition; preserve storage identity. */
    @JvmStatic fun freeze(array: Array<Any?>): Array<Any?> = array
}

/** Bounded GHC a_levpoly specialization: known lifted elements only. */
internal enum class ArrayOp(val primitive: String, private val arguments: List<String>, private val result: List<String>) {
    NEW("newArray#", listOf("int", "element", "state"), listOf("state", "array")),
    READ("readArray#", listOf("array", "int", "state"), listOf("state", "element")),
    WRITE("writeArray#", listOf("array", "int", "element", "state"), emptyList()),
    FREEZE("unsafeFreezeArray#", listOf("array", "state"), listOf("state", "array")),
    INDEX("indexArray#", listOf("array", "int"), listOf("element"));

    val tuple: Boolean get() = result.isNotEmpty()
    fun validate(actual: List<CoreRepresentation>, flags: List<*>, proof: CoreRepresentation) {
        fun matches(rep: CoreRepresentation, role: String): Boolean = !rep.isTuple && !rep.isVector && when (role) {
            "state" -> rep.kind == CoreKind.VOID && rep.primReps == emptyList<String>()
            "int" -> rep.kind == CoreKind.LONG && rep.primReps == listOf("IntRep")
            "array" -> rep.kind == CoreKind.OBJECT && rep.primReps == listOf("BoxedRep (Just Unlifted)")
            else -> rep.kind in setOf(CoreKind.DATA, CoreKind.CLOSURE, CoreKind.OBJECT) &&
                rep.primReps == listOf("BoxedRep (Just Lifted)")
        }
        if (actual.size != arguments.size || flags.size != arguments.size)
            throw RuntimeFault("Primitive arity mismatch: $primitive")
        if (flags != arguments.map { it == "element" } || actual.indices.any { !matches(actual[it], arguments[it]) })
            throw RuntimeFault("Array primitive argument representation mismatch: $primitive")
        val valid = if (!tuple) matches(proof, "state") else proof.isTuple && proof.kind == CoreKind.UNKNOWN &&
            proof.components!!.size == result.size && result.indices.all { matches(proof.components[it], result[it]) } &&
            proof.primReps == proof.components.flatMap { it.primReps!! }
        if (!valid) throw RuntimeFault("Array primitive result representation mismatch: $primitive")
    }
    companion object { fun named(name: String): ArrayOp? = entries.firstOrNull { it.primitive == name } }
}

internal fun arrayExpression(operation: ArrayOp, proof: CoreRepresentation, operands: Array<Expr>): Expr = when (operation) {
    ArrayOp.NEW -> NewArrayExpression(operands[0], operands[1], operands[2])
    ArrayOp.READ -> ReadArrayExpression(operands[0], operands[1], operands[2])
    ArrayOp.WRITE -> WriteArrayExpression(operands[0], operands[1], operands[2], operands[3])
    ArrayOp.FREEZE -> FreezeArrayExpression(operands[0], operands[1])
    ArrayOp.INDEX -> IndexArrayExpression(operands[0], operands[1])
}.proven(proof.copy(evaluated = true))

private class NewArrayExpression(@field:Child private var size: Expr, @field:Child private var initial: Expr,
    @field:Child private var state: Expr) : Expr() {
    override fun execute(frame: VirtualFrame): Nothing = fault("Tuple primitive requires a destination")
    override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Any? {
        val count = size.executeRequiredLong(frame)
        val value = initial.execute(frame)
        requireVoidCarrier(state.execute(frame))
        FrameAccess.write(frame, slots[offset], ManagedArray.allocate(count, value))
        return null
    }
}
private class ReadArrayExpression(@field:Child private var array: Expr, @field:Child private var index: Expr,
    @field:Child private var state: Expr) : Expr() {
    override fun execute(frame: VirtualFrame): Nothing = fault("Tuple primitive requires a destination")
    override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Any? {
        val storage = ManagedArray.require(array.execute(frame))
        val at = index.executeRequiredLong(frame)
        requireVoidCarrier(state.execute(frame))
        FrameAccess.write(frame, slots[offset], ManagedArray.read(storage, at))
        return null
    }
}
private class WriteArrayExpression(@field:Child private var array: Expr, @field:Child private var index: Expr,
    @field:Child private var value: Expr, @field:Child private var state: Expr) : Expr() {
    override fun execute(frame: VirtualFrame): Any {
        val storage = ManagedArray.require(array.execute(frame))
        val at = index.executeRequiredLong(frame)
        val stored = value.execute(frame)
        requireVoidCarrier(state.execute(frame))
        ManagedArray.write(storage, at, stored)
        return Unit
    }
}
private class FreezeArrayExpression(@field:Child private var array: Expr, @field:Child private var state: Expr) : Expr() {
    override fun execute(frame: VirtualFrame): Nothing = fault("Tuple primitive requires a destination")
    override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Any? {
        val storage = ManagedArray.require(array.execute(frame))
        requireVoidCarrier(state.execute(frame))
        FrameAccess.write(frame, slots[offset], ManagedArray.freeze(storage))
        return null
    }
}
private class IndexArrayExpression(@field:Child private var array: Expr, @field:Child private var index: Expr) : Expr() {
    override fun execute(frame: VirtualFrame): Nothing = fault("Tuple primitive requires a destination")
    override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Any? {
        val storage = ManagedArray.require(array.execute(frame))
        val at = index.executeRequiredLong(frame)
        FrameAccess.write(frame, slots[offset], ManagedArray.read(storage, at))
        return null
    }
}
