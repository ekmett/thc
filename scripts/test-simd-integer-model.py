#!/usr/bin/env python3
"""Independent integer controls; --verify-ghc also checks pinned GHC contracts.

The optional GHC checks validate signatures and scalar observation conventions.
They do not claim native or guest execution of the new SIMD families.
"""
import ctypes
import importlib.util
import json
import operator
import os
from pathlib import Path
import random
import subprocess
import sys
import unittest

from simd_integer_model import integer_operation

ROOT = Path(__file__).resolve().parent.parent
FRAGMENT = ROOT / 'scripts/simd-word-families.json'
VERIFY_GHC = '--verify-ghc' in sys.argv
if VERIFY_GHC:
    sys.argv.remove('--verify-ghc')

CASTS = {
    8: (ctypes.c_int8, ctypes.c_uint8),
    16: (ctypes.c_int16, ctypes.c_uint16),
    32: (ctypes.c_int32, ctypes.c_uint32),
    64: (ctypes.c_int64, ctypes.c_uint64),
}
ARITHMETIC = {'plus': operator.add, 'minus': operator.sub, 'times': operator.mul}


def observed(value, width, unsigned):
    """An independent C integer conversion control, including the guest Long."""
    return ctypes.c_int64(CASTS[width][unsigned](value).value).value


def edges(width):
    sign = 1 << (width - 1)
    modulus = 1 << width
    alternating = int('55' * (width // 8), 16)
    return sorted({
        -(1 << 63), -(1 << 63) + 1, (1 << 63) - 1,
        -modulus - 1, -modulus, -sign - 1, -sign, -sign + 1,
        -2, -1, 0, 1, 2, sign - 1, sign, sign + 1,
        modulus - 2, modulus - 1, modulus, modulus + 1,
        alternating, alternating << 1,
    })


def generator():
    spec = importlib.util.spec_from_file_location(
        'simd_generator', ROOT / 'scripts/generate-simd-families.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class SimdIntegerModelTest(unittest.TestCase):
    def test_exhaustive_byte_operand_pairs(self):
        for unsigned in (False, True):
            for left in range(256):
                for right in range(256):
                    for operation, arithmetic in ARITHMETIC.items():
                        actual = integer_operation(operation, left, right, 8, unsigned)
                        expected = observed(arithmetic(left, right), 8, unsigned)
                        self.assertEqual(expected, actual,
                                         (operation, left, right, unsigned))

    def test_exhaustive_byte_unary_and_observation(self):
        for left in range(-256, 512):
            for unsigned in (False, True):
                for operation in ('pack', 'unpack', 'broadcast'):
                    self.assertEqual(observed(left, 8, unsigned),
                                     integer_operation(operation, left, 77, 8, unsigned))
            self.assertEqual(observed(-left, 8, False),
                             integer_operation('negate', left, 77, 8, False))

    def test_wider_boundary_cross_products(self):
        for width in (16, 32, 64):
            for unsigned in (False, True):
                for left in edges(width):
                    for right in edges(width):
                        for operation, arithmetic in ARITHMETIC.items():
                            with self.subTest(width=width, unsigned=unsigned,
                                              operation=operation, left=left, right=right):
                                self.assertEqual(observed(arithmetic(left, right), width, unsigned),
                                                 integer_operation(operation, left, right, width, unsigned))

    def test_deterministic_random_controls(self):
        rng = random.Random(0x544843)
        for width in (8, 16, 32, 64):
            for _ in range(2048):
                left = rng.randrange(-(1 << 63), 1 << 63)
                right = rng.randrange(-(1 << 63), 1 << 63)
                for unsigned in (False, True):
                    for operation, arithmetic in ARITHMETIC.items():
                        self.assertEqual(observed(arithmetic(left, right), width, unsigned),
                                         integer_operation(operation, left, right, width, unsigned),
                                         (width, unsigned, operation, left, right))
                    self.assertEqual(observed(left, width, unsigned),
                                     integer_operation('broadcast', left, right, width, unsigned))
                self.assertEqual(observed(-left, width, False),
                                 integer_operation('negate', left, right, width, False))

    def test_unsigned_high_bits_and_signed_minimum_are_observable(self):
        for width in (8, 16, 32):
            sign = 1 << (width - 1)
            maximum = (1 << width) - 1
            self.assertEqual(maximum, integer_operation('minus', 0, 1, width, True))
            self.assertEqual(-1, integer_operation('minus', 0, 1, width, False))
            self.assertEqual(sign, integer_operation('plus', sign - 1, 1, width, True))
            self.assertEqual(-sign, integer_operation('plus', sign - 1, 1, width, False))
            self.assertEqual(-sign, integer_operation('negate', -sign, 0, width, False))
            self.assertEqual(0, integer_operation('plus', maximum, 1, width, True))
            self.assertEqual(1, integer_operation('times', maximum, maximum, width, True))
        for unsigned in (False, True):
            self.assertEqual(-1, integer_operation('broadcast', (1 << 64) - 1, 0, 64, unsigned))
            self.assertEqual(-(1 << 63), integer_operation('plus', (1 << 63) - 1, 1, 64, unsigned))
            self.assertEqual(1, integer_operation('times', (1 << 64) - 1, (1 << 64) - 1, 64, unsigned))

    def test_broadcast_uses_left_and_ignores_right(self):
        for width in CASTS:
            for unsigned in (False, True):
                for left in edges(width):
                    self.assertEqual(observed(left, width, unsigned),
                                     integer_operation('broadcast', left, None, width, unsigned))

    def test_invalid_contracts_are_rejected(self):
        for width in (0, 1, 7, 24, 128, True, 8.0):
            with self.assertRaises(ValueError):
                integer_operation('plus', 1, 2, width, True)
        for unsigned in (None, 0, 1, 'Word'):
            with self.assertRaises(ValueError):
                integer_operation('plus', 1, 2, 8, unsigned)
        for operation in ('divide', 'shuffle', 'negate'):
            with self.assertRaises(ValueError):
                integer_operation(operation, 1, 2, 8, True)

    def test_wide_word_fragment_has_exact_shapes_and_contracts(self):
        data = json.loads(FRAGMENT.read_text())
        self.assertEqual((1, '9.14.1'), (data['schema'], data['ghc']))
        families = data['families']
        expected = {f'Word{width}X{bits // width}': (width, bits)
                    for width in (8, 16, 32, 64) for bits in (256, 512)}
        self.assertEqual(8, len(families))
        self.assertEqual(set(expected), {family['name'] for family in families})
        contracts = generator().contracts(families)
        self.assertEqual(48, len(contracts))
        for family in families:
            width, bits = expected[family['name']]
            count = bits // width
            lane = f'Word{width}Rep'
            element = f'Word{width}ElemRep'
            vector = [f'VecRep {count} {element}']
            packed = [lane] * count
            self.assertEqual((lane, element, count, bits, True),
                             (family['laneRep'], family['element'], family['lanes'],
                              family['bits'], family['newCarrier']))
            self.assertEqual(['pack', 'unpack', 'broadcast', 'plus', 'minus', 'times'],
                             family['operations'])
            for operation in family['operations']:
                if operation == 'pack':
                    reps, tuples = [packed, vector], [True, False]
                elif operation == 'unpack':
                    reps, tuples = [vector, packed], [False, True]
                elif operation == 'broadcast':
                    reps, tuples = [[lane], vector], [False, False]
                else:
                    reps, tuples = [vector, vector, vector], [False, False, False]
                self.assertEqual(dict(arity=len(reps) - 1, reps=reps, tuples=tuples),
                                 contracts[operation + family['name'] + '#'])

    @unittest.skipUnless(VERIFY_GHC, 'use --verify-ghc for pinned GHC checks')
    def test_pinned_ghc_exact_fragment_contracts(self):
        generator().verify_ghc(json.loads(FRAGMENT.read_text())['families'])

    @unittest.skipUnless(VERIFY_GHC, 'use --verify-ghc for pinned GHC checks')
    def test_pinned_ghc_scalar_observation_conventions(self):
        ghc = os.environ.get('GHC', 'ghc')
        self.assertEqual('9.14.1', subprocess.check_output(
            [ghc, '--numeric-version'], text=True).strip())
        expressions, expected = [], []
        for width in CASTS:
            # These are host Int inputs, including high lane bits and truncation.
            values = sorted(set(range(-256, 512) if width == 8 else []) |
                            {value for value in edges(width) if -(1 << 63) <= value < 1 << 63})
            for unsigned in (False, True):
                observe = (f'word2Int# (word{width}ToWord# (wordToWord{width}# (int2Word# x)))'
                           if unsigned else f'int{width}ToInt# (intToInt{width}# x)')
                expressions.append(f'map (\\value -> case value of I# x -> I# ({observe})) {values}')
                expected.append([integer_operation('broadcast', value, None, width, unsigned)
                                 for value in values])
        actual = subprocess.check_output(
            [ghc, '-ignore-dot-ghci', '-XMagicHash', '-e', ':m +GHC.Exts',
             '-e', '[' + ','.join(expressions) + ']'], text=True)
        self.assertEqual(expected, json.loads(actual))


if __name__ == '__main__':
    unittest.main()
