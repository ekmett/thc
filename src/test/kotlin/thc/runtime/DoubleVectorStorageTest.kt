package thc.runtime

import java.lang.reflect.Modifier
import java.math.BigInteger
import java.nio.ByteOrder
import jdk.incubator.vector.DoubleVector
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class DoubleVectorStorageTest {
    private fun shift(byte: Int) = 8 * (if (ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN) byte else 7 - byte)
    private fun loadModel(bytes: ByteArray, offset: Int) = LongArray(2) { lane ->
        (0..7).fold(0L) { bits, byte -> bits or ((bytes[offset + lane * 8 + byte].toLong() and 255) shl shift(byte)) }
    }
    private fun storeModel(bytes: ByteArray, offset: Int, bits: LongArray) {
        for (lane in 0..1) for (byte in 0..7) bytes[offset + lane * 8 + byte] = (bits[lane] ushr shift(byte)).toByte()
    }
    private fun lanes(value: DoubleX2) = LongArray(2) { java.lang.Double.doubleToRawLongBits(value.lane(it)) }
    private fun packed(bits: LongArray) = DoubleX2.pack(java.lang.Double.longBitsToDouble(bits[0]),
        java.lang.Double.longBitsToDouble(bits[1]))
    private fun offsetModel(size: Int, index: Long, scalarOffset: Boolean): Int? {
        val offset = BigInteger.valueOf(index).multiply(BigInteger.valueOf(if (scalarOffset) 8 else 16))
        return if (offset.signum() < 0 || offset.add(BigInteger.valueOf(16)) > BigInteger.valueOf(size.toLong())) null
            else offset.intValueExact()
    }
    private fun finiteBytes(size: Int) = ByteArray(size) { byte ->
        val bits = (byte / 8 * 0x0123456789abcdefL + 0x8123456789abcdefUL.toLong()) and 0xbfffffffffffffffUL.toLong()
        (bits ushr shift(byte % 8)).toByte()
    }

    @Test fun fullWidthBoundsAndNoPartialEffectsMatchIndependentByteModels() {
        val indices = ((-2L..12L).toList() + listOf(
            Long.MIN_VALUE, Long.MIN_VALUE + 1, Long.MAX_VALUE - 1, Long.MAX_VALUE,
            Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong() - 1, Int.MAX_VALUE.toLong(), Int.MAX_VALUE.toLong() + 1,
            (1L shl 32) - 1, 1L shl 32, (1L shl 32) + 1) +
            listOf(60, 61, 62).flatMap { power -> (-1L..1L).map { (1L shl power) + it } } +
            listOf(8, 16).flatMap { stride -> (-1L..1L).flatMap {
                listOf(Int.MAX_VALUE.toLong() / stride + it, Long.MAX_VALUE / stride + it, Long.MIN_VALUE / stride + it)
            } }).distinct()
        val values = longArrayOf(Long.MIN_VALUE, 0x7ff8123456789abcL)
        for (size in 0..40) for (scalarOffset in listOf(false, true)) for (index in indices) {
            val bytes = finiteBytes(size); val before = bytes.copyOf()
            val offset = offsetModel(size, index, scalarOffset)
            val label = "size=$size/index=$index/scalar=$scalarOffset"
            if (offset == null) {
                val read = assertThrows(RuntimeFault::class.java, { DoubleX2.readArray(bytes, index, scalarOffset) }, label)
                assertEquals("DoubleX2 ByteArray# range outside its backing storage", read.message, label)
                assertArrayEquals(before, bytes, "failed read $label")
                val write = assertThrows(RuntimeFault::class.java, { DoubleX2.writeArray(bytes, index, packed(values), scalarOffset) }, label)
                assertEquals("DoubleX2 ByteArray# range outside its backing storage", write.message, label)
                assertArrayEquals(before, bytes, "no partial write $label")
            } else {
                assertArrayEquals(loadModel(before, offset), lanes(DoubleX2.readArray(bytes, index, scalarOffset)), label)
                assertArrayEquals(before, bytes, "read preserved storage $label")
                val expected = before.copyOf(); storeModel(expected, offset, values)
                DoubleX2.writeArray(bytes, index, packed(values), scalarOffset)
                assertArrayEquals(expected, bytes, "whole vector and sentinel neighbours $label")
            }
        }
    }

    @Test fun immutableVectorRetainsFiniteSpecialAndQuietNaNBitsAtBothOffsetUnits() {
        val fields = DoubleX2::class.java.declaredFields
        assertEquals(1, fields.size)
        assertTrue(fields.single().type == DoubleVector::class.java && Modifier.isPrivate(fields.single().modifiers)
            && Modifier.isFinal(fields.single().modifiers) && !Modifier.isStatic(fields.single().modifiers))
        val seeds = longArrayOf(0, Long.MIN_VALUE, 1, Long.MIN_VALUE + 1,
            0x000fffffffffffffL, 0x800fffffffffffffUL.toLong(), 0x0010000000000000L, 0x8010000000000000UL.toLong(),
            0x3ff0000000000000L, 0xbff0000000000000UL.toLong(), 0x4025000000000000L, 0xc02b000000000000UL.toLong(),
            0x7fefffffffffffffL, 0xffefffffffffffffUL.toLong(), 0x7ff0000000000000L, 0xfff0000000000000UL.toLong(),
            0x7ff8000000000001L, 0x7ff8123456789abcL, 0x7fffffffffffffffL,
            0xfff8000000000001UL.toLong(), 0xfff8123456789abcUL.toLong(), -1)
        val offsets = (0L..2L).map { false to it } + (0L..4L).map { true to it }
        for ((scalarOffset, index) in offsets) for (rotation in seeds.indices) {
            val values = LongArray(2) { seeds[(rotation + it * 5) % seeds.size] }
            val offset = offsetModel(48, index, scalarOffset)!!
            val source = ByteArray(48) { 0x5a }; storeModel(source, offset, values)
            val before = source.copyOf(); val loaded = DoubleX2.readArray(source, index, scalarOffset)
            assertArrayEquals(values, lanes(loaded))
            assertArrayEquals(before, source, "load does not mutate source")
            val expected = ByteArray(48) { 0x5a }; storeModel(expected, offset, values)
            val fromPack = ByteArray(48) { 0x5a }; DoubleX2.writeArray(fromPack, index, packed(values), scalarOffset)
            assertArrayEquals(expected, fromPack)
            val fromLoad = ByteArray(48) { 0x5a }; DoubleX2.writeArray(fromLoad, index, loaded, scalarOffset)
            assertArrayEquals(expected, fromLoad, "quiet NaN byte transport is not floating arithmetic")
        }
    }

    @Test fun byteAliasesAndOverlappingStoresPreserveLoadedFloatingSnapshots() {
        val bytes = finiteBytes(48); val alias = bytes
        val captured = DoubleX2.readArray(bytes, 1, true); val original = loadModel(bytes, 8)
        assertArrayEquals(lanes(DoubleX2.readArray(alias, 1, false)), lanes(DoubleX2.readArray(bytes, 2, true)))
        for (byte in 0..15) {
            // Flip one bit per byte; the cleared exponent bit in finiteBytes stays clear.
            alias[8 + byte] = (alias[8 + byte].toInt() xor 1).toByte()
            assertArrayEquals(loadModel(bytes, 8), lanes(DoubleX2.readArray(alias, 1, true)))
            assertArrayEquals(original, lanes(captured), "immutable snapshot after byte $byte mutation")
        }
        val expected = bytes.copyOf(); storeModel(expected, 16, original)
        DoubleX2.writeArray(alias, 2, captured, true)
        assertArrayEquals(expected, bytes, "overlap uses retained vector snapshot")
        for (index in 0L..4L) assertArrayEquals(loadModel(expected, (index * 8).toInt()), lanes(DoubleX2.readArray(bytes, index, true)))
        for (index in 0L..2L) assertArrayEquals(loadModel(expected, (index * 16).toInt()), lanes(DoubleX2.readArray(alias, index, false)))
    }

    @Test fun selectedSignalingNaNByteMovementIsReportedAsPlatformQualifiedObservation() {
        // Never extract/pack scalar floating lanes here: scalar copies are allowed to quiet sNaNs.
        // This diagnostic is deliberately separate from portable finite/quiet-NaN assertions above.
        val seeds = longArrayOf(0x7ff0000000000001L, 0x7ff123456789abcdL,
            0xfff0000000000001UL.toLong(), 0xfff123456789abcdUL.toLong())
        for ((scalarOffset, index) in listOf(false to 1L, true to 1L, true to 3L)) for (rotation in seeds.indices) {
            val offset = offsetModel(40, index, scalarOffset)!!
            val source = ByteArray(40) { 0x35 }; storeModel(source, offset, LongArray(2) { seeds[(rotation + it) % seeds.size] })
            val before = source.copyOf(); val target = ByteArray(40) { 0x35 }
            DoubleX2.writeArray(target, index, DoubleX2.readArray(source, index, scalarOffset), scalarOffset)
            assertArrayEquals(before, source)
            assertArrayEquals(before.copyOfRange(0, offset), target.copyOfRange(0, offset))
            assertArrayEquals(before.copyOfRange(offset + 16, 40), target.copyOfRange(offset + 16, 40))
            val output = loadModel(target, offset)
            assertTrue(output.all { (it and 0x7ff0000000000000L) == 0x7ff0000000000000L && (it and 0x000fffffffffffffL) != 0L })
            println("DoubleX2 selected sNaN byte movement: arch=${System.getProperty("os.arch")}, " +
                "java=${System.getProperty("java.version")}, nativeOrder=${ByteOrder.nativeOrder()}, scalar=$scalarOffset, index=$index, " +
                "input=${loadModel(source, offset).joinToString { java.lang.Long.toUnsignedString(it, 16) }}, " +
                "output=${output.joinToString { java.lang.Long.toUnsignedString(it, 16) }}, exact=${source.contentEquals(target)}; " +
                "platform observation only, not a scalar pack/unpack or arithmetic guarantee")
        }
    }
}
