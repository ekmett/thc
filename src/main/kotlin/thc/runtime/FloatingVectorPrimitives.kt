// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal
import com.oracle.truffle.api.frame.VirtualFrame

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
