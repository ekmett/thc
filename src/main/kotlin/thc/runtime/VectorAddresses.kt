// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.frame.VirtualFrame
import jdk.incubator.vector.ByteVector
import jdk.incubator.vector.ShortVector
import jdk.incubator.vector.IntVector
import jdk.incubator.vector.LongVector
import jdk.incubator.vector.FloatVector
import jdk.incubator.vector.DoubleVector
import jdk.incubator.vector.VectorOperators
import jdk.incubator.vector.VectorShape
import java.nio.ByteOrder

/** Local Addr# vector reads keep State erased and return the exact public carrier. */
internal class VectorAddressExpression(private val operation: VectorMemoryOp,
    @field:Children private var arguments: Array<Expr>) : Expr() {
    private val vectorBytes = operation.vectorBytes
    private val width = vectorBytes / operation.vectorProof.vector!!.lanes
    private val stride = if (operation.scalarOffset) width else vectorBytes
    private val shape = VectorShape.forBitSize(vectorBytes * 8)
    init { representation = if (operation.isWrite) CoreVectorMemory.stateProof else operation.vectorProof }
    override fun execute(frame: VirtualFrame): Any {
        val address = arguments[0].executeRequiredAddress(frame)
        val index = arguments[1].executeRequiredLong(frame)
        if (operation.isWrite) {
            val bytes: ByteVector = when (operation.family) {
                VectorMemoryFamily.INT8, VectorMemoryFamily.WORD8 -> {
                    val vector = CoreVectors.requireByte(arguments[2].execute(frame), ByteVector.SPECIES_128.withShape(shape))
                    vector
                }
                VectorMemoryFamily.INT16, VectorMemoryFamily.WORD16 -> {
                    val vector = CoreVectors.requireShort(arguments[2].execute(frame), ShortVector.SPECIES_128.withShape(shape))
                    (if (ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN) vector
                        else vector.lanewise(VectorOperators.REVERSE_BYTES)).reinterpretAsBytes()
                }
                VectorMemoryFamily.INT32, VectorMemoryFamily.WORD32 -> {
                    val vector = CoreVectors.requireInt(arguments[2].execute(frame), IntVector.SPECIES_128.withShape(shape))
                    (if (ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN) vector
                        else vector.lanewise(VectorOperators.REVERSE_BYTES)).reinterpretAsBytes()
                }
                VectorMemoryFamily.INT64, VectorMemoryFamily.WORD64 -> {
                    val vector = CoreVectors.requireLong(arguments[2].execute(frame), LongVector.SPECIES_128.withShape(shape))
                    (if (ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN) vector
                        else vector.lanewise(VectorOperators.REVERSE_BYTES)).reinterpretAsBytes()
                }
                VectorMemoryFamily.FLOAT32 -> {
                    val vector = CoreVectors.requireFloat(arguments[2].execute(frame), FloatVector.SPECIES_128.withShape(shape))
                    val bits = vector.reinterpretAsInts()
                    (if (ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN) bits
                        else bits.lanewise(VectorOperators.REVERSE_BYTES)).reinterpretAsBytes()
                }
                VectorMemoryFamily.DOUBLE64 -> {
                    val vector = CoreVectors.requireDouble(arguments[2].execute(frame), DoubleVector.SPECIES_128.withShape(shape))
                    val bits = vector.reinterpretAsLongs()
                    (if (ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN) bits
                        else bits.lanewise(VectorOperators.REVERSE_BYTES)).reinterpretAsBytes()
                }
            }
            ManagedByteArray.requireState(arguments[3].execute(frame))
            address.writeVectorBytes(index, stride, bytes, vectorBytes)
            return Unit
        }
        if (operation.isRead) ManagedByteArray.requireState(arguments[2].execute(frame))
        val bytes = address.readVectorBytes(index, stride, vectorBytes)
        // Return each public carrier directly: Kotlin's inferred common vector
        // superclass is package-private in the JDK module.
        when (operation.family) {
            VectorMemoryFamily.INT8, VectorMemoryFamily.WORD8 -> return bytes
            VectorMemoryFamily.INT16, VectorMemoryFamily.WORD16 -> {
                val vector = bytes.reinterpretAsShorts()
                return (if (ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN) vector
                    else vector.lanewise(VectorOperators.REVERSE_BYTES))
            }
            VectorMemoryFamily.INT32, VectorMemoryFamily.WORD32 -> {
                val vector = bytes.reinterpretAsInts()
                return (if (ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN) vector
                    else vector.lanewise(VectorOperators.REVERSE_BYTES))
            }
            VectorMemoryFamily.INT64, VectorMemoryFamily.WORD64 -> {
                val vector = bytes.reinterpretAsLongs()
                return (if (ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN) vector
                    else vector.lanewise(VectorOperators.REVERSE_BYTES))
            }
            VectorMemoryFamily.FLOAT32 -> {
                val vector = bytes.reinterpretAsInts()
                return (if (ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN) vector
                    else vector.lanewise(VectorOperators.REVERSE_BYTES)).reinterpretAsFloats()
            }
            VectorMemoryFamily.DOUBLE64 -> {
                val vector = bytes.reinterpretAsLongs()
                return (if (ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN) vector
                    else vector.lanewise(VectorOperators.REVERSE_BYTES)).reinterpretAsDoubles()
            }
        }
    }
}
