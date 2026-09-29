// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node.Child;
import jdk.incubator.vector.LongVector;

public final class VectorUnpack extends Expr {
    @Child private Expr argument;

    public VectorUnpack(Expr argument) {
        this.argument = argument;
        setRepresentation(CoreVectors.unpacked);
    }

    @Override public Object execute(VirtualFrame frame) {
        CompilerDirectives.transferToInterpreterAndInvalidate();
        throw new RuntimeFault("Vector unpack requires a tuple destination");
    }

    @Override public Object executeTuple(VirtualFrame frame, int[] slots, int offset) {
        LongVector value = vector(argument.execute(frame));
        FrameAccess.INSTANCE.writeLong(frame, slots[offset], value.lane(0));
        FrameAccess.INSTANCE.writeLong(frame, slots[offset + 1], value.lane(1));
        return null;
    }

    private static LongVector vector(Object value) {
        return RuntimeTypes.requireLong(value, LongVector.SPECIES_128);
    }
}
