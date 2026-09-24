#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

"""Small independent sanity checks; native GHC comparison belongs to preparation."""
import unittest

from sequence_model import (
    ENTRIES, _drain, _from_ends, _index_update_score, _measure, _signed, _split,
    _update, _view_measure, sequence_model,
)


class SequenceModelTest(unittest.TestCase):
    def test_empty_inputs_keep_repeated_misses_and_the_guarded_index_default(self):
        expected = dict(sequenceBuild=0, sequenceEnds=0, sequenceAppend=0,
                        sequenceSplit=0, sequenceIndexUpdate=-7902823,
                        sequenceAggregate=-102736699, sequenceLazyPayloads=4,
                        sequenceBuildViews=0, sequenceDequeViews=0,
                        sequenceAppendViews=0, sequenceLazyLength=3)
        self.assertEqual(set(ENTRIES), set(expected))
        for entry, result in expected.items():
            for value in (0, -1, -(1 << 63)):
                with self.subTest(entry=entry, value=value):
                    self.assertEqual(sequence_model(entry, value), result)

    def test_singleton_payload_and_both_end_drains(self):
        # payload(0)=-1013, whose masked contribution is 64524.
        self.assertEqual(sequence_model('sequenceBuild', 1), 258103)
        self.assertEqual(sequence_model('sequenceEnds', 1), 1290487)
        # Singleton >< empty and empty >< singleton each score 258103,
        # with weights 1 and 3; the second orientation must not be omitted.
        self.assertEqual(sequence_model('sequenceAppend', 1), 4 * 258103)
        self.assertEqual(sequence_model('sequenceIndexUpdate', 1), -19120800)
        self.assertEqual(sequence_model('sequenceLazyPayloads', 1), 6)
        self.assertEqual(sequence_model('sequenceBuildViews', 1), 258103)
        self.assertEqual(sequence_model('sequenceDequeViews', 1), 258103)
        self.assertEqual(sequence_model('sequenceAppendViews', 1), 4 * 258103)
        self.assertEqual(sequence_model('sequenceLazyLength', 1), 4)

    def test_both_append_orientations_have_distinct_order_sensitive_scores(self):
        # At size 2, left=[a,b], right=[a]; the concatenations are [a,b,a]
        # and [a,a,b]. Expand each checksum polynomial without calling a model
        # helper. p/q are the masked payload contributions for a/b.
        p, q = 64524, 64561
        forward_first = 1960 * p + 84 * q + 21
        forward_second = 1176 * p + 868 * q + 21
        views_first = 1520 * p + 4 * q + 21
        views_second = 422 * p + 1102 * q + 21
        self.assertNotEqual(forward_first, forward_second)
        self.assertNotEqual(views_first, views_second)
        self.assertEqual(sequence_model('sequenceAppend', 2), forward_first + 3 * forward_second)
        self.assertEqual(sequence_model('sequenceAppendViews', 2), views_first + 3 * views_second)

    def test_insertion_fold_and_drain_directions_are_observable(self):
        self.assertEqual(_from_ends(5), [-865, -939, -1013, -976, -902])
        self.assertEqual(_measure([1, 2, 3]), 5929)
        self.assertEqual(_measure([3, 2, 1]), 6377)
        self.assertEqual(_drain([1, 2, 3, 4], True), 15584)
        self.assertEqual(_drain([1, 2, 3, 4], False), 35096)
        self.assertEqual(_view_measure([1, 2, 3, 4]), 120900)

    def test_split_and_update_clamp_differently_without_negative_indexing(self):
        items = [10, 20, 30]
        for position in (-2, 0):
            self.assertEqual(_split(items, position), ([], items))
        self.assertEqual(_split(items, 1), ([10], [20, 30]))
        for position in (3, 5):
            self.assertEqual(_split(items, position), (items, []))
        for position in (-1, 3, 4):
            self.assertEqual(_update(items, position, 99999), items)
        self.assertEqual(_update(items, 1, 99), [10, 99, 30])
        self.assertEqual(items, [10, 20, 30])
        # All six lookup positions are misses for empty input, not one miss.
        self.assertEqual(_index_update_score([]), 13 * (-31 * 19608) + 17 * -47)

    def test_sizes_are_bounded_and_results_use_machine_int_wrap(self):
        for entry in ENTRIES:
            for value in (1025, (1 << 63) - 1):
                with self.subTest(entry=entry, value=value):
                    self.assertEqual(sequence_model(entry, value), sequence_model(entry, 1024))
        self.assertEqual(_signed(1 << 63), -(1 << 63))
        self.assertEqual(_signed((1 << 64) - 1), -1)
        self.assertEqual(_signed(-(1 << 63) - 1), (1 << 63) - 1)
        with self.assertRaises(ValueError):
            sequence_model('notAnEntry', 0)


if __name__ == '__main__':
    unittest.main()
