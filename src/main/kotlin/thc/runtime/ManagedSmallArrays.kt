// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.frame.VirtualFrame

/** SmallArray# has its own storage carrier, so ordinary Array# operations
 * cannot accidentally accept it despite their identical unlifted Core rep. */
internal class SmallArrayStorage(val elements: Array<Any?>)

internal object ManagedSmallArray {
    @JvmStatic fun allocate(size: Long, initial: Any?): SmallArrayStorage {
        if (size < 0 || size > Int.MAX_VALUE.toLong()) fault("SmallArray# size outside the managed allocation domain")
        return SmallArrayStorage(Array(size.toInt()) { initial })
    }
    @JvmStatic fun require(value: Any?): SmallArrayStorage =
        value as? SmallArrayStorage ?: fault("Expected managed SmallArray# storage")
    @JvmStatic fun size(array: SmallArrayStorage): Long = array.elements.size.toLong()
    private fun index(array: SmallArrayStorage, index: Long): Int {
        if (index < 0 || index >= size(array)) fault("SmallArray# index outside its backing storage")
        return index.toInt()
    }
    @JvmStatic fun read(array: SmallArrayStorage, index: Long): Any? = array.elements[index(array, index)]
    @JvmStatic fun write(array: SmallArrayStorage, index: Long, value: Any?) {
        array.elements[index(array, index)] = value
    }
    @JvmStatic fun freeze(array: SmallArrayStorage): SmallArrayStorage = array
    private fun range(array: SmallArrayStorage, offset: Long, count: Long) {
        val size = size(array)
        if (offset < 0 || offset > size || count < 0 || count > size - offset)
            fault("SmallArray# slice outside its backing storage")
    }
    @JvmStatic fun slice(array: SmallArrayStorage, offset: Long, count: Long): SmallArrayStorage {
        range(array, offset, count)
        return SmallArrayStorage(array.elements.copyOfRange(offset.toInt(), (offset + count).toInt()))
    }
    /** Mutable self-copy has memmove semantics. All checks precede mutation. */
    @JvmStatic fun copy(source: SmallArrayStorage, sourceOffset: Long, destination: SmallArrayStorage,
        destinationOffset: Long, count: Long, mutableSource: Boolean) {
        range(source, sourceOffset, count)
        range(destination, destinationOffset, count)
        if (!mutableSource && source === destination) fault("copySmallArray# requires distinct arrays")
        System.arraycopy(source.elements, sourceOffset.toInt(), destination.elements, destinationOffset.toInt(), count.toInt())
    }
}

internal enum class SmallArrayOp(val primitive: String, private val arguments: List<String>,
    private val result: List<String>) {
    NEW("newSmallArray#", listOf("int", "element", "state"), listOf("state", "array")),
    READ("readSmallArray#", listOf("array", "int", "state"), listOf("state", "element")),
    WRITE("writeSmallArray#", listOf("array", "int", "element", "state"), listOf("state")),
    INDEX("indexSmallArray#", listOf("array", "int"), listOf("element")),
    FREEZE("unsafeFreezeSmallArray#", listOf("array", "state"), listOf("state", "array")),
    SIZE("sizeofSmallArray#", listOf("array"), listOf("int")),
    SIZE_MUTABLE("sizeofSmallMutableArray#", listOf("array"), listOf("int")),
    GET_SIZE_MUTABLE("getSizeofSmallMutableArray#", listOf("array", "state"), listOf("state", "int")),
    CLONE("cloneSmallArray#", listOf("array", "int", "int"), listOf("array")),
    CLONE_MUTABLE("cloneSmallMutableArray#", listOf("array", "int", "int", "state"), listOf("state", "array")),
    COPY("copySmallArray#", listOf("array", "int", "array", "int", "int", "state"), listOf("state")),
    COPY_MUTABLE("copySmallMutableArray#", listOf("array", "int", "array", "int", "int", "state"), listOf("state")),
    SAFE_FREEZE("freezeSmallArray#", listOf("array", "int", "int", "state"), listOf("state", "array")),
    THAW("thawSmallArray#", listOf("array", "int", "int", "state"), listOf("state", "array")),
    UNSAFE_THAW("unsafeThawSmallArray#", listOf("array", "state"), listOf("state", "array"));

    val tuple: Boolean get() = this in setOf(NEW, READ, INDEX, FREEZE, GET_SIZE_MUTABLE,
        CLONE_MUTABLE, SAFE_FREEZE, THAW, UNSAFE_THAW)
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
            throw RuntimeFault("SmallArray primitive argument representation mismatch: $primitive")
        val valid = if (!tuple) matches(proof, result.single()) else proof.isTuple &&
            proof.kind == CoreKind.UNKNOWN && proof.components!!.size == result.size &&
            result.indices.all { matches(proof.components[it], result[it]) } &&
            proof.primReps == proof.components.flatMap { it.primReps!! }
        if (!valid) throw RuntimeFault("SmallArray primitive result representation mismatch: $primitive")
    }
    companion object { fun named(name: String): SmallArrayOp? = entries.firstOrNull { it.primitive == name } }
}

internal fun smallArrayExpression(operation: SmallArrayOp, proof: CoreRepresentation,
    operands: Array<Expr>): Expr = when (operation) {
    SmallArrayOp.NEW -> NewSmallArrayExpression(operands[0], operands[1], operands[2])
    SmallArrayOp.READ -> ReadSmallArrayExpression(operands[0], operands[1], operands[2])
    SmallArrayOp.WRITE -> WriteSmallArrayExpression(operands[0], operands[1], operands[2], operands[3])
    SmallArrayOp.INDEX -> IndexSmallArrayExpression(operands[0], operands[1])
    SmallArrayOp.FREEZE, SmallArrayOp.UNSAFE_THAW -> FreezeSmallArrayExpression(operands[0], operands[1])
    SmallArrayOp.SIZE, SmallArrayOp.SIZE_MUTABLE -> SizeSmallArrayExpression(operands[0])
    SmallArrayOp.GET_SIZE_MUTABLE -> GetSizeSmallArrayExpression(operands[0], operands[1])
    SmallArrayOp.CLONE -> CloneSmallArrayExpression(operands[0], operands[1], operands[2])
    SmallArrayOp.CLONE_MUTABLE, SmallArrayOp.SAFE_FREEZE, SmallArrayOp.THAW ->
        CopySmallArrayExpression(operands[0], operands[1], operands[2], operands[3])
    SmallArrayOp.COPY, SmallArrayOp.COPY_MUTABLE -> TransferSmallArrayExpression(operation == SmallArrayOp.COPY_MUTABLE,
        operands[0], operands[1], operands[2], operands[3], operands[4], operands[5])
}.proven(proof.copy(evaluated = true))

private class NewSmallArrayExpression(@field:Child private var size: Expr, @field:Child private var initial: Expr,
    @field:Child private var state: Expr) : Expr() {
    override fun execute(frame: VirtualFrame): Nothing = fault("Tuple primitive requires a destination")
    override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Any? {
        val count = size.executeRequiredLong(frame)
        val value = initial.execute(frame)
        requireVoidCarrier(state.execute(frame))
        FrameAccess.write(frame, slots[offset], ManagedSmallArray.allocate(count, value))
        return null
    }
}
private class ReadSmallArrayExpression(@field:Child private var array: Expr, @field:Child private var index: Expr,
    @field:Child private var state: Expr) : Expr() {
    override fun execute(frame: VirtualFrame): Nothing = fault("Tuple primitive requires a destination")
    override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Any? {
        val storage = ManagedSmallArray.require(array.execute(frame))
        val at = index.executeRequiredLong(frame)
        requireVoidCarrier(state.execute(frame))
        FrameAccess.write(frame, slots[offset], ManagedSmallArray.read(storage, at))
        return null
    }
}
private class WriteSmallArrayExpression(@field:Child private var array: Expr, @field:Child private var index: Expr,
    @field:Child private var value: Expr, @field:Child private var state: Expr) : Expr() {
    override fun execute(frame: VirtualFrame): Any {
        val storage = ManagedSmallArray.require(array.execute(frame))
        val at = index.executeRequiredLong(frame)
        val stored = value.execute(frame)
        requireVoidCarrier(state.execute(frame))
        ManagedSmallArray.write(storage, at, stored)
        return Unit
    }
}
private class IndexSmallArrayExpression(@field:Child private var array: Expr, @field:Child private var index: Expr) : Expr() {
    override fun execute(frame: VirtualFrame): Nothing = fault("Tuple primitive requires a destination")
    override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Any? {
        FrameAccess.write(frame, slots[offset],
            ManagedSmallArray.read(ManagedSmallArray.require(array.execute(frame)), index.executeRequiredLong(frame)))
        return null
    }
}
private class FreezeSmallArrayExpression(@field:Child private var array: Expr, @field:Child private var state: Expr) : Expr() {
    override fun execute(frame: VirtualFrame): Nothing = fault("Tuple primitive requires a destination")
    override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Any? {
        val storage = ManagedSmallArray.require(array.execute(frame))
        requireVoidCarrier(state.execute(frame))
        FrameAccess.write(frame, slots[offset], ManagedSmallArray.freeze(storage))
        return null
    }
}
private class SizeSmallArrayExpression(@field:Child private var array: Expr) : Expr() {
    override fun execute(frame: VirtualFrame): Any = executeLong(frame)
    override fun executeLong(frame: VirtualFrame): Long =
        ManagedSmallArray.size(ManagedSmallArray.require(array.execute(frame)))
}
private class GetSizeSmallArrayExpression(@field:Child private var array: Expr, @field:Child private var state: Expr) : Expr() {
    override fun execute(frame: VirtualFrame): Nothing = fault("Tuple primitive requires a destination")
    override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Any? {
        val storage = ManagedSmallArray.require(array.execute(frame))
        requireVoidCarrier(state.execute(frame))
        FrameAccess.writeLong(frame, slots[offset], ManagedSmallArray.size(storage))
        return null
    }
}

private class CloneSmallArrayExpression(@field:Child private var array: Expr, @field:Child private var offset: Expr,
    @field:Child private var count: Expr) : Expr() {
    override fun execute(frame: VirtualFrame): SmallArrayStorage = ManagedSmallArray.slice(
        ManagedSmallArray.require(array.execute(frame)), offset.executeRequiredLong(frame), count.executeRequiredLong(frame))
}
private class CopySmallArrayExpression(@field:Child private var array: Expr, @field:Child private var offset: Expr,
    @field:Child private var count: Expr, @field:Child private var state: Expr) : Expr() {
    override fun execute(frame: VirtualFrame): Nothing = fault("Tuple primitive requires a destination")
    override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Any? {
        val storage = ManagedSmallArray.require(array.execute(frame))
        val start = this.offset.executeRequiredLong(frame)
        val length = count.executeRequiredLong(frame)
        requireVoidCarrier(state.execute(frame))
        FrameAccess.write(frame, slots[offset], ManagedSmallArray.slice(storage, start, length))
        return null
    }
}
private class TransferSmallArrayExpression(private val mutableSource: Boolean,
    @field:Child private var source: Expr, @field:Child private var sourceOffset: Expr,
    @field:Child private var destination: Expr, @field:Child private var destinationOffset: Expr,
    @field:Child private var count: Expr, @field:Child private var state: Expr) : Expr() {
    override fun execute(frame: VirtualFrame): Any {
        val from = ManagedSmallArray.require(source.execute(frame))
        val fromOffset = sourceOffset.executeRequiredLong(frame)
        val to = ManagedSmallArray.require(destination.execute(frame))
        val toOffset = destinationOffset.executeRequiredLong(frame)
        val length = count.executeRequiredLong(frame)
        requireVoidCarrier(state.execute(frame))
        ManagedSmallArray.copy(from, fromOffset, to, toOffset, length, mutableSource)
        return Unit
    }
}
