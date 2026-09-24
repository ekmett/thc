#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

import importlib.util
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location("fast_ci", Path(__file__).with_name("fast_ci.py"))
ci = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(ci)


class FastRunnerTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)

    def suite(self, name="example.Test", body=None, **attrs):
        attrs = dict(tests="1", failures="0", errors="0", skipped="0", **attrs)
        attributes = " ".join(f'{key}="{value}"' for key, value in attrs.items())
        body = body if body is not None else f'<testcase name="works" classname="{name}"/>'
        (self.root / ("TEST-" + name + ".xml")).write_text(
            f'<testsuite name="{name}" {attributes}>{body}</testsuite>')

    def selection(self, mode="narrow"):
        return {"mode": mode, "runnable": True, "junit": {
            "classes": ["example.Test"], "patterns": ["*"] if mode == "full" else ["example.Test"]}}

    def test_exact_fresh_suite_and_cases(self):
        self.suite()
        result = ci.validate_xml(self.root, ["example.Test"])
        self.assertEqual(result["tests"], 1)
        self.assertEqual(result["cases"], [["example.Test", "works"]])

    def test_missing_xml_rejected(self):
        with self.assertRaisesRegex(RuntimeError, "No fresh"):
            ci.validate_xml(self.root, ["example.Test"])

    def test_missing_or_extra_class_rejected(self):
        self.suite()
        for expected in (["other.Test"], ["example.Test", "other.Test"]):
            with self.subTest(expected=expected), self.assertRaisesRegex(RuntimeError, "class mismatch"):
                ci.validate_xml(self.root, expected)

    def test_failure_element_rejected_even_if_counter_lies(self):
        self.suite(body='<testcase name="bad" classname="example.Test"><failure/></testcase>')
        with self.assertRaisesRegex(RuntimeError, "Unsuccessful"):
            ci.validate_xml(self.root, ["example.Test"])

    def test_skipped_error_and_failure_counters_rejected(self):
        for field in ("skipped", "errors", "failures"):
            with self.subTest(field=field):
                path = self.root / "TEST-example.Test.xml"
                self.suite()
                path.write_text(path.read_text().replace(f'{field}="0"', f'{field}="1"'))
                with self.assertRaisesRegex(RuntimeError, "failures/errors/skips"):
                    ci.validate_xml(self.root, ["example.Test"])

    def test_empty_or_inconsistent_suite_rejected(self):
        self.suite(body="")
        with self.assertRaisesRegex(RuntimeError, "Empty/inconsistent"):
            ci.validate_xml(self.root, ["example.Test"])

    def test_wrong_testcase_class_rejected(self):
        self.suite(body='<testcase name="works" classname="other.Test"/>')
        with self.assertRaisesRegex(RuntimeError, "Mismatched"):
            ci.validate_xml(self.root, ["example.Test"])

    def test_rerun_is_task_scoped_and_full_has_no_filters(self):
        narrow = ci.gradle_command(self.selection())
        self.assertIn("--build-cache", narrow)
        self.assertIn("--rerun", narrow)
        self.assertIn(".github/scripts/fast_ci.init.gradle", narrow)
        self.assertNotIn("--rerun-tasks", narrow)
        self.assertEqual(narrow[-2:], ["--tests", "example.Test"])
        self.assertNotIn("--tests", ci.gradle_command(self.selection("full")))
        init = Path(__file__).with_name("fast_ci.init.gradle").read_text()
        self.assertIn("tasks.withType(org.gradle.api.tasks.testing.Test)", init)
        self.assertIn("outputs.doNotCacheIf", init)

    def test_nonrunnable_or_method_only_selection_rejected(self):
        selection = self.selection()
        selection["runnable"] = False
        with self.assertRaises(RuntimeError):
            ci.gradle_command(selection)
        selection["runnable"] = True
        selection["junit"]["patterns"] = ["example.Test.oneMethod"]
        with self.assertRaises(RuntimeError):
            ci.gradle_command(selection)

    def test_python_runs_normal_and_optimized_without_shell(self):
        selected = {"python": {"commands": [["python3", "odd name/test_me.py"]]}}
        self.assertEqual(list(ci.python_commands(selected, "/python")),
                         [["/python", "odd name/test_me.py"], ["/python", "-O", "odd name/test_me.py"]])
        with self.assertRaises(RuntimeError):
            list(ci.python_commands({"python": {"commands": [["bash", "-c", "exit 0"]]}}, "/python"))

    def test_matching_automation_job_reuses_only_its_complete_test_files(self):
        selected = {"python": {"commands": [["python3", path] for path in (
            ".github/scripts/test_fast_select.py", "scripts/test-scalar-bitcasts.py",
            ".github/scripts/extra/test_nested.py")]}}
        all_commands = list(ci.python_commands(selected, "/python"))
        reused = list(ci.python_commands(selected, "/python", automation_checked=True))
        self.assertEqual(reused, all_commands[2:])

    def test_previous_outputs_are_preserved_and_cannot_count(self):
        source = self.root / "build/test-results/test"
        source.mkdir(parents=True)
        (source / "TEST-stale.xml").write_text("old")
        destination = self.root / "build/fast/prior"
        ci.preserve_previous(self.root, destination)
        self.assertFalse(source.exists())
        self.assertEqual((destination / "xml/TEST-stale.xml").read_text(), "old")
        with self.assertRaises(RuntimeError):
            ci.validate_xml(source, ["example.Test"])

    def test_linked_test_output_rejected(self):
        source = self.root / "build/test-results/test"
        source.parent.mkdir(parents=True)
        source.symlink_to(self.root, target_is_directory=True)
        with self.assertRaises(RuntimeError):
            ci.preserve_previous(self.root, self.root / "receipts")

    def test_only_successful_current_main_can_publish(self):
        head = "a" * 40
        env = {"GITHUB_REF": "refs/heads/main", "GITHUB_EVENT_NAME": "push", "GITHUB_SHA": head}
        with patch.object(ci, "git", side_effect=[head, head + "\trefs/heads/main"]):
            self.assertTrue(ci.publication_allowed(self.root, env))
        for changed in ({"GITHUB_EVENT_NAME": "pull_request"}, {"GITHUB_REF": "refs/heads/feature"},
                        {"GITHUB_EVENT_NAME": "workflow_dispatch"}, {"GITHUB_SHA": "b" * 40}):
            with self.subTest(changed=changed), patch.object(ci, "git", return_value=head):
                self.assertFalse(ci.publication_allowed(self.root, env | changed))
        dispatch = env | {"GITHUB_EVENT_NAME": "workflow_dispatch", "EXPECTED_SHA": head}
        with patch.object(ci, "git", side_effect=[head, head + "\trefs/heads/main"]):
            self.assertTrue(ci.publication_allowed(self.root, dispatch))
        with patch.object(ci, "git", side_effect=[head, "b" * 40 + "\trefs/heads/main"]):
            self.assertFalse(ci.publication_allowed(self.root, dispatch))

    def test_workflow_does_not_restore_test_status_or_write_pr_caches(self):
        workflow = Path(__file__).parents[1] / "workflows/fast.yml"
        text = workflow.read_text()
        self.assertNotIn("pull_request_target", text)
        self.assertNotIn("native-inputs.tar.gz", text)
        self.assertIn("name: Fast checks", text)
        self.assertIn("  fast-check:", text)

    def test_previous_revision_or_driver_error_cannot_publish(self):
        with patch.object(ci, "git", return_value="a" * 40):
            recorder = ci.Recorder(self.root, self.root / "receipts")
            recorder.data["passed"] = True
            self.assertTrue(ci.successful_revision(recorder))
            recorder.data["driverError"] = "failure after tests"
            self.assertFalse(ci.successful_revision(recorder))
            del recorder.data["driverError"]
            recorder.data["revision"] = "b" * 40
            self.assertFalse(ci.successful_revision(recorder))

    def test_primop_check_and_selected_fixtures_run_before_junit(self):
        selection = self.selection() | {"reasons": [], "python": {"commands": []}}
        identity = {"platform": "linux", "toolchain": {"version": "9.14.1"}}
        identity_path = self.root / "identity.json"
        identity_path.write_text(json.dumps(identity))
        with patch.object(ci, "git", return_value="a" * 40):
            recorder = ci.Recorder(self.root, self.root / "receipts")
        with patch.object(recorder, "command", side_effect=[(0, json.dumps(selection)), (0, "")]) as run:
            with patch.object(ci, "run_mode", return_value={"cases": [["example.Test", "works"]]}), \
                    patch.object(ci.fixtures, "prepare", return_value={"mode": "selected", "reused": ["smoke"]}) as prepare:
                ci.execute(recorder, "HEAD", "HEAD", identity_path)
                prepare.assert_called_once_with(self.root, selection, run, identity)
        self.assertEqual(run.call_args_list[1].args[0], "primop-checklist")
        self.assertEqual(run.call_args_list[1].args[1][1:],
                         ["scripts/primop-coverage.py", "--check", "--output",
                          str(recorder.directory / "primop-coverage.json")])
        self.assertEqual(run.call_count, 2)
        self.assertEqual(recorder.data["nativeInputs"]["reused"], ["smoke"])
        self.assertTrue(recorder.data["passed"])

    def test_native_oracle_stdout_excludes_diagnostics(self):
        with patch.object(ci, "git", return_value="a" * 40):
            recorder = ci.Recorder(self.root, self.root / "receipts")
        recorder.command("native", [sys.executable, "-c",
                         "import sys; print('1\\t42'); print('diagnostic', file=sys.stderr)"],
                         stdout="oracle.tsv")
        self.assertEqual((self.root / "oracle.tsv").read_text(), "1\t42\n")
        log = self.root / recorder.data["phases"][0]["log"]
        self.assertEqual(log.read_text(), "diagnostic\n")

    def test_fixture_failure_prevents_junit_success(self):
        selection = self.selection() | {"reasons": [], "python": {"commands": []}}
        identity_path = self.root / "identity.json"
        identity_path.write_text(json.dumps({"platform": "linux", "toolchain": {}}))
        with patch.object(ci, "git", return_value="a" * 40):
            recorder = ci.Recorder(self.root, self.root / "receipts")
        with patch.object(recorder, "command", side_effect=[(0, json.dumps(selection)), (0, "")]), \
                patch.object(ci.fixtures, "prepare", side_effect=RuntimeError("native failed")), \
                patch.object(ci, "run_mode") as junit:
            with self.assertRaisesRegex(RuntimeError, "native failed"):
                ci.execute(recorder, "HEAD", "HEAD", identity_path)
            junit.assert_not_called()
        self.assertNotIn("passed", recorder.data)

    def test_only_the_current_revision_can_reuse_automation_results(self):
        selection = self.selection() | {"reasons": [], "python": {"commands": [
            ["python3", ".github/scripts/test_fast_select.py"]]}}
        identity_path = self.root / "identity.json"
        identity_path.write_text(json.dumps({"platform": "linux", "toolchain": {}}))
        for checked, expected in (("a" * 40, 2), ("b" * 40, 4), ("", 4)):
            with self.subTest(checked=checked), patch.object(ci, "git", return_value="a" * 40), \
                    patch.dict(os.environ, {"FAST_AUTOMATION_SHA": checked}), \
                    patch.object(ci.fixtures, "prepare", return_value={"mode": "selected"}), \
                    patch.object(ci, "run_mode", return_value={"cases": []}):
                recorder = ci.Recorder(self.root, self.root / ("run-" + (checked or "none")))
                with patch.object(recorder, "command", side_effect=[(0, json.dumps(selection))] + [(0, "")] * 3) as run:
                    ci.execute(recorder, "HEAD", "HEAD", identity_path)
                self.assertEqual(run.call_count, expected)
                self.assertEqual(recorder.data["automationReused"], checked if expected == 2 else None)


if __name__ == "__main__":
    unittest.main()
