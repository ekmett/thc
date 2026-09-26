// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.frame.VirtualFrame

internal fun rawBitCastPrimitive(name: String, arguments: Array<Expr>): Expr? {
    if (name !in setOf("castFloatToWord32#", "castWord32ToFloat#", "castDoubleToWord64#", "castWord64ToDouble#")) return null
    if (arguments.size != 1) throw RuntimeFault("Primitive arity mismatch: $name")
    return when (name) {
        "castFloatToWord32#" -> FloatToWord32(arguments[0])
        "castWord32ToFloat#" -> Word32ToFloat(arguments[0])
        "castDoubleToWord64#" -> DoubleToWord64(arguments[0])
        else -> Word64ToDouble(arguments[0])
    }
}

private class FloatToWord32(@field:Child private var value: Expr) : Expr() {
    init { representation = CoreRepresentation(CoreKind.LONG, evaluated = true, primReps = listOf("Word32Rep")) }
    override fun execute(frame: VirtualFrame): Any = executeLong(frame)
    override fun executeLong(frame: VirtualFrame): Long = (java.lang.Float.floatToRawIntBits(value.executeRequiredFloat(frame)).toLong() and 0xffffffffL)
}
private class Word32ToFloat(@field:Child private var value: Expr) : Expr() {
    init { representation = CoreRepresentation(CoreKind.FLOAT, evaluated = true, primReps = listOf("FloatRep")) }
    override fun execute(frame: VirtualFrame): Any = executeFloat(frame)
    override fun executeFloat(frame: VirtualFrame): Float = java.lang.Float.intBitsToFloat((value.executeRequiredLong(frame)).toInt())
}
private class DoubleToWord64(@field:Child private var value: Expr) : Expr() {
    init { representation = CoreRepresentation(CoreKind.LONG, evaluated = true, primReps = listOf("Word64Rep")) }
    override fun execute(frame: VirtualFrame): Any = executeLong(frame)
    override fun executeLong(frame: VirtualFrame): Long = java.lang.Double.doubleToRawLongBits(value.executeRequiredDouble(frame))
}
private class Word64ToDouble(@field:Child private var value: Expr) : Expr() {
    init { representation = CoreRepresentation(CoreKind.DOUBLE, evaluated = true, primReps = listOf("DoubleRep")) }
    override fun execute(frame: VirtualFrame): Any = executeDouble(frame)
    override fun executeDouble(frame: VirtualFrame): Double = java.lang.Double.longBitsToDouble(value.executeRequiredLong(frame))
}
