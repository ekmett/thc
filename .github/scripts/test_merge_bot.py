import copy
import unittest
from urllib.error import HTTPError

from merge_bot import (ACTIONS_APP, CHECKS, REQUIRED_STATUS, BUILD_GATE, FAST_GATE, PR_GATE,
                       build_result, main_build_health, owner_authorized,
                       publish_run as publish_selected_run, reconcile, workflow_result, dispatch, ensure_main_checks)


def publish_run(api, run_id):
    # Existing regressions explicitly retain the transition's Build/11 policy.
    return publish_selected_run(api, run_id, BUILD_GATE)


def pull(number=1):
    return {"number": number, "state": "open", "draft": False,
            "base": {"ref": "main", "sha": "base", "repo": {"full_name": "ekmett/thc"}},
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
        reconcile(api, messages.append, lambda _: None, BUILD_GATE)
        return messages

    def test_green_exact_head_merges(self):
        api = FakeAPI()
        self.run_bot(api)
        self.assertEqual(api.actions, [("PUT", "pulls/1/merge", {"sha": "head", "merge_method": "squash"})])
        self.assertEqual(api.statuses[0]["state"], "success")

    def test_required_names_cover_all_eight_library_modes_and_original_jobs(self):
        self.assertEqual(CHECKS, {
            "automation", "build (ubuntu-latest)", "build (macos-latest)",
            "library (ubuntu-latest, ast, handoff=false)",
            "library (ubuntu-latest, ast, handoff=true)",
            "library (ubuntu-latest, bytecode, handoff=false)",
            "library (ubuntu-latest, bytecode, handoff=true)",
            "library (macos-latest, ast, handoff=false)",
            "library (macos-latest, ast, handoff=true)",
            "library (macos-latest, bytecode, handoff=false)",
            "library (macos-latest, bytecode, handoff=true)",
        })

    def test_every_required_mode_is_mandatory_and_must_appear_exactly_once(self):
        for name in CHECKS:
            for duplicate in (False, True):
                with self.subTest(name=name, duplicate=duplicate):
                    api = FakeAPI()
                    if duplicate:
                        api.jobs.append(copy.deepcopy(next(job for job in api.jobs if job["name"] == name)))
                    else:
                        api.jobs = [job for job in api.jobs if job["name"] != name]
                    self.run_bot(api)
                    self.assertEqual(api.actions, [])
                    self.assertEqual(api.statuses[0]["state"], "failure")

    def test_pre_split_three_job_success_is_not_a_complete_build(self):
        api = FakeAPI()
        api.jobs = [job for job in api.jobs if not job["name"].startswith("library (")]
        self.assertEqual(len(api.jobs), 3)
        self.assertEqual(build_result(api, "head")[0], "failure")

    def test_library_modes_cannot_be_skipped_pending_or_failed(self):
        for name in sorted(name for name in CHECKS if name.startswith("library (")):
            for status, conclusion in (("queued", None), ("in_progress", None),
                                       ("completed", "skipped"), ("completed", "failure"),
                                       ("completed", "cancelled")):
                with self.subTest(name=name, status=status, conclusion=conclusion):
                    api = FakeAPI()
                    next(job for job in api.jobs if job["name"] == name).update(
                        status=status, conclusion=conclusion)
                    self.assertEqual(build_result(api, "head")[0], "failure")

    def test_library_result_changes_during_final_merge_recheck_prevent_merge(self):
        api = FakeAPI()
        reads = 0

        def changed():
            nonlocal reads
            reads += 1
            if reads == 3:
                next(job for job in api.jobs if job["name"] ==
                     "library (macos-latest, bytecode, handoff=true)")["conclusion"] = "failure"

        api.before_jobs_read = changed
        messages = self.run_bot(api)
        self.assertEqual(reads, 3)
        self.assertEqual(api.actions, [])
        self.assertEqual(api.statuses[0]["state"], "failure")
        self.assertIn("Build changed before merging", messages[-1])

    def test_library_rerun_cannot_reuse_completed_previous_attempt_jobs(self):
        api = FakeAPI()
        api.before_jobs_read = lambda: api.runs[0].update(run_attempt=2, status="in_progress", conclusion=None)
        self.assertEqual(build_result(api, "head")[0], "pending")
        self.assertEqual(api.actions, [])

    def test_green_fresh_unstable_head_attempts_protected_merge(self):
        api = FakeAPI()
        api.pr["mergeable_state"] = "blocked"
        api.before_pr_read = lambda n: api.pr.update(mergeable_state="unstable") if n == 2 else None
        self.run_bot(api)
        self.assertEqual(api.actions, [("PUT", "pulls/1/merge", {"sha": "head", "merge_method": "squash"})])
        self.assertEqual(api.statuses[0]["state"], "success")

    def test_unstable_requires_complete_trusted_build(self):
        mutations = {
            "missing": lambda a: a.runs.clear(),
            "wrong workflow": lambda a: a.runs[0].update(workflow_id=18),
            "wrong head": lambda a: a.runs[0].update(head_sha="other"),
            "pending": lambda a: a.runs[0].update(status="in_progress", conclusion=None),
            "failed run": lambda a: a.runs[0].update(conclusion="failure"),
            "failed job": lambda a: a.jobs[0].update(conclusion="failure"),
            "skipped job": lambda a: a.jobs[0].update(conclusion="skipped"),
            "missing job": lambda a: a.jobs.pop(),
            "duplicate job": lambda a: a.jobs.append(copy.deepcopy(a.jobs[0])),
            "extra failed job": lambda a: a.jobs.append(
                {"name": "extra", "status": "completed", "conclusion": "failure"}),
        }
        for name, mutate in mutations.items():
            with self.subTest(name=name):
                api = FakeAPI()
                api.pr["mergeable_state"] = "unstable"
                mutate(api)
                self.run_bot(api)
                self.assertFalse(any(path.endswith("/merge") for _, path, _ in api.actions))
                self.assertNotEqual(api.statuses[0]["state"], "success")

    def test_unstable_preserves_fresh_authorization_base_and_build_rechecks(self):
        mutations = {
            "head": lambda a: a.pr["head"].update(sha="new"),
            "owner": lambda a: a.events[0]["actor"].update(login="collaborator"),
            "consent": lambda a: a.pr.update(labels=[]),
            "main": lambda a: setattr(a, "base", "new"),
            "build": lambda a: a.runs[0].update(run_attempt=2, status="in_progress"),
        }
        for name, mutate in mutations.items():
            with self.subTest(name=name):
                api = FakeAPI()
                api.pr["mergeable_state"] = "unstable"
                api.before_pr_read = lambda n: mutate(api) if n == 2 else None
                self.run_bot(api)
                self.assertEqual(api.actions, [])

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
        states = ((False, "dirty"), (None, "unknown")) + tuple(
            (True, state) for state in ("blocked", "dirty", "unknown", "behind", "draft", "has_hooks", None))
        for mergeable, state in states:
            api = FakeAPI()
            api.pr.update(mergeable=mergeable, mergeable_state=state)
            self.run_bot(api)
            self.assertEqual(api.actions, [])

    def test_server_rejection_does_not_retry_or_bypass(self):
        for state in ("clean", "unstable"):
            for code in (405, 409):
                with self.subTest(state=state, code=code):
                    api = FakeAPI()
                    api.pr["mergeable_state"] = state
                    api.merge_error = HTTPError("", code, "Merge refused", {}, None)
                    self.addCleanup(api.merge_error.close)
                    messages = self.run_bot(api)
                    self.assertEqual(api.actions, [("PUT", "pulls/1/merge", {"sha": "head", "merge_method": "squash"})])
                    self.assertEqual(api.base, "base")
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

def main_build(run_id=100, sha="base", conclusion="success", event="push", minute=1):
    return {**build(run_id), "head_sha": sha, "head_branch": "main",
            "head_repository": {"full_name": "ekmett/thc"}, "event": event,
            "conclusion": conclusion, "updated_at": f"2026-09-23T00:{minute:02}:00Z"}


class FastAPI(FakeAPI):
    def __init__(self):
        super().__init__()
        self.runs[0]["workflow_id"] = 18
        self.jobs = [{"name": n, "status": "completed", "conclusion": "success"}
                     for n in sorted(FAST_GATE.checks)]
        self.full_runs = [main_build()]
        self.main_fast_runs = [{**main_build(200), "workflow_id": 18}]
        self.commit_parents = {"base": "parent", "merged": "base"}
        self.dispatch_errors = {}
        self.after_dispatch = lambda _: None
        self.full_jobs = jobs()
        self.prior_attempts = {}
        self.comparisons = {}
        self.after_merge = lambda: None
        self.on_full_jobs = lambda: None
        self.on_main_runs = lambda: None
        self.dispatch_error = None
        self.reads = []

    def call(self, method, path, body=None):
        if method == "GET":
            self.reads.append(path)
            if path == "actions/workflows/fast.yml":
                return {"id": 18}
            if path.startswith("commits/"):
                sha = path.split("/")[1]
                return {"sha": sha, "parents": [{"sha": self.commit_parents[sha]}]}
            if path in self.comparisons:
                return self.comparisons[path]
            if path.startswith("actions/runs/"):
                parts = path.split("/")
                run_id = int(parts[2])
                if len(parts) == 5 and parts[3] == "attempts":
                    return copy.deepcopy(self.prior_attempts[(run_id, int(parts[4]))])
                if any(r["id"] == run_id for r in self.full_runs):
                    return copy.deepcopy(next(r for r in self.full_runs if r["id"] == run_id))
        if method == "PUT" and path.endswith("/merge"):
            result = super().call(method, path, body)
            result["sha"] = "merged"
            self.after_merge()
            return result
        if method == "POST" and path.endswith("/dispatches"):
            error = self.dispatch_errors.get(path)
            if path == "actions/workflows/build.yml/dispatches":
                error = error or self.dispatch_error
            if error:
                self.mutations.append((method, path, body))
                raise error
            result = super().call(method, path, body)
            if body["ref"] == "main":
                fast = path == "actions/workflows/fast.yml/dispatches"
                runs = self.main_fast_runs if fast else self.full_runs
                run = main_build((200 if fast else 100) + len(runs), body["inputs"]["expected_sha"],
                                 event="workflow_dispatch", minute=2)
                run.update(workflow_id=18 if fast else 17, status="queued", conclusion=None)
                runs.append(run)
            self.after_dispatch(path)
            return result
        return super().call(method, path, body)

    def pages(self, path, key=None):
        if path == "actions/workflows/build.yml/runs?branch=main":
            self.on_main_runs()
            return copy.deepcopy(self.full_runs)
        if path == "actions/workflows/fast.yml/runs?branch=main":
            return copy.deepcopy(self.main_fast_runs)
        if path.startswith("actions/workflows/fast.yml/runs?"):
            return copy.deepcopy(self.runs)
        if path.endswith("/jobs") and int(path.split("/")[2]) >= 100:
            self.on_full_jobs()
            return copy.deepcopy(self.full_jobs)
        return super().pages(path, key)


class FastGateTest(unittest.TestCase):
    def run_bot(self, api):
        messages = []
        reconcile(api, messages.append, lambda _: None)
        return messages

    def assert_no_merge(self, api):
        self.assertFalse(any(path.endswith("/merge") for _, path, _ in api.actions))

    def test_trusted_policy_changes_only_pr_gate(self):
        self.assertEqual(PR_GATE, FAST_GATE)
        self.assertEqual(FAST_GATE.workflow, "fast.yml")
        self.assertEqual(FAST_GATE.checks, {"automation", "fast-check"})
        self.assertEqual(len(BUILD_GATE.checks), 11)
        api = FastAPI()
        self.run_bot(api)
        self.assertEqual(api.actions, [
            ("PUT", "pulls/1/merge", {"sha": "head", "merge_method": "squash"}),
            ("POST", "actions/workflows/build.yml/dispatches", {"ref": "main", "inputs": {"expected_sha": "merged"}}),
            ("POST", "actions/workflows/fast.yml/dispatches", {"ref": "main", "inputs": {"expected_sha": "merged", "base_sha": "base"}})])
        self.assertIn("Fast checks", api.statuses[0]["description"])
        self.assertNotIn("head", api.actions[-1][2]["inputs"]["expected_sha"])

    def test_fast_names_attempt_and_source_are_exact(self):
        mutations = [lambda a: a.jobs.pop(), lambda a: a.jobs.append(copy.deepcopy(a.jobs[0])),
                     lambda a: a.jobs[0].update(conclusion="skipped"),
                     lambda a: a.jobs[0].update(conclusion="failure"),
                     lambda a: a.jobs[0].update(status="in_progress"),
                     lambda a: a.runs[0].update(conclusion="cancelled"),
                     lambda a: a.runs[0].update(workflow_id=17),
                     lambda a: a.runs[0].update(head_sha="wrong"),
                     lambda a: a.runs[0].update(event="workflow_run")]
        for mutation in mutations:
            api = FastAPI(); mutation(api); self.run_bot(api); self.assert_no_merge(api)
        api = FastAPI()
        api.before_jobs_read = lambda: api.runs[0].update(run_attempt=2, status="in_progress")
        self.run_bot(api); self.assert_no_merge(api)
        self.assertEqual(api.statuses[0]["state"], "pending")

    def test_old_build_green_is_retired_on_unlabelled_fork_without_fast(self):
        api = FastAPI(); api.runs = []; api.pr["labels"] = []
        api.pr["head"]["repo"]["full_name"] = "outsider/thc"
        api.statuses = [{"context": REQUIRED_STATUS, "state": "success", "target_url": "old-build",
                         "creator": {"login": "github-actions[bot]"}}]
        self.run_bot(api)
        self.assertEqual(api.statuses[0]["state"], "pending")
        self.assertTrue(api.statuses[0]["target_url"].endswith("fast.yml"))
        self.assertEqual(api.actions, [])

    def test_build_event_cannot_publish_a_fast_green(self):
        api = FastAPI(); publish_selected_run(api, 100)
        self.assertEqual(api.mutations, [])
        publish_selected_run(api, 1)
        self.assertEqual(api.statuses[0]["state"], "success")

    def test_bot_authored_branch_update_dispatches_fast_exact_new_head(self):
        api = FastAPI(); api.behind = 1; self.run_bot(api)
        self.assertEqual(api.actions, [("PUT", "pulls/1/update-branch", {"expected_head_sha": "head"}),
            ("POST", "actions/workflows/fast.yml/dispatches", {"ref": "codex/change", "inputs": {"expected_sha": "updated", "base_sha": "base"}})])
        self.assertEqual(api.statuses[0]["state"], "pending")

    def test_missing_fast_dispatch_carries_verified_base_and_exact_head(self):
        api = FastAPI(); api.runs = []
        self.run_bot(api)
        self.assertEqual(api.actions, [("POST", "actions/workflows/fast.yml/dispatches", {
            "ref": "codex/change", "inputs": {"expected_sha": "head", "base_sha": "base"}})])

    def test_fast_dispatch_rejects_changed_consent_head_base_or_ancestry(self):
        mutations = [lambda a: a.pr.update(labels=[]), lambda a: a.pr["head"].update(sha="moved"),
                     lambda a: a.pr["base"].update(sha="stale"), lambda a: setattr(a, "behind", 1)]
        for mutation in mutations:
            api = FastAPI(); original = copy.deepcopy(api.pr)
            api.before_pr_read = lambda _: mutation(api)
            self.assertFalse(dispatch(api, original))
            self.assertEqual(api.mutations, [])

    def test_both_missing_main_workflows_recover_independently_with_parent_base(self):
        for failed_workflow in ("build.yml", "fast.yml"):
            with self.subTest(failed=failed_workflow):
                api = FastAPI(); api.prs = []; api.full_runs = []; api.main_fast_runs = []
                path = f"actions/workflows/{failed_workflow}/dispatches"
                error = HTTPError("", 503, "unavailable", {}, None); self.addCleanup(error.close)
                api.dispatch_errors[path] = error
                with self.assertRaises(HTTPError): self.run_bot(api)
                self.assertEqual(len(api.actions), 2)
                other = api.main_fast_runs if failed_workflow == "build.yml" else api.full_runs
                self.assertEqual(len(other), 1)
                api.dispatch_errors = {}; before = len(api.actions)
                self.run_bot(api)
                self.assertEqual([action[1] for action in api.actions[before:]], [path])
                before = len(api.actions); self.run_bot(api)
                self.assertEqual(len(api.actions), before)
                fast_dispatch = next(body for _, route, body in api.actions if route.endswith("fast.yml/dispatches"))
                self.assertEqual(fast_dispatch, {"ref": "main", "inputs": {"expected_sha": "base", "base_sha": "parent"}})

    def test_failed_postmerge_fast_dispatch_recovers_without_duplicate_build(self):
        api = FastAPI()
        error = HTTPError("", 503, "unavailable", {}, None); self.addCleanup(error.close)
        path = "actions/workflows/fast.yml/dispatches"
        api.dispatch_errors[path] = error
        with self.assertRaises(HTTPError): self.run_bot(api)
        self.assertEqual(api.base, "merged")
        self.assertEqual(api.actions[-2][1], "actions/workflows/build.yml/dispatches")
        self.assertEqual(api.actions[-1][1], path)
        api.prs = []; api.dispatch_errors = {}; before = len(api.actions)
        self.run_bot(api)
        self.assertEqual(api.actions[before:], [("POST", path, {
            "ref": "main", "inputs": {"expected_sha": "merged", "base_sha": "base"}})])

    def test_missing_main_fast_starts_cache_work_without_holding_green_pr(self):
        api = FastAPI(); api.main_fast_runs = []
        self.run_bot(api)
        self.assertEqual(api.actions[0], ("POST", "actions/workflows/fast.yml/dispatches", {
            "ref": "main", "inputs": {"expected_sha": "base", "base_sha": "parent"}}))
        self.assertTrue(any(path.endswith("/merge") for _, path, _ in api.actions))

    def test_pending_or_failed_main_fast_neither_duplicates_dispatch_nor_holds_pr(self):
        for status, conclusion in (("queued", None), ("completed", "failure")):
            api = FastAPI(); api.main_fast_runs[0].update(status=status, conclusion=conclusion)
            self.run_bot(api)
            self.assertTrue(any(path.endswith("/merge") for _, path, _ in api.actions))
            dispatches = [body for _, path, body in api.actions if path.endswith("fast.yml/dispatches")]
            self.assertEqual(dispatches, [{"ref": "main", "inputs": {"expected_sha": "merged", "base_sha": "base"}}])

    def test_wrong_source_main_fast_cannot_suppress_cache_recovery(self):
        for mutation in (lambda r: r.update(event="pull_request"), lambda r: r.update(workflow_id=17),
                         lambda r: r.update(head_branch="feature"),
                         lambda r: r.update(head_repository={"full_name": "outsider/thc"})):
            api = FastAPI(); api.prs = []; mutation(api.main_fast_runs[0])
            self.run_bot(api)
            self.assertEqual(api.actions, [("POST", "actions/workflows/fast.yml/dispatches", {
                "ref": "main", "inputs": {"expected_sha": "base", "base_sha": "parent"}})])

    def test_main_advance_between_dispatches_does_not_seed_wrong_revision(self):
        api = FastAPI(); api.prs = []; api.full_runs = []; api.main_fast_runs = []
        api.after_dispatch = lambda _: setattr(api, "base", "advanced")
        self.run_bot(api)
        self.assertEqual(api.actions, [("POST", "actions/workflows/build.yml/dispatches", {
            "ref": "main", "inputs": {"expected_sha": "base"}})])

    def test_fast_preserves_owner_head_base_and_final_attempt_checks(self):
        mutations = [lambda a: a.events[0]["actor"].update(login="collaborator"),
                     lambda a: a.pr.update(labels=[]), lambda a: a.pr["head"].update(sha="new"),
                     lambda a: setattr(a, "base", "new"),
                     lambda a: a.runs[0].update(run_attempt=2, status="in_progress")]
        for mutation in mutations:
            api = FastAPI(); api.before_pr_read = lambda n: mutation(api) if n == 2 else None
            self.run_bot(api); self.assert_no_merge(api)

    def test_pending_new_full_build_does_not_reintroduce_serial_gate(self):
        api = FastAPI()
        pending = main_build(101, "new-main", minute=2)
        pending.update(status="in_progress", conclusion=None)
        api.full_runs.append(pending)
        self.run_bot(api)
        self.assertTrue(any(path.endswith("/merge") for _, path, _ in api.actions))
        self.assertNotIn("actions/runs/101/attempts/1/jobs", api.reads)

    def test_failed_cancelled_or_incomplete_full_build_blocks_automatic_merge(self):
        for conclusion in ("failure", "cancelled", "timed_out", "skipped", "neutral", "action_required"):
            api = FastAPI(); api.full_runs.append(main_build(101, conclusion=conclusion, minute=2))
            messages = self.run_bot(api); self.assert_no_merge(api)
            self.assertIn("automatic merges paused", messages[-1])
            # PR Fast status remains truthful; this is an auto-merge veto.
            self.assertEqual(api.statuses[0]["state"], "success")
        for change in (lambda a: a.full_jobs.pop(),
                       lambda a: a.full_jobs.append(copy.deepcopy(a.full_jobs[0])),
                       lambda a: a.full_jobs[0].update(conclusion="skipped")):
            api = FastAPI(); change(api); self.run_bot(api); self.assert_no_merge(api)

    def test_pending_rerun_cannot_hide_previous_main_failure(self):
        api = FastAPI(); prior = main_build(101, conclusion="failure", minute=2)
        api.prior_attempts[(101, 1)] = prior
        api.full_runs.append({**prior, "run_attempt": 2, "status": "in_progress", "conclusion": None})
        self.run_bot(api); self.assert_no_merge(api)

    def test_pending_rerun_of_success_does_not_hold_green_fast(self):
        api = FastAPI(); prior = copy.deepcopy(api.full_runs[0])
        api.prior_attempts[(100, 1)] = prior
        api.full_runs[0].update(run_attempt=2, status="in_progress", conclusion=None)
        self.run_bot(api)
        self.assertTrue(any(path.endswith("/merge") for _, path, _ in api.actions))

    def test_rerun_history_wrong_source_or_attempt_fails_closed(self):
        for mutation in (lambda p: p.update(head_sha="other"), lambda p: p.update(run_attempt=2),
                         lambda p: p.update(workflow_id=18), lambda p: p.update(status="in_progress")):
            api = FastAPI(); prior = copy.deepcopy(api.full_runs[0]); mutation(prior)
            api.prior_attempts[(100, 1)] = prior
            api.full_runs[0].update(run_attempt=2, status="in_progress", conclusion=None)
            with self.assertRaisesRegex(RuntimeError, "previous full Build"):
                self.run_bot(api)
            self.assert_no_merge(api)

    def test_later_verified_descendant_main_success_clears_failure(self):
        api = FastAPI(); api.full_runs[0]["conclusion"] = "failure"
        api.full_runs.append(main_build(101, "fixed", event="workflow_dispatch", minute=2))
        api.comparisons["compare/base...fixed"] = {"status": "ahead"}
        self.assertEqual(main_build_health(api)[0], "success")
        api.comparisons["compare/base...fixed"] = {"status": "behind"}
        self.assertEqual(main_build_health(api)[0], "failure")

    def test_old_commit_rerun_success_cannot_clear_newer_main_failure(self):
        api = FastAPI(); api.full_runs[0].update(run_attempt=2, updated_at="2026-09-23T00:03:00Z")
        api.full_runs.append(main_build(101, "broken", conclusion="failure", minute=2))
        api.comparisons["compare/broken...base"] = {"status": "behind"}
        self.assertEqual(main_build_health(api)[0], "failure")

    def test_pr_fork_wrong_workflow_and_nonmain_full_results_do_not_clear_failure(self):
        for mutation in (lambda r: r.update(event="pull_request"), lambda r: r.update(workflow_id=18),
                         lambda r: r.update(head_branch="feature"),
                         lambda r: r.update(head_repository={"full_name": "fork/thc"})):
            api = FastAPI(); api.full_runs[0]["conclusion"] = "failure"
            unrelated = main_build(101, minute=2); mutation(unrelated); api.full_runs.append(unrelated)
            self.assertEqual(main_build_health(api)[0], "failure")

    def test_full_failure_completed_during_health_check_prevents_merge(self):
        api = FastAPI()
        def fail():
            if len(api.full_runs) == 1:
                api.full_runs.append(main_build(101, conclusion="failure", minute=2))
        api.on_full_jobs = fail
        messages = self.run_bot(api); self.assert_no_merge(api)
        self.assertIn("health is changed", messages[-1])

    def test_full_failure_during_final_pr_or_fast_reads_prevents_merge(self):
        for phase in ("pr", "fast"):
            with self.subTest(phase=phase):
                api = FastAPI()
                def fail():
                    if len(api.full_runs) == 1:
                        api.full_runs.append(main_build(101, conclusion="failure", minute=2))
                if phase == "pr":
                    api.before_pr_read = lambda n: fail() if n == 2 else None
                else:
                    api.on_full_jobs = lambda: setattr(api, "before_jobs_read", fail)
                messages = self.run_bot(api); self.assert_no_merge(api)
                self.assertIn("full main Build changed before merging", messages[-1])

    def test_pending_full_start_during_final_reads_does_not_block_merge(self):
        api = FastAPI()
        pending = main_build(101, "new-main", minute=2)
        pending.update(status="in_progress", conclusion=None)
        api.before_pr_read = lambda n: api.full_runs.append(pending) if n == 2 else None
        self.run_bot(api)
        self.assertTrue(any(path.endswith("/merge") for _, path, _ in api.actions))

    def test_consent_revoked_during_full_health_read_is_rechecked_before_merge(self):
        api = FastAPI(); api.on_full_jobs = lambda: api.pr.update(labels=[])
        self.run_bot(api); self.assert_no_merge(api)

    def test_fast_attempt_changed_after_full_health_pass_prevents_merge(self):
        api = FastAPI()
        api.on_full_jobs = lambda: api.runs[0].update(run_attempt=2, status="in_progress", conclusion=None)
        messages = self.run_bot(api); self.assert_no_merge(api)
        self.assertEqual(api.statuses[0]["state"], "pending")
        self.assertIn("Fast checks changed before merging", messages[-1])

    def test_main_advanced_during_health_check_prevents_merge(self):
        api = FastAPI(); api.on_full_jobs = lambda: setattr(api, "base", "new")
        messages = self.run_bot(api); self.assert_no_merge(api)
        self.assertIn("main advanced", messages[-1])

    def test_main_advanced_after_merge_does_not_dispatch_wrong_merge_sha(self):
        api = FastAPI(); api.after_merge = lambda: setattr(api, "base", "maintainer-push")
        messages = self.run_bot(api)
        self.assertEqual(api.actions, [("PUT", "pulls/1/merge", {"sha": "head", "merge_method": "squash"})])
        self.assertIn("main advanced", messages[-1])

    def test_missing_main_baseline_starts_full_work_without_pretending_success(self):
        api = FastAPI(); api.full_runs = []
        messages = self.run_bot(api); self.assert_no_merge(api)
        self.assertEqual(api.actions, [("POST", "actions/workflows/build.yml/dispatches",
                                      {"ref": "main", "inputs": {"expected_sha": "base"}})])
        self.assertIn("health is missing", messages[-1])

    def test_lost_postmerge_dispatch_is_recovered_without_rerunning_existing_failures(self):
        api = FastAPI(); api.dispatch_error = HTTPError("", 503, "unavailable", {}, None)
        self.addCleanup(api.dispatch_error.close)
        with self.assertRaises(HTTPError): self.run_bot(api)
        self.assertEqual(api.base, "merged")
        api.prs = []; api.dispatch_error = None
        self.run_bot(api)
        self.assertEqual(api.actions[-1], ("POST", "actions/workflows/build.yml/dispatches",
                                          {"ref": "main", "inputs": {"expected_sha": "merged"}}))
        count = len(api.actions)
        api.full_runs.append(main_build(101, "merged", conclusion="failure", minute=2))
        self.run_bot(api); self.assertEqual(len(api.actions), count)

    def test_active_full_run_prevents_duplicate_dispatch_even_without_a_baseline(self):
        api = FastAPI(); api.prs = []
        api.full_runs[0].update(status="queued", conclusion=None)
        self.run_bot(api); self.assertEqual(api.actions, [])


if __name__ == "__main__":
    unittest.main()
