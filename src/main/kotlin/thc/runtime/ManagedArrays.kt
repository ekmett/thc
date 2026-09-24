// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

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
    /** Shallow, independent storage. Validate full-width values without adding
     * offset and count, so invalid overflowing ranges cannot wrap into bounds. */
    @JvmStatic fun slice(array: Array<Any?>, offset: Long, count: Long): Array<Any?> {
        val size = array.size.toLong()
        if (offset < 0 || offset > size || count < 0 || count > size - offset)
            fault("Array# slice outside its backing storage")
        return array.copyOfRange(offset.toInt(), (offset + count).toInt())
    }
    /** The managed collector requires no info-table transition; preserve storage identity. */
    @JvmStatic fun freeze(array: Array<Any?>): Array<Any?> = array
}

/** Element-exposing operations keep the known-lifted specialization. Slice
 * operations copy boxed references opaquely without changing element proofs. */
internal enum class ArrayOp(val primitive: String, private val arguments: List<String>, private val result: List<String>) {
    NEW("newArray#", listOf("int", "element", "state"), listOf("state", "array")),
    READ("readArray#", listOf("array", "int", "state"), listOf("state", "element")),
    WRITE("writeArray#", listOf("array", "int", "element", "state"), emptyList()),
    FREEZE("unsafeFreezeArray#", listOf("array", "state"), listOf("state", "array")),
    INDEX("indexArray#", listOf("array", "int"), listOf("element")),
    CLONE("cloneArray#", listOf("array", "int", "int"), listOf("array")),
    FREEZE_COPY("freezeArray#", listOf("array", "int", "int", "state"), listOf("state", "array")),
    THAW("thawArray#", listOf("array", "int", "int", "state"), listOf("state", "array"));

    val tuple: Boolean get() = result.isNotEmpty() && this != CLONE
    fun validate(actual: List<CoreRepresentation>, flags: List<*>, proof: CoreRepresentation) {
        fun matches(rep: CoreRepresentation, role: String): Boolean = !rep.isAggregate && !rep.isVector && when (role) {
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
        val valid = if (!tuple) matches(proof, if (this == CLONE) "array" else "state") else proof.isTuple && proof.kind == CoreKind.UNKNOWN &&
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
    ArrayOp.CLONE -> CloneArrayExpression(operands[0], operands[1], operands[2])
    ArrayOp.FREEZE_COPY, ArrayOp.THAW -> CopyArrayExpression(operands[0], operands[1], operands[2], operands[3])
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

private class CloneArrayExpression(@field:Child private var array: Expr, @field:Child private var offset: Expr,
    @field:Child private var count: Expr) : Expr() {
    override fun execute(frame: VirtualFrame): Any {
        val storage = ManagedArray.require(array.execute(frame))
        val start = offset.executeRequiredLong(frame)
        val length = count.executeRequiredLong(frame)
        return ManagedArray.slice(storage, start, length)
    }
}
private class CopyArrayExpression(@field:Child private var array: Expr, @field:Child private var offset: Expr,
    @field:Child private var count: Expr, @field:Child private var state: Expr) : Expr() {
    override fun execute(frame: VirtualFrame): Nothing = fault("Tuple primitive requires a destination")
    override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Any? {
        val storage = ManagedArray.require(array.execute(frame))
        val start = this.offset.executeRequiredLong(frame)
        val length = count.executeRequiredLong(frame)
        requireVoidCarrier(state.execute(frame))
        FrameAccess.write(frame, slots[offset], ManagedArray.slice(storage, start, length))
        return null
    }
}
