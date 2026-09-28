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
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;
import kotlin.Unit;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static thc.runtime.ByteArrayOp.expression;

class DoubleArrayTest {
    @Test
    void rawMovementPreservesDefinedBitsInSharedByteStorage() {
        long[] bits = {0L, Long.MIN_VALUE, 1L, Long.MIN_VALUE + 1, 0x000fffffffffffffL, 0x0010000000000000L,
            0x3ff0000000000000L, 0x7fefffffffffffffL, 0x7ff0000000000000L, -0x0010000000000000L, 0x7ff8000000001234L,
            -0x0007ffffffffa988L};
        var bytes = new byte[bits.length * 8 + 7];
        Arrays.fill(bytes, (byte) 91);
        for (int index = 0; index < bits.length; index++) {
            long value = bits[index];
            ManagedByteArray.writeInt(bytes, index, value);
            double number = ManagedByteArray.readDouble(bytes, index);
            assertEquals(value, Double.doubleToRawLongBits(number));
            ManagedByteArray.writeDouble(bytes, index, number);
            assertEquals(value, ManagedByteArray.readInt(bytes, index));
            for (int b = 0; b <= 7; b++) {
                int shift = (ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN ? b : 7 - b) * 8;
                assertEquals((value >>> shift) & 255, (long) ManagedByteArray.read(bytes, index * 8L + b));
            }
        }
        for (int b = bits.length * 8; b < bytes.length; b++) assertEquals(91, (int) bytes[b]);
        assertSame(bytes, ManagedByteArray.freeze(bytes));
        var other = new byte[8];
        ManagedByteArray.writeDouble(other, 0, 3.5);
        assertEquals(Double.doubleToRawLongBits(3.5), ManagedByteArray.readInt(other, 0));
        assertEquals(0L, ManagedByteArray.readInt(bytes, 0));
    }
    @Test
    void boundsRejectIncompleteElementsAndIndexOverflowWithoutWrites() {
        for (int size : new int[] {0, 1, 7, 8, 9, 15, 16, 17, 31}) {
            var bytes = new byte[size];
            Arrays.fill(bytes, (byte) 37);
            for (long index : new long[] {Long.MIN_VALUE, -1L, size / 8, 1L << 32, 1L << 61, Long.MAX_VALUE}) {
                var before = bytes.clone();
                assertThrows(RuntimeFault.class, () -> ManagedByteArray.readDouble(bytes, index));
                assertThrows(RuntimeFault.class, () -> ManagedByteArray.writeDouble(bytes, index, -0.0));
                assertArrayEquals(before, bytes);
            }
        }
    }
    @Test
    void ownedDoubleBoundsPreserveBytesAndRespectShrunkLogicalSize() {
        for (long size : new long[] {0L, 7L, 8L, 15L, 16L}) {
            var array = ManagedByteArray.allocateGuest(size + 8);
            ManagedByteArray.fillGuest(array, 0, size + 8, 37);
            // The backing still contains a complete extra Double. Both mutable
            // reads and the frozen alias must honor the owner's logical length.
            var frozen = ManagedByteArray.freezeGuest(array);
            ManagedByteArray.shrinkGuest(array, size);
            assertSame(array, frozen);
            var expected = Collections.nCopies((int) size, 37L);
            for (var invalid : new Object[][] {{Long.MIN_VALUE, "element"}, {-1L, "element"}, {size / 8, "range"},
                     {1L << 32, "range"}, {1L << 61, "element"}, {Long.MAX_VALUE, "element"}}) {
                long index = (Long) invalid[0];
                String guard = (String) invalid[1];
                var read = assertThrows(RuntimeFault.class, () -> ManagedByteArray.readDoubleGuest(array, index));
                var indexRead = assertThrows(RuntimeFault.class, () -> ManagedByteArray.readDoubleGuest(frozen, index));
                var write =
                    assertThrows(RuntimeFault.class, () -> ManagedByteArray.writeDoubleGuest(array, index, -0.0));
                for (var failure : List.of(read, indexRead, write))
                    assertEquals("Managed allocation " + guard + " outside its backing storage", failure.getMessage(),
                        "size=" + size + "/index=" + index);
                assertEquals(size, ManagedByteArray.sizeGuest(array));
                var actual = new ArrayList<Long>();
                for (long i = 0; i < size; i++) actual.add((long) ManagedByteArray.readGuest(array, i, true));
                assertEquals(expected, actual);
            }
            if (size >= 8) {
                ManagedByteArray.writeDoubleGuest(array, 0, -0.0);
                assertEquals(Long.MIN_VALUE, Double.doubleToRawLongBits(ManagedByteArray.readDoubleGuest(frozen, 0)));
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
    void statePrecedesMemoryAccessAndOnlySuccessfulReadsPublishPrimitiveDouble() throws Exception {
        var descriptor = FrameDescriptor.newBuilder();
        int slot = descriptor.addSlot(FrameSlotKind.Double, "result", null);
        var frame = Truffle.getRuntime().createVirtualFrame(new Object[0], descriptor.build());
        var bytes = new byte[8];
        ManagedByteArray.writeDouble(bytes, 0, 7.5);
        var events = new ArrayList<String>();
        var read = expression(ByteArrayOp.READ_DOUBLE, CoreRepresentation.UNKNOWN,
            new Expr[] {operand(events, "array", () -> bytes), operand(events, "index", () -> 0L),
                operand(events, "state", () -> {
                    ManagedByteArray.writeDouble(bytes, 0, -0.0);
                    return Unit.INSTANCE;
                })});
        read.executeTuple(frame, new int[] {slot}, 0);
        assertEquals(List.of("array", "index", "state"), events);
        assertTrue(frame.isDouble(slot));
        assertEquals(Long.MIN_VALUE, Double.doubleToRawLongBits(frame.getDouble(slot)));
        events.clear();
        double quiet = Double.longBitsToDouble(0x7ff8000000001234L);
        var write = expression(ByteArrayOp.WRITE_DOUBLE, CoreRepresentation.UNKNOWN,
            new Expr[] {operand(events, "array", () -> bytes), operand(events, "index", () -> 0L),
                operand(events, "value", () -> quiet), operand(events, "state", () -> {
                    assertEquals(Long.MIN_VALUE, ManagedByteArray.readInt(bytes, 0));
                    return Unit.INSTANCE;
                })});
        assertSame(Unit.INSTANCE, write.execute(frame));
        assertEquals(List.of("array", "index", "value", "state"), events);
        assertEquals(Double.doubleToRawLongBits(quiet), ManagedByteArray.readInt(bytes, 0));
        for (var badState : List.<Supplier<Object>>of(() -> { throw new RuntimeFault("state failed"); }, () -> 0L)) {
            frame.setDouble(slot, -0.0);
            var failedRead = expression(ByteArrayOp.READ_DOUBLE, CoreRepresentation.UNKNOWN,
                new Expr[] {operand(events, "array", () -> bytes), operand(events, "index", () -> 0L),
                    operand(events, "state", badState)});
            assertThrows(RuntimeFault.class, () -> failedRead.executeTuple(frame, new int[] {slot}, 0));
            assertEquals(Long.MIN_VALUE, Double.doubleToRawLongBits(frame.getDouble(slot)));
            var failedWrite = expression(ByteArrayOp.WRITE_DOUBLE, CoreRepresentation.UNKNOWN,
                new Expr[] {operand(events, "array", () -> bytes), operand(events, "index", () -> 0L),
                    operand(events, "value", () -> 99.0), operand(events, "state", badState)});
            assertThrows(RuntimeFault.class, () -> failedWrite.execute(frame));
            assertEquals(Double.doubleToRawLongBits(quiet), ManagedByteArray.readInt(bytes, 0));
        }
    }
}
