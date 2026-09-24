package thc.runtime

import com.oracle.truffle.api.frame.VirtualFrame

// Resolve names during lowering. String equality may simplify after frame
// virtualization, leaving unreachable wrong-kind frame reads visible to PEA.
private const val FLOAT_ADD = 0
private const val FLOAT_SUB = 1
private const val FLOAT_MUL = 2
private const val FLOAT_DIV = 3
private const val FLOAT_NEG = 4
private const val DOUBLE_ADD = 5
private const val DOUBLE_SUB = 6
private const val DOUBLE_MUL = 7
private const val DOUBLE_DIV = 8
private const val DOUBLE_NEG = 9
private const val FLOAT_EQ = 10
private const val FLOAT_NE = 11
private const val FLOAT_LT = 12
private const val FLOAT_LE = 13
private const val FLOAT_GT = 14
private const val FLOAT_GE = 15
private const val DOUBLE_EQ = 16
private const val DOUBLE_NE = 17
private const val DOUBLE_LT = 18
private const val DOUBLE_LE = 19
private const val DOUBLE_GT = 20
private const val DOUBLE_GE = 21
private const val INT_FLOAT = 22
private const val INT_DOUBLE = 23
private const val FLOAT_INT = 24
private const val DOUBLE_INT = 25
private const val FLOAT_DOUBLE = 26
private const val DOUBLE_FLOAT = 27

private val floatingOperations = mapOf(
    "plusFloat#" to FLOAT_ADD, "minusFloat#" to FLOAT_SUB, "timesFloat#" to FLOAT_MUL,
    "divideFloat#" to FLOAT_DIV, "negateFloat#" to FLOAT_NEG,
    "+##" to DOUBLE_ADD, "-##" to DOUBLE_SUB, "*##" to DOUBLE_MUL, "/##" to DOUBLE_DIV, "negateDouble#" to DOUBLE_NEG,
    "eqFloat#" to FLOAT_EQ, "neFloat#" to FLOAT_NE, "ltFloat#" to FLOAT_LT,
    "leFloat#" to FLOAT_LE, "gtFloat#" to FLOAT_GT, "geFloat#" to FLOAT_GE,
    "==##" to DOUBLE_EQ, "/=##" to DOUBLE_NE, "<##" to DOUBLE_LT,
    "<=##" to DOUBLE_LE, ">##" to DOUBLE_GT, ">=##" to DOUBLE_GE,
    "int2Float#" to INT_FLOAT, "int2Double#" to INT_DOUBLE, "float2Int#" to FLOAT_INT,
    "double2Int#" to DOUBLE_INT, "float2Double#" to FLOAT_DOUBLE, "double2Float#" to DOUBLE_FLOAT)

/** JVM float operations round each result to binary32; no implicit numeric widening. */
internal fun floatingPrimitive(name: String, arguments: Array<Expr>): Expr? {
    rawBitCastPrimitive(name, arguments)?.let { return it }
    if (name == "sqrtFloat#" || name == "sqrtDouble#") {
        if (arguments.size != 1) throw RuntimeFault("Primitive arity mismatch: $name")
        return if (name == "sqrtFloat#") FloatSqrt(arguments[0]) else DoubleSqrt(arguments[0])
    }
    val kind = when (name) {
        "plusFloat#", "minusFloat#", "timesFloat#", "divideFloat#", "negateFloat#",
        "int2Float#", "double2Float#" -> CoreKind.FLOAT
        "+##", "-##", "*##", "/##", "negateDouble#", "int2Double#", "float2Double#" -> CoreKind.DOUBLE
        "eqFloat#", "neFloat#", "ltFloat#", "leFloat#", "gtFloat#", "geFloat#",
        "==##", "/=##", "<##", "<=##", ">##", ">=##", "float2Int#", "double2Int#" -> CoreKind.LONG
        else -> return null
    }
    val unary = name in setOf("negateFloat#", "negateDouble#", "int2Float#", "int2Double#",
        "float2Int#", "double2Int#", "float2Double#", "double2Float#")
    if (arguments.size != if (unary) 1 else 2) throw RuntimeFault("Primitive arity mismatch: $name")
    return FloatingPrimitive(floatingOperations.getValue(name), arguments, kind)
}

// Keep each operand and result primitive throughout execution. Float inputs are
// exactly widened for JVM sqrt and rounded back to their binary32 result.
private class FloatSqrt(@field:Child private var value: Expr) : Expr() {
    init { representation = CoreRepresentation(CoreKind.FLOAT, evaluated = true) }
    override fun execute(frame: VirtualFrame): Any = executeFloat(frame)
    override fun executeFloat(frame: VirtualFrame): Float = Math.sqrt(value.executeRequiredFloat(frame).toDouble()).toFloat()
}

private class DoubleSqrt(@field:Child private var value: Expr) : Expr() {
    init { representation = CoreRepresentation(CoreKind.DOUBLE, evaluated = true) }
    override fun execute(frame: VirtualFrame): Any = executeDouble(frame)
    override fun executeDouble(frame: VirtualFrame): Double = Math.sqrt(value.executeRequiredDouble(frame))
}

private class FloatingPrimitive(private val operation: Int,
    @field:Children private var arguments: Array<Expr>, private val resultKind: CoreKind) : Expr() {
    init { representation = CoreRepresentation(resultKind, evaluated = true) }
    override fun execute(frame: VirtualFrame): Any =
        if (resultKind == CoreKind.FLOAT) executeFloat(frame)
        else if (resultKind == CoreKind.DOUBLE) executeDouble(frame) else executeLong(frame)

    override fun executeFloat(frame: VirtualFrame): Float {
        if (operation == INT_FLOAT) return arguments[0].executeRequiredLong(frame).toFloat()
        if (operation == DOUBLE_FLOAT) return arguments[0].executeRequiredDouble(frame).toFloat()
        val x = arguments[0].executeRequiredFloat(frame)
        if (operation == FLOAT_NEG) return -x
        val y = arguments[1].executeRequiredFloat(frame)
        return when (operation) {
            FLOAT_ADD -> x + y
            FLOAT_SUB -> x - y
            FLOAT_MUL -> x * y
            FLOAT_DIV -> x / y
            else -> fault("Expected Float primitive result")
        }
    }

    override fun executeDouble(frame: VirtualFrame): Double {
        if (operation == INT_DOUBLE) return arguments[0].executeRequiredLong(frame).toDouble()
        if (operation == FLOAT_DOUBLE) return arguments[0].executeRequiredFloat(frame).toDouble()
        val x = arguments[0].executeRequiredDouble(frame)
        if (operation == DOUBLE_NEG) return -x
        val y = arguments[1].executeRequiredDouble(frame)
        return when (operation) {
            DOUBLE_ADD -> x + y
            DOUBLE_SUB -> x - y
            DOUBLE_MUL -> x * y
            DOUBLE_DIV -> x / y
            else -> fault("Expected Double primitive result")
        }
    }

    override fun executeLong(frame: VirtualFrame): Long {
        // GHC leaves non-finite/out-of-range floating-to-Int conversions undefined.
        // Differential fixtures exercise only finite, representable operands.
        if (operation == FLOAT_INT) return arguments[0].executeRequiredFloat(frame).toLong()
        if (operation == DOUBLE_INT) return arguments[0].executeRequiredDouble(frame).toLong()
        val result = if (operation in FLOAT_EQ..FLOAT_GE) {
            val x = arguments[0].executeRequiredFloat(frame)
            val y = arguments[1].executeRequiredFloat(frame)
            when (operation) {
                FLOAT_EQ -> x == y; FLOAT_NE -> x != y
                FLOAT_LT -> x < y; FLOAT_LE -> x <= y
                FLOAT_GT -> x > y; FLOAT_GE -> x >= y
                else -> fault("Unsupported Float comparison")
            }
        } else {
            val x = arguments[0].executeRequiredDouble(frame)
            val y = arguments[1].executeRequiredDouble(frame)
            when (operation) {
                DOUBLE_EQ -> x == y; DOUBLE_NE -> x != y
                DOUBLE_LT -> x < y; DOUBLE_LE -> x <= y
                DOUBLE_GT -> x > y; DOUBLE_GE -> x >= y
                else -> fault("Unsupported Double comparison")
            }
        }
        return if (result) 1L else 0L
    }
}
