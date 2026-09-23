#!/usr/bin/env python3
"""Exact binary64 rounding controls independent of the JVM Vector API."""
import random
import struct
import unittest
import doublex2_model as model


def bits(value):
    return struct.unpack('>Q', struct.pack('>d', value))[0]


def value(bits):
    return struct.unpack('>d', struct.pack('>Q', bits))[0]


class DoubleX2ModelTest(unittest.TestCase):
    def test_binary64_ties_and_no_float_narrowing(self):
        self.assertEqual(model.integer(2**53 + 1), 0x4340000000000000)
        self.assertEqual(model.integer(2**53 + 3), 0x4340000000000002)
        self.assertEqual(model.add(0x3ff0000000000001, 0), 0x3ff0000000000001)
        self.assertEqual(model.add(0x3ff0000000000000, 0x3ca0000000000000), 0x3ff0000000000000)
        self.assertEqual(model.add(0x3ff0000000000001, 0x3ca0000000000000), 0x3ff0000000000002)

    def test_underflow_transition_overflow_and_sign(self):
        half = 0x3fe0000000000000
        self.assertEqual(model.multiply(1, half), 0)
        self.assertEqual(model.multiply(model.SIGN | 1, half), model.SIGN)
        self.assertEqual(model.multiply(3, half), 2)
        self.assertEqual(model.add(model.FRAC, 1), 1 << 52)
        self.assertEqual(model.multiply(1 << 52, half), 1 << 51)
        self.assertEqual(model.multiply(model.INF - 1, model.integer(2)), model.INF)
        self.assertEqual(model.add(model.SIGN, model.SIGN), model.SIGN)
        self.assertEqual(model.subtract(model.SIGN, 0), model.SIGN)
        self.assertEqual(model.subtract(model.integer(1), model.integer(1)), 0)
        self.assertTrue(model.is_nan(model.add(model.INF, model.SIGN | model.INF)))
        self.assertTrue(model.is_nan(model.multiply(0, model.INF)))

    def test_non_fma_and_asymmetric_lanes(self):
        self.assertEqual(model.expected('nonFmaCase', 0), '0')
        self.assertNotEqual(model.rounded(model.SIGN, 1, -104), 0)
        self.assertEqual(model.expected('edgeTimes0', 19, 20, 13), '2')
        self.assertEqual(model.expected('edgeTimes1', 19, 20, 13), str(model.SIGN | 2))
        self.assertNotEqual(model.expected('timesCase', 2, 7), model.expected('timesCase', 7, 2))

    def test_integer_model_agrees_with_separate_host_binary64_controls(self):
        # This is only a cross-check of the integer model, never the oracle implementation.
        randomizer = random.Random(0xD02B1E)
        pairs = [(a, b) for a in model.EDGE_BITS for b in model.EDGE_BITS]
        pairs += [(randomizer.getrandbits(64), randomizer.getrandbits(64)) for _ in range(2000)]
        for a, b in pairs:
            for expected, operation in [(model.add(a, b), lambda: value(a) + value(b)),
                                        (model.subtract(a, b), lambda: value(a) - value(b)),
                                        (model.multiply(a, b), lambda: value(a) * value(b))]:
                actual = bits(operation())
                self.assertEqual(model.answer(expected), model.answer(actual), (hex(a), hex(b)))

    def test_all_declared_rows_and_each_lane_edge_pair(self):
        rows = model.model_rows()
        self.assertEqual(len(rows), 3390)
        text = ''.join('\t'.join(map(str, (*key, answer)))+'\n' for key, answer in rows.items())
        self.assertEqual(model.parse_rows(text), rows)
        with self.assertRaises(AssertionError):
            model.parse_rows(text + text.splitlines()[0] + '\n')
        for entry in model.entries():
            if entry['name'].startswith('edge'):
                lane = int(entry['name'][-1])
                self.assertEqual({(xs[lane], xs[2]) for xs in entry['cases']},
                                 {(a, b) for a in range(21) for b in range(21)})


if __name__ == '__main__':
    unittest.main()
