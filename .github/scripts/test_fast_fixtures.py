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


def process_identity_provider(unit):
    prefix = 'build/original-process-identity/'
    publication = prefix + 'installed/unit-core/v3/' + 'a' * 64 + '/'
    return dict(packageManifest=prefix + 'installed/packages.json',
        installedBundles=[prefix + 'installed/bundles/ghc-internal-9.1401.0-inplace.zip',
                          prefix + 'installed/bundles/' + unit + '.zip'],
        installedPublications=[publication + '0.cbd', publication + 'publication.json'],
        runtimeModules=[prefix + 'runtime-core/THC.Exception.cbd',
                        prefix + 'runtime-core/THC.Internal.Exception.cbd'])


class FixturePreparationTest(unittest.TestCase):
    def test_cmake_selection_never_falls_back_to_ordered_recipes(self):
        self.manifest["groups"]["alpha"]["cmakeTarget"] = "fixture-alpha"
        (self.root / fast_fixtures.MANIFEST).write_text(json.dumps(self.manifest))
        run = mock.Mock()
        with mock.patch.dict("os.environ", {"GHC": "chosen-ghc"}):
            result = fast_fixtures.prepare_cmake(self.root, self.selection("thc.AlphaTest"), run)
        self.assertEqual(result, {"mode": "cmake", "targets": ["fixture-alpha"]})
        self.assertEqual(run.call_count, 2)
        self.assertIn("-DGHC=chosen-ghc", run.call_args_list[0].args[1])
        self.assertEqual(run.call_args_list[1].args[1][-2:], ["--target", "fixture-alpha"])
        run.reset_mock()
        with self.assertRaisesRegex(ValueError, "not yet migrated"):
            fast_fixtures.prepare_cmake(self.root, self.selection("thc.BetaTest"), run)
        run.assert_not_called()
        self.assertEqual(fast_fixtures.prepare_cmake(self.root, self.selection("thc.FreeTest"), run)["targets"], [])
        run.assert_not_called()

    def test_model_writers_require_the_declared_encoder(self):
        project = Path(__file__).resolve().parents[2]
        for name in ("thc.runtime.BigNatLiteralTest", "thc.StaticExportStartupTest",
                     "thc.runtime.IoMainPapNativeTest", "thc.runtime.AddressArrayCopyTest"):
            with self.subTest(test=name):
                result = fast_fixtures.prepare_cmake(project, self.selection(name), mock.Mock())
                self.assertIn("fixture-compact-model", result["targets"])
        result = fast_fixtures.prepare_cmake(project, self.selection("thc.runtime.Int32ByteOffsetTest"), mock.Mock())
        self.assertEqual([], result["targets"])

    def test_process_native_controls_do_not_acquire_package_core(self):
        project = Path(__file__).resolve().parents[2]
        for name in ("thc.runtime.ManagedProcessesTest", "thc.runtime.ManagedProcessForeignTest"):
            with self.subTest(test=name):
                result = fast_fixtures.prepare_cmake(project, self.selection(name), mock.Mock())
                self.assertEqual(["fixture-process-lifecycle-native"], result["targets"])

    def test_literal_cases_and_continuations_have_independent_producers(self):
        project = Path(__file__).resolve().parents[2]
        for name, target in (("thc.runtime.LargeLiteralCaseNativeTest", "fixture-large-literal-cases"),
                             ("thc.runtime.CoreContinuationNativeTest", "fixture-core-continuation")):
            with self.subTest(test=name):
                result = fast_fixtures.prepare_cmake(project, self.selection(name), mock.Mock())
                self.assertEqual([target], result["targets"])

    def test_direct_runtime_controls_need_no_fixture_toolchain(self):
        project = Path(__file__).resolve().parents[2]
        for name in ("thc.runtime.DescriptorFlagsTest", "thc.BoxedForeignProvenanceTest", "thc.PrimForeignProvenanceTest",
                     "thc.runtime.ForkHostFailureTest", "thc.runtime.LoomSignalProcessTest"):
            with self.subTest(test=name):
                run = mock.Mock()
                result = fast_fixtures.prepare_cmake(project, self.selection(name), run)
                self.assertEqual([], result["targets"])
                run.assert_not_called()

    def test_every_real_quarantined_fixture_stops_before_toolchain_or_generation(self):
        project = Path(__file__).resolve().parents[2]
        manifest, _ = fast_fixtures._manifest(project)
        for name, group in manifest["groups"].items():
            if not group.get("quarantined"):
                continue
            with self.subTest(group=name):
                run = mock.Mock(side_effect=AssertionError("A quarantined producer ran"))
                with self.assertRaisesRegex(ValueError, "Quarantined fixtures cannot run"):
                    fast_fixtures.prepare(project, self.selection(*group["junit"]), run, {})
                run.assert_not_called()

    def test_explicit_quarantine_also_blocks_fixture_free_selection(self):
        self.manifest["quarantinedJunit"] = ["thc.FreeTest"]
        (self.root / fast_fixtures.MANIFEST).write_text(json.dumps(self.manifest))
        self.assertIn("thc.FreeTest", fast_fixtures.quarantined_classes(self.root))
        with self.assertRaisesRegex(ValueError, "Quarantined tests cannot run: thc.FreeTest"):
            self.prepare("thc.FreeTest")
        self.assertEqual([], self.calls)

    def test_unknown_or_wildcard_selection_stops_before_any_producer(self):
        for selector in ("thc.UnknownTest", "*Alpha*"):
            with self.subTest(selector=selector):
                self.calls.clear()
                with self.assertRaisesRegex(ValueError, "Blanket fixture preparation is quarantined"):
                    self.prepare(selector, mode="full")
                self.assertEqual([], self.calls)

    def test_quarantine_blocks_direct_and_dependent_selection_before_any_command(self):
        self.manifest["groups"]["alpha"]["requires"] = ["beta"]
        self.manifest["groups"]["beta"]["quarantined"] = True
        (self.root / fast_fixtures.MANIFEST).write_text(json.dumps(self.manifest))
        for name in ("thc.AlphaTest", "thc.BetaTest"):
            with self.subTest(name=name):
                with self.assertRaisesRegex(ValueError, "Quarantined fixtures cannot run: beta"):
                    self.prepare(name)
                self.assertEqual([], self.calls)
        self.assertEqual({"thc.AlphaTest", "thc.AlphaBackendTest", "thc.BetaTest"},
                         fast_fixtures.quarantined_classes(self.root))
        self.assertEqual([], self.prepare("thc.FreeTest")["rebuilt"])
        self.assertEqual([], self.calls)

    def test_missing_prepared_encoder_rebuilds_selected_receipt(self):
        pointer = "build/thc-fixtures.path"
        executable = self.root / "dist-newstyle/thc-fixtures"
        self.manifest["groups"]["alpha"]["outputs"] = [pointer]
        self.manifest["groups"]["alpha"]["commands"] = [{"argv": ["make-encoder"]}]
        (self.root / fast_fixtures.MANIFEST).write_text(json.dumps(self.manifest))
        def run(name, argv, stdout=None):
            if argv != ["make-encoder"]:
                return
            executable.parent.mkdir(parents=True, exist_ok=True)
            executable.write_text("#!/bin/sh\nexit 0\n")
            executable.chmod(0o755)
            path = self.root / pointer
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(str(executable) + "\n")
        def prepare():
            return fast_fixtures.prepare(self.root, self.selection("thc.AlphaTest"), run, self.toolchain)
        self.assertEqual(["alpha"], prepare()["rebuilt"])
        self.assertEqual(["alpha"], prepare()["reused"])
        executable.unlink()
        self.assertEqual(["alpha"], prepare()["rebuilt"])
        self.assertEqual(["alpha"], prepare()["reused"])

    def test_local_selectors_resolve_classes_and_methods(self):
        owners = {"thc.AlphaTest": "alpha", "thc.AlphaBackendTest": "alpha", "thc.FreeTest": None}
        for selector, expected in (
                ("thc.AlphaTest", ["thc.AlphaTest"]),
                ("AlphaTest.someMethod", ["thc.AlphaTest"]),
                ("thc.AlphaTest.someMethod", ["thc.AlphaTest"]),
                ("thc.FreeTest", ["thc.FreeTest"])):
            with self.subTest(selector=selector):
                self.assertEqual({"mode": "narrow", "junit": {"classes": expected}},
                                 fast_fixtures.local_selection(selector, owners))
        self.assertEqual("full", fast_fixtures.local_selection("UnknownTest", owners)["mode"])
        # Gradle also matches '*Alpha*' against methods in otherwise unrelated classes.
        self.assertEqual("full", fast_fixtures.local_selection("*Alpha*", owners)["mode"])


    def test_shell_fixture_commands_use_selected_tools_with_spaces(self):
        project = Path(__file__).resolve().parents[2]
        manifest, _ = fast_fixtures._manifest(project)
        cabal = self.root / "selected cabal"
        cabal.write_text('#!/bin/sh\nprintf "%s\\n" "$@" >> "$CALLS"\n'
                         'if test "$1" = list-bin; then printf "%s\\n" /fixture; fi\n')
        cabal.chmod(0o755)
        calls = self.root / "calls"
        argv = manifest["groups"]["compact-model"]["commands"][0]["argv"]
        subprocess.run(argv, cwd=self.root, check=True, env={"PATH": "/usr/bin:/bin",
            "CABAL": str(cabal), "GHC": "/selected compiler/ghc",
            "GHC_PKG": "/selected compiler/ghc-pkg", "CALLS": str(calls)})
        arguments = calls.read_text().splitlines()
        self.assertEqual(2, arguments.count("--with-compiler=/selected compiler/ghc"))
        self.assertEqual(2, arguments.count("--with-hc-pkg=/selected compiler/ghc-pkg"))
        self.assertEqual("/fixture", (self.root / "build/thc-fixtures.path").read_text().strip())

    def test_build_installs_matching_llvm_tools_on_macos(self):
        project = Path(__file__).resolve().parents[2]
        source = (project / '.github/workflows/checks.yml').read_text()
        self.assertIn("if: runner.os == 'macOS'", source)
        self.assertIn('brew install llvm@18', source)
        self.assertIn('echo "$(brew --prefix llvm@18)/bin" >> "$GITHUB_PATH"', source)
        self.assertIn('for tool in clang llc opt llvm-nm llvm-link llvm-objcopy; do', source)
        self.assertIn("- name: Check LLVM backend tools\n        if: inputs.cadence != 'commit'", source)


    def test_shared_stdio_probe_changes_invalidate_the_posix_fixture_key(self):
        project = Path(__file__).resolve().parents[2]
        group = fast_fixtures._manifest(project)[0]['groups']['original-posix-stat']
        for name in group['sources']:
            path = self.root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(name)
        probe = 'src/main/c/stdio-abi-probe.c'
        sources = fast_fixtures._source_hashes(self.root, group)
        self.assertEqual(fast_fixtures._digest(self.root / probe), sources[probe])
        before = fast_fixtures.cache_key(self.root, 'original-posix-stat', group, self.toolchain)
        (self.root / probe).write_text('changed AT_* ABI probe')
        self.assertNotEqual(before,
            fast_fixtures.cache_key(self.root, 'original-posix-stat', group, self.toolchain))

    def test_original_unix_receipts_accept_only_pinned_installed_units(self):
        cache = fast_fixtures.fast_inputs
        for stem, entries, outputs, validate in (
                ('original-path-stat', cache.ORIGINAL_PATH_STAT_ENTRIES, cache.ORIGINAL_PATH_STAT_OUTPUTS, cache.original_path_stat_artifact_hashes),
                ('original-path-mode', cache.ORIGINAL_PATH_MODE_ENTRIES, cache.ORIGINAL_PATH_MODE_OUTPUTS, cache.original_path_mode_artifact_hashes),
                ('original-path-link', cache.ORIGINAL_PATH_LINK_ENTRIES, cache.ORIGINAL_PATH_LINK_OUTPUTS, cache.original_path_link_artifact_hashes),
                ('original-path-access', cache.ORIGINAL_PATH_ACCESS_ENTRIES, cache.ORIGINAL_PATH_ACCESS_OUTPUTS, cache.original_path_access_artifact_hashes),
                ('original-process-identity', cache.ORIGINAL_PROCESS_IDENTITY_ENTRIES, cache.ORIGINAL_PROCESS_IDENTITY_OUTPUTS, cache.process_identity_artifact_hashes)):
            artifacts = {name: 'a' * 64 for name in outputs if name != 'build/' + stem + '/manifest.json'}
            receipt = dict(schema=1, ghc='9.14.1', entries=list(entries), supported=True,
                strictAccepted=True, runtimeVerified=False, nativeRows=1, installedArtifactsHashed=False,
                artifactHashes=artifacts)
            for unit in ('unix-2.8.8.0-inplace', 'unix-2.8.8.0-460b', 'unix-2.8.8.0-deadbeef'):
                if stem == 'original-process-identity':
                    provider = process_identity_provider(unit)
                    artifacts = {name: 'a' * 64 for name in outputs | set(provider['installedBundles']) | set(provider['installedPublications'])
                                 if name != 'build/' + stem + '/manifest.json'}
                    receipt = dict(receipt, installedArtifactsHashed=True, artifactHashes=artifacts, **provider)
                self.assertEqual(artifacts, validate(dict(receipt, unixUnit=unit)), (stem, unit))
            for unit in (None, 42, 'unix-2.8.8.0', 'unix-2.8.8.0-', 'unix-2.8.8.0-ABCD',
                         'unix-2.8.8.0-xyz', 'unix-2.8.7.0-460b', 'base-2.8.8.0-460b',
                         'unix-2.8.8.0-inplace\n', 'unix-2.8.8.0-460b:forged'):
                with self.assertRaises(cache.CacheMiss, msg=(stem, unit)):
                    validate(dict(receipt, unixUnit=unit))


    def test_original_fd_ready_selected_receipt_preserves_rejection_stages_and_hashes(self):
        self.assertTrue(hasattr(fast_fixtures.fast_inputs, 'fd_ready_artifact_hashes'))
        project = Path(__file__).resolve().parents[2]
        group = fast_fixtures._manifest(project)[0]['groups']['original-fd-ready']
        name = 'build/original-fd-ready/manifest.json'
        artifacts = {}
        for artifact in fast_fixtures.fast_inputs.ORIGINAL_FD_READY_OUTPUTS - {name}:
            path = self.root / artifact
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text('{}\n')
            artifacts[artifact] = fast_fixtures._digest(path)
        receipt = dict(schema=1, ghc='9.14.1', entries=list(fast_fixtures.fast_inputs.ORIGINAL_FD_READY_ENTRIES),
                       nativeRows=168, negativeAudits=10, negativeControls=10,
                       negativeControlLabels=list(fast_fixtures.fast_inputs.ORIGINAL_FD_READY_NEGATIVES),
                       artifactHashes=artifacts)
        path = self.root / name
        path.write_text(json.dumps(receipt))
        self.assertEqual(fast_fixtures.fast_inputs.ORIGINAL_FD_READY_OUTPUTS,
                         fast_fixtures._output_hashes(self.root, group).keys())
        for key, value in (('schema', True), ('nativeRows', 167), ('nativeRows', True),
                           ('negativeAudits', 24), ('negativeAudits', True),
                           ('negativeControls', 11),
                           ('negativeControlLabels', list(reversed(receipt['negativeControlLabels'])))):
            path.write_text(json.dumps(dict(receipt, **{key: value})))
            with self.assertRaises(RuntimeError): fast_fixtures._output_hashes(self.root, group)
        for change in ('unknown', 'missing', 'changed', 'symlink'):
            path.write_text(json.dumps(receipt))
            artifact = self.root / 'build/original-fd-ready/OriginalFdReadyAudit.cbd'
            if artifact.is_symlink(): artifact.unlink()
            artifact.write_text('{}\n')
            if change == 'unknown':
                path.write_text(json.dumps(dict(receipt, artifactHashes=dict(artifacts, **{'build/original-fd-ready/extra.cbd': '0'*64}))))
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
            artifact = self.root / 'build/original-rts-locks/pre.cbd'
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

    def test_compiler_preparation_builds_required_inspector(self):
        project = Path(__file__).resolve().parents[2]
        self.assertIn('set -- build lib:thc exe:thc-compact --offline',
                      (project / 'bin/build-compiler.sh').read_text())

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name).resolve()
        self.enterContext(mock.patch.dict(fast_fixtures.os.environ, {"CABAL": "cabal"}))
        for pattern in fast_fixtures.COMMON_SOURCES:
            name = pattern.replace("**/*.hs", "Plugin.hs").replace("*.py", "core_vectors.py")
            path = self.root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(name)
        (self.root / "bin/toolchain.sh").write_text(
            "GHC='/selected compiler/ghc'\nGHC_PKG='/selected compiler/ghc-pkg'\n")
        self.scalar_argv = ["cabal", "run", "--with-compiler=/selected compiler/ghc",
                            "--with-hc-pkg=/selected compiler/ghc-pkg", "exe:thc-primops", "--", "scalars"]
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

    def test_required_producers_are_ordered_once_and_cycles_fail_closed(self):
        self.manifest["groups"]["alpha"]["requires"] = ["beta"]
        (self.root / fast_fixtures.MANIFEST).write_text(json.dumps(self.manifest))
        manifest, _ = fast_fixtures._manifest(self.root)
        self.assertEqual(["beta", "alpha"], fast_fixtures._group_order(manifest, ["alpha", "beta"]))
        self.manifest["groups"]["beta"]["requires"] = ["alpha"]
        (self.root / fast_fixtures.MANIFEST).write_text(json.dumps(self.manifest))
        with self.assertRaisesRegex(ValueError, "Cyclic"):
            fast_fixtures._manifest(self.root)
        self.manifest["groups"]["beta"]["requires"] = ["missing"]
        (self.root / fast_fixtures.MANIFEST).write_text(json.dumps(self.manifest))
        with self.assertRaisesRegex(ValueError, "Unknown"):
            fast_fixtures._manifest(self.root)

    def fake_run(self, name, argv, stdout=None):
        self.calls.append((name, argv, stdout))
        if name == "fixture-scalar-signatures" and self.mutate_scalar_on_generator:
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
            self.scalar_argv,
            ["bin/build-compiler.sh"], ["make-alpha"]])
        self.assertEqual(self.prepare("thc.AlphaBackendTest"),
                         {"mode": "selected", "rebuilt": [], "reused": ["alpha"]})
        self.assertEqual(len(self.calls), 3)

    def test_prepare_passes_selected_tools_to_recorder_and_reuses_warm_outputs(self):
        argv = ["cabal", "run", "exe:thc-fixtures", "--offline", "--", "alpha"]
        self.manifest["groups"]["alpha"]["commands"][0]["argv"] = argv
        (self.root / fast_fixtures.MANIFEST).write_text(json.dumps(self.manifest))
        fast_fixtures.os.environ["CABAL"] = "/selected cabal/cabal"
        def record(name, command, stdout=None):
            self.calls.append((name, command, stdout))
            self.assertEqual("/selected compiler/ghc", fast_fixtures.os.environ["GHC"])
            self.assertEqual("/selected compiler/ghc-pkg", fast_fixtures.os.environ["GHC_PKG"])
            if stdout is not None:
                (self.root / stdout).write_text("native alpha\n")
        selection = self.selection("thc.AlphaTest")
        self.assertEqual(["alpha"], fast_fixtures.prepare(self.root, selection, record, self.toolchain)["rebuilt"])
        self.assertEqual([
            ("fixture-scalar-signatures", ["/selected cabal/cabal", *self.scalar_argv[1:]], None),
            ("fixture-compiler", ["bin/build-compiler.sh"], None),
            ("fixture-alpha-00", ["/selected cabal/cabal", "run",
                "--with-compiler=/selected compiler/ghc", "--with-hc-pkg=/selected compiler/ghc-pkg",
                "exe:thc-fixtures", "--offline", "--", "alpha"], "build/alpha/oracle.tsv"),
        ], self.calls)
        self.assertEqual(argv, json.loads((self.root / fast_fixtures.MANIFEST).read_text())
                         ["groups"]["alpha"]["commands"][0]["argv"])
        self.calls.clear()
        with mock.patch.object(fast_fixtures.subprocess, "check_output",
                               side_effect=AssertionError("warm preparation must not resolve tools")):
            self.assertEqual(["alpha"], fast_fixtures.prepare(self.root, selection, record, self.toolchain)["reused"])
        self.assertEqual([], self.calls)

    def test_only_changed_group_rebuilds_and_compiler_runs_once(self):
        self.prepare("thc.AlphaTest", "thc.BetaTest")
        self.assertEqual([argv for _, argv, _ in self.calls].count(["bin/build-compiler.sh"]), 1)
        self.calls.clear()
        (self.root / "fixtures/beta.hs").write_text("changed")
        result = self.prepare("thc.AlphaTest", "thc.BetaTest")
        self.assertEqual(result, {"mode": "selected", "rebuilt": ["beta"], "reused": ["alpha"]})
        self.assertEqual([argv for _, argv, _ in self.calls], [
            self.scalar_argv,
            ["bin/build-compiler.sh"], ["make-beta"]])

    def test_missing_or_changed_output_rebuilds(self):
        self.prepare("thc.AlphaTest")
        output = self.root / "build/alpha/oracle.tsv"
        self.calls.clear()
        output.write_text("tampered")
        self.assertEqual(self.prepare("thc.AlphaTest")["rebuilt"], ["alpha"])
        self.calls.clear()
        output.unlink()
        self.assertEqual(self.prepare("thc.AlphaTest")["rebuilt"], ["alpha"])

    def test_compiler_interface_overlay_is_not_a_runtime_fixture_input(self):
        self.prepare("thc.AlphaTest")
        output = self.root / "build/alpha"
        (output / "Installed.hi").symlink_to(self.root / "not-installed-here.hi")
        (output / "Installed.dyn_hi").symlink_to(self.root / "not-installed-here.dyn_hi")
        self.calls.clear()
        self.assertEqual(self.prepare("thc.AlphaTest")["reused"], ["alpha"])
        self.assertEqual([], self.calls)
        (output / "Module.cbd").symlink_to(output / "oracle.tsv")
        with self.assertRaisesRegex(RuntimeError, "Unexpected fixture output"):
            fast_fixtures._output_hashes(self.root, self.manifest["groups"]["alpha"])

    def test_invalid_stamp_rebuilds(self):
        self.prepare("thc.AlphaTest")
        self.calls.clear()
        stamp = self.root / fast_fixtures.STAMP_DIR / "alpha.json"
        stamp.write_text("[]")
        self.assertEqual(self.prepare("thc.AlphaTest")["rebuilt"], ["alpha"])

    def test_core_native_override_profile_change_rebuilds(self):
        self.prepare("thc.AlphaTest", "thc.BetaTest")
        profile = self.root / "src/main/resources/thc/core-native-overrides.json"
        profile.write_text("changed runtime-owned foreign call contract")
        self.assertEqual(self.prepare("thc.AlphaTest", "thc.BetaTest")["rebuilt"], ["alpha", "beta"])

    def test_common_source_or_toolchain_change_rebuilds(self):
        self.prepare("thc.AlphaTest")
        self.calls.clear()
        (self.root / "src/compiler/THC/Plugin.hs").write_text("new plugin")
        self.assertEqual(self.prepare("thc.AlphaTest")["rebuilt"], ["alpha"])
        self.calls.clear()
        self.toolchain["ghcVersion"] = "9.14.2"
        self.assertEqual(self.prepare("thc.AlphaTest")["rebuilt"], ["alpha"])

    def test_shared_core_symbols_dependency_change_rebuilds(self):
        self.prepare("thc.AlphaTest")
        for name in ("src/core-symbols/THC/CoreSymbols.hs",):
            with self.subTest(path=name):
                self.calls.clear()
                path = self.root / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text("changed shared symbols dependency")
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
        for name in ("thc.cabal", "cabal.project", "Setup.hs", "Makefile", "bin/plugin.py"):
            with self.subTest(name=name):
                self.calls.clear()
                (self.root / name).write_text("changed plugin build input")
                self.assertEqual(self.prepare("thc.AlphaTest")["rebuilt"], ["alpha"])


    def test_full_known_selection_prepares_dependencies_and_reuses_group_receipts(self):
        self.manifest["groups"]["alpha"]["requires"] = ["beta"]
        (self.root / fast_fixtures.MANIFEST).write_text(json.dumps(self.manifest))
        self.assertEqual(self.prepare("thc.AlphaTest", "thc.AlphaBackendTest", "thc.FreeTest", mode="full"),
                         {"mode": "selected", "rebuilt": ["beta", "alpha"], "reused": []})
        self.assertEqual([argv for _, argv, _ in self.calls], [
            self.scalar_argv,
            ["bin/build-compiler.sh"], ["make-beta"], ["make-alpha"]])
        preserved = self.root / "build/beta.previous-attempt/failure.log"
        preserved.parent.mkdir(parents=True)
        preserved.write_text("retained failed evidence\n")
        self.calls.clear()
        self.assertEqual(self.prepare("thc.AlphaTest", "thc.AlphaBackendTest", "thc.BetaTest", "thc.FreeTest", mode="full"),
                         {"mode": "selected", "rebuilt": [], "reused": ["beta", "alpha"]})
        self.assertEqual([], self.calls)
        self.assertEqual("retained failed evidence\n", preserved.read_text())
        (self.root / "build/beta/result.tsv").unlink()
        self.assertEqual(self.prepare("thc.AlphaTest", mode="full"),
                         {"mode": "selected", "rebuilt": ["beta"], "reused": ["alpha"]})
        self.assertEqual([argv for _, argv, _ in self.calls], [
            self.scalar_argv,
            ["bin/build-compiler.sh"], ["make-beta"]])

    def test_fixture_free_selection_runs_no_commands(self):
        self.assertEqual(self.prepare("thc.FreeTest"),
                         {"mode": "selected", "rebuilt": [], "reused": []})
        self.assertEqual(self.calls, [])

    def test_direct_runtime_controls_need_no_exported_fixture(self):
        project = Path(__file__).resolve().parents[2]
        _, owners = fast_fixtures._manifest(project)
        for simple_name in ("MixedBackendContinuationTest", "StockGraphRecoveryTest", "MutVarTest"):
            name = "thc.runtime." + simple_name
            self.assertIn(name, owners)
            self.assertIsNone(owners[name])
            self.assertTrue((project / "src/test/java/thc/runtime" / (simple_name + ".java")).is_file())


    def test_example_fixture_keys_follow_exported_sources(self):
        project = Path(__file__).resolve().parents[2]
        manifest, _ = fast_fixtures._manifest(project)
        for source in ("t/fixtures/Input.hs", "t/fixtures/input.c"):
            path = self.root / source
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text("fixture input\n")
        for name, sources in (
                ("vector-api", ("t/haskell-fixtures/VectorApiFixtures.hs",
                                "src/examples/VectorLoops.hs", "src/runtime/THC/Prim.hs")),
                ("truffle-strings", ("t/haskell-fixtures/TruffleStringFixtures.hs",
                                     "src/examples/StringPrimitives.hs", "src/runtime/THC/Prim.hs",
                                     "src/runtime/THC/Exception.hs", "src/runtime/THC/Internal/Exception.hs"))):
            group = manifest["groups"][name]
            for source in sources:
                path = self.root / source
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text("original input\n")
            original_key = fast_fixtures.cache_key(self.root, name, group, self.toolchain)
            for source in sources:
                with self.subTest(group=name, source=source):
                    path = self.root / source
                    path.write_text("changed input\n")
                    try:
                        self.assertNotEqual(original_key,
                            fast_fixtures.cache_key(self.root, name, group, self.toolchain))
                    finally:
                        path.write_text("original input\n")

    def test_unrelated_source_does_not_invalidate_group(self):
        self.prepare("thc.AlphaTest")
        self.calls.clear()
        (self.root / "fixtures/beta.hs").write_text("unrelated change")
        self.manifest["groups"]["beta"]["commands"][0]["argv"] = ["changed-beta"]
        (self.root / fast_fixtures.MANIFEST).write_text(json.dumps(self.manifest))
        self.assertEqual(self.prepare("thc.AlphaTest")["reused"], ["alpha"])
        self.assertEqual(self.calls, [])

    def test_haskell_producer_changes_only_rebuild_its_owners(self):
        directory = self.root / "t/haskell-fixtures"
        for name in ("AlphaFixtures.hs", "BetaFixtures.hs", "InstalledCoreFixtures.hs"):
            (directory / name).write_text(name)
        for group, producer in (("alpha", "AlphaFixtures.hs"), ("beta", "BetaFixtures.hs")):
            self.manifest["groups"][group]["sources"] += [
                "t/haskell-fixtures/" + producer, "t/haskell-fixtures/InstalledCoreFixtures.hs"]
        (self.root / fast_fixtures.MANIFEST).write_text(json.dumps(self.manifest))
        self.prepare("thc.AlphaTest", "thc.BetaTest")
        for source, rebuilt, reused in (
                ("BetaFixtures.hs", ["beta"], ["alpha"]),
                ("AlphaFixtures.hs", ["alpha"], ["beta"]),
                ("InstalledCoreFixtures.hs", ["alpha", "beta"], []),
                ("FixtureSupport.hs", ["alpha", "beta"], [])):
            with self.subTest(source=source):
                (directory / source).write_text("changed " + source)
                self.assertEqual({"mode": "selected", "rebuilt": rebuilt, "reused": reused},
                                 self.prepare("thc.AlphaTest", "thc.BetaTest"))

    def test_remaining_scalar_memory_audits_use_cbd_inputs(self):
        project = Path(__file__).resolve().parents[2]
        families = ("FloatingAddress", "FloatingByteOffset",
                    "Explicit64Array", "AtomicAddress", "AlignedScalarMemory", "UnalignedScalarMemory",
                    "ScalarMemoryUtilities")
        for family in families:
            module = family + ("" if family == "ScalarMemoryUtilities" else "Audit")
            producer = (project / f"t/haskell-fixtures/{family}Fixtures.hs").read_text()
            consumer = (project / f"src/test/java/thc/runtime/{family}Test.java").read_text()
            self.assertIn(module + ".cbd", producer)
            self.assertNotIn(module + ".json", producer)
            self.assertIn(module + ".cbd", consumer)
            self.assertNotIn(module + ".json", consumer)
            self.assertIn("CoreCbdFixtures.read", consumer)


    def test_floating_model_controls_are_explicitly_fixture_free(self):
        project = Path(__file__).resolve().parents[2]
        _, owners = fast_fixtures._manifest(project)
        for name in ("BytecodeTypedTupleInputTest", "DoubleArrayTest", "DoubleVectorMemoryProofTest",
                     "DoubleVectorStorageTest", "FloatArrayTest", "FloatVectorMemoryProofTest",
                     "FloatVectorStorageTest"):
            with self.subTest(name=name):
                self.assertIn("thc.runtime." + name, owners)
                self.assertIsNone(owners["thc.runtime." + name])
                path = project / "src/test/java/thc/runtime" / (name + ".java")
                self.assertTrue(path.is_file(), name)
                source = path.read_text()
                self.assertNotIn('"build/', source)

    def test_managed_file_and_stdio_controls_do_not_force_full_fixture_preparation(self):
        project = Path(__file__).resolve().parents[2]
        _, owners = fast_fixtures._manifest(project)
        names = ("thc.GuestExceptionsTest", "thc.runtime.ManagedFileCallTest",
                 "thc.runtime.ManagedFilesTest", "thc.runtime.ManagedStdioTest",
                 "thc.runtime.OriginalStdioCallTest", "thc.runtime.StdioHostAbiTest",
                 "thc.runtime.OriginalUnixBatchTest", "thc.runtime.PosixStatAbiTest")
        for name in names:
            with self.subTest(name=name):
                self.assertIn(name, owners)
                self.assertIsNone(owners[name])
                path = project / "src/test/java" / (name.replace(".", "/") + ".java")
                self.assertTrue(path.is_file(), name)
                source = path.read_text()
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
                    prefix = 'uname() { printf "%s\\n" ' + machine + '; }; python3() { printf "%s\\n" "$@"; }; cabal() { printf "%s\\n" "$@"; }; '
                    actual = subprocess.check_output([*command[:2], prefix + command[2]], text=True,
                        env={"PATH": "/usr/bin:/bin"}).splitlines()
                    self.assertEqual((["run", "exe:thc-fixtures", "--offline", "--with-compiler=ghc", "--with-hc-pkg=ghc-pkg", "--", family] if family.endswith("-bytearray")
                                      else ["bin/prepare-" + family + "-audit.py"]) +
                                     ([] if machine == "x86_64" else ["--export-only"]), actual)


    def test_float_decode_tracks_upstream_sources_without_bundling_them(self):
        cache = fast_fixtures.fast_inputs
        name = "build/float-decode/manifest.json"
        artifacts = {}
        for item in cache.FLOAT_DECODE_OUTPUTS - {name}:
            path = self.root / item
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text("fixture\n")
            artifacts[item] = fast_fixtures._digest(path)
        (self.root / name).write_text(json.dumps({"artifactHashes": artifacts}))
        for item in cache.BIGNUM_SOURCES:
            path = self.root / item
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text("pinned original\n")
        group = {"outputs": ["build/float-decode"], "sources": sorted(cache.BIGNUM_SOURCES)}
        self.assertEqual(cache.FLOAT_DECODE_OUTPUTS, set(fast_fixtures._output_hashes(self.root, group)))
        with mock.patch.object(fast_fixtures, "COMMON_SOURCES", ()):
            original_key = fast_fixtures.cache_key(self.root, "float-decode", group, {})
            for item in sorted(cache.BIGNUM_SOURCES):
                path = self.root / item
                original = path.read_bytes()
                path.write_text("changed original\n")
                self.assertNotEqual(original_key, fast_fixtures.cache_key(self.root, "float-decode", group, {}))
                path.unlink()
                with self.assertRaisesRegex(RuntimeError, "Missing fixture source"):
                    fast_fixtures.cache_key(self.root, "float-decode", group, {})
                path.write_bytes(original)


    def test_original_read_selected_receipt_covers_haskell_producer_and_all_outputs(self):
        project = Path(__file__).resolve().parents[2]
        manifest, _ = fast_fixtures._manifest(project)
        group = manifest["groups"]["original-stdio-read"]
        self.manifest["groups"]["original-stdio-read"] = group
        (self.root / fast_fixtures.MANIFEST).write_text(json.dumps(self.manifest))
        for name in group["sources"]:
            path = self.root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text("original fixture source\n")
        prepared = []
        def run(name, argv, stdout=None):
            self.fake_run(name, argv, stdout)
            if argv[-1] == "original-stdio-read":
                prepared.append(name)
                directory = self.root / "build/original-stdio-read"
                directory.mkdir(parents=True, exist_ok=True)
                (directory / "manifest.json").write_text("{}\n")
                (directory / "oracle.json").write_text("[]\n")
        def prepare():
            return fast_fixtures.prepare(self.root, self.selection("thc.runtime.OriginalStdioReadTest"), run, self.toolchain)
        self.assertEqual(["original-stdio-read"], prepare()["rebuilt"])
        self.assertEqual(["original-stdio-read"], prepare()["reused"])
        for name in group["sources"]:
            (self.root / name).write_text("changed fixture source\n")
            self.assertEqual(["original-stdio-read"], prepare()["rebuilt"], name)
        (self.root / "build/original-stdio-read/oracle.json").write_text("tampered output\n")
        self.assertEqual(["original-stdio-read"], prepare()["rebuilt"])
        (self.root / "build/original-stdio-read/manifest.json").unlink()
        self.assertEqual(["original-stdio-read"], prepare()["rebuilt"])
        self.assertEqual(len(group["sources"]) + 3, len(prepared))
        self.assertNotIn("fixtures-full", [name for name, _, _ in self.calls])

    def test_numeric_family_exports_and_consumers_use_cbd(self):
        project = Path(__file__).resolve().parents[2]
        producer = (project / 't/haskell-fixtures/Main.hs').read_text()
        paths = producer.split('relativeCore family stage =', 1)[1].split('inputPaths ::', 1)[0]
        self.assertIn('fixtureModule family ++ ".cbd"', paths)
        self.assertIn('"THC.InterfaceClosure.cbd"', paths)
        self.assertNotIn('.json', paths)
        for name in ('IntegerPrimopsTest', 'SignedNarrowPrimopsTest',
                     'runtime/BitPrimopsTest', 'runtime/Explicit64PrimopsTest'):
            consumer = (project / f'src/test/java/thc/{name}.java').read_text()
            self.assertIn('CoreCbdFixtures.read', consumer)
            self.assertNotIn('Json.INSTANCE.stringify(Map.of("modules",', consumer)

    def test_pr80_affected_classes_have_focused_preparation(self):
        project = Path(__file__).resolve().parents[2]
        manifest, owners = fast_fixtures._manifest(project)
        affected = {"thc.RealCoreEntryContractTest", "thc.runtime.ScalarLexicalProofTest",
                    "thc.runtime.BoxedLexicalProofTest", "thc.runtime.ScalarPrimitiveSignatureTest",
                    "thc.runtime.DataToTagTest", "thc.runtime.MutableByteArraySizeTest",
                    "thc.runtime.Int8ArrayNativeTest", "thc.runtime.Int16ArrayNativeTest",
                    "thc.runtime.Int16BoundaryCompilationTest",
                    "thc.runtime.Int32ArrayNativeTest"}
        self.assertEqual({"cbv-coercion", "data-to-tag", "mutable-bytearray-size",
                          "int8-arrays", "int16-arrays", "int32-arrays"},
                         {owners[name] for name in affected})
        for group_id in {owners[name] for name in affected}:
            group = manifest["groups"][group_id]
            self.assertTrue(all((project / path).is_file() for path in group["sources"]))
            self.assertTrue(group["commands"] and group["outputs"])
        cbv = manifest["groups"]["cbv-coercion"]
        self.assertEqual(1, cbv["outputs"].count("build/cbv-post-core/CBVCoercionAudit.cbd"))
        self.assertNotIn("build/cbv-post-core/CBVCoercionAudit.json", cbv["outputs"])
        exports = [command["argv"] for command in cbv["commands"] if "t/fixtures/compiler/CBVAudit.hs" in command["argv"]]
        self.assertEqual(2, len(exports))
        self.assertTrue(all("-fplugin-opt=THC.Plugin:pretty-diagnostics" not in command for command in exports))
        self.assertEqual(1, sum("-fplugin-opt=THC.Plugin:post-tidy" in command for command in exports))
        self.assertIn("build/tuple-arithmetic/pre-core/TupleArithmeticAudit.cbd", cbv["outputs"])
        self.assertIn("build/explicit64-primops/core/Explicit64PrimopsAudit.cbd", cbv["outputs"])
        self.assertTrue(any("bin/check-cbv-metadata.py" in command["argv"]
                            for command in cbv["commands"]))

    def test_simd_memory_families_use_haskell_and_exact_attempt_receipts(self):
        project = Path(__file__).resolve().parents[2]
        manifest, owners = fast_fixtures._manifest(project)
        for family, klass in (("int32x4", "Int32"), ("word32x4", "Word32"), ("floatx4", "Float"), ("doublex2", "Double")):
            group_id = f"simd-{family}-bytearray"; group = manifest["groups"][group_id]
            self.assertEqual(group_id, owners[f"thc.runtime.Simd{klass}ByteArrayTest"])
            self.assertEqual(["build/"+group_id], group["outputs"])
            for path in ("t/haskell-fixtures/SimdByteArrayFixtures.hs", "t/haskell-fixtures/SimdByteArrayModel.hs",
                         "t/haskell-fixtures/Main.hs", "t/haskell-fixtures/FixtureSupport.hs", "thc.cabal"):
                self.assertIn(path, group["sources"])
            self.assertTrue(all((project / path).is_file() for path in group["sources"]))
            argv = group["commands"][0]["argv"]
            self.assertEqual(["sh", "-c"], argv[:2])
            self.assertIn(f" -- {family}-bytearray --export-only", argv[2])
            self.assertIn(' --with-compiler="${GHC:-ghc}" --with-hc-pkg="${GHC_PKG:-ghc-pkg}"', argv[2])
            self.assertNotIn("python", argv[2])
            for path in (f"prepare-{family}-bytearray-audit.py", f"{family}_bytearray_model.py", f"test-{family}-bytearray-model.py"):
                self.assertFalse((project / "bin" / path).exists())
            for native in (False, True):
                attempt = f"build/{group_id}/prepare-run-Abc123"; name = f"build/{group_id}/provenance.json"
                rows = fast_fixtures.fast_inputs.SIMD_BYTEARRAY_FAMILIES[group_id][1]
                expected = {path: "0"*64 for path in fast_fixtures.fast_inputs.simd_bytearray_outputs(group_id, attempt, native)}
                module = fast_fixtures.fast_inputs.SIMD_BYTEARRAY_FAMILIES[group_id][0]
                self.assertIn(f"build/{group_id}/pre-core/{module}.cbd", expected)
                self.assertNotIn(f"build/{group_id}/pre-core/{module}.json", expected)
                self.assertTrue(all(path.endswith(".cbd") for path in expected if "/mutations/" in path))
                with mock.patch.object(fast_fixtures.fast_inputs, "file_path") as path, mock.patch.object(fast_fixtures, "_manifest_output_hashes") as output:
                    path.return_value.read_text.return_value = json.dumps(dict(schema=1, vector=f"{family}-bytearray",
                        stages=["pre", "post"] if native else ["pre"], attempt=attempt, modelRows=rows, modelByteOrder="little",
                        nativeRows=rows if native else None, nativeByteOrder="little" if native else None, modelMatched=True if native else None,
                        artifacts=[dict(path=p, sha256=h) for p, h in expected.items()]))
                    fast_fixtures._output_hashes(project, group)
                    self.assertEqual((project, name, expected), output.call_args.args)

    def test_bytearray_families_use_haskell_producers_and_closed_receipts(self):
        project = Path(__file__).resolve().parents[2]
        manifest, owners = fast_fixtures._manifest(project)
        self.assertIsNone(owners["thc.runtime.MutableByteArrayTest"])
        groups = {"bytearray": "ByteArrayTest", "mutable-bytearrays": "MutableByteArrayNativeTest",
                  "resize-bytearrays": "ResizeByteArrayTest", "mutable-bytearray-size": "MutableByteArraySizeTest",
                  "compare-byte-arrays": "CompareByteArraysTest"}
        for family, klass in groups.items():
            group = manifest['groups'][family]
            self.assertEqual(family, owners['thc.runtime.' + klass])
            self.assertEqual([{'argv': ['cabal', 'run', 'exe:thc-fixtures', '--offline', '--', family]}], group['commands'])
            self.assertEqual(['build/' + family], group['outputs'])
            for path in ('t/haskell-fixtures/ByteArrayFixtures.hs', 't/haskell-fixtures/FixtureSupport.hs', 't/haskell-fixtures/Main.hs', 'thc.cabal'):
                self.assertIn(path, group['sources']); self.assertTrue((project / path).is_file())
            name = f'build/{family}/manifest.json'; artifacts = fast_fixtures.fast_inputs.BYTEARRAY_OUTPUTS[family] - {name}
            expected = {path: '0'*64 for path in artifacts}
            with mock.patch.object(fast_fixtures.fast_inputs, 'file_path') as file_path, mock.patch.object(fast_fixtures, '_manifest_output_hashes') as outputs:
                file_path.return_value.read_text.return_value = json.dumps(dict(schema=1, ghc='9.14.1', wordBits=64,
                    entries=list(fast_fixtures.fast_inputs.BYTEARRAY_FAMILIES[family][1]), artifactHashes=expected))
                fast_fixtures._output_hashes(project, group)
                recorded = outputs.call_args.args[2]
                self.assertEqual(set(expected), set(recorded))
                if family in ('bytearray', 'compare-byte-arrays'):
                    self.assertTrue(fast_fixtures.fast_inputs.BYTEARRAY_SOURCES <= set(group['sources']))
        for script in ('prepare-bytearray.py', 'prepare-mutable-bytearrays.py', 'prepare-resize-bytearrays.py',
                       'prepare-mutable-bytearray-size.py', 'prepare-compare-byte-arrays.py', 'mutable_bytearray_model.py', 'test-mutable-bytearray-model.py'):
            self.assertFalse((project / 'bin' / script).exists())

    def test_int16_boundary_control_prepares_the_genuine_native_fixture(self):
        project = Path(__file__).resolve().parents[2]
        manifest, owners = fast_fixtures._manifest(project)
        self.assertEqual("int16-arrays", owners["thc.runtime.Int16BoundaryCompilationTest"])
        group = manifest["groups"]["int16-arrays"]
        self.assertEqual({"thc.runtime.Int16ArrayNativeTest", "thc.runtime.Int16BoundaryCompilationTest"},
                         set(group["junit"]))
        self.assertEqual([{"argv": ["cabal", "run", "exe:thc-fixtures", "--offline", "--", "int16-arrays"]}],
                         group["commands"])
        self.assertEqual(["build/int16-arrays"], group["outputs"])


    def test_selected_process_signal_preparation_reuses_explicit_mac_exclusion(self):
        project = Path(__file__).resolve().parents[2]
        group = fast_fixtures._manifest(project)[0]['groups']['process-signals']
        # Isolate this provider's platform exclusion; encoder edges have their own check.
        group = {**group, 'requires': []}
        self.assertIn('t/haskell-fixtures/ProcessSignalFixtures.hs', group['sources'])
        self.assertIn('t/haskell-fixtures/FixtureSupport.hs', group['sources'])
        for name in group['sources']:
            path = self.root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text('fixture source\n')
        self.manifest['groups'] = {'process-signals': group}
        (self.root / fast_fixtures.MANIFEST).write_text(json.dumps(self.manifest))
        name = 'build/process-signals/manifest.json'
        def run(label, argv, stdout=None):
            self.fake_run(label, argv, stdout)
            if argv[-1] == 'process-signals':
                path = self.root / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(json.dumps(dict(schema=1, platform='darwin', supported=False, artifactHashes={})))
        with mock.patch.object(fast_fixtures.platform, 'system', return_value='Darwin'), \
             mock.patch.object(fast_fixtures.platform, 'machine', return_value='arm64'):
            selection = self.selection('thc.runtime.ProcessSignalsTest')
            toolchain = {'platform': {'system': 'Darwin', 'machine': 'arm64'}}
            self.assertEqual({'mode': 'selected', 'rebuilt': ['process-signals'], 'reused': []},
                fast_fixtures.prepare(self.root, selection, run, toolchain))
            self.assertEqual({'mode': 'selected', 'rebuilt': [], 'reused': ['process-signals']},
                fast_fixtures.prepare(self.root, selection, run, toolchain))
            self.assertEqual({name}, set(fast_fixtures._output_hashes(self.root, group)))
            self.assertFalse((self.root / 'build/process-signals/oracle.txt').exists())
            self.assertFalse((self.root / 'build/process-signals/native-controls.txt').exists())

    def test_selected_output_receipt_rejects_symlinked_parent(self):
        output = self.root / 'build/process-signals/oracle.txt'
        target = self.root / 'signal-artifacts'
        target.mkdir()
        (target / 'oracle.txt').write_text('native oracle\n')
        output.parent.parent.mkdir(parents=True)
        output.parent.symlink_to(target, target_is_directory=True)
        with self.assertRaisesRegex(RuntimeError, 'Symlink/noncanonical destination'):
            fast_fixtures._output_hashes(self.root, {'outputs': ['build/process-signals/oracle.txt']})


if __name__ == "__main__":
    unittest.main()
