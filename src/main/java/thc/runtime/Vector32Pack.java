// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node.Child;
import jdk.incubator.vector.IntVector;

public final class Vector32Pack extends Expr {
    @Child private Expr argument;
    @CompilationFinal(dimensions = 1) private final int[] slots;

    public Vector32Pack(Expr argument, int[] slots) {
        this.argument = argument;
        this.slots = slots;
        argument.prepareTuple(slots, 0);
        setRepresentation(CoreVectors.proof32);
    }

    @Override public IntVector execute(VirtualFrame frame) {
        argument.executeTuple(frame, slots, 0);
        return pack(frame.getInt(slots[0]), frame.getInt(slots[1]), frame.getInt(slots[2]), frame.getInt(slots[3]));
    }

    private static IntVector pack(int lane0, int lane1, int lane2, int lane3) {
        return IntVector.broadcast(IntVector.SPECIES_128, lane0)
            .withLane(1, lane1)
            .withLane(2, lane2)
            .withLane(3, lane3);
    }
}
