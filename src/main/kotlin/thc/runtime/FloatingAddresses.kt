// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.frame.VirtualFrame

/** IEEE values in managed, native-endian Addr# storage. Element offsets have their GHC widths. */
internal object FloatingAddresses {
    @JvmStatic @JvmOverloads fun readFloat(address: ManagedAddress, index: Long, byteOffset: Boolean = false): Float =
        java.lang.Float.intBitsToFloat((ManagedAddressRead.WORD32.read(address, index, byteOffset)).toInt())
    @JvmStatic @JvmOverloads fun readDouble(address: ManagedAddress, index: Long, byteOffset: Boolean = false): Double =
        java.lang.Double.longBitsToDouble(ManagedAddressRead.WORD64.read(address, index, byteOffset))
    @JvmStatic @JvmOverloads fun writeFloat(address: ManagedAddress, index: Long, value: Float, byteOffset: Boolean = false) =
        address.writeNativeScalar(index, 4, (java.lang.Float.floatToRawIntBits(value).toLong() and 0xffffffffL), byteOffset)
    @JvmStatic @JvmOverloads fun writeDouble(address: ManagedAddress, index: Long, value: Double, byteOffset: Boolean = false) =
        address.writeNativeScalar(index, 8, java.lang.Double.doubleToRawLongBits(value), byteOffset)
}

internal enum class FloatingAddressOp(val primitive: String, val floating: Boolean, val tuple: Boolean, val write: Boolean) {
    INDEX_FLOAT("indexFloatOffAddr#", true, false, false),
    INDEX_DOUBLE("indexDoubleOffAddr#", false, false, false),
    READ_FLOAT("readFloatOffAddr#", true, true, false),
    READ_DOUBLE("readDoubleOffAddr#", false, true, false),
    WRITE_FLOAT("writeFloatOffAddr#", true, false, true),
    WRITE_DOUBLE("writeDoubleOffAddr#", false, false, true);

    fun validate(actual: List<CoreRepresentation>, flags: List<*>, result: CoreRepresentation) {
        val payload = if (floating) "FloatRep" else "DoubleRep"
        fun exact(proof: CoreRepresentation, reps: List<String>, kind: CoreKind): Boolean =
            !proof.isAggregate && !proof.isVector && (kind == CoreKind.LONG || proof.primReps == reps) && proof.kind == kind
        val arguments = 2 + (if (write) 2 else if (tuple) 1 else 0)
        if (actual.size != arguments || flags != List(arguments) { false } ||
            !exact(actual[0], listOf("AddrRep"), CoreKind.ADDRESS) ||
            !exact(actual[1], listOf("IntRep"), CoreKind.LONG) ||
            (write && !exact(actual[2], listOf(payload), if (floating) CoreKind.FLOAT else CoreKind.DOUBLE)) ||
            ((write || tuple) && !exact(actual.last(), emptyList(), CoreKind.VOID)))
            fault("Floating Addr# argument representation mismatch: $primitive")
        val valueKind = if (floating) CoreKind.FLOAT else CoreKind.DOUBLE
        val valid = if (tuple) result.isTuple && result.kind == CoreKind.UNKNOWN &&
            result.components?.size == 2 &&
            exact(result.components[0], emptyList(), CoreKind.VOID) &&
            exact(result.components[1], listOf(payload), valueKind) && result.primReps == listOf(payload)
        else if (write) exact(result, emptyList(), CoreKind.VOID)
        else exact(result, listOf(payload), valueKind)
        if (!valid) fault("Floating Addr# result representation mismatch: $primitive")
    }

    companion object {
        fun named(name: String): FloatingAddressOp? = when (name) {
            "indexWord8OffAddrAsFloat#" -> INDEX_FLOAT
            "indexWord8OffAddrAsDouble#" -> INDEX_DOUBLE
            "readWord8OffAddrAsFloat#" -> READ_FLOAT
            "readWord8OffAddrAsDouble#" -> READ_DOUBLE
            "writeWord8OffAddrAsFloat#" -> WRITE_FLOAT
            "writeWord8OffAddrAsDouble#" -> WRITE_DOUBLE
            else -> entries.firstOrNull { it.primitive == name }
        }
    }
}

internal class FloatingAddressExpression(private val operation: FloatingAddressOp, proof: CoreRepresentation,
    @field:Children private var operands: Array<Expr>, private val byteOffset: Boolean = false) : Expr() {
    init { representation = proof.copy(evaluated = true) }
    override fun execute(frame: VirtualFrame): Any {
        if (operation.tuple) fault("Floating Addr# tuple read requires a destination")
        if (operation.write) {
            val address = operands[0].executeRequiredAddress(frame)
            val index = operands[1].executeRequiredLong(frame)
            if (operation.floating) {
                val value = operands[2].executeRequiredFloat(frame)
                ManagedByteArray.requireState(operands[3].execute(frame))
                FloatingAddresses.writeFloat(address, index, value, byteOffset)
            } else {
                val value = operands[2].executeRequiredDouble(frame)
                ManagedByteArray.requireState(operands[3].execute(frame))
                FloatingAddresses.writeDouble(address, index, value, byteOffset)
            }
            return Unit
        }
        return if (operation == FloatingAddressOp.INDEX_FLOAT) executeFloat(frame) else executeDouble(frame)
    }
    override fun executeFloat(frame: VirtualFrame): Float {
        if (operation == FloatingAddressOp.INDEX_FLOAT)
            return FloatingAddresses.readFloat(operands[0].executeRequiredAddress(frame),
                operands[1].executeRequiredLong(frame), byteOffset)
        if (operation == FloatingAddressOp.INDEX_DOUBLE) return super.executeFloat(frame)
        fault("Floating Addr# operation does not produce a scalar Float")
    }
    override fun executeDouble(frame: VirtualFrame): Double {
        if (operation == FloatingAddressOp.INDEX_DOUBLE)
            return FloatingAddresses.readDouble(operands[0].executeRequiredAddress(frame),
                operands[1].executeRequiredLong(frame), byteOffset)
        if (operation == FloatingAddressOp.INDEX_FLOAT) return super.executeDouble(frame)
        fault("Floating Addr# operation does not produce a scalar Double")
    }
    override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Any? {
        if (!operation.tuple) fault("Floating Addr# operation does not produce a tuple")
        val address = operands[0].executeRequiredAddress(frame)
        val index = operands[1].executeRequiredLong(frame)
        ManagedByteArray.requireState(operands[2].execute(frame))
        if (operation.floating) FrameAccess.writeFloat(frame, slots[offset], FloatingAddresses.readFloat(address, index, byteOffset))
        else FrameAccess.writeDouble(frame, slots[offset], FloatingAddresses.readDouble(address, index, byteOffset))
        return null
    }
}
