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

class FloatArrayTest {
    @Test void rawFloatMovementUsesFourSharedBytesWithoutNumericalConversion() {
        int[] bits = {0, Integer.MIN_VALUE, 1, Integer.MIN_VALUE + 1, 0x007fffff, 0x00800000, 0x3f800000, 0x7f7fffff, 0x7f800000, -0x00800000, 0x7fc01234, -0x003fa988};
        var bytes = new byte[bits.length * 4 + 3]; Arrays.fill(bytes, (byte) 91);
        for (int index = 0; index < bits.length; index++) {
            int value = bits[index]; ManagedByteArray.writeInt32(bytes, index, value); float number = ManagedByteArray.readFloat(bytes, index);
            assertEquals(value, Float.floatToRawIntBits(number)); ManagedByteArray.writeFloat(bytes, index, number);
            assertEquals((long) value & 0xffff_ffffL, Integer.toUnsignedLong(ManagedByteArray.readWord32(bytes, index)));
            for (int b = 0; b <= 3; b++) {
                int shift = (ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN ? b : 3 - b) * 8;
                assertEquals((long) ((value >>> shift) & 255), (long) ManagedByteArray.read(bytes, index * 4L + b));
            }
        }
        for (int b = bits.length * 4; b < bytes.length; b++) assertEquals(91, (int) bytes[b]);
        assertSame(bytes, ManagedByteArray.freeze(bytes)); var other = new byte[8];
        ManagedByteArray.writeFloat(other, 0, -0.0f); ManagedByteArray.writeFloat(other, 1, 3.5f);
        long expected = ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN ? ((long) Float.floatToRawIntBits(3.5f) << 32) | 0x8000_0000L : Long.MIN_VALUE | (long) Float.floatToRawIntBits(3.5f);
        assertEquals(expected, ManagedByteArray.readInt(other, 0)); assertEquals(0L, Integer.toUnsignedLong(ManagedByteArray.readWord32(bytes, 0)));
        // Mutate two bytes across the Float element boundary using mutable storage.
        ManagedByteArray.write(other, 3, 0x41); ManagedByteArray.write(other, 4, 0x23);
        for (int element = 0; element <= 1; element++) {
            int expectedBits = (int) Integer.toUnsignedLong(ManagedByteArray.readWord32(other, element));
            assertEquals(expectedBits, Float.floatToRawIntBits(ManagedByteArray.readFloat(other, element)));
        }
    }
    @Test void boundsExcludePartialElementsAndHugeIndicesBeforeAnyWrite() {
        for (int size : new int[]{0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 11, 12, 13, 15, 16, 17, 31}) {
            var bytes = new byte[size]; Arrays.fill(bytes, (byte) 37);
            for (long index : new long[]{Long.MIN_VALUE, -1L, size / 4, 1L << 32, 1L << 62, Long.MAX_VALUE}) {
                var before = bytes.clone(); assertThrows(RuntimeFault.class, () -> ManagedByteArray.readFloat(bytes, index));
                assertThrows(RuntimeFault.class, () -> ManagedByteArray.writeFloat(bytes, index, -0.0f)); assertArrayEquals(before, bytes);
            }
        }
    }
    private Expr operand(List<String> events, String name, Supplier<Object> action) {
        return new Expr() { @Override public Object execute(VirtualFrame frame) { events.add(name); return action.get(); } };
    }
    @Test void statePrecedesAccessAndOnlySuccessfulReadsPublishPrimitiveFloat() throws Exception {
        var descriptor = FrameDescriptor.newBuilder(); int slot = descriptor.addSlot(FrameSlotKind.Float, "result", null);
        var frame = Truffle.getRuntime().createVirtualFrame(new Object[0], descriptor.build());
        var bytes = new byte[4]; ManagedByteArray.writeFloat(bytes, 0, 7.5f); var events = new ArrayList<String>();
        var read = expression(ByteArrayOp.READ_FLOAT, CoreRepresentation.UNKNOWN, new Expr[]{
            operand(events, "array", () -> bytes), operand(events, "index", () -> 0L),
            operand(events, "state", () -> { ManagedByteArray.writeFloat(bytes, 0, -0.0f); return Unit.INSTANCE; })});
        read.executeTuple(frame, new int[]{slot}, 0); assertEquals(List.of("array", "index", "state"), events);
        assertTrue(frame.isFloat(slot)); assertEquals(Integer.MIN_VALUE, Float.floatToRawIntBits(frame.getFloat(slot))); events.clear();
        float quiet = Float.intBitsToFloat(0x7fc01234);
        var write = expression(ByteArrayOp.WRITE_FLOAT, CoreRepresentation.UNKNOWN, new Expr[]{
            operand(events, "array", () -> bytes), operand(events, "index", () -> 0L), operand(events, "value", () -> quiet),
            operand(events, "state", () -> { assertEquals(0x8000_0000L, Integer.toUnsignedLong(ManagedByteArray.readWord32(bytes, 0))); return Unit.INSTANCE; })});
        assertSame(Unit.INSTANCE, write.execute(frame)); assertEquals(List.of("array", "index", "value", "state"), events);
        assertEquals((long) Float.floatToRawIntBits(quiet), Integer.toUnsignedLong(ManagedByteArray.readWord32(bytes, 0)));
        for (var badState : List.<Supplier<Object>>of(() -> { throw new RuntimeFault("state failed"); }, () -> 0L)) {
            frame.setFloat(slot, -0.0f);
            var failedRead = expression(ByteArrayOp.READ_FLOAT, CoreRepresentation.UNKNOWN, new Expr[]{
                operand(events, "array", () -> bytes), operand(events, "index", () -> 0L), operand(events, "state", badState)});
            assertThrows(RuntimeFault.class, () -> failedRead.executeTuple(frame, new int[]{slot}, 0));
            assertEquals(Integer.MIN_VALUE, Float.floatToRawIntBits(frame.getFloat(slot)));
            var failedWrite = expression(ByteArrayOp.WRITE_FLOAT, CoreRepresentation.UNKNOWN, new Expr[]{
                operand(events, "array", () -> bytes), operand(events, "index", () -> 0L), operand(events, "value", () -> 99.0f), operand(events, "state", badState)});
            assertThrows(RuntimeFault.class, () -> failedWrite.execute(frame)); assertEquals((long) Float.floatToRawIntBits(quiet), Integer.toUnsignedLong(ManagedByteArray.readWord32(bytes, 0)));
        }
    }
}
