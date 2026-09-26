// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

@file:JvmName("VectorMemory")

package thc.runtime

import java.nio.ByteOrder
import jdk.incubator.vector.VectorShape
import jdk.incubator.vector.ByteVector
import jdk.incubator.vector.ShortVector
import jdk.incubator.vector.LongVector
import jdk.incubator.vector.IntVector
import jdk.incubator.vector.FloatVector
import jdk.incubator.vector.DoubleVector
import jdk.incubator.vector.VectorOperators

/** Native-order byte movement at the explicit ByteArray# boundary. */
private fun byteOffset(bytes: ByteArray, index: Long, scalarOffset: Boolean, laneBytes: Int, name: String, vectorBytes: Int): Int {
    val stride = if (scalarOffset) laneBytes else vectorBytes
    if (bytes.size < vectorBytes || index < 0 || index > (bytes.size - vectorBytes).toLong() / stride)
        fault("$name ByteArray# range outside its backing storage")
    return (index * stride).toInt()
}

internal fun readByteVectorArray(bytes: ByteArray, index: Long, scalarOffset: Boolean, vectorBytes: Int = 16): ByteVector {
    val offset = byteOffset(bytes, index, scalarOffset, 1, "Byte128", vectorBytes)
    val bits = ByteVector.fromArray(ByteVector.SPECIES_128.withShape(VectorShape.forBitSize(vectorBytes * 8)), bytes, offset)
    return bits
}
internal fun writeByteVectorArray(bytes: ByteArray, index: Long, value: ByteVector, scalarOffset: Boolean, vectorBytes: Int = 16) {
    val offset = byteOffset(bytes, index, scalarOffset, 1, "Byte128", vectorBytes)
    val vector = CoreVectors.requireByte(value, ByteVector.SPECIES_128.withShape(VectorShape.forBitSize(vectorBytes * 8)))
    val bits = vector
    bits.reinterpretAsBytes().intoArray(bytes, offset)
}
internal fun readShortVectorArray(bytes: ByteArray, index: Long, scalarOffset: Boolean, vectorBytes: Int = 16): ShortVector {
    val offset = byteOffset(bytes, index, scalarOffset, 2, "Short128", vectorBytes)
    val bits = ByteVector.fromArray(ByteVector.SPECIES_128.withShape(VectorShape.forBitSize(vectorBytes * 8)), bytes, offset).reinterpretAsShorts()
    return if (ByteOrder.nativeOrder() == ByteOrder.BIG_ENDIAN) bits.lanewise(VectorOperators.REVERSE_BYTES) else bits
}
internal fun writeShortVectorArray(bytes: ByteArray, index: Long, value: ShortVector, scalarOffset: Boolean, vectorBytes: Int = 16) {
    val offset = byteOffset(bytes, index, scalarOffset, 2, "Short128", vectorBytes)
    val vector = CoreVectors.requireShort(value, ShortVector.SPECIES_128.withShape(VectorShape.forBitSize(vectorBytes * 8)))
    val bits = if (ByteOrder.nativeOrder() == ByteOrder.BIG_ENDIAN) vector.lanewise(VectorOperators.REVERSE_BYTES) else vector
    bits.reinterpretAsBytes().intoArray(bytes, offset)
}
internal fun readLongVectorArray(bytes: ByteArray, index: Long, scalarOffset: Boolean, vectorBytes: Int = 16): LongVector {
    val offset = byteOffset(bytes, index, scalarOffset, 8, "Long128", vectorBytes)
    val bits = ByteVector.fromArray(ByteVector.SPECIES_128.withShape(VectorShape.forBitSize(vectorBytes * 8)), bytes, offset).reinterpretAsLongs()
    return if (ByteOrder.nativeOrder() == ByteOrder.BIG_ENDIAN) bits.lanewise(VectorOperators.REVERSE_BYTES) else bits
}
internal fun writeLongVectorArray(bytes: ByteArray, index: Long, value: LongVector, scalarOffset: Boolean, vectorBytes: Int = 16) {
    val offset = byteOffset(bytes, index, scalarOffset, 8, "Long128", vectorBytes)
    val vector = CoreVectors.requireLong(value, LongVector.SPECIES_128.withShape(VectorShape.forBitSize(vectorBytes * 8)))
    val bits = if (ByteOrder.nativeOrder() == ByteOrder.BIG_ENDIAN) vector.lanewise(VectorOperators.REVERSE_BYTES) else vector
    bits.reinterpretAsBytes().intoArray(bytes, offset)
}

internal fun readIntVectorArray(bytes: ByteArray, index: Long, scalarOffset: Boolean, name: String, vectorBytes: Int = 16): IntVector {
    val offset = byteOffset(bytes, index, scalarOffset, 4, name, vectorBytes)
    val bits = ByteVector.fromArray(ByteVector.SPECIES_128.withShape(VectorShape.forBitSize(vectorBytes * 8)), bytes, offset).reinterpretAsInts()
    return if (ByteOrder.nativeOrder() == ByteOrder.BIG_ENDIAN) bits.lanewise(VectorOperators.REVERSE_BYTES) else bits
}
internal fun writeIntVectorArray(bytes: ByteArray, index: Long, value: IntVector, scalarOffset: Boolean, name: String, vectorBytes: Int = 16) {
    val offset = byteOffset(bytes, index, scalarOffset, 4, name, vectorBytes)
    val vector = CoreVectors.requireInt(value, IntVector.SPECIES_128.withShape(VectorShape.forBitSize(vectorBytes * 8)))
    val bits = if (ByteOrder.nativeOrder() == ByteOrder.BIG_ENDIAN) vector.lanewise(VectorOperators.REVERSE_BYTES) else vector
    bits.reinterpretAsBytes().intoArray(bytes, offset)
}
internal fun readFloatVectorArray(bytes: ByteArray, index: Long, scalarOffset: Boolean, vectorBytes: Int = 16): FloatVector =
    readIntVectorArray(bytes, index, scalarOffset, "FloatX4", vectorBytes).reinterpretAsFloats()
internal fun writeFloatVectorArray(bytes: ByteArray, index: Long, value: FloatVector, scalarOffset: Boolean, vectorBytes: Int = 16) =
    writeIntVectorArray(bytes, index, CoreVectors.requireFloat(value, FloatVector.SPECIES_128.withShape(VectorShape.forBitSize(vectorBytes * 8))).reinterpretAsInts(), scalarOffset, "FloatX4", vectorBytes)

internal fun readDoubleVectorArray(bytes: ByteArray, index: Long, scalarOffset: Boolean, vectorBytes: Int = 16): DoubleVector {
    val offset = byteOffset(bytes, index, scalarOffset, 8, "DoubleX2", vectorBytes)
    val bits = ByteVector.fromArray(ByteVector.SPECIES_128.withShape(VectorShape.forBitSize(vectorBytes * 8)), bytes, offset).reinterpretAsLongs()
    return (if (ByteOrder.nativeOrder() == ByteOrder.BIG_ENDIAN) bits.lanewise(VectorOperators.REVERSE_BYTES) else bits).reinterpretAsDoubles()
}
internal fun writeDoubleVectorArray(bytes: ByteArray, index: Long, value: DoubleVector, scalarOffset: Boolean, vectorBytes: Int = 16) {
    val offset = byteOffset(bytes, index, scalarOffset, 8, "DoubleX2", vectorBytes)
    val vector = CoreVectors.requireDouble(value, DoubleVector.SPECIES_128.withShape(VectorShape.forBitSize(vectorBytes * 8))).reinterpretAsLongs()
    val bits = if (ByteOrder.nativeOrder() == ByteOrder.BIG_ENDIAN) vector.lanewise(VectorOperators.REVERSE_BYTES) else vector
    bits.reinterpretAsBytes().intoArray(bytes, offset)
}
