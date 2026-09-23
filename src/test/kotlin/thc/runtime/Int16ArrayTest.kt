package thc.runtime

import com.oracle.truffle.api.Truffle
import com.oracle.truffle.api.frame.FrameDescriptor
import com.oracle.truffle.api.frame.FrameSlotKind
import com.oracle.truffle.api.frame.VirtualFrame
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.ByteOrder

class Int16ArrayTest {
    private fun unsigned(value: Long) = value and 0xffffL
    private fun signed(value: Long): Long {
        val bits = unsigned(value)
        return if (bits >= 0x8000L) bits - 0x1_0000L else bits
    }

    @Test fun twoByteStorageNarrowsAndWidensWithoutChangingIdentityOrAdjacentBytes() {
        val values = listOf(Long.MIN_VALUE, Long.MAX_VALUE, -1L, 0L, 1L, 0x7fffL,
            0x8000L, 0xffffL, 0x1_0001L, -0x1_0001L, 0x0123456789abcdefL)
        val bytes = ByteArray(values.size * 2 + 1) { 91 }
        for ((index, value) in values.withIndex()) ManagedInt16Array.write(bytes, index.toLong(), value)
        for ((index, value) in values.withIndex()) {
            assertEquals(signed(value), ManagedInt16Array.readSigned(bytes, index.toLong()))
            assertEquals(unsigned(value), ManagedInt16Array.readUnsigned(bytes, index.toLong()))
            for (byte in 0..1) {
                val shift = (if (ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN) byte else 1-byte) * 8
                assertEquals((value ushr shift) and 255, ManagedByteArray.read(bytes, index*2L+byte))
            }
        }
        for (byte in values.size*2 until bytes.size) assertEquals(91, bytes[byte].toInt())
        assertSame(bytes, ManagedByteArray.freeze(bytes))
        val other = ByteArray(2)
        ManagedInt16Array.write(other, 0, 73)
        assertEquals(0L, ManagedInt16Array.readUnsigned(bytes, 0))
        assertEquals(73L, ManagedInt16Array.readSigned(other, 0))
    }

    @Test fun byteAliasesAcrossOneAndTwoAndWideViewsShareTheSameStorage() {
        val bytes = ByteArray(8)
        ManagedInt16Array.write(bytes, 0, 0x80abL)
        ManagedInt16Array.write(bytes, 1, 0xfedcL)
        ManagedByteArray.write(bytes, 1, 0x91)
        ManagedByteArray.write(bytes, 2, 0xa2)
        fun expected(offset: Int, count: Int): Long {
            var bits = 0L
            for (byte in 0 until count) {
                val shift = (if (ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN) byte else count-1-byte) * 8
                bits = bits or ((bytes[offset+byte].toLong() and 255) shl shift)
            }
            return bits
        }
        for (element in 0..1) {
            val bits = expected(element*2, 2)
            assertEquals(bits, ManagedInt16Array.readUnsigned(bytes, element.toLong()))
            assertEquals(signed(bits), ManagedInt16Array.readSigned(bytes, element.toLong()))
        }
        assertEquals(expected(0, 8), ManagedIntArray.read(bytes, 0))
        ManagedDoubleArray.write(bytes, 0, -0.0)
        assertEquals(Long.MIN_VALUE, ManagedIntArray.read(bytes, 0))
        assertEquals(if (ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN) 0L else 0x8000L,
            ManagedInt16Array.readUnsigned(bytes, 0))
        val copied = ByteArray(8)
        ManagedByteArray.copy(ManagedByteArray.freeze(bytes), 0, copied, 0, 8)
        assertArrayEquals(bytes, copied)
        ManagedInt16Array.write(copied, 0, 73)
        assertNotEquals(73L, ManagedInt16Array.readSigned(bytes, 0))
    }

    @Test fun fullWidthBoundsExcludePartialElementsBeforeScalingOrWriting() {
        for (size in (0..9).toList() + listOf(11, 12, 13, 15, 16, 17, 31)) {
            val bytes = ByteArray(size) { 37 }
            for (index in listOf(Long.MIN_VALUE, -1L, (size/2).toLong(), Int.MAX_VALUE.toLong(),
                1L shl 32, 1L shl 62, Long.MAX_VALUE)) {
                val before = bytes.copyOf()
                assertThrows(RuntimeFault::class.java) { ManagedInt16Array.readSigned(bytes, index) }
                assertThrows(RuntimeFault::class.java) { ManagedInt16Array.readUnsigned(bytes, index) }
                assertThrows(RuntimeFault::class.java) { ManagedInt16Array.write(bytes, index, Long.MIN_VALUE) }
                assertArrayEquals(before, bytes, "size=$size/index=$index")
            }
            if (size >= 2) {
                val last = (size/2-1).toLong()
                ManagedInt16Array.write(bytes, last, 0x8000L)
                assertEquals(-0x8000L, ManagedInt16Array.readSigned(bytes, last))
                assertEquals(0x8000L, ManagedInt16Array.readUnsigned(bytes, last))
            }
        }
    }

    @Test fun statePrecedesAccessAndFailedReadsNeverPublish() {
        val descriptor = FrameDescriptor.newBuilder()
        val slot = descriptor.addSlot(FrameSlotKind.Long, "result", null)
        val frame = Truffle.getRuntime().createVirtualFrame(emptyArray(), descriptor.build())
        for (unsigned in listOf(false, true)) {
            val readOp = if (unsigned) ByteArrayOp.READ_WORD16 else ByteArrayOp.READ_INT16
            val writeOp = if (unsigned) ByteArrayOp.WRITE_WORD16 else ByteArrayOp.WRITE_INT16
            val bytes = ByteArray(2)
            ManagedInt16Array.write(bytes, 0, 7)
            val events = mutableListOf<String>()
            fun operand(name: String, action: () -> Any?) = object : Expr() {
                override fun execute(frame: VirtualFrame): Any? { events.add(name); return action() }
            }
            val read = byteArrayExpression(readOp, CoreRepresentation.UNKNOWN, arrayOf(
                operand("array") { bytes }, operand("index") { 0L },
                operand("state") { ManagedInt16Array.write(bytes, 0, 0x8000L); Unit }))
            read.executeTuple(frame, intArrayOf(slot), 0)
            assertEquals(listOf("array", "index", "state"), events)
            assertTrue(frame.isLong(slot))
            assertEquals(if (unsigned) 0x8000L else -0x8000L, frame.getLong(slot))
            events.clear()
            val write = byteArrayExpression(writeOp, CoreRepresentation.UNKNOWN, arrayOf(
                operand("array") { bytes }, operand("index") { 0L }, operand("value") { 0x1_ffffL },
                operand("state") { assertEquals(0x8000L, ManagedInt16Array.readUnsigned(bytes, 0)); Unit }))
            assertSame(Unit, write.execute(frame))
            assertEquals(listOf("array", "index", "value", "state"), events)
            assertEquals(0xffffL, ManagedInt16Array.readUnsigned(bytes, 0))
            for (badState in listOf<() -> Any?>({ throw RuntimeFault("state failed") }, { 0L })) {
                frame.setLong(slot, 73)
                val failedRead = byteArrayExpression(readOp, CoreRepresentation.UNKNOWN, arrayOf(
                    operand("array") { bytes }, operand("index") { 0L }, operand("state", badState)))
                assertThrows(RuntimeFault::class.java) { failedRead.executeTuple(frame, intArrayOf(slot), 0) }
                assertEquals(73L, frame.getLong(slot))
                val failedWrite = byteArrayExpression(writeOp, CoreRepresentation.UNKNOWN, arrayOf(
                    operand("array") { bytes }, operand("index") { 0L }, operand("value") { 99L }, operand("state", badState)))
                assertThrows(RuntimeFault::class.java) { failedWrite.execute(frame) }
                assertEquals(0xffffL, ManagedInt16Array.readUnsigned(bytes, 0))
            }
            frame.setLong(slot, 73)
            val badIndexRead = byteArrayExpression(readOp, CoreRepresentation.UNKNOWN, arrayOf(
                operand("array") { bytes }, operand("index") { 1L }, operand("state") { Unit }))
            assertThrows(RuntimeFault::class.java) { badIndexRead.executeTuple(frame, intArrayOf(slot), 0) }
            assertEquals(73L, frame.getLong(slot))
            // Bytecode specializations likewise validate State before any access.
            assertThrows(RuntimeFault::class.java) { BytecodeRoot.WriteInt16Array.write(bytes, 0, 99, 0L) }
            assertEquals(0xffffL, ManagedInt16Array.readUnsigned(bytes, 0))
        }
    }

    @Test fun everySixteenBitPatternHasIndependentSignedAndUnsignedResults() {
        val bytes = ByteArray(2)
        for (bits in 0L..65535L) {
            ManagedInt16Array.write(bytes, 0, bits or (0x12345678L shl 32))
            assertEquals(bits, ManagedInt16Array.readUnsigned(bytes, 0))
            assertEquals(if (bits < 32768) bits else bits-65536, ManagedInt16Array.readSigned(bytes, 0))
        }
    }
}
