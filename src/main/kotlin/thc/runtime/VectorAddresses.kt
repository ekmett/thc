// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.frame.VirtualFrame
import jdk.incubator.vector.ByteVector
import jdk.incubator.vector.ShortVector
import jdk.incubator.vector.LongVector
import jdk.incubator.vector.VectorOperators
import java.nio.ByteOrder

/** Local Addr# vector reads keep State erased and return the exact public carrier. */
internal class VectorAddressExpression(private val operation: VectorMemoryOp,
    @field:Children private var arguments: Array<Expr>) : Expr() {
    private val width = 16 / operation.vectorProof.vector!!.lanes
    private val stride = if (operation.scalarOffset) width else 16
    init { representation = if (operation.isWrite) CoreVectorMemory.stateProof else operation.vectorProof }
    override fun execute(frame: VirtualFrame): Any {
        val address = arguments[0].executeRequiredAddress(frame)
        val index = arguments[1].executeRequiredLong(frame)
        if (operation.isWrite) {
            when {
                width == 1 -> {
                    val vector = CoreVectors.requireByte(arguments[2].execute(frame), ByteVector.SPECIES_128)
                    ManagedByteArray.requireState(arguments[3].execute(frame))
                    address.writeVectorBytes(index, stride, vector)
                }
                width == 2 -> {
                    val vector = CoreVectors.requireShort(arguments[2].execute(frame), ShortVector.SPECIES_128)
                    ManagedByteArray.requireState(arguments[3].execute(frame))
                    address.writeVectorBytes(index, stride, (if (ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN)
                        vector else vector.lanewise(VectorOperators.REVERSE_BYTES)).reinterpretAsBytes())
                }
                width == 8 -> {
                    val vector = CoreVectors.requireLong(arguments[2].execute(frame), LongVector.SPECIES_128)
                    ManagedByteArray.requireState(arguments[3].execute(frame))
                    address.writeVectorBytes(index, stride, (if (ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN)
                        vector else vector.lanewise(VectorOperators.REVERSE_BYTES)).reinterpretAsBytes())
                }
                else -> fault("Unsupported Addr# vector lane width")
            }
            return Unit
        }
        if (operation.isRead) ManagedByteArray.requireState(arguments[2].execute(frame))
        val bytes = address.readVectorBytes(index, stride)
        if (width == 1) return bytes
        if (width == 2) {
            val vector = bytes.reinterpretAsShorts()
            return if (ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN) vector
                else vector.lanewise(VectorOperators.REVERSE_BYTES)
        }
        if (width == 8) {
            val vector = bytes.reinterpretAsLongs()
            return if (ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN) vector
                else vector.lanewise(VectorOperators.REVERSE_BYTES)
        }
        fault("Unsupported Addr# vector lane width")
    }
}
