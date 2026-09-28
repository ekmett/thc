// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node.Child;
import jdk.incubator.vector.ShortVector;

public final class VectorWord16Pack extends Expr {
    @Child private Expr argument;
    @CompilationFinal(dimensions = 1) private final int[] slots;

    public VectorWord16Pack(Expr argument, int[] slots) {
        this.argument = argument;
        this.slots = slots;
        setRepresentation(CoreVectors.proofWord16);
    }

    @Override public ShortVector execute(VirtualFrame frame) {
        argument.executeTuple(frame, slots, 0);
        return ShortVector.broadcast(ShortVector.SPECIES_128, (short) frame.getInt(slots[0]))
            .withLane(1, (short) frame.getInt(slots[1]))
            .withLane(2, (short) frame.getInt(slots[2]))
            .withLane(3, (short) frame.getInt(slots[3]))
            .withLane(4, (short) frame.getInt(slots[4]))
            .withLane(5, (short) frame.getInt(slots[5]))
            .withLane(6, (short) frame.getInt(slots[6]))
            .withLane(7, (short) frame.getInt(slots[7]));
    }
}
