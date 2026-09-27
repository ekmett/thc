// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node.Child;
import jdk.incubator.vector.IntVector;

public final class VectorWord32Pack extends Expr {
    @Child private Expr argument;
    @CompilationFinal(dimensions = 1) private final int[] slots;

    public VectorWord32Pack(Expr argument, int[] slots) {
        this.argument = argument;
        this.slots = slots;
        setRepresentation(CoreVectors.INSTANCE.getProofWord32());
    }

    @Override public IntVector execute(VirtualFrame frame) {
        argument.executeTuple(frame, slots, 0);
        return IntVector.broadcast(IntVector.SPECIES_128, frame.getInt(slots[0]))
            .withLane(1, frame.getInt(slots[1]))
            .withLane(2, frame.getInt(slots[2]))
            .withLane(3, frame.getInt(slots[3]));
    }
}
