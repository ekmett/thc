# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

"""Integer-only IEEE encodings; no host floating conversion or NaN normalization."""
ENTRIES = [p+s for p in ('float', 'double') for s in ('Roundtrip', 'Field', 'Captured', 'Decode', 'Encode')]
PRIMITIVES = {'castFloatToWord32#', 'castWord32ToFloat#', 'castDoubleToWord64#', 'castWord64ToDouble#'}


def signed64(bits):
    bits &= (1 << 64)-1
    return bits-(1 << 64) if bits >= 1 << 63 else bits


def patterns(width):
    exponent_bits, mantissa_bits = (8, 23) if width == 32 else (11, 52)
    exponent = ((1 << exponent_bits)-1) << mantissa_bits
    values = {0, 1, (1 << mantissa_bits)-1, 1 << mantissa_bits, exponent, exponent-1, (1 << width)-1}
    for sign in (0, 1 << (width-1)):
        values.update(sign | bits for bits in (0, 1, (1 << mantissa_bits)-1, 1 << mantissa_bits, exponent, exponent-1))
        for quiet in (0, 1 << (mantissa_bits-1)):
            for payload in [*range(0, 257), *(1 << bit for bit in range(mantissa_bits-1)),
                            *((1 << bit)-1 for bit in range(1, mantissa_bits))]:
                values.add(sign | exponent | quiet | payload)
        values.update(sign | (1 << bit) for bit in range(width))
    return sorted(values)


def inputs(width):
    values = {signed64(bits) for bits in patterns(width)}
    if width == 32:
        values.update([-(1 << 63), (1 << 63)-1, -1, -(1 << 32), (1 << 32),
                       (1 << 48) | 0x7f800001, -((1 << 40) | 0x123456)])
    return sorted(values)


def expected(name, raw):
    return raw & 0xffffffff if name.startswith('float') else signed64(raw)


def classify(bits, width):
    mantissa_bits, exponent_bits = (23, 8) if width == 32 else (52, 11)
    sign = bits >> (width-1) & 1
    mantissa = bits & ((1 << mantissa_bits)-1)
    exponent = bits >> mantissa_bits & ((1 << exponent_bits)-1)
    kind = ('signalling-nan' if not (mantissa >> (mantissa_bits-1)) else 'quiet-nan') if exponent == (1 << exponent_bits)-1 and mantissa else (
           'infinity' if exponent == (1 << exponent_bits)-1 else 'subnormal' if exponent == 0 and mantissa else 'zero' if exponent == 0 else 'normal')
    return sign, kind
