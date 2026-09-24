#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

"""Independent checks of sum metadata projections, not execution coverage."""
import copy
import importlib.util
from pathlib import Path
import unittest
from sum_layout_model import alternative_slots

spec = importlib.util.spec_from_file_location('sum_check', Path(__file__).with_name('check-sum-layout.py'))
check = importlib.util.module_from_spec(spec)
spec.loader.exec_module(check)


class SumLayoutTest(unittest.TestCase):
    def test_ordered_alternatives_share_only_compatible_slots(self):
        expected = {
            'returnedSum': [[1], [1]], 'nestedSum': [[1, 2], [1, 2, 3]],
            'lazySum': [[1], [2]], 'zeroSum': [[], []], 'unitSum': [[1], []],
            'boxedKindsSum': [[1], [2]], 'floatDoubleSum': [[1], [2]],
            'narrowWideSum': [[1], [1]], 'threeWaySum': [[], [1], [1]],
            'addressResult': [[1], [1]], 'vectorResult': [[2], [1]],
        }
        for name, slots in expected.items():
            with self.subTest(name=name):
                self.assertEqual(slots, check.EXPECTED[name]['alternativeSlots'])
                check.validate_layout(check.EXPECTED[name])

    def test_projection_preserves_source_order_and_repeated_fields(self):
        alternatives = [dict(primReps=['DoubleRep', 'IntRep', 'IntRep']), dict(primReps=['IntRep'])]
        self.assertEqual([[3, 1, 2], [1]], alternative_slots(alternatives,
            ['WordRep', 'WordRep', 'WordRep', 'DoubleRep']))
        with self.assertRaises(ValueError):
            alternative_slots(alternatives, ['WordRep', 'WordRep', 'DoubleRep'])

    def test_distinct_pointer_levities_float_widths_and_vector_shapes(self):
        for source, target in [(check.LIFTED, check.UNLIFTED), ('FloatRep', 'DoubleRep'),
                ('VecRep 4 Int32ElemRep', 'VecRep 2 Int64ElemRep'), ('Word64Rep', 'WordRep')]:
            with self.subTest(source=source), self.assertRaises(ValueError):
                alternative_slots([dict(primReps=[source])], ['WordRep', target])

    def test_unknown_physical_or_logical_layout_stays_null(self):
        for name in ('runtimePolymorphic', 'levityPolymorphic', 'abstractSumIdentity', 'abstractRuntimeSum'):
            with self.subTest(name=name):
                self.assertIsNone(check.EXPECTED[name]['alternativeSlots'])
                check.validate_layout(check.EXPECTED[name])
        self.assertIsNone(alternative_slots([dict(primReps=['BoxedRep Nothing'])], ['WordRep', check.LIFTED]))
        # A child's exact physical projection can be known without its logical decomposition.
        record = check.EXPECTED['abstractAlternative']
        self.assertIsNone(record['alternatives'][0]['components'])
        self.assertEqual([[1], [1]], record['alternativeSlots'])

    def test_state_empty_and_boxed_unit_remain_distinct(self):
        state, empty = check.EXPECTED['zeroSum']['alternatives']
        self.assertEqual('void', state['kind'])
        self.assertNotIn('aggregate', state)
        self.assertEqual([], empty['components'])
        boxed = check.EXPECTED['unitSum']['alternatives'][0]
        self.assertEqual([check.LIFTED], boxed['primReps'])
        self.assertFalse(boxed['evaluated'])
        self.assertNotIn('aggregate', boxed)

    def test_forged_tag_or_projection_is_rejected(self):
        mutations = [('tagSlot', 1), ('tagSlot', False), ('alternativeSlots', None),
            ('alternativeSlots', [[1, 1], [1, 2, 3]]), ('alternativeSlots', [[2, 1], [1, 2, 3]]),
            ('alternativeSlots', [[1, 2], [1, 2, 4]]), ('alternativeSlots', [[True, 2], [1, 2, 3]])]
        for field, value in mutations:
            with self.subTest(field=field, value=value):
                record = copy.deepcopy(check.EXPECTED['nestedSum']); record[field] = value
                with self.assertRaises(AssertionError): check.validate_layout(record)
        record = copy.deepcopy(check.EXPECTED['returnedSum']); del record['alternativeSlots']
        with self.assertRaises(AssertionError): check.validate_layout(record)
        record = copy.deepcopy(check.EXPECTED['returnedSum']); record['primReps'][0] = 'IntRep'
        with self.assertRaises(ValueError): check.validate_layout(record)

    def test_native_model_wrap_and_narrow_boundaries(self):
        self.assertEqual((1 << 63)-3, check.model('sumCase', -(1 << 63)))
        self.assertEqual(-(1 << 63)+7, check.model('sumCase', (1 << 63)-1))
        self.assertEqual(2147483647, check.model('narrowWideCase', -2147483649))
        self.assertEqual(-2147483648, check.model('narrowWideCase', -2147483648))
        self.assertEqual(2, check.model('nestedCase', -(1 << 63)))


if __name__ == '__main__': unittest.main()
