// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.frame.VirtualFrame

/** IEEE decomposition used by GHC 9.14.1's scalar floating decode primops.
 * Non-finite encodings follow the pinned RTS bit decomposition, not frexp.
 * Both backends write the primitive fields directly into their destination. */
internal enum class FloatDecodeOp(val primitive: String, val inputKind: CoreKind,
    private val fractionBits: Int, private val exponentBits: Int, private val bias: Int) {
    FLOAT("decodeFloat_Int#", CoreKind.FLOAT, 23, 8, 127),
    DOUBLE("decodeDouble_Int64#", CoreKind.DOUBLE, 52, 11, 1023),
    DOUBLE_WORDS("decodeDouble_2Int#", CoreKind.DOUBLE, 52, 11, 1023);

    val fields: Int get() = if (this == DOUBLE_WORDS) 4 else 2
    // The pinned RTS leaves zero's sign output uninitialized. THC chooses +1.
    fun sign(bits: Long): Long = if (mantissa(bits) < 0L) -1L else 1L
    fun high(bits: Long): Long = Math.abs(mantissa(bits)) ushr 32
    fun low(bits: Long): Long = Math.abs(mantissa(bits)) and 0xffff_ffffL

    private val hidden = 1L shl fractionBits
    private val fractionMask = hidden - 1
    private val exponentMask = (1L shl exponentBits) - 1
    private val signMask = 1L shl (fractionBits + exponentBits)

    fun mantissa(bits: Long): Long {
        val fraction = bits and fractionMask
        val magnitude = when {
            bits ushr fractionBits and exponentMask != 0L -> fraction or hidden
            fraction == 0L -> 0L
            else -> fraction shl (java.lang.Long.numberOfLeadingZeros(fraction) - (63 - fractionBits))
        }
        return if (bits and signMask == 0L) magnitude else -magnitude
    }

    fun exponent(bits: Long): Long {
        val exponent = bits ushr fractionBits and exponentMask
        if (exponent != 0L) return exponent - bias - fractionBits
        val fraction = bits and fractionMask
        if (fraction == 0L) return 0L
        val shift = java.lang.Long.numberOfLeadingZeros(fraction) - (63 - fractionBits)
        return (1 - bias - fractionBits - shift).toLong()
    }

    fun validate(arguments: List<CoreRepresentation>, lifted: List<*>, result: CoreRepresentation) {
        if (arguments.size != 1 || lifted != listOf(false))
            throw RuntimeFault("Floating decode requires one unlifted operand: $primitive")
        val input = arguments.single()
        if (input.kind != inputKind || input.isAggregate || input.isVector)
            throw RuntimeFault("Floating decode operand carrier mismatch: $primitive")
        if (!result.isTuple || result.components!!.size != fields || result.components.any {
                it.kind != CoreKind.LONG || it.isAggregate || it.isVector })
            throw RuntimeFault("Floating decode requires $fields scalar Long results: $primitive")
    }

    companion object {
        fun named(name: String): FloatDecodeOp? = entries.firstOrNull { it.primitive == name }
    }
}

internal class FloatDecodeExpression(private val operation: FloatDecodeOp, proof: CoreRepresentation,
    @field:Child private var operand: Expr) : Expr() {
    init { representation = proof.copy(evaluated = true) }
    override fun execute(frame: VirtualFrame): Nothing = fault("Floating decode requires a destination")
    override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Any? {
        val bits = if (operation == FloatDecodeOp.FLOAT)
            java.lang.Float.floatToRawIntBits(operand.executeRequiredFloat(frame)).toLong()
        else java.lang.Double.doubleToRawLongBits(operand.executeRequiredDouble(frame))
        if (operation == FloatDecodeOp.DOUBLE_WORDS) {
            FrameAccess.writeLong(frame, slots[offset], operation.sign(bits))
            FrameAccess.writeLong(frame, slots[offset + 1], operation.high(bits))
            FrameAccess.writeLong(frame, slots[offset + 2], operation.low(bits))
            FrameAccess.writeLong(frame, slots[offset + 3], operation.exponent(bits))
        } else {
            FrameAccess.writeLong(frame, slots[offset], operation.mantissa(bits))
            FrameAccess.writeLong(frame, slots[offset + 1], operation.exponent(bits))
        }
        return null
    }
}
