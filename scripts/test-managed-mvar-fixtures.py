#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
"""Model/contract checks; synthetic test JSON is never supplied as guest Core."""

import importlib.util
import io
import json
from pathlib import Path
from contextlib import redirect_stdout
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('prepare_mvars', Path(__file__).with_name('prepare-managed-mvars.py'))
recipe = importlib.util.module_from_spec(spec)
spec.loader.exec_module(recipe)


def application(name, lifted):
    def proof(role):
        if role == 'state':
            return dict(kind='void', primReps=[], evaluated=True)
        if role == 'flag':
            return dict(kind='long', primReps=['IntRep'], evaluated=True)
        return dict(kind='object' if role == 'mvar' else 'data',
                    primReps=['BoxedRep (Just ' + ('Lifted' if role == 'boxed' and lifted else 'Unlifted') + ')'],
                    evaluated=not (role == 'boxed' and lifted))
    arguments, result = recipe.CONTRACTS[name]
    args = [['var', str(index), dict(rep=proof(role))] for index, role in enumerate(arguments)]
    if isinstance(result, list):
        fields = [proof(role) for role in result]
        returned = dict(kind='unknown', aggregate='unboxed-tuple', components=fields,
                        primReps=[rep for field in fields for rep in field['primReps']], evaluated=True)
    else:
        returned = proof(result)
    return ['app', ['prim', name], args, [role == 'boxed' and lifted for role in arguments], False, False, dict(rep=returned)]


class ManagedMVarFixtureTests(unittest.TestCase):
    def test_exact_contracts_at_both_boxed_levities(self):
        for name in recipe.CONTRACTS:
            for lifted in (False, True):
                with self.subTest(name=name, lifted=lifted):
                    self.assertEqual(recipe.validate_application(application(name, lifted))[0], name)

    def test_state_is_not_an_empty_tuple_and_flags_are_full_width(self):
        app = application('tryReadMVar#', True)
        app[2][-1][-1]['rep'].update(aggregate='unboxed-tuple', components=[])
        with self.assertRaisesRegex(ValueError, 'state argument'):
            recipe.validate_application(app)
        app = application('tryReadMVar#', True)
        app[-1]['rep']['components'][1]['primReps'] = ['Int32Rep']
        app[-1]['rep']['primReps'][0] = 'Int32Rep'
        with self.assertRaisesRegex(ValueError, 'flag result'):
            recipe.validate_application(app)
        app = application('takeMVar#', True)
        app[-1]['rep']['components'].pop(0)
        with self.assertRaisesRegex(ValueError, 'logical State#'):
            recipe.validate_application(app)

    def test_arity_boxed_payload_and_lifted_argument_flags(self):
        for change in ('arity', 'boxed', 'lifted'):
            app = application('putMVar#', True)
            if change == 'arity':
                app[2].pop()
            elif change == 'boxed':
                app[2][1][-1]['rep'] = dict(kind='long', primReps=['IntRep'])
            else:
                app[3][1] = False
            with self.subTest(change=change), self.assertRaises(ValueError):
                recipe.validate_application(app)

    def test_signed_model_boundaries_and_independent_cells(self):
        self.assertEqual(recipe.mathematical('lazyPayload', 2**63 - 1), -2**63 + 29)
        self.assertEqual(recipe.mathematical('closurePayload', -2**63), 17)
        self.assertEqual(recipe.mathematical('aliasRoundTrip', -1), -65565)
        self.assertEqual(recipe.mathematical('aliasRoundTrip', 0), 65537)
        self.assertEqual(recipe.mathematical('transitions', 0), 17 * 16777259 + 96)
        for value in (-2**63, -1, 0, 1, 2**63 - 1):
            self.assertEqual(recipe.mathematical('transitions', value), recipe.mathematical('unliftedPayload', value))
            self.assertEqual(recipe.mathematical('nativeWaitTake', value), value)
            self.assertEqual(recipe.mathematical('nativeWaitRead', value), value)

    def test_native_protocol_rejects_missing_duplicate_and_wrong_rows(self):
        self.assertEqual(recipe.validate_rows('lazyPayload\t0\t30\n', ['lazyPayload'], [0]), [['lazyPayload', '0', '30']])
        for text in ('', 'lazyPayload\t0\t31\n', 'lazyPayload\t1\t31\n', 'lazyPayload\t0\t30\nlazyPayload\t0\t30\n'):
            with self.subTest(text=text), self.assertRaises(ValueError):
                recipe.validate_rows(text, ['lazyPayload'], [0])


class PreparationReuseTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        # macOS exposes /var through /private/var; match the recipe's resolved
        # containment checks while keeping every synthetic artifact in this temp tree.
        self.root = Path(temporary.name).resolve()
        self.build = self.root / 'build/fixture'

    def prepared(self):
        # Synthetic unit-test artifacts only; no compiler, evaluator, or guest
        # export is invoked. Actual GHC preparation is verified separately.
        for name in recipe.source_inputs(self.root):
            path = self.root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text('unit-test source\n')
        stages, artifacts = recipe.expected_artifacts(self.build, self.root)
        for name in artifacts:
            path = self.root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(b'unit-test artifact\n')
        values, concurrent_values = recipe.input_vectors()
        rows = lambda names, inputs: ''.join(f'{name}\t{value}\t{recipe.mathematical(name, value)}\n'
                                             for name in names for value in inputs)
        (self.build / 'oracle.tsv').write_text(rows(recipe.ENTRIES, values))
        (self.build / 'context-oracle.tsv').write_text(rows(recipe.READY_ENTRIES, values))
        (self.build / 'logs/native-ready.stdout').write_text(rows(recipe.ENTRIES + recipe.READY_ENTRIES, values))
        for label in recipe.COMMAND_LABELS:
            (self.build / ('logs/' + label + '.command.json')).write_text(json.dumps({'exit': 0}))
        (self.build / 'logs/native-word-bits.stdout').write_text('64\n')
        (self.build / 'logs/ghc-version.stdout').write_text('9.14.1\n')
        concurrent = {}
        for count in ('1', '2'):
            (self.build / ('logs/native-concurrent-N' + count + '.stdout')).write_text(rows(recipe.CONCURRENT_ENTRIES, concurrent_values))
            concurrent[count] = {'rows': len(recipe.CONCURRENT_ENTRIES) * len(concurrent_values),
                                 'entries': recipe.CONCURRENT_ENTRIES, 'inputs': concurrent_values}
        statuses, reachable = {}, {}
        for stage in stages:
            for name in recipe.ENTRIES + recipe.CONTEXT_ENTRIES:
                report = {'roots': ['main:ManagedMVarAudit.' + name], 'accepted': True, 'issues': [], 'missingGlobals': [],
                          'primitives': [{'name': primitive} for primitive in sorted(recipe.REQUIRED[name])], 'reachableBindings': []}
                (self.build / stage / (name + '.audit.json')).write_text(json.dumps(report))
                statuses[stage + '/' + name] = 'accepted'
                reachable[stage + '/' + name] = []
        manifest = {'schema': 1, 'recipeVersion': 2, 'ghc': '9.14.1', 'entries': recipe.ENTRIES, 'entryNames': recipe.ENTRIES,
                    'contextEntryNames': recipe.CONTEXT_ENTRIES, 'stages': stages, 'inputs': values,
                    'contextEntries': {stage: {name: {} for name in recipe.CONTEXT_ENTRIES} for stage in stages},
                    'inputHashes': recipe.hash_files(recipe.source_inputs(self.root), self.root),
                    'artifactHashes': recipe.hash_files(artifacts, self.root),
                    'nativeRows': len(recipe.ENTRIES) * len(values), 'nativeContextRows': len(recipe.READY_ENTRIES) * len(values),
                    'nativeConcurrent': concurrent, 'auditStatus': statuses, 'reachableBindings': reachable,
                    'installedArtifactsHashed': False}
        self.save(manifest)
        return manifest

    def save(self, manifest):
        (self.build / 'manifest.json').write_text(json.dumps(manifest))

    def test_complete_check_is_read_only_and_rechecks_all_row_families(self):
        manifest = self.prepared()
        before = {str(path): path.read_bytes() for path in self.root.rglob('*') if path.is_file()}
        self.assertEqual(recipe.check_prepared(self.build, self.root), manifest)
        after = {str(path): path.read_bytes() for path in self.root.rglob('*') if path.is_file()}
        self.assertEqual(before, after)

    def test_missing_preparation_is_not_created_or_repaired(self):
        with self.assertRaisesRegex(ValueError, 'Stale or incomplete'):
            recipe.check_prepared(self.build, self.root)
        self.assertFalse(self.build.exists())
        self.build.mkdir(parents=True)
        sentinel = self.build / 'partial-evidence.log'
        sentinel.write_text('preserve this failed attempt\n')
        with self.assertRaisesRegex(ValueError, 'Stale or incomplete'):
            recipe.check_prepared(self.build, self.root)
        self.assertEqual(sentinel.read_text(), 'preserve this failed attempt\n')
        self.assertFalse((self.build / 'manifest.json').exists())

    def test_stale_missing_hash_and_incomplete_inventory_are_rejected(self):
        for mutation in ('source', 'artifact', 'missing-file', 'missing-input-hash', 'missing-artifact-hash', 'old-version'):
            manifest = self.prepared()
            if mutation == 'source':
                (self.root / recipe.SOURCE).write_text('changed source')
            elif mutation == 'artifact':
                (self.build / 'oracle.tsv').write_text('changed oracle')
            elif mutation == 'missing-file':
                (self.build / 'post/waitTake.audit.json').unlink()
            elif mutation == 'missing-input-hash':
                manifest['inputHashes'].pop(recipe.SOURCE)
            elif mutation == 'missing-artifact-hash':
                manifest['artifactHashes'].pop(str((self.build / 'post/waitTake.audit.json').relative_to(self.root)))
            else:
                manifest.pop('recipeVersion')
            self.save(manifest)
            with self.subTest(mutation=mutation), self.assertRaisesRegex(ValueError, 'Stale or incomplete.*Outputs were not changed'):
                recipe.check_prepared(self.build, self.root)

    def test_row_counts_and_model_are_not_trusted_from_manifest_or_hash_alone(self):
        for mutation in ('guest-count', 'context-count', 'concurrent-count', 'model-value', 'shortened-inputs'):
            manifest = self.prepared()
            if mutation == 'guest-count':
                manifest['nativeRows'] -= 1
            elif mutation == 'context-count':
                manifest['nativeContextRows'] -= 1
            elif mutation == 'concurrent-count':
                manifest['nativeConcurrent']['2']['rows'] -= 1
            elif mutation == 'shortened-inputs':
                manifest['inputs'] = []
            else:
                path = self.build / 'oracle.tsv'
                lines = path.read_text().splitlines()
                fields = lines[0].split('\t')
                fields[-1] = str(int(fields[-1]) + 1)
                lines[0] = '\t'.join(fields)
                path.write_text('\n'.join(lines) + '\n')
                key = str(path.relative_to(self.root))
                manifest['artifactHashes'].update(recipe.hash_files([key], self.root))
            self.save(manifest)
            with self.subTest(mutation=mutation), self.assertRaisesRegex(ValueError, 'Stale or incomplete'):
                recipe.check_prepared(self.build, self.root)

    def test_context_audits_tolerate_only_pending_mvar_capabilities(self):
        report = {'accepted': False, 'missingGlobals': [], 'issues': [{'code': 'unsupported-primitive', 'detail': 'takeMVar#'}]}
        self.assertEqual(recipe.classify_audit(report, 'waitTake'), 'pending-managed-mvar-capabilities')
        report['issues'][0]['detail'] = 'fork#'
        with self.assertRaisesRegex(ValueError, 'unexpected audit rejection'):
            recipe.classify_audit(report, 'waitTake')

    def test_normal_reuse_and_check_only_do_not_run_commands(self):
        self.build.mkdir(parents=True)
        for options in ([], ['--check-only'], ['--refresh']):
            argv = ['prepare-managed-mvars.py', '--out', str(self.build), *options]
            with patch.object(recipe, 'ROOT', self.root), patch.object(recipe.sys, 'argv', argv), \
                    patch.object(recipe, 'check_prepared', return_value={'nativeRows': 845}) as check, \
                    patch.object(recipe.subprocess, 'run', side_effect=AssertionError('unexpected compiler/oracle call')), \
                    redirect_stdout(io.StringIO()):
                recipe.main()
                check.assert_called_once_with(self.build)

    def test_refresh_rebuilds_stale_generated_output(self):
        self.build.mkdir(parents=True)
        stale = self.build / 'old-manifest.json'
        stale.write_text('obsolete fixture')
        argv = ['prepare-managed-mvars.py', '--out', str(self.build), '--refresh']
        with patch.object(recipe, 'ROOT', self.root), patch.object(recipe.sys, 'argv', argv), \
                patch.object(recipe, 'check_prepared', side_effect=ValueError('stale')), \
                patch.object(recipe.subprocess, 'run', side_effect=RuntimeError('rebuild reached')) as run:
            with self.assertRaisesRegex(RuntimeError, 'rebuild reached'):
                recipe.main()
            run.assert_called_once()
        self.assertFalse(stale.exists())
        self.assertTrue(self.build.is_dir())

    def test_refresh_does_not_remove_output_outside_generated_build_tree(self):
        output = self.root / 'saved-evidence'
        output.mkdir()
        sentinel = output / 'keep.txt'
        sentinel.write_text('keep')
        argv = ['prepare-managed-mvars.py', '--out', str(output), '--refresh']
        with patch.object(recipe, 'ROOT', self.root), patch.object(recipe.sys, 'argv', argv), \
                patch.object(recipe, 'check_prepared', side_effect=ValueError('stale')):
            with self.assertRaisesRegex(ValueError, 'below build/'):
                recipe.main()
        self.assertEqual(sentinel.read_text(), 'keep')


if __name__ == '__main__':
    unittest.main()
