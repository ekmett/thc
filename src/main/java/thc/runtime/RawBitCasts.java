// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime;

import com.oracle.truffle.api.frame.VirtualFrame;
import java.util.List;

final class RawBitCasts {
    private RawBitCasts() {}

    static Expr rawBitCastPrimitive(String name, Expr[] arguments) {
        switch (name) {
            case "castFloatToWord32#", "castWord32ToFloat#", "castDoubleToWord64#", "castWord64ToDouble#":
                if (arguments.length != 1) throw new RuntimeFault("Primitive arity mismatch: " + name);
                break;
            default:
                return null;
        }
        return switch (name) {
            case "castFloatToWord32#" -> new FloatToWord32(arguments[0]);
            case "castWord32ToFloat#" -> new Word32ToFloat(arguments[0]);
            case "castDoubleToWord64#" -> new DoubleToWord64(arguments[0]);
            default -> new Word64ToDouble(arguments[0]);
        };
    }

    private static final class FloatToWord32 extends Expr {
        @Child private Expr value;

        FloatToWord32(Expr value) {
            this.value = value;
            setRepresentation(new CoreRepresentation(CoreKind.LONG, true, false, List.of("Word32Rep"), null, null, null, null, null));
        }

        @Override public Object execute(VirtualFrame frame) { return executeInt(frame); }
        @Override public int executeInt(VirtualFrame frame) { return Float.floatToRawIntBits(value.executeRequiredFloat(frame)); }
    }

    private static final class Word32ToFloat extends Expr {
        @Child private Expr value;

        Word32ToFloat(Expr value) {
            this.value = value;
            setRepresentation(new CoreRepresentation(CoreKind.FLOAT, true, false, List.of("FloatRep"), null, null, null, null, null));
        }

        @Override public Object execute(VirtualFrame frame) { return executeFloat(frame); }
        @Override public float executeFloat(VirtualFrame frame) { return Float.intBitsToFloat(value.executeRequiredInt(frame)); }
    }

    private static final class DoubleToWord64 extends Expr {
        @Child private Expr value;

        DoubleToWord64(Expr value) {
            this.value = value;
            setRepresentation(new CoreRepresentation(CoreKind.LONG, true, false, List.of("Word64Rep"), null, null, null, null, null));
        }

        @Override public Object execute(VirtualFrame frame) { return executeLong(frame); }
        @Override public long executeLong(VirtualFrame frame) { return Double.doubleToRawLongBits(value.executeRequiredDouble(frame)); }
    }

    private static final class Word64ToDouble extends Expr {
        @Child private Expr value;

        Word64ToDouble(Expr value) {
            this.value = value;
            setRepresentation(new CoreRepresentation(CoreKind.DOUBLE, true, false, List.of("DoubleRep"), null, null, null, null, null));
        }

        @Override public Object execute(VirtualFrame frame) { return executeDouble(frame); }
        @Override public double executeDouble(VirtualFrame frame) { return Double.longBitsToDouble(value.executeRequiredLong(frame)); }
    }
}
