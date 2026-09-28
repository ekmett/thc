// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node.Children;
import jdk.incubator.vector.FloatVector;

public final class VectorFloat8Fused extends Expr {
    private final int operation;
    @Children private Expr[] arguments;

    public VectorFloat8Fused(String name, Expr[] arguments) {
        this.operation = CoreVectors.fusedFloat8.indexOf(name);
        this.arguments = arguments;
        setRepresentation(GeneratedVectors.proofFloatX8);
    }

    @Override public FloatVector execute(VirtualFrame frame) {
        if (operation < 0 || operation > 3) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw new RuntimeFault("Invalid FloatX8 fused operation");
        }
        FloatVector a = vector(arguments[0].execute(frame));
        FloatVector b = vector(arguments[1].execute(frame));
        FloatVector c = vector(arguments[2].execute(frame));
        FloatVector left = operation >= 2 ? a.neg() : a;
        FloatVector addend = (operation & 1) != 0 ? c.neg() : c;
        return left.fma(b, addend);
    }

    private static FloatVector vector(Object value) {
        return CoreVectors.requireFloat(value, FloatVector.SPECIES_256);
    }
}
