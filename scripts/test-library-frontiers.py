#!/usr/bin/env python3
"""Reject vanished or misclassified tuple frontiers without compiling fixtures."""
import importlib.util
from contextlib import redirect_stdout
import io
import json
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest


spec = importlib.util.spec_from_file_location(
    'prepare_library_tests', Path(__file__).with_name('prepare-library-tests.py'))
prepare = importlib.util.module_from_spec(spec)
spec.loader.exec_module(prepare)


class LibraryFrontierTest(unittest.TestCase):
    def check_existing_fixture(self, change=None):
        """Use the real auditor and valid export hashes, without native fixtures."""
        long_rep = dict(primReps=['IntRep'], kind='long', evaluated=True)
        closure_rep = dict(primReps=['BoxedRep (Just Lifted)'], kind='closure', evaluated=True)
        tuple_rep = dict(aggregate='unboxed-tuple', kind='unknown', evaluated=True,
                         components=[], primReps=[])
        supported = {'sequenceBuildViews', 'sequenceDequeViews', 'sequenceAppendViews', 'sequenceLazyLength'}
        bindings, entries = [], []
        for name in prepare.SEQUENCE_ENTRIES:
            expr = ['lit', 'int', '0']
            if name not in supported:
                expr = ['lam', [dict(id='token', lifted=False, rep=tuple_rep)], expr,
                        dict(rep=closure_rep, resultRep=long_rep)]
            bindings.append(dict(id=name, name=name, lifted=True, arity=int(name not in supported), expr=expr))
            entries.append(dict(name=name, execution='supported' if name in supported else 'frontier'))
        module = dict(schema=1, ghc='9.14.1', bindings=bindings, constructors=[])
        group = dict(id='sequence', execution='frontier', entries=entries)
        if change:
            change(module, group)
        with TemporaryDirectory() as directory:
            core = Path(directory) / 'core.json'
            core.write_text(json.dumps(module))
            group['modules'] = [str(core)]
            manifest = Path(directory) / 'cases.json'
            manifest.write_text(json.dumps(dict(groups=[group], artifactHashes={str(core): prepare.digest(core)})))
            with redirect_stdout(io.StringIO()):
                prepare.check_existing(manifest)

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

    def test_existing_checks_each_frontier_even_when_the_union_stays_rejected(self):
        self.check_existing_fixture()

        def replace_boundary(module, group):
            module['bindings'][0]['expr'] = ['prim', 'notARealPrimitive#']

        with self.assertRaisesRegex(RuntimeError, 'sequenceBuild: expected aggregate frontier changed'):
            self.check_existing_fixture(replace_boundary)

    def test_existing_rejects_changed_support_declarations(self):
        def change_declaration(module, group):
            next(entry for entry in group['entries'] if entry['name'] == 'sequenceBuildViews')['execution'] = 'frontier'

        with self.assertRaisesRegex(RuntimeError, 'sequenceBuildViews: expected supported declaration'):
            self.check_existing_fixture(change_declaration)

    def test_existing_requires_every_unique_sequence_entry(self):
        for duplicate in (False, True):
            def change_entries(module, group):
                if duplicate:
                    group['entries'].append(group['entries'][0])
                else:
                    group['entries'].pop()

            with self.subTest(duplicate=duplicate):
                with self.assertRaisesRegex(RuntimeError, 'expected every Sequence entry exactly once'):
                    self.check_existing_fixture(change_entries)

    def test_existing_rejects_missing_main_bodies_even_with_the_expected_boundary(self):
        def remove_body(module, group):
            module['bindings'][0]['expr'][2] = ['var', 'main:Missing.body']

        with self.assertRaisesRegex(RuntimeError, 'source-library definitions must resolve'):
            self.check_existing_fixture(remove_body)


if __name__ == '__main__':
    unittest.main()
