// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.nodes.Node;
import thc.Language;
import static thc.runtime.RuntimeServiceStatus.fault;

/** Typed normalized operand lanes; original evaluation order is retained by each lowering. */
public final class ManagedGmp {
    private ManagedGmp() {}

    public static double invokeDouble(Node node, GmpForeignOp operation, Object input, long a, long b) {
        if (operation == GmpForeignOp.ENCODE_DOUBLE) {
            // Preserve the RTS's wrapped machine abs, including minBound, before conversion.
            long magnitude = a < 0 ? -a : a;
            double scaled = Math.scalb((double) magnitude, (int) Math.clamp(b, Integer.MIN_VALUE, Integer.MAX_VALUE));
            return a < 0 ? -scaled : scaled;
        }
        return getDouble(node, input, a, b);
    }

    @TruffleBoundary
    private static double getDouble(Node node, Object input, long count, long exponent) {
        if (count == Long.MIN_VALUE) throw fault("Invalid signed limb count");
        var region = LimbRegion.Companion.read(input, Math.abs(count), true);
        return Language.currentState(node).limbs().toDouble(region, count < 0, exponent);
    }

    @TruffleBoundary
    public static long invoke(Node node, GmpForeignOp operation, Object first, Object second,
                              Object third, Object fourth, long a, long b, long c) {
        var provider = Language.currentState(node).limbs();
        return switch (operation) {
            case ADD -> provider.add(LimbRegion.Companion.write(first, a, false), LimbRegion.Companion.read(second, a, false), LimbRegion.Companion.read(third, b, false));
            case SUBTRACT -> provider.subtract(LimbRegion.Companion.write(first, a, false), LimbRegion.Companion.read(second, a, false), LimbRegion.Companion.read(third, b, false));
            case MULTIPLY -> {
                var left = LimbRegion.Companion.read(second, a, false);
                var right = LimbRegion.Companion.read(third, b, false);
                yield provider.multiply(LimbRegion.Companion.write(first, left.getLimbs() + right.getLimbs(), false), left, right);
            }
            case ADD_WORD -> provider.addWord(LimbRegion.Companion.write(first, a, false), LimbRegion.Companion.read(second, a, false), b);
            case MULTIPLY_WORD -> provider.multiplyWord(LimbRegion.Companion.write(first, a, false), LimbRegion.Companion.read(second, a, false), b);
            case SHIFT_RIGHT, SHIFT_RIGHT_NEGATIVE -> {
                var input = LimbRegion.Companion.read(second, a, false);
                if (b <= 0 || b >= a * 64) throw fault("Limb right shift must be inside the input width");
                boolean negative = operation == GmpForeignOp.SHIFT_RIGHT_NEGATIVE;
                long size = a - (negative ? b - 1 : b) / 64;
                yield provider.shiftRight(LimbRegion.Companion.write(first, size, false), input, b, negative);
            }
            // The original Int# import zero-extends C int; its caller applies narrowCInt#.
            case COMPARE -> provider.compare(LimbRegion.Companion.read(first, a, false), LimbRegion.Companion.read(second, a, false)) & 0xffff_ffffL;
            case DIVIDE_WORD -> {
                var input = LimbRegion.Companion.read(second, b, true);
                if (a < 0 || a > (long) Integer.MAX_VALUE / 8) throw fault("Invalid fractional limb count");
                yield provider.divideWord(LimbRegion.Companion.write(first, input.getLimbs() + a, true), a, input, c);
            }
            case MODULO_WORD -> provider.moduloWord(LimbRegion.Companion.read(first, a, true), b);
            case GCD_WORDS -> provider.gcdWords(a, b);
            case GCD_WORD -> provider.gcdWord(LimbRegion.Companion.read(first, a, false), b);
            case GCD -> provider.gcd(LimbRegion.Companion.write(first, b, false), LimbRegion.Companion.read(second, a, false), LimbRegion.Companion.read(third, b, false));
            case SHIFT_LEFT -> {
                var input = LimbRegion.Companion.read(second, a, false);
                if (b <= 0 || b > (long) Integer.MAX_VALUE * 8) throw fault("Invalid limb left shift count");
                yield provider.shiftLeft(LimbRegion.Companion.write(first, a + (b + 63) / 64, false), input, b);
            }
            case AND, AND_NOT, OR, XOR -> {
                var logical = switch (operation) {
                    case AND -> LimbBitwise.AND;
                    case AND_NOT -> LimbBitwise.AND_NOT;
                    case OR -> LimbBitwise.OR;
                    default -> LimbBitwise.XOR;
                };
                provider.bitwise(LimbRegion.Companion.write(first, a, false), LimbRegion.Companion.read(second, a, false), LimbRegion.Companion.read(third, a, false), logical);
                yield 0;
            }
            case POPCOUNT -> provider.populationCount(LimbRegion.Companion.read(first, a, false));
            case DIVIDE -> {
                var numerator = LimbRegion.Companion.read(third, b, false);
                var divisor = LimbRegion.Companion.read(fourth, c, false);
                provider.divide(LimbRegion.Companion.write(first, b - c + 1, false), LimbRegion.Companion.write(second, c, false), a, numerator, divisor);
                yield 0; // Unused internal lane; the guest result is singleton State.
            }
            case QUOTIENT -> {
                var numerator = LimbRegion.Companion.read(second, a, false);
                var divisor = LimbRegion.Companion.read(third, b, false);
                provider.quotient(LimbRegion.Companion.write(first, a - b + 1, false), numerator, divisor);
                yield 0;
            }
            case REMAINDER -> {
                var numerator = LimbRegion.Companion.read(second, a, false);
                var divisor = LimbRegion.Companion.read(third, b, false);
                provider.remainder(LimbRegion.Companion.write(first, b, false), numerator, divisor);
                yield 0;
            }
            case GET_DOUBLE, ENCODE_DOUBLE -> throw fault("Floating GMP operation requires a Double destination");
        };
    }
}
