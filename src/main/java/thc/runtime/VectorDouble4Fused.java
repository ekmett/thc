// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node.Children;
import jdk.incubator.vector.DoubleVector;

public final class VectorDouble4Fused extends Expr {
    private final int operation;
    @Children private Expr[] arguments;

    public VectorDouble4Fused(String name, Expr[] arguments) {
        this.operation = CoreVectors.INSTANCE.getFusedDouble4().indexOf(name);
        this.arguments = arguments;
        setRepresentation(GeneratedVectors.INSTANCE.getProofDoubleX4());
    }

    @Override public DoubleVector execute(VirtualFrame frame) {
        if (operation < 0 || operation > 3) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw new RuntimeFault("Invalid DoubleX4 fused operation");
        }
        DoubleVector a = vector(frame, 0);
        DoubleVector b = vector(frame, 1);
        DoubleVector c = vector(frame, 2);
        DoubleVector left = operation >= 2 ? a.neg() : a;
        DoubleVector addend = (operation & 1) != 0 ? c.neg() : c;
        return left.fma(b, addend);
    }

    private DoubleVector vector(VirtualFrame frame, int index) {
        return CoreVectors.requireDouble(arguments[index].execute(frame), DoubleVector.SPECIES_256);
    }
}
