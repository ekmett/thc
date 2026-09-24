# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

import copy
import unittest
from urllib.error import HTTPError

from merge_bot import (ACTIONS_APP, CHECKS, REQUIRED_STATUS, BUILD_GATE, FAST_GATE, PR_GATE,
                       BULK_LABEL, BULK_PREFIX, bulk_chain, candidate_manifest, select_bulk,
                       discard_candidate, mark_solo_retest, solo_retest_cutoff,
                       build_result, main_build_health, owner_authorized,
                       publish_run as publish_selected_run, reconcile, workflow_result, dispatch, ensure_main_checks)


def publish_run(api, run_id):
    # Existing regressions explicitly retain the transition's Build/11 policy.
    return publish_selected_run(api, run_id, BUILD_GATE)


def pull(number=1):
    return {"number": number, "title": "A change", "state": "open", "draft": False,
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
            if method == "PATCH" and path.startswith("pulls/"):
                target = next(p for p in self.prs if p["number"] == int(path.split("/")[1]))
                target.update(body)
                return copy.deepcopy(target)
            if path.startswith("statuses/"):
                self.statuses.insert(0, {**body, "sha": path.split("/")[1],
                                         "creator": {"login": "github-actions[bot]"}})
            if path.endswith("/update-branch"):
                if self.update_error:
                    raise self.update_error
                if not self.delayed_update:
                    number = int(path.split("/")[1])
                    target = next(p for p in self.prs if p["number"] == number)
                    old_head = target["head"]["sha"]
                    target["head"]["sha"] = "updated"
                    if isinstance(self.behind, dict):
                        self.behind[old_head] = 0
                    else:
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
            sha = path.split("/")[1]
            return copy.deepcopy([item for item in self.statuses if item.get("sha", sha) == sha])
        if path.startswith("pulls?"):
            return copy.deepcopy([pr for pr in self.prs if pr["state"] == "open"])
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
                second = pull(2); second["draft"] = True
                api.prs.append(second)
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
        self.commit_messages = {}
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
                return {"sha": sha, "parents": [{"sha": self.commit_parents.get(sha, "base")}],
                        "commit": {"message": self.commit_messages.get(sha, "ordinary commit")}}
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
            self.commit_messages["merged"] = body.get("commit_title", "ordinary merge")
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

    def test_title_tag_skips_pr_and_postmerge_ci_with_explicit_status(self):
        api = FastAPI(); api.runs = []
        api.pr["title"] = "Clarify docs [CI SKIP]"
        self.run_bot(api)
        self.assertEqual(api.actions, [("PUT", "pulls/1/merge", {
            "sha": "head", "merge_method": "squash", "commit_title": "Clarify docs [ci skip]"})])
        self.assertEqual(api.statuses[0]["state"], "success")
        self.assertEqual(api.statuses[0]["description"], "Fast checks skipped by [ci skip] in PR title")
        self.assertEqual(api.statuses[0]["target_url"], "https://github.com/ekmett/thc/pull/1")
        self.assertNotIn("actions/workflows/fast.yml/dispatches", [path for _, path, _ in api.actions])

    def test_head_commit_tag_survives_squash_and_skips_main_dispatch(self):
        api = FastAPI(); api.runs = []
        api.commit_messages["head"] = "Document interface [ci skip]\n\nDetails"
        self.run_bot(api)
        self.assertEqual(api.actions, [("PUT", "pulls/1/merge", {
            "sha": "head", "merge_method": "squash", "commit_title": "A change [ci skip]"})])
        self.assertIn("[ci skip]", api.commit_messages["merged"])
        self.assertEqual(api.statuses[0]["description"], "Fast checks skipped by [ci skip] in head commit")
        before = len(api.actions)
        api.prs = []
        self.run_bot(api)
        self.assertEqual(len(api.actions), before)

    def test_head_only_tag_survives_bot_branch_update_without_fast(self):
        api = FastAPI(); api.runs = []; api.behind = 1
        api.commit_messages["head"] = "Document interface [ci skip]"
        self.run_bot(api)
        self.assertEqual(api.actions, [
            ("PATCH", "pulls/1", {"title": "A change [ci skip]"}),
            ("PUT", "pulls/1/update-branch", {"expected_head_sha": "head"})])
        self.assertEqual(api.pr["head"]["sha"], "updated")
        self.assertEqual(api.statuses[0]["state"], "success")
        self.assertIn("PR title", api.statuses[0]["description"])
        self.run_bot(api)
        self.assertIn(("PUT", "pulls/1/merge", {
            "sha": "updated", "merge_method": "squash", "commit_title": "A change [ci skip]"}),
            api.actions)
        self.assertFalse(any(path.endswith("/dispatches") for _, path, _ in api.actions))

    def test_removed_title_tag_retires_skip_status_before_merge(self):
        api = FastAPI(); api.runs = []
        api.pr["title"] = "Docs [ci skip]"
        api.before_pr_read = lambda count: api.pr.update(title="Docs") if count == 2 else None
        messages = self.run_bot(api)
        self.assert_no_merge(api)
        self.assertEqual(api.statuses[0]["state"], "pending")
        self.assertIn("declaration changed", messages[-1])

    def test_edited_title_revokes_prior_skip_status_for_same_head(self):
        api = FastAPI(); api.runs = []
        api.pr.update(title="Docs [ci skip]", labels=[])
        self.run_bot(api)
        self.assertEqual(api.statuses[0]["state"], "success")
        api.pr["title"] = "Docs"
        self.run_bot(api)
        self.assertEqual(api.statuses[0]["state"], "pending")
        self.assertTrue(api.statuses[0]["target_url"].endswith("fast.yml"))

    def test_skip_status_does_not_require_merge_label_or_file_inspection(self):
        api = FastAPI(); api.runs = []
        api.pr.update(title="Docs [ci skip]", labels=[])
        api.pr["head"]["repo"]["full_name"] = "fork/thc"
        self.run_bot(api)
        self.assertEqual(api.actions, [])
        self.assertEqual(api.statuses[0]["state"], "success")

    def test_skip_workflows_have_metadata_trigger_and_title_gate(self):
        from pathlib import Path
        workflows = Path(__file__).parents[1] / "workflows"
        merge = (workflows / "merge.yml").read_text()
        fast = (workflows / "fast.yml").read_text()
        self.assertIn("pull_request_target:", merge)
        self.assertIn("opened, synchronize, edited", merge)
        self.assertIn("!contains(github.event.pull_request.title, '[ci skip]')", fast)

    def test_missing_fast_runs_dispatch_all_current_heads_in_one_pass(self):
        api = FastAPI(); api.runs = []
        second = pull(2)
        second["head"].update(sha="second", ref="codex/second")
        api.prs.append(second)
        self.run_bot(api)
        self.assertEqual(api.actions, [
            ("POST", "actions/workflows/fast.yml/dispatches", {
                "ref": "codex/change", "inputs": {"expected_sha": "head", "base_sha": "base"}}),
            ("POST", "actions/workflows/fast.yml/dispatches", {
                "ref": "codex/second", "inputs": {"expected_sha": "second", "base_sha": "base"}})])

    def test_pending_fast_run_does_not_hold_another_green_pr(self):
        api = FastAPI()
        api.runs[0].update(status="in_progress", conclusion=None)
        second = pull(2)
        second["head"].update(sha="second", ref="codex/second")
        api.prs.append(second)
        api.runs.append({**build(2), "workflow_id": 18, "head_sha": "second"})
        messages = self.run_bot(api)
        self.assertIn("#1: waiting for required checks (pending)", messages)
        self.assertIn(("PUT", "pulls/2/merge", {"sha": "second", "merge_method": "squash"}), api.actions)

    def test_unknown_mergeability_does_not_hold_another_green_pr(self):
        api = FastAPI(); api.pr["mergeable"] = None
        second = pull(2)
        second["head"].update(sha="second", ref="codex/second")
        api.prs.append(second)
        api.runs.append({**build(2), "workflow_id": 18, "head_sha": "second"})
        messages = self.run_bot(api)
        self.assertIn("#1: GitHub is calculating mergeability", messages)
        self.assertIn(("PUT", "pulls/2/merge", {"sha": "second", "merge_method": "squash"}), api.actions)

    def test_one_stale_update_still_dispatches_another_current_head(self):
        api = FastAPI(); api.behind = {"head": 1}; api.runs = []
        second = pull(2)
        second["head"].update(sha="second", ref="codex/second")
        api.prs.append(second)
        self.run_bot(api)
        self.assertEqual(api.actions, [
            ("PUT", "pulls/1/update-branch", {"expected_head_sha": "head"}),
            ("POST", "actions/workflows/fast.yml/dispatches", {
                "ref": "codex/change", "inputs": {"expected_sha": "updated", "base_sha": "base"}}),
            ("POST", "actions/workflows/fast.yml/dispatches", {
                "ref": "codex/second", "inputs": {"expected_sha": "second", "base_sha": "base"}})])

    def test_only_one_stale_branch_update_per_pass(self):
        api = FastAPI(); api.behind = {"head": 1, "second": 1}
        second = pull(2)
        second["head"].update(sha="second", ref="codex/second")
        api.prs.append(second)
        messages = self.run_bot(api)
        self.assertEqual([path for method, path, _ in api.actions if method == "PUT"],
                         ["pulls/1/update-branch"])
        self.assertIn("#2: waiting for the next branch-update pass", messages)

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


class BulkAPI(FastAPI):
    base_sha = "a" * 40
    first_sha = "b" * 40
    second_sha = "c" * 40
    merged_sha = "f" * 40

    def __init__(self):
        super().__init__()
        self.base = self.base_sha
        self.pr["base"]["sha"] = self.base_sha
        self.pr["head"].update(sha=self.first_sha, ref="feature/first")
        self.pr["labels"] = [{"name": BULK_LABEL}]
        second = pull(2)
        second["base"]["sha"] = self.base_sha
        second["head"].update(sha=self.second_sha, ref="feature/second")
        second["labels"] = [{"name": BULK_LABEL}]
        self.prs.append(second)
        self.events_by_pr = {number: [{"id": number, "event": "labeled", "label": {"name": BULK_LABEL},
                                       "actor": {"login": "ekmett"}}] for number in (1, 2)}
        self.next_event = 3
        self.full_runs[0]["head_sha"] = self.base_sha
        self.main_fast_runs[0]["head_sha"] = self.base_sha
        self.runs = []
        self.refs = {}
        self.parents = {}
        self.files = {1: [{"filename": "first.txt", "status": "modified"}],
                      2: [{"filename": "second.txt", "status": "added"}]}
        self.fast_forward_first = False

    def call(self, method, path, body=None):
        if method == "GET" and path.startswith("git/ref/heads/"):
            branch = path[len("git/ref/heads/"):]
            if branch not in self.refs:
                raise HTTPError("", 404, "Missing ref", {}, None)
            return {"object": {"sha": self.refs[branch]}}
        if method == "GET" and path.startswith("commits/") and path.split("/")[1] in self.parents:
            sha = path.split("/")[1]
            return {"sha": sha, "parents": [{"sha": parent} for parent in self.parents[sha]],
                    "commit": {"message": self.commit_messages.get(sha, "Batch PR")}}
        if method == "GET" and path.startswith("compare/"):
            head = path.split("...")[-1]
            return {"ahead_by": 1, "behind_by": self.behind.get(head, 0) if isinstance(self.behind, dict) else self.behind,
                    "status": "ahead"}
        if method == "POST" and path == "git/refs":
            self.mutations.append((method, path, body))
            self.refs[body["ref"][len("refs/heads/"):]] = body["sha"]
            return {"object": {"sha": body["sha"]}}
        if method == "POST" and path == "merges":
            self.mutations.append((method, path, body))
            branch = body["base"]
            old = self.refs[branch]
            if self.fast_forward_first and old == self.base_sha:
                sha = body["head"]
            else:
                sha = ("d" if old == self.base_sha else "e") * 40
                self.parents[sha] = [old, body["head"]]
            self.refs[branch] = sha
            return {"sha": sha}
        if method == "POST" and path == "pulls":
            self.mutations.append((method, path, body))
            result = pull(3)
            result["base"]["sha"] = self.base
            result["head"].update(sha=self.refs[body["head"]], ref=body["head"])
            result.update(labels=[], user={"login": "github-actions[bot]"}, body=body["body"])
            self.prs.append(result)
            return copy.deepcopy(result)
        if method == "POST" and path.startswith("issues/") and path.endswith("/labels"):
            self.mutations.append((method, path, body))
            number = int(path.split("/")[1])
            target = next(pr for pr in self.prs if pr["number"] == number)
            for label in body["labels"]:
                target["labels"].append({"name": label})
                self.events_by_pr[number].append({"id": self.next_event, "event": "labeled",
                    "label": {"name": label}, "actor": {"login": "github-actions[bot]"}})
                self.next_event += 1
            return copy.deepcopy(target["labels"])
        if method == "DELETE" and path.startswith("issues/"):
            self.mutations.append((method, path, body))
            number = int(path.split("/")[1])
            label = path.split("/")[-1]
            target = next(pr for pr in self.prs if pr["number"] == number)
            target["labels"] = [item for item in target["labels"] if item["name"] != label]
            self.events_by_pr[number].append({"id": self.next_event, "event": "unlabeled",
                "label": {"name": label}, "actor": {"login": "github-actions[bot]"}})
            self.next_event += 1
            return copy.deepcopy(target["labels"])
        if method == "PATCH" and path.startswith("pulls/"):
            self.mutations.append((method, path, body))
            target = next(pr for pr in self.prs if pr["number"] == int(path.split("/")[1]))
            target.update(body)
            return copy.deepcopy(target)
        if method == "DELETE" and path.startswith("git/refs/heads/"):
            self.mutations.append((method, path, body))
            del self.refs[path[len("git/refs/heads/"):]]
            return None
        if method == "POST" and path.startswith("actions/runs/") and path.endswith("/rerun"):
            self.mutations.append((method, path, body))
            run = next(run for run in self.runs if run["id"] == int(path.split("/")[2]))
            run.update(run_attempt=run["run_attempt"] + 1, status="in_progress", conclusion=None)
            return None
        if method == "POST" and path.startswith("actions/runs/") and path.endswith("/cancel"):
            self.mutations.append((method, path, body))
            run = next(run for run in self.runs if run["id"] == int(path.split("/")[2]))
            run.update(status="completed", conclusion="cancelled")
            return None
        if method == "PUT" and path == "pulls/3/merge":
            self.mutations.append((method, path, body))
            self.base = self.merged_sha
            for pr in self.prs:
                pr["state"] = "closed"
            return {"merged": True, "sha": self.merged_sha}
        return super().call(method, path, body)

    def pages(self, path, key=None):
        if path.startswith("pulls/") and path.endswith("/files"):
            return copy.deepcopy(self.files[int(path.split("/")[1])])
        if path.startswith("issues/") and path.endswith("/events"):
            return copy.deepcopy(self.events_by_pr[int(path.split("/")[1])])
        return super().pages(path, key)

    def finish_fast(self, conclusion="success"):
        candidate = self.prs[2]
        self.runs.append({**build(3), "workflow_id": 18, "event": "workflow_dispatch",
                          "head_repository": {"full_name": self.repo},
                          "head_branch": candidate["head"]["ref"], "head_sha": candidate["head"]["sha"],
                          "conclusion": conclusion})


class BulkMergeTest(unittest.TestCase):
    def run_bot(self, api):
        messages = []
        reconcile(api, messages.append, lambda _: None)
        return messages

    def pending_batch(self):
        api = BulkAPI()
        self.run_bot(api)
        api.finish_fast()
        api.runs[-1].update(status="queued", conclusion=None)
        return api

    def add_member_run(self, api, run_id=10, **changes):
        run = {**build(run_id), "workflow_id": 18, "head_sha": api.first_sha,
               "head_branch": "feature/first", "head_repository": {"full_name": api.repo},
               "status": "queued", "conclusion": None, **changes}
        api.runs.append(run)
        return run

    def retire(self, api):
        messages = []
        discard_candidate(api, api.prs[2], messages.append, "test retirement")
        return messages

    def test_retired_candidate_cancels_its_active_fast_runs_only(self):
        api = self.pending_batch()
        candidate = api.prs[2]
        api.runs.append({**api.runs[0], "id": 4, "event": "pull_request"})
        self.add_member_run(api)
        messages = self.retire(api)
        self.assertEqual(api.prs[2]["state"], "closed")
        self.assertNotIn(candidate["head"]["ref"], api.refs)
        self.assertEqual([path for _, path, _ in api.actions if path.endswith("/cancel")],
                         ["actions/runs/3/cancel", "actions/runs/4/cancel"])
        self.assertEqual(api.runs[2]["status"], "queued")  # Member check is independent.
        self.assertTrue(any("cancelled retired candidate Fast run 3" in line for line in messages))

    def test_retired_candidate_ignores_other_or_completed_runs(self):
        changes = ({"workflow_id": 17}, {"head_sha": "9" * 40},
                   {"head_branch": "thc-bulk/other"},
                   {"head_repository": {"full_name": "fork/thc"}},
                   {"head_repository": None}, {"event": "push"},
                   {"status": "completed", "conclusion": "success"})
        for change in changes:
            with self.subTest(change=change):
                api = self.pending_batch()
                api.runs[0].update(change)
                self.retire(api)
                self.assertFalse(any(path.endswith("/cancel") for _, path, _ in api.actions))

    def test_retired_candidate_rechecks_run_and_attempt_before_cancel(self):
        for change in ({"status": "completed", "conclusion": "success"},
                       {"run_attempt": 2}, {"head_sha": "9" * 40},
                       {"head_branch": "thc-bulk/other"},
                       {"head_repository": {"full_name": "fork/thc"}}):
            with self.subTest(change=change):
                api = self.pending_batch()
                original = api.call
                def call(method, path, body=None):
                    if method == "GET" and path == "actions/runs/3":
                        api.runs[0].update(change)
                    return original(method, path, body)
                api.call = call
                self.retire(api)
                self.assertFalse(any(path.endswith("/cancel") for _, path, _ in api.actions))

    def test_retired_candidate_cancel_refusal_does_not_change_solo_retest(self):
        api = self.pending_batch()
        original = api.call
        error = HTTPError("", 409, "already completed", {}, None)
        self.addCleanup(error.close)
        def call(method, path, body=None):
            if method == "POST" and path == "actions/runs/3/cancel":
                raise error
            return original(method, path, body)
        api.call = call
        messages = self.retire(api)
        self.assertEqual(api.prs[2]["state"], "closed")
        self.assertIsNone(solo_retest_cutoff(api, api.first_sha))
        self.assertTrue(any("cancellation unavailable (HTTP 409)" in line for line in messages))

    def test_pending_batch_replaces_individual_fast_without_green_status(self):
        for status in ("queued", "in_progress", "waiting", "pending"):
            api = self.pending_batch()
            self.add_member_run(api, status=status)
            self.run_bot(api)
            self.assertIn(("POST", "actions/runs/10/cancel", None), api.actions)
            self.assertEqual(solo_retest_cutoff(api, api.first_sha), 10)
            self.assertFalse(any(item["sha"] == api.first_sha and item["state"] == "success"
                                 for item in api.statuses))
            marker = next(i for i, (_, path, body) in enumerate(api.mutations)
                          if path == f"statuses/{api.first_sha}" and "solo-retest" in body["target_url"])
            cancel = api.mutations.index(("POST", "actions/runs/10/cancel", None))
            self.assertLess(marker, cancel)
            self.assertEqual(api.runs[0]["status"], "queued")  # Combined run remains live.

    def test_only_exact_member_fast_runs_are_cancelled(self):
        for changes in ({"workflow_id": 17}, {"head_sha": "9" * 40},
                        {"head_branch": "other"}, {"head_repository": {"full_name": "fork/thc"}},
                        {"head_repository": None}, {"event": "push"}, {"event": "merge_group"},
                        {"status": "completed", "conclusion": "failure"},
                        {"status": "completed", "conclusion": "success"}):
            with self.subTest(changes=changes):
                api = self.pending_batch(); self.add_member_run(api, **changes)
                self.run_bot(api)
                self.assertFalse(any(path.endswith("/cancel") for _, path, _ in api.actions))
                self.assertIsNone(solo_retest_cutoff(api, api.first_sha))

    def test_member_dispatch_can_be_replaced_but_missing_combined_run_cannot(self):
        api = self.pending_batch(); self.add_member_run(api, event="workflow_dispatch")
        self.run_bot(api)
        self.assertIn(("POST", "actions/runs/10/cancel", None), api.actions)
        api = BulkAPI(); self.add_member_run(api)
        self.run_bot(api)
        self.assertFalse(any(path.endswith("/cancel") for _, path, _ in api.actions))

    def test_run_completion_or_rerun_during_inspection_is_not_cancelled(self):
        for changes in ({"status": "completed", "conclusion": "success"}, {"run_attempt": 2}):
            api = self.pending_batch(); member = self.add_member_run(api)
            original = api.call
            def call(method, path, body=None):
                if method == "GET" and path == "actions/runs/10":
                    member.update(changes)
                return original(method, path, body)
            api.call = call
            self.run_bot(api)
            self.assertFalse(any(path.endswith("/cancel") for _, path, _ in api.actions))

    def test_changed_batch_or_member_before_cancellation_is_left_alone(self):
        for mutation in (lambda a: setattr(a, "base", "9" * 40),
                         lambda a: a.prs[0]["head"].update(sha="9" * 40),
                         lambda a: a.prs[0].update(labels=[]),
                         lambda a: a.prs[1].update(labels=[]),
                         lambda a: a.prs[2].update(state="closed"),
                         lambda a: a.runs[0].update(status="completed", conclusion="failure"),
                         lambda a: a.runs[0].update(status="completed", conclusion="cancelled"),
                         lambda a: a.runs[0].update(run_attempt=2),
                         lambda a: a.refs.update({a.prs[2]["head"]["ref"]: "9" * 40})):
            api = self.pending_batch(); self.add_member_run(api)
            original = api.call
            def call(method, path, body=None):
                if method == "GET" and path == "actions/runs/10":
                    mutation(api)
                return original(method, path, body)
            api.call = call
            self.run_bot(api)
            self.assertFalse(any(path.endswith("/cancel") for _, path, _ in api.actions))

    def test_late_duplicate_advances_cutoff_without_repeated_cancellation(self):
        api = self.pending_batch(); self.add_member_run(api)
        self.run_bot(api); self.run_bot(api)
        self.assertEqual(api.actions.count(("POST", "actions/runs/10/cancel", None)), 1)
        self.add_member_run(api, run_id=12)
        self.run_bot(api)
        self.assertEqual(solo_retest_cutoff(api, api.first_sha), 12)
        self.assertIn(("POST", "actions/runs/12/cancel", None), api.actions)

    def test_wrong_branch_or_repository_combined_run_cannot_cancel_or_merge(self):
        for status, conclusion in (("queued", None), ("completed", "success")):
            for changes in ({"head_branch": "feature/unrelated"},
                            {"head_repository": {"full_name": "fork/thc"}},
                            {"head_repository": None}):
                api = self.pending_batch(); self.add_member_run(api)
                api.runs[0].update(status=status, conclusion=conclusion, **changes)
                self.run_bot(api)
                self.assertFalse(any(path.endswith(("/cancel", "/merge")) for _, path, _ in api.actions))

    def test_abandoned_batch_restarts_solo_after_deliberate_cancellation(self):
        api = self.pending_batch(); self.add_member_run(api)
        self.run_bot(api)
        api.prs[1]["labels"] = []
        self.run_bot(api)  # Invalid candidate is discarded.
        self.assertIn(("POST", "actions/runs/3/cancel", None), api.actions)
        self.run_bot(api)  # Remaining member receives a fresh individual run.
        self.assertIn(("POST", "actions/workflows/fast.yml/dispatches", {
            "ref": "feature/first", "inputs": {"expected_sha": api.first_sha, "base_sha": api.base_sha}}),
            api.actions)
        self.assertFalse(any(path.endswith("/merge") for _, path, _ in api.actions))

    def test_failed_batch_still_requires_fresh_solo_after_cancellation(self):
        api = self.pending_batch(); self.add_member_run(api)
        self.run_bot(api)
        api.runs[0].update(status="completed", conclusion="failure")
        self.run_bot(api)
        self.assertEqual([label["name"] for label in api.prs[0]["labels"]], ["auto-merge"])
        self.assertEqual(solo_retest_cutoff(api, api.first_sha), 10)
        self.assertIn(("POST", "actions/workflows/fast.yml/dispatches", {
            "ref": "feature/first", "inputs": {"expected_sha": api.first_sha, "base_sha": api.base_sha}}),
            api.actions)

    def test_exhausted_combined_retries_restore_fresh_solo_checks(self):
        for conclusion in ("cancelled", "timed_out"):
            api = self.pending_batch(); self.add_member_run(api)
            self.run_bot(api)
            api.runs[0].update(status="completed", conclusion=conclusion, run_attempt=3)
            self.run_bot(api)
            self.assertEqual(api.prs[2]["state"], "closed")
            self.assertEqual([label["name"] for label in api.prs[0]["labels"]], ["auto-merge"])
            self.assertIn(("POST", "actions/workflows/fast.yml/dispatches", {
                "ref": "feature/first", "inputs": {"expected_sha": api.first_sha, "base_sha": api.base_sha}}),
                api.actions)

    def test_interrupted_exhausted_retry_fallback_finishes_label_swap(self):
        api = self.pending_batch(); self.add_member_run(api)
        self.run_bot(api)
        api.runs[0].update(status="completed", conclusion="timed_out", run_attempt=3)
        api.call("POST", "issues/1/labels", {"labels": ["auto-merge"]})
        self.run_bot(api)
        self.assertEqual([label["name"] for label in api.prs[0]["labels"]], ["auto-merge"])
        self.assertTrue(owner_authorized(api, 1))
        self.assertEqual(api.prs[2]["state"], "closed")

    def test_batch_failure_advances_deferred_cutoff_past_late_individual_green(self):
        api = self.pending_batch(); self.add_member_run(api)
        self.run_bot(api)
        self.add_member_run(api, run_id=12, status="completed", conclusion="success")
        api.runs[0].update(status="completed", conclusion="failure")
        self.run_bot(api)
        self.assertEqual(solo_retest_cutoff(api, api.first_sha), 12)
        self.assertFalse(any(path.endswith("/merge") for _, path, _ in api.actions))
        self.assertIn(("POST", "actions/workflows/fast.yml/dispatches", {
            "ref": "feature/first", "inputs": {"expected_sha": api.first_sha, "base_sha": api.base_sha}}),
            api.actions)

    def test_finished_during_cancel_is_recoverable_and_unexpected_errors_surface(self):
        for code in (403, 409, 503):
            api = self.pending_batch(); self.add_member_run(api)
            original = api.call
            error = HTTPError("", code, "cancel", {}, None)
            self.addCleanup(error.close)
            def call(method, path, body=None):
                if method == "POST" and path == "actions/runs/10/cancel":
                    raise error
                return original(method, path, body)
            api.call = call
            if code == 503:
                with self.assertRaises(HTTPError): self.run_bot(api)
            else:
                messages = self.run_bot(api)
                self.assertTrue(any("cancellation refused" in line for line in messages))
            self.assertEqual(solo_retest_cutoff(api, api.first_sha), 10)

    def test_disjoint_bulk_heads_form_one_checked_candidate(self):
        api = BulkAPI()
        self.run_bot(api)
        candidate = api.prs[2]
        self.assertTrue(candidate["head"]["ref"].startswith(BULK_PREFIX))
        self.assertEqual(bulk_chain(api, api.base_sha, candidate["head"]["sha"],
                                    [api.first_sha, api.second_sha]), [api.first_sha, api.second_sha])
        self.assertEqual([part["sha"] for part in candidate_manifest(candidate)["components"]],
                         [api.first_sha, api.second_sha])
        dispatches = [body for _, path, body in api.actions if path == "actions/workflows/fast.yml/dispatches"]
        self.assertEqual(dispatches, [{"ref": candidate["head"]["ref"], "inputs": {
            "expected_sha": candidate["head"]["sha"], "base_sha": api.base_sha}}])

    def test_tagged_bulk_member_takes_solo_skip_and_untagged_member_is_not_green(self):
        api = BulkAPI()
        api.prs[0]["title"] = "Docs [ci skip]"
        self.run_bot(api)
        self.assertEqual(len(api.prs), 2)  # No candidate can inherit a member's skip.
        self.assertEqual([status["state"] for status in api.statuses if status["sha"] == api.first_sha],
                         ["success"])
        self.assertEqual(next(status["state"] for status in api.statuses if status["sha"] == api.second_sha),
                         "pending")
        self.assertIn(("PUT", "pulls/1/merge", {
            "sha": api.first_sha, "merge_method": "squash", "commit_title": "Docs [ci skip]"}), api.actions)

    def test_tagged_bulk_member_does_not_wait_for_unrelated_candidate(self):
        api = BulkAPI(); self.run_bot(api)
        candidate = api.prs[2]
        docs = pull(4)
        docs["base"]["sha"] = api.base_sha
        docs["head"].update(sha="9" * 40, ref="feature/docs")
        docs["title"] = "Docs [ci skip]"
        docs["labels"] = [{"name": BULK_LABEL}]
        api.prs.append(docs)
        api.events_by_pr[4] = [{"id": 4, "event": "labeled", "label": {"name": BULK_LABEL},
                                "actor": {"login": "ekmett"}}]
        self.run_bot(api)
        self.assertIn(("PUT", "pulls/4/merge", {
            "sha": "9" * 40, "merge_method": "squash", "commit_title": "Docs [ci skip]"}),
            api.actions)
        self.assertFalse(any(status["sha"] == candidate["head"]["sha"] and status["state"] == "success"
                             for status in api.statuses))

    def test_mixed_bulk_selection_keeps_two_untagged_members_in_candidate(self):
        api = BulkAPI()
        api.prs[0]["title"] = "Docs [ci skip]"
        third = pull(3)
        third["base"]["sha"] = api.base_sha
        third["head"].update(sha="9" * 40, ref="feature/third")
        third["labels"] = [{"name": BULK_LABEL}]
        api.prs.append(third)
        api.events_by_pr[3] = [{"id": 3, "event": "labeled", "label": {"name": BULK_LABEL},
                                "actor": {"login": "ekmett"}}]
        api.files[3] = [{"filename": "third.txt", "status": "modified"}]
        self.assertEqual([pr["number"] for pr in select_bulk(api, api.prs, api.base_sha)], [2, 3])

    def test_existing_candidate_still_requires_combined_fast_when_member_later_tags(self):
        api = BulkAPI(); self.run_bot(api)
        api.prs[0]["title"] = "Docs [ci skip]"
        candidate = api.prs[2]
        self.assertFalse(any(status["sha"] == candidate["head"]["sha"] and status["state"] == "success"
                             for status in api.statuses))
        api.finish_fast()
        self.run_bot(api)
        self.assertIn(("PUT", "pulls/3/merge", {"sha": candidate["head"]["sha"],
                                                  "merge_method": "merge"}), api.actions)

    def test_success_promotes_exact_checked_ancestry(self):
        api = BulkAPI(); self.run_bot(api); api.finish_fast()
        self.run_bot(api)
        candidate = api.prs[2]
        self.assertIn(("PUT", "pulls/3/merge", {"sha": candidate["head"]["sha"],
                                                  "merge_method": "merge"}), api.actions)
        self.assertEqual(api.base, api.merged_sha)
        self.assertFalse(any(path.startswith("issues/") for _, path, _ in api.actions))

    def test_failed_combined_check_converts_to_owner_authorized_solo(self):
        api = BulkAPI(); self.run_bot(api); api.finish_fast("failure")
        self.run_bot(api)
        self.assertEqual([item["name"] for item in api.prs[0]["labels"]], ["auto-merge"])
        self.assertEqual([item["name"] for item in api.prs[1]["labels"]], ["auto-merge"])
        self.assertTrue(owner_authorized(api, 1))
        self.assertFalse(api.prs[2]["state"] == "open")
        refs = [body["ref"] for _, path, body in api.actions
                if path == "actions/workflows/fast.yml/dispatches"]
        self.assertIn("feature/first", refs)
        self.assertIn("feature/second", refs)

    def test_old_individual_green_cannot_replace_fresh_solo_retest(self):
        api = BulkAPI()
        api.runs = [{**build(10), "workflow_id": 18, "head_sha": api.first_sha},
                    {**build(11), "workflow_id": 18, "head_sha": api.second_sha}]
        self.run_bot(api); api.finish_fast("failure")
        self.run_bot(api)
        self.assertFalse(any(path in ("pulls/1/merge", "pulls/2/merge") for _, path, _ in api.actions))
        self.assertEqual([item["name"] for item in api.prs[0]["labels"]], ["auto-merge"])
        self.assertEqual([body["ref"] for _, path, body in api.actions
                          if path == "actions/workflows/fast.yml/dispatches"][-2:],
                         ["feature/first", "feature/second"])
        before = len(api.actions)
        self.run_bot(api)
        self.assertFalse(any(path.endswith("/merge") for _, path, _ in api.actions[before:]))

    def test_interrupted_label_swap_recovers_without_old_green(self):
        api = BulkAPI(); self.run_bot(api); api.finish_fast("failure")
        api.call("POST", "issues/1/labels", {"labels": ["auto-merge"]})
        self.run_bot(api)
        self.assertEqual([item["name"] for item in api.prs[0]["labels"]], ["auto-merge"])
        self.assertEqual([item["name"] for item in api.prs[1]["labels"]], ["auto-merge"])
        self.assertTrue(owner_authorized(api, 1))

    def test_stale_failure_listing_cannot_trigger_solo_fallback(self):
        api = BulkAPI(); self.run_bot(api); api.finish_fast("failure")
        api.before_run_read = lambda: api.runs[0].update(run_attempt=2, status="in_progress", conclusion=None)
        self.run_bot(api)
        self.assertEqual([item["name"] for item in api.prs[0]["labels"]], [BULK_LABEL])
        self.assertEqual(api.prs[2]["state"], "open")

    def test_candidate_pr_run_does_not_mask_trusted_dispatch(self):
        api = BulkAPI(); self.run_bot(api); api.finish_fast()
        api.runs.append({**build(4), "workflow_id": 18, "event": "pull_request",
                         "head_sha": api.prs[2]["head"]["sha"], "status": "in_progress",
                         "conclusion": None})
        self.run_bot(api)
        self.assertIn(("PUT", "pulls/3/merge", {"sha": api.prs[2]["head"]["sha"],
                                                  "merge_method": "merge"}), api.actions)

    def test_changed_candidate_head_does_not_promote(self):
        api = BulkAPI(); self.run_bot(api); api.finish_fast()
        candidate = api.prs[2]
        candidate["head"]["sha"] = "8" * 40
        api.refs[candidate["head"]["ref"]] = "8" * 40
        api.parents["8" * 40] = api.parents["e" * 40]
        self.run_bot(api)
        self.assertNotIn("pulls/3/merge", [path for _, path, _ in api.actions])

    def test_revoked_bulk_grant_cannot_be_laundered_by_bot_labels(self):
        api = BulkAPI()
        api.events_by_pr[1].append({"id": 4, "event": "unlabeled", "label": {"name": BULK_LABEL},
                                    "actor": {"login": "ekmett"}})
        api.call("POST", "issues/1/labels", {"labels": ["auto-merge"]})
        api.call("DELETE", "issues/1/labels/bulk-merge")
        self.assertFalse(owner_authorized(api, 1))

    def test_second_failed_batch_advances_same_head_retest_cutoff(self):
        api = BulkAPI()
        api.runs = [{**build(10), "workflow_id": 18, "head_sha": api.first_sha}]
        mark_solo_retest(api, api.first_sha, "d" * 40)
        self.assertEqual(solo_retest_cutoff(api, api.first_sha), 10)
        api.runs.append({**build(12), "workflow_id": 18, "head_sha": api.first_sha})
        mark_solo_retest(api, api.first_sha, "e" * 40)
        self.assertEqual(solo_retest_cutoff(api, api.first_sha), 12)

    def test_cancelled_check_reruns_without_relabeling(self):
        api = BulkAPI(); self.run_bot(api); api.finish_fast("cancelled")
        self.run_bot(api)
        self.assertIn(("POST", "actions/runs/3/rerun", None), api.actions)
        self.assertEqual(api.runs[0]["run_attempt"], 2)
        self.assertEqual([item["name"] for item in api.prs[0]["labels"]], [BULK_LABEL])

    def test_changed_component_discards_candidate_without_relabeling(self):
        api = BulkAPI(); self.run_bot(api)
        api.prs[0]["head"]["sha"] = "9" * 40
        self.run_bot(api)
        self.assertEqual(api.prs[2]["state"], "closed")
        self.assertEqual([item["name"] for item in api.prs[0]["labels"]], [BULK_LABEL])

    def test_assembly_accepts_stale_individual_heads(self):
        api = BulkAPI(); api.behind = {api.first_sha: 2, api.second_sha: 1}
        self.run_bot(api)
        self.assertEqual(len(api.prs), 3)
        self.assertEqual([path for method, path, _ in api.actions if method == "PUT"], [])

    def test_fast_forward_first_component_preserves_head(self):
        api = BulkAPI(); api.fast_forward_first = True
        self.run_bot(api)
        self.assertEqual(bulk_chain(api, api.base_sha, api.prs[2]["head"]["sha"],
                                    [api.first_sha, api.second_sha]), [api.first_sha, api.second_sha])

    def test_overlap_avoids_batch_and_keeps_solo_bulk_gate(self):
        api = BulkAPI(); api.files[2] = [{"filename": "first.txt", "status": "modified"}]
        self.run_bot(api)
        self.assertEqual(len(api.prs), 2)
        self.assertEqual(len([x for x in api.actions if x[1] == "actions/workflows/fast.yml/dispatches"]), 2)


if __name__ == "__main__":
    unittest.main()
