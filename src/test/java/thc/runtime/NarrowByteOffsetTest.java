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

class NarrowByteOffsetTest {
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
        var bytes = new byte[12];
        ManagedByteArray.writeInt16ByteOffsetGuest(bytes, 1, -32768);
        ManagedByteArray.writeInt16ByteOffsetGuest(bytes, 5, 0x8001);
        assertEquals(-32768L, (long) ManagedByteArray.readInt16ByteOffsetGuest(bytes, 1, false));
        assertEquals(0x8001L, (long) ManagedByteArray.readInt16ByteOffsetGuest(bytes, 5, true));
        var model = ByteBuffer.allocate(12)
                        .order(ByteOrder.nativeOrder())
                        .putShort(1, (short) -32768)
                        .putShort(5, (short) 0x8001)
                        .array();
        assertArrayEquals(model, bytes);
        ManagedByteArray.writeInt16ByteOffsetGuest(bytes, 10, -1);
        assertEquals(65535L, (long) ManagedByteArray.readInt16ByteOffsetGuest(bytes, 10, true));
        for (long offset : new long[] {-1L, 11L, Long.MAX_VALUE}) {
            assertThrows(RuntimeFault.class, () -> ManagedByteArray.readInt16ByteOffsetGuest(bytes, offset, false));
            assertThrows(RuntimeFault.class, () -> ManagedByteArray.writeInt16ByteOffsetGuest(bytes, offset, 1));
        }
        var owner = ManagedAllocation.mutable(24, 8);
        var target = ManagedAddress.fromAllocation(ManagedAllocation.mutable(8, 8));
        owner.writeAddressByteOffset(8, target);
        ManagedByteArray.writeInt16ByteOffsetGuest(owner, 1, -1);
        assertEquals(-1L, (long) ManagedByteArray.readInt16ByteOffsetGuest(owner, 1, false));
        assertThrows(RuntimeFault.class, () -> ManagedByteArray.readInt16ByteOffsetGuest(owner, 9, true));
        assertThrows(RuntimeFault.class, () -> ManagedByteArray.writeInt16ByteOffsetGuest(owner, 9, 1));
        assertSame(target, owner.readAddressByteOffset(8));
        var frame = Truffle.getRuntime().createVirtualFrame(new Object[0], FrameDescriptor.newBuilder().build());
        var write = expression(ByteArrayOp.WRITE_WORD8_AS_INT16, CoreRepresentation.UNKNOWN,
            new Expr[] {operand(bytes), operand(1L), operand(7L), operand("invalid state")});
        assertThrows(RuntimeFault.class, () -> write.execute(frame));
        assertThrows(RuntimeFault.class, () -> BytecodeRoot.WriteInt16Array.write(true, bytes, 5L, 7, "invalid state"));
        assertEquals(-32768L, (long) ManagedByteArray.readInt16ByteOffsetGuest(bytes, 1, false));
        assertEquals(0x8001L, (long) ManagedByteArray.readInt16ByteOffsetGuest(bytes, 5, true));
    }
}
