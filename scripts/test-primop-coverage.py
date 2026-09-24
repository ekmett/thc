#!/usr/bin/env python3
"""Coverage reports must not silently accept a false capability declaration."""
import importlib.util
import copy
from pathlib import Path
import tempfile
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


class PrimopChecklistTest(unittest.TestCase):
    rows = [('+#', '2', 'Int# -> Int# -> Int#'),
            ('packInt64X2#', '1', '(# Int64#, Int64# #) -> Int64X2#'),
            ('plusAddr#', '2', 'Addr# -> Int# -> Addr#'),
            ('tagToEnum#', '1', 'forall a. Int# -> a'),
            ('dataToTagSmall#', '1', 'forall a. a -> Int#'),
            ('dataToTagLarge#', '1', 'forall a. a -> Int#')]
    capability = dict(primitives={'+#': 2, 'packInt64X2#': 1, 'plusAddr#': 2},
                      tagToEnum='concrete-nullary-family', dataToTag='concrete-algebraic-family-64')
    scalars = dict(schema=1, ghc='9.14.1', targetWordSize=64, primitives={
        '+#': dict(arguments=['IntRep', 'IntRep'], result='IntRep'),
        'plusAddr#': dict(arguments=['AddrRep', 'IntRep'], result='AddrRep')})

    def derive(self, cap=None, scalars=None):
        cap = self.capability if cap is None else cap
        data = coverage.report(self.rows, coverage.declared_primitives(cap))
        return coverage.classify(data, cap, self.scalars if scalars is None else scalars)

    def test_family_gates_count_as_partial_not_missing_or_full_scalar(self):
        data = self.derive()
        self.assertEqual(dict(supported=1, partial=5, missing=0), data['supportCounts'])
        by_name = {row['name']: row for row in data['primitives']}
        for name in ('tagToEnum#', 'dataToTagSmall#', 'dataToTagLarge#'):
            self.assertEqual(('partial', 1), (by_name[name]['status'], by_name[name]['valueArity']))

    def test_vector_and_address_forms_do_not_become_full_scalar_support(self):
        rows = {row['name']: row for row in self.derive()['primitives']}
        self.assertEqual('partial', rows['packInt64X2#']['status'])
        self.assertEqual('Managed literal addresses only', rows['plusAddr#']['scope'])
        self.assertEqual('supported', rows['+#']['status'])

    def test_managed_mvars_remain_partial_without_claiming_a_guest_scheduler(self):
        cap = dict(primitives={'newMVar#': 1}, managedMVarPrimitives={
            'newMVar#': dict(arguments=['state'], result=['state', 'mvar'])})
        data = coverage.report([('newMVar#', '1', 'State# s -> (# State# s, MVar# s a #)')], cap['primitives'])
        scalars = dict(schema=1, ghc='9.14.1', targetWordSize=64, primitives={})
        row = coverage.classify(data, cap, scalars)['primitives'][0]
        self.assertEqual('partial', row['status'])
        self.assertEqual('Managed blocking cells; no guest scheduler or async exceptions', row['scope'])

    def test_unadvertised_is_missing_and_new_advertisements_default_to_partial(self):
        cap = copy.deepcopy(self.capability)
        del cap['tagToEnum']
        before = self.derive(cap)
        self.assertEqual(1, before['supportCounts']['missing'])
        cap['primitives']['tagToEnum#'] = 1
        after = self.derive(cap)
        self.assertEqual(dict(supported=1, partial=5, missing=0), after['supportCounts'])

    def test_stale_scalar_table_or_wrong_target_cannot_mark_support(self):
        for mutation in ('undeclared', 'arity', 'target'):
            scalars = copy.deepcopy(self.scalars)
            if mutation == 'undeclared':
                scalars['primitives']['madeUp#'] = dict(arguments=[], result='IntRep')
            elif mutation == 'arity':
                scalars['primitives']['+#']['arguments'].pop()
            else:
                scalars['targetWordSize'] = 32
            with self.subTest(mutation=mutation), self.assertRaises(ValueError):
                self.derive(scalars=scalars)

    def test_family_gate_cannot_hide_false_arity_or_unknown_contract(self):
        for bad in (True, 2, '1'):
            cap = copy.deepcopy(self.capability)
            cap['primitives']['tagToEnum#'] = bad
            with self.subTest(arity=bad), self.assertRaisesRegex(ValueError, 'Contradictory'):
                self.derive(cap)
        cap = copy.deepcopy(self.capability)
        cap['tagToEnum'] = 'unrestricted'
        with self.assertRaisesRegex(ValueError, 'Unrecognized'):
            self.derive(cap)
        with self.assertRaisesRegex(ValueError, 'absent from pinned GHC'):
            coverage.report(self.rows[:-1], coverage.declared_primitives(self.capability))

    def test_capability_change_makes_document_stale_without_overwriting_it(self):
        original = coverage.checklist(self.derive(), self.capability)
        changed = copy.deepcopy(self.capability)
        changed['primitives'].pop('packInt64X2#')
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'primops.md'
            path.write_text(original)
            coverage.check_document(path, original)
            with self.assertRaisesRegex(ValueError, 'is stale'):
                coverage.check_document(path, coverage.checklist(self.derive(changed), changed))
            self.assertEqual(original, path.read_text())
        self.assertIn('- [x] `+#`', original)
        self.assertIn('- [ ] `packInt64X2#`', original)

    def test_document_is_independent_of_input_order_and_does_not_mutate_contracts(self):
        cap = copy.deepcopy(self.capability)
        sig = copy.deepcopy(self.scalars)
        expected = coverage.checklist(self.derive(), cap)
        result = coverage.classify(coverage.report(list(reversed(self.rows)), coverage.declared_primitives(cap)), cap, sig)
        self.assertEqual(expected, coverage.checklist(result, cap))
        self.assertEqual(self.capability, cap)
        self.assertEqual(self.scalars, sig)


if __name__ == '__main__':
    unittest.main()
