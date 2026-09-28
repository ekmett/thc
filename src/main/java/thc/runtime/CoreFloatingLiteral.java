// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.util.Map;

/** Decode-time literal payload, never a guest carrier. JSON retains decimal strings;
 * compact IEEE bits avoid decimal NaN canonicalization. */
public sealed interface CoreFloatingLiteral {
    record Single(int bits) implements CoreFloatingLiteral {}
    record Double(long bits) implements CoreFloatingLiteral {}

    /** Lossless selected-source detachment; ordinary exported decimal strings stay unchanged. */
    default Map<String,String> document() {
        return switch (this) {
            case Single value -> Map.of("floatBits", Integer.toUnsignedString(value.bits(), 16));
            case Double value -> Map.of("doubleBits", Long.toUnsignedString(value.bits(), 16));
        };
    }

    static CoreFloatingLiteral fromDocument(Map<?,?> document) {
        if (document.size() == 1) {
            if (document.get("floatBits") instanceof String bits) return new Single(Integer.parseUnsignedInt(bits, 16));
            if (document.get("doubleBits") instanceof String bits) return new Double(Long.parseUnsignedLong(bits, 16));
        }
        throw new UnsupportedCore("Malformed detached floating literal");
    }

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
