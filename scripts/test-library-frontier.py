#!/usr/bin/env python3
"""Regression coverage for the manifest's strict Set missing-definition frontier."""
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

    def test_actual_set_frontier_is_missing_definitions_not_capability_gaps(self):
        self.assertEqual([], self.violations())
        self.assertEqual(0, len(self.audit['issues']))
        self.assertEqual(3, len(self.audit['missingGlobals']))
        self.assertNotIn('aggregate-boundary', {i['code'] for i in self.audit['issues']})

    def test_matching_native_rows_cannot_promote_frontier_to_supported(self):
        self.group['execution'] = 'supported'
        self.assertTrue(self.violations())
        self.audit.update(accepted=True, issues=[], missingGlobals=[])
        self.assertTrue(self.violations(), 'Even newly accepted Core requires explicit coverage review')

    def test_additional_gap_with_existing_diagnostic_kind_is_rejected(self):
        extra = dict(code='unsupported-primitive', owner='main:Data.Set.Internal.newUnsupportedPath',
                     detail='newUnsupported#')
        self.audit['issues'].append(extra)
        self.assertTrue(self.violations())

    def test_reintroduced_zero_width_tuple_gap_is_rejected(self):
        self.audit['issues'].append(dict(code='aggregate-representation', owner=prepare.SET_BACKTRACE,
                                        detail='unboxed-tuple: unsupported component'))
        self.assertTrue(self.violations(), 'State# tuple support must not silently regress')

    def test_lost_runrw_tuple_case_proof_is_rejected(self):
        self.audit['issues'].append(dict(code='constructor-kind', owner=prepare.SET_EXCEPTION,
                                        detail='ghc-internal:GHC.Internal.Types.(#,#): unboxed-tuple'))
        self.assertTrue(self.violations(), 'Fresh runRW# exports must retain the exact exception tuple layout')

    def test_reintroduced_mutvar_gap_is_rejected(self):
        self.audit['issues'].append(dict(code='unsupported-primitive', owner=prepare.SET_BACKTRACE,
                                        detail='readMutVar#'))
        self.assertTrue(self.violations(), 'Native-validated MutVar support must not silently regress')

    def test_duplicate_missing_definition_is_rejected(self):
        self.audit['missingGlobals'].append(copy.deepcopy(self.audit['missingGlobals'][0]))
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


class SequenceFrontierTest(unittest.TestCase):
    def setUp(self):
        self.audits = json.loads((ROOT / 'testdata/sequence-entry-frontiers.json').read_text())

    def violations(self, name, audit=None, execution=None):
        if execution is None:
            execution = 'supported' if name in prepare.SEQUENCE_SUPPORTED else 'frontier'
        return prepare.sequence_entry_violations(
            dict(name=name, execution=execution), audit if audit is not None else self.audits[name])

    def test_genuine_entry_audits_keep_four_positives_and_three_missing_definition_frontiers(self):
        self.assertEqual(set(prepare.SEQUENCE_ENTRIES), set(self.audits))
        self.assertEqual(4, sum(audit['accepted'] for audit in self.audits.values()))
        for name in self.audits:
            with self.subTest(name=name):
                self.assertEqual([], self.violations(name))

    def test_frontier_native_agreement_does_not_promote_or_hide_an_entry(self):
        for name in self.audits:
            with self.subTest(name=name):
                wrong = 'frontier' if name in prepare.SEQUENCE_SUPPORTED else 'supported'
                self.assertTrue(self.violations(name, execution=wrong))
        self.assertTrue(self.violations('sequenceBuildViews', self.audits['sequenceBuild']))

    def test_missing_resolution_new_missing_and_duplicate_missing_require_review(self):
        for mutation in ('removed', 'new', 'duplicate'):
            with self.subTest(mutation=mutation):
                audit = copy.deepcopy(self.audits['sequenceSplit'])
                if mutation == 'removed':
                    audit['missingGlobals'].pop()
                elif mutation == 'new':
                    audit['missingGlobals'].append(dict(id='main:Data.Sequence.Internal.unresolved'))
                else:
                    audit['missingGlobals'].append(copy.deepcopy(audit['missingGlobals'][0]))
                self.assertTrue(self.violations('sequenceSplit', audit))

    def test_regressed_empty_input_or_state_tuple_proofs_fail_even_on_an_existing_frontier(self):
        for name in ('sequenceBuild', 'sequenceSplit'):
            with self.subTest(name=name):
                audit = copy.deepcopy(self.audits[name])
                audit['issues'].append(dict(code='aggregate-boundary', owner=audit['roots'][0],
                                          detail='unboxed-tuple formal argument'))
                self.assertTrue(self.violations(name, audit))

    def test_swapped_entry_audit_and_silent_acceptance_are_rejected(self):
        self.assertTrue(self.violations('sequenceEnds', self.audits['sequenceBuild']))
        audit = copy.deepcopy(self.audits['sequenceSplit'])
        audit.update(accepted=True, missingGlobals=[], issues=[])
        self.assertTrue(self.violations('sequenceSplit', audit))


if __name__ == '__main__':
    unittest.main()
