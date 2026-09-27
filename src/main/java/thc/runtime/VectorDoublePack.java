// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node.Child;
import jdk.incubator.vector.DoubleVector;

public final class VectorDoublePack extends Expr {
    @Child private Expr argument;
    @CompilationFinal(dimensions = 1) private final int[] slots;

    public VectorDoublePack(Expr argument, int[] slots) {
        this.argument = argument;
        this.slots = slots;
        setRepresentation(CoreVectors.INSTANCE.getProofDouble());
    }

    @Override public DoubleVector execute(VirtualFrame frame) {
        argument.executeTuple(frame, slots, 0);
        return DoubleVector.broadcast(DoubleVector.SPECIES_128, frame.getDouble(slots[0]))
            .withLane(1, frame.getDouble(slots[1]));
    }
}
