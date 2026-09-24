#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
"""Fixture contract/model/provenance checks; synthetic JSON is never executed as guest Core."""

import copy
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('exception_recipe', Path(__file__).with_name('prepare-synchronous-exceptions.py'))
recipe = importlib.util.module_from_spec(spec)
spec.loader.exec_module(recipe)


def application(name):
    def proof(role):
        if role == 'state':
            return dict(kind='void', primReps=[], evaluated=True)
        return dict(kind='closure' if role == 'closure' else 'data',
                    primReps=['BoxedRep (Just Lifted)'], evaluated=False)
    roles = {'catch#': ['closure', 'closure', 'state'], 'raiseIO#': ['boxed', 'state'], 'raise#': ['boxed'],
             'maskAsyncExceptions#': ['closure', 'state'], 'maskUninterruptible#': ['closure', 'state'],
             'unmaskAsyncExceptions#': ['closure', 'state'], 'noDuplicate#': ['state']}[name]
    args = [['var', str(index), dict(rep=proof(role))] for index, role in enumerate(roles)]
    result = proof('boxed') if name == 'raise#' else proof('state') if name == 'noDuplicate#' else dict(
        kind='unknown', aggregate='unboxed-tuple', components=[proof('state'), proof('boxed')],
        primReps=['BoxedRep (Just Lifted)'], evaluated=False)
    return ['app', ['prim', name], args, [role != 'state' for role in roles], False, False, dict(rep=result)]


def audit_report(name):
    return dict(accepted=True, missingGlobals=[], reachableBindings=[],
                primitives=[{'name': primitive} for primitive in sorted(recipe.REQUIRED[name])],
                issues=[])


class ContractAndModelTests(unittest.TestCase):
    def test_observed_lifted_contracts(self):
        for name in ('catch#', 'raiseIO#', 'raise#', 'maskAsyncExceptions#',
                     'maskUninterruptible#', 'unmaskAsyncExceptions#', 'noDuplicate#'):
            with self.subTest(name=name):
                self.assertEqual(recipe.validate_contract(application(name))['primitive'], name)

    def test_state_argument_is_not_an_empty_tuple(self):
        for name in ('catch#', 'raiseIO#'):
            app = application(name)
            app[2][-1][-1]['rep'].update(aggregate='unboxed-tuple', components=[])
            with self.subTest(name=name), self.assertRaisesRegex(ValueError, 'state argument'):
                recipe.validate_contract(app)

    def test_catch_requires_closure_proofs_for_action_and_handler(self):
        for index in (0, 1):
            app = application('catch#')
            app[2][index][-1]['rep']['kind'] = 'data'
            with self.subTest(index=index), self.assertRaisesRegex(ValueError, 'closure argument'):
                recipe.validate_contract(app)

    def test_payload_and_result_do_not_admit_unknown_or_other_representations(self):
        for bad in (None, {}, dict(kind='unknown'), dict(kind='long', primReps=['IntRep']),
                    dict(kind='data', primReps=['BoxedRep Nothing']),
                    dict(kind='data', primReps=['BoxedRep (Just Unlifted)']),
                    dict(kind='data', primReps=['BoxedRep (Just Lifted)'], aggregate='unboxed-tuple')):
            for location in ('argument', 'result'):
                app = application('raise#')
                target = app[2][0][-1] if location == 'argument' else app[-1]
                target['rep'] = copy.deepcopy(bad)
                with self.subTest(bad=bad, location=location), self.assertRaises(ValueError):
                    recipe.validate_contract(app)

    def test_exact_logical_state_and_boxed_result_components(self):
        for name in ('catch#', 'raiseIO#'):
            for mutation in ('missing-state', 'extra-field', 'wrong-flat', 'wrong-state', 'wrong-boxed', 'no-aggregate'):
                app = application(name)
                result = app[-1]['rep']
                if mutation == 'missing-state':
                    result['components'].pop(0)
                elif mutation == 'extra-field':
                    result['components'].append(copy.deepcopy(result['components'][-1]))
                elif mutation == 'wrong-flat':
                    result['primReps'] = []
                elif mutation == 'wrong-state':
                    result['components'][0]['primReps'] = ['VoidRep']
                elif mutation == 'wrong-boxed':
                    result['components'][1]['primReps'] = ['IntRep']
                else:
                    result.pop('aggregate')
                with self.subTest(name=name, mutation=mutation), self.assertRaises(ValueError):
                    recipe.validate_contract(app)

    def test_flags_are_boolean_and_match_arity_and_levity(self):
        for name in ('catch#', 'raiseIO#', 'raise#'):
            for bad in (None, [], [True] * 5):
                app = application(name)
                app[3] = bad
                with self.subTest(name=name, bad=bad), self.assertRaises(ValueError):
                    recipe.validate_contract(app)
            for index in range(len(application(name)[3])):
                for bad in (None, 0, 1, 'true', not application(name)[3][index]):
                    app = application(name)
                    app[3][index] = bad
                    with self.subTest(name=name, index=index, bad=bad), self.assertRaises(ValueError):
                        recipe.validate_contract(app)

    def test_signed_models_cover_overflow_and_mask_state(self):
        self.assertEqual(recipe.mathematical('preciseCatch', 2**63 - 1), -2**63 + 16)
        self.assertEqual(recipe.mathematical('restoreAndRethrow', -2**63), 41)
        self.assertEqual(recipe.mathematical('restoreAndRethrow', 2**63 - 1), -217)
        expected = [17, 19, 23, 87, 31, 37, 41, 34, 212, 7, 5]
        self.assertEqual([recipe.mathematical(name, 0) for name in recipe.ENTRIES], expected)
        self.assertTrue(all(recipe.mathematical('handlerMaskState', x) == 34 for x in recipe.input_vectors()))
        self.assertEqual(len(recipe.input_vectors()), 169)
        with self.assertRaisesRegex(ValueError, 'Unknown oracle'):
            recipe.mathematical('unknown', 0)

    def test_native_rows_require_every_entry_input_once_and_correct_results(self):
        valid = '\n'.join(f'{name}\t0\t{recipe.mathematical(name, 0)}' for name in recipe.ENTRIES) + '\n'
        self.assertEqual(len(recipe.validate_rows(valid, [0])), len(recipe.ENTRIES))
        rows = valid.splitlines()
        for bad in ('', valid + rows[0] + '\n', valid.replace('preciseCatch\t0\t17', 'preciseCatch\t0\t18'),
                    valid.replace('preciseCatch\t0\t17', 'preciseCatch\t1\t18'),
                    '\n'.join([rows[1]] + rows[1:]), valid.replace('\t0\t17', '\t17')):
            with self.subTest(bad=bad), self.assertRaises(ValueError):
                recipe.validate_rows(bad, [0])

    def test_synchronous_and_masking_state_audit_is_accepted(self):
        for name in recipe.ENTRIES:
            self.assertTrue(recipe.classify_audit(audit_report(name), name)['accepted'])
        for mutation in ('unknown-primitive', 'missing-global', 'missing-required', 'other-issue', 'false-acceptance'):
            report = audit_report('handlerMaskState')
            if mutation == 'unknown-primitive':
                report['issues'] = [dict(code='unsupported-primitive', detail='fork#')]
            elif mutation == 'missing-global':
                report['missingGlobals'] = ['absent']
            elif mutation == 'missing-required':
                report['primitives'] = [{'name': 'catch#'}]
            elif mutation == 'other-issue':
                report['issues'] = [dict(code='primitive-representation', detail='bad')]
            else:
                report['accepted'] = False
            with self.subTest(mutation=mutation), self.assertRaises(ValueError):
                recipe.classify_audit(report, 'handlerMaskState')


class ProvenanceTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name).resolve()
        self.build = self.root / 'build/fixture'

    def write(self, path, text):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text)

    def prepared(self):
        # Deliberately synthetic provenance only, never compiler/runtime input.
        for name in recipe.source_inputs(self.root):
            self.write(self.root / name, 'synthetic source\n')
        artifacts = set()
        def artifact(path, text):
            self.write(path, text)
            artifacts.add(str(path.relative_to(self.root)))
        for label in recipe.COMMAND_LABELS:
            artifact(self.build / 'logs' / (label + '.command.json'), json.dumps({'exit': 0}))
            artifact(self.build / 'logs' / (label + '.stdout'), '')
            artifact(self.build / 'logs' / (label + '.stderr'), '')
        artifact(self.build / 'logs/ghc-version.stdout', '9.14.1\n')
        artifact(self.build / 'logs/native-word-bits.stdout', '64\n')
        output = ''.join(f'{name}\t{x}\t{recipe.mathematical(name, x)}\n' for name in recipe.ENTRIES for x in recipe.input_vectors())
        artifact(self.build / 'oracle.tsv', output)
        artifact(self.build / 'logs/native-oracle.stdout', output)
        artifact(self.build / 'contracts.json', '{}\n')
        artifact(self.build / 'native/synchronous-exception-oracle', 'synthetic executable placeholder\n')
        stages, statuses = {}, {}
        for stage in ('pre', 'post'):
            path = self.build / stage / 'core/module.json'
            artifact(path, '{}\n')
            stages[stage] = [str(path.relative_to(self.root))]
            for name in recipe.ENTRIES:
                report = audit_report(name)
                artifact(self.build / stage / (name + '.audit.json'), json.dumps(report))
                statuses[stage + '/' + name] = recipe.classify_audit(report, name)
        plugin = dict(schema=1, unitId='synthetic-test-unit', packageDb=str(self.root / 'dist-newstyle/packagedb'),
                      sharedLibrary=str(self.root / 'build/compiler/plugin.so'),
                      cabalSharedLibrary=str(self.root / 'dist-newstyle/build/plugin.so'))
        for field in ('sharedLibrary', 'cabalSharedLibrary'):
            artifact(Path(plugin[field]), 'synthetic shared-library placeholder\n')
        artifact(self.root / 'build/compiler/plugin.json', json.dumps(plugin))
        manifest = dict(schema=1, recipeVersion=1, ghc='9.14.1', wordBits=64, entries=recipe.ENTRIES,
                        inputs=recipe.input_vectors(), installedArtifactsHashed=False,
                        inputHashes=recipe.hash_files(recipe.source_inputs(self.root), self.root),
                        artifactHashes=recipe.hash_files(artifacts, self.root), stages=stages,
                        auditStatus=statuses, plugin=plugin, nativeRows=len(recipe.ENTRIES) * len(recipe.input_vectors()))
        self.save(manifest)
        return manifest

    def save(self, manifest):
        self.write(self.build / 'manifest.json', json.dumps(manifest))

    def test_check_is_read_only_and_executes_no_tools(self):
        manifest = self.prepared()
        before = {str(path): path.read_bytes() for path in self.root.rglob('*') if path.is_file()}
        with patch.object(recipe.subprocess, 'run', side_effect=AssertionError('Unexpected execution')):
            self.assertEqual(recipe.check_prepared(self.build, self.root), manifest)
        self.assertEqual(before, {str(path): path.read_bytes() for path in self.root.rglob('*') if path.is_file()})

    def test_missing_preparation_is_not_created(self):
        with self.assertRaises(FileNotFoundError):
            recipe.check_prepared(self.build, self.root)
        self.assertFalse(self.build.exists())

    def test_incomplete_or_stale_inventory_is_rejected(self):
        for mutation in ('source', 'artifact', 'source-hash', 'artifact-hash', 'input-count', 'row-count', 'word-size'):
            manifest = self.prepared()
            if mutation == 'source':
                self.write(self.root / recipe.SOURCE, 'changed source')
            elif mutation == 'artifact':
                self.write(self.build / 'contracts.json', 'changed artifact')
            elif mutation == 'source-hash':
                manifest['inputHashes'].pop(recipe.SOURCE)
            elif mutation == 'artifact-hash':
                manifest['artifactHashes'].pop(str((self.build / 'contracts.json').relative_to(self.root)))
            elif mutation == 'input-count':
                manifest['inputs'].pop()
            elif mutation == 'row-count':
                manifest['nativeRows'] -= 1
            else:
                manifest['wordBits'] = 32
            self.save(manifest)
            with self.subTest(mutation=mutation), self.assertRaises(ValueError):
                recipe.check_prepared(self.build, self.root)

    def test_rehashed_incorrect_oracle_is_still_rejected(self):
        manifest = self.prepared()
        rows = (self.build / 'oracle.tsv').read_text().splitlines()
        fields = rows[0].split('\t')
        fields[2] = str(int(fields[2]) + 1)
        rows[0] = '\t'.join(fields)
        for name in ('oracle.tsv', 'logs/native-oracle.stdout'):
            path = self.build / name
            self.write(path, '\n'.join(rows) + '\n')
            manifest['artifactHashes'].update(recipe.hash_files([str(path.relative_to(self.root))], self.root))
        self.save(manifest)
        with self.assertRaisesRegex(ValueError, 'Native/model mismatch'):
            recipe.check_prepared(self.build, self.root)

    def test_hashing_rejects_paths_outside_checkout(self):
        for path in ('../not-owned', '/etc/passwd'):
            with self.subTest(path=path), self.assertRaisesRegex(ValueError, 'outside checkout'):
                recipe.hash_files([path], self.root)


if __name__ == '__main__':
    unittest.main()
