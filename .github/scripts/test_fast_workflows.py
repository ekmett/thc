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
    def test_library_and_map_launchers_resolve_the_required_vector_module(self):
        workflow = (WORKFLOW.parent / "build.yml").read_text()
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
        workflow = (WORKFLOW.parent / "build.yml").read_text()
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
        workflow = (WORKFLOW.parent / "build.yml").read_text()
        block = workflow.split("name: Check tuple join arguments and bottoming tuple cases", 1)[1].split("      - name:", 1)[0]
        self.assertIn("cabal run exe:thc-fixtures --offline -- tuple-join --local", block)
        self.assertIn("./gradlew tupleJoinFullCoreTest tupleJoinFullCoreDenseTest", block)
        self.assertIn("build/tuple-join-input/", workflow)
        self.assertNotIn("continue-on-error", block)

    def test_windows_ci_covers_direct_main_pushes_and_preserves_evidence(self):
        workflow = (WORKFLOW.parent / "windows.yml").read_text()
        self.assertIn("  push:\n    branches: [main]", workflow)
        self.assertIn("  pull_request:\n    branches: [main]", workflow)
        self.assertIn("  workflow_dispatch:", workflow)
        self.assertIn("runs-on: windows-2025", workflow)
        self.assertIn("  contents: read", workflow)
        self.assertIn("persist-credentials: false", workflow)
        self.assertIn("group: native-windows-${{ github.event.pull_request.number || github.ref }}", workflow)
        self.assertIn("cancel-in-progress: false", workflow)
        self.assertIn("bin/windows.ps1 -Action Test -Jobs 4", workflow)
        self.assertIn("} *>&1 | Tee-Object build/windows-ci.log", workflow)
        self.assertIn("if: always()", workflow)
        self.assertIn("build/test-results/windows*SmokeTest/", workflow)
        self.assertNotIn("pull_request_target:", workflow)
        self.assertNotIn("continue-on-error:", workflow)

    def test_stdio_checks_use_haskell_and_java_not_a_python_test_family(self):
        workflow = (WORKFLOW.parent / "build.yml").read_text()
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
