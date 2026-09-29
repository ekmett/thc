// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node.Children;
import jdk.incubator.vector.DoubleVector;

public final class VectorDouble8Fused extends Expr {
    private final int operation;
    @Children private Expr[] arguments;

    public VectorDouble8Fused(String name, Expr[] arguments) {
        this.operation = CoreVectors.fusedDouble8.indexOf(name);
        this.arguments = arguments;
        setRepresentation(GeneratedVectors.proofDoubleX8);
    }

    @Override public DoubleVector execute(VirtualFrame frame) {
        if (operation < 0 || operation > 3) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw new RuntimeFault("Invalid DoubleX8 fused operation");
        }
        DoubleVector a = vector(arguments[0].execute(frame));
        DoubleVector b = vector(arguments[1].execute(frame));
        DoubleVector c = vector(arguments[2].execute(frame));
        DoubleVector left = operation >= 2 ? a.neg() : a;
        DoubleVector addend = (operation & 1) != 0 ? c.neg() : c;
        return left.fma(b, addend);
    }

    private static DoubleVector vector(Object value) {
        return RuntimeTypes.requireDouble(value, DoubleVector.SPECIES_512);
    }
}
