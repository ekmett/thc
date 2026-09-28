#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

"""Independent offset formulas and exact public slice/source-frontier controls."""
import importlib.util
from pathlib import Path
import unittest
import short_bytes_slice_model as model

class ModelTest(unittest.TestCase):
    def test_every_native_observation_against_direct_source_offsets(self):
        for name, seed, count, side, selector in model.requests():
            length = abs(seed) % 17
            split = 0 if count < 0 else length if count > length else count
            left = name == 'takeCase' or name == 'splitCase' and side == 0
            offset, size = (0, split) if left else (split, length-split)
            if selector == -1: expected = size
            elif selector == -2:
                expected = 0
                for i in range(size): expected = model.wrap(33*expected+((seed+73*(offset+i)) & 255))
            else: expected = ((seed+73*(offset+selector)) & 255) if 0 <= selector < size else -1
            self.assertEqual(model.expected(name, seed, count, side, selector), expected)

    def test_full_byte_domain_both_split_parts_and_machine_boundaries(self):
        self.assertEqual(len(model.seeds()), 265)
        self.assertEqual({b for s in model.seeds() for b in model.payload(s)}, set(range(256)))
        for seed in model.seeds():
            for count in model.counts(seed):
                left = model.sliced('splitCase', seed, count, 0)
                right = model.sliced('splitCase', seed, count, 1)
                self.assertEqual(left+right, model.payload(seed))
                self.assertEqual(left, model.sliced('takeCase', seed, count, 0))
                self.assertEqual(right, model.sliced('dropCase', seed, count, 0))
            self.assertEqual(model.sliced('takeCase', seed, model.MIN, 0), [])
            self.assertEqual(model.sliced('dropCase', seed, model.MAX, 0), [])
        self.assertEqual(len(list(model.requests())), 81312)

    def test_missing_duplicate_reordered_and_wrong_native_rows_rejected(self):
        text = ''.join('\t'.join(map(str, (*key, model.expected(*key))))+'\n' for key in model.requests())
        self.assertEqual(model.verify(text), 81312)
        rows = text.splitlines(keepends=True)
        for bad in (''.join(rows[1:]), text+rows[0], ''.join([rows[1], rows[0], *rows[2:]]),
                    rows[0].rsplit('\t', 1)[0]+'\t999\n'+''.join(rows[1:])):
            with self.assertRaises(AssertionError): model.verify(bad)

    def test_installed_unit_identity_and_frontier_remain_exact(self):
        spec = importlib.util.spec_from_file_location('slice_prepare_units', Path(__file__).with_name('prepare-short-bytes-slices.py'))
        prepare = importlib.util.module_from_spec(spec); spec.loader.exec_module(prepare)
        for unit in ('bytestring-0.12.2.0-5637', 'bytestring-0.12.2.0-3f3f'):
            expected = unit+':Data.ByteString.Internal.Type.overflowError'
            good = dict(accepted=False, issues=[], missingGlobals=[dict(id=expected)])
            prepare.check_frontier(good, unit, 'retained platform unit')
            other = 'bytestring-0.12.2.0-'+('3f3f' if unit.endswith('5637') else '5637')
            for bad in (dict(good, accepted=True), dict(good, issues=[dict(code='unsupported-primop')]),
                        dict(good, missingGlobals=[]), dict(good, missingGlobals=[dict(id=expected)]*2),
                        dict(good, missingGlobals=[dict(id=other+prepare.OVERFLOW_WORKER)]),
                        dict(good, missingGlobals=[dict(id=unit+':Data.ByteString.Internal.Type.other')])):
                with self.assertRaises(AssertionError): prepare.check_frontier(bad, unit, 'negative')
        for bad in (None, '', 'bytestring-0.12.1.0-5637', 'bytestring-0.12.2.0-5637 extra',
                    'bytestring-0.12.2.0-5637:forged', ['bytestring-0.12.2.0-5637']):
            with self.assertRaises(AssertionError): prepare.overflow_id(bad)

    def test_prepared_original_composition_and_exact_missing_source_frontiers(self):
        spec = importlib.util.spec_from_file_location('slice_prepare', Path(__file__).with_name('prepare-short-bytes-slices.py'))
        prepare = importlib.util.module_from_spec(spec); spec.loader.exec_module(prepare)
        stages, coverage = prepare.inventory()
        self.assertEqual(set(stages), {'pre', 'post'})
        for reports in coverage.values():
            self.assertEqual(set(reports), set(model.ENTRIES) | set(prepare.FRONTIERS))
            self.assertTrue(all(reports[name]['accepted'] for name in model.ENTRIES))
            self.assertTrue(all(not reports[name]['accepted'] for name in prepare.FRONTIERS))

if __name__ == '__main__': unittest.main()
