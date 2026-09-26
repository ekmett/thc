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

class WordArrayTest {
    @Test fun machineWordRawBitsAliasBytesWithoutTruncatingTheHighHalf() {
        val values = listOf(Long.MIN_VALUE, Long.MAX_VALUE, -1L, 0L, 1L,
            0x0123456789abcdefL, -0x0123456789abcdefL)
        val bytes = ManagedByteArray.allocate(values.size * 8L + 7)
        // Explicitly initialize every byte used below, including the partial tail.
        bytes.fill(91)
        values.forEachIndexed { index, value -> ManagedByteArray.writeInt(bytes, index.toLong(), value) }
        val frozen = ManagedByteArray.freeze(bytes)
        assertSame(bytes, frozen)
        for ((index, value) in values.withIndex()) {
            assertEquals(value, ManagedByteArray.readInt(frozen, index.toLong()))
            for (byte in 0..7) {
                val shift = (if (ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN) byte else 7 - byte) * 8
                assertEquals((value ushr shift) and 255, ManagedByteArray.read(frozen, index * 8L + byte))
            }
        }
        for (byte in values.size * 8 until bytes.size) assertEquals(91L, ManagedByteArray.read(bytes, byte.toLong()))
        val separate = ManagedByteArray.allocate(8)
        ManagedByteArray.writeInt(separate, 0, 73)
        // Use mutable storage only: no mutation through an unsafe-frozen alias.
        for (byte in 0..7) ManagedByteArray.write(separate, byte.toLong(), 128L + byte)
        var expected = 0L
        for (byte in 0..7) {
            val shift = (if (ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN) byte else 7 - byte) * 8
            expected = expected or ((128L + byte) shl shift)
        }
        assertEquals(expected, ManagedByteArray.readInt(separate, 0))
        assertEquals(values[0], ManagedByteArray.readInt(frozen, 0))
    }

    @Test fun elementBoundsRejectPartialWordsAndOverflowBeforeAnyWrite() {
        for (size in listOf(0, 1, 7, 8, 9, 15, 16, 17, 31)) {
            val bytes = ByteArray(size) { 37 }
            for (index in listOf(Long.MIN_VALUE, -1L, (size / 8).toLong(),
                Int.MAX_VALUE.toLong(), 1L shl 32, 1L shl 61, Long.MAX_VALUE)) {
                val before = bytes.copyOf()
                assertThrows(RuntimeFault::class.java) { ManagedByteArray.readInt(bytes, index) }
                assertThrows(RuntimeFault::class.java) { ManagedByteArray.writeInt(bytes, index, Long.MIN_VALUE) }
                assertArrayEquals(before, bytes, "size=$size/index=$index")
            }
        }
    }

    @Test fun stateRunsBeforeReadOrWriteAndFailureDoesNotPublish() {
        val descriptor = FrameDescriptor.newBuilder()
        val slot = descriptor.addSlot(FrameSlotKind.Long, "result", null)
        val frame = Truffle.getRuntime().createVirtualFrame(emptyArray(), descriptor.build())
        val bytes = ByteArray(8)
        ManagedByteArray.writeInt(bytes, 0, 7)
        val events = mutableListOf<String>()
        fun operand(name: String, action: () -> Any?) = object : Expr() {
            override fun execute(frame: VirtualFrame): Any? { events.add(name); return action() }
        }
        val read = byteArrayExpression(ByteArrayOp.READ_WORD, CoreRepresentation.UNKNOWN, arrayOf(
            operand("array") { bytes }, operand("index") { 0L },
            operand("state") { ManagedByteArray.writeInt(bytes, 0, Long.MIN_VALUE); Unit }))
        read.executeTuple(frame, intArrayOf(slot), 0)
        assertEquals(listOf("array", "index", "state"), events)
        assertTrue(frame.isLong(slot))
        assertEquals(Long.MIN_VALUE, frame.getLong(slot))
        events.clear()
        val write = byteArrayExpression(ByteArrayOp.WRITE_WORD, CoreRepresentation.UNKNOWN, arrayOf(
            operand("array") { bytes }, operand("index") { 0L }, operand("value") { Long.MAX_VALUE },
            operand("state") { assertEquals(Long.MIN_VALUE, ManagedByteArray.readInt(bytes, 0)); Unit }))
        assertSame(Unit, write.execute(frame))
        assertEquals(listOf("array", "index", "value", "state"), events)
        assertEquals(Long.MAX_VALUE, ManagedByteArray.readInt(bytes, 0))
        for (badState in listOf<() -> Any?>({ throw RuntimeFault("state failed") }, { 0L })) {
            frame.setLong(slot, 73)
            val failedRead = byteArrayExpression(ByteArrayOp.READ_WORD, CoreRepresentation.UNKNOWN, arrayOf(
                operand("array") { bytes }, operand("index") { 0L }, operand("state", badState)))
            assertThrows(RuntimeFault::class.java) { failedRead.executeTuple(frame, intArrayOf(slot), 0) }
            assertEquals(73L, frame.getLong(slot))
            val failedWrite = byteArrayExpression(ByteArrayOp.WRITE_WORD, CoreRepresentation.UNKNOWN, arrayOf(
                operand("array") { bytes }, operand("index") { 0L }, operand("value") { 99L }, operand("state", badState)))
            assertThrows(RuntimeFault::class.java) { failedWrite.execute(frame) }
            assertEquals(Long.MAX_VALUE, ManagedByteArray.readInt(bytes, 0))
        }
    }
}
