# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

"""Exercise hosted CI-only routing with real Git merge and diff metadata."""

import importlib.util
from pathlib import Path
import subprocess
import tempfile
import unittest


SPEC = importlib.util.spec_from_file_location("fast_runner", Path(__file__).with_name("fast_runner.py"))
runner = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(runner)
REPO = "ekmett/thc"


class FastRunnerTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.git("init", "-q", "-b", "main")
        self.git("config", "user.name", "CI fixture")
        self.git("config", "user.email", "ci@example.invalid")
        self.write(".github/scripts/merge_bot.py", "initial\n")
        self.git("add", ".")
        self.git("commit", "-qm", "base")
        self.base = self.git("rev-parse", "HEAD")

    def git(self, *args):
        return subprocess.check_output(["git", "-C", str(self.root), *args],
                                       stderr=subprocess.PIPE, text=True).strip()

    def write(self, path, text):
        target = self.root / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(text)

    def merge(self, changes, *, remove=()):
        self.git("checkout", "-qb", "feature")
        for path, content in changes.items():
            self.write(path, content)
        for path in remove:
            (self.root / path).unlink()
        self.git("add", "-A")
        self.git("commit", "-qm", "feature")
        head = self.git("rev-parse", "HEAD")
        self.git("checkout", "-q", "main")
        self.git("merge", "--no-ff", "--no-edit", "-q", "feature")
        merge = self.git("rev-parse", "HEAD")
        event = {"repository": {"full_name": REPO}, "pull_request": {
            "base": {"ref": "main", "sha": self.base, "repo": {"full_name": REPO}},
            "head": {"sha": head, "repo": {"full_name": REPO, "fork": False}}}}
        return merge, event

    def test_exact_ci_automation_merge_uses_hosted_capacity(self):
        merge, event = self.merge({".github/scripts/merge_bot.py": "updated\n",
                                   ".github/workflows/fast.yml": "name: Fast\n",
                                   "docs/contributing.md": "CI queue\n",
                                   "docs/fast-ci.md": "Fixture reuse\n"})
        self.assertTrue(runner.hosted_ci_only(self.root, event, merge, REPO))
        with tempfile.TemporaryDirectory() as temporary:
            shallow = Path(temporary) / "checkout"
            subprocess.run(["git", "clone", "-q", "--depth", "2", "file://" + str(self.root),
                            str(shallow)], check=True)
            self.assertTrue(runner.hosted_ci_only(shallow, event, merge, REPO))

    def test_runtime_or_preparer_change_keeps_persistent_runner(self):
        merge, event = self.merge({"src/main/kotlin/thc/Language.kt": "changed\n"})
        self.assertFalse(runner.hosted_ci_only(self.root, event, merge, REPO))
        self.assertFalse(runner.ci_only_paths(b"M\0scripts/prepare-tests.sh\0"))
        self.assertFalse(runner.ci_only_paths(b"M\0docs/pinned-memory.md\0"))

    def test_deletion_and_rename_fail_closed(self):
        merge, event = self.merge({".github/scripts/new_bot.py": "initial\n"},
                                  remove=(".github/scripts/merge_bot.py",))
        self.assertFalse(runner.hosted_ci_only(self.root, event, merge, REPO))
        self.assertFalse(runner.ci_only_paths(b"R100\0old\0new\0"))

    def test_wrong_event_parent_or_checkout_cannot_claim_ci_only(self):
        merge, event = self.merge({".github/scripts/merge_bot.py": "updated\n"})
        self.assertFalse(runner.hosted_ci_only(self.root, event, event["pull_request"]["head"]["sha"], REPO))
        event["pull_request"]["base"]["sha"] = "0" * 40
        self.assertFalse(runner.hosted_ci_only(self.root, event, merge, REPO))
        event["pull_request"]["base"]["sha"] = self.base
        event["pull_request"]["head"]["repo"]["fork"] = True
        self.assertFalse(runner.hosted_ci_only(self.root, event, merge, REPO))

    def test_empty_or_malformed_inventory_is_not_ci_only(self):
        self.assertFalse(runner.ci_only_paths(b""))
        self.assertFalse(runner.ci_only_paths(b"M\0.github/scripts/merge_bot.py"))
        self.assertFalse(runner.ci_only_paths(b"M\0.github/scripts/merge_bot.py\0A\0src/Main.hs\0"))


if __name__ == "__main__":
    unittest.main()
