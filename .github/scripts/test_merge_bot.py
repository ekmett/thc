import copy
import unittest
from urllib.error import HTTPError

from merge_bot import ACTIONS_APP, CHECKS, REQUIRED_STATUS, build_result, owner_authorized, publish_run, reconcile


def pull(number=1):
    return {"number": number, "state": "open", "draft": False,
            "base": {"ref": "main", "repo": {"full_name": "ekmett/thc"}},
            "head": {"sha": "head", "ref": "codex/change", "repo": {"full_name": "ekmett/thc"}},
            "labels": [{"name": "auto-merge"}], "mergeable": True, "mergeable_state": "clean"}


def build(run_id=1):
    return {"id": run_id, "head_sha": "head", "workflow_id": 17, "event": "pull_request",
            "run_attempt": 1, "status": "completed", "conclusion": "success",
            "updated_at": f"2026-09-23T00:00:0{run_id}Z",
            "html_url": f"https://github.com/ekmett/thc/actions/runs/{run_id}"}


def jobs():
    return [{"name": name, "status": "completed", "conclusion": "success"} for name in sorted(CHECKS)]


class FakeAPI:
    repo = "ekmett/thc"

    def __init__(self):
        self.pr = pull()
        self.prs = [self.pr]
        self.jobs = jobs()
        self.events = [{"id": 1, "event": "labeled", "label": {"name": "auto-merge"},
                        "actor": {"login": "ekmett"}}]
        self.runs = [build()]
        self.statuses = []
        self.base = "base"
        self.behind = 0
        self.protected = True
        self.protection = {"required_status_checks": {"enforcement_level": "everyone",
            "contexts": [REQUIRED_STATUS], "checks": [{"context": REQUIRED_STATUS, "app_id": ACTIONS_APP}]}}
        self.mutations = []
        self.pr_reads = 0
        self.before_pr_read = lambda _: None
        self.before_run_read = lambda: None
        self.before_jobs_read = lambda: None
        self.merge_error = None
        self.update_error = None
        self.delayed_update = False

    @property
    def actions(self):
        return [m for m in self.mutations if not m[1].startswith("statuses/")]

    def call(self, method, path, body=None):
        if method != "GET":
            self.mutations.append((method, path, body))
            if path.startswith("statuses/"):
                self.statuses.insert(0, {**body, "creator": {"login": "github-actions[bot]"}})
            if path.endswith("/update-branch"):
                if self.update_error:
                    raise self.update_error
                if not self.delayed_update:
                    self.pr["head"]["sha"] = "updated"
                    self.behind = 0
            if path.endswith("/merge"):
                if self.merge_error:
                    raise self.merge_error
                self.base = "merged"
                return {"merged": True}
            return None
        if path == "branches/main":
            return {"protected": self.protected, "commit": {"sha": self.base}, "protection": self.protection}
        if path.startswith("compare/"):
            return {"behind_by": (self.behind.get(path.split("...")[-1], 0)
                                  if isinstance(self.behind, dict) else self.behind)}
        if path.startswith("pulls/"):
            self.pr_reads += 1
            self.before_pr_read(self.pr_reads)
            return copy.deepcopy(next(p for p in self.prs if p["number"] == int(path.split("/")[1])))
        if path == "actions/workflows/build.yml":
            return {"id": 17}
        if path.startswith("actions/runs/"):
            self.before_run_read()
            return copy.deepcopy(next(r for r in self.runs if r["id"] == int(path.split("/")[2])))
        raise AssertionError(path)

    def pages(self, path, key=None):
        if path.endswith("/statuses"):
            return copy.deepcopy(self.statuses)
        if path.startswith("pulls?"):
            return copy.deepcopy(self.prs)
        if path.endswith("/events"):
            return copy.deepcopy(self.events)
        if path.startswith("actions/workflows/"):
            return copy.deepcopy(self.runs)
        if path.endswith("/jobs"):
            self.before_jobs_read()
            return copy.deepcopy(self.jobs)
        raise AssertionError(path)


class MergeBotTest(unittest.TestCase):
    def run_bot(self, api):
        messages = []
        reconcile(api, messages.append, lambda _: None)
        return messages

    def test_green_exact_head_merges(self):
        api = FakeAPI()
        self.run_bot(api)
        self.assertEqual(api.actions, [("PUT", "pulls/1/merge", {"sha": "head", "merge_method": "squash"})])
        self.assertEqual(api.statuses[0]["state"], "success")

    def test_no_owner_label_no_action(self):
        for actor in ("collaborator", "github-actions[bot]", None):
            with self.subTest(actor=actor):
                api = FakeAPI()
                api.events[0]["actor"] = {"login": actor}
                self.run_bot(api)
                self.assertEqual(api.actions, [])

    def test_removal_or_other_actor_reapplication_revokes_authority(self):
        for event, actor in (("unlabeled", "ekmett"), ("labeled", "collaborator")):
            api = FakeAPI()
            api.events.insert(0, {"id": 2, "event": event, "label": {"name": "auto-merge"}, "actor": {"login": actor}})
            self.assertFalse(owner_authorized(api, 1))
            self.run_bot(api)
            self.assertEqual(api.actions, [])

    def test_missing_history_fails_closed(self):
        api = FakeAPI()
        api.events = []
        self.run_bot(api)
        self.assertEqual(api.actions, [])

    def test_ineligible_prs_do_nothing(self):
        changes = [lambda p: p.update(draft=True), lambda p: p.update(state="closed"),
                   lambda p: p.update(labels=[]), lambda p: p["base"].update(ref="other"),
                   lambda p: p["head"].update(repo={"full_name": "outsider/thc"}),
                   lambda p: p["head"].update(repo=None)]
        for change in changes:
            api = FakeAPI()
            change(api.pr)
            self.run_bot(api)
            self.assertEqual(api.actions, [])

    def test_inadequate_protection_fails_closed(self):
        for mutate in (lambda a: setattr(a, "protected", False),
                       lambda a: setattr(a, "protection", {}),
                       lambda a: a.protection["required_status_checks"].update(enforcement_level="non_admins"),
                       lambda a: a.protection["required_status_checks"].update(checks=[]),
                       lambda a: a.protection["required_status_checks"]["checks"][0].update(app_id=-1)):
            api = FakeAPI()
            mutate(api)
            with self.assertRaisesRegex(RuntimeError, "protection"):
                self.run_bot(api)
            self.assertEqual(api.mutations, [])

    def test_failed_skipped_cancelled_or_missing_jobs_cannot_pass(self):
        for conclusion in ("failure", "skipped", "neutral", "cancelled", "timed_out"):
            api = FakeAPI()
            api.jobs[0]["conclusion"] = conclusion
            self.run_bot(api)
            self.assertEqual(api.actions, [])
            self.assertEqual(api.statuses[0]["state"], "failure")
        api = FakeAPI()
        api.jobs.pop()
        self.assertEqual(build_result(api, "head")[0], "failure")

    def test_failed_run_cannot_be_masked_by_successful_jobs(self):
        api = FakeAPI()
        api.runs[0]["conclusion"] = "failure"
        self.assertEqual(build_result(api, "head")[0], "failure")

    def test_pending_existing_build_is_not_dispatched_again(self):
        api = FakeAPI()
        api.runs[0].update(status="in_progress", conclusion=None)
        self.run_bot(api)
        self.assertEqual(api.actions, [])
        self.assertEqual(api.statuses[0]["state"], "pending")

    def test_missing_build_dispatches_exact_head_after_pending_status(self):
        api = FakeAPI()
        api.runs = []
        self.run_bot(api)
        self.assertEqual(api.actions, [("POST", "actions/workflows/build.yml/dispatches",
                                       {"ref": "codex/change", "inputs": {"expected_sha": "head"}})])
        self.assertEqual(api.mutations[0][1], "statuses/head")
        self.assertEqual(api.statuses[0]["state"], "pending")

    def test_wrong_workflow_or_head_is_not_accepted(self):
        for change in ({"workflow_id": 18}, {"head_sha": "other"}, {"event": "workflow_run"}):
            api = FakeAPI()
            api.runs[0].update(change)
            self.assertEqual(build_result(api, "head")[0], "missing")

    def test_latest_build_supersedes_old_success(self):
        api = FakeAPI()
        api.runs.insert(0, build(2))
        api.runs[0]["conclusion"] = "failure"
        self.assertEqual(build_result(api, "head")[0], "failure")

    def test_old_run_id_rerun_supersedes_newer_success(self):
        for status, conclusion, expected in (("in_progress", None, "pending"),
                                              ("completed", "failure", "failure")):
            api = FakeAPI()
            api.runs.append(build(2))
            api.runs[0].update(run_attempt=2, updated_at="2026-09-23T00:00:03Z",
                               status=status, conclusion=conclusion)
            self.assertEqual(build_result(api, "head")[0], expected)
            self.run_bot(api)
            self.assertEqual(api.actions, [])

    def test_active_older_execution_holds_gate_even_with_later_update(self):
        api = FakeAPI()
        api.runs.append(build(2))
        api.runs[0].update(status="in_progress", conclusion=None)
        self.assertEqual(build_result(api, "head")[0], "pending")

    def test_attempt_changed_during_job_read_cannot_pass(self):
        api = FakeAPI()
        api.before_jobs_read = lambda: api.runs[0].update(run_attempt=2, status="in_progress")
        self.assertEqual(build_result(api, "head")[0], "pending")

    def test_completed_rerun_still_invalidates_previous_attempt(self):
        api = FakeAPI()
        api.before_jobs_read = lambda: api.runs[0].update(run_attempt=2)
        self.assertEqual(build_result(api, "head")[0], "pending")

    def test_outdated_branch_updates_and_tests_before_merging(self):
        api = FakeAPI()
        api.behind = 1
        self.run_bot(api)
        self.assertEqual(api.actions, [
            ("PUT", "pulls/1/update-branch", {"expected_head_sha": "head"}),
            ("POST", "actions/workflows/build.yml/dispatches",
             {"ref": "codex/change", "inputs": {"expected_sha": "updated"}})])

    def test_delayed_branch_update_never_dispatches_stale_head(self):
        api = FakeAPI()
        api.behind = 1
        api.delayed_update = True
        self.run_bot(api)
        self.assertEqual(len(api.actions), 1)
        self.assertEqual(api.actions[0][1], "pulls/1/update-branch")

    def test_forbidden_branch_update_needs_manual_fix_without_starving_queue(self):
        for code in (403, 405):
            with self.subTest(code=code):
                api = FakeAPI()
                api.behind = {"head": 1}
                api.update_error = HTTPError("", code, "Do not echo response details", {}, None)
                second = pull(2)
                second["head"].update(sha="second", ref="codex/second")
                api.prs.append(second)
                api.runs.append({**build(2), "head_sha": "second"})
                messages = self.run_bot(api)
                self.assertEqual(api.actions, [
                    ("PUT", "pulls/1/update-branch", {"expected_head_sha": "head"}),
                    ("PUT", "pulls/2/merge", {"sha": "second", "merge_method": "squash"})])
                self.assertIn(f"HTTP {code}", messages[0])
                self.assertIn("maintainer must update", messages[0])
                self.assertNotIn("response details", "\n".join(messages))
                self.assertEqual(api.pr["head"]["sha"], "head")

    def test_branch_update_race_waits_and_later_dispatches_only_fresh_head(self):
        for code in (409, 422):
            with self.subTest(code=code):
                api = FakeAPI()
                api.behind = 1
                api.update_error = HTTPError("", code, "Conflict", {}, None)
                api.prs.append(pull(2))
                messages = self.run_bot(api)
                self.assertEqual(api.actions, [
                    ("PUT", "pulls/1/update-branch", {"expected_head_sha": "head"})])
                self.assertIn("later run will recheck", messages[-1])
                api.update_error = None
                api.behind = 0
                api.pr["head"]["sha"] = "externally-updated"
                api.runs = []
                self.run_bot(api)
                self.assertEqual(api.actions[-1],
                    ("POST", "actions/workflows/build.yml/dispatches",
                     {"ref": "codex/change", "inputs": {"expected_sha": "externally-updated"}}))
                self.assertEqual(len(api.actions), 2)

    def test_successful_merge_is_reported_before_next_branch_update_is_forbidden(self):
        api = FakeAPI()
        second = pull(2)
        second["head"].update(sha="second", ref="codex/second")
        api.prs.append(second)
        api.runs.append({**build(2), "head_sha": "second"})
        api.behind = {"second": 1}
        api.update_error = HTTPError("", 403, "Forbidden", {}, None)
        messages = self.run_bot(api)
        self.assertEqual(api.actions, [
            ("PUT", "pulls/1/merge", {"sha": "head", "merge_method": "squash"}),
            ("PUT", "pulls/2/update-branch", {"expected_head_sha": "second"})])
        self.assertEqual(messages[0], "#1: merged head")
        self.assertIn("#2: GitHub refused branch update (HTTP 403)", messages[1])

    def test_branch_update_rate_limit_stops_until_later_run(self):
        for headers in ({"Retry-After": "60"}, {"X-RateLimit-Remaining": "0"}):
            with self.subTest(headers=headers):
                api = FakeAPI()
                api.behind = 1
                api.update_error = HTTPError("", 403, "Forbidden", headers, None)
                api.prs.append(pull(2))
                messages = self.run_bot(api)
                self.assertEqual(len(api.actions), 1)
                self.assertIn("rate limited", messages[-1])
                self.assertNotIn("maintainer", messages[-1])

    def test_unexpected_branch_update_http_errors_are_not_hidden(self):
        for code in (400, 401, 404, 408, 429, 500, 503):
            with self.subTest(code=code):
                api = FakeAPI()
                api.behind = 1
                api.update_error = HTTPError("", code, "Unexpected", {}, None)
                self.addCleanup(api.update_error.close)
                with self.assertRaises(HTTPError) as caught:
                    self.run_bot(api)
                self.assertIs(caught.exception, api.update_error)
                self.assertEqual(len(api.actions), 1)

    def test_head_or_consent_changed_before_merge(self):
        for mutation in (lambda a: a.pr["head"].update(sha="new"),
                         lambda a: a.pr.update(labels=[]),
                         lambda a: a.events[0]["actor"].update(login="collaborator"),
                         lambda a: a.pr.update(draft=True),
                         lambda a: a.pr["base"].update(ref="other")):
            api = FakeAPI()
            api.before_pr_read = lambda n: mutation(api) if n == 2 else None
            self.run_bot(api)
            self.assertEqual(api.actions, [])

    def test_main_advanced_before_merge(self):
        api = FakeAPI()
        api.before_pr_read = lambda n: setattr(api, "base", "new") if n == 2 else None
        self.run_bot(api)
        self.assertEqual(api.actions, [])

    def test_rerun_started_before_merge(self):
        api = FakeAPI()
        api.before_pr_read = lambda n: api.runs[0].update(run_attempt=2, status="in_progress") if n == 2 else None
        self.run_bot(api)
        self.assertEqual(api.actions, [])
        self.assertEqual(api.statuses[0]["state"], "pending")

    def test_conflicted_unknown_or_blocked_pr_never_merges(self):
        for mergeable, state in ((False, "dirty"), (None, "unknown"), (True, "blocked")):
            api = FakeAPI()
            api.pr.update(mergeable=mergeable, mergeable_state=state)
            self.run_bot(api)
            self.assertEqual(api.actions, [])

    def test_server_rejection_does_not_retry_or_bypass(self):
        api = FakeAPI()
        api.merge_error = HTTPError("", 409, "Head changed", {}, None)
        self.addCleanup(api.merge_error.close)
        messages = self.run_bot(api)
        self.assertEqual(len(api.actions), 1)
        self.assertIn("GitHub refused", messages[-1])

    def test_publisher_does_not_replace_latest_failure_with_old_success(self):
        api = FakeAPI()
        api.runs.append(build(2))
        api.runs[1]["conclusion"] = "failure"
        publish_run(api, 1)
        self.assertEqual(api.mutations, [])
        publish_run(api, 2)
        self.assertEqual(api.statuses[0]["state"], "failure")

    def test_publisher_supports_manual_and_fork_pr_checks_without_merging(self):
        api = FakeAPI()
        api.pr["head"]["repo"]["full_name"] = "outsider/thc"
        publish_run(api, 1)
        self.assertEqual(api.statuses[0]["state"], "success")
        self.assertEqual(api.actions, [])

    def test_identical_status_is_not_republished(self):
        api = FakeAPI()
        publish_run(api, 1)
        publish_run(api, 1)
        self.assertEqual(len(api.mutations), 1)

    def test_identical_status_from_wrong_publisher_is_replaced(self):
        api = FakeAPI()
        publish_run(api, 1)
        api.statuses[0]["creator"]["login"] = "someone-else"
        publish_run(api, 1)
        self.assertEqual(len(api.mutations), 2)

    def test_older_matching_status_does_not_hide_newer_state(self):
        api = FakeAPI()
        publish_run(api, 1)
        api.statuses.insert(0, {**api.statuses[0], "state": "pending"})
        publish_run(api, 1)
        self.assertEqual(len(api.mutations), 2)
        self.assertEqual(api.statuses[0]["state"], "success")

    def test_unrelated_status_context_is_ignored(self):
        api = FakeAPI()
        publish_run(api, 1)
        api.statuses.insert(0, {"context": "other-check", "state": "pending"})
        publish_run(api, 1)
        self.assertEqual(len(api.mutations), 1)

    def test_context_case_does_not_hide_newer_state(self):
        api = FakeAPI()
        publish_run(api, 1)
        api.statuses.insert(0, {**api.statuses[0], "context": REQUIRED_STATUS.upper(), "state": "pending"})
        publish_run(api, 1)
        self.assertEqual(len(api.mutations), 2)
        self.assertEqual(api.statuses[0]["state"], "success")

    def test_missing_creator_is_not_trusted(self):
        for creator in (None, {}):
            with self.subTest(creator=creator):
                api = FakeAPI()
                publish_run(api, 1)
                api.statuses[0]["creator"] = creator
                publish_run(api, 1)
                self.assertEqual(len(api.mutations), 2)

    def test_reconcile_recovers_dropped_completion_for_unlabelled_fork(self):
        api = FakeAPI()
        api.pr["labels"] = []
        api.pr["head"]["repo"]["full_name"] = "outsider/thc"
        api.statuses = [{"context": REQUIRED_STATUS, "state": "pending", "target_url": "old"}]
        self.run_bot(api)
        self.assertEqual(api.statuses[0]["state"], "success")
        self.assertEqual(api.actions, [])

    def test_terminal_incomplete_build_does_not_starve_next_pr(self):
        api = FakeAPI()
        api.jobs.pop()
        api.prs.append(pull(2))
        messages = self.run_bot(api)
        self.assertEqual(len(messages), 2)
        self.assertTrue(messages[1].startswith("#2:"))
        self.assertEqual(api.actions, [])


if __name__ == "__main__":
    unittest.main()
