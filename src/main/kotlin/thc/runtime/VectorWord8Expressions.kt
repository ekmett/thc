package thc.runtime

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal
import com.oracle.truffle.api.frame.VirtualFrame

internal class VectorWord8Pack(@field:Child private var argument: Expr,
    @field:CompilationFinal(dimensions = 1) private val slots: IntArray) : Expr() {
    init { representation = CoreVectors.proofWord8 }
    override fun execute(frame: VirtualFrame): Word8X16 {
        argument.executeTuple(frame, slots)
        return Word8X16(frame.getLong(slots[0]).toByte(), frame.getLong(slots[1]).toByte(),
            frame.getLong(slots[2]).toByte(), frame.getLong(slots[3]).toByte(),
            frame.getLong(slots[4]).toByte(), frame.getLong(slots[5]).toByte(),
            frame.getLong(slots[6]).toByte(), frame.getLong(slots[7]).toByte(),
            frame.getLong(slots[8]).toByte(), frame.getLong(slots[9]).toByte(),
            frame.getLong(slots[10]).toByte(), frame.getLong(slots[11]).toByte(),
            frame.getLong(slots[12]).toByte(), frame.getLong(slots[13]).toByte(),
            frame.getLong(slots[14]).toByte(), frame.getLong(slots[15]).toByte())
    }
}
internal class VectorWord8Unpack(@field:Child private var argument: Expr) : Expr() {
    init { representation = CoreVectors.unpackedWord8 }
    override fun execute(frame: VirtualFrame): Any = fault("Vector unpack requires a tuple destination")
    override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Any? {
        val value = argument.execute(frame) as? Word8X16 ?: fault("Expected Word8X16#")
        FrameAccess.writeLong(frame, slots[offset], value.first.toLong() and 0xffL)
        FrameAccess.writeLong(frame, slots[offset + 1], value.second.toLong() and 0xffL)
        FrameAccess.writeLong(frame, slots[offset + 2], value.third.toLong() and 0xffL)
        FrameAccess.writeLong(frame, slots[offset + 3], value.fourth.toLong() and 0xffL)
        FrameAccess.writeLong(frame, slots[offset + 4], value.fifth.toLong() and 0xffL)
        FrameAccess.writeLong(frame, slots[offset + 5], value.sixth.toLong() and 0xffL)
        FrameAccess.writeLong(frame, slots[offset + 6], value.seventh.toLong() and 0xffL)
        FrameAccess.writeLong(frame, slots[offset + 7], value.eighth.toLong() and 0xffL)
        FrameAccess.writeLong(frame, slots[offset + 8], value.ninth.toLong() and 0xffL)
        FrameAccess.writeLong(frame, slots[offset + 9], value.tenth.toLong() and 0xffL)
        FrameAccess.writeLong(frame, slots[offset + 10], value.eleventh.toLong() and 0xffL)
        FrameAccess.writeLong(frame, slots[offset + 11], value.twelfth.toLong() and 0xffL)
        FrameAccess.writeLong(frame, slots[offset + 12], value.thirteenth.toLong() and 0xffL)
        FrameAccess.writeLong(frame, slots[offset + 13], value.fourteenth.toLong() and 0xffL)
        FrameAccess.writeLong(frame, slots[offset + 14], value.fifteenth.toLong() and 0xffL)
        FrameAccess.writeLong(frame, slots[offset + 15], value.sixteenth.toLong() and 0xffL)
        return null
    }
}
internal class VectorWord8Operation(name: String, @field:Children private var arguments: Array<Expr>) : Expr() {
    private val operation = when (name) {
        "broadcastWord8X16#" -> 0; "plusWord8X16#" -> 1; "minusWord8X16#" -> 2
        "timesWord8X16#" -> 3
        else -> throw RuntimeFault("Invalid Word8X16 operation")
    }
    init { representation = CoreVectors.proofWord8 }
    override fun execute(frame: VirtualFrame): Word8X16 = when (operation) {
        0 -> Word8X16.broadcast(arguments[0].executeRequiredLong(frame).toByte())
        1 -> Word8X16.add(vector(frame, 0), vector(frame, 1))
        2 -> Word8X16.subtract(vector(frame, 0), vector(frame, 1))
        3 -> Word8X16.multiply(vector(frame, 0), vector(frame, 1))
        else -> fault("Invalid Word8X16 operation")
    }
    private fun vector(frame: VirtualFrame, index: Int): Word8X16 = arguments[index].execute(frame) as? Word8X16 ?: fault("Expected Word8X16#")
}
