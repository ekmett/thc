# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

"""Fixture selection and persistent-stamp tests; no compiler or JVM is run."""

from contextlib import ExitStack, redirect_stderr
import hashlib
import io
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent))
import fast_fixtures


class FixturePreparationTest(unittest.TestCase):
    def test_libdw_unavailable_native_fixture_registration(self):
        project = Path(__file__).resolve().parents[2]
        manifest, owners = fast_fixtures._manifest(project)
        group = manifest['groups']['libdw-unavailable']
        self.assertEqual('libdw-unavailable', owners['thc.runtime.LibdwUnavailableTest'])
        self.assertEqual([{'argv': ['cabal', 'run', 'exe:thc-fixtures', '--offline', '--',
                                   'libdw-unavailable']}], group['commands'])
        self.assertEqual(['build/libdw-unavailable/manifest.json', 'build/libdw-unavailable/oracle.json',
                          'build/libdw-unavailable/foreign-labels.json'], group['outputs'])
        self.assertTrue(all((project / path).is_file() for path in group['sources']))
        self.assertIn('"$fixture_bin" libdw-unavailable', (project / 'scripts/prepare-tests.sh').read_text())
        self.assertEqual(fast_fixtures.FULL_PREPARATION_PLAN, fast_fixtures._preparation_plan(project))
        self.assertIn('build/libdw-unavailable/manifest.json', fast_fixtures.FULL_REQUIRED)
        for suffix in ('manifest.json', 'oracle.json', 'foreign-labels.json'):
            self.assertTrue(fast_fixtures.fast_inputs.allowed_payload('build/libdw-unavailable/' + suffix, {}))
            self.assertIn('"libdw-unavailable/' + suffix + '"', (project / 'build.gradle.kts').read_text())
        self.assertFalse(fast_fixtures.fast_inputs.allowed_payload('build/libdw-unavailable/native/oracle', {}))
        self.assertIn('build/libdw-unavailable/logs/', (project / '.github/workflows/build.yml').read_text())

    def test_original_gmp_registration_platform_and_exact_cache(self):
        project = Path(__file__).resolve().parents[2]
        manifest, owners = fast_fixtures._manifest(project)
        group = manifest['groups']['original-gmp']
        self.assertEqual('original-gmp', owners['thc.runtime.OriginalGmpTest'])
        for name in ('CoreGmpForeignTest', 'SulongLimbProviderTest'):
            self.assertIsNone(owners['thc.runtime.' + name])
        self.assertEqual([{'argv': ['cabal', 'run', 'exe:thc-fixtures', '--offline', '--',
                                   'original-gmp', '--require-supported']}], group['commands'])
        self.assertTrue(all((project / path).is_file() for path in group['sources']))
        script = (project / 'scripts/prepare-tests.sh').read_text()
        self.assertIn('case "$(uname -s)-$(uname -m)" in\n'
                      '  Linux-x86_64) "$fixture_bin" original-gmp --require-supported ;;\nesac', script)
        self.assertEqual(fast_fixtures.FULL_PREPARATION_PLAN, fast_fixtures._preparation_plan(project))
        self.assertIn('build/original-gmp', fast_fixtures.FULL_OUTPUT_ROOTS)
        self.assertEqual(fast_fixtures.fast_inputs.GMP_NATIVE_HOST,
                         'build/original-gmp/manifest.json' in fast_fixtures.FULL_REQUIRED)
        gradle = (project / 'build.gradle.kts').read_text()
        for name in ('**/*.json', 'native/oracle', 'exposed-ghc-internal.conf', 'logs/*.stdout', 'logs/*.stderr'):
            self.assertIn('"original-gmp/' + name + '"', gradle)
        self.assertIn('build/original-gmp/', (project / '.github/workflows/build.yml').read_text())
        for workflow in ('build.yml', 'fast.yml'):
            self.assertIn('install --yes clang libgmp-dev', (project / '.github/workflows' / workflow).read_text())
        policy = json.loads((project / '.github/scripts/fast-tests.json').read_text())
        for source in ('compiler/test-fixtures/OriginalGmpAudit.hs',
                       'compiler/test-fixtures/OriginalGmpNative.hs',
                       'test/haskell-fixtures/OriginalGmpFixtures.hs', 'test/haskell-fixtures/Main.hs'):
            self.assertIn('thc.runtime.OriginalGmpTest', policy['owners'][source]['junit'])

    def gmp_preparation(self):
        project = Path(__file__).resolve().parents[2]
        group = fast_fixtures._manifest(project)[0]['groups']['original-gmp']
        self.manifest['groups']['original-gmp'] = group
        (self.root / fast_fixtures.MANIFEST).write_text(json.dumps(self.manifest))
        for name in group['sources']:
            path = self.root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes((project / name).read_bytes())
        def run(name, argv, stdout=None):
            self.fake_run(name, argv, stdout)
            if argv != group['commands'][0]['argv']:
                return
            artifacts = {}
            for name in fast_fixtures.fast_inputs.ORIGINAL_GMP_OUTPUTS - {'build/original-gmp/manifest.json'}:
                path = self.root / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(b'{}\n' if name.endswith('.json') else b'\x00\x80\xff\n')
                artifacts[name] = fast_fixtures._digest(path)
            (self.root / 'build/original-gmp/native/oracle').chmod(0o755)
            (self.root / 'build/original-gmp/manifest.json').write_text(json.dumps(
                {'strictAccepted': True, 'artifactHashes': artifacts}))
            database = self.root / 'build/original-gmp/package-db-0'
            database.mkdir(exist_ok=True)
            link = database / 'installed-interface.hi'
            if not link.is_symlink():
                link.symlink_to(self.root / 'fixtures/alpha.hs')
        def prepare():
            with mock.patch.object(fast_fixtures.fast_inputs, 'GMP_NATIVE_HOST', True):
                return fast_fixtures.prepare(self.root, self.selection(*group['junit']), run, self.toolchain)
        return group, prepare

    def test_gmp_selected_reuse_and_full_receipt_exclude_package_database(self):
        group, prepare = self.gmp_preparation()
        self.assertEqual(['original-gmp'], prepare()['rebuilt'])
        self.calls.clear()
        self.assertEqual(['original-gmp'], prepare()['reused'])
        self.assertEqual([], self.calls)
        expected = fast_fixtures.fast_inputs.ORIGINAL_GMP_OUTPUTS
        self.assertEqual(expected, fast_fixtures._output_hashes(self.root, group).keys())
        with mock.patch.object(fast_fixtures, 'FULL_OUTPUT_ROOTS', {'build/original-gmp'}), \
                mock.patch.object(fast_fixtures, 'FULL_REQUIRED', {'build/original-gmp/manifest.json'}), \
                mock.patch.object(fast_fixtures.fast_inputs, 'GMP_NATIVE_HOST', True):
            full = fast_fixtures._full_output_hashes(self.root)
            self.assertEqual(expected, full.keys())
            self.assertEqual(0o755, full['build/original-gmp/native/oracle']['mode'])
        for source in group['sources']:
            with (self.root / source).open('a') as stream:
                stream.write('\n-- changed source\n')
            self.assertEqual(['original-gmp'], prepare()['rebuilt'], source)

    def test_gmp_selected_receipt_rejects_stale_missing_linked_and_unreviewed_artifacts(self):
        group, prepare = self.gmp_preparation()
        for change in ('bytes', 'missing', 'symlink', 'unknown', 'rejected'):
            prepare()
            path = self.root / 'build/original-gmp/logs/native-observations.stdout'
            manifest_path = self.root / 'build/original-gmp/manifest.json'
            if change == 'bytes':
                path.write_bytes(b'changed')
            elif change == 'missing':
                path.unlink()
            elif change == 'symlink':
                path.unlink()
                path.symlink_to(self.root / 'fixtures/alpha.hs')
            else:
                manifest = json.loads(manifest_path.read_text())
                if change == 'unknown':
                    manifest['artifactHashes']['build/original-gmp/package-db-0/package.cache'] = '0' * 64
                else:
                    manifest['strictAccepted'] = False
                manifest_path.write_text(json.dumps(manifest))
            with self.assertRaises((RuntimeError, FileNotFoundError), msg=change):
                fast_fixtures._output_hashes(self.root, group)
            if path.is_symlink():
                path.unlink()

    def test_gmp_other_platforms_do_not_invoke_native_preparation(self):
        group, _ = self.gmp_preparation()
        with mock.patch.object(fast_fixtures.fast_inputs, 'GMP_NATIVE_HOST', False):
            result = fast_fixtures.prepare(self.root, self.selection(*group['junit']), self.fake_run, self.toolchain)
        self.assertEqual({'mode': 'selected', 'rebuilt': [], 'reused': []}, result)
        self.assertEqual([], self.calls)

    def test_original_posix_stat_fixture_registration_and_narrow_cache(self):
        project = Path(__file__).resolve().parents[2]
        manifest, owners = fast_fixtures._manifest(project)
        group = manifest['groups']['original-posix-stat']
        for name in ('OriginalPosixStatTest', 'PosixStatAbiTest', 'OriginalFstatTest'):
            self.assertEqual('original-posix-stat', owners['thc.runtime.' + name])
        self.assertEqual([{'argv': ['cabal', 'run', 'exe:thc-fixtures', '--offline', '--', 'original-posix-stat']}], group['commands'])
        self.assertIn('"$fixture_bin" original-posix-stat', (project / 'scripts/prepare-tests.sh').read_text())
        self.assertIn('build/original-posix-stat/manifest.json', fast_fixtures.FULL_REQUIRED)
        self.assertIn('build/original-posix-stat', fast_fixtures.FULL_OUTPUT_ROOTS)
        cache = fast_fixtures.fast_inputs
        self.assertEqual(90, len(cache.ORIGINAL_POSIX_STAT_OUTPUTS))
        for path in cache.ORIGINAL_POSIX_STAT_OUTPUTS:
            self.assertTrue(cache.allowed_payload(path, {}), path)
        for suffix in ('native/unknown', 'logs/unknown.stdout', 'pre/core/Other.json', 'attempt-0/oracle.json'):
            self.assertFalse(cache.allowed_payload('build/original-posix-stat/' + suffix, {}), suffix)
        for suffix in ('../original-stdio/manifest.json', 'native/../../outside'):
            with self.assertRaises(cache.CacheMiss):
                cache.allowed_payload('build/original-posix-stat/' + suffix, {})
        self.assertEqual(0o755, cache.safe_mode(0o755, 'build/original-posix-stat/native/oracle'))
        with self.assertRaises(cache.CacheMiss):
            cache.safe_mode(0o755, 'build/original-posix-stat/oracle.json')
        producer = (project / 'test/haskell-fixtures/OriginalPosixStatFixtures.hs').read_text()
        self.assertIn('os /= "linux"', producer)
        self.assertIn('"supported" .= False', producer)

    def test_simd_smoke_recipe_tracks_generated_haskell_inputs(self):
        project = Path(__file__).resolve().parents[2]
        manifest, owners = fast_fixtures._manifest(project)
        group = manifest['groups']['simd-capability-smoke']
        self.assertEqual('simd-capability-smoke', owners['thc.runtime.SimdCapabilitySmokeTest'])
        sources = fast_fixtures.fast_inputs.SIMD_SMOKE_SOURCES
        self.assertEqual({'build/simd-capability-smoke'} | sources, set(group['outputs']))
        self.assertLessEqual(sources, fast_fixtures.FULL_REQUIRED)
        self.assertNotIn('scripts/prepare-simd-families.py', group['sources'])
        self.assertNotIn('scripts/simd_family_model.py', group['sources'])

    def test_original_fd_ready_fixture_registration(self):
        project = Path(__file__).resolve().parents[2]
        manifest, owners = fast_fixtures._manifest(project)
        group = manifest['groups']['original-fd-ready']
        self.assertEqual('original-fd-ready', owners['thc.runtime.OriginalFdReadyNativeTest'])
        self.assertEqual([{'argv': ['cabal', 'run', 'exe:thc-fixtures', '--offline', '--',
                                   'original-fd-ready']}], group['commands'])
        self.assertEqual(['build/original-fd-ready'], group['outputs'])
        self.assertTrue(all((project / name).is_file() for name in group['sources']))
        self.assertIn('"$fixture_bin" original-fd-ready',
                      (project / 'scripts/prepare-tests.sh').read_text().splitlines())
        self.assertEqual(fast_fixtures.FULL_PREPARATION_PLAN, fast_fixtures._preparation_plan(project))
        self.assertIn('build/original-fd-ready', fast_fixtures.FULL_OUTPUT_ROOTS)
        self.assertTrue(fast_fixtures.fast_inputs.ORIGINAL_FD_READY_OUTPUTS <= fast_fixtures.FULL_REQUIRED)
        self.assertIn('"original-fd-ready/**/*.json"', (project / 'build.gradle.kts').read_text())
        for path in fast_fixtures.fast_inputs.ORIGINAL_FD_READY_OUTPUTS:
            self.assertTrue(fast_fixtures.fast_inputs.allowed_payload(path, {}), path)
        for name in ('OriginalFD.json', 'native/unreviewed', 'logs/extra.stdout',
                     'negative/extra.json', 'native/OriginalFdReadyAudit.hi', 'test-results/pass.xml'):
            self.assertFalse(fast_fixtures.fast_inputs.allowed_payload('build/original-fd-ready/' + name, {}))

    def test_original_rts_locks_exact_fixture_registration(self):
        project = Path(__file__).resolve().parents[2]
        manifest, owners = fast_fixtures._manifest(project)
        group = manifest['groups']['original-rts-locks']
        self.assertEqual('original-rts-locks', owners['thc.runtime.OriginalRtsLocksTest'])
        self.assertEqual([{'argv': ['cabal', 'run', 'exe:thc-fixtures', '--offline', '--',
                                  'original-rts-locks', '--require-supported']}], group['commands'])
        self.assertEqual(['build/original-rts-locks'], group['outputs'])
        self.assertTrue(all((project / name).is_file() for name in group['sources']))
        self.assertIn('"$fixture_bin" original-rts-locks --require-supported',
                      (project / 'scripts/prepare-tests.sh').read_text().splitlines())
        self.assertEqual(fast_fixtures.FULL_PREPARATION_PLAN, fast_fixtures._preparation_plan(project))
        self.assertIn('build/original-rts-locks', fast_fixtures.FULL_OUTPUT_ROOTS)
        self.assertTrue(fast_fixtures.fast_inputs.ORIGINAL_RTS_LOCK_OUTPUTS <= fast_fixtures.FULL_REQUIRED)
        self.assertIn('"original-rts-locks/**/*.json"', (project / 'build.gradle.kts').read_text())

    def test_original_open_exact_registration_and_closed_manifest(self):
        project = Path(__file__).resolve().parents[2]
        manifest, owners = fast_fixtures._manifest(project)
        group = manifest['groups']['original-open']
        self.assertEqual('original-open', owners['thc.runtime.OriginalOpenTest'])
        self.assertEqual([{'argv': ['cabal', 'run', 'exe:thc-fixtures', '--offline', '--', 'original-open']}], group['commands'])
        self.assertEqual(['build/original-open'], group['outputs'])
        self.assertTrue(all((project / name).is_file() for name in group['sources']))
        self.assertIn('"$fixture_bin" original-open', (project / 'scripts/prepare-tests.sh').read_text().splitlines())
        self.assertEqual(fast_fixtures.FULL_PREPARATION_PLAN, fast_fixtures._preparation_plan(project))
        self.assertIn('build/original-open', fast_fixtures.FULL_OUTPUT_ROOTS)
        self.assertIn('"original-open/**/*.json"', (project / 'build.gradle.kts').read_text())
        name = 'build/original-open/manifest.json'
        artifacts = {}
        for artifact in fast_fixtures.fast_inputs.ORIGINAL_OPEN_OUTPUTS - {name}:
            path = self.root / artifact; path.parent.mkdir(parents=True, exist_ok=True); path.write_text('{}\n')
            artifacts[artifact] = fast_fixtures._digest(path)
        receipt = dict(schema=1, supported=True, strictAccepted=True, runtimeVerified=False,
                       installedArtifactsHashed=False, nativeRows=13, artifactHashes=artifacts)
        path = self.root / name
        with mock.patch.object(fast_fixtures.fast_inputs, 'GMP_NATIVE_HOST', True):
            path.write_text(json.dumps(receipt))
            self.assertEqual(fast_fixtures.fast_inputs.ORIGINAL_OPEN_OUTPUTS,
                             fast_fixtures._output_hashes(self.root, group).keys())
            for key, value in (('schema', True), ('supported', False), ('strictAccepted', False),
                               ('runtimeVerified', True), ('installedArtifactsHashed', True), ('nativeRows', True), ('nativeRows', 12)):
                path.write_text(json.dumps(dict(receipt, **{key: value})))
                with self.assertRaises(RuntimeError): fast_fixtures._output_hashes(self.root, group)
            for change in ('unknown', 'missing', 'changed', 'symlink'):
                path.write_text(json.dumps(receipt))
                artifact = self.root / 'build/original-open/pre/core/OriginalOpenAudit.json'
                if artifact.is_symlink(): artifact.unlink()
                artifact.write_text('{}\n')
                if change == 'unknown':
                    path.write_text(json.dumps(dict(receipt, artifactHashes=dict(artifacts, **{'build/original-open/extra.json': '0'*64}))))
                elif change == 'missing': artifact.unlink()
                elif change == 'changed': artifact.write_text('changed')
                else: artifact.unlink(); artifact.symlink_to(path)
                with self.assertRaises((RuntimeError, FileNotFoundError)): fast_fixtures._output_hashes(self.root, group)

    def test_rts_lock_selected_receipt_requires_complete_strict_unchanged_artifacts(self):
        project = Path(__file__).resolve().parents[2]
        group = fast_fixtures._manifest(project)[0]['groups']['original-rts-locks']
        manifest_name = 'build/original-rts-locks/manifest.json'
        artifacts = {}
        for name in fast_fixtures.fast_inputs.ORIGINAL_RTS_LOCK_OUTPUTS - {manifest_name}:
            path = self.root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text('{}\n')
            artifacts[name] = fast_fixtures._digest(path)
        manifest = dict(schema=1, strictAccepted=True, originalIdsChecked=True,
                        typeEqualityChecked=True, installedArtifactsHashed=False, artifactHashes=artifacts)
        path = self.root / manifest_name
        path.write_text(json.dumps(manifest))
        self.assertEqual(fast_fixtures.fast_inputs.ORIGINAL_RTS_LOCK_OUTPUTS,
                         fast_fixtures._output_hashes(self.root, group).keys())
        for key in ('strictAccepted', 'originalIdsChecked', 'typeEqualityChecked'):
            path.write_text(json.dumps(dict(manifest, **{key: False})))
            with self.assertRaises(RuntimeError): fast_fixtures._output_hashes(self.root, group)
        for change in ('unknown', 'missing', 'changed', 'symlink'):
            path.write_text(json.dumps(manifest))
            artifact = self.root / 'build/original-rts-locks/pre.json'
            if artifact.is_symlink(): artifact.unlink()
            artifact.write_text('{}\n')
            if change == 'unknown':
                path.write_text(json.dumps(dict(manifest, artifactHashes=dict(artifacts, **{'build/original-rts-locks/extra.json': '0'*64}))))
            elif change == 'missing': artifact.unlink()
            elif change == 'changed': artifact.write_text('changed')
            else:
                artifact.unlink(); artifact.symlink_to(path)
            with self.assertRaises((RuntimeError, FileNotFoundError)):
                fast_fixtures._output_hashes(self.root, group)

    def test_interface_core_fixture_registration(self):
        project = Path(__file__).resolve().parents[2]
        manifest, owners = fast_fixtures._manifest(project)
        group = manifest['groups']['interface-core']
        self.assertEqual('interface-core', owners['thc.runtime.InterfaceCoreNativeTest'])
        self.assertEqual([{'argv': ['cabal', 'run', 'exe:thc-fixtures', '--offline', '--',
                                   'interface-core']}], group['commands'])
        self.assertEqual(['build/interface-core'], group['outputs'])
        self.assertTrue(all((project / name).is_file() for name in group['sources']))
        self.assertIn('"$fixture_bin" interface-core',
                      (project / 'scripts/prepare-tests.sh').read_text().splitlines())
        self.assertEqual(fast_fixtures.FULL_PREPARATION_PLAN, fast_fixtures._preparation_plan(project))
        self.assertIn('build/interface-core', fast_fixtures.FULL_OUTPUT_ROOTS)
        for name in ('manifest.json', 'InterfaceLibrary.json', 'logs/native-oracle.stdout',
                     'full/InterfaceLibrary.hi', 'thin/InterfaceLibrary.hi',
                     'full/InterfaceLibrary.dyn_hi', 'full/InterfaceForeign.hi',
                     'source/InterfaceLibrary.saved', 'opaqueEntry-audit.json',
                     'wrapperEntry-audit.json', 'installed-wrapper-facts.json',
                     'inlineEntry-audit.json', 'recursiveEntry-audit.json',
                     'CBVCoercionAudit.json', 'direct/CBVCoercionAudit.json',
                     'full/CBVCoercionAudit.hi', 'thin/CBVCoercionAudit.hi',
                     'source/CBVCoercionAudit.saved', 'coercionEntry-audit.json',
                     'logs/helper-thin.stdout', 'logs/helper-thin.command.json',
                     'wired-unit.json', 'logs/helper-wired-unit.stdout',
                     'logs/helper-wired-unit.command.json', 'packages.json', 'driver-controls.json',
                     'InterfaceForeign.json', 'foreign-packages.json',
                     'foreign-association.json', 'installed-bound-facts.json',
                     'foreign-alias/a.json', 'foreign-alias/b.json',
                     'source/InterfaceForeignAlias.hs.saved'):
            self.assertIn('build/interface-core/' + name, fast_fixtures.FULL_REQUIRED)
        self.assertIn('"interface-core/**/*.json"', (project / 'build.gradle.kts').read_text())

    def test_formatter_focused_full_gradle_and_upload_registration(self):
        project = Path(__file__).resolve().parents[2]
        manifest, owners = fast_fixtures._manifest(project)
        group = manifest['groups']['original-stack-formatter']
        self.assertEqual('original-stack-formatter', owners['thc.runtime.OriginalStackFormatterTest'])
        self.assertEqual([{'argv': ['cabal', 'run', 'exe:thc-fixtures', '--offline', '--',
                                   'original-stack-formatter']}], group['commands'])
        self.assertEqual(['build/original-stack-formatter'], group['outputs'])
        self.assertTrue(all((project / name).is_file() for name in group['sources']))
        self.assertIn('"$fixture_bin" original-stack-formatter',
                      (project / 'scripts/prepare-tests.sh').read_text().splitlines())
        self.assertEqual(fast_fixtures.FULL_PREPARATION_PLAN, fast_fixtures._preparation_plan(project))
        self.assertIn('build/original-stack-formatter', fast_fixtures.FULL_OUTPUT_ROOTS)
        self.assertIn('build/original-stack-formatter/manifest.json', fast_fixtures.FULL_REQUIRED)
        gradle = (project / 'build.gradle.kts').read_text()
        for pattern in ('original-stack-formatter/manifest.json',
                        'original-stack-formatter/run-*/originals/core/*.json',
                        'original-stack-formatter/run-*/originals/generated/**/*.hs',
                        'original-stack-formatter/run-*/originals/generated.json',
                        'original-stack-formatter/run-*/originals/target-layout.json',
                        'original-stack-formatter/run-*/native/formatter',
                        'src/THC/Driver/Wired.hs', 'compiler/target-layout.c',
                        'compiler/pinned-ghc-internal', '**/*.hs-boot', '**/*.hsc', 'include/WordSize.h'):
            self.assertIn('"' + pattern + '"', gradle)
        self.assertIn('build/original-stack-formatter/', (project / '.github/workflows/build.yml').read_text())
        sources = fast_fixtures._source_hashes(project, group)
        _, pins = fast_fixtures.fast_inputs.wired_catalog(project)
        self.assertLessEqual({'compiler/pinned-ghc-internal/' + name for name in pins}, sources.keys())
        self.assertIn(fast_fixtures.fast_inputs.WIRED_SOURCE, sources)

    def formatter_preparation(self):
        project = Path(__file__).resolve().parents[2]
        group = fast_fixtures._manifest(project)[0]['groups']['original-stack-formatter']
        self.manifest['groups']['original-stack-formatter'] = group
        (self.root / fast_fixtures.MANIFEST).write_text(json.dumps(self.manifest))
        for name in group['sources']:
            path = self.root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes((project / name).read_bytes())
        _, pins = fast_fixtures.fast_inputs.wired_catalog(self.root)
        for name in pins:
            path = self.root / 'compiler/pinned-ghc-internal' / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text('pinned source\n')
        def run(name, argv, stdout=None):
            self.fake_run(name, argv, stdout)
            if argv != group['commands'][0]['argv']:
                return
            base = self.root / group['outputs'][0]
            attempt = 1
            while (base / f'run-{attempt}').exists():
                attempt += 1
            directory = base / f'run-{attempt}'
            artifacts = {}
            for name in fast_fixtures.fast_inputs.original_stack_formatter_files(self.root):
                path = directory / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(b'{}\n' if name.endswith('.json') else b'\x00\x80\xff\n')
                artifacts[path.relative_to(self.root).as_posix()] = fast_fixtures._digest(path)
            overlay = directory / 'originals/interfaces'
            overlay.mkdir()
            (overlay / 'Installed.dyn_hi').symlink_to(self.root / 'fixtures/alpha.hs')
            (base / 'manifest.json').write_text(json.dumps({'artifactHashes': artifacts}))
        def prepare():
            return fast_fixtures.prepare(self.root, self.selection(*group['junit']), run, self.toolchain)
        return group, prepare

    def test_formatter_receipt_reuses_only_current_artifacts_and_all_catalog_sources(self):
        group, prepare = self.formatter_preparation()
        self.assertEqual(['original-stack-formatter'], prepare()['rebuilt'])
        self.calls.clear()
        self.assertEqual(['original-stack-formatter'], prepare()['reused'])
        self.assertEqual([], self.calls)
        outputs = fast_fixtures._output_hashes(self.root, group)
        self.assertEqual(96, len(outputs))
        self.assertFalse(any('interfaces/' in path for path in outputs))
        # Includes the production exporter, all pinned source kinds and the
        # target-layout C probe, without a second hand-maintained source list.
        for name in (*group['sources'],
                     'compiler/pinned-ghc-internal/GHC/Internal/Stack/Decode.hs',
                     'compiler/pinned-ghc-internal/GHC/Internal/IO/Unsafe.hs',
                     'compiler/pinned-ghc-internal/GHC/Internal/Heap/InfoTable/Types.hsc',
                     'compiler/pinned-ghc-internal/GHC/Internal/Ptr.hs',
                     'compiler/pinned-ghc-internal/GHC/Internal/Data/Either.hs',
                     'compiler/pinned-ghc-internal/GHC/Internal/Word.hs',
                     'compiler/pinned-ghc-internal/GHC/Internal/Bignum/Integer.hs',
                     'compiler/pinned-ghc-internal/GHC/Internal/Classes.hs',
                     'compiler/pinned-ghc-internal/GHC/Internal/Num.hs',
                     'compiler/pinned-ghc-internal/GHC/Internal/Bignum/Integer.hs-boot',
                     'compiler/pinned-ghc-internal/GHC/Internal/Bignum/BigNat.hs-boot',
                     'compiler/pinned-ghc-internal/GHC/Internal/Bignum/Natural.hs-boot',
                     'compiler/pinned-ghc-internal/GHC/Internal/Real.hs',
                     'compiler/pinned-ghc-internal/GHC/Internal/Numeric.hs',
                     'compiler/pinned-ghc-internal/GHC/Internal/Enum.hs',
                     'compiler/pinned-ghc-internal/GHC/Internal/ForeignPtr.hs',
                     'compiler/pinned-ghc-internal/GHC/Internal/Foreign/C/String/Encoding.hs',
                     'compiler/pinned-ghc-internal/GHC/Internal/IO/Encoding/UTF8.hs',
                     'compiler/pinned-ghc-internal/GHC/Internal/IO/Encoding/Types.hs',
                     'compiler/pinned-ghc-internal/GHC/Internal/IO/Encoding/Failure.hs',
                     'compiler/pinned-ghc-internal/GHC/Internal/IO/Encoding.hs',
                     'compiler/pinned-ghc-internal/GHC/Internal/IO/Handle/Types.hs-boot',
                     'compiler/pinned-ghc-internal/GHC/Internal/InfoProv/Types.hsc',
                     'compiler/pinned-ghc-internal/GHC/Internal/Num.hs-boot',
                     'compiler/pinned-ghc-internal/include/WordSize.h',
                     'compiler/pinned-ghc-internal/LICENSE'):
            with self.subTest(name=name):
                path = self.root / name
                path.write_bytes(path.read_bytes() + b'\n-- changed\n')
                self.assertEqual(['original-stack-formatter'], prepare()['rebuilt'])
                self.assertEqual(['original-stack-formatter'], prepare()['reused'])
        # Failed/old attempts are deliberately not success-receipt dependencies.
        (self.root / 'build/original-stack-formatter/run-1/logs/native-observations.stdout').write_text('old')
        self.calls.clear()
        self.assertEqual(['original-stack-formatter'], prepare()['reused'])
        self.assertEqual([], self.calls)

    def test_formatter_receipts_reject_mutation_missing_symlink_and_unknown_artifacts(self):
        group, prepare = self.formatter_preparation()
        prepare()
        manifest_path = self.root / 'build/original-stack-formatter/manifest.json'
        for change in ('bytes', 'missing', 'symlink', 'unknown', 'attempt-zero'):
            with self.subTest(change=change):
                manifest = json.loads(manifest_path.read_text())
                name = next(name for name in manifest['artifactHashes'] if name.endswith('/native/formatter'))
                path = self.root / name
                if change == 'bytes':
                    path.write_bytes(b'tampered')
                elif change == 'missing':
                    path.unlink()
                elif change == 'symlink':
                    path.unlink()
                    path.symlink_to(self.root / 'fixtures/alpha.hs')
                else:
                    replacement = name.replace('/native/formatter', '/logs/unknown.stdout') if change == 'unknown' \
                        else name.replace(name.split('/')[2], 'run-0')
                    manifest['artifactHashes'][replacement] = manifest['artifactHashes'].pop(name)
                    manifest_path.write_text(json.dumps(manifest))
                self.assertEqual(['original-stack-formatter'], prepare()['rebuilt'])
                self.assertEqual(['original-stack-formatter'], prepare()['reused'])
        with mock.patch.object(fast_fixtures, 'FULL_OUTPUT_ROOTS', frozenset(group['outputs'])), \
             mock.patch.object(fast_fixtures, 'FULL_REQUIRED', frozenset({manifest_path.relative_to(self.root).as_posix()})):
            full = fast_fixtures._full_output_hashes(self.root)
            self.assertEqual(fast_fixtures._output_hashes(self.root, group).keys(), full.keys())
            current = next(name for name in full if name.endswith('/logs/native-observations.stdout'))
            (self.root / current).write_bytes(b'tampered')
            with self.assertRaisesRegex(RuntimeError, 'Stale formatter artifact'):
                fast_fixtures._full_output_hashes(self.root)

    def test_boxed_array_extensions_focused_full_and_gradle_inputs(self):
        project = Path(__file__).resolve().parents[2]
        manifest, owners = fast_fixtures._manifest(project)
        group = manifest['groups']['boxed-array-extensions']
        self.assertEqual('boxed-array-extensions', owners['thc.runtime.BoxedArrayExtensionsTest'])
        self.assertEqual([{'argv': ['cabal', 'run', 'exe:thc-fixtures', '--offline', '--', 'boxed-array-extensions']}], group['commands'])
        self.assertTrue(all((project / name).is_file() for name in group['sources']))
        self.assertIn('"$fixture_bin" boxed-array-extensions', (project / 'scripts/prepare-tests.sh').read_text().splitlines())
        self.assertIn('build/boxed-array-extensions/manifest.json', fast_fixtures.FULL_REQUIRED)
        gradle = (project / 'build.gradle.kts').read_text()
        for pattern in ('"boxed-array-extensions/manifest.json"', '"boxed-array-extensions/run-*/**"', 'inputs.file("thc.cabal")'):
            self.assertIn(pattern, gradle)

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name).resolve()
        for pattern in fast_fixtures.COMMON_SOURCES:
            name = pattern.replace("**/*.hs", "Plugin.hs").replace("*.py", "core_vectors.py")
            path = self.root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(name)
        for name in ("fixtures/alpha.hs", "fixtures/beta.hs"):
            path = self.root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(name)
        self.manifest = {
            "schema": 1,
            "fixtureFreeJunit": ["thc.FreeTest"],
            "groups": {
                "alpha": {
                    "junit": ["thc.AlphaTest", "thc.AlphaBackendTest"],
                    "commands": [{"argv": ["make-alpha"], "stdout": "build/alpha/oracle.tsv"}],
                    "outputs": ["build/alpha"],
                    "sources": ["fixtures/alpha.hs"],
                },
                "beta": {
                    "junit": ["thc.BetaTest"],
                    "commands": [{"argv": ["make-beta"]}],
                    "outputs": ["build/beta/result.tsv"],
                    "sources": ["fixtures/beta.hs"],
                },
            },
        }
        manifest = self.root / fast_fixtures.MANIFEST
        manifest.parent.mkdir(parents=True, exist_ok=True)
        manifest.write_text(json.dumps(self.manifest))
        self.calls = []
        self.mutate_scalar_on_generator = False
        self.toolchain = {"ghcVersion": "9.14.1", "platform": "Linux-x86_64"}

    def fake_run(self, name, argv, stdout=None):
        self.calls.append((name, argv, stdout))
        if argv == ["python3", "scripts/generate-scalar-signatures.py"] and self.mutate_scalar_on_generator:
            resource = self.root / "src/main/resources/thc/scalar-primop-signatures.json"
            resource.write_text("updated signature table")
        if argv == ["make-alpha"]:
            self.assertEqual(stdout, "build/alpha/oracle.tsv")
            output = self.root / stdout
            self.assertTrue(output.parent.is_dir())
            output.write_text("native alpha\n")
        elif argv == ["make-beta"]:
            output = self.root / "build/beta/result.tsv"
            output.parent.mkdir(parents=True, exist_ok=True)
            output.write_text("native beta\n")

    @staticmethod
    def selection(*classes, mode="narrow"):
        return {"mode": mode, "junit": {"classes": list(classes)}}

    def prepare(self, *classes, mode="narrow"):
        with redirect_stderr(io.StringIO()):
            return fast_fixtures.prepare(self.root, self.selection(*classes, mode=mode),
                                         self.fake_run, self.toolchain)

    def test_deduplicates_group_and_reuses_verified_outputs(self):
        first = self.prepare("thc.AlphaTest", "thc.AlphaBackendTest")
        self.assertEqual(first, {"mode": "selected", "rebuilt": ["alpha"], "reused": []})
        self.assertEqual([argv for _, argv, _ in self.calls], [
            ["python3", "scripts/generate-scalar-signatures.py"],
            ["compiler/build.sh"], ["make-alpha"]])
        self.assertEqual(self.prepare("thc.AlphaBackendTest"),
                         {"mode": "selected", "rebuilt": [], "reused": ["alpha"]})
        self.assertEqual(len(self.calls), 3)

    def test_only_changed_group_rebuilds_and_compiler_runs_once(self):
        self.prepare("thc.AlphaTest", "thc.BetaTest")
        self.assertEqual([argv for _, argv, _ in self.calls].count(["compiler/build.sh"]), 1)
        self.calls.clear()
        (self.root / "fixtures/beta.hs").write_text("changed")
        result = self.prepare("thc.AlphaTest", "thc.BetaTest")
        self.assertEqual(result, {"mode": "selected", "rebuilt": ["beta"], "reused": ["alpha"]})
        self.assertEqual([argv for _, argv, _ in self.calls], [
            ["python3", "scripts/generate-scalar-signatures.py"],
            ["compiler/build.sh"], ["make-beta"]])

    def test_missing_or_changed_output_rebuilds(self):
        self.prepare("thc.AlphaTest")
        output = self.root / "build/alpha/oracle.tsv"
        self.calls.clear()
        output.write_text("tampered")
        self.assertEqual(self.prepare("thc.AlphaTest")["rebuilt"], ["alpha"])
        self.calls.clear()
        output.unlink()
        self.assertEqual(self.prepare("thc.AlphaTest")["rebuilt"], ["alpha"])

    def test_invalid_stamp_rebuilds(self):
        self.prepare("thc.AlphaTest")
        self.calls.clear()
        stamp = self.root / fast_fixtures.STAMP_DIR / "alpha.json"
        stamp.write_text("[]")
        self.assertEqual(self.prepare("thc.AlphaTest")["rebuilt"], ["alpha"])

    def test_common_source_or_toolchain_change_rebuilds(self):
        self.prepare("thc.AlphaTest")
        self.calls.clear()
        (self.root / "compiler/THC/Plugin.hs").write_text("new plugin")
        self.assertEqual(self.prepare("thc.AlphaTest")["rebuilt"], ["alpha"])
        self.calls.clear()
        self.toolchain["ghcVersion"] = "9.14.2"
        self.assertEqual(self.prepare("thc.AlphaTest")["rebuilt"], ["alpha"])

    def test_preparatory_source_change_rechecks_previous_hits(self):
        self.prepare("thc.AlphaTest")
        self.calls.clear()
        self.mutate_scalar_on_generator = True
        result = self.prepare("thc.AlphaTest", "thc.BetaTest")
        self.assertEqual(result["rebuilt"], ["alpha", "beta"])
        self.assertEqual(result["reused"], [])

    def test_cabal_plugin_inputs_invalidate_selected_fixtures(self):
        self.prepare("thc.AlphaTest")
        for name in ("thc.cabal", "cabal.project", "Setup.hs", "Makefile", "compiler/plugin.py"):
            with self.subTest(name=name):
                self.calls.clear()
                (self.root / name).write_text("changed plugin build input")
                self.assertEqual(self.prepare("thc.AlphaTest")["rebuilt"], ["alpha"])

    def test_unknown_or_full_selection_runs_complete_preparation(self):
        self.assertEqual(self.prepare("thc.UnknownTest"),
                         {"mode": "full", "rebuilt": ["full"], "reused": []})
        self.assertEqual(self.calls, [("fixtures-full", ["scripts/prepare-tests.sh"], None)])
        self.calls.clear()
        self.assertEqual(self.prepare("thc.AlphaTest", mode="full")["mode"], "full")
        self.assertEqual(self.calls, [("fixtures-full", ["scripts/prepare-tests.sh"], None)])

    def test_fixture_free_selection_runs_no_commands(self):
        self.assertEqual(self.prepare("thc.FreeTest"),
                         {"mode": "selected", "rebuilt": [], "reused": []})
        self.assertEqual(self.calls, [])

    def test_unrelated_source_does_not_invalidate_group(self):
        self.prepare("thc.AlphaTest")
        self.calls.clear()
        (self.root / "fixtures/beta.hs").write_text("unrelated change")
        self.manifest["groups"]["beta"]["commands"][0]["argv"] = ["changed-beta"]
        (self.root / fast_fixtures.MANIFEST).write_text(json.dumps(self.manifest))
        self.assertEqual(self.prepare("thc.AlphaTest")["reused"], ["alpha"])
        self.assertEqual(self.calls, [])

    def test_word_and_fused_floating_have_focused_and_full_preparation(self):
        project = Path(__file__).resolve().parents[2]
        manifest, owners = fast_fixtures._manifest(project)
        self.assertEqual(fast_fixtures.FULL_PREPARATION_PLAN, fast_fixtures._preparation_plan(project))
        policy = json.loads((project / ".github/scripts/fast-tests.json").read_text())
        for name, junit in (("word-floating", "thc.runtime.WordFloatingTest"),
                            ("fused-floating", "thc.runtime.FusedFloatingTest")):
            with self.subTest(name=name):
                group = manifest["groups"][name]
                self.assertEqual(name, owners[junit])
                self.assertEqual([{"argv": ["cabal", "run", "exe:thc-fixtures", "--offline", "--", name]}], group["commands"])
                self.assertEqual(["build/" + name], group["outputs"])
                self.assertTrue(all((project / source).is_file() for source in group["sources"]))
                self.assertIn('"$fixture_bin" ' + name, (project / "scripts/prepare-tests.sh").read_text().splitlines())
                self.assertIn("build/" + name, fast_fixtures.FULL_OUTPUT_ROOTS)
                self.assertIn("build/" + name + "/manifest.json", fast_fixtures.FULL_REQUIRED)
                self.assertIn("build/" + name + "/", (project / ".github/workflows/build.yml").read_text())
                self.assertIn(name + "/**/*.json", (project / "build.gradle.kts").read_text())
                self.assertIn(junit, policy["leafSources"]["src/main/kotlin/thc/runtime/FloatingPrimitives.kt"]["junit"])

    def test_explicit64_array_fixture_is_selected_and_receipted(self):
        project = Path(__file__).resolve().parents[2]
        manifest, owners = fast_fixtures._manifest(project)
        group = manifest["groups"]["explicit64-arrays"]
        self.assertEqual("explicit64-arrays", owners["thc.runtime.Explicit64ArrayTest"])
        self.assertEqual(["build/explicit64-arrays"], group["outputs"])
        self.assertTrue(all((project / name).is_file() for name in group["sources"]))
        self.assertIn('"$fixture_bin" explicit64-arrays',
                      (project / "scripts/prepare-tests.sh").read_text().splitlines())
        self.assertEqual(fast_fixtures.FULL_PREPARATION_PLAN, fast_fixtures._preparation_plan(project))
        self.assertIn("build/explicit64-arrays", fast_fixtures.FULL_OUTPUT_ROOTS)
        for name in ("manifest.json", "oracle.tsv", "pre/audit.json", "post/audit.json",
                     "pre/core/Explicit64ArrayAudit.json", "post/core/Explicit64ArrayAudit.json"):
            self.assertIn("build/explicit64-arrays/" + name, fast_fixtures.FULL_REQUIRED)
        self.assertIn('"explicit64-arrays/*.tsv"', (project / "build.gradle.kts").read_text())

    def test_floating_address_fixture_is_selected_and_receipted(self):
        project = Path(__file__).resolve().parents[2]
        manifest, owners = fast_fixtures._manifest(project)
        group = manifest["groups"]["floating-address"]
        self.assertEqual("floating-address", owners["thc.runtime.FloatingAddressTest"])
        self.assertEqual(["build/floating-address"], group["outputs"])
        self.assertTrue(all((project / name).is_file() for name in group["sources"]))
        self.assertIn('"$fixture_bin" floating-address',
                      (project / "scripts/prepare-tests.sh").read_text().splitlines())
        self.assertEqual(fast_fixtures.FULL_PREPARATION_PLAN, fast_fixtures._preparation_plan(project))
        self.assertIn("build/floating-address", fast_fixtures.FULL_OUTPUT_ROOTS)
        for name in ("manifest.json", "oracle.tsv", "pre/audit.json", "post/audit.json",
                     "pre/core/FloatingAddressAudit.json", "post/core/FloatingAddressAudit.json"):
            self.assertIn("build/floating-address/" + name, fast_fixtures.FULL_REQUIRED)
        self.assertIn('"floating-address/*.tsv"', (project / "build.gradle.kts").read_text())

    def test_floating_native_consumers_use_existing_complete_preparation_groups(self):
        project = Path(__file__).resolve().parents[2]
        manifest, owners = fast_fixtures._manifest(project)
        # Reviewed generated inputs of the ten previously unowned consumers.
        consumed = {
            "thc.SumLayoutMetadataTest": ("sum-results", ["sum-layout"]),
            "thc.runtime.SumProtocolTest": ("sum-results", ["sum-layout", "sum-result"]),
            "thc.runtime.SumResultTest": ("sum-results", ["sum-layout", "sum-result", "aggregate-core",
                                                       "aggregate-post-core", "aggregate-native"]),
            "thc.runtime.FloatingTupleTest": ("floating-tuples", ["floating-tuple"]),
            "thc.runtime.SqrtPrimitiveTest": ("sqrt", ["sqrt"]),
            "thc.runtime.ScalarBitCastTest": ("scalar-bitcasts", ["scalar-bitcasts"]),
            "thc.runtime.SimdFloatVectorTest": ("simd-floatx4", ["simd-floatx4"]),
            "thc.runtime.SimdDoubleVectorTest": ("simd-doublex2", ["simd-doublex2"]),
            "thc.runtime.SimdFloatByteArrayTest": ("simd-floatx4-bytearray", ["simd-floatx4-bytearray"]),
            "thc.runtime.SimdDoubleByteArrayTest": ("simd-doublex2-bytearray", ["simd-doublex2-bytearray"]),
        }
        full = " ".join((project / "scripts/prepare-tests.sh").read_text().split())
        for junit, (group_id, roots) in consumed.items():
            with self.subTest(junit=junit):
                self.assertEqual(group_id, owners[junit])
                group = manifest["groups"][group_id]
                source = (project / ("src/test/kotlin/" + junit.replace(".", "/") + ".kt")).read_text()
                for root in roots:
                    self.assertTrue('"build/' + root in source or '"' + root + '"' in source, root)
                    self.assertIn("build/" + root, group["outputs"])
                self.assertTrue(all((project / name).is_file() for name in group["sources"]))
                for command in group["commands"]:
                    argv = command["argv"]
                    if argv[:5] == ["cabal", "run", "exe:thc-fixtures", "--offline", "--"] and len(argv) == 6:
                        self.assertIn('"$fixture_bin" ' + argv[5], full)
                    else:
                        self.assertIn(" ".join((argv[2] if argv[:2] == ["sh", "-c"] else " ".join(argv)).split()), full)
        # The aggregate producer also runs the recursive-layout rejection
        # checks. Keep their inputs and outputs in the same receipt.
        sums = manifest["groups"]["sum-results"]
        self.assertIn("scripts/check-aggregate-layout.py", sums["sources"])
        self.assertIn("compiler/test-fixtures/AggregateLayoutAudit.hs", sums["sources"])
        self.assertIn("build/aggregate-layout", sums["outputs"])
        self.assertIn("build/aggregate-frontier.json", sums["outputs"])

    def test_floating_model_controls_are_explicitly_fixture_free(self):
        project = Path(__file__).resolve().parents[2]
        _, owners = fast_fixtures._manifest(project)
        for name in ("BytecodeTypedTupleInputTest", "DoubleArrayTest", "DoubleVectorMemoryProofTest",
                     "DoubleVectorStorageTest", "FloatArrayTest", "FloatVectorMemoryProofTest",
                     "FloatVectorStorageTest"):
            with self.subTest(name=name):
                self.assertIn("thc.runtime." + name, owners)
                self.assertIsNone(owners["thc.runtime." + name])
                source = (project / "src/test/kotlin/thc/runtime" / (name + ".kt")).read_text()
                self.assertNotIn('"build/', source)

    def test_managed_file_and_stdio_controls_do_not_force_full_fixture_preparation(self):
        project = Path(__file__).resolve().parents[2]
        _, owners = fast_fixtures._manifest(project)
        names = ("thc.GuestExceptionsTest", "thc.runtime.ManagedFileCallTest",
                 "thc.runtime.ManagedFilesTest", "thc.runtime.ManagedStdioTest",
                 "thc.runtime.OriginalStdioCallTest", "thc.runtime.StdioHostAbiTest")
        for name in names:
            with self.subTest(name=name):
                self.assertIn(name, owners)
                self.assertIsNone(owners[name])
                source = (project / "src/test/kotlin" / (name.replace(".", "/") + ".kt")).read_text()
                self.assertNotIn('"build/', source)

    def test_floating_simd_commands_preserve_full_preparation_platform_modes(self):
        project = Path(__file__).resolve().parents[2]
        manifest, _ = fast_fixtures._manifest(project)
        for family in ("floatx4", "doublex2", "floatx4-bytearray", "doublex2-bytearray"):
            command = manifest["groups"]["simd-" + family]["commands"][0]["argv"]
            self.assertEqual(["sh", "-c"], command[:2])
            for machine in ("x86_64", "arm64", "aarch64"):
                with self.subTest(family=family, machine=machine):
                    # Execute only shell dispatch: these functions replace both
                    # external tools, never invoking a compiler or producer.
                    prefix = 'uname() { printf "%s\\n" ' + machine + '; }; python3() { printf "%s\\n" "$@"; }; '
                    actual = subprocess.check_output([*command[:2], prefix + command[2]], text=True).splitlines()
                    self.assertEqual(["scripts/prepare-" + family + "-audit.py"] +
                                     ([] if machine == "x86_64" else ["--export-only"]), actual)

    def test_complete_floating_selection_prepares_and_reuses_without_full_fallback(self):
        project = Path(__file__).resolve().parents[2]
        manifest, owners = fast_fixtures._manifest(project)
        policy = json.loads((project / ".github/scripts/fast-tests.json").read_text())
        classes = policy["leafSources"]["src/main/kotlin/thc/runtime/FloatingPrimitives.kt"]["junit"]
        expected = sorted({owners[name] for name in classes if owners[name] is not None})
        self.assertEqual(25, len(classes))
        self.manifest = {"schema": 1, "fixtureFreeJunit": manifest["fixtureFreeJunit"],
                         "groups": {name: manifest["groups"][name] for name in expected}}
        (self.root / fast_fixtures.MANIFEST).write_text(json.dumps(self.manifest))
        for group in self.manifest["groups"].values():
            for name in group["sources"]:
                path = self.root / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes((project / name).read_bytes())
        def run(name, argv, stdout=None):
            self.calls.append((name, argv, stdout))
            for group_id, group in self.manifest["groups"].items():
                if not name.startswith("fixture-" + group_id + "-"):
                    continue
                for output in group["outputs"]:
                    path = self.root / output
                    if path.suffix != ".json":
                        path /= "fixture.json"
                    path.parent.mkdir(parents=True, exist_ok=True)
                    path.write_text("prepared fixture\n")
        def prepare():
            return fast_fixtures.prepare(self.root, self.selection(*classes), run, self.toolchain)
        self.assertEqual({"mode": "selected", "rebuilt": expected, "reused": []}, prepare())
        self.assertEqual(2 + sum(len(group["commands"]) for group in self.manifest["groups"].values()), len(self.calls))
        self.calls.clear()
        self.assertEqual({"mode": "selected", "rebuilt": [], "reused": expected}, prepare())
        self.assertEqual([], self.calls)
        # Real transitive model and native fixture changes invalidate the
        # affected group only; unchanged inputs never rerun the full producer.
        for group_id, source in (("sum-results", "scripts/sum_layout_model.py"),
                                 ("floating-tuples", "compiler/test-fixtures/FloatingTupleAudit.hs")):
            with self.subTest(source=source):
                path = self.root / source
                path.write_bytes(path.read_bytes() + b"\n# changed\n")
                self.assertEqual([group_id], prepare()["rebuilt"])
        for group_id in ("sum-results", "floating-tuples", "sqrt", "scalar-bitcasts", "simd-floatx4",
                         "simd-doublex2", "simd-floatx4-bytearray", "simd-doublex2-bytearray"):
            for change in ("bytes", "missing"):
                with self.subTest(group=group_id, change=change):
                    path = self.root / self.manifest["groups"][group_id]["outputs"][0] / "fixture.json"
                    if change == "bytes":
                        path.write_text("corrupt\n")
                    else:
                        path.unlink()
                    self.assertEqual([group_id], prepare()["rebuilt"])
                    self.assertEqual([], prepare()["rebuilt"])

    def test_original_stack_has_portable_focused_and_full_preparation(self):
        project = Path(__file__).resolve().parents[2]
        manifest, owners = fast_fixtures._manifest(project)
        group = manifest["groups"]["original-stack"]
        self.assertEqual("original-stack", owners["thc.runtime.OriginalStackConsumerProofTest"])
        self.assertEqual([{"argv": ["cabal", "run", "exe:thc-fixtures", "--offline", "--", "original-stack"]}], group["commands"])
        self.assertEqual(["build/original-stack"], group["outputs"])
        self.assertTrue(all((project / name).is_file() for name in group["sources"]))
        self.assertIn('"$fixture_bin" original-stack', (project / "scripts/prepare-tests.sh").read_text().splitlines())
        self.assertIn("build/original-stack", fast_fixtures.FULL_OUTPUT_ROOTS)
        self.assertIn("build/original-stack/manifest.json", fast_fixtures.FULL_REQUIRED)
        gradle = (project / "build.gradle.kts").read_text()
        self.assertIn('"original-stack/manifest.json"', gradle)
        self.assertIn('"original-stack/run-*/**"', gradle)
        self.assertNotIn('"original-stack/proof.json"', gradle)
        for name in ("thc.cabal", "compiler/pinned-ghc-internal/LICENSE",
                     "compiler/pinned-ghc-internal/GHC/Internal/InfoProv/Types.hsc",
                     "compiler/pinned-ghc-internal/GHC/Internal/Heap/InfoTable.hsc"):
            self.assertIn('"' + name + '"', gradle)
            self.assertIn(name, group["sources"])
        for name in ("Stack/CloneStack.hs", "Stack/Decode.hs"):
            self.assertIn("compiler/pinned-ghc-internal/GHC/Internal/" + name, group["sources"])

    def test_compiled_thunk_retention_prepares_both_native_families(self):
        project = Path(__file__).resolve().parents[2]
        manifest, owners = fast_fixtures._manifest(project)
        group = manifest["groups"]["compiled-thunk-retention"]
        self.assertEqual({"thc.runtime.CompiledThunkRetentionTest", "thc.runtime.BoxedArrayTest",
                          "thc.runtime.FloatingPrimitiveTest"}, set(group["junit"]))
        self.assertEqual({"compiled-thunk-retention"}, {owners[name] for name in group["junit"]})
        self.assertEqual([{"argv": ["python3", "scripts/prepare-boxed-arrays.py"]},
                          {"argv": ["python3", "scripts/prepare-floating-audit.py"]}], group["commands"])
        self.assertEqual(["build/boxed-arrays", "build/floating"], group["outputs"])
        self.assertEqual({"scripts/prepare-boxed-arrays.py", "scripts/prepare-floating-audit.py",
                          "compiler/test-fixtures/BoxedArrayAudit.hs",
                          "compiler/test-fixtures/FloatingAudit.hs",
                          "compiler/test-fixtures/FloatingAuditNative.hs"}, set(group["sources"]))
        self.assertTrue(all((project / name).is_file() for name in group["sources"]))
        full = (project / "scripts/prepare-tests.sh").read_text().splitlines()
        for command in group["commands"]:
            self.assertIn(" ".join(command["argv"]), full)
        self.assertLessEqual(set(group["outputs"]), fast_fixtures.FULL_OUTPUT_ROOTS)
        self.assertLessEqual({"build/boxed-arrays/manifest.json", "build/floating/checks.json"},
                             fast_fixtures.FULL_REQUIRED)
        gradle = (project / "build.gradle.kts").read_text()
        for pattern in ("boxed-arrays/**/*.json", "boxed-arrays/*.tsv", "floating/core/**/*.json",
                        "floating/checks.json", "floating/oracle.tsv"):
            self.assertIn('"' + pattern + '"', gradle)

    def test_retention_receipt_rejects_changed_sources_or_either_output_tree(self):
        project = Path(__file__).resolve().parents[2]
        manifest, _ = fast_fixtures._manifest(project)
        group = manifest["groups"]["compiled-thunk-retention"]
        self.manifest["groups"]["compiled-thunk-retention"] = group
        (self.root / fast_fixtures.MANIFEST).write_text(json.dumps(self.manifest))
        for name in group["sources"]:
            path = self.root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text("fixture source\n")
        def run(name, argv, stdout=None):
            self.fake_run(name, argv, stdout)
            for command, output in zip(group["commands"], group["outputs"]):
                if argv == command["argv"]:
                    directory = self.root / output
                    directory.mkdir(parents=True, exist_ok=True)
                    (directory / "oracle.tsv").write_text("native rows\n")
        def prepare(*classes):
            return fast_fixtures.prepare(self.root, self.selection(*classes), run, self.toolchain)
        self.assertEqual(["compiled-thunk-retention"], prepare(*group["junit"])["rebuilt"])
        for name in group["junit"]:
            self.assertEqual(["compiled-thunk-retention"], prepare(name)["reused"])
        for name in group["sources"]:
            (self.root / name).write_text("changed source\n")
            self.assertEqual(["compiled-thunk-retention"], prepare(group["junit"][0])["rebuilt"])
        for output in group["outputs"]:
            path = self.root / output / "oracle.tsv"
            path.write_text("changed native rows\n")
            self.assertEqual(["compiled-thunk-retention"], prepare(group["junit"][0])["rebuilt"])
            path.unlink()
            self.assertEqual(["compiled-thunk-retention"], prepare(group["junit"][0])["rebuilt"])
        self.assertNotIn("fixtures-full", [name for name, _, _ in self.calls])

    def test_original_stdio_has_strict_focused_and_full_preparation(self):
        project = Path(__file__).resolve().parents[2]
        manifest, owners = fast_fixtures._manifest(project)
        group = manifest["groups"]["original-stdio"]
        command = ["scripts/prepare-original-stdio.sh", "--require-supported"]
        self.assertEqual("original-stdio", owners["thc.runtime.OriginalStdioNativeTest"])
        self.assertEqual([{"argv": command}], group["commands"])
        self.assertEqual(["build/original-stdio"], group["outputs"])
        self.assertEqual({"compiler/test-fixtures/OriginalStdioAudit.hs",
                          "compiler/test-fixtures/OriginalStdioAuditNative.hs",
                          "scripts/prepare-original-stdio.sh", "test/haskell-fixtures/Main.hs",
                          "test/haskell-fixtures/FixtureSupport.hs",
                          "test/haskell-fixtures/OriginalStdioFixtures.hs", "thc.cabal"}, set(group["sources"]))
        self.assertTrue(all((project / name).is_file() for name in group["sources"]))
        self.assertIn('"$fixture_bin" original-stdio --require-supported',
                      (project / "scripts/prepare-tests.sh").read_text().splitlines())
        self.assertEqual(fast_fixtures.FULL_PREPARATION_PLAN, fast_fixtures._preparation_plan(project))
        self.assertIn("build/original-stdio", fast_fixtures.FULL_OUTPUT_ROOTS)
        self.assertIn("build/original-stdio/manifest.json", fast_fixtures.FULL_REQUIRED)

    def test_original_stdio_selected_receipt_covers_haskell_producer_and_all_outputs(self):
        project = Path(__file__).resolve().parents[2]
        manifest, _ = fast_fixtures._manifest(project)
        group = manifest["groups"]["original-stdio"]
        self.manifest["groups"]["original-stdio"] = group
        (self.root / fast_fixtures.MANIFEST).write_text(json.dumps(self.manifest))
        for name in group["sources"]:
            path = self.root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text("original fixture source\n")
        prepared = []
        def run(name, argv, stdout=None):
            self.fake_run(name, argv, stdout)
            if argv == group["commands"][0]["argv"]:
                prepared.append(name)
                directory = self.root / "build/original-stdio"
                directory.mkdir(parents=True, exist_ok=True)
                (directory / "manifest.json").write_text("{}\n")
                (directory / "oracle.json").write_text("[]\n")
        def prepare():
            return fast_fixtures.prepare(self.root, self.selection("thc.runtime.OriginalStdioNativeTest"), run, self.toolchain)
        self.assertEqual(["original-stdio"], prepare()["rebuilt"])
        self.assertEqual(["original-stdio"], prepare()["reused"])
        for name in group["sources"]:
            (self.root / name).write_text("changed fixture source\n")
            self.assertEqual(["original-stdio"], prepare()["rebuilt"], name)
        (self.root / "build/original-stdio/oracle.json").write_text("tampered output\n")
        self.assertEqual(["original-stdio"], prepare()["rebuilt"])
        (self.root / "build/original-stdio/manifest.json").unlink()
        self.assertEqual(["original-stdio"], prepare()["rebuilt"])
        self.assertEqual(len(group["sources"]) + 3, len(prepared))
        self.assertNotIn("fixtures-full", [name for name, _, _ in self.calls])

    def test_pr80_affected_classes_have_focused_preparation(self):
        project = Path(__file__).resolve().parents[2]
        manifest, owners = fast_fixtures._manifest(project)
        affected = {"thc.RealCoreEntryContractTest", "thc.runtime.ScalarLexicalProofTest",
                    "thc.runtime.BoxedLexicalProofTest", "thc.runtime.ScalarPrimitiveSignatureTest",
                    "thc.runtime.DataToTagTest", "thc.runtime.MutableByteArraySizeTest",
                    "thc.runtime.Int8ArrayNativeTest", "thc.runtime.Int16ArrayNativeTest",
                    "thc.runtime.Int32ArrayNativeTest"}
        self.assertEqual({"cbv-coercion", "data-to-tag", "mutable-bytearray-size",
                          "int8-arrays", "int16-arrays", "int32-arrays"},
                         {owners[name] for name in affected})
        for group_id in {owners[name] for name in affected}:
            group = manifest["groups"][group_id]
            self.assertTrue(all((project / path).is_file() for path in group["sources"]))
            self.assertTrue(group["commands"] and group["outputs"])
        cbv = manifest["groups"]["cbv-coercion"]
        self.assertIn("build/cbv-post-core/CBVCoercionAudit.json", cbv["outputs"])
        self.assertIn("build/tuple-arithmetic/pre-core/TupleArithmeticAudit.json", cbv["outputs"])
        self.assertIn("build/explicit64-primops/core/Explicit64PrimopsAudit.json", cbv["outputs"])
        self.assertTrue(any("scripts/check-cbv-metadata.py" in command["argv"]
                            for command in cbv["commands"]))


class FullFixtureReceiptTest(unittest.TestCase):
    prepare = FixturePreparationTest.prepare
    selection = staticmethod(FixturePreparationTest.selection)

    def setUp(self):
        FixturePreparationTest.setUp(self)
        for name in ("build.gradle.kts", "thc.cabal", "cabal.project", "Setup.hs",
                     ".github/scripts/fast_fixtures.py",
                     "scripts/prepare-tests.sh"):
            path = self.root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text("prepare reviewed fixtures\n")
        self.fail_preparation = False
        self.extra_output = False
        self.generated_fixture = False
        self.prepared = 0
        self.patches = ExitStack()
        self.addCleanup(self.patches.close)
        plan = fast_fixtures._preparation_plan(self.root)
        self.patches.enter_context(mock.patch.object(fast_fixtures, "FULL_PREPARATION_PLAN", plan))
        self.patches.enter_context(mock.patch.object(fast_fixtures, "FULL_OUTPUT_ROOTS",
                                               frozenset({"build/alpha", "build/beta"})))
        self.patches.enter_context(mock.patch.object(fast_fixtures, "FULL_REQUIRED",
                                               frozenset({"build/alpha/oracle.tsv", "build/beta/result.tsv"})))
        self.vendor_pins = self.patches.enter_context(mock.patch.object(
            fast_fixtures.fast_inputs, "vendor_pins", return_value={}))
        def identity(_root):
            return {"source": hashlib.sha256((self.root / "fixtures/alpha.hs").read_bytes()).hexdigest(),
                    "toolchain": self.toolchain.copy()}
        self.patches.enter_context(mock.patch.object(fast_fixtures.fast_inputs, "identity",
                                               side_effect=identity))

    def fake_run(self, name, argv, stdout=None):
        if argv != ["scripts/prepare-tests.sh"]:
            return FixturePreparationTest.fake_run(self, name, argv, stdout)
        self.calls.append((name, argv, stdout))
        if self.fail_preparation:
            raise RuntimeError("interrupted full preparation")
        self.prepared += 1
        for name in ("build/alpha/oracle.tsv", "build/beta/result.tsv"):
            path = self.root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(f"prepared {self.prepared}\n")
        if self.extra_output:
            output = self.root / "build/new-family/proof.tsv"
            output.parent.mkdir(parents=True, exist_ok=True)
            output.write_text("new preparer output\n")
        if self.generated_fixture:
            for name in fast_fixtures.fast_inputs.SIMD_SMOKE_SOURCES:
                output = self.root / name
                output.parent.mkdir(parents=True, exist_ok=True)
                output.write_text(f"generated {self.prepared}\n")

    def test_full_miss_then_hit_for_full_and_unknown_selection(self):
        self.assertEqual(self.prepare("thc.AlphaTest", mode="full"),
                         {"mode": "full", "rebuilt": ["full"], "reused": []})
        (self.root / "build/alpha/Main.o").write_text("compiler intermediate")
        self.assertEqual(self.prepare("thc.UnknownTest"),
                         {"mode": "full", "rebuilt": [], "reused": ["full"]})
        self.assertEqual(self.prepared, 1)

    def test_full_original_stdio_receipt_requires_manifest_and_all_output_bytes(self):
        def run(name, argv, stdout=None):
            self.fake_run(name, argv, stdout)
            directory = self.root / "build/original-stdio"
            directory.mkdir(parents=True, exist_ok=True)
            (directory / "manifest.json").write_text("{}\n")
            (directory / "oracle.json").write_text("[]\n")
        def prepare():
            return fast_fixtures.prepare(self.root, self.selection("thc.UnknownTest"), run, self.toolchain)
        with mock.patch.object(fast_fixtures, "FULL_OUTPUT_ROOTS", fast_fixtures.FULL_OUTPUT_ROOTS | {"build/original-stdio"}), \
             mock.patch.object(fast_fixtures, "FULL_REQUIRED", fast_fixtures.FULL_REQUIRED | {"build/original-stdio/manifest.json"}):
            self.assertEqual(["full"], prepare()["rebuilt"])
            self.assertEqual(["full"], prepare()["reused"])
            (self.root / "build/original-stdio/manifest.json").unlink()
            self.assertEqual(["full"], prepare()["rebuilt"])
            (self.root / "build/original-stdio/oracle.json").write_text("tampered output\n")
            self.assertEqual(["full"], prepare()["rebuilt"])
            self.assertEqual(["full"], prepare()["reused"])
        self.assertEqual(3, self.prepared)

    def test_jvm_generated_sources_do_not_invalidate_full_fixture_receipt(self):
        self.prepare("thc.UnknownTest")
        generated = self.root / "build/generated/kapt/main/Generated.java"
        generated.parent.mkdir(parents=True)
        generated.write_text("class Generated {}\n")
        self.assertEqual(self.prepare("thc.UnknownTest")["reused"], ["full"])
        generated.write_text("class Generated { int changed; }\n")
        self.assertEqual(self.prepare("thc.UnknownTest")["reused"], ["full"])
        self.assertEqual(self.prepared, 1)

    def test_generated_haskell_fixture_is_verified_without_jvm_codegen(self):
        self.generated_fixture = True
        names = fast_fixtures.fast_inputs.SIMD_SMOKE_SOURCES
        with mock.patch.object(fast_fixtures, "FULL_REQUIRED", fast_fixtures.FULL_REQUIRED | names):
            self.prepare("thc.UnknownTest")
            self.assertEqual(self.prepare("thc.UnknownTest")["reused"], ["full"])
            generated = self.root / "build/generated/simd/fixtures/GeneratedSimdSmoke.hs"
            generated.write_text("changed generated fixture\n")
            self.assertEqual(self.prepare("thc.UnknownTest")["rebuilt"], ["full"])
            self.assertEqual(self.prepared, 2)

    def test_root_cabal_inputs_invalidate_full_fixture_receipt(self):
        self.prepare("thc.UnknownTest")
        for name in ("thc.cabal", "cabal.project", "Setup.hs"):
            with self.subTest(name=name):
                (self.root / name).write_text("changed root Cabal input\n")
                self.assertEqual(self.prepare("thc.UnknownTest")["rebuilt"], ["full"])
        self.assertEqual(self.prepared, 4)

    def test_source_output_and_toolchain_drift_each_miss(self):
        self.prepare("thc.UnknownTest")
        (self.root / "fixtures/alpha.hs").write_text("changed fixture source")
        self.assertEqual(self.prepare("thc.UnknownTest")["rebuilt"], ["full"])
        (self.root / "build/beta/result.tsv").write_text("tampered output")
        self.assertEqual(self.prepare("thc.UnknownTest")["rebuilt"], ["full"])
        (self.root / "build/beta/result.tsv").chmod(0o600)
        self.assertEqual(self.prepare("thc.UnknownTest")["rebuilt"], ["full"])
        (self.root / "build/alpha/unrecorded.tsv").write_text("new output")
        self.assertEqual(self.prepare("thc.UnknownTest")["rebuilt"], ["full"])
        (self.root / "build/alpha/unrecorded.tsv").unlink()
        self.toolchain["ghcVersion"] = "9.14.2"
        self.assertEqual(self.prepare("thc.UnknownTest")["rebuilt"], ["full"])
        (self.root / "build/alpha/oracle.tsv").unlink()
        self.assertEqual(self.prepare("thc.UnknownTest")["rebuilt"], ["full"])
        self.assertEqual(self.prepared, 7)

    def test_interrupted_preparation_cannot_leave_a_hit(self):
        self.prepare("thc.UnknownTest")
        (self.root / "fixtures/alpha.hs").write_text("changed fixture source")
        self.fail_preparation = True
        with self.assertRaisesRegex(RuntimeError, "interrupted"):
            self.prepare("thc.UnknownTest")
        self.assertFalse((self.root / fast_fixtures.FULL_STAMP).exists())
        self.fail_preparation = False
        self.assertEqual(self.prepare("thc.UnknownTest")["rebuilt"], ["full"])
        self.assertEqual(self.prepared, 2)

    def test_unreviewed_preparer_command_or_new_output_never_gets_a_receipt(self):
        self.prepare("thc.UnknownTest")
        (self.root / "scripts/prepare-tests.sh").write_text("prepare reviewed fixtures\nmake-new-output\n")
        self.assertEqual(self.prepare("thc.UnknownTest")["rebuilt"], ["full"])
        self.assertFalse((self.root / fast_fixtures.FULL_STAMP).exists())
        (self.root / "scripts/prepare-tests.sh").write_text("prepare reviewed fixtures\n")
        self.extra_output = True
        self.assertEqual(self.prepare("thc.UnknownTest")["rebuilt"], ["full"])
        self.assertFalse((self.root / fast_fixtures.FULL_STAMP).exists())
        self.assertEqual(self.prepare("thc.UnknownTest")["rebuilt"], ["full"])
        self.assertEqual(self.prepared, 4)

    def test_vendored_source_bytes_and_presence_are_bound_to_receipt(self):
        name = "vendor/ghc-9.14.1/GHC/Internal/Base.hs"
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text("pinned source")
        self.vendor_pins.return_value = {name: hashlib.sha256(path.read_bytes()).hexdigest()}
        self.prepare("thc.UnknownTest")
        path.write_text("altered source")
        self.assertEqual(self.prepare("thc.UnknownTest")["rebuilt"], ["full"])
        self.assertFalse((self.root / fast_fixtures.FULL_STAMP).exists())
        path.write_text("pinned source")
        self.prepare("thc.UnknownTest")
        path.unlink()
        self.assertEqual(self.prepare("thc.UnknownTest")["rebuilt"], ["full"])

    def test_compiler_interface_link_is_ignored_but_fixture_link_is_rejected(self):
        self.prepare("thc.UnknownTest")
        target = self.root / "fixtures/alpha.hs"
        (self.root / "build/alpha/Imported.hi").symlink_to(target)
        self.assertEqual(self.prepare("thc.UnknownTest")["reused"], ["full"])
        (self.root / "build/alpha/linked.tsv").symlink_to(target)
        with self.assertRaisesRegex(RuntimeError, "Unexpected full fixture output"):
            fast_fixtures._full_output_hashes(self.root)


if __name__ == "__main__":
    unittest.main()
