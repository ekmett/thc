// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.Truffle
import com.oracle.truffle.api.frame.FrameDescriptor
import com.oracle.truffle.api.frame.FrameSlotKind
import com.oracle.truffle.api.frame.VirtualFrame
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.ByteOrder

class FloatArrayTest {
    @Test fun rawFloatMovementUsesFourSharedBytesWithoutNumericalConversion() {
        val bits = listOf(0, Int.MIN_VALUE, 1, Int.MIN_VALUE+1, 0x007fffff, 0x00800000,
            0x3f800000, 0x7f7fffff, 0x7f800000, -0x00800000, 0x7fc01234, -0x003fa988)
        val bytes = ByteArray(bits.size*4+3) { 91 }
        for ((index, value) in bits.withIndex()) {
            ByteArrayAccess.writeInt32(bytes, index.toLong(), value.toLong())
            val number = ByteArrayAccess.readFloat(bytes, index.toLong())
            assertEquals(value, number.toRawBits())
            ByteArrayAccess.writeFloat(bytes, index.toLong(), number)
            assertEquals(value.toLong() and 0xffff_ffffL, ByteArrayAccess.readWord32(bytes, index.toLong()))
            for (byte in 0..3) {
                val shift = (if (ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN) byte else 3-byte)*8
                assertEquals(((value ushr shift) and 255).toLong(), ManagedByteArray.read(bytes, index*4L+byte))
            }
        }
        for (byte in bits.size*4 until bytes.size) assertEquals(91, bytes[byte].toInt())
        assertSame(bytes, ManagedByteArray.freeze(bytes))
        val other = ByteArray(8)
        ByteArrayAccess.writeFloat(other, 0, -0.0f)
        ByteArrayAccess.writeFloat(other, 1, 3.5f)
        val expected = if (ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN)
            (3.5f.toRawBits().toLong() shl 32) or 0x8000_0000L
            else Long.MIN_VALUE or 3.5f.toRawBits().toLong()
        assertEquals(expected, ByteArrayAccess.readInt(other, 0))
        assertEquals(0L, ByteArrayAccess.readWord32(bytes, 0))
        // Mutate two bytes across the Float element boundary using mutable storage.
        ManagedByteArray.write(other, 3, 0x41)
        ManagedByteArray.write(other, 4, 0x23)
        for (element in 0..1) {
            val expectedBits = ByteArrayAccess.readWord32(other, element.toLong()).toInt()
            assertEquals(expectedBits, ByteArrayAccess.readFloat(other, element.toLong()).toRawBits())
        }
    }

    @Test fun boundsExcludePartialElementsAndHugeIndicesBeforeAnyWrite() {
        for (size in (0..9).toList() + listOf(11, 12, 13, 15, 16, 17, 31)) {
            val bytes = ByteArray(size) { 37 }
            for (index in listOf(Long.MIN_VALUE, -1L, (size/4).toLong(), 1L shl 32, 1L shl 62, Long.MAX_VALUE)) {
                val before = bytes.copyOf()
                assertThrows(RuntimeFault::class.java) { ByteArrayAccess.readFloat(bytes, index) }
                assertThrows(RuntimeFault::class.java) { ByteArrayAccess.writeFloat(bytes, index, -0.0f) }
                assertArrayEquals(before, bytes)
            }
        }
    }

    @Test fun statePrecedesAccessAndOnlySuccessfulReadsPublishPrimitiveFloat() {
        val descriptor = FrameDescriptor.newBuilder()
        val slot = descriptor.addSlot(FrameSlotKind.Float, "result", null)
        val frame = Truffle.getRuntime().createVirtualFrame(emptyArray(), descriptor.build())
        val bytes = ByteArray(4)
        ByteArrayAccess.writeFloat(bytes, 0, 7.5f)
        val events = mutableListOf<String>()
        fun operand(name: String, action: () -> Any?) = object : Expr() {
            override fun execute(frame: VirtualFrame): Any? { events.add(name); return action() }
        }
        val read = byteArrayExpression(ByteArrayOp.READ_FLOAT, CoreRepresentation.UNKNOWN, arrayOf(
            operand("array") { bytes }, operand("index") { 0L },
            operand("state") { ByteArrayAccess.writeFloat(bytes, 0, -0.0f); Unit }))
        read.executeTuple(frame, intArrayOf(slot), 0)
        assertEquals(listOf("array", "index", "state"), events)
        assertTrue(frame.isFloat(slot)); assertEquals(Int.MIN_VALUE, frame.getFloat(slot).toRawBits())
        events.clear()
        val quiet = Float.fromBits(0x7fc01234)
        val write = byteArrayExpression(ByteArrayOp.WRITE_FLOAT, CoreRepresentation.UNKNOWN, arrayOf(
            operand("array") { bytes }, operand("index") { 0L }, operand("value") { quiet },
            operand("state") { assertEquals(0x8000_0000L, ByteArrayAccess.readWord32(bytes, 0)); Unit }))
        assertSame(Unit, write.execute(frame))
        assertEquals(listOf("array", "index", "value", "state"), events)
        assertEquals(quiet.toRawBits().toLong(), ByteArrayAccess.readWord32(bytes, 0))
        for (badState in listOf<() -> Any?>({ throw RuntimeFault("state failed") }, { 0L })) {
            frame.setFloat(slot, -0.0f)
            val failedRead = byteArrayExpression(ByteArrayOp.READ_FLOAT, CoreRepresentation.UNKNOWN, arrayOf(
                operand("array") { bytes }, operand("index") { 0L }, operand("state", badState)))
            assertThrows(RuntimeFault::class.java) { failedRead.executeTuple(frame, intArrayOf(slot), 0) }
            assertEquals(Int.MIN_VALUE, frame.getFloat(slot).toRawBits())
            val failedWrite = byteArrayExpression(ByteArrayOp.WRITE_FLOAT, CoreRepresentation.UNKNOWN, arrayOf(
                operand("array") { bytes }, operand("index") { 0L }, operand("value") { 99.0f }, operand("state", badState)))
            assertThrows(RuntimeFault::class.java) { failedWrite.execute(frame) }
            assertEquals(quiet.toRawBits().toLong(), ByteArrayAccess.readWord32(bytes, 0))
        }
    }
}
