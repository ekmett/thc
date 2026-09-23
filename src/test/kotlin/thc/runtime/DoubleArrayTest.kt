package thc.runtime

import com.oracle.truffle.api.Truffle
import com.oracle.truffle.api.frame.FrameDescriptor
import com.oracle.truffle.api.frame.FrameSlotKind
import com.oracle.truffle.api.frame.VirtualFrame
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.ByteOrder

class DoubleArrayTest {
    @Test fun rawMovementPreservesDefinedBitsInSharedByteStorage() {
        val bits = listOf(0L, Long.MIN_VALUE, 1L, Long.MIN_VALUE+1, 0x000fffffffffffffL,
            0x0010000000000000L, 0x3ff0000000000000L, 0x7fefffffffffffffL,
            0x7ff0000000000000L, -0x0010000000000000L, 0x7ff8000000001234L, -0x0007ffffffffa988L)
        val bytes = ByteArray(bits.size * 8 + 7) { 91 }
        for ((index, value) in bits.withIndex()) {
            ManagedIntArray.write(bytes, index.toLong(), value)
            val number = ManagedDoubleArray.read(bytes, index.toLong())
            assertEquals(value, number.toRawBits())
            ManagedDoubleArray.write(bytes, index.toLong(), number)
            assertEquals(value, ManagedIntArray.read(bytes, index.toLong()))
            for (byte in 0..7) {
                val shift = (if (ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN) byte else 7-byte)*8
                assertEquals((value ushr shift) and 255, ManagedByteArray.read(bytes, index*8L+byte))
            }
        }
        for (byte in bits.size*8 until bytes.size) assertEquals(91, bytes[byte].toInt())
        assertSame(bytes, ManagedByteArray.freeze(bytes))
        val other = ByteArray(8)
        ManagedDoubleArray.write(other, 0, 3.5)
        assertEquals(3.5.toRawBits(), ManagedIntArray.read(other, 0))
        assertEquals(0L, ManagedIntArray.read(bytes, 0))
    }

    @Test fun boundsRejectIncompleteElementsAndIndexOverflowWithoutWrites() {
        for (size in listOf(0, 1, 7, 8, 9, 15, 16, 17, 31)) {
            val bytes = ByteArray(size) { 37 }
            for (index in listOf(Long.MIN_VALUE, -1L, (size/8).toLong(), 1L shl 32, 1L shl 61, Long.MAX_VALUE)) {
                val before = bytes.copyOf()
                assertThrows(RuntimeFault::class.java) { ManagedDoubleArray.read(bytes, index) }
                assertThrows(RuntimeFault::class.java) { ManagedDoubleArray.write(bytes, index, -0.0) }
                assertArrayEquals(before, bytes)
            }
        }
    }

    @Test fun statePrecedesMemoryAccessAndOnlySuccessfulReadsPublishPrimitiveDouble() {
        val descriptor = FrameDescriptor.newBuilder()
        val slot = descriptor.addSlot(FrameSlotKind.Double, "result", null)
        val frame = Truffle.getRuntime().createVirtualFrame(emptyArray(), descriptor.build())
        val bytes = ByteArray(8)
        ManagedDoubleArray.write(bytes, 0, 7.5)
        val events = mutableListOf<String>()
        fun operand(name: String, action: () -> Any?) = object : Expr() {
            override fun execute(frame: VirtualFrame): Any? { events.add(name); return action() }
        }
        val read = byteArrayExpression(ByteArrayOp.READ_DOUBLE, CoreRepresentation.UNKNOWN, arrayOf(
            operand("array") { bytes }, operand("index") { 0L },
            operand("state") { ManagedDoubleArray.write(bytes, 0, -0.0); Unit }))
        read.executeTuple(frame, intArrayOf(slot), 0)
        assertEquals(listOf("array", "index", "state"), events)
        assertTrue(frame.isDouble(slot)); assertEquals(Long.MIN_VALUE, frame.getDouble(slot).toRawBits())
        events.clear()
        val quiet = Double.fromBits(0x7ff8000000001234L)
        val write = byteArrayExpression(ByteArrayOp.WRITE_DOUBLE, CoreRepresentation.UNKNOWN, arrayOf(
            operand("array") { bytes }, operand("index") { 0L }, operand("value") { quiet },
            operand("state") { assertEquals(Long.MIN_VALUE, ManagedIntArray.read(bytes, 0)); Unit }))
        assertSame(Unit, write.execute(frame))
        assertEquals(listOf("array", "index", "value", "state"), events)
        assertEquals(quiet.toRawBits(), ManagedIntArray.read(bytes, 0))
        for (badState in listOf<() -> Any?>({ throw RuntimeFault("state failed") }, { 0L })) {
            frame.setDouble(slot, -0.0)
            val failedRead = byteArrayExpression(ByteArrayOp.READ_DOUBLE, CoreRepresentation.UNKNOWN, arrayOf(
                operand("array") { bytes }, operand("index") { 0L }, operand("state", badState)))
            assertThrows(RuntimeFault::class.java) { failedRead.executeTuple(frame, intArrayOf(slot), 0) }
            assertEquals(Long.MIN_VALUE, frame.getDouble(slot).toRawBits())
            val failedWrite = byteArrayExpression(ByteArrayOp.WRITE_DOUBLE, CoreRepresentation.UNKNOWN, arrayOf(
                operand("array") { bytes }, operand("index") { 0L }, operand("value") { 99.0 }, operand("state", badState)))
            assertThrows(RuntimeFault::class.java) { failedWrite.execute(frame) }
            assertEquals(quiet.toRawBits(), ManagedIntArray.read(bytes, 0))
        }
    }
}
