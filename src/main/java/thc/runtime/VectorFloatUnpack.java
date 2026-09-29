// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node.Child;
import jdk.incubator.vector.FloatVector;

public final class VectorFloatUnpack extends Expr {
    @Child private Expr argument;

    public VectorFloatUnpack(Expr argument) {
        this.argument = argument;
        setRepresentation(CoreVectors.unpackedFloat);
    }

    @Override public Object execute(VirtualFrame frame) {
        CompilerDirectives.transferToInterpreterAndInvalidate();
        throw new RuntimeFault("Vector unpack requires a tuple destination");
    }

    @Override public Object executeTuple(VirtualFrame frame, int[] slots, int offset) {
        FloatVector value = RuntimeTypes.requireFloat(argument.execute(frame), FloatVector.SPECIES_128);
        FrameAccess.INSTANCE.writeFloat(frame, slots[offset], value.lane(0));
        FrameAccess.INSTANCE.writeFloat(frame, slots[offset + 1], value.lane(1));
        FrameAccess.INSTANCE.writeFloat(frame, slots[offset + 2], value.lane(2));
        FrameAccess.INSTANCE.writeFloat(frame, slots[offset + 3], value.lane(3));
        return null;
    }
}
