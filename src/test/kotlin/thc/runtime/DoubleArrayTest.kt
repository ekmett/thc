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

class DoubleArrayTest {
    @Test fun rawMovementPreservesDefinedBitsInSharedByteStorage() {
        val bits = listOf(0L, Long.MIN_VALUE, 1L, Long.MIN_VALUE+1, 0x000fffffffffffffL,
            0x0010000000000000L, 0x3ff0000000000000L, 0x7fefffffffffffffL,
            0x7ff0000000000000L, -0x0010000000000000L, 0x7ff8000000001234L, -0x0007ffffffffa988L)
        val bytes = ByteArray(bits.size * 8 + 7) { 91 }
        for ((index, value) in bits.withIndex()) {
            ByteArrayAccess.writeInt(bytes, index.toLong(), value)
            val number = ByteArrayAccess.readDouble(bytes, index.toLong())
            assertEquals(value, number.toRawBits())
            ByteArrayAccess.writeDouble(bytes, index.toLong(), number)
            assertEquals(value, ByteArrayAccess.readInt(bytes, index.toLong()))
            for (byte in 0..7) {
                val shift = (if (ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN) byte else 7-byte)*8
                assertEquals((value ushr shift) and 255, ManagedByteArray.read(bytes, index*8L+byte))
            }
        }
        for (byte in bits.size*8 until bytes.size) assertEquals(91, bytes[byte].toInt())
        assertSame(bytes, ManagedByteArray.freeze(bytes))
        val other = ByteArray(8)
        ByteArrayAccess.writeDouble(other, 0, 3.5)
        assertEquals(3.5.toRawBits(), ByteArrayAccess.readInt(other, 0))
        assertEquals(0L, ByteArrayAccess.readInt(bytes, 0))
    }

    @Test fun boundsRejectIncompleteElementsAndIndexOverflowWithoutWrites() {
        for (size in listOf(0, 1, 7, 8, 9, 15, 16, 17, 31)) {
            val bytes = ByteArray(size) { 37 }
            for (index in listOf(Long.MIN_VALUE, -1L, (size/8).toLong(), 1L shl 32, 1L shl 61, Long.MAX_VALUE)) {
                val before = bytes.copyOf()
                assertThrows(RuntimeFault::class.java) { ByteArrayAccess.readDouble(bytes, index) }
                assertThrows(RuntimeFault::class.java) { ByteArrayAccess.writeDouble(bytes, index, -0.0) }
                assertArrayEquals(before, bytes)
            }
        }
    }

    @Test fun ownedDoubleBoundsPreserveBytesAndRespectShrunkLogicalSize() {
        for (size in listOf(0L, 7L, 8L, 15L, 16L)) {
            val array = ManagedByteArray.allocateGuest(size + 8)
            ManagedByteArray.fillGuest(array, 0, size + 8, 37)
            // The backing still contains a complete extra Double. Both mutable
            // reads and the frozen alias must honor the owner's logical length.
            val frozen = ManagedByteArray.freezeGuest(array)
            ManagedByteArray.shrinkGuest(array, size)
            assertSame(array, frozen)
            val expected = (0 until size).map { 37L }
            val invalid = listOf(Long.MIN_VALUE to "element", -1L to "element", (size / 8) to "range",
                (1L shl 32) to "range", (1L shl 61) to "element", Long.MAX_VALUE to "element")
            for ((index, guard) in invalid) {
                val read = assertThrows(RuntimeFault::class.java) { ManagedByteArray.readDoubleGuest(array, index) }
                val indexRead = assertThrows(RuntimeFault::class.java) { ManagedByteArray.readDoubleGuest(frozen, index) }
                val write = assertThrows(RuntimeFault::class.java) { ManagedByteArray.writeDoubleGuest(array, index, -0.0) }
                for (failure in listOf(read, indexRead, write))
                    assertEquals("Managed allocation $guard outside its backing storage", failure.message,
                        "size=$size/index=$index")
                assertEquals(size, ManagedByteArray.sizeGuest(array))
                assertEquals(expected, (0 until size).map { ManagedByteArray.readGuest(array, it, true) })
            }
            if (size >= 8) {
                ManagedByteArray.writeDoubleGuest(array, 0, -0.0)
                assertEquals(Long.MIN_VALUE, ManagedByteArray.readDoubleGuest(frozen, 0).toRawBits())
            }
        }
    }

    @Test fun statePrecedesMemoryAccessAndOnlySuccessfulReadsPublishPrimitiveDouble() {
        val descriptor = FrameDescriptor.newBuilder()
        val slot = descriptor.addSlot(FrameSlotKind.Double, "result", null)
        val frame = Truffle.getRuntime().createVirtualFrame(emptyArray(), descriptor.build())
        val bytes = ByteArray(8)
        ByteArrayAccess.writeDouble(bytes, 0, 7.5)
        val events = mutableListOf<String>()
        fun operand(name: String, action: () -> Any?) = object : Expr() {
            override fun execute(frame: VirtualFrame): Any? { events.add(name); return action() }
        }
        val read = byteArrayExpression(ByteArrayOp.READ_DOUBLE, CoreRepresentation.UNKNOWN, arrayOf(
            operand("array") { bytes }, operand("index") { 0L },
            operand("state") { ByteArrayAccess.writeDouble(bytes, 0, -0.0); Unit }))
        read.executeTuple(frame, intArrayOf(slot), 0)
        assertEquals(listOf("array", "index", "state"), events)
        assertTrue(frame.isDouble(slot)); assertEquals(Long.MIN_VALUE, frame.getDouble(slot).toRawBits())
        events.clear()
        val quiet = Double.fromBits(0x7ff8000000001234L)
        val write = byteArrayExpression(ByteArrayOp.WRITE_DOUBLE, CoreRepresentation.UNKNOWN, arrayOf(
            operand("array") { bytes }, operand("index") { 0L }, operand("value") { quiet },
            operand("state") { assertEquals(Long.MIN_VALUE, ByteArrayAccess.readInt(bytes, 0)); Unit }))
        assertSame(Unit, write.execute(frame))
        assertEquals(listOf("array", "index", "value", "state"), events)
        assertEquals(quiet.toRawBits(), ByteArrayAccess.readInt(bytes, 0))
        for (badState in listOf<() -> Any?>({ throw RuntimeFault("state failed") }, { 0L })) {
            frame.setDouble(slot, -0.0)
            val failedRead = byteArrayExpression(ByteArrayOp.READ_DOUBLE, CoreRepresentation.UNKNOWN, arrayOf(
                operand("array") { bytes }, operand("index") { 0L }, operand("state", badState)))
            assertThrows(RuntimeFault::class.java) { failedRead.executeTuple(frame, intArrayOf(slot), 0) }
            assertEquals(Long.MIN_VALUE, frame.getDouble(slot).toRawBits())
            val failedWrite = byteArrayExpression(ByteArrayOp.WRITE_DOUBLE, CoreRepresentation.UNKNOWN, arrayOf(
                operand("array") { bytes }, operand("index") { 0L }, operand("value") { 99.0 }, operand("state", badState)))
            assertThrows(RuntimeFault::class.java) { failedWrite.execute(frame) }
            assertEquals(quiet.toRawBits(), ByteArrayAccess.readInt(bytes, 0))
        }
    }
}
