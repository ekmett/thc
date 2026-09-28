// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node.Child;
import jdk.incubator.vector.ShortVector;

public final class Vector16Unpack extends Expr {
    @Child private Expr argument;

    public Vector16Unpack(Expr argument) {
        this.argument = argument;
        setRepresentation(CoreVectors.unpacked16);
    }

    @Override public Object execute(VirtualFrame frame) {
        CompilerDirectives.transferToInterpreterAndInvalidate();
        throw new RuntimeFault("Vector unpack requires a tuple destination");
    }

    @Override public Object executeTuple(VirtualFrame frame, int[] slots, int offset) {
        ShortVector value = RuntimeTypes.requireShort(argument.execute(frame), ShortVector.SPECIES_128);
        FrameAccess.INSTANCE.writeInt(frame, slots[offset], value.lane(0));
        FrameAccess.INSTANCE.writeInt(frame, slots[offset + 1], value.lane(1));
        FrameAccess.INSTANCE.writeInt(frame, slots[offset + 2], value.lane(2));
        FrameAccess.INSTANCE.writeInt(frame, slots[offset + 3], value.lane(3));
        FrameAccess.INSTANCE.writeInt(frame, slots[offset + 4], value.lane(4));
        FrameAccess.INSTANCE.writeInt(frame, slots[offset + 5], value.lane(5));
        FrameAccess.INSTANCE.writeInt(frame, slots[offset + 6], value.lane(6));
        FrameAccess.INSTANCE.writeInt(frame, slots[offset + 7], value.lane(7));
        return null;
    }
}
