// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import jdk.incubator.vector.FloatVector

import java.math.BigInteger
import java.nio.ByteOrder
import jdk.incubator.vector.FloatVector
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class FloatVectorStorageTest {
    private fun shift(byte: Int) = 8 * (if (ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN) byte else 3 - byte)
    private fun loadModel(bytes: ByteArray, offset: Int) = IntArray(4) { lane ->
        (0..3).fold(0) { bits, byte -> bits or ((bytes[offset + lane * 4 + byte].toInt() and 255) shl shift(byte)) }
    }
    private fun storeModel(bytes: ByteArray, offset: Int, bits: IntArray) {
        for (lane in 0..3) for (byte in 0..3) bytes[offset + lane * 4 + byte] = (bits[lane] ushr shift(byte)).toByte()
    }
    private fun lanes(value: FloatVector) = IntArray(4) { java.lang.Float.floatToRawIntBits(value.lane(it)) }
    private fun packed(bits: IntArray) = FloatVector.broadcast(FloatVector.SPECIES_128, java.lang.Float.intBitsToFloat(bits[0])).withLane(1, java.lang.Float.intBitsToFloat(bits[1])).withLane(2, java.lang.Float.intBitsToFloat(bits[2])).withLane(3, java.lang.Float.intBitsToFloat(bits[3]))
    private fun offsetModel(size: Int, index: Long, scalarOffset: Boolean): Int? {
        val offset = BigInteger.valueOf(index).multiply(BigInteger.valueOf(if (scalarOffset) 4 else 16))
        return if (offset.signum() < 0 || offset.add(BigInteger.valueOf(16)) > BigInteger.valueOf(size.toLong())) null
            else offset.intValueExact()
    }
    private fun finiteBytes(size: Int) = ByteArray(size) { byte ->
        val bits = (byte / 4 * 0x1234567 + 0x81234567.toInt()) and 0xbfffffff.toInt()
        (bits ushr shift(byte % 4)).toByte()
    }

    @Test fun fullWidthBoundsAndNoPartialEffectsMatchIndependentByteModels() {
        val indices = (-2L..12L).toList() + listOf(
            Long.MIN_VALUE, Long.MIN_VALUE + 1, Long.MAX_VALUE - 1, Long.MAX_VALUE,
            Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong() - 1, Int.MAX_VALUE.toLong(), Int.MAX_VALUE.toLong() + 1,
            (1L shl 32) - 1, 1L shl 32, (1L shl 32) + 1,
            (1L shl 60) - 1, 1L shl 60, (1L shl 60) + 1,
            (1L shl 62) - 1, 1L shl 62, (1L shl 62) + 1) +
            listOf(4, 16).flatMap { stride -> (-1L..1L).map { Int.MAX_VALUE.toLong() / stride + it } }
        val values = intArrayOf(0x80000000.toInt(), 0x00000001, 0x7fc12345, 0xff800000.toInt())
        for (size in 0..40) for (scalarOffset in listOf(false, true)) for (index in indices) {
            val bytes = finiteBytes(size); val before = bytes.copyOf()
            val offset = offsetModel(size, index, scalarOffset)
            val label = "size=$size/index=$index/scalar=$scalarOffset"
            if (offset == null) {
                val read = assertThrows(RuntimeFault::class.java, { readFloatVectorArray(bytes, index, scalarOffset) }, label)
                assertEquals("FloatX4 ByteArray# range outside its backing storage", read.message, label)
                assertArrayEquals(before, bytes, "failed read $label")
                val write = assertThrows(RuntimeFault::class.java, { writeFloatVectorArray(bytes, index, packed(values), scalarOffset) }, label)
                assertEquals("FloatX4 ByteArray# range outside its backing storage", write.message, label)
                assertArrayEquals(before, bytes, "no partial write $label")
            } else {
                assertArrayEquals(loadModel(before, offset), lanes(readFloatVectorArray(bytes, index, scalarOffset)), label)
                assertArrayEquals(before, bytes, "read preserved storage $label")
                val expected = before.copyOf(); storeModel(expected, offset, values)
                writeFloatVectorArray(bytes, index, packed(values), scalarOffset)
                assertArrayEquals(expected, bytes, "whole vector and sentinel neighbours $label")
            }
        }
    }

    @Test fun immutableVectorRetainsFiniteSpecialAndQuietNaNBitsAtBothOffsetUnits() {
        val seeds = intArrayOf(0, 0x80000000.toInt(), 1, 0x80000001.toInt(), 0x007fffff, 0x807fffff.toInt(),
            0x00800000, 0x80800000.toInt(), 0x3f800000, 0xbf800000.toInt(), 0x41280000, 0xc1580000.toInt(),
            0x7f7fffff, 0xff7fffff.toInt(), 0x7f800000, 0xff800000.toInt(),
            0x7fc00001, 0x7fc12345, 0x7fffffff, 0xffc00001.toInt(), 0xffc12345.toInt(), 0xffffffff.toInt())
        for ((scalarOffset, index) in listOf(false to 1L, true to 1L, true to 2L, true to 3L, false to 2L, true to 8L))
            for (rotation in seeds.indices) {
                val values = IntArray(4) { seeds[(rotation + it * 5) % seeds.size] }
                val offset = offsetModel(48, index, scalarOffset)!!
                val source = ByteArray(48) { 0x5a }; storeModel(source, offset, values)
                val loaded = readFloatVectorArray(source, index, scalarOffset)
                assertArrayEquals(values, lanes(loaded))
                val expected = ByteArray(48) { 0x5a }; storeModel(expected, offset, values)
                val fromPack = ByteArray(48) { 0x5a }; writeFloatVectorArray(fromPack, index, packed(values), scalarOffset)
                assertArrayEquals(expected, fromPack)
                val fromLoad = ByteArray(48) { 0x5a }; writeFloatVectorArray(fromLoad, index, loaded, scalarOffset)
                assertArrayEquals(expected, fromLoad, "quiet NaN byte transport is not floating arithmetic")
            }
    }

    @Test fun byteAliasesAndOverlappingStoresPreserveLoadedFloatingSnapshots() {
        val bytes = finiteBytes(48); val alias = bytes
        val captured = readFloatVectorArray(bytes, 1, true); val original = loadModel(bytes, 4)
        assertArrayEquals(lanes(readFloatVectorArray(alias, 1, false)), lanes(readFloatVectorArray(bytes, 4, true)))
        for (byte in 0..15) {
            // Flip one bit per byte; the cleared exponent bit in finiteBytes stays clear.
            alias[4 + byte] = (alias[4 + byte].toInt() xor 1).toByte()
            assertArrayEquals(loadModel(bytes, 4), lanes(readFloatVectorArray(alias, 1, true)))
            assertArrayEquals(original, lanes(captured), "immutable snapshot after byte $byte mutation")
        }
        val expected = bytes.copyOf(); storeModel(expected, 8, original)
        writeFloatVectorArray(alias, 2, captured, true)
        assertArrayEquals(expected, bytes, "overlap uses retained vector snapshot")
        for (index in 0L..8L) assertArrayEquals(loadModel(expected, (index * 4).toInt()), lanes(readFloatVectorArray(bytes, index, true)))
        for (index in 0L..2L) assertArrayEquals(loadModel(expected, (index * 16).toInt()), lanes(readFloatVectorArray(alias, index, false)))
    }

    @Test fun selectedSignalingNaNByteMovementIsReportedAsPlatformQualifiedObservation() {
        // Never extract/pack scalar floating lanes here: scalar copies are allowed to quiet sNaNs.
        // This diagnostic is deliberately separate from portable finite/quiet-NaN assertions above.
        val source = ByteArray(40) { 0x35 }
        storeModel(source, 4, intArrayOf(0x7f800001, 0x7fa12345, 0xff800001.toInt(), 0xffa12345.toInt()))
        val target = ByteArray(40) { 0x35 }; val expected = source.copyOf()
        writeFloatVectorArray(target, 1, readFloatVectorArray(source, 1, true), true)
        assertArrayEquals(expected.copyOfRange(0, 4), target.copyOfRange(0, 4))
        assertArrayEquals(expected.copyOfRange(20, 40), target.copyOfRange(20, 40))
        println("FloatX4 selected sNaN byte movement: arch=${System.getProperty("os.arch")}, " +
            "java=${System.getProperty("java.version")}, nativeOrder=${ByteOrder.nativeOrder()}, " +
            "input=${loadModel(source, 4).joinToString { Integer.toUnsignedString(it, 16) }}, " +
            "output=${loadModel(target, 4).joinToString { Integer.toUnsignedString(it, 16) }}, exact=${source.contentEquals(target)}; " +
            "platform observation only, not a scalar pack/unpack or arithmetic guarantee")
    }
}
