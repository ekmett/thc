// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node.Children;
import jdk.incubator.vector.ByteVector;

public final class VectorWord8Operation extends Expr {
    private final int operation;
    @Children private Expr[] arguments;

    public VectorWord8Operation(String name, Expr[] arguments) {
        this.operation = switch (name) {
            case "broadcastWord8X16#" -> 0;
            case "plusWord8X16#" -> 1;
            case "minusWord8X16#" -> 2;
            case "timesWord8X16#" -> 3;
            default -> throw new RuntimeFault("Invalid Word8X16 operation");
        };
        this.arguments = arguments;
        setRepresentation(CoreVectors.proofWord8);
    }

    @Override public ByteVector execute(VirtualFrame frame) {
        return switch (operation) {
            case 0 -> ByteVector.broadcast(ByteVector.SPECIES_128, (byte) arguments[0].executeRequiredInt(frame));
            case 1 -> vector(arguments[0].execute(frame)).add(vector(arguments[1].execute(frame)));
            case 2 -> vector(arguments[0].execute(frame)).sub(vector(arguments[1].execute(frame)));
            case 3 -> vector(arguments[0].execute(frame)).mul(vector(arguments[1].execute(frame)));
            default -> {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                throw new RuntimeFault("Invalid Word8X16 operation");
            }
        };
    }

    private static ByteVector vector(Object value) {
        return CoreVectors.requireByte(value, ByteVector.SPECIES_128);
    }
}
