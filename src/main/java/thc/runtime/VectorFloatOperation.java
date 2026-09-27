// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node.Children;
import jdk.incubator.vector.FloatVector;

public final class VectorFloatOperation extends Expr {
    private final int operation;
    @Children private Expr[] arguments;

    public VectorFloatOperation(String name, Expr[] arguments) {
        this.operation = switch (name) {
            case "broadcastFloatX4#" -> 0;
            case "plusFloatX4#" -> 1;
            case "minusFloatX4#" -> 2;
            case "timesFloatX4#" -> 3;
            default -> {
                int fused = CoreVectors.INSTANCE.getFusedFloat().indexOf(name);
                if (fused < 0) throw new RuntimeFault("Invalid FloatX4 operation");
                yield 4 + fused;
            }
        };
        this.arguments = arguments;
        setRepresentation(CoreVectors.INSTANCE.getProofFloat());
    }

    @Override public FloatVector execute(VirtualFrame frame) {
        return switch (operation) {
            case 0 -> FloatVector.broadcast(FloatVector.SPECIES_128, arguments[0].executeRequiredFloat(frame));
            case 1 -> vector(frame, 0).add(vector(frame, 1));
            case 2 -> vector(frame, 0).sub(vector(frame, 1));
            case 3 -> vector(frame, 0).mul(vector(frame, 1));
            case 4, 5, 6, 7 -> fused(operation - 4, vector(frame, 0), vector(frame, 1), vector(frame, 2));
            default -> {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                throw new RuntimeFault("Invalid FloatX4 operation");
            }
        };
    }

    private FloatVector fused(int operation, FloatVector a, FloatVector b, FloatVector c) {
        FloatVector left = operation >= 2 ? a.neg() : a;
        FloatVector addend = (operation & 1) != 0 ? c.neg() : c;
        return left.fma(b, addend);
    }

    private FloatVector vector(VirtualFrame frame, int index) {
        return CoreVectors.requireFloat(arguments[index].execute(frame), FloatVector.SPECIES_128);
    }
}
