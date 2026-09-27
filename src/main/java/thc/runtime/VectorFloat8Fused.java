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
        this.operation = CoreVectors.INSTANCE.getFusedFloat8().indexOf(name);
        this.arguments = arguments;
        setRepresentation(GeneratedVectors.INSTANCE.getProofFloatX8());
    }

    @Override public FloatVector execute(VirtualFrame frame) {
        if (operation < 0 || operation > 3) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw new RuntimeFault("Invalid FloatX8 fused operation");
        }
        FloatVector a = vector(frame, 0);
        FloatVector b = vector(frame, 1);
        FloatVector c = vector(frame, 2);
        FloatVector left = operation >= 2 ? a.neg() : a;
        FloatVector addend = (operation & 1) != 0 ? c.neg() : c;
        return left.fma(b, addend);
    }

    private FloatVector vector(VirtualFrame frame, int index) {
        return CoreVectors.requireFloat(arguments[index].execute(frame), FloatVector.SPECIES_256);
    }
}
