// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.frame.VirtualFrame;

final class FloatingPrimitives {
    private FloatingPrimitives() {}

    // Resolve names during lowering. String equality may simplify after frame
    // virtualization, leaving unreachable wrong-kind frame reads visible to PEA.
    private static final int FLOAT_ADD = 0;
    private static final int FLOAT_SUB = 1;
    private static final int FLOAT_MUL = 2;
    private static final int FLOAT_DIV = 3;
    private static final int FLOAT_NEG = 4;
    private static final int DOUBLE_ADD = 5;
    private static final int DOUBLE_SUB = 6;
    private static final int DOUBLE_MUL = 7;
    private static final int DOUBLE_DIV = 8;
    private static final int DOUBLE_NEG = 9;
    private static final int FLOAT_EQ = 10;
    private static final int FLOAT_NE = 11;
    private static final int FLOAT_LT = 12;
    private static final int FLOAT_LE = 13;
    private static final int FLOAT_GT = 14;
    private static final int FLOAT_GE = 15;
    private static final int DOUBLE_EQ = 16;
    private static final int DOUBLE_NE = 17;
    private static final int DOUBLE_LT = 18;
    private static final int DOUBLE_LE = 19;
    private static final int DOUBLE_GT = 20;
    private static final int DOUBLE_GE = 21;
    private static final int INT_FLOAT = 22;
    private static final int INT_DOUBLE = 23;
    private static final int FLOAT_INT = 24;
    private static final int DOUBLE_INT = 25;
    private static final int FLOAT_DOUBLE = 26;
    private static final int DOUBLE_FLOAT = 27;
    private static final int FLOAT_ABS = 28;
    private static final int FLOAT_EXP = 29;
    private static final int FLOAT_EXPM1 = 30;
    private static final int FLOAT_LOG = 31;
    private static final int FLOAT_LOG1P = 32;
    private static final int FLOAT_SIN = 33;
    private static final int FLOAT_COS = 34;
    private static final int FLOAT_POWER = 35;
    private static final int DOUBLE_ABS = 36;
    private static final int DOUBLE_EXP = 37;
    private static final int DOUBLE_EXPM1 = 38;
    private static final int DOUBLE_LOG = 39;
    private static final int DOUBLE_LOG1P = 40;
    private static final int DOUBLE_SIN = 41;
    private static final int DOUBLE_COS = 42;
    private static final int DOUBLE_POWER = 43;
    private static final int FLOAT_TAN = 44;
    private static final int FLOAT_ASIN = 45;
    private static final int FLOAT_ACOS = 46;
    private static final int FLOAT_ATAN = 47;
    private static final int FLOAT_SINH = 48;
    private static final int FLOAT_COSH = 49;
    private static final int FLOAT_TANH = 50;
    private static final int DOUBLE_TAN = 51;
    private static final int DOUBLE_ASIN = 52;
    private static final int DOUBLE_ACOS = 53;
    private static final int DOUBLE_ATAN = 54;
    private static final int DOUBLE_SINH = 55;
    private static final int DOUBLE_COSH = 56;
    private static final int DOUBLE_TANH = 57;
    private static final int WORD_FLOAT = 58;
    private static final int WORD_DOUBLE = 59;
    private static final int FLOAT_ASINH = 60;
    private static final int FLOAT_ACOSH = 61;
    private static final int FLOAT_ATANH = 62;
    private static final int DOUBLE_ASINH = 63;
    private static final int DOUBLE_ACOSH = 64;
    private static final int DOUBLE_ATANH = 65;
    private static final int FLOAT_MIN = 66;
    private static final int FLOAT_MAX = 67;
    private static final int DOUBLE_MIN = 68;
    private static final int DOUBLE_MAX = 69;

    /** JVM float operations round each result to binary32; no implicit numeric widening. */
    static Expr floatingPrimitive(String name, Expr[] arguments) {
        Expr cast = RawBitCasts.rawBitCastPrimitive(name, arguments);
        if (cast != null) return cast;
        int fused = switch (name) {
            case "fmaddFloat#", "fmaddDouble#" -> 0;
            case "fmsubFloat#", "fmsubDouble#" -> 1;
            case "fnmaddFloat#", "fnmaddDouble#" -> 2;
            case "fnmsubFloat#", "fnmsubDouble#" -> 3;
            default -> -1;
        };
        if (fused >= 0) {
            if (arguments.length != 3) throw new RuntimeFault("Primitive arity mismatch: " + name);
            return name.endsWith("Float#") ? new FusedFloat(fused, arguments) : new FusedDouble(fused, arguments);
        }
        if (name.equals("sqrtFloat#") || name.equals("sqrtDouble#")) {
            if (arguments.length != 1) throw new RuntimeFault("Primitive arity mismatch: " + name);
            return name.equals("sqrtFloat#") ? new FloatSqrt(arguments[0]) : new DoubleSqrt(arguments[0]);
        }
        int operation = switch (name) {
            case "plusFloat#" -> FLOAT_ADD;
            case "minusFloat#" -> FLOAT_SUB;
            case "timesFloat#" -> FLOAT_MUL;
            case "divideFloat#" -> FLOAT_DIV;
            case "negateFloat#" -> FLOAT_NEG;
            case "+##" -> DOUBLE_ADD;
            case "-##" -> DOUBLE_SUB;
            case "*##" -> DOUBLE_MUL;
            case "/##" -> DOUBLE_DIV;
            case "negateDouble#" -> DOUBLE_NEG;
            case "eqFloat#" -> FLOAT_EQ;
            case "neFloat#" -> FLOAT_NE;
            case "ltFloat#" -> FLOAT_LT;
            case "leFloat#" -> FLOAT_LE;
            case "gtFloat#" -> FLOAT_GT;
            case "geFloat#" -> FLOAT_GE;
            case "==##" -> DOUBLE_EQ;
            case "/=##" -> DOUBLE_NE;
            case "<##" -> DOUBLE_LT;
            case "<=##" -> DOUBLE_LE;
            case ">##" -> DOUBLE_GT;
            case ">=##" -> DOUBLE_GE;
            case "int2Float#" -> INT_FLOAT;
            case "int2Double#" -> INT_DOUBLE;
            case "float2Int#" -> FLOAT_INT;
            case "double2Int#" -> DOUBLE_INT;
            case "float2Double#" -> FLOAT_DOUBLE;
            case "double2Float#" -> DOUBLE_FLOAT;
            case "word2Float#" -> WORD_FLOAT;
            case "word2Double#" -> WORD_DOUBLE;
            case "fabsFloat#" -> FLOAT_ABS;
            case "expFloat#" -> FLOAT_EXP;
            case "expm1Float#" -> FLOAT_EXPM1;
            case "logFloat#" -> FLOAT_LOG;
            case "log1pFloat#" -> FLOAT_LOG1P;
            case "sinFloat#" -> FLOAT_SIN;
            case "cosFloat#" -> FLOAT_COS;
            case "powerFloat#" -> FLOAT_POWER;
            case "fabsDouble#" -> DOUBLE_ABS;
            case "expDouble#" -> DOUBLE_EXP;
            case "expm1Double#" -> DOUBLE_EXPM1;
            case "logDouble#" -> DOUBLE_LOG;
            case "log1pDouble#" -> DOUBLE_LOG1P;
            case "sinDouble#" -> DOUBLE_SIN;
            case "cosDouble#" -> DOUBLE_COS;
            case "**##" -> DOUBLE_POWER;
            case "tanFloat#" -> FLOAT_TAN;
            case "asinFloat#" -> FLOAT_ASIN;
            case "acosFloat#" -> FLOAT_ACOS;
            case "atanFloat#" -> FLOAT_ATAN;
            case "sinhFloat#" -> FLOAT_SINH;
            case "coshFloat#" -> FLOAT_COSH;
            case "tanhFloat#" -> FLOAT_TANH;
            case "tanDouble#" -> DOUBLE_TAN;
            case "asinDouble#" -> DOUBLE_ASIN;
            case "acosDouble#" -> DOUBLE_ACOS;
            case "atanDouble#" -> DOUBLE_ATAN;
            case "sinhDouble#" -> DOUBLE_SINH;
            case "coshDouble#" -> DOUBLE_COSH;
            case "tanhDouble#" -> DOUBLE_TANH;
            case "asinhFloat#" -> FLOAT_ASINH;
            case "acoshFloat#" -> FLOAT_ACOSH;
            case "atanhFloat#" -> FLOAT_ATANH;
            case "asinhDouble#" -> DOUBLE_ASINH;
            case "acoshDouble#" -> DOUBLE_ACOSH;
            case "atanhDouble#" -> DOUBLE_ATANH;
            case "minFloat#" -> FLOAT_MIN;
            case "maxFloat#" -> FLOAT_MAX;
            case "minDouble#" -> DOUBLE_MIN;
            case "maxDouble#" -> DOUBLE_MAX;
            default -> -1;
        };
        if (operation < 0) return null;
        CoreKind kind = switch (operation) {
            case FLOAT_ADD, FLOAT_SUB, FLOAT_MUL, FLOAT_DIV, FLOAT_NEG,
                    INT_FLOAT, WORD_FLOAT, DOUBLE_FLOAT, FLOAT_ABS, FLOAT_EXP, FLOAT_EXPM1,
                    FLOAT_LOG, FLOAT_LOG1P, FLOAT_SIN, FLOAT_COS, FLOAT_POWER,
                    FLOAT_TAN, FLOAT_ASIN, FLOAT_ACOS, FLOAT_ATAN, FLOAT_SINH, FLOAT_COSH, FLOAT_TANH,
                    FLOAT_ASINH, FLOAT_ACOSH, FLOAT_ATANH, FLOAT_MIN, FLOAT_MAX -> CoreKind.FLOAT;
            case DOUBLE_ADD, DOUBLE_SUB, DOUBLE_MUL, DOUBLE_DIV, DOUBLE_NEG,
                    INT_DOUBLE, WORD_DOUBLE, FLOAT_DOUBLE, DOUBLE_ABS, DOUBLE_EXP, DOUBLE_EXPM1,
                    DOUBLE_LOG, DOUBLE_LOG1P, DOUBLE_SIN, DOUBLE_COS, DOUBLE_POWER,
                    DOUBLE_TAN, DOUBLE_ASIN, DOUBLE_ACOS, DOUBLE_ATAN, DOUBLE_SINH, DOUBLE_COSH, DOUBLE_TANH,
                    DOUBLE_ASINH, DOUBLE_ACOSH, DOUBLE_ATANH, DOUBLE_MIN, DOUBLE_MAX -> CoreKind.DOUBLE;
            default -> CoreKind.LONG;
        };
        int arity = switch (operation) {
            case FLOAT_ADD, FLOAT_SUB, FLOAT_MUL, FLOAT_DIV, DOUBLE_ADD, DOUBLE_SUB, DOUBLE_MUL, DOUBLE_DIV,
                    FLOAT_EQ, FLOAT_NE, FLOAT_LT, FLOAT_LE, FLOAT_GT, FLOAT_GE,
                    DOUBLE_EQ, DOUBLE_NE, DOUBLE_LT, DOUBLE_LE, DOUBLE_GT, DOUBLE_GE,
                    FLOAT_POWER, DOUBLE_POWER, FLOAT_MIN, FLOAT_MAX, DOUBLE_MIN, DOUBLE_MAX -> 2;
            default -> 1;
        };
        if (arguments.length != arity) throw new RuntimeFault("Primitive arity mismatch: " + name);
        return new FloatingPrimitive(operation, arguments, kind);
    }

    // GHC's negated variants negate operands, not the rounded result. The distinction
    // matters for signed zero. Math.fma performs one rounding in the declared format.
    private static final class FusedFloat extends Expr {
        private final int operation;
        @Children private Expr[] arguments;

        FusedFloat(int operation, Expr[] arguments) {
            this.operation = operation;
            this.arguments = arguments;
            setRepresentation(new CoreRepresentation(CoreKind.FLOAT, true, false, null, null, null, null, null, null));
        }

        @Override public Object execute(VirtualFrame frame) { return executeFloat(frame); }
        @Override public float executeFloat(VirtualFrame frame) {
            float x = arguments[0].executeRequiredFloat(frame);
            float y = arguments[1].executeRequiredFloat(frame);
            float z = arguments[2].executeRequiredFloat(frame);
            return Math.fma(operation >= 2 ? -x : x, y, (operation & 1) != 0 ? -z : z);
        }
    }

    private static final class FusedDouble extends Expr {
        private final int operation;
        @Children private Expr[] arguments;

        FusedDouble(int operation, Expr[] arguments) {
            this.operation = operation;
            this.arguments = arguments;
            setRepresentation(new CoreRepresentation(CoreKind.DOUBLE, true, false, null, null, null, null, null, null));
        }

        @Override public Object execute(VirtualFrame frame) { return executeDouble(frame); }
        @Override public double executeDouble(VirtualFrame frame) {
            double x = arguments[0].executeRequiredDouble(frame);
            double y = arguments[1].executeRequiredDouble(frame);
            double z = arguments[2].executeRequiredDouble(frame);
            return Math.fma(operation >= 2 ? -x : x, y, (operation & 1) != 0 ? -z : z);
        }
    }

    // Keep each operand and result primitive throughout execution. Float inputs are
    // exactly widened for JVM sqrt and rounded back to their binary32 result.
    private static final class FloatSqrt extends Expr {
        @Child private Expr value;

        FloatSqrt(Expr value) {
            this.value = value;
            setRepresentation(new CoreRepresentation(CoreKind.FLOAT, true, false, null, null, null, null, null, null));
        }

        @Override public Object execute(VirtualFrame frame) { return executeFloat(frame); }
        @Override public float executeFloat(VirtualFrame frame) { return (float) Math.sqrt(value.executeRequiredFloat(frame)); }
    }

    private static final class DoubleSqrt extends Expr {
        @Child private Expr value;

        DoubleSqrt(Expr value) {
            this.value = value;
            setRepresentation(new CoreRepresentation(CoreKind.DOUBLE, true, false, null, null, null, null, null, null));
        }

        @Override public Object execute(VirtualFrame frame) { return executeDouble(frame); }
        @Override public double executeDouble(VirtualFrame frame) { return Math.sqrt(value.executeRequiredDouble(frame)); }
    }

    private static final class FloatingPrimitive extends Expr {
        private final int operation;
        @Children private Expr[] arguments;
        private final CoreKind resultKind;

        FloatingPrimitive(int operation, Expr[] arguments, CoreKind resultKind) {
            this.operation = operation;
            this.arguments = arguments;
            this.resultKind = resultKind;
            setRepresentation(new CoreRepresentation(resultKind, true, false, null, null, null, null, null, null));
        }

        @Override public Object execute(VirtualFrame frame) {
            if (resultKind == CoreKind.FLOAT) return executeFloat(frame);
            if (resultKind == CoreKind.DOUBLE) return executeDouble(frame);
            return executeLong(frame);
        }

        @Override public float executeFloat(VirtualFrame frame) {
            if (operation == WORD_FLOAT) return WordFloatingConversions.toFloat(arguments[0].executeRequiredLong(frame));
            if (operation == INT_FLOAT) return (float) arguments[0].executeRequiredLong(frame);
            if (operation == DOUBLE_FLOAT) return (float) arguments[0].executeRequiredDouble(frame);
            float x = arguments[0].executeRequiredFloat(frame);
            switch (operation) {
                case FLOAT_NEG: return -x;
                case FLOAT_ABS: return Math.abs(x);
                case FLOAT_EXP: return (float) Math.exp(x);
                case FLOAT_EXPM1: return (float) Math.expm1(x);
                case FLOAT_LOG: return (float) Math.log(x);
                case FLOAT_LOG1P: return (float) Math.log1p(x);
                case FLOAT_SIN: return (float) Math.sin(x);
                case FLOAT_COS: return (float) Math.cos(x);
                case FLOAT_TAN: return (float) Math.tan(x);
                case FLOAT_ASIN: return (float) Math.asin(x);
                case FLOAT_ACOS: return (float) Math.acos(x);
                case FLOAT_ATAN: return (float) Math.atan(x);
                case FLOAT_SINH: return (float) Math.sinh(x);
                case FLOAT_COSH: return (float) Math.cosh(x);
                case FLOAT_TANH: return (float) Math.tanh(x);
                case FLOAT_ASINH: return (float) InverseHyperbolic.asinh(x);
                case FLOAT_ACOSH: return (float) InverseHyperbolic.acosh(x);
                case FLOAT_ATANH: return (float) InverseHyperbolic.atanh(x);
            }
            float y = arguments[1].executeRequiredFloat(frame);
            switch (operation) {
                case FLOAT_ADD: return x + y;
                case FLOAT_SUB: return x - y;
                case FLOAT_MUL: return x * y;
                case FLOAT_DIV: return x / y;
                case FLOAT_POWER: return (float) Math.pow(x, y);
                case FLOAT_MIN: return x < y ? x : y;
                case FLOAT_MAX: return x > y ? x : y;
                default:
                    CompilerDirectives.transferToInterpreterAndInvalidate();
                    throw new RuntimeFault("Expected Float primitive result");
            }
        }

        @Override public double executeDouble(VirtualFrame frame) {
            if (operation == WORD_DOUBLE) return WordFloatingConversions.toDouble(arguments[0].executeRequiredLong(frame));
            if (operation == INT_DOUBLE) return (double) arguments[0].executeRequiredLong(frame);
            if (operation == FLOAT_DOUBLE) return (double) arguments[0].executeRequiredFloat(frame);
            double x = arguments[0].executeRequiredDouble(frame);
            switch (operation) {
                case DOUBLE_NEG: return -x;
                case DOUBLE_ABS: return Math.abs(x);
                case DOUBLE_EXP: return Math.exp(x);
                case DOUBLE_EXPM1: return Math.expm1(x);
                case DOUBLE_LOG: return Math.log(x);
                case DOUBLE_LOG1P: return Math.log1p(x);
                case DOUBLE_SIN: return Math.sin(x);
                case DOUBLE_COS: return Math.cos(x);
                case DOUBLE_TAN: return Math.tan(x);
                case DOUBLE_ASIN: return Math.asin(x);
                case DOUBLE_ACOS: return Math.acos(x);
                case DOUBLE_ATAN: return Math.atan(x);
                case DOUBLE_SINH: return Math.sinh(x);
                case DOUBLE_COSH: return Math.cosh(x);
                case DOUBLE_TANH: return Math.tanh(x);
                case DOUBLE_ASINH: return InverseHyperbolic.asinh(x);
                case DOUBLE_ACOSH: return InverseHyperbolic.acosh(x);
                case DOUBLE_ATANH: return InverseHyperbolic.atanh(x);
            }
            double y = arguments[1].executeRequiredDouble(frame);
            switch (operation) {
                case DOUBLE_ADD: return x + y;
                case DOUBLE_SUB: return x - y;
                case DOUBLE_MUL: return x * y;
                case DOUBLE_DIV: return x / y;
                case DOUBLE_POWER: return Math.pow(x, y);
                case DOUBLE_MIN: return x < y ? x : y;
                case DOUBLE_MAX: return x > y ? x : y;
                default:
                    CompilerDirectives.transferToInterpreterAndInvalidate();
                    throw new RuntimeFault("Expected Double primitive result");
            }
        }

        @Override public long executeLong(VirtualFrame frame) {
            // GHC leaves non-finite/out-of-range floating-to-Int conversions undefined.
            // Differential fixtures exercise only finite, representable operands.
            if (operation == FLOAT_INT) return (long) arguments[0].executeRequiredFloat(frame);
            if (operation == DOUBLE_INT) return (long) arguments[0].executeRequiredDouble(frame);
            boolean result;
            if (operation >= FLOAT_EQ && operation <= FLOAT_GE) {
                float x = arguments[0].executeRequiredFloat(frame);
                float y = arguments[1].executeRequiredFloat(frame);
                switch (operation) {
                    case FLOAT_EQ: result = x == y; break;
                    case FLOAT_NE: result = x != y; break;
                    case FLOAT_LT: result = x < y; break;
                    case FLOAT_LE: result = x <= y; break;
                    case FLOAT_GT: result = x > y; break;
                    case FLOAT_GE: result = x >= y; break;
                    default:
                        CompilerDirectives.transferToInterpreterAndInvalidate();
                        throw new RuntimeFault("Unsupported Float comparison");
                }
            } else {
                double x = arguments[0].executeRequiredDouble(frame);
                double y = arguments[1].executeRequiredDouble(frame);
                switch (operation) {
                    case DOUBLE_EQ: result = x == y; break;
                    case DOUBLE_NE: result = x != y; break;
                    case DOUBLE_LT: result = x < y; break;
                    case DOUBLE_LE: result = x <= y; break;
                    case DOUBLE_GT: result = x > y; break;
                    case DOUBLE_GE: result = x >= y; break;
                    default:
                        CompilerDirectives.transferToInterpreterAndInvalidate();
                        throw new RuntimeFault("Unsupported Double comparison");
                }
            }
            return result ? 1L : 0L;
        }
    }
}
