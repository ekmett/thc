// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc;

import java.math.BigInteger;

/** Mathematical reference for the scalar primop families exercised against native GHC. */
public final class ScalarPrimopModel {
    private ScalarPrimopModel() {}

    private static BigInteger modulus(int width) {
        if (width < 1 || width > 64) throw new IllegalArgumentException("Failed requirement.");
        return BigInteger.ONE.shiftLeft(width);
    }

    private static BigInteger unsigned(long value, int width) {
        return BigInteger.valueOf(value).mod(modulus(width));
    }

    private static BigInteger signed(BigInteger value, int width) {
        BigInteger residue = value.mod(modulus(width));
        return residue.testBit(width - 1) ? residue.subtract(modulus(width)) : residue;
    }

    /** Bit-walk oracle independent of the JVM's compress/expand operations. */
    private static BigInteger depositOrExtract(boolean deposit, int width,
                                                BigInteger source, BigInteger mask) {
        BigInteger result = BigInteger.ZERO;
        int packedBit = 0;
        for (int position = 0; position < width; position++) {
            if (mask.testBit(position)) {
                if (deposit) {
                    if (source.testBit(packedBit)) result = result.setBit(position);
                } else if (source.testBit(position)) {
                    result = result.setBit(packedBit);
                }
                packedBit++;
            }
        }
        return result;
    }

    private static BigInteger bit(boolean value) {
        return value ? BigInteger.ONE : BigInteger.ZERO;
    }

    /** All arithmetic is unbounded until the final GHC-width truncation. */
    public static long scalar(String operation, int width, boolean isUnsigned,
                              long left, long right) {
        BigInteger x = isUnsigned ? unsigned(left, width) : signed(BigInteger.valueOf(left), width);
        BigInteger y = isUnsigned ? unsigned(right, width) : signed(BigInteger.valueOf(right), width);
        BigInteger result = switch (operation) {
            case "identity" -> x;
            case "negate" -> x.negate();
            case "plus" -> x.add(y);
            case "sub" -> x.subtract(y);
            case "times" -> x.multiply(y);
            case "quot" -> x.divide(y);
            case "rem" -> x.remainder(y);
            case "eq" -> bit(x.equals(y));
            case "ne" -> bit(!x.equals(y));
            case "lt" -> bit(x.compareTo(y) < 0);
            case "le" -> bit(x.compareTo(y) <= 0);
            case "gt" -> bit(x.compareTo(y) > 0);
            case "ge" -> bit(x.compareTo(y) >= 0);
            case "and" -> x.and(y);
            case "or" -> x.or(y);
            case "xor" -> x.xor(y);
            case "not" -> x.not();
            case "shiftL", "uncheckedShiftL" -> x.shiftLeft((int) right);
            case "shiftRA" -> x.shiftRight((int) right);
            case "shiftRL", "uncheckedShiftRL" -> unsigned(left, width).shiftRight((int) right);
            case "pdep" -> depositOrExtract(true, width, x, y);
            case "pext" -> depositOrExtract(false, width, x, y);
            default -> throw new IllegalStateException("Unknown scalar primop operation: " + operation);
        };
        return (isUnsigned ? result.mod(modulus(width)) : signed(result, width)).longValue();
    }

    public static long explicit64(String operation, boolean isUnsigned, long left, long right) {
        if (operation.equals("literals")) {
            if (left == 0) return 0;
            if (left == 1) return Long.MAX_VALUE;
            if (left == 2) return -1;
            return Long.MIN_VALUE;
        }
        if (operation.equals("case")) {
            if (left == 0) return 11;
            if (left == Long.MIN_VALUE) return 13;
            if (left == -1) return 17;
            return 19;
        }
        return scalar(operation, 64, isUnsigned, left, right);
    }

    /** Build results from individual bits, independent of JVM bit-count/reversal helpers. */
    public static long bit(String operation, int width, long carrier) {
        BigInteger input = unsigned(carrier, width);
        boolean[] bits = new boolean[width];
        for (int i = 0; i < width; i++) bits[i] = input.testBit(i);
        return switch (operation) {
            case "narrowWord" -> input.longValue();
            case "popCnt" -> {
                long count = 0;
                for (boolean value : bits) if (value) count++;
                yield count;
            }
            case "clz" -> {
                int count = 0;
                while (count < width && !bits[width - 1 - count]) count++;
                yield count;
            }
            case "ctz" -> {
                int count = 0;
                while (count < width && !bits[count]) count++;
                yield count;
            }
            case "bitReverse", "byteSwap" -> {
                BigInteger result = BigInteger.ZERO;
                for (int i = 0; i < width; i++) {
                    if (bits[i]) {
                        int destination = operation.equals("bitReverse") ? width - 1 - i
                            : width - 8 - 8 * (i / 8) + i % 8;
                        result = result.add(BigInteger.ONE.shiftLeft(destination));
                    }
                }
                yield result.longValue();
            }
            default -> throw new IllegalStateException("Unknown bit primop operation: " + operation);
        };
    }
}
