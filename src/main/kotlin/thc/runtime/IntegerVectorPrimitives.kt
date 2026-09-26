// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import jdk.incubator.vector.ByteVector
import jdk.incubator.vector.ShortVector
import jdk.incubator.vector.IntVector
import jdk.incubator.vector.LongVector

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal
import com.oracle.truffle.api.frame.VirtualFrame

internal class VectorPack(@field:Child private var argument: Expr,
    @field:CompilationFinal(dimensions = 1) private val slots: IntArray) : Expr() {
    init { representation = CoreVectors.proof }
    override fun execute(frame: VirtualFrame): LongVector {
        argument.executeTuple(frame, slots)
        return LongVector.broadcast(LongVector.SPECIES_128, frame.getLong(slots[0])).withLane(1, frame.getLong(slots[1]))
    }
}
internal class VectorUnpack(@field:Child private var argument: Expr) : Expr() {
    init { representation = CoreVectors.unpacked }
    override fun execute(frame: VirtualFrame): Any = fault("Vector unpack requires a tuple destination")
    override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Any? {
        val vector = CoreVectors.requireLong(argument.execute(frame), LongVector.SPECIES_128)
        FrameAccess.writeLong(frame, slots[offset], vector.lane(0))
        FrameAccess.writeLong(frame, slots[offset + 1], vector.lane(1))
        return null
    }
}
internal class VectorOperation(private val operation: String, @field:Children private var arguments: Array<Expr>) : Expr() {
    init { representation = CoreVectors.proof }
    override fun execute(frame: VirtualFrame): LongVector = when (operation) {
        "broadcastInt64X2#" -> LongVector.broadcast(LongVector.SPECIES_128, arguments[0].executeRequiredLong(frame))
        "plusInt64X2#" -> (vector(frame, 0)).add(vector(frame, 1))
        "minusInt64X2#" -> (vector(frame, 0)).sub(vector(frame, 1))
        "negateInt64X2#" -> (vector(frame, 0)).neg()
        else -> fault("Invalid vector operation")
    }
    private fun vector(frame: VirtualFrame, index: Int): LongVector = CoreVectors.requireLong(arguments[index].execute(frame), LongVector.SPECIES_128)
}

/** Each node family selects one exact vector species while lowering Core. */
internal class Vector32Pack(@field:Child private var argument: Expr,
    @field:CompilationFinal(dimensions = 1) private val slots: IntArray) : Expr() {
    init { representation = CoreVectors.proof32 }
    override fun execute(frame: VirtualFrame): IntVector {
        argument.executeTuple(frame, slots)
        return IntVector.broadcast(IntVector.SPECIES_128, frame.getLong(slots[0]).toInt()).withLane(1, frame.getLong(slots[1]).toInt()).withLane(2, frame.getLong(slots[2]).toInt()).withLane(3, frame.getLong(slots[3]).toInt())
    }
}
internal class Vector32Unpack(@field:Child private var argument: Expr) : Expr() {
    init { representation = CoreVectors.unpacked32 }
    override fun execute(frame: VirtualFrame): Any = fault("Vector unpack requires a tuple destination")
    override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Any? {
        val value = CoreVectors.requireInt(argument.execute(frame), IntVector.SPECIES_128)
        FrameAccess.writeLong(frame, slots[offset], value.lane(0).toLong())
        FrameAccess.writeLong(frame, slots[offset + 1], value.lane(1).toLong())
        FrameAccess.writeLong(frame, slots[offset + 2], value.lane(2).toLong())
        FrameAccess.writeLong(frame, slots[offset + 3], value.lane(3).toLong())
        return null
    }
}
internal class Vector32Operation(private val operation: String, @field:Children private var arguments: Array<Expr>) : Expr() {
    init { representation = CoreVectors.proof32 }
    override fun execute(frame: VirtualFrame): IntVector = when (operation) {
        "broadcastInt32X4#" -> IntVector.broadcast(IntVector.SPECIES_128, arguments[0].executeRequiredLong(frame).toInt())
        "plusInt32X4#" -> (vector(frame, 0)).add(vector(frame, 1))
        "minusInt32X4#" -> (vector(frame, 0)).sub(vector(frame, 1))
        "timesInt32X4#" -> (vector(frame, 0)).mul(vector(frame, 1))
        "negateInt32X4#" -> (vector(frame, 0)).neg()
        else -> fault("Invalid vector operation")
    }
    private fun vector(frame: VirtualFrame, index: Int): IntVector = CoreVectors.requireInt(arguments[index].execute(frame), IntVector.SPECIES_128)
}

internal class Vector16Pack(@field:Child private var argument: Expr,
    @field:CompilationFinal(dimensions = 1) private val slots: IntArray) : Expr() {
    init { representation = CoreVectors.proof16 }
    override fun execute(frame: VirtualFrame): ShortVector {
        argument.executeTuple(frame, slots)
        return ShortVector.broadcast(ShortVector.SPECIES_128, frame.getLong(slots[0]).toShort()).withLane(1, frame.getLong(slots[1]).toShort()).withLane(2, frame.getLong(slots[2]).toShort()).withLane(3, frame.getLong(slots[3]).toShort()).withLane(4, frame.getLong(slots[4]).toShort()).withLane(5, frame.getLong(slots[5]).toShort()).withLane(6, frame.getLong(slots[6]).toShort()).withLane(7, frame.getLong(slots[7]).toShort())
    }
}
internal class Vector16Unpack(@field:Child private var argument: Expr) : Expr() {
    init { representation = CoreVectors.unpacked16 }
    override fun execute(frame: VirtualFrame): Any = fault("Vector unpack requires a tuple destination")
    override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Any? {
        val value = CoreVectors.requireShort(argument.execute(frame), ShortVector.SPECIES_128)
        FrameAccess.writeLong(frame, slots[offset], value.lane(0).toLong())
        FrameAccess.writeLong(frame, slots[offset + 1], value.lane(1).toLong())
        FrameAccess.writeLong(frame, slots[offset + 2], value.lane(2).toLong())
        FrameAccess.writeLong(frame, slots[offset + 3], value.lane(3).toLong())
        FrameAccess.writeLong(frame, slots[offset + 4], value.lane(4).toLong())
        FrameAccess.writeLong(frame, slots[offset + 5], value.lane(5).toLong())
        FrameAccess.writeLong(frame, slots[offset + 6], value.lane(6).toLong())
        FrameAccess.writeLong(frame, slots[offset + 7], value.lane(7).toLong())
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
    override fun execute(frame: VirtualFrame): ShortVector = when (operation) {
        0 -> ShortVector.broadcast(ShortVector.SPECIES_128, arguments[0].executeRequiredLong(frame).toShort())
        1 -> (vector(frame, 0)).add(vector(frame, 1))
        2 -> (vector(frame, 0)).sub(vector(frame, 1))
        3 -> (vector(frame, 0)).neg()
        4 -> (vector(frame, 0)).mul(vector(frame, 1))
        else -> fault("Invalid Int16X8 operation")
    }
    private fun vector(frame: VirtualFrame, index: Int): ShortVector = CoreVectors.requireShort(arguments[index].execute(frame), ShortVector.SPECIES_128)
}

internal class Vector8Pack(@field:Child private var argument: Expr,
    @field:CompilationFinal(dimensions = 1) private val slots: IntArray) : Expr() {
    init { representation = CoreVectors.proof8 }
    override fun execute(frame: VirtualFrame): ByteVector {
        argument.executeTuple(frame, slots)
        return ByteVector.broadcast(ByteVector.SPECIES_128, frame.getLong(slots[0]).toByte()).withLane(1, frame.getLong(slots[1]).toByte()).withLane(2, frame.getLong(slots[2]).toByte()).withLane(3, frame.getLong(slots[3]).toByte()).withLane(4, frame.getLong(slots[4]).toByte()).withLane(5, frame.getLong(slots[5]).toByte()).withLane(6, frame.getLong(slots[6]).toByte()).withLane(7, frame.getLong(slots[7]).toByte()).withLane(8, frame.getLong(slots[8]).toByte()).withLane(9, frame.getLong(slots[9]).toByte()).withLane(10, frame.getLong(slots[10]).toByte()).withLane(11, frame.getLong(slots[11]).toByte()).withLane(12, frame.getLong(slots[12]).toByte()).withLane(13, frame.getLong(slots[13]).toByte()).withLane(14, frame.getLong(slots[14]).toByte()).withLane(15, frame.getLong(slots[15]).toByte())
    }
}
internal class Vector8Unpack(@field:Child private var argument: Expr) : Expr() {
    init { representation = CoreVectors.unpacked8 }
    override fun execute(frame: VirtualFrame): Any = fault("Vector unpack requires a tuple destination")
    override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Any? {
        val value = CoreVectors.requireByte(argument.execute(frame), ByteVector.SPECIES_128)
        FrameAccess.writeLong(frame, slots[offset], value.lane(0).toLong())
        FrameAccess.writeLong(frame, slots[offset + 1], value.lane(1).toLong())
        FrameAccess.writeLong(frame, slots[offset + 2], value.lane(2).toLong())
        FrameAccess.writeLong(frame, slots[offset + 3], value.lane(3).toLong())
        FrameAccess.writeLong(frame, slots[offset + 4], value.lane(4).toLong())
        FrameAccess.writeLong(frame, slots[offset + 5], value.lane(5).toLong())
        FrameAccess.writeLong(frame, slots[offset + 6], value.lane(6).toLong())
        FrameAccess.writeLong(frame, slots[offset + 7], value.lane(7).toLong())
        FrameAccess.writeLong(frame, slots[offset + 8], value.lane(8).toLong())
        FrameAccess.writeLong(frame, slots[offset + 9], value.lane(9).toLong())
        FrameAccess.writeLong(frame, slots[offset + 10], value.lane(10).toLong())
        FrameAccess.writeLong(frame, slots[offset + 11], value.lane(11).toLong())
        FrameAccess.writeLong(frame, slots[offset + 12], value.lane(12).toLong())
        FrameAccess.writeLong(frame, slots[offset + 13], value.lane(13).toLong())
        FrameAccess.writeLong(frame, slots[offset + 14], value.lane(14).toLong())
        FrameAccess.writeLong(frame, slots[offset + 15], value.lane(15).toLong())
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
    override fun execute(frame: VirtualFrame): ByteVector = when (operation) {
        0 -> ByteVector.broadcast(ByteVector.SPECIES_128, arguments[0].executeRequiredLong(frame).toByte())
        1 -> (vector(frame, 0)).add(vector(frame, 1))
        2 -> (vector(frame, 0)).sub(vector(frame, 1))
        3 -> (vector(frame, 0)).neg()
        4 -> (vector(frame, 0)).mul(vector(frame, 1))
        else -> fault("Invalid Int8X16 operation")
    }
    private fun vector(frame: VirtualFrame, index: Int): ByteVector = CoreVectors.requireByte(arguments[index].execute(frame), ByteVector.SPECIES_128)
}

internal class VectorWord32Pack(@field:Child private var argument: Expr,
    @field:CompilationFinal(dimensions = 1) private val slots: IntArray) : Expr() {
    init { representation = CoreVectors.proofWord32 }
    override fun execute(frame: VirtualFrame): IntVector {
        argument.executeTuple(frame, slots)
        return IntVector.broadcast(IntVector.SPECIES_128, frame.getLong(slots[0]).toInt()).withLane(1, frame.getLong(slots[1]).toInt()).withLane(2, frame.getLong(slots[2]).toInt()).withLane(3, frame.getLong(slots[3]).toInt())
    }
}
internal class VectorWord32Unpack(@field:Child private var argument: Expr) : Expr() {
    init { representation = CoreVectors.unpackedWord32 }
    override fun execute(frame: VirtualFrame): Any = fault("Vector unpack requires a tuple destination")
    override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Any? {
        val value = CoreVectors.requireInt(argument.execute(frame), IntVector.SPECIES_128)
        FrameAccess.writeLong(frame, slots[offset], value.lane(0).toLong() and 0xffff_ffffL)
        FrameAccess.writeLong(frame, slots[offset + 1], value.lane(1).toLong() and 0xffff_ffffL)
        FrameAccess.writeLong(frame, slots[offset + 2], value.lane(2).toLong() and 0xffff_ffffL)
        FrameAccess.writeLong(frame, slots[offset + 3], value.lane(3).toLong() and 0xffff_ffffL)
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
    override fun execute(frame: VirtualFrame): IntVector = when (operation) {
        0 -> IntVector.broadcast(IntVector.SPECIES_128, arguments[0].executeRequiredLong(frame).toInt())
        1 -> (vector(frame, 0)).add(vector(frame, 1))
        2 -> (vector(frame, 0)).sub(vector(frame, 1))
        3 -> (vector(frame, 0)).mul(vector(frame, 1))
        else -> fault("Invalid Word32X4 operation")
    }
    private fun vector(frame: VirtualFrame, index: Int): IntVector = CoreVectors.requireInt(arguments[index].execute(frame), IntVector.SPECIES_128)
}

internal class VectorWord16Pack(@field:Child private var argument: Expr,
    @field:CompilationFinal(dimensions = 1) private val slots: IntArray) : Expr() {
    init { representation = CoreVectors.proofWord16 }
    override fun execute(frame: VirtualFrame): ShortVector {
        argument.executeTuple(frame, slots)
        return ShortVector.broadcast(ShortVector.SPECIES_128, frame.getLong(slots[0]).toShort()).withLane(1, frame.getLong(slots[1]).toShort()).withLane(2, frame.getLong(slots[2]).toShort()).withLane(3, frame.getLong(slots[3]).toShort()).withLane(4, frame.getLong(slots[4]).toShort()).withLane(5, frame.getLong(slots[5]).toShort()).withLane(6, frame.getLong(slots[6]).toShort()).withLane(7, frame.getLong(slots[7]).toShort())
    }
}
internal class VectorWord16Unpack(@field:Child private var argument: Expr) : Expr() {
    init { representation = CoreVectors.unpackedWord16 }
    override fun execute(frame: VirtualFrame): Any = fault("Vector unpack requires a tuple destination")
    override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Any? {
        val value = CoreVectors.requireShort(argument.execute(frame), ShortVector.SPECIES_128)
        FrameAccess.writeLong(frame, slots[offset], value.lane(0).toLong() and 0xffffL)
        FrameAccess.writeLong(frame, slots[offset + 1], value.lane(1).toLong() and 0xffffL)
        FrameAccess.writeLong(frame, slots[offset + 2], value.lane(2).toLong() and 0xffffL)
        FrameAccess.writeLong(frame, slots[offset + 3], value.lane(3).toLong() and 0xffffL)
        FrameAccess.writeLong(frame, slots[offset + 4], value.lane(4).toLong() and 0xffffL)
        FrameAccess.writeLong(frame, slots[offset + 5], value.lane(5).toLong() and 0xffffL)
        FrameAccess.writeLong(frame, slots[offset + 6], value.lane(6).toLong() and 0xffffL)
        FrameAccess.writeLong(frame, slots[offset + 7], value.lane(7).toLong() and 0xffffL)
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
    override fun execute(frame: VirtualFrame): ShortVector = when (operation) {
        0 -> ShortVector.broadcast(ShortVector.SPECIES_128, arguments[0].executeRequiredLong(frame).toShort())
        1 -> (vector(frame, 0)).add(vector(frame, 1))
        2 -> (vector(frame, 0)).sub(vector(frame, 1))
        3 -> (vector(frame, 0)).mul(vector(frame, 1))
        else -> fault("Invalid Word16X8 operation")
    }
    private fun vector(frame: VirtualFrame, index: Int): ShortVector = CoreVectors.requireShort(arguments[index].execute(frame), ShortVector.SPECIES_128)
}

internal class VectorWord8Pack(@field:Child private var argument: Expr,
    @field:CompilationFinal(dimensions = 1) private val slots: IntArray) : Expr() {
    init { representation = CoreVectors.proofWord8 }
    override fun execute(frame: VirtualFrame): ByteVector {
        argument.executeTuple(frame, slots)
        return ByteVector.broadcast(ByteVector.SPECIES_128, frame.getLong(slots[0]).toByte()).withLane(1, frame.getLong(slots[1]).toByte()).withLane(2, frame.getLong(slots[2]).toByte()).withLane(3, frame.getLong(slots[3]).toByte()).withLane(4, frame.getLong(slots[4]).toByte()).withLane(5, frame.getLong(slots[5]).toByte()).withLane(6, frame.getLong(slots[6]).toByte()).withLane(7, frame.getLong(slots[7]).toByte()).withLane(8, frame.getLong(slots[8]).toByte()).withLane(9, frame.getLong(slots[9]).toByte()).withLane(10, frame.getLong(slots[10]).toByte()).withLane(11, frame.getLong(slots[11]).toByte()).withLane(12, frame.getLong(slots[12]).toByte()).withLane(13, frame.getLong(slots[13]).toByte()).withLane(14, frame.getLong(slots[14]).toByte()).withLane(15, frame.getLong(slots[15]).toByte())
    }
}
internal class VectorWord8Unpack(@field:Child private var argument: Expr) : Expr() {
    init { representation = CoreVectors.unpackedWord8 }
    override fun execute(frame: VirtualFrame): Any = fault("Vector unpack requires a tuple destination")
    override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Any? {
        val value = CoreVectors.requireByte(argument.execute(frame), ByteVector.SPECIES_128)
        FrameAccess.writeLong(frame, slots[offset], value.lane(0).toLong() and 0xffL)
        FrameAccess.writeLong(frame, slots[offset + 1], value.lane(1).toLong() and 0xffL)
        FrameAccess.writeLong(frame, slots[offset + 2], value.lane(2).toLong() and 0xffL)
        FrameAccess.writeLong(frame, slots[offset + 3], value.lane(3).toLong() and 0xffL)
        FrameAccess.writeLong(frame, slots[offset + 4], value.lane(4).toLong() and 0xffL)
        FrameAccess.writeLong(frame, slots[offset + 5], value.lane(5).toLong() and 0xffL)
        FrameAccess.writeLong(frame, slots[offset + 6], value.lane(6).toLong() and 0xffL)
        FrameAccess.writeLong(frame, slots[offset + 7], value.lane(7).toLong() and 0xffL)
        FrameAccess.writeLong(frame, slots[offset + 8], value.lane(8).toLong() and 0xffL)
        FrameAccess.writeLong(frame, slots[offset + 9], value.lane(9).toLong() and 0xffL)
        FrameAccess.writeLong(frame, slots[offset + 10], value.lane(10).toLong() and 0xffL)
        FrameAccess.writeLong(frame, slots[offset + 11], value.lane(11).toLong() and 0xffL)
        FrameAccess.writeLong(frame, slots[offset + 12], value.lane(12).toLong() and 0xffL)
        FrameAccess.writeLong(frame, slots[offset + 13], value.lane(13).toLong() and 0xffL)
        FrameAccess.writeLong(frame, slots[offset + 14], value.lane(14).toLong() and 0xffL)
        FrameAccess.writeLong(frame, slots[offset + 15], value.lane(15).toLong() and 0xffL)
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
    override fun execute(frame: VirtualFrame): ByteVector = when (operation) {
        0 -> ByteVector.broadcast(ByteVector.SPECIES_128, arguments[0].executeRequiredLong(frame).toByte())
        1 -> (vector(frame, 0)).add(vector(frame, 1))
        2 -> (vector(frame, 0)).sub(vector(frame, 1))
        3 -> (vector(frame, 0)).mul(vector(frame, 1))
        else -> fault("Invalid Word8X16 operation")
    }
    private fun vector(frame: VirtualFrame, index: Int): ByteVector = CoreVectors.requireByte(arguments[index].execute(frame), ByteVector.SPECIES_128)
}
