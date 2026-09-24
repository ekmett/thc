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
    def test_candidate_pr_does_not_duplicate_or_cancel_dispatch(self):
        workflow = WORKFLOW.read_text()
        self.assertIn("if: github.event_name != 'pull_request' || !startsWith(github.head_ref, 'thc-bulk/')", workflow)
        self.assertIn("&& 'candidate-pr' || 'gate'", workflow)

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
            self.assertEqual(json.loads(outputs["runner"]),
                             ["self-hosted", "Linux", "X64", "thc-fast"] if trusted else "ubuntu-24.04")
            guard = subprocess.run([sys.executable, "-c", embedded_python("PYCODE")],
                                   env=env, text=True, capture_output=True)
            self.assertEqual(guard.returncode == 0, trusted, guard.stderr)

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
