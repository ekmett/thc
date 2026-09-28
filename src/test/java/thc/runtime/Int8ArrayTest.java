// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.FrameSlotKind;
import com.oracle.truffle.api.frame.VirtualFrame;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;
import kotlin.Unit;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static thc.runtime.ByteArrayOp.expression;

class Int8ArrayTest {
    @Test
    void everyBytePatternHasExactSignedAndUnsignedLongResultsAndSharedIdentity() {
        var bytes = new byte[258];
        Arrays.fill(bytes, (byte) 91);
        for (long bits = 0; bits <= 255L; bits++) {
            ManagedByteArray.write(bytes, bits + 1, (int) (bits | (0x12345678L << 32)));
            assertEquals(bits, (long) ManagedByteArray.read(bytes, bits + 1));
            assertEquals(bits < 128 ? bits : bits - 256, (long) ManagedByteArray.readSigned(bytes, bits + 1));
        }
        assertEquals(91, (int) bytes[0]);
        assertEquals(91, (int) bytes[bytes.length - 1]);
        assertSame(bytes, ManagedByteArray.freeze(bytes));
        assertEquals(byte[].class, bytes.getClass());
        var other = ManagedByteArray.allocate(1);
        ManagedByteArray.write(other, 0, 73);
        assertEquals(91L, (long) ManagedByteArray.read(bytes, 0));
        assertNotSame(bytes, other);
        var copy = new byte[bytes.length];
        ManagedByteArray.copy(bytes, 0, copy, 0, bytes.length);
        assertArrayEquals(bytes, copy);
        ManagedByteArray.write(copy, 1, 255);
        assertEquals(0L, (long) ManagedByteArray.readSigned(bytes, 1));
        assertEquals(-1L, (long) ManagedByteArray.readSigned(copy, 1));
    }
    @Test
    void fullWidthBoundsFailBeforeNarrowingOrWritingIncludingEmptyStorage() {
        for (int size : new int[] {0, 1, 2, 7, 8, 9, 31}) {
            var bytes = new byte[size];
            Arrays.fill(bytes, (byte) 37);
            for (long index :
                new long[] {Long.MIN_VALUE, -1L, size, Integer.MAX_VALUE, 1L << 32, 1L << 62, Long.MAX_VALUE}) {
                var before = bytes.clone();
                assertThrows(RuntimeFault.class, () -> ManagedByteArray.readSigned(bytes, index));
                assertThrows(RuntimeFault.class, () -> ManagedByteArray.read(bytes, index));
                assertThrows(RuntimeFault.class, () -> ManagedByteArray.write(bytes, index, (int) Long.MIN_VALUE));
                assertArrayEquals(before, bytes);
            }
            if (size > 0) {
                ManagedByteArray.write(bytes, size - 1L, 0x180);
                assertEquals(-128L, (long) ManagedByteArray.readSigned(bytes, size - 1L));
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
    void statePrecedesReadAndWriteAndFailedReadNeverPublishes() throws Exception {
        var builder = FrameDescriptor.newBuilder();
        int slot = builder.addSlot(FrameSlotKind.Int, "result", null);
        var frame = Truffle.getRuntime().createVirtualFrame(new Object[0], builder.build());
        for (boolean unsigned : new boolean[] {false, true}) {
            var bytes = new byte[] {7};
            var events = new ArrayList<String>();
            var readOp = unsigned ? ByteArrayOp.READ_WORD8 : ByteArrayOp.READ_INT8;
            var read = expression(readOp, CoreRepresentation.UNKNOWN,
                new Expr[] {operand(events, "array", () -> bytes), operand(events, "index", () -> 0L),
                    operand(events, "state", () -> {
                        ManagedByteArray.write(bytes, 0, 128);
                        return Unit.INSTANCE;
                    })});
            read.executeTuple(frame, new int[] {slot}, 0);
            assertEquals(List.of("array", "index", "state"), events);
            assertTrue(frame.isInt(slot));
            assertEquals(unsigned ? 128 : -128, frame.getInt(slot));
            events.clear();
            var write = expression(ByteArrayOp.WRITE_INT8, CoreRepresentation.UNKNOWN,
                new Expr[] {operand(events, "array", () -> bytes), operand(events, "index", () -> 0L),
                    operand(events, "value", () -> 511), operand(events, "state", () -> {
                        assertEquals(128L, (long) ManagedByteArray.read(bytes, 0));
                        return Unit.INSTANCE;
                    })});
            assertSame(Unit.INSTANCE, write.execute(frame));
            assertEquals(List.of("array", "index", "value", "state"), events);
            assertEquals(-1L, (long) ManagedByteArray.readSigned(bytes, 0));
            for (var badState :
                List.<Supplier<Object>>of(() -> { throw new RuntimeFault("state failed"); }, () -> 0L)) {
                frame.setInt(slot, 73);
                var badRead = expression(readOp, CoreRepresentation.UNKNOWN,
                    new Expr[] {operand(events, "array", () -> bytes), operand(events, "index", () -> 0L),
                        operand(events, "state", badState)});
                assertThrows(RuntimeFault.class, () -> badRead.executeTuple(frame, new int[] {slot}, 0));
                assertEquals(73, frame.getInt(slot));
                var badWrite = expression(ByteArrayOp.WRITE_INT8, CoreRepresentation.UNKNOWN,
                    new Expr[] {operand(events, "array", () -> bytes), operand(events, "index", () -> 0L),
                        operand(events, "value", () -> 99), operand(events, "state", badState)});
                assertThrows(RuntimeFault.class, () -> badWrite.execute(frame));
                assertEquals(255L, (long) ManagedByteArray.read(bytes, 0));
            }
            assertThrows(RuntimeFault.class, () -> BytecodeRoot.WriteByteArray.write(bytes, 0, 99, 0L));
            assertEquals(255L, (long) ManagedByteArray.read(bytes, 0));
            // Invalid State must fail before the read or destination publication.
            // Null destination/node deliberately make premature publication fail
            // with the wrong exception instead of silently passing this control.
            assertThrows(
                RuntimeFault.class, () -> BytecodeRoot.ReadByteArray.read(frame, unsigned, null, bytes, 0, 0L, null));
            assertEquals(73, frame.getInt(slot));
        }
    }
}
