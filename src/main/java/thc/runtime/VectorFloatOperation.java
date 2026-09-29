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
                int fused = CoreVectors.fusedFloat.indexOf(name);
                if (fused < 0) throw new RuntimeFault("Invalid FloatX4 operation");
                yield 4 + fused;
            }
        };
        this.arguments = arguments;
        setRepresentation(CoreVectors.proofFloat);
    }

    @Override public FloatVector execute(VirtualFrame frame) {
        return switch (operation) {
            case 0 -> broadcast(arguments[0].executeRequiredFloat(frame));
            case 1 -> vector(arguments[0].execute(frame)).add(vector(arguments[1].execute(frame)));
            case 2 -> vector(arguments[0].execute(frame)).sub(vector(arguments[1].execute(frame)));
            case 3 -> vector(arguments[0].execute(frame)).mul(vector(arguments[1].execute(frame)));
            case 4, 5, 6, 7 -> fused(operation - 4, vector(arguments[0].execute(frame)), vector(arguments[1].execute(frame)), vector(arguments[2].execute(frame)));
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

    private static FloatVector broadcast(float value) {
        return FloatVector.broadcast(FloatVector.SPECIES_128, value);
    }

    private static FloatVector vector(Object value) {
        return RuntimeTypes.requireFloat(value, FloatVector.SPECIES_128);
    }
}
