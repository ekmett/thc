// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives;

/** Original ghc-internal primFloat.c declarations, not arbitrary libm calls. */
enum FloatForeignOp {
    FLOAT_NAN("isFloatNaN", true), FLOAT_INFINITE("isFloatInfinite", true),
    FLOAT_FINITE("isFloatFinite", true), FLOAT_DENORMAL("isFloatDenormalized", true),
    FLOAT_NEGATIVE_ZERO("isFloatNegativeZero", true), FLOAT_ROUND("rintFloat", true, true),
    DOUBLE_NAN("isDoubleNaN", false), DOUBLE_INFINITE("isDoubleInfinite", false),
    DOUBLE_FINITE("isDoubleFinite", false), DOUBLE_DENORMAL("isDoubleDenormalized", false),
    DOUBLE_NEGATIVE_ZERO("isDoubleNegativeZero", false), DOUBLE_ROUND("rintDouble", false, true);

    private final String symbol;
    private final boolean single;
    private final boolean rounding;
    private final String argumentRep;
    private final String resultRep;

    FloatForeignOp(String symbol, boolean single) { this(symbol, single, false); }

    FloatForeignOp(String symbol, boolean single, boolean rounding) {
        this.symbol = symbol;
        this.single = single;
        this.rounding = rounding;
        argumentRep = single ? "FloatRep" : "DoubleRep";
        resultRep = rounding ? argumentRep : "IntRep";
    }

    public String getSymbol() { return symbol; }
    public boolean getSingle() { return single; }
    public boolean getRounding() { return rounding; }
    public String getArgumentRep() { return argumentRep; }
    public String getResultRep() { return resultRep; }

    public long classify(float value) {
        boolean result = switch (this) {
            case FLOAT_NAN -> Float.isNaN(value);
            case FLOAT_INFINITE -> Float.isInfinite(value);
            case FLOAT_FINITE -> Float.isFinite(value);
            case FLOAT_DENORMAL -> {
                int bits = Float.floatToRawIntBits(value) & Integer.MAX_VALUE;
                yield bits >= 1 && bits <= 0x7fffff;
            }
            case FLOAT_NEGATIVE_ZERO -> Float.floatToRawIntBits(value) == Integer.MIN_VALUE;
            default -> {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                throw new RuntimeFault("Expected a Float classification");
            }
        };
        return result ? 1L : 0L;
    }

    public long classify(double value) {
        boolean result = switch (this) {
            case DOUBLE_NAN -> Double.isNaN(value);
            case DOUBLE_INFINITE -> Double.isInfinite(value);
            case DOUBLE_FINITE -> Double.isFinite(value);
            case DOUBLE_DENORMAL -> {
                long bits = Double.doubleToRawLongBits(value) & Long.MAX_VALUE;
                yield bits >= 1 && bits <= 0xfffffffffffffL;
            }
            case DOUBLE_NEGATIVE_ZERO -> Double.doubleToRawLongBits(value) == Long.MIN_VALUE;
            default -> {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                throw new RuntimeFault("Expected a Double classification");
            }
        };
        return result ? 1L : 0L;
    }

    public static FloatForeignOp named(Object symbol) {
        if (!(symbol instanceof String name)) return null;
        return switch (name) {
            case "isFloatNaN" -> FLOAT_NAN;
            case "isFloatInfinite" -> FLOAT_INFINITE;
            case "isFloatFinite" -> FLOAT_FINITE;
            case "isFloatDenormalized" -> FLOAT_DENORMAL;
            case "isFloatNegativeZero" -> FLOAT_NEGATIVE_ZERO;
            case "rintFloat" -> FLOAT_ROUND;
            case "isDoubleNaN" -> DOUBLE_NAN;
            case "isDoubleInfinite" -> DOUBLE_INFINITE;
            case "isDoubleFinite" -> DOUBLE_FINITE;
            case "isDoubleDenormalized" -> DOUBLE_DENORMAL;
            case "isDoubleNegativeZero" -> DOUBLE_NEGATIVE_ZERO;
            case "rintDouble" -> DOUBLE_ROUND;
            default -> null;
        };
    }

    // GHC canonicalizes small results to +0 and returns large values/NaNs
    // unchanged; do not quiet a NaN through an arithmetic operation.
    public static float round(float value) {
        if (!Float.isFinite(value) || Math.abs(value) >= 8388608.0f) return value;
        if (Math.abs(value) <= 0.5f) return 0.0f;
        return (float) Math.rint(value);
    }

    public static double round(double value) {
        if (!Double.isFinite(value) || Math.abs(value) >= 4503599627370496.0) return value;
        if (Math.abs(value) <= 0.5) return 0.0;
        return Math.rint(value);
    }
}
