// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

/** Canonical Core literal spellings and their existing scalar carriers. */
public final class ScalarLiterals {
    private ScalarLiterals() {}

    /** Machine-word narrowing retains the Word# Long carrier. */
    public static long narrowWordPrimitiveMask(String name) {
        return switch (name) {
            case "narrow8Word#" -> 0xffL;
            case "narrow16Word#" -> 0xffffL;
            case "narrow32Word#" -> 0xffff_ffffL;
            default -> 0L;
        };
    }

    public static int narrowWordLiteral(String kind, String value) {
        long maximum = switch (kind) {
            case "word8" -> 0xffL;
            case "word16" -> 0xffffL;
            case "word32" -> 0xffff_ffffL;
            default -> throw new RuntimeFault("Invalid narrow word literal kind: " + kind);
        };
        return (int) canonical(kind, value, 0L, maximum);
    }

    public static int int8Literal(String value) {
        return (int) canonical("int8", value, Byte.MIN_VALUE, Byte.MAX_VALUE);
    }

    public static int int16Literal(String value) {
        return (int) canonical("int16", value, Short.MIN_VALUE, Short.MAX_VALUE);
    }

    public static int int32Literal(String value) {
        return (int) canonical("int32", value, Integer.MIN_VALUE, Integer.MAX_VALUE);
    }

    public static long int64Literal(String value) {
        return canonical("int64", value, Long.MIN_VALUE, Long.MAX_VALUE);
    }

    private static long canonical(String kind, String value, long minimum, long maximum) {
        try {
            long number = Long.parseLong(value);
            if (number >= minimum && number <= maximum && Long.toString(number).equals(value)) return number;
        } catch (NumberFormatException ignored) {
            // Malformed and noncanonical input retain the same Core diagnostic.
        }
        throw new RuntimeFault("Invalid " + kind + " literal: " + value);
    }
}
