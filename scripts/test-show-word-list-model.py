#!/usr/bin/env python3
import unittest
from show_word_list_model import ENTRIES, SHAPES, MASK, inputs, formatted, requests, expected, verify, wrap

class ShowWordListModelTest(unittest.TestCase):
    def test_unsigned_extrema_decimal_and_bit_boundaries(self):
        values = set(inputs())
        self.assertEqual('18446744073709551615', formatted('wordChecksum', -1, 0))
        self.assertEqual('9223372036854775808', formatted('wordChecksum', -(1 << 63), 0))
        self.assertEqual('0', formatted('wordChecksum', 0, 0))
        for power in range(20):
            self.assertTrue({wrap(10**power+d) for d in (-1, 0, 1)} <= values)
        for bit in range(64):
            self.assertTrue({wrap(s*((1 << bit)+d)) for s in (-1, 1) for d in (-1, 0, 1)} <= values)
        self.assertTrue(all(0 <= int(formatted('wordChecksum', x, 0)) <= MASK for x in values))
    def test_list_punctuation_and_wrapping_elements(self):
        self.assertEqual('[]', formatted('listChecksum', 99, 0))
        self.assertEqual('[-1]', formatted('listChecksum', -1, 1))
        self.assertEqual('[-1,0,1]', formatted('listChecksum', -1, 3))
        self.assertEqual('[9223372036854775807,-9223372036854775808,-9223372036854775807]',
                         formatted('listChecksum', (1 << 63)-1, 3))
        self.assertEqual('[-9223372036854775808,-9223372036854775807,-9223372036854775808]',
                         formatted('listChecksum', -(1 << 63), 3))
    def test_every_character_and_end_are_independent_of_checksum(self):
        for x in inputs():
            for prefix in ('word', 'list'):
                for shape in ((0,) if prefix == 'word' else SHAPES):
                    text = formatted(prefix+'Checksum', x, shape)
                    self.assertEqual(text, ''.join(chr(expected(prefix+'Character', x, shape, i)) for i in range(len(text))))
                    self.assertEqual(-1, expected(prefix+'Character', x, shape, -1))
                    self.assertEqual(-1, expected(prefix+'Character', x, shape, len(text)))
                    polynomial = wrap(5381*33**len(text) + sum(ord(c)*33**i for i,c in enumerate(reversed(text))))
                    self.assertEqual(polynomial, expected(prefix+'Checksum', x, shape, 0))
    def test_oracle_inventory_rejects_missing_duplicate_reordered_and_bad_rows(self):
        rows = [f'{n}\t{x}\t{s}\t{i}\t{expected(n,x,s,i)}\n' for n,x,s,i in requests()]
        self.assertEqual(len(rows), len(verify(''.join(rows))))
        for bad in (rows[1:], rows+rows[:1], list(reversed(rows)), rows[:-1]+[rows[-1].rsplit('\t',1)[0]+'\t123\n']):
            with self.assertRaises(AssertionError): verify(''.join(bad))
        with self.assertRaises(ValueError): expected('unknown', 0, 0, 0)

if __name__ == '__main__': unittest.main()
