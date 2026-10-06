# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

"""Test selection boundaries; real Ninja runs verify file freshness."""

import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent))
import fast_fixtures


class FixtureGraphTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.manifest = {"schema": 1, "fixtureFreeJunit": ["thc.FreeTest"], "groups": {
            "alpha": {"junit": ["thc.AlphaTest", "thc.AlphaBackendTest"],
                      "commands": [{"argv": ["old-alpha"]}], "outputs": ["build/alpha"],
                      "sources": ["alpha.hs"]},
            "beta": {"junit": ["thc.BetaTest"], "commands": [{"argv": ["old-beta"]}],
                     "outputs": ["build/beta"], "sources": ["beta.hs"]}}}
        manifest = self.root / fast_fixtures.MANIFEST
        manifest.parent.mkdir(parents=True)
        manifest.write_text(json.dumps(self.manifest))
        self.calls = []

    @staticmethod
    def selection(*classes, mode="narrow"):
        return {"mode": mode, "junit": {"classes": list(classes)}}

    def prepare(self, *classes, mode="narrow"):
        return fast_fixtures.prepare_cmake(self.root, self.selection(*classes, mode=mode),
                                          lambda name, argv: self.calls.append((name, argv)))

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
                     "thc.runtime.ForkHostFailureTest", "thc.runtime.LoomSignalProcessTest", "thc.runtime.OriginalProcessIdentityTest",
                     "thc.runtime.CompilerRtsTest", "thc.ThreadedThunkTest"):
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
                with self.assertRaisesRegex(ValueError, "Quarantined tests cannot run"):
                    fast_fixtures.prepare_cmake(project, self.selection(*group["junit"]), run)
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
                with self.assertRaisesRegex(ValueError, "Select exact known test classes"):
                    self.prepare(selector, mode="full")
                self.assertEqual([], self.calls)

    def test_quarantine_blocks_direct_and_dependent_selection_before_any_command(self):
        self.manifest["groups"]["alpha"]["requires"] = ["beta"]
        self.manifest["groups"]["beta"]["quarantined"] = True
        (self.root / fast_fixtures.MANIFEST).write_text(json.dumps(self.manifest))
        for name in ("thc.AlphaTest", "thc.BetaTest"):
            with self.subTest(name=name):
                with self.assertRaisesRegex(ValueError, "Quarantined tests cannot run"):
                    self.prepare(name)
                self.assertEqual([], self.calls)
        self.assertEqual({"thc.AlphaTest", "thc.AlphaBackendTest", "thc.BetaTest"},
                         fast_fixtures.quarantined_classes(self.root))
        self.assertEqual([], self.prepare("thc.FreeTest")["targets"])
        self.assertEqual([], self.calls)

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

    def test_admitted_inventory_selects_only_declared_graph_targets(self):
        project = Path(__file__).resolve().parents[2]
        manifest, owners = fast_fixtures._manifest(project)
        classes = sorted(set(owners) - fast_fixtures.quarantined_classes(project))
        run = mock.Mock()
        result = fast_fixtures.prepare_cmake(project, self.selection(*classes), run)
        expected = {group["cmakeTarget"] for group in manifest["groups"].values() if not group.get("quarantined")}
        self.assertEqual(expected, set(result["targets"]))
        self.assertEqual(len(expected), len(result["targets"]))
        self.assertEqual(["fixture-configure", "fixture-build"], [call.args[0] for call in run.call_args_list])

    def test_aggregate_layout_has_one_independent_target_at_commit_cadence(self):
        import fast_select
        project = Path(__file__).resolve().parents[2]
        manifest, owners = fast_fixtures._manifest(project)
        classes = ["thc.AggregateLayoutTest", "thc.runtime.UnknownBoxedNativeTest"]
        self.assertEqual(classes, manifest["groups"]["aggregate-layout"]["junit"])
        self.assertFalse(manifest["groups"]["aggregate-layout"].get("requires"))
        result = fast_fixtures.prepare_cmake(project, self.selection(*classes), mock.Mock())
        self.assertEqual(["fixture-aggregate-layout"], result["targets"])
        policy = json.loads((project / fast_select.POLICY).read_text())
        cadence = fast_select.cadence_assignments(manifest, owners, policy)
        for name in classes:
            self.assertEqual("commit", cadence[name], name)
            self.assertNotIn(name, fast_fixtures.quarantined_classes(project))

    def test_sum_layout_has_one_independent_target_and_explicit_result_dependency(self):
        import fast_select
        project = Path(__file__).resolve().parents[2]
        manifest, owners = fast_fixtures._manifest(project)
        group = manifest["groups"]["sum-layout"]
        classes = ["thc.SumLayoutMetadataTest"]
        self.assertEqual(classes, group["junit"])
        self.assertFalse(group.get("requires"))
        self.assertEqual(["fixture-sum-layout"], fast_fixtures.prepare_cmake(project, self.selection(*classes), mock.Mock())["targets"])
        policy = json.loads((project / fast_select.POLICY).read_text())
        self.assertEqual("commit", fast_select.cadence_assignments(manifest, owners, policy)[classes[0]])
        remaining = manifest["groups"]["sum-results"]
        self.assertIn("sum-layout", remaining["requires"])
        self.assertEqual({"thc.runtime.SumProtocolTest", "thc.runtime.SumResultTest"}, set(remaining["junit"]))

    def test_floating_tuple_owner_uses_named_products_at_commit_cadence(self):
        import fast_select
        project = Path(__file__).resolve().parents[2]
        manifest, owners = fast_fixtures._manifest(project)
        group = manifest["groups"]["floating-tuples"]
        self.assertEqual(["compact-model"], group["requires"])
        self.assertEqual(["Linux", "Darwin"], group["ciPlatforms"])
        self.assertEqual(set(group["outputs"]), {
            *(f"build/floating-tuple/{stage}-core/FloatingTupleAudit.cbd" for stage in ("pre", "post")),
            *(f"build/floating-tuple/{stage}-ghc/FloatingTupleAudit.{suffix}" for stage in ("pre", "post") for suffix in ("hi", "o")),
            "build/floating-tuple/native/oracle", "build/floating-tuple/oracle.tsv", "build/floating-tuple/bits.tsv",
            *(f"build/floating-tuple/native/{module}.{suffix}" for module in ("Main", "FloatingTupleAudit") for suffix in ("hi", "o")),
        })
        self.assertEqual(["fixture-compact-model", "fixture-floating-tuples"],
            fast_fixtures.prepare_cmake(project, self.selection(*group["junit"]), mock.Mock())["targets"])
        policy = json.loads((project / fast_select.POLICY).read_text())
        name = "thc.runtime.FloatingTupleTest"
        self.assertEqual([name], group["junit"])
        self.assertEqual("commit", fast_select.cadence_assignments(manifest, owners, policy)[name])
        self.assertNotIn(name, fast_fixtures.quarantined_classes(project))

    def test_sum_result_owners_use_named_products_and_explicit_dependencies(self):
        import fast_select
        project = Path(__file__).resolve().parents[2]
        manifest, owners = fast_fixtures._manifest(project)
        group = manifest["groups"]["sum-results"]
        self.assertEqual({"compact-model", "sum-layout"}, set(group["requires"]))
        self.assertEqual(set(group["outputs"]), {
            *(f"build/sum-result/{stage}-core/SumResultAudit.cbd" for stage in ("pre", "post")),
            *(f"build/sum-result/{stage}-ghc/SumResultAudit.{suffix}" for stage in ("pre", "post") for suffix in ("hi", "o")),
            "build/sum-result/native/oracle", "build/sum-result/oracle.tsv", "build/sum-result/oracle-pairs.tsv",
            *(f"build/sum-result/native/{module}.{suffix}" for module in ("Main", "SumResultAudit") for suffix in ("hi", "o")),
        })
        self.assertEqual(["fixture-compact-model", "fixture-sum-layout", "fixture-sum-results"],
            fast_fixtures.prepare_cmake(project, self.selection(*group["junit"]), mock.Mock())["targets"])
        policy = json.loads((project / fast_select.POLICY).read_text())
        cadence = fast_select.cadence_assignments(manifest, owners, policy)
        for name in group["junit"]:
            self.assertEqual("commit", cadence[name], name)
            self.assertNotIn(name, fast_fixtures.quarantined_classes(project))

    def test_frontier_public_models_and_representation_controls_have_separate_owners(self):
        import fast_select
        project = Path(__file__).resolve().parents[2]
        manifest, owners = fast_fixtures._manifest(project)
        public, component = "thc.AggregateFrontierTest", "thc.runtime.TupleRepresentationTest"
        self.assertEqual("compact-model", owners[public])
        self.assertIn(component, manifest["fixtureFreeJunit"])
        self.assertIsNone(owners[component])
        self.assertEqual(["fixture-compact-model"], fast_fixtures.prepare_cmake(project, self.selection(public, component), mock.Mock())["targets"])
        policy = json.loads((project / fast_select.POLICY).read_text())
        cadence = fast_select.cadence_assignments(manifest, owners, policy)
        for name in (public, component):
            self.assertEqual("commit", cadence[name], name)
            self.assertNotIn(name, fast_fixtures.quarantined_classes(project))

    def test_selected_consumers_share_one_dependency_build(self):
        self.manifest["groups"]["alpha"].update(cmakeTarget="fixture-alpha", requires=["beta"])
        self.manifest["groups"]["beta"]["cmakeTarget"] = "fixture-beta"
        (self.root / fast_fixtures.MANIFEST).write_text(json.dumps(self.manifest))
        result = self.prepare("thc.AlphaTest", "thc.AlphaBackendTest", "thc.BetaTest")
        self.assertEqual(["fixture-beta", "fixture-alpha"], result["targets"])
        self.assertEqual(2, len(self.calls))
        self.assertEqual(["--target", "fixture-beta", "fixture-alpha"], self.calls[-1][1][-3:])


if __name__ == "__main__":
    unittest.main()
