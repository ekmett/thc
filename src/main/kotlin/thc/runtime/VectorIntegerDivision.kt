// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime

import com.oracle.truffle.api.nodes.ExplodeLoop
import jdk.incubator.vector.*

/** Integer SIMD has no division instruction on the pinned target.
 * Keep lane arithmetic explicit, including unsigned 64-bit division.
 */
internal object VectorIntegerDivision {
    @JvmStatic @ExplodeLoop
    fun quotByte(left: ByteVector, right: ByteVector, unsigned: Boolean): ByteVector {
        var result = left
        for (lane in 0 until left.length()) {
            val a = left.lane(lane).toLong()
            val b = right.lane(lane).toLong()
            val value = if (unsigned) (a and 0xffL) / (b and 0xffL) else a / b
            result = result.withLane(lane, value.toByte())
        }
        return result
    }
    @JvmStatic @ExplodeLoop
    fun remByte(left: ByteVector, right: ByteVector, unsigned: Boolean): ByteVector {
        var result = left
        for (lane in 0 until left.length()) {
            val a = left.lane(lane).toLong()
            val b = right.lane(lane).toLong()
            val value = if (unsigned) (a and 0xffL) % (b and 0xffL) else a % b
            result = result.withLane(lane, value.toByte())
        }
        return result
    }
    @JvmStatic @ExplodeLoop
    fun quotShort(left: ShortVector, right: ShortVector, unsigned: Boolean): ShortVector {
        var result = left
        for (lane in 0 until left.length()) {
            val a = left.lane(lane).toLong()
            val b = right.lane(lane).toLong()
            val value = if (unsigned) (a and 0xffffL) / (b and 0xffffL) else a / b
            result = result.withLane(lane, value.toShort())
        }
        return result
    }
    @JvmStatic @ExplodeLoop
    fun remShort(left: ShortVector, right: ShortVector, unsigned: Boolean): ShortVector {
        var result = left
        for (lane in 0 until left.length()) {
            val a = left.lane(lane).toLong()
            val b = right.lane(lane).toLong()
            val value = if (unsigned) (a and 0xffffL) % (b and 0xffffL) else a % b
            result = result.withLane(lane, value.toShort())
        }
        return result
    }
    @JvmStatic @ExplodeLoop
    fun quotInt(left: IntVector, right: IntVector, unsigned: Boolean): IntVector {
        var result = left
        for (lane in 0 until left.length()) {
            val a = left.lane(lane).toLong()
            val b = right.lane(lane).toLong()
            val value = if (unsigned) (a and 0xffff_ffffL) / (b and 0xffff_ffffL) else a / b
            result = result.withLane(lane, value.toInt())
        }
        return result
    }
    @JvmStatic @ExplodeLoop
    fun remInt(left: IntVector, right: IntVector, unsigned: Boolean): IntVector {
        var result = left
        for (lane in 0 until left.length()) {
            val a = left.lane(lane).toLong()
            val b = right.lane(lane).toLong()
            val value = if (unsigned) (a and 0xffff_ffffL) % (b and 0xffff_ffffL) else a % b
            result = result.withLane(lane, value.toInt())
        }
        return result
    }
    @JvmStatic @ExplodeLoop
    fun quotLong(left: LongVector, right: LongVector, unsigned: Boolean): LongVector {
        var result = left
        for (lane in 0 until left.length()) {
            val a = left.lane(lane)
            val b = right.lane(lane)
            val value = if (unsigned) java.lang.Long.divideUnsigned(a, b) else a / b
            result = result.withLane(lane, value)
        }
        return result
    }
    @JvmStatic @ExplodeLoop
    fun remLong(left: LongVector, right: LongVector, unsigned: Boolean): LongVector {
        var result = left
        for (lane in 0 until left.length()) {
            val a = left.lane(lane)
            val b = right.lane(lane)
            val value = if (unsigned) java.lang.Long.remainderUnsigned(a, b) else a % b
            result = result.withLane(lane, value)
        }
        return result
    }
}
