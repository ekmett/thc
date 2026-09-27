// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

/** Decode-time literal payload, never a guest carrier. JSON retains decimal strings;
 * compact IEEE bits avoid decimal NaN canonicalization. */
public sealed interface CoreFloatingLiteral {
    record Single(int bits) implements CoreFloatingLiteral {}
    record Double(long bits) implements CoreFloatingLiteral {}

    default Object decode(String kind) {
        return switch (this) {
            case Single value -> {
                if (!"float".equals(kind)) throw new UnsupportedCore("Mismatched compact floating literal");
                yield Float.intBitsToFloat(value.bits());
            }
            case Double value -> {
                if (!"double".equals(kind)) throw new UnsupportedCore("Mismatched compact floating literal");
                yield java.lang.Double.longBitsToDouble(value.bits());
            }
        };
    }
}
