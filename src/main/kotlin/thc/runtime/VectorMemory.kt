// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

@file:JvmName("VectorMemory")

package thc.runtime

import java.nio.ByteOrder
import jdk.incubator.vector.ByteVector
import jdk.incubator.vector.IntVector
import jdk.incubator.vector.FloatVector
import jdk.incubator.vector.DoubleVector
import jdk.incubator.vector.VectorOperators

/** Native-order byte movement at the explicit ByteArray# boundary. */
private fun byteOffset(bytes: ByteArray, index: Long, scalarOffset: Boolean, laneBytes: Int, name: String): Int {
    val stride = if (scalarOffset) laneBytes else 16
    if (bytes.size < 16 || index < 0 || index > (bytes.size - 16).toLong() / stride)
        fault("$name ByteArray# range outside its backing storage")
    return (index * stride).toInt()
}

internal fun readIntVectorArray(bytes: ByteArray, index: Long, scalarOffset: Boolean, name: String): IntVector {
    val offset = byteOffset(bytes, index, scalarOffset, 4, name)
    val bits = ByteVector.fromArray(ByteVector.SPECIES_128, bytes, offset).reinterpretAsInts()
    return if (ByteOrder.nativeOrder() == ByteOrder.BIG_ENDIAN) bits.lanewise(VectorOperators.REVERSE_BYTES) else bits
}
internal fun writeIntVectorArray(bytes: ByteArray, index: Long, value: IntVector, scalarOffset: Boolean, name: String) {
    val offset = byteOffset(bytes, index, scalarOffset, 4, name)
    val vector = CoreVectors.requireInt(value, IntVector.SPECIES_128)
    val bits = if (ByteOrder.nativeOrder() == ByteOrder.BIG_ENDIAN) vector.lanewise(VectorOperators.REVERSE_BYTES) else vector
    bits.reinterpretAsBytes().intoArray(bytes, offset)
}
internal fun readFloatVectorArray(bytes: ByteArray, index: Long, scalarOffset: Boolean): FloatVector =
    readIntVectorArray(bytes, index, scalarOffset, "FloatX4").reinterpretAsFloats()
internal fun writeFloatVectorArray(bytes: ByteArray, index: Long, value: FloatVector, scalarOffset: Boolean) =
    writeIntVectorArray(bytes, index, CoreVectors.requireFloat(value, FloatVector.SPECIES_128).reinterpretAsInts(), scalarOffset, "FloatX4")

internal fun readDoubleVectorArray(bytes: ByteArray, index: Long, scalarOffset: Boolean): DoubleVector {
    val offset = byteOffset(bytes, index, scalarOffset, 8, "DoubleX2")
    val bits = ByteVector.fromArray(ByteVector.SPECIES_128, bytes, offset).reinterpretAsLongs()
    return (if (ByteOrder.nativeOrder() == ByteOrder.BIG_ENDIAN) bits.lanewise(VectorOperators.REVERSE_BYTES) else bits).reinterpretAsDoubles()
}
internal fun writeDoubleVectorArray(bytes: ByteArray, index: Long, value: DoubleVector, scalarOffset: Boolean) {
    val offset = byteOffset(bytes, index, scalarOffset, 8, "DoubleX2")
    val vector = CoreVectors.requireDouble(value, DoubleVector.SPECIES_128).reinterpretAsLongs()
    val bits = if (ByteOrder.nativeOrder() == ByteOrder.BIG_ENDIAN) vector.lanewise(VectorOperators.REVERSE_BYTES) else vector
    bits.reinterpretAsBytes().intoArray(bytes, offset)
}
