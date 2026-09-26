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


class FixturePreparationTest(unittest.TestCase):
    def test_stable_names_keep_native_values_and_closed_artifacts(self):
        project = Path(__file__).resolve().parents[2]
        manifest, owners = fast_fixtures._manifest(project)
        cache = fast_fixtures.fast_inputs
        group = manifest['groups']['stable-names']
        self.assertEqual('stable-names', owners['thc.runtime.StableNamesTest'])
        self.assertIn('test/haskell-fixtures/StableNameFixtures.hs', group['sources'])
        self.assertIn('"$fixture_bin" stable-names', (project / 'scripts/prepare-tests.sh').read_text().splitlines())
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
        self.assertFalse(cache.allowed_payload('build/stable-names/unknown.json', {}))
        (self.root / 'build/stable-names/commands/native-run.stdout').write_text('mutated')
        with self.assertRaises(RuntimeError): fast_fixtures._output_hashes(self.root, group)

    def test_closure_inspection_fixture_ownership_and_closed_inventory(self):
        project = Path(__file__).resolve().parents[2]
        manifest, owners = fast_fixtures._manifest(project)
        self.assertEqual('closure-inspection', owners['thc.runtime.ClosureInspectionTest'])
        self.assertEqual([{'argv': ['cabal', 'run', 'exe:thc-fixtures', '--offline', '--', 'closure-inspection']}],
                         manifest['groups']['closure-inspection']['commands'])
        self.assertIn('"$fixture_bin" closure-inspection', (project / 'scripts/prepare-tests.sh').read_text().splitlines())
        self.assertEqual(13, len(fast_fixtures.fast_inputs.CLOSURE_INSPECTION_OUTPUTS))
        self.assertTrue(fast_fixtures.fast_inputs.CLOSURE_INSPECTION_OUTPUTS <= fast_fixtures.FULL_REQUIRED)
        for name in fast_fixtures.fast_inputs.CLOSURE_INSPECTION_OUTPUTS:
            self.assertTrue(fast_fixtures.fast_inputs.allowed_payload(name, {}), name)
        self.assertFalse(fast_fixtures.fast_inputs.allowed_payload('build/closure-inspection/native/Other.o', {}))

    def test_hint_trace_has_native_fixture_and_complete_cache_scope(self):
        project = Path(__file__).resolve().parents[2]
        manifest, owners = fast_fixtures._manifest(project)
        self.assertEqual('hint-trace', owners['thc.runtime.HintTraceTest'])
        self.assertEqual([{'argv': ['cabal', 'run', 'exe:thc-fixtures', '--offline', '--', 'hint-trace']}],
                         manifest['groups']['hint-trace']['commands'])
        self.assertIn('"$fixture_bin" hint-trace', (project / 'scripts/prepare-tests.sh').read_text().splitlines())
        self.assertIn('build/hint-trace/manifest.json', fast_fixtures.fast_inputs.REQUIRED)
        self.assertEqual(18, len([path for path in fast_fixtures.FULL_REQUIRED if path.startswith('build/hint-trace/')]))
        self.assertEqual(fast_fixtures.FULL_PREPARATION_PLAN, fast_fixtures._preparation_plan(project))

    def test_stm_keeps_original_exception_proof_in_explicit_fail_closed_full_core_gate(self):
        project = Path(__file__).resolve().parents[2]
        manifest, owners = fast_fixtures._manifest(project)
        self.assertIn('thc.runtime.ManagedSTMTest', manifest['fixtureFreeJunit'])
        self.assertNotIn('thc.runtime.STMFullCoreTest', owners)
        self.assertNotIn('"$fixture_bin" stm', (project / 'scripts/prepare-tests.sh').read_text())
        self.assertNotIn('build/stm/manifest.json', fast_fixtures.FULL_REQUIRED)
        self.assertTrue((project / 'src/fullCoreTest/kotlin/thc/runtime/STMFullCoreTest.kt').is_file())
        build = (project / 'build.gradle.kts').read_text()
        self.assertIn('tasks.register<Test>("stmFullCoreTest")', build)
        self.assertIn('includeTestsMatching("thc.runtime.STMFullCoreTest")', build)
        self.assertIn('check(file("build/stm/manifest.json").isFile)', build)
        self.assertEqual(fast_fixtures.FULL_PREPARATION_PLAN, fast_fixtures._preparation_plan(project))

    def test_boxed_cas_owns_native_inputs_and_only_declared_artifacts(self):
        project = Path(__file__).resolve().parents[2]
        manifest, owners = fast_fixtures._manifest(project)
        group = manifest['groups']['boxed-cas']
        cache = fast_fixtures.fast_inputs
        self.assertEqual('boxed-cas', owners['thc.runtime.BoxedCasTest'])
        self.assertEqual([{'argv': ['cabal', 'run', 'exe:thc-fixtures', '--offline', '--', 'boxed-cas']}], group['commands'])
        self.assertIn('examples/THC/BoxedCasCounter.hs', group['sources'])
        self.assertIn('"$fixture_bin" boxed-cas', (project / 'scripts/prepare-tests.sh').read_text().splitlines())
        self.assertIn('build/boxed-cas/manifest.json', fast_fixtures.FULL_REQUIRED)
        for suffix in cache.BOXED_CAS_FILES:
            self.assertTrue(cache.allowed_payload('build/boxed-cas/run-1/' + suffix, {}))
        for suffix in ('logs/secret.stdout', 'native/unowned', 'pre-core/Fake.json'):
            self.assertFalse(cache.allowed_payload('build/boxed-cas/run-1/' + suffix, {}))
        self.assertEqual(fast_fixtures.FULL_PREPARATION_PLAN, fast_fixtures._preparation_plan(project))

    def test_scalar_memory_owns_closed_native_outputs_and_rejects_stale_receipts(self):
        project = Path(__file__).resolve().parents[2]
        manifest, owners = fast_fixtures._manifest(project)
        cache = fast_fixtures.fast_inputs
        group = manifest['groups']['scalar-memory-utilities']
        self.assertEqual('scalar-memory-utilities', owners['thc.runtime.ScalarMemoryUtilitiesTest'])
        self.assertEqual([{'argv': ['cabal', 'run', 'exe:thc-fixtures', '--offline', '--', 'scalar-memory-utilities']}], group['commands'])
        self.assertIn('"$fixture_bin" scalar-memory-utilities', (project / 'scripts/prepare-tests.sh').read_text().splitlines())
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
        self.assertIn('examples/GhcBCO.hs', group['sources'])
        self.assertIn('"$fixture_bin" ghc-bco', (project / 'scripts/prepare-tests.sh').read_text().splitlines())
        self.assertEqual(82, len(cache.BCO_OUTPUTS))
        self.assertTrue(cache.BCO_OUTPUTS <= fast_fixtures.FULL_REQUIRED)
        name = 'build/ghc-bco/manifest.json'
        artifacts = {}
        for item in cache.BCO_OUTPUTS - {name}:
            path = self.root / item; path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text('fixture\n'); artifacts[item] = fast_fixtures._digest(path)
        receipt = dict(schema=1, ghc='9.14.1', entries=list(cache.BCO_ENTRIES), stages=['pre', 'post'],
                       arguments=[-2, 0, 7], native=[0] * 24, artifactHashes=artifacts)
        (self.root / name).write_text(json.dumps(receipt))
        self.assertEqual(cache.BCO_OUTPUTS, set(fast_fixtures._output_hashes(self.root, group)))
        for bad in (dict(receipt, schema=True), dict(receipt, ghc='9.12.2'), dict(receipt, entries=[]),
                    dict(receipt, native=[0] * 23), dict(receipt, native=[False] * 24),
                    dict(receipt, stages=['pre']), dict(receipt, artifactHashes={})):
            with self.assertRaises(cache.CacheMiss): cache.bco_artifact_hashes(bad)
        self.assertFalse(cache.allowed_payload('build/ghc-bco/unknown.json', {}))
        (self.root / 'build/ghc-bco/commands/native-run.stdout').write_text('mutated')
        with self.assertRaises(RuntimeError): fast_fixtures._output_hashes(self.root, group)

    def test_delimited_continuations_retain_closed_command_and_native_evidence(self):
        project = Path(__file__).resolve().parents[2]
        manifest, owners = fast_fixtures._manifest(project)
        cache = fast_fixtures.fast_inputs
        group = manifest['groups']['delimited-continuations']
        self.assertEqual('delimited-continuations', owners['thc.runtime.DelimitedContinuationsTest'])
        self.assertIn('examples/DelimitedContinuations.hs', group['sources'])
        self.assertEqual([{'argv': ['cabal', 'run', 'exe:thc-fixtures', '--offline', '--', 'delimited-continuations']}], group['commands'])
        self.assertIn('"$fixture_bin" delimited-continuations', (project / 'scripts/prepare-tests.sh').read_text().splitlines())
        self.assertEqual(154, len(cache.DELIMITED_OUTPUTS))
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
        self.assertIn('"$fixture_bin" simd-address-families', (project / 'scripts/prepare-tests.sh').read_text().splitlines())
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
        self.assertIn('examples/ThreadInventory.hs', group['sources'])
        self.assertEqual([{'argv': ['cabal', 'run', 'exe:thc-fixtures', '--offline', '--', 'thread-inventory']}], group['commands'])
        self.assertIn('"$fixture_bin" thread-inventory', (project / 'scripts/prepare-tests.sh').read_text().splitlines())
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
        (self.root / 'build/thread-inventory/pre/core/ThreadInventory.json').write_text('mutated')
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
            self.assertTrue(cache.allowed_payload(item, {}))
        receipt = dict(schema=1, ghc='9.14.1', entries=list(cache.THREAD_SCHEDULING_ENTRIES), stages=['pre', 'post'],
                       nativeRTS='non-threaded: direct delay# uses the POSIX I/O manager', artifactHashes=artifacts)
        (self.root / name).write_text(json.dumps(receipt))
        self.assertEqual(cache.THREAD_SCHEDULING_OUTPUTS, set(fast_fixtures._output_hashes(self.root, group)))
        for bad in (dict(receipt, schema=True), dict(receipt, entries=[]), dict(receipt, stages=['pre']),
                    dict(receipt, nativeRTS='threaded'), dict(receipt, artifactHashes={})):
            with self.assertRaises(cache.CacheMiss): cache.thread_scheduling_artifact_hashes(bad)
        self.assertFalse(cache.allowed_payload('build/thread-scheduling/native/oracle', {}))

    def test_integer_completion_has_owned_native_inputs_and_cache_registration(self):
        project = Path(__file__).resolve().parents[2]
        manifest, owners = fast_fixtures._manifest(project)
        group = manifest["groups"]["integer-completion"]
        self.assertEqual("integer-completion", owners["thc.runtime.IntegerCompletionTest"])
        self.assertEqual([{"argv": ["cabal", "run", "exe:thc-fixtures", "--offline", "--", "integer-completion"]}], group["commands"])
        self.assertEqual(["build/integer-completion"], group["outputs"])
        self.assertEqual({"test/haskell-fixtures/Main.hs", "test/haskell-fixtures/IntegerCompletionFixtures.hs",
                          "compiler/test-fixtures/IntegerCompletionAudit.hs"}, set(group["sources"]))
        self.assertIn('"$fixture_bin" integer-completion', (project / "scripts/prepare-tests.sh").read_text().splitlines())
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
        self.assertIn('compiler/test-fixtures/AtomicIntArrayAudit.hs', group['sources'])
        self.assertIn('test/haskell-fixtures/AtomicIntArrayFixtures.hs', group['sources'])
        self.assertIn('"$fixture_bin" atomic-int-arrays', (project / 'scripts/prepare-tests.sh').read_text().splitlines())
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
        for path in ('compiler/test-fixtures/Simd128AddressAudit.hs', 'compiler/test-fixtures/Simd128AddressNative.hs',
                     'test/haskell-fixtures/Simd128AddressFixtures.hs', 'scripts/core_vector_memory.py',
                     'scripts/core_vectors.py', 'scripts/simd-families.json'):
            self.assertIn(path, sources)
        self.assertIn('build/simd128-addresses/manifest.json', fast_fixtures.FULL_REQUIRED)
        self.assertIn('build/simd128-addresses', fast_fixtures.FULL_OUTPUT_ROOTS)
        self.assertEqual(36, len(fast_fixtures.fast_inputs.SIMD128_ARRAY_ENTRIES))
        self.assertIn('"$fixture_bin" simd128-addresses', (project / 'scripts/prepare-tests.sh').read_text().splitlines())
        for path in fast_fixtures.fast_inputs.SIMD128_ADDRESS_OUTPUTS:
            self.assertTrue(fast_fixtures.fast_inputs.allowed_payload(path, {}), path)
        for path in ('build/simd128-addresses/native/rogue', 'build/simd128-addresses/commands/fake.stdout',
                     'build/simd128-addresses/arbitrary.json'):
            self.assertFalse(fast_fixtures.fast_inputs.allowed_payload(path, {}), path)

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
        for path in ('compiler/test-fixtures/Simd128ArrayAudit.hs', 'compiler/test-fixtures/Simd128ArrayNative.hs',
                     'test/haskell-fixtures/Simd128ArrayFixtures.hs', 'scripts/core_vector_memory.py',
                     'scripts/core_vectors.py', 'scripts/simd-families.json'):
            self.assertIn(path, sources)
        self.assertIn('build/simd128-arrays/manifest.json', fast_fixtures.FULL_REQUIRED)
        self.assertIn('build/simd128-arrays', fast_fixtures.FULL_OUTPUT_ROOTS)
        self.assertEqual(36, len(fast_fixtures.fast_inputs.SIMD128_ARRAY_ENTRIES))
        self.assertIn('"$fixture_bin" simd128-arrays', (project / 'scripts/prepare-tests.sh').read_text().splitlines())
        for path in fast_fixtures.fast_inputs.SIMD128_ARRAY_OUTPUTS:
            self.assertTrue(fast_fixtures.fast_inputs.allowed_payload(path, {}), path)
        for path in ('build/simd128-arrays/native/rogue', 'build/simd128-arrays/commands/fake.stdout',
                     'build/simd128-arrays/arbitrary.json'):
            self.assertFalse(fast_fixtures.fast_inputs.allowed_payload(path, {}), path)

    def test_thread_label_uses_its_native_core_fixture_and_baseline_registration(self):
        project = Path(__file__).resolve().parents[2]
        manifest, owners = fast_fixtures._manifest(project)
        self.assertEqual('thread-label', owners['thc.runtime.ThreadLabelNativeTest'])
        self.assertIn('thc.runtime.GuestThreadLabelTest', manifest['fixtureFreeJunit'])
        self.assertEqual([{'argv': ['cabal', 'run', 'exe:thc-fixtures', '--offline', '--', 'thread-label']}],
                         manifest['groups']['thread-label']['commands'])
        self.assertIn('"$fixture_bin" thread-label', (project / 'scripts/prepare-tests.sh').read_text().splitlines())
        self.assertIn('build/thread-label/manifest.json', fast_fixtures.fast_inputs.REQUIRED)
        self.assertEqual(14, len([p for p in fast_fixtures.FULL_REQUIRED if p.startswith('build/thread-label/')]))
        self.assertEqual(fast_fixtures.FULL_PREPARATION_PLAN, fast_fixtures._preparation_plan(project))

    def test_original_tcsetattr_registration_and_closed_native_receipt(self):
        project = Path(__file__).resolve().parents[2]
        manifest, owners = fast_fixtures._manifest(project)
        group = manifest['groups']['original-tcsetattr']
        self.assertEqual('original-tcsetattr', owners['thc.runtime.OriginalTcsetattrTest'])
        self.assertEqual([{'argv': ['cabal', 'run', 'exe:thc-fixtures', '--offline', '--', 'original-tcsetattr']}], group['commands'])
        self.assertIn('"$fixture_bin" original-tcsetattr', (project / 'scripts/prepare-tests.sh').read_text().splitlines())
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
            artifact = self.root / 'build/original-tcsetattr/pre/core/OriginalTcsetattrAudit.json'
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
        self.assertIn('"$fixture_bin" original-tcgetattr', (project / 'scripts/prepare-tests.sh').read_text().splitlines())
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
            artifact = self.root / 'build/original-tcgetattr/pre/core/OriginalTcgetattrAudit.json'
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
        self.assertIn('"$fixture_bin" original-sigprocmask', (project / 'scripts/prepare-tests.sh').read_text().splitlines())
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
            artifact = self.root / 'build/original-sigprocmask/pre/core/OriginalSigprocmaskAudit.json'
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
        self.assertIn('"$fixture_bin" original-sigset', (project / 'scripts/prepare-tests.sh').read_text().splitlines())
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
            artifact = self.root / 'build/original-sigset/pre/core/OriginalSigsetAudit.json'
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
            self.assertTrue((project / f'src/test/kotlin/thc/runtime/{name}.kt').is_file())
        self.assertNotIn('build/original-stack-decoder', fast_fixtures.FULL_OUTPUT_ROOTS)
        self.assertNotIn('build/original-stack-decoder/manifest.json', fast_fixtures.FULL_REQUIRED)
        self.assertNotIn('"$fixture_bin" original-stack-decoder', (project / 'scripts/prepare-tests.sh').read_text())
        self.assertEqual(fast_fixtures.FULL_PREPARATION_PLAN, fast_fixtures._preparation_plan(project))
        self.assertFalse((project / 'src/test/kotlin/thc/runtime/OriginalStackDecoderTest.kt').exists())
        self.assertTrue((project / 'src/fullCoreTest/kotlin/thc/runtime/OriginalStackDecoderTest.kt').is_file())

    def test_explicit_weak_fixture_registration_preserves_native_and_strict_inputs(self):
        project = Path(__file__).resolve().parents[2]
        manifest, owners = fast_fixtures._manifest(project)
        group = manifest['groups']['weak-explicit']
        self.assertEqual('weak-explicit', owners['thc.runtime.ManagedWeakTest'])
        self.assertEqual([{'argv': ['cabal', 'run', 'exe:thc-fixtures', '--offline', '--',
                                   'weak-explicit']}], group['commands'])
        self.assertEqual(['build/weak-explicit'], group['outputs'])
        self.assertTrue(all((project / path).is_file() for path in group['sources']))
        self.assertIn('"$fixture_bin" weak-explicit', (project / 'scripts/prepare-tests.sh').read_text())
        self.assertEqual(fast_fixtures.FULL_PREPARATION_PLAN, fast_fixtures._preparation_plan(project))
        self.assertIn('build/weak-explicit', fast_fixtures.FULL_OUTPUT_ROOTS)
        self.assertIn('build/weak-explicit/manifest.json', fast_fixtures.FULL_REQUIRED)
        gradle = (project / 'build.gradle.kts').read_text()
        for pattern in ('**/*.json', 'oracle.tsv', 'NativeWeak.hs'):
            self.assertIn('"weak-explicit/' + pattern + '"', gradle)
        for suffix in ('manifest.json', 'oracle.tsv', 'NativeWeak.hs',
                       'pre/audit.json', 'post/audit.json',
                       'pre/core/WeakAudit.json', 'post/core/WeakAudit.json',
                       'pre/core/THC.InterfaceClosure.json', 'post/core/THC.InterfaceClosure.json'):
            self.assertTrue(fast_fixtures.fast_inputs.allowed_payload('build/weak-explicit/' + suffix, {}))
        self.assertFalse(fast_fixtures.fast_inputs.allowed_payload('build/weak-explicit/result.xml', {}))

    def test_libdw_unavailable_native_fixture_registration(self):
        project = Path(__file__).resolve().parents[2]
        manifest, owners = fast_fixtures._manifest(project)
        group = manifest['groups']['libdw-unavailable']
        self.assertEqual('libdw-unavailable', owners['thc.runtime.LibdwUnavailableTest'])
        self.assertEqual([{'argv': ['cabal', 'run', 'exe:thc-fixtures', '--offline', '--',
                                   'libdw-unavailable']}], group['commands'])
        self.assertEqual(['build/libdw-unavailable/manifest.json', 'build/libdw-unavailable/oracle.json',
                          'build/libdw-unavailable/foreign-labels.json'], group['outputs'])
        self.assertTrue(all((project / path).is_file() for path in group['sources']))
        self.assertIn('"$fixture_bin" libdw-unavailable', (project / 'scripts/prepare-tests.sh').read_text())
        self.assertEqual(fast_fixtures.FULL_PREPARATION_PLAN, fast_fixtures._preparation_plan(project))
        self.assertIn('build/libdw-unavailable/manifest.json', fast_fixtures.FULL_REQUIRED)
        for suffix in ('manifest.json', 'oracle.json', 'foreign-labels.json'):
            self.assertTrue(fast_fixtures.fast_inputs.allowed_payload('build/libdw-unavailable/' + suffix, {}))
            self.assertIn('"libdw-unavailable/' + suffix + '"', (project / 'build.gradle.kts').read_text())
        self.assertFalse(fast_fixtures.fast_inputs.allowed_payload('build/libdw-unavailable/native/oracle', {}))
        self.assertIn('build/libdw-unavailable/logs/', (project / '.github/workflows/build.yml').read_text())

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
        self.assertIn('compiler/test-fixtures/NativeMallocNative.hs', group['sources'])
        self.assertIn('src/test/resources/core/original-malloc-descriptors.json', group['sources'])
        self.assertTrue(all((project / path).is_file() for path in group['sources']))
        self.assertIn('"$fixture_bin" native-addresses', (project / 'scripts/prepare-tests.sh').read_text())
        self.assertEqual(fast_fixtures.FULL_PREPARATION_PLAN, fast_fixtures._preparation_plan(project))
        self.assertIn('build/native-addresses/manifest.json', fast_fixtures.FULL_REQUIRED)
        self.assertIn('build/native-malloc/manifest.json', fast_fixtures.FULL_REQUIRED)
        self.assertIn('build/native-malloc/oracle.txt', fast_fixtures.FULL_REQUIRED)
        for suffix in ('manifest.json', 'oracle.json'):
            self.assertTrue(fast_fixtures.fast_inputs.allowed_payload('build/native-addresses/' + suffix, {}))
            self.assertIn('"native-addresses/' + suffix + '"', (project / 'build.gradle.kts').read_text())
        self.assertFalse(fast_fixtures.fast_inputs.allowed_payload('build/native-addresses/native/oracle', {}))
        for suffix in ('manifest.json', 'oracle.txt'):
            path = 'build/native-malloc/' + suffix
            self.assertTrue(fast_fixtures.fast_inputs.allowed_payload(path, {}))
            self.assertIn('"native-malloc/' + suffix + '"', (project / 'build.gradle.kts').read_text())
            self.assertIn(path, (project / '.github/workflows/build.yml').read_text())
        self.assertFalse(fast_fixtures.fast_inputs.allowed_payload('build/native-malloc/native/oracle', {}))

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
        script = (project / 'scripts/prepare-tests.sh').read_text()
        self.assertIn('case "$(uname -s)-$(uname -m)" in\n'
                      '  Linux-x86_64) "$fixture_bin" original-gmp --require-supported ;;\nesac', script)
        self.assertEqual(fast_fixtures.FULL_PREPARATION_PLAN, fast_fixtures._preparation_plan(project))
        self.assertIn('build/original-gmp', fast_fixtures.FULL_OUTPUT_ROOTS)
        self.assertEqual(fast_fixtures.fast_inputs.GMP_NATIVE_HOST,
                         'build/original-gmp/manifest.json' in fast_fixtures.FULL_REQUIRED)
        gradle = (project / 'build.gradle.kts').read_text()
        for name in ('**/*.json', 'native/oracle', 'exposed-ghc-internal.conf', 'logs/*.stdout', 'logs/*.stderr'):
            self.assertIn('"original-gmp/' + name + '"', gradle)
        self.assertIn('build/original-gmp/', (project / '.github/workflows/build.yml').read_text())
        for workflow in ('build.yml', 'fast.yml'):
            source = (project / '.github/workflows' / workflow).read_text()
            self.assertIn('install --yes clang-18 llvm-18 libgmp-dev', source)
            self.assertIn('echo /usr/lib/llvm-18/bin >> "$GITHUB_PATH"', source)
            self.assertIn('for tool in clang llc opt; do', source)
        policy = json.loads((project / '.github/scripts/fast-tests.json').read_text())
        for source in ('compiler/test-fixtures/OriginalGmpAudit.hs',
                       'compiler/test-fixtures/OriginalGmpNative.hs',
                       'test/haskell-fixtures/OriginalGmpFixtures.hs', 'test/haskell-fixtures/Main.hs'):
            self.assertIn('thc.runtime.OriginalGmpTest', policy['owners'][source]['junit'])

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
        self.assertIn('"$fixture_bin" original-fcntl', (project / 'scripts/prepare-tests.sh').read_text().splitlines())
        self.assertIn('build/original-fcntl', fast_fixtures.FULL_OUTPUT_ROOTS)
        name = 'build/original-fcntl/manifest.json'
        self.assertEqual(113, len(cache.ORIGINAL_FCNTL_OUTPUTS))
        for item in cache.ORIGINAL_FCNTL_OUTPUTS:
            self.assertTrue(cache.allowed_payload(item, {}), item)
        for item in ('native/private-file', 'native/Main.o', 'pre/core/Other.json', 'logs/unknown.stdout'):
            self.assertFalse(cache.allowed_payload('build/original-fcntl/' + item, {}), item)
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
            for missing in ('native/oracle', 'pre/core/OriginalFcntlAudit.json',
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

    def test_original_termios_registration_receipt_and_stale_artifact(self):
        project = Path(__file__).resolve().parents[2]
        manifest, owners = fast_fixtures._manifest(project)
        group = manifest['groups']['original-termios']
        self.assertEqual('original-termios', owners['thc.runtime.OriginalTermiosTest'])
        self.assertEqual('original-termios', owners['thc.runtime.OriginalSavedTermiosTest'])
        self.assertIn('thc.runtime.TermiosAbiTest', manifest['fixtureFreeJunit'])
        self.assertIn('thc.runtime.SavedTermiosTest', manifest['fixtureFreeJunit'])
        for fixture in ('Audit', 'Native'):
            self.assertIn(f'compiler/test-fixtures/OriginalSavedTermios{fixture}.hs', group['sources'])
        self.assertEqual([{'argv': ['cabal', 'run', 'exe:thc-fixtures', '--offline', '--', 'original-termios']}], group['commands'])
        self.assertIn('"$fixture_bin" original-termios', (project / 'scripts/prepare-tests.sh').read_text().splitlines())
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
            for missing in ('saved/native/oracle', 'saved/pre/core/OriginalSavedTermiosAudit.json',
                            'saved/post/originalSetSavedTermios.audit.json', 'logs/saved-native-run.stdout'):
                incomplete = dict(artifacts); del incomplete['build/original-termios/' + missing]
                with self.assertRaises(cache.CacheMiss):
                    cache.termios_artifact_hashes(dict(receipt, artifactHashes=incomplete))
            for relative in ('pre/core/OriginalTermiosAudit.json', 'saved/pre/core/OriginalSavedTermiosAudit.json',
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
        for name in ('OriginalPosixStatTest', 'PosixStatAbiTest', 'OriginalFstatTest'):
            self.assertEqual('original-posix-stat', owners['thc.runtime.' + name])
        self.assertEqual([{'argv': ['cabal', 'run', 'exe:thc-fixtures', '--offline', '--', 'original-posix-stat']}], group['commands'])
        self.assertIn('"$fixture_bin" original-posix-stat', (project / 'scripts/prepare-tests.sh').read_text())
        self.assertIn('build/original-posix-stat/manifest.json', fast_fixtures.FULL_REQUIRED)
        self.assertIn('build/original-posix-stat', fast_fixtures.FULL_OUTPUT_ROOTS)
        cache = fast_fixtures.fast_inputs
        self.assertEqual(90, len(cache.ORIGINAL_POSIX_STAT_OUTPUTS))
        for path in cache.ORIGINAL_POSIX_STAT_OUTPUTS:
            self.assertTrue(cache.allowed_payload(path, {}), path)
        for suffix in ('native/unknown', 'logs/unknown.stdout', 'pre/core/Other.json', 'attempt-0/oracle.json'):
            self.assertFalse(cache.allowed_payload('build/original-posix-stat/' + suffix, {}), suffix)
        for suffix in ('../original-stdio/manifest.json', 'native/../../outside'):
            with self.assertRaises(cache.CacheMiss):
                cache.allowed_payload('build/original-posix-stat/' + suffix, {})
        self.assertEqual(0o755, cache.safe_mode(0o755, 'build/original-posix-stat/native/oracle'))
        with self.assertRaises(cache.CacheMiss):
            cache.safe_mode(0o755, 'build/original-posix-stat/oracle.json')
        producer = (project / 'test/haskell-fixtures/OriginalPosixStatFixtures.hs').read_text()
        self.assertIn('os /= "linux"', producer)
        self.assertIn('"supported" .= False', producer)

    def test_simd_smoke_recipe_tracks_generated_haskell_inputs(self):
        project = Path(__file__).resolve().parents[2]
        manifest, owners = fast_fixtures._manifest(project)
        group = manifest['groups']['simd-capability-smoke']
        self.assertEqual('simd-capability-smoke', owners['thc.runtime.SimdCapabilitySmokeTest'])
        sources = fast_fixtures.fast_inputs.SIMD_SMOKE_SOURCES
        self.assertEqual({'build/simd-capability-smoke'} | sources, set(group['outputs']))
        self.assertLessEqual(sources, fast_fixtures.FULL_REQUIRED)
        self.assertNotIn('scripts/prepare-simd-families.py', group['sources'])
        self.assertNotIn('scripts/simd_family_model.py', group['sources'])

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
                      (project / 'scripts/prepare-tests.sh').read_text().splitlines())
        self.assertEqual(fast_fixtures.FULL_PREPARATION_PLAN, fast_fixtures._preparation_plan(project))
        self.assertIn('build/original-fd-ready', fast_fixtures.FULL_OUTPUT_ROOTS)
        self.assertTrue(fast_fixtures.fast_inputs.ORIGINAL_FD_READY_OUTPUTS <= fast_fixtures.FULL_REQUIRED)
        self.assertIn('"original-fd-ready/**/*.json"', (project / 'build.gradle.kts').read_text())
        for path in fast_fixtures.fast_inputs.ORIGINAL_FD_READY_OUTPUTS:
            self.assertTrue(fast_fixtures.fast_inputs.allowed_payload(path, {}), path)
        for name in ('OriginalFD.json', 'native/unreviewed', 'logs/extra.stdout',
                     'negative/extra.json', 'native/OriginalFdReadyAudit.hi', 'test-results/pass.xml'):
            self.assertFalse(fast_fixtures.fast_inputs.allowed_payload('build/original-fd-ready/' + name, {}))

    def test_rts_diagnostics_exact_fixture_registration(self):
        project = Path(__file__).resolve().parents[2]
        manifest, owners = fast_fixtures._manifest(project)
        group = manifest['groups']['rts-diagnostics']
        self.assertEqual('rts-diagnostics', owners['thc.runtime.RtsDiagnosticsTest'])
        self.assertEqual(['build/rts-diagnostics'], group['outputs'])
        self.assertTrue(all((project / name).is_file() for name in group['sources']))
        self.assertIn('"$fixture_bin" rts-diagnostics', (project / 'scripts/prepare-tests.sh').read_text().splitlines())
        self.assertEqual(fast_fixtures.FULL_PREPARATION_PLAN, fast_fixtures._preparation_plan(project))
        outputs = fast_fixtures.fast_inputs.RTS_DIAGNOSTIC_OUTPUTS
        self.assertEqual(29, len(outputs))
        self.assertTrue(outputs <= fast_fixtures.FULL_REQUIRED)
        self.assertTrue(all(fast_fixtures.fast_inputs.allowed_payload(path, {}) for path in outputs))
        self.assertFalse(fast_fixtures.fast_inputs.allowed_payload('build/rts-diagnostics/native/oracle', {}))
        artifacts = {path: '0' * 64 for path in outputs if not path.endswith('/manifest.json')}
        proof = dict(schema=1, artifactHashes=artifacts)
        self.assertEqual(artifacts, fast_fixtures.fast_inputs.rts_diagnostic_artifact_hashes(proof))
        for invalid in (dict(artifacts, **{'build/rts-diagnostics/extra.json': '0'*64}), {}):
            with self.assertRaises(RuntimeError):
                fast_fixtures.fast_inputs.rts_diagnostic_artifact_hashes(dict(proof, artifactHashes=invalid))

    def test_rts_shutdown_exact_fixture_registration(self):
        project = Path(__file__).resolve().parents[2]
        manifest, owners = fast_fixtures._manifest(project)
        group = manifest['groups']['rts-shutdown']
        self.assertEqual('rts-shutdown', owners['thc.runtime.RtsShutdownTest'])
        self.assertEqual(['build/rts-shutdown'], group['outputs'])
        self.assertTrue(all((project / name).is_file() for name in group['sources']))
        self.assertIn('"$fixture_bin" rts-shutdown', (project / 'scripts/prepare-tests.sh').read_text().splitlines())
        self.assertEqual(fast_fixtures.FULL_PREPARATION_PLAN, fast_fixtures._preparation_plan(project))
        outputs = fast_fixtures.fast_inputs.RTS_SHUTDOWN_OUTPUTS
        self.assertEqual(53, len(outputs))
        self.assertTrue(outputs <= fast_fixtures.FULL_REQUIRED)
        self.assertTrue(all(fast_fixtures.fast_inputs.allowed_payload(path, {}) for path in outputs))
        self.assertFalse(fast_fixtures.fast_inputs.allowed_payload('build/rts-shutdown/native/oracle', {}))
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
                      (project / 'scripts/prepare-tests.sh').read_text().splitlines())
        self.assertEqual(fast_fixtures.FULL_PREPARATION_PLAN, fast_fixtures._preparation_plan(project))
        self.assertIn('build/original-rts-locks', fast_fixtures.FULL_OUTPUT_ROOTS)
        self.assertTrue(fast_fixtures.fast_inputs.ORIGINAL_RTS_LOCK_OUTPUTS <= fast_fixtures.FULL_REQUIRED)
        self.assertIn('"original-rts-locks/**/*.json"', (project / 'build.gradle.kts').read_text())

    def test_original_open_exact_registration_and_closed_manifest(self):
        project = Path(__file__).resolve().parents[2]
        manifest, owners = fast_fixtures._manifest(project)
        group = manifest['groups']['original-open']
        self.assertEqual('original-open', owners['thc.runtime.OriginalOpenTest'])
        self.assertEqual([{'argv': ['cabal', 'run', 'exe:thc-fixtures', '--offline', '--', 'original-open']}], group['commands'])
        self.assertEqual(['build/original-open'], group['outputs'])
        self.assertTrue(all((project / name).is_file() for name in group['sources']))
        self.assertIn('"$fixture_bin" original-open', (project / 'scripts/prepare-tests.sh').read_text().splitlines())
        self.assertEqual(fast_fixtures.FULL_PREPARATION_PLAN, fast_fixtures._preparation_plan(project))
        self.assertIn('build/original-open', fast_fixtures.FULL_OUTPUT_ROOTS)
        self.assertIn('"original-open/**/*.json"', (project / 'build.gradle.kts').read_text())
        name = 'build/original-open/manifest.json'
        artifacts = {}
        for artifact in fast_fixtures.fast_inputs.ORIGINAL_OPEN_OUTPUTS - {name}:
            path = self.root / artifact; path.parent.mkdir(parents=True, exist_ok=True); path.write_text('{}\n')
            artifacts[artifact] = fast_fixtures._digest(path)
        receipt = dict(schema=1, supported=True, strictAccepted=True, runtimeVerified=False,
                       installedArtifactsHashed=False, nativeRows=13, nativeVariants=["unsafe", "safe", "interruptible"],
                       ownedRequestControls=True, artifactHashes=artifacts)
        path = self.root / name
        with mock.patch.object(fast_fixtures.fast_inputs, 'GMP_NATIVE_HOST', True):
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
                artifact = self.root / 'build/original-open/pre/core/OriginalOpenAudit.json'
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
            artifact = self.root / 'build/original-rts-locks/pre.json'
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

    def test_interface_core_fixture_registration(self):
        project = Path(__file__).resolve().parents[2]
        manifest, owners = fast_fixtures._manifest(project)
        group = manifest['groups']['interface-core']
        self.assertEqual('interface-core', owners['thc.runtime.InterfaceCoreNativeTest'])
        self.assertEqual('interface-core', owners['thc.ManagedImportStubsNativeTest'])
        self.assertEqual([{'argv': ['cabal', 'run', 'exe:thc-fixtures', '--offline', '--',
                                   'interface-core']}], group['commands'])
        self.assertEqual(['build/interface-core'], group['outputs'])
        self.assertTrue(all((project / name).is_file() for name in group['sources']))
        self.assertIn('"$fixture_bin" interface-core',
                      (project / 'scripts/prepare-tests.sh').read_text().splitlines())
        self.assertEqual(fast_fixtures.FULL_PREPARATION_PLAN, fast_fixtures._preparation_plan(project))
        self.assertIn('build/interface-core', fast_fixtures.FULL_OUTPUT_ROOTS)
        for name in ('manifest.json', 'InterfaceLibrary.json', 'logs/native-oracle.stdout',
                     'full/InterfaceLibrary.hi', 'thin/InterfaceLibrary.hi',
                     'full/InterfaceLibrary.dyn_hi', 'full/InterfaceForeign.hi',
                     'source/InterfaceLibrary.saved', 'opaqueEntry-audit.json',
                     'wrapperEntry-audit.json', 'installed-wrapper-facts.json',
                     'inlineEntry-audit.json', 'recursiveEntry-audit.json',
                     'CBVCoercionAudit.json', 'direct/CBVCoercionAudit.json',
                     'full/CBVCoercionAudit.hi', 'thin/CBVCoercionAudit.hi',
                     'source/CBVCoercionAudit.saved', 'coercionEntry-audit.json',
                     'logs/helper-thin.stdout', 'logs/helper-thin.command.json',
                     'wired-unit.json', 'logs/helper-wired-unit.stdout',
                     'logs/helper-wired-unit.command.json', 'packages.json', 'driver-controls.json',
                     'cache-controls/facts.json', 'cache-controls/helper-calls',
                     'InterfaceForeign.json', 'foreign-packages.json',
                     'foreign-association.json', 'installed-bound-facts.json',
                     'foreign-alias/a.json', 'foreign-alias/b.json',
                     'source/InterfaceForeignAlias.hs.saved', 'import-stubs/plain.json', 'import-stubs/extra-file.json',
                     'import-stubs/wrapper.json', 'import-stubs/instrumented.json',
                     'source/ForeignImportStubs.hs.saved', 'import-stubs/plain/ForeignImportStubs.hi',
                     'logs/import-stubs-native-oracle.stdout'):
            self.assertIn('build/interface-core/' + name, fast_fixtures.FULL_REQUIRED)
        self.assertIn('"interface-core/**/*.json"', (project / 'build.gradle.kts').read_text())

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
                      (project / 'scripts/prepare-tests.sh').read_text().splitlines())
        self.assertEqual(fast_fixtures.FULL_PREPARATION_PLAN, fast_fixtures._preparation_plan(project))
        self.assertIn('build/original-stack-formatter', fast_fixtures.FULL_OUTPUT_ROOTS)
        self.assertIn('build/original-stack-formatter/manifest.json', fast_fixtures.FULL_REQUIRED)
        gradle = (project / 'build.gradle.kts').read_text()
        for pattern in ('original-stack-formatter/manifest.json',
                        'original-stack-formatter/run-*/originals/core/*.json',
                        'original-stack-formatter/run-*/originals/generated/**/*.hs',
                        'original-stack-formatter/run-*/originals/generated.json',
                        'original-stack-formatter/run-*/originals/target-layout.json',
                        'original-stack-formatter/run-*/native/formatter',
                        'src/THC/Driver/Wired.hs', 'compiler/target-layout.c',
                        'compiler/pinned-ghc-internal', '**/*.hs-boot', '**/*.hsc', 'include/WordSize.h'):
            self.assertIn('"' + pattern + '"', gradle)
        self.assertIn('build/original-stack-formatter/', (project / '.github/workflows/build.yml').read_text())
        sources = fast_fixtures._source_hashes(project, group)
        _, pins = fast_fixtures.fast_inputs.wired_catalog(project)
        self.assertLessEqual({'compiler/pinned-ghc-internal/' + name for name in pins}, sources.keys())
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
            path = self.root / 'compiler/pinned-ghc-internal' / name
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
                     'compiler/pinned-ghc-internal/GHC/Internal/Stack/Decode.hs',
                     'compiler/pinned-ghc-internal/GHC/Internal/IO/Unsafe.hs',
                     'compiler/pinned-ghc-internal/GHC/Internal/Heap/InfoTable/Types.hsc',
                     'compiler/pinned-ghc-internal/GHC/Internal/Ptr.hs',
                     'compiler/pinned-ghc-internal/GHC/Internal/Data/Either.hs',
                     'compiler/pinned-ghc-internal/GHC/Internal/Word.hs',
                     'compiler/pinned-ghc-internal/GHC/Internal/Bignum/Integer.hs',
                     'compiler/pinned-ghc-internal/GHC/Internal/Classes.hs',
                     'compiler/pinned-ghc-internal/GHC/Internal/Num.hs',
                     'compiler/pinned-ghc-internal/GHC/Internal/Bignum/Integer.hs-boot',
                     'compiler/pinned-ghc-internal/GHC/Internal/Bignum/BigNat.hs-boot',
                     'compiler/pinned-ghc-internal/GHC/Internal/Bignum/Natural.hs-boot',
                     'compiler/pinned-ghc-internal/GHC/Internal/Real.hs',
                     'compiler/pinned-ghc-internal/GHC/Internal/Numeric.hs',
                     'compiler/pinned-ghc-internal/GHC/Internal/Enum.hs',
                     'compiler/pinned-ghc-internal/GHC/Internal/ForeignPtr.hs',
                     'compiler/pinned-ghc-internal/GHC/Internal/Foreign/C/String/Encoding.hs',
                     'compiler/pinned-ghc-internal/GHC/Internal/IO/Encoding/UTF8.hs',
                     'compiler/pinned-ghc-internal/GHC/Internal/IO/Encoding/Types.hs',
                     'compiler/pinned-ghc-internal/GHC/Internal/IO/Encoding/Failure.hs',
                     'compiler/pinned-ghc-internal/GHC/Internal/IO/Encoding.hs',
                     'compiler/pinned-ghc-internal/GHC/Internal/IO/Handle/Types.hs-boot',
                     'compiler/pinned-ghc-internal/GHC/Internal/InfoProv/Types.hsc',
                     'compiler/pinned-ghc-internal/GHC/Internal/Num.hs-boot',
                     'compiler/pinned-ghc-internal/include/WordSize.h',
                     'compiler/pinned-ghc-internal/LICENSE'):
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
        self.assertIn('"$fixture_bin" boxed-array-extensions', (project / 'scripts/prepare-tests.sh').read_text().splitlines())
        self.assertIn('build/boxed-array-extensions/manifest.json', fast_fixtures.FULL_REQUIRED)
        gradle = (project / 'build.gradle.kts').read_text()
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
                self.assertIn('"$fixture_bin" ' + name, (project / "scripts/prepare-tests.sh").read_text().splitlines())
                self.assertIn("build/" + name, fast_fixtures.FULL_OUTPUT_ROOTS)
                self.assertIn("build/" + name + "/manifest.json", fast_fixtures.FULL_REQUIRED)
                self.assertIn("build/" + name + "/", (project / ".github/workflows/build.yml").read_text())
                self.assertIn(name + "/**/*.json", (project / "build.gradle.kts").read_text())
                self.assertIn(junit, policy["leafSources"]["src/main/kotlin/thc/runtime/FloatingPrimitives.kt"]["junit"])

    def test_explicit64_array_fixture_is_selected_and_receipted(self):
        project = Path(__file__).resolve().parents[2]
        manifest, owners = fast_fixtures._manifest(project)
        group = manifest["groups"]["explicit64-arrays"]
        self.assertEqual("explicit64-arrays", owners["thc.runtime.Explicit64ArrayTest"])
        self.assertEqual(["build/explicit64-arrays"], group["outputs"])
        self.assertTrue(all((project / name).is_file() for name in group["sources"]))
        self.assertIn('"$fixture_bin" explicit64-arrays',
                      (project / "scripts/prepare-tests.sh").read_text().splitlines())
        self.assertEqual(fast_fixtures.FULL_PREPARATION_PLAN, fast_fixtures._preparation_plan(project))
        self.assertIn("build/explicit64-arrays", fast_fixtures.FULL_OUTPUT_ROOTS)
        for name in ("manifest.json", "oracle.tsv", "pre/audit.json", "post/audit.json",
                     "pre/core/Explicit64ArrayAudit.json", "post/core/Explicit64ArrayAudit.json"):
            self.assertIn("build/explicit64-arrays/" + name, fast_fixtures.FULL_REQUIRED)
        self.assertIn('"explicit64-arrays/*.tsv"', (project / "build.gradle.kts").read_text())

    def test_floatx4_fma_has_focused_full_and_closed_native_inputs(self):
        project = Path(__file__).resolve().parents[2]
        manifest, owners = fast_fixtures._manifest(project)
        group = manifest['groups']['simd-floatx4-fma']
        self.assertEqual('simd-floatx4-fma', owners['thc.runtime.SimdFloatFmaTest'])
        self.assertEqual([{'argv': ['cabal', 'run', 'exe:thc-fixtures', '--offline', '--',
                                   'simd-floatx4-fma']}], group['commands'])
        self.assertEqual(['build/simd-floatx4-fma'], group['outputs'])
        self.assertTrue(all((project / name).is_file() for name in group['sources']))
        self.assertIn('"$fixture_bin" simd-floatx4-fma', (project / 'scripts/prepare-tests.sh').read_text().splitlines())
        self.assertEqual(fast_fixtures.FULL_PREPARATION_PLAN, fast_fixtures._preparation_plan(project))
        self.assertIn('build/simd-floatx4-fma', fast_fixtures.FULL_OUTPUT_ROOTS)
        for suffix in ('manifest.json', 'pre-audit.json', 'pre-double-audit.json', 'pre-core/SimdFloatFma.json'):
            self.assertIn('build/simd-floatx4-fma/' + suffix, fast_fixtures.FULL_REQUIRED)
        native = fast_fixtures.platform.machine().lower() not in ('arm64', 'aarch64')
        for suffix in ('oracle.txt', 'post-audit.json', 'post-double-audit.json', 'post-core/SimdFloatFma.json'):
            self.assertEqual(native, 'build/simd-floatx4-fma/' + suffix in fast_fixtures.FULL_REQUIRED)
        gradle = (project / 'build.gradle.kts').read_text()
        for suffix in ('**/*.json', 'oracle.txt'):
            self.assertIn('"simd-floatx4-fma/' + suffix + '"', gradle)
        self.assertIn('build/simd-floatx4-fma/', (project / '.github/workflows/build.yml').read_text())

        wide = manifest['groups']['simd-wide-floating-fma']
        self.assertEqual('simd-wide-floating-fma', owners['thc.runtime.SimdWideFloatFmaTest'])
        self.assertEqual([{'argv': ['cabal', 'run', 'exe:thc-fixtures', '--offline', '--',
                                   'simd-wide-floating-fma']}], wide['commands'])
        self.assertEqual(['build/simd-wide-floating-fma'], wide['outputs'])
        self.assertTrue(all((project / name).is_file() for name in wide['sources']))
        self.assertIn('"$fixture_bin" simd-wide-floating-fma', (project / 'scripts/prepare-tests.sh').read_text().splitlines())
        self.assertIn('build/simd-wide-floating-fma', fast_fixtures.FULL_OUTPUT_ROOTS)
        # Scalar FMA expectations are mandatory on every supported host; AVX512 is not required.
        for suffix in ('manifest.json', 'oracle.txt', 'pre-audit.json', 'pre-double-audit.json',
                       'pre-core/SimdWideFloatFma.json'):
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
                      (project / "scripts/prepare-tests.sh").read_text().splitlines())
        self.assertEqual(fast_fixtures.FULL_PREPARATION_PLAN, fast_fixtures._preparation_plan(project))
        self.assertIn("build/floating-address", fast_fixtures.FULL_OUTPUT_ROOTS)
        for name in ("manifest.json", "oracle.tsv", "pre/audit.json", "post/audit.json",
                     "pre/core/FloatingAddressAudit.json", "post/core/FloatingAddressAudit.json"):
            self.assertIn("build/floating-address/" + name, fast_fixtures.FULL_REQUIRED)
        self.assertIn('"floating-address/*.tsv"', (project / "build.gradle.kts").read_text())

    def test_atomic_address_family_has_one_native_receipt_and_complete_ownership(self):
        project = Path(__file__).resolve().parents[2]
        manifest, owners = fast_fixtures._manifest(project)
        group = manifest["groups"]["atomic-address"]
        self.assertEqual("atomic-address", owners["thc.runtime.AtomicAddressTest"])
        self.assertEqual(["build/atomic-address"], group["outputs"])
        self.assertTrue(all((project / name).is_file() for name in group["sources"]))
        self.assertIn('"$fixture_bin" atomic-address',
                      (project / "scripts/prepare-tests.sh").read_text().splitlines())
        self.assertIn("build/atomic-address", fast_fixtures.FULL_OUTPUT_ROOTS)
        for suffix in ("manifest.json", "oracle.tsv", "pre/audit.json", "post/audit.json",
                       "pre/core/AtomicAddressAudit.json", "post/core/AtomicAddressAudit.json"):
            self.assertIn("build/atomic-address/" + suffix, fast_fixtures.FULL_REQUIRED)
        self.assertIn('"atomic-address/*.tsv"', (project / "build.gradle.kts").read_text())
        self.assertIn("build/atomic-address/", (project / ".github/workflows/build.yml").read_text())

    def test_floating_native_consumers_use_existing_complete_preparation_groups(self):
        project = Path(__file__).resolve().parents[2]
        manifest, owners = fast_fixtures._manifest(project)
        # Reviewed generated inputs of the ten previously unowned consumers.
        consumed = {
            "thc.SumLayoutMetadataTest": ("sum-results", ["sum-layout"]),
            "thc.runtime.SumProtocolTest": ("sum-results", ["sum-layout", "sum-result"]),
            "thc.runtime.SumResultTest": ("sum-results", ["sum-layout", "sum-result", "aggregate-core",
                                                       "aggregate-post-core", "aggregate-native"]),
            "thc.runtime.FloatingTupleTest": ("floating-tuples", ["floating-tuple"]),
            "thc.runtime.SqrtPrimitiveTest": ("sqrt", ["sqrt"]),
            "thc.runtime.ScalarBitCastTest": ("scalar-bitcasts", ["scalar-bitcasts"]),
            "thc.runtime.BigNatLiteralTest": ("bignat-literals", ["bignat-literals"]),
            "thc.runtime.SimdFloatVectorTest": ("simd-floatx4", ["simd-floatx4"]),
            "thc.runtime.SimdDoubleVectorTest": ("simd-doublex2", ["simd-doublex2"]),
            "thc.runtime.SimdFloatByteArrayTest": ("simd-floatx4-bytearray", ["simd-floatx4-bytearray"]),
            "thc.runtime.SimdDoubleByteArrayTest": ("simd-doublex2-bytearray", ["simd-doublex2-bytearray"]),
        }
        full = " ".join((project / "scripts/prepare-tests.sh").read_text().split())
        for junit, (group_id, roots) in consumed.items():
            with self.subTest(junit=junit):
                self.assertEqual(group_id, owners[junit])
                group = manifest["groups"][group_id]
                source = (project / ("src/test/kotlin/" + junit.replace(".", "/") + ".kt")).read_text()
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
        self.assertIn("scripts/check-aggregate-layout.py", sums["sources"])
        self.assertIn("compiler/test-fixtures/AggregateLayoutAudit.hs", sums["sources"])
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
                source = (project / "src/test/kotlin/thc/runtime" / (name + ".kt")).read_text()
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
                source = (project / "src/test/kotlin" / (name.replace(".", "/") + ".kt")).read_text()
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
                                      else ["scripts/prepare-" + family + "-audit.py"]) +
                                     ([] if machine == "x86_64" else ["--export-only"]), actual)

    def test_complete_floating_selection_prepares_and_reuses_without_full_fallback(self):
        project = Path(__file__).resolve().parents[2]
        manifest, owners = fast_fixtures._manifest(project)
        policy = json.loads((project / ".github/scripts/fast-tests.json").read_text())
        classes = policy["leafSources"]["src/main/kotlin/thc/runtime/FloatingPrimitives.kt"]["junit"]
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
        for group_id, source in (("sum-results", "scripts/sum_layout_model.py"),
                                 ("floating-tuples", "compiler/test-fixtures/FloatingTupleAudit.hs")):
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
        self.assertEqual({"test/haskell-fixtures/PinnedAddressFixtures.hs", "test/haskell-fixtures/FixtureSupport.hs",
                          "test/haskell-fixtures/Main.hs", "thc.cabal", "compiler/test-fixtures/PinnedAddressAudit.hs",
                          "compiler/test-fixtures/PinnedAddressAuditNative.hs"}, set(group["sources"]))
        self.assertTrue(all((project / name).is_file() for name in group["sources"]))
        self.assertIn('"$fixture_bin" pinned-addresses', (project / "scripts/prepare-tests.sh").read_text().splitlines())
        self.assertEqual(fast_fixtures.FULL_PREPARATION_PLAN, fast_fixtures._preparation_plan(project))
        for name in ("prepare-pinned-addresses.py", "pinned_address_model.py", "test-pinned-addresses.py"):
            self.assertFalse((project / "scripts" / name).exists())
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

    def test_bignat_uses_haskell_and_preserves_original_source_outputs(self):
        project = Path(__file__).resolve().parents[2]
        manifest, owners = fast_fixtures._manifest(project)
        group = manifest["groups"]["bignat-literals"]
        self.assertEqual("bignat-literals", owners["thc.runtime.BigNatLiteralTest"])
        self.assertEqual([{"argv": ["cabal", "run", "exe:thc-fixtures", "--offline", "--", "bignat-literals"]}], group["commands"])
        originals = {"vendor/ghc-9.14.1/GHC/Internal/Bignum/" + name + suffix
                     for name in ("BigNat", "Integer", "Natural") for suffix in (".hs", ".hs-boot")}
        originals.update(("vendor/ghc-9.14.1/include/WordSize.h", "vendor/ghc-9.14.1/LICENSE"))
        self.assertEqual({"build/bignat-literals"} | originals, set(group["outputs"]))
        self.assertEqual({"test/haskell-fixtures/BigNatLiteralFixtures.hs", "test/haskell-fixtures/FixtureSupport.hs",
                          "test/haskell-fixtures/Main.hs", "thc.cabal", "compiler/test-fixtures/BigNatLiteralAudit.hs",
                          "compiler/test-fixtures/BigNatLiteralAuditNative.hs", "compiler/export-boot.py"}, set(group["sources"]))
        self.assertTrue(all((project / name).is_file() for name in group["sources"]))
        self.assertIn('"$fixture_bin" bignat-literals', (project / "scripts/prepare-tests.sh").read_text().splitlines())
        self.assertEqual(fast_fixtures.FULL_PREPARATION_PLAN, fast_fixtures._preparation_plan(project))
        for name in ("prepare-bignat-literals.py", "bignat_literal_model.py", "test-bignat-literals.py"):
            self.assertFalse((project / "scripts" / name).exists())
        cache = fast_fixtures.fast_inputs
        pins = {}
        for name in cache.BIGNAT_VENDOR:
            path = self.root / name; path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text('original source\n'); pins[name] = fast_fixtures._digest(path)
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
        with mock.patch.object(cache, 'vendor_pins', return_value=pins):
            self.assertEqual(cache.BIGNAT_OUTPUTS | cache.BIGNAT_VENDOR, set(fast_fixtures._output_hashes(self.root, group)))
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
        self.assertEqual({"test/haskell-fixtures/FloatingRemainderFixtures.hs", "test/haskell-fixtures/FixtureSupport.hs",
                          "test/haskell-fixtures/Main.hs", "thc.cabal", "compiler/test-fixtures/FloatingRemainderAudit.hs",
                          "compiler/test-fixtures/FloatingRemainderNative.hs", "examples/THC/InverseHyperbolic.hs"}, set(group["sources"]))
        self.assertTrue(all((project / path).is_file() for path in group["sources"]))
        self.assertIn('"$fixture_bin" floating-remainder', (project / "scripts/prepare-tests.sh").read_text().splitlines())
        self.assertIn('"floating-remainder/commands/**"', (project / "build.gradle.kts").read_text())
        policy = json.loads((project / ".github/scripts/fast-tests.json").read_text())
        for path in ("examples/THC/InverseHyperbolic.hs", "test/haskell-fixtures/FloatingRemainderFixtures.hs"):
            self.assertEqual(["thc.runtime.FloatingRemainderTest"], policy["owners"][path]["junit"])
        for path in ("src/main/kotlin/thc/runtime/FloatingPrimitives.kt", "src/main/kotlin/thc/runtime/FloatDecodePrimitives.kt"):
            self.assertIn("thc.runtime.FloatingRemainderTest", policy["leafSources"][path]["junit"])
        self.assertEqual(fast_fixtures.FULL_PREPARATION_PLAN, fast_fixtures._preparation_plan(project))

    def test_float_decode_has_native_haskell_preparation(self):
        project = Path(__file__).resolve().parents[2]
        manifest, owners = fast_fixtures._manifest(project)
        group = manifest["groups"]["float-decode"]
        self.assertEqual("float-decode", owners["thc.runtime.FloatDecodeTest"])
        self.assertEqual([{"argv": ["cabal", "run", "exe:thc-fixtures", "--offline", "--", "float-decode"]}], group["commands"])
        self.assertEqual({"build/float-decode"} | fast_fixtures.fast_inputs.BIGNAT_VENDOR, set(group["outputs"]))
        self.assertEqual({"test/haskell-fixtures/FloatDecodeFixtures.hs", "test/haskell-fixtures/FixtureSupport.hs",
                          "test/haskell-fixtures/Main.hs", "thc.cabal", "compiler/test-fixtures/FloatDecodeAudit.hs",
                          "compiler/test-fixtures/FloatDecodeNative.hs", "compiler/export-boot.py", "examples/THC/FloatDecode.hs"}, set(group["sources"]))
        self.assertTrue(all((project / path).is_file() for path in group["sources"]))
        self.assertIn('"$fixture_bin" float-decode', (project / "scripts/prepare-tests.sh").read_text().splitlines())
        self.assertIn('"float-decode/commands/**"', (project / "build.gradle.kts").read_text())
        policy = json.loads((project / ".github/scripts/fast-tests.json").read_text())
        self.assertEqual(["thc.runtime.FloatDecodeTest"], policy["owners"]["examples/THC/FloatDecode.hs"]["junit"])

    def test_float_decode_receipt_requires_pinned_vendor_inputs(self):
        cache = fast_fixtures.fast_inputs
        name = "build/float-decode/manifest.json"
        artifacts, pins = {}, {}
        for item in cache.FLOAT_DECODE_OUTPUTS - {name}:
            path = self.root / item
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text("fixture\n")
            artifacts[item] = fast_fixtures._digest(path)
        (self.root / name).write_text(json.dumps({"artifactHashes": artifacts}))
        for item in cache.BIGNAT_VENDOR:
            path = self.root / item
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text("pinned original\n")
            pins[item] = fast_fixtures._digest(path)
        with mock.patch.object(cache, "vendor_pins", return_value=pins):
            group = {"outputs": ["build/float-decode", *sorted(cache.BIGNAT_VENDOR)]}
            self.assertEqual(cache.FLOAT_DECODE_OUTPUTS | cache.BIGNAT_VENDOR,
                             set(fast_fixtures._output_hashes(self.root, group)))
            for item in sorted(cache.BIGNAT_VENDOR):
                path = self.root / item
                original = path.read_bytes()
                path.write_text("corrupt original\n")
                with self.assertRaises(RuntimeError):
                    fast_fixtures._output_hashes(self.root, group)
                path.unlink()
                with self.assertRaises((OSError, RuntimeError)):
                    fast_fixtures._output_hashes(self.root, group)
                path.write_bytes(original)

    def test_scalar_bitcasts_use_haskell_producer_and_keep_native_inputs(self):
        project = Path(__file__).resolve().parents[2]
        manifest, owners = fast_fixtures._manifest(project)
        group = manifest["groups"]["scalar-bitcasts"]
        self.assertEqual("scalar-bitcasts", owners["thc.runtime.ScalarBitCastTest"])
        self.assertEqual([{"argv": ["cabal", "run", "exe:thc-fixtures", "--offline", "--", "scalar-bitcasts"]}], group["commands"])
        self.assertEqual(["build/scalar-bitcasts"], group["outputs"])
        self.assertEqual({"test/haskell-fixtures/ScalarBitCastFixtures.hs", "test/haskell-fixtures/FixtureSupport.hs",
                          "test/haskell-fixtures/Main.hs", "thc.cabal", "compiler/test-fixtures/ScalarBitCastAudit.hs",
                          "compiler/test-fixtures/ScalarBitCastNative.hs"}, set(group["sources"]))
        self.assertTrue(all((project / name).is_file() for name in group["sources"]))
        self.assertIn('"$fixture_bin" scalar-bitcasts', (project / "scripts/prepare-tests.sh").read_text().splitlines())

    def test_address_array_copies_use_haskell_native_producer_and_full_cache_identity(self):
        project = Path(__file__).resolve().parents[2]
        manifest, owners = fast_fixtures._manifest(project)
        group = manifest["groups"]["address-array-copy"]
        self.assertEqual("address-array-copy", owners["thc.runtime.AddressArrayCopyTest"])
        self.assertEqual([{"argv": ["cabal", "run", "exe:thc-fixtures", "--offline", "--", "address-array-copy"]}], group["commands"])
        self.assertEqual(["build/address-array-copy"], group["outputs"])
        self.assertEqual({"test/haskell-fixtures/AddressArrayCopyFixtures.hs", "test/haskell-fixtures/FixtureSupport.hs",
                          "test/haskell-fixtures/Main.hs", "thc.cabal", "compiler/test-fixtures/AddressArrayCopyAudit.hs",
                          "compiler/test-fixtures/AddressArrayCopyNative.hs"}, set(group["sources"]))
        self.assertTrue(all((project / name).is_file() for name in group["sources"]))
        self.assertIn('"$fixture_bin" address-array-copy', (project / "scripts/prepare-tests.sh").read_text().splitlines())
        self.assertEqual(fast_fixtures.FULL_PREPARATION_PLAN, fast_fixtures._preparation_plan(project))
        self.assertIn("build/address-array-copy", fast_fixtures.FULL_OUTPUT_ROOTS)
        self.assertIn("address-array-copy", fast_fixtures.fast_inputs.MANIFEST_DIRS)
        self.assertIn('"address-array-copy/**/*.json"', (project / "build.gradle.kts").read_text())
        self.assertIn("build/address-array-copy/", (project / ".github/workflows/build.yml").read_text())

    def test_original_stack_has_portable_focused_and_full_preparation(self):
        project = Path(__file__).resolve().parents[2]
        manifest, owners = fast_fixtures._manifest(project)
        group = manifest["groups"]["original-stack"]
        self.assertEqual("original-stack", owners["thc.runtime.OriginalStackConsumerProofTest"])
        self.assertEqual([{"argv": ["cabal", "run", "exe:thc-fixtures", "--offline", "--", "original-stack"]}], group["commands"])
        self.assertEqual(["build/original-stack"], group["outputs"])
        self.assertTrue(all((project / name).is_file() for name in group["sources"]))
        self.assertIn('"$fixture_bin" original-stack', (project / "scripts/prepare-tests.sh").read_text().splitlines())
        self.assertIn("build/original-stack", fast_fixtures.FULL_OUTPUT_ROOTS)
        self.assertIn("build/original-stack/manifest.json", fast_fixtures.FULL_REQUIRED)
        gradle = (project / "build.gradle.kts").read_text()
        self.assertIn('"original-stack/manifest.json"', gradle)
        self.assertIn('"original-stack/run-*/**"', gradle)
        self.assertNotIn('"original-stack/proof.json"', gradle)
        for name in ("thc.cabal", "compiler/pinned-ghc-internal/LICENSE",
                     "compiler/pinned-ghc-internal/GHC/Internal/InfoProv/Types.hsc",
                     "compiler/pinned-ghc-internal/GHC/Internal/Heap/InfoTable.hsc"):
            self.assertIn('"' + name + '"', gradle)
            self.assertIn(name, group["sources"])
        for name in ("Stack/CloneStack.hs", "Stack/Decode.hs"):
            self.assertIn("compiler/pinned-ghc-internal/GHC/Internal/" + name, group["sources"])

    def test_compiled_thunk_retention_prepares_both_native_families(self):
        project = Path(__file__).resolve().parents[2]
        manifest, owners = fast_fixtures._manifest(project)
        group = manifest["groups"]["compiled-thunk-retention"]
        self.assertEqual({"thc.runtime.CompiledThunkRetentionTest", "thc.runtime.BoxedArrayTest",
                          "thc.runtime.FloatingPrimitiveTest"}, set(group["junit"]))
        self.assertEqual({"compiled-thunk-retention"}, {owners[name] for name in group["junit"]})
        self.assertEqual([{"argv": ["python3", "scripts/prepare-boxed-arrays.py"]},
                          {"argv": ["python3", "scripts/prepare-floating-audit.py"]}], group["commands"])
        self.assertEqual(["build/boxed-arrays", "build/floating"], group["outputs"])
        self.assertEqual({"scripts/prepare-boxed-arrays.py", "scripts/prepare-floating-audit.py",
                          "compiler/test-fixtures/BoxedArrayAudit.hs",
                          "compiler/test-fixtures/FloatingAudit.hs",
                          "compiler/test-fixtures/FloatingAuditNative.hs"}, set(group["sources"]))
        self.assertTrue(all((project / name).is_file() for name in group["sources"]))
        full = (project / "scripts/prepare-tests.sh").read_text().splitlines()
        for command in group["commands"]:
            self.assertIn(" ".join(command["argv"]), full)
        self.assertLessEqual(set(group["outputs"]), fast_fixtures.FULL_OUTPUT_ROOTS)
        self.assertLessEqual({"build/boxed-arrays/manifest.json", "build/floating/checks.json"},
                             fast_fixtures.FULL_REQUIRED)
        gradle = (project / "build.gradle.kts").read_text()
        for pattern in ("boxed-arrays/**/*.json", "boxed-arrays/*.tsv", "floating/core/**/*.json",
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
        command = ["scripts/prepare-original-stdio.sh", "--require-supported"]
        self.assertEqual("original-stdio", owners["thc.runtime.OriginalStdioNativeTest"])
        self.assertEqual([{"argv": command}], group["commands"])
        self.assertEqual(["build/original-stdio"], group["outputs"])
        self.assertEqual({"compiler/test-fixtures/OriginalStdioAudit.hs",
                          "compiler/test-fixtures/OriginalStdioAuditNative.hs",
                          "scripts/prepare-original-stdio.sh", "test/haskell-fixtures/Main.hs",
                          "test/haskell-fixtures/FixtureSupport.hs",
                          "test/haskell-fixtures/OriginalStdioFixtures.hs", "thc.cabal"}, set(group["sources"]))
        self.assertTrue(all((project / name).is_file() for name in group["sources"]))
        self.assertIn('"$fixture_bin" original-stdio --require-supported',
                      (project / "scripts/prepare-tests.sh").read_text().splitlines())
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
        self.assertIn("build/cbv-post-core/CBVCoercionAudit.json", cbv["outputs"])
        self.assertIn("build/tuple-arithmetic/pre-core/TupleArithmeticAudit.json", cbv["outputs"])
        self.assertIn("build/explicit64-primops/core/Explicit64PrimopsAudit.json", cbv["outputs"])
        self.assertTrue(any("scripts/check-cbv-metadata.py" in command["argv"]
                            for command in cbv["commands"]))

    def test_simd_memory_families_use_haskell_and_exact_attempt_receipts(self):
        project = Path(__file__).resolve().parents[2]
        manifest, owners = fast_fixtures._manifest(project)
        for family, klass in (("int32x4", "Int32"), ("word32x4", "Word32"), ("floatx4", "Float"), ("doublex2", "Double")):
            group_id = f"simd-{family}-bytearray"; group = manifest["groups"][group_id]
            self.assertEqual(group_id, owners[f"thc.runtime.Simd{klass}ByteArrayTest"])
            self.assertEqual(["build/"+group_id], group["outputs"])
            for path in ("test/haskell-fixtures/SimdByteArrayFixtures.hs", "test/haskell-fixtures/SimdByteArrayModel.hs",
                         "test/haskell-fixtures/Main.hs", "test/haskell-fixtures/FixtureSupport.hs", "thc.cabal"):
                self.assertIn(path, group["sources"])
            self.assertTrue(all((project / path).is_file() for path in group["sources"]))
            argv = group["commands"][0]["argv"]
            self.assertEqual(["sh", "-c"], argv[:2])
            self.assertIn(f"cabal run exe:thc-fixtures --offline -- {family}-bytearray --export-only", argv[2])
            self.assertNotIn("python", argv[2])
            for path in (f"prepare-{family}-bytearray-audit.py", f"{family}_bytearray_model.py", f"test-{family}-bytearray-model.py"):
                self.assertFalse((project / "scripts" / path).exists())
            for native in (False, True):
                attempt = f"build/{group_id}/prepare-run-Abc123"; name = f"build/{group_id}/provenance.json"
                rows = fast_fixtures.fast_inputs.SIMD_BYTEARRAY_FAMILIES[group_id][1]
                expected = {path: "0"*64 for path in fast_fixtures.fast_inputs.simd_bytearray_outputs(group_id, attempt, native)}
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
            for path in ('test/haskell-fixtures/ByteArrayFixtures.hs', 'test/haskell-fixtures/FixtureSupport.hs', 'test/haskell-fixtures/Main.hs', 'thc.cabal'):
                self.assertIn(path, group['sources']); self.assertTrue((project / path).is_file())
            name = f'build/{family}/manifest.json'; artifacts = fast_fixtures.fast_inputs.BYTEARRAY_OUTPUTS[family] - {name}
            expected = {path: '0'*64 for path in artifacts}
            with mock.patch.object(fast_fixtures.fast_inputs, 'file_path') as file_path, mock.patch.object(fast_fixtures, '_manifest_output_hashes') as outputs, \
                    mock.patch.object(fast_fixtures.fast_inputs, 'vendor_pins', return_value={path: '1'*64 for path in fast_fixtures.fast_inputs.BYTEARRAY_VENDOR}):
                file_path.return_value.read_text.return_value = json.dumps(dict(schema=1, ghc='9.14.1', wordBits=64,
                    entries=list(fast_fixtures.fast_inputs.BYTEARRAY_FAMILIES[family][1]), artifactHashes=expected))
                fast_fixtures._output_hashes(project, group)
                recorded = outputs.call_args.args[2]
                self.assertTrue(set(expected) <= set(recorded))
                if family in ('bytearray', 'compare-byte-arrays'):
                    self.assertEqual(set(expected) | fast_fixtures.fast_inputs.BYTEARRAY_VENDOR, set(recorded))
        for script in ('prepare-bytearray.py', 'prepare-mutable-bytearrays.py', 'prepare-resize-bytearrays.py',
                       'prepare-mutable-bytearray-size.py', 'prepare-compare-byte-arrays.py', 'mutable_bytearray_model.py', 'test-mutable-bytearray-model.py'):
            self.assertFalse((project / 'scripts' / script).exists())
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
        generated = self.root / "build/generated/kapt/main/Generated.java"
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
