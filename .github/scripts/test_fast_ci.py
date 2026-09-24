#!/usr/bin/env python3
import importlib.util
import json
from pathlib import Path
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
        self.assertEqual(text.count("uses: actions/cache/save@"), 3)
        self.assertEqual(text.count("if: steps.publish.outputs.allowed == 'true'"), 3)
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

    def test_primop_check_runs_on_cache_hit_without_overwriting_cached_provenance(self):
        selection = self.selection() | {"reasons": [], "python": {"commands": []}}
        with patch.object(ci, "git", return_value="a" * 40):
            recorder = ci.Recorder(self.root, self.root / "receipts")
        with patch.object(recorder, "command", side_effect=[(0, json.dumps(selection)), (0, ""), (0, "")]) as run:
            with patch.object(ci, "run_mode", return_value={"cases": [["example.Test", "works"]]}):
                ci.execute(recorder, "HEAD", "HEAD", self.root / "identity", self.root / "bundle")
        self.assertEqual(run.call_args_list[1].args[0], "primop-checklist")
        self.assertEqual(run.call_args_list[1].args[1][1:],
                         ["scripts/primop-coverage.py", "--check", "--output",
                          str(recorder.directory / "primop-coverage.json")])
        self.assertEqual(run.call_args_list[2].args[0], "native-restore")
        self.assertTrue(recorder.data["passed"])


if __name__ == "__main__":
    unittest.main()
