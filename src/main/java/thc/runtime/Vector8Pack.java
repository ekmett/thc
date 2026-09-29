// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node.Child;
import jdk.incubator.vector.ByteVector;

public final class Vector8Pack extends Expr {
    @Child private Expr argument;
    @CompilationFinal(dimensions = 1) private final int[] slots;

    public Vector8Pack(Expr argument, int[] slots) {
        this.argument = argument;
        this.slots = slots;
        argument.prepareTuple(slots, 0);
        setRepresentation(CoreVectors.proof8);
    }

    @Override public ByteVector execute(VirtualFrame frame) {
        argument.executeTuple(frame, slots, 0);
        return pack((byte) frame.getInt(slots[0]), (byte) frame.getInt(slots[1]), (byte) frame.getInt(slots[2]), (byte) frame.getInt(slots[3]), (byte) frame.getInt(slots[4]), (byte) frame.getInt(slots[5]), (byte) frame.getInt(slots[6]), (byte) frame.getInt(slots[7]), (byte) frame.getInt(slots[8]), (byte) frame.getInt(slots[9]), (byte) frame.getInt(slots[10]), (byte) frame.getInt(slots[11]), (byte) frame.getInt(slots[12]), (byte) frame.getInt(slots[13]), (byte) frame.getInt(slots[14]), (byte) frame.getInt(slots[15]));
    }

    private static ByteVector pack(byte lane0, byte lane1, byte lane2, byte lane3, byte lane4, byte lane5, byte lane6, byte lane7, byte lane8, byte lane9, byte lane10, byte lane11, byte lane12, byte lane13, byte lane14, byte lane15) {
        return ByteVector.broadcast(ByteVector.SPECIES_128, lane0)
            .withLane(1, lane1)
            .withLane(2, lane2)
            .withLane(3, lane3)
            .withLane(4, lane4)
            .withLane(5, lane5)
            .withLane(6, lane6)
            .withLane(7, lane7)
            .withLane(8, lane8)
            .withLane(9, lane9)
            .withLane(10, lane10)
            .withLane(11, lane11)
            .withLane(12, lane12)
            .withLane(13, lane13)
            .withLane(14, lane14)
            .withLane(15, lane15);
    }
}
