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
    def test_gc_carrier_provenance_has_a_focused_producer(self):
        project = Path(__file__).resolve().parents[2]
        manifest, owners = fast_fixtures._manifest(project)
        classes = {"thc.BoxedForeignProvenanceTest", "thc.PrimForeignProvenanceTest"}
        self.assertEqual({"package-native-gc-carriers"}, {owners.get(name) for name in classes})
        group = manifest["groups"]["package-native-gc-carriers"]
        self.assertEqual(classes, set(group["junit"]))
        self.assertEqual([{"argv": ["cabal", "run", "exe:thc-fixtures", "--offline", "--", "package-native-gc-carriers"]}],
                         group["commands"])
        self.assertEqual(["build/package-native-gc-carriers"], group["outputs"])
        self.assertTrue(all((project / name).is_file() for name in group["sources"]))

    def test_gc_carrier_receipt_rejects_partial_stale_or_unreviewed_products(self):
        directory = "build/package-native-gc-carriers/"
        main = ["PackageNativeGcCarriers.cbd", "oracle.txt"]
        original = ["original-v2/GHC.Internal.Stack.Decode.cbd", "original-v2/objects/GHC/Internal/Stack/Decode.hi"]
        primitive = ["primitive/PackageNativePrimCarriers.cbd", "primitive/PackageNativeUnknownPrim.cbd",
                     "primitive/objects/PackageNativePrimCarriers.hi"]
        def records(names):
            result = {}
            for name in names:
                path = self.root / (directory + name)
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(b"original producer output\n")
                result[directory + name] = hashlib.sha256(path.read_bytes()).hexdigest()
            return result
        manifest = {"schema": 1, "nativeRows": 6, "gcImports": 7, "artifactHashes": records(main),
            "originalModules": [{"unit": "ghc-internal", "module": "GHC.Internal.Stack.Decode",
                "nativeSignatures": [], "artifactHashes": records(original)}],
            "primitiveModule": {"artifactHashes": records(primitive)}}
        manifest_path = self.root / (directory + "manifest.json")
        manifest_path.write_text(json.dumps(manifest))
        group = {"outputs": [directory.rstrip("/")]}
        outputs = fast_fixtures._output_hashes(self.root, group)
        self.assertEqual({directory + name for name in ["manifest.json", *main, *original, *primitive]}, set(outputs))
        for name in [*main, *original, *primitive]:
            path = self.root / (directory + name)
            original_bytes = path.read_bytes()
            path.write_bytes(b"changed\n")
            with self.assertRaisesRegex(RuntimeError, "Stale original artifact"):
                fast_fixtures._output_hashes(self.root, group)
            path.unlink()
            with self.assertRaises(FileNotFoundError):
                fast_fixtures._output_hashes(self.root, group)
            path.symlink_to(manifest_path)
            with self.assertRaises((ValueError, RuntimeError)):
                fast_fixtures._output_hashes(self.root, group)
            path.unlink()
            path.write_bytes(original_bytes)
        for mutate in (lambda m: m["originalModules"].clear(),
                       lambda m: m["originalModules"].append(m["originalModules"][0]),
                       lambda m: m["artifactHashes"].update({directory + "Invented.cbd": "a" * 64})):
            bad = json.loads(json.dumps(manifest))
            mutate(bad)
            manifest_path.write_text(json.dumps(bad))
            with self.assertRaises((ValueError, RuntimeError)):
                fast_fixtures._output_hashes(self.root, group)


    def test_foreign_exception_preparation_installs_runtime_before_cli_consumers(self):
        project = Path(__file__).resolve().parents[2]
        planned = subprocess.run(
            ["make", "--dry-run", "--no-print-directory", "foreign-exception-fixtures"],
            cwd=project, check=True, capture_output=True, text=True).stdout
        # Package-native-demand calls the installed CLI even on a fresh checkout.
        self.assertIn("./gradlew installDist", planned)
        self.assertLess(planned.index("./gradlew installDist"),
                        planned.index(" -- foreign-exceptions"))

    def test_foreign_exception_native_consumers_use_matching_configured_ghc(self):
        project = Path(__file__).resolve().parents[2]
        fixture = self.root / "fixture"
        fixture.write_text('#!/bin/sh\nprintf "%s\\t%s\\n" "$1" "${THC_INSTALLED_CORE_GHC_SOURCE:-missing}"\n')
        fixture.chmod(0o755)
        cabal = self.root / "cabal"
        cabal.write_text('#!/bin/sh\ntest "$1" = list-bin || exit 1\nprintf "%s\\n" "$TEST_FIXTURE_BIN"\n')
        cabal.chmod(0o755)
        planned = subprocess.run(
            ["make", "--dry-run", "--no-print-directory", "foreign-exception-fixtures", "CABAL=" + str(cabal)],
            cwd=project, check=True, capture_output=True, text=True).stdout
        # Execute the real broad-consumer recipe, replacing only its compilers.
        recipe = planned[planned.index("set -eu;"):]
        source = str(self.root / "configured ghc")
        consumed = subprocess.run(["sh", "-c", recipe], cwd=project, check=True,
            capture_output=True, text=True, env={"PATH": "/usr/bin:/bin",
                "TEST_FIXTURE_BIN": str(fixture), "THC_FOREIGN_EXCEPTION_GHC_SOURCE": source,
                "THC_INSTALLED_CORE_GHC_SOURCE": "wrong compiler tree"}).stdout.splitlines()
        self.assertGreaterEqual(len(consumed), 8)
        for row in consumed:
            self.assertEqual(source, row.split("\t", 1)[1])

    def test_native_inspection_families_require_cbd_without_flat_core_json(self):
        for family, module, outputs in (
                ("ghc-bco", "GhcBCO", fast_fixtures.fast_inputs.BCO_OUTPUTS),
                ("stable-names", "StableNames", fast_fixtures.fast_inputs.STABLE_NAME_OUTPUTS),
                ("thread-scheduling", "ThreadScheduling", fast_fixtures.fast_inputs.THREAD_SCHEDULING_OUTPUTS),
                ("integer-completion", "IntegerCompletionAudit", fast_fixtures.fast_inputs.INTEGER_COMPLETION_OUTPUTS),
                ("hint-trace", "HintTraceAudit", fast_fixtures.fast_inputs.HINT_TRACE_OUTPUTS),
                ("closure-inspection", "ClosureInspectionAudit", fast_fixtures.fast_inputs.CLOSURE_INSPECTION_OUTPUTS)):
            stems = ([f"build/{family}/core/{module}"] if family == "closure-inspection" else
                     [f"build/{family}/{stage}-core/{module}" if family == "integer-completion" else
                      f"build/{family}/{stage}/core/{module}" for stage in ("pre", "post")])
            for stem in stems:
                self.assertIn(stem + ".cbd", outputs)
                self.assertNotIn(stem + ".json", outputs)
                self.assertTrue(fast_fixtures.fast_inputs.allowed_payload(stem + ".cbd"))
                self.assertFalse(fast_fixtures.fast_inputs.allowed_payload(stem + ".json"))
                self.assertFalse(fast_fixtures.fast_inputs.allowed_payload(stem.replace(module, "Unreviewed") + ".cbd"))
                if family in ("hint-trace", "closure-inspection"):
                    self.assertIn(stem + ".cbd", fast_fixtures.FULL_REQUIRED)
                    self.assertNotIn(stem + ".json", fast_fixtures.FULL_REQUIRED)

    def test_standalone_array_required_core_products_are_cbd(self):
        for family, fixture in (("atomic-int-arrays", "AtomicIntArrayAudit"),
                                ("fetch-add-int-array", "FetchAddIntArrayAudit"),
                                ("shrink-bytearrays", "ShrinkMutableByteArrayAudit")):
            for stage in ("pre", "post"):
                for module in (fixture, "THC.InterfaceClosure"):
                    path = f"build/{family}/{stage}/core/{module}"
                    self.assertIn(path + ".cbd", fast_fixtures.FULL_REQUIRED)
                    self.assertNotIn(path + ".json", fast_fixtures.FULL_REQUIRED)

    def test_full_truffle_string_reuse_requires_cbd_without_core_json(self):
        modules = ("THC.Prim", "StringPrimitives", "IntrinsicOperands",
                   "TruffleStringExceptions", "THC.Exception", "THC.Internal.Exception")
        required = {name for name in fast_fixtures.FULL_REQUIRED
                    if name.startswith("build/truffle-strings/")}
        expected = {f"build/truffle-strings/core/{module}.cbd" for module in modules}
        expected.update("build/truffle-strings/" + name
                        for name in ("manifest.json", "oracle.json", "native/oracle"))
        self.assertEqual(expected, required)
        for name in expected:
            path = self.root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(b"fixture\n")
        with mock.patch.object(fast_fixtures, "FULL_REQUIRED", required):
            outputs = fast_fixtures._full_output_hashes(self.root)
            self.assertEqual(expected, set(outputs))
            for module in modules:
                path = self.root / f"build/truffle-strings/core/{module}.cbd"
                with self.subTest(module=module):
                    path.write_bytes(b"changed\n")
                    self.assertNotEqual(outputs, fast_fixtures._full_output_hashes(self.root))
                    path.unlink()
                    with self.assertRaises(FileNotFoundError):
                        fast_fixtures._full_output_hashes(self.root)
                    path.write_bytes(b"fixture\n")

    def test_full_converted_core_families_require_cbd_artifacts(self):
        paths = {
            *(f"record-fields/{stage}/{module}.cbd"
              for stage in ("pre", "post", "installed")
              for module in ("RecordFieldLibrary", "RecordFieldClient")),
            "core-continuation/core/CoreContinuationAudit.cbd",
            "core-continuation/core/LazyIOCallbackAudit.cbd",
            *(f"{family}/{stage}/core/{module}.cbd" for stage in ("pre", "post")
              for family, module in (("live-async", "LiveAsyncAudit"),
                                     ("thread-label", "ThreadLabelAudit"),
                                     ("thread-status", "ThreadStatusAudit"),
                                     ("uncaught-self", "UncaughtSelfAudit"),
                                     ("scalar-exception-results", "ScalarExceptionResultsAudit"),
                                     ("deep-evaluation", "DeepEvaluation"),
                                     ("deep-evaluation", "THC.InterfaceClosure"),
                                     ("pinned-pointer-cells", "PinnedPointerCellsAudit"))),
            *(f"addr-identity/{stage}-core/AddressIdentityAudit.cbd" for stage in ("pre", "post")),
            *(f"exception-result-layouts/{stage}/core/ExceptionResultLayoutsAudit.cbd"
              for stage in (("pre",) if fast_fixtures.platform.machine().lower()
                            in ("arm64", "aarch64") else ("pre", "post"))),
        }
        required = {"build/" + name for name in paths}
        for name in required:
            self.assertTrue(name in fast_fixtures.FULL_REQUIRED, "Missing Core artifact: " + name)
            legacy = name.removesuffix(".cbd") + ".json"
            self.assertFalse(legacy in fast_fixtures.FULL_REQUIRED, "Obsolete Core artifact: " + legacy)
            path = self.root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(b"fixture\n")
        with mock.patch.object(fast_fixtures, "FULL_REQUIRED", required):
            outputs = fast_fixtures._full_output_hashes(self.root)
            self.assertEqual(required, set(outputs))
            for name in sorted(required):
                path = self.root / name
                with self.subTest(artifact=name):
                    path.write_bytes(b"changed\n")
                    self.assertNotEqual(outputs, fast_fixtures._full_output_hashes(self.root))
                    path.unlink()
                    with self.assertRaises(FileNotFoundError):
                        fast_fixtures._full_output_hashes(self.root)
                    path.write_bytes(b"fixture\n")

    def test_selector_proof_is_required_for_full_preparation_reuse(self):
        project = Path(__file__).resolve().parents[2]
        source = (project / "bin/prepare-tests.sh").read_text()
        command = '"$fixture_bin" selector-proof'
        self.assertIn(command, source)
        self.assertEqual(fast_fixtures.FULL_PREPARATION_PLAN, fast_fixtures._preparation_plan(project))
        self.assertIn("build/selector-proof", fast_fixtures.FULL_OUTPUT_ROOTS)
        expected = {"build/selector-proof/" + name for name in (
            "manifest.json",
            *[f"{stage}/{name}.cbd" for stage in ("pre", "post")
              for name in ("core/SelectorProofAudit", "SelectorProofAudit.roundtrip")],
            "api/predicate",
            *[f"commands/{command}.{suffix}"
              for command in ("pre-export", "post-export", "predicate-build", "libdir", "predicate-run")
              for suffix in ("stdout", "stderr", "command.json")])}
        required = {name for name in fast_fixtures.FULL_REQUIRED if name.startswith("build/selector-proof/")}
        self.assertEqual(expected, required)
        for name in expected:
            path = self.root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text("fixture\n")
        with mock.patch.object(fast_fixtures, "FULL_REQUIRED", required):
            outputs = fast_fixtures._full_output_hashes(self.root)
            self.assertEqual(expected, set(outputs))
            for name in sorted(expected):
                with self.subTest(missing=name):
                    path = self.root / name
                    path.unlink()
                    with self.assertRaises(FileNotFoundError):
                        fast_fixtures._full_output_hashes(self.root)
                    path.write_text("fixture\n")
            (self.root / "build/selector-proof/post/core/SelectorProofAudit.cbd").write_text("changed\n")
            self.assertNotEqual(outputs, fast_fixtures._full_output_hashes(self.root))
        script = self.root / "bin/prepare-tests.sh"
        script.parent.mkdir(parents=True, exist_ok=True)
        script.write_text(source.replace(command, "", 1))
        with self.assertRaisesRegex(RuntimeError, "not been reviewed"):
            fast_fixtures._full_key(self.root)

    def test_delimited_self_delivery_retains_thread_fixture_audits(self):
        project = Path(__file__).resolve().parents[2]
        manifest, owners = fast_fixtures._manifest(project)
        self.assertEqual("thread-async", owners["thc.runtime.ThreadAsyncNativeTest"])
        self.assertEqual(["build/thread-async"], manifest["groups"]["thread-async"]["outputs"])
        producer = (project / "t/haskell-fixtures/ThreadAsyncFixtures.hs").read_text()
        native = (project / "t/fixtures/compiler/ThreadAsyncNative.hs").read_text()
        self.assertIn("build/thread-async/saved-oracle.txt", fast_fixtures.FULL_REQUIRED)
        self.assertIn("build/thread-async/external-saved-oracle.txt", fast_fixtures.FULL_REQUIRED)
        self.assertIn("build/thread-async/scheduled-saved-oracle.txt", fast_fixtures.FULL_REQUIRED)
        for entry in ("promptSelfThrow", "promptMaskedUnmaskSelf", "savedSelfThrow",
                      "savedMaskedSelf", "savedSuffixSelf", "savedMaskCatchSelf", "externalSaved", "scheduledSaved"):
            self.assertIn('"' + entry + '"', producer)
            self.assertIn("Audit." + entry + " 0#", native)
            for stage in ("pre", "post"):
                self.assertIn(f"build/thread-async/{stage}/{entry}-audit.json", fast_fixtures.FULL_REQUIRED)

    def test_cbd_metadata_exports_keep_required_outputs_and_reviewed_tidy_plan(self):
        project = Path(__file__).resolve().parents[2]
        source = (project / "bin/prepare-tests.sh").read_text()
        option = "-fplugin-opt=THC.Plugin:post-tidy"
        self.assertNotIn("-fplugin-opt=THC.Plugin:pretty-diagnostics", source)
        commands = [line.split() for line in source.splitlines()
                    if line.strip().startswith("bin/export-core.sh") and
                    ("t/fixtures/compiler/CBV" in line or "t/fixtures/compiler/SourceNotes.hs" in line)]
        inputs = ["t/fixtures/core/Fixtures.hs"] + [
            "t/fixtures/compiler/" + name + ".hs" for name in (
                "StrictFields", "SpeculationAudit", "RepresentationAudit", "SourceNotes",
                "CBVAudit", "CBVJoinAudit", "CBVCoercionAudit", "ConstructorFieldAudit", "DemandAudit")]
        self.assertEqual([
            ["bin/export-core.sh", *inputs],
            ["bin/export-core.sh", option,
             *["t/fixtures/compiler/" + name + ".hs" for name in
               ("CBVAudit", "CBVJoinAudit", "CBVCoercionAudit")]],
            ["bin/export-core.sh", "t/fixtures/compiler/SourceNotes.hs",
             "t/fixtures/compiler/RepresentationAudit.hs"],
        ], commands)
        self.assertIn("build/core", fast_fixtures.FULL_OUTPUT_ROOTS)
        for path in inputs:
            self.assertTrue((project / path).is_file(), path)
            module = "Fixtures" if path == inputs[0] else Path(path).stem
            self.assertIn("build/core/" + module + ".cbd", fast_fixtures.FULL_REQUIRED)
        # The real post-Tidy comparison must remain in the reviewed producer plan.
        # Removing it invalidates that plan before any cache hit.
        script = self.root / "bin/prepare-tests.sh"
        script.parent.mkdir(parents=True, exist_ok=True)
        script.write_text(source)
        self.assertEqual(fast_fixtures.FULL_PREPARATION_PLAN, fast_fixtures._preparation_plan(self.root))
        script.write_text(source.replace(option + " ", "", 1))
        with self.assertRaisesRegex(RuntimeError, "not been reviewed"):
            fast_fixtures._full_key(self.root)

    def test_integer_simd_has_focused_preparation_and_closed_receipts(self):
        project = Path(__file__).resolve().parents[2]
        manifest, owners = fast_fixtures._manifest(project)
        group = manifest["groups"]["integer-simd"]
        for name in group["junit"]:
            self.assertEqual("integer-simd", owners[name])
        self.assertEqual({"build/" + family for family in fast_fixtures.fast_inputs.INTEGER_SIMD_FAMILIES}, set(group["outputs"]))
        self.assertTrue(all((project / path).is_file() for path in group["sources"]))
        sources = fast_fixtures._source_hashes(project, group)
        self.assertIn("src/test/java/thc/runtime/IntegerSimdModelTest.java", sources)
        self.assertIn("src/test/java/thc/runtime/IntegerSimdModel.java", sources)
        self.assertIn("t/haskell-fixtures/IntegerSimdFixtures.hs", sources)
        for native in (False, True):
            with tempfile.TemporaryDirectory() as directory:
                root = Path(directory).resolve()
                expected = {}
                for family, (_, rows) in fast_fixtures.fast_inputs.INTEGER_SIMD_FAMILIES.items():
                    name = f"build/{family}/provenance.json"
                    outputs = fast_fixtures.fast_inputs.integer_simd_outputs(family, native)
                    artifacts = {}
                    for path in outputs - {name}:
                        target = root / path
                        target.parent.mkdir(parents=True, exist_ok=True)
                        target.write_bytes(b"fixture\n")
                        artifacts[path] = fast_fixtures._digest(target)
                    receipt = dict(stages=["pre", "post"] if native else ["pre"], modelRows=rows,
                        nativeRows=rows if native else None, modelMatched=True if native else None,
                        positiveAuditsAccepted=True, proofNegativeControlsPassed=True,
                        artifacts=[dict(path=path, sha256=digest) for path, digest in artifacts.items()])
                    (root / name).write_text(json.dumps(receipt))
                    expected.update(artifacts)
                    expected[name] = fast_fixtures._digest(root / name)
                self.assertEqual(expected, fast_fixtures._output_hashes(root, group))
                (root / "build/simd-int8x16/expected.tsv").write_bytes(b"corrupt\n")
                with self.assertRaisesRegex(RuntimeError, "Stale original artifact"):
                    fast_fixtures._output_hashes(root, group)

    def test_deep_evaluation_is_required_for_full_preparation_reuse(self):
        project = Path(__file__).resolve().parents[2]
        manifest, owners = fast_fixtures._manifest(project)
        self.assertEqual('deep-evaluation', owners['thc.runtime.AstStackNativeTest'])
        self.assertEqual(['build/deep-evaluation'], manifest['groups']['deep-evaluation']['outputs'])
        self.assertEqual(fast_fixtures.FULL_PREPARATION_PLAN, fast_fixtures._preparation_plan(project))
        self.assertIn('build/deep-evaluation', fast_fixtures.FULL_OUTPUT_ROOTS)
        for path in ('manifest.json', 'native/oracle', 'logs/native-oracle.stdout',
                     'pre/core/DeepEvaluation.cbd', 'post/core/DeepEvaluation.cbd',
                     'pre/core/THC.InterfaceClosure.cbd', 'post/core/THC.InterfaceClosure.cbd',
                     'pre/audit.json', 'post/audit.json'):
            self.assertIn('build/deep-evaluation/' + path, fast_fixtures.FULL_REQUIRED)

    def test_bytestring_sort_has_focused_preparation_and_closed_cache(self):
        project = Path(__file__).resolve().parents[2]
        manifest, owners = fast_fixtures._manifest(project)
        self.assertEqual("bytestring-sort", owners["thc.runtime.ByteStringSortTest"])
        group = manifest["groups"]["bytestring-sort"]
        self.assertEqual(["build/bytestring-sort"], group["outputs"])
        self.assertTrue(all((project / path).is_file() for path in group["sources"]))
        outputs = fast_fixtures.fast_inputs.BYTESTRING_SORT_OUTPUTS
        self.assertEqual(24, len(outputs))
        self.assertTrue(outputs <= fast_fixtures.FULL_REQUIRED)
        self.assertIn("build/bytestring-sort", fast_fixtures.FULL_OUTPUT_ROOTS)
        self.assertTrue(all(fast_fixtures.fast_inputs.allowed_payload(path) for path in outputs))
        self.assertFalse(fast_fixtures.fast_inputs.allowed_payload("build/bytestring-sort/ghc/ByteStringSortAudit.o"))

    def test_bytestring_decimal_has_focused_preparation_and_closed_cache(self):
        project = Path(__file__).resolve().parents[2]
        manifest, owners = fast_fixtures._manifest(project)
        self.assertEqual("bytestring-decimal", owners["thc.runtime.ByteStringDecimalTest"])
        group = manifest["groups"]["bytestring-decimal"]
        self.assertEqual(["build/bytestring-decimal"], group["outputs"])
        self.assertTrue(all((project / path).is_file() for path in group["sources"]))
        outputs = fast_fixtures.fast_inputs.BYTESTRING_DECIMAL_OUTPUTS
        self.assertEqual(32, len(outputs))
        self.assertTrue(outputs <= fast_fixtures.FULL_REQUIRED)
        self.assertIn("build/bytestring-decimal", fast_fixtures.FULL_OUTPUT_ROOTS)
        self.assertTrue(all(fast_fixtures.fast_inputs.allowed_payload(path) for path in outputs))
        self.assertFalse(fast_fixtures.fast_inputs.allowed_payload("build/bytestring-decimal/ghc/ByteStringDecimalAudit.o"))

    def test_unix_libc_has_focused_preparation_and_closed_cache(self):
        project = Path(__file__).resolve().parents[2]
        manifest, owners = fast_fixtures._manifest(project)
        self.assertEqual("unix-libc", owners["thc.runtime.UnixLibcTest"])
        group = manifest["groups"]["unix-libc"]
        self.assertEqual(["build/unix-libc"], group["outputs"])
        self.assertTrue(all((project / path).is_file() for path in group["sources"]))
        outputs = fast_fixtures.fast_inputs.UNIX_LIBC_OUTPUTS
        self.assertEqual(48, len(outputs))
        self.assertTrue(outputs <= fast_fixtures.FULL_REQUIRED)
        self.assertIn("build/unix-libc", fast_fixtures.FULL_OUTPUT_ROOTS)
        self.assertTrue(all(fast_fixtures.fast_inputs.allowed_payload(path) for path in outputs))
        self.assertFalse(fast_fixtures.fast_inputs.allowed_payload("build/unix-libc/ghc/UnixLibcAudit.o"))

    def test_unix_wait_status_cache_preserves_all_original_proofs(self):
        project = Path(__file__).resolve().parents[2]
        manifest, owners = fast_fixtures._manifest(project)
        group = manifest['groups']['unix-wait-status']
        cache = fast_fixtures.fast_inputs
        self.assertEqual('unix-wait-status', owners['thc.runtime.UnixWaitStatusTest'])
        self.assertEqual(72, len(cache.UNIX_WAIT_OUTPUTS))
        self.assertTrue(cache.UNIX_WAIT_OUTPUTS <= fast_fixtures.FULL_REQUIRED)
        self.assertIn('build/unix-wait-status', fast_fixtures.FULL_OUTPUT_ROOTS)
        self.assertIn('"$fixture_bin" unix-wait-status', (project / 'bin/prepare-tests.sh').read_text().splitlines())
        name = 'build/unix-wait-status/manifest.json'
        artifacts = {}
        for item in cache.UNIX_WAIT_OUTPUTS - {name}:
            self.assertTrue(cache.allowed_payload(item), item)
            path = self.root / item; path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text('fixture\n'); artifacts[item] = fast_fixtures._digest(path)
        for item in ('ghc/UnixWaitStatusAudit.o', 'private-core.json', 'logs/extra.stdout'):
            self.assertFalse(cache.allowed_payload('build/unix-wait-status/' + item), item)
        receipt = dict(schema=1, ghc='9.14.1', unixUnit='unix-2.8.8.0-460b', strictAccepted=True,
                       entries=list(cache.UNIX_WAIT_ENTRIES), nativeRows=280, artifactHashes=artifacts)
        path = self.root / name; path.write_text(json.dumps(receipt))
        self.assertEqual(cache.UNIX_WAIT_OUTPUTS, set(fast_fixtures._output_hashes(self.root, group)))
        for changes in (dict(schema=True), dict(ghc='9.14.0'), dict(unixUnit='unix-2.8.8.0-ABCD'),
                        dict(unixUnit='unix-2.8.7.0-460b'), dict(entries=[]), dict(nativeRows=279),
                        dict(strictAccepted=False), dict(artifactHashes=dict(artifacts, extra='a' * 64))):
            with self.assertRaises(cache.CacheMiss): cache.unix_wait_artifact_hashes(dict(receipt, **changes))
        for suffix in ('logs/unit.stdout', 'post-waitWCOREDUMP.audit.json', 'oracle.tsv'):
            item = 'build/unix-wait-status/' + suffix
            missing = dict(artifacts); del missing[item]
            with self.assertRaises(cache.CacheMiss): cache.unix_wait_artifact_hashes(dict(receipt, artifactHashes=missing))
            artifact = self.root / item; artifact.write_text('changed')
            with self.assertRaises(RuntimeError): fast_fixtures._output_hashes(self.root, group)
            artifact.unlink(); artifact.symlink_to(self.root / 'build/unix-wait-status/pre.cbd')
            with self.assertRaises(cache.CacheMiss): fast_fixtures._output_hashes(self.root, group)
            artifact.unlink(); artifact.write_text('fixture\n')

    def test_proxy_void_has_focused_preparation_and_closed_cache(self):
        project = Path(__file__).resolve().parents[2]
        manifest, owners = fast_fixtures._manifest(project)
        self.assertEqual('proxy-void', owners['thc.runtime.ProxyVoidTest'])
        group = manifest['groups']['proxy-void']
        self.assertEqual(['build/proxy-void'], group['outputs'])
        self.assertEqual([{'argv': ['cabal', 'run', 'exe:thc-fixtures', '--offline', '--', 'proxy-void']}], group['commands'])
        self.assertTrue(all((project / path).is_file() for path in group['sources']))
        self.assertEqual(fast_fixtures.FULL_PREPARATION_PLAN, fast_fixtures._preparation_plan(project))
        required = fast_fixtures.fast_inputs.PROXY_VOID_OUTPUTS
        self.assertEqual(37, len(required))
        self.assertEqual({f"build/proxy-void/{stage}/core/{module}.cbd"
                          for stage in ("pre", "post")
                          for module in ("ProxyVoidAudit", "THC.InterfaceClosure")},
                         {path for path in required if path.endswith(".cbd")})
        self.assertTrue(required <= fast_fixtures.FULL_REQUIRED)
        self.assertIn('build/proxy-void', fast_fixtures.FULL_OUTPUT_ROOTS)
        for path in required:
            self.assertTrue(fast_fixtures.fast_inputs.allowed_payload(path), path)
        self.assertFalse(fast_fixtures.fast_inputs.allowed_payload('build/proxy-void/baseline-audit.json'))

    def test_original_bytestring_utf8_registration_and_closed_cache(self):
        project = Path(__file__).resolve().parents[2]
        manifest, owners = fast_fixtures._manifest(project)
        self.assertEqual('bytestring-utf8', owners['thc.runtime.ByteStringUtf8Test'])
        group = manifest['groups']['bytestring-utf8']
        self.assertEqual(['build/bytestring-utf8'], group['outputs'])
        self.assertEqual([{'argv': ['cabal', 'run', 'exe:thc-fixtures', '--offline', '--', 'bytestring-utf8']}], group['commands'])
        self.assertTrue(all((project / path).is_file() for path in group['sources']))
        self.assertIn('"$fixture_bin" bytestring-utf8', (project / 'bin/prepare-tests.sh').read_text())
        self.assertEqual(fast_fixtures.FULL_PREPARATION_PLAN, fast_fixtures._preparation_plan(project))
        self.assertTrue(fast_fixtures.fast_inputs.BYTESTRING_UTF8_OUTPUTS <= fast_fixtures.FULL_REQUIRED)
        self.assertIn('build/bytestring-utf8', fast_fixtures.FULL_OUTPUT_ROOTS)
        self.assertIn('"bytestring-utf8/**/*.json"', (project / 'build.gradle').read_text())

    def test_original_memset_registration_and_closed_cache(self):
        project = Path(__file__).resolve().parents[2]
        manifest, owners = fast_fixtures._manifest(project)
        self.assertEqual('original-memset', owners['thc.runtime.OriginalMemsetTest'])
        group = manifest['groups']['original-memset']
        self.assertEqual(['build/original-memset'], group['outputs'])
        self.assertEqual([{'argv': ['cabal', 'run', 'exe:thc-fixtures', '--offline', '--', 'original-memset']}], group['commands'])
        self.assertTrue(all((project / path).is_file() for path in group['sources']))
        self.assertIn('"$fixture_bin" original-memset', (project / 'bin/prepare-tests.sh').read_text())
        self.assertEqual(fast_fixtures.FULL_PREPARATION_PLAN, fast_fixtures._preparation_plan(project))
        self.assertTrue(fast_fixtures.fast_inputs.MEMSET_OUTPUTS <= fast_fixtures.FULL_REQUIRED)
        self.assertIn('build/original-memset', fast_fixtures.FULL_OUTPUT_ROOTS)
        self.assertIn('"original-memset/**/*.json"', (project / 'build.gradle').read_text())

    def test_original_memory_search_registration_and_closed_cache(self):
        project = Path(__file__).resolve().parents[2]
        manifest, owners = fast_fixtures._manifest(project)
        self.assertEqual('original-memory-search', owners['thc.runtime.OriginalMemorySearchTest'])
        group = manifest['groups']['original-memory-search']
        self.assertEqual(['build/original-memory-search'], group['outputs'])
        self.assertEqual([{'argv': ['cabal', 'run', 'exe:thc-fixtures', '--offline', '--', 'original-memory-search']}], group['commands'])
        self.assertTrue(all((project / path).is_file() for path in group['sources']))
        self.assertIn('"$fixture_bin" original-memory-search', (project / 'bin/prepare-tests.sh').read_text())
        self.assertEqual(fast_fixtures.FULL_PREPARATION_PLAN, fast_fixtures._preparation_plan(project))
        self.assertTrue(fast_fixtures.fast_inputs.MEMORY_SEARCH_OUTPUTS <= fast_fixtures.FULL_REQUIRED)
        self.assertIn('build/original-memory-search', fast_fixtures.FULL_OUTPUT_ROOTS)
        self.assertIn('"original-memory-search/**/*.json"', (project / 'build.gradle').read_text())

    def test_rubbish_native_fixture_owns_complete_bounded_outputs(self):
        project = Path(__file__).resolve().parents[2]
        manifest, owners = fast_fixtures._manifest(project)
        self.assertEqual('rubbish-literals', owners['thc.runtime.RubbishLiteralTest'])
        group = manifest['groups']['rubbish-literals']
        self.assertEqual(['build/rubbish-literals'], group['outputs'])
        self.assertEqual([{'argv': ['cabal', 'run', 'exe:thc-fixtures', '--offline', '--', 'rubbish-literals']}], group['commands'])
        self.assertEqual(fast_fixtures.FULL_PREPARATION_PLAN, fast_fixtures._preparation_plan(project))
        required = fast_fixtures.fast_inputs.RUBBISH_OUTPUTS
        self.assertEqual(38, len(required))
        for name in ('pre.cbd', 'post.cbd', 'Data.Sequence.Internal.cbd',
                     'native.s', 'native.o', 'native-codegen.json',
                     *(f'logs/{command}.{suffix}' for command in ('imports-containers', 'containers-unit', 'native-assemble')
                       for suffix in ('stdout', 'stderr', 'command.json'))):
            self.assertIn('build/rubbish-literals/' + name, required)
        for name in ('pre.json', 'post.json', 'frontiers.json', 'frontiers.cbd', 'frontiers.audit.json',
                     *(f'logs/frontiers-audit.{suffix}' for suffix in ('stdout', 'stderr', 'command.json'))):
            path = 'build/rubbish-literals/' + name
            self.assertNotIn(path, required)
            self.assertFalse(fast_fixtures.fast_inputs.allowed_payload(path))
        self.assertTrue(required <= fast_fixtures.FULL_REQUIRED)
        self.assertTrue(required <= set(fast_fixtures.fast_inputs.REQUIRED))
        self.assertIn('build/rubbish-literals', fast_fixtures.FULL_OUTPUT_ROOTS)
        for path in required:
            self.assertTrue(fast_fixtures.fast_inputs.allowed_payload(path), path)
        self.assertFalse(fast_fixtures.fast_inputs.allowed_payload('build/rubbish-literals/unowned.json'))

    def test_recent_native_producers_have_named_preparation_and_keep_full_receipts(self):
        project = Path(__file__).resolve().parents[2]
        manifest, owners = fast_fixtures._manifest(project)
        plan = (project / 'bin/prepare-tests.sh').read_text().splitlines()
        for family, junit, count, native in (
                ('sum-join', 'thc.runtime.SumJoinResultTest', 28, 'oracle.tsv'),
                ('record-fields', 'thc.runtime.RecordFieldNativeTest', 60, 'logs/post-native.stdout')):
            with self.subTest(family=family):
                self.assertIn('"$fixture_bin" ' + family, plan)
                self.assertEqual(family, owners[junit])
                self.assertNotIn(junit, manifest['fixtureFreeJunit'])
                output = 'build/' + family
                self.assertIn(output, fast_fixtures.FULL_OUTPUT_ROOTS)
                required = {p for p in fast_fixtures.FULL_REQUIRED if p.startswith(output + '/')}
                self.assertEqual(count, len(required))
                for name in required:
                    path = self.root / name
                    path.parent.mkdir(parents=True, exist_ok=True)
                    path.write_text('native producer evidence\n')
                # Isolate this family while retaining the actual full-output
                # validator, including mandatory native evidence and hashes.
                with mock.patch.object(fast_fixtures, 'FULL_REQUIRED', required), \
                     mock.patch.object(fast_fixtures, 'FULL_OUTPUT_ROOTS', {output}), \
                     mock.patch.object(fast_fixtures, 'NON_FIXTURE_BUILD_ROOTS', {'sum-join', 'record-fields'}):
                    self.assertEqual(required, set(fast_fixtures._full_output_hashes(self.root)))
                    (self.root / output / native).unlink()
                    with self.assertRaises(FileNotFoundError):
                        fast_fixtures._full_output_hashes(self.root)
        self.assertEqual(fast_fixtures.FULL_PREPARATION_PLAN, fast_fixtures._preparation_plan(project))

    def test_stable_names_keep_native_values_and_closed_artifacts(self):
        project = Path(__file__).resolve().parents[2]
        manifest, owners = fast_fixtures._manifest(project)
        cache = fast_fixtures.fast_inputs
        group = manifest['groups']['stable-names']
        self.assertEqual('stable-names', owners['thc.runtime.StableNamesTest'])
        self.assertIn('t/haskell-fixtures/StableNameFixtures.hs', group['sources'])
        self.assertIn('"$fixture_bin" stable-names', (project / 'bin/prepare-tests.sh').read_text().splitlines())
        self.assertEqual(50, len(cache.STABLE_NAME_OUTPUTS))
        self.assertTrue(cache.STABLE_NAME_OUTPUTS <= fast_fixtures.FULL_REQUIRED)
        name = 'build/stable-names/manifest.json'
        artifacts = {}
        for item in cache.STABLE_NAME_OUTPUTS - {name}:
            path = self.root / item; path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text('fixture\n'); artifacts[item] = fast_fixtures._digest(path)
        receipt = dict(schema=1, ghc='9.14.1', entries=list(cache.STABLE_NAME_ENTRIES), stages=['pre', 'post'],
                       native=[0] * 20, artifactHashes=artifacts)
        (self.root / name).write_text(json.dumps(receipt))
        self.assertEqual(cache.STABLE_NAME_OUTPUTS, set(fast_fixtures._output_hashes(self.root, group)))
        for bad in (dict(receipt, schema=True), dict(receipt, ghc='9.12.2'), dict(receipt, entries=[]),
                    dict(receipt, native=[0] * 19), dict(receipt, native=[False] * 20),
                    dict(receipt, stages=['pre']), dict(receipt, artifactHashes={})):
            with self.assertRaises(cache.CacheMiss): cache.stable_name_artifact_hashes(bad)
        self.assertFalse(cache.allowed_payload('build/stable-names/unknown.json'))
        (self.root / 'build/stable-names/commands/native-run.stdout').write_text('mutated')
        with self.assertRaises(RuntimeError): fast_fixtures._output_hashes(self.root, group)

    def test_closure_inspection_fixture_ownership_and_closed_inventory(self):
        project = Path(__file__).resolve().parents[2]
        manifest, owners = fast_fixtures._manifest(project)
        self.assertEqual('closure-inspection', owners['thc.runtime.ClosureInspectionTest'])
        self.assertEqual([{'argv': ['cabal', 'run', 'exe:thc-fixtures', '--offline', '--', 'closure-inspection']}],
                         manifest['groups']['closure-inspection']['commands'])
        self.assertIn('"$fixture_bin" closure-inspection', (project / 'bin/prepare-tests.sh').read_text().splitlines())
        self.assertEqual(13, len(fast_fixtures.fast_inputs.CLOSURE_INSPECTION_OUTPUTS))
        self.assertTrue(fast_fixtures.fast_inputs.CLOSURE_INSPECTION_OUTPUTS <= fast_fixtures.FULL_REQUIRED)
        for name in fast_fixtures.fast_inputs.CLOSURE_INSPECTION_OUTPUTS:
            self.assertTrue(fast_fixtures.fast_inputs.allowed_payload(name), name)
        self.assertFalse(fast_fixtures.fast_inputs.allowed_payload('build/closure-inspection/native/Other.o'))

    def test_hint_trace_has_native_fixture_and_complete_cache_scope(self):
        project = Path(__file__).resolve().parents[2]
        manifest, owners = fast_fixtures._manifest(project)
        self.assertEqual('hint-trace', owners['thc.runtime.HintTraceTest'])
        self.assertEqual([{'argv': ['cabal', 'run', 'exe:thc-fixtures', '--offline', '--', 'hint-trace']}],
                         manifest['groups']['hint-trace']['commands'])
        self.assertIn('"$fixture_bin" hint-trace', (project / 'bin/prepare-tests.sh').read_text().splitlines())
        self.assertIn('build/hint-trace/manifest.json', fast_fixtures.fast_inputs.REQUIRED)
        self.assertEqual(18, len([path for path in fast_fixtures.FULL_REQUIRED if path.startswith('build/hint-trace/')]))
        self.assertEqual(fast_fixtures.FULL_PREPARATION_PLAN, fast_fixtures._preparation_plan(project))

    def test_stm_keeps_original_exception_proof_in_explicit_fail_closed_full_core_gate(self):
        project = Path(__file__).resolve().parents[2]
        manifest, owners = fast_fixtures._manifest(project)
        self.assertIn('thc.runtime.ManagedSTMTest', manifest['fixtureFreeJunit'])
        self.assertNotIn('thc.runtime.STMFullCoreTest', owners)
        self.assertNotIn('"$fixture_bin" stm', (project / 'bin/prepare-tests.sh').read_text())
        self.assertNotIn('build/stm/manifest.json', fast_fixtures.FULL_REQUIRED)
        self.assertTrue((project / 'src/fullCoreTest/java/thc/runtime/STMFullCoreTest.java').is_file())
        build = (project / 'build.gradle').read_text()
        self.assertIn('[["stmFullCoreTest", false],[ "stmDenseFullCoreTest", true]].each { taskName, dense -> tasks.register(taskName, Test)', build)
        self.assertIn('systemProperty("thc.handoffSlabs", dense.toString())', build)
        self.assertIn('includeTestsMatching("thc.runtime.STMFullCoreTest")', build)
        self.assertIn('checkBuild(file("build/stm/manifest.json").isFile())', build)
        self.assertEqual(fast_fixtures.FULL_PREPARATION_PLAN, fast_fixtures._preparation_plan(project))

    def test_boxed_cas_owns_native_inputs_and_only_declared_artifacts(self):
        project = Path(__file__).resolve().parents[2]
        manifest, owners = fast_fixtures._manifest(project)
        group = manifest['groups']['boxed-cas']
        cache = fast_fixtures.fast_inputs
        self.assertEqual('boxed-cas', owners['thc.runtime.BoxedCasTest'])
        self.assertEqual([{'argv': ['cabal', 'run', 'exe:thc-fixtures', '--offline', '--', 'boxed-cas']}], group['commands'])
        self.assertIn('t/fixtures/core/BoxedCasCounter.hs', group['sources'])
        self.assertIn('"$fixture_bin" boxed-cas', (project / 'bin/prepare-tests.sh').read_text().splitlines())
        self.assertIn('build/boxed-cas/manifest.json', fast_fixtures.FULL_REQUIRED)
        for suffix in cache.BOXED_CAS_FILES:
            self.assertTrue(cache.allowed_payload('build/boxed-cas/run-1/' + suffix))
        for suffix in ('logs/secret.stdout', 'native/unowned', 'pre-core/Fake.json'):
            self.assertFalse(cache.allowed_payload('build/boxed-cas/run-1/' + suffix))
        self.assertEqual(fast_fixtures.FULL_PREPARATION_PLAN, fast_fixtures._preparation_plan(project))

    def test_scalar_memory_owns_closed_native_outputs_and_rejects_stale_receipts(self):
        project = Path(__file__).resolve().parents[2]
        manifest, owners = fast_fixtures._manifest(project)
        cache = fast_fixtures.fast_inputs
        group = manifest['groups']['scalar-memory-utilities']
        self.assertEqual('scalar-memory-utilities', owners['thc.runtime.ScalarMemoryUtilitiesTest'])
        self.assertEqual([{'argv': ['cabal', 'run', 'exe:thc-fixtures', '--offline', '--', 'scalar-memory-utilities']}], group['commands'])
        self.assertIn('"$fixture_bin" scalar-memory-utilities', (project / 'bin/prepare-tests.sh').read_text().splitlines())
        self.assertTrue(cache.SCALAR_MEMORY_OUTPUTS <= fast_fixtures.FULL_REQUIRED)
        self.assertEqual(fast_fixtures.FULL_PREPARATION_PLAN, fast_fixtures._preparation_plan(project))
        name = 'build/scalar-memory-utilities/manifest.json'
        artifacts = {}
        for item in cache.SCALAR_MEMORY_OUTPUTS - {name}:
            path = self.root / item; path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text('fixture\n'); artifacts[item] = fast_fixtures._digest(path)
        receipt = dict(schema=1, ghc='9.14.1', entries=list(cache.SCALAR_MEMORY_ENTRIES),
            stages=['pre','post'], nativeRows=271, artifactHashes=artifacts)
        (self.root / name).write_text(json.dumps(receipt))
        self.assertEqual(cache.SCALAR_MEMORY_OUTPUTS, set(fast_fixtures._output_hashes(self.root, group)))
        for bad in (dict(receipt, schema=True), dict(receipt, ghc='9.12.2'), dict(receipt, entries=[]),
                    dict(receipt, nativeRows=270), dict(receipt, stages=['pre']), dict(receipt, artifactHashes={})):
            with self.assertRaises(cache.CacheMiss): cache.scalar_memory_artifact_hashes(bad)
        (self.root / 'build/scalar-memory-utilities/native/oracle').write_text('mutated')
        with self.assertRaises(RuntimeError): fast_fixtures._output_hashes(self.root, group)

    def test_bco_retain_closed_command_and_native_evidence(self):
        project = Path(__file__).resolve().parents[2]
        manifest, owners = fast_fixtures._manifest(project)
        cache = fast_fixtures.fast_inputs
        group = manifest['groups']['ghc-bco']
        self.assertEqual('ghc-bco', owners['thc.runtime.GhcBCOTest'])
        self.assertIn('t/fixtures/core/GhcBCO.hs', group['sources'])
        self.assertIn('"$fixture_bin" ghc-bco', (project / 'bin/prepare-tests.sh').read_text().splitlines())
        self.assertEqual(298, len(cache.BCO_OUTPUTS))
        self.assertTrue(cache.BCO_OUTPUTS <= fast_fixtures.FULL_REQUIRED)
        name = 'build/ghc-bco/manifest.json'
        artifacts = {}
        for item in cache.BCO_OUTPUTS - {name}:
            path = self.root / item; path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text('fixture\n'); artifacts[item] = fast_fixtures._digest(path)
        receipt = dict(schema=1, ghc='9.14.1', entries=list(cache.BCO_ENTRIES), stages=['pre', 'post'],
                       arguments=[-2, 0, 7], native=[0] * 105, artifactHashes=artifacts)
        (self.root / name).write_text(json.dumps(receipt))
        self.assertEqual(cache.BCO_OUTPUTS, set(fast_fixtures._output_hashes(self.root, group)))
        for bad in (dict(receipt, schema=True), dict(receipt, ghc='9.12.2'), dict(receipt, entries=[]),
                    dict(receipt, native=[0] * 104), dict(receipt, native=[False] * 105),
                    dict(receipt, stages=['pre']), dict(receipt, artifactHashes={})):
            with self.assertRaises(cache.CacheMiss): cache.bco_artifact_hashes(bad)
        self.assertFalse(cache.allowed_payload('build/ghc-bco/unknown.json'))
        (self.root / 'build/ghc-bco/commands/native-run.stdout').write_text('mutated')
        with self.assertRaises(RuntimeError): fast_fixtures._output_hashes(self.root, group)

    def test_delimited_continuations_retain_closed_command_and_native_evidence(self):
        project = Path(__file__).resolve().parents[2]
        manifest, owners = fast_fixtures._manifest(project)
        cache = fast_fixtures.fast_inputs
        group = manifest['groups']['delimited-continuations']
        self.assertEqual('delimited-continuations', owners['thc.runtime.DelimitedContinuationsTest'])
        self.assertIn('t/fixtures/core/DelimitedContinuations.hs', group['sources'])
        self.assertEqual([{'argv': ['cabal', 'run', 'exe:thc-fixtures', '--offline', '--', 'delimited-continuations']}], group['commands'])
        self.assertIn('"$fixture_bin" delimited-continuations', (project / 'bin/prepare-tests.sh').read_text().splitlines())
        self.assertEqual(168, len(cache.DELIMITED_OUTPUTS))
        self.assertEqual({f"build/delimited-continuations/{stage}/core/DelimitedContinuations.cbd"
                          for stage in ("pre", "post")} |
                         {"build/delimited-continuations/parked/core/ParkedControl.cbd"},
                         {path for path in cache.DELIMITED_OUTPUTS if path.endswith(".cbd")})
        for command in ("parked-native-build", "parked-native-run", "parked-export", "parked-audit"):
            for suffix in ("stdout", "stderr", "command.json"):
                self.assertIn(f"build/delimited-continuations/commands/{command}.{suffix}", cache.DELIMITED_OUTPUTS)
        for path in cache.DELIMITED_OUTPUTS:
            if path.endswith(".cbd"):
                self.assertNotIn(path.removesuffix(".cbd") + ".json", cache.DELIMITED_OUTPUTS)
        self.assertTrue(cache.DELIMITED_OUTPUTS <= fast_fixtures.FULL_REQUIRED)
        name = 'build/delimited-continuations/manifest.json'
        artifacts = {}
        for item in cache.DELIMITED_OUTPUTS - {name}:
            path = self.root / item; path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text('fixture\n'); artifacts[item] = fast_fixtures._digest(path)
        receipt = dict(schema=1, ghc='9.14.1', entries=list(cache.DELIMITED_ENTRIES), stages=['pre', 'post'],
                       arguments=[-2, 0, 7], native=[0] * 51, artifactHashes=artifacts)
        (self.root / name).write_text(json.dumps(receipt))
        self.assertEqual(cache.DELIMITED_OUTPUTS, set(fast_fixtures._output_hashes(self.root, group)))
        for bad in (dict(receipt, schema=True), dict(receipt, ghc='9.12.2'), dict(receipt, entries=[]),
                    dict(receipt, native=[0] * 50), dict(receipt, native=[False] * 51),
                    dict(receipt, stages=['pre']), dict(receipt, artifactHashes={})):
            with self.assertRaises(cache.CacheMiss): cache.delimited_artifact_hashes(bad)
        (self.root / 'build/delimited-continuations/commands/native-run.stdout').write_text('mutated')
        with self.assertRaises(RuntimeError): fast_fixtures._output_hashes(self.root, group)

    def test_simd_address_closed_native_outputs_and_stale_receipts(self):
        project = Path(__file__).resolve().parents[2]
        manifest, owners = fast_fixtures._manifest(project)
        cache = fast_fixtures.fast_inputs
        group = manifest['groups']['simd-address-families']
        self.assertEqual('simd-address-families', owners['thc.runtime.SimdAddressFamiliesTest'])
        self.assertEqual([{'argv': ['cabal', 'run', 'exe:thc-fixtures', '--offline', '--', 'simd-address-families']}], group['commands'])
        self.assertIn('"$fixture_bin" simd-address-families', (project / 'bin/prepare-tests.sh').read_text().splitlines())
        self.assertTrue(cache.SIMD_ADDRESS_OUTPUTS <= fast_fixtures.FULL_REQUIRED)
        self.assertEqual(fast_fixtures.FULL_PREPARATION_PLAN, fast_fixtures._preparation_plan(project))
        name = 'build/simd-address-families/manifest.json'
        artifacts = {}
        for item in cache.SIMD_ADDRESS_OUTPUTS - {name}:
            path = self.root / item; path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text('fixture\n'); artifacts[item] = fast_fixtures._digest(path)
        receipt = dict(schema=1, ghc='9.14.1', entries=list(cache.SIMD_ADDRESS_ENTRIES), scalarRows=3456,
            nativeVector128Rows=576 if cache.SIMD_ADDRESS_NATIVE128 else 0,
            stages={stage: {} for stage, _ in cache.SIMD_ADDRESS_STAGES}, artifactHashes=artifacts)
        (self.root / name).write_text(json.dumps(receipt))
        self.assertEqual(cache.SIMD_ADDRESS_OUTPUTS, set(fast_fixtures._output_hashes(self.root, group)))
        for bad in (dict(receipt, schema=True), dict(receipt, ghc='9.12.2'), dict(receipt, entries=[]),
                    dict(receipt, scalarRows=3455), dict(receipt, stages={}), dict(receipt, artifactHashes={})):
            with self.assertRaises(cache.CacheMiss): cache.simd_address_artifact_hashes(bad)
        (self.root / 'build/simd-address-families/source/SimdAddressAudit.hs').write_text('mutated')
        with self.assertRaises(RuntimeError): fast_fixtures._output_hashes(self.root, group)

    def test_thread_inventory_owns_exact_native_outputs_and_rejects_partial_receipts(self):
        project = Path(__file__).resolve().parents[2]
        manifest, owners = fast_fixtures._manifest(project)
        cache = fast_fixtures.fast_inputs
        group = manifest['groups']['thread-inventory']
        self.assertEqual('thread-inventory', owners['thc.runtime.ThreadInventoryNativeTest'])
        self.assertIn('thc.runtime.GuestThreadInventoryTest', manifest['fixtureFreeJunit'])
        self.assertIn('t/fixtures/core/ThreadInventory.hs', group['sources'])
        self.assertEqual([{'argv': ['cabal', 'run', 'exe:thc-fixtures', '--offline', '--', 'thread-inventory']}], group['commands'])
        self.assertIn('"$fixture_bin" thread-inventory', (project / 'bin/prepare-tests.sh').read_text().splitlines())
        self.assertIn('build/thread-inventory', fast_fixtures.FULL_OUTPUT_ROOTS)
        self.assertTrue(cache.THREAD_INVENTORY_OUTPUTS <= fast_fixtures.FULL_REQUIRED)
        self.assertEqual(fast_fixtures.FULL_PREPARATION_PLAN, fast_fixtures._preparation_plan(project))
        name = 'build/thread-inventory/manifest.json'
        artifacts = {}
        for item in cache.THREAD_INVENTORY_OUTPUTS - {name}:
            path = self.root / item; path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text('fixture\n'); artifacts[item] = fast_fixtures._digest(path)
        receipt = dict(schema=1, ghc='9.14.1', entries=list(cache.THREAD_INVENTORY_ENTRIES), stages=['pre', 'post'],
                       nativeThread='unbound forkIO, threaded RTS -N2', artifactHashes=artifacts)
        (self.root / name).write_text(json.dumps(receipt))
        self.assertEqual(cache.THREAD_INVENTORY_OUTPUTS, set(fast_fixtures._output_hashes(self.root, group)))
        for bad in (dict(receipt, schema=True), dict(receipt, ghc='9.12.2'), dict(receipt, entries=[]),
                    dict(receipt, nativeThread='main'), dict(receipt, stages=['pre']), dict(receipt, artifactHashes={})):
            with self.assertRaises(cache.CacheMiss): cache.thread_inventory_artifact_hashes(bad)
        (self.root / 'build/thread-inventory/pre/core/ThreadInventory.cbd').write_text('mutated')
        with self.assertRaises(RuntimeError): fast_fixtures._output_hashes(self.root, group)

    def test_thread_scheduling_has_closed_outputs_and_native_rts_provenance(self):
        project = Path(__file__).resolve().parents[2]
        manifest, owners = fast_fixtures._manifest(project)
        cache = fast_fixtures.fast_inputs
        group = manifest['groups']['thread-scheduling']
        self.assertEqual('thread-scheduling', owners['thc.runtime.ThreadSchedulingTest'])
        self.assertTrue(cache.THREAD_SCHEDULING_OUTPUTS <= fast_fixtures.FULL_REQUIRED)
        self.assertEqual(fast_fixtures.FULL_PREPARATION_PLAN, fast_fixtures._preparation_plan(project))
        name = 'build/thread-scheduling/manifest.json'
        artifacts = {}
        for item in cache.THREAD_SCHEDULING_OUTPUTS - {name}:
            path = self.root / item; path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text('fixture\n'); artifacts[item] = fast_fixtures._digest(path)
            self.assertTrue(cache.allowed_payload(item))
        receipt = dict(schema=1, ghc='9.14.1', entries=list(cache.THREAD_SCHEDULING_ENTRIES), stages=['pre', 'post'],
                       nativeRTS='non-threaded: direct delay# uses the POSIX I/O manager', artifactHashes=artifacts)
        (self.root / name).write_text(json.dumps(receipt))
        self.assertEqual(cache.THREAD_SCHEDULING_OUTPUTS, set(fast_fixtures._output_hashes(self.root, group)))
        for bad in (dict(receipt, schema=True), dict(receipt, entries=[]), dict(receipt, stages=['pre']),
                    dict(receipt, nativeRTS='threaded'), dict(receipt, artifactHashes={})):
            with self.assertRaises(cache.CacheMiss): cache.thread_scheduling_artifact_hashes(bad)
        self.assertFalse(cache.allowed_payload('build/thread-scheduling/native/oracle'))

    def test_integer_completion_has_owned_native_inputs_and_cache_registration(self):
        project = Path(__file__).resolve().parents[2]
        manifest, owners = fast_fixtures._manifest(project)
        group = manifest["groups"]["integer-completion"]
        self.assertEqual("integer-completion", owners["thc.runtime.IntegerCompletionTest"])
        self.assertEqual([{"argv": ["cabal", "run", "exe:thc-fixtures", "--offline", "--", "integer-completion"]}], group["commands"])
        self.assertEqual(["build/integer-completion"], group["outputs"])
        self.assertEqual({"t/haskell-fixtures/Main.hs", "t/haskell-fixtures/IntegerCompletionFixtures.hs",
                          "t/fixtures/compiler/IntegerCompletionAudit.hs"}, set(group["sources"]))
        self.assertIn('"$fixture_bin" integer-completion', (project / "bin/prepare-tests.sh").read_text().splitlines())
        self.assertIn("build/integer-completion/manifest.json", fast_fixtures.fast_inputs.REQUIRED)
        self.assertIn("build/integer-completion", fast_fixtures.FULL_OUTPUT_ROOTS)
        self.assertTrue(fast_fixtures.fast_inputs.native_executable("build/integer-completion/native/integer-completion-oracle"))
        self.assertEqual(fast_fixtures.FULL_PREPARATION_PLAN, fast_fixtures._preparation_plan(project))

    def test_atomic_int_array_family_owns_native_inputs_and_full_receipt(self):
        project = Path(__file__).resolve().parents[2]
        manifest, owners = fast_fixtures._manifest(project)
        self.assertEqual('atomic-int-arrays', owners['thc.runtime.AtomicIntArrayTest'])
        group = manifest['groups']['atomic-int-arrays']
        self.assertEqual([{'argv': ['cabal', 'run', 'exe:thc-fixtures', '--offline', '--', 'atomic-int-arrays']}], group['commands'])
        self.assertIn('t/fixtures/compiler/AtomicIntArrayAudit.hs', group['sources'])
        self.assertIn('t/haskell-fixtures/AtomicIntArrayFixtures.hs', group['sources'])
        self.assertIn('"$fixture_bin" atomic-int-arrays', (project / 'bin/prepare-tests.sh').read_text().splitlines())
        self.assertIn('build/atomic-int-arrays/manifest.json', fast_fixtures.fast_inputs.REQUIRED)
        self.assertEqual(6, len([p for p in fast_fixtures.FULL_REQUIRED if p.startswith('build/atomic-int-arrays/')]))
        self.assertEqual(fast_fixtures.FULL_PREPARATION_PLAN, fast_fixtures._preparation_plan(project))

    def test_simd128_address_completion_has_native_owner_and_closed_cache_scope(self):
        project = Path(__file__).resolve().parents[2]
        manifest, owners = fast_fixtures._manifest(project)
        self.assertEqual('simd128-addresses', owners['thc.runtime.Simd128AddressNativeTest'])
        self.assertIsNone(owners['thc.runtime.Simd128AddressTest'])
        group = manifest['groups']['simd128-addresses']
        self.assertEqual([{'argv': ['cabal', 'run', 'exe:thc-fixtures', '--offline', '--', 'simd128-addresses']}],
                         group['commands'])
        self.assertEqual(['build/simd128-addresses'], group['outputs'])
        sources = fast_fixtures._source_hashes(project, group)
        for path in ('t/fixtures/compiler/Simd128AddressAudit.hs', 't/fixtures/compiler/Simd128AddressNative.hs',
                     't/haskell-fixtures/Simd128AddressFixtures.hs', 'bin/core_vector_memory.py',
                     'bin/core_vectors.py', 'bin/simd-families.json'):
            self.assertIn(path, sources)
        self.assertIn('build/simd128-addresses/manifest.json', fast_fixtures.FULL_REQUIRED)
        self.assertIn('build/simd128-addresses', fast_fixtures.FULL_OUTPUT_ROOTS)
        self.assertEqual(36, len(fast_fixtures.fast_inputs.SIMD128_ARRAY_ENTRIES))
        self.assertIn('"$fixture_bin" simd128-addresses', (project / 'bin/prepare-tests.sh').read_text().splitlines())
        for path in fast_fixtures.fast_inputs.SIMD128_ADDRESS_OUTPUTS:
            self.assertTrue(fast_fixtures.fast_inputs.allowed_payload(path), path)
        for path in ('build/simd128-addresses/native/rogue', 'build/simd128-addresses/commands/fake.stdout',
                     'build/simd128-addresses/arbitrary.json'):
            self.assertFalse(fast_fixtures.fast_inputs.allowed_payload(path), path)

    def test_simd128_array_completion_has_native_owner_and_closed_cache_scope(self):
        project = Path(__file__).resolve().parents[2]
        manifest, owners = fast_fixtures._manifest(project)
        self.assertEqual('simd128-arrays', owners['thc.runtime.Simd128ArrayNativeTest'])
        self.assertIsNone(owners['thc.runtime.Simd128ArrayProofTest'])
        group = manifest['groups']['simd128-arrays']
        self.assertEqual([{'argv': ['cabal', 'run', 'exe:thc-fixtures', '--offline', '--', 'simd128-arrays']}],
                         group['commands'])
        self.assertEqual(['build/simd128-arrays'], group['outputs'])
        sources = fast_fixtures._source_hashes(project, group)
        for path in ('t/fixtures/compiler/Simd128ArrayAudit.hs', 't/fixtures/compiler/Simd128ArrayNative.hs',
                     't/haskell-fixtures/Simd128ArrayFixtures.hs', 'bin/core_vector_memory.py',
                     'bin/core_vectors.py', 'bin/simd-families.json'):
            self.assertIn(path, sources)
        self.assertIn('build/simd128-arrays/manifest.json', fast_fixtures.FULL_REQUIRED)
        self.assertIn('build/simd128-arrays', fast_fixtures.FULL_OUTPUT_ROOTS)
        self.assertEqual(36, len(fast_fixtures.fast_inputs.SIMD128_ARRAY_ENTRIES))
        self.assertIn('"$fixture_bin" simd128-arrays', (project / 'bin/prepare-tests.sh').read_text().splitlines())
        for path in fast_fixtures.fast_inputs.SIMD128_ARRAY_OUTPUTS:
            self.assertTrue(fast_fixtures.fast_inputs.allowed_payload(path), path)
        for path in ('build/simd128-arrays/native/rogue', 'build/simd128-arrays/commands/fake.stdout',
                     'build/simd128-arrays/arbitrary.json'):
            self.assertFalse(fast_fixtures.fast_inputs.allowed_payload(path), path)

    def test_thread_label_uses_its_native_core_fixture_and_baseline_registration(self):
        project = Path(__file__).resolve().parents[2]
        manifest, owners = fast_fixtures._manifest(project)
        self.assertEqual('thread-label', owners['thc.runtime.ThreadLabelNativeTest'])
        self.assertIn('thc.runtime.GuestThreadLabelTest', manifest['fixtureFreeJunit'])
        self.assertEqual([{'argv': ['cabal', 'run', 'exe:thc-fixtures', '--offline', '--', 'thread-label']}],
                         manifest['groups']['thread-label']['commands'])
        self.assertIn('"$fixture_bin" thread-label', (project / 'bin/prepare-tests.sh').read_text().splitlines())
        self.assertIn('build/thread-label/manifest.json', fast_fixtures.fast_inputs.REQUIRED)
        self.assertEqual(14, len([p for p in fast_fixtures.FULL_REQUIRED if p.startswith('build/thread-label/')]))
        for stage in ('pre', 'post'):
            stem = f'build/thread-label/{stage}/core/ThreadLabelAudit'
            self.assertIn(stem + '.cbd', fast_fixtures.FULL_REQUIRED)
            self.assertTrue(fast_fixtures.fast_inputs.allowed_payload(stem + '.cbd'))
            self.assertFalse(fast_fixtures.fast_inputs.allowed_payload(stem + '.json'))
            self.assertFalse(fast_fixtures.fast_inputs.allowed_payload(stem.replace('ThreadLabelAudit', 'Unreviewed') + '.cbd'))
        self.assertEqual(fast_fixtures.FULL_PREPARATION_PLAN, fast_fixtures._preparation_plan(project))

    def test_original_tcsetattr_registration_and_closed_native_receipt(self):
        project = Path(__file__).resolve().parents[2]
        manifest, owners = fast_fixtures._manifest(project)
        group = manifest['groups']['original-tcsetattr']
        self.assertEqual('original-tcsetattr', owners['thc.runtime.OriginalTcsetattrTest'])
        self.assertEqual([{'argv': ['cabal', 'run', 'exe:thc-fixtures', '--offline', '--', 'original-tcsetattr']}], group['commands'])
        self.assertIn('"$fixture_bin" original-tcsetattr', (project / 'bin/prepare-tests.sh').read_text().splitlines())
        self.assertEqual(fast_fixtures.FULL_PREPARATION_PLAN, fast_fixtures._preparation_plan(project))
        self.assertIn('build/original-tcsetattr', fast_fixtures.FULL_OUTPUT_ROOTS)
        cache = fast_fixtures.fast_inputs
        name = 'build/original-tcsetattr/manifest.json'
        with mock.patch.object(cache, 'GMP_NATIVE_HOST', True):
            artifacts = {}
            for item in cache.ORIGINAL_TCSETATTR_OUTPUTS - {name}:
                path = self.root / item; path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text('fixture\n'); artifacts[item] = fast_fixtures._digest(path)
            receipt = dict(schema=1, supported=True, strictAccepted=True, runtimeVerified=False,
                           installedArtifactsHashed=False, nativeRows=22,
                           entries=list(cache.ORIGINAL_TCSETATTR_ENTRIES), artifactHashes=artifacts)
            path = self.root / name; path.write_text(json.dumps(receipt))
            self.assertEqual(cache.ORIGINAL_TCSETATTR_OUTPUTS, set(fast_fixtures._output_hashes(self.root, group)))
            for bad in (dict(receipt, schema=True), dict(receipt, entries=[]), dict(receipt, nativeRows=21),
                        dict(receipt, nativeRows=True), dict(receipt, runtimeVerified=True),
                        dict(receipt, artifactHashes={}), dict(receipt, artifactHashes=dict(artifacts, **{'build/original-tcsetattr/extra.json': '0'*64}))):
                with self.assertRaises(cache.CacheMiss): cache.tcsetattr_artifact_hashes(bad)
            artifact = self.root / 'build/original-tcsetattr/pre/core/OriginalTcsetattrAudit.cbd'
            artifact.write_text('mutated')
            with self.assertRaises(RuntimeError): fast_fixtures._output_hashes(self.root, group)
            artifact.unlink(); artifact.symlink_to(self.root / 'build/original-tcsetattr/oracle.json')
            with self.assertRaises(cache.CacheMiss): fast_fixtures._output_hashes(self.root, group)

    def test_original_tcgetattr_registration_and_closed_native_receipt(self):
        project = Path(__file__).resolve().parents[2]
        manifest, owners = fast_fixtures._manifest(project)
        group = manifest['groups']['original-tcgetattr']
        self.assertEqual('original-tcgetattr', owners['thc.runtime.OriginalTcgetattrTest'])
        self.assertEqual([{'argv': ['cabal', 'run', 'exe:thc-fixtures', '--offline', '--', 'original-tcgetattr']}], group['commands'])
        self.assertIn('"$fixture_bin" original-tcgetattr', (project / 'bin/prepare-tests.sh').read_text().splitlines())
        self.assertEqual(fast_fixtures.FULL_PREPARATION_PLAN, fast_fixtures._preparation_plan(project))
        self.assertIn('build/original-tcgetattr', fast_fixtures.FULL_OUTPUT_ROOTS)
        cache = fast_fixtures.fast_inputs
        name = 'build/original-tcgetattr/manifest.json'
        with mock.patch.object(cache, 'GMP_NATIVE_HOST', True):
            artifacts = {}
            for item in cache.ORIGINAL_TCGETATTR_OUTPUTS - {name}:
                path = self.root / item; path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text('fixture\n'); artifacts[item] = fast_fixtures._digest(path)
            receipt = dict(schema=1, supported=True, strictAccepted=True, runtimeVerified=False,
                           installedArtifactsHashed=False, nativeRows=12,
                           entries=list(cache.ORIGINAL_TCGETATTR_ENTRIES), artifactHashes=artifacts)
            path = self.root / name; path.write_text(json.dumps(receipt))
            self.assertEqual(cache.ORIGINAL_TCGETATTR_OUTPUTS, set(fast_fixtures._output_hashes(self.root, group)))
            for bad in (dict(receipt, schema=True), dict(receipt, entries=[]), dict(receipt, nativeRows=11),
                        dict(receipt, nativeRows=True), dict(receipt, runtimeVerified=True),
                        dict(receipt, artifactHashes={}), dict(receipt, artifactHashes=dict(artifacts, **{'build/original-tcgetattr/extra.json': '0'*64}))):
                with self.assertRaises(cache.CacheMiss): cache.tcgetattr_artifact_hashes(bad)
            artifact = self.root / 'build/original-tcgetattr/pre/core/OriginalTcgetattrAudit.cbd'
            artifact.write_text('mutated')
            with self.assertRaises(RuntimeError): fast_fixtures._output_hashes(self.root, group)
            artifact.unlink(); artifact.symlink_to(self.root / 'build/original-tcgetattr/oracle.json')
            with self.assertRaises(cache.CacheMiss): fast_fixtures._output_hashes(self.root, group)

    def test_original_sigprocmask_registration_and_closed_native_receipt(self):
        project = Path(__file__).resolve().parents[2]
        manifest, owners = fast_fixtures._manifest(project)
        group = manifest['groups']['original-sigprocmask']
        self.assertEqual('original-sigprocmask', owners['thc.runtime.OriginalSigprocmaskTest'])
        self.assertEqual([{'argv': ['cabal', 'run', 'exe:thc-fixtures', '--offline', '--', 'original-sigprocmask']}], group['commands'])
        self.assertIn('"$fixture_bin" original-sigprocmask', (project / 'bin/prepare-tests.sh').read_text().splitlines())
        self.assertEqual(fast_fixtures.FULL_PREPARATION_PLAN, fast_fixtures._preparation_plan(project))
        self.assertIn('build/original-sigprocmask', fast_fixtures.FULL_OUTPUT_ROOTS)
        cache = fast_fixtures.fast_inputs
        name = 'build/original-sigprocmask/manifest.json'
        with mock.patch.object(cache, 'GMP_NATIVE_HOST', True):
            artifacts = {}
            for item in cache.ORIGINAL_SIGPROCMASK_OUTPUTS - {name}:
                path = self.root / item; path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text('fixture\n'); artifacts[item] = fast_fixtures._digest(path)
            receipt = dict(schema=1, supported=True, strictAccepted=True, runtimeVerified=False,
                           installedArtifactsHashed=False, nativeRows=8,
                           entries=list(cache.ORIGINAL_SIGPROCMASK_ENTRIES), artifactHashes=artifacts)
            path = self.root / name; path.write_text(json.dumps(receipt))
            self.assertEqual(cache.ORIGINAL_SIGPROCMASK_OUTPUTS, set(fast_fixtures._output_hashes(self.root, group)))
            for bad in (dict(receipt, schema=True), dict(receipt, entries=[]), dict(receipt, nativeRows=7),
                        dict(receipt, nativeRows=True), dict(receipt, runtimeVerified=True),
                        dict(receipt, artifactHashes={}), dict(receipt, artifactHashes=dict(artifacts, **{'build/original-sigprocmask/extra.json': '0'*64}))):
                with self.assertRaises(cache.CacheMiss): cache.sigprocmask_artifact_hashes(bad)
            artifact = self.root / 'build/original-sigprocmask/pre/core/OriginalSigprocmaskAudit.cbd'
            artifact.write_text('mutated')
            with self.assertRaises(RuntimeError): fast_fixtures._output_hashes(self.root, group)
            artifact.unlink(); artifact.symlink_to(self.root / 'build/original-sigprocmask/oracle.json')
            with self.assertRaises(cache.CacheMiss): fast_fixtures._output_hashes(self.root, group)

    def test_original_sigset_registration_and_closed_native_receipt(self):
        project = Path(__file__).resolve().parents[2]
        manifest, owners = fast_fixtures._manifest(project)
        group = manifest['groups']['original-sigset']
        self.assertEqual('original-sigset', owners['thc.runtime.OriginalSigsetTest'])
        self.assertEqual([{'argv': ['cabal', 'run', 'exe:thc-fixtures', '--offline', '--', 'original-sigset']}], group['commands'])
        self.assertIn('"$fixture_bin" original-sigset', (project / 'bin/prepare-tests.sh').read_text().splitlines())
        self.assertEqual(fast_fixtures.FULL_PREPARATION_PLAN, fast_fixtures._preparation_plan(project))
        self.assertIn('build/original-sigset', fast_fixtures.FULL_OUTPUT_ROOTS)
        cache = fast_fixtures.fast_inputs
        name = 'build/original-sigset/manifest.json'
        with mock.patch.object(cache, 'GMP_NATIVE_HOST', True):
            artifacts = {}
            for item in cache.ORIGINAL_SIGSET_OUTPUTS - {name}:
                path = self.root / item; path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text('fixture\n'); artifacts[item] = fast_fixtures._digest(path)
            receipt = dict(schema=1, supported=True, strictAccepted=True, runtimeVerified=False,
                           installedArtifactsHashed=False, nativeRows=532,
                           entries=list(cache.ORIGINAL_SIGSET_ENTRIES), artifactHashes=artifacts)
            path = self.root / name; path.write_text(json.dumps(receipt))
            self.assertEqual(cache.ORIGINAL_SIGSET_OUTPUTS, set(fast_fixtures._output_hashes(self.root, group)))
            for bad in (dict(receipt, schema=True), dict(receipt, entries=[]), dict(receipt, nativeRows=531),
                        dict(receipt, nativeRows=True), dict(receipt, runtimeVerified=True),
                        dict(receipt, artifactHashes={}), dict(receipt, artifactHashes=dict(artifacts, **{'build/original-sigset/extra.json': '0'*64}))):
                with self.assertRaises(cache.CacheMiss): cache.sigset_artifact_hashes(bad)
            artifact = self.root / 'build/original-sigset/pre/core/OriginalSigsetAudit.cbd'
            artifact.write_text('mutated')
            with self.assertRaises(RuntimeError): fast_fixtures._output_hashes(self.root, group)
            artifact.unlink(); artifact.symlink_to(self.root / 'build/original-sigset/oracle.json')
            with self.assertRaises(cache.CacheMiss): fast_fixtures._output_hashes(self.root, group)

    def test_full_core_decoder_is_explicit_but_getter_controls_remain_baseline(self):
        project = Path(__file__).resolve().parents[2]
        manifest, owners = fast_fixtures._manifest(project)
        self.assertNotIn('original-stack-decoder', manifest['groups'])
        self.assertNotIn('thc.runtime.OriginalStackDecoderTest', owners)
        self.assertEqual('original-stack', owners['thc.runtime.OriginalStackDecoderCallTest'])
        for name in ('OriginalStackDecoderCallTest', 'ManagedStackRuntimeTest'):
            self.assertTrue((project / f'src/test/java/thc/runtime/{name}.java').is_file())
        self.assertNotIn('build/original-stack-decoder', fast_fixtures.FULL_OUTPUT_ROOTS)
        self.assertNotIn('build/original-stack-decoder/manifest.json', fast_fixtures.FULL_REQUIRED)
        self.assertNotIn('"$fixture_bin" original-stack-decoder', (project / 'bin/prepare-tests.sh').read_text())
        self.assertEqual(fast_fixtures.FULL_PREPARATION_PLAN, fast_fixtures._preparation_plan(project))
        self.assertFalse((project / 'src/test/java/thc/runtime/OriginalStackDecoderTest.java').exists())
        self.assertTrue((project / 'src/fullCoreTest/java/thc/runtime/OriginalStackDecoderTest.java').is_file())

    def test_explicit_weak_fixture_registration_preserves_native_and_strict_inputs(self):
        project = Path(__file__).resolve().parents[2]
        manifest, owners = fast_fixtures._manifest(project)
        group = manifest['groups']['weak-explicit']
        self.assertEqual('weak-explicit', owners['thc.runtime.ManagedWeakTest'])
        self.assertEqual([{'argv': ['cabal', 'run', 'exe:thc-fixtures', '--offline', '--',
                                   'weak-explicit']}], group['commands'])
        self.assertEqual(['build/weak-explicit'], group['outputs'])
        self.assertTrue(all((project / path).is_file() for path in group['sources']))
        self.assertIn('"$fixture_bin" weak-explicit', (project / 'bin/prepare-tests.sh').read_text())
        self.assertEqual(fast_fixtures.FULL_PREPARATION_PLAN, fast_fixtures._preparation_plan(project))
        self.assertIn('build/weak-explicit', fast_fixtures.FULL_OUTPUT_ROOTS)
        self.assertIn('build/weak-explicit/manifest.json', fast_fixtures.FULL_REQUIRED)
        gradle = (project / 'build.gradle').read_text()
        for pattern in ('**/*.cbd', '**/*.json', 'oracle.tsv', 'NativeWeak.hs'):
            self.assertIn('"weak-explicit/' + pattern + '"', gradle)
        for suffix in ('manifest.json', 'oracle.tsv', 'NativeWeak.hs',
                       'pre/audit.json', 'post/audit.json',
                       'pre/core/WeakAudit.cbd', 'post/core/WeakAudit.cbd',
                       'pre/core/THC.InterfaceClosure.cbd', 'post/core/THC.InterfaceClosure.cbd'):
            self.assertTrue(fast_fixtures.fast_inputs.allowed_payload('build/weak-explicit/' + suffix))
        self.assertFalse(fast_fixtures.fast_inputs.allowed_payload('build/weak-explicit/result.xml'))
        self.assertFalse(fast_fixtures.fast_inputs.allowed_payload('build/weak-explicit/pre/core/WeakAudit.json'))
        self.assertFalse(fast_fixtures.fast_inputs.allowed_payload('build/weak-explicit/pre/core/Unreviewed.cbd'))
        self.assertTrue(fast_fixtures.fast_inputs.WEAK_OUTPUTS <= fast_fixtures.FULL_REQUIRED)

    def test_native_addresses_fixture_registration(self):
        project = Path(__file__).resolve().parents[2]
        manifest, owners = fast_fixtures._manifest(project)
        group = manifest['groups']['native-addresses']
        self.assertEqual('native-addresses', owners['thc.runtime.NativeAddressTest'])
        self.assertEqual('native-addresses', owners['thc.runtime.NativeMallocTest'])
        self.assertEqual([{'argv': ['cabal', 'run', 'exe:thc-fixtures', '--offline', '--',
                                   'native-addresses']}], group['commands'])
        self.assertEqual(['build/native-addresses/manifest.json', 'build/native-addresses/oracle.json',
                          'build/native-malloc/manifest.json', 'build/native-malloc/oracle.txt'], group['outputs'])
        self.assertIn('t/fixtures/compiler/NativeMallocNative.hs', group['sources'])
        self.assertIn('src/test/resources/core/original-malloc-descriptors.json', group['sources'])
        self.assertTrue(all((project / path).is_file() for path in group['sources']))
        self.assertIn('"$fixture_bin" native-addresses', (project / 'bin/prepare-tests.sh').read_text())
        self.assertEqual(fast_fixtures.FULL_PREPARATION_PLAN, fast_fixtures._preparation_plan(project))
        self.assertIn('build/native-addresses/manifest.json', fast_fixtures.FULL_REQUIRED)
        self.assertIn('build/native-malloc/manifest.json', fast_fixtures.FULL_REQUIRED)
        self.assertIn('build/native-malloc/oracle.txt', fast_fixtures.FULL_REQUIRED)
        for suffix in ('manifest.json', 'oracle.json'):
            self.assertTrue(fast_fixtures.fast_inputs.allowed_payload('build/native-addresses/' + suffix))
            self.assertIn('"native-addresses/' + suffix + '"', (project / 'build.gradle').read_text())
        self.assertFalse(fast_fixtures.fast_inputs.allowed_payload('build/native-addresses/native/oracle'))
        for suffix in ('manifest.json', 'oracle.txt'):
            path = 'build/native-malloc/' + suffix
            self.assertTrue(fast_fixtures.fast_inputs.allowed_payload(path))
            self.assertIn('"native-malloc/' + suffix + '"', (project / 'build.gradle').read_text())
            self.assertIn(path, (project / '.github/workflows/build.yml').read_text())
        self.assertFalse(fast_fixtures.fast_inputs.allowed_payload('build/native-malloc/native/oracle'))

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
        script = (project / 'bin/prepare-tests.sh').read_text()
        self.assertIn('case "$(uname -s)-$(uname -m)" in\n'
                      '  Linux-x86_64) "$fixture_bin" original-gmp --require-supported ;;\nesac', script)
        self.assertEqual(fast_fixtures.FULL_PREPARATION_PLAN, fast_fixtures._preparation_plan(project))
        self.assertIn('build/original-gmp', fast_fixtures.FULL_OUTPUT_ROOTS)
        self.assertEqual(fast_fixtures.fast_inputs.GMP_NATIVE_HOST,
                         'build/original-gmp/manifest.json' in fast_fixtures.FULL_REQUIRED)
        gradle = (project / 'build.gradle').read_text()
        for name in ('**/*.json', 'native/oracle', 'exposed-ghc-internal.conf', 'logs/*.stdout', 'logs/*.stderr'):
            self.assertIn('"original-gmp/' + name + '"', gradle)
        self.assertIn('build/original-gmp/', (project / '.github/workflows/build.yml').read_text())
        for workflow in ('build.yml', 'fast.yml'):
            source = (project / '.github/workflows' / workflow).read_text()
            self.assertIn('install --yes clang-18 llvm-18 libgmp-dev', source)
            self.assertIn('echo /usr/lib/llvm-18/bin >> "$GITHUB_PATH"', source)
            tools = 'clang llc opt llvm-nm llvm-link llvm-objcopy' if workflow == 'build.yml' else 'clang llc opt'
            self.assertIn(f'for tool in {tools}; do', source)
        policy = json.loads((project / '.github/scripts/fast-tests.json').read_text())
        for source in ('t/fixtures/compiler/OriginalGmpAudit.hs',
                       't/fixtures/compiler/OriginalGmpNative.hs',
                       't/haskell-fixtures/OriginalGmpFixtures.hs', 't/haskell-fixtures/Main.hs'):
            self.assertIn('thc.runtime.OriginalGmpTest', policy['owners'][source]['junit'])

    def test_build_installs_matching_llvm_tools_on_macos(self):
        project = Path(__file__).resolve().parents[2]
        source = (project / '.github/workflows/build.yml').read_text()
        self.assertIn("if: runner.os == 'macOS'", source)
        self.assertIn('brew install llvm@18', source)
        self.assertIn('echo "$(brew --prefix llvm@18)/bin" >> "$GITHUB_PATH"', source)
        self.assertIn('for tool in clang llc opt llvm-nm llvm-link llvm-objcopy; do', source)
        self.assertNotIn("- name: Check LLVM backend tools\n        if:", source)

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

    def test_original_fcntl_registered_cache_checks_all_artifacts(self):
        project = Path(__file__).resolve().parents[2]
        manifest, owners = fast_fixtures._manifest(project)
        group = manifest['groups']['original-fcntl']
        cache = fast_fixtures.fast_inputs
        self.assertEqual('original-fcntl', owners['thc.runtime.OriginalFcntlTest'])
        self.assertIn('"$fixture_bin" original-fcntl', (project / 'bin/prepare-tests.sh').read_text().splitlines())
        self.assertIn('build/original-fcntl', fast_fixtures.FULL_OUTPUT_ROOTS)
        name = 'build/original-fcntl/manifest.json'
        self.assertEqual(137, len(cache.ORIGINAL_FCNTL_OUTPUTS))
        for item in cache.ORIGINAL_FCNTL_OUTPUTS:
            self.assertTrue(cache.allowed_payload(item), item)
        for item in ('native/private-file', 'native/Main.o', 'pre/core/Other.json', 'logs/unknown.stdout'):
            self.assertFalse(cache.allowed_payload('build/original-fcntl/' + item), item)
        with mock.patch.object(cache, 'GMP_NATIVE_HOST', True):
            artifacts = {}
            for item in cache.ORIGINAL_FCNTL_OUTPUTS - {name}:
                path = self.root / item; path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text('fixture\n'); artifacts[item] = fast_fixtures._digest(path)
            receipt = dict(schema=1, supported=True, strictAccepted=True, runtimeVerified=False,
                           installedArtifactsHashed=False, nativeRows=4,
                           entries=list(cache.ORIGINAL_FCNTL_ENTRIES), artifactHashes=artifacts)
            path = self.root / name; path.write_text(json.dumps(receipt))
            self.assertEqual(cache.ORIGINAL_FCNTL_OUTPUTS, set(fast_fixtures._output_hashes(self.root, group)))
            for missing in ('native/oracle', 'pre/core/OriginalFcntlAudit.cbd',
                            'post/originalSetFlags.audit.json', 'logs/native-run.stdout'):
                item = 'build/original-fcntl/' + missing
                incomplete = dict(artifacts); del incomplete[item]
                with self.assertRaises(cache.CacheMiss):
                    cache.fcntl_artifact_hashes(dict(receipt, artifactHashes=incomplete))
                artifact = self.root / item; artifact.write_text('changed')
                with self.assertRaises(RuntimeError): fast_fixtures._output_hashes(self.root, group)
                artifact.unlink(); artifact.symlink_to(self.root / 'build/original-fcntl/oracle.json')
                with self.assertRaises(cache.CacheMiss): fast_fixtures._output_hashes(self.root, group)
                artifact.unlink(); artifact.write_text('fixture\n')
        with mock.patch.object(cache, 'GMP_NATIVE_HOST', False):
            receipt = dict(schema=1, supported=False, artifactHashes={})
            path.write_text(json.dumps(receipt))
            self.assertEqual({name}, set(fast_fixtures._output_hashes(self.root, group)))

    def test_original_errno_registered_cache_checks_all_artifacts(self):
        project = Path(__file__).resolve().parents[2]
        manifest, owners = fast_fixtures._manifest(project)
        group = manifest['groups']['original-errno']
        cache = fast_fixtures.fast_inputs
        self.assertEqual('original-errno', owners['thc.runtime.OriginalErrnoTest'])
        self.assertIn('"$fixture_bin" original-errno', (project / 'bin/prepare-tests.sh').read_text().splitlines())
        self.assertIn('build/original-errno', fast_fixtures.FULL_OUTPUT_ROOTS)
        name = 'build/original-errno/manifest.json'
        self.assertEqual(34, len(cache.ORIGINAL_ERRNO_OUTPUTS))
        for item in cache.ORIGINAL_ERRNO_OUTPUTS:
            self.assertTrue(cache.allowed_payload(item), item)
        for item in ('native/private-file', 'native/Main.o', 'pre/core/Other.json', 'logs/unknown.stdout'):
            self.assertFalse(cache.allowed_payload('build/original-errno/' + item), item)
        with mock.patch.object(cache, 'ERRNO_NATIVE_HOST', True):
            artifacts = {}
            for item in cache.ORIGINAL_ERRNO_OUTPUTS - {name}:
                path = self.root / item; path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text('fixture\n'); artifacts[item] = fast_fixtures._digest(path)
            receipt = dict(schema=1, supported=True, strictAccepted=True, runtimeVerified=False,
                           installedArtifactsHashed=False, ghc="9.14.1", nativeRows=5,
                           entries=list(cache.ORIGINAL_ERRNO_ENTRIES), artifactHashes=artifacts)
            path = self.root / name; path.write_text(json.dumps(receipt))
            self.assertEqual(cache.ORIGINAL_ERRNO_OUTPUTS, set(fast_fixtures._output_hashes(self.root, group)))
            for changes in (dict(schema=True), dict(ghc='9.14.0'), dict(nativeRows=4),
                            dict(entries=[]), dict(strictAccepted=False), dict(runtimeVerified=True),
                            dict(installedArtifactsHashed=True),
                            dict(artifactHashes=dict(artifacts, unknown='a' * 64))):
                with self.assertRaises(cache.CacheMiss):
                    cache.errno_artifact_hashes(dict(receipt, **changes))
            for missing in ('native/observations.txt', 'native/oracle', 'pre/core/OriginalErrnoAudit.cbd',
                            'post/originalResetErrno.audit.json', 'logs/native-run.stdout'):
                item = 'build/original-errno/' + missing
                incomplete = dict(artifacts); del incomplete[item]
                with self.assertRaises(cache.CacheMiss):
                    cache.errno_artifact_hashes(dict(receipt, artifactHashes=incomplete))
                artifact = self.root / item; artifact.write_text('changed')
                with self.assertRaises(RuntimeError): fast_fixtures._output_hashes(self.root, group)
                artifact.unlink(); artifact.symlink_to(self.root / 'build/original-errno/oracle.json')
                with self.assertRaises(cache.CacheMiss): fast_fixtures._output_hashes(self.root, group)
                artifact.unlink(); artifact.write_text('fixture\n')
        with mock.patch.object(cache, 'ERRNO_NATIVE_HOST', False):
            receipt = dict(schema=1, supported=False, artifactHashes={})
            path.write_text(json.dumps(receipt))
            self.assertEqual({name}, set(fast_fixtures._output_hashes(self.root, group)))

    def test_original_process_identity_registered_cache_checks_all_artifacts(self):
        project = Path(__file__).resolve().parents[2]
        manifest, owners = fast_fixtures._manifest(project)
        group = manifest['groups']['original-process-identity']
        cache = fast_fixtures.fast_inputs
        self.assertEqual('original-process-identity', owners['thc.runtime.OriginalProcessIdentityTest'])
        self.assertIn('"$fixture_bin" original-process-identity', (project / 'bin/prepare-tests.sh').read_text().splitlines())
        self.assertIn('build/original-process-identity', fast_fixtures.FULL_OUTPUT_ROOTS)
        name = 'build/original-process-identity/manifest.json'
        self.assertEqual(63, len(cache.ORIGINAL_PROCESS_IDENTITY_OUTPUTS))
        for item in cache.ORIGINAL_PROCESS_IDENTITY_OUTPUTS:
            self.assertTrue(cache.allowed_payload(item), item)
        for item in ('native/private-file', 'native/Main.o', 'pre/core/Other.json', 'logs/unknown.stdout'):
            self.assertFalse(cache.allowed_payload('build/original-process-identity/' + item), item)
        with mock.patch.object(cache, 'ERRNO_NATIVE_HOST', True):
            provider = process_identity_provider('unix-2.8.8.0-inplace')
            outputs = cache.ORIGINAL_PROCESS_IDENTITY_OUTPUTS | set(provider['installedBundles']) | set(provider['installedPublications'])
            artifacts = {}
            for item in outputs - {name}:
                path = self.root / item; path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text('fixture\n'); artifacts[item] = fast_fixtures._digest(path)
            receipt = dict(schema=1, supported=True, strictAccepted=True, runtimeVerified=False,
                           installedArtifactsHashed=True, ghc="9.14.1", unixUnit="unix-2.8.8.0-inplace", nativeRows=1,
                           entries=list(cache.ORIGINAL_PROCESS_IDENTITY_ENTRIES), artifactHashes=artifacts, **provider)
            path = self.root / name; path.write_text(json.dumps(receipt))
            self.assertEqual(outputs, set(fast_fixtures._output_hashes(self.root, group)))
            for changes in (dict(schema=True), dict(ghc='9.14.0'), dict(nativeRows=2), dict(unixUnit="unix-2.8.7.0-deadbeef"),
                            dict(entries=[]), dict(strictAccepted=False), dict(runtimeVerified=True),
                            dict(installedArtifactsHashed=False),
                            dict(artifactHashes=dict(artifacts, unknown='a' * 64))):
                with self.assertRaises(cache.CacheMiss):
                    cache.process_identity_artifact_hashes(dict(receipt, **changes))
            for missing in ('native/observations.txt', 'native/oracle', 'pre/core/OriginalProcessIdentityAudit.cbd',
                            'post/originalGetEuid.audit.json', 'logs/native-run.stdout'):
                item = 'build/original-process-identity/' + missing
                incomplete = dict(artifacts); del incomplete[item]
                with self.assertRaises(cache.CacheMiss):
                    cache.process_identity_artifact_hashes(dict(receipt, artifactHashes=incomplete))
                artifact = self.root / item; artifact.write_text('changed')
                with self.assertRaises(RuntimeError): fast_fixtures._output_hashes(self.root, group)
                artifact.unlink(); artifact.symlink_to(self.root / 'build/original-process-identity/oracle.json')
                with self.assertRaises(cache.CacheMiss): fast_fixtures._output_hashes(self.root, group)
                artifact.unlink(); artifact.write_text('fixture\n')
        with mock.patch.object(cache, 'ERRNO_NATIVE_HOST', False):
            receipt = dict(schema=1, supported=False, artifactHashes={})
            path.write_text(json.dumps(receipt))
            self.assertEqual({name}, set(fast_fixtures._output_hashes(self.root, group)))

    def test_original_termios_registration_receipt_and_stale_artifact(self):
        project = Path(__file__).resolve().parents[2]
        manifest, owners = fast_fixtures._manifest(project)
        group = manifest['groups']['original-termios']
        self.assertEqual('original-termios', owners['thc.runtime.OriginalTermiosTest'])
        self.assertEqual('original-termios', owners['thc.runtime.OriginalSavedTermiosTest'])
        self.assertIn('thc.runtime.TermiosAbiTest', manifest['fixtureFreeJunit'])
        self.assertIn('thc.runtime.SavedTermiosTest', manifest['fixtureFreeJunit'])
        for fixture in ('Audit', 'Native'):
            self.assertIn(f't/fixtures/compiler/OriginalSavedTermios{fixture}.hs', group['sources'])
        self.assertEqual([{'argv': ['cabal', 'run', 'exe:thc-fixtures', '--offline', '--', 'original-termios']}], group['commands'])
        self.assertIn('"$fixture_bin" original-termios', (project / 'bin/prepare-tests.sh').read_text().splitlines())
        self.assertIn('build/original-termios', fast_fixtures.FULL_OUTPUT_ROOTS)
        cache = fast_fixtures.fast_inputs
        name = 'build/original-termios/manifest.json'
        with mock.patch.object(cache, 'GMP_NATIVE_HOST', True):
            artifacts = {}
            for item in cache.ORIGINAL_TERMIOS_OUTPUTS - {name}:
                path = self.root / item; path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text('fixture\n'); artifacts[item] = fast_fixtures._digest(path)
            receipt = dict(schema=1, supported=True, strictAccepted=True, runtimeVerified=False,
                           installedArtifactsHashed=False, nativeRows=6,
                           entries=list(cache.ORIGINAL_TERMIOS_ENTRIES), artifactHashes=artifacts)
            path = self.root / name; path.write_text(json.dumps(receipt))
            self.assertEqual(cache.ORIGINAL_TERMIOS_OUTPUTS, set(fast_fixtures._output_hashes(self.root, group)))
            for bad in (dict(receipt, schema=True), dict(receipt, entries=[]),
                        dict(receipt, artifactHashes={}), dict(receipt, artifactHashes=dict(artifacts, **{'build/original-termios/extra.json': '0'*64}))):
                with self.assertRaises(cache.CacheMiss): cache.termios_artifact_hashes(bad)
            for missing in ('saved/native/oracle', 'saved/pre/core/OriginalSavedTermiosAudit.cbd',
                            'saved/post/originalSetSavedTermios.audit.json', 'logs/saved-native-run.stdout'):
                incomplete = dict(artifacts); del incomplete['build/original-termios/' + missing]
                with self.assertRaises(cache.CacheMiss):
                    cache.termios_artifact_hashes(dict(receipt, artifactHashes=incomplete))
            for relative in ('pre/core/OriginalTermiosAudit.cbd', 'saved/pre/core/OriginalSavedTermiosAudit.cbd',
                             'saved/native/oracle', 'logs/saved-native-run.stdout'):
                artifact = self.root / 'build/original-termios' / relative
                artifact.write_text('mutated')
                with self.assertRaises(RuntimeError): fast_fixtures._output_hashes(self.root, group)
                artifact.unlink(); artifact.symlink_to(self.root / 'build/original-termios/oracle.json')
                with self.assertRaises(cache.CacheMiss): fast_fixtures._output_hashes(self.root, group)
                artifact.unlink(); artifact.write_text('fixture\n')

    def test_original_posix_stat_fixture_registration_and_narrow_cache(self):
        project = Path(__file__).resolve().parents[2]
        manifest, owners = fast_fixtures._manifest(project)
        group = manifest['groups']['original-posix-stat']
        for name in ('OriginalPosixStatTest', 'PosixStatAbiTest', 'OriginalFstatTest', 'OriginalPathStatTest', 'OriginalPathModeTest', 'OriginalPathLinkTest', 'OriginalPathAccessTest', 'OriginalUnlinkAtTest', 'OriginalFstatAtTest', 'OriginalCurrentDirectoryTest', 'OriginalDirectoryStreamsTest', 'OriginalDirectoryPathsTest'):
            self.assertEqual('original-posix-stat', owners['thc.runtime.' + name])
        self.assertEqual([{'argv': ['cabal', 'run', 'exe:thc-fixtures', '--offline', '--', 'original-posix-stat']}], group['commands'])
        self.assertIn('"$fixture_bin" original-posix-stat', (project / 'bin/prepare-tests.sh').read_text())
        self.assertIn('build/original-posix-stat/manifest.json', fast_fixtures.FULL_REQUIRED)
        self.assertIn('build/original-posix-stat', fast_fixtures.FULL_OUTPUT_ROOTS)
        self.assertEqual(['build/original-posix-stat', 'build/original-path-stat', 'build/original-path-mode', 'build/original-path-link', 'build/original-path-access', 'build/original-unlinkat', 'build/original-fstatat', 'build/original-current-directory', 'build/original-directory-streams', 'build/original-directory-paths'], group['outputs'])
        self.assertIn('t/fixtures/compiler/OriginalPathStatAudit.hs', group['sources'])
        self.assertIn('t/haskell-fixtures/OriginalPathStatFixtures.hs', group['sources'])
        self.assertIn('build/original-path-stat', fast_fixtures.FULL_OUTPUT_ROOTS)
        self.assertIn('t/fixtures/compiler/OriginalPathModeAudit.hs', group['sources'])
        self.assertIn('t/haskell-fixtures/OriginalPathModeFixtures.hs', group['sources'])
        self.assertIn('build/original-path-mode', fast_fixtures.FULL_OUTPUT_ROOTS)
        self.assertIn('t/fixtures/compiler/OriginalPathLinkAudit.hs', group['sources'])
        self.assertIn('t/haskell-fixtures/OriginalPathLinkFixtures.hs', group['sources'])
        self.assertIn('build/original-path-link', fast_fixtures.FULL_OUTPUT_ROOTS)
        self.assertIn('t/fixtures/compiler/OriginalUnlinkAtAudit.hs', group['sources'])
        self.assertIn('t/fixtures/compiler/OriginalFstatAtAudit.hs', group['sources'])
        self.assertIn('t/haskell-fixtures/OriginalUnlinkAtFixtures.hs', group['sources'])
        self.assertIn('t/haskell-fixtures/OriginalFstatAtFixtures.hs', group['sources'])
        self.assertIn('t/fixtures/compiler/OriginalCurrentDirectoryAudit.hs', group['sources'])
        self.assertIn('t/haskell-fixtures/OriginalCurrentDirectoryFixtures.hs', group['sources'])
        self.assertIn('thc.runtime.NativeDirectoryFileSystemTest', manifest['fixtureFreeJunit'])
        self.assertIn('src/main/c/stdio-abi-probe.c', fast_fixtures.COMMON_SOURCES)
        self.assertIn('build/original-unlinkat', fast_fixtures.FULL_OUTPUT_ROOTS)
        self.assertIn('build/original-fstatat', fast_fixtures.FULL_OUTPUT_ROOTS)
        self.assertIn('build/original-current-directory', fast_fixtures.FULL_OUTPUT_ROOTS)
        if fast_fixtures.platform.system() == 'Linux':
            self.assertTrue(fast_fixtures.fast_inputs.ORIGINAL_PATH_STAT_OUTPUTS <= fast_fixtures.FULL_REQUIRED)
            self.assertTrue(fast_fixtures.fast_inputs.ORIGINAL_PATH_MODE_OUTPUTS <= fast_fixtures.FULL_REQUIRED)
            self.assertTrue(fast_fixtures.fast_inputs.ORIGINAL_PATH_LINK_OUTPUTS <= fast_fixtures.FULL_REQUIRED)
            self.assertTrue(fast_fixtures.fast_inputs.ORIGINAL_PATH_ACCESS_OUTPUTS <= fast_fixtures.FULL_REQUIRED)
            self.assertTrue(fast_fixtures.fast_inputs.ORIGINAL_UNLINKAT_OUTPUTS <= fast_fixtures.FULL_REQUIRED)
            self.assertTrue(fast_fixtures.fast_inputs.ORIGINAL_FSTATAT_OUTPUTS <= fast_fixtures.FULL_REQUIRED)
            self.assertTrue(fast_fixtures.fast_inputs.ORIGINAL_CURRENT_DIRECTORY_OUTPUTS <= fast_fixtures.FULL_REQUIRED)
        cache = fast_fixtures.fast_inputs
        self.assertEqual(90, len(cache.ORIGINAL_POSIX_STAT_OUTPUTS))
        for path in cache.ORIGINAL_POSIX_STAT_OUTPUTS:
            self.assertTrue(cache.allowed_payload(path), path)
        for suffix in ('native/unknown', 'logs/unknown.stdout', 'pre/core/Other.json', 'attempt-0/oracle.json'):
            self.assertFalse(cache.allowed_payload('build/original-posix-stat/' + suffix), suffix)
        for suffix in ('../original-stdio/manifest.json', 'native/../../outside'):
            with self.assertRaises(cache.CacheMiss):
                cache.allowed_payload('build/original-posix-stat/' + suffix)
        self.assertEqual(0o755, cache.safe_mode(0o755, 'build/original-posix-stat/native/oracle'))
        with self.assertRaises(cache.CacheMiss):
            cache.safe_mode(0o755, 'build/original-posix-stat/oracle.json')
        producer = (project / 't/haskell-fixtures/OriginalPosixStatFixtures.hs').read_text()
        self.assertIn('os /= "linux"', producer)
        self.assertIn('"supported" .= False', producer)

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

    def test_path_stat_receipt_excludes_scratch_and_checks_every_artifact(self):
        cache = fast_fixtures.fast_inputs
        name = 'build/original-path-stat/manifest.json'
        artifacts = {}
        for relative in cache.ORIGINAL_PATH_STAT_OUTPUTS - {name}:
            path = self.root / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text('fixture\n')
            artifacts[relative] = cache.digest(path)
        receipt = dict(schema=1, ghc='9.14.1', unixUnit='unix-2.8.8.0-inplace',
            entries=list(cache.ORIGINAL_PATH_STAT_ENTRIES), installedArtifactsHashed=False, artifactHashes=artifacts)
        (self.root / name).write_text(json.dumps(receipt))
        scratch = self.root / 'build/original-path-stat/native-paths'
        scratch.mkdir()
        (scratch / 'link').symlink_to('/definitely/missing/path-stat-target')
        old = self.root / 'build/original-posix-stat/manifest.json'
        old.parent.mkdir(); old.write_text('{"supported": false}\n')
        group = {'outputs': ['build/original-posix-stat', 'build/original-path-stat']}
        with mock.patch.object(fast_fixtures.platform, 'system', return_value='Linux'):
            expected = fast_fixtures._output_hashes(self.root, group)
            self.assertEqual(cache.ORIGINAL_PATH_STAT_OUTPUTS | {'build/original-posix-stat/manifest.json'}, set(expected))
            with mock.patch.object(fast_fixtures, 'FULL_REQUIRED', set(expected)), \
                    mock.patch.object(fast_fixtures, 'FULL_OUTPUT_ROOTS', frozenset(group['outputs'])):
                self.assertEqual(set(expected), set(fast_fixtures._full_output_hashes(self.root)))
            path = self.root / 'build/original-path-stat/pre.cbd'
            path.write_text('mutated')
            with self.assertRaises(RuntimeError): fast_fixtures._output_hashes(self.root, group)
            path.unlink(); path.symlink_to(scratch / 'link')
            with self.assertRaises(cache.CacheMiss): fast_fixtures._output_hashes(self.root, group)
            path.unlink()
            with self.assertRaises(FileNotFoundError): fast_fixtures._output_hashes(self.root, group)
        with mock.patch.object(fast_fixtures.platform, 'system', return_value='Darwin'):
            self.assertEqual({'build/original-posix-stat/manifest.json'}, set(fast_fixtures._output_hashes(self.root, group)))

    def test_path_mode_receipt_excludes_scratch_and_checks_every_artifact(self):
        cache = fast_fixtures.fast_inputs
        name = 'build/original-path-mode/manifest.json'
        artifacts = {}
        for relative in cache.ORIGINAL_PATH_MODE_OUTPUTS - {name}:
            path = self.root / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text('fixture\n')
            artifacts[relative] = cache.digest(path)
        receipt = dict(schema=1, ghc='9.14.1', unixUnit='unix-2.8.8.0-inplace',
            entries=list(cache.ORIGINAL_PATH_MODE_ENTRIES), installedArtifactsHashed=False, artifactHashes=artifacts)
        (self.root / name).write_text(json.dumps(receipt))
        scratch = self.root / 'build/original-path-mode/native-paths'
        scratch.mkdir()
        (scratch / 'link').symlink_to('/definitely/missing/path-mode-target')
        old = self.root / 'build/original-posix-stat/manifest.json'
        old.parent.mkdir(); old.write_text('{"supported": false}\n')
        # The original POSIX preparation now owns both independently closed
        # pathname receipts. Neither may hide the other during recursion.
        stat_name = 'build/original-path-stat/manifest.json'
        stat_artifacts = {}
        for relative in cache.ORIGINAL_PATH_STAT_OUTPUTS - {stat_name}:
            path = self.root / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text('stat fixture\n')
            stat_artifacts[relative] = cache.digest(path)
        (self.root / stat_name).write_text(json.dumps(dict(receipt,
            entries=list(cache.ORIGINAL_PATH_STAT_ENTRIES), artifactHashes=stat_artifacts)))
        group = {'outputs': ['build/original-posix-stat', 'build/original-path-stat', 'build/original-path-mode']}
        with mock.patch.object(fast_fixtures.platform, 'system', return_value='Linux'):
            expected = fast_fixtures._output_hashes(self.root, group)
            self.assertEqual(cache.ORIGINAL_PATH_MODE_OUTPUTS | cache.ORIGINAL_PATH_STAT_OUTPUTS |
                             {'build/original-posix-stat/manifest.json'}, set(expected))
            with mock.patch.object(fast_fixtures, 'FULL_REQUIRED', set(expected)), \
                    mock.patch.object(fast_fixtures, 'FULL_OUTPUT_ROOTS', frozenset(group['outputs'])):
                self.assertEqual(set(expected), set(fast_fixtures._full_output_hashes(self.root)))
            path = self.root / 'build/original-path-mode/pre.cbd'
            path.write_text('mutated')
            with self.assertRaises(RuntimeError): fast_fixtures._output_hashes(self.root, group)
            path.unlink(); path.symlink_to(scratch / 'link')
            with self.assertRaises(cache.CacheMiss): fast_fixtures._output_hashes(self.root, group)
            path.unlink()
            with self.assertRaises(FileNotFoundError): fast_fixtures._output_hashes(self.root, group)
        with mock.patch.object(fast_fixtures.platform, 'system', return_value='Darwin'):
            self.assertEqual({'build/original-posix-stat/manifest.json'}, set(fast_fixtures._output_hashes(self.root, group)))

    def test_path_link_receipt_excludes_scratch_and_checks_every_artifact(self):
        cache = fast_fixtures.fast_inputs
        name = 'build/original-path-link/manifest.json'
        artifacts = {}
        for relative in cache.ORIGINAL_PATH_LINK_OUTPUTS - {name}:
            path = self.root / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text('fixture\n')
            artifacts[relative] = cache.digest(path)
        receipt = dict(schema=1, ghc='9.14.1', unixUnit='unix-2.8.8.0-inplace',
            entries=list(cache.ORIGINAL_PATH_LINK_ENTRIES), installedArtifactsHashed=False, artifactHashes=artifacts)
        (self.root / name).write_text(json.dumps(receipt))
        scratch = self.root / 'build/original-path-link/native-paths'
        scratch.mkdir()
        (scratch / 'link').symlink_to('/definitely/missing/path-link-target')
        old = self.root / 'build/original-posix-stat/manifest.json'
        old.parent.mkdir(); old.write_text('{"supported": false}\n')
        # The original POSIX preparation now owns both independently closed
        # pathname receipts. Neither may hide the other during recursion.
        stat_name = 'build/original-path-stat/manifest.json'
        stat_artifacts = {}
        for relative in cache.ORIGINAL_PATH_STAT_OUTPUTS - {stat_name}:
            path = self.root / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text('stat fixture\n')
            stat_artifacts[relative] = cache.digest(path)
        (self.root / stat_name).write_text(json.dumps(dict(receipt,
            entries=list(cache.ORIGINAL_PATH_STAT_ENTRIES), artifactHashes=stat_artifacts)))
        group = {'outputs': ['build/original-posix-stat', 'build/original-path-stat', 'build/original-path-link']}
        with mock.patch.object(fast_fixtures.platform, 'system', return_value='Linux'):
            expected = fast_fixtures._output_hashes(self.root, group)
            self.assertEqual(cache.ORIGINAL_PATH_LINK_OUTPUTS | cache.ORIGINAL_PATH_STAT_OUTPUTS |
                             {'build/original-posix-stat/manifest.json'}, set(expected))
            with mock.patch.object(fast_fixtures, 'FULL_REQUIRED', set(expected)), \
                    mock.patch.object(fast_fixtures, 'FULL_OUTPUT_ROOTS', frozenset(group['outputs'])):
                self.assertEqual(set(expected), set(fast_fixtures._full_output_hashes(self.root)))
            path = self.root / 'build/original-path-link/pre.cbd'
            path.write_text('mutated')
            with self.assertRaises(RuntimeError): fast_fixtures._output_hashes(self.root, group)
            path.unlink(); path.symlink_to(scratch / 'link')
            with self.assertRaises(cache.CacheMiss): fast_fixtures._output_hashes(self.root, group)
            path.unlink()
            with self.assertRaises(FileNotFoundError): fast_fixtures._output_hashes(self.root, group)
        with mock.patch.object(fast_fixtures.platform, 'system', return_value='Darwin'):
            self.assertEqual({'build/original-posix-stat/manifest.json'}, set(fast_fixtures._output_hashes(self.root, group)))

    def test_directory_paths_receipt_excludes_scratch_and_checks_every_artifact(self):
        cache = fast_fixtures.fast_inputs
        name = 'build/original-directory-paths/manifest.json'
        artifacts = {}
        for relative in cache.ORIGINAL_DIRECTORY_PATHS_OUTPUTS - {name}:
            path = self.root / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text('fixture\n')
            artifacts[relative] = cache.digest(path)
        receipt = dict(schema=1, ghc='9.14.1', unixUnit='unix-2.8.8.0-inplace',
            entries=list(cache.ORIGINAL_DIRECTORY_PATHS_ENTRIES), installedArtifactsHashed=False, readlinkUnit="ghc-internal", artifactHashes=artifacts)
        (self.root / name).write_text(json.dumps(receipt))
        scratch = self.root / 'build/original-directory-paths/native-paths'
        scratch.mkdir()
        (scratch / 'link').symlink_to('/definitely/missing/path-link-target')
        old = self.root / 'build/original-posix-stat/manifest.json'
        old.parent.mkdir(); old.write_text('{"supported": false}\n')
        # The original POSIX preparation now owns both independently closed
        # pathname receipts. Neither may hide the other during recursion.
        stat_name = 'build/original-path-stat/manifest.json'
        stat_artifacts = {}
        for relative in cache.ORIGINAL_PATH_STAT_OUTPUTS - {stat_name}:
            path = self.root / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text('stat fixture\n')
            stat_artifacts[relative] = cache.digest(path)
        (self.root / stat_name).write_text(json.dumps(dict(receipt,
            entries=list(cache.ORIGINAL_PATH_STAT_ENTRIES), artifactHashes=stat_artifacts)))
        group = {'outputs': ['build/original-posix-stat', 'build/original-path-stat', 'build/original-directory-paths']}
        with mock.patch.object(fast_fixtures.platform, 'system', return_value='Linux'):
            expected = fast_fixtures._output_hashes(self.root, group)
            self.assertEqual(cache.ORIGINAL_DIRECTORY_PATHS_OUTPUTS | cache.ORIGINAL_PATH_STAT_OUTPUTS |
                             {'build/original-posix-stat/manifest.json'}, set(expected))
            with mock.patch.object(fast_fixtures, 'FULL_REQUIRED', set(expected)), \
                    mock.patch.object(fast_fixtures, 'FULL_OUTPUT_ROOTS', frozenset(group['outputs'])):
                self.assertEqual(set(expected), set(fast_fixtures._full_output_hashes(self.root)))
            path = self.root / 'build/original-directory-paths/pre.cbd'
            path.write_text('mutated')
            with self.assertRaises(RuntimeError): fast_fixtures._output_hashes(self.root, group)
            path.unlink(); path.symlink_to(scratch / 'link')
            with self.assertRaises(cache.CacheMiss): fast_fixtures._output_hashes(self.root, group)
            path.unlink()
            with self.assertRaises(FileNotFoundError): fast_fixtures._output_hashes(self.root, group)
        with mock.patch.object(fast_fixtures.platform, 'system', return_value='Darwin'):
            self.assertEqual({'build/original-posix-stat/manifest.json'}, set(fast_fixtures._output_hashes(self.root, group)))

    def test_path_access_receipt_excludes_scratch_and_checks_every_artifact(self):
        cache = fast_fixtures.fast_inputs
        name = 'build/original-path-access/manifest.json'
        artifacts = {}
        for relative in cache.ORIGINAL_PATH_ACCESS_OUTPUTS - {name}:
            path = self.root / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text('fixture\n')
            artifacts[relative] = cache.digest(path)
        receipt = dict(schema=1, ghc='9.14.1', unixUnit='unix-2.8.8.0-inplace',
            entries=list(cache.ORIGINAL_PATH_ACCESS_ENTRIES), installedArtifactsHashed=False, artifactHashes=artifacts)
        (self.root / name).write_text(json.dumps(receipt))
        scratch = self.root / 'build/original-path-access/native-paths'
        scratch.mkdir()
        (scratch / 'link').symlink_to('/definitely/missing/path-access-target')
        old = self.root / 'build/original-posix-stat/manifest.json'
        old.parent.mkdir(); old.write_text('{"supported": false}\n')
        # The original POSIX preparation now owns both independently closed
        # pathname receipts. Neither may hide the other during recursion.
        stat_name = 'build/original-path-stat/manifest.json'
        stat_artifacts = {}
        for relative in cache.ORIGINAL_PATH_STAT_OUTPUTS - {stat_name}:
            path = self.root / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text('stat fixture\n')
            stat_artifacts[relative] = cache.digest(path)
        (self.root / stat_name).write_text(json.dumps(dict(receipt,
            entries=list(cache.ORIGINAL_PATH_STAT_ENTRIES), artifactHashes=stat_artifacts)))
        group = {'outputs': ['build/original-posix-stat', 'build/original-path-stat', 'build/original-path-access']}
        with mock.patch.object(fast_fixtures.platform, 'system', return_value='Linux'):
            expected = fast_fixtures._output_hashes(self.root, group)
            self.assertEqual(cache.ORIGINAL_PATH_ACCESS_OUTPUTS | cache.ORIGINAL_PATH_STAT_OUTPUTS |
                             {'build/original-posix-stat/manifest.json'}, set(expected))
            with mock.patch.object(fast_fixtures, 'FULL_REQUIRED', set(expected)), \
                    mock.patch.object(fast_fixtures, 'FULL_OUTPUT_ROOTS', frozenset(group['outputs'])):
                self.assertEqual(set(expected), set(fast_fixtures._full_output_hashes(self.root)))
            path = self.root / 'build/original-path-access/pre.cbd'
            path.write_text('mutated')
            with self.assertRaises(RuntimeError): fast_fixtures._output_hashes(self.root, group)
            path.unlink(); path.symlink_to(scratch / 'link')
            with self.assertRaises(cache.CacheMiss): fast_fixtures._output_hashes(self.root, group)
            path.unlink()
            with self.assertRaises(FileNotFoundError): fast_fixtures._output_hashes(self.root, group)
        with mock.patch.object(fast_fixtures.platform, 'system', return_value='Darwin'):
            self.assertEqual({'build/original-posix-stat/manifest.json'}, set(fast_fixtures._output_hashes(self.root, group)))

    def test_unlinkat_receipt_excludes_scratch_and_checks_every_artifact(self):
        cache = fast_fixtures.fast_inputs
        name = 'build/original-unlinkat/manifest.json'
        artifacts = {}
        for relative in cache.ORIGINAL_UNLINKAT_OUTPUTS - {name}:
            path = self.root / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text('fixture\n')
            artifacts[relative] = cache.digest(path)
        receipt = dict(schema=1, ghc='9.14.1', directoryUnit='directory-1.3.10.0-inplace',
            entries=list(cache.ORIGINAL_UNLINKAT_ENTRIES), installedArtifactsHashed=False, artifactHashes=artifacts)
        (self.root / name).write_text(json.dumps(receipt))
        scratch = self.root / 'build/original-unlinkat/native-paths'
        scratch.mkdir()
        (scratch / 'link').symlink_to('/definitely/missing/unlinkat-target')
        ghc = self.root / 'build/original-unlinkat/ghc'
        ghc.mkdir()
        (ghc / 'abi-probe').write_bytes(b'excluded probe binary')
        old = self.root / 'build/original-posix-stat/manifest.json'
        old.parent.mkdir(); old.write_text('{"supported": false}\n')
        # The original POSIX preparation now owns both independently closed
        # pathname receipts. Neither may hide the other during recursion.
        stat_name = 'build/original-path-stat/manifest.json'
        stat_artifacts = {}
        for relative in cache.ORIGINAL_PATH_STAT_OUTPUTS - {stat_name}:
            path = self.root / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text('stat fixture\n')
            stat_artifacts[relative] = cache.digest(path)
        (self.root / stat_name).write_text(json.dumps(dict(receipt,
            entries=list(cache.ORIGINAL_PATH_STAT_ENTRIES), unixUnit='unix-2.8.8.0-inplace', artifactHashes=stat_artifacts)))
        group = {'outputs': ['build/original-posix-stat', 'build/original-path-stat', 'build/original-unlinkat']}
        with mock.patch.object(fast_fixtures.platform, 'system', return_value='Linux'):
            expected = fast_fixtures._output_hashes(self.root, group)
            self.assertEqual(cache.ORIGINAL_UNLINKAT_OUTPUTS | cache.ORIGINAL_PATH_STAT_OUTPUTS |
                             {'build/original-posix-stat/manifest.json'}, set(expected))
            with mock.patch.object(fast_fixtures, 'FULL_REQUIRED', set(expected)), \
                    mock.patch.object(fast_fixtures, 'FULL_OUTPUT_ROOTS', frozenset(group['outputs'])):
                self.assertEqual(set(expected), set(fast_fixtures._full_output_hashes(self.root)))
            path = self.root / 'build/original-unlinkat/pre.cbd'
            path.write_text('mutated')
            with self.assertRaises(RuntimeError): fast_fixtures._output_hashes(self.root, group)
            path.unlink(); path.symlink_to(scratch / 'link')
            with self.assertRaises(cache.CacheMiss): fast_fixtures._output_hashes(self.root, group)
            path.unlink()
            with self.assertRaises(FileNotFoundError): fast_fixtures._output_hashes(self.root, group)
        with mock.patch.object(fast_fixtures.platform, 'system', return_value='Darwin'):
            self.assertEqual({'build/original-posix-stat/manifest.json'}, set(fast_fixtures._output_hashes(self.root, group)))

    def test_fstatat_receipt_excludes_scratch_and_checks_every_artifact(self):
        cache = fast_fixtures.fast_inputs
        name = 'build/original-fstatat/manifest.json'
        artifacts = {}
        for relative in cache.ORIGINAL_FSTATAT_OUTPUTS - {name}:
            path = self.root / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text('fixture\n')
            artifacts[relative] = cache.digest(path)
        receipt = dict(schema=1, ghc='9.14.1', directoryUnit='directory-1.3.10.0-inplace',
            entries=list(cache.ORIGINAL_FSTATAT_ENTRIES), installedArtifactsHashed=False, artifactHashes=artifacts)
        (self.root / name).write_text(json.dumps(receipt))
        scratch = self.root / 'build/original-fstatat/native-paths'
        scratch.mkdir()
        (scratch / 'link').symlink_to('/definitely/missing/fstatat-target')
        ghc = self.root / 'build/original-fstatat/ghc'
        ghc.mkdir()
        (ghc / 'abi-probe').write_bytes(b'excluded probe binary')
        old = self.root / 'build/original-posix-stat/manifest.json'
        old.parent.mkdir(); old.write_text('{"supported": false}\n')
        # The original POSIX preparation now owns both independently closed
        # pathname receipts. Neither may hide the other during recursion.
        sibling_name = 'build/original-unlinkat/manifest.json'
        sibling_artifacts = {}
        for relative in cache.ORIGINAL_UNLINKAT_OUTPUTS - {sibling_name}:
            path = self.root / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text('unlinkat fixture\n')
            sibling_artifacts[relative] = cache.digest(path)
        (self.root / sibling_name).write_text(json.dumps(dict(receipt,
            entries=list(cache.ORIGINAL_UNLINKAT_ENTRIES), artifactHashes=sibling_artifacts)))
        group = {'outputs': ['build/original-posix-stat', 'build/original-unlinkat', 'build/original-fstatat']}
        with mock.patch.object(fast_fixtures.platform, 'system', return_value='Linux'):
            expected = fast_fixtures._output_hashes(self.root, group)
            self.assertEqual(cache.ORIGINAL_FSTATAT_OUTPUTS | cache.ORIGINAL_UNLINKAT_OUTPUTS |
                             {'build/original-posix-stat/manifest.json'}, set(expected))
            with mock.patch.object(fast_fixtures, 'FULL_REQUIRED', set(expected)), \
                    mock.patch.object(fast_fixtures, 'FULL_OUTPUT_ROOTS', frozenset(group['outputs'])):
                self.assertEqual(set(expected), set(fast_fixtures._full_output_hashes(self.root)))
            path = self.root / 'build/original-fstatat/pre.cbd'
            path.write_text('mutated')
            with self.assertRaises(RuntimeError): fast_fixtures._output_hashes(self.root, group)
            path.unlink(); path.symlink_to(scratch / 'link')
            with self.assertRaises(cache.CacheMiss): fast_fixtures._output_hashes(self.root, group)
            path.unlink()
            with self.assertRaises(FileNotFoundError): fast_fixtures._output_hashes(self.root, group)
        with mock.patch.object(fast_fixtures.platform, 'system', return_value='Darwin'):
            self.assertEqual({'build/original-posix-stat/manifest.json'}, set(fast_fixtures._output_hashes(self.root, group)))

    def test_current_directory_receipt_excludes_scratch_and_checks_every_artifact(self):
        cache = fast_fixtures.fast_inputs
        name = 'build/original-current-directory/manifest.json'
        artifacts = {}
        for relative in cache.ORIGINAL_CURRENT_DIRECTORY_OUTPUTS - {name}:
            path = self.root / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text('fixture\n')
            artifacts[relative] = cache.digest(path)
        receipt = dict(schema=1, ghc='9.14.1', unixUnit='unix-2.8.8.0-inplace',
            entries=list(cache.ORIGINAL_CURRENT_DIRECTORY_ENTRIES), installedArtifactsHashed=False, nativeIsolatedChild=True, coordinatorCwdUnchanged=True, privateRebuiltUnix=True, unixSourceReceipt='build/original-current-directory/unix-source.json',
                    unixArchiveSha256='a128dea3bfeb731a562f22d376fa606e902154d95321363f7ec1ea6b787a5a3e', artifactHashes=artifacts)
        (self.root / name).write_text(json.dumps(receipt))
        scratch = self.root / 'build/original-current-directory/native-paths'
        scratch.mkdir()
        (scratch / 'link').symlink_to('/definitely/missing/current_directory-target')
        ghc = self.root / 'build/original-current-directory/ghc'
        ghc.mkdir()
        (ghc / 'abi-probe').write_bytes(b'excluded probe binary')
        old = self.root / 'build/original-posix-stat/manifest.json'
        old.parent.mkdir(); old.write_text('{"supported": false}\n')
        # The original POSIX preparation now owns both independently closed
        # pathname receipts. Neither may hide the other during recursion.
        sibling_name = 'build/original-unlinkat/manifest.json'
        sibling_artifacts = {}
        for relative in cache.ORIGINAL_UNLINKAT_OUTPUTS - {sibling_name}:
            path = self.root / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text('unlinkat fixture\n')
            sibling_artifacts[relative] = cache.digest(path)
        (self.root / sibling_name).write_text(json.dumps(dict(receipt,
            directoryUnit="directory-1.3.10.0-inplace", entries=list(cache.ORIGINAL_UNLINKAT_ENTRIES), artifactHashes=sibling_artifacts)))
        group = {'outputs': ['build/original-posix-stat', 'build/original-unlinkat', 'build/original-current-directory']}
        with mock.patch.object(fast_fixtures.platform, 'system', return_value='Linux'):
            expected = fast_fixtures._output_hashes(self.root, group)
            self.assertEqual(cache.ORIGINAL_CURRENT_DIRECTORY_OUTPUTS | cache.ORIGINAL_UNLINKAT_OUTPUTS |
                             {'build/original-posix-stat/manifest.json'}, set(expected))
            with mock.patch.object(fast_fixtures, 'FULL_REQUIRED', set(expected)), \
                    mock.patch.object(fast_fixtures, 'FULL_OUTPUT_ROOTS', frozenset(group['outputs'])):
                self.assertEqual(set(expected), set(fast_fixtures._full_output_hashes(self.root)))
            path = self.root / 'build/original-current-directory/pre.cbd'
            path.write_text('mutated')
            with self.assertRaises(RuntimeError): fast_fixtures._output_hashes(self.root, group)
            path.unlink(); path.symlink_to(scratch / 'link')
            with self.assertRaises(cache.CacheMiss): fast_fixtures._output_hashes(self.root, group)
            path.unlink()
            with self.assertRaises(FileNotFoundError): fast_fixtures._output_hashes(self.root, group)
        with mock.patch.object(fast_fixtures.platform, 'system', return_value='Darwin'):
            self.assertEqual({'build/original-posix-stat/manifest.json'}, set(fast_fixtures._output_hashes(self.root, group)))

    def test_directory_streams_receipt_excludes_scratch_and_checks_every_artifact(self):
        cache = fast_fixtures.fast_inputs
        name = 'build/original-directory-streams/manifest.json'
        artifacts = {}
        for relative in cache.ORIGINAL_DIRECTORY_STREAMS_OUTPUTS - {name}:
            path = self.root / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text('fixture\n')
            artifacts[relative] = cache.digest(path)
        receipt = dict(schema=1, ghc='9.14.1', unixUnit='unix-2.8.8.0-inplace',
            entries=list(cache.ORIGINAL_DIRECTORY_STREAMS_ENTRIES), installedArtifactsHashed=False, privateRebuiltUnix=True, unixSourceReceipt='build/original-directory-streams/unix-source.json',
                    unixArchiveSha256='a128dea3bfeb731a562f22d376fa606e902154d95321363f7ec1ea6b787a5a3e', artifactHashes=artifacts)
        (self.root / name).write_text(json.dumps(receipt))
        scratch = self.root / 'build/original-directory-streams/native-paths'
        scratch.mkdir()
        (scratch / 'link').symlink_to('/definitely/missing/current_directory-target')
        ghc = self.root / 'build/original-directory-streams/ghc'
        ghc.mkdir()
        (ghc / 'abi-probe').write_bytes(b'excluded probe binary')
        old = self.root / 'build/original-posix-stat/manifest.json'
        old.parent.mkdir(); old.write_text('{"supported": false}\n')
        # The original POSIX preparation now owns both independently closed
        # pathname receipts. Neither may hide the other during recursion.
        sibling_name = 'build/original-unlinkat/manifest.json'
        sibling_artifacts = {}
        for relative in cache.ORIGINAL_UNLINKAT_OUTPUTS - {sibling_name}:
            path = self.root / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text('unlinkat fixture\n')
            sibling_artifacts[relative] = cache.digest(path)
        (self.root / sibling_name).write_text(json.dumps(dict(receipt,
            directoryUnit="directory-1.3.10.0-inplace", entries=list(cache.ORIGINAL_UNLINKAT_ENTRIES), artifactHashes=sibling_artifacts)))
        group = {'outputs': ['build/original-posix-stat', 'build/original-unlinkat', 'build/original-directory-streams']}
        with mock.patch.object(fast_fixtures.platform, 'system', return_value='Linux'):
            expected = fast_fixtures._output_hashes(self.root, group)
            self.assertEqual(cache.ORIGINAL_DIRECTORY_STREAMS_OUTPUTS | cache.ORIGINAL_UNLINKAT_OUTPUTS |
                             {'build/original-posix-stat/manifest.json'}, set(expected))
            with mock.patch.object(fast_fixtures, 'FULL_REQUIRED', set(expected)), \
                    mock.patch.object(fast_fixtures, 'FULL_OUTPUT_ROOTS', frozenset(group['outputs'])):
                self.assertEqual(set(expected), set(fast_fixtures._full_output_hashes(self.root)))
            path = self.root / 'build/original-directory-streams/pre.cbd'
            path.write_text('mutated')
            with self.assertRaises(RuntimeError): fast_fixtures._output_hashes(self.root, group)
            path.unlink(); path.symlink_to(scratch / 'link')
            with self.assertRaises(cache.CacheMiss): fast_fixtures._output_hashes(self.root, group)
            path.unlink()
            with self.assertRaises(FileNotFoundError): fast_fixtures._output_hashes(self.root, group)
        with mock.patch.object(fast_fixtures.platform, 'system', return_value='Darwin'):
            self.assertEqual({'build/original-posix-stat/manifest.json'}, set(fast_fixtures._output_hashes(self.root, group)))

    def test_simd_smoke_recipe_tracks_generated_haskell_inputs(self):
        project = Path(__file__).resolve().parents[2]
        manifest, owners = fast_fixtures._manifest(project)
        group = manifest['groups']['simd-capability-smoke']
        self.assertEqual('simd-capability-smoke', owners['thc.runtime.SimdCapabilitySmokeTest'])
        sources = fast_fixtures.fast_inputs.SIMD_SMOKE_SOURCES
        self.assertIn('build/simd-capability-smoke/pre-core/GeneratedSimdSmoke.cbd', fast_fixtures.fast_inputs.SIMD_SMOKE_OUTPUTS)
        self.assertNotIn('build/simd-capability-smoke/pre-core/GeneratedSimdSmoke.json', fast_fixtures.fast_inputs.SIMD_SMOKE_OUTPUTS)
        self.assertEqual({'build/simd-capability-smoke'} | sources, set(group['outputs']))
        self.assertLessEqual(sources, fast_fixtures.FULL_REQUIRED)
        self.assertNotIn('bin/prepare-simd-families.py', group['sources'])
        self.assertNotIn('bin/simd_family_model.py', group['sources'])

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
                      (project / 'bin/prepare-tests.sh').read_text().splitlines())
        self.assertEqual(fast_fixtures.FULL_PREPARATION_PLAN, fast_fixtures._preparation_plan(project))
        self.assertIn('build/original-fd-ready', fast_fixtures.FULL_OUTPUT_ROOTS)
        self.assertTrue(fast_fixtures.fast_inputs.ORIGINAL_FD_READY_OUTPUTS <= fast_fixtures.FULL_REQUIRED)
        self.assertIn('"original-fd-ready/**/*.json"', (project / 'build.gradle').read_text())
        self.assertIn('"original-fd-ready/**/*.cbd"', (project / 'build.gradle').read_text())
        self.assertIn('src/cbd/THC/Compact/Module.hs', group['sources'])
        self.assertIn('build/original-fd-ready/OriginalFdReadyAudit.cbd',
                      fast_fixtures.fast_inputs.ORIGINAL_FD_READY_OUTPUTS)
        for label in fast_fixtures.fast_inputs.ORIGINAL_FD_READY_NEGATIVES:
            self.assertIn(f'build/original-fd-ready/negative/{label}.cbd',
                          fast_fixtures.fast_inputs.ORIGINAL_FD_READY_OUTPUTS)
        for path in fast_fixtures.fast_inputs.ORIGINAL_FD_READY_OUTPUTS:
            self.assertTrue(fast_fixtures.fast_inputs.allowed_payload(path), path)
        for name in ('OriginalFD.json', 'native/unreviewed', 'logs/extra.stdout',
                     'negative/extra.json', 'native/OriginalFdReadyAudit.hi', 'test-results/pass.xml'):
            self.assertFalse(fast_fixtures.fast_inputs.allowed_payload('build/original-fd-ready/' + name))

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
                       nativeRows=168, negativeAudits=20, negativeControls=10,
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

    def test_rts_diagnostics_exact_fixture_registration(self):
        project = Path(__file__).resolve().parents[2]
        manifest, owners = fast_fixtures._manifest(project)
        group = manifest['groups']['rts-diagnostics']
        self.assertEqual('rts-diagnostics', owners['thc.runtime.RtsDiagnosticsTest'])
        self.assertEqual(['build/rts-diagnostics'], group['outputs'])
        self.assertTrue(all((project / name).is_file() for name in group['sources']))
        self.assertIn('"$fixture_bin" rts-diagnostics', (project / 'bin/prepare-tests.sh').read_text().splitlines())
        self.assertEqual(fast_fixtures.FULL_PREPARATION_PLAN, fast_fixtures._preparation_plan(project))
        outputs = fast_fixtures.fast_inputs.RTS_DIAGNOSTIC_OUTPUTS
        self.assertEqual(47, len(outputs))
        for label in ('debug-ascii', 'debug-empty', 'debug-bytes', 'debug-nul',
                      'debug-newline', 'trace-nul'):
            for suffix in ('stdout', 'stderr', 'command.json'):
                self.assertIn(f'build/rts-diagnostics/logs/{label}.{suffix}', outputs)
        self.assertTrue(outputs <= fast_fixtures.FULL_REQUIRED)
        self.assertTrue(all(fast_fixtures.fast_inputs.allowed_payload(path) for path in outputs))
        self.assertFalse(fast_fixtures.fast_inputs.allowed_payload('build/rts-diagnostics/native/oracle'))
        artifacts = {path: '0' * 64 for path in outputs if not path.endswith('/manifest.json')}
        proof = dict(schema=1, artifactHashes=artifacts)
        self.assertEqual(artifacts, fast_fixtures.fast_inputs.rts_diagnostic_artifact_hashes(proof))
        legacy = {path: digest for path, digest in artifacts.items()
                  if '/logs/debug-' not in path and '/logs/trace-nul.' not in path}
        for invalid in (dict(artifacts, **{'build/rts-diagnostics/extra.json': '0'*64}),
                        legacy, {}):
            with self.assertRaises(RuntimeError):
                fast_fixtures.fast_inputs.rts_diagnostic_artifact_hashes(dict(proof, artifactHashes=invalid))

    def test_exception_result_layout_fixture_registration(self):
        project = Path(__file__).resolve().parents[2]
        manifest, owners = fast_fixtures._manifest(project)
        group = manifest['groups']['exception-result-layouts']
        self.assertEqual('exception-result-layouts', owners['thc.runtime.ExceptionResultLayoutsNativeTest'])
        self.assertEqual(['build/exception-result-layouts'], group['outputs'])
        self.assertTrue(all((project / name).is_file() for name in group['sources']))
        self.assertIn('"$fixture_bin" exception-result-layouts', (project / 'bin/prepare-tests.sh').read_text().splitlines())
        self.assertEqual(fast_fixtures.FULL_PREPARATION_PLAN, fast_fixtures._preparation_plan(project))
        self.assertIn('build/exception-result-layouts', fast_fixtures.FULL_OUTPUT_ROOTS)
        expected = {'build/exception-result-layouts/manifest.json',
                    'build/exception-result-layouts/native/oracle', 'build/exception-result-layouts/oracle.tsv'}
        stages = ('pre',) if fast_fixtures.platform.machine().lower() in ('arm64', 'aarch64') else ('pre', 'post')
        expected.update(f'build/exception-result-layouts/{stage}/core/ExceptionResultLayoutsAudit.cbd'
                        for stage in stages)
        expected.update(f'build/exception-result-layouts/{stage}/{family}Result-audit.json'
                        for stage in stages for family in ('int8', 'word8', 'int16', 'word16',
                        'int32', 'word32', 'int64', 'word64', 'float', 'double', 'empty', 'nested',
                        'sum', 'vector', 'unlifted', 'unliftedPayload'))
        self.assertTrue(expected <= fast_fixtures.FULL_REQUIRED)

    def test_scalar_exception_result_fixture_registration(self):
        project = Path(__file__).resolve().parents[2]
        manifest, owners = fast_fixtures._manifest(project)
        group = manifest['groups']['scalar-exception-results']
        self.assertEqual('scalar-exception-results', owners['thc.runtime.ScalarExceptionResultsNativeTest'])
        self.assertEqual(['build/scalar-exception-results'], group['outputs'])
        self.assertTrue(all((project / name).is_file() for name in group['sources']))
        self.assertIn('"$fixture_bin" scalar-exception-results', (project / 'bin/prepare-tests.sh').read_text().splitlines())
        self.assertEqual(fast_fixtures.FULL_PREPARATION_PLAN, fast_fixtures._preparation_plan(project))
        self.assertIn('build/scalar-exception-results', fast_fixtures.FULL_OUTPUT_ROOTS)
        expected = {'build/scalar-exception-results/manifest.json',
                    'build/scalar-exception-results/native/oracle',
                    'build/scalar-exception-results/logs/native-oracle.stdout'}
        expected.update(f'build/scalar-exception-results/{stage}/core/ScalarExceptionResultsAudit.cbd'
                        for stage in ('pre', 'post'))
        expected.update(f'build/scalar-exception-results/{stage}/{prefix}{suffix}-audit.json'
                        for stage in ('pre', 'post') for prefix in ('normal', 'throw', 'interrupt')
                        for suffix in ('Int', 'Word', 'Addr'))
        self.assertTrue(expected <= fast_fixtures.FULL_REQUIRED)

    def test_rts_shutdown_exact_fixture_registration(self):
        project = Path(__file__).resolve().parents[2]
        manifest, owners = fast_fixtures._manifest(project)
        group = manifest['groups']['rts-shutdown']
        self.assertEqual('rts-shutdown', owners['thc.runtime.RtsShutdownTest'])
        self.assertEqual(['build/rts-shutdown'], group['outputs'])
        self.assertTrue(all((project / name).is_file() for name in group['sources']))
        self.assertIn('"$fixture_bin" rts-shutdown', (project / 'bin/prepare-tests.sh').read_text().splitlines())
        self.assertEqual(fast_fixtures.FULL_PREPARATION_PLAN, fast_fixtures._preparation_plan(project))
        outputs = fast_fixtures.fast_inputs.RTS_SHUTDOWN_OUTPUTS
        self.assertEqual(53, len(outputs))
        self.assertTrue(outputs <= fast_fixtures.FULL_REQUIRED)
        self.assertTrue(all(fast_fixtures.fast_inputs.allowed_payload(path) for path in outputs))
        self.assertFalse(fast_fixtures.fast_inputs.allowed_payload('build/rts-shutdown/native/oracle'))
        artifacts = {path: '0' * 64 for path in outputs if not path.endswith('/manifest.json')}
        proof = dict(schema=1, artifactHashes=artifacts)
        self.assertEqual(artifacts, fast_fixtures.fast_inputs.rts_shutdown_artifact_hashes(proof))
        for invalid in (dict(artifacts, **{'build/rts-shutdown/extra.json': '0'*64}), {}):
            with self.assertRaises(RuntimeError):
                fast_fixtures.fast_inputs.rts_shutdown_artifact_hashes(dict(proof, artifactHashes=invalid))

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
                      (project / 'bin/prepare-tests.sh').read_text().splitlines())
        self.assertEqual(fast_fixtures.FULL_PREPARATION_PLAN, fast_fixtures._preparation_plan(project))
        self.assertIn('build/original-rts-locks', fast_fixtures.FULL_OUTPUT_ROOTS)
        self.assertTrue(fast_fixtures.fast_inputs.ORIGINAL_RTS_LOCK_OUTPUTS <= fast_fixtures.FULL_REQUIRED)
        self.assertIn('"original-rts-locks/**/*.json"', (project / 'build.gradle').read_text())
        self.assertIn('"original-rts-locks/**/*.cbd"', (project / 'build.gradle').read_text())
        self.assertIn('src/cbd/THC/Compact/Module.hs', group['sources'])
        for stage in ('pre', 'post'):
            name = f'build/original-rts-locks/{stage}.cbd'
            self.assertIn(name, fast_fixtures.fast_inputs.ORIGINAL_RTS_LOCK_OUTPUTS)
            self.assertTrue(fast_fixtures.fast_inputs.allowed_payload(name))
        self.assertFalse(fast_fixtures.fast_inputs.allowed_payload('build/original-rts-locks/extra.cbd'))

    def test_original_open_exact_registration_and_closed_manifest(self):
        project = Path(__file__).resolve().parents[2]
        manifest, owners = fast_fixtures._manifest(project)
        group = manifest['groups']['original-open']
        self.assertEqual('original-open', owners['thc.runtime.OriginalOpenTest'])
        self.assertEqual([{'argv': ['cabal', 'run', 'exe:thc-fixtures', '--offline', '--', 'original-open']}], group['commands'])
        self.assertEqual(['build/original-open'], group['outputs'])
        self.assertTrue({'t/fixtures/compiler/OriginalOpenRequestNative.hs',
                         'src/main/c/native-open-request.c', 'src/driver/THC/Driver/NativeCache.hs'}
                        <= set(group['sources']))
        self.assertTrue(all((project / name).is_file() for name in group['sources']))
        self.assertIn('"$fixture_bin" original-open', (project / 'bin/prepare-tests.sh').read_text().splitlines())
        self.assertEqual(fast_fixtures.FULL_PREPARATION_PLAN, fast_fixtures._preparation_plan(project))
        self.assertIn('build/original-open', fast_fixtures.FULL_OUTPUT_ROOTS)
        self.assertIn('"original-open/**/*.json"', (project / 'build.gradle').read_text())
        name = 'build/original-open/manifest.json'
        artifacts = {}
        for artifact in fast_fixtures.fast_inputs.ORIGINAL_OPEN_OUTPUTS - {name}:
            path = self.root / artifact; path.parent.mkdir(parents=True, exist_ok=True); path.write_text('{}\n')
            artifacts[artifact] = fast_fixtures._digest(path)
        receipt = dict(schema=1, supported=True, strictAccepted=True, runtimeVerified=False,
                       installedArtifactsHashed=False, nativeRows=13, nativeVariants=["unsafe", "safe", "interruptible"],
                       ownedRequestControls=True, artifactHashes=artifacts)
        path = self.root / name
        with mock.patch.object(fast_fixtures.fast_inputs, 'ORIGINAL_OPEN_HOST', True, create=True), \
                mock.patch.object(fast_fixtures.fast_inputs, 'GMP_NATIVE_HOST', False):
            path.write_text(json.dumps(receipt))
            self.assertEqual(fast_fixtures.fast_inputs.ORIGINAL_OPEN_OUTPUTS,
                             fast_fixtures._output_hashes(self.root, group).keys())
            for key, value in (('schema', True), ('supported', False), ('strictAccepted', False),
                               ('runtimeVerified', True), ('installedArtifactsHashed', True), ('nativeRows', True), ('nativeRows', 12),
                               ('nativeVariants', ['unsafe']), ('ownedRequestControls', False)):
                path.write_text(json.dumps(dict(receipt, **{key: value})))
                with self.assertRaises(RuntimeError): fast_fixtures._output_hashes(self.root, group)
            for change in ('unknown', 'missing', 'changed', 'symlink'):
                path.write_text(json.dumps(receipt))
                artifact = self.root / 'build/original-open/pre/core/OriginalOpenAudit.cbd'
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

    def test_interface_core_fixture_registration(self):
        project = Path(__file__).resolve().parents[2]
        manifest, owners = fast_fixtures._manifest(project)
        group = manifest['groups']['interface-core']
        self.assertEqual('interface-core', owners['thc.runtime.InterfaceCoreNativeTest'])
        self.assertEqual('interface-core', owners['thc.ManagedImportStubsNativeTest'])
        self.assertEqual('interface-core', owners['thc.CoreUnitManagedExportTest'])
        self.assertEqual([{'argv': ['cabal', 'run', 'exe:thc-fixtures', '--offline', '--',
                                   'interface-core']}], group['commands'])
        self.assertEqual(['build/interface-core'], group['outputs'])
        self.assertTrue(all((project / name).is_file() for name in group['sources']))
        self.assertIn('"$fixture_bin" interface-core',
                      (project / 'bin/prepare-tests.sh').read_text().splitlines())
        self.assertEqual(fast_fixtures.FULL_PREPARATION_PLAN, fast_fixtures._preparation_plan(project))
        self.assertIn('build/interface-core', fast_fixtures.FULL_OUTPUT_ROOTS)
        for name in ('manifest.json', 'InterfaceLibrary.cbd', 'logs/native-oracle.stdout',
                     'full/InterfaceLibrary.hi', 'thin/InterfaceLibrary.hi',
                     'full/InterfaceLibrary.dyn_hi', 'full/InterfaceForeign.hi',
                     'source/InterfaceLibrary.saved', 'opaqueEntry-audit.json',
                     'wrapperEntry-audit.json', 'installed-wrapper-facts.json',
                     'inlineEntry-audit.json', 'recursiveEntry-audit.json',
                     'CBVCoercionAudit.cbd', 'direct/CBVCoercionAudit.cbd',
                     'full/CBVCoercionAudit.hi', 'thin/CBVCoercionAudit.hi',
                     'source/CBVCoercionAudit.saved', 'coercionEntry-audit.json',
                     'logs/helper-thin.stdout', 'logs/helper-thin.command.json',
                     'wired-unit.json', 'logs/helper-wired-unit.stdout',
                     'logs/helper-wired-unit.command.json', 'packages.json', 'driver-controls.json',
                     'cache-controls/facts.json', 'cache-controls/helper-calls',
                     'InterfaceForeign.cbd', 'foreign-packages.json',
                     'foreign-association.json', 'installed-bound-facts.json',
                     'foreign-alias/a.cbd', 'foreign-alias/b.cbd',
                     'source/InterfaceForeignAlias.hs.saved', 'import-stubs/plain.cbd', 'import-stubs/extra-file.cbd',
                     'import-stubs/wrapper.cbd', 'import-stubs/instrumented.cbd',
                     'source/ForeignImportStubs.hs.saved', 'import-stubs/plain/ForeignImportStubs.hi',
                     'logs/import-stubs-native-oracle.stdout'):
            self.assertIn('build/interface-core/' + name, fast_fixtures.FULL_REQUIRED)
        self.assertIn('"interface-core/**/*.json"', (project / 'build.gradle').read_text())
        self.assertIn('"interface-core/**/*.cbd"', (project / 'build.gradle').read_text())
        for path in fast_fixtures.FULL_REQUIRED:
            if path.startswith("build/interface-core/") and path.endswith(".cbd"):
                self.assertNotIn(path.removesuffix(".cbd") + ".json", fast_fixtures.FULL_REQUIRED)

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
                      (project / 'bin/prepare-tests.sh').read_text().splitlines())
        self.assertEqual(fast_fixtures.FULL_PREPARATION_PLAN, fast_fixtures._preparation_plan(project))
        self.assertIn('build/original-stack-formatter', fast_fixtures.FULL_OUTPUT_ROOTS)
        self.assertIn('build/original-stack-formatter/manifest.json', fast_fixtures.FULL_REQUIRED)
        gradle = (project / 'build.gradle').read_text()
        for pattern in ('original-stack-formatter/manifest.json',
                        'original-stack-formatter/run-*/originals/core/*.cbd',
                        'original-stack-formatter/run-*/originals/generated/**/*.hs',
                        'original-stack-formatter/run-*/originals/generated.json',
                        'original-stack-formatter/run-*/originals/target-layout.json',
                        'original-stack-formatter/run-*/native/formatter',
                        'src/driver/THC/Driver/Wired.hs', 'src/driver/cbits/target-layout.c',
                        'nih/pinned/ghc-9.14.1/libraries/ghc-internal', '**/*.hs-boot', '**/*.hsc', 'include/WordSize.h'):
            self.assertIn('"' + pattern + '"', gradle)
        self.assertIn('build/original-stack-formatter/', (project / '.github/workflows/build.yml').read_text())
        sources = fast_fixtures._source_hashes(project, group)
        _, pins = fast_fixtures.fast_inputs.wired_catalog(project)
        self.assertLessEqual({fast_fixtures.fast_inputs.wired_source_path(name) for name in pins}, sources.keys())
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
            path = self.root / fast_fixtures.fast_inputs.wired_source_path(name)
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
                     'nih/pinned/ghc-9.14.1/libraries/ghc-internal/src/GHC/Internal/Stack/Decode.hs',
                     'nih/pinned/ghc-9.14.1/libraries/ghc-internal/src/GHC/Internal/IO/Unsafe.hs',
                     'nih/pinned/ghc-9.14.1/libraries/ghc-internal/src/GHC/Internal/Heap/InfoTable/Types.hsc',
                     'nih/pinned/ghc-9.14.1/libraries/ghc-internal/src/GHC/Internal/Ptr.hs',
                     'nih/pinned/ghc-9.14.1/libraries/ghc-internal/src/GHC/Internal/Data/Either.hs',
                     'nih/pinned/ghc-9.14.1/libraries/ghc-internal/src/GHC/Internal/Word.hs',
                     'nih/pinned/ghc-9.14.1/libraries/ghc-internal/src/GHC/Internal/Bignum/Integer.hs',
                     'nih/pinned/ghc-9.14.1/libraries/ghc-internal/src/GHC/Internal/Classes.hs',
                     'nih/pinned/ghc-9.14.1/libraries/ghc-internal/src/GHC/Internal/Num.hs',
                     'nih/pinned/ghc-9.14.1/libraries/ghc-internal/src/GHC/Internal/Bignum/Integer.hs-boot',
                     'nih/pinned/ghc-9.14.1/libraries/ghc-internal/src/GHC/Internal/Bignum/BigNat.hs-boot',
                     'nih/pinned/ghc-9.14.1/libraries/ghc-internal/src/GHC/Internal/Bignum/Natural.hs-boot',
                     'nih/pinned/ghc-9.14.1/libraries/ghc-internal/src/GHC/Internal/Real.hs',
                     'nih/pinned/ghc-9.14.1/libraries/ghc-internal/src/GHC/Internal/Numeric.hs',
                     'nih/pinned/ghc-9.14.1/libraries/ghc-internal/src/GHC/Internal/Enum.hs',
                     'nih/pinned/ghc-9.14.1/libraries/ghc-internal/src/GHC/Internal/ForeignPtr.hs',
                     'nih/pinned/ghc-9.14.1/libraries/ghc-internal/src/GHC/Internal/Foreign/C/String/Encoding.hs',
                     'nih/pinned/ghc-9.14.1/libraries/ghc-internal/src/GHC/Internal/IO/Encoding/UTF8.hs',
                     'nih/pinned/ghc-9.14.1/libraries/ghc-internal/src/GHC/Internal/IO/Encoding/Types.hs',
                     'nih/pinned/ghc-9.14.1/libraries/ghc-internal/src/GHC/Internal/IO/Encoding/Failure.hs',
                     'nih/pinned/ghc-9.14.1/libraries/ghc-internal/src/GHC/Internal/IO/Encoding.hs',
                     'nih/pinned/ghc-9.14.1/libraries/ghc-internal/src/GHC/Internal/IO/Handle/Types.hs-boot',
                     'nih/pinned/ghc-9.14.1/libraries/ghc-internal/src/GHC/Internal/InfoProv/Types.hsc',
                     'nih/pinned/ghc-9.14.1/libraries/ghc-internal/src/GHC/Internal/Num.hs-boot',
                     'nih/pinned/ghc-9.14.1/libraries/ghc-internal/include/WordSize.h',
                     'nih/pinned/ghc-9.14.1/libraries/ghc-internal/LICENSE'):
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
        self.assertIn('"$fixture_bin" boxed-array-extensions', (project / 'bin/prepare-tests.sh').read_text().splitlines())
        self.assertIn('build/boxed-array-extensions/manifest.json', fast_fixtures.FULL_REQUIRED)
        gradle = (project / 'build.gradle').read_text()
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
        if argv == ["cabal", "run", "exe:thc-primops", "--", "scalars"] and self.mutate_scalar_on_generator:
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
            ["cabal", "run", "exe:thc-primops", "--", "scalars"],
            ["bin/build-compiler.sh"], ["make-alpha"]])
        self.assertEqual(self.prepare("thc.AlphaBackendTest"),
                         {"mode": "selected", "rebuilt": [], "reused": ["alpha"]})
        self.assertEqual(len(self.calls), 3)

    def test_only_changed_group_rebuilds_and_compiler_runs_once(self):
        self.prepare("thc.AlphaTest", "thc.BetaTest")
        self.assertEqual([argv for _, argv, _ in self.calls].count(["bin/build-compiler.sh"]), 1)
        self.calls.clear()
        (self.root / "fixtures/beta.hs").write_text("changed")
        result = self.prepare("thc.AlphaTest", "thc.BetaTest")
        self.assertEqual(result, {"mode": "selected", "rebuilt": ["beta"], "reused": ["alpha"]})
        self.assertEqual([argv for _, argv, _ in self.calls], [
            ["cabal", "run", "exe:thc-primops", "--", "scalars"],
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

    def test_missing_prepared_encoder_rebuilds_narrow_and_full_receipts(self):
        pointer = "build/thc-fixtures.path"
        executable = self.root / "dist-newstyle/thc-fixtures"
        self.manifest["groups"]["alpha"]["outputs"] = [pointer]
        self.manifest["groups"]["alpha"]["commands"] = [{"argv": ["make-encoder"]}]
        (self.root / fast_fixtures.MANIFEST).write_text(json.dumps(self.manifest))

        def run(name, argv, stdout=None):
            if argv not in (["make-encoder"], ["bin/prepare-tests.sh"]):
                return
            executable.parent.mkdir(parents=True, exist_ok=True)
            executable.write_text("#!/bin/sh\nexit 0\n")
            executable.chmod(0o755)
            path = self.root / pointer
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(str(executable) + "\n")

        with mock.patch.object(fast_fixtures, "FULL_OUTPUT_ROOTS", frozenset()), \
             mock.patch.object(fast_fixtures, "FULL_REQUIRED", frozenset({pointer})), \
             mock.patch.object(fast_fixtures, "_full_key", return_value="encoder-fixture"):
            for mode, group in (("narrow", "alpha"), ("full", "full")):
                with self.subTest(mode=mode):
                    def prepare():
                        return fast_fixtures.prepare(self.root, self.selection("thc.AlphaTest", mode=mode),
                                                     run, self.toolchain)
                    self.assertEqual([group], prepare()["rebuilt"])
                    self.assertEqual([group], prepare()["reused"])
                    executable.unlink()
                    self.assertEqual(str(executable), (self.root / pointer).read_text().strip())
                    self.assertEqual([group], prepare()["rebuilt"])
                    self.assertEqual([group], prepare()["reused"])

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

    def test_unknown_or_full_selection_runs_complete_preparation(self):
        self.assertEqual(self.prepare("thc.UnknownTest"),
                         {"mode": "full", "rebuilt": ["full"], "reused": []})
        self.assertEqual(self.calls, [("fixtures-full", ["bin/prepare-tests.sh"], None)])
        self.calls.clear()
        self.assertEqual(self.prepare("thc.AlphaTest", mode="full")["mode"], "full")
        self.assertEqual(self.calls, [("fixtures-full", ["bin/prepare-tests.sh"], None)])

    def test_fixture_free_selection_runs_no_commands(self):
        self.assertEqual(self.prepare("thc.FreeTest"),
                         {"mode": "selected", "rebuilt": [], "reused": []})
        self.assertEqual(self.calls, [])

    def test_mixed_backend_and_stock_recovery_need_no_exported_fixture(self):
        project = Path(__file__).resolve().parents[2]
        _, owners = fast_fixtures._manifest(project)
        for simple_name in ("MixedBackendContinuationTest", "StockGraphRecoveryTest"):
            name = "thc.runtime." + simple_name
            self.assertIn(name, owners)
            self.assertIsNone(owners[name])
            self.assertTrue((project / "src/test/java/thc/runtime" / (simple_name + ".java")).is_file())

    def test_java_array_direct_controls_need_no_full_core_fixture(self):
        project = Path(__file__).resolve().parents[2]
        _, owners = fast_fixtures._manifest(project)
        name = "thc.runtime.JavaArrayTest"
        self.assertIn(name, owners)
        self.assertIsNone(owners[name])
        self.manifest["fixtureFreeJunit"].append(name)
        (self.root / fast_fixtures.MANIFEST).write_text(json.dumps(self.manifest))
        self.assertEqual(self.prepare(name), {"mode": "selected", "rebuilt": [], "reused": []})
        self.assertEqual([], self.calls)
        policy = json.loads((project / ".github/scripts/fast-tests.json").read_text())
        for source in ("src/test/java/thc/runtime/JavaArrayTest.java", "t/haskell-fixtures/JavaArrayFixtures.hs",
                       "t/fixtures/compiler/JavaArrays.hs", "t/fixtures/compiler/JavaInteropSafe.hs"):
            self.assertEqual([name], policy["owners"][source]["junit"])
        build = (project / "build.gradle").read_text()
        self.assertIn('includeTestsMatching("thc.runtime.JavaArrayTest")', build)
        self.assertIn('checkBuild(file("build/java-arrays/packages.json").isFile())', build)
        self.assertIn('"java-arrays/core/**/*.cbd"', build)
        self.assertNotIn('"$fixture_bin" java-arrays', (project / 'bin/prepare-tests.sh').read_text())

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
                self.assertIn('"$fixture_bin" ' + name, (project / "bin/prepare-tests.sh").read_text().splitlines())
                self.assertIn("build/" + name, fast_fixtures.FULL_OUTPUT_ROOTS)
                self.assertIn("build/" + name + "/manifest.json", fast_fixtures.FULL_REQUIRED)
                self.assertIn("build/" + name + "/", (project / ".github/workflows/build.yml").read_text())
                self.assertIn(name + "/**/*.json", (project / "build.gradle").read_text())
                self.assertIn(junit, policy["leafSources"]["src/main/java/thc/runtime/FloatingPrimitives.java"]["junit"])

    def test_explicit64_array_fixture_is_selected_and_receipted(self):
        project = Path(__file__).resolve().parents[2]
        manifest, owners = fast_fixtures._manifest(project)
        group = manifest["groups"]["explicit64-arrays"]
        self.assertEqual("explicit64-arrays", owners["thc.runtime.Explicit64ArrayTest"])
        self.assertEqual(["build/explicit64-arrays"], group["outputs"])
        self.assertTrue(all((project / name).is_file() for name in group["sources"]))
        self.assertIn('"$fixture_bin" explicit64-arrays',
                      (project / "bin/prepare-tests.sh").read_text().splitlines())
        self.assertEqual(fast_fixtures.FULL_PREPARATION_PLAN, fast_fixtures._preparation_plan(project))
        self.assertIn("build/explicit64-arrays", fast_fixtures.FULL_OUTPUT_ROOTS)
        for name in ("manifest.json", "oracle.tsv", "pre/audit.json", "post/audit.json",
                     "pre/core/Explicit64ArrayAudit.cbd", "post/core/Explicit64ArrayAudit.cbd"):
            self.assertIn("build/explicit64-arrays/" + name, fast_fixtures.FULL_REQUIRED)
        self.assertIn('"explicit64-arrays/*.tsv"', (project / "build.gradle").read_text())

    def test_remaining_scalar_memory_audits_use_cbd_inputs(self):
        project = Path(__file__).resolve().parents[2]
        families = ("FloatingAddress", "FloatingByteOffset", "NarrowByteOffset", "Int32ByteOffset",
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

    def test_floatx4_fma_has_focused_full_and_closed_native_inputs(self):
        project = Path(__file__).resolve().parents[2]
        manifest, owners = fast_fixtures._manifest(project)
        group = manifest['groups']['simd-floatx4-fma']
        self.assertEqual('simd-floatx4-fma', owners['thc.runtime.SimdFloatFmaTest'])
        self.assertEqual([{'argv': ['cabal', 'run', 'exe:thc-fixtures', '--offline', '--',
                                   'simd-floatx4-fma']}], group['commands'])
        self.assertEqual(['build/simd-floatx4-fma'], group['outputs'])
        self.assertTrue(all((project / name).is_file() for name in group['sources']))
        self.assertIn('"$fixture_bin" simd-floatx4-fma', (project / 'bin/prepare-tests.sh').read_text().splitlines())
        self.assertEqual(fast_fixtures.FULL_PREPARATION_PLAN, fast_fixtures._preparation_plan(project))
        self.assertIn('build/simd-floatx4-fma', fast_fixtures.FULL_OUTPUT_ROOTS)
        for suffix in ('manifest.json', 'pre-audit.json', 'pre-double-audit.json', 'pre-core/SimdFloatFma.cbd'):
            self.assertIn('build/simd-floatx4-fma/' + suffix, fast_fixtures.FULL_REQUIRED)
        native = fast_fixtures.platform.machine().lower() not in ('arm64', 'aarch64')
        for suffix in ('oracle.txt', 'post-audit.json', 'post-double-audit.json', 'post-core/SimdFloatFma.cbd'):
            self.assertEqual(native, 'build/simd-floatx4-fma/' + suffix in fast_fixtures.FULL_REQUIRED)
        gradle = (project / 'build.gradle').read_text()
        for suffix in ('**/*.json', 'oracle.txt'):
            self.assertIn('"simd-floatx4-fma/' + suffix + '"', gradle)
        self.assertIn('build/simd-floatx4-fma/', (project / '.github/workflows/build.yml').read_text())

        wide = manifest['groups']['simd-wide-floating-fma']
        self.assertEqual('simd-wide-floating-fma', owners['thc.runtime.SimdWideFloatFmaTest'])
        self.assertEqual([{'argv': ['cabal', 'run', 'exe:thc-fixtures', '--offline', '--',
                                   'simd-wide-floating-fma']}], wide['commands'])
        self.assertEqual(['build/simd-wide-floating-fma'], wide['outputs'])
        self.assertTrue(all((project / name).is_file() for name in wide['sources']))
        self.assertIn('"$fixture_bin" simd-wide-floating-fma', (project / 'bin/prepare-tests.sh').read_text().splitlines())
        self.assertIn('build/simd-wide-floating-fma', fast_fixtures.FULL_OUTPUT_ROOTS)
        # Scalar FMA expectations are mandatory on every supported host; AVX512 is not required.
        for suffix in ('manifest.json', 'oracle.txt', 'pre-audit.json', 'pre-double-audit.json',
                       'pre-core/SimdWideFloatFma.cbd'):
            self.assertIn('build/simd-wide-floating-fma/' + suffix, fast_fixtures.FULL_REQUIRED)
        for suffix in ('**/*.json', 'oracle.txt'):
            self.assertIn('"simd-wide-floating-fma/' + suffix + '"', gradle)
        self.assertIn('build/simd-wide-floating-fma/', (project / '.github/workflows/build.yml').read_text())

    def test_floating_address_fixture_is_selected_and_receipted(self):
        project = Path(__file__).resolve().parents[2]
        manifest, owners = fast_fixtures._manifest(project)
        group = manifest["groups"]["floating-address"]
        self.assertEqual("floating-address", owners["thc.runtime.FloatingAddressTest"])
        self.assertEqual(["build/floating-address"], group["outputs"])
        self.assertTrue(all((project / name).is_file() for name in group["sources"]))
        self.assertIn('"$fixture_bin" floating-address',
                      (project / "bin/prepare-tests.sh").read_text().splitlines())
        self.assertEqual(fast_fixtures.FULL_PREPARATION_PLAN, fast_fixtures._preparation_plan(project))
        self.assertIn("build/floating-address", fast_fixtures.FULL_OUTPUT_ROOTS)
        for name in ("manifest.json", "oracle.tsv", "pre/audit.json", "post/audit.json",
                     "pre/core/FloatingAddressAudit.cbd", "post/core/FloatingAddressAudit.cbd"):
            self.assertIn("build/floating-address/" + name, fast_fixtures.FULL_REQUIRED)
        self.assertIn('"floating-address/*.tsv"', (project / "build.gradle").read_text())

    def test_atomic_address_family_has_one_native_receipt_and_complete_ownership(self):
        project = Path(__file__).resolve().parents[2]
        manifest, owners = fast_fixtures._manifest(project)
        group = manifest["groups"]["atomic-address"]
        self.assertEqual("atomic-address", owners["thc.runtime.AtomicAddressTest"])
        self.assertEqual(["build/atomic-address"], group["outputs"])
        self.assertTrue(all((project / name).is_file() for name in group["sources"]))
        self.assertIn('"$fixture_bin" atomic-address',
                      (project / "bin/prepare-tests.sh").read_text().splitlines())
        self.assertIn("build/atomic-address", fast_fixtures.FULL_OUTPUT_ROOTS)
        for suffix in ("manifest.json", "oracle.tsv", "pre/audit.json", "post/audit.json",
                       "pre/core/AtomicAddressAudit.cbd", "post/core/AtomicAddressAudit.cbd"):
            self.assertIn("build/atomic-address/" + suffix, fast_fixtures.FULL_REQUIRED)
        self.assertIn('"atomic-address/*.tsv"', (project / "build.gradle").read_text())
        self.assertIn("build/atomic-address/", (project / ".github/workflows/build.yml").read_text())

    def test_floating_native_consumers_use_existing_complete_preparation_groups(self):
        project = Path(__file__).resolve().parents[2]
        manifest, owners = fast_fixtures._manifest(project)
        # Reviewed generated inputs of these native consumers.
        consumed = {
            "thc.SumLayoutMetadataTest": ("sum-results", ["sum-layout"]),
            "thc.runtime.SumProtocolTest": ("sum-results", ["sum-layout", "sum-result"]),
            "thc.runtime.SumResultTest": ("sum-results", ["sum-layout", "sum-result", "aggregate-core",
                                                       "aggregate-post-core", "aggregate-native"]),
            "thc.runtime.TupleInputNativeTest": ("tuple-input", ["tuple-input"]),
            "thc.runtime.FloatingTupleTest": ("floating-tuples", ["floating-tuple"]),
            "thc.runtime.SqrtPrimitiveTest": ("sqrt", ["sqrt"]),
            "thc.runtime.ScalarBitCastTest": ("scalar-bitcasts", ["scalar-bitcasts"]),
            "thc.runtime.BigNatLiteralTest": ("bignat-literals", ["bignat-literals"]),
            "thc.runtime.SimdFloatVectorTest": ("simd-floatx4", ["simd-floatx4"]),
            "thc.runtime.SimdDoubleVectorTest": ("simd-doublex2", ["simd-doublex2"]),
            "thc.runtime.SimdFloatByteArrayTest": ("simd-floatx4-bytearray", ["simd-floatx4-bytearray"]),
            "thc.runtime.SimdDoubleByteArrayTest": ("simd-doublex2-bytearray", ["simd-doublex2-bytearray"]),
        }
        full = " ".join((project / "bin/prepare-tests.sh").read_text().split())
        for junit, (group_id, roots) in consumed.items():
            with self.subTest(junit=junit):
                self.assertEqual(group_id, owners[junit])
                group = manifest["groups"][group_id]
                path = project / "src/test/java" / (junit.replace(".", "/") + ".java")
                self.assertTrue(path.is_file(), junit)
                source = path.read_text()
                for root in roots:
                    self.assertTrue('"build/' + root in source or '"' + root + '"' in source, root)
                    self.assertIn("build/" + root, group["outputs"])
                self.assertTrue(all((project / name).is_file() for name in group["sources"]))
                for command in group["commands"]:
                    argv = command["argv"]
                    if argv[:5] == ["cabal", "run", "exe:thc-fixtures", "--offline", "--"] and len(argv) == 6:
                        self.assertIn('"$fixture_bin" ' + argv[5], full)
                    else:
                        command = argv[2] if argv[:2] == ["sh", "-c"] else " ".join(argv)
                        self.assertIn(" ".join(command.replace("cabal run exe:thc-fixtures --offline --", '"$fixture_bin"').split()), full)
        # The aggregate producer also runs the recursive-layout rejection
        # checks. Keep their inputs and outputs in the same receipt.
        sums = manifest["groups"]["sum-results"]
        self.assertIn("bin/check-aggregate-layout.py", sums["sources"])
        self.assertIn("t/fixtures/compiler/AggregateLayoutAudit.hs", sums["sources"])
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
                path = project / "src/test/java/thc/runtime" / (name + ".java")
                self.assertTrue(path.is_file(), name)
                source = path.read_text()
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
                    actual = subprocess.check_output([*command[:2], prefix + command[2]], text=True).splitlines()
                    self.assertEqual((["run", "exe:thc-fixtures", "--offline", "--", family] if family.endswith("-bytearray")
                                      else ["bin/prepare-" + family + "-audit.py"]) +
                                     ([] if machine == "x86_64" else ["--export-only"]), actual)

    def test_complete_floating_selection_prepares_and_reuses_without_full_fallback(self):
        project = Path(__file__).resolve().parents[2]
        manifest, owners = fast_fixtures._manifest(project)
        policy = json.loads((project / ".github/scripts/fast-tests.json").read_text())
        classes = policy["leafSources"]["src/main/java/thc/runtime/FloatingPrimitives.java"]["junit"]
        expected = sorted({owners[name] for name in classes if owners[name] is not None})
        self.assertEqual(28, len(classes))
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
                if group_id in fast_fixtures.fast_inputs.SIMD_BYTEARRAY_FAMILIES:
                    attempt = f"build/{group_id}/prepare-run-Test123"
                    required = fast_fixtures.fast_inputs.simd_bytearray_outputs(group_id, attempt, True)
                    for output in required:
                        path = self.root / output
                        path.parent.mkdir(parents=True, exist_ok=True)
                        path.write_bytes(b"{}\n" if output.endswith(".json") else b"fixture\n")
                    rows = fast_fixtures.fast_inputs.SIMD_BYTEARRAY_FAMILIES[group_id][1]
                    (self.root / f"build/{group_id}/provenance.json").write_text(json.dumps(dict(
                        schema=1, vector=group_id.removeprefix("simd-"), stages=["pre", "post"], attempt=attempt,
                        modelRows=rows, modelByteOrder="little", nativeRows=rows, nativeByteOrder="little", modelMatched=True,
                        artifacts=[dict(path=path, sha256=fast_fixtures.fast_inputs.digest(self.root / path)) for path in sorted(required)])))
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
        for group_id, source in (("sum-results", "bin/sum_layout_model.py"),
                                 ("floating-tuples", "t/fixtures/compiler/FloatingTupleAudit.hs")):
            with self.subTest(source=source):
                path = self.root / source
                path.write_bytes(path.read_bytes() + b"\n# changed\n")
                self.assertEqual([group_id], prepare()["rebuilt"])
        for group_id in ("floating-remainder", "sum-results", "floating-tuples", "sqrt", "scalar-bitcasts", "simd-floatx4", "simd-floatx4-fma", "simd-wide-floating-fma",
                         "simd-doublex2", "simd-floatx4-bytearray", "simd-doublex2-bytearray"):
            for change in ("bytes", "missing"):
                with self.subTest(group=group_id, change=change):
                    path = self.root / self.manifest["groups"][group_id]["outputs"][0] / (
                        "expected.tsv" if group_id in fast_fixtures.fast_inputs.SIMD_BYTEARRAY_FAMILIES else "fixture.json")
                    if change == "bytes":
                        path.write_text("corrupt\n")
                    else:
                        path.unlink()
                    self.assertEqual([group_id], prepare()["rebuilt"])
                    self.assertEqual([], prepare()["rebuilt"])

    def test_pinned_addresses_use_haskell_and_closed_original_receipts(self):
        project = Path(__file__).resolve().parents[2]
        manifest, owners = fast_fixtures._manifest(project)
        group = manifest["groups"]["pinned-addresses"]
        self.assertEqual("pinned-addresses", owners["thc.runtime.PinnedAddressTest"])
        self.assertEqual([{"argv": ["cabal", "run", "exe:thc-fixtures", "--offline", "--", "pinned-addresses"]}], group["commands"])
        self.assertEqual(["build/pinned-addresses"], group["outputs"])
        self.assertEqual({"t/haskell-fixtures/PinnedAddressFixtures.hs", "t/haskell-fixtures/FixtureSupport.hs",
                          "t/haskell-fixtures/Main.hs", "thc.cabal", "t/fixtures/compiler/PinnedAddressAudit.hs",
                          "t/fixtures/compiler/PinnedAddressAuditNative.hs"}, set(group["sources"]))
        self.assertTrue(all((project / name).is_file() for name in group["sources"]))
        self.assertIn('"$fixture_bin" pinned-addresses', (project / "bin/prepare-tests.sh").read_text().splitlines())
        self.assertEqual(fast_fixtures.FULL_PREPARATION_PLAN, fast_fixtures._preparation_plan(project))
        for name in ("prepare-pinned-addresses.py", "pinned_address_model.py", "test-pinned-addresses.py"):
            self.assertFalse((project / "bin" / name).exists())
        cache = fast_fixtures.fast_inputs
        name = "build/pinned-addresses/manifest.json"
        records = {}
        for path in cache.PINNED_ADDRESS_OUTPUTS - {name}:
            file = self.root / path; file.parent.mkdir(parents=True, exist_ok=True)
            file.write_text('{}\n' if path.endswith('.json') else 'fixture\n')
            records[path] = fast_fixtures._digest(file)
        receipt = dict(mode="full", strictAccepted=True, artifactHashes=records)
        (self.root / name).write_text(json.dumps(receipt))
        archive = self.root / 'build/pinned-addresses/previous-manifests/old.json'
        archive.parent.mkdir(); archive.write_text('preserved prior proof\n')
        self.assertEqual(cache.PINNED_ADDRESS_OUTPUTS, set(fast_fixtures._output_hashes(self.root, group)))
        for mode in ("native-only", "export-only"):
            with self.assertRaises(cache.CacheMiss): cache.pinned_address_artifact_hashes(dict(receipt, mode=mode))
        with self.assertRaises(cache.CacheMiss): cache.pinned_address_artifact_hashes(dict(receipt, strictAccepted=False))
        corrupt = self.root / 'build/pinned-addresses/oracle.tsv'; corrupt.write_text('corrupt\n')
        with self.assertRaises(RuntimeError): fast_fixtures._output_hashes(self.root, group)
        corrupt.unlink(); corrupt.symlink_to(archive)
        with self.assertRaises(cache.CacheMiss): fast_fixtures._output_hashes(self.root, group)

    def test_bignat_uses_haskell_and_tracks_original_source_inputs(self):
        project = Path(__file__).resolve().parents[2]
        manifest, owners = fast_fixtures._manifest(project)
        group = manifest["groups"]["bignat-literals"]
        self.assertEqual("bignat-literals", owners["thc.runtime.BigNatLiteralTest"])
        self.assertEqual([{"argv": ["cabal", "run", "exe:thc-fixtures", "--offline", "--", "bignat-literals"]}], group["commands"])
        originals = fast_fixtures.fast_inputs.BIGNAT_SOURCES
        self.assertEqual({"build/bignat-literals"}, set(group["outputs"]))
        self.assertEqual({"t/haskell-fixtures/BigNatLiteralFixtures.hs", "t/haskell-fixtures/FixtureSupport.hs",
                          "t/haskell-fixtures/Main.hs", "thc.cabal", "t/fixtures/compiler/BigNatLiteralAudit.hs",
                          "t/fixtures/compiler/BigNatLiteralAuditNative.hs", "bin/export-boot.py"} | originals, set(group["sources"]))
        self.assertTrue(all((project / name).is_file() for name in group["sources"]))
        self.assertIn('"$fixture_bin" bignat-literals', (project / "bin/prepare-tests.sh").read_text().splitlines())
        self.assertEqual(fast_fixtures.FULL_PREPARATION_PLAN, fast_fixtures._preparation_plan(project))
        for name in ("prepare-bignat-literals.py", "bignat_literal_model.py", "test-bignat-literals.py"):
            self.assertFalse((project / "bin" / name).exists())
        cache = fast_fixtures.fast_inputs
        records = []
        manifest_path = 'build/bignat-literals/manifest.json'
        for name in cache.BIGNAT_OUTPUTS - {manifest_path}:
            path = self.root / name; path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text('{}\n' if name.endswith('.json') else 'fixture\n')
            records.append({'path': name, 'sha256': fast_fixtures._digest(path)})
        (self.root / manifest_path).write_text(json.dumps({'artifacts': records}))
        # Installed-interface overlays are not reusable fixture payload.
        overlay = self.root / 'build/bignat-literals/boot/interfaces/unused.hi'
        overlay.parent.mkdir(parents=True); overlay.symlink_to(self.root / 'not-present')
        # Rechecks retain their own diagnostics, including fresh audit catalogue
        # paths, without replacing the original manifest-bound command evidence.
        verification = 'build/bignat-literals/verification-control/commands/pre-integerRoundTrip-audit.stderr'
        recheck = self.root / verification
        recheck.parent.mkdir(parents=True); recheck.write_text('Audit working catalogue: fresh/catalogue.sqlite\n')
        self.assertFalse(cache.allowed_payload(verification))
        self.assertEqual(cache.BIGNAT_OUTPUTS, set(fast_fixtures._output_hashes(self.root, group)))
        original_log = self.root / 'build/bignat-literals/commands/pre-integerRoundTrip-audit.stderr'
        original_bytes = original_log.read_bytes()
        original_log.write_bytes(recheck.read_bytes())
        with self.assertRaisesRegex(RuntimeError, 'Stale original artifact'):
            fast_fixtures._output_hashes(self.root, group)
        original_log.write_bytes(original_bytes)
        corrupted = self.root / 'build/bignat-literals/oracle.tsv'
        corrupted.write_text('corrupt\n')
        with self.assertRaises(RuntimeError): fast_fixtures._output_hashes(self.root, group)

    def test_floating_remainder_has_native_haskell_preparation(self):
        project = Path(__file__).resolve().parents[2]
        manifest, owners = fast_fixtures._manifest(project)
        group = manifest["groups"]["floating-remainder"]
        self.assertEqual("floating-remainder", owners["thc.runtime.FloatingRemainderTest"])
        self.assertEqual([{"argv": ["cabal", "run", "exe:thc-fixtures", "--offline", "--", "floating-remainder"]}], group["commands"])
        self.assertEqual(["build/floating-remainder"], group["outputs"])
        self.assertEqual({"t/haskell-fixtures/FloatingRemainderFixtures.hs", "t/haskell-fixtures/FixtureSupport.hs",
                          "t/haskell-fixtures/Main.hs", "thc.cabal", "t/fixtures/compiler/FloatingRemainderAudit.hs",
                          "t/fixtures/compiler/FloatingRemainderNative.hs", "t/fixtures/core/InverseHyperbolic.hs"}, set(group["sources"]))
        self.assertTrue(all((project / path).is_file() for path in group["sources"]))
        self.assertIn('"$fixture_bin" floating-remainder', (project / "bin/prepare-tests.sh").read_text().splitlines())
        self.assertIn('"floating-remainder/commands/**"', (project / "build.gradle").read_text())
        policy = json.loads((project / ".github/scripts/fast-tests.json").read_text())
        for path in ("t/fixtures/core/InverseHyperbolic.hs", "t/haskell-fixtures/FloatingRemainderFixtures.hs"):
            self.assertEqual(["thc.runtime.FloatingRemainderTest"], policy["owners"][path]["junit"])
        for path in ("src/main/java/thc/runtime/FloatingPrimitives.java", "src/main/java/thc/runtime/FloatDecodeExpression.java"):
            self.assertIn("thc.runtime.FloatingRemainderTest", policy["leafSources"][path]["junit"])
        self.assertEqual(fast_fixtures.FULL_PREPARATION_PLAN, fast_fixtures._preparation_plan(project))

    def test_float_decode_has_native_haskell_preparation(self):
        project = Path(__file__).resolve().parents[2]
        manifest, owners = fast_fixtures._manifest(project)
        group = manifest["groups"]["float-decode"]
        self.assertEqual("float-decode", owners["thc.runtime.FloatDecodeTest"])
        self.assertEqual([{"argv": ["cabal", "run", "exe:thc-fixtures", "--offline", "--", "float-decode"]}], group["commands"])
        self.assertEqual({"build/float-decode"}, set(group["outputs"]))
        self.assertEqual({"t/haskell-fixtures/FloatDecodeFixtures.hs", "t/haskell-fixtures/FixtureSupport.hs",
                          "t/haskell-fixtures/Main.hs", "thc.cabal", "t/fixtures/compiler/FloatDecodeAudit.hs",
                          "t/fixtures/compiler/FloatDecodeNative.hs", "bin/export-boot.py", "t/fixtures/core/FloatDecode.hs"} | fast_fixtures.fast_inputs.BIGNAT_SOURCES, set(group["sources"]))
        self.assertTrue(all((project / path).is_file() for path in group["sources"]))
        self.assertIn('"$fixture_bin" float-decode', (project / "bin/prepare-tests.sh").read_text().splitlines())
        self.assertIn('"float-decode/commands/**"', (project / "build.gradle").read_text())
        policy = json.loads((project / ".github/scripts/fast-tests.json").read_text())
        self.assertEqual(["thc.runtime.FloatDecodeTest"], policy["owners"]["t/fixtures/core/FloatDecode.hs"]["junit"])

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
        for item in cache.BIGNAT_SOURCES:
            path = self.root / item
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text("pinned original\n")
        group = {"outputs": ["build/float-decode"], "sources": sorted(cache.BIGNAT_SOURCES)}
        self.assertEqual(cache.FLOAT_DECODE_OUTPUTS, set(fast_fixtures._output_hashes(self.root, group)))
        with mock.patch.object(fast_fixtures, "COMMON_SOURCES", ()):
            original_key = fast_fixtures.cache_key(self.root, "float-decode", group, {})
            for item in sorted(cache.BIGNAT_SOURCES):
                path = self.root / item
                original = path.read_bytes()
                path.write_text("changed original\n")
                self.assertNotEqual(original_key, fast_fixtures.cache_key(self.root, "float-decode", group, {}))
                path.unlink()
                with self.assertRaisesRegex(RuntimeError, "Missing fixture source"):
                    fast_fixtures.cache_key(self.root, "float-decode", group, {})
                path.write_bytes(original)

    def test_scalar_bitcasts_use_haskell_producer_and_keep_native_inputs(self):
        project = Path(__file__).resolve().parents[2]
        manifest, owners = fast_fixtures._manifest(project)
        group = manifest["groups"]["scalar-bitcasts"]
        self.assertEqual("scalar-bitcasts", owners["thc.runtime.ScalarBitCastTest"])
        self.assertEqual([{"argv": ["cabal", "run", "exe:thc-fixtures", "--offline", "--", "scalar-bitcasts"]}], group["commands"])
        self.assertEqual(["build/scalar-bitcasts"], group["outputs"])
        self.assertEqual({"t/haskell-fixtures/ScalarBitCastFixtures.hs", "t/haskell-fixtures/FixtureSupport.hs",
                          "t/haskell-fixtures/Main.hs", "thc.cabal", "t/fixtures/compiler/ScalarBitCastAudit.hs",
                          "t/fixtures/compiler/ScalarBitCastNative.hs"}, set(group["sources"]))
        self.assertTrue(all((project / name).is_file() for name in group["sources"]))
        self.assertIn('"$fixture_bin" scalar-bitcasts', (project / "bin/prepare-tests.sh").read_text().splitlines())

    def test_address_array_copies_use_haskell_native_producer_and_full_cache_identity(self):
        project = Path(__file__).resolve().parents[2]
        manifest, owners = fast_fixtures._manifest(project)
        group = manifest["groups"]["address-array-copy"]
        self.assertEqual("address-array-copy", owners["thc.runtime.AddressArrayCopyTest"])
        self.assertEqual([{"argv": ["cabal", "run", "exe:thc-fixtures", "--offline", "--", "address-array-copy"]}], group["commands"])
        self.assertEqual(["build/address-array-copy"], group["outputs"])
        self.assertEqual({"t/haskell-fixtures/AddressArrayCopyFixtures.hs", "t/haskell-fixtures/FixtureSupport.hs",
                          "t/haskell-fixtures/Main.hs", "thc.cabal", "t/fixtures/compiler/AddressArrayCopyAudit.hs",
                          "t/fixtures/compiler/AddressArrayCopyNative.hs"}, set(group["sources"]))
        self.assertTrue(all((project / name).is_file() for name in group["sources"]))
        self.assertIn('"$fixture_bin" address-array-copy', (project / "bin/prepare-tests.sh").read_text().splitlines())
        self.assertEqual(fast_fixtures.FULL_PREPARATION_PLAN, fast_fixtures._preparation_plan(project))
        self.assertIn("build/address-array-copy", fast_fixtures.FULL_OUTPUT_ROOTS)
        self.assertIn("address-array-copy", fast_fixtures.fast_inputs.MANIFEST_DIRS)
        self.assertIn('"address-array-copy/**/*.json"', (project / "build.gradle").read_text())
        self.assertIn("build/address-array-copy/", (project / ".github/workflows/build.yml").read_text())

    def test_original_stack_has_portable_focused_and_full_preparation(self):
        project = Path(__file__).resolve().parents[2]
        manifest, owners = fast_fixtures._manifest(project)
        group = manifest["groups"]["original-stack"]
        self.assertEqual("original-stack", owners["thc.runtime.OriginalStackConsumerProofTest"])
        self.assertEqual([{"argv": ["cabal", "run", "exe:thc-fixtures", "--offline", "--", "original-stack"]}], group["commands"])
        self.assertEqual(["build/original-stack"], group["outputs"])
        self.assertTrue(all((project / name).is_file() for name in group["sources"]))
        self.assertIn('"$fixture_bin" original-stack', (project / "bin/prepare-tests.sh").read_text().splitlines())
        self.assertIn("build/original-stack", fast_fixtures.FULL_OUTPUT_ROOTS)
        self.assertIn("build/original-stack/manifest.json", fast_fixtures.FULL_REQUIRED)
        gradle = (project / "build.gradle").read_text()
        self.assertIn('"original-stack/manifest.json"', gradle)
        self.assertIn('"original-stack/run-*/**"', gradle)
        self.assertNotIn('"original-stack/proof.json"', gradle)
        for name in ("thc.cabal", "nih/pinned/ghc-9.14.1/libraries/ghc-internal/LICENSE",
                     "nih/pinned/ghc-9.14.1/libraries/ghc-internal/src/GHC/Internal/InfoProv/Types.hsc",
                     "nih/pinned/ghc-9.14.1/libraries/ghc-internal/src/GHC/Internal/Heap/InfoTable.hsc"):
            self.assertIn('"' + name + '"', gradle)
            self.assertIn(name, group["sources"])
        for name in ("Stack/CloneStack.hs", "Stack/Decode.hs"):
            self.assertIn("nih/pinned/ghc-9.14.1/libraries/ghc-internal/src/GHC/Internal/" + name, group["sources"])

    def test_compiled_thunk_retention_prepares_both_native_families(self):
        project = Path(__file__).resolve().parents[2]
        manifest, owners = fast_fixtures._manifest(project)
        group = manifest["groups"]["compiled-thunk-retention"]
        self.assertEqual({"thc.runtime.CompiledThunkRetentionTest", "thc.runtime.BoxedArrayTest",
                          "thc.runtime.FloatingPrimitiveTest"}, set(group["junit"]))
        self.assertEqual({"compiled-thunk-retention"}, {owners[name] for name in group["junit"]})
        self.assertEqual([{"argv": ["python3", "bin/prepare-boxed-arrays.py"]},
                          {"argv": ["python3", "bin/prepare-floating-audit.py"]}], group["commands"])
        self.assertEqual(["build/boxed-arrays", "build/floating"], group["outputs"])
        self.assertEqual({"bin/prepare-boxed-arrays.py", "bin/prepare-floating-audit.py",
                          "t/fixtures/compiler/BoxedArrayAudit.hs",
                          "t/fixtures/compiler/FloatingAudit.hs",
                          "t/fixtures/compiler/FloatingAuditNative.hs"}, set(group["sources"]))
        self.assertTrue(all((project / name).is_file() for name in group["sources"]))
        full = (project / "bin/prepare-tests.sh").read_text().splitlines()
        for command in group["commands"]:
            self.assertIn(" ".join(command["argv"]), full)
        self.assertLessEqual(set(group["outputs"]), fast_fixtures.FULL_OUTPUT_ROOTS)
        self.assertLessEqual({"build/boxed-arrays/manifest.json", "build/floating/checks.json"},
                             fast_fixtures.FULL_REQUIRED)
        gradle = (project / "build.gradle").read_text()
        for pattern in ("boxed-arrays/**/*.json", "boxed-arrays/*.tsv", "floating/core/**/*.cbd",
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
        command = ["bin/prepare-original-stdio.sh", "--require-supported"]
        self.assertEqual("original-stdio", owners["thc.runtime.OriginalStdioNativeTest"])
        self.assertEqual([{"argv": command}], group["commands"])
        self.assertEqual(["build/original-stdio"], group["outputs"])
        self.assertEqual({"t/fixtures/compiler/OriginalStdioAudit.hs",
                          "t/fixtures/compiler/OriginalStdioAuditNative.hs",
                          "bin/prepare-original-stdio.sh", "t/haskell-fixtures/Main.hs",
                          "t/haskell-fixtures/FixtureSupport.hs",
                          "t/haskell-fixtures/OriginalStdioFixtures.hs", "thc.cabal"}, set(group["sources"]))
        self.assertTrue(all((project / name).is_file() for name in group["sources"]))
        self.assertIn('"$fixture_bin" original-stdio --require-supported',
                      (project / "bin/prepare-tests.sh").read_text().splitlines())
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
            self.assertIn(f"cabal run exe:thc-fixtures --offline -- {family}-bytearray --export-only", argv[2])
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
        self.assertEqual(fast_fixtures.FULL_PREPARATION_PLAN, fast_fixtures._preparation_plan(project))

    def test_bytearray_families_use_haskell_producers_and_closed_receipts(self):
        project = Path(__file__).resolve().parents[2]
        manifest, owners = fast_fixtures._manifest(project)
        groups = {"bytearray": "ByteArrayTest", "mutable-bytearrays": "MutableByteArrayTest",
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
        self.assertEqual(fast_fixtures.FULL_PREPARATION_PLAN, fast_fixtures._preparation_plan(project))

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


class FullFixtureReceiptTest(unittest.TestCase):
    prepare = FixturePreparationTest.prepare
    selection = staticmethod(FixturePreparationTest.selection)

    def setUp(self):
        FixturePreparationTest.setUp(self)
        for name in ("build.gradle", "thc.cabal", "cabal.project", "Setup.hs",
                     ".github/scripts/fast_fixtures.py",
                     "bin/prepare-tests.sh"):
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
        def identity(_root):
            return {"source": hashlib.sha256((self.root / "fixtures/alpha.hs").read_bytes()).hexdigest(),
                    "toolchain": self.toolchain.copy()}
        self.patches.enter_context(mock.patch.object(fast_fixtures.fast_inputs, "identity",
                                               side_effect=identity))

    def fake_run(self, name, argv, stdout=None):
        if argv != ["bin/prepare-tests.sh"]:
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

    def test_reviewed_float_interface_scratch_does_not_invalidate_full_receipt(self):
        self.assertEqual(["full"], self.prepare("thc.UnknownTest")["rebuilt"])
        scratch = self.root / "build/float-decode-originals/interfaces"
        scratch.mkdir(parents=True)
        (scratch / "installed.hi").symlink_to(self.root / "not-present")
        self.assertEqual(["full"], self.prepare("thc.UnknownTest")["reused"])
        self.assertEqual(1, self.prepared)

    def test_full_pinned_receipt_retains_required_native_objects(self):
        cache = fast_fixtures.fast_inputs
        name = "build/pinned-addresses/manifest.json"
        artifacts = {}
        for item in cache.PINNED_ADDRESS_OUTPUTS - {name}:
            path = self.root / item
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text("fixture\n")
            artifacts[item] = fast_fixtures._digest(path)
        (self.root / name).write_text(json.dumps({
            "artifactHashes": artifacts, "mode": "full", "strictAccepted": True}))
        with mock.patch.object(fast_fixtures, "FULL_OUTPUT_ROOTS", {"build/pinned-addresses"}), \
             mock.patch.object(fast_fixtures, "FULL_REQUIRED", {name}):
            self.assertEqual(cache.PINNED_ADDRESS_OUTPUTS,
                             set(fast_fixtures._full_output_hashes(self.root)))
            for item in sorted(cache.PINNED_ADDRESS_OUTPUTS):
                if not item.endswith((".hi", ".o")):
                    continue
                path = self.root / item
                original = path.read_bytes()
                path.write_text("corrupt required object\n")
                with self.assertRaises(RuntimeError):
                    fast_fixtures._full_output_hashes(self.root)
                path.unlink()
                with self.assertRaises((OSError, RuntimeError)):
                    fast_fixtures._full_output_hashes(self.root)
                path.write_bytes(original)

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
        generated = self.root / "build/generated/main/java/Generated.java"
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
        (self.root / "bin/prepare-tests.sh").write_text("prepare reviewed fixtures\nmake-new-output\n")
        self.assertEqual(self.prepare("thc.UnknownTest")["rebuilt"], ["full"])
        self.assertFalse((self.root / fast_fixtures.FULL_STAMP).exists())
        (self.root / "bin/prepare-tests.sh").write_text("prepare reviewed fixtures\n")
        self.extra_output = True
        self.assertEqual(self.prepare("thc.UnknownTest")["rebuilt"], ["full"])
        self.assertFalse((self.root / fast_fixtures.FULL_STAMP).exists())
        self.assertEqual(self.prepare("thc.UnknownTest")["rebuilt"], ["full"])
        self.assertEqual(self.prepared, 4)

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
