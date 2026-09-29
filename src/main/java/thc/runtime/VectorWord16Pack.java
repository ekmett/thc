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
        argument.prepareTuple(slots, 0);
        setRepresentation(CoreVectors.proofWord16);
    }

    @Override public ShortVector execute(VirtualFrame frame) {
        argument.executeTuple(frame, slots, 0);
        return pack((short) frame.getInt(slots[0]), (short) frame.getInt(slots[1]), (short) frame.getInt(slots[2]), (short) frame.getInt(slots[3]), (short) frame.getInt(slots[4]), (short) frame.getInt(slots[5]), (short) frame.getInt(slots[6]), (short) frame.getInt(slots[7]));
    }

    private static ShortVector pack(short lane0, short lane1, short lane2, short lane3, short lane4, short lane5, short lane6, short lane7) {
        return ShortVector.broadcast(ShortVector.SPECIES_128, lane0)
            .withLane(1, lane1)
            .withLane(2, lane2)
            .withLane(3, lane3)
            .withLane(4, lane4)
            .withLane(5, lane5)
            .withLane(6, lane6)
            .withLane(7, lane7);
    }
}
