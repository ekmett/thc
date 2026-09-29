// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node.Child;
import jdk.incubator.vector.DoubleVector;

public final class VectorDoubleUnpack extends Expr {
    @Child private Expr argument;

    public VectorDoubleUnpack(Expr argument) {
        this.argument = argument;
        setRepresentation(CoreVectors.unpackedDouble);
    }

    @Override public Object execute(VirtualFrame frame) {
        CompilerDirectives.transferToInterpreterAndInvalidate();
        throw new RuntimeFault("Vector unpack requires a tuple destination");
    }

    @Override public Object executeTuple(VirtualFrame frame, int[] slots, int offset) {
        DoubleVector value = vector(argument.execute(frame));
        FrameAccess.INSTANCE.writeDouble(frame, slots[offset], value.lane(0));
        FrameAccess.INSTANCE.writeDouble(frame, slots[offset + 1], value.lane(1));
        return null;
    }

    private static DoubleVector vector(Object value) {
        return RuntimeTypes.requireDouble(value, DoubleVector.SPECIES_128);
    }
}
