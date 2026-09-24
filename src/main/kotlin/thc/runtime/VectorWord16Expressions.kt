package thc.runtime

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal
import com.oracle.truffle.api.frame.VirtualFrame

internal class VectorWord16Pack(@field:Child private var argument: Expr,
    @field:CompilationFinal(dimensions = 1) private val slots: IntArray) : Expr() {
    init { representation = CoreVectors.proofWord16 }
    override fun execute(frame: VirtualFrame): Word16X8 {
        argument.executeTuple(frame, slots)
        return Word16X8(frame.getLong(slots[0]).toShort(), frame.getLong(slots[1]).toShort(),
            frame.getLong(slots[2]).toShort(), frame.getLong(slots[3]).toShort(),
            frame.getLong(slots[4]).toShort(), frame.getLong(slots[5]).toShort(),
            frame.getLong(slots[6]).toShort(), frame.getLong(slots[7]).toShort())
    }
}
internal class VectorWord16Unpack(@field:Child private var argument: Expr) : Expr() {
    init { representation = CoreVectors.unpackedWord16 }
    override fun execute(frame: VirtualFrame): Any = fault("Vector unpack requires a tuple destination")
    override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Any? {
        val value = argument.execute(frame) as? Word16X8 ?: fault("Expected Word16X8#")
        FrameAccess.writeLong(frame, slots[offset], value.first.toLong() and 0xffffL)
        FrameAccess.writeLong(frame, slots[offset + 1], value.second.toLong() and 0xffffL)
        FrameAccess.writeLong(frame, slots[offset + 2], value.third.toLong() and 0xffffL)
        FrameAccess.writeLong(frame, slots[offset + 3], value.fourth.toLong() and 0xffffL)
        FrameAccess.writeLong(frame, slots[offset + 4], value.fifth.toLong() and 0xffffL)
        FrameAccess.writeLong(frame, slots[offset + 5], value.sixth.toLong() and 0xffffL)
        FrameAccess.writeLong(frame, slots[offset + 6], value.seventh.toLong() and 0xffffL)
        FrameAccess.writeLong(frame, slots[offset + 7], value.eighth.toLong() and 0xffffL)
        return null
    }
}
internal class VectorWord16Operation(name: String, @field:Children private var arguments: Array<Expr>) : Expr() {
    private val operation = when (name) {
        "broadcastWord16X8#" -> 0; "plusWord16X8#" -> 1; "minusWord16X8#" -> 2
        "timesWord16X8#" -> 3
        else -> throw RuntimeFault("Invalid Word16X8 operation")
    }
    init { representation = CoreVectors.proofWord16 }
    override fun execute(frame: VirtualFrame): Word16X8 = when (operation) {
        0 -> Word16X8.broadcast(arguments[0].executeRequiredLong(frame).toShort())
        1 -> Word16X8.add(vector(frame, 0), vector(frame, 1))
        2 -> Word16X8.subtract(vector(frame, 0), vector(frame, 1))
        3 -> Word16X8.multiply(vector(frame, 0), vector(frame, 1))
        else -> fault("Invalid Word16X8 operation")
    }
    private fun vector(frame: VirtualFrame, index: Int): Word16X8 = arguments[index].execute(frame) as? Word16X8 ?: fault("Expected Word16X8#")
}
