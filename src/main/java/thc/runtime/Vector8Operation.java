// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node.Children;
import jdk.incubator.vector.ByteVector;

public final class Vector8Operation extends Expr {
    private final int operation;
    @Children private Expr[] arguments;

    public Vector8Operation(String name, Expr[] arguments) {
        this.operation = switch (name) {
            case "broadcastInt8X16#" -> 0;
            case "plusInt8X16#" -> 1;
            case "minusInt8X16#" -> 2;
            case "negateInt8X16#" -> 3;
            case "timesInt8X16#" -> 4;
            default -> throw new RuntimeFault("Invalid Int8X16 operation");
        };
        this.arguments = arguments;
        setRepresentation(CoreVectors.proof8);
    }

    @Override public ByteVector execute(VirtualFrame frame) {
        return switch (operation) {
            case 0 -> broadcast((byte) arguments[0].executeRequiredInt(frame));
            case 1 -> vector(arguments[0].execute(frame)).add(vector(arguments[1].execute(frame)));
            case 2 -> vector(arguments[0].execute(frame)).sub(vector(arguments[1].execute(frame)));
            case 3 -> vector(arguments[0].execute(frame)).neg();
            case 4 -> vector(arguments[0].execute(frame)).mul(vector(arguments[1].execute(frame)));
            default -> {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                throw new RuntimeFault("Invalid Int8X16 operation");
            }
        };
    }

    private static ByteVector broadcast(byte value) {
        return ByteVector.broadcast(ByteVector.SPECIES_128, value);
    }

    private static ByteVector vector(Object value) {
        return RuntimeTypes.requireByte(value, ByteVector.SPECIES_128);
    }
}
