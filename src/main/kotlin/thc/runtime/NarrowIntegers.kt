// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime

import com.oracle.truffle.api.staticobject.DefaultStaticProperty
import com.oracle.truffle.api.frame.VirtualFrame

/** Storage width and signedness are independent of the JVM Int computation
 * carrier. Word32 uses all 32 raw bits, including a negative JVM Int. */
internal enum class NarrowInteger(val rep: String, val bits: Int, val unsigned: Boolean) {
    INT8("Int8Rep", 8, false), WORD8("Word8Rep", 8, true),
    INT16("Int16Rep", 16, false), WORD16("Word16Rep", 16, true),
    INT32("Int32Rep", 32, false), WORD32("Word32Rep", 32, true);

    val storageClass: Class<*> = when (bits) {
        8 -> Byte::class.javaPrimitiveType!!
        16 -> Short::class.javaPrimitiveType!!
        else -> Int::class.javaPrimitiveType!!
    }

    fun narrow(value: Int): Int = when (bits) {
        8 -> if (unsigned) value and 255 else value.toByte().toInt()
        16 -> if (unsigned) value and 65535 else value.toShort().toInt()
        else -> value
    }

    /** Public scalar inputs are range checked before adopting the exact guest carrier. */
    fun fromHost(value: Long): Int {
        val min = if (unsigned) 0L else -(1L shl (bits - 1))
        val max = if (unsigned) (1L shl bits) - 1 else (1L shl (bits - 1)) - 1
        if (value !in min..max) fault("Public narrow integer argument is out of range")
        return value.toInt()
    }

    /** Only declared widening/native-word boundaries use this conversion. */
    fun widen(value: Int): Long = if (this == WORD32) Integer.toUnsignedLong(value) else narrow(value).toLong()

    fun read(property: DefaultStaticProperty, storage: Any): Int = when (bits) {
        8 -> property.getByte(storage).toInt().let { if (unsigned) it and 255 else it }
        16 -> property.getShort(storage).toInt().let { if (unsigned) it and 65535 else it }
        else -> property.getInt(storage)
    }

    fun write(property: DefaultStaticProperty, storage: Any, value: Int) {
        when (bits) {
            8 -> property.setByte(storage, value.toByte())
            16 -> property.setShort(storage, value.toShort())
            else -> property.setInt(storage, value)
        }
    }

    companion object {
        fun fromRep(rep: String?): NarrowInteger? = when (rep) {
            "Int8Rep" -> INT8; "Word8Rep" -> WORD8
            "Int16Rep" -> INT16; "Word16Rep" -> WORD16
            "Int32Rep" -> INT32; "Word32Rep" -> WORD32
            else -> null
        }
    }
}

/** Lowering-time operation descriptor. Machine-width narrow8/16/32Int#/Word#
 * are deliberately absent: those consume and produce machine Long values. */
internal class NarrowScalarOp private constructor(val integer: NarrowInteger, val code: Code,
    val sourceLong: Boolean = false, val resultLong: Boolean = false) {
    enum class Code { CONVERT, NEGATE, ADD, SUB, MUL, QUOT, REM, EQ, NE, LT, LE, GT, GE, AND, OR, XOR, NOT, SHL, SRA, SRL }
    val unary = code == Code.CONVERT || code == Code.NEGATE || code == Code.NOT
    val shift = code == Code.SHL || code == Code.SRA || code == Code.SRL
    val result = CoreRepresentation(CoreKind.LONG, true, true,
        listOf(if (!resultLong) integer.rep else if (code == Code.CONVERT && integer.unsigned) "WordRep" else "IntRep"))

    /** Lowering checks actual carrier/aggregate differences, not scalar names
     * that share a carrier. An unconstrained legacy operand is checked at use. */
    fun validateOperand(proof: CoreRepresentation, index: Int) {
        val wide = sourceLong && index == 0 || shift && index == 1
        if (proof.isTypedTransport || proof.kind != CoreKind.UNKNOWN &&
            (if (wide) !proof.isLong else !proof.isInt))
            fault("Narrow primitive operand carrier mismatch")
    }

    fun intResult(left: Int, right: Int): Int {
        val x = integer.narrow(left)
        val y = integer.narrow(right)
        val value = when {
            code == Code.CONVERT -> x
            code == Code.NEGATE -> -x
            code == Code.ADD -> x + y
            code == Code.SUB -> x - y
            code == Code.MUL -> x * y
            code == Code.QUOT -> if (integer.unsigned) Integer.divideUnsigned(x, y) else x / y
            code == Code.REM -> if (integer.unsigned) Integer.remainderUnsigned(x, y) else x % y
            code == Code.AND -> x and y
            code == Code.OR -> x or y
            code == Code.XOR -> x xor y
            code == Code.NOT -> x.inv()
            code == Code.SHL -> x shl right
            code == Code.SRA -> x shr right
            code == Code.SRL -> (if (integer.bits == 32) x else x and ((1 shl integer.bits) - 1)) ushr right
            else -> fault("Comparison requires a machine Int# result")
        }
        return integer.narrow(value)
    }

    fun longResult(left: Int, right: Int): Long {
        if (code == Code.CONVERT) return integer.widen(left)
        val x = integer.narrow(left)
        val y = integer.narrow(right)
        val order = if (integer.unsigned) Integer.compareUnsigned(x, y) else x.compareTo(y)
        val value = when {
            code == Code.EQ -> order == 0; code == Code.NE -> order != 0
            code == Code.LT -> order < 0; code == Code.LE -> order <= 0
            code == Code.GT -> order > 0; code == Code.GE -> order >= 0
            else -> fault("Narrow arithmetic requires an Int carrier")
        }
        return if (value) 1L else 0L
    }

    companion object {
        private val operations = buildMap {
            for (integer in NarrowInteger.entries) {
                val family = integer.rep.removeSuffix("Rep")
                val lower = family.replaceFirstChar(Char::lowercaseChar)
                val machine = if (integer.unsigned) "word" else "int"
                put("${machine}To$family#", NarrowScalarOp(integer, Code.CONVERT, sourceLong = true))
                put("${lower}To${machine.replaceFirstChar(Char::uppercaseChar)}#", NarrowScalarOp(integer, Code.CONVERT, resultLong = true))
                val other = (if (integer.unsigned) "int" else "word") + integer.bits
                put("${other}To$family#", NarrowScalarOp(integer, Code.CONVERT))
                for ((prefix, code) in mapOf("negate" to Code.NEGATE, "plus" to Code.ADD,
                    "sub" to Code.SUB, "times" to Code.MUL, "quot" to Code.QUOT, "rem" to Code.REM,
                    "eq" to Code.EQ, "ne" to Code.NE, "lt" to Code.LT, "le" to Code.LE,
                    "gt" to Code.GT, "ge" to Code.GE, "and" to Code.AND, "or" to Code.OR,
                    "xor" to Code.XOR, "not" to Code.NOT, "uncheckedShiftL" to Code.SHL,
                    "uncheckedShiftRA" to Code.SRA, "uncheckedShiftRL" to Code.SRL)) {
                    if (integer.unsigned && code in setOf(Code.NEGATE, Code.SRA)) continue
                    if (!integer.unsigned && code in setOf(Code.AND, Code.OR, Code.XOR, Code.NOT)) continue
                    put("$prefix$family#", NarrowScalarOp(integer, code,
                        resultLong = code in setOf(Code.EQ, Code.NE, Code.LT, Code.LE, Code.GT, Code.GE)))
                }
            }
        }
        fun named(name: String): NarrowScalarOp? = operations[name]
    }
}

internal class NarrowScalarExpression(name: String, private val operation: NarrowScalarOp,
    @field:Children private var arguments: Array<Expr>) : Expr() {
    init {
        if (arguments.size != if (operation.unary) 1 else 2) fault("Primitive arity mismatch: $name")
        arguments.forEachIndexed { index, argument -> operation.validateOperand(argument.representation, index) }
        representation = operation.result
    }
    override fun execute(frame: VirtualFrame): Any =
        if (operation.resultLong) executeLong(frame) else executeInt(frame)
    override fun executeInt(frame: VirtualFrame): Int {
        if (operation.resultLong) fault("Expected primitive Int result")
        val left = if (operation.sourceLong) arguments[0].executeRequiredLong(frame).toInt()
            else arguments[0].executeRequiredInt(frame)
        val right = if (operation.unary) 0 else if (operation.shift) arguments[1].executeRequiredLong(frame).toInt()
            else arguments[1].executeRequiredInt(frame)
        return operation.intResult(left, right)
    }
    override fun executeLong(frame: VirtualFrame): Long {
        if (!operation.resultLong) fault("Expected primitive Long result")
        val left = arguments[0].executeRequiredInt(frame)
        val right = if (operation.unary) 0 else arguments[1].executeRequiredInt(frame)
        return operation.longResult(left, right)
    }
}

/** Only a selected sum arm may reinterpret the canonical native WordSlot.
 * No conversion is installed globally on Long-to-Int reads. */
internal class SumNarrowRead(private val slot: Int, private val integer: NarrowInteger,
    proof: CoreRepresentation) : Expr() {
    init { representation = proof.copy(evaluated = true) }
    override fun execute(frame: VirtualFrame): Any = executeInt(frame)
    override fun executeInt(frame: VirtualFrame): Int = integer.narrow(frame.getLong(slot).toInt())
}
