import copy
import io
import json
import os
from pathlib import Path
import platform
import shutil
import subprocess
import sys
import tarfile
import tempfile
import unittest
from unittest.mock import patch

import library_bundle as bundle


class LibraryBundleTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve() / "checkout"
        self.root.mkdir()
        self.source = self.root / "src/LibraryCheck.kt"
        self.source.parent.mkdir()
        self.source.write_text("verified source\n")
        subprocess.run(["git", "init", "-q", str(self.root)], check=True)
        subprocess.run(["git", "-C", str(self.root), "add", "src"], check=True)
        subprocess.run(["git", "-C", str(self.root), "-c", "user.name=Test", "-c",
                        "user.email=test@example.invalid", "commit", "-qm", "fixture"], check=True)
        java = self.root.parent / "jdk"
        java.mkdir()
        (java / "release").write_text('JAVA_VERSION="25.0.4.1"\nGRAALVM_VERSION="25.3.4.1"\n')
        self.env = {"GITHUB_REPOSITORY": "ekmett/thc", "GITHUB_RUN_ID": "123", "GITHUB_RUN_ATTEMPT": "1",
                    "GITHUB_SHA": bundle.git(self.root, "rev-parse", "HEAD"), "GITHUB_WORKSPACE": str(self.root),
                    "RUNNER_OS": {"Linux": "Linux", "Darwin": "macOS"}[platform.system()],
                    "RUNNER_ARCH": {"x86_64": "X64", "arm64": "ARM64", "aarch64": "ARM64"}[platform.machine()],
                    "JAVA_HOME": str(java)}
        self.environ = patch.dict(os.environ, self.env)
        self.environ.start()
        self.addCleanup(self.environ.stop)
        files = {"vendor/source.hs": b"original upstream source\n",
                 "build/libraries/group/core.json": b'{"source":"native Core"}',
                 "build/libraries/group/audit.json": b'{"accepted":true}',
                 "build/libraries/group/entry.audit.json": b'{"accepted":true}',
                 "build/libraries/group/provenance.json": b'{"compiler":"9.14.1","sourcePatches":[]}',
                 "build/libraries/oracle.tsv": b"entry\t1\t7\n",
                 "build/libraries/oracle-validation.json": b'{"compiler":"9.14.1"}',
                 "build/libraries/native/library-oracle": b"native binary bytes, never executed",
                 "build/install/thc/lib/thc-test.jar": b"JAR from producer"}
        for name, data in files.items():
            path = self.root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(data)
        self.cases = {"schema": 1, "inputHashes": {
            str(self.source): bundle.digest(self.source),
            str(self.root / "vendor/source.hs"): bundle.digest(self.root / "vendor/source.hs")},
            "artifactHashes": {str(self.root / name): bundle.digest(self.root / name)
                               for name in files if name.startswith("build/libraries/")},
            "groups": [{"id": "group", "modules": [str(self.root / "build/libraries/group/core.json")],
                        "audit": str(self.root / "build/libraries/group/audit.json"),
                        "entries": [{"name": "entry", "audit": str(self.root / "build/libraries/group/entry.audit.json")}]}]}
        self.write_cases()
        self.archive = self.root.parent / "bundle.tar.gz"

    def write_cases(self):
        (self.root / bundle.CASES).write_text(json.dumps(self.cases))

    def pack(self):
        self.manifest = bundle.pack(self.root, self.archive)

    def clear_payload(self):
        shutil.rmtree(self.root / "build")
        shutil.rmtree(self.root / "vendor")

    def rewrite(self, manifest_change=None, payload_change=None, extra=None):
        output = self.root.parent / "changed.tar.gz"
        with tarfile.open(self.archive) as old, tarfile.open(output, "w:gz") as new:
            for item in old.getmembers():
                data = old.extractfile(item).read()
                if item.name == "bundle.json" and manifest_change:
                    manifest = json.loads(data)
                    manifest_change(manifest)
                    data = json.dumps(manifest).encode()
                if payload_change:
                    data = payload_change(item.name, data)
                item.size = len(data)
                new.addfile(item, io.BytesIO(data))
            if extra:
                item, data = extra
                new.addfile(item, io.BytesIO(data) if item.isfile() else None)
        return output

    def test_round_trip_preserves_original_manifest_provenance_and_native_bytes(self):
        original = (self.root / bundle.CASES).read_bytes()
        self.pack()
        self.clear_payload()
        receipt = bundle.restore(self.root, self.archive)
        self.assertEqual((self.root / bundle.CASES).read_bytes(), original)
        bundle.check_files(self.root, self.manifest["payloadFiles"])
        self.assertEqual(receipt["identity"], self.manifest["identity"])
        self.assertEqual(receipt["sourceFilesVerified"], 1)
        self.assertEqual(self.source.read_text(), "verified source\n")
        # Extraction writes data, not an executable native binary or checkout source.
        self.assertFalse(os.access(self.root / "build/libraries/native/library-oracle", os.X_OK))

    def test_run_attempt_platform_source_and_jdk_mismatches_fail_closed(self):
        self.pack()
        self.clear_payload()
        for key in self.manifest["identity"]:
            with self.subTest(key=key):
                path = self.rewrite(lambda m: m["identity"].update({key: "other"}))
                with self.assertRaisesRegex(RuntimeError, "mismatch; rerun all"):
                    bundle.restore(self.root, path)
                self.assertFalse((self.root / "build").exists())

    def test_failed_job_only_rerun_cannot_use_prior_attempt_bundle(self):
        self.pack()
        with patch.dict(os.environ, {"GITHUB_RUN_ATTEMPT": "2"}):
            with self.assertRaisesRegex(RuntimeError, "rerun all"):
                bundle.restore(self.root, self.archive)

    def test_payload_corruption_fails_before_any_restore(self):
        self.pack()
        self.clear_payload()
        path = self.rewrite(payload_change=lambda name, data: data + b"corrupt"
                            if name.endswith("thc-test.jar") else data)
        with self.assertRaisesRegex(RuntimeError, "payload hash mismatch"):
            bundle.restore(self.root, path)
        self.assertFalse((self.root / "build").exists())
        self.assertFalse((self.root / "vendor").exists())

    def test_missing_or_extra_declared_artifact_is_rejected(self):
        self.pack()
        for change in (lambda m: m["payloadFiles"].pop("build/libraries/group/core.json"),
                       lambda m: m["payloadFiles"].update({"build/extra": "0" * 64})):
            with self.subTest(change=change):
                with self.assertRaisesRegex(RuntimeError, "members differ"):
                    bundle.restore(self.root, self.rewrite(change))

    def test_links_duplicate_members_and_escaping_paths_are_rejected(self):
        self.pack()
        for name, kind in (("files/build/link", tarfile.SYMTYPE),
                           ("files/build/link", tarfile.LNKTYPE),
                           ("bundle.json", tarfile.REGTYPE),
                           ("../outside", tarfile.REGTYPE),
                           ("/absolute", tarfile.REGTYPE)):
            with self.subTest(name=name, kind=kind):
                item = tarfile.TarInfo(name)
                item.type = kind
                item.linkname = "../../outside"
                with self.assertRaises(RuntimeError):
                    bundle.restore(self.root, self.rewrite(extra=(item, b"")))
        with self.assertRaisesRegex(RuntimeError, "Unsafe bundle path"):
            bundle.restore(self.root, self.rewrite(lambda m: m["payloadFiles"].update({"../outside": "0" * 64})))

    def test_tracked_source_changes_are_never_overwritten(self):
        self.pack()
        self.source.write_text("changed source\n")
        with self.assertRaisesRegex(RuntimeError, "checkout is dirty"):
            bundle.restore(self.root, self.archive)
        self.assertEqual(self.source.read_text(), "changed source\n")

    def test_unfingerprinted_entry_audit_or_external_input_cannot_be_packaged(self):
        original = copy.deepcopy(self.cases)
        for edit, message in (
            (lambda m: m["artifactHashes"].pop(str(self.root / "build/libraries/group/entry.audit.json")),
             "Unfingerprinted"),
            (lambda m: m["inputHashes"].update({str(self.root.parent / "secret"): "0" * 64}), "escapes checkout"),
        ):
            with self.subTest(message=message):
                self.cases = copy.deepcopy(original)
                edit(self.cases)
                self.write_cases()
                with self.assertRaisesRegex(RuntimeError, message):
                    self.pack()

    def test_symlink_destination_and_conflicting_existing_file_are_rejected(self):
        self.pack()
        shutil.rmtree(self.root / "vendor")
        (self.root / "vendor").symlink_to(self.root.parent, target_is_directory=True)
        with self.assertRaisesRegex(RuntimeError, "traverses a link"):
            bundle.restore(self.root, self.archive)
        (self.root / "vendor").unlink()
        (self.root / "build/libraries/oracle.tsv").write_text("different oracle")
        with self.assertRaisesRegex(RuntimeError, "Conflicting existing"):
            bundle.restore(self.root, self.archive)

    def test_unexpected_classpath_jar_and_unpinned_jdk_are_rejected(self):
        self.pack()
        (self.root / bundle.LIB / "extra.jar").write_text("not producer runtime")
        with self.assertRaisesRegex(RuntimeError, "Unexpected installed runtime JAR"):
            bundle.restore(self.root, self.archive)
        release = Path(os.environ["JAVA_HOME"]) / "release"
        release.write_text('JAVA_VERSION="25"\nGRAALVM_VERSION="other"\n')
        with self.assertRaisesRegex(RuntimeError, "pinned GraalVM"):
            bundle.restore(self.root, self.archive)

    def test_receipt_link_or_nonregular_destination_cannot_modify_verified_source(self):
        self.pack()
        self.clear_payload()
        receipt = self.root / bundle.RECEIPT
        receipt.parent.mkdir()
        receipt.symlink_to(self.source)
        with self.assertRaisesRegex(RuntimeError, "traverses a link"):
            bundle.restore(self.root, self.archive)
        self.assertEqual(self.source.read_text(), "verified source\n")
        self.assertFalse((self.root / "vendor").exists())
        receipt.unlink()
        os.link(self.source, receipt)
        with self.assertRaisesRegex(RuntimeError, "receipt already exists"):
            bundle.restore(self.root, self.archive)
        self.assertEqual(self.source.read_text(), "verified source\n")
        self.assertFalse((self.root / "vendor").exists())
        receipt.unlink()
        receipt.mkdir()
        with self.assertRaisesRegex(RuntimeError, "receipt already exists"):
            bundle.restore(self.root, self.archive)
        self.assertFalse((self.root / "vendor").exists())

    def test_verification_receipt_cannot_overlap_hashed_payload(self):
        receipt = self.root / bundle.RECEIPT
        receipt.write_text("payload claimed as receipt")
        self.cases["artifactHashes"][str(receipt)] = bundle.digest(receipt)
        self.write_cases()
        with self.assertRaisesRegex(RuntimeError, "collides with verification receipt"):
            self.pack()

    def test_fifo_runtime_or_cases_is_rejected_without_blocking(self):
        for name in (bundle.LIB + "/extra.jar", bundle.CASES):
            with self.subTest(name=name):
                path = self.root / name
                if path.exists():
                    path.unlink()
                os.mkfifo(path)
                result = subprocess.run([sys.executable, bundle.__file__, "pack", "--output", str(self.archive)],
                                        cwd=self.root, text=True, stdout=subprocess.PIPE,
                                        stderr=subprocess.STDOUT, timeout=3)
                self.assertNotEqual(result.returncode, 0)
                self.assertIn("regular file", result.stdout)
                path.unlink()


if __name__ == "__main__":
    unittest.main()
