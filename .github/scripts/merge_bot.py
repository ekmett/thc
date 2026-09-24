#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

"""Merge opted-in PRs using GitHub's required checks and expected-head guard.

Run only from trusted main. PR contents are data and are never checked out here.
"""
import json
import os
import re
import sys
import time
from dataclasses import dataclass
from urllib.error import HTTPError
from urllib.request import Request, urlopen

LABEL = "auto-merge"
BOT_LOGIN = "github-actions[bot]"
CI_SKIP = re.compile(r"\[ci skip\]", re.IGNORECASE)
CHECKS = {"build (ubuntu-latest)", "build (macos-latest)", "automation"} | {
    f"library ({os}, {backend}, handoff={handoff})"
    for os in ("ubuntu-latest", "macos-latest")
    for backend in ("ast", "bytecode")
    for handoff in ("false", "true")
}


@dataclass(frozen=True)
class Gate:
    workflow: str
    name: str
    checks: frozenset


BUILD_GATE = Gate("build.yml", "Build", frozenset(CHECKS))
FAST_GATE = Gate("fast.yml", "Fast checks", frozenset({"automation", "fast-check"}))
# This policy is code checked out from trusted main, never PR data or an event
# input. The transition PR itself still uses the old main's Build/11 policy.
PR_GATE = FAST_GATE
REQUIRED_STATUS = "required-tests"
ACTIONS_APP = 15368


class API:
    def __init__(self, repo, token):
        self.repo = repo
        self.token = token

    def call(self, method, path, body=None):
        request = Request(
            f"https://api.github.com/repos/{self.repo}/{path}",
            data=None if body is None else json.dumps(body).encode(),
            method=method,
            headers={"Authorization": f"Bearer {self.token}",
                     "Accept": "application/vnd.github+json",
                     "X-GitHub-Api-Version": "2022-11-28",
                     "Content-Type": "application/json"},
        )
        with urlopen(request, timeout=30) as response:
            data = response.read()
            return json.loads(data) if data else None

    def pages(self, path, key=None):
        for page in range(1, 101):
            result = self.call("GET", f"{path}{'&' if '?' in path else '?'}per_page=100&page={page}")
            items = result[key] if key else result
            yield from items
            if len(items) < 100:
                return
        raise RuntimeError("Pagination limit reached; refusing an incomplete view")


def eligible(pr, repo):
    labels = {item["name"] for item in pr["labels"]}
    return (pr["state"] == "open" and not pr["draft"]
            and pr["base"]["ref"] == "main"
            and pr["base"]["repo"]["full_name"] == repo
            and pr["head"].get("repo") is not None
            and pr["head"]["repo"]["full_name"] == repo
            and LABEL in labels)


def complete_attempt(api, run, gate, current=True):
    if run["status"] != "completed":
        return "pending"
    if run["conclusion"] != "success":
        if current:
            fresh = api.call("GET", f"actions/runs/{run['id']}")
            if any(fresh[key] != run[key] for key in
                   ("id", "workflow_id", "head_sha", "run_attempt", "status", "conclusion")):
                return "pending"
        return "failure"
    jobs = list(api.pages(f"actions/runs/{run['id']}/attempts/{run['run_attempt']}/jobs", "jobs"))
    names = [job["name"] for job in jobs]
    passed = (all(names.count(name) == 1 for name in gate.checks)
              and all(job["status"] == "completed" and job["conclusion"] == "success" for job in jobs))
    if current:
        fresh = api.call("GET", f"actions/runs/{run['id']}")
        if any(fresh[key] != run[key] for key in
               ("id", "workflow_id", "head_sha", "run_attempt", "status", "conclusion")):
            return "pending"
    return "success" if passed else "failure"


def workflow_result(api, sha, gate, events=("push", "pull_request", "workflow_dispatch", "merge_group")):
    workflow = api.call("GET", f"actions/workflows/{gate.workflow}")
    runs = [run for run in api.pages(f"actions/workflows/{gate.workflow}/runs?head_sha={sha}", "workflow_runs")
            if run["head_sha"] == sha and run["workflow_id"] == workflow["id"]
            and run["event"] in events]
    if not runs:
        return "missing", None
    # An older-ID rerun can supersede a newer run. Any active exact-head run
    # holds the PR gate; jobs belong to exactly the selected attempt.
    active = [run for run in runs if run["status"] != "completed"]
    run = max(active or runs, key=lambda item: (item["updated_at"], item["id"]))
    return complete_attempt(api, run, gate), run


def build_result(api, sha):
    """The full Build contract remains independently testable during bootstrap."""
    return workflow_result(api, sha, BUILD_GATE)


def main_workflow_runs(api, gate):
    workflow = api.call("GET", f"actions/workflows/{gate.workflow}")
    return [run for run in api.pages(f"actions/workflows/{gate.workflow}/runs?branch=main", "workflow_runs")
            if run["workflow_id"] == workflow["id"] and run.get("head_branch") == "main"
            and run["event"] in ("push", "workflow_dispatch")
            and (run.get("head_repository") or {}).get("full_name") == api.repo]


def main_build_runs(api):
    return main_workflow_runs(api, BUILD_GATE)


def completed_main_attempts(api):
    completed = []
    for run in main_build_runs(api):
        if run["status"] == "completed":
            completed.append(run)
        elif run["run_attempt"] > 1:
            prior = api.call("GET", f"actions/runs/{run['id']}/attempts/{run['run_attempt'] - 1}")
            if (any(prior[key] != run[key] for key in
                    ("id", "workflow_id", "head_sha", "head_branch", "event"))
                    or (prior.get("head_repository") or {}).get("full_name") != api.repo
                    or prior["run_attempt"] != run["run_attempt"] - 1
                    or prior["status"] != "completed"):
                raise RuntimeError("Cannot establish previous full Build attempt")
            completed.append(prior)
    return completed


def main_build_snapshot(runs):
    return tuple(sorted((r["id"], r["head_sha"], r["run_attempt"], r["conclusion"], r["updated_at"])
                        for r in runs))


def main_build_health(api):
    """Pending full builds do not hold PRs; completed failures do.

    A rerun retains its previous completed result. A later verified full success
    clears old results only when its source includes those completed main heads.
    """
    completed = completed_main_attempts(api)
    snapshot = main_build_snapshot(completed)
    if not completed:
        return "missing", snapshot
    latest = max(completed, key=lambda item: (item["updated_at"], item["id"], item["run_attempt"]))
    state = complete_attempt(api, latest, BUILD_GATE, current=False)
    if state != "success":
        return state, snapshot
    for sha in {run["head_sha"] for run in completed} - {latest["head_sha"]}:
        comparison = api.call("GET", f"compare/{sha}...{latest['head_sha']}")
        if comparison.get("status") not in ("ahead", "identical"):
            return "failure", snapshot
    # Catch a completed failure/rerun that appeared while reading jobs/ancestry.
    # New first attempts still pending are deliberately absent from this view.
    if main_build_snapshot(completed_main_attempts(api)) != snapshot:
        return "changed", snapshot
    return "success", snapshot


def skip_declaration(api, pr):
    """Trust the requested marker, never infer it from the changed files."""
    if CI_SKIP.search(pr.get("title") or ""):
        return "PR title"
    sha = pr["head"]["sha"]
    try:
        commit = api.call("GET", f"commits/{sha}")
    except HTTPError as error:
        if error.code != 404:
            raise
        error.close()
        return None
    if commit["sha"] != sha:
        raise RuntimeError("Head commit changed during CI-skip inspection")
    return "head commit" if CI_SKIP.search(commit["commit"]["message"]) else None


def skipped_main_commit(api, sha):
    commit = api.call("GET", f"commits/{sha}")
    if commit["sha"] != sha:
        raise RuntimeError("Main commit changed during CI-skip inspection")
    return bool(CI_SKIP.search(commit["commit"]["message"]))


def ensure_main_checks(api, sha, report, base_sha=None):
    # GITHUB_TOKEN merges suppress both ordinary push workflows. Recover each
    # missing workflow independently; an existing failed run is never retried.
    if skipped_main_commit(api, sha):
        report(f"main {sha}: [ci skip] declared; no automatic Build/Fast dispatch")
        return
    failure = None
    for gate in (BUILD_GATE, FAST_GATE):
        try:
            if any(run["head_sha"] == sha for run in main_workflow_runs(api, gate)):
                continue
            inputs = {"expected_sha": sha}
            if gate == FAST_GATE:
                if base_sha is None:
                    commit = api.call("GET", f"commits/{sha}")
                    if commit["sha"] != sha or not commit.get("parents"):
                        raise RuntimeError("Cannot establish main's parent for Fast checks")
                    base_sha = commit["parents"][0]["sha"]
                inputs["base_sha"] = base_sha
            if api.call("GET", "branches/main")["commit"]["sha"] != sha:
                report(f"main advanced; a later run will recover its {gate.name}")
                continue
            api.call("POST", f"actions/workflows/{gate.workflow}/dispatches", {
                "ref": "main", "inputs": inputs,
            })
            report(f"Dispatched missing {gate.name} for main {sha}")
        except HTTPError as error:
            # Do not let one failed dispatch prevent the other workflow from
            # starting. A later reconciliation inspects actual runs to recover.
            if failure is None:
                failure = error
            else:
                error.close()
            report(f"Could not dispatch {gate.name} for main {sha}: HTTP {error.code}")
    if failure is not None:
        raise failure


def publish_result(api, sha, state, run, gate=PR_GATE):
    # workflow_dispatch job checks are not reliably eligible for PR protection.
    # This separate commit status is produced by trusted main after inspecting
    # GitHub's actual selected workflow run/attempt, without executing any PR artifacts.
    target = (f"{run['html_url']}/attempts/{run['run_attempt']}" if run
              else f"https://github.com/{api.repo}/actions/workflows/{gate.workflow}")
    # The combined status response omits creator. The reverse-chronological
    # status history retains it; only this context's latest entry counts.
    existing = next((status for status in api.pages(f"commits/{sha}/statuses")
                     if status["context"].casefold() == REQUIRED_STATUS.casefold()), None)
    if (existing and existing["state"] == state
            and (existing.get("creator") or {}).get("login") == "github-actions[bot]"
            and existing.get("target_url") == target):
        return
    api.call("POST", f"statuses/{sha}", {
        "context": REQUIRED_STATUS, "state": state, "target_url": target,
        "description": {"success": f"All required {gate.name} jobs passed",
                        "pending": f"Waiting for the current {gate.name} attempt",
                        "failure": f"{gate.name} failed or omitted a required job"}[state],
    })


def publish_skip_result(api, pr, source):
    sha = pr["head"]["sha"]
    target = f"https://github.com/{api.repo}/pull/{pr['number']}"
    description = f"Fast checks skipped by [ci skip] in {source}"
    existing = next((status for status in api.pages(f"commits/{sha}/statuses")
                     if status["context"].casefold() == REQUIRED_STATUS.casefold()), None)
    if (existing and existing["state"] == "success"
            and (existing.get("creator") or {}).get("login") == BOT_LOGIN
            and existing.get("target_url") == target and existing.get("description") == description):
        return
    api.call("POST", f"statuses/{sha}", {
        "context": REQUIRED_STATUS, "state": "success", "target_url": target,
        "description": description,
    })


def publish_run(api, run_id, gate=PR_GATE):
    run = api.call("GET", f"actions/runs/{int(run_id)}")
    state, latest = workflow_result(api, run["head_sha"], gate)
    if latest and latest["id"] == run["id"]:
        publish_result(api, run["head_sha"], state, latest, gate)


def owner_authorized(api, number):
    events = sorted((event for event in api.pages(f"issues/{number}/events")
              if event["event"] in ("labeled", "unlabeled")
              and event.get("label", {}).get("name") == LABEL), key=lambda event: event["id"])
    if not events:
        return False
    latest = events[-1]
    if latest["event"] != "labeled":
        return False
    return (latest.get("actor") or {}).get("login") == api.repo.split("/")[0]


def still_authorized(api, pr):
    return eligible(pr, api.repo) and owner_authorized(api, pr["number"])


def dispatch(api, pr, gate=PR_GATE):
    if not still_authorized(api, pr):
        return False
    inputs = {"expected_sha": pr["head"]["sha"]}
    if gate == FAST_GATE:
        base = api.call("GET", "branches/main")["commit"]["sha"]
        fresh = api.call("GET", f"pulls/{pr['number']}")
        if (not still_authorized(api, fresh) or fresh["head"]["sha"] != pr["head"]["sha"]
                or fresh["base"]["sha"] != base
                or api.call("GET", f"compare/{base}...{fresh['head']['sha']}")["behind_by"]):
            return False
        if skip_declaration(api, fresh):
            return False
        inputs["base_sha"] = base
        pr = fresh
    publish_result(api, pr["head"]["sha"], "pending", None, gate)
    api.call("POST", f"actions/workflows/{gate.workflow}/dispatches", {
        "ref": pr["head"]["ref"], "inputs": inputs,
    })
    return True


def reconcile(api, report=print, sleep=time.sleep, gate=PR_GATE):
    # The installation configures strict, administrator-enforced protection.
    # The Actions token has no administration permission or bypass actor.
    branch = api.call("GET", "branches/main")
    protection = branch.get("protection", {}).get("required_status_checks", {})
    # The public branch response omits strict; setup verifies it with the admin
    # endpoint. Verify everything the runtime token can actually read here.
    if (not branch["protected"] or protection.get("enforcement_level") != "everyone"
            or not any(check["context"] == REQUIRED_STATUS and check["app_id"] == ACTIONS_APP
                       for check in protection.get("checks", []))):
        raise RuntimeError("main lacks enforced required-tests protection; refusing to merge")
    candidates = list(api.pages("pulls?state=open&base=main&sort=created&direction=asc"))
    # Events can be coalesced by workflow concurrency. Recover gate publication
    # for every PR, including forks and manually merged/unlabelled work.
    for candidate in candidates:
        skipped = skip_declaration(api, candidate) if gate == FAST_GATE else None
        if skipped:
            publish_skip_result(api, candidate, skipped)
            continue
        state, run = workflow_result(api, candidate["head"]["sha"], gate)
        # A policy transition must retire old Build-derived greens even on
        # unlabelled/fork PRs that do not yet have a Fast checks run.
        publish_result(api, candidate["head"]["sha"], "pending" if state == "missing" else state, run, gate)
    if gate == FAST_GATE:
        ensure_main_checks(api, branch["commit"]["sha"], report)
    updated_branch = False
    for candidate in candidates:
        number = candidate["number"]
        path = f"pulls/{number}"
        pr = api.call("GET", path)
        if not still_authorized(api, pr):
            continue
        skip_source = skip_declaration(api, pr) if gate == FAST_GATE else None
        sha = pr["head"]["sha"]
        if pr["mergeable"] is False:
            report(f"#{number}: conflicts need resolution")
            continue
        if pr["mergeable"] is None:
            report(f"#{number}: GitHub is calculating mergeability")
            continue
        base = api.call("GET", "branches/main")["commit"]["sha"]
        comparison = api.call("GET", f"compare/{base}...{sha}")
        if comparison.get("ahead_by") == 0:
            report(f"#{number}: head is already contained in main")
            continue
        if comparison["behind_by"]:
            # Only one update per pass: a merge would invalidate later updates.
            # Still inspect current-base PRs so this branch cannot serialize
            # their independent Fast checks.
            if updated_branch:
                report(f"#{number}: waiting for the next branch-update pass")
                continue
            fresh = api.call("GET", path)
            if not still_authorized(api, fresh) or fresh["head"]["sha"] != sha:
                report(f"#{number}: changed while inspecting; waiting")
                continue
            if skip_source and skip_declaration(api, fresh) != skip_source:
                report(f"#{number}: [ci skip] declaration changed before branch update; waiting")
                continue
            if skip_source == "head commit":
                # GitHub's update-branch merge commit may replace the tagged
                # head message. Keep the owner's declaration in the PR title.
                title = fresh["title"]
                api.call("PATCH", path, {"title": title + " [ci skip]"})
                fresh = api.call("GET", path)
                if (not still_authorized(api, fresh) or fresh["head"]["sha"] != sha
                        or not CI_SKIP.search(fresh["title"])):
                    report(f"#{number}: title or head changed during skip preservation; waiting")
                    continue
            updated_branch = True
            try:
                api.call("PUT", f"{path}/update-branch", {"expected_head_sha": sha})
            except HTTPError as error:
                if error.code in (403, 405):
                    error.close()
                    headers = error.headers or {}
                    if error.code == 403 and (headers.get("Retry-After")
                                              or headers.get("X-RateLimit-Remaining") == "0"):
                        report(f"#{number}: branch update rate limited; waiting for a later run")
                        return
                    # The built-in token cannot update some workflow-changing
                    # branches. Keep this PR blocked without starving others.
                    report(f"#{number}: GitHub refused branch update (HTTP {error.code}); "
                           "a maintainer must update the branch against main and push "
                           "(workflow edits can require a manual update)")
                    continue
                if error.code in (409, 422):
                    error.close()
                    # 422 also covers an expected_head_sha race. Re-read all
                    # state on the next run; never dispatch this rejected head.
                    report(f"#{number}: GitHub rejected branch update (HTTP {error.code}); "
                           "a later run will recheck the branch; update manually if it persists")
                    continue
                raise
            for _ in range(15):
                sleep(2)
                updated = api.call("GET", path)
                if not still_authorized(api, updated):
                    break
                if updated["head"]["sha"] != sha:
                    # Token-authored pushes don't start ordinary push CI.
                    # Dispatch is explicit and verifies the expected commit.
                    new_skip = skip_declaration(api, updated) if gate == FAST_GATE else None
                    if new_skip:
                        publish_skip_result(api, updated, new_skip)
                        report(f"#{number}: updated against main; [ci skip] retained on new head")
                    elif dispatch(api, updated, gate):
                        report(f"#{number}: updated against main and dispatched {gate.name}")
                    else:
                        report(f"#{number}: changed before dispatch; waiting")
                    break
            else:
                report(f"#{number}: branch update pending; a later run will dispatch {gate.name}")
            continue
        state, run = (("success", None) if skip_source else
                      workflow_result(api, sha, gate))
        if skip_source:
            publish_skip_result(api, pr, skip_source)
        elif run:
            publish_result(api, sha, state, run, gate)
        if state == "failure":
            report(f"#{number}: checks failed; waiting for a fix or manual rerun")
            continue
        if state != "success":
            if state == "missing":
                if dispatch(api, pr, gate):
                    report(f"#{number}: dispatched missing {gate.name}")
                else:
                    report(f"#{number}: changed before dispatch; waiting")
            else:
                report(f"#{number}: waiting for required checks ({state})")
            continue
        if gate == FAST_GATE:
            health, health_snapshot = main_build_health(api)
            if health != "success":
                report(f"#{number}: full main Build health is {health}; automatic merges paused")
                return
        # Recheck consent and head immediately before the mutation. GitHub
        # enforces strict checks again atomically when accepting the merge.
        fresh = api.call("GET", path)
        if not still_authorized(api, fresh) or fresh["head"]["sha"] != sha:
            report(f"#{number}: changed while inspecting; waiting")
            return
        if api.call("GET", "branches/main")["commit"]["sha"] != base:
            report(f"#{number}: main advanced; waiting for an updated build")
            return
        # An unrelated approval-required run can leave an otherwise eligible
        # PR unstable. Our trusted selected-workflow gate still must pass; GitHub decides
        # atomically whether protection permits the expected-head merge.
        if fresh["mergeable_state"] not in ("clean", "unstable"):
            report(f"#{number}: GitHub merge state is {fresh['mergeable_state']}")
            continue
        final_skip = skip_declaration(api, fresh) if gate == FAST_GATE else None
        if final_skip != skip_source:
            if final_skip:
                publish_skip_result(api, fresh, final_skip)
            else:
                changed_state, changed_run = workflow_result(api, sha, gate)
                publish_result(api, sha, "pending" if changed_state == "missing" else changed_state,
                               changed_run, gate)
            report(f"#{number}: [ci skip] declaration changed before merging; waiting")
            return
        if final_skip:
            publish_skip_result(api, fresh, final_skip)
        else:
            final_state, final_run = workflow_result(api, sha, gate)
            if (final_state != "success" or final_run["id"] != run["id"]
                    or final_run["run_attempt"] != run["run_attempt"]):
                if final_run:
                    publish_result(api, sha, final_state, final_run, gate)
                report(f"#{number}: {gate.name} changed before merging; waiting")
                return
        if (gate == FAST_GATE
                and main_build_snapshot(completed_main_attempts(api)) != health_snapshot):
            # A full result may have arrived during the final consent/Fast
            # reads. Keep this last observation adjacent to the mutation;
            # pending first attempts still do not block automatic merging.
            report(f"#{number}: full main Build changed before merging; waiting")
            return
        try:
            merge = {"sha": sha, "merge_method": "squash"}
            if final_skip:
                title = fresh["title"]
                merge["commit_title"] = (CI_SKIP.sub("[ci skip]", title, count=1)
                                          if CI_SKIP.search(title) else title + " [ci skip]")
            result = api.call("PUT", f"{path}/merge", merge)
        except HTTPError as error:
            if error.code in (405, 409):
                report(f"#{number}: merge requirements changed; GitHub refused the merge")
                return
            raise
        if not result["merged"]:
            raise RuntimeError(f"GitHub did not merge #{number}")
        report(f"#{number}: merged {sha}")
        if gate == FAST_GATE:
            merged = result.get("sha")
            if not isinstance(merged, str) or not merged:
                raise RuntimeError("Successful merge response lacks its main commit SHA")
            # Send the actual merge result and previous main to Fast checks so
            # it can select affected tests and seed trusted current-main caches.
            # Build retains its original expected_sha-only input contract.
            ensure_main_checks(api, merged, report, base_sha=base)
        return  # A later reconciliation rechecks the next PR against new main.


if __name__ == "__main__":
    messages = []
    def report(message):
        print(message)
        messages.append(message)
    api = API(os.environ["GITHUB_REPOSITORY"], os.environ["GH_TOKEN"])
    if len(sys.argv) == 3 and sys.argv[1] == "--publish-run":
        publish_run(api, sys.argv[2])
    else:
        reconcile(api, report)
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a") as summary:
            summary.write("\n".join(f"- {message}" for message in messages) + "\n")
