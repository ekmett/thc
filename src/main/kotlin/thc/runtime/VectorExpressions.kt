package thc.runtime

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal
import com.oracle.truffle.api.frame.VirtualFrame

internal class VectorPack(@field:Child private var argument: Expr,
    @field:CompilationFinal(dimensions = 1) private val slots: IntArray) : Expr() {
    init { representation = CoreVectors.proof }
    override fun execute(frame: VirtualFrame): Int64X2 {
        argument.executeTuple(frame, slots)
        return Int64X2(frame.getLong(slots[0]), frame.getLong(slots[1]))
    }
}
internal class VectorUnpack(@field:Child private var argument: Expr) : Expr() {
    init { representation = CoreVectors.unpacked }
    override fun execute(frame: VirtualFrame): Any = fault("Vector unpack requires a tuple destination")
    override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Any? {
        val vector = argument.execute(frame) as? Int64X2 ?: fault("Expected Int64X2#")
        FrameAccess.writeLong(frame, slots[offset], vector.first)
        FrameAccess.writeLong(frame, slots[offset + 1], vector.second)
        return null
    }
}
internal class VectorOperation(private val operation: String, @field:Children private var arguments: Array<Expr>) : Expr() {
    init { representation = CoreVectors.proof }
    override fun execute(frame: VirtualFrame): Int64X2 = when (operation) {
        "broadcastInt64X2#" -> arguments[0].executeRequiredLong(frame).let { Int64X2(it, it) }
        "plusInt64X2#" -> Int64X2.add(vector(frame, 0), vector(frame, 1))
        "minusInt64X2#" -> Int64X2.subtract(vector(frame, 0), vector(frame, 1))
        "negateInt64X2#" -> Int64X2.negate(vector(frame, 0))
        else -> fault("Invalid vector operation")
    }
    private fun vector(frame: VirtualFrame, index: Int): Int64X2 = arguments[index].execute(frame) as? Int64X2 ?: fault("Expected Int64X2#")
}
