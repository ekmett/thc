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
import jdk.incubator.vector.ByteVector
import jdk.incubator.vector.ShortVector
import jdk.incubator.vector.IntVector
import jdk.incubator.vector.LongVector
import jdk.incubator.vector.FloatVector
import jdk.incubator.vector.DoubleVector
import jdk.incubator.vector.VectorSpecies

/** A logical vector owns final primitive properties inside its enclosing heap object.
 * Slot arrays are immutable lowering metadata, never payload storage or retained frames. */
internal class OwnedVectorFields(val proof: CoreRepresentation, name: String) {
    init { VectorLayout.validate(proof) }
    private val vector = proof.vector!!
    private val transport = VectorLayout(proof)
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
        require(offset >= 0 && offset < size) { "Invalid owned vector transport slot" }
    }
    @ExplodeLoop private fun initializeRaw(owner: Any, raw: Any?) {
        val value = transport.require(raw)
        for (index in 0 until lanes) {
            when (value) {
                is ByteVector -> putLong(owner, index, value.lane(index).toLong())
                is ShortVector -> putLong(owner, index, value.lane(index).toLong())
                is IntVector -> putLong(owner, index, value.lane(index).toLong())
                is LongVector -> putLong(owner, index, value.lane(index))
                is FloatVector -> properties[index].setFloat(owner, value.lane(index))
                is DoubleVector -> properties[index].setDouble(owner, value.lane(index))
                else -> fault("Unsupported raw vector carrier")
            }
        }
    }
    @Suppress("UNCHECKED_CAST")
    @ExplodeLoop private fun restoreRaw(owner: Any): Any {
        // Return each public carrier directly; a value-producing when joins at the inaccessible AbstractVector.
        when (lane) {
            "Int8Rep", "Word8Rep" -> {
                var value = ByteVector.broadcast(transport.species as VectorSpecies<Byte>, getLong(owner, 0).toByte())
                for (index in 1 until lanes) value = value.withLane(index, getLong(owner, index).toByte())
                return value
            }
            "Int16Rep", "Word16Rep" -> {
                var value = ShortVector.broadcast(transport.species as VectorSpecies<Short>, getLong(owner, 0).toShort())
                for (index in 1 until lanes) value = value.withLane(index, getLong(owner, index).toShort())
                return value
            }
            "Int32Rep", "Word32Rep" -> {
                var value = IntVector.broadcast(transport.species as VectorSpecies<Int>, getLong(owner, 0).toInt())
                for (index in 1 until lanes) value = value.withLane(index, getLong(owner, index).toInt())
                return value
            }
            "Int64Rep", "Word64Rep" -> {
                var value = LongVector.broadcast(transport.species as VectorSpecies<Long>, getLong(owner, 0))
                for (index in 1 until lanes) value = value.withLane(index, getLong(owner, index))
                return value
            }
            "FloatRep" -> {
                var value = FloatVector.broadcast(transport.species as VectorSpecies<Float>, properties[0].getFloat(owner))
                for (index in 1 until lanes) value = value.withLane(index, properties[index].getFloat(owner))
                return value
            }
            "DoubleRep" -> {
                var value = DoubleVector.broadcast(transport.species as VectorSpecies<Double>, properties[0].getDouble(owner))
                for (index in 1 until lanes) value = value.withLane(index, properties[index].getDouble(owner))
                return value
            }
            else -> fault("Unsupported owned vector lane")
        }
    }
    fun initialize(owner: Any, frame: Frame, slots: IntArray, offset: Int) {
        checkSlots(slots.size, offset)
        initializeRaw(owner, frame.getObject(slots[offset]))
    }
    fun restore(owner: Any, frame: Frame, slots: IntArray, offset: Int) {
        checkSlots(slots.size, offset)
        FrameAccess.writeObject(frame, slots[offset], restoreRaw(owner))
    }
    fun initialize(owner: Any, bytecode: BytecodeNode, frame: VirtualFrame,
        slots: Array<LocalAccessor>, offset: Int) {
        checkSlots(slots.size, offset)
        initializeRaw(owner, slots[offset].getObject(bytecode, frame))
    }
    fun restore(owner: Any, bytecode: BytecodeNode, frame: VirtualFrame,
        slots: Array<LocalAccessor>, offset: Int) {
        checkSlots(slots.size, offset)
        slots[offset].setObject(bytecode, frame, restoreRaw(owner))
    }
}
