// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.frame.VirtualFrame

/** All offsets count elements of [width] bytes. Integral Core values have
 * already been lowered to Long; the operation supplies narrowing/signedness. */
internal enum class AtomicIntArrayOp(val primitive: String, val width: Int = 8, val operands: Int = 1) {
    READ("atomicReadIntArray#", operands = 0),
    WRITE("atomicWriteIntArray#"),
    ADD("fetchAddIntArray#"), SUB("fetchSubIntArray#"),
    AND("fetchAndIntArray#"), NAND("fetchNandIntArray#"),
    OR("fetchOrIntArray#"), XOR("fetchXorIntArray#"),
    CAS("casIntArray#", operands = 2),
    CAS8("casInt8Array#", 1, 2), CAS16("casInt16Array#", 2, 2),
    CAS32("casInt32Array#", 4, 2), CAS64("casInt64Array#", 8, 2);

    val tuple: Boolean get() = this != WRITE

    fun validate(arguments: List<CoreRepresentation>, flags: List<*>, result: CoreRepresentation) {
        fun scalar(proof: CoreRepresentation, kind: CoreKind) = !proof.isAggregate && !proof.isVector && proof.kind == kind
        if (arguments.size != operands + 3 || flags != List(arguments.size) { false } ||
            !scalar(arguments[0], CoreKind.OBJECT) || !scalar(arguments.last(), CoreKind.VOID) ||
            arguments.subList(1, arguments.lastIndex).any { !scalar(it, CoreKind.LONG) })
            fault("Atomic byte-array primitive argument carrier mismatch: $primitive")
        val valid = if (tuple) result.isTuple && result.kind == CoreKind.UNKNOWN &&
            result.components?.size == 2 && result.primReps?.size == 1 &&
            scalar(result.components[0], CoreKind.VOID) && scalar(result.components[1], CoreKind.LONG)
        else scalar(result, CoreKind.VOID)
        if (!valid) fault("Atomic byte-array primitive result carrier mismatch: $primitive")
    }

    fun execute(value: Any?, index: Long, operand: Long, replacement: Long): Long =
        (value as? ManagedAllocation ?: fault("$primitive requires an owned MutableByteArray#"))
            .atomicInt(index, operand, replacement, this)

    companion object {
        fun named(name: String): AtomicIntArrayOp? = entries.firstOrNull { it.primitive == name }
    }
}

internal class AtomicIntArrayExpression(private val operation: AtomicIntArrayOp,
    @field:Children private val arguments: Array<Expr>) : Expr() {
    override fun execute(frame: VirtualFrame): Any {
        if (operation.tuple) fault("Tuple primitive requires a destination")
        val owner = arguments[0].execute(frame)
        val index = arguments[1].executeRequiredLong(frame)
        val value = arguments[2].executeRequiredLong(frame)
        ManagedByteArray.requireState(arguments[3].execute(frame))
        operation.execute(owner, index, value, 0)
        return Unit
    }

    override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Any? {
        val owner = arguments[0].execute(frame)
        val index = arguments[1].executeRequiredLong(frame)
        val operand = if (operation.operands > 0) arguments[2].executeRequiredLong(frame) else 0L
        val replacement = if (operation.operands == 2) arguments[3].executeRequiredLong(frame) else 0L
        ManagedByteArray.requireState(arguments.last().execute(frame))
        FrameAccess.writeLong(frame, slots[offset], operation.execute(owner, index, operand, replacement))
        return null
    }
}
