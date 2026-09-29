// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node.Child;
import jdk.incubator.vector.FloatVector;

public final class VectorFloatPack extends Expr {
    @Child private Expr argument;
    @CompilationFinal(dimensions = 1) private final int[] slots;

    public VectorFloatPack(Expr argument, int[] slots) {
        this.argument = argument;
        this.slots = slots;
        argument.prepareTuple(slots, 0);
        setRepresentation(CoreVectors.proofFloat);
    }

    @Override public FloatVector execute(VirtualFrame frame) {
        argument.executeTuple(frame, slots, 0);
        return pack(frame.getFloat(slots[0]), frame.getFloat(slots[1]), frame.getFloat(slots[2]), frame.getFloat(slots[3]));
    }

    private static FloatVector pack(float lane0, float lane1, float lane2, float lane3) {
        return FloatVector.broadcast(FloatVector.SPECIES_128, lane0)
            .withLane(1, lane1)
            .withLane(2, lane2)
            .withLane(3, lane3);
    }
}
