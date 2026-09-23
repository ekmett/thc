package thc.runtime

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal
import com.oracle.truffle.api.frame.VirtualFrame

internal class Vector16Pack(@field:Child private var argument: Expr,
    @field:CompilationFinal(dimensions = 1) private val slots: IntArray) : Expr() {
    init { representation = CoreVectors.proof16 }
    override fun execute(frame: VirtualFrame): Int16X8 {
        argument.executeTuple(frame, slots)
        return Int16X8(frame.getLong(slots[0]).toShort(), frame.getLong(slots[1]).toShort(),
            frame.getLong(slots[2]).toShort(), frame.getLong(slots[3]).toShort(),
            frame.getLong(slots[4]).toShort(), frame.getLong(slots[5]).toShort(),
            frame.getLong(slots[6]).toShort(), frame.getLong(slots[7]).toShort())
    }
}
internal class Vector16Unpack(@field:Child private var argument: Expr) : Expr() {
    init { representation = CoreVectors.unpacked16 }
    override fun execute(frame: VirtualFrame): Any = fault("Vector unpack requires a tuple destination")
    override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Any? {
        val value = argument.execute(frame) as? Int16X8 ?: fault("Expected Int16X8#")
        FrameAccess.writeLong(frame, slots[offset], value.first.toLong())
        FrameAccess.writeLong(frame, slots[offset + 1], value.second.toLong())
        FrameAccess.writeLong(frame, slots[offset + 2], value.third.toLong())
        FrameAccess.writeLong(frame, slots[offset + 3], value.fourth.toLong())
        FrameAccess.writeLong(frame, slots[offset + 4], value.fifth.toLong())
        FrameAccess.writeLong(frame, slots[offset + 5], value.sixth.toLong())
        FrameAccess.writeLong(frame, slots[offset + 6], value.seventh.toLong())
        FrameAccess.writeLong(frame, slots[offset + 7], value.eighth.toLong())
        return null
    }
}
internal class Vector16Operation(name: String, @field:Children private var arguments: Array<Expr>) : Expr() {
    private val operation = when (name) {
        "broadcastInt16X8#" -> 0; "plusInt16X8#" -> 1; "minusInt16X8#" -> 2
        "negateInt16X8#" -> 3; "timesInt16X8#" -> 4
        else -> throw RuntimeFault("Invalid Int16X8 operation")
    }
    init { representation = CoreVectors.proof16 }
    override fun execute(frame: VirtualFrame): Int16X8 = when (operation) {
        0 -> Int16X8.broadcast(arguments[0].executeRequiredLong(frame).toShort())
        1 -> Int16X8.add(vector(frame, 0), vector(frame, 1))
        2 -> Int16X8.subtract(vector(frame, 0), vector(frame, 1))
        3 -> Int16X8.negate(vector(frame, 0))
        4 -> Int16X8.multiply(vector(frame, 0), vector(frame, 1))
        else -> fault("Invalid Int16X8 operation")
    }
    private fun vector(frame: VirtualFrame, index: Int): Int16X8 = arguments[index].execute(frame) as? Int16X8 ?: fault("Expected Int16X8#")
}
