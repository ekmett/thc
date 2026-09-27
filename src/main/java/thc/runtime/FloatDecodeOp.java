// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime;

import java.util.List;

/** IEEE decomposition used by GHC 9.14.1's scalar floating decode primops.
 * Non-finite encodings follow the pinned RTS bit decomposition, not frexp.
 * Both backends write the primitive fields directly into their destination. */
enum FloatDecodeOp {
    FLOAT("decodeFloat_Int#", CoreKind.FLOAT, 23, 8, 127),
    DOUBLE("decodeDouble_Int64#", CoreKind.DOUBLE, 52, 11, 1023),
    DOUBLE_WORDS("decodeDouble_2Int#", CoreKind.DOUBLE, 52, 11, 1023);

    private final String primitive;
    private final CoreKind inputKind;
    private final int fractionBits;
    private final int bias;
    private final long hidden;
    private final long fractionMask;
    private final long exponentMask;
    private final long signMask;

    FloatDecodeOp(String primitive, CoreKind inputKind, int fractionBits, int exponentBits, int bias) {
        this.primitive = primitive;
        this.inputKind = inputKind;
        this.fractionBits = fractionBits;
        this.bias = bias;
        hidden = 1L << fractionBits;
        fractionMask = hidden - 1;
        exponentMask = (1L << exponentBits) - 1;
        signMask = 1L << (fractionBits + exponentBits);
    }

    public String getPrimitive() { return primitive; }
    public CoreKind getInputKind() { return inputKind; }
    public int getFields() { return this == DOUBLE_WORDS ? 4 : 2; }

    // The pinned RTS leaves zero's sign output uninitialized. THC chooses +1.
    public long sign(long bits) { return mantissa(bits) < 0L ? -1L : 1L; }
    public long high(long bits) { return Math.abs(mantissa(bits)) >>> 32; }
    public long low(long bits) { return Math.abs(mantissa(bits)) & 0xffff_ffffL; }

    public long mantissa(long bits) {
        long fraction = bits & fractionMask;
        long magnitude;
        if (((bits >>> fractionBits) & exponentMask) != 0L) magnitude = fraction | hidden;
        else if (fraction == 0L) magnitude = 0L;
        else magnitude = fraction << (Long.numberOfLeadingZeros(fraction) - (63 - fractionBits));
        return (bits & signMask) == 0L ? magnitude : -magnitude;
    }

    public long exponent(long bits) {
        long exponent = (bits >>> fractionBits) & exponentMask;
        if (exponent != 0L) return exponent - bias - fractionBits;
        long fraction = bits & fractionMask;
        if (fraction == 0L) return 0L;
        int shift = Long.numberOfLeadingZeros(fraction) - (63 - fractionBits);
        return 1 - bias - fractionBits - shift;
    }

    public void validate(List<CoreRepresentation> arguments, List<?> lifted, CoreRepresentation result) {
        if (arguments.size() != 1 || lifted.size() != 1 || !Boolean.FALSE.equals(lifted.get(0)))
            throw new RuntimeFault("Floating decode requires one unlifted operand: " + primitive);
        CoreRepresentation input = arguments.get(0);
        if (input.getKind() != inputKind || input.isAggregate() || input.isVector())
            throw new RuntimeFault("Floating decode operand carrier mismatch: " + primitive);
        List<CoreRepresentation> components = result.getComponents();
        if (!result.isTuple() || components.size() != getFields())
            throw new RuntimeFault("Floating decode requires " + getFields() + " scalar Long results: " + primitive);
        for (CoreRepresentation component : components) {
            if (component.getKind() != CoreKind.LONG || component.isAggregate() || component.isVector())
                throw new RuntimeFault("Floating decode requires " + getFields() + " scalar Long results: " + primitive);
        }
    }

    public static FloatDecodeOp named(String name) {
        return switch (name) {
            case "decodeFloat_Int#" -> FLOAT;
            case "decodeDouble_Int64#" -> DOUBLE;
            case "decodeDouble_2Int#" -> DOUBLE_WORDS;
            default -> null;
        };
    }
}
