// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.VirtualFrame;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static thc.runtime.ByteArrayOp.expression;

class Int32ByteOffsetTest {
    private Expr operand(Object value) {
        return new Expr() {
            @Override
            public Object execute(VirtualFrame frame) {
                return value;
            }
        };
    }
    @Test
    void unalignedSignedUnsignedStorageChecksBoundsPointerCellsAndState() {
        var bytes = new byte[16];
        ManagedByteArray.writeInt32ByteOffsetGuest(bytes, 1, -2147483648);
        ManagedByteArray.writeInt32ByteOffsetGuest(bytes, 9, 0x80000001);
        assertEquals(-2147483648L, (long) ManagedByteArray.readInt32ByteOffsetGuest(bytes, 1, false));
        assertEquals(0x80000001L, Integer.toUnsignedLong(ManagedByteArray.readInt32ByteOffsetGuest(bytes, 9, true)));
        var model =
            ByteBuffer.allocate(16).order(ByteOrder.nativeOrder()).putInt(1, -2147483648).putInt(9, 0x80000001).array();
        assertArrayEquals(model, bytes);
        var tail = new byte[16];
        ManagedByteArray.writeInt32ByteOffsetGuest(tail, 12, -1);
        assertEquals(4294967295L, Integer.toUnsignedLong(ManagedByteArray.readInt32ByteOffsetGuest(tail, 12, true)));
        for (long offset : new long[] {-1L, 13L, Long.MAX_VALUE}) {
            assertThrows(RuntimeFault.class, () -> ManagedByteArray.readInt32ByteOffsetGuest(bytes, offset, false));
            assertThrows(RuntimeFault.class, () -> ManagedByteArray.writeInt32ByteOffsetGuest(bytes, offset, 1));
        }
        var owner = ManagedAllocation.mutable(24, 8);
        var target = ManagedAddress.fromAllocation(ManagedAllocation.mutable(8, 8));
        owner.writeAddressByteOffset(8, target);
        ManagedByteArray.writeInt32ByteOffsetGuest(owner, 1, -1);
        assertEquals(-1L, (long) ManagedByteArray.readInt32ByteOffsetGuest(owner, 1, false));
        assertThrows(RuntimeFault.class,
            () -> Integer.toUnsignedLong(ManagedByteArray.readInt32ByteOffsetGuest(owner, 9, true)));
        assertThrows(RuntimeFault.class, () -> ManagedByteArray.writeInt32ByteOffsetGuest(owner, 9, 1));
        assertSame(target, owner.readAddressByteOffset(8));
        var frame = Truffle.getRuntime().createVirtualFrame(new Object[0], FrameDescriptor.newBuilder().build());
        var write = expression(ByteArrayOp.WRITE_WORD8_AS_INT32, CoreRepresentation.UNKNOWN,
            new Expr[] {operand(bytes), operand(1L), operand(7L), operand("invalid state")});
        assertThrows(RuntimeFault.class, () -> write.execute(frame));
        assertThrows(RuntimeFault.class, () -> BytecodeRoot.WriteInt32Array.write(true, bytes, 9L, 7, "invalid state"));
        assertEquals(-2147483648L, (long) ManagedByteArray.readInt32ByteOffsetGuest(bytes, 1, false));
        assertEquals(0x80000001L, Integer.toUnsignedLong(ManagedByteArray.readInt32ByteOffsetGuest(bytes, 9, true)));
    }
}
