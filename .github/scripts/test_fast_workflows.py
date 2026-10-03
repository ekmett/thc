# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

"""Exercise the workflow's runner-routing and persistent-runner guards."""

import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import textwrap
import unittest


WORKFLOW = Path(__file__).resolve().parents[1] / "workflows/fast.yml"
REPO = "ekmett/thc"


def embedded_python(delimiter):
    text = WORKFLOW.read_text()
    match = re.search(r"python3 - <<'" + delimiter + r"'\n(.*?)\n          " + delimiter,
                      text, re.DOTALL)
    if match is None:
        raise AssertionError(f"Missing workflow guard: {delimiter}")
    return textwrap.dedent(match.group(1)) + "\n"


class FastWorkflowGuardsTest(unittest.TestCase):
    def test_cadence_wrappers_select_one_immutable_revision_without_commit_batching(self):
        for filename, name, cadence, trigger in (
            ("build.yml", "Build", "commit", "  push:\n    branches: [main]"),
            ("hourly.yml", "Hourly", "hourly", "    - cron: '31 * * * *'"),
            ("intensive.yml", "Intensive", "nightly", "    - cron: '17 7 * * *'"),
        ):
            with self.subTest(cadence=cadence):
                workflow = (WORKFLOW.parent / filename).read_text()
                self.assertIn("name: " + name + "\n", workflow)
                self.assertIn(trigger, workflow)
                self.assertIn("uses: ./.github/workflows/checks.yml", workflow)
                self.assertIn("cadence: " + cadence, workflow)
                self.assertIn("expected_sha: ${{ inputs.expected_sha || github.sha }}", workflow)
                self.assertNotIn("concurrency:", workflow)
                self.assertNotIn("runs-on:", workflow)
        for filename in ("checks.yml", "test-groups.yml"):
            workflow = (WORKFLOW.parent / filename).read_text()
            self.assertIn("  workflow_call:", workflow)
            self.assertIn("      cadence:\n        type: string\n        required: true", workflow)
            self.assertEqual(workflow.count("uses: actions/checkout@"),
                             workflow.count("ref: ${{ github.sha }}"))
            self.assertIn('test "$(git rev-parse HEAD)" = "$GITHUB_SHA"', workflow)
            self.assertIn('test "$(git rev-parse HEAD)" = "$EXPECTED_SHA"', workflow)
            self.assertNotIn("concurrency:", workflow)

    def test_cadence_validation_and_hourly_health_precede_all_builds(self):
        workflow = (WORKFLOW.parent / "checks.yml").read_text()
        block = workflow.split("name: Validate cadence\n", 1)[1].split("\n      - ", 1)[0]
        script = textwrap.dedent(block.split("        run: |\n", 1)[1])
        for cadence in ("commit", "hourly", "nightly", "", "daily", "commit hourly"):
            with self.subTest(cadence=cadence):
                result = subprocess.run(["bash", "-e", "-c", script],
                                        env=dict(os.environ, CI_CADENCE=cadence), capture_output=True)
                self.assertEqual(cadence in ("commit", "hourly", "nightly"), result.returncode == 0)
        gate = workflow.split("name: Stop commit checks when Hourly is failing\n", 1)[1].split("\n      - ", 1)[0]
        self.assertIn("if: inputs.cadence == 'commit'", gate)
        self.assertIn("GH_TOKEN: ${{ github.token }}", gate)
        self.assertIn("run: python3 .github/scripts/hourly_health.py", gate)
        self.assertIn("  actions: read", workflow)
        self.assertNotIn("continue-on-error", gate)
        for job in ("jvm-linux", "jvm-macos", "windows", "build", "foreign-exceptions"):
            self.assertIn("  " + job + ":\n    needs: automation\n", workflow)
        self.assertIn("    needs: build\n", workflow.split("  library:\n", 1)[1])

    def test_intensive_and_hourly_work_have_explicit_single_cadence_owners(self):
        workflow = (WORKFLOW.parent / "checks.yml").read_text()
        for cadence, names in (
            ("hourly", (
                "Check tuple join arguments and bottoming tuple cases",
                "Check original binary sum inputs and captures",
                "Check original binary sum join inputs and captures",
                "Check original tuple closure and thunk captures")),
            ("nightly", (
                "Check original aggregate constructor fields against native GHC",
                "Check Haskell calls into JavaScript",
                "Compare public packages with native GHC on both backends and handoff modes",
                "Check cold project dependency acquisition",
                "Prepare fresh library source and native oracles",
                "Package verified library inputs and installed runtime",
                "Share this platform's library inputs with this workflow attempt",
                "Prepare fresh Map source and native oracle",
                "Check diagnostic Map on both backends and handoff modes")),
        ):
            for name in names:
                with self.subTest(name=name):
                    self.assertIn("      - name: " + name + "\n        if: inputs.cadence == '" + cadence + "'\n", workflow)
        for job in ("foreign-exceptions", "library"):
            block = workflow.split("  " + job + ":\n", 1)[1].split("    steps:", 1)[0]
            self.assertIn("if: inputs.cadence == 'nightly'", block)
        grouped = (WORKFLOW.parent / "test-groups.yml").read_text()
        commands = [line.strip() for line in (workflow + grouped).splitlines() if "cabal test driver-tests" in line]
        self.assertEqual(3, len(commands))
        for option in ("--unit-only", "--public-packages-only", "--acquire-project-only"):
            self.assertEqual(1, sum("--test-options=" + option in command for command in commands))
        self.assertIn("name: Build JVM distribution from source\n        if: inputs.cadence != 'commit'", workflow)
        grouped = (WORKFLOW.parent / "test-groups.yml").read_text()
        self.assertIn('fast_select.py --matrix --cadence "$CI_CADENCE"', grouped)
        self.assertIn('fast_ci.py group --group "$group" --cadence "$CI_CADENCE"', grouped)

    def test_commit_checks_reuse_common_compilation_and_scheduled_workers_stay_separate(self):
        workflow = (WORKFLOW.parent / "checks.yml").read_text()
        build = workflow.split("  build:\n", 1)[1].split("    steps:", 1)[0]
        self.assertIn("if: inputs.cadence != 'commit'", build)
        grouped = (WORKFLOW.parent / "test-groups.yml").read_text()
        common = grouped.split("  compile:\n", 1)[1].split("  group:\n", 1)[0]
        for name in (
            "Check the primop checklist",
            "Check pinned guest protocol artifacts",
            "Check driver units and CPU affinity API",
            "Check exact dependency auditor and library frontier",
            "Check merged fixture and runtime recipes with and without assertions",
            "Check benchmark power provenance",
        ):
            with self.subTest(name=name):
                self.assertNotIn("name: " + name, workflow)
                self.assertEqual(1, common.count("name: " + name + "\n"))
                block = common.split("name: " + name + "\n", 1)[1].split("\n      - ", 1)[0]
                self.assertIn("if: inputs.cadence == 'commit'", block)
                self.assertLess(common.index("fast_ci.py compile-common"), common.index("name: " + name))
        self.assertNotIn("cabal run exe:thc-primops -- scalars", grouped)
        self.assertNotIn("cabal build exe:thc", grouped)
        self.assertIn("cabal run thc --offline -fdevelopment -- --help", common)
        self.assertNotIn("continue-on-error", common)
        self.assertIn("name: Verify pinned toolchain\n        if: inputs.cadence == 'commit'", common)
        self.assertIn("python3 bin/test-audit-core.py", common)
        self.assertIn("testMaterializableApi testReturnPolicy testReturnContinuations", common)

    def test_nightly_foreign_exception_runner_rejects_untrusted_events(self):
        workflow = (WORKFLOW.parent / "checks.yml").read_text()
        lane = workflow.split("  foreign-exceptions:\n", 1)[1]
        block = lane.split("name: Require a trusted repository branch\n", 1)[1].split("\n      - ", 1)[0]
        script = textwrap.dedent(block.split("        run: |\n", 1)[1])
        for event, ref, expected in (
            ("schedule", "refs/heads/main", True),
            ("schedule", "refs/heads/feature", False),
            ("push", "refs/heads/main", True),
            ("push", "refs/heads/feature", False),
            ("workflow_dispatch", "refs/heads/feature", True),
            ("workflow_dispatch", "refs/tags/v1", False),
            ("pull_request", "refs/pull/1/merge", False),
            ("pull_request_target", "refs/heads/main", False),
        ):
            with self.subTest(event=event, ref=ref):
                result = subprocess.run(["bash", "-e", "-c", script],
                    env=dict(os.environ, GITHUB_EVENT_NAME=event, GITHUB_REF=ref), capture_output=True)
                self.assertEqual(expected, result.returncode == 0)

    def test_build_runs_driver_units_without_selecting_package_integration(self):
        workflow = (WORKFLOW.parent / "test-groups.yml").read_text()
        block = workflow.split("name: Check driver units and CPU affinity API", 1)[1].split("\n      - ", 1)[0]
        commands = [line.strip() for line in block.splitlines() if line.strip().startswith("cabal test ")]
        self.assertEqual(commands, [
            "cabal test driver-tests -fdevelopment --test-options=--unit-only --test-show-details=direct",
            "cabal test cpu-affinity-api -fdevelopment --test-show-details=direct",
        ])
        self.assertNotIn("continue-on-error", block)

    def test_library_and_map_launchers_resolve_the_required_vector_module(self):
        workflow = (WORKFLOW.parent / "checks.yml").read_text()
        for label, entry in (
            ("Run the complete strict and compiled library checks", "thc.LibraryCheck build/libraries/cases.json"),
            ("Check diagnostic Map on both backends and handoff modes", "thc.MapCheck build/map/modules.txt build/map/oracle.tsv"),
        ):
            with self.subTest(label=label):
                block = workflow.split("name: " + label, 1)[1].split("\n      - ", 1)[0]
                self.assertIn("--add-modules=jdk.incubator.vector", block)
                self.assertIn(entry, block)
                self.assertNotIn("continue-on-error", block)

    def test_full_build_reports_independent_groups_and_never_prepares_every_fixture(self):
        workflow = (WORKFLOW.parent / "checks.yml").read_text()
        grouped = (WORKFLOW.parent / "test-groups.yml").read_text()
        self.assertEqual(2, workflow.count("uses: ./.github/workflows/test-groups.yml"))
        self.assertNotIn("bin/try.sh --handoff-modes", workflow)
        self.assertNotIn("bin/prepare-tests.sh", workflow)
        self.assertIn("fail-fast: false", grouped)
        self.assertIn('fast_ci.py group --group "$group"', grouped)
        self.assertIn("fast_ci.py compile-common", grouped)
        self.assertIn("digest-mismatch: error", grouped)
        self.assertIn("cabal-update: false", grouped)
        self.assertNotIn("continue-on-error", grouped)
        self.assertNotIn("needs: build", grouped)

    def test_tuple_join_ci_uses_stock_core_subset_and_both_modes(self):
        workflow = (WORKFLOW.parent / "checks.yml").read_text()
        block = workflow.split("name: Check tuple join arguments and bottoming tuple cases", 1)[1].split("      - name:", 1)[0]
        self.assertIn("cabal run exe:thc-fixtures --offline -- tuple-join --local", block)
        self.assertIn("./gradlew tupleJoinFullCoreTest tupleJoinFullCoreDenseTest", block)
        self.assertIn("build/tuple-join-input/", workflow)
        self.assertNotIn("continue-on-error", block)

    def test_scheduled_windows_checks_select_runtime_hourly_and_driver_nightly(self):
        workflow = (WORKFLOW.parent / "checks.yml").read_text()
        caller = workflow.split("  windows:\n", 1)[1].split("  build:\n", 1)[0]
        self.assertIn("needs: automation", caller)
        self.assertIn("if: inputs.cadence != 'commit'", caller)
        self.assertIn("uses: ./.github/workflows/windows.yml", caller)
        self.assertIn("testGroup: ${{ inputs.cadence == 'hourly' && 'Runtime' || 'Driver' }}", caller)
        windows = (WORKFLOW.parent / "windows.yml").read_text()
        self.assertIn("  workflow_call:", windows)
        self.assertIn("      testGroup:\n", windows)
        self.assertIn("        required: true", windows)
        self.assertIn("  workflow_dispatch:", windows)
        self.assertNotIn("  push:", windows)
        self.assertNotIn("  pull_request:", windows)
        self.assertNotIn("pull_request_target:", windows)
        self.assertIn("ref: ${{ github.sha }}", windows)
        self.assertIn("runs-on: windows-2025", windows)
        self.assertIn("  contents: read", windows)
        self.assertIn("persist-credentials: false", windows)
        self.assertIn("timeout-minutes: 45", windows)
        self.assertIn("bin/windows.ps1 -Action Test -Jobs 4", windows)
        self.assertIn("Tee-Object build/windows-ci.log", windows)
        self.assertIn("if: always()", windows)
        self.assertIn("build/test-results/windows*SmokeTest/", windows)
        self.assertNotIn("continue-on-error:", windows)

    def test_windows_runtime_smoke_does_not_require_driver_provenance(self):
        project = WORKFLOW.parents[2]
        smoke = (project / "build.gradle").read_text().split('// Native Windows checkpoint:', 1)[1]
        self.assertNotIn('build/windows-driver/provenance.json', smoke)
        self.assertIn('excludeTestsMatching("thc.WindowsDistributionTest.$it")', smoke)
        script = (project / 'bin/windows.ps1').read_text()
        self.assertIn("[ValidateSet('All', 'Driver', 'Runtime')][string]$TestGroup = 'All'", script)
        self.assertIn("if ($testDriver)", script)
        self.assertIn("if ($testRuntime)", script)
        self.assertIn("foreach ($mode in @('testDefault', 'testDense'))", script)
        focused = script.split('$focusedTests = @()', 1)[1]
        runtime = focused.split('if ($testRuntime) {', 1)[1].split('\n    }', 1)[0]
        self.assertIn("Invoke-ThcTool $fixture @('bit')", runtime)
        self.assertIn("'thc.runtime.BitPrimopsTest'", runtime)
        driver = focused.split('if ($testDriver) {', 1)[1].split('\n    }', 1)[0]
        self.assertNotIn('BitPrimopsTest', driver)
        codepages = (project / 't/haskell-fixtures/WindowsCodePageFixtures.hs').read_text()
        self.assertIn('lookupEnv "GHC_PKG"', codepages)

    def test_stdio_checks_use_haskell_and_java_not_a_python_test_family(self):
        workflow = (WORKFLOW.parent / "test-groups.yml").read_text()
        block = workflow.split("name: Check merged fixture and runtime recipes with and without assertions", 1)[1].split("      - name:", 1)[0]
        for name in ("test-core-original-stdio.py", "test-original-stdio-fixtures.py", "test-generate-stdio-abi.py"):
            self.assertNotIn("bin/" + name, block)
            self.assertFalse((WORKFLOW.parents[2] / "bin" / name).exists(), name)
        self.assertIn('python3 "$test"', block)
        self.assertIn('python3 -O "$test"', block)

    def test_title_skip_preserves_normal_pr_gate(self):
        workflow = WORKFLOW.read_text()
        self.assertIn("!contains(github.event.pull_request.title, '[ci skip]')", workflow)

    def test_main_fast_run_finishes_while_pr_updates_still_cancel(self):
        concurrency = WORKFLOW.read_text().split("\nconcurrency:\n", 1)[1].split("\njobs:", 1)[0]
        self.assertIn("cancel-in-progress: ${{ github.event_name == 'pull_request' || github.ref != 'refs/heads/main' }}", concurrency)
        # A merged PR's close event can use main's ref: it must still cancel only
        # its PR group, including when the event lacks a usable PR number.
        self.assertIn("group: fast-${{ github.event_name == 'pull_request' && format('pr-{0}', github.event.number || github.run_id) || format('ref-{0}', github.ref) }}", concurrency)
        # Keep GitHub's default single pending slot: newest pending replaces old.
        self.assertNotIn("queue:", concurrency)

    def check_case(self, kind, ref, event, trusted):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            event_path = directory / "event.json"
            output_path = directory / "outputs"
            event_path.write_text(json.dumps(event))
            env = dict(os.environ, GITHUB_EVENT_PATH=str(event_path),
                       GITHUB_OUTPUT=str(output_path), GITHUB_REPOSITORY=REPO,
                       GITHUB_EVENT_NAME=kind, GITHUB_REF=ref)
            route = subprocess.run([sys.executable, "-c", embedded_python("PY")],
                                   env=env, text=True, capture_output=True)
            self.assertEqual(route.returncode, 0, route.stderr)
            outputs = dict(line.split("=", 1) for line in output_path.read_text().splitlines())
            self.assertEqual(outputs["trusted"], str(trusted).lower())
            self.assertNotIn("runner", outputs)  # Checkout diff chooses this later.
            guard = subprocess.run([sys.executable, "-c", embedded_python("PYCODE")],
                                   env=env, text=True, capture_output=True)
            self.assertEqual(guard.returncode == 0, trusted, guard.stderr)

    def test_hosted_ci_only_still_runs_the_full_fast_contract(self):
        workflow = WORKFLOW.read_text()
        self.assertIn("runner: ${{ steps.route.outputs.runner }}", workflow)
        self.assertIn("persistent: ${{ steps.route.outputs.persistent }}", workflow)
        self.assertIn("python3 .github/scripts/fast_runner.py", workflow)
        self.assertIn('if [ "$TRUSTED_EVENT" != true ]; then', workflow)
        self.assertEqual(workflow.count("if: needs.automation.outputs.persistent != 'true'"), 3)
        self.assertIn("Run fresh smoke plus affected tests", workflow)
        self.assertIn("needs: automation", workflow)

    def test_same_repository_pr_uses_persistent_runner(self):
        self.check_case("pull_request", "refs/pull/42/merge", {
            "repository": {"full_name": REPO},
            "pull_request": {
                "head": {"repo": {"full_name": REPO, "fork": False}},
                "base": {"repo": {"full_name": REPO}, "ref": "main"},
            }}, True)

    def test_fork_or_unknown_pr_stays_hosted(self):
        for head in ({"full_name": "someone/thc", "fork": True},
                     {"full_name": REPO}, None):
            with self.subTest(head=head):
                self.check_case("pull_request", "refs/pull/42/merge", {
                    "repository": {"full_name": REPO},
                    "pull_request": {
                        "head": {"repo": head},
                        "base": {"repo": {"full_name": REPO}, "ref": "main"},
                    }}, False)

    def test_main_push_and_feature_branch_dispatch_are_trusted(self):
        self.check_case("push", "refs/heads/main", {"repository": {"full_name": REPO}}, True)
        self.check_case("workflow_dispatch", "refs/heads/feature", {
            "repository": {"full_name": REPO}}, True)

    def test_foreign_or_unknown_push_stays_hosted(self):
        self.check_case("push", "refs/heads/main", {
            "repository": {"full_name": "someone/thc"}}, False)
        self.check_case("push", "refs/heads/feature", {
            "repository": {"full_name": REPO}}, False)
        self.check_case("repository_dispatch", "refs/heads/main", {
            "repository": {"full_name": REPO}}, False)


if __name__ == "__main__":
    unittest.main()
