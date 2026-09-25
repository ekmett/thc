// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal
import com.oracle.truffle.api.frame.VirtualFrame
import jdk.incubator.vector.FloatVector

internal class VectorFloatPack(@field:Child private var argument: Expr,
    @field:CompilationFinal(dimensions = 1) private val slots: IntArray) : Expr() {
    init { representation = CoreVectors.proofFloat }
    override fun execute(frame: VirtualFrame): FloatX4 {
        argument.executeTuple(frame, slots)
        return FloatX4.pack(frame.getFloat(slots[0]), frame.getFloat(slots[1]),
            frame.getFloat(slots[2]), frame.getFloat(slots[3]))
    }
}
internal class VectorFloatUnpack(@field:Child private var argument: Expr) : Expr() {
    init { representation = CoreVectors.unpackedFloat }
    override fun execute(frame: VirtualFrame): Any = fault("Vector unpack requires a tuple destination")
    override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Any? {
        val value = argument.execute(frame) as? FloatX4 ?: fault("Expected FloatX4#")
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
    override fun execute(frame: VirtualFrame): FloatX4 = when (operation) {
        0 -> FloatX4.broadcast(arguments[0].executeRequiredFloat(frame))
        1 -> FloatX4.add(vector(frame, 0), vector(frame, 1))
        2 -> FloatX4.subtract(vector(frame, 0), vector(frame, 1))
        3 -> FloatX4.multiply(vector(frame, 0), vector(frame, 1))
        in 4..7 -> FloatX4.fused(operation - 4, vector(frame, 0), vector(frame, 1), vector(frame, 2))
        else -> fault("Invalid FloatX4 operation")
    }
    private fun vector(frame: VirtualFrame, index: Int): FloatX4 = arguments[index].execute(frame) as? FloatX4 ?: fault("Expected FloatX4#")
}

internal class VectorDoublePack(@field:Child private var argument: Expr,
    @field:CompilationFinal(dimensions = 1) private val slots: IntArray) : Expr() {
    init { representation = CoreVectors.proofDouble }
    override fun execute(frame: VirtualFrame): DoubleX2 {
        argument.executeTuple(frame, slots)
        return DoubleX2.pack(frame.getDouble(slots[0]), frame.getDouble(slots[1]))
    }
}
internal class VectorDoubleUnpack(@field:Child private var argument: Expr) : Expr() {
    init { representation = CoreVectors.unpackedDouble }
    override fun execute(frame: VirtualFrame): Any = fault("Vector unpack requires a tuple destination")
    override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Any? {
        val value = argument.execute(frame) as? DoubleX2 ?: fault("Expected DoubleX2#")
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
    override fun execute(frame: VirtualFrame): DoubleX2 = when (operation) {
        0 -> DoubleX2.broadcast(arguments[0].executeRequiredDouble(frame))
        1 -> DoubleX2.add(vector(frame, 0), vector(frame, 1))
        2 -> DoubleX2.subtract(vector(frame, 0), vector(frame, 1))
        3 -> DoubleX2.multiply(vector(frame, 0), vector(frame, 1))
        in 4..7 -> DoubleX2.fused(operation - 4, vector(frame, 0), vector(frame, 1), vector(frame, 2))
        else -> fault("Invalid DoubleX2 operation")
    }
    private fun vector(frame: VirtualFrame, index: Int): DoubleX2 = arguments[index].execute(frame) as? DoubleX2 ?: fault("Expected DoubleX2#")
}

/** Only transient Vector API values; the existing FloatX8 owns eight primitive fields. */
internal object FloatX8Fused {
    private fun vector(a: FloatX8): FloatVector = FloatVector.broadcast(FloatVector.SPECIES_256, a.lane0)
        .withLane(1, a.lane1).withLane(2, a.lane2).withLane(3, a.lane3)
        .withLane(4, a.lane4).withLane(5, a.lane5).withLane(6, a.lane6).withLane(7, a.lane7)

    @JvmStatic fun apply(operation: Int, a: FloatX8, b: FloatX8, c: FloatX8): FloatX8 {
        if (operation !in 0..3) fault("Invalid FloatX8 fused operation")
        // Negate operands before the single rounding, not the rounded result.
        val left = vector(a).let { if (operation >= 2) it.neg() else it }
        val addend = vector(c).let { if (operation and 1 != 0) it.neg() else it }
        val result = left.fma(vector(b), addend)
        return FloatX8(result.lane(0), result.lane(1), result.lane(2), result.lane(3),
            result.lane(4), result.lane(5), result.lane(6), result.lane(7))
    }
}

internal class VectorFloat8Fused(name: String, @field:Children private var arguments: Array<Expr>) : Expr() {
    private val operation = CoreVectors.fusedFloat8.indexOf(name)
    init { representation = GeneratedVectors.proofFloatX8 }
    override fun execute(frame: VirtualFrame): FloatX8 =
        FloatX8Fused.apply(operation, vector(frame, 0), vector(frame, 1), vector(frame, 2))
    private fun vector(frame: VirtualFrame, index: Int): FloatX8 =
        arguments[index].execute(frame) as? FloatX8 ?: fault("Expected FloatX8#")
}
