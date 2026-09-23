package thc.runtime

import com.oracle.truffle.api.Truffle
import com.oracle.truffle.api.frame.FrameDescriptor
import com.oracle.truffle.api.frame.FrameSlotKind
import com.oracle.truffle.api.frame.VirtualFrame
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class Int8ArrayTest {
    @Test fun everyBytePatternHasExactSignedAndUnsignedLongResultsAndSharedIdentity() {
        val bytes = ByteArray(258) { 91 }
        for (bits in 0L..255L) {
            ManagedByteArray.write(bytes, bits + 1, bits or (0x12345678L shl 32))
            assertEquals(bits, ManagedByteArray.read(bytes, bits + 1))
            assertEquals(if (bits < 128) bits else bits - 256, ManagedByteArray.readSigned(bytes, bits + 1))
        }
        assertEquals(91, bytes.first().toInt())
        assertEquals(91, bytes.last().toInt())
        assertSame(bytes, ManagedByteArray.freeze(bytes))
        assertEquals(ByteArray::class.java, bytes.javaClass)
        val other = ManagedByteArray.allocate(1)
        ManagedByteArray.write(other, 0, 73)
        assertEquals(91L, ManagedByteArray.read(bytes, 0))
        assertNotSame(bytes, other)
        val copy = ByteArray(bytes.size)
        ManagedByteArray.copy(bytes, 0, copy, 0, bytes.size.toLong())
        assertArrayEquals(bytes, copy)
        ManagedByteArray.write(copy, 1, 255)
        assertEquals(0L, ManagedByteArray.readSigned(bytes, 1))
        assertEquals(-1L, ManagedByteArray.readSigned(copy, 1))
    }

    @Test fun fullWidthBoundsFailBeforeNarrowingOrWritingIncludingEmptyStorage() {
        for (size in listOf(0, 1, 2, 7, 8, 9, 31)) {
            val bytes = ByteArray(size) { 37 }
            for (index in listOf(Long.MIN_VALUE, -1L, size.toLong(), Int.MAX_VALUE.toLong(),
                1L shl 32, 1L shl 62, Long.MAX_VALUE)) {
                val before = bytes.copyOf()
                assertThrows(RuntimeFault::class.java) { ManagedByteArray.readSigned(bytes, index) }
                assertThrows(RuntimeFault::class.java) { ManagedByteArray.read(bytes, index) }
                assertThrows(RuntimeFault::class.java) { ManagedByteArray.write(bytes, index, Long.MIN_VALUE) }
                assertArrayEquals(before, bytes)
            }
            if (size > 0) {
                ManagedByteArray.write(bytes, size - 1L, 0x180)
                assertEquals(-128L, ManagedByteArray.readSigned(bytes, size - 1L))
            }
        }
    }

    @Test fun statePrecedesReadAndWriteAndFailedReadNeverPublishes() {
        val builder = FrameDescriptor.newBuilder()
        val slot = builder.addSlot(FrameSlotKind.Long, "result", null)
        val frame = Truffle.getRuntime().createVirtualFrame(emptyArray(), builder.build())
        for (unsigned in listOf(false, true)) {
            val bytes = byteArrayOf(7)
            val events = mutableListOf<String>()
            fun operand(name: String, action: () -> Any?) = object : Expr() {
                override fun execute(frame: VirtualFrame): Any? {
                    events += name
                    return action()
                }
            }
            val readOp = if (unsigned) ByteArrayOp.READ_WORD8 else ByteArrayOp.READ_INT8
            val read = byteArrayExpression(readOp, CoreRepresentation.UNKNOWN, arrayOf(
                operand("array") { bytes }, operand("index") { 0L },
                operand("state") { ManagedByteArray.write(bytes, 0, 128); Unit }))
            read.executeTuple(frame, intArrayOf(slot), 0)
            assertEquals(listOf("array", "index", "state"), events)
            assertTrue(frame.isLong(slot))
            assertEquals(if (unsigned) 128L else -128L, frame.getLong(slot))
            events.clear()
            val write = byteArrayExpression(ByteArrayOp.WRITE_INT8, CoreRepresentation.UNKNOWN, arrayOf(
                operand("array") { bytes }, operand("index") { 0L }, operand("value") { 511L },
                operand("state") { assertEquals(128L, ManagedByteArray.read(bytes, 0)); Unit }))
            assertSame(Unit, write.execute(frame))
            assertEquals(listOf("array", "index", "value", "state"), events)
            assertEquals(-1L, ManagedByteArray.readSigned(bytes, 0))
            for (badState in listOf<() -> Any?>({ throw RuntimeFault("state failed") }, { 0L })) {
                frame.setLong(slot, 73)
                val badRead = byteArrayExpression(readOp, CoreRepresentation.UNKNOWN, arrayOf(
                    operand("array") { bytes }, operand("index") { 0L }, operand("state", badState)))
                assertThrows(RuntimeFault::class.java) { badRead.executeTuple(frame, intArrayOf(slot), 0) }
                assertEquals(73L, frame.getLong(slot))
                val badWrite = byteArrayExpression(ByteArrayOp.WRITE_INT8, CoreRepresentation.UNKNOWN, arrayOf(
                    operand("array") { bytes }, operand("index") { 0L }, operand("value") { 99L },
                    operand("state", badState)))
                assertThrows(RuntimeFault::class.java) { badWrite.execute(frame) }
                assertEquals(255L, ManagedByteArray.read(bytes, 0))
            }
            assertThrows(RuntimeFault::class.java) { BytecodeRoot.WriteByteArray.write(bytes, 0, 99, 0L) }
            assertEquals(255L, ManagedByteArray.read(bytes, 0))
            // Invalid State must fail before the read or destination publication.
            // Null destination/node deliberately make premature publication fail
            // with the wrong exception instead of silently passing this control.
            assertThrows(RuntimeFault::class.java) {
                BytecodeRoot.ReadByteArray.read(frame, unsigned, null, bytes, 0, 0L, null)
            }
            assertEquals(73L, frame.getLong(slot))
        }
    }
}
