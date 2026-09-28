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
        setRepresentation(CoreVectors.proof8);
    }

    @Override public ByteVector execute(VirtualFrame frame) {
        argument.executeTuple(frame, slots, 0);
        return ByteVector.broadcast(ByteVector.SPECIES_128, (byte) frame.getInt(slots[0]))
            .withLane(1, (byte) frame.getInt(slots[1]))
            .withLane(2, (byte) frame.getInt(slots[2]))
            .withLane(3, (byte) frame.getInt(slots[3]))
            .withLane(4, (byte) frame.getInt(slots[4]))
            .withLane(5, (byte) frame.getInt(slots[5]))
            .withLane(6, (byte) frame.getInt(slots[6]))
            .withLane(7, (byte) frame.getInt(slots[7]))
            .withLane(8, (byte) frame.getInt(slots[8]))
            .withLane(9, (byte) frame.getInt(slots[9]))
            .withLane(10, (byte) frame.getInt(slots[10]))
            .withLane(11, (byte) frame.getInt(slots[11]))
            .withLane(12, (byte) frame.getInt(slots[12]))
            .withLane(13, (byte) frame.getInt(slots[13]))
            .withLane(14, (byte) frame.getInt(slots[14]))
            .withLane(15, (byte) frame.getInt(slots[15]));
    }
}
