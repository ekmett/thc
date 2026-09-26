// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import jdk.incubator.vector.FloatVector
import jdk.incubator.vector.DoubleVector

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal
import com.oracle.truffle.api.frame.VirtualFrame

internal class VectorFloatPack(@field:Child private var argument: Expr,
    @field:CompilationFinal(dimensions = 1) private val slots: IntArray) : Expr() {
    init { representation = CoreVectors.proofFloat }
    override fun execute(frame: VirtualFrame): FloatVector {
        argument.executeTuple(frame, slots)
        return FloatVector.broadcast(FloatVector.SPECIES_128, frame.getFloat(slots[0])).withLane(1, frame.getFloat(slots[1])).withLane(2, frame.getFloat(slots[2])).withLane(3, frame.getFloat(slots[3]))
    }
}
internal class VectorFloatUnpack(@field:Child private var argument: Expr) : Expr() {
    init { representation = CoreVectors.unpackedFloat }
    override fun execute(frame: VirtualFrame): Any = fault("Vector unpack requires a tuple destination")
    override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Any? {
        val value = CoreVectors.requireFloat(argument.execute(frame), FloatVector.SPECIES_128)
        FrameAccess.writeFloat(frame, slots[offset], value.lane(0))
        FrameAccess.writeFloat(frame, slots[offset + 1], value.lane(1))
        FrameAccess.writeFloat(frame, slots[offset + 2], value.lane(2))
        FrameAccess.writeFloat(frame, slots[offset + 3], value.lane(3))
        return null
    }
}
internal class VectorFloatOperation(name: String, @field:Children private var arguments: Array<Expr>) : Expr() {
    private val operation = when (name) {
        "broadcastFloatX4#" -> 0; "plusFloatX4#" -> 1; "minusFloatX4#" -> 2; "timesFloatX4#" -> 3
        in CoreVectors.fusedFloat -> 4 + CoreVectors.fusedFloat.indexOf(name)
        else -> throw RuntimeFault("Invalid FloatX4 operation")
    }
    init { representation = CoreVectors.proofFloat }
    override fun execute(frame: VirtualFrame): FloatVector = when (operation) {
        0 -> FloatVector.broadcast(FloatVector.SPECIES_128, arguments[0].executeRequiredFloat(frame))
        1 -> (vector(frame, 0)).add(vector(frame, 1))
        2 -> (vector(frame, 0)).sub(vector(frame, 1))
        3 -> (vector(frame, 0)).mul(vector(frame, 1))
        in 4..7 -> fused(operation - 4, vector(frame, 0), vector(frame, 1), vector(frame, 2))
        else -> fault("Invalid FloatX4 operation")
    }
    private fun fused(operation: Int, a: FloatVector, b: FloatVector, c: FloatVector): FloatVector {
        val left = if (operation >= 2) a.neg() else a
        val addend = if (operation and 1 != 0) c.neg() else c
        return left.fma(b, addend)
    }
    private fun vector(frame: VirtualFrame, index: Int): FloatVector = CoreVectors.requireFloat(arguments[index].execute(frame), FloatVector.SPECIES_128)
}

internal class VectorDoublePack(@field:Child private var argument: Expr,
    @field:CompilationFinal(dimensions = 1) private val slots: IntArray) : Expr() {
    init { representation = CoreVectors.proofDouble }
    override fun execute(frame: VirtualFrame): DoubleVector {
        argument.executeTuple(frame, slots)
        return DoubleVector.broadcast(DoubleVector.SPECIES_128, frame.getDouble(slots[0])).withLane(1, frame.getDouble(slots[1]))
    }
}
internal class VectorDoubleUnpack(@field:Child private var argument: Expr) : Expr() {
    init { representation = CoreVectors.unpackedDouble }
    override fun execute(frame: VirtualFrame): Any = fault("Vector unpack requires a tuple destination")
    override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Any? {
        val value = CoreVectors.requireDouble(argument.execute(frame), DoubleVector.SPECIES_128)
        FrameAccess.writeDouble(frame, slots[offset], value.lane(0))
        FrameAccess.writeDouble(frame, slots[offset + 1], value.lane(1))
        return null
    }
}
internal class VectorDoubleOperation(name: String, @field:Children private var arguments: Array<Expr>) : Expr() {
    private val operation = when (name) {
        "broadcastDoubleX2#" -> 0; "plusDoubleX2#" -> 1; "minusDoubleX2#" -> 2; "timesDoubleX2#" -> 3
        in CoreVectors.fusedDouble -> 4 + CoreVectors.fusedDouble.indexOf(name)
        else -> throw RuntimeFault("Invalid DoubleX2 operation")
    }
    init { representation = CoreVectors.proofDouble }
    override fun execute(frame: VirtualFrame): DoubleVector = when (operation) {
        0 -> DoubleVector.broadcast(DoubleVector.SPECIES_128, arguments[0].executeRequiredDouble(frame))
        1 -> (vector(frame, 0)).add(vector(frame, 1))
        2 -> (vector(frame, 0)).sub(vector(frame, 1))
        3 -> (vector(frame, 0)).mul(vector(frame, 1))
        in 4..7 -> fused(operation - 4, vector(frame, 0), vector(frame, 1), vector(frame, 2))
        else -> fault("Invalid DoubleX2 operation")
    }
    private fun fused(operation: Int, a: DoubleVector, b: DoubleVector, c: DoubleVector): DoubleVector {
        val left = if (operation >= 2) a.neg() else a
        val addend = if (operation and 1 != 0) c.neg() else c
        return left.fma(b, addend)
    }
    private fun vector(frame: VirtualFrame, index: Int): DoubleVector = CoreVectors.requireDouble(arguments[index].execute(frame), DoubleVector.SPECIES_128)
}

internal class VectorFloat8Fused(name: String, @field:Children private var arguments: Array<Expr>) : Expr() {
    private val operation = CoreVectors.fusedFloat8.indexOf(name)
    init { representation = GeneratedVectors.proofFloatX8 }
    override fun execute(frame: VirtualFrame): FloatVector {
        if (operation !in 0..3) fault("Invalid FloatX8 fused operation")
        val a = vector(frame, 0); val b = vector(frame, 1); val c = vector(frame, 2)
        val left = if (operation >= 2) a.neg() else a
        val addend = if (operation and 1 != 0) c.neg() else c
        return left.fma(b, addend)
    }
    private fun vector(frame: VirtualFrame, index: Int): FloatVector =
        CoreVectors.requireFloat(arguments[index].execute(frame), FloatVector.SPECIES_256)
}

internal class VectorDouble4Fused(name: String, @field:Children private var arguments: Array<Expr>) : Expr() {
    private val operation = CoreVectors.fusedDouble4.indexOf(name)
    init { representation = GeneratedVectors.proofDoubleX4 }
    override fun execute(frame: VirtualFrame): DoubleVector {
        if (operation !in 0..3) fault("Invalid DoubleX4 fused operation")
        val a = vector(frame, 0); val b = vector(frame, 1); val c = vector(frame, 2)
        val left = if (operation >= 2) a.neg() else a
        val addend = if (operation and 1 != 0) c.neg() else c
        return left.fma(b, addend)
    }
    private fun vector(frame: VirtualFrame, index: Int): DoubleVector =
        CoreVectors.requireDouble(arguments[index].execute(frame), DoubleVector.SPECIES_256)
}

internal class VectorFloat16Fused(name: String, @field:Children private var arguments: Array<Expr>) : Expr() {
    private val operation = CoreVectors.fusedFloat16.indexOf(name)
    init { representation = GeneratedVectors.proofFloatX16 }
    override fun execute(frame: VirtualFrame): FloatVector {
        if (operation !in 0..3) fault("Invalid FloatX16 fused operation")
        val a = vector(frame, 0); val b = vector(frame, 1); val c = vector(frame, 2)
        val left = if (operation >= 2) a.neg() else a
        val addend = if (operation and 1 != 0) c.neg() else c
        return left.fma(b, addend)
    }
    private fun vector(frame: VirtualFrame, index: Int): FloatVector =
        CoreVectors.requireFloat(arguments[index].execute(frame), FloatVector.SPECIES_512)
}

internal class VectorDouble8Fused(name: String, @field:Children private var arguments: Array<Expr>) : Expr() {
    private val operation = CoreVectors.fusedDouble8.indexOf(name)
    init { representation = GeneratedVectors.proofDoubleX8 }
    override fun execute(frame: VirtualFrame): DoubleVector {
        if (operation !in 0..3) fault("Invalid DoubleX8 fused operation")
        val a = vector(frame, 0); val b = vector(frame, 1); val c = vector(frame, 2)
        val left = if (operation >= 2) a.neg() else a
        val addend = if (operation and 1 != 0) c.neg() else c
        return left.fma(b, addend)
    }
    private fun vector(frame: VirtualFrame, index: Int): DoubleVector =
        CoreVectors.requireDouble(arguments[index].execute(frame), DoubleVector.SPECIES_512)
}
