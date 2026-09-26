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
        return {"mode": mode, "runnable": True, "haskell": {"suites": [], "count": 0},
                "polyglot": {"required": False, "classes": []}, "junit": {
            "classes": ["example.Test"], "patterns": ["*"] if mode == "full" else ["example.Test"]}}

    def test_haskell_suite_is_selected_exactly(self):
        self.assertEqual([], ci.haskell_suites(self.selection()))
        self.assertEqual(["driver-tests"], ci.haskell_suites(self.selection() |
                         {"haskell": {"suites": ["driver-tests"], "count": 1}}))
        with self.assertRaisesRegex(RuntimeError, "Haskell"):
            ci.haskell_suites(self.selection() | {"haskell": {"suites": ["other"], "count": 1}})

    def test_exact_fresh_suite_and_cases(self):
        self.suite()
        result = ci.validate_xml(self.root, ["example.Test"])
        self.assertEqual(result["tests"], 1)
        self.assertEqual(result["cases"], [["example.Test", "works"]])

    def test_haskell_compile_targets_cannot_select_production_or_inject_options(self):
        self.assertEqual([], ci.haskell_compile_targets(self.selection()))
        for targets in (["exe:thc"], ["--enable-tests"], ["test:driver-tests"], ["test:x-full-core"] * 2, "test:x-full-core"):
            with self.subTest(targets=targets), self.assertRaisesRegex(RuntimeError, "Haskell"):
                ci.haskell_compile_targets(self.selection() | {"haskell": {"compileTargets": targets}})

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
        self.assertIn("--fail-fast", narrow)
        self.assertIn(".github/scripts/fast_ci.init.gradle", narrow)
        self.assertNotIn("--rerun-tasks", narrow)
        self.assertEqual(narrow[-2:], ["--tests", "example.Test"])
        full = ci.gradle_command(self.selection("full"))
        self.assertIn("--fail-fast", full)
        self.assertNotIn("--tests", full)
        init = Path(__file__).with_name("fast_ci.init.gradle").read_text()
        self.assertIn("tasks.withType(org.gradle.api.tasks.testing.Test)", init)
        self.assertIn("outputs.doNotCacheIf", init)

    def test_polyglot_task_is_optional_and_reruns_its_exact_inventory(self):
        self.assertIsNone(ci.polyglot_command(self.selection()))
        selected = self.selection() | {"polyglot": {"required": True, "classes": ["example.PolyglotTest"]}}
        command = ci.polyglot_command(selected)
        self.assertEqual("./gradlew", command[0])
        self.assertIn("polyglotTest", command)
        self.assertIn("--rerun", command)
        self.assertNotIn("--fail-fast", command)
        self.assertNotIn("--tests", command)
        for malformed in ({"required": True, "classes": []},
                          {"required": False, "classes": ["example.PolyglotTest"]},
                          {"required": True, "classes": ["example.PolyglotTest", "example.PolyglotTest"]}):
            with self.subTest(malformed=malformed), self.assertRaises(RuntimeError):
                ci.polyglot_command(self.selection() | {"polyglot": malformed})

    def test_polyglot_task_cannot_reuse_previous_xml(self):
        selected = self.selection() | {"polyglot": {"required": True, "classes": ["example.PolyglotTest"]}}
        old = self.root / "build/test-results/polyglotTest/TEST-stale.xml"
        old.parent.mkdir(parents=True)
        old.write_text("old")
        with patch.object(ci, "git", return_value="a" * 40):
            recorder = ci.Recorder(self.root, self.root / "receipts")
        with patch.object(recorder, "command", return_value=(0, "")):
            with self.assertRaisesRegex(RuntimeError, "No fresh JUnit XML"):
                ci.run_polyglot(recorder, selected)
        self.assertEqual((recorder.directory / "prior-polyglot/xml/TEST-stale.xml").read_text(), "old")

        def fresh(_, __, **___):
            output = self.root / "build/test-results/polyglotTest/TEST-example.PolyglotTest.xml"
            output.parent.mkdir(parents=True)
            output.write_text('<testsuite name="example.PolyglotTest" tests="1" failures="0" '
                              'errors="0" skipped="0"><testcase name="works" '
                              'classname="example.PolyglotTest"/></testsuite>')
            return (0, "")

        with patch.object(recorder, "command", side_effect=fresh):
            summary = ci.run_polyglot(recorder, selected)
        self.assertEqual(summary["classes"], ["example.PolyglotTest"])
        self.assertEqual(json.loads((recorder.directory / "polyglot/summary.json").read_text())["tests"], 1)

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

    def test_failed_gradle_preserves_partial_xml_and_reports_its_exit(self):
        class FailedRun:
            root = self.root
            directory = self.root / "receipts"

            def command(self, name, argv, **kwargs):
                output = self.root / "build/test-results/test"
                output.mkdir(parents=True)
                (output / "TEST-example.Test.xml").write_text(
                    '<testsuite name="example.Test" tests="1" failures="1" errors="0" skipped="0">'
                    '<testcase name="fails" classname="example.Test"><failure/></testcase></testsuite>')
                return 1, ""

        selection = self.selection() | {"junit": {"classes": ["example.Test", "later.Test"],
                                                  "patterns": ["example.Test", "later.Test"]}}
        with self.assertRaisesRegex(RuntimeError, "Gradle default failed with exit 1"):
            ci.run_mode(FailedRun(), selection, "default")
        self.assertTrue((self.root / "receipts/default/xml/TEST-example.Test.xml").exists())

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
                ci.execute(recorder, "b" * 40, "HEAD", identity_path)
                prepare.assert_called_once_with(self.root, selection, run, identity)
        self.assertEqual(run.call_args_list[0].args[1][2:4], ["--base", "b" * 40])
        self.assertEqual(run.call_args_list[1].args[0], "primop-checklist")
        self.assertEqual(run.call_args_list[1].args[1][1:],
                         ["scripts/primop-coverage.py", "--check", "--output",
                          str(recorder.directory / "primop-coverage.json")])
        self.assertEqual(run.call_count, 2)
        self.assertEqual(recorder.data["nativeInputs"]["reused"], ["smoke"])
        self.assertEqual((recorder.data["requestedBase"], recorder.data["selectionBase"]), ("b" * 40, "b" * 40))
        self.assertTrue(recorder.data["passed"])

    def test_selected_haskell_suite_builds_runtime_and_runs_after_fixtures(self):
        selection = self.selection() | {"reasons": [], "python": {"commands": []},
                                        "haskell": {"suites": ["driver-tests"], "count": 1}}
        identity_path = self.root / "identity.json"
        identity_path.write_text(json.dumps({"platform": "linux", "toolchain": {}}))
        with patch.object(ci, "git", return_value="a" * 40):
            recorder = ci.Recorder(self.root, self.root / "receipts")
        with patch.object(recorder, "command", side_effect=[(0, json.dumps(selection))] + [(0, "")] * 4) as commands, \
                patch.object(ci.fixtures, "prepare", return_value={"mode": "selected"}), \
                patch.object(ci, "run_mode", return_value={"cases": []}):
            ci.execute(recorder, "HEAD", "HEAD", identity_path)
        self.assertEqual([call.args[0] for call in commands.call_args_list[2:]],
                         ["driver-plugin", "driver-launcher", "driver-tests"])
        self.assertEqual(["cabal", "test", "driver-tests", "-fdevelopment", "--test-show-details=direct"],
                         commands.call_args_list[4].args[1])


    def test_opt_in_harness_compiles_without_executing_and_retains_jvm_smoke(self):
        selection = self.selection() | {"reasons": [], "python": {"commands": []},
                                        "haskell": {"suites": [], "count": 0,
                                                    "compileTargets": ["test:added-full-core"]}}
        identity_path = self.root / "identity.json"
        identity_path.write_text(json.dumps({"platform": "linux", "toolchain": {}}))
        for failed in (False, True):
            with self.subTest(failed=failed), patch.object(ci, "git", return_value="a" * 40):
                recorder = ci.Recorder(self.root, self.root / ("failed" if failed else "passed"))
                outputs = [(0, json.dumps(selection)), (0, ""),
                           RuntimeError("compile failed") if failed else (0, "")]
                with patch.object(recorder, "command", side_effect=outputs) as commands, \
                        patch.object(ci.fixtures, "prepare", return_value={"mode": "selected"}), \
                        patch.object(ci, "run_mode", return_value={"cases": []}) as smoke:
                    if failed:
                        with self.assertRaisesRegex(RuntimeError, "haskell-compile"):
                            ci.execute(recorder, "HEAD", "HEAD", identity_path)
                    else:
                        ci.execute(recorder, "HEAD", "HEAD", identity_path)
                self.assertEqual(commands.call_args_list[2].args,
                                 ("haskell-compile", ["cabal", "build", "test:added-full-core",
                                                       "-fdevelopment", "-ffull-core-tests"]))
                self.assertEqual([call.args[2] for call in smoke.call_args_list], ["default", "dense"])
                self.assertEqual(recorder.data["passed"], not failed)


    def test_required_polyglot_lane_runs_real_demo_after_normal_tests(self):
        selection = self.selection() | {"reasons": [], "python": {"commands": []},
                                        "polyglot": {"required": True, "classes": ["example.PolyglotTest"]}}
        identity_path = self.root / "identity.json"
        identity_path.write_text(json.dumps({"platform": "linux", "toolchain": {}}))
        with patch.object(ci, "git", return_value="a" * 40):
            recorder = ci.Recorder(self.root, self.root / "receipts")
        with patch.object(recorder, "command", side_effect=[(0, json.dumps(selection)), (0, ""), (0, "")]) as run, \
                patch.object(ci.fixtures, "prepare", return_value={"mode": "selected"}), \
                patch.object(ci, "run_mode", return_value={"cases": []}), \
                patch.object(ci, "run_polyglot", return_value={"classes": ["example.PolyglotTest"]}) as optional:
            ci.execute(recorder, "HEAD", "HEAD", identity_path)
        optional.assert_called_once_with(recorder, selection)
        self.assertEqual(run.call_args_list[2].args, ("javascript-demo", ["scripts/javascript-demo.sh"]))
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
