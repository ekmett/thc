// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node.Child;
import jdk.incubator.vector.ByteVector;

public final class VectorWord8Unpack extends Expr {
    @Child private Expr argument;

    public VectorWord8Unpack(Expr argument) {
        this.argument = argument;
        setRepresentation(CoreVectors.unpackedWord8);
    }

    @Override public Object execute(VirtualFrame frame) {
        CompilerDirectives.transferToInterpreterAndInvalidate();
        throw new RuntimeFault("Vector unpack requires a tuple destination");
    }

    @Override public Object executeTuple(VirtualFrame frame, int[] slots, int offset) {
        ByteVector value = CoreVectors.requireByte(argument.execute(frame), ByteVector.SPECIES_128);
        FrameAccess.INSTANCE.writeInt(frame, slots[offset], value.lane(0) & 0xff);
        FrameAccess.INSTANCE.writeInt(frame, slots[offset + 1], value.lane(1) & 0xff);
        FrameAccess.INSTANCE.writeInt(frame, slots[offset + 2], value.lane(2) & 0xff);
        FrameAccess.INSTANCE.writeInt(frame, slots[offset + 3], value.lane(3) & 0xff);
        FrameAccess.INSTANCE.writeInt(frame, slots[offset + 4], value.lane(4) & 0xff);
        FrameAccess.INSTANCE.writeInt(frame, slots[offset + 5], value.lane(5) & 0xff);
        FrameAccess.INSTANCE.writeInt(frame, slots[offset + 6], value.lane(6) & 0xff);
        FrameAccess.INSTANCE.writeInt(frame, slots[offset + 7], value.lane(7) & 0xff);
        FrameAccess.INSTANCE.writeInt(frame, slots[offset + 8], value.lane(8) & 0xff);
        FrameAccess.INSTANCE.writeInt(frame, slots[offset + 9], value.lane(9) & 0xff);
        FrameAccess.INSTANCE.writeInt(frame, slots[offset + 10], value.lane(10) & 0xff);
        FrameAccess.INSTANCE.writeInt(frame, slots[offset + 11], value.lane(11) & 0xff);
        FrameAccess.INSTANCE.writeInt(frame, slots[offset + 12], value.lane(12) & 0xff);
        FrameAccess.INSTANCE.writeInt(frame, slots[offset + 13], value.lane(13) & 0xff);
        FrameAccess.INSTANCE.writeInt(frame, slots[offset + 14], value.lane(14) & 0xff);
        FrameAccess.INSTANCE.writeInt(frame, slots[offset + 15], value.lane(15) & 0xff);
        return null;
    }
}
