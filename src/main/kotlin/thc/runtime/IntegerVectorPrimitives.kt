// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

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
        "timesInt32X4#" -> Int32X4.multiply(vector(frame, 0), vector(frame, 1))
        "negateInt32X4#" -> Int32X4.negate(vector(frame, 0))
        else -> fault("Invalid vector operation")
    }
    private fun vector(frame: VirtualFrame, index: Int): Int32X4 = arguments[index].execute(frame) as? Int32X4 ?: fault("Expected Int32X4#")
}

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

internal class Vector8Pack(@field:Child private var argument: Expr,
    @field:CompilationFinal(dimensions = 1) private val slots: IntArray) : Expr() {
    init { representation = CoreVectors.proof8 }
    override fun execute(frame: VirtualFrame): Int8X16 {
        argument.executeTuple(frame, slots)
        return Int8X16(frame.getLong(slots[0]).toByte(), frame.getLong(slots[1]).toByte(),
            frame.getLong(slots[2]).toByte(), frame.getLong(slots[3]).toByte(),
            frame.getLong(slots[4]).toByte(), frame.getLong(slots[5]).toByte(),
            frame.getLong(slots[6]).toByte(), frame.getLong(slots[7]).toByte(),
            frame.getLong(slots[8]).toByte(), frame.getLong(slots[9]).toByte(),
            frame.getLong(slots[10]).toByte(), frame.getLong(slots[11]).toByte(),
            frame.getLong(slots[12]).toByte(), frame.getLong(slots[13]).toByte(),
            frame.getLong(slots[14]).toByte(), frame.getLong(slots[15]).toByte())
    }
}
internal class Vector8Unpack(@field:Child private var argument: Expr) : Expr() {
    init { representation = CoreVectors.unpacked8 }
    override fun execute(frame: VirtualFrame): Any = fault("Vector unpack requires a tuple destination")
    override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Any? {
        val value = argument.execute(frame) as? Int8X16 ?: fault("Expected Int8X16#")
        FrameAccess.writeLong(frame, slots[offset], value.first.toLong())
        FrameAccess.writeLong(frame, slots[offset + 1], value.second.toLong())
        FrameAccess.writeLong(frame, slots[offset + 2], value.third.toLong())
        FrameAccess.writeLong(frame, slots[offset + 3], value.fourth.toLong())
        FrameAccess.writeLong(frame, slots[offset + 4], value.fifth.toLong())
        FrameAccess.writeLong(frame, slots[offset + 5], value.sixth.toLong())
        FrameAccess.writeLong(frame, slots[offset + 6], value.seventh.toLong())
        FrameAccess.writeLong(frame, slots[offset + 7], value.eighth.toLong())
        FrameAccess.writeLong(frame, slots[offset + 8], value.ninth.toLong())
        FrameAccess.writeLong(frame, slots[offset + 9], value.tenth.toLong())
        FrameAccess.writeLong(frame, slots[offset + 10], value.eleventh.toLong())
        FrameAccess.writeLong(frame, slots[offset + 11], value.twelfth.toLong())
        FrameAccess.writeLong(frame, slots[offset + 12], value.thirteenth.toLong())
        FrameAccess.writeLong(frame, slots[offset + 13], value.fourteenth.toLong())
        FrameAccess.writeLong(frame, slots[offset + 14], value.fifteenth.toLong())
        FrameAccess.writeLong(frame, slots[offset + 15], value.sixteenth.toLong())
        return null
    }
}
internal class Vector8Operation(name: String, @field:Children private var arguments: Array<Expr>) : Expr() {
    private val operation = when (name) {
        "broadcastInt8X16#" -> 0; "plusInt8X16#" -> 1; "minusInt8X16#" -> 2
        "negateInt8X16#" -> 3; "timesInt8X16#" -> 4
        else -> throw RuntimeFault("Invalid Int8X16 operation")
    }
    init { representation = CoreVectors.proof8 }
    override fun execute(frame: VirtualFrame): Int8X16 = when (operation) {
        0 -> Int8X16.broadcast(arguments[0].executeRequiredLong(frame).toByte())
        1 -> Int8X16.add(vector(frame, 0), vector(frame, 1))
        2 -> Int8X16.subtract(vector(frame, 0), vector(frame, 1))
        3 -> Int8X16.negate(vector(frame, 0))
        4 -> Int8X16.multiply(vector(frame, 0), vector(frame, 1))
        else -> fault("Invalid Int8X16 operation")
    }
    private fun vector(frame: VirtualFrame, index: Int): Int8X16 = arguments[index].execute(frame) as? Int8X16 ?: fault("Expected Int8X16#")
}

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
