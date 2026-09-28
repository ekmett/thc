// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node.Children;
import jdk.incubator.vector.IntVector;

public final class VectorWord32Operation extends Expr {
    private final int operation;
    @Children private Expr[] arguments;

    public VectorWord32Operation(String name, Expr[] arguments) {
        this.operation = switch (name) {
            case "broadcastWord32X4#" -> 0;
            case "plusWord32X4#" -> 1;
            case "minusWord32X4#" -> 2;
            case "timesWord32X4#" -> 3;
            default -> throw new RuntimeFault("Invalid Word32X4 operation");
        };
        this.arguments = arguments;
        setRepresentation(CoreVectors.proofWord32);
    }

    @Override public IntVector execute(VirtualFrame frame) {
        return switch (operation) {
            case 0 -> IntVector.broadcast(IntVector.SPECIES_128, arguments[0].executeRequiredInt(frame));
            case 1 -> vector(arguments[0].execute(frame)).add(vector(arguments[1].execute(frame)));
            case 2 -> vector(arguments[0].execute(frame)).sub(vector(arguments[1].execute(frame)));
            case 3 -> vector(arguments[0].execute(frame)).mul(vector(arguments[1].execute(frame)));
            default -> {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                throw new RuntimeFault("Invalid Word32X4 operation");
            }
        };
    }

    private static IntVector vector(Object value) {
        return RuntimeTypes.requireInt(value, IntVector.SPECIES_128);
    }
}
