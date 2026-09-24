// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import java.lang.reflect.Modifier
import java.math.BigInteger
import java.nio.ByteOrder
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class Word32VectorStorageTest {
    private fun lanes(value: Word32X4) = intArrayOf(value.first, value.second, value.third, value.fourth)
        .map { it.toLong() and 0xffff_ffffL }.toLongArray()
    private fun vector(values: LongArray): Word32X4 {
        assertTrue(values.size == 4 && values.all { it in 0L..0xffff_ffffL })
        return Word32X4(values[0].toInt(), values[1].toInt(), values[2].toInt(), values[3].toInt())
    }
    private fun shift(byte: Int) = 8 * (if (ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN) byte else 3 - byte)
    private fun loadModel(bytes: ByteArray, offset: Int) = LongArray(4) { lane ->
        (0..3).fold(0L) { value, byte -> value or ((bytes[offset + lane * 4 + byte].toLong() and 255L) shl shift(byte)) }
    }
    private fun storeModel(bytes: ByteArray, offset: Int, values: LongArray) {
        for (lane in 0..3) for (byte in 0..3)
            bytes[offset + lane * 4 + byte] = (values[lane] ushr shift(byte)).toByte()
    }
    private fun offsetModel(size: Int, index: Long, scalarOffset: Boolean): Int? {
        val offset = BigInteger.valueOf(index).multiply(BigInteger.valueOf(if (scalarOffset) 4L else 16L))
        return if (offset.signum() < 0 || offset.add(BigInteger.valueOf(16L)) > BigInteger.valueOf(size.toLong())) null
            else offset.intValueExact()
    }

    @Test fun smallArrayBoundariesAndFullWidthIndicesMatchIndependentUnsignedByteModels() {
        val indices = (-2L..12L).toList() + listOf(
            Long.MIN_VALUE, Long.MIN_VALUE + 1, Long.MAX_VALUE - 1, Long.MAX_VALUE,
            Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong() - 1, Int.MAX_VALUE.toLong(), Int.MAX_VALUE.toLong() + 1,
            (1L shl 32) - 1, 1L shl 32, (1L shl 32) + 1,
            (1L shl 60) - 1, 1L shl 60, (1L shl 60) + 1,
            (1L shl 62) - 1, 1L shl 62, (1L shl 62) + 1) +
            listOf(4, 16).flatMap { stride -> (-1L..1L).map { Int.MAX_VALUE.toLong() / stride + it } }
        val values = longArrayOf(0xffff_ffffL, 0x8000_0000L, 0x0123_4567L, 0x7fff_ffffL)
        for (size in 0..40) for (scalarOffset in listOf(false, true)) for (index in indices) {
            val bytes = ByteArray(size) { (it * 73 + size * 17 + 129).toByte() }
            val before = bytes.copyOf()
            val offset = offsetModel(size, index, scalarOffset)
            val label = "size=$size/index=$index/scalar=$scalarOffset"
            if (offset == null) {
                val read = assertThrows(RuntimeFault::class.java, { Word32X4.readArray(bytes, index, scalarOffset) }, label)
                assertEquals("Word32X4 ByteArray# range outside its backing storage", read.message, label)
                assertArrayEquals(before, bytes, "failed read $label")
                val write = assertThrows(RuntimeFault::class.java, { Word32X4.writeArray(bytes, index, vector(values), scalarOffset) }, label)
                assertEquals("Word32X4 ByteArray# range outside its backing storage", write.message, label)
                assertArrayEquals(before, bytes, "no partial write $label")
            } else {
                assertArrayEquals(loadModel(before, offset), lanes(Word32X4.readArray(bytes, index, scalarOffset)), label)
                assertArrayEquals(before, bytes, "read preserved storage $label")
                val expected = before.copyOf()
                storeModel(expected, offset, values)
                Word32X4.writeArray(bytes, index, vector(values), scalarOffset)
                assertArrayEquals(expected, bytes, "full vector and sentinel neighbours $label")
            }
        }
    }

    @Test fun fourFinalIntFieldsPreserveEveryUnsignedLaneBitAtBothOffsetUnits() {
        val fields = Word32X4::class.java.declaredFields
        assertEquals(4, fields.size)
        assertTrue(fields.all { it.type == Int::class.javaPrimitiveType && Modifier.isFinal(it.modifiers) && !Modifier.isStatic(it.modifiers) })
        val patterns = listOf(
            longArrayOf(0, 0x7fff_ffffL, 0x8000_0000L, 0xffff_ffffL),
            longArrayOf(0x0123_4567L, 0x89ab_cdefL, 0x55aa_55aaL, 0xaa55_aa55L),
            longArrayOf(0x0000_0080L, 0x0000_8000L, 0x0080_0000L, 0x8000_0000L),
            longArrayOf(0x0000_ffffL, 0x0001_0000L, 0xffff_0000L, 0x8000_0001L)) +
            (0..31).map { bit -> longArrayOf(1L shl bit, (1L shl bit) xor 0xffff_ffffL,
                1L shl ((bit + 11) % 32), 1L shl ((bit + 23) % 32)) }
        for ((scalarOffset, index) in listOf(false to 1L, true to 1L, true to 2L, true to 3L, false to 2L, true to 8L))
            for (values in patterns) {
                val offset = offsetModel(48, index, scalarOffset)!!
                val expected = ByteArray(48) { (it * 29 + 83).toByte() }
                val actual = expected.copyOf()
                storeModel(expected, offset, values)
                assertArrayEquals(values, lanes(Word32X4.readArray(expected, index, scalarOffset)))
                Word32X4.writeArray(actual, index, vector(values), scalarOffset)
                assertArrayEquals(expected, actual)
            }
    }

    @Test fun byteAliasesAndOverlappingStoresKeepLoadedUnsignedSnapshots() {
        val bytes = ByteArray(48) { (it * 19 + 131).toByte() }
        val alias = bytes
        val captured = Word32X4.readArray(bytes, 1, true)
        val original = loadModel(bytes, 4)
        assertArrayEquals(loadModel(bytes, 16), lanes(Word32X4.readArray(alias, 1, false)))
        assertArrayEquals(lanes(Word32X4.readArray(alias, 1, false)), lanes(Word32X4.readArray(bytes, 4, true)))
        for (byte in 0..15) {
            alias[4 + byte] = (alias[4 + byte].toInt() xor 0x80).toByte()
            assertArrayEquals(loadModel(bytes, 4), lanes(Word32X4.readArray(bytes, 1, true)))
            assertArrayEquals(original, lanes(captured), "loaded unsigned snapshot after byte $byte mutation")
        }
        val expected = bytes.copyOf()
        storeModel(expected, 8, original)
        Word32X4.writeArray(alias, 2, captured, true)
        assertArrayEquals(expected, bytes, "overlapping store uses captured raw lane bits")
        for (index in 0L..8L)
            assertArrayEquals(loadModel(expected, (index * 4).toInt()), lanes(Word32X4.readArray(bytes, index, true)))
        for (index in 0L..2L)
            assertArrayEquals(loadModel(expected, (index * 16).toInt()), lanes(Word32X4.readArray(alias, index, false)))
    }
}
