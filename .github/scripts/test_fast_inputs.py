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
    def formatter_fixture(self):
        project = Path(__file__).resolve().parents[2]
        self.put(cache.WIRED_SOURCE, (project / cache.WIRED_SOURCE).read_bytes())
        self.current = cache.identity(self.root)
        manifest_path = 'build/original-stack-formatter/manifest.json'
        attempt = 'build/original-stack-formatter/run-3/'
        artifacts = {attempt + name for name in cache.original_stack_formatter_files(self.root)}
        for name in artifacts:
            self.put(name, b'{}\n' if name.endswith('.json') else b'\x00\x80\xff\n')
        (self.root / (attempt + 'native/formatter')).chmod(0o755)
        manifest = {'schema': 1, 'inputHashes': {
            **self.manifest['inputHashes'], cache.WIRED_SOURCE: self.current['sources'][cache.WIRED_SOURCE]},
            'artifactHashes': {name: cache.digest(self.root / name) for name in sorted(artifacts)}}
        self.put(manifest_path, json.dumps(manifest))
        return manifest_path, attempt, artifacts, manifest

    def test_formatter_catalog_and_exact_artifact_admission(self):
        project = Path(__file__).resolve().parents[2]
        modules, pins = cache.wired_catalog(project)
        self.assertEqual((39, 51), (len(modules), len(pins)))
        self.assertEqual('GHC.Internal.Enum', modules['GHC/Internal/Enum.hs'])
        self.assertLess(list(modules).index('GHC/Internal/Show.hs'), list(modules).index('GHC/Internal/Enum.hs'))
        self.assertLess(list(modules).index('GHC/Internal/Enum.hs'), list(modules).index('GHC/Internal/ClosureTypes.hs'))
        self.assertIn('  compiler/pinned-ghc-internal/GHC/Internal/Enum.hs\n',
                      (project / 'thc.cabal').read_text())
        for name, expected in pins.items():
            self.assertEqual(expected, cache.digest(project / 'compiler/pinned-ghc-internal' / name), name)
        files = cache.original_stack_formatter_files(project)
        self.assertEqual(79, len(files))
        self.assertIn('build/original-stack-formatter/manifest.json', DECLARED_REQUIRED)
        for attempt in ('run-1', 'run-42'):
            for suffix in files:
                name = f'build/original-stack-formatter/{attempt}/{suffix}'
                self.assertTrue(cache.allowed_payload(name, {}), name)
                if suffix == 'native/formatter':
                    self.assertEqual(0o755, cache.safe_mode(0o755, name))
                else:
                    with self.assertRaises(cache.CacheMiss):
                        cache.safe_mode(0o755, name)
        for suffix in ('run-0/native/formatter', 'run-01/native/formatter',
                       'run-1/previous-manifest.json', 'run-1/native/Main.o',
                       'run-1/native/other-formatter', 'run-1/logs/unknown.stdout',
                       'run-1/pre-core/THC.InterfaceClosure.json',
                       'run-1/originals/core/Extra.json', 'run-1/originals/generated/Extra.hs',
                       'run-1/originals/interfaces/GHC/Internal/Base.hi'):
            self.assertFalse(cache.allowed_payload('build/original-stack-formatter/' + suffix, {}), suffix)
        with self.assertRaises(cache.CacheMiss):
            cache.allowed_payload('build/original-stack-formatter/run-1/../native/formatter', {})

    def test_formatter_round_trip_preserves_bytes_mode_and_complete_attempt(self):
        manifest_path, attempt, artifacts, original = self.formatter_fixture()
        with patch.object(cache, 'REQUIRED', (*cache.REQUIRED, manifest_path)):
            manifest = self.pack()
            self.assertTrue(artifacts <= manifest['payload'].keys())
            self.remove_payload(manifest)
            cache.restore(self.root, self.current, self.bundle)
            self.assertEqual(original, json.loads((self.root / manifest_path).read_text()))
            self.assertEqual(b'\x00\x80\xff\n', (self.root / (attempt + 'logs/native-observations.stdout')).read_bytes())
            self.assertEqual(0o755, (self.root / (attempt + 'native/formatter')).stat().st_mode & 0o7777)
            self.remove_payload(manifest)
            for missing in ('logs/pre-audit.command.json', 'native/formatter',
                            'originals/generated/GHC/Internal/InfoProv/Types.hs'):
                with self.subTest(missing=missing):
                    changed = self.rewrite(lambda entries: [(member, data) for member, data in entries
                        if member.name != 'files/' + attempt + missing])
                    self.rejected_without_writes(changed)
            changed = self.rewrite(lambda entries: [(member, data + b'tampered' if member.name ==
                'files/' + attempt + 'logs/native-observations.stdout' else data) for member, data in entries])
            self.rejected_without_writes(changed)

    def test_formatter_rejects_unreviewed_mixed_or_missing_artifact_sets(self):
        _, attempt, _, original = self.formatter_fixture()
        for source, target in (
                ('logs/ghc-version.stdout', None),
                ('logs/ghc-version.stdout', 'build/original-stack-formatter/run-3/logs/unknown.stdout'),
                ('native/formatter', 'build/original-stack-formatter/run-0/native/formatter'),
                ('native/formatter', 'build/original-stack-formatter/run-4/native/formatter'),
                ('native/formatter', 'build/original-stack-formatter/run-3/../native/formatter')):
            doc = copy.deepcopy(original)
            value = doc['artifactHashes'].pop(attempt + source)
            if target is not None:
                doc['artifactHashes'][target] = value
            with self.subTest(target=target), self.assertRaises(cache.CacheMiss):
                cache.formatter_artifact_hashes(self.root, doc)

    def test_formatter_referenced_symlink_and_wired_source_drift_rejected(self):
        manifest_path, attempt, _, _ = self.formatter_fixture()
        with patch.object(cache, 'REQUIRED', (*cache.REQUIRED, manifest_path)):
            native = self.root / (attempt + 'native/formatter')
            target = self.temp_root / 'formatter'
            native.rename(target)
            native.symlink_to(target)
            with self.assertRaises(cache.CacheMiss):
                self.pack()
            native.unlink()
            target.rename(native)
            with (self.root / cache.WIRED_SOURCE).open('a') as stream:
                stream.write('\n-- changed producer\n')
            self.assertNotEqual(cache.cache_key(self.current), cache.cache_key(cache.identity(self.root)))
            self.current = cache.identity(self.root)
            with self.assertRaisesRegex(cache.CacheMiss, 'Stale or unkeyed tracked source'):
                self.pack()

    def test_formatter_catalog_fails_closed_on_nonliteral_or_unpinned_sources(self):
        self.formatter_fixture()
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
                self.assertTrue(cache.allowed_payload(name, {}), name)
            self.assertTrue(cache.native_executable(
                f"build/original-stack/{attempt}/native/original-stack-native"))
        for name in ("proof.json", "run-0/logs/ghc-version.stdout",
                     "run-01/logs/ghc-version.stdout", "run-1/previous-manifest.json",
                     "run-1/logs/unknown.stdout", "run-1/pre-core/Extra.json",
                     "run-1/native/OriginalStackAudit.o", "run-1/native/other-oracle",
                     "run-1/logs/native-invariants.sh", "run-1/retained/Decode.json"):
            self.assertFalse(cache.allowed_payload("build/original-stack/" + name, {}), name)
    def test_boxed_array_extensions_only_admit_reviewed_attempt_artifacts(self):
        self.assertIn('build/boxed-array-extensions/manifest.json', DECLARED_REQUIRED)
        for attempt in ('run-1', 'run-42'):
            for suffix in cache.BOXED_ARRAY_EXTENSION_FILES:
                name = f'build/boxed-array-extensions/{attempt}/{suffix}'
                self.assertTrue(cache.allowed_payload(name, {}), name)
            self.assertEqual(0o755, cache.safe_mode(0o755,
                f'build/boxed-array-extensions/{attempt}/native/boxed-array-extensions-oracle'))
        for suffix in ('proof.json', 'run-0/logs/ghc-info.stdout', 'run-01/logs/ghc-info.stdout',
                       'run-1/previous-manifest.json', 'run-1/logs/extra.stdout',
                       'run-1/pre-core/Extra.json', 'run-1/native/Main.o', 'run-1/native/other-oracle'):
            self.assertFalse(cache.allowed_payload('build/boxed-array-extensions/' + suffix, {}), suffix)
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
        self.vendor_name = "vendor/ghc-9.14.1/GHC/Internal/CString.hs"
        self.vendor = b"original GHC source\n"
        self.put("compiler/export-boot.py", "exception_sources = " + repr({
            "GHC/Internal/CString.hs": cache.sha(self.vendor)}) + "\n")
        for name in (cache.SELF, cache.WIRED_SOURCE, *cache.RUNTIME_INPUTS, *cache.COMPILER_BUILD_INPUTS,
                     "scripts/prepare-tests.sh", "examples/coverage.json",
                     "src/main/resources/thc/scalar-primop-signatures.json"):
            self.put(name, "source: " + name)
        self.put("src/main/kotlin/thc/runtime/Program.kt", "unrelated runtime\n")
        subprocess.run(["git", "-C", str(self.root), "add", "."], check=True)
        self.tc = {"ghcLibdir": str(self.temp_root / "toolchain/lib"),
                   "version": "9.14.1", "target": "x86_64-unknown-linux",
                   "javaRelease": {"path": str(self.temp_root / "jdk/release"), "sha256": "b" * 64}}
        self.tool_patch = patch.object(cache, "toolchain", side_effect=lambda root: copy.deepcopy(self.tc))
        self.tool_patch.start(); self.addCleanup(self.tool_patch.stop)
        self.required_patch = patch.object(cache, "REQUIRED", ("build/bytearray/manifest.json",))
        self.required_patch.start(); self.addCleanup(self.required_patch.stop)
        self.current = cache.identity(self.root)
        self.put(self.vendor_name, self.vendor)
        self.put("build/bytearray/oracle.tsv", "0\t17\n")
        self.put("build/core/Fixture.json", json.dumps({"module": "Fixture", "bindings": []}))
        self.manifest = {"inputHashes": {"scripts/prepare-tests.sh": self.current["sources"]["scripts/prepare-tests.sh"],
                                         self.vendor_name: cache.sha(self.vendor)},
                         "artifactHashes": {"build/bytearray/oracle.tsv": cache.digest(self.root / "build/bytearray/oracle.tsv")}}
        self.write_manifest()
        self.bundle = self.temp_root / "bundle.tar.gz"

    def put(self, name, content):
        p = self.root / name
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_bytes(content if isinstance(content, bytes) else content.encode())

    def write_manifest(self):
        self.put("build/bytearray/manifest.json", json.dumps(self.manifest))

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

    def rejected_without_writes(self, source):
        before = {str(p.relative_to(self.root)): p.read_bytes() for p in self.root.rglob("*") if p.is_file()}
        with self.assertRaises(cache.CacheMiss):
            cache.restore(self.root, self.current, source)
        after = {str(p.relative_to(self.root)): p.read_bytes() for p in self.root.rglob("*") if p.is_file()}
        self.assertEqual(before, after)

    def test_round_trip_preserves_original_provenance_and_all_bytes(self):
        original = (self.root / "build/bytearray/manifest.json").read_bytes()
        manifest = self.pack(); self.remove_payload(manifest)
        result = cache.restore(self.root, self.current, self.bundle)
        self.assertEqual(manifest, result)
        self.assertEqual(original, (self.root / "build/bytearray/manifest.json").read_bytes())
        for name, expected in result["payload"].items():
            self.assertEqual(expected, cache.digest(self.root / name))
        # Existing identical files are accepted, never overwritten.
        p = self.root / "build/bytearray/oracle.tsv"; before = p.stat().st_mtime_ns
        cache.restore(self.root, self.current, self.bundle)
        self.assertEqual(before, p.stat().st_mtime_ns)

    def test_noncanonical_workspace_alias_is_still_rejected(self):
        alias = self.temp_root / "workspace-alias"
        alias.symlink_to(self.root, target_is_directory=True)
        with self.assertRaisesRegex(cache.CacheMiss, "Symlink/noncanonical destination"):
            cache.identity(alias)
        self.assertEqual(self.current, cache.identity(self.root))

    def test_authoritative_inputs_cannot_be_omitted_by_producer(self):
        self.manifest["inputHashes"] = {}
        self.write_manifest(); manifest = self.pack(); self.remove_payload(manifest)
        self.put("examples/coverage.json", "changed but omitted by producer")
        current = cache.identity(self.root)
        self.assertNotEqual(cache.cache_key(current), cache.cache_key(self.current))
        with self.assertRaises(cache.CacheMiss):
            cache.restore(self.root, current, self.bundle)

    def test_unrelated_runtime_change_reuses_key_but_recorded_runtime_change_misses(self):
        self.put("src/main/kotlin/thc/runtime/Program.kt", "new lowering")
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

    def test_original_source_artifact_vendor_hash_mismatch_rejected(self):
        for section, name in (("inputHashes", "scripts/prepare-tests.sh"),
                              ("artifactHashes", "build/bytearray/oracle.tsv"),
                              ("inputHashes", self.vendor_name)):
            with self.subTest(name=name):
                old = self.manifest[section][name]; self.manifest[section][name] = "f" * 64
                self.write_manifest()
                with self.assertRaises(cache.CacheMiss): self.pack()
                self.assertFalse(self.bundle.exists())
                self.manifest[section][name] = old

    def test_unkeyed_runtime_source_fails_closed(self):
        self.manifest["inputHashes"]["src/main/kotlin/thc/runtime/Program.kt"] = cache.digest(
            self.root / "src/main/kotlin/thc/runtime/Program.kt")
        self.write_manifest()
        with self.assertRaises(cache.CacheMiss): self.pack()

    def test_renamed_runtime_provenance_is_keyed_and_old_path_is_not_aliased(self):
        for name in cache.RUNTIME_INPUTS:
            self.manifest["inputHashes"][name] = self.current["sources"][name]
        self.write_manifest()
        manifest = self.pack(); self.remove_payload(manifest)
        cache.restore(self.root, self.current, self.bundle)
        self.manifest["inputHashes"]["src/main/kotlin/thc/runtime/CoreVectorMemory.kt"] = "a" * 64
        self.write_manifest()
        with self.assertRaises(cache.CacheMiss):
            cache.inventory(self.root, self.current, lambda name: (self.root / name).read_bytes(), [])

    def test_old_cbv_payload_cannot_satisfy_renamed_required_core(self):
        self.put("build/core/CbvAudit.json", json.dumps({"module": "CbvAudit", "bindings": []}))
        manifest = self.pack(); self.remove_payload(manifest)
        self.assertIn("build/core/CbvAudit.json", manifest["payload"])
        self.assertNotIn("build/core/CBVAudit.json", manifest["payload"])
        # Archive names remain exact even on a case-insensitive host filesystem.
        with patch.object(cache, "REQUIRED", ("build/core/CBVAudit.json",)):
            self.rejected_without_writes(self.bundle)

    def test_installed_interfaces_use_the_toolchain_version_gate(self):
        interface = Path(self.tc["ghcLibdir"]) / "pkg/Foo.dyn_hi"
        interface.parent.mkdir(parents=True); interface.write_bytes(b"actual interface")
        self.manifest["installedShortInterface"] = {"path": str(interface), "sha256": cache.digest(interface)}
        self.manifest["sources"] = [{"path": str(self.root / "scripts/prepare-tests.sh"),
            "sha256": self.current["sources"]["scripts/prepare-tests.sh"], "url": "original/source"}]
        self.write_manifest(); manifest = self.pack(); self.remove_payload(manifest)
        interface.write_bytes(b"modified same package/version")
        digest = cache.digest
        def workspace_digest(path):
            self.assertFalse(Path(path).is_relative_to(Path(self.tc["ghcLibdir"])))
            return digest(path)
        with patch.object(cache, "digest", side_effect=workspace_digest):
            cache.restore(self.root, self.current, self.bundle)

    def test_conflicting_original_records_and_external_escape(self):
        self.manifest["sources"] = [{"path": "scripts/prepare-tests.sh", "sha256": "f" * 64}]
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
                m = tarfile.TarInfo("files/build/bytearray/evil.json"); m.type = kind; m.linkname = "/tmp/escape"
                return es + [(m, b"")]
            with self.subTest(kind=kind): self.rejected_without_writes(self.rewrite(mutate))

    def test_self_consistent_unknown_or_tracked_payload_is_not_accepted(self):
        manifest = self.pack(); self.remove_payload(manifest)
        for name in ("build/fast/pass.json", "build/test-results/test/pass.xml", "build/classes/Evil.class",
                     "build/bytearray/evil.sh", "scripts/prepare-tests.sh", "vendor/ghc-9.14.1/unknown.hs"):
            def mutate(es):
                doc = json.loads(es[0][1]); doc["payload"][name] = cache.sha(b"evil")
                return [(es[0][0], cache.canonical(doc)), *es[1:], (tarfile.TarInfo("files/"+name), b"evil")]
            with self.subTest(name=name): self.rejected_without_writes(self.rewrite(mutate))

    def test_symlink_destination_and_conflicting_file_preserved(self):
        manifest = self.pack(); self.remove_payload(manifest)
        target = self.root / "build/bytearray/oracle.tsv"
        target.symlink_to(self.temp_root / "missing-target")
        with self.assertRaises(cache.CacheMiss): cache.restore(self.root, self.current, self.bundle)
        self.assertTrue(target.is_symlink()); target.unlink()
        self.put("build/bytearray/oracle.tsv", "existing different evidence")
        self.rejected_without_writes(self.bundle)

    def test_parent_symlink_and_non_directory_conflict(self):
        manifest = self.pack(); self.remove_payload(manifest)
        directory = self.root / "build/bytearray"; directory.rmdir()
        outside = self.temp_root / "outside"; outside.mkdir()
        directory.symlink_to(outside, target_is_directory=True)
        with self.assertRaises(cache.CacheMiss): cache.restore(self.root, self.current, self.bundle)
        self.assertEqual([], list(outside.iterdir())); directory.unlink()
        directory.write_bytes(b"not a directory")
        self.rejected_without_writes(self.bundle)

    def test_original_provenance_payload_inventory_cannot_be_extended(self):
        manifest = self.pack(); self.remove_payload(manifest)
        def mutate(es):
            doc = json.loads(es[0][1]); doc["payload"]["build/bytearray/unreferenced.json"] = cache.sha(b"{}")
            return [(es[0][0], cache.canonical(doc)), *es[1:],
                    (tarfile.TarInfo("files/build/bytearray/unreferenced.json"), b"{}")]
        self.rejected_without_writes(self.rewrite(mutate))

    def test_archive_file_directory_collision(self):
        manifest = self.pack(); self.remove_payload(manifest)
        def mutate(es):
            doc = json.loads(es[0][1]); name = "build/bytearray/oracle.tsv/child.json"
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
        self.pack()
        identity_file = self.temp_root / "identity.json"
        identity_file.write_text(json.dumps(self.current))
        command = ["restore", "--root", str(self.root), "--identity", str(identity_file),
                   "--bundle", str(self.bundle)]
        with patch.object(cache, "identity", wraps=cache.identity) as identify, patch("sys.stderr", io.StringIO()):
            self.assertEqual(0, cache.main(command))
            identify.assert_called_once_with(self.root)
        self.put("scripts/prepare-tests.sh", "changed after the key step")
        with patch.object(cache, "identity", wraps=cache.identity) as identify, patch("sys.stderr", io.StringIO()):
            self.assertEqual(1, cache.main(command))
            identify.assert_called_once_with(self.root)

    def test_payload_scope_has_no_runtime_or_test_outputs(self):
        pins = cache.vendor_pins(self.root)
        self.assertTrue(cache.allowed_payload("build/unsafe-equality/api/predicate", pins))
        self.assertTrue(cache.allowed_payload("build/aggregate-layout/pre-ghc/A.dyn_o", pins))
        self.assertTrue(cache.allowed_payload("build/compiler/plugin.json", pins))
        self.assertTrue(cache.allowed_payload("build/compiler/libHSthc-0.1.0.0-inplace-ghc9.14.1.dylib", pins))
        self.assertTrue(cache.allowed_payload("build/compiler/libHSthc-0.1.0.0-inplace-ghc9.14.1.so", pins))
        for name in ("build/install/thc/lib/runtime.jar", "build/test-results/test/TEST.xml",
                     "build/reports/tests/index.html", "build/fast/native-inputs.tar.gz",
                     "build/compiler/thc-core-plugin.conf", "build/compiler/package.conf.d/package.cache",
                     "dist-newstyle/packagedb/ghc-9.14.1/package.cache", ".gradle/cache.bin"):
            self.assertFalse(cache.allowed_payload(name, pins), name)

    def test_original_native_executable_names_and_cstring_are_in_scope(self):
        pins = cache.vendor_pins(self.root)
        for name in ("state-tuple", "tuple-input", "tuple-return", "empty-tuple-input"):
            self.assertTrue(cache.allowed_payload(f"build/{name}/native/{name}", pins))
        self.assertIn("build/map/boot-core", cache.CORE_DIRS)

    def test_word_floating_manifest_and_semantic_payload_are_cache_inputs(self):
        self.assertIn("build/word-floating/manifest.json", DECLARED_REQUIRED)
        for name in ("oracle.tsv", "pre-audit.json", "post-audit.json",
                     "pre-core/WordFloatingAudit.json", "post-core/WordFloatingAudit.json"):
            self.assertTrue(cache.allowed_payload("build/word-floating/" + name, {}), name)
        for name in ("test-results/results.json", "classes/Main.class", "unreviewed.sh"):
            self.assertFalse(cache.allowed_payload("build/word-floating/" + name, {}), name)

    def test_original_stdio_inventory_is_exact_and_manifest_is_required(self):
        # setUp replaces REQUIRED for the small archive tests.
        self.assertIn("build/original-stdio/manifest.json", DECLARED_REQUIRED)
        self.assertIn("original-stdio", cache.MANIFEST_DIRS)
        self.assertIn("original-stdio", cache.BUILD_DIRS)
        self.assertEqual(630, len(cache.ORIGINAL_STDIO_OUTPUTS))
        for name in cache.ORIGINAL_STDIO_OUTPUTS:
            self.assertTrue(cache.allowed_payload(name, {}), name)
        self.assertIn("build/original-stdio/native/original-stdio-oracle", cache.NATIVE_EXECUTABLES)
        for name in ("other.json", "unknown.stdout", "logs/extra.stdout", "logs/native-144.stdout",
                     "logs/native-000.sh", "logs/pre-export.stdout.extra", "results/144.txt", "results/00.txt",
                     "native/other-oracle", "pre/ghc/OriginalStdioAudit.o", "post/core/Unreviewed.json",
                     "test-results/pass.json", "reports/pass.json", "previous-manifests/stale.json",
                     "expected.json", "pre/proofs.json", "logs/pre-audit-unknown.stdout"):
            self.assertFalse(cache.allowed_payload("build/original-stdio/" + name, {}), name)
        self.assertFalse(cache.allowed_payload("build/bytearray/logs/pre-export.stdout", {}))

    def test_original_stdio_archive_round_trip_preserves_complete_artifact_inventory(self):
        manifest_path = "build/original-stdio/manifest.json"
        binary = "build/original-stdio/native/original-stdio-oracle"
        artifacts = cache.ORIGINAL_STDIO_OUTPUTS - {manifest_path}
        for name in artifacts:
            self.put(name, b"{}\n" if name.endswith(".json") else b"\x00\x80\xff\n")
        (self.root / binary).chmod(0o755)
        original = json.dumps({"schema": 1, "installedArtifactsHashed": False,
            "inputHashes": self.manifest["inputHashes"],
            "artifactHashes": {name: cache.digest(self.root / name) for name in sorted(artifacts)}})
        self.put(manifest_path, original)
        with patch.object(cache, "REQUIRED", (*cache.REQUIRED, manifest_path)):
            manifest = self.pack()
            self.assertTrue(cache.ORIGINAL_STDIO_OUTPUTS <= manifest["payload"].keys())
            self.remove_payload(manifest)
            cache.restore(self.root, self.current, self.bundle)
            self.assertEqual(original, (self.root / manifest_path).read_text())
            self.assertEqual(0o755, (self.root / binary).stat().st_mode & 0o7777)
            for name in cache.ORIGINAL_STDIO_OUTPUTS:
                self.assertEqual(manifest["payload"][name], cache.digest(self.root / name), name)
            self.remove_payload(manifest)
            changed = self.rewrite(lambda entries: [(member, data) for member, data in entries
                                                    if member.name != "files/build/original-stdio/logs/native-000.stdout"])
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

    def test_original_stdio_forged_unreviewed_artifact_is_rejected(self):
        unknown = "build/original-stdio/logs/unreviewed.stdout"
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
        self.write_manifest(); original = (self.root/"build/bytearray/manifest.json").read_bytes()
        m = self.pack();self.assertIn(raw,m["external"]);self.remove_payload(m)
        cache.restore(self.root,self.current,self.bundle)
        self.assertEqual(original,(self.root/"build/bytearray/manifest.json").read_bytes())
        self.assertFalse(cache.external_allowed(str(folder/"../../../etc/passwd"),self.current))

    def test_native_execute_mode_preserved_and_special_or_data_execute_modes_rejected(self):
        path = self.root/"build/bytearray/oracle.tsv"
        # A real executable has a native executable name, not an oracle TSV.
        name = "build/bytearray/native/oracle"
        self.put(name,b"native bytes");(self.root/name).chmod(0o755)
        self.manifest["artifactHashes"][name]=cache.digest(self.root/name);self.write_manifest()
        m=self.pack();self.remove_payload(m);cache.restore(self.root,self.current,self.bundle)
        self.assertEqual(0o755,(self.root/name).stat().st_mode & 0o7777)
        with self.assertRaises(cache.CacheMiss):cache.safe_mode(0o4755,name)
        with self.assertRaises(cache.CacheMiss):cache.safe_mode(0o777,name)
        with self.assertRaises(cache.CacheMiss):cache.safe_mode(0o755,"build/core/Fixture.json")

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
            with patch.dict(os.environ, {"JAVA_HOME": str(release.parent), "GHC_ENVIRONMENT": "-"}, clear=True), \
                    patch.object(cache, "command", side_effect=responses), \
                    patch.object(cache, "digest", wraps=cache.digest) as digest, \
                    patch.object(Path, "rglob", side_effect=AssertionError("Do not scan GHC")):
                result = cache.toolchain(root)
            digest.assert_called_once_with(release)
            self.assertEqual(result["version"], "9.14.1")
            self.assertEqual(result["target"], "x86_64-unknown-linux")
            self.assertNotIn("installedAbiSha256", result)

    def test_wrong_ghc_version_fails_before_other_inspection(self):
        with patch.object(cache, "command", return_value="9.12.2") as command, \
                self.assertRaisesRegex(cache.CacheMiss, "Requires GHC9.14.1"):
            cache.toolchain(Path.cwd())
        self.assertEqual(command.call_count, 1)


class RenamedInputContractTests(unittest.TestCase):
    def test_recorded_runtime_and_compiler_sources_use_actual_published_paths(self):
        root = Path(__file__).resolve().parents[2]
        self.assertEqual(("src/main/kotlin/thc/runtime/VectorMemoryPrimitives.kt",
                          "src/main/java/thc/runtime/DoubleX2.java"), cache.RUNTIME_INPUTS)
        with patch.object(cache, "toolchain", return_value={}):
            sources = cache.identity(root)["sources"]
        for name in (*cache.RUNTIME_INPUTS, *("compiler/THC/" + name + ".hs" for name in
                                             ("CBV", "Demands", "Plugin", "Sources", "Wired"))):
            self.assertIn(name, sources)
            self.assertEqual(cache.digest(root / name), sources[name])
        self.assertNotIn("src/main/kotlin/thc/runtime/CoreVectorMemory.kt", sources)
        self.assertFalse(any(name.startswith("compiler/Thc/") for name in sources))

    def test_required_cbv_modules_match_renamed_genuine_fixture_declarations(self):
        root = Path(__file__).resolve().parents[2]
        expected = {f"build/{folder}/{module}.json" for folder in ("core", "cbv-post-core")
                    for module in ("CBVAudit", "CBVJoinAudit", "CBVCoercionAudit")}
        self.assertEqual(expected, {name for name in cache.REQUIRED if "CBV" in name})
        self.assertFalse(any("Cbv" in name for name in cache.REQUIRED))
        for module in ("CBVAudit", "CBVJoinAudit", "CBVCoercionAudit"):
            source = (root / "compiler/test-fixtures" / (module + ".hs")).read_text()
            self.assertRegex(source, r"(?m)^module " + module + r"\b")


if __name__ == "__main__":
    unittest.main()
