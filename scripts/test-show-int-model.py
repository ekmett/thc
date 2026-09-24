#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

import unittest
from show_int_model import inputs, requests, expected, verify, wrap

class ShowIntModelTest(unittest.TestCase):
    def test_all_signs_decimal_boundaries_and_machine_extrema(self):
        values = set(inputs())
        self.assertTrue({-(1 << 63), (1 << 63)-1, 0} <= values)
        for p in range(19):
            self.assertTrue({s*(10**p+d) for s in (-1, 1) for d in (-1, 0, 1)} <= values)
        for x in values:
            observations = [expected('showCharacter', x, i) for i in range(len(str(x)))]
            self.assertEqual(str(x), ''.join(map(chr, observations)))
            self.assertEqual(-1, expected('showCharacter', x, -1))
            self.assertEqual(-1, expected('showCharacter', x, len(str(x))))
    def test_order_and_length_are_independent_of_wrapping_checksum(self):
        for x in (102, 120, 210, -102, 1001, 1010, 1111, 90909):
            text = str(x)
            polynomial = wrap(5381 * 33**len(text) + sum(ord(c)*33**i for i,c in enumerate(reversed(text))))
            self.assertEqual(polynomial, expected('showChecksum', x, 0))
        self.assertNotEqual(expected('showCharacter', 120, 1), expected('showCharacter', 102, 1))
        self.assertNotEqual(expected('showCharacter', 10, 2), expected('showCharacter', 100, 2))
    def test_missing_duplicate_and_corrupted_observations_fail_closed(self):
        rows = [f'{n}\t{x}\t{i}\t{expected(n,x,i)}\n' for n,x,i in requests()]
        self.assertEqual(len(rows), len(verify(''.join(rows))))
        for bad in (''.join(rows[1:]), ''.join(rows+rows[:1]), ''.join(reversed(rows)), ''.join(rows[:-1])+rows[-1].rsplit('\t',1)[0]+'\t123\n'):
            with self.assertRaises(AssertionError): verify(bad)
if __name__ == '__main__': unittest.main()
