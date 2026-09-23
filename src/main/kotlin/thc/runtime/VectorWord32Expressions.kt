package thc.runtime

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal
import com.oracle.truffle.api.frame.VirtualFrame

internal class VectorWord32Pack(@field:Child private var argument: Expr,
    @field:CompilationFinal(dimensions = 1) private val slots: IntArray) : Expr() {
    init { representation = CoreVectors.proofWord32 }
    override fun execute(frame: VirtualFrame): Word32X4 {
        argument.executeTuple(frame, slots)
        return Word32X4(frame.getLong(slots[0]).toInt(), frame.getLong(slots[1]).toInt(),
            frame.getLong(slots[2]).toInt(), frame.getLong(slots[3]).toInt())
    }
}
internal class VectorWord32Unpack(@field:Child private var argument: Expr) : Expr() {
    init { representation = CoreVectors.unpackedWord32 }
    override fun execute(frame: VirtualFrame): Any = fault("Vector unpack requires a tuple destination")
    override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Any? {
        val value = argument.execute(frame) as? Word32X4 ?: fault("Expected Word32X4#")
        FrameAccess.writeLong(frame, slots[offset], value.first.toLong() and 0xffff_ffffL)
        FrameAccess.writeLong(frame, slots[offset + 1], value.second.toLong() and 0xffff_ffffL)
        FrameAccess.writeLong(frame, slots[offset + 2], value.third.toLong() and 0xffff_ffffL)
        FrameAccess.writeLong(frame, slots[offset + 3], value.fourth.toLong() and 0xffff_ffffL)
        return null
    }
}
internal class VectorWord32Operation(name: String, @field:Children private var arguments: Array<Expr>) : Expr() {
    private val operation = when (name) {
        "broadcastWord32X4#" -> 0; "plusWord32X4#" -> 1; "minusWord32X4#" -> 2
        "timesWord32X4#" -> 3
        else -> throw RuntimeFault("Invalid Word32X4 operation")
    }
    init { representation = CoreVectors.proofWord32 }
    override fun execute(frame: VirtualFrame): Word32X4 = when (operation) {
        0 -> Word32X4.broadcast(arguments[0].executeRequiredLong(frame).toInt())
        1 -> Word32X4.add(vector(frame, 0), vector(frame, 1))
        2 -> Word32X4.subtract(vector(frame, 0), vector(frame, 1))
        3 -> Word32X4.multiply(vector(frame, 0), vector(frame, 1))
        else -> fault("Invalid Word32X4 operation")
    }
    private fun vector(frame: VirtualFrame, index: Int): Word32X4 = arguments[index].execute(frame) as? Word32X4 ?: fault("Expected Word32X4#")
}
