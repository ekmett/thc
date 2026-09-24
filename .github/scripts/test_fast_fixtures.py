# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

"""Fixture selection and persistent-stamp tests; no compiler or JVM is run."""

from contextlib import ExitStack, redirect_stderr
import hashlib
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent))
import fast_fixtures


class FixturePreparationTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
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

    def test_word_floating_has_focused_and_full_preparation(self):
        project = Path(__file__).resolve().parents[2]
        manifest, owners = fast_fixtures._manifest(project)
        group = manifest["groups"]["word-floating"]
        self.assertEqual("word-floating", owners["thc.runtime.WordFloatingTest"])
        self.assertEqual([{"argv": ["cabal", "run", "exe:thc-fixtures", "--offline", "--", "word-floating"]}], group["commands"])
        self.assertEqual(["build/word-floating"], group["outputs"])
        self.assertTrue(all((project / name).is_file() for name in group["sources"]))
        self.assertIn('"$fixture_bin" word-floating', (project / "scripts/prepare-tests.sh").read_text().splitlines())
        self.assertEqual(fast_fixtures.FULL_PREPARATION_PLAN, fast_fixtures._preparation_plan(project))
        self.assertIn("build/word-floating", fast_fixtures.FULL_OUTPUT_ROOTS)
        self.assertIn("build/word-floating/manifest.json", fast_fixtures.FULL_REQUIRED)
        policy = json.loads((project / ".github/scripts/fast-tests.json").read_text())
        self.assertIn("thc.runtime.WordFloatingTest",
                      policy["leafSources"]["src/main/kotlin/thc/runtime/FloatingPrimitives.kt"]["junit"])

    def test_original_stdio_has_strict_focused_and_full_preparation(self):
        project = Path(__file__).resolve().parents[2]
        manifest, owners = fast_fixtures._manifest(project)
        group = manifest["groups"]["original-stdio"]
        command = ["python3", "scripts/prepare-original-stdio.py", "--require-supported"]
        self.assertEqual("original-stdio", owners["thc.runtime.OriginalStdioNativeTest"])
        self.assertEqual([{"argv": command}], group["commands"])
        self.assertEqual(["build/original-stdio"], group["outputs"])
        self.assertEqual({"compiler/test-fixtures/OriginalStdioAudit.hs",
                          "compiler/test-fixtures/OriginalStdioAuditNative.hs",
                          "scripts/prepare-original-stdio.py", "scripts/original_stdio_model.py",
                          "scripts/test-original-stdio-fixtures.py"}, set(group["sources"]))
        self.assertTrue(all((project / name).is_file() for name in group["sources"]))
        self.assertIn(" ".join(command), (project / "scripts/prepare-tests.sh").read_text().splitlines())
        self.assertEqual(fast_fixtures.FULL_PREPARATION_PLAN, fast_fixtures._preparation_plan(project))
        self.assertIn("build/original-stdio", fast_fixtures.FULL_OUTPUT_ROOTS)
        self.assertIn("build/original-stdio/manifest.json", fast_fixtures.FULL_REQUIRED)

    def test_original_stdio_selected_receipt_covers_model_checker_and_all_outputs(self):
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
        self.assertEqual(8, len(prepared))
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
            for name in ("GeneratedSimdFamilies.hs", "GeneratedSimdFamiliesNative.hs"):
                output = self.root / "build/generated/simd/fixtures" / name
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
        names = {"build/generated/simd/fixtures/GeneratedSimdFamilies.hs",
                 "build/generated/simd/fixtures/GeneratedSimdFamiliesNative.hs"}
        with mock.patch.object(fast_fixtures, "FULL_REQUIRED", fast_fixtures.FULL_REQUIRED | names):
            self.prepare("thc.UnknownTest")
            self.assertEqual(self.prepare("thc.UnknownTest")["reused"], ["full"])
            generated = self.root / "build/generated/simd/fixtures/GeneratedSimdFamilies.hs"
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
