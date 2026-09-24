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


class FastInputTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name) / "workspace"
        self.root.mkdir()
        subprocess.run(["git", "init", "-q", str(self.root)], check=True)
        self.vendor_name = "vendor/ghc-9.14.1/GHC/Internal/CString.hs"
        self.vendor = b"original GHC source\n"
        self.put("compiler/export-boot.py", "exception_sources = " + repr({
            "GHC/Internal/CString.hs": cache.sha(self.vendor)}) + "\n")
        for name in (cache.SELF, *cache.RUNTIME_INPUTS, "scripts/prepare-tests.sh", "examples/coverage.json",
                     "src/main/resources/thc/scalar-primop-signatures.json"):
            self.put(name, "source: " + name)
        self.put("src/main/kotlin/thc/runtime/Program.kt", "unrelated runtime\n")
        subprocess.run(["git", "-C", str(self.root), "add", "."], check=True)
        self.tc = {"ghcLibdir": str(Path(self.temp.name) / "toolchain/lib"),
                   "version": "9.14.1", "installedAbiSha256": "a" * 64,
                   "javaRelease": {"path": str(Path(self.temp.name) / "jdk/release"), "sha256": "b" * 64}}
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
        self.bundle = Path(self.temp.name) / "bundle.tar.gz"

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
        changed = Path(self.temp.name) / "changed.tar.gz"
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

    def test_helper_tool_package_interface_jdk_platform_workspace_changes_miss(self):
        self.pack()
        for field in ("schema", "workspace", "platform", "toolchain"):
            current = copy.deepcopy(self.current); current[field] = "changed"
            with self.subTest(field=field), self.assertRaises(cache.CacheMiss):
                cache.restore(self.root, current, self.bundle)
        for field in ("version", "installedAbiSha256", "javaRelease"):
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

    def test_original_list_records_absolute_paths_and_external_interfaces(self):
        interface = Path(self.tc["ghcLibdir"]) / "pkg/Foo.dyn_hi"
        interface.parent.mkdir(parents=True); interface.write_bytes(b"actual interface")
        self.manifest["installedShortInterface"] = {"path": str(interface), "sha256": cache.digest(interface)}
        self.manifest["sources"] = [{"path": str(self.root / "scripts/prepare-tests.sh"),
            "sha256": self.current["sources"]["scripts/prepare-tests.sh"], "url": "original/source"}]
        self.write_manifest(); manifest = self.pack(); self.remove_payload(manifest)
        interface.write_bytes(b"modified same package/version")
        self.rejected_without_writes(self.bundle)

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
        target.symlink_to(Path(self.temp.name) / "missing-target")
        with self.assertRaises(cache.CacheMiss): cache.restore(self.root, self.current, self.bundle)
        self.assertTrue(target.is_symlink()); target.unlink()
        self.put("build/bytearray/oracle.tsv", "existing different evidence")
        self.rejected_without_writes(self.bundle)

    def test_parent_symlink_and_non_directory_conflict(self):
        manifest = self.pack(); self.remove_payload(manifest)
        directory = self.root / "build/bytearray"; directory.rmdir()
        outside = Path(self.temp.name) / "outside"; outside.mkdir()
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
        output = Path(self.temp.name) / "identity.json"
        stdout, stderr = io.StringIO(), io.StringIO()
        with patch("sys.stdout", stdout), patch("sys.stderr", stderr):
            self.assertEqual(0, cache.main(["key", "--root", str(self.root), "--output", str(output)]))
            self.assertEqual(cache.cache_key(self.current)+"\n", stdout.getvalue())
            self.assertEqual(1, cache.main(["restore", "--root", str(self.root), "--identity", str(output),
                                          "--bundle", str(self.bundle)]))
        self.assertIn("MISS:", stderr.getvalue())

    def test_payload_scope_has_no_runtime_or_test_outputs(self):
        pins = cache.vendor_pins(self.root)
        self.assertTrue(cache.allowed_payload("build/unsafe-equality/api/predicate", pins))
        self.assertTrue(cache.allowed_payload("build/aggregate-layout/pre-ghc/A.dyn_o", pins))
        for name in ("build/install/thc/lib/runtime.jar", "build/test-results/test/TEST.xml",
                     "build/reports/tests/index.html", "build/fast/native-inputs.tar.gz",
                     "build/compiler/thc-core-plugin.conf", ".gradle/cache.bin"):
            self.assertFalse(cache.allowed_payload(name, pins), name)

    def test_original_native_executable_names_and_cstring_are_in_scope(self):
        pins = cache.vendor_pins(self.root)
        for name in ("state-tuple", "tuple-input", "tuple-return", "empty-tuple-input"):
            self.assertTrue(cache.allowed_payload(f"build/{name}/native/{name}", pins))
        self.assertIn("build/map/boot-core", cache.CORE_DIRS)

    def test_legacy_launcher_digest_and_explicit_binary_digest_are_distinct(self):
        launcher = Path(self.temp.name)/"launcher"; launcher.write_bytes(b"launcher script")
        binary = Path(self.temp.name)/"binary"; binary.write_bytes(b"actual ELF")
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


if __name__ == "__main__":
    unittest.main()
