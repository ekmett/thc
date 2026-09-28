// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node.Children;
import jdk.incubator.vector.DoubleVector;

public final class VectorDoubleOperation extends Expr {
    private final int operation;
    @Children private Expr[] arguments;

    public VectorDoubleOperation(String name, Expr[] arguments) {
        this.operation = switch (name) {
            case "broadcastDoubleX2#" -> 0;
            case "plusDoubleX2#" -> 1;
            case "minusDoubleX2#" -> 2;
            case "timesDoubleX2#" -> 3;
            default -> {
                int fused = CoreVectors.fusedDouble.indexOf(name);
                if (fused < 0) throw new RuntimeFault("Invalid DoubleX2 operation");
                yield 4 + fused;
            }
        };
        this.arguments = arguments;
        setRepresentation(CoreVectors.proofDouble);
    }

    @Override public DoubleVector execute(VirtualFrame frame) {
        return switch (operation) {
            case 0 -> DoubleVector.broadcast(DoubleVector.SPECIES_128, arguments[0].executeRequiredDouble(frame));
            case 1 -> vector(frame, 0).add(vector(frame, 1));
            case 2 -> vector(frame, 0).sub(vector(frame, 1));
            case 3 -> vector(frame, 0).mul(vector(frame, 1));
            case 4, 5, 6, 7 -> fused(operation - 4, vector(frame, 0), vector(frame, 1), vector(frame, 2));
            default -> {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                throw new RuntimeFault("Invalid DoubleX2 operation");
            }
        };
    }

    private DoubleVector fused(int operation, DoubleVector a, DoubleVector b, DoubleVector c) {
        DoubleVector left = operation >= 2 ? a.neg() : a;
        DoubleVector addend = (operation & 1) != 0 ? c.neg() : c;
        return left.fma(b, addend);
    }

    private DoubleVector vector(VirtualFrame frame, int index) {
        return CoreVectors.requireDouble(arguments[index].execute(frame), DoubleVector.SPECIES_128);
    }
}
