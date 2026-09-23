#!/usr/bin/env python3
"""Regression coverage for the manifest's strict Set exception/state frontier."""
import copy
import importlib.util
import json
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('prepare_libraries', ROOT / 'prepare-library-tests.py')
prepare = importlib.util.module_from_spec(spec)
spec.loader.exec_module(prepare)


class LibraryFrontierTest(unittest.TestCase):
    def setUp(self):
        self.audit = json.loads((ROOT / 'testdata/set-frontier-audit.json').read_text())
        self.group = dict(id='set', execution='frontier', entries=[dict(name='setAggregate', warm=[[1, 334]], cold=[[8, 128985]])])

    def violations(self):
        return prepare.audit_structure_violations(self.group, self.audit)

    def test_actual_set_frontier_is_exception_state_not_collection_tuple_results(self):
        self.assertEqual([], self.violations())
        self.assertEqual(4, len(self.audit['issues']))
        self.assertEqual(3, len(self.audit['missingGlobals']))
        self.assertNotIn('aggregate-boundary', {i['code'] for i in self.audit['issues']})

    def test_matching_native_rows_cannot_promote_frontier_to_supported(self):
        self.group['execution'] = 'supported'
        self.assertTrue(self.violations())
        self.audit.update(accepted=True, issues=[], missingGlobals=[])
        self.assertTrue(self.violations(), 'Even newly accepted Core requires explicit coverage review')

    def test_additional_gap_with_existing_diagnostic_kind_is_rejected(self):
        extra = copy.deepcopy(next(i for i in self.audit['issues'] if i['code'] == 'constructor-field-representation'))
        extra['owner'] = 'main:Data.Set.Internal.newUnsupportedPath'
        self.audit['issues'].append(extra)
        self.assertTrue(self.violations())

    def test_reintroduced_zero_width_tuple_gap_is_rejected(self):
        self.audit['issues'].append(dict(code='aggregate-representation', owner=prepare.SET_BACKTRACE,
                                        detail='unboxed-tuple: unsupported component'))
        self.assertTrue(self.violations(), 'State# tuple support must not silently regress')

    def test_duplicate_gap_and_changed_primitive_are_rejected(self):
        original = copy.deepcopy(self.audit)
        self.audit['issues'].append(copy.deepcopy(self.audit['issues'][0]))
        self.assertTrue(self.violations())
        self.audit = original
        next(i for i in self.audit['issues'] if i['code'] == 'unsupported-primitive')['detail'] = 'newUnsupported#'
        self.assertTrue(self.violations())

    def test_removed_gap_requires_coverage_review(self):
        self.audit['issues'].pop()
        self.assertTrue(self.violations())

    def test_unknown_or_missing_source_definition_is_rejected(self):
        self.audit['missingGlobals'][0]['id'] = 'main:Data.Set.Internal.unresolved'
        self.assertTrue(self.violations())

    def test_missing_definition_resolution_requires_coverage_review(self):
        self.audit['missingGlobals'].pop()
        self.assertTrue(self.violations())

    def test_wrong_entry_or_lost_pointer_primitive_is_rejected(self):
        self.audit['roots'] = ['main:THC.SetWorkload.other']
        self.assertTrue(self.violations())
        self.audit['roots'] = ['main:THC.SetWorkload.setAggregate']
        self.audit['primitives'] = [p for p in self.audit['primitives'] if p['name'] != 'reallyUnsafePtrEquality#']
        self.assertTrue(self.violations())


if __name__ == '__main__':
    unittest.main()
