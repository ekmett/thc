#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

"""Safe repeatable MD5 preparation; synthetic files only, no native/compiler calls."""
import importlib.util
import hashlib
import json
from pathlib import Path
import shlex
import tempfile
import unittest

SCRIPT = Path(__file__).with_name('prepare-managed-md5.py')
spec = importlib.util.spec_from_file_location('prepare_managed_md5', SCRIPT)
prepare = importlib.util.module_from_spec(spec)
spec.loader.exec_module(prepare)


class PrepareManagedMd5Test(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='thc-md5-prepare-test-')
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name).resolve() / 'repo'
        self.root.mkdir()
        self.output = self.root / 'build' / 'managed-md5-native'

    def archive(self):
        return prepare.prepare_output(self.root, self.output)

    def prior(self, files):
        self.output.mkdir(parents=True)
        for name, content in files.items():
            (self.output / name).write_bytes(content)

    def assert_files(self, directory, expected):
        self.assertEqual(set(expected), {p.name for p in directory.iterdir()})
        for name, content in expected.items():
            self.assertEqual(content, (directory / name).read_bytes(), name)

    def test_first_attempt_creates_only_the_canonical_output(self):
        output, archive = self.archive()
        self.assertEqual(self.output, output)
        self.assertIsNone(archive)
        self.assertEqual(prepare.OWNER, json.loads((output / prepare.OWNER_NAME).read_text()))
        self.assertEqual([prepare.OWNER_NAME], [p.name for p in output.iterdir()])
        self.assertEqual([self.output], list(output.parent.iterdir()))

    def test_success_then_partial_attempts_are_archived_without_changing_any_bytes(self):
        original = {name: ('original:' + name).encode() for name in prepare.ATTEMPT_FILES}
        original[prepare.OWNER_NAME] = json.dumps(prepare.OWNER, sort_keys=True).encode() + b'\n'
        self.assertEqual(19, len(original))
        self.prior(original)
        output, first = self.archive()
        self.assertEqual(self.output.parent, first.parent.parent)
        self.assert_files(first, original)
        marker = {prepare.OWNER_NAME: original[prepare.OWNER_NAME]}
        self.assert_files(output, marker)
        partial = {'compile.command.txt': b'command\n', 'compile.stderr': b'first failure\n',
                   'compile.exit-status.txt': b'1\n', **marker}
        for name, content in partial.items():
            (output / name).write_bytes(content)
        output, second = self.archive()
        self.assertNotEqual(first, second)
        self.assert_files(first, original)
        self.assert_files(second, partial)
        self.assert_files(output, marker)

    def test_only_marked_empty_partial_attempt_can_be_archived(self):
        self.prior({})
        with self.assertRaisesRegex(ValueError, 'unmarked'):
            self.archive()
        marker = {prepare.OWNER_NAME: json.dumps(prepare.OWNER, sort_keys=True).encode() + b'\n'}
        (self.output / prepare.OWNER_NAME).write_bytes(marker[prepare.OWNER_NAME])
        output, archived = self.archive()
        self.assert_files(output, marker)
        self.assert_files(archived, marker)

    def test_legacy_success_requires_exact_provenance_and_all_artifact_hashes(self):
        files = {name: ('legacy:' + name).encode() for name in prepare.LEGACY_FILES - {'provenance.json'}}
        files['native.command.txt'] = (shlex.join([str(self.output / 'managed-md5-native')]) + '\n').encode()
        provenance = dict(schema=1, contextSize=88, contextOffsets=[0, 16, 24], contextAlignment=4,
                          independentModelMatched=True,
                          referenceGitBlobs={'md5.c': '4fa83bda7aacc8a1656d7e2d78251bbe70a04b56',
                                             'md5.h': 'a87296687a2f3dc6748264ff2a8a0c919518db55'},
                          artifacts=[dict(path='build/managed-md5-native/' + name,
                                          sha256=hashlib.sha256(content).hexdigest()) for name, content in files.items()])
        files['provenance.json'] = json.dumps(provenance).encode()
        self.prior(files)
        (self.output / 'native.stdout').write_bytes(b'changed')
        with self.assertRaisesRegex(ValueError, 'ownership/hash mismatch'):
            self.archive()
        self.assertEqual([self.output], list(self.output.parent.iterdir()))
        (self.output / 'native.stdout').write_bytes(files['native.stdout'])
        _, archived = self.archive()
        self.assert_files(archived, files)

    def test_malformed_or_wrong_owner_marker_is_never_moved(self):
        self.prior({})
        for marker in (b'{', b'{}', json.dumps(dict(prepare.OWNER, tool='other')).encode()):
            (self.output / prepare.OWNER_NAME).write_bytes(marker)
            with self.assertRaises(ValueError):
                self.archive()
            self.assertEqual(marker, (self.output / prepare.OWNER_NAME).read_bytes())
            self.assertEqual([self.output], list(self.output.parent.iterdir()))

    def test_unknown_file_is_not_moved_or_overwritten(self):
        original = {'provenance.json': b'known', 'unrelated.txt': b'user evidence'}
        self.prior(original)
        with self.assertRaisesRegex(ValueError, 'ambiguous'):
            self.archive()
        self.assert_files(self.output, original)
        self.assertEqual([self.output], list(self.output.parent.iterdir()))

    def test_nested_directory_even_with_known_filename_is_rejected(self):
        self.prior({'compile.stderr': b'failure'})
        nested = self.output / 'provenance.json'
        nested.mkdir()
        (nested / 'keep').write_bytes(b'user data')
        with self.assertRaisesRegex(ValueError, 'ambiguous'):
            self.archive()
        self.assertEqual(b'user data', (nested / 'keep').read_bytes())
        self.assertEqual(b'failure', (self.output / 'compile.stderr').read_bytes())
        self.assertEqual([self.output], list(self.output.parent.iterdir()))

    def test_known_name_symlinks_including_dangling_are_rejected(self):
        self.prior({})
        existing = self.root / 'outside'
        existing.write_bytes(b'untouched')
        for target in (existing, self.root / 'missing'):
            link = self.output / 'native.stdout'
            link.symlink_to(target)
            with self.assertRaisesRegex(ValueError, 'ambiguous'):
                self.archive()
            self.assertTrue(link.is_symlink())
            self.assertEqual(b'untouched', existing.read_bytes())
            self.assertEqual([self.output], list(self.output.parent.iterdir()))
            link.unlink()  # Only the synthetic link created by this test.

    def test_output_file_or_symlink_is_never_replaced(self):
        self.output.parent.mkdir()
        self.output.write_bytes(b'not a tool directory')
        with self.assertRaisesRegex(ValueError, 'real directory'):
            self.archive()
        self.assertEqual(b'not a tool directory', self.output.read_bytes())
        self.output.unlink()  # Only the synthetic file created by this test.
        outside = self.root / 'outside'
        outside.mkdir()
        (outside / 'provenance.json').write_bytes(b'not this attempt')
        self.output.symlink_to(outside, target_is_directory=True)
        with self.assertRaisesRegex(ValueError, 'real directory'):
            self.archive()
        self.assertTrue(self.output.is_symlink())
        self.assertEqual(b'not this attempt', (outside / 'provenance.json').read_bytes())

    def test_symlinked_build_directory_and_broad_or_custom_targets_reject(self):
        for target in (Path('/'), self.root, self.root / 'build', self.root / 'other',
                       self.root / 'build' / '..' / 'build' / 'managed-md5-native',
                       Path('build/managed-md5-native')):
            with self.assertRaisesRegex(ValueError, 'canonical'):
                prepare.prepare_output(self.root, target)
            self.assertEqual([], list(self.root.iterdir()))
        outside = Path(self.temporary.name) / 'outside'
        outside.mkdir()
        (self.root / 'build').symlink_to(outside, target_is_directory=True)
        with self.assertRaisesRegex(ValueError, 'real directory'):
            self.archive()
        self.assertEqual([], list(outside.iterdir()))


if __name__ == '__main__':
    unittest.main()
