// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal
import com.oracle.truffle.api.bytecode.BytecodeNode
import com.oracle.truffle.api.bytecode.LocalAccessor
import com.oracle.truffle.api.frame.Frame
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.ExplodeLoop
import com.oracle.truffle.api.staticobject.DefaultStaticProperty
import com.oracle.truffle.api.staticobject.StaticShape

/** A logical vector owns final primitive properties inside its enclosing heap object.
 * Slot arrays are immutable lowering metadata, never payload storage or retained frames. */
internal class OwnedVectorFields(val proof: CoreRepresentation, name: String) {
    init { VectorLayout.validate(proof) }
    private val vector = proof.vector!!
    val lanes = vector.lanes
    private val lane = VectorLayout.laneProof(vector).primReps!!.single()
    @field:CompilationFinal(dimensions = 1)
    private val properties = Array(lanes) { DefaultStaticProperty("${name}_lane_$it") }

    fun register(builder: StaticShape.Builder) {
        val type = when (lane) {
            "Int8Rep", "Word8Rep" -> Byte::class.javaPrimitiveType
            "Int16Rep", "Word16Rep" -> Short::class.javaPrimitiveType
            "Int32Rep", "Word32Rep" -> Int::class.javaPrimitiveType
            "Int64Rep", "Word64Rep" -> Long::class.javaPrimitiveType
            "FloatRep" -> Float::class.javaPrimitiveType
            "DoubleRep" -> Double::class.javaPrimitiveType
            else -> fault("Invalid owned vector lane")
        }
        properties.forEach { builder.property(it, type, true) }
    }

    private fun putLong(owner: Any, index: Int, value: Long) {
        val field = properties[index]
        when (lane) {
            "Int8Rep", "Word8Rep" -> field.setByte(owner, value.toByte())
            "Int16Rep", "Word16Rep" -> field.setShort(owner, value.toShort())
            "Int32Rep", "Word32Rep" -> field.setInt(owner, value.toInt())
            "Int64Rep", "Word64Rep" -> field.setLong(owner, value)
            else -> fault("Expected integral owned vector lane")
        }
    }
    private fun getLong(owner: Any, index: Int): Long = when (lane) {
        "Int8Rep" -> properties[index].getByte(owner).toLong()
        "Word8Rep" -> properties[index].getByte(owner).toLong() and 255L
        "Int16Rep" -> properties[index].getShort(owner).toLong()
        "Word16Rep" -> properties[index].getShort(owner).toLong() and 65535L
        "Int32Rep" -> properties[index].getInt(owner).toLong()
        "Word32Rep" -> properties[index].getInt(owner).toLong() and 4294967295L
        "Int64Rep", "Word64Rep" -> properties[index].getLong(owner)
        else -> fault("Expected integral owned vector lane")
    }
    private fun checkSlots(size: Int, offset: Int) {
        require(offset >= 0 && offset <= size - lanes) { "Invalid owned vector lane slots" }
    }
    @ExplodeLoop fun initialize(owner: Any, frame: Frame, slots: IntArray, offset: Int) {
        checkSlots(slots.size, offset)
        for (index in 0 until lanes) {
            val slot = slots[offset + index]
            when (lane) {
                "FloatRep" -> properties[index].setFloat(owner, if (frame.isFloat(slot)) frame.getFloat(slot)
                    else frame.getObject(slot) as? Float ?: fault("Expected Float vector lane"))
                "DoubleRep" -> properties[index].setDouble(owner, if (frame.isDouble(slot)) frame.getDouble(slot)
                    else frame.getObject(slot) as? Double ?: fault("Expected Double vector lane"))
                else -> putLong(owner, index, if (frame.isLong(slot)) frame.getLong(slot)
                    else frame.getObject(slot) as? Long ?: fault("Expected Long vector lane"))
            }
        }
    }
    @ExplodeLoop fun restore(owner: Any, frame: Frame, slots: IntArray, offset: Int) {
        checkSlots(slots.size, offset)
        for (index in 0 until lanes) when (lane) {
            "FloatRep" -> FrameAccess.writeFloat(frame, slots[offset + index], properties[index].getFloat(owner))
            "DoubleRep" -> FrameAccess.writeDouble(frame, slots[offset + index], properties[index].getDouble(owner))
            else -> FrameAccess.writeLong(frame, slots[offset + index], getLong(owner, index))
        }
    }
    @ExplodeLoop fun initialize(owner: Any, bytecode: BytecodeNode, frame: VirtualFrame,
        slots: Array<LocalAccessor>, offset: Int) {
        checkSlots(slots.size, offset)
        for (index in 0 until lanes) when (lane) {
            "FloatRep" -> properties[index].setFloat(owner, slots[offset + index].getFloat(bytecode, frame))
            "DoubleRep" -> properties[index].setDouble(owner, slots[offset + index].getDouble(bytecode, frame))
            else -> putLong(owner, index, slots[offset + index].getLong(bytecode, frame))
        }
    }
    @ExplodeLoop fun restore(owner: Any, bytecode: BytecodeNode, frame: VirtualFrame,
        slots: Array<LocalAccessor>, offset: Int) {
        checkSlots(slots.size, offset)
        for (index in 0 until lanes) when (lane) {
            "FloatRep" -> slots[offset + index].setFloat(bytecode, frame, properties[index].getFloat(owner))
            "DoubleRep" -> slots[offset + index].setDouble(bytecode, frame, properties[index].getDouble(owner))
            else -> slots[offset + index].setLong(bytecode, frame, getLong(owner, index))
        }
    }
}
