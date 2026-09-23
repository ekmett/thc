package thc.runtime

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal
import com.oracle.truffle.api.frame.VirtualFrame

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
        else -> throw RuntimeFault("Invalid DoubleX2 operation")
    }
    init { representation = CoreVectors.proofDouble }
    override fun execute(frame: VirtualFrame): DoubleX2 = when (operation) {
        0 -> DoubleX2.broadcast(arguments[0].executeRequiredDouble(frame))
        1 -> DoubleX2.add(vector(frame, 0), vector(frame, 1))
        2 -> DoubleX2.subtract(vector(frame, 0), vector(frame, 1))
        3 -> DoubleX2.multiply(vector(frame, 0), vector(frame, 1))
        else -> fault("Invalid DoubleX2 operation")
    }
    private fun vector(frame: VirtualFrame, index: Int): DoubleX2 = arguments[index].execute(frame) as? DoubleX2 ?: fault("Expected DoubleX2#")
}
