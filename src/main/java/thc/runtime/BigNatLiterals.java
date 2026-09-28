// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.math.BigInteger;
import java.nio.ByteOrder;
import java.util.List;

/** Load-time 64-bit GHC BigNat limbs, least-significant first in native byte order. */
public final class BigNatLiterals {
    private BigNatLiterals() {}
    private static final CoreRepresentation PROOF = new CoreRepresentation(CoreKind.OBJECT, true, true,
        List.of("BoxedRep (Just Unlifted)"), null, null, null, null, null);
    public static CoreRepresentation getProof() { return PROOF; }
    public static CoreRepresentation proof(List<Object> expression) {
        var metadata = CoreRepresentations.INSTANCE.metadata(expression);
        var actual = CoreRepresentations.INSTANCE.parse(metadata == null ? null : metadata.get("rep"));
        boolean unconstrained = actual.getKind() == CoreKind.UNKNOWN && actual.getPrimReps() == null && !actual.isAggregate() && !actual.isVector();
        if (actual.getPresent() && !unconstrained && (actual.getKind() != CoreKind.OBJECT ||
            !PROOF.getPrimReps().equals(actual.getPrimReps()) || actual.isAggregate() || actual.isVector()))
            throw new RuntimeFault("bignat literal requires exact unlifted ByteArray# metadata");
        return PROOF;
    }
    public static int byteSize(long bits) {
        if (bits < 0 || bits > ((long) Integer.MAX_VALUE / 8) * 64)
            throw new RuntimeFault("bignat literal exceeds managed byte-array size");
        return (int) (((bits + 63) / 64) * 8);
    }
    public static byte[] decode(String decimal) {
        if (decimal.isEmpty() || decimal.length() > 1 && decimal.charAt(0) == '0')
            throw new RuntimeFault("bignat literal requires canonical nonnegative decimal");
        for (int i = 0; i < decimal.length(); i++)
            if (decimal.charAt(i) < '0' || decimal.charAt(i) > '9')
                throw new RuntimeFault("bignat literal requires canonical nonnegative decimal");
        var number = new BigInteger(decimal);
        byte[] output = new byte[byteSize(number.bitLength())];
        byte[] bytes = number.toByteArray();
        int count = (int) (((long) number.bitLength() + 7) / 8);
        boolean little = ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN;
        for (int i = 0; i < count; i++) output[little ? i : (i / 8) * 8 + 7 - i % 8] = bytes[bytes.length - 1 - i];
        return output;
    }
}
