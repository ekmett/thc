// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.frame.VirtualFrame
import java.lang.invoke.MethodHandles

/** One JVM Object[] is the actual Array# storage. Elements are mutable guest
 * references, never CompilationFinal and never entered by storage operations. */
internal object ManagedArray {
    private val ELEMENT = MethodHandles.arrayElementVarHandle(Array<Any?>::class.java)
    // Object[] has identity equality. Weak metadata records GHC's frozen info-table
    // distinction without replacing the existing array carrier or retaining it.
    private val frozen = java.util.WeakHashMap<Array<Any?>, Boolean>()
    @JvmStatic @Synchronized fun isFrozen(array: Array<Any?>): Boolean = frozen.containsKey(array)
    @JvmStatic @Synchronized fun thaw(array: Array<Any?>): Array<Any?> {
        frozen.remove(array)
        return array
    }
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
    /** Full-memory-order CAS, including already-completed thunk indirections.
     * Return expected on success or the atomic observation on failure; never force. */
    @JvmStatic fun compareExchange(array: Array<Any?>, index: Long, expected: Any?, replacement: Any?): Any? {
        val at = index(array, index)
        var witness = ELEMENT.compareAndExchange(array, at, expected, replacement)
        if (witness === expected) return expected
        while (completedBoxedIdentity(witness) === completedBoxedIdentity(expected)) {
            val prior = witness
            witness = ELEMENT.compareAndExchange(array, at, prior, replacement)
            if (witness === prior) return expected
        }
        return witness
    }
    /** Shallow, independent storage. Validate full-width values without adding
     * offset and count, so invalid overflowing ranges cannot wrap into bounds. */
    @JvmStatic fun slice(array: Array<Any?>, offset: Long, count: Long): Array<Any?> {
        range(array, offset, count)
        return array.copyOfRange(offset.toInt(), (offset + count).toInt())
    }
    private fun range(array: Array<Any?>, offset: Long, count: Long) {
        val size = array.size.toLong()
        if (offset < 0 || offset > size || count < 0 || count > size - offset)
            fault("Array# slice outside its backing storage")
    }
    @JvmStatic fun size(array: Array<Any?>): Long = array.size.toLong()
    /** Both ranges and immutable-source non-aliasing are checked before any write.
     * System.arraycopy has memmove semantics and never enters boxed elements. */
    @JvmStatic fun copy(source: Array<Any?>, sourceOffset: Long, destination: Array<Any?>,
        destinationOffset: Long, count: Long, mutableSource: Boolean) {
        range(source, sourceOffset, count)
        range(destination, destinationOffset, count)
        if (!mutableSource && source === destination) fault("copyArray# requires distinct arrays")
        System.arraycopy(source, sourceOffset.toInt(), destination, destinationOffset.toInt(), count.toInt())
    }
    /** Preserve storage identity and retain the immutable-pointer-array distinction. */
    @JvmStatic @Synchronized fun freeze(array: Array<Any?>): Array<Any?> {
        frozen[array] = true
        return array
    }
}

/** GHC's levity-polymorphic elements are boxed references of either known levity.
 * Storage operations copy references opaquely without entering their contents. */
internal enum class ArrayOp(val primitive: String, private val arguments: List<String>, private val result: List<String>) {
    NEW("newArray#", listOf("int", "element", "state"), listOf("state", "array")),
    READ("readArray#", listOf("array", "int", "state"), listOf("state", "element")),
    WRITE("writeArray#", listOf("array", "int", "element", "state"), emptyList()),
    CAS("casArray#", listOf("array", "int", "element", "element", "state"), listOf("state", "int", "element")),
    FREEZE("unsafeFreezeArray#", listOf("array", "state"), listOf("state", "array")),
    INDEX("indexArray#", listOf("array", "int"), listOf("element")),
    CLONE("cloneArray#", listOf("array", "int", "int"), listOf("array")),
    FREEZE_COPY("freezeArray#", listOf("array", "int", "int", "state"), listOf("state", "array")),
    THAW("thawArray#", listOf("array", "int", "int", "state"), listOf("state", "array")),
    SIZE("sizeofArray#", listOf("array"), listOf("int")),
    SIZE_MUTABLE("sizeofMutableArray#", listOf("array"), listOf("int")),
    CLONE_MUTABLE("cloneMutableArray#", listOf("array", "int", "int", "state"), listOf("state", "array")),
    COPY("copyArray#", listOf("array", "int", "array", "int", "int", "state"), emptyList()),
    COPY_MUTABLE("copyMutableArray#", listOf("array", "int", "array", "int", "int", "state"), emptyList()),
    UNSAFE_THAW("unsafeThawArray#", listOf("array", "state"), listOf("state", "array"));

    val tuple: Boolean get() = result.isNotEmpty() && this !in setOf(CLONE, SIZE, SIZE_MUTABLE)
    fun validate(actual: List<CoreRepresentation>, flags: List<*>, proof: CoreRepresentation) {
        fun matches(rep: CoreRepresentation, role: String): Boolean = !rep.isAggregate && !rep.isVector && when (role) {
            "state" -> rep.kind == CoreKind.VOID && rep.primReps == emptyList<String>()
            "int" -> rep.kind == CoreKind.LONG && rep.primReps == listOf("IntRep")
            "array" -> rep.kind == CoreKind.OBJECT && rep.primReps == listOf("BoxedRep (Just Unlifted)")
            else -> rep.kind in setOf(CoreKind.DATA, CoreKind.CLOSURE, CoreKind.OBJECT) &&
                rep.primReps?.singleOrNull() in setOf("BoxedRep (Just Lifted)", "BoxedRep (Just Unlifted)")
        }
        if (actual.size != arguments.size || flags.size != arguments.size)
            throw RuntimeFault("Primitive arity mismatch: $primitive")
        if (flags != actual.map { it.primReps == listOf("BoxedRep (Just Lifted)") } ||
            actual.indices.any { !matches(actual[it], arguments[it]) })
            throw RuntimeFault("Array primitive argument representation mismatch: $primitive")
        val valid = if (!tuple) matches(proof, result.singleOrNull() ?: "state") else proof.isTuple && proof.kind == CoreKind.UNKNOWN &&
            proof.components!!.size == result.size && result.indices.all { matches(proof.components[it], result[it]) } &&
            proof.primReps == proof.components.flatMap { it.primReps!! }
        if (!valid) throw RuntimeFault("Array primitive result representation mismatch: $primitive")
    }
    companion object {
        fun named(name: String): ArrayOp? = entries.firstOrNull { it.primitive == name }
        /** Decoding must not erase stray aggregate/vector fields or coerce raw
         * flags before the exact Array# contract sees them, including cold code. */
        fun validateApplications(value: Any?) {
            fun rawProof(value: Any?) {
                val map = value as? Map<*, *> ?: fault("Missing Array# representation proof")
                val tuple = map["aggregate"] == "unboxed-tuple"
                val keys = setOf("kind", "primReps", "evaluated") + if (tuple) setOf("aggregate", "components") else emptySet()
                if (map.keys != keys || map["evaluated"] !is Boolean)
                    fault("Malformed Array# representation proof")
                if (tuple) (map["components"] as? List<*>)?.forEach(::rawProof)
                    ?: fault("Malformed Array# tuple proof")
            }
            when (value) {
                is Map<*, *> -> value.values.forEach(::validateApplications)
                is List<*> -> {
                    val head = value.getOrNull(1) as? List<*>
                    val operation = if (value.firstOrNull() == "app" && head?.firstOrNull() == "prim")
                        (head.getOrNull(1) as? String)?.let(::named) else null
                    if (operation != null) {
                        val args = value.getOrNull(2) as? List<*> ?: fault("Malformed Array# arguments")
                        val flags = value.getOrNull(3) as? List<*> ?: fault("Malformed Array# flags")
                        if (value.size != 7 || flags.any { it !is Boolean }) fault("Malformed Array# application")
                        val proofs = args.map { argument ->
                            val expression = argument as? List<*> ?: fault("Malformed Array# argument")
                            val rep = CoreRepresentations.metadata(expression)?.get("rep")
                            rawProof(rep)
                            CoreRepresentations.parse(rep)
                        }
                        val rep = (value[6] as? Map<*, *>)?.get("rep")
                        rawProof(rep)
                        operation.validate(proofs, flags, CoreRepresentations.parse(rep))
                    }
                    value.forEach(::validateApplications)
                }
            }
        }
    }
}

internal fun arrayExpression(operation: ArrayOp, proof: CoreRepresentation, operands: Array<Expr>): Expr = when (operation) {
    ArrayOp.NEW -> NewArrayExpression(operands[0], operands[1], operands[2])
    ArrayOp.READ -> ReadArrayExpression(operands[0], operands[1], operands[2])
    ArrayOp.WRITE -> WriteArrayExpression(operands[0], operands[1], operands[2], operands[3])
    ArrayOp.CAS -> CasArrayExpression(operands[0], operands[1], operands[2], operands[3], operands[4])
    ArrayOp.FREEZE, ArrayOp.UNSAFE_THAW -> FreezeArrayExpression(operands[0], operands[1], operation == ArrayOp.FREEZE)
    ArrayOp.INDEX -> IndexArrayExpression(operands[0], operands[1])
    ArrayOp.CLONE -> CloneArrayExpression(operands[0], operands[1], operands[2])
    ArrayOp.FREEZE_COPY, ArrayOp.THAW, ArrayOp.CLONE_MUTABLE -> CopyArrayExpression(operands[0], operands[1], operands[2], operands[3], operation == ArrayOp.FREEZE_COPY)
    ArrayOp.SIZE, ArrayOp.SIZE_MUTABLE -> SizeArrayExpression(operands[0])
    ArrayOp.COPY, ArrayOp.COPY_MUTABLE -> TransferArrayExpression(operation == ArrayOp.COPY_MUTABLE,
        operands[0], operands[1], operands[2], operands[3], operands[4], operands[5])
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
private class CasArrayExpression(@field:Child private var array: Expr, @field:Child private var index: Expr,
    @field:Child private var expected: Expr, @field:Child private var replacement: Expr,
    @field:Child private var state: Expr) : Expr() {
    override fun execute(frame: VirtualFrame): Nothing = fault("Tuple primitive requires a destination")
    override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Any? {
        val storage = ManagedArray.require(array.execute(frame))
        val at = index.executeRequiredLong(frame)
        val old = expected.execute(frame)
        val new = replacement.execute(frame)
        requireVoidCarrier(state.execute(frame))
        val witness = ManagedArray.compareExchange(storage, at, old, new)
        val success = witness === old
        FrameAccess.writeLong(frame, slots[offset], if (success) 0L else 1L)
        FrameAccess.write(frame, slots[offset + 1], if (success) new else witness)
        return null
    }
}
private class FreezeArrayExpression(@field:Child private var array: Expr, @field:Child private var state: Expr,
    private val freeze: Boolean) : Expr() {
    override fun execute(frame: VirtualFrame): Nothing = fault("Tuple primitive requires a destination")
    override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Any? {
        val storage = ManagedArray.require(array.execute(frame))
        requireVoidCarrier(state.execute(frame))
        FrameAccess.write(frame, slots[offset], if (freeze) ManagedArray.freeze(storage) else ManagedArray.thaw(storage))
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
        return ManagedArray.freeze(ManagedArray.slice(storage, start, length))
    }
}
private class SizeArrayExpression(@field:Child private var array: Expr) : Expr() {
    override fun execute(frame: VirtualFrame): Long = executeLong(frame)
    override fun executeLong(frame: VirtualFrame): Long = ManagedArray.size(ManagedArray.require(array.execute(frame)))
}
private class TransferArrayExpression(private val mutableSource: Boolean,
    @field:Child private var source: Expr, @field:Child private var sourceOffset: Expr,
    @field:Child private var destination: Expr, @field:Child private var destinationOffset: Expr,
    @field:Child private var count: Expr, @field:Child private var state: Expr) : Expr() {
    override fun execute(frame: VirtualFrame): Any {
        val from = ManagedArray.require(source.execute(frame))
        val fromOffset = sourceOffset.executeRequiredLong(frame)
        val to = ManagedArray.require(destination.execute(frame))
        val toOffset = destinationOffset.executeRequiredLong(frame)
        val length = count.executeRequiredLong(frame)
        requireVoidCarrier(state.execute(frame))
        ManagedArray.copy(from, fromOffset, to, toOffset, length, mutableSource)
        return Unit
    }
}
private class CopyArrayExpression(@field:Child private var array: Expr, @field:Child private var offset: Expr,
    @field:Child private var count: Expr, @field:Child private var state: Expr, private val freeze: Boolean) : Expr() {
    override fun execute(frame: VirtualFrame): Nothing = fault("Tuple primitive requires a destination")
    override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Any? {
        val storage = ManagedArray.require(array.execute(frame))
        val start = this.offset.executeRequiredLong(frame)
        val length = count.executeRequiredLong(frame)
        requireVoidCarrier(state.execute(frame))
        val copy = ManagedArray.slice(storage, start, length)
        FrameAccess.write(frame, slots[offset], if (freeze) ManagedArray.freeze(copy) else copy)
        return null
    }
}
