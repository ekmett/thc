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

class Int32ArrayTest {
    private long unsigned(long value) { return value & 0xffff_ffffL; }
    private long signed(long value) { long bits = unsigned(value); return bits >= 0x8000_0000L ? bits - 0x1_0000_0000L : bits; }
    @Test void fourByteStorageNarrowsAndWidensWithoutChangingIdentityOrAdjacentBytes() {
        long[] values = {Long.MIN_VALUE, Long.MAX_VALUE, -1L, 0L, 1L, 0x7fff_ffffL, 0x8000_0000L, 0xffff_ffffL, 0x1_0000_0001L, -0x1_0000_0001L, 0x0123456789abcdefL};
        var bytes = new byte[values.length * 4 + 3]; Arrays.fill(bytes, (byte) 91);
        for (int index = 0; index < values.length; index++) ManagedByteArray.writeInt32(bytes, index, (int) values[index]);
        for (int index = 0; index < values.length; index++) {
            long value = values[index];
            assertEquals(signed(value), (long) ManagedByteArray.readInt32(bytes, index));
            assertEquals(unsigned(value), (long) ManagedByteArray.readWord16(bytes, index));
            for (int b = 0; b <= 3; b++) {
                int shift = (ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN ? b : 3 - b) * 8;
                assertEquals((value >>> shift) & 255, (long) ManagedByteArray.read(bytes, index * 4L + b));
            }
        }
        for (int b = values.length * 4; b < bytes.length; b++) assertEquals(91, (int) bytes[b]);
        assertSame(bytes, ManagedByteArray.freeze(bytes));
        var other = new byte[4]; ManagedByteArray.writeInt32(other, 0, 73);
        assertEquals(0L, (long) ManagedByteArray.readWord16(bytes, 0));
        assertEquals(73L, (long) ManagedByteArray.readInt32(other, 0));
    }
    private long expected(byte[] bytes, int offset, int count) {
        long bits = 0L;
        for (int b = 0; b < count; b++) {
            int shift = (ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN ? b : count - 1 - b) * 8;
            bits |= ((long) bytes[offset + b] & 255) << shift;
        }
        return bits;
    }
    @Test void byteAliasesAcrossThreeAndFourAndWideViewsShareTheSameStorage() {
        var bytes = new byte[8];
        ManagedByteArray.writeInt32(bytes, 0, (int) 0x80ab_cdefL); ManagedByteArray.writeInt32(bytes, 1, (int) 0xfedc_ba98L);
        ManagedByteArray.write(bytes, 3, 0x91); ManagedByteArray.write(bytes, 4, 0xa2);
        for (int element = 0; element <= 1; element++) {
            long bits = expected(bytes, element * 4, 4);
            assertEquals(bits, (long) ManagedByteArray.readWord16(bytes, element));
            assertEquals(signed(bits), (long) ManagedByteArray.readInt32(bytes, element));
        }
        assertEquals(expected(bytes, 0, 8), ManagedByteArray.readInt(bytes, 0));
        ManagedByteArray.writeDouble(bytes, 0, -0.0); assertEquals(Long.MIN_VALUE, ManagedByteArray.readInt(bytes, 0));
        assertEquals(ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN ? 0L : 0x8000_0000L, (long) ManagedByteArray.readWord16(bytes, 0));
        var copied = new byte[8]; ManagedByteArray.copy(ManagedByteArray.freeze(bytes), 0, copied, 0, 8);
        assertArrayEquals(bytes, copied); ManagedByteArray.writeInt32(copied, 0, 73);
        assertNotEquals(73L, (long) ManagedByteArray.readInt32(bytes, 0));
    }
    @Test void fullWidthBoundsExcludePartialElementsBeforeScalingOrWriting() {
        for (int size : new int[]{0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 11, 12, 13, 15, 16, 17, 31}) {
            var bytes = new byte[size]; Arrays.fill(bytes, (byte) 37);
            for (long index : new long[]{Long.MIN_VALUE, -1L, size / 4, Integer.MAX_VALUE, 1L << 32, 1L << 62, Long.MAX_VALUE}) {
                var before = bytes.clone();
                assertThrows(RuntimeFault.class, () -> ManagedByteArray.readInt32(bytes, index));
                assertThrows(RuntimeFault.class, () -> ManagedByteArray.readWord16(bytes, index));
                assertThrows(RuntimeFault.class, () -> ManagedByteArray.writeInt32(bytes, index, (int) Long.MIN_VALUE));
                assertArrayEquals(before, bytes, "size=" + size + "/index=" + index);
            }
            if (size >= 4) {
                long last = size / 4 - 1; ManagedByteArray.writeInt32(bytes, last, (int) 0x8000_0000L);
                assertEquals(-0x8000_0000L, (long) ManagedByteArray.readInt32(bytes, last));
                assertEquals(0x8000_0000L, (long) ManagedByteArray.readWord16(bytes, last));
            }
        }
    }
    private Expr operand(List<String> events, String name, Supplier<Object> action) {
        return new Expr() { @Override public Object execute(VirtualFrame frame) { events.add(name); return action.get(); } };
    }
    @Test void statePrecedesAccessAndFailedReadsNeverPublish() throws Exception {
        var descriptor = FrameDescriptor.newBuilder(); int slot = descriptor.addSlot(FrameSlotKind.Int, "result", null);
        var frame = Truffle.getRuntime().createVirtualFrame(new Object[0], descriptor.build());
        for (boolean unsigned : new boolean[]{false, true}) {
            var readOp = unsigned ? ByteArrayOp.READ_WORD32 : ByteArrayOp.READ_INT32;
            var writeOp = unsigned ? ByteArrayOp.WRITE_WORD32 : ByteArrayOp.WRITE_INT32;
            var bytes = new byte[4]; ManagedByteArray.writeInt32(bytes, 0, 7);
            var events = new ArrayList<String>();
            var read = expression(readOp, CoreRepresentation.Companion.getUNKNOWN(), new Expr[]{
                operand(events, "array", () -> bytes), operand(events, "index", () -> 0L),
                operand(events, "state", () -> { ManagedByteArray.writeInt32(bytes, 0, (int) 0x8000_0000L); return Unit.INSTANCE; })});
            read.executeTuple(frame, new int[]{slot}, 0);
            assertEquals(List.of("array", "index", "state"), events); assertTrue(frame.isInt(slot));
            assertEquals(Integer.MIN_VALUE, frame.getInt(slot), "Word32 retains its raw high bit"); events.clear();
            var write = expression(writeOp, CoreRepresentation.Companion.getUNKNOWN(), new Expr[]{
                operand(events, "array", () -> bytes), operand(events, "index", () -> 0L), operand(events, "value", () -> -1),
                operand(events, "state", () -> { assertEquals(0x8000_0000L, (long) ManagedByteArray.readWord16(bytes, 0)); return Unit.INSTANCE; })});
            assertSame(Unit.INSTANCE, write.execute(frame)); assertEquals(List.of("array", "index", "value", "state"), events);
            assertEquals(0xffff_ffffL, (long) ManagedByteArray.readWord16(bytes, 0));
            for (var badState : List.<Supplier<Object>>of(() -> { throw new RuntimeFault("state failed"); }, () -> 0L)) {
                frame.setInt(slot, 73);
                var failedRead = expression(readOp, CoreRepresentation.Companion.getUNKNOWN(), new Expr[]{
                    operand(events, "array", () -> bytes), operand(events, "index", () -> 0L), operand(events, "state", badState)});
                assertThrows(RuntimeFault.class, () -> failedRead.executeTuple(frame, new int[]{slot}, 0)); assertEquals(73, frame.getInt(slot));
                var failedWrite = expression(writeOp, CoreRepresentation.Companion.getUNKNOWN(), new Expr[]{
                    operand(events, "array", () -> bytes), operand(events, "index", () -> 0L), operand(events, "value", () -> 99), operand(events, "state", badState)});
                assertThrows(RuntimeFault.class, () -> failedWrite.execute(frame)); assertEquals(0xffff_ffffL, (long) ManagedByteArray.readWord16(bytes, 0));
            }
            frame.setInt(slot, 73);
            var badIndexRead = expression(readOp, CoreRepresentation.Companion.getUNKNOWN(), new Expr[]{
                operand(events, "array", () -> bytes), operand(events, "index", () -> 1L), operand(events, "state", () -> Unit.INSTANCE)});
            assertThrows(RuntimeFault.class, () -> badIndexRead.executeTuple(frame, new int[]{slot}, 0)); assertEquals(73, frame.getInt(slot));
            // Bytecode specializations likewise validate State before any access.
            assertThrows(RuntimeFault.class, () -> BytecodeRoot.WriteInt32Array.write(false, bytes, 0, 99, 0L));
            assertEquals(0xffff_ffffL, (long) ManagedByteArray.readWord16(bytes, 0));
        }
    }
}
