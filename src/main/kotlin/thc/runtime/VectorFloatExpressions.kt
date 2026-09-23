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
        else -> throw RuntimeFault("Invalid FloatX4 operation")
    }
    init { representation = CoreVectors.proofFloat }
    override fun execute(frame: VirtualFrame): FloatX4 = when (operation) {
        0 -> FloatX4.broadcast(arguments[0].executeRequiredFloat(frame))
        1 -> FloatX4.add(vector(frame, 0), vector(frame, 1))
        2 -> FloatX4.subtract(vector(frame, 0), vector(frame, 1))
        3 -> FloatX4.multiply(vector(frame, 0), vector(frame, 1))
        else -> fault("Invalid FloatX4 operation")
    }
    private fun vector(frame: VirtualFrame, index: Int): FloatX4 = arguments[index].execute(frame) as? FloatX4 ?: fault("Expected FloatX4#")
}
