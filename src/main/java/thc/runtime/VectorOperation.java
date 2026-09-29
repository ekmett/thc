// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node.Children;
import jdk.incubator.vector.LongVector;

public final class VectorOperation extends Expr {
    private final String operation;
    @Children private Expr[] arguments;

    public VectorOperation(String name, Expr[] arguments) {
        this.operation = name;
        this.arguments = arguments;
        setRepresentation(CoreVectors.proof);
    }

    @Override public LongVector execute(VirtualFrame frame) {
        return switch (operation) {
            case "broadcastInt64X2#" -> LongVector.broadcast(LongVector.SPECIES_128, arguments[0].executeRequiredLong(frame));
            case "plusInt64X2#" -> vector(arguments[0].execute(frame)).add(vector(arguments[1].execute(frame)));
            case "minusInt64X2#" -> vector(arguments[0].execute(frame)).sub(vector(arguments[1].execute(frame)));
            case "negateInt64X2#" -> vector(arguments[0].execute(frame)).neg();
            default -> {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                throw new RuntimeFault("Invalid vector operation");
            }
        };
    }

    private static LongVector vector(Object value) {
        return RuntimeTypes.requireLong(value, LongVector.SPECIES_128);
    }
}
