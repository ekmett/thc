#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

"""Package manifests reject stale, mismatched and pre-Tidy Core artifacts."""

import hashlib
import json
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest

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


if __name__ == '__main__':
    unittest.main()
