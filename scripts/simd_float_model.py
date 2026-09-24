"""Exact IEEE binary32/binary64 results for scalar observations of SIMD lanes.

Finite arithmetic uses rational numbers and rounds once, to nearest with ties to
even. No host floating arithmetic or JVM vector implementation supplies results.
NaNs have the canonical bits produced by the fixture's bitsFloat/bitsDouble
observer; this does not prescribe the NaN payload of the underlying operation.
Broadcast, pack and unpack retain their operand bits before that observation.
"""
from fractions import Fraction


FORMATS = {32: (23, 8), 64: (52, 11)}
OPERATIONS = {'pack', 'unpack', 'broadcast', 'plus', 'minus', 'times', 'negate', 'divide'}


def _format(width):
    if width not in FORMATS:
        raise ValueError(f'Unsupported IEEE lane width: {width}')
    fraction_bits, exponent_bits = FORMATS[width]
    bias = (1 << (exponent_bits - 1)) - 1
    infinity = ((1 << exponent_bits) - 1) << fraction_bits
    return fraction_bits, bias, infinity


def observe_bits(bits, width):
    """Preserve every non-NaN bit, and canonicalize NaNs as the fixture does."""
    fraction_bits, _, infinity = _format(width)
    bits &= (1 << width) - 1
    if bits & infinity == infinity and bits & ((1 << fraction_bits) - 1):
        return infinity | (1 << (fraction_bits - 1))
    return bits


def _power_of_two(exponent):
    return Fraction(1 << exponent) if exponent >= 0 else Fraction(1, 1 << -exponent)


def _decode(bits, width):
    fraction_bits, bias, infinity = _format(width)
    sign = bits >> (width - 1)
    exponent = (bits & infinity) >> fraction_bits
    fraction = bits & ((1 << fraction_bits) - 1)
    if bits & infinity == infinity:
        return sign, 'nan' if fraction else 'infinity', None
    significand = fraction + ((1 << fraction_bits) if exponent else 0)
    return sign, 'finite', significand * _power_of_two((exponent or 1) - bias - fraction_bits)


def _round_even(value):
    whole, remainder = divmod(value.numerator, value.denominator)
    halfway = 2 * remainder - value.denominator
    return whole + int(halfway > 0 or halfway == 0 and whole % 2 == 1)


def _encode(sign, magnitude, width):
    fraction_bits, bias, infinity = _format(width)
    sign_bit = sign << (width - 1)
    if not magnitude:
        return sign_bit
    exponent = magnitude.numerator.bit_length() - magnitude.denominator.bit_length()
    if magnitude < _power_of_two(exponent):
        exponent -= 1
    quantum = max(exponent, 1 - bias) - fraction_bits
    significand = _round_even(magnitude / _power_of_two(quantum))
    if significand == 1 << (fraction_bits + 1):
        significand >>= 1
        exponent += 1
    if exponent > bias:
        return sign_bit | infinity
    if exponent < 1 - bias:
        # Rounding the greatest subnormal up naturally sets the exponent bit.
        return sign_bit | significand
    return sign_bit | ((exponent + bias) << fraction_bits) | (significand - (1 << fraction_bits))


def float_operation(operation, left_bits, right_bits, width):
    """Return observed lane bits, accepting signed or unsigned bit-pattern inputs.

For unary/transport operations right_bits is ignored. The transport cases model
one selected lane; lane ordering and replication belong to the family fixture.
"""
    fraction_bits, _, infinity = _format(width)
    if operation not in OPERATIONS:
        raise ValueError(f'Unsupported floating SIMD operation: {operation}')
    mask = (1 << width) - 1
    left_bits &= mask
    if operation in ('pack', 'unpack', 'broadcast'):
        return observe_bits(left_bits, width)
    if operation == 'negate':
        return observe_bits(left_bits ^ (1 << (width - 1)), width)

    right_bits &= mask
    left_sign, left_class, left = _decode(left_bits, width)
    right_sign, right_class, right = _decode(right_bits, width)
    nan = infinity | (1 << (fraction_bits - 1))
    if left_class == 'nan' or right_class == 'nan':
        return nan

    if operation in ('plus', 'minus'):
        right_sign ^= operation == 'minus'
        if left_class == right_class == 'infinity':
            return nan if left_sign != right_sign else (left_sign << (width - 1)) | infinity
        if left_class == 'infinity':
            return (left_sign << (width - 1)) | infinity
        if right_class == 'infinity':
            return (right_sign << (width - 1)) | infinity
        total = (-left if left_sign else left) + (-right if right_sign else right)
        # Exact cancellation is +0, except when both signed operands are -0.
        sign = int(total < 0) if total else left_sign & right_sign
        return _encode(sign, abs(total), width)

    sign = left_sign ^ right_sign
    sign_bit = sign << (width - 1)
    if operation == 'times':
        if left_class == 'infinity' or right_class == 'infinity':
            if left_class == 'finite' and not left or right_class == 'finite' and not right:
                return nan
            return sign_bit | infinity
        return _encode(sign, left * right, width)

    if left_class == right_class == 'infinity' or left_class == right_class == 'finite' and not left and not right:
        return nan
    if left_class == 'infinity' or right_class == 'finite' and not right:
        return sign_bit | infinity
    if right_class == 'infinity':
        return sign_bit
    return _encode(sign, left / right, width)
