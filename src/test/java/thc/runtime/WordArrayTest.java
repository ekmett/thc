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
import thc.runtime.Unit;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static thc.runtime.ByteArrayOp.expression;

class WordArrayTest {
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
    void machineWordRawBitsAliasBytesWithoutTruncatingTheHighHalf() {
        long[] values = {Long.MIN_VALUE, Long.MAX_VALUE, -1L, 0L, 1L, 0x0123456789abcdefL, -0x0123456789abcdefL};
        var bytes = ManagedByteArray.allocate(values.length * 8L + 7);
        // Explicitly initialize every byte used below, including the partial tail.
        Arrays.fill(bytes, (byte) 91);
        for (int index = 0; index < values.length; index++) ManagedByteArray.writeInt(bytes, index, values[index]);
        var frozen = ManagedByteArray.freeze(bytes);
        assertSame(bytes, frozen);
        for (int index = 0; index < values.length; index++) {
            long value = values[index];
            assertEquals(value, ManagedByteArray.readInt(frozen, index));
            for (int b = 0; b <= 7; b++) {
                int shift = (ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN ? b : 7 - b) * 8;
                assertEquals((value >>> shift) & 255, (long) ManagedByteArray.read(frozen, index * 8L + b));
            }
        }
        for (int b = values.length * 8; b < bytes.length; b++)
            assertEquals(91L, (long) ManagedByteArray.read(bytes, b));
        var separate = ManagedByteArray.allocate(8);
        ManagedByteArray.writeInt(separate, 0, 73);
        // Use mutable storage only: no mutation through an unsafe-frozen alias.
        for (int b = 0; b <= 7; b++) ManagedByteArray.write(separate, b, (int) (128L + b));
        long expected = 0L;
        for (int b = 0; b <= 7; b++) {
            int shift = (ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN ? b : 7 - b) * 8;
            expected |= (128L + b) << shift;
        }
        assertEquals(expected, ManagedByteArray.readInt(separate, 0));
        assertEquals(values[0], ManagedByteArray.readInt(frozen, 0));
    }
    @Test
    void elementBoundsRejectPartialWordsAndOverflowBeforeAnyWrite() {
        for (int size : new int[] {0, 1, 7, 8, 9, 15, 16, 17, 31}) {
            var bytes = new byte[size];
            Arrays.fill(bytes, (byte) 37);
            for (long index :
                new long[] {Long.MIN_VALUE, -1L, size / 8, Integer.MAX_VALUE, 1L << 32, 1L << 61, Long.MAX_VALUE}) {
                var before = bytes.clone();
                assertThrows(RuntimeFault.class, () -> ManagedByteArray.readInt(bytes, index));
                assertThrows(RuntimeFault.class, () -> ManagedByteArray.writeInt(bytes, index, Long.MIN_VALUE));
                assertArrayEquals(before, bytes, "size=" + size + "/index=" + index);
            }
        }
    }
    @Test
    void stateRunsBeforeReadOrWriteAndFailureDoesNotPublish() throws Exception {
        var descriptor = FrameDescriptor.newBuilder();
        int slot = descriptor.addSlot(FrameSlotKind.Long, "result", null);
        var frame = Truffle.getRuntime().createVirtualFrame(new Object[0], descriptor.build());
        var bytes = new byte[8];
        ManagedByteArray.writeInt(bytes, 0, 7);
        var events = new ArrayList<String>();
        var read = expression(ByteArrayOp.READ_WORD, CoreRepresentation.UNKNOWN,
            new Expr[] {operand(events, "array", () -> bytes), operand(events, "index", () -> 0L),
                operand(events, "state", () -> {
                    ManagedByteArray.writeInt(bytes, 0, Long.MIN_VALUE);
                    return Unit.INSTANCE;
                })});
        read.executeTuple(frame, new int[] {slot}, 0);
        assertEquals(List.of("array", "index", "state"), events);
        assertTrue(frame.isLong(slot));
        assertEquals(Long.MIN_VALUE, frame.getLong(slot));
        events.clear();
        var write = expression(ByteArrayOp.WRITE_WORD, CoreRepresentation.UNKNOWN,
            new Expr[] {operand(events, "array", () -> bytes), operand(events, "index", () -> 0L),
                operand(events, "value", () -> Long.MAX_VALUE), operand(events, "state", () -> {
                    assertEquals(Long.MIN_VALUE, ManagedByteArray.readInt(bytes, 0));
                    return Unit.INSTANCE;
                })});
        assertSame(Unit.INSTANCE, write.execute(frame));
        assertEquals(List.of("array", "index", "value", "state"), events);
        assertEquals(Long.MAX_VALUE, ManagedByteArray.readInt(bytes, 0));
        for (var badState : List.<Supplier<Object>>of(() -> { throw new RuntimeFault("state failed"); }, () -> 0L)) {
            frame.setLong(slot, 73);
            var failedRead = expression(ByteArrayOp.READ_WORD, CoreRepresentation.UNKNOWN,
                new Expr[] {operand(events, "array", () -> bytes), operand(events, "index", () -> 0L),
                    operand(events, "state", badState)});
            assertThrows(RuntimeFault.class, () -> failedRead.executeTuple(frame, new int[] {slot}, 0));
            assertEquals(73L, frame.getLong(slot));
            var failedWrite = expression(ByteArrayOp.WRITE_WORD, CoreRepresentation.UNKNOWN,
                new Expr[] {operand(events, "array", () -> bytes), operand(events, "index", () -> 0L),
                    operand(events, "value", () -> 99L), operand(events, "state", badState)});
            assertThrows(RuntimeFault.class, () -> failedWrite.execute(frame));
            assertEquals(Long.MAX_VALUE, ManagedByteArray.readInt(bytes, 0));
        }
    }
}
