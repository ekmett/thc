// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

final class Scalar64Primitives {
    private Scalar64Primitives() {}

    /** Select a shared Long instruction, without rewriting any exact Core type proof. */
    static String scalar64PrimitiveOperation(String name) {
        return switch (name) {
            case "int64ToWord64#", "wordToWord64#" -> "int2Word#";
            case "word64ToInt64#", "word64ToWord#" -> "word2Int#";
            case "negateInt64#" -> "negateInt#";
            case "plusInt64#", "plusWord64#" -> "+#";
            case "subInt64#", "subWord64#" -> "-#";
            case "timesInt64#", "timesWord64#" -> "*#";
            case "quotInt64#" -> "quotInt#";
            case "remInt64#" -> "remInt#";
            case "quotWord64#" -> "quotWord#";
            case "remWord64#" -> "remWord#";
            case "eqInt64#", "eqWord64#" -> "==#";
            case "neInt64#", "neWord64#" -> "/=#";
            case "ltInt64#" -> "<#";
            case "leInt64#" -> "<=#";
            case "gtInt64#" -> ">#";
            case "geInt64#" -> ">=#";
            case "ltWord64#" -> "ltWord#";
            case "leWord64#" -> "leWord#";
            case "gtWord64#" -> "gtWord#";
            case "geWord64#" -> "geWord#";
            case "and64#" -> "and#";
            case "or64#" -> "or#";
            case "xor64#" -> "xor#";
            case "not64#" -> "not#";
            case "uncheckedIShiftL64#", "uncheckedShiftL64#" -> "uncheckedShiftL#";
            case "uncheckedIShiftRA64#" -> "uncheckedIShiftRA#";
            case "uncheckedIShiftRL64#", "uncheckedShiftRL64#" -> "uncheckedShiftRL#";
            default -> name;
        };
    }

    /** Unsigned decimal literals retain their complete bit pattern in a Long. */
    static long word64Literal(String value) {
        try {
            long number = Long.parseUnsignedLong(value);
            if (Long.toUnsignedString(number).equals(value)) return number;
        } catch (NumberFormatException ignored) {
            // Report the same Core diagnostic for malformed and noncanonical literals.
        }
        throw new RuntimeFault("Invalid word64 literal: " + value);
    }

    /** Unsigned 128/64 division, with the GHC high < divisor precondition checked.
     * The restoring loop maintains remainder < divisor. Its carry is the 65th bit,
     * so subtracting even a divisor with its top bit set is ordinary wrapping Long
     * arithmetic. The quotient fits one word; low - quotient * divisor is the
     * remainder. No BigInteger, tuple, or scratch array is needed by either backend. */
    static long unsignedDoubleWordQuotient(long high, long low, long divisor) {
        if (divisor == 0L || Long.compareUnsigned(high, divisor) >= 0)
            throw new RuntimeFault("Undefined input to quotRemWord2#: high must be less than divisor");
        if (high == 0L) return Long.divideUnsigned(low, divisor);
        long remainder = high;
        long quotient = low;
        for (int i = 0; i < 64; i++) {
            boolean carry = remainder < 0L;
            remainder = (remainder << 1) | (quotient >>> 63);
            quotient <<= 1;
            if (carry || Long.compareUnsigned(remainder, divisor) >= 0) {
                remainder -= divisor;
                quotient |= 1L;
            }
        }
        return quotient;
    }
}
