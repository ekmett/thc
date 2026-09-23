#!/usr/bin/env python3
"""Reject vanished or misclassified tuple frontiers without compiling fixtures."""
import importlib.util
from pathlib import Path
import unittest


spec = importlib.util.spec_from_file_location(
    'prepare_library_tests', Path(__file__).with_name('prepare-library-tests.py'))
prepare = importlib.util.module_from_spec(spec)
spec.loader.exec_module(prepare)


class LibraryFrontierTest(unittest.TestCase):
    @staticmethod
    def audit(code, detail):
        return {'issues': [{'code': code, 'detail': detail}]}

    def test_exact_remaining_boundaries_are_recognized(self):
        for detail in ('unboxed-tuple argument', 'unboxed-tuple formal argument'):
            with self.subTest(detail=detail):
                prepare.require_sequence_tuple_frontier(self.audit('aggregate-boundary', detail))

    def test_removing_the_boundary_fails_preparation_guard(self):
        for issues in ([], [{'code': 'unsupported-primitive', 'detail': 'readMutVar#'}]):
            with self.subTest(issues=issues):
                with self.assertRaisesRegex(RuntimeError, 'expected aggregate frontier changed'):
                    prepare.require_sequence_tuple_frontier({'issues': issues})

    def test_mutating_either_issue_field_fails_preparation_guard(self):
        for detail in ('unboxed-tuple argument', 'unboxed-tuple formal argument'):
            for code, changed_detail in [('aggregate-representation', detail),
                                         ('aggregate-boundary', 'unboxed-tuple host result'),
                                         ('aggregate-representation', 'unboxed-tuple')]:
                with self.subTest(code=code, detail=changed_detail):
                    with self.assertRaisesRegex(RuntimeError, 'expected aggregate frontier changed'):
                        prepare.require_sequence_tuple_frontier(self.audit(code, changed_detail))

    def test_join_result_boundary_cannot_substitute_for_argument_frontier(self):
        with self.assertRaisesRegex(RuntimeError, 'expected aggregate frontier changed'):
            prepare.require_sequence_tuple_frontier(self.audit('aggregate-boundary', 'unboxed-tuple join result'))


if __name__ == '__main__':
    unittest.main()
