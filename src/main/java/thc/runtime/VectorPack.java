// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node.Child;
import jdk.incubator.vector.LongVector;

public final class VectorPack extends Expr {
    @Child private Expr argument;
    @CompilationFinal(dimensions = 1) private final int[] slots;

    public VectorPack(Expr argument, int[] slots) {
        this.argument = argument;
        this.slots = slots;
        argument.prepareTuple(slots, 0);
        setRepresentation(CoreVectors.proof);
    }

    @Override public LongVector execute(VirtualFrame frame) {
        argument.executeTuple(frame, slots, 0);
        return pack(frame.getLong(slots[0]), frame.getLong(slots[1]));
    }

    private static LongVector pack(long lane0, long lane1) {
        return LongVector.broadcast(LongVector.SPECIES_128, lane0)
            .withLane(1, lane1);
    }
}
