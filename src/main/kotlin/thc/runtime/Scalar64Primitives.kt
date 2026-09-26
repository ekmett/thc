// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.frame.VirtualFrame

/** Select a shared Long instruction, without rewriting any exact Core type proof. */
internal fun scalar64PrimitiveOperation(name: String): String = when (name) {
    "int64ToWord64#", "wordToWord64#" -> "int2Word#"
    "word64ToInt64#", "word64ToWord#" -> "word2Int#"
    "negateInt64#" -> "negateInt#"
    "plusInt64#", "plusWord64#" -> "+#"
    "subInt64#", "subWord64#" -> "-#"
    "timesInt64#", "timesWord64#" -> "*#"
    "quotInt64#" -> "quotInt#"
    "remInt64#" -> "remInt#"
    "quotWord64#" -> "quotWord#"
    "remWord64#" -> "remWord#"
    "eqInt64#", "eqWord64#" -> "==#"
    "neInt64#", "neWord64#" -> "/=#"
    "ltInt64#" -> "<#"
    "leInt64#" -> "<=#"
    "gtInt64#" -> ">#"
    "geInt64#" -> ">=#"
    "ltWord64#" -> "ltWord#"
    "leWord64#" -> "leWord#"
    "gtWord64#" -> "gtWord#"
    "geWord64#" -> "geWord#"
    "and64#" -> "and#"
    "or64#" -> "or#"
    "xor64#" -> "xor#"
    "not64#" -> "not#"
    "uncheckedIShiftL64#", "uncheckedShiftL64#" -> "uncheckedShiftL#"
    "uncheckedIShiftRA64#" -> "uncheckedIShiftRA#"
    "uncheckedIShiftRL64#", "uncheckedShiftRL64#" -> "uncheckedShiftRL#"
    else -> name
}

/** Unsigned decimal literals retain their complete bit pattern in a Long. */
internal fun word64Literal(value: String): Long {
    val number = value.toULongOrNull()
    if (number == null || number.toString() != value) throw RuntimeFault("Invalid word64 literal: $value")
    return number.toLong()
}

/** GHC 9.14.1 machine-width tuple primops writing exact scalar destinations. */
internal enum class TupleArithmeticOp(val primitive: String, val resultArity: Int = 2,
    val argumentArity: Int = 2, private val narrowBits: Int = 64, private val unsigned: Boolean = false) {
    QUOT_REM_INT("quotRemInt#"), QUOT_REM_WORD("quotRemWord#"),
    QUOT_REM_INT8("quotRemInt8#", narrowBits = 8),
    QUOT_REM_INT16("quotRemInt16#", narrowBits = 16),
    QUOT_REM_INT32("quotRemInt32#", narrowBits = 32),
    QUOT_REM_WORD8("quotRemWord8#", narrowBits = 8, unsigned = true),
    QUOT_REM_WORD16("quotRemWord16#", narrowBits = 16, unsigned = true),
    QUOT_REM_WORD32("quotRemWord32#", narrowBits = 32, unsigned = true),
    QUOT_REM_WORD_2("quotRemWord2#", argumentArity = 3),
    ADD_INT_C("addIntC#"), SUB_INT_C("subIntC#"),
    ADD_WORD_C("addWordC#"), SUB_WORD_C("subWordC#"),
    PLUS_WORD_2("plusWord2#"), TIMES_WORD_2("timesWord2#"),
    TIMES_INT_2("timesInt2#", resultArity = 3);

    private fun divisionDomain(left: Long, right: Long) {
        if (right == 0L || this == QUOT_REM_INT && left == Long.MIN_VALUE && right == -1L)
            throw RuntimeFault("Undefined input to $primitive")
    }
    private fun narrow(value: Long): Long = if (unsigned) value and (-1L ushr (64 - narrowBits))
        else (value shl (64 - narrowBits)) shr (64 - narrowBits)
    fun first(left: Long, right: Long): Long = when (this) {
        QUOT_REM_INT -> { divisionDomain(left, right); left / right }
        QUOT_REM_WORD -> { divisionDomain(left, right); java.lang.Long.divideUnsigned(left, right) }
        QUOT_REM_INT8, QUOT_REM_INT16, QUOT_REM_INT32, QUOT_REM_WORD8, QUOT_REM_WORD16, QUOT_REM_WORD32 -> {
            val x = narrow(left); val y = narrow(right)
            if (y == 0L) throw RuntimeFault("Undefined input to $primitive")
            narrow(x / y)
        }
        QUOT_REM_WORD_2 -> error("Double-word division needs three operands")
        ADD_INT_C, ADD_WORD_C -> left + right
        SUB_INT_C, SUB_WORD_C -> left - right
        PLUS_WORD_2 -> if (java.lang.Long.compareUnsigned(left + right, left) < 0) 1L else 0L
        TIMES_WORD_2 -> Math.unsignedMultiplyHigh(left, right)
        TIMES_INT_2 -> if (Math.multiplyHigh(left, right) == ((left * right) shr 63)) 0L else 1L
    }
    fun second(left: Long, right: Long): Long = when (this) {
        QUOT_REM_INT -> { divisionDomain(left, right); left % right }
        QUOT_REM_WORD -> { divisionDomain(left, right); java.lang.Long.remainderUnsigned(left, right) }
        QUOT_REM_INT8, QUOT_REM_INT16, QUOT_REM_INT32, QUOT_REM_WORD8, QUOT_REM_WORD16, QUOT_REM_WORD32 -> {
            val x = narrow(left); val y = narrow(right)
            if (y == 0L) throw RuntimeFault("Undefined input to $primitive")
            narrow(x % y)
        }
        QUOT_REM_WORD_2 -> error("Double-word division needs three operands")
        ADD_INT_C -> if (((left xor (left + right)) and (right xor (left + right))) < 0) 1L else 0L
        SUB_INT_C -> if (((left xor right) and (left xor (left - right))) < 0) 1L else 0L
        ADD_WORD_C -> if (java.lang.Long.compareUnsigned(left + right, left) < 0) 1L else 0L
        SUB_WORD_C -> if (java.lang.Long.compareUnsigned(left, right) < 0) 1L else 0L
        PLUS_WORD_2 -> left + right
        TIMES_WORD_2 -> left * right
        TIMES_INT_2 -> Math.multiplyHigh(left, right)
    }
    fun third(left: Long, right: Long): Long {
        check(this == TIMES_INT_2)
        return left * right
    }
    fun validate(arguments: List<CoreRepresentation>, lifted: List<*>, result: CoreRepresentation) {
        if (arguments.size != argumentArity) throw RuntimeFault("Primitive arity mismatch: $primitive")
        fun scalar(rep: CoreRepresentation): Boolean = !rep.isAggregate && !rep.isVector && rep.kind == CoreKind.LONG
        if (lifted.size != argumentArity || lifted.any { it != false } || arguments.any { !scalar(it) })
            throw RuntimeFault("Tuple primitive argument representation mismatch: $primitive")
        if (!result.isTuple || result.components!!.size != resultArity ||
            result.components.any { !scalar(it) })
            throw RuntimeFault("Tuple primitive result representation mismatch: $primitive")
    }
    companion object {
        fun named(name: String): TupleArithmeticOp? = entries.firstOrNull { it.primitive == name }
    }
}

/** Unsigned 128/64 division, with the GHC high < divisor precondition checked.
 * The restoring loop maintains remainder < divisor. Its carry is the 65th bit,
 * so subtracting even a divisor with its top bit set is ordinary wrapping Long
 * arithmetic. The quotient fits one word; low - quotient * divisor is the
 * remainder. No BigInteger, tuple, or scratch array is needed by either backend.
 */
internal fun unsignedDoubleWordQuotient(high: Long, low: Long, divisor: Long): Long {
    if (divisor == 0L || java.lang.Long.compareUnsigned(high, divisor) >= 0)
        throw RuntimeFault("Undefined input to quotRemWord2#: high must be less than divisor")
    if (high == 0L) return java.lang.Long.divideUnsigned(low, divisor)
    var remainder = high
    var quotient = low
    repeat(64) {
        val carry = remainder < 0L
        remainder = (remainder shl 1) or (quotient ushr 63)
        quotient = quotient shl 1
        if (carry || java.lang.Long.compareUnsigned(remainder, divisor) >= 0) {
            remainder -= divisor
            quotient = quotient or 1L
        }
    }
    return quotient
}

internal class DoubleWordDivisionExpression(proof: CoreRepresentation,
    @field:Child private var high: Expr, @field:Child private var low: Expr,
    @field:Child private var divisor: Expr) : Expr() {
    init { representation = proof.copy(evaluated = true) }
    override fun execute(frame: VirtualFrame): Nothing = fault("Tuple primitive requires a destination")
    override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Any? {
        val h = high.executeRequiredLong(frame)
        val l = low.executeRequiredLong(frame)
        val d = divisor.executeRequiredLong(frame)
        val q = unsignedDoubleWordQuotient(h, l, d)
        FrameAccess.writeLong(frame, slots[offset], q)
        FrameAccess.writeLong(frame, slots[offset + 1], l - q * d)
        return null
    }
}

internal class TupleArithmeticExpression(private val operation: TupleArithmeticOp, proof: CoreRepresentation,
    @field:Child private var left: Expr, @field:Child private var right: Expr) : Expr() {
    init { representation = proof.copy(evaluated = true) }
    override fun execute(frame: VirtualFrame): Nothing = fault("Tuple primitive requires a destination")
    override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Any? {
        val x = left.executeRequiredLong(frame)
        val y = right.executeRequiredLong(frame)
        val first = operation.first(x, y)
        val second = operation.second(x, y)
        val third = if (operation.resultArity == 3) operation.third(x, y) else 0L
        FrameAccess.writeLong(frame, slots[offset], first)
        FrameAccess.writeLong(frame, slots[offset + 1], second)
        if (operation.resultArity == 3) FrameAccess.writeLong(frame, slots[offset + 2], third)
        return null
    }
}
