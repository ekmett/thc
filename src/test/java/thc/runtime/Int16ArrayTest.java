// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.FrameSlotKind;
import com.oracle.truffle.api.frame.VirtualFrame;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;
import kotlin.Unit;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static thc.runtime.ByteArrayOp.expression;

class Int16ArrayTest {
    private long unsigned(long value) {
        return value & 0xffffL;
    }
    private long signed(long value) {
        long bits = unsigned(value);
        return bits >= 0x8000L ? bits - 0x1_0000L : bits;
    }
    @Test
    void twoByteStorageNarrowsAndWidensWithoutChangingIdentityOrAdjacentBytes() {
        long[] values = {Long.MIN_VALUE, Long.MAX_VALUE, -1L, 0L, 1L, 0x7fffL, 0x8000L, 0xffffL, 0x1_0001L, -0x1_0001L,
            0x0123456789abcdefL};
        var bytes = new byte[values.length * 2 + 1];
        Arrays.fill(bytes, (byte) 91);
        for (int index = 0; index < values.length; index++)
            ManagedByteArray.writeInt16(bytes, index, (int) values[index]);
        for (int index = 0; index < values.length; index++) {
            long value = values[index];
            assertEquals(signed(value), (long) ManagedByteArray.readInt16(bytes, index));
            assertEquals(unsigned(value), (long) ManagedByteArray.readWord16(bytes, index));
            for (int b = 0; b <= 1; b++) {
                int shift = (ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN ? b : 1 - b) * 8;
                assertEquals((value >>> shift) & 255, (long) ManagedByteArray.read(bytes, index * 2L + b));
            }
        }
        for (int b = values.length * 2; b < bytes.length; b++) assertEquals(91, (int) bytes[b]);
        assertSame(bytes, ManagedByteArray.freeze(bytes));
        var other = new byte[2];
        ManagedByteArray.writeInt16(other, 0, 73);
        assertEquals(0L, (long) ManagedByteArray.readWord16(bytes, 0));
        assertEquals(73L, (long) ManagedByteArray.readInt16(other, 0));
    }
    private long expected(byte[] bytes, int offset, int count) {
        long bits = 0L;
        for (int b = 0; b < count; b++) {
            int shift = (ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN ? b : count - 1 - b) * 8;
            bits |= ((long) bytes[offset + b] & 255) << shift;
        }
        return bits;
    }
    @Test
    void byteAliasesAcrossOneAndTwoAndWideViewsShareTheSameStorage() {
        var bytes = new byte[8];
        ManagedByteArray.writeInt16(bytes, 0, (int) 0x80abL);
        ManagedByteArray.writeInt16(bytes, 1, (int) 0xfedcL);
        ManagedByteArray.write(bytes, 1, 0x91);
        ManagedByteArray.write(bytes, 2, 0xa2);
        for (int element = 0; element <= 1; element++) {
            long bits = expected(bytes, element * 2, 2);
            assertEquals(bits, (long) ManagedByteArray.readWord16(bytes, element));
            assertEquals(signed(bits), (long) ManagedByteArray.readInt16(bytes, element));
        }
        assertEquals(expected(bytes, 0, 8), ManagedByteArray.readInt(bytes, 0));
        ManagedByteArray.writeDouble(bytes, 0, -0.0);
        assertEquals(Long.MIN_VALUE, ManagedByteArray.readInt(bytes, 0));
        assertEquals(ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN ? 0L : 0x8000L,
            (long) ManagedByteArray.readWord16(bytes, 0));
        var copied = new byte[8];
        ManagedByteArray.copy(ManagedByteArray.freeze(bytes), 0, copied, 0, 8);
        assertArrayEquals(bytes, copied);
        ManagedByteArray.writeInt16(copied, 0, 73);
        assertNotEquals(73L, (long) ManagedByteArray.readInt16(bytes, 0));
    }
    @Test
    void fullWidthBoundsExcludePartialElementsBeforeScalingOrWriting() {
        for (int size : new int[] {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 11, 12, 13, 15, 16, 17, 31}) {
            var bytes = new byte[size];
            Arrays.fill(bytes, (byte) 37);
            for (long index :
                new long[] {Long.MIN_VALUE, -1L, size / 2, Integer.MAX_VALUE, 1L << 32, 1L << 62, Long.MAX_VALUE}) {
                var before = bytes.clone();
                assertThrows(RuntimeFault.class, () -> ManagedByteArray.readInt16(bytes, index));
                assertThrows(RuntimeFault.class, () -> ManagedByteArray.readWord16(bytes, index));
                assertThrows(RuntimeFault.class, () -> ManagedByteArray.writeInt16(bytes, index, (int) Long.MIN_VALUE));
                assertArrayEquals(before, bytes, "size=" + size + "/index=" + index);
            }
            if (size >= 2) {
                long last = size / 2 - 1;
                ManagedByteArray.writeInt16(bytes, last, (int) 0x8000L);
                assertEquals(-0x8000L, (long) ManagedByteArray.readInt16(bytes, last));
                assertEquals(0x8000L, (long) ManagedByteArray.readWord16(bytes, last));
            }
        }
    }
    private Expr operand(List<String> events, String name, Supplier<Object> action) {
        return new Expr() {
            @Override
            public Object execute(VirtualFrame frame) {
                events.add(name);
                return action.get();
            }
        };
    }
    @Test
    void statePrecedesAccessAndFailedReadsNeverPublish() throws Exception {
        var descriptor = FrameDescriptor.newBuilder();
        int slot = descriptor.addSlot(FrameSlotKind.Int, "result", null);
        var frame = Truffle.getRuntime().createVirtualFrame(new Object[0], descriptor.build());
        for (boolean unsigned : new boolean[] {false, true}) {
            var readOp = unsigned ? ByteArrayOp.READ_WORD16 : ByteArrayOp.READ_INT16;
            var writeOp = unsigned ? ByteArrayOp.WRITE_WORD16 : ByteArrayOp.WRITE_INT16;
            var bytes = new byte[2];
            ManagedByteArray.writeInt16(bytes, 0, 7);
            var events = new ArrayList<String>();
            var read = expression(readOp, CoreRepresentation.UNKNOWN,
                new Expr[] {operand(events, "array", () -> bytes), operand(events, "index", () -> 0L),
                    operand(events, "state", () -> {
                        ManagedByteArray.writeInt16(bytes, 0, (int) 0x8000L);
                        return Unit.INSTANCE;
                    })});
            read.executeTuple(frame, new int[] {slot}, 0);
            assertEquals(List.of("array", "index", "state"), events);
            assertTrue(frame.isInt(slot));
            assertEquals(unsigned ? 0x8000 : -0x8000, frame.getInt(slot));
            events.clear();
            var write = expression(writeOp, CoreRepresentation.UNKNOWN,
                new Expr[] {operand(events, "array", () -> bytes), operand(events, "index", () -> 0L),
                    operand(events, "value", () -> 0x1_ffff), operand(events, "state", () -> {
                        assertEquals(0x8000L, (long) ManagedByteArray.readWord16(bytes, 0));
                        return Unit.INSTANCE;
                    })});
            assertSame(Unit.INSTANCE, write.execute(frame));
            assertEquals(List.of("array", "index", "value", "state"), events);
            assertEquals(0xffffL, (long) ManagedByteArray.readWord16(bytes, 0));
            for (var badState :
                List.<Supplier<Object>>of(() -> { throw new RuntimeFault("state failed"); }, () -> 0L)) {
                frame.setInt(slot, 73);
                var failedRead = expression(readOp, CoreRepresentation.UNKNOWN,
                    new Expr[] {operand(events, "array", () -> bytes), operand(events, "index", () -> 0L),
                        operand(events, "state", badState)});
                assertThrows(RuntimeFault.class, () -> failedRead.executeTuple(frame, new int[] {slot}, 0));
                assertEquals(73, frame.getInt(slot));
                var failedWrite = expression(writeOp, CoreRepresentation.UNKNOWN,
                    new Expr[] {operand(events, "array", () -> bytes), operand(events, "index", () -> 0L),
                        operand(events, "value", () -> 99), operand(events, "state", badState)});
                assertThrows(RuntimeFault.class, () -> failedWrite.execute(frame));
                assertEquals(0xffffL, (long) ManagedByteArray.readWord16(bytes, 0));
            }
            frame.setInt(slot, 73);
            var badIndexRead = expression(readOp, CoreRepresentation.UNKNOWN,
                new Expr[] {operand(events, "array", () -> bytes), operand(events, "index", () -> 1L),
                    operand(events, "state", () -> Unit.INSTANCE)});
            assertThrows(RuntimeFault.class, () -> badIndexRead.executeTuple(frame, new int[] {slot}, 0));
            assertEquals(73, frame.getInt(slot));
            // Bytecode specializations likewise validate State before any access.
            assertThrows(RuntimeFault.class, () -> BytecodeRoot.WriteInt16Array.write(false, bytes, 0, 99, 0L));
            assertEquals(0xffffL, (long) ManagedByteArray.readWord16(bytes, 0));
        }
    }
    @Test
    void everySixteenBitPatternHasIndependentSignedAndUnsignedResults() {
        var bytes = new byte[2];
        for (long bits = 0; bits <= 65535L; bits++) {
            ManagedByteArray.writeInt16(bytes, 0, (int) (bits | (0x12345678L << 32)));
            assertEquals(bits, (long) ManagedByteArray.readWord16(bytes, 0));
            assertEquals(bits < 32768 ? bits : bits - 65536, (long) ManagedByteArray.readInt16(bytes, 0));
        }
    }
}
