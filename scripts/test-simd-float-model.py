#!/usr/bin/env python3
"""Wide floating family contracts and independent IEEE arithmetic controls."""
import importlib.util
import json
import math
from pathlib import Path
import random
import struct
import unittest
from unittest.mock import patch

from simd_float_model import float_operation, observe_bits


ROOT = Path(__file__).resolve().parent.parent
FRAGMENT = ROOT / 'scripts/simd-float-families.json'
FAMILIES = json.loads(FRAGMENT.read_text())['families']
SPEC = importlib.util.spec_from_file_location('simd_generator', ROOT / 'scripts/generate-simd-families.py')
GEN = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(GEN)

# Independently written bit constants, rather than the model's format helpers.
FORMATS = (
    (32, 'f', 23, 127, 0x3f800000, 0x40000000, 0x3f000000, 0x00800000, 0x7f800000, 0x7fc00000),
    (64, 'd', 52, 1023, 0x3ff0000000000000, 0x4000000000000000, 0x3fe0000000000000,
     0x0010000000000000, 0x7ff0000000000000, 0x7ff8000000000000),
)


def host_value(bits, width, code):
    return struct.unpack('>' + code, bits.to_bytes(width // 8, 'big'))[0]


def host_bits(value, width, code, nan):
    if math.isnan(value):
        return nan
    try:
        packed = struct.pack('>' + code, value)
    except OverflowError:
        packed = struct.pack('>' + code, math.copysign(math.inf, value))
    return int.from_bytes(packed, 'big')


class SimdFloatModelTest(unittest.TestCase):
    def test_fragment_has_exactly_the_four_wide_shapes_and_32_operations(self):
        with patch.object(GEN, 'SPEC', FRAGMENT):
            self.assertEqual(FAMILIES, GEN.families())
        self.assertEqual(['FloatX8', 'FloatX16', 'DoubleX4', 'DoubleX8'], [f['name'] for f in FAMILIES])
        self.assertEqual(32, len(GEN.contracts(FAMILIES)))
        for family in FAMILIES:
            self.assertTrue(family['newCarrier'])
            self.assertEqual(['pack', 'unpack', 'broadcast', 'plus', 'minus', 'times', 'negate', 'divide'], family['operations'])
            contracts = GEN.contracts([family])
            count = family['lanes']
            lane = [family['laneRep']]
            vector = [f'VecRep {count} {family["element"]}']
            self.assertEqual({'arity': 1, 'reps': [lane * count, vector], 'tuples': [True, False]},
                             contracts['pack' + family['name'] + '#'])
            self.assertEqual({'arity': 1, 'reps': [vector, lane * count], 'tuples': [False, True]},
                             contracts['unpack' + family['name'] + '#'])
            for op in ('plus', 'minus', 'times', 'divide'):
                self.assertEqual({'arity': 2, 'reps': [vector, vector, vector], 'tuples': [False, False, False]},
                                 contracts[op + family['name'] + '#'])

    def test_transport_and_negation_preserve_all_observed_bits(self):
        for width, _, _, _, one, _, _, normal, infinity, nan in FORMATS:
            sign = 1 << (width - 1)
            patterns = [0, 1, 3, normal - 1, normal, one - 1, one, one + 1, infinity - 1, infinity]
            for magnitude in patterns:
                for sign_bit in (0, sign):
                    bits = magnitude | sign_bit
                    self.assertEqual(bits, observe_bits(bits, width))
                    for op in ('pack', 'unpack', 'broadcast'):
                        self.assertEqual(bits, float_operation(op, bits, None, width))
                    self.assertEqual(bits ^ sign, float_operation('negate', bits, None, width))
            for bits in (infinity + 1, nan, infinity + normal - 1):
                for sign_bit in (0, sign):
                    for op in ('pack', 'unpack', 'broadcast', 'negate'):
                        self.assertEqual(nan, float_operation(op, bits | sign_bit, None, width))

    def test_signed_zero_rules_for_every_arithmetic_operation(self):
        for width, _, _, _, one, _, _, _, _, _ in FORMATS:
            sign = 1 << (width - 1)
            for left_sign in (0, 1):
                for right_sign in (0, 1):
                    left = left_sign * sign
                    right = right_sign * sign
                    self.assertEqual((left_sign & right_sign) * sign, float_operation('plus', left, right, width))
                    self.assertEqual((left_sign & (1 - right_sign)) * sign, float_operation('minus', left, right, width))
                    self.assertEqual((left_sign ^ right_sign) * sign, float_operation('times', left, right, width))
                    self.assertEqual((left_sign ^ right_sign) * sign, float_operation('divide', left, right | one, width))
                    self.assertEqual(0, float_operation('plus', one | left, one | (left ^ sign), width))
                    self.assertEqual(0, float_operation('minus', one | left, one | left, width))

    def test_infinities_invalid_operations_and_nan_observer(self):
        for width, _, _, _, one, _, _, _, infinity, nan in FORMATS:
            sign = 1 << (width - 1)
            for left_sign in (0, sign):
                for right_sign in (0, sign):
                    left_inf = infinity | left_sign
                    right_inf = infinity | right_sign
                    result_sign = left_sign ^ right_sign
                    self.assertEqual(nan if left_sign != right_sign else left_inf,
                                     float_operation('plus', left_inf, right_inf, width))
                    self.assertEqual(nan if left_sign == right_sign else left_inf,
                                     float_operation('minus', left_inf, right_inf, width))
                    self.assertEqual(infinity | result_sign, float_operation('times', left_inf, right_inf, width))
                    self.assertEqual(nan, float_operation('divide', left_inf, right_inf, width))
                    self.assertEqual(nan, float_operation('times', left_inf, right_sign, width))
                    self.assertEqual(nan, float_operation('times', left_sign, right_inf, width))
                    self.assertEqual(nan, float_operation('divide', left_sign, right_sign, width))
                    self.assertEqual(infinity | result_sign, float_operation('divide', one | left_sign, right_sign, width))
                    self.assertEqual(infinity | result_sign, float_operation('divide', left_inf, right_sign, width))
                    self.assertEqual(result_sign, float_operation('divide', one | left_sign, right_inf, width))
                    for op in ('plus', 'minus', 'times', 'divide'):
                        self.assertEqual(nan, float_operation(op, left_inf | 1, one | right_sign, width))
                        self.assertEqual(nan, float_operation(op, one | left_sign, right_inf | 1, width))

    def test_normal_rounding_ties_to_even_and_exact_cancellation(self):
        for width, _, fraction, bias, one, _, _, _, _, _ in FORMATS:
            sign = 1 << (width - 1)
            half_ulp = (bias - fraction - 1) << fraction
            for sign_bit in (0, sign):
                self.assertEqual(one | sign_bit, float_operation('plus', one | sign_bit, half_ulp | sign_bit, width))
                self.assertEqual((one + 2) | sign_bit, float_operation('plus', (one + 1) | sign_bit, half_ulp | sign_bit, width))
                self.assertEqual(half_ulp + (1 << fraction),
                                 float_operation('minus', one + 1, one, width))
            # Two adjacent values can cancel to an exactly represented tiny value.
            self.assertEqual(1, float_operation('minus', 3, 2, width))
            self.assertEqual(sign | 1, float_operation('minus', 2, 3, width))

    def test_subnormal_rounding_ties_and_normal_boundary(self):
        for width, _, _, _, _, two, half, normal, _, _ in FORMATS:
            sign = 1 << (width - 1)
            for sign_bit in (0, sign):
                for source, expected in ((1, 0), (3, 2), (5, 2), (7, 4)):
                    self.assertEqual(expected | sign_bit, float_operation('divide', source | sign_bit, two, width))
                    self.assertEqual(expected | sign_bit, float_operation('times', source | sign_bit, half, width))
                self.assertEqual(normal | sign_bit, float_operation('times', (2 * normal - 1) | sign_bit, half, width))
                self.assertEqual((normal - 2) | sign_bit, float_operation('times', (2 * normal - 3) | sign_bit, half, width))
                self.assertEqual((normal - 1) | sign_bit, float_operation('plus', (normal - 2) | sign_bit, 1 | sign_bit, width))
                self.assertEqual(normal | sign_bit, float_operation('plus', (normal - 1) | sign_bit, 1 | sign_bit, width))

    def test_overflow_rounding_threshold_and_underflow_sign(self):
        for width, _, fraction, bias, _, two, _, _, infinity, _ in FORMATS:
            sign = 1 << (width - 1)
            half_ulp = (2 * bias - fraction - 1) << fraction
            for sign_bit in (0, sign):
                largest = (infinity - 1) | sign_bit
                self.assertEqual(largest, float_operation('plus', largest, (half_ulp - 1) | sign_bit, width))
                self.assertEqual(infinity | sign_bit, float_operation('plus', largest, half_ulp | sign_bit, width))
                self.assertEqual(infinity | sign_bit, float_operation('times', largest, two, width))
                self.assertEqual(infinity | sign_bit, float_operation('divide', largest, 1, width))
                self.assertEqual(sign_bit, float_operation('divide', 1 | sign_bit, infinity - 1, width))
                self.assertEqual(sign_bit, float_operation('times', 1 | sign_bit, 1, width))

    def test_finite_edges_and_seeded_random_match_independent_host_controls(self):
        operations = {'plus': lambda a, b: a + b, 'minus': lambda a, b: a - b,
                      'times': lambda a, b: a * b, 'divide': lambda a, b: a / b}
        for width, code, _, _, one, two, half, normal, infinity, nan in FORMATS:
            sign = 1 << (width - 1)
            edges = [0, 1, 3, normal - 1, normal, normal + 1, half, one - 1, one, one + 1, two, infinity - 1]
            edges = [bits | sign_bit for bits in edges for sign_bit in (0, sign)]
            pairs = [(a, b) for a in edges for b in edges]
            random_source = random.Random(0x51adf10a7 + width)
            pairs += [(random_source.getrandbits(width), random_source.getrandbits(width)) for _ in range(2500)]
            checked = 0
            for left, right in pairs:
                if left & infinity == infinity or right & infinity == infinity:
                    continue
                a = host_value(left, width, code)
                b = host_value(right, width, code)
                for op, host_op in operations.items():
                    if op == 'divide' and b == 0:
                        continue
                    expected = host_bits(host_op(a, b), width, code, nan)
                    self.assertEqual(expected, float_operation(op, left, right, width),
                                     f'{op} binary{width}: {left:#x}, {right:#x}')
                    checked += 1
            self.assertGreater(checked, 12000)

    def test_signed_inputs_are_masked_to_the_lane_width(self):
        for width, _, _, _, one, _, _, _, _, nan in FORMATS:
            mask = (1 << width) - 1
            self.assertEqual(nan, float_operation('broadcast', -1, 0, width))
            for op in ('plus', 'minus', 'times', 'divide'):
                self.assertEqual(float_operation(op, one, one, width),
                                 float_operation(op, one - mask - 1, one + mask + 1, width))

    def test_invalid_operations_and_widths_fail_explicitly(self):
        for width in (0, 16, 80, 128):
            with self.assertRaisesRegex(ValueError, 'lane width'):
                float_operation('plus', 0, 0, width)
        for operation in ('add', 'quot', 'shuffle'):
            with self.assertRaisesRegex(ValueError, 'operation'):
                float_operation(operation, 0, 0, 32)


if __name__ == '__main__':
    unittest.main()
