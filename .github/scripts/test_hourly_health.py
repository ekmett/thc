#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
import unittest
from unittest.mock import Mock
import hourly_health


class HourlyHealthTest(unittest.TestCase):
    def run_record(self, conclusion):
        return {"id": 1, "workflow_id": 7, "head_sha": "a" * 40, "run_attempt": 1,
                "event": "schedule", "head_branch": "main",
                "head_repository": {"full_name": "ekmett/thc"},
                "status": "completed", "conclusion": conclusion,
                "html_url": "https://github.com/ekmett/thc/actions/runs/1"}

    def api(self, runs, *attempts):
        return Mock(side_effect=[{"workflows": [{"id": 7, "path": ".github/workflows/hourly.yml"}]},
                                 {"workflow_runs": runs}, *attempts])

    def test_failure_blocks_until_a_later_success(self):
        failure = self.run_record("failure")
        for result in ("failure", "timed_out", "action_required", "startup_failure"):
            with self.subTest(result=result), self.assertRaisesRegex(RuntimeError, "stop development"):
                hourly_health.check("ekmett/thc", self.api([self.run_record(result)]))
        hourly_health.check("ekmett/thc", self.api([self.run_record("success"), failure]))

    def test_cancelled_and_skipped_runs_cannot_clear_failure(self):
        runs = [self.run_record(state) for state in ("cancelled", "skipped", "neutral", "failure")]
        with self.assertRaises(RuntimeError):
            hourly_health.check("ekmett/thc", self.api(runs))

    def test_pending_or_cancelled_rerun_retains_previous_failure(self):
        for status, conclusion in (("queued", None), ("in_progress", None),
                                   ("completed", "cancelled"), ("completed", "skipped"),
                                   ("completed", "neutral")):
            with self.subTest(status=status, conclusion=conclusion):
                rerun = self.run_record(conclusion) | {"status": status, "run_attempt": 2}
                api = self.api([rerun, self.run_record("success")], self.run_record("failure"))
                with self.assertRaisesRegex(RuntimeError, "stop development"):
                    hourly_health.check("ekmett/thc", api)
                self.assertNotIn("status=completed", api.call_args_list[1].args[0])
                self.assertTrue(api.call_args.args[0].endswith("/runs/1/attempts/1"))

    def test_multiple_cancelled_attempts_cannot_erase_the_failure(self):
        rerun = self.run_record("cancelled") | {"run_attempt": 3}
        skipped = self.run_record("skipped") | {"run_attempt": 2}
        api = self.api([rerun, self.run_record("success")], skipped, self.run_record("failure"))
        with self.assertRaisesRegex(RuntimeError, "stop development"):
            hourly_health.check("ekmett/thc", api)
        self.assertEqual([call.args[0].rsplit("/", 1)[1] for call in api.call_args_list[2:]], ["2", "1"])

    def test_successful_repair_and_pending_first_attempt_use_existing_history(self):
        repaired = self.run_record("success") | {"run_attempt": 2}
        api = self.api([repaired, self.run_record("failure")])
        hourly_health.check("ekmett/thc", api)
        self.assertEqual(api.call_count, 2)
        pending = self.run_record(None) | {"status": "in_progress"}
        api = self.api([pending, self.run_record("failure")])
        with self.assertRaisesRegex(RuntimeError, "stop development"):
            hourly_health.check("ekmett/thc", api)
        self.assertEqual(api.call_count, 2)

    def test_cancelled_rerun_of_a_success_does_not_invent_a_failure(self):
        rerun = self.run_record("cancelled") | {"run_attempt": 2}
        hourly_health.check("ekmett/thc", self.api([rerun], self.run_record("success")))

    def test_unverifiable_previous_attempt_cannot_clear_failure(self):
        rerun = self.run_record(None) | {"status": "in_progress", "run_attempt": 2}
        for changed in ({"id": 2}, {"workflow_id": 8}, {"head_sha": "b" * 40},
                        {"head_branch": "repair"}, {"event": "pull_request"},
                        {"head_repository": {"full_name": "someone/thc"}},
                        {"run_attempt": 2}, {"status": "in_progress"}):
            with self.subTest(changed=changed), self.assertRaisesRegex(RuntimeError, "previous Hourly"):
                hourly_health.check("ekmett/thc", self.api(
                    [rerun], self.run_record("success") | changed))
        with self.assertRaisesRegex(RuntimeError, "API unavailable"):
            hourly_health.check("ekmett/thc", self.api([rerun], RuntimeError("API unavailable")))

    def test_foreign_pr_or_nonmain_success_cannot_clear_failure(self):
        for changed in ({"event": "pull_request"}, {"head_branch": "repair"},
                        {"head_repository": {"full_name": "someone/thc"}}):
            with self.subTest(changed=changed), self.assertRaises(RuntimeError):
                hourly_health.check("ekmett/thc", self.api([
                    self.run_record("success") | changed, self.run_record("failure")]))

    def test_missing_identity_and_api_failures_do_not_pass(self):
        malformed = self.run_record("success")
        del malformed["head_repository"]
        with self.assertRaises(KeyError):
            hourly_health.check("ekmett/thc", self.api([malformed]))
        with self.assertRaisesRegex(RuntimeError, "API unavailable"):
            hourly_health.check("ekmett/thc", Mock(side_effect=RuntimeError("API unavailable")))

    def test_empty_initial_history_is_reported_without_claiming_success(self):
        hourly_health.check("ekmett/thc", self.api([]))
        hourly_health.check("ekmett/thc", Mock(return_value={"workflows": []}))

    def test_cancelled_page_does_not_hide_older_failure(self):
        api = Mock(side_effect=[{"workflows": [{"id": 7, "path": ".github/workflows/hourly.yml"}]},
                               {"workflow_runs": [self.run_record("cancelled")] * 100},
                               {"workflow_runs": [self.run_record("failure")]}])
        with self.assertRaises(RuntimeError):
            hourly_health.check("ekmett/thc", api)
        self.assertIn("page=2", api.call_args.args[0])


if __name__ == "__main__":
    unittest.main()
