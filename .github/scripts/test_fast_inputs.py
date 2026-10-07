# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

"""Adversarial fixture-bundle tests; no installed GHC or JVM needed."""
import copy
import importlib.util
import io
import json
import os
from pathlib import Path
import subprocess
import tarfile
import tempfile
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location("fast_inputs", Path(__file__).with_name("fast_inputs.py"))
cache = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(cache)
DECLARED_REQUIRED = cache.REQUIRED


class FastInputTests(unittest.TestCase):
    def test_native_oracle_reuses_baseline_and_invalidates_its_actual_inputs(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve()
            for name in ("ghc", "ghc-pkg", "boot/settings", "boot/package.cache", "source.hs"):
                path = root / name; path.parent.mkdir(parents=True, exist_ok=True); path.write_text(name)
            calls = []
            def query(argv, _root):
                if argv[-1] == "--numeric-version": return "9.14.1"
                if argv[-1] in ("--print-libdir", "--print-global-package-db"): return str(root / "boot")
                return "pinned compiler and packages"
            def produce(argv, **kwargs):
                calls.append(argv[-1])
                (root / "oracle.tsv").write_text((root / "source.hs").read_text())
            with patch.object(cache, "command", side_effect=query), \
                 patch.object(cache.shutil, "which", side_effect=lambda name: str(root / Path(name).name)), \
                 patch.object(cache.subprocess, "run", side_effect=produce), \
                 patch.dict(os.environ, {"GHC": "ghc", "GHC_PKG": "ghc-pkg"}):
                def run(): cache.native_oracle(root, root / "producer", "integer", ["source.hs"], ["oracle.tsv"], root / "cache")
                run()
                (root / "oracle.tsv").unlink()
                (root / "exporter.hs").write_text("unrelated THC change")
                run()
                self.assertEqual(["native", "oracle"], calls)
                self.assertEqual("source.hs", (root / "oracle.tsv").read_text())
                (root / "source.hs").write_text("changed fixture")
                run()
                self.assertEqual(4, len(calls))
                (root / "boot/package.cache").write_text("changed boot libraries")
                run()
                self.assertEqual(6, len(calls))
                for archive in (root / "cache/v1").glob("*.tar.gz"): archive.write_bytes(b"broken")
                run()
                self.assertEqual(8, len(calls))

    def test_numeric_oracles_keep_native_cache_permissions_on_windows(self):
        for family, executable in (('bit-primops', 'bit-primops-oracle'),
                                   ('signed-narrow-primops', 'signed-narrow-primops-oracle'),
                                   ('explicit64-primops', 'explicit64-oracle')):
            oracle = f'build/{family}/native/{executable}'
            for suffix in ('', '.exe'):
                path = oracle + suffix
                self.assertTrue(cache.allowed_payload(path), path)
                self.assertEqual(0o755, cache.safe_mode(0o755, path))
            for path in (oracle + '.exe.exe', f'build/{family}/native/unowned.exe'):
                self.assertFalse(cache.allowed_payload(path), path)
                with self.assertRaises(cache.CacheMiss):
                    cache.safe_mode(0o755, path)

    def test_simd_audit_cbd_and_native_paths_are_closed(self):
        for family, module in (("simd", "SimdInt64X2"), ("simd-int32x4", "SimdInt32X4")):
            self.assertIn(f"build/{family}/provenance.json", DECLARED_REQUIRED)
            for stage in ("pre", "post"):
                self.assertTrue(cache.allowed_payload(f"build/{family}/{stage}-core/{module}.cbd"))
                self.assertFalse(cache.allowed_payload(f"build/{family}/{stage}-core/Other.cbd"))
            self.assertFalse(cache.allowed_payload(f"build/{family}/unreviewed/{module}.cbd"))
            native = f"build/{family}/native/simd"
            self.assertTrue(cache.allowed_payload(native))
            self.assertTrue(cache.native_executable(native))
            self.assertEqual(0o755, cache.safe_mode(0o755, native))
            self.assertFalse(cache.allowed_payload(native + "-unknown"))

    def test_weak_runtime_inventory_follows_declared_cbd_owners(self):
        root = Path('/fixture-workspace')
        folder = 'build/weak-explicit/runtime-support'
        unit = 'thc-0.1.0.0-runtime-inplace'
        modules = ['THC.Internal.Exception', 'THC.Internal.Weak']
        refs = [{'name': name, 'compact': {'path': str(root / folder / 'modules' / unit / (name + '.cbd')),
                  'sha256': 'a' * 64, 'format': 'thc-cbd-v1'}} for name in modules]
        artifacts = {name: 'a' * 64 for name in cache.WEAK_RUNTIME_OUTPUTS - {folder + '/manifest.json'}}
        artifacts.update({str(Path(ref['compact']['path']).relative_to(root)): 'a' * 64 for ref in refs})
        receipt = dict(schema=1, ghc='9.14.1', runtimeUnit=unit,
                       packages=dict(format='thc-core-packages', schema=1, ghc='9.14.1',
                                     foreignExceptionBridgeUnit=unit, units=[dict(id=unit, depends=[], modules=refs)]),
                       artifactHashes=artifacts)
        self.assertEqual(artifacts, cache.weak_runtime_artifact_hashes(receipt, root))
        for name in artifacts:
            self.assertTrue(cache.allowed_payload(name), name)
        for changes in (dict(runtimeUnit='wrong-owner'), dict(artifactHashes={}),
                        dict(artifactHashes=dict(artifacts, **{folder + '/modules/' + unit + '/Cold.cbd': 'a' * 64}))):
            with self.assertRaises(cache.CacheMiss):
                cache.weak_runtime_artifact_hashes(dict(receipt, **changes), root)
        for product in cache.WEAK_RUNTIME_OUTPUTS - {folder + '/manifest.json'}:
            incomplete = dict(artifacts)
            del incomplete[product]
            with self.assertRaises(cache.CacheMiss, msg=product):
                cache.weak_runtime_artifact_hashes(dict(receipt, artifactHashes=incomplete), root)
        forged = copy.deepcopy(receipt)
        forged['packages']['units'][0]['modules'][0]['compact']['path'] = str(root / 'build/discovered/THC.Internal.Exception.cbd')
        with self.assertRaises(cache.CacheMiss):
            cache.weak_runtime_artifact_hashes(forged, root)
        duplicated = copy.deepcopy(receipt)
        duplicated['packages']['units'][0]['modules'].append(refs[0])
        with self.assertRaises(cache.CacheMiss):
            cache.weak_runtime_artifact_hashes(duplicated, root)
        for name in (folder + '/cache/warmed.cbd', folder + '/modules/' + unit + '/Cold.json'):
            self.assertFalse(cache.allowed_payload(name), name)
        with self.assertRaises(cache.CacheMiss):
            cache.allowed_payload(folder + '/modules/../Cold.cbd')

    def test_publication_receipt_keeps_zip_members_inside_the_hashed_source(self):
        receipt = {"source": {"path": "build/interface-core/installed/bundles/unit.zip", "sha256": "a" * 64},
                   "modules": [{"path": "core/Original.cbd", "sha256": "b" * 64}],
                   "unit": {"modules": [{"compact": {"path": "build/interface-core/installed/unit-core/v3/" + "a" * 64 + "/0.cbd", "sha256": "b" * 64}}]},
                   "sizes": [123]}
        self.assertEqual([(receipt["source"]["path"], "a" * 64),
                          (receipt["unit"]["modules"][0]["compact"]["path"], "b" * 64)], list(cache.hashes_in(receipt, {})))

    def test_heap_exception_corpus_cbd_paths_are_closed(self):
        self.assertIn("build/managed-mvars/manifest.json", DECLARED_REQUIRED)
        for name in cache.MANAGED_MVAR_OUTPUTS | cache.SYNCHRONOUS_EXCEPTION_OUTPUTS | cache.HEAP_CORPUS_CBD_OUTPUTS:
            self.assertTrue(cache.allowed_payload(name), name)
        for family, folder in (("managed-mvars", "pre/core"), ("synchronous-exceptions", "post/core"),
                               ("addr-identity", "pre-core"), ("corpus", "groups/lists/core")):
            self.assertFalse(cache.allowed_payload(f"build/{family}/{folder}/Other.cbd"))
            self.assertFalse(cache.allowed_payload(f"build/{family}/unreviewed/core/THC.InterfaceClosure.cbd"))
        for name in cache.MANAGED_MVAR_OUTPUTS | cache.HEAP_CORPUS_CBD_OUTPUTS:
            if name.endswith(".cbd") and ("/managed-mvars/" in name or "/corpus/" in name):
                self.assertFalse(cache.allowed_payload(name[:-4] + ".json"), name)
        self.assertFalse(cache.allowed_payload("build/managed-mvars/logs/unknown.stdout"))
        self.assertFalse(cache.allowed_payload("build/synchronous-exceptions/logs/unknown.stdout"))

    def test_original_path_stat_closed_receipt(self):
        name = 'build/original-path-stat/manifest.json'
        outputs = cache.ORIGINAL_PATH_STAT_OUTPUTS
        self.assertEqual(43, len(outputs))
        self.assertEqual(cache.platform.system() == 'Linux', name in DECLARED_REQUIRED)
        artifacts = {path: 'a' * 64 for path in outputs - {name}}
        good = dict(schema=1, ghc='9.14.1', unixUnit='unix-2.8.8.0-inplace',
                    entries=['pathStat', 'pathLstat', 'unixPathLstat'], installedArtifactsHashed=False,
                    artifactHashes=artifacts)
        self.assertEqual(artifacts, cache.original_path_stat_artifact_hashes(good))
        for path in outputs:
            self.assertTrue(cache.allowed_payload(path), path)
        for changes in (dict(schema=True), dict(ghc='9.14.0'), dict(unixUnit='unix-2.8.8.0-ABC'),
                        dict(entries=['pathStat', 'pathLstat']), dict(installedArtifactsHashed=True),
                        dict(artifactHashes={}), dict(artifactHashes=dict(artifacts, unknown='a' * 64)),
                        dict(artifactHashes={**artifacts, 'build/original-path-stat/pre.cbd': 'bad'})):
            with self.assertRaises(cache.CacheMiss):
                cache.original_path_stat_artifact_hashes(dict(good, **changes))
        for path in artifacts:
            with self.assertRaises(cache.CacheMiss):
                cache.original_path_stat_artifact_hashes(dict(good,
                    artifactHashes={key: value for key, value in artifacts.items() if key != path}))
        for suffix in ('native-paths/file', 'native-paths/link', 'ghc/OriginalPathStatAudit.o', 'unknown.json'):
            self.assertFalse(cache.allowed_payload('build/original-path-stat/' + suffix))

    def test_original_path_mode_closed_receipt(self):
        name = 'build/original-path-mode/manifest.json'
        outputs = cache.ORIGINAL_PATH_MODE_OUTPUTS
        self.assertEqual(35, len(outputs))
        self.assertEqual(cache.platform.system() == 'Linux', name in DECLARED_REQUIRED)
        artifacts = {path: 'a' * 64 for path in outputs - {name}}
        good = dict(schema=1, ghc='9.14.1', unixUnit='unix-2.8.8.0-inplace',
                    entries=['pathMkdir', 'pathChmod'], installedArtifactsHashed=False,
                    artifactHashes=artifacts)
        self.assertEqual(artifacts, cache.original_path_mode_artifact_hashes(good))
        for path in outputs:
            self.assertTrue(cache.allowed_payload(path), path)
        for changes in (dict(schema=True), dict(ghc='9.14.0'), dict(unixUnit='unix-2.8.8.0-ABC'),
                        dict(entries=['pathMkdir']), dict(installedArtifactsHashed=True),
                        dict(artifactHashes={}), dict(artifactHashes=dict(artifacts, unknown='a' * 64)),
                        dict(artifactHashes={**artifacts, 'build/original-path-mode/pre.cbd': 'bad'})):
            with self.assertRaises(cache.CacheMiss):
                cache.original_path_mode_artifact_hashes(dict(good, **changes))
        for path in artifacts:
            with self.assertRaises(cache.CacheMiss):
                cache.original_path_mode_artifact_hashes(dict(good,
                    artifactHashes={key: value for key, value in artifacts.items() if key != path}))
        for suffix in ('native-paths/file', 'native-paths/link', 'ghc/OriginalPathModeAudit.o', 'unknown.json'):
            self.assertFalse(cache.allowed_payload('build/original-path-mode/' + suffix))

    def test_original_path_link_closed_receipt(self):
        name = 'build/original-path-link/manifest.json'
        outputs = cache.ORIGINAL_PATH_LINK_OUTPUTS
        self.assertEqual(43, len(outputs))
        self.assertEqual(cache.platform.system() == 'Linux', name in DECLARED_REQUIRED)
        artifacts = {path: 'a' * 64 for path in outputs - {name}}
        good = dict(schema=1, ghc='9.14.1', unixUnit='unix-2.8.8.0-inplace',
                    entries=['pathSymlink', 'pathReadlink', 'pathRename'], installedArtifactsHashed=False,
                    artifactHashes=artifacts)
        self.assertEqual(artifacts, cache.original_path_link_artifact_hashes(good))
        for path in outputs:
            self.assertTrue(cache.allowed_payload(path), path)
        for changes in (dict(schema=True), dict(ghc='9.14.0'), dict(unixUnit='unix-2.8.8.0-ABC'),
                        dict(entries=['pathSymlink']), dict(entries=['pathSymlink', 'pathReadlink']),
                        dict(installedArtifactsHashed=True),
                        dict(artifactHashes={}), dict(artifactHashes=dict(artifacts, unknown='a' * 64)),
                        dict(artifactHashes={**artifacts, 'build/original-path-link/pre.cbd': 'bad'})):
            with self.assertRaises(cache.CacheMiss):
                cache.original_path_link_artifact_hashes(dict(good, **changes))
        for path in artifacts:
            with self.assertRaises(cache.CacheMiss):
                cache.original_path_link_artifact_hashes(dict(good,
                    artifactHashes={key: value for key, value in artifacts.items() if key != path}))
        for suffix in ('native-paths/file', 'native-paths/link', 'ghc/OriginalPathLinkAudit.o', 'unknown.json'):
            self.assertFalse(cache.allowed_payload('build/original-path-link/' + suffix))

    def test_original_directory_paths_closed_receipt(self):
        name = 'build/original-directory-paths/manifest.json'
        outputs = cache.ORIGINAL_DIRECTORY_PATHS_OUTPUTS
        self.assertEqual(35, len(outputs))
        self.assertEqual(cache.platform.system() == 'Linux', name in DECLARED_REQUIRED)
        artifacts = {path: 'a' * 64 for path in outputs - {name}}
        good = dict(schema=1, ghc='9.14.1', unixUnit='unix-2.8.8.0-inplace',
                    entries=['pathRemoveDirectory', 'executableReadlink'], installedArtifactsHashed=False,
                    readlinkUnit="ghc-internal", artifactHashes=artifacts)
        self.assertEqual(artifacts, cache.original_directory_paths_artifact_hashes(good))
        for path in outputs:
            self.assertTrue(cache.allowed_payload(path), path)
        for changes in (dict(schema=True), dict(ghc='9.14.0'), dict(unixUnit='unix-2.8.8.0-ABC'),
                        dict(entries=['pathRemoveDirectory']), dict(installedArtifactsHashed=True), dict(readlinkUnit="main"),
                        dict(artifactHashes={}), dict(artifactHashes=dict(artifacts, unknown='a' * 64)),
                        dict(artifactHashes={**artifacts, 'build/original-directory-paths/pre.cbd': 'bad'})):
            with self.assertRaises(cache.CacheMiss):
                cache.original_directory_paths_artifact_hashes(dict(good, **changes))
        for path in artifacts:
            with self.assertRaises(cache.CacheMiss):
                cache.original_directory_paths_artifact_hashes(dict(good,
                    artifactHashes={key: value for key, value in artifacts.items() if key != path}))
        for suffix in ('native-paths/file', 'native-paths/link', 'ghc/OriginalDirectoryPathsAudit.o', 'unknown.json'):
            self.assertFalse(cache.allowed_payload('build/original-directory-paths/' + suffix))

    def test_original_path_access_closed_receipt(self):
        name = 'build/original-path-access/manifest.json'
        outputs = cache.ORIGINAL_PATH_ACCESS_OUTPUTS
        self.assertEqual(27, len(outputs))
        self.assertEqual(cache.platform.system() == 'Linux', name in DECLARED_REQUIRED)
        artifacts = {path: 'a' * 64 for path in outputs - {name}}
        good = dict(schema=1, ghc='9.14.1', unixUnit='unix-2.8.8.0-inplace',
                    entries=['pathAccess'], installedArtifactsHashed=False,
                    artifactHashes=artifacts)
        self.assertEqual(artifacts, cache.original_path_access_artifact_hashes(good))
        for path in outputs:
            self.assertTrue(cache.allowed_payload(path), path)
        for changes in (dict(schema=True), dict(ghc='9.14.0'), dict(unixUnit='unix-2.8.8.0-ABC'),
                        dict(entries=[]), dict(installedArtifactsHashed=True),
                        dict(artifactHashes={}), dict(artifactHashes=dict(artifacts, unknown='a' * 64)),
                        dict(artifactHashes={**artifacts, 'build/original-path-access/pre.cbd': 'bad'})):
            with self.assertRaises(cache.CacheMiss):
                cache.original_path_access_artifact_hashes(dict(good, **changes))
        for path in artifacts:
            with self.assertRaises(cache.CacheMiss):
                cache.original_path_access_artifact_hashes(dict(good,
                    artifactHashes={key: value for key, value in artifacts.items() if key != path}))
        for suffix in ('native-paths/file', 'native-paths/link', 'ghc/OriginalPathAccessAudit.o', 'unknown.json'):
            self.assertFalse(cache.allowed_payload('build/original-path-access/' + suffix))

    def test_original_unlinkat_closed_receipt(self):
        name = 'build/original-unlinkat/manifest.json'
        outputs = cache.ORIGINAL_UNLINKAT_OUTPUTS
        self.assertEqual(33, len(outputs))
        self.assertEqual(cache.platform.system() == 'Linux', name in DECLARED_REQUIRED)
        artifacts = {path: 'a' * 64 for path in outputs - {name}}
        good = dict(schema=1, ghc='9.14.1', directoryUnit='directory-1.3.10.0-inplace',
                    entries=['pathUnlinkAt'], installedArtifactsHashed=False,
                    artifactHashes=artifacts)
        self.assertEqual(artifacts, cache.original_unlinkat_artifact_hashes(good))
        for unit in ('directory-1.3.10.0-inplace', 'directory-1.3.10.0-02fc', 'directory-1.3.10.0-deadbeef'):
            self.assertEqual(artifacts, cache.original_unlinkat_artifact_hashes(dict(good, directoryUnit=unit)))
        for unit in (None, 42, 'directory-1.3.10.0', 'directory-1.3.10.0-',
                     'directory-1.3.10.0-ABCD', 'directory-1.3.10.0-xyz',
                     'directory-1.3.9.0-02fc', 'unix-1.3.10.0-02fc',
                     'directory-1.3.10.0-inplace\n', 'directory-1.3.10.0-02fc:forged'):
            with self.assertRaises(cache.CacheMiss, msg=unit):
                cache.original_unlinkat_artifact_hashes(dict(good, directoryUnit=unit))
        for path in outputs:
            self.assertTrue(cache.allowed_payload(path), path)
        for changes in (dict(schema=True), dict(ghc='9.14.0'), dict(directoryUnit='directory-1.3.10.0-ABC'),
                        dict(entries=[]), dict(installedArtifactsHashed=True),
                        dict(artifactHashes={}), dict(artifactHashes=dict(artifacts, unknown='a' * 64)),
                        dict(artifactHashes={**artifacts, 'build/original-unlinkat/pre.cbd': 'bad'})):
            with self.assertRaises(cache.CacheMiss):
                cache.original_unlinkat_artifact_hashes(dict(good, **changes))
        for path in artifacts:
            with self.assertRaises(cache.CacheMiss):
                cache.original_unlinkat_artifact_hashes(dict(good,
                    artifactHashes={key: value for key, value in artifacts.items() if key != path}))
        for suffix in ('native-paths/file', 'native-paths/link', 'ghc/OriginalUnlinkAtAudit.o', 'ghc/abi-probe', 'native/oracle', 'unknown.json'):
            self.assertFalse(cache.allowed_payload('build/original-unlinkat/' + suffix))

    def test_original_fstatat_closed_receipt(self):
        name = 'build/original-fstatat/manifest.json'
        outputs = cache.ORIGINAL_FSTATAT_OUTPUTS
        self.assertEqual(33, len(outputs))
        self.assertEqual(cache.platform.system() == 'Linux', name in DECLARED_REQUIRED)
        artifacts = {path: 'a' * 64 for path in outputs - {name}}
        good = dict(schema=1, ghc='9.14.1', directoryUnit='directory-1.3.10.0-inplace',
                    entries=['pathFstatAt'], installedArtifactsHashed=False,
                    artifactHashes=artifacts)
        self.assertEqual(artifacts, cache.original_fstatat_artifact_hashes(good))
        for unit in ('directory-1.3.10.0-inplace', 'directory-1.3.10.0-02fc', 'directory-1.3.10.0-deadbeef'):
            self.assertEqual(artifacts, cache.original_fstatat_artifact_hashes(dict(good, directoryUnit=unit)))
        for unit in (None, 42, 'directory-1.3.10.0', 'directory-1.3.10.0-',
                     'directory-1.3.10.0-ABCD', 'directory-1.3.10.0-xyz',
                     'directory-1.3.9.0-02fc', 'unix-1.3.10.0-02fc',
                     'directory-1.3.10.0-inplace\n', 'directory-1.3.10.0-02fc:forged'):
            with self.assertRaises(cache.CacheMiss, msg=unit):
                cache.original_fstatat_artifact_hashes(dict(good, directoryUnit=unit))
        for path in outputs:
            self.assertTrue(cache.allowed_payload(path), path)
        for changes in (dict(schema=True), dict(ghc='9.14.0'), dict(directoryUnit='directory-1.3.10.0-ABC'),
                        dict(entries=[]), dict(installedArtifactsHashed=True),
                        dict(artifactHashes={}), dict(artifactHashes=dict(artifacts, unknown='a' * 64)),
                        dict(artifactHashes={**artifacts, 'build/original-fstatat/pre.cbd': 'bad'})):
            with self.assertRaises(cache.CacheMiss):
                cache.original_fstatat_artifact_hashes(dict(good, **changes))
        for path in artifacts:
            with self.assertRaises(cache.CacheMiss):
                cache.original_fstatat_artifact_hashes(dict(good,
                    artifactHashes={key: value for key, value in artifacts.items() if key != path}))
        for suffix in ('native-paths/file', 'native-paths/link', 'ghc/OriginalFstatAtAudit.o', 'ghc/abi-probe', 'native/oracle', 'unknown.json'):
            self.assertFalse(cache.allowed_payload('build/original-fstatat/' + suffix))

    def test_original_current_directory_closed_receipt(self):
        name = 'build/original-current-directory/manifest.json'
        outputs = cache.ORIGINAL_CURRENT_DIRECTORY_OUTPUTS
        self.assertEqual(46, len(outputs))
        self.assertEqual(cache.platform.system() == 'Linux', name in DECLARED_REQUIRED)
        artifacts = {path: 'a' * 64 for path in outputs - {name}}
        good = dict(schema=1, ghc='9.14.1', unixUnit='unix-2.8.8.0-inplace',
                    entries=['pathChdir', 'pathGetCwd'], installedArtifactsHashed=False,
                    nativeIsolatedChild=True, coordinatorCwdUnchanged=True, privateRebuiltUnix=True, unixSourceReceipt='build/original-current-directory/unix-source.json',
                    unixArchiveSha256='a128dea3bfeb731a562f22d376fa606e902154d95321363f7ec1ea6b787a5a3e', artifactHashes=artifacts)
        self.assertEqual(artifacts, cache.original_current_directory_artifact_hashes(good))
        for unit in ('unix-2.8.8.0-inplace', 'unix-2.8.8.0-02fc', 'unix-2.8.8.0-deadbeef'):
            self.assertEqual(artifacts, cache.original_current_directory_artifact_hashes(dict(good, unixUnit=unit)))
        for unit in (None, 42, 'unix-2.8.8.0', 'unix-2.8.8.0-',
                     'unix-2.8.8.0-ABCD', 'unix-2.8.8.0-xyz',
                     'unix-2.8.7.0-02fc', 'directory-2.8.8.0-02fc',
                     'unix-2.8.8.0-inplace\n', 'unix-2.8.8.0-02fc:forged'):
            with self.assertRaises(cache.CacheMiss, msg=unit):
                cache.original_current_directory_artifact_hashes(dict(good, unixUnit=unit))
        for label in ('pre-audit-pathChdir', 'post-audit-pathGetCwd', 'native-child', 'unix-extract', 'unix-build'):
            self.assertIn('build/original-current-directory/logs/' + label + '.command.json', outputs)
        for path in outputs:
            self.assertTrue(cache.allowed_payload(path), path)
        for changes in (dict(schema=True), dict(ghc='9.14.0'), dict(unixUnit='unix-2.8.8.0-ABC'),
                        dict(entries=[]), dict(installedArtifactsHashed=True),
                        dict(nativeIsolatedChild=False), dict(coordinatorCwdUnchanged=False),
                        dict(privateRebuiltUnix=False), dict(unixSourceReceipt="wrong"), dict(unixArchiveSha256="a" * 64),
                        dict(artifactHashes={}), dict(artifactHashes=dict(artifacts, unknown='a' * 64)),
                        dict(artifactHashes={**artifacts, 'build/original-current-directory/pre.cbd': 'bad'})):
            with self.assertRaises(cache.CacheMiss):
                cache.original_current_directory_artifact_hashes(dict(good, **changes))
        for path in artifacts:
            with self.assertRaises(cache.CacheMiss):
                cache.original_current_directory_artifact_hashes(dict(good,
                    artifactHashes={key: value for key, value in artifacts.items() if key != path}))
        for suffix in ('native-paths/file', 'native-paths/link', 'ghc/OriginalCurrentDirectoryAudit.o', 'ghc/abi-probe', 'native/oracle', 'unix-source/unix.cabal', 'unix-build/build/libHSunix.so', 'unknown.json'):
            self.assertFalse(cache.allowed_payload('build/original-current-directory/' + suffix))

    def test_original_directory_streams_closed_receipt(self):
        name = 'build/original-directory-streams/manifest.json'
        outputs = cache.ORIGINAL_DIRECTORY_STREAMS_OUTPUTS
        self.assertEqual(68, len(outputs))
        self.assertEqual(cache.platform.system() == 'Linux', name in DECLARED_REQUIRED)
        artifacts = {path: 'a' * 64 for path in outputs - {name}}
        good = dict(schema=1, ghc='9.14.1', unixUnit='unix-2.8.8.0-inplace',
                    entries=list(cache.ORIGINAL_DIRECTORY_STREAMS_ENTRIES), installedArtifactsHashed=False,
                    privateRebuiltUnix=True, unixSourceReceipt='build/original-directory-streams/unix-source.json',
                    unixArchiveSha256='a128dea3bfeb731a562f22d376fa606e902154d95321363f7ec1ea6b787a5a3e', artifactHashes=artifacts)
        self.assertEqual(artifacts, cache.original_directory_streams_artifact_hashes(good))
        for unit in ('unix-2.8.8.0-inplace', 'unix-2.8.8.0-02fc', 'unix-2.8.8.0-deadbeef'):
            self.assertEqual(artifacts, cache.original_directory_streams_artifact_hashes(dict(good, unixUnit=unit)))
        for unit in (None, 42, 'unix-2.8.8.0', 'unix-2.8.8.0-',
                     'unix-2.8.8.0-ABCD', 'unix-2.8.8.0-xyz',
                     'unix-2.8.7.0-02fc', 'directory-2.8.8.0-02fc',
                     'unix-2.8.8.0-inplace\n', 'unix-2.8.8.0-02fc:forged'):
            with self.assertRaises(cache.CacheMiss, msg=unit):
                cache.original_directory_streams_artifact_hashes(dict(good, unixUnit=unit))
        for label in ('pre-audit-directoryOpen', 'post-audit-directoryFree'):
            self.assertIn('build/original-directory-streams/logs/' + label + '.command.json', outputs)
        for path in outputs:
            self.assertTrue(cache.allowed_payload(path), path)
        for changes in (dict(schema=True), dict(ghc='9.14.0'), dict(unixUnit='unix-2.8.8.0-ABC'),
                        dict(entries=[]), dict(installedArtifactsHashed=True),
                        dict(privateRebuiltUnix=False), dict(unixSourceReceipt="wrong"), dict(unixArchiveSha256="a" * 64),
                        dict(artifactHashes={}), dict(artifactHashes=dict(artifacts, unknown='a' * 64)),
                        dict(artifactHashes={**artifacts, 'build/original-directory-streams/pre.cbd': 'bad'})):
            with self.assertRaises(cache.CacheMiss):
                cache.original_directory_streams_artifact_hashes(dict(good, **changes))
        for path in artifacts:
            with self.assertRaises(cache.CacheMiss):
                cache.original_directory_streams_artifact_hashes(dict(good,
                    artifactHashes={key: value for key, value in artifacts.items() if key != path}))
        for suffix in ('native-paths/file', 'native-paths/link', 'ghc/OriginalDirectoryStreamsAudit.o', 'ghc/abi-probe', 'native/oracle', 'unix-source/unix.cabal', 'unix-build/build/libHSunix.so', 'unknown.json'):
            self.assertFalse(cache.allowed_payload('build/original-directory-streams/' + suffix))

    def test_original_current_directory_archive_roundtrip_preserves_source_receipt(self):
        name = 'build/original-current-directory/manifest.json'
        receipt_name = 'build/original-current-directory/unix-source.json'
        artifacts = cache.ORIGINAL_CURRENT_DIRECTORY_OUTPUTS - {name}
        for path in artifacts:
            self.put(path, b'{}\n' if path.endswith('.json') else b'fixture\x00\xff\n')
        # The complete Core and unchanged upstream sources are provenance, not
        # prerequisites for restoring the closed exported fixture set.
        private_source = 'build/original-current-directory/unix-source-run/unix-2.8.8.0/unix.cabal'
        private_interface = '/private/fixture/unix-dist/PosixPath.hi'
        receipt = json.dumps(dict(archive='vendor/archives/unix-2.8.8.0.tar.gz',
            archiveSha256='a128dea3bfeb731a562f22d376fa606e902154d95321363f7ec1ea6b787a5a3e',
            sourceHashes={private_source: 'a' * 64}, interfaceHashes={private_interface: 'b' * 64},
            sourcesUnchangedAfterBuild=True, unixUnit='unix-2.8.8.0-inplace'))
        self.put(receipt_name, receipt)
        original = json.dumps(dict(schema=1, ghc='9.14.1', unixUnit='unix-2.8.8.0-inplace',
            entries=list(cache.ORIGINAL_CURRENT_DIRECTORY_ENTRIES), installedArtifactsHashed=False,
            nativeIsolatedChild=True, coordinatorCwdUnchanged=True, privateRebuiltUnix=True,
            unixSourceReceipt=receipt_name,
            unixArchiveSha256='a128dea3bfeb731a562f22d376fa606e902154d95321363f7ec1ea6b787a5a3e',
            inputHashes=self.manifest['inputHashes'],
            artifactHashes={path: cache.digest(self.root / path) for path in artifacts}))
        self.put(name, original)
        with patch.object(cache, 'REQUIRED', (*cache.REQUIRED, name)):
            packed = self.pack()
            self.assertTrue(cache.ORIGINAL_CURRENT_DIRECTORY_OUTPUTS <= packed['payload'].keys())
            self.assertNotIn(private_source, packed['payload'])
            self.assertNotIn(private_interface, packed['payload'])
            self.remove_payload(packed)
            cache.restore(self.root, self.current, self.bundle)
            self.assertEqual(original, (self.root / name).read_text())
            self.assertEqual(receipt, (self.root / receipt_name).read_text())
            for path in cache.ORIGINAL_CURRENT_DIRECTORY_OUTPUTS:
                self.assertEqual(packed['payload'][path], cache.digest(self.root / path), path)
            self.remove_payload(packed)
            missing = self.rewrite(lambda entries: [(member, data) for member, data in entries
                if member.name != 'files/' + receipt_name])
            self.rejected_without_writes(missing)
            corrupt = self.rewrite(lambda entries: [(member, data + b' ' if member.name == 'files/' + receipt_name else data)
                for member, data in entries])
            self.rejected_without_writes(corrupt)

    def test_simd_memory_closed_receipts_and_export_only_archives(self):
        for family in cache.SIMD_BYTEARRAY_FAMILIES:
            for native in (False, True):
                name = f"build/{family}/provenance.json"
                attempt = f"build/{family}/prepare-run-Abc123"
                required = cache.simd_bytearray_outputs(family, attempt, native)
                self.assertIn(name, DECLARED_REQUIRED)
                for path in required:
                    self.assertTrue(cache.allowed_payload(path), path)
                    self.put(path, b'{}\n' if path.endswith('.json') else b'fixture\n')
                for suffix in ("prepare-run-Abc123/previous/old.json", "native/extra.o", "pre-core/Other.json",
                               "prepare-run-Abc123/audits/extra.json", "prepare-run-Abc123/commands/extra.stdout"):
                    self.assertFalse(cache.allowed_payload(f"build/{family}/{suffix}"), suffix)
                rows = cache.SIMD_BYTEARRAY_FAMILIES[family][1]
                good = dict(schema=1, vector=family.removeprefix("simd-"), attempt=attempt,
                    stages=["pre", "post"] if native else ["pre"], modelRows=rows, modelByteOrder="little",
                    nativeRows=rows if native else None, nativeByteOrder="little" if native else None,
                    modelMatched=True if native else None,
                    sources=[dict(path=p, sha256=h) for p, h in self.manifest['inputHashes'].items()],
                    artifacts=[dict(path=p, sha256=cache.digest(self.root / p)) for p in sorted(required)])
                self.assertEqual(required, set(cache.simd_bytearray_artifact_hashes(family, good)))
                for index in range(len(good["artifacts"])):
                    with self.assertRaises(cache.CacheMiss):
                        cache.simd_bytearray_artifact_hashes(family, dict(good, artifacts=good["artifacts"][:index]+good["artifacts"][index+1:]))
                for changes in (dict(schema=True), dict(modelRows=True), dict(nativeRows=False), dict(vector="wrong"),
                                dict(stages=["post"]), dict(attempt=attempt+"/../escape"),
                                dict(artifacts=good["artifacts"]+[good["artifacts"][0]]),
                                dict(artifacts=[dict(good["artifacts"][0], sha256="bad")]+good["artifacts"][1:])):
                    with self.assertRaises(cache.CacheMiss): cache.simd_bytearray_artifact_hashes(family, dict(good, **changes))
                original = json.dumps(good); self.put(name, original)
                self.bundle = self.temp_root / f"{family}-{native}.tar.gz"
                with patch.object(cache, "REQUIRED", (*cache.REQUIRED, name)):
                    packed = self.pack(); self.remove_payload(packed)
                    cache.restore(self.root, self.current, self.bundle)
                    self.assertEqual(original, (self.root / name).read_text())
                    self.remove_payload(packed)
                    missing = self.rewrite(lambda members: [(member, data) for member, data in members
                        if member.name != "files/" + sorted(required)[0]])
                    self.rejected_without_writes(missing)
                    cache.restore(self.root, self.current, self.bundle)

    def test_floating_remainder_has_closed_native_inventory(self):
        name = 'build/floating-remainder/manifest.json'
        binary = 'build/floating-remainder/native/oracle'
        self.assertEqual(124, len(cache.FLOATING_REMAINDER_OUTPUTS))
        self.assertIn(name, DECLARED_REQUIRED)
        for path in cache.FLOATING_REMAINDER_OUTPUTS:
            self.assertTrue(cache.allowed_payload(path), path)
            if path == binary:
                self.assertEqual(0o755, cache.safe_mode(0o755, path))
            else:
                with self.assertRaises(cache.CacheMiss): cache.safe_mode(0o755, path)
        for suffix in ('native/other', 'native/FloatingRemainderNative.o', 'pre-core/Other.json',
                       'commands/extra.stdout', 'post-ghc/Main.hi', 'test-results/result.xml'):
            self.assertFalse(cache.allowed_payload('build/floating-remainder/' + suffix), suffix)
        artifacts = cache.FLOATING_REMAINDER_OUTPUTS - {name}
        for path in artifacts:
            self.put(path, b'{}\n' if path.endswith('.json') else b'\x00\x80\xff\n')
        (self.root / binary).chmod(0o755)
        manifest = dict(schema=1, inputHashes=self.manifest['inputHashes'],
                        artifactHashes={path: cache.digest(self.root / path) for path in artifacts})
        original = json.dumps(manifest)
        self.put(name, original)
        with patch.object(cache, 'REQUIRED', (*cache.REQUIRED, name)):
            packed = self.pack(); self.remove_payload(packed)
            cache.restore(self.root, self.current, self.bundle)
            self.assertEqual(original, (self.root / name).read_text())
            self.assertEqual(0o755, (self.root / binary).stat().st_mode & 0o7777)
            self.remove_payload(packed)
            for absent in (binary, 'build/floating-remainder/pre-core/FloatingRemainderAudit.cbd'):
                changed = self.rewrite(lambda entries: [(member, data) for member, data in entries
                    if member.name != 'files/' + absent])
                self.rejected_without_writes(changed)
            cache.restore(self.root, self.current, self.bundle)
            manifest['artifactHashes'].pop('build/floating-remainder/commands/native-build.command.json')
            self.put(name, json.dumps(manifest))
            with self.assertRaises(cache.CacheMiss): self.pack()

    def test_scalar_memory_exact_archive_and_missing_native_binary(self):
        self.assertEqual(25, len(cache.SCALAR_MEMORY_OUTPUTS))
        self.assertIn('build/scalar-memory-utilities/manifest.json', DECLARED_REQUIRED)
        for path in cache.SCALAR_MEMORY_OUTPUTS:
            self.assertTrue(cache.allowed_payload(path), path)
        for suffix in ('extra.json', 'native/other', 'post/core/Other.json', 'failed-native-import/command.json'):
            self.assertFalse(cache.allowed_payload('build/scalar-memory-utilities/' + suffix))
        name = 'build/scalar-memory-utilities/manifest.json'
        binary = 'build/scalar-memory-utilities/native/oracle'
        artifacts = cache.SCALAR_MEMORY_OUTPUTS - {name}
        for path in artifacts:
            self.put(path, b'{}\n' if path.endswith('.json') else b'fixture\n')
        (self.root / binary).chmod(0o755)
        original = json.dumps(dict(schema=1, ghc='9.14.1', entries=list(cache.SCALAR_MEMORY_ENTRIES),
            stages=['pre','post'], nativeRows=271, inputHashes=self.manifest['inputHashes'],
            artifactHashes={path: cache.digest(self.root / path) for path in artifacts}))
        self.put(name, original)
        with patch.object(cache, 'REQUIRED', (*cache.REQUIRED, name)):
            packed = self.pack(); self.remove_payload(packed)
            cache.restore(self.root, self.current, self.bundle)
            self.assertEqual(original, (self.root / name).read_text())
            self.assertEqual(0o755, (self.root / binary).stat().st_mode & 0o7777)
            self.remove_payload(packed)
            changed = self.rewrite(lambda entries: [(member, data) for member, data in entries
                if member.name != 'files/' + binary])
            self.rejected_without_writes(changed)

    def test_io_and_mask_cbd_archive_roundtrip_requires_executable_core(self):
        for family, outputs, receipt, binary, compact in (
                ("io-main-pap", cache.IO_MAIN_PAP_OUTPUTS, "provenance.json", "native/io-main-pap-oracle", "pre/core/IoMainPapAudit.cbd"),
                ("mask-functions", cache.MASK_FUNCTION_OUTPUTS, "manifest.json", "native/oracle", "post/core/MaskFunctionAudit.cbd")):
            name = f"build/{family}/{receipt}"
            artifacts = outputs - {name}
            for path in artifacts:
                self.put(path, b'{}\n' if path.endswith('.json') else b'fixture\n')
            native = f"build/{family}/{binary}"
            (self.root / native).chmod(0o755)
            hashes = {path: cache.digest(self.root / path) for path in artifacts}
            doc = dict(inputHashes=self.manifest['inputHashes'])
            if family == "io-main-pap":
                doc['artifacts'] = [dict(path=path, sha256=digest) for path, digest in hashes.items()]
            else:
                doc['artifactHashes'] = hashes
            self.put(name, json.dumps(doc))
            with patch.object(cache, 'REQUIRED', (name,)):
                packed = self.pack(); self.remove_payload(packed)
                cache.restore(self.root, self.current, self.bundle)
                self.assertEqual(0o755, (self.root / native).stat().st_mode & 0o7777)
                self.remove_payload(packed)
                changed = self.rewrite(lambda entries: [(member, data) for member, data in entries
                    if member.name != f'files/build/{family}/{compact}'])
                self.rejected_without_writes(changed)
            self.bundle.unlink()

    def test_coroutine_cbd_receipts_admit_only_recorded_products(self):
        for family, outputs, receipt in (("io-main-pap", cache.IO_MAIN_PAP_OUTPUTS, "provenance.json"),
                                         ("mask-functions", cache.MASK_FUNCTION_OUTPUTS, "manifest.json"),
                                         ("proxy-void", cache.PROXY_VOID_OUTPUTS, "manifest.json")):
            name = f"build/{family}/{receipt}"
            self.assertIn(name, DECLARED_REQUIRED)
            for path in outputs:
                self.assertTrue(cache.allowed_payload(path), path)
            for suffix in ("extra.cbd", "pre/core/Extra.cbd", "unreviewed/core/MaskFunctionAudit.cbd", "logs/extra.stdout"):
                self.assertFalse(cache.allowed_payload(f"build/{family}/{suffix}"))

    def test_delimited_continuation_closed_outputs_exclude_native_binaries_and_extras(self):
        self.assertEqual(168, len(cache.DELIMITED_OUTPUTS))
        self.assertIn('build/delimited-continuations/manifest.json', DECLARED_REQUIRED)
        for path in cache.DELIMITED_OUTPUTS:
            self.assertTrue(cache.allowed_payload(path), path)
            if path.endswith('.cbd'):
                self.assertFalse(cache.allowed_payload(path.removesuffix('.cbd') + '.json'))
        for suffix in ('native/oracle', 'other.json', 'pre/core/Other.json', 'commands/extra.stdout'):
            self.assertFalse(cache.allowed_payload('build/delimited-continuations/' + suffix))

    def test_simd_address_archive_roundtrip_and_missing_native_binary(self):
        self.assertEqual(43 if cache.SIMD_ADDRESS_NATIVE128 else 26, len(cache.SIMD_ADDRESS_OUTPUTS))
        self.assertIn('build/simd-address-families/manifest.json', DECLARED_REQUIRED)
        for path in cache.SIMD_ADDRESS_OUTPUTS:
            self.assertTrue(cache.allowed_payload(path), path)
        for suffix in ('extra.json', 'source/Other.hs', 'scalar/other', 'pre-core/Other.json'):
            self.assertFalse(cache.allowed_payload('build/simd-address-families/' + suffix))
        name = 'build/simd-address-families/manifest.json'
        binary = 'build/simd-address-families/scalar/oracle'
        artifacts = cache.SIMD_ADDRESS_OUTPUTS - {name}
        for path in artifacts:
            self.put(path, b'{}\n' if path.endswith('.json') else b'fixture\n')
        (self.root / binary).chmod(0o755)
        original = json.dumps(dict(schema=1, ghc='9.14.1', entries=list(cache.SIMD_ADDRESS_ENTRIES),
            scalarRows=cache.SIMD_ADDRESS_ROWS, nativeVector128Rows=cache.SIMD_ADDRESS_128_ROWS if cache.SIMD_ADDRESS_NATIVE128 else 0,
            stages={stage: {} for stage, _ in cache.SIMD_ADDRESS_STAGES}, inputHashes=self.manifest['inputHashes'],
            artifactHashes={path: cache.digest(self.root / path) for path in artifacts}))
        self.put(name, original)
        with patch.object(cache, 'REQUIRED', (*cache.REQUIRED, name)):
            packed = self.pack(); self.remove_payload(packed)
            cache.restore(self.root, self.current, self.bundle)
            self.assertEqual(original, (self.root / name).read_text())
            self.assertEqual(0o755, (self.root / binary).stat().st_mode & 0o7777)
            self.remove_payload(packed)
            changed = self.rewrite(lambda entries: [(member, data) for member, data in entries
                if member.name != 'files/' + binary])
            self.rejected_without_writes(changed)

    def test_thread_inventory_exact_closed_archive_roundtrip_and_missing_member(self):
        self.assertEqual(23, len(cache.THREAD_INVENTORY_OUTPUTS))
        self.assertIn('build/thread-inventory/manifest.json', DECLARED_REQUIRED)
        for path in cache.THREAD_INVENTORY_OUTPUTS:
            self.assertTrue(cache.allowed_payload(path), path)
        for suffix in ('extra.json', 'native/oracle', 'post/core/Other.json', 'pre/other-audit.json'):
            self.assertFalse(cache.allowed_payload('build/thread-inventory/' + suffix))
        name = 'build/thread-inventory/manifest.json'
        artifacts = cache.THREAD_INVENTORY_OUTPUTS - {name}
        for path in artifacts:
            self.put(path, b'{}\n' if path.endswith('.json') else b'10\n0\n111\n1\n')
        original = json.dumps(dict(schema=1, ghc='9.14.1', entries=list(cache.THREAD_INVENTORY_ENTRIES),
            stages=['pre', 'post'], nativeThread='unbound forkIO, threaded RTS -N2',
            inputHashes=self.manifest['inputHashes'],
            artifactHashes={path: cache.digest(self.root / path) for path in artifacts}))
        self.put(name, original)
        with patch.object(cache, 'REQUIRED', (*cache.REQUIRED, name)):
            packed = self.pack(); self.remove_payload(packed)
            cache.restore(self.root, self.current, self.bundle)
            self.assertEqual(original, (self.root / name).read_text())
            self.remove_payload(packed)
            changed = self.rewrite(lambda entries: [(member, data) for member, data in entries
                if member.name != 'files/build/thread-inventory/post/core/ThreadInventory.cbd'])
            self.rejected_without_writes(changed)

    def test_bytearray_family_closed_receipts_and_native_permissions(self):
        counts = {"bytearray": 49, "mutable-bytearrays": 87, "resize-bytearrays": 57,
                  "mutable-bytearray-size": 81, "compare-byte-arrays": 85}
        for family, count in counts.items():
            self.bundle = self.temp_root / f"bundle-{family}.tar.gz"
            name = f"build/{family}/manifest.json"
            binary = f"build/{family}/native/{family}-oracle"
            outputs = cache.BYTEARRAY_OUTPUTS[family]
            self.assertEqual(count, len(outputs)); self.assertIn(name, DECLARED_REQUIRED)
            artifacts = outputs - {name}
            for path in artifacts:
                self.assertTrue(cache.allowed_payload(path), path)
                self.put(path, b'{}\n' if path.endswith('.json') else b'fixture\n')
                if path == binary:
                    self.assertEqual(0o755, cache.safe_mode(0o755, path))
                else:
                    with self.assertRaises(cache.CacheMiss): cache.safe_mode(0o755, path)
            (self.root / binary).chmod(0o755)
            for suffix in ('commands/extra.stdout', 'pre-core/Other.json', 'native/extra.o', 'previous-manifests/old.json'):
                self.assertFalse(cache.allowed_payload(f'build/{family}/' + suffix))
            good = dict(schema=1, ghc='9.14.1', wordBits=64, entries=list(cache.BYTEARRAY_FAMILIES[family][1]),
                        inputHashes=self.manifest['inputHashes'], artifactHashes={path: cache.digest(self.root / path) for path in artifacts})
            self.assertEqual(good['artifactHashes'], cache.bytearray_artifact_hashes(family, good))
            for path in artifacts:
                broken = dict(good, artifactHashes={p: h for p, h in good['artifactHashes'].items() if p != path})
                with self.assertRaises(cache.CacheMiss): cache.bytearray_artifact_hashes(family, broken)
            for changes in (dict(entries=[]), dict(schema=True), dict(wordBits=32), dict(ghc='other'),
                            dict(artifactHashes=dict(good['artifactHashes'], unknown='0'*64)),
                            dict(artifactHashes={**good['artifactHashes'], binary: 'bad'})):
                with self.assertRaises(cache.CacheMiss): cache.bytearray_artifact_hashes(family, dict(good, **changes))
            original = json.dumps(good); self.put(name, original)
            with patch.object(cache, 'REQUIRED', (*cache.REQUIRED, name)):
                packed = self.pack(); self.remove_payload(packed)
                cache.restore(self.root, self.current, self.bundle)
                self.assertEqual(original, (self.root / name).read_text())
                self.assertEqual(0o755, (self.root / binary).stat().st_mode & 0o7777)
                self.remove_payload(packed)
                missing = self.rewrite(lambda items: [(member, data) for member, data in items if member.name != 'files/' + binary])
                self.rejected_without_writes(missing)
                cache.restore(self.root, self.current, self.bundle)

    def test_integer_completion_closed_native_and_command_inventory(self):
        self.assertEqual(33, len(cache.INTEGER_COMPLETION_OUTPUTS))
        name = "build/integer-completion/manifest.json"
        binary = "build/integer-completion/native/integer-completion-oracle"
        self.assertIn(name, DECLARED_REQUIRED)
        for path in cache.INTEGER_COMPLETION_OUTPUTS:
            self.assertTrue(cache.allowed_payload(path), path)
            if path == binary:
                self.assertEqual(0o755, cache.safe_mode(0o755, path))
            else:
                with self.assertRaises(cache.CacheMiss): cache.safe_mode(0o755, path)
        for suffix in ("commands/unknown.stdout", "native/other", "extra.json", "pre-core/Other.json"):
            self.assertFalse(cache.allowed_payload("build/integer-completion/" + suffix))
        artifacts = cache.INTEGER_COMPLETION_OUTPUTS - {name}
        for path in artifacts:
            self.put(path, b"{}\n" if path.endswith(".json") else b"native evidence\n")
        (self.root / binary).chmod(0o755)
        original = json.dumps(dict(schema=1, ghc="9.14.1", wordBits=64, nativeRows=3373,
            inputHashes=self.manifest["inputHashes"],
            artifactHashes={path: cache.digest(self.root / path) for path in artifacts}))
        self.put(name, original)
        with patch.object(cache, "REQUIRED", (*cache.REQUIRED, name)):
            packed = self.pack(); self.remove_payload(packed)
            cache.restore(self.root, self.current, self.bundle)
            self.assertEqual(original, (self.root / name).read_text())
            self.assertEqual(0o755, (self.root / binary).stat().st_mode & 0o7777)
            self.remove_payload(packed)
            changed = self.rewrite(lambda entries: [(member, data) for member, data in entries
                if member.name != "files/" + binary])
            self.rejected_without_writes(changed)

    def memory_fixture(self, directory):
        manifest_path = f'build/{directory}/manifest.json'
        artifacts = cache.MEMORY_FIXTURE_OUTPUTS[directory] - {manifest_path}
        for name in artifacts:
            self.put(name, b'{}\n' if name.endswith('.json') else b'\x00\x80\xff\n')
        binary = f'build/{directory}/native/oracle'
        if binary in artifacts:
            (self.root / binary).chmod(0o755)
        manifest = {'schema': 1, 'ghc': '9.14.1', 'inputHashes': self.manifest['inputHashes'],
                    'artifactHashes': {name: cache.digest(self.root / name) for name in sorted(artifacts)}}
        self.put(manifest_path, json.dumps(manifest))
        return manifest_path, manifest

    def test_memory_fixtures_admit_exact_recorded_outputs_only(self):
        expected_counts = {'address-array-copy': 42, 'atomic-int-arrays': 116,
                           'atomic-address': 28, 'unaligned-scalar-memory': 31,
                           'pinned-pointer-cells': 8, 'wide-char-address': 32}
        for directory, outputs in cache.MEMORY_FIXTURE_OUTPUTS.items():
            with self.subTest(directory=directory):
                self.assertEqual(expected_counts[directory], len(outputs))
                self.assertIn(f'build/{directory}/manifest.json', DECLARED_REQUIRED)
                for name in outputs:
                    self.assertTrue(cache.allowed_payload(name), name)
                    if name in ('build/address-array-copy/native/oracle', 'build/wide-char-address/native/oracle'):
                        self.assertEqual(0o755, cache.safe_mode(0o755, name))
                    else:
                        with self.assertRaises(cache.CacheMiss, msg=name):
                            cache.safe_mode(0o755, name)
                for suffix in ('logs/extra.stdout', 'commands/extra.stderr', 'native/other-oracle',
                               'pre/core/Extra.json', 'native/Main.o', 'retained/oracle.tsv'):
                    self.assertFalse(cache.allowed_payload(f'build/{directory}/{suffix}'), suffix)
        # These are recorded inputs/logs that the former generic suffix policy rejected.
        for name in ('address-array-copy/commands/native-build.stdout',
                     'atomic-int-arrays/commands/pre-export.stderr',
                     'atomic-address/inputs.txt', 'atomic-address/logs/export-post.command.json',
                     'unaligned-scalar-memory/inputs.txt', 'unaligned-scalar-memory/logs/ghc-inventory.stdout'):
            self.assertTrue(cache.allowed_payload('build/' + name), name)

    def test_core_json_is_not_a_fixture_cache_payload(self):
        for path in ("build/thread-async/pre/core/ThreadAsyncAudit.json",
                     "build/bytearray/post-core/ByteArrayAudit.json",
                     "build/boxed-cas/run-1/pre-core/BoxedCasAudit.json"):
            self.assertFalse(cache.allowed_payload(path))

    def test_array_core_products_require_cbd_not_diagnostic_json(self):
        for family, folder, module in (('boxed-arrays', '{stage}/core', 'BoxedArrayAudit'),
                                       ('array-slices', '{stage}-core', 'ArraySliceAudit'),
                                       ('fetch-add-int-array', '{stage}/core', 'FetchAddIntArrayAudit'),
                                       ('shrink-bytearrays', '{stage}/core', 'ShrinkMutableByteArrayAudit'),
                                       ('managed-address-reads', '{stage}-core', 'ManagedAddressReadAudit')):
            for stage in ('pre', 'post'):
                for name in (module, 'THC.InterfaceClosure'):
                    path = f'build/{family}/{folder.format(stage=stage)}/{name}.cbd'
                    self.assertTrue(cache.allowed_payload(path), path)
                    self.assertFalse(cache.allowed_payload(path.replace(name, 'Extra')), path)
        self.assertTrue(cache.allowed_payload('build/floating/core/FloatingAudit.cbd'))
        self.assertFalse(cache.allowed_payload('build/floating/core/Extra.cbd'))
        for outputs in (cache.ADDRESS_ARRAY_COPY_OUTPUTS, cache.ATOMIC_INT_ARRAY_OUTPUTS):
            products = [name for name in outputs if name.endswith('.cbd')]
            self.assertTrue(products)
            for name in products:
                self.assertTrue(cache.allowed_payload(name), name)
                self.assertFalse(cache.allowed_payload(name[:-4] + '.json'), name)
        for name in cache.BOXED_ARRAY_EXTENSION_FILES:
            if name.endswith('.cbd'):
                path = 'build/boxed-array-extensions/run-1/' + name
                self.assertTrue(cache.allowed_payload(path), path)
                self.assertFalse(cache.allowed_payload(path[:-4] + '.json'), path)

    def test_memory_manifest_rejects_omitted_extra_and_invalid_artifact_hashes(self):
        for directory in cache.MEMORY_FIXTURE_OUTPUTS:
            with self.subTest(directory=directory):
                path, original = self.memory_fixture(directory)
                artifacts = original['artifactHashes']
                self.assertEqual(artifacts, cache.memory_artifact_hashes(directory, original))
                for missing in artifacts:
                    changed = dict(original, artifactHashes={k: v for k, v in artifacts.items() if k != missing})
                    with self.assertRaises(cache.CacheMiss, msg=missing):
                        cache.memory_artifact_hashes(directory, changed)
                for change in ({'schema': True}, {'schema': 2}, {'ghc': '9.12.2'},
                               {'artifactHashes': None}, {'artifactHashes': dict(artifacts, extra='0' * 64)},
                               {'artifactHashes': artifacts | {next(iter(artifacts)): 'not-a-hash'}},
                               {'artifactHashes': artifacts | {next(iter(artifacts)): 42}}):
                    with self.assertRaises(cache.CacheMiss, msg=repr(change)):
                        cache.memory_artifact_hashes(directory, original | change)
                # Packing must invoke the validator rather than merely relying on callers.
                self.put(path, json.dumps(original | {'artifactHashes': {}}))
                with patch.object(cache, 'REQUIRED', (*cache.REQUIRED, path)):
                    with self.assertRaisesRegex(cache.CacheMiss, 'Incomplete/unreviewed memory'):
                        self.pack()
                self.assertFalse(self.bundle.exists())

    def test_memory_archive_round_trip_preserves_all_original_bytes(self):
        manifests = dict(self.memory_fixture(directory) for directory in cache.MEMORY_FIXTURE_OUTPUTS)
        original_bytes = {path: (self.root / path).read_bytes() for path in manifests}
        with patch.object(cache, 'REQUIRED', (*cache.REQUIRED, *manifests)):
            packed = self.pack()
            for outputs in cache.MEMORY_FIXTURE_OUTPUTS.values():
                self.assertLessEqual(outputs, packed['payload'].keys())
            self.remove_payload(packed)
            restored = cache.restore(self.root, self.current, self.bundle)
            self.assertEqual(packed, restored)
            for path, original in original_bytes.items():
                self.assertEqual(original, (self.root / path).read_bytes())
            for path, expected in restored['payload'].items():
                self.assertEqual(expected, cache.digest(self.root / path))
            for binary in ('build/address-array-copy/native/oracle', 'build/wide-char-address/native/oracle'):
                self.assertEqual(0o755, (self.root / binary).stat().st_mode & 0o7777)
            self.remove_payload(packed)
            for directory in cache.MEMORY_FIXTURE_OUTPUTS:
                artifact = (f'build/{directory}/logs/native-oracle.stdout' if directory == 'wide-char-address'
                            else f'build/{directory}/oracle.tsv')
                with self.subTest(directory=directory, failure='missing'):
                    changed = self.rewrite(lambda entries: [(member, data) for member, data in entries
                        if member.name != 'files/' + artifact])
                    self.rejected_without_writes(changed)
                with self.subTest(directory=directory, failure='corrupt'):
                    changed = self.rewrite(lambda entries: [(member, b'changed\n' if member.name ==
                        'files/' + artifact else data) for member, data in entries])
                    self.rejected_without_writes(changed)

    def test_memory_restore_rejects_self_consistent_but_incomplete_original_inventory(self):
        manifests = dict(self.memory_fixture(directory) for directory in cache.MEMORY_FIXTURE_OUTPUTS)
        with patch.object(cache, 'REQUIRED', (*cache.REQUIRED, *manifests)):
            packed = self.pack()
            self.remove_payload(packed)
            for directory in cache.MEMORY_FIXTURE_OUTPUTS:
                with self.subTest(directory=directory):
                    path = f'build/{directory}/manifest.json'
                    omitted = (f'build/{directory}/logs/native-oracle.stdout' if directory == 'wide-char-address'
                               else f'build/{directory}/oracle.tsv')

                    def omit_evidence(entries):
                        # This is a forged synthetic archive, never resealed real evidence.
                        original = copy.deepcopy(manifests[path])
                        del original['artifactHashes'][omitted]
                        raw = json.dumps(original).encode()
                        bundle = copy.deepcopy(packed)
                        del bundle['payload'][omitted]
                        del bundle['modes'][omitted]
                        bundle['payload'][path] = cache.sha(raw)
                        return [(member, json.dumps(bundle).encode() if member.name == 'bundle.json'
                                 else raw if member.name == 'files/' + path else data)
                                for member, data in entries if member.name != 'files/' + omitted]

                    changed = self.rewrite(omit_evidence)
                    self.rejected_without_writes(changed, 'Incomplete/unreviewed memory fixture artifacts')

    def test_float_decode_preserves_complete_original_core_and_native_evidence(self):
        name = 'build/float-decode/manifest.json'
        binary = 'build/float-decode/native/oracle'
        self.assertIn(name, DECLARED_REQUIRED)
        for path in cache.FLOAT_DECODE_OUTPUTS:
            self.assertTrue(cache.allowed_payload(path), path)
            if path == binary:
                self.assertEqual(0o755, cache.safe_mode(0o755, path))
            else:
                with self.assertRaises(cache.CacheMiss): cache.safe_mode(0o755, path)
        for suffix in ('native/other', 'native/FloatDecodeNative.o', 'original/Other.json',
                       'commands/extra.stdout', 'boot-ghc/Integer.hi', 'test-results/result.xml'):
            self.assertFalse(cache.allowed_payload('build/float-decode/' + suffix), suffix)
        artifacts = cache.FLOAT_DECODE_OUTPUTS - {name}
        for path in artifacts:
            self.put(path, b'{}\n' if path.endswith('.json') else b'\x00\x80\xff\n')
        (self.root / binary).chmod(0o755)
        manifest = dict(schema=1, inputHashes=self.manifest['inputHashes'],
                        artifactHashes={path: cache.digest(self.root / path) for path in artifacts})
        original = json.dumps(manifest)
        self.put(name, original)
        with patch.object(cache, 'REQUIRED', (*cache.REQUIRED, name)):
            packed = self.pack(); self.remove_payload(packed)
            cache.restore(self.root, self.current, self.bundle)
            self.assertEqual(original, (self.root / name).read_text())
            self.assertEqual(0o755, (self.root / binary).stat().st_mode & 0o7777)
            self.remove_payload(packed)
            for absent in (binary, 'build/float-decode/original/core/GHC.Internal.Bignum.Integer.cbd'):
                changed = self.rewrite(lambda entries: [(member, data) for member, data in entries
                    if member.name != 'files/' + absent])
                self.rejected_without_writes(changed)
            cache.restore(self.root, self.current, self.bundle)
            manifest['artifactHashes'].pop('build/float-decode/original/boot-provenance.json')
            self.put(name, json.dumps(manifest))
            with self.assertRaises(cache.CacheMiss): self.pack()

    def test_hint_trace_exact_native_fixture_inventory(self):
        self.assertEqual(18, len(cache.HINT_TRACE_OUTPUTS))
        self.assertIn('build/hint-trace/manifest.json', DECLARED_REQUIRED)
        for name in cache.HINT_TRACE_OUTPUTS:
            self.assertTrue(cache.allowed_payload(name), name)
            if name == 'build/hint-trace/native/oracle':
                self.assertEqual(0o755, cache.safe_mode(0o755, name))
            else:
                with self.assertRaises(cache.CacheMiss): cache.safe_mode(0o755, name)
        for suffix in ('native/other', 'native/other.eventlog', 'native/HintTraceNative.o',
                       'test-results/TEST.xml', 'pre/unknown.audit.json', 'pre/core/Other.json'):
            self.assertFalse(cache.allowed_payload('build/hint-trace/' + suffix), suffix)

    def test_tcsetattr_exact_native_image_fixture_inventory(self):
        self.assertEqual(33, len(cache.ORIGINAL_TCSETATTR_OUTPUTS))
        self.assertIn('build/original-tcsetattr/manifest.json', DECLARED_REQUIRED)
        for name in cache.ORIGINAL_TCSETATTR_OUTPUTS:
            self.assertTrue(cache.allowed_payload(name), name)
            if name == 'build/original-tcsetattr/native/oracle':
                self.assertEqual(0o755, cache.safe_mode(0o755, name))
            else:
                with self.assertRaises(cache.CacheMiss): cache.safe_mode(0o755, name)
        for suffix in ('native/other', 'logs/extra.stdout', 'pre/unknown.audit.json',
                       'attempt-0/oracle.json', 'pre/core/Other.json', 'native/OriginalTcsetattrNative.o'):
            self.assertFalse(cache.allowed_payload('build/original-tcsetattr/' + suffix), suffix)
        for suffix in ('../outside', 'logs/../../outside'):
            with self.assertRaises(cache.CacheMiss): cache.file_path(self.root, 'build/original-tcsetattr/' + suffix)
        name = 'build/original-tcsetattr/manifest.json'
        artifacts = cache.ORIGINAL_TCSETATTR_OUTPUTS - {name}
        binary = 'build/original-tcsetattr/native/oracle'
        for path in artifacts:
            self.put(path, b'{}\n' if path.endswith('.json') else b'\x00\x80\xff\n')
        (self.root / binary).chmod(0o755)
        original = json.dumps(dict(schema=1, supported=True, strictAccepted=True, runtimeVerified=False,
            installedArtifactsHashed=False, nativeRows=22, entries=['originalTcsetattr'],
            inputHashes=self.manifest['inputHashes'],
            artifactHashes={path: cache.digest(self.root / path) for path in artifacts}))
        self.put(name, original)
        with patch.object(cache, 'LINUX_X86_64_HOST', True), patch.object(cache, 'REQUIRED', (*cache.REQUIRED, name)):
            packed = self.pack(); self.remove_payload(packed)
            cache.restore(self.root, self.current, self.bundle)
            self.assertEqual(original, (self.root / name).read_text())
            self.assertEqual(0o755, (self.root / binary).stat().st_mode & 0o7777)
            self.remove_payload(packed)
            changed = self.rewrite(lambda entries: [(member, data) for member, data in entries
                if member.name != 'files/' + binary])
            self.rejected_without_writes(changed)

    def test_tcgetattr_exact_native_image_fixture_inventory(self):
        self.assertEqual(33, len(cache.ORIGINAL_TCGETATTR_OUTPUTS))
        self.assertIn('build/original-tcgetattr/manifest.json', DECLARED_REQUIRED)
        for name in cache.ORIGINAL_TCGETATTR_OUTPUTS:
            self.assertTrue(cache.allowed_payload(name), name)
            if name == 'build/original-tcgetattr/native/oracle':
                self.assertEqual(0o755, cache.safe_mode(0o755, name))
            else:
                with self.assertRaises(cache.CacheMiss): cache.safe_mode(0o755, name)
        for suffix in ('native/other', 'logs/extra.stdout', 'pre/unknown.audit.json',
                       'attempt-0/oracle.json', 'pre/core/Other.json', 'native/OriginalTcgetattrNative.o'):
            self.assertFalse(cache.allowed_payload('build/original-tcgetattr/' + suffix), suffix)
        for suffix in ('../outside', 'logs/../../outside'):
            with self.assertRaises(cache.CacheMiss): cache.file_path(self.root, 'build/original-tcgetattr/' + suffix)
        name = 'build/original-tcgetattr/manifest.json'
        artifacts = cache.ORIGINAL_TCGETATTR_OUTPUTS - {name}
        binary = 'build/original-tcgetattr/native/oracle'
        for path in artifacts:
            self.put(path, b'{}\n' if path.endswith('.json') else b'\x00\x80\xff\n')
        (self.root / binary).chmod(0o755)
        original = json.dumps(dict(schema=1, supported=True, strictAccepted=True, runtimeVerified=False,
            installedArtifactsHashed=False, nativeRows=12, entries=['originalTcgetattr'],
            inputHashes=self.manifest['inputHashes'],
            artifactHashes={path: cache.digest(self.root / path) for path in artifacts}))
        self.put(name, original)
        with patch.object(cache, 'LINUX_X86_64_HOST', True), patch.object(cache, 'REQUIRED', (*cache.REQUIRED, name)):
            packed = self.pack(); self.remove_payload(packed)
            cache.restore(self.root, self.current, self.bundle)
            self.assertEqual(original, (self.root / name).read_text())
            self.assertEqual(0o755, (self.root / binary).stat().st_mode & 0o7777)
            self.remove_payload(packed)
            changed = self.rewrite(lambda entries: [(member, data) for member, data in entries
                if member.name != 'files/' + binary])
            self.rejected_without_writes(changed)


    def test_pinned_address_closed_artifacts_and_byte_preserving_restore(self):
        name = 'build/pinned-addresses/manifest.json'
        artifacts = cache.PINNED_ADDRESS_OUTPUTS - {name}
        self.assertIn(name, DECLARED_REQUIRED)
        binary = 'build/pinned-addresses/native/pinned-address-oracle'
        for path in artifacts:
            self.assertTrue(cache.allowed_payload(path), path)
            self.put(path, b'{}\n' if path.endswith('.json') else b'fixture\n')
            if path == binary: self.assertEqual(0o755, cache.safe_mode(0o755, path))
            else:
                with self.assertRaises(cache.CacheMiss): cache.safe_mode(0o755, path)
        (self.root / binary).chmod(0o755)
        for suffix in ('commands/extra.stdout', 'previous-manifests/old.json', 'pre/ghc/Unknown.hi', 'native/extra.o', 'pre/negative/extra.json', 'pre/negative/extra.cbd', 'pre/negative/read-state-is-int-0.json', 'pre/core/Other.cbd'):
            self.assertFalse(cache.allowed_payload('build/pinned-addresses/' + suffix), suffix)
        records = {path: cache.digest(self.root / path) for path in artifacts}
        manifest = dict(schema=1, mode='full', strictAccepted=True, inputHashes=self.manifest['inputHashes'], artifactHashes=records)
        original = json.dumps(manifest); self.put(name, original)
        for path in records:
            with self.assertRaises(cache.CacheMiss): cache.pinned_address_artifact_hashes(dict(manifest, artifactHashes={p: h for p, h in records.items() if p != path}))
        for changed in (dict(records, **{'build/pinned-addresses/extra.json': '0'*64}), dict(records, **{binary: 'bad'})):
            with self.assertRaises(cache.CacheMiss): cache.pinned_address_artifact_hashes(dict(manifest, artifactHashes=changed))
        with patch.object(cache, 'REQUIRED', (*cache.REQUIRED, name)):
            packed = self.pack(); self.remove_payload(packed)
            cache.restore(self.root, self.current, self.bundle)
            self.assertEqual(original, (self.root / name).read_text())
            self.assertEqual(0o755, (self.root / binary).stat().st_mode & 0o7777)
            self.remove_payload(packed)
            missing = self.rewrite(lambda items: [(member, data) for member, data in items
                if member.name != 'files/build/pinned-addresses/pre/negative/read-state-is-int-0.cbd'])
            self.rejected_without_writes(missing)

    def test_native_malloc_cache_preserves_exact_oracle_and_rejects_extra_members(self):
        manifest_path = 'build/native-malloc/manifest.json'
        oracle_path = 'build/native-malloc/oracle.txt'
        self.assertIn(manifest_path, DECLARED_REQUIRED)
        self.put(oracle_path, '0 0 0 0\n1 1 257 1\n')
        original = json.dumps({'schema': 1, 'inputHashes': self.manifest['inputHashes'],
            'artifactHashes': {oracle_path: cache.digest(self.root / oracle_path)}})
        self.put(manifest_path, original)
        for path in (manifest_path, oracle_path):
            self.assertTrue(cache.allowed_payload(path))
            with self.assertRaises(cache.CacheMiss): cache.safe_mode(0o755, path)
        for suffix in ('native/oracle', 'native/oracle.o', 'logs/extra.stdout', 'other.txt'):
            self.assertFalse(cache.allowed_payload('build/native-malloc/' + suffix), suffix)
        with patch.object(cache, 'REQUIRED', (*cache.REQUIRED, manifest_path)):
            packed = self.pack()
            self.assertLessEqual({manifest_path, oracle_path}, packed['payload'].keys())
            self.remove_payload(packed)
            cache.restore(self.root, self.current, self.bundle)
            self.assertEqual(original, (self.root / manifest_path).read_text())
            self.assertEqual(packed['payload'][oracle_path], cache.digest(self.root / oracle_path))
            self.remove_payload(packed)
            missing_oracle = self.rewrite(lambda entries: [(member, data) for member, data in entries
                if member.name != 'files/' + oracle_path])
            self.rejected_without_writes(missing_oracle)


    def test_floatx4_fma_payload_is_closed_and_preserves_original_provenance(self):
        manifest_path = 'build/simd-floatx4-fma/manifest.json'
        self.assertIn(manifest_path, DECLARED_REQUIRED)
        self.assertEqual(8, len(cache.SIMD_FLOAT_FMA_OUTPUTS))
        artifacts = cache.SIMD_FLOAT_FMA_OUTPUTS - {manifest_path}
        for path in artifacts:
            self.assertTrue(cache.allowed_payload(path), path)
            self.put(path, b'{}\n' if path.endswith('.json') else b'original native rows\n')
            with self.assertRaises(cache.CacheMiss): cache.safe_mode(0o755, path)
        original = json.dumps({'schema': 1, 'inputHashes': self.manifest['inputHashes'],
            'artifactHashes': {name: cache.digest(self.root / name) for name in artifacts}})
        self.put(manifest_path, original)
        with patch.object(cache, 'REQUIRED', (*cache.REQUIRED, manifest_path)):
            manifest = self.pack()
            self.assertLessEqual(cache.SIMD_FLOAT_FMA_OUTPUTS, manifest['payload'].keys())
            self.remove_payload(manifest)
            cache.restore(self.root, self.current, self.bundle)
            self.assertEqual(original, (self.root / manifest_path).read_text())
            for name in artifacts:
                self.assertEqual(manifest['payload'][name], cache.digest(self.root / name))
            self.remove_payload(manifest)
            changed = self.rewrite(lambda entries: [(member, data) for member, data in entries
                if member.name != 'files/build/simd-floatx4-fma/oracle.txt'])
            self.rejected_without_writes(changed)
        for suffix in ('native/oracle', 'native/SimdFloatFma.o', 'logs/extra.command.json',
                       'other.txt', 'pre-core/Other.json', 'test-results/pass.json'):
            self.assertFalse(cache.allowed_payload('build/simd-floatx4-fma/' + suffix), suffix)

    def test_wide_fma_scalar_oracle_payload_is_closed(self):
        self.assertIn('build/simd-wide-floating-fma/manifest.json', DECLARED_REQUIRED)
        self.assertEqual(5, len(cache.SIMD_WIDE_FMA_OUTPUTS))
        for path in cache.SIMD_WIDE_FMA_OUTPUTS:
            self.assertTrue(cache.allowed_payload(path), path)
            with self.assertRaises(cache.CacheMiss): cache.safe_mode(0o755, path)
        for suffix in ('native/scalar-lane-oracle', 'native/SimdWideFloatFmaNative.o',
                       'post-core/SimdWideFloatFma.cbd', 'pre-core/Other.json', 'logs/extra.stdout'):
            self.assertFalse(cache.allowed_payload('build/simd-wide-floating-fma/' + suffix), suffix)

    def test_arithmetic_installed_bundle_hashes_do_not_escape_into_zip_member_paths(self):
        path = 'build/arithmetic-exceptions/installed/bundles/ghc-internal.zip'
        package = dict(format='thc-core-packages', units=[dict(bundle=dict(path=path, sha256='a'*64),
            modules=[dict(path='core/0.cbd', sha256='b'*64)])])
        self.assertEqual([(path, 'a'*64)], list(cache.hashes_in(package, {})))
        self.assertFalse(cache.allowed_payload(path))
        self.assertFalse(cache.allowed_payload('build/arithmetic-exceptions/native/other.zip'))
        with self.assertRaises(cache.CacheMiss): cache.safe_mode(0o755, path)

    def test_saved_termios_fixture_artifact_scope(self):
        self.assertIn('build/original-termios/manifest.json', DECLARED_REQUIRED)
        for name in cache.ORIGINAL_TERMIOS_OUTPUTS:
            self.assertTrue(cache.allowed_payload(name), name)
            if name == 'build/original-termios/saved/native/oracle':
                self.assertEqual(0o755, cache.safe_mode(0o755, name))
            else:
                with self.assertRaises(cache.CacheMiss): cache.safe_mode(0o755, name)
        for suffix in ('native/other', 'logs/extra.stdout', 'pre/unknown.audit.json',
                       'attempt-0/oracle.json', 'pre/core/Other.json', 'saved/native/other.o',
                       'saved/native/other', 'saved/native/OriginalSavedTermiosNative.o',
                       'saved/pre/core/Other.json', 'logs/saved-extra.stdout'):
            self.assertFalse(cache.allowed_payload('build/original-termios/' + suffix), suffix)
        for suffix in ('../outside', 'logs/../../outside'):
            with self.assertRaises(cache.CacheMiss): cache.file_path(self.root, 'build/original-termios/' + suffix)

    def test_termios_cache_round_trip_keeps_saved_pointer_provenance_and_native_mode(self):
        name = 'build/original-termios/manifest.json'
        artifacts = cache.ORIGINAL_TERMIOS_OUTPUTS - {name}
        binaries = ('build/original-termios/saved/native/oracle',)
        for path in artifacts:
            self.put(path, b'{}\n' if path.endswith('.json') else b'\x00\x80\xff\n')
        for binary in binaries:
            (self.root / binary).chmod(0o755)
        original = json.dumps(dict(schema=1, supported=True, strictAccepted=True, runtimeVerified=False,
            installedArtifactsHashed=False, nativeRows=28, entries=list(cache.ORIGINAL_SAVED_TERMIOS_ENTRIES),
            inputHashes=self.manifest['inputHashes'],
            artifactHashes={path: cache.digest(self.root / path) for path in artifacts}))
        self.put(name, original)
        with patch.object(cache, 'LINUX_X86_64_HOST', True), patch.object(cache, 'REQUIRED', (*cache.REQUIRED, name)):
            packed = self.pack()
            self.assertLessEqual(cache.ORIGINAL_TERMIOS_OUTPUTS, packed['payload'].keys())
            self.remove_payload(packed)
            cache.restore(self.root, self.current, self.bundle)
            self.assertEqual(original, (self.root / name).read_text())
            for path in artifacts:
                self.assertEqual(packed['payload'][path], cache.digest(self.root / path), path)
            for binary in binaries:
                self.assertEqual(0o755, (self.root / binary).stat().st_mode & 0o7777)
            self.remove_payload(packed)
            for missing in ('saved/native/oracle', 'saved/pre/core/OriginalSavedTermiosAudit.cbd',
                            'logs/saved-native-run.stdout'):
                altered = self.rewrite(lambda entries: [(member, data) for member, data in entries
                    if member.name != 'files/build/original-termios/' + missing])
                self.rejected_without_writes(altered)

    def test_rts_locks_exact_nonexecutable_cache_inventory(self):
        self.assertIn('build/original-rts-locks/manifest.json', DECLARED_REQUIRED)
        for name in cache.ORIGINAL_RTS_LOCK_OUTPUTS:
            self.assertTrue(cache.allowed_payload(name), name)
            with self.assertRaises(cache.CacheMiss):
                cache.safe_mode(0o755, name)
        for suffix in ('native/oracle', 'ghc/OriginalRtsLocksAudit.o', 'logs/unknown.stdout',
                       'pre-unknown.audit.json', 'attempt-0/pre.json', 'pre/other.json'):
            self.assertFalse(cache.allowed_payload('build/original-rts-locks/' + suffix), suffix)
        for suffix in ('../outside', 'logs/../../outside'):
            with self.assertRaises(cache.CacheMiss):
                cache.file_path(self.root, 'build/original-rts-locks/' + suffix)

    def test_simd_smoke_generated_sources_and_native_oracle_roundtrip(self):
        name = 'build/simd-capability-smoke/manifest.json'
        self.assertIn(name, DECLARED_REQUIRED)
        artifacts = cache.SIMD_SMOKE_OUTPUTS - {name}
        for path in artifacts:
            self.put(path, b'{}\n' if path.endswith('.json') else b'fixture\n')
        binary = self.root / 'build/simd-capability-smoke/native/simd-smoke-oracle'
        binary.chmod(0o755)
        self.put(name, json.dumps({'inputs': [dict(path=path, sha256=cache.digest(self.root / path))
                                            for path in sorted(artifacts)]}))
        with patch.object(cache, 'REQUIRED', (*cache.REQUIRED, name)):
            manifest = self.pack()
            self.assertLessEqual(cache.SIMD_SMOKE_OUTPUTS, manifest['payload'].keys())
            self.remove_payload(manifest)
            cache.restore(self.root, self.current, self.bundle)
            for path in artifacts:
                self.assertEqual(manifest['payload'][path], cache.digest(self.root / path))
            self.assertEqual(0o755, binary.stat().st_mode & 0o7777)
            self.remove_payload(manifest)
            for missing in cache.SIMD_SMOKE_SOURCES:
                with self.subTest(missing=missing):
                    changed = self.rewrite(lambda entries: [(member, data) for member, data in entries
                        if member.name != 'files/' + missing])
                    self.rejected_without_writes(changed)
        for unexpected in ('build/generated/simd/java/Unexpected.java',
                           'build/generated/simd/fixtures/Unexpected.hs',
                           'build/simd-capability-smoke/native/unreviewed'):
            self.assertFalse(cache.allowed_payload(unexpected))

    def test_wired_catalog_fails_closed_on_nonliteral_or_unpinned_sources(self):
        project = Path(__file__).resolve().parents[2]
        self.put(cache.WIRED_SOURCE, (project / cache.WIRED_SOURCE).read_bytes())
        path = self.root / cache.WIRED_SOURCE
        source = path.read_text()
        for changed in (source.replace('moduleSources =', 'moduleSources = undefined\n--'),
                        source.replace('sourceHashes =', 'missingHashes ='),
                        source.replace('GHC/Internal/Arr.hs', '../GHC/Internal/Arr.hs')):
            path.write_text(changed)
            with self.assertRaises(cache.CacheMiss):
                cache.wired_catalog(self.root)

    def test_original_stack_cache_accepts_only_reviewed_attempt_artifacts(self):
        self.assertIn("build/original-stack/manifest.json", DECLARED_REQUIRED)
        for attempt in ("run-1", "run-42"):
            for suffix in cache.ORIGINAL_STACK_FILES:
                name = f"build/original-stack/{attempt}/{suffix}"
                self.assertTrue(cache.allowed_payload(name), name)
            self.assertTrue(cache.native_executable(
                f"build/original-stack/{attempt}/native/original-stack-native"))
        for name in ("proof.json", "run-0/logs/ghc-version.stdout",
                     "run-01/logs/ghc-version.stdout", "run-1/previous-manifest.json",
                     "run-1/logs/unknown.stdout", "run-1/pre-core/Extra.json",
                     "run-1/native/OriginalStackAudit.o", "run-1/native/other-oracle",
                     "run-1/logs/native-invariants.sh", "run-1/retained/Decode.json"):
            self.assertFalse(cache.allowed_payload("build/original-stack/" + name), name)
    def test_boxed_array_extensions_only_admit_reviewed_attempt_artifacts(self):
        self.assertIn('build/boxed-array-extensions/manifest.json', DECLARED_REQUIRED)
        for attempt in ('run-1', 'run-42'):
            for suffix in cache.BOXED_ARRAY_EXTENSION_FILES:
                name = f'build/boxed-array-extensions/{attempt}/{suffix}'
                self.assertTrue(cache.allowed_payload(name), name)
            self.assertEqual(0o755, cache.safe_mode(0o755,
                f'build/boxed-array-extensions/{attempt}/native/boxed-array-extensions-oracle'))
        for suffix in ('proof.json', 'run-0/logs/ghc-info.stdout', 'run-01/logs/ghc-info.stdout',
                       'run-1/previous-manifest.json', 'run-1/logs/extra.stdout',
                       'run-1/pre-core/Extra.json', 'run-1/native/Main.o', 'run-1/native/other-oracle'):
            self.assertFalse(cache.allowed_payload('build/boxed-array-extensions/' + suffix), suffix)
        with self.assertRaises(cache.CacheMiss):
            cache.safe_mode(0o755, 'build/boxed-array-extensions/run-1/logs/ghc-info.stdout')

    def test_boxed_array_extensions_cache_round_trip_and_missing_dependency(self):
        manifest_path = 'build/boxed-array-extensions/manifest.json'
        attempt = 'build/boxed-array-extensions/run-3/'
        artifacts = {attempt + name for name in cache.BOXED_ARRAY_EXTENSION_FILES}
        executable = attempt + 'native/boxed-array-extensions-oracle'
        for name in artifacts:
            self.put(name, b'{}\n' if name.endswith('.json') else b'\x00\x80\xff\n')
        (self.root / executable).chmod(0o755)
        original = json.dumps({'schema': 1, 'inputHashes': self.manifest['inputHashes'],
            'artifactHashes': {name: cache.digest(self.root / name) for name in sorted(artifacts)}})
        self.put(manifest_path, original)
        with patch.object(cache, 'REQUIRED', (*cache.REQUIRED, manifest_path)):
            manifest = self.pack()
            self.assertTrue(artifacts <= manifest['payload'].keys())
            self.remove_payload(manifest)
            cache.restore(self.root, self.current, self.bundle)
            self.assertEqual(original, (self.root / manifest_path).read_text())
            self.assertEqual(0o755, (self.root / executable).stat().st_mode & 0o7777)
            self.remove_payload(manifest)
            changed = self.rewrite(lambda entries: [(member, data) for member, data in entries
                if member.name != 'files/' + attempt + 'logs/native-oracle.stdout'])
            self.rejected_without_writes(changed)

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        # macOS exposes /var through a symlink; production receives resolved paths.
        self.temp_root = Path(self.temp.name).resolve()
        self.root = self.temp_root / "workspace"
        self.root.mkdir()
        subprocess.run(["git", "init", "-q", str(self.root)], check=True)
        self.pinned_name = cache.wired_source_path("GHC/Internal/CString.hs")
        self.pinned_source = b"original GHC source\n"
        self.put(self.pinned_name, self.pinned_source)
        self.put("bin/export-boot.py", "exception_sources = " + repr({
            "GHC/Internal/CString.hs": cache.sha(self.pinned_source)}) + "\n")
        for name in (cache.SELF, cache.WIRED_SOURCE, *cache.RUNTIME_INPUTS, *cache.COMPILER_BUILD_INPUTS,
                     "CMakeLists.txt", ".github/scripts/fast_fixtures.py", ".github/scripts/fast-fixtures.json",
                     "cmake/CoreFixtures.cmake", "t/fixtures/core/coverage.json",
                     "src/test/resources/core/original-unix-libc-descriptors.json",
                     "src/main/resources/thc/scalar-primop-signatures.json", "src/tools/primops/PrimopTools.hs"):
            self.put(name, "source: " + name)
        self.put("src/main/java/thc/runtime/Program.java", "unrelated runtime\n")
        subprocess.run(["git", "-C", str(self.root), "add", "."], check=True)
        self.tc = {"ghcLibdir": str(self.temp_root / "toolchain/lib"),
                   "version": "9.14.1", "target": "x86_64-unknown-linux",
                   "javaRelease": {"path": str(self.temp_root / "jdk/release"), "sha256": "b" * 64}}
        self.tool_patch = patch.object(cache, "toolchain", side_effect=lambda root: copy.deepcopy(self.tc))
        self.tool_patch.start(); self.addCleanup(self.tool_patch.stop)
        self.required_patch = patch.object(cache, "REQUIRED", ("build/data-to-tag/manifest.json",))
        self.required_patch.start(); self.addCleanup(self.required_patch.stop)
        self.current = cache.identity(self.root)
        self.put("build/data-to-tag/oracle.tsv", "0\t17\n")
        self.put("build/core/Fixtures.cbd", json.dumps({"module": "Fixture", "bindings": []}))
        self.manifest = {"inputHashes": {"CMakeLists.txt": self.current["sources"]["CMakeLists.txt"],
                                         self.pinned_name: cache.sha(self.pinned_source)},
                         "artifactHashes": {"build/data-to-tag/oracle.tsv": cache.digest(self.root / "build/data-to-tag/oracle.tsv")}}
        self.write_manifest()
        self.bundle = self.temp_root / "bundle.tar.gz"

    def put(self, name, content):
        p = self.root / name
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_bytes(content if isinstance(content, bytes) else content.encode())

    def write_manifest(self):
        self.put("build/data-to-tag/manifest.json", json.dumps(self.manifest))

    def pack(self):
        return cache.pack(self.root, self.current, self.bundle)

    def remove_payload(self, manifest):
        for name in manifest["payload"]:
            (self.root / name).unlink()

    def rewrite(self, mutate):
        with tarfile.open(self.bundle, "r:gz") as archive:
            entries = [(m, archive.extractfile(m).read()) for m in archive]
        entries = mutate(entries)
        changed = self.temp_root / "changed.tar.gz"
        with tarfile.open(changed, "w:gz") as archive:
            for member, data in entries:
                member.size = len(data)
                archive.addfile(member, io.BytesIO(data))
        return changed

    def rejected_without_writes(self, source, message=None):
        before = {str(p.relative_to(self.root)): p.read_bytes() for p in self.root.rglob("*") if p.is_file()}
        with self.assertRaises(cache.CacheMiss) as rejected:
            cache.restore(self.root, self.current, source)
        if message is not None:
            self.assertRegex(str(rejected.exception), message)
        after = {str(p.relative_to(self.root)): p.read_bytes() for p in self.root.rglob("*") if p.is_file()}
        self.assertEqual(before, after)

    def test_round_trip_preserves_original_provenance_and_all_bytes(self):
        original = (self.root / "build/data-to-tag/manifest.json").read_bytes()
        manifest = self.pack(); self.remove_payload(manifest)
        result = cache.restore(self.root, self.current, self.bundle)
        self.assertEqual(manifest, result)
        self.assertEqual(original, (self.root / "build/data-to-tag/manifest.json").read_bytes())
        for name, expected in result["payload"].items():
            self.assertEqual(expected, cache.digest(self.root / name))
        # Existing identical files are accepted, never overwritten.
        p = self.root / "build/data-to-tag/oracle.tsv"; before = p.stat().st_mtime_ns
        cache.restore(self.root, self.current, self.bundle)
        self.assertEqual(before, p.stat().st_mtime_ns)

    def test_weak_depfile_restore_requires_original_canonical_workspace(self):
        depfile = 'build/weak-explicit/runtime-support/runtime.d'
        original = (str(self.root / 'build/weak-explicit/runtime-support/manifest.json') + ': ' +
                    str(self.root / 'CMakeLists.txt') + '\n').encode()
        self.put(depfile, original)
        self.manifest['artifactHashes'][depfile] = cache.sha(original)
        self.write_manifest()
        manifest = self.pack()
        self.remove_payload(manifest)
        moved = self.temp_root / 'moved-workspace'
        self.root.rename(moved)
        current = cache.identity(moved)
        self.assertEqual(self.current['sources'], current['sources'])
        self.assertNotEqual(cache.cache_key(self.current), cache.cache_key(current))
        before = {str(path.relative_to(moved)): path.read_bytes() for path in moved.rglob('*') if path.is_file()}
        with self.assertRaisesRegex(cache.CacheMiss, 'workspace identity mismatch'):
            cache.restore(moved, current, self.bundle)
        after = {str(path.relative_to(moved)): path.read_bytes() for path in moved.rglob('*') if path.is_file()}
        self.assertEqual(before, after)
        self.assertFalse((moved / depfile).exists())
        moved.rename(self.root)
        cache.restore(self.root, self.current, self.bundle)
        self.assertEqual(original, (self.root / depfile).read_bytes())

    def test_compiled_primop_source_changes_invalidate_fixture_identity(self):
        name = "src/tools/primops/PrimopTools.hs"
        self.assertIn(name, self.current["sources"])
        self.put(name, "changed compiled GHC API query\n")
        changed = cache.identity(self.root)
        self.assertNotEqual(self.current["sources"][name], changed["sources"][name])
        self.assertNotEqual(cache.cache_key(self.current), cache.cache_key(changed))

    def test_submodule_identity_reads_only_declared_used_files(self):
        name = "nih/pinned/probe"
        module = self.root / name
        self.put(name + "/used.c", "used source\n")
        self.put(name + "/unrelated.c", "unrelated source\n")
        subprocess.run(["git", "init", "-q", str(module)], check=True)
        subprocess.run(["git", "-C", str(module), "add", "."], check=True)
        subprocess.run(["git", "-C", str(module), "-c", "user.name=Test", "-c",
                        "user.email=test@example.invalid", "commit", "-qm", "fixture"], check=True)
        revision = cache.command(["git", "rev-parse", "HEAD"], module)
        subprocess.run(["git", "-C", str(self.root), "update-index", "--add", "--cacheinfo",
                        "160000," + revision + "," + name], check=True)
        self.put("thc.cabal", "extra-source-files:\n  " + name + "/used.c\n")
        before = cache.identity(self.root)
        self.assertIn(name + "/used.c", before["sources"])
        self.assertNotIn(name, before["sources"])
        self.assertNotIn(name + "/unrelated.c", before["sources"])
        self.put(name + "/unrelated.c", "changed unrelated source\n")
        self.assertEqual(before, cache.identity(self.root))
        self.put(name + "/used.c", "changed used source\n")
        self.assertNotEqual(cache.cache_key(before), cache.cache_key(cache.identity(self.root)))

    def test_noncanonical_workspace_alias_is_still_rejected(self):
        alias = self.temp_root / "workspace-alias"
        alias.symlink_to(self.root, target_is_directory=True)
        with self.assertRaisesRegex(cache.CacheMiss, "Symlink/noncanonical destination"):
            cache.identity(alias)
        self.assertEqual(self.current, cache.identity(self.root))

    def test_authoritative_inputs_cannot_be_omitted_by_producer(self):
        self.manifest["inputHashes"] = {}
        self.write_manifest(); manifest = self.pack(); self.remove_payload(manifest)
        self.put("t/fixtures/core/coverage.json", "changed but omitted by producer")
        current = cache.identity(self.root)
        self.assertNotEqual(cache.cache_key(current), cache.cache_key(self.current))
        with self.assertRaises(cache.CacheMiss):
            cache.restore(self.root, current, self.bundle)

    def test_unrelated_runtime_change_reuses_key_but_recorded_runtime_change_misses(self):
        self.put("src/main/java/thc/runtime/Program.java", "new lowering")
        self.assertEqual(self.current, cache.identity(self.root))
        for name in cache.RUNTIME_INPUTS:
            before = cache.identity(self.root)
            self.put(name, "changed recorded runtime dependency")
            self.assertNotEqual(cache.cache_key(before), cache.cache_key(cache.identity(self.root)))

    def test_cabal_plugin_configuration_changes_invalidate_core_fixtures(self):
        for name in cache.COMPILER_BUILD_INPUTS:
            with self.subTest(name=name):
                before = cache.identity(self.root)
                self.put(name, "changed plugin build configuration")
                self.assertNotEqual(cache.cache_key(before), cache.cache_key(cache.identity(self.root)))

    def test_helper_tool_package_interface_jdk_platform_workspace_changes_miss(self):
        self.pack()
        for field in ("schema", "workspace", "platform", "toolchain"):
            current = copy.deepcopy(self.current); current[field] = "changed"
            with self.subTest(field=field), self.assertRaises(cache.CacheMiss):
                cache.restore(self.root, current, self.bundle)
        for field in ("version", "target", "javaRelease"):
            self.tc[field] = "changed"
            self.assertNotEqual(cache.cache_key(self.current), cache.cache_key(cache.identity(self.root)))
        self.put(cache.SELF, "changed cache schema implementation")
        self.assertNotEqual(cache.cache_key(self.current), cache.cache_key(cache.identity(self.root)))

    def test_original_source_and_artifact_hash_mismatch_rejected(self):
        for section, name in (("inputHashes", "CMakeLists.txt"),
                              ("artifactHashes", "build/data-to-tag/oracle.tsv"),
                              ("inputHashes", self.pinned_name)):
            with self.subTest(name=name):
                old = self.manifest[section][name]; self.manifest[section][name] = "f" * 64
                self.write_manifest()
                with self.assertRaises(cache.CacheMiss): self.pack()
                self.assertFalse(self.bundle.exists())
                self.manifest[section][name] = old

    def test_pinned_ghc_sources_are_inputs_and_never_restored_from_payload(self):
        self.assertIn(self.pinned_name, self.current["sources"])
        self.assertFalse(cache.allowed_payload(self.pinned_name))
        self.assertNotIn(self.pinned_name, self.pack()["payload"])
        self.put(self.pinned_name, "modified upstream source\n")
        with self.assertRaisesRegex(cache.CacheMiss, "Pinned GHC source missing or changed"):
            cache.identity(self.root)
        (self.root / self.pinned_name).unlink()
        with self.assertRaises((OSError, cache.CacheMiss)):
            cache.identity(self.root)

    def test_unkeyed_runtime_source_fails_closed(self):
        self.manifest["inputHashes"]["src/main/java/thc/runtime/Program.java"] = cache.digest(
            self.root / "src/main/java/thc/runtime/Program.java")
        self.write_manifest()
        with self.assertRaises(cache.CacheMiss): self.pack()

    def test_renamed_runtime_provenance_is_keyed_and_old_path_is_not_aliased(self):
        for name in cache.RUNTIME_INPUTS:
            self.manifest["inputHashes"][name] = self.current["sources"][name]
        self.write_manifest()
        manifest = self.pack(); self.remove_payload(manifest)
        cache.restore(self.root, self.current, self.bundle)
        self.manifest["inputHashes"]["src/main/java/thc/runtime/RetiredVectorMemory.java"] = "a" * 64
        self.write_manifest()
        with self.assertRaises(cache.CacheMiss):
            cache.inventory(self.root, self.current, lambda name: (self.root / name).read_bytes(), [])

    def test_old_cbv_payload_cannot_satisfy_renamed_required_core(self):
        self.put("build/core/CbvAudit.cbd", json.dumps({"module": "CbvAudit", "bindings": []}))
        with self.assertRaisesRegex(cache.CacheMiss, "Unknown/tracked payload"):
            self.pack()
        allowed = cache.allowed_payload
        with patch.object(cache, "allowed_payload", lambda name:
                          name == "build/core/CbvAudit.cbd" or allowed(name)):
            manifest = self.pack()
        self.remove_payload(manifest)
        self.assertIn("build/core/CbvAudit.cbd", manifest["payload"])
        self.assertNotIn("build/core/CBVAudit.cbd", manifest["payload"])
        # Archive names remain exact even on a case-insensitive host filesystem.
        with patch.object(cache, "REQUIRED", ("build/core/CBVAudit.cbd",)):
            self.rejected_without_writes(self.bundle)

    def test_installed_interfaces_use_the_toolchain_version_gate(self):
        interface = Path(self.tc["ghcLibdir"]) / "pkg/Foo.dyn_hi"
        interface.parent.mkdir(parents=True); interface.write_bytes(b"actual interface")
        self.manifest["installedShortInterface"] = {"path": str(interface), "sha256": cache.digest(interface)}
        self.manifest["sources"] = [{"path": str(self.root / "CMakeLists.txt"),
            "sha256": self.current["sources"]["CMakeLists.txt"], "url": "original/source"}]
        self.write_manifest(); manifest = self.pack(); self.remove_payload(manifest)
        interface.write_bytes(b"modified same package/version")
        digest = cache.digest
        def workspace_digest(path):
            self.assertFalse(Path(path).is_relative_to(Path(self.tc["ghcLibdir"])))
            return digest(path)
        with patch.object(cache, "digest", side_effect=workspace_digest):
            cache.restore(self.root, self.current, self.bundle)

    def test_conflicting_original_records_and_external_escape(self):
        self.manifest["sources"] = [{"path": "CMakeLists.txt", "sha256": "f" * 64}]
        self.write_manifest()
        with self.assertRaises(cache.CacheMiss): self.pack()
        self.manifest["sources"] = [{"path": "/etc/passwd", "sha256": "f" * 64}]
        self.write_manifest()
        with self.assertRaises(cache.CacheMiss): self.pack()

    def test_archive_corruption_and_missing_dependency_rejected_before_writes(self):
        manifest = self.pack(); self.remove_payload(manifest)
        changed = self.rewrite(lambda es: [(m, b"corrupt" if m.name.endswith("oracle.tsv") else d) for m, d in es])
        self.rejected_without_writes(changed)
        changed = self.rewrite(lambda es: [(m, d) for m, d in es if not m.name.endswith("oracle.tsv")])
        self.rejected_without_writes(changed)

    def test_duplicate_unknown_absolute_and_traversal_members(self):
        manifest = self.pack(); self.remove_payload(manifest)
        self.rejected_without_writes(self.rewrite(lambda es: es + [es[0]]))
        for name in ("/tmp/escape", "files/../../escape", "files/build/../escape", "files\\escape",
                     "files/build//escape", "files/build/./escape", "C:/escape", "files/build/classes/Evil.class"):
            with self.subTest(name=name):
                self.rejected_without_writes(self.rewrite(lambda es: es + [(tarfile.TarInfo(name), b"evil")]))

    def test_links_and_nonregular_members(self):
        manifest = self.pack(); self.remove_payload(manifest)
        for kind in (tarfile.SYMTYPE, tarfile.LNKTYPE, tarfile.DIRTYPE, tarfile.FIFOTYPE, tarfile.CHRTYPE):
            def mutate(es):
                m = tarfile.TarInfo("files/build/data-to-tag/evil.json"); m.type = kind; m.linkname = "/tmp/escape"
                return es + [(m, b"")]
            with self.subTest(kind=kind): self.rejected_without_writes(self.rewrite(mutate))

    def test_self_consistent_unknown_or_tracked_payload_is_not_accepted(self):
        manifest = self.pack(); self.remove_payload(manifest)
        for name in ("build/fast/pass.json", "build/test-results/test/pass.xml", "build/classes/Evil.class",
                     "build/data-to-tag/evil.sh", "CMakeLists.txt", "vendor/ghc-9.14.1/unknown.hs"):
            def mutate(es):
                doc = json.loads(es[0][1]); doc["payload"][name] = cache.sha(b"evil")
                return [(es[0][0], cache.canonical(doc)), *es[1:], (tarfile.TarInfo("files/"+name), b"evil")]
            with self.subTest(name=name): self.rejected_without_writes(self.rewrite(mutate))

    def test_symlink_destination_and_conflicting_file_preserved(self):
        manifest = self.pack(); self.remove_payload(manifest)
        target = self.root / "build/data-to-tag/oracle.tsv"
        target.symlink_to(self.temp_root / "missing-target")
        with self.assertRaises(cache.CacheMiss): cache.restore(self.root, self.current, self.bundle)
        self.assertTrue(target.is_symlink()); target.unlink()
        self.put("build/data-to-tag/oracle.tsv", "existing different evidence")
        self.rejected_without_writes(self.bundle)

    def test_parent_symlink_and_non_directory_conflict(self):
        manifest = self.pack(); self.remove_payload(manifest)
        directory = self.root / "build/data-to-tag"; directory.rmdir()
        outside = self.temp_root / "outside"; outside.mkdir()
        directory.symlink_to(outside, target_is_directory=True)
        with self.assertRaises(cache.CacheMiss): cache.restore(self.root, self.current, self.bundle)
        self.assertEqual([], list(outside.iterdir())); directory.unlink()
        directory.write_bytes(b"not a directory")
        self.rejected_without_writes(self.bundle)

    def test_original_provenance_payload_inventory_cannot_be_extended(self):
        manifest = self.pack(); self.remove_payload(manifest)
        def mutate(es):
            doc = json.loads(es[0][1]); doc["payload"]["build/data-to-tag/unreferenced.json"] = cache.sha(b"{}")
            return [(es[0][0], cache.canonical(doc)), *es[1:],
                    (tarfile.TarInfo("files/build/data-to-tag/unreferenced.json"), b"{}")]
        self.rejected_without_writes(self.rewrite(mutate))

    def test_archive_file_directory_collision(self):
        manifest = self.pack(); self.remove_payload(manifest)
        def mutate(es):
            doc = json.loads(es[0][1]); name = "build/data-to-tag/oracle.tsv/child.json"
            doc["payload"][name] = cache.sha(b"{}")
            return [(es[0][0], cache.canonical(doc)), *es[1:], (tarfile.TarInfo("files/"+name), b"{}")]
        self.rejected_without_writes(self.rewrite(mutate))

    def test_cli_key_stdout_and_restore_miss_status(self):
        output = self.temp_root / "identity.json"
        stdout, stderr = io.StringIO(), io.StringIO()
        with patch("sys.stdout", stdout), patch("sys.stderr", stderr):
            self.assertEqual(0, cache.main(["key", "--root", str(self.root), "--output", str(output)]))
            self.assertEqual(cache.cache_key(self.current)+"\n", stdout.getvalue())
            self.assertEqual(1, cache.main(["restore", "--root", str(self.root), "--identity", str(output),
                                          "--bundle", str(self.bundle)]))
        self.assertIn("MISS:", stderr.getvalue())

    def test_cli_known_restore_miss_does_not_scan_the_toolchain(self):
        directory = self.temp_root / "directory"; directory.mkdir()
        linked = self.temp_root / "linked"
        target = self.temp_root / "target"; target.write_bytes(b"not a bundle")
        linked.symlink_to(target)
        for bundle in (self.bundle, directory, linked):
            with self.subTest(bundle=bundle), patch.object(cache, "identity") as identify, \
                    patch("sys.stderr", io.StringIO()) as stderr:
                self.assertEqual(1, cache.main(["restore", "--root", str(self.root),
                    "--identity", str(self.temp_root / "not-needed.json"), "--bundle", str(bundle)]))
                identify.assert_not_called()
                self.assertIn("Bundle missing or linked", stderr.getvalue())

    def test_cli_restore_candidate_still_requires_fresh_identity(self):
        # Restore absent payloads: pack deliberately removes group/world
        # write bits, so source files created under umask 0002 may conflict.
        manifest = self.pack(); self.remove_payload(manifest)
        identity_file = self.temp_root / "identity.json"
        identity_file.write_text(json.dumps(self.current))
        command = ["restore", "--root", str(self.root), "--identity", str(identity_file),
                   "--bundle", str(self.bundle)]
        with patch.object(cache, "identity", wraps=cache.identity) as identify, patch("sys.stderr", io.StringIO()) as stderr:
            self.assertEqual(0, cache.main(command), stderr.getvalue())
            identify.assert_called_once_with(self.root)
        self.put("CMakeLists.txt", "changed after the key step")
        with patch.object(cache, "identity", wraps=cache.identity) as identify, patch("sys.stderr", io.StringIO()) as stderr:
            self.assertEqual(1, cache.main(command), stderr.getvalue())
            identify.assert_called_once_with(self.root)
            self.assertIn("Current identity changed since key step", stderr.getvalue())

    def test_payload_scope_has_no_runtime_or_test_outputs(self):
        self.assertTrue(cache.allowed_payload("build/unsafe-equality/api/predicate"))
        self.assertFalse(cache.allowed_payload("build/aggregate-layout/pre-ghc/A.dyn_o"))
        self.assertTrue(cache.allowed_payload("build/compiler/plugin.json"))
        self.assertTrue(cache.allowed_payload("build/compiler/libHSthc-0.1.0.0-inplace-ghc9.14.1.dylib"))
        self.assertTrue(cache.allowed_payload("build/compiler/libHSthc-0.1.0.0-inplace-ghc9.14.1.so"))
        for name in ("build/install/thc/lib/runtime.jar", "build/test-results/test/TEST.xml",
                     "build/reports/tests/index.html", "build/fast/native-inputs.tar.gz",
                     "build/compiler/thc-core-plugin.conf", "build/compiler/package.conf.d/package.cache",
                     "dist-newstyle/packagedb/ghc-9.14.1/package.cache", ".gradle/cache.bin"):
            self.assertFalse(cache.allowed_payload(name), name)

    def test_word_floating_manifest_and_semantic_payload_are_cache_inputs(self):
        self.assertIn("build/word-floating/manifest.json", DECLARED_REQUIRED)
        for name in ("oracle.tsv", "pre-audit.json", "post-audit.json",
                     "pre-core/WordFloatingAudit.cbd", "post-core/WordFloatingAudit.cbd"):
            self.assertTrue(cache.allowed_payload("build/word-floating/" + name), name)
        for name in ("test-results/results.json", "classes/Main.class", "unreviewed.sh"):
            self.assertFalse(cache.allowed_payload("build/word-floating/" + name), name)

    def test_full_pack_restores_exact_executable_contract_siblings(self):
        expected = {
            *(f"build/core/{module}.cbd" for module in ("StrictFields", "CBVAudit", "CBVCoercionAudit", "DemandAudit")),
            *(f"build/cbv-post-core/{module}.cbd" for module in ("CBVAudit", "CBVCoercionAudit")),
            "build/source-core/RepresentationAudit.cbd", "build/tuple-arithmetic/pre-core/TupleArithmeticAudit.cbd",
        }
        self.assertEqual(expected, cache.CORE_CONTRACT_CBD_REQUIRED)
        self.assertTrue(expected <= set(DECLARED_REQUIRED))
        originals = {name: b"compact fixture bytes: " + name.encode() for name in expected}
        for name, data in originals.items():
            self.put(name, data)
        # These executable siblings have no provenance record seeding them: exercise actual REQUIRED acquisition.
        with patch.object(cache, "REQUIRED", tuple(name for name in DECLARED_REQUIRED if name in expected)):
            packed = self.pack()
            self.assertTrue(expected <= set(packed["payload"]))
            self.remove_payload(packed)
            cache.restore(self.root, self.current, self.bundle)
            for name, data in originals.items():
                self.assertEqual(data, (self.root / name).read_bytes(), name)
            self.remove_payload(packed)
            omitted = "build/core/CBVCoercionAudit.cbd"
            changed = self.rewrite(lambda entries: [(member, data) for member, data in entries
                if member.name != "files/" + omitted])
            self.rejected_without_writes(changed)
            self.put(omitted, originals[omitted])
            with self.assertRaises(cache.CacheMiss):
                self.pack()

    def test_aggregate_host_cbd_payloads_are_closed_to_exact_modules_and_stages(self):
        for name in cache.AGGREGATE_HOST_CBD_OUTPUTS:
            self.assertTrue(cache.allowed_payload(name), name)
        for stage in ("pre", "post"):
            for suffix in ("hi", "o"):
                self.assertTrue(cache.allowed_payload(f"build/aggregate-layout/{stage}-ghc/AggregateLayoutAudit.{suffix}"))
        for name in ("build/aggregate-layout/provenance.json", "build/aggregate-layout/checks.json",
                     "build/aggregate-layout/native/AggregateLayoutAudit.o", "build/aggregate-layout/pre-ghc/Other.hi"):
            self.assertFalse(cache.allowed_payload(name), name)
        for name in ("build/sum-layout/other-core/SumLayoutAudit.cbd", "build/sum-result/pre-core/Other.cbd",
                     "build/empty-join-input/pre-core/EmptyJoinInputAudit.cbd", "build/empty-join-input/provenance.json"):
            self.assertFalse(cache.allowed_payload(name), name)

    def test_floating_tuple_products_exclude_receipts_and_unrelated_compiler_outputs(self):
        import fast_fixtures
        manifest, _ = fast_fixtures._manifest(Path(__file__).resolve().parents[2])
        self.assertEqual(set(manifest["groups"]["floating-tuples"]["outputs"]), cache.FLOATING_TUPLE_OUTPUTS)
        for name in cache.FLOATING_TUPLE_OUTPUTS:
            self.assertTrue(cache.allowed_payload(name), name)
            self.assertIn(name, DECLARED_REQUIRED)
        for name in ("provenance.json", "checks.json", "pre-audit.json", "post-audit.json", "pre-ghc/Other.hi",
                     "native/Other.o", "native/Main.dyn_o", "oracle.tsv.tmp", "bits.tsv.tmp"):
            self.assertFalse(cache.allowed_payload("build/floating-tuple/" + name), name)

    def test_sum_result_products_exclude_receipts_and_unrelated_compiler_outputs(self):
        import fast_fixtures
        manifest, _ = fast_fixtures._manifest(Path(__file__).resolve().parents[2])
        self.assertEqual(set(manifest["groups"]["sum-results"]["outputs"]), cache.SUM_RESULT_OUTPUTS)
        for name in cache.SUM_RESULT_OUTPUTS:
            self.assertTrue(cache.allowed_payload(name), name)
            self.assertIn(name, DECLARED_REQUIRED)
        for name in ("provenance.json", "checks.json", "pre-audit.json", "post-audit.json", "pre-ghc/Other.hi",
                     "native/Other.o", "native/Main.dyn_o", "oracle.tsv.tmp", "oracle-pairs.tsv.tmp"):
            self.assertFalse(cache.allowed_payload("build/sum-result/" + name), name)

    def test_sum_layout_products_exclude_receipts_and_unrelated_compiler_outputs(self):
        for stage in ("pre", "post"):
            for suffix in ("hi", "o"):
                self.assertTrue(cache.allowed_payload(f"build/sum-layout/{stage}-ghc/SumLayoutAudit.{suffix}"))
        for name in ("native/sum-layout-oracle", "oracle.tsv", "native/Main.hi", "native/Main.o", "native/SumLayoutAudit.hi", "native/SumLayoutAudit.o"):
            self.assertTrue(cache.allowed_payload("build/sum-layout/" + name), name)
        for name in ("provenance.json", "checks.json", "pre-ghc/Other.hi", "native/Other.o", "native/Main.dyn_o", "oracle.tsv.tmp"):
            self.assertFalse(cache.allowed_payload("build/sum-layout/" + name), name)

    def test_cbv_contract_cbd_payloads_are_closed_to_exact_modules_and_stages(self):
        for name in cache.CBV_CONTRACT_CBD_OUTPUTS:
            self.assertTrue(cache.allowed_payload(name), name)
        for name in ("build/core/Other.cbd", "build/cbv-post-core/DemandAudit.cbd",
                     "build/source-core/StrictFields.cbd"):
            self.assertFalse(cache.allowed_payload(name), name)

    def test_scalar_cbd_payloads_admit_only_the_exported_modules_and_stages(self):
        for family, module in (("word-floating", "WordFloatingAudit"), ("scalar-bitcasts", "ScalarBitCastAudit"),
                               ("fused-floating", "FloatingAudit")):
            for stage in ("pre", "post"):
                self.assertTrue(cache.allowed_payload(f"build/{family}/{stage}-core/{module}.cbd"))
                self.assertFalse(cache.allowed_payload(f"build/{family}/{stage}-core/Other.cbd"))
            self.assertFalse(cache.allowed_payload(f"build/{family}/unreviewed-core/{module}.cbd"))

    def test_address_and_tag_cbd_payloads_admit_only_exact_modules_and_stages(self):
        for family, module in (("address-fields", "AddressFieldAudit"), ("data-to-tag", "DataToTagAudit")):
            for stage in ("pre", "post"):
                for exported in (module, "THC.InterfaceClosure"):
                    self.assertTrue(cache.allowed_payload(f"build/{family}/{stage}/core/{exported}.cbd"))
                self.assertFalse(cache.allowed_payload(f"build/{family}/{stage}/core/Other.cbd"))
            self.assertFalse(cache.allowed_payload(f"build/{family}/unreviewed/core/{module}.cbd"))

    def test_original_read_archive_round_trip_preserves_complete_artifact_inventory(self):
        manifest_path = "build/original-stdio-read/manifest.json"
        binary = "build/original-stdio-read/native/original-stdio-read-oracle"
        artifacts = cache.ORIGINAL_STDIO_READ_OUTPUTS - {manifest_path}
        for name in artifacts:
            self.put(name, b"{}\n" if name.endswith(".json") else b"\x00\x80\xff\n")
        (self.root / binary).chmod(0o755)
        original = json.dumps({"schema": 1, "installedArtifactsHashed": False,
            "inputHashes": self.manifest["inputHashes"],
            "artifactHashes": {name: cache.digest(self.root / name) for name in sorted(artifacts)}})
        self.put(manifest_path, original)
        with patch.object(cache, "REQUIRED", (*cache.REQUIRED, manifest_path)):
            manifest = self.pack()
            self.assertTrue(cache.ORIGINAL_STDIO_READ_OUTPUTS <= manifest["payload"].keys())
            self.remove_payload(manifest)
            cache.restore(self.root, self.current, self.bundle)
            self.assertEqual(original, (self.root / manifest_path).read_text())
            self.assertEqual(0o755, (self.root / binary).stat().st_mode & 0o7777)
            for name in cache.ORIGINAL_STDIO_READ_OUTPUTS:
                self.assertEqual(manifest["payload"][name], cache.digest(self.root / name), name)
            self.remove_payload(manifest)
            changed = self.rewrite(lambda entries: [(member, data) for member, data in entries
                                                    if member.name != "files/build/original-stdio-read/logs/native-observations.stdout"])
            self.rejected_without_writes(changed)

    def test_original_stack_attempt_round_trip_keeps_logs_and_native_mode(self):
        manifest_path = "build/original-stack/manifest.json"
        attempt = "build/original-stack/run-3/"
        binary = attempt + "native/original-stack-native"
        artifacts = {attempt + name for name in cache.ORIGINAL_STACK_FILES}
        for name in artifacts:
            self.put(name, b"{}\n" if name.endswith(".json") else b"\x00\x80\xff\n")
        (self.root / binary).chmod(0o755)
        original = json.dumps({"schema": 1, "installedArtifactsHashed": False,
            "inputHashes": self.manifest["inputHashes"],
            "artifactHashes": {name: cache.digest(self.root / name) for name in sorted(artifacts)}})
        self.put(manifest_path, original)
        with patch.object(cache, "REQUIRED", (*cache.REQUIRED, manifest_path)):
            manifest = self.pack()
            self.assertTrue(artifacts <= manifest["payload"].keys())
            self.remove_payload(manifest)
            cache.restore(self.root, self.current, self.bundle)
            self.assertEqual(original, (self.root / manifest_path).read_text())
            self.assertEqual(0o755, (self.root / binary).stat().st_mode & 0o7777)
            for name in artifacts:
                self.assertEqual(manifest["payload"][name], cache.digest(self.root / name), name)
            self.remove_payload(manifest)
            changed = self.rewrite(lambda entries: [(member, data) for member, data in entries
                if member.name != "files/" + attempt + "logs/native-invariants.stdout"])
            self.rejected_without_writes(changed)

    def test_original_read_forged_unreviewed_artifact_is_rejected(self):
        unknown = "build/original-stdio-read/logs/unreviewed.stdout"
        self.put(unknown, "not part of the reviewed preparation plan")
        self.manifest["artifactHashes"][unknown] = cache.digest(self.root / unknown)
        self.write_manifest()
        with self.assertRaisesRegex(cache.CacheMiss, "Unknown/tracked payload"):
            self.pack()

    def test_legacy_launcher_digest_and_explicit_binary_digest_are_distinct(self):
        launcher = self.temp_root/"launcher"; launcher.write_bytes(b"launcher script")
        binary = self.temp_root/"binary"; binary.write_bytes(b"actual ELF")
        tc = {"ghcLauncher": {"path": str(launcher)}}
        legacy = {"ghcVersion": "9.14.1", "ghcInfo": "info", "ghcBinarySha256": cache.digest(launcher)}
        self.assertEqual([(str(launcher), cache.digest(launcher))], list(cache.hashes_in(legacy, tc)))
        explicit = {"ghcBinaryPath": str(binary), "ghcBinarySha256": cache.digest(binary)}
        self.assertEqual([(str(binary), cache.digest(binary))], list(cache.hashes_in(explicit, tc)))
        with self.assertRaises(cache.CacheMiss): list(cache.hashes_in({"ghcBinarySha256": "f"*64}, tc))

    def test_original_external_dotdot_path_is_validated_without_rewriting(self):
        folder = Path(self.tc["ghcLibdir"]); folder.mkdir(parents=True)
        interface = folder/"Foo.dyn_hi"; interface.write_bytes(b"installed interface")
        raw = str(folder/".."/"lib"/"Foo.dyn_hi")
        self.manifest["installedInterface"] = {"path": raw, "sha256": cache.digest(interface)}
        self.write_manifest(); original = (self.root/"build/data-to-tag/manifest.json").read_bytes()
        m = self.pack();self.assertIn(raw,m["external"]);self.remove_payload(m)
        cache.restore(self.root,self.current,self.bundle)
        self.assertEqual(original,(self.root/"build/data-to-tag/manifest.json").read_bytes())
        self.assertFalse(cache.external_allowed(str(folder/"../../../etc/passwd"),self.current))

    def test_native_execute_mode_preserved_and_special_or_data_execute_modes_rejected(self):
        path = self.root/"build/data-to-tag/oracle.tsv"
        # A real executable has a native executable name, not an oracle TSV.
        name = "build/data-to-tag/native/oracle"
        self.put(name,b"native bytes");(self.root/name).chmod(0o755)
        self.manifest["artifactHashes"][name]=cache.digest(self.root/name);self.write_manifest()
        m=self.pack();self.remove_payload(m);cache.restore(self.root,self.current,self.bundle)
        self.assertEqual(0o755,(self.root/name).stat().st_mode & 0o7777)
        with self.assertRaises(cache.CacheMiss):cache.safe_mode(0o4755,name)
        with self.assertRaises(cache.CacheMiss):cache.safe_mode(0o777,name)
        with self.assertRaises(cache.CacheMiss):cache.safe_mode(0o755,"build/core/Fixtures.cbd")

    def test_pack_sanitizes_write_permissions_without_overwriting_existing_modes(self):
        name = "build/data-to-tag/oracle.tsv"
        path = self.root / name
        original = path.read_bytes()
        path.chmod(0o664)
        manifest = self.pack()
        self.assertEqual(0o644, manifest["modes"][name])
        self.assertEqual(0o664, path.stat().st_mode & 0o7777)
        self.remove_payload(manifest)
        self.put(name, original)
        path.chmod(0o664)
        self.rejected_without_writes(self.bundle)
        self.assertEqual(0o664, path.stat().st_mode & 0o7777)
        path.unlink()
        cache.restore(self.root, self.current, self.bundle)
        self.assertEqual(original, path.read_bytes())
        self.assertEqual(0o644, path.stat().st_mode & 0o7777)

    def test_malformed_bundle_shapes_are_cache_misses(self):
        m=self.pack();self.remove_payload(m)
        variants=[[],None,{"schema":cache.SCHEMA,"identity":self.current,"key":cache.cache_key(self.current),
                           "payload":m["payload"],"coreFiles":None}]
        for value in variants:
            def mutate(es):return [(es[0][0],cache.canonical(value)),*es[1:]]
            with self.subTest(value=value):self.rejected_without_writes(self.rewrite(mutate))

    def test_custom_or_user_package_databases_rejected(self):
        with patch.dict(os.environ,{"GHC_ENVIRONMENT":"-"},clear=True), patch.object(cache,"command",return_value=""):
            cache.check_package_scope(self.root,"ghc-pkg")
            with patch.dict(os.environ,{"GHC_PACKAGE_PATH":"/same/mutable/db"}),self.assertRaises(cache.CacheMiss):
                cache.check_package_scope(self.root,"ghc-pkg")
            with patch.dict(os.environ,{"GHC_ENVIRONMENT":"/same/environment"}),self.assertRaises(cache.CacheMiss):
                cache.check_package_scope(self.root,"ghc-pkg")
        with patch.dict(os.environ,{"GHC_ENVIRONMENT":"-"},clear=True), patch.object(cache,"command",return_value="custom-package-1.0"),self.assertRaises(cache.CacheMiss):
            cache.check_package_scope(self.root,"ghc-pkg")
        self.put(".ghc.environment.x86_64-linux-9.14.1","package-id changed")
        with patch.dict(os.environ,{},clear=True),patch.object(cache,"command",return_value=""),self.assertRaises(cache.CacheMiss):
            cache.check_package_scope(self.root,"ghc-pkg")


class ToolchainVersionTests(unittest.TestCase):
    def test_ghc_uses_version_and_target_without_hashing_installation(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            libdir = root / "ghc/lib"
            libdir.mkdir(parents=True)
            release = root / "jdk/release"
            release.parent.mkdir()
            release.write_text('JAVA_VERSION="25"\n')
            responses = ["9.14.1", "GHC package manager version 9.14.1", "",
                         repr([("Target platform", "x86_64-unknown-linux")]), str(libdir)]
            provider = {"THC_INSTALLED_CORE_GHC": "/full-core/bin/ghc",
                        "THC_INSTALLED_CORE_GHC_PKG": "/full-core/bin/ghc-pkg",
                        "THC_INSTALLED_CORE_GHC_SOURCE": "/configured-ghc"}
            with patch.dict(os.environ, {"JAVA_HOME": str(release.parent), "GHC_ENVIRONMENT": "-", **provider}, clear=True), \
                    patch.object(cache, "command", side_effect=responses), \
                    patch.object(cache, "digest", wraps=cache.digest) as digest, \
                    patch.object(Path, "rglob", side_effect=AssertionError("Do not scan GHC")):
                result = cache.toolchain(root)
            digest.assert_called_once_with(release)
            self.assertEqual(result["version"], "9.14.1")
            self.assertEqual(result["target"], "x86_64-unknown-linux")
            self.assertNotIn("installedAbiSha256", result)
            self.assertEqual(provider, {key: result["environment"].get(key) for key in provider})

    def test_wrong_ghc_version_fails_before_other_inspection(self):
        with patch.object(cache, "command", return_value="9.12.2") as command, \
                self.assertRaisesRegex(cache.CacheMiss, "Requires GHC9.14.1"):
            cache.toolchain(Path.cwd())
        self.assertEqual(command.call_count, 1)


class RenamedInputContractTests(unittest.TestCase):
    def test_recorded_runtime_and_compiler_sources_use_actual_published_paths(self):
        root = Path(__file__).resolve().parents[2]
        self.assertEqual(("src/main/c/stdio-abi-probe.c",
                          "src/main/c/native-process-signal-api.c",
                          "src/test/c/native-process-signals-test.c",
                          "src/test/resources/core/original-signal-install-descriptor.json",
                          "src/test/resources/core/original-unix-signal-install-descriptor.json",
                          "src/main/java/thc/runtime/CoreOriginalStdio.java", "src/main/java/thc/runtime/OriginalStdioOp.java",
                          "src/main/java/thc/runtime/ProcessIdentity.java",
                          "src/main/java/thc/runtime/CoreEnvironmentForeign.java", "src/main/java/thc/runtime/EnvironmentOp.java", "src/main/java/thc/runtime/EnvironmentExpression.java",
                          "src/main/java/thc/runtime/VectorMemoryFamily.java",
                          "src/main/java/thc/runtime/VectorMemoryOp.java",
                          "src/main/java/thc/runtime/VectorReadCase.java",
                          "src/main/java/thc/runtime/CoreVectorMemory.java",
                          "src/main/java/thc/runtime/VectorByteArrayExpression.java",
                          "src/main/java/thc/runtime/VectorMemory.java"), cache.RUNTIME_INPUTS)
        with patch.object(cache, "toolchain", return_value={}):
            sources = cache.identity(root)["sources"]
        for name in (*cache.RUNTIME_INPUTS, "CMakeLists.txt", "cmake/FixtureTools.cmake",
                     ".github/scripts/fast_fixtures.py", ".github/scripts/fast-fixtures.json",
                     "src/core-symbols/THC/CoreSymbols.hs", *("src/compiler/THC/" + name + ".hs" for name in
                                             ("CBV", "Demands", "Plugin", "Sources", "Wired"))):
            self.assertIn(name, sources)
            self.assertEqual(cache.digest(root / name), sources[name])
        self.assertNotIn("src/main/java/thc/runtime/RetiredVectorMemory.java", sources)
        self.assertFalse(any(name.startswith("compiler/Thc/") for name in sources))
        self.assertIn("t/haskell-fixtures/PinnedAddressFixtures.hs", sources)
        self.assertIn("src/tools/primops/PrimopTools.hs", sources)
        declaration = "src/test/resources/core/original-unix-libc-descriptors.json"
        self.assertEqual(cache.digest(root / declaration), sources[declaration])
        for name in ("generate-scalar-signatures.py", "primop-coverage.py", "test-primop-coverage.py"):
            self.assertNotIn("bin/" + name, sources)
        for name in ("prepare-pinned-addresses.py", "pinned_address_model.py", "test-pinned-addresses.py"):
            self.assertNotIn("bin/" + name, sources)

    def test_required_cbv_modules_match_renamed_genuine_fixture_declarations(self):
        root = Path(__file__).resolve().parents[2]
        expected = {f"build/{folder}/{module}.{suffix}" for folder in ("core", "cbv-post-core")
                    for module in ("CBVAudit", "CBVCoercionAudit") for suffix in ("cbd",)}
        self.assertEqual(expected, {name for name in cache.REQUIRED if "CBV" in name})
        self.assertFalse(any("Cbv" in name for name in cache.REQUIRED))
        for module in ("CBVAudit", "CBVCoercionAudit"):
            source = (root / "t/fixtures/compiler" / (module + ".hs")).read_text()
            self.assertRegex(source, r"(?m)^module " + module + r"\b")


if __name__ == "__main__":
    unittest.main()
