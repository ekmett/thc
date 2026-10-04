#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

"""Independent projection algorithm controls, not exporter or runtime evidence."""
import unittest
from sum_layout_model import alternative_slots


class SumLayoutTest(unittest.TestCase):
    def test_compatible_narrow_and_wide_alternatives_share_payload_slot(self):
        self.assertEqual([[1], [1]], alternative_slots(
            [dict(primReps=['Int32Rep']), dict(primReps=['Word64Rep'])], ['WordRep', 'Word64Rep']))

    def test_projection_preserves_source_order_and_repeated_fields(self):
        alternatives = [dict(primReps=['DoubleRep', 'IntRep', 'IntRep']), dict(primReps=['IntRep'])]
        self.assertEqual([[3, 1, 2], [1]], alternative_slots(alternatives,
            ['WordRep', 'WordRep', 'WordRep', 'DoubleRep']))
        with self.assertRaises(ValueError):
            alternative_slots(alternatives, ['WordRep', 'WordRep', 'DoubleRep'])

    def test_distinct_pointer_levities_float_widths_and_vector_shapes(self):
        for source, target in [('BoxedRep (Just Lifted)', 'BoxedRep (Just Unlifted)'), ('FloatRep', 'DoubleRep'),
                ('VecRep 4 Int32ElemRep', 'VecRep 2 Int64ElemRep'), ('Word64Rep', 'WordRep')]:
            with self.subTest(source=source), self.assertRaises(ValueError):
                alternative_slots([dict(primReps=[source])], ['WordRep', target])

    def test_unknown_physical_layout_stays_null_without_inventing_logical_shape(self):
        self.assertIsNone(alternative_slots(None, ['WordRep', 'WordRep']))
        self.assertIsNone(alternative_slots([dict(primReps=['IntRep'])], None))
        self.assertIsNone(alternative_slots([dict(primReps=None)], ['WordRep', 'WordRep']))
        self.assertIsNone(alternative_slots([dict(primReps=['BoxedRep Nothing'])], ['WordRep', 'BoxedRep (Just Lifted)']))
        # Physical projection need not imply a known logical tuple decomposition.
        self.assertEqual([[1], [1]], alternative_slots([
            dict(aggregate='unboxed-tuple', components=None, primReps=['IntRep']), dict(primReps=['IntRep'])], ['WordRep', 'WordRep']))

    def test_zero_width_alternatives_need_only_tag_storage(self):
        self.assertEqual([[], []], alternative_slots([dict(primReps=[]), dict(primReps=[])], ['WordRep']))
        with self.assertRaises(ValueError):
            alternative_slots([dict(primReps=[])], [])
        with self.assertRaises(ValueError):
            alternative_slots([dict(primReps=['IntRep'])], ['IntRep', 'WordRep'])


if __name__ == '__main__': unittest.main()
