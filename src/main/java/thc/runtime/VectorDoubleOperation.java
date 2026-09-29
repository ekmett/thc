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
            case 0 -> broadcast(arguments[0].executeRequiredDouble(frame));
            case 1 -> vector(arguments[0].execute(frame)).add(vector(arguments[1].execute(frame)));
            case 2 -> vector(arguments[0].execute(frame)).sub(vector(arguments[1].execute(frame)));
            case 3 -> vector(arguments[0].execute(frame)).mul(vector(arguments[1].execute(frame)));
            case 4, 5, 6, 7 -> fused(operation - 4, vector(arguments[0].execute(frame)), vector(arguments[1].execute(frame)), vector(arguments[2].execute(frame)));
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

    private static DoubleVector broadcast(double value) {
        return DoubleVector.broadcast(DoubleVector.SPECIES_128, value);
    }

    private static DoubleVector vector(Object value) {
        return RuntimeTypes.requireDouble(value, DoubleVector.SPECIES_128);
    }
}
