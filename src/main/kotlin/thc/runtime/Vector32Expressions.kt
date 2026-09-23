package thc.runtime

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal
import com.oracle.truffle.api.frame.VirtualFrame

/** Each node family has one exact vector carrier, chosen while lowering Core. */
internal class Vector32Pack(@field:Child private var argument: Expr,
    @field:CompilationFinal(dimensions = 1) private val slots: IntArray) : Expr() {
    init { representation = CoreVectors.proof32 }
    override fun execute(frame: VirtualFrame): Int32X4 {
        argument.executeTuple(frame, slots)
        return Int32X4(frame.getLong(slots[0]).toInt(), frame.getLong(slots[1]).toInt(),
            frame.getLong(slots[2]).toInt(), frame.getLong(slots[3]).toInt())
    }
}
internal class Vector32Unpack(@field:Child private var argument: Expr) : Expr() {
    init { representation = CoreVectors.unpacked32 }
    override fun execute(frame: VirtualFrame): Any = fault("Vector unpack requires a tuple destination")
    override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Any? {
        val value = argument.execute(frame) as? Int32X4 ?: fault("Expected Int32X4#")
        FrameAccess.writeLong(frame, slots[offset], value.first.toLong())
        FrameAccess.writeLong(frame, slots[offset + 1], value.second.toLong())
        FrameAccess.writeLong(frame, slots[offset + 2], value.third.toLong())
        FrameAccess.writeLong(frame, slots[offset + 3], value.fourth.toLong())
        return null
    }
}
internal class Vector32Operation(private val operation: String, @field:Children private var arguments: Array<Expr>) : Expr() {
    init { representation = CoreVectors.proof32 }
    override fun execute(frame: VirtualFrame): Int32X4 = when (operation) {
        "broadcastInt32X4#" -> arguments[0].executeRequiredLong(frame).toInt().let { Int32X4(it, it, it, it) }
        "plusInt32X4#" -> Int32X4.add(vector(frame, 0), vector(frame, 1))
        "minusInt32X4#" -> Int32X4.subtract(vector(frame, 0), vector(frame, 1))
        "negateInt32X4#" -> Int32X4.negate(vector(frame, 0))
        else -> fault("Invalid vector operation")
    }
    private fun vector(frame: VirtualFrame, index: Int): Int32X4 = arguments[index].execute(frame) as? Int32X4 ?: fault("Expected Int32X4#")
}
