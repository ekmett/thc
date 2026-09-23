package thc.runtime

import com.oracle.truffle.api.frame.VirtualFrame

/** GHC 9.14.1 machine-width tuple primops; each operation writes two scalar locals. */
internal enum class TupleArithmeticOp(val primitive: String, val scalarRep: String) {
    QUOT_REM_INT("quotRemInt#", "IntRep"), QUOT_REM_WORD("quotRemWord#", "WordRep"),
    ADD_INT_C("addIntC#", "IntRep"), SUB_INT_C("subIntC#", "IntRep"),
    PLUS_WORD_2("plusWord2#", "WordRep"), TIMES_WORD_2("timesWord2#", "WordRep");

    private fun divisionDomain(left: Long, right: Long) {
        if (right == 0L || this == QUOT_REM_INT && left == Long.MIN_VALUE && right == -1L)
            throw RuntimeFault("Undefined input to $primitive")
    }
    fun first(left: Long, right: Long): Long = when (this) {
        QUOT_REM_INT -> { divisionDomain(left, right); left / right }
        QUOT_REM_WORD -> { divisionDomain(left, right); java.lang.Long.divideUnsigned(left, right) }
        ADD_INT_C -> left + right
        SUB_INT_C -> left - right
        PLUS_WORD_2 -> if (java.lang.Long.compareUnsigned(left + right, left) < 0) 1L else 0L
        TIMES_WORD_2 -> Math.unsignedMultiplyHigh(left, right)
    }
    fun second(left: Long, right: Long): Long = when (this) {
        QUOT_REM_INT -> { divisionDomain(left, right); left % right }
        QUOT_REM_WORD -> { divisionDomain(left, right); java.lang.Long.remainderUnsigned(left, right) }
        ADD_INT_C -> if (((left xor (left + right)) and (right xor (left + right))) < 0) 1L else 0L
        SUB_INT_C -> if (((left xor right) and (left xor (left - right))) < 0) 1L else 0L
        PLUS_WORD_2 -> left + right
        TIMES_WORD_2 -> left * right
    }
    fun validate(arguments: List<CoreRepresentation>, lifted: List<*>, result: CoreRepresentation) {
        if (arguments.size != 2) throw RuntimeFault("Primitive arity mismatch: $primitive")
        fun scalar(rep: CoreRepresentation): Boolean = !rep.isTuple && rep.kind == CoreKind.LONG && rep.primReps == listOf(scalarRep)
        if (lifted != listOf(false, false) || arguments.any { !scalar(it) })
            throw RuntimeFault("Tuple primitive argument representation mismatch: $primitive")
        if (!result.isTuple || result.components!!.size != 2 || result.components.any { !scalar(it) } ||
            result.primReps != listOf(scalarRep, scalarRep))
            throw RuntimeFault("Tuple primitive result representation mismatch: $primitive")
    }
    companion object {
        fun named(name: String): TupleArithmeticOp? = entries.firstOrNull { it.primitive == name }
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
        FrameAccess.writeLong(frame, slots[offset], first)
        FrameAccess.writeLong(frame, slots[offset + 1], second)
        return null
    }
}
