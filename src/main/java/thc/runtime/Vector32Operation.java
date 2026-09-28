// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node.Children;
import jdk.incubator.vector.IntVector;

public final class Vector32Operation extends Expr {
    private final String operation;
    @Children private Expr[] arguments;

    public Vector32Operation(String name, Expr[] arguments) {
        this.operation = name;
        this.arguments = arguments;
        setRepresentation(CoreVectors.proof32);
    }

    @Override public IntVector execute(VirtualFrame frame) {
        return switch (operation) {
            case "broadcastInt32X4#" -> IntVector.broadcast(IntVector.SPECIES_128, arguments[0].executeRequiredInt(frame));
            case "plusInt32X4#" -> vector(frame, 0).add(vector(frame, 1));
            case "minusInt32X4#" -> vector(frame, 0).sub(vector(frame, 1));
            case "timesInt32X4#" -> vector(frame, 0).mul(vector(frame, 1));
            case "negateInt32X4#" -> vector(frame, 0).neg();
            default -> {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                throw new RuntimeFault("Invalid vector operation");
            }
        };
    }

    private IntVector vector(VirtualFrame frame, int index) {
        return CoreVectors.requireInt(arguments[index].execute(frame), IntVector.SPECIES_128);
    }
}
