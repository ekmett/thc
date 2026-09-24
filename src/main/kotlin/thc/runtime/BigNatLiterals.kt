package thc.runtime

import java.math.BigInteger
import java.nio.ByteOrder

/** GHC CorePrep's BigNat literal layout for the supported 64-bit target.
 * Decimal Core is target-independent; limbs are least-significant first and
 * each limb uses native byte order. This is load-time constant construction,
 * not a guest Integer representation or arithmetic implementation. */
internal object BigNatLiterals {
    val proof = CoreRepresentation(CoreKind.OBJECT, evaluated = true, present = true,
        primReps = listOf("BoxedRep (Just Unlifted)"))

    fun proof(expression: List<Any?>): CoreRepresentation {
        val actual = CoreRepresentations.expression(expression)
        val unconstrained = actual.kind == CoreKind.UNKNOWN && actual.primReps == null &&
            !actual.isAggregate && !actual.isVector
        if (actual.present && !unconstrained && (actual.kind != CoreKind.OBJECT ||
                actual.primReps != proof.primReps || actual.isAggregate || actual.isVector))
            throw RuntimeFault("bignat literal requires exact unlifted ByteArray# metadata")
        return proof
    }

    internal fun byteSize(bits: Long): Int {
        if (bits < 0 || bits > (Int.MAX_VALUE.toLong() / 8) * 64)
            throw RuntimeFault("bignat literal exceeds managed byte-array size")
        return (((bits + 63) / 64) * 8).toInt()
    }

    fun decode(decimal: String): ByteArray {
        if (decimal.isEmpty() || decimal.any { it !in '0'..'9' } || decimal.length > 1 && decimal[0] == '0')
            throw RuntimeFault("bignat literal requires canonical nonnegative decimal")
        val number = BigInteger(decimal)
        val output = ByteArray(byteSize(number.bitLength().toLong()))
        // BigInteger's temporary big-endian sign byte is not part of the limbs.
        val bytes = number.toByteArray()
        val count = (number.bitLength().toLong() + 7) / 8
        val little = ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN
        for (i in 0 until count.toInt()) {
            val position = if (little) i else (i / 8) * 8 + 7 - i % 8
            output[position] = bytes[bytes.lastIndex - i]
        }
        return output // A distinct private constant per literal; no interning.
    }
}
