#!/usr/bin/env python3
"""Merge opted-in PRs using GitHub's required checks and expected-head guard.

Run only from trusted main. PR contents are data and are never checked out here.
"""
import json
import os
import sys
import time
from urllib.error import HTTPError
from urllib.request import Request, urlopen

LABEL = "auto-merge"
CHECKS = {"build (ubuntu-latest)", "build (macos-latest)", "automation"}
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
    return (pr["state"] == "open" and not pr["draft"]
            and pr["base"]["ref"] == "main"
            and pr["base"]["repo"]["full_name"] == repo
            and pr["head"].get("repo") is not None
            and pr["head"]["repo"]["full_name"] == repo
            and LABEL in {label["name"] for label in pr["labels"]})


def build_result(api, sha):
    workflow = api.call("GET", "actions/workflows/build.yml")
    runs = [run for run in api.pages(f"actions/workflows/build.yml/runs?head_sha={sha}", "workflow_runs")
            if run["head_sha"] == sha and run["workflow_id"] == workflow["id"]
            and run["event"] in ("push", "pull_request", "workflow_dispatch", "merge_group")]
    if not runs:
        return "missing", None
    # Rerunning an old run does not change its ID. Any active execution holds
    # the gate; otherwise the most recently updated result wins, so a failed
    # rerun cannot hide behind a higher-ID success.
    active = [run for run in runs if run["status"] != "completed"]
    run = max(active or runs, key=lambda item: (item["updated_at"], item["id"]))
    if run["status"] != "completed":
        return "pending", run
    if run["conclusion"] != "success":
        return "failure", run
    # Read this attempt, not jobs left over from an earlier successful attempt
    # or identically named jobs in an unrelated workflow.
    jobs = list(api.pages(f"actions/runs/{run['id']}/attempts/{run['run_attempt']}/jobs", "jobs"))
    names = [job["name"] for job in jobs]
    passed = (all(names.count(name) == 1 for name in CHECKS)
              and all(job["status"] == "completed" and job["conclusion"] == "success" for job in jobs))
    current = api.call("GET", f"actions/runs/{run['id']}")
    if current["run_attempt"] != run["run_attempt"] or current["status"] != "completed":
        return "pending", current
    return ("success" if passed else "failure"), run


def publish_result(api, sha, state, run):
    # workflow_dispatch job checks are not reliably eligible for PR protection.
    # This separate commit status is produced by trusted main after inspecting
    # GitHub's actual Build run/attempt, without executing any PR artifacts.
    target = (f"{run['html_url']}/attempts/{run['run_attempt']}" if run
              else f"https://github.com/{api.repo}/actions/workflows/build.yml")
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
        "description": {"success": "All required Build jobs passed",
                        "pending": "Waiting for the current Build attempt",
                        "failure": "Build failed or omitted a required job"}[state],
    })


def publish_run(api, run_id):
    run = api.call("GET", f"actions/runs/{int(run_id)}")
    state, latest = build_result(api, run["head_sha"])
    if latest and latest["id"] == run["id"]:
        publish_result(api, run["head_sha"], state, latest)


def owner_authorized(api, number):
    events = [event for event in api.pages(f"issues/{number}/events")
              if event["event"] in ("labeled", "unlabeled")
              and event.get("label", {}).get("name") == LABEL]
    if not events:
        return False
    latest = max(events, key=lambda event: event["id"])
    return (latest["event"] == "labeled"
            and (latest.get("actor") or {}).get("login") == api.repo.split("/")[0])


def still_authorized(api, pr):
    return eligible(pr, api.repo) and owner_authorized(api, pr["number"])


def dispatch(api, pr):
    if not still_authorized(api, pr):
        return
    publish_result(api, pr["head"]["sha"], "pending", None)
    api.call("POST", "actions/workflows/build.yml/dispatches", {
        "ref": pr["head"]["ref"], "inputs": {"expected_sha": pr["head"]["sha"]},
    })


def reconcile(api, report=print, sleep=time.sleep):
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
        state, run = build_result(api, candidate["head"]["sha"])
        if run:
            publish_result(api, candidate["head"]["sha"], state, run)
    for candidate in candidates:
        number = candidate["number"]
        path = f"pulls/{number}"
        pr = api.call("GET", path)
        if not still_authorized(api, pr):
            continue
        sha = pr["head"]["sha"]
        if pr["mergeable"] is False:
            report(f"#{number}: conflicts need resolution")
            continue
        if pr["mergeable"] is None:
            report(f"#{number}: GitHub is calculating mergeability")
            return
        base = api.call("GET", "branches/main")["commit"]["sha"]
        comparison = api.call("GET", f"compare/{base}...{sha}")
        if comparison["behind_by"]:
            # Never update all queued branches at once: each successful merge
            # would invalidate the later branches' work.
            fresh = api.call("GET", path)
            if not still_authorized(api, fresh) or fresh["head"]["sha"] != sha:
                report(f"#{number}: changed while inspecting; waiting")
                return
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
                    return
                raise
            for _ in range(15):
                sleep(2)
                updated = api.call("GET", path)
                if not still_authorized(api, updated):
                    return
                if updated["head"]["sha"] != sha:
                    # Token-authored pushes don't start ordinary push CI.
                    # Dispatch is explicit and verifies the expected commit.
                    dispatch(api, updated)
                    report(f"#{number}: updated against main and dispatched Build")
                    return
            report(f"#{number}: branch update pending; a later run will dispatch Build")
            return
        state, run = build_result(api, sha)
        if run:
            publish_result(api, sha, state, run)
        if state == "failure":
            report(f"#{number}: checks failed; waiting for a fix or manual rerun")
            continue
        if state != "success":
            if state == "missing":
                dispatch(api, pr)
                report(f"#{number}: dispatched missing Build")
            else:
                report(f"#{number}: waiting for required checks ({state})")
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
        if fresh["mergeable_state"] != "clean":
            report(f"#{number}: GitHub merge state is {fresh['mergeable_state']}")
            continue
        final_state, final_run = build_result(api, sha)
        if (final_state != "success" or final_run["id"] != run["id"]
                or final_run["run_attempt"] != run["run_attempt"]):
            if final_run:
                publish_result(api, sha, final_state, final_run)
            report(f"#{number}: Build changed before merging; waiting")
            return
        try:
            result = api.call("PUT", f"{path}/merge", {"sha": sha, "merge_method": "squash"})
        except HTTPError as error:
            if error.code in (405, 409):
                report(f"#{number}: merge requirements changed; GitHub refused the merge")
                return
            raise
        if not result["merged"]:
            raise RuntimeError(f"GitHub did not merge #{number}")
        report(f"#{number}: merged {sha}")
        # Continue with a fresh base for the next candidate; no stale green PR
        # can be merged just because it was ready before this merge.


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
