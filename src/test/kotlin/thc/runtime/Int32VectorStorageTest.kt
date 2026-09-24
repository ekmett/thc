// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import java.math.BigInteger
import java.nio.ByteOrder
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class Int32VectorStorageTest {
    private fun lanes(value: Int32X4) = intArrayOf(value.first, value.second, value.third, value.fourth)
    private fun vector(values: IntArray) = Int32X4(values[0], values[1], values[2], values[3])
    private fun shift(byte: Int) = 8 * (if (ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN) byte else 3 - byte)
    private fun loadModel(bytes: ByteArray, offset: Int) = IntArray(4) { lane ->
        (0..3).fold(0) { value, byte -> value or ((bytes[offset + lane * 4 + byte].toInt() and 255) shl shift(byte)) }
    }
    private fun storeModel(bytes: ByteArray, offset: Int, values: IntArray) {
        for (lane in 0..3) for (byte in 0..3)
            bytes[offset + lane * 4 + byte] = (values[lane] ushr shift(byte)).toByte()
    }
    private fun offsetModel(size: Int, index: Long, scalarOffset: Boolean): Int? {
        val offset = BigInteger.valueOf(index).multiply(BigInteger.valueOf(if (scalarOffset) 4L else 16L))
        return if (offset.signum() < 0 || offset.add(BigInteger.valueOf(16L)) > BigInteger.valueOf(size.toLong())) null
            else offset.intValueExact()
    }

    @Test fun smallArrayBoundariesAndFullWidthIndicesMatchIndependentByteModels() {
        val indices = (-2L..12L).toList() + listOf(
            Long.MIN_VALUE, Long.MIN_VALUE + 1, Long.MAX_VALUE - 1, Long.MAX_VALUE,
            Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong() - 1, Int.MAX_VALUE.toLong(), Int.MAX_VALUE.toLong() + 1,
            (1L shl 32) - 1, 1L shl 32, (1L shl 32) + 1,
            (1L shl 60) - 1, 1L shl 60, (1L shl 60) + 1,
            (1L shl 62) - 1, 1L shl 62, (1L shl 62) + 1) +
            listOf(4, 16).flatMap { stride -> (-1L..1L).map { Int.MAX_VALUE.toLong() / stride + it } }
        val values = intArrayOf(Int.MIN_VALUE, 0x01234567, -1, Int.MAX_VALUE)
        for (size in 0..40) for (scalarOffset in listOf(false, true)) for (index in indices) {
            val bytes = ByteArray(size) { (it * 73 + size * 17 + 129).toByte() }
            val before = bytes.copyOf()
            val offset = offsetModel(size, index, scalarOffset)
            val label = "size=$size/index=$index/scalar=$scalarOffset"
            if (offset == null) {
                assertThrows(RuntimeFault::class.java, { Int32X4.readArray(bytes, index, scalarOffset) }, label)
                assertArrayEquals(before, bytes, "failed read $label")
                assertThrows(RuntimeFault::class.java, { Int32X4.writeArray(bytes, index, vector(values), scalarOffset) }, label)
                assertArrayEquals(before, bytes, "no partial write $label")
            } else {
                assertArrayEquals(loadModel(before, offset), lanes(Int32X4.readArray(bytes, index, scalarOffset)), label)
                assertArrayEquals(before, bytes, "read preserved storage $label")
                val expected = before.copyOf()
                storeModel(expected, offset, values)
                Int32X4.writeArray(bytes, index, vector(values), scalarOffset)
                assertArrayEquals(expected, bytes, "full vector and sentinel neighbours $label")
            }
        }
    }

    @Test fun everyLaneBitAndSignedPatternsUseNativeOrderAtBothOffsetUnits() {
        val patterns = listOf(
            intArrayOf(0, Int.MIN_VALUE, Int.MAX_VALUE, -1),
            intArrayOf(0x01234567, 0x89abcdef.toInt(), 0x55aa55aa, 0xaa55aa55.toInt()),
            intArrayOf(0x00000080, 0x00008000, 0x00800000, Int.MIN_VALUE)) +
            (0..31).map { bit -> intArrayOf(1 shl bit, (1 shl bit).inv(), 1 shl ((bit + 11) % 32), 1 shl ((bit + 23) % 32)) }
        for ((scalarOffset, index) in listOf(false to 1L, true to 1L, true to 2L, true to 3L)) for (values in patterns) {
            val offset = offsetModel(48, index, scalarOffset)!!
            val expected = ByteArray(48) { (it * 29 + 83).toByte() }
            val actual = expected.copyOf()
            storeModel(expected, offset, values)
            assertArrayEquals(values, lanes(Int32X4.readArray(expected, index, scalarOffset)))
            Int32X4.writeArray(actual, index, vector(values), scalarOffset)
            assertArrayEquals(expected, actual)
        }
    }

    @Test fun byteAliasesAndOverlappingStoresObserveWritesButKeepLoadedLanes() {
        val bytes = ByteArray(48) { (it * 19 + 131).toByte() }
        val alias = bytes
        val captured = Int32X4.readArray(bytes, 1, true)
        val original = loadModel(bytes, 4)
        assertArrayEquals(loadModel(bytes, 16), lanes(Int32X4.readArray(alias, 1, false)))
        assertArrayEquals(lanes(Int32X4.readArray(alias, 1, false)), lanes(Int32X4.readArray(bytes, 4, true)))
        for (byte in 0..15) {
            alias[4 + byte] = (alias[4 + byte].toInt() xor 0x80).toByte()
            assertArrayEquals(loadModel(bytes, 4), lanes(Int32X4.readArray(bytes, 1, true)))
            assertArrayEquals(original, lanes(captured), "loaded lane snapshot after byte $byte mutation")
        }
        val expected = bytes.copyOf()
        storeModel(expected, 8, original)
        Int32X4.writeArray(alias, 2, captured, true)
        assertArrayEquals(expected, bytes, "overlapping store uses captured lane values")
        for (index in 0L..8L)
            assertArrayEquals(loadModel(expected, (index * 4).toInt()), lanes(Int32X4.readArray(bytes, index, true)))
        for (index in 0L..2L)
            assertArrayEquals(loadModel(expected, (index * 16).toInt()), lanes(Int32X4.readArray(alias, index, false)))
    }
}
