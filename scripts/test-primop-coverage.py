#!/usr/bin/env python3
"""Coverage reports must not silently accept a false capability declaration."""
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('primop_coverage', Path(__file__).with_name('primop-coverage.py'))
coverage = importlib.util.module_from_spec(spec)
spec.loader.exec_module(coverage)


class PrimopCoverageTest(unittest.TestCase):
    rows = [('+#', '2', 'Int# -> Int# -> Int#'),
            ('packInt64X2#', '1', '(# Int64#, Int64# #) -> Int64X2#')]

    def test_inventory_retains_unadvertised_operations_and_exact_signatures(self):
        result = coverage.report(self.rows, {'+#': 2})
        self.assertEqual({'total': 2, 'advertised': 1, 'unadvertised': 1}, result['counts'])
        self.assertEqual(self.rows[1][2], result['primitives'][1]['signature'])
        self.assertFalse(result['primitives'][1]['advertised'])

    def test_unknown_names_are_not_counted_as_coverage(self):
        with self.assertRaisesRegex(ValueError, 'absent from pinned GHC'):
            coverage.report(self.rows, {'misspelled#': 1})

    def test_tuple_payload_width_is_not_primitive_value_arity(self):
        with self.assertRaisesRegex(ValueError, 'arity mismatch'):
            coverage.report(self.rows, {'packInt64X2#': 2})
        self.assertEqual(1, coverage.report(self.rows, {'packInt64X2#': 1})['counts']['advertised'])

    def test_boolean_arity_and_duplicate_ghc_rows_are_rejected(self):
        with self.assertRaisesRegex(ValueError, 'arity mismatch'):
            coverage.report(self.rows, {'packInt64X2#': True})
        with self.assertRaisesRegex(ValueError, 'Duplicate GHC primop'):
            coverage.report(self.rows + self.rows[:1], {})


if __name__ == '__main__':
    unittest.main()
