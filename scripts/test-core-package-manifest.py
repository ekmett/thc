#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

"""Package manifests reject stale, mismatched and pre-Tidy Core artifacts."""

import hashlib
import json
from pathlib import Path
import subprocess
from tempfile import TemporaryDirectory
import unittest
import warnings
from zipfile import ZipFile

import core_package_manifest


class PackageManifestTest(unittest.TestCase):
    def setUp(self):
        self.scratch = TemporaryDirectory()
        self.addCleanup(self.scratch.cleanup)
        self.root = Path(self.scratch.name)
        self.boundary = core_package_manifest.BOUNDARY

    def unit(self, unit_id, name='Shared'):
        source = dict(schema=1, ghc='9.14.1', unit=unit_id, module=name,
                      boundary=self.boundary, bindings=[], constructors=[])
        path = self.root / f'{unit_id}.json'
        path.write_text(json.dumps(source) + '\n')
        return dict(id=unit_id, depends=[], modules=[dict(name=name, boundary=self.boundary,
                    path=path.name, sha256=hashlib.sha256(path.read_bytes()).hexdigest())])

    def manifest(self, units):
        path = self.root / 'packages.json'
        path.write_text(json.dumps(dict(format='thc-core-packages', schema=1,
                                        ghc='9.14.1', units=units)) + '\n')
        return path

    def bundled(self, unit, members=None, inner=None, build_inputs=None, input_hash=None):
        module = unit['modules'][0]
        data = (self.root / module['path']).read_bytes()
        module['path'] = 'core/' + module['name'] + '.json'
        contents = {module['path']: data} if members is None else members
        inner = inner or dict(format='thc-core-bundle', schema=1, unit=unit['id'],
                              buildKey='a' * 64, exportKey='b' * 64, modules=unit['modules'])
        if build_inputs is not None:
            input_bytes = json.dumps(build_inputs).encode()
            inner['buildInputs'] = dict(path='inplace-manifest.json',
                                        sha256=input_hash or hashlib.sha256(input_bytes).hexdigest())
        bundle = self.root / 'bundle.zip'
        with ZipFile(bundle, 'w') as archive:
            archive.writestr('manifest.json', json.dumps(inner))
            if build_inputs is not None:
                archive.writestr('inplace-manifest.json', input_bytes)
            for name, value in contents.items():
                archive.writestr(name, value)
        unit['bundle'] = dict(path=str(bundle), sha256=hashlib.sha256(bundle.read_bytes()).hexdigest())
        return self.manifest([unit])

    def test_same_module_name_in_distinct_units_is_unambiguous(self):
        path = self.manifest([self.unit('first'), self.unit('second')])
        self.assertEqual(['first', 'second'], [source['unit'] for _, source in core_package_manifest.load(path)])

    def test_hash_unit_boundary_and_duplicate_unit_must_match(self):
        unit = self.unit('first')
        path = self.manifest([unit])
        self.assertEqual(1, len(core_package_manifest.load(path)))
        with self.subTest('hash'):
            (self.root / 'first.json').write_text('{}')
            with self.assertRaisesRegex(ValueError, 'hash mismatch'):
                core_package_manifest.load(path)
        unit = self.unit('first')
        with self.subTest('unit'):
            source_path = self.root / 'first.json'
            source = json.loads(source_path.read_text())
            source['unit'] = 'spoofed'
            source_path.write_text(json.dumps(source))
            unit['modules'][0]['sha256'] = hashlib.sha256(source_path.read_bytes()).hexdigest()
            path = self.manifest([unit])
            with self.assertRaisesRegex(ValueError, 'unit/module/boundary mismatch'):
                core_package_manifest.load(path)
        unit = self.unit('first')
        with self.subTest('pre-Tidy'):
            unit['modules'][0]['boundary'] = 'optimized-Core-before-Tidy'
            path = self.manifest([unit])
            with self.assertRaisesRegex(ValueError, 'post-Tidy'):
                core_package_manifest.load(path)
        unit = self.unit('first')
        with self.subTest('foreign binding'):
            source_path = self.root / 'first.json'
            source = json.loads(source_path.read_text())
            source['bindings'] = [dict(id='second:Shared.spoofed')]
            source_path.write_text(json.dumps(source))
            unit['modules'][0]['sha256'] = hashlib.sha256(source_path.read_bytes()).hexdigest()
            with self.assertRaisesRegex(ValueError, 'foreign binding owner'):
                core_package_manifest.load(self.manifest([unit]))
        unit = self.unit('first')
        with self.subTest('duplicate'):
            path = self.manifest([unit, unit])
            with self.assertRaisesRegex(ValueError, 'duplicate unit'):
                core_package_manifest.load(path)

    def test_module_path_cannot_escape_manifest_directory(self):
        unit = self.unit('first')
        unit['modules'][0]['path'] = '../first.json'
        with self.assertRaisesRegex(ValueError, 'stay inside'):
            core_package_manifest.load(self.manifest([unit]))

    def test_bundle_modules_are_read_directly_and_audited_by_exact_unit(self):
        unit = self.unit('first')
        source = self.root / 'first.json'
        document = json.loads(source.read_text())
        document['bindings'] = [dict(id='first:Shared.entry', name='entry', lifted=True,
                                     arity=0, expr=['lit', 'int', '42'])]
        source.write_text(json.dumps(document))
        unit['modules'][0]['sha256'] = hashlib.sha256(source.read_bytes()).hexdigest()
        path = self.bundled(unit)
        source.unlink()  # A loose-file fallback would fail here.
        loaded = core_package_manifest.load(path)
        self.assertEqual(['first:Shared.entry'], [binding['id'] for _, module in loaded
                          for binding in module['bindings']])
        self.assertIn('bundle.zip!/core/Shared.json', loaded[0][0])
        command = ['python3', str(Path(__file__).with_name('audit-core.py')),
                   '--package-manifest', str(path), '--entry', 'first:Shared.entry']
        result = subprocess.run(command, text=True, capture_output=True, check=True)
        self.assertTrue(json.loads(result.stdout)['accepted'])

    def test_bundle_validates_optional_build_inputs_without_rebuilding_them(self):
        unit = self.unit('first')
        inputs = dict(format='thc-core-build-inputs', schema=1, unit='first',
                      buildKey='a' * 64, exportKey='b' * 64,
                      nativeArtifacts=[dict(path='native/lib.a', sha256='c' * 64)])
        path = self.bundled(unit, build_inputs=inputs)
        self.assertEqual(1, len(core_package_manifest.load(path)))
        unit = self.unit('first')
        with self.assertRaisesRegex(ValueError, 'build-inputs hash mismatch'):
            core_package_manifest.load(self.bundled(unit, build_inputs=inputs, input_hash='0' * 64))

    def test_bundle_hash_inventory_members_and_core_identity_fail_closed(self):
        unit = self.unit('first')
        path = self.bundled(unit)
        bundle = self.root / 'bundle.zip'
        bundle.write_bytes(bundle.read_bytes() + b'changed')
        with self.assertRaisesRegex(ValueError, 'bundle hash mismatch'):
            core_package_manifest.load(path)

        unit = self.unit('first')
        with self.assertRaisesRegex(ValueError, 'missing, or extra ZIP entry'):
            core_package_manifest.load(self.bundled(unit, members={}))
        unit = self.unit('first')
        with self.assertRaisesRegex(ValueError, 'missing, or extra ZIP entry'):
            core_package_manifest.load(self.bundled(unit, members={
                'core/Shared.json': (self.root / 'first.json').read_bytes(),
                '../escape': b'no extraction'}))

        unit = self.unit('first')
        inner = dict(format='thc-core-bundle', schema=1, unit='wrong',
                     buildKey='a' * 64, exportKey='b' * 64, modules=[dict(unit['modules'][0])])
        inner['modules'][0]['path'] = 'core/Shared.json'
        with self.assertRaisesRegex(ValueError, 'bundle manifest disagrees'):
            core_package_manifest.load(self.bundled(unit, inner=inner))

        unit = self.unit('first')
        self.bundled(unit)
        unit['modules'][0]['sha256'] = '0' * 64
        with self.assertRaisesRegex(ValueError, 'bundle manifest disagrees'):
            core_package_manifest.load(self.manifest([unit]))
        unit = self.unit('first')
        unit['modules'][0]['sha256'] = '0' * 64
        path = self.bundled(unit)
        with self.assertRaisesRegex(ValueError, 'content hash mismatch'):
            core_package_manifest.load(path)

    def test_bundle_rejects_duplicate_and_unsafe_member_names(self):
        unit = self.unit('first')
        path = self.bundled(unit)
        bundle = self.root / 'bundle.zip'
        with warnings.catch_warnings():
            warnings.simplefilter('ignore', UserWarning)
            with ZipFile(bundle, 'a') as archive:
                archive.writestr('core/Shared.json', b'duplicate')
        unit['bundle']['sha256'] = hashlib.sha256(bundle.read_bytes()).hexdigest()
        with self.assertRaisesRegex(ValueError, 'duplicate, unsafe'):
            core_package_manifest.load(self.manifest([unit]))
        for unsafe in ('../Shared.json', '/absolute.json', 'core/../Shared.json', 'core\\Shared.json'):
            with self.subTest(unsafe=unsafe):
                unit = self.unit('first')
                path = self.bundled(unit)
                unit['modules'][0]['path'] = unsafe
                with self.assertRaisesRegex(ValueError, 'unsafe ZIP member path'):
                    core_package_manifest.load(self.manifest([unit]))

    def test_bundle_rejects_corrupt_zip_and_wrong_core_unit(self):
        unit = self.unit('first')
        path = self.bundled(unit)
        bundle = self.root / 'bundle.zip'
        bundle.write_bytes(b'not a ZIP')
        unit['bundle']['sha256'] = hashlib.sha256(bundle.read_bytes()).hexdigest()
        with self.assertRaisesRegex(ValueError, 'invalid ZIP bundle'):
            core_package_manifest.load(self.manifest([unit]))

        unit = self.unit('first')
        source = self.root / 'first.json'
        document = json.loads(source.read_text())
        document['unit'] = 'another-unit'
        source.write_text(json.dumps(document))
        unit['modules'][0]['sha256'] = hashlib.sha256(source.read_bytes()).hexdigest()
        with self.assertRaisesRegex(ValueError, 'unit/module/boundary mismatch'):
            core_package_manifest.load(self.bundled(unit))

    def test_archive_only_foreign_core_reports_execution_gap_not_identity_mismatch(self):
        unit = self.unit('first')
        source = self.root / 'first.json'
        module = json.loads(source.read_text())
        module['schema'] = 2
        module['foreign'] = dict(schema=1, execution='not-linked',
                                 stubs=dict(header='', source='foreign stub', initializers=[], finalizers=[]),
                                 files=[])
        source.write_text(json.dumps(module))
        unit['modules'][0]['sha256'] = hashlib.sha256(source.read_bytes()).hexdigest()
        with self.assertRaisesRegex(ValueError, 'Unsupported foreign execution for first:Shared.*typed foreign registration.*callback'):
            core_package_manifest.load(self.bundled(unit))

        result = subprocess.run(['python3', str(Path(__file__).with_name('audit-core.py')),
                                 '--entry', 'first:Shared.entry', str(source)], text=True, capture_output=True)
        self.assertEqual(1, result.returncode)
        issues = json.loads(result.stdout)['issues']
        self.assertTrue(any(issue['code'] == 'module-format' and
                            'Unsupported foreign execution for first:Shared' in issue['detail']
                            for issue in issues), issues)

        module['unit'] = 'wrong'
        unit = self.unit('first')
        source.write_text(json.dumps(module))
        unit['modules'][0]['sha256'] = hashlib.sha256(source.read_bytes()).hexdigest()
        with self.assertRaisesRegex(ValueError, 'unit/module/boundary mismatch'):
            core_package_manifest.load(self.bundled(unit))

        module['unit'] = 'first'
        module['schema'] = 1  # Renumbering must not silently discard registration obligations.
        unit = self.unit('first')
        source.write_text(json.dumps(module))
        unit['modules'][0]['sha256'] = hashlib.sha256(source.read_bytes()).hexdigest()
        with self.assertRaisesRegex(ValueError, 'unsupported Core module schema/foreign metadata'):
            core_package_manifest.load(self.bundled(unit))

    def test_audit_only_foreign_bundle_never_accepts_and_still_checks_reachable_core(self):
        def archive(expression, foreign=None):
            unit = self.unit('first')
            source = self.root / 'first.json'
            module = json.loads(source.read_text())
            module['schema'] = 2
            module['foreign'] = foreign if foreign is not None else dict(
                schema=1, execution='not-linked',
                stubs=dict(header='', source='foreign stub', initializers=[], finalizers=[]), files=[])
            module['bindings'] = [dict(id='first:Shared.entry', name='entry', lifted=True,
                                       arity=0, expr=expression)]
            source.write_text(json.dumps(module))
            unit['modules'][0]['sha256'] = hashlib.sha256(source.read_bytes()).hexdigest()
            return self.bundled(unit)

        def audit(path):
            output = self.root / 'audit.json'
            result = subprocess.run(['python3', str(Path(__file__).with_name('audit-core.py')),
                                     '--package-manifest', str(path), '--entry', 'first:Shared.entry',
                                     '--output', str(output)], text=True, capture_output=True)
            self.assertEqual(1, result.returncode, result.stderr)
            return json.loads(output.read_text())

        safe = archive(['lit', 'int', '42'])
        with self.assertRaisesRegex(ValueError, 'Unsupported foreign execution'):
            core_package_manifest.load(safe)  # The normal loader remains strict.
        report = audit(safe)
        self.assertFalse(report['accepted'])
        self.assertEqual(['first:Shared.entry'], [item['id'] for item in report['reachableBindings']])
        self.assertEqual({'module-format'}, {issue['code'] for issue in report['issues']})
        self.assertIn('execution=not-linked', report['issues'][0]['detail'])

        unsupported = archive(['app', ['prim', 'unsupported#'], [['lit', 'int', '1']], [False]])
        report = audit(unsupported)
        self.assertFalse(report['accepted'])
        self.assertLessEqual({'module-format', 'unsupported-primitive'},
                             {issue['code'] for issue in report['issues']})
        self.assertEqual(['first:Shared.entry'], [item['id'] for item in report['reachableBindings']])

        malformed = dict(schema=1, execution='not-linked',
                         stubs=dict(header='', source='', initializers=[], finalizers=[]), files=[])
        with self.assertRaisesRegex(ValueError, 'malformed foreign archive'):
            core_package_manifest.load_for_audit(archive(['lit', 'int', '42'], malformed))

        for bad in (
                dict(schema=True, execution='not-linked', stubs=None,
                     files=[dict(language='c', source='int f;', extension='c')]),
                dict(schema=1, execution='not-linked', stubs=None,
                     files=[dict(language='', source='int f;', extension='c')]),
                dict(schema=1, execution='not-linked',
                     stubs=dict(header='', source='', initializers=[dict(
                         isInitializer=False, unit='first', module='Shared', name='init')],
                         finalizers=[]), files=[])):
            with self.subTest(foreign=bad), self.assertRaises(ValueError):
                core_package_manifest.load_for_audit(archive(['lit', 'int', '42'], bad))

        valid = archive(['lit', 'int', '42'])
        unit = json.loads(valid.read_text())['units'][0]
        unit['bundle']['sha256'] = '0' * 64
        with self.assertRaisesRegex(ValueError, 'bundle hash mismatch'):
            core_package_manifest.load_for_audit(self.manifest([unit]))

    def test_audit_keeps_strict_missing_global_closure_for_bundle(self):
        unit = self.unit('first')
        source = self.root / 'first.json'
        document = json.loads(source.read_text())
        document['bindings'] = [dict(id='first:Shared.entry', name='entry', lifted=True,
                                     arity=0, expr=['var', 'second:Other.absent'])]
        source.write_text(json.dumps(document))
        unit['modules'][0]['sha256'] = hashlib.sha256(source.read_bytes()).hexdigest()
        path = self.bundled(unit)
        command = ['python3', str(Path(__file__).with_name('audit-core.py')),
                   '--package-manifest', str(path), '--entry', 'first:Shared.entry']
        result = subprocess.run(command, text=True, capture_output=True)
        self.assertEqual(1, result.returncode)
        report = json.loads(result.stdout)
        self.assertFalse(report['accepted'])
        self.assertEqual(1, report['summary']['missingGlobals'])
        self.assertEqual('second:Other.absent', report['missingGlobals'][0]['id'])


if __name__ == '__main__':
    unittest.main()
