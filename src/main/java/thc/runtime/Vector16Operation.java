// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node.Children;
import jdk.incubator.vector.ShortVector;

public final class Vector16Operation extends Expr {
    private final int operation;
    @Children private Expr[] arguments;

    public Vector16Operation(String name, Expr[] arguments) {
        this.operation = switch (name) {
            case "broadcastInt16X8#" -> 0;
            case "plusInt16X8#" -> 1;
            case "minusInt16X8#" -> 2;
            case "negateInt16X8#" -> 3;
            case "timesInt16X8#" -> 4;
            default -> throw new RuntimeFault("Invalid Int16X8 operation");
        };
        this.arguments = arguments;
        setRepresentation(CoreVectors.proof16);
    }

    @Override public ShortVector execute(VirtualFrame frame) {
        return switch (operation) {
            case 0 -> broadcast((short) arguments[0].executeRequiredInt(frame));
            case 1 -> vector(arguments[0].execute(frame)).add(vector(arguments[1].execute(frame)));
            case 2 -> vector(arguments[0].execute(frame)).sub(vector(arguments[1].execute(frame)));
            case 3 -> vector(arguments[0].execute(frame)).neg();
            case 4 -> vector(arguments[0].execute(frame)).mul(vector(arguments[1].execute(frame)));
            default -> {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                throw new RuntimeFault("Invalid Int16X8 operation");
            }
        };
    }

    private static ShortVector broadcast(short value) {
        return ShortVector.broadcast(ShortVector.SPECIES_128, value);
    }

    private static ShortVector vector(Object value) {
        return RuntimeTypes.requireShort(value, ShortVector.SPECIES_128);
    }
}
