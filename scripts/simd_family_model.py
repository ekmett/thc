# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

"""Independent integer and floating lane arithmetic for generated SIMD fixtures."""
from fractions import Fraction
import json
from pathlib import Path

FAMILIES = json.loads(Path(__file__).with_name('simd-families.json').read_text())['families']


def signed(value, width=64):
    return (value + (1 << (width - 1))) % (1 << width) - (1 << (width - 1))


def entries():
    return [(f['name'][0].lower() + f['name'][1:] + 'Composite', f, 'composite') if f.get('composite') else (op + f['name'], f, op)
            for f in FAMILIES for op in (['composite'] if f.get('composite') else f['operations'])
            if op not in ('pack', 'unpack')]


def floating(bits, width):
    fraction_bits, exponent_bits = (23, 8) if width == 32 else (52, 11)
    sign = bits >> (width - 1)
    exponent = bits >> fraction_bits & ((1 << exponent_bits) - 1)
    fraction = bits & ((1 << fraction_bits) - 1)
    if exponent == (1 << exponent_bits) - 1:
        return sign, 'nan' if fraction else 'infinity', None
    bias = (1 << (exponent_bits - 1)) - 1
    significand = fraction + ((1 << fraction_bits) if exponent else 0)
    power = (exponent or 1) - bias - fraction_bits
    value = Fraction(significand << max(0, power), 1 << max(0, -power))
    return sign, 'finite', value


def nearest_even(value):
    quotient, remainder = divmod(value.numerator, value.denominator)
    return quotient + (2 * remainder > value.denominator or (2 * remainder == value.denominator and quotient % 2 == 1))


def encode(sign, value, width):
    fraction_bits, exponent_bits = (23, 8) if width == 32 else (52, 11)
    bias = (1 << (exponent_bits - 1)) - 1
    sign_bit = sign << (width - 1)
    if not value:
        return sign_bit
    exponent = value.numerator.bit_length() - value.denominator.bit_length()
    if value < (Fraction(1 << exponent) if exponent >= 0 else Fraction(1, 1 << -exponent)):
        exponent -= 1
    quantum = max(exponent, 1 - bias) - fraction_bits
    scaled = value / (Fraction(1 << quantum) if quantum >= 0 else Fraction(1, 1 << -quantum))
    significand = nearest_even(scaled)
    if significand == 1 << (fraction_bits + 1):
        significand >>= 1
        exponent += 1
    if exponent > bias:
        return sign_bit | (((1 << exponent_bits) - 1) << fraction_bits)
    if exponent < 1 - bias:
        return sign_bit | significand
    return sign_bit | ((exponent + bias) << fraction_bits) | (significand - (1 << fraction_bits))


def float_operation(operation, left, right, width):
    mask = (1 << width) - 1
    left &= mask
    right &= mask
    fraction_bits, exponent_bits = (23, 8) if width == 32 else (52, 11)
    infinity = ((1 << exponent_bits) - 1) << fraction_bits
    nan = infinity | (1 << (fraction_bits - 1))
    ls, lc, lv = floating(left, width)
    rs, rc, rv = floating(right, width)
    if operation == 'broadcast':
        return nan if lc == 'nan' else left
    if operation == 'negate':
        return nan if lc == 'nan' else left ^ (1 << (width - 1))
    sign = ls ^ rs
    if operation in ('plus', 'minus'):
        if operation == 'minus':
            rs ^= 1
        if lc == 'nan' or rc == 'nan' or lc == rc == 'infinity' and ls != rs:
            return nan
        if lc == 'infinity':
            return (ls << (width - 1)) | infinity
        if rc == 'infinity':
            return (rs << (width - 1)) | infinity
        value = (-lv if ls else lv) + (-rv if rs else rv)
        if not value:
            return (ls << (width - 1)) if not lv and not rv and ls == rs else 0
        return encode(int(value < 0), abs(value), width)
    if operation == 'times':
        zero_times_infinity = ((lc == 'infinity' and rc == 'finite' and rv == 0) or
                               (rc == 'infinity' and lc == 'finite' and lv == 0))
        if lc == 'nan' or rc == 'nan' or zero_times_infinity:
            return nan
        if lc == 'infinity' or rc == 'infinity':
            return (sign << (width - 1)) | infinity
        return encode(sign, lv * rv, width)
    assert operation == 'divide'
    if lc == 'nan' or rc == 'nan' or lc == rc == 'infinity' or lc == rc == 'finite' and not lv and not rv:
        return nan
    if lc == 'infinity' or rc == 'finite' and not rv:
        return (sign << (width - 1)) | infinity
    if rc == 'infinity':
        return sign << (width - 1)
    return encode(sign, lv / rv, width)


def result(family, operation, lane, a, b):
    width = family['bits'] // family['lanes']
    left = signed(a + lane * 104729)
    right = signed(b - lane * 7919)
    if family['laneRep'] in ('FloatRep', 'DoubleRep'):
        value = (float_operation('broadcast', b, 0, width) if operation == 'insert' else
                 float_operation(operation, a if operation == 'broadcast' else left, right, width))
    else:
        left = signed(left, width)
        right = signed(right, width)
        value = {'broadcast': lambda: a, 'insert': lambda: b, 'negate': lambda: -left,
                 'plus': lambda: left + right, 'minus': lambda: left - right,
                 'times': lambda: left * right}[operation]()
        value = value & ((1 << width) - 1) if family['laneRep'] == 'Word32Rep' else signed(value, width)
    # Public wrappers retain an OPAQUE scalar worker and a post-call addition.
    return signed(value + 17)


INTEGER_EDGES = [-(1 << 63), -(1 << 63) + 1, -(1 << 32), -(1 << 31), -(1 << 31) + 1,
                 -65537, -1, 0, 1, 2, 65537, (1 << 31) - 1, 1 << 31, (1 << 32) - 1,
                 (1 << 63) - 1]


def cases(family, operation=None):
    width = family['bits'] // family['lanes']
    if family['laneRep'] in ('FloatRep', 'DoubleRep'):
        fraction_bits, exponent_bits = (23, 8) if width == 32 else (52, 11)
        inf = ((1 << exponent_bits) - 1) << fraction_bits
        one = ((1 << (exponent_bits - 1)) - 1) << fraction_bits
        magnitude = [0, 1, 3, (1 << fraction_bits) - 1, 1 << fraction_bits,
                     one - 1, one, one + 1, one + (1 << fraction_bits), inf - 1, inf,
                     inf + 1, inf + (1 << (fraction_bits - 1)), inf + (1 << fraction_bits) - 1]
        edges = [signed(x | sign << (width - 1)) for x in magnitude for sign in (0, 1)]
        pairs = [(a, 0) for a in edges] if operation in ('broadcast', 'negate') else [
            (a, b) for a in edges for b in edges]
    else:
        pairs = [(a, b) for a in INTEGER_EDGES for b in INTEGER_EDGES]
    # Shift each selected lane back to the edge pattern. Other lanes carry
    # distinct dynamic values, so every position and sign-extension is observed.
    return [(lane, signed(a if operation == 'broadcast' else a - lane * 104729),
             signed(b + lane * 7919))
            for lane in range(family['lanes']) for a, b in pairs]


def rows():
    for name, family, operation in entries():
        operations = [op for op in family['operations'] if op not in ('pack', 'unpack')] if operation == 'composite' else [operation]
        for index, selected in enumerate(operations):
            for lane, a, b in cases(family, selected if operation == 'composite' else None):
                selector = index * family['lanes'] + lane if operation == 'composite' else lane
                yield name, selector, a, b, result(family, selected, lane, a, b)
