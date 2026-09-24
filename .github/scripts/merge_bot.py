#!/usr/bin/env python3
"""Merge opted-in PRs using GitHub's required checks and expected-head guard.

Run only from trusted main. PR contents are data and are never checked out here.
"""
import json
import hashlib
import os
import re
import sys
import time
from dataclasses import dataclass
from urllib.error import HTTPError
from urllib.request import Request, urlopen

LABEL = "auto-merge"
BULK_LABEL = "bulk-merge"
BULK_PREFIX = "thc-bulk/"
BULK_LIMIT = 4
BULK_FILE_LIMIT = 100
BOT_LOGIN = "github-actions[bot]"
RETEST_MARKER = "#thc-solo-retest-after-"
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


def eligible(pr, repo, label=LABEL):
    labels = {item["name"] for item in pr["labels"]}
    return (pr["state"] == "open" and not pr["draft"]
            and pr["base"]["ref"] == "main"
            and pr["base"]["repo"]["full_name"] == repo
            and pr["head"].get("repo") is not None
            and pr["head"]["repo"]["full_name"] == repo
            and not pr["head"]["ref"].startswith(BULK_PREFIX)
            and label in labels and ({LABEL, BULK_LABEL} & labels) == {label})


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


def workflow_result(api, sha, gate, events=("push", "pull_request", "workflow_dispatch", "merge_group"),
                    after_id=0):
    workflow = api.call("GET", f"actions/workflows/{gate.workflow}")
    runs = [run for run in api.pages(f"actions/workflows/{gate.workflow}/runs?head_sha={sha}", "workflow_runs")
            if run["head_sha"] == sha and run["workflow_id"] == workflow["id"] and run["id"] > after_id
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


def ensure_main_checks(api, sha, report, base_sha=None):
    # GITHUB_TOKEN merges suppress both ordinary push workflows. Recover each
    # missing workflow independently; an existing failed run is never retried.
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


def publish_run(api, run_id, gate=PR_GATE):
    run = api.call("GET", f"actions/runs/{int(run_id)}")
    events = ("workflow_dispatch",) if run.get("head_branch", "").startswith(BULK_PREFIX) else (
        "push", "pull_request", "workflow_dispatch", "merge_group")
    state, latest = workflow_result(api, run["head_sha"], gate, events, solo_retest_cutoff(api, run["head_sha"]) or 0)
    if latest and latest["id"] == run["id"]:
        publish_result(api, run["head_sha"], state, latest, gate)


def owner_authorized(api, number, label=LABEL):
    events = sorted((event for event in api.pages(f"issues/{number}/events")
              if event["event"] in ("labeled", "unlabeled")
              and event.get("label", {}).get("name") in (LABEL, BULK_LABEL)), key=lambda event: event["id"])
    if not events:
        return False
    latest = next((event for event in reversed(events) if event["label"]["name"] == label), None)
    if latest is None or latest["event"] != "labeled":
        return False
    actor = (latest.get("actor") or {}).get("login")
    if actor == api.repo.split("/")[0]:
        return True
    # A failed bot-owned batch may convert a previously owner-authorized bulk
    # request to solo. Add solo first, then remove bulk: if interrupted, the
    # next reconciliation can finish the swap without losing the owner's grant.
    if label != LABEL or actor != BOT_LOGIN or len(events) < 3 or events[-2] != latest:
        return False
    prior, addition, removal = events[-3:]
    return (prior["event"] == "labeled" and prior["label"]["name"] == BULK_LABEL
            and (prior.get("actor") or {}).get("login") == api.repo.split("/")[0]
            and addition["event"] == "labeled" and addition["label"]["name"] == LABEL
            and (addition.get("actor") or {}).get("login") == BOT_LOGIN
            and removal["event"] == "unlabeled" and removal["label"]["name"] == BULK_LABEL
            and (removal.get("actor") or {}).get("login") == BOT_LOGIN)


def still_authorized(api, pr, label=LABEL):
    return eligible(pr, api.repo, label) and owner_authorized(api, pr["number"], label)


def solo_retest_marker(api, sha):
    prefix = f"https://github.com/{api.repo}/actions/workflows/fast.yml{RETEST_MARKER}"
    for status in api.pages(f"commits/{sha}/statuses"):
        target = status.get("target_url") or ""
        if (status.get("context", "").casefold() == REQUIRED_STATUS.casefold()
                and (status.get("creator") or {}).get("login") == BOT_LOGIN
                and target.startswith(prefix)):
            value = target[len(prefix):]
            match = re.fullmatch(r"(\d+)-([0-9a-f]{40})", value)
            if match:
                return int(match[1]), match[2]
    return None


def solo_retest_cutoff(api, sha):
    marker = solo_retest_marker(api, sha)
    return marker[0] if marker else None


def mark_solo_retest(api, sha, candidate_sha):
    previous = solo_retest_marker(api, sha)
    if previous is not None and previous[1] == candidate_sha:
        return
    workflow = api.call("GET", "actions/workflows/fast.yml")
    runs = api.pages(f"actions/workflows/fast.yml/runs?head_sha={sha}", "workflow_runs")
    cutoff = max((run["id"] for run in runs if run["head_sha"] == sha
                  and run["workflow_id"] == workflow["id"]), default=0)
    cutoff = max(cutoff, previous[0] if previous else 0)
    api.call("POST", f"statuses/{sha}", {
        "context": REQUIRED_STATUS, "state": "pending",
        "target_url": f"https://github.com/{api.repo}/actions/workflows/fast.yml{RETEST_MARKER}{cutoff}-{candidate_sha}",
        "description": "Solo Fast retest required after failed combined check"})


def dispatch(api, pr, gate=PR_GATE, label=LABEL):
    if not still_authorized(api, pr, label):
        return False
    inputs = {"expected_sha": pr["head"]["sha"]}
    if gate == FAST_GATE:
        base = api.call("GET", "branches/main")["commit"]["sha"]
        fresh = api.call("GET", f"pulls/{pr['number']}")
        if (not still_authorized(api, fresh, label) or fresh["head"]["sha"] != pr["head"]["sha"]
                or fresh["base"]["sha"] != base
                or api.call("GET", f"compare/{base}...{fresh['head']['sha']}")["behind_by"]):
            return False
        inputs["base_sha"] = base
        pr = fresh
    publish_result(api, pr["head"]["sha"], "pending", None, gate)
    api.call("POST", f"actions/workflows/{gate.workflow}/dispatches", {
        "ref": pr["head"]["ref"], "inputs": inputs,
    })
    return True


def bulk_manifest(base, components):
    return {"version": 1, "base": base,
            "components": [{"number": pr["number"], "sha": pr["head"]["sha"]} for pr in components]}


def bulk_branch(manifest):
    identity = {key: manifest[key] for key in ("version", "base", "components")}
    content = json.dumps(identity, sort_keys=True, separators=(",", ":")).encode()
    return BULK_PREFIX + hashlib.sha256(content).hexdigest()[:24]


def candidate_manifest(pr):
    if ((pr.get("user") or {}).get("login") != BOT_LOGIN
            or not pr["head"]["ref"].startswith(BULK_PREFIX)):
        return None
    try:
        marker, payload = pr["body"].split("\n", 1)
        manifest = json.loads(payload)
        if (marker != "THC-BULK-V1" or manifest.get("version") != 1
                or not re.fullmatch(r"[0-9a-f]{40}", manifest["base"])
                or not re.fullmatch(r"[0-9a-f]{40}", manifest["head"])
                or not 2 <= len(manifest["components"]) <= BULK_LIMIT
                or any(not isinstance(part["number"], int)
                       or not re.fullmatch(r"[0-9a-f]{40}", part["sha"])
                       for part in manifest["components"])
                or len({part["number"] for part in manifest["components"]}) != len(manifest["components"])
                or pr["head"]["ref"] != bulk_branch(manifest)
                or pr["head"]["sha"] != manifest["head"]):
            return None
        return manifest
    except (AttributeError, KeyError, TypeError, ValueError):
        return None


def bulk_chain(api, base, head, expected):
    """Return the exact ordered second parents on a bot candidate merge chain."""
    members = []
    while head != base and len(members) < len(expected):
        if head == expected[0]:
            comparison = api.call("GET", f"compare/{base}...{head}")
            if comparison["behind_by"] or not comparison["ahead_by"]:
                raise ValueError("batch fast-forward does not descend from its base")
            return [head] + list(reversed(members))
        commit = api.call("GET", f"commits/{head}")
        parents = [parent["sha"] for parent in commit["parents"]]
        if commit["sha"] != head or len(parents) != 2:
            raise ValueError("batch has an unexpected commit parent")
        members.append(parents[1])
        head = parents[0]
    if head != base:
        raise ValueError("batch does not descend from its exact base")
    return list(reversed(members))


def component_state(api, part, base, swapping=False):
    pr = api.call("GET", f"pulls/{part['number']}")
    labels = {item["name"] for item in pr["labels"]}
    valid = (pr["state"] == "open" and not pr["draft"]
             and pr["base"]["ref"] == "main" and pr["base"]["sha"] == base
             and pr["base"]["repo"]["full_name"] == api.repo
             and (pr["head"].get("repo") or {}).get("full_name") == api.repo
             and pr["head"]["sha"] == part["sha"]
             and not pr["head"]["ref"].startswith(BULK_PREFIX))
    if not valid:
        return None
    if swapping:
        if BULK_LABEL in labels and owner_authorized(api, pr["number"], BULK_LABEL):
            return pr
        if labels & {LABEL, BULK_LABEL} == {LABEL} and owner_authorized(api, pr["number"]):
            return pr  # An earlier reconciliation already finished this swap.
        return None
    return pr if still_authorized(api, pr, BULK_LABEL) else None


def select_bulk(api, candidates, base):
    selected, paths = [], set()
    for summary in candidates:
        if len(selected) == BULK_LIMIT:
            break
        if not eligible(summary, api.repo, BULK_LABEL):
            continue
        pr = api.call("GET", f"pulls/{summary['number']}")
        if (not still_authorized(api, pr, BULK_LABEL) or pr["base"]["sha"] != base
                or pr["mergeable"] is not True):
            continue
        comparison = api.call("GET", f"compare/{base}...{pr['head']['sha']}")
        if not comparison["ahead_by"]:
            continue
        files = list(api.pages(f"pulls/{pr['number']}/files"))
        if (not files or len(files) > BULK_FILE_LIMIT
                or any(file["status"] not in ("added", "modified", "removed") for file in files)):
            continue
        changed = {file["filename"] for file in files}
        if len(changed) != len(files) or paths & changed:
            continue
        selected.append(pr)
        paths.update(changed)
    return selected if len(selected) >= 2 else []


def candidate_ref(api, branch):
    try:
        return api.call("GET", f"git/ref/heads/{branch}")["object"]["sha"]
    except HTTPError as error:
        if error.code != 404:
            raise
        error.close()
        return None


def discard_candidate(api, pr, report, reason):
    api.call("PATCH", f"pulls/{pr['number']}", {"state": "closed"})
    try:
        api.call("DELETE", f"git/refs/heads/{pr['head']['ref']}")
    except HTTPError as error:
        if error.code != 404:
            raise
        error.close()
    report(f"bulk #{pr['number']}: {reason}; candidate closed")


def create_candidate(api, members, base, report):
    manifest = bulk_manifest(base, members)
    branch = bulk_branch(manifest)
    head = candidate_ref(api, branch)
    if head is None:
        api.call("POST", "git/refs", {"ref": f"refs/heads/{branch}", "sha": base})
        head = base
    expected = [part["sha"] for part in manifest["components"]]
    merged = bulk_chain(api, base, head, expected)
    if merged != expected[:len(merged)]:
        raise RuntimeError("Existing batch branch has an unexpected head")
    for part in manifest["components"][len(merged):]:
        result = api.call("POST", "merges", {"base": branch, "head": part["sha"],
                                              "commit_message": f"Batch PR #{part['number']}"})
        head = result["sha"] if result and result.get("sha") else candidate_ref(api, branch)
        if head is None:
            raise RuntimeError("Batch merge did not retain a candidate head")
    if (candidate_ref(api, branch) != head or bulk_chain(api, base, head, expected) != expected
            or api.call("GET", "branches/main")["commit"]["sha"] != base
            or any(component_state(api, part, base) is None for part in manifest["components"])):
        api.call("DELETE", f"git/refs/heads/{branch}")
        report("bulk: source changed during assembly; candidate branch discarded")
        return None
    manifest["head"] = head
    pr = api.call("POST", "pulls", {"title": "Batch opted-in pull requests",
                                    "head": branch, "base": "main", "draft": False,
                                    "body": "THC-BULK-V1\n" + json.dumps(manifest, sort_keys=True, separators=(",", ":"))})
    if pr["head"]["sha"] != head:
        raise RuntimeError("New batch PR does not point at the tested candidate head")
    report(f"bulk #{pr['number']}: assembled {len(members)} disjoint PRs")
    return pr


def dispatch_candidate(api, pr, manifest):
    sha = pr["head"]["sha"]
    if (api.call("GET", "branches/main")["commit"]["sha"] != manifest["base"]
            or candidate_ref(api, pr["head"]["ref"]) != sha):
        return False
    publish_result(api, sha, "pending", None, FAST_GATE)
    api.call("POST", "actions/workflows/fast.yml/dispatches", {
        "ref": pr["head"]["ref"], "inputs": {"expected_sha": sha, "base_sha": manifest["base"]}})
    return True


def fallback_to_solo(api, manifest):
    states = [component_state(api, part, manifest["base"], swapping=True)
              for part in manifest["components"]]
    if any(pr is None for pr in states):
        return False
    # Record the highest pre-fallback Fast run before changing labels. The
    # marker is an immutable bot-authored commit status; old green runs on the
    # same head cannot satisfy the required fresh individual retest.
    for part in manifest["components"]:
        mark_solo_retest(api, part["sha"], manifest["head"])
    for pr in states:
        labels = {item["name"] for item in pr["labels"]}
        if BULK_LABEL not in labels:
            continue
        if LABEL not in labels:
            api.call("POST", f"issues/{pr['number']}/labels", {"labels": [LABEL]})
        api.call("DELETE", f"issues/{pr['number']}/labels/{BULK_LABEL}")
    return True


def reconcile_bulk(api, candidates, report):
    bot_prs = [pr for pr in candidates if (pr.get("user") or {}).get("login") == BOT_LOGIN
               and pr["head"]["ref"].startswith(BULK_PREFIX)]
    if bot_prs:
        summary = bot_prs[0]
        pr = api.call("GET", f"pulls/{summary['number']}")
        manifest = candidate_manifest(pr)
        if manifest is None:
            report(f"bulk #{pr['number']}: invalid candidate manifest; manual review required")
            return True
        expected = [part["sha"] for part in manifest["components"]]
        base = manifest["base"]
        sha = pr["head"]["sha"]
        state, run = workflow_result(api, sha, FAST_GATE, ("workflow_dispatch",))
        swapping = state == "failure" and run["conclusion"] == "failure"
        try:
            chain = bulk_chain(api, base, sha, expected)
        except (KeyError, ValueError):
            chain = None
        if (chain != expected or api.call("GET", "branches/main")["commit"]["sha"] != base
                or candidate_ref(api, pr["head"]["ref"]) != sha
                or any(component_state(api, part, base, swapping=swapping) is None
                       for part in manifest["components"])):
            discard_candidate(api, pr, report, "base, component, or candidate changed")
            return True  # Reassemble against the new snapshot on the next pass.
        publish_result(api, sha, "pending" if state == "missing" else state, run, FAST_GATE)
        if state == "missing":
            if dispatch_candidate(api, pr, manifest):
                report(f"bulk #{pr['number']}: dispatched combined Fast checks")
            return True
        if state == "pending":
            report(f"bulk #{pr['number']}: combined Fast checks pending")
            return True
        if state == "failure":
            if run["conclusion"] == "failure":
                if fallback_to_solo(api, manifest):
                    discard_candidate(api, pr, report, "combined Fast checks failed; components moved to solo")
                    return False
                discard_candidate(api, pr, report, "component changed before solo fallback")
                return True
            if run["conclusion"] in ("cancelled", "timed_out") and run["run_attempt"] < 3:
                try:
                    api.call("POST", f"actions/runs/{run['id']}/rerun")
                    report(f"bulk #{pr['number']}: retrying {run['conclusion']} Fast attempt")
                except HTTPError as error:
                    if error.code not in (403, 409, 422):
                        raise
                    error.close()
                    report(f"bulk #{pr['number']}: retry already pending or refused (HTTP {error.code})")
            else:
                report(f"bulk #{pr['number']}: infrastructure result {run['conclusion']}; manual rerun needed")
            return True
        health, snapshot = main_build_health(api)
        if health != "success":
            report(f"bulk #{pr['number']}: full main Build health is {health}; promotion paused")
            return True
        fresh = api.call("GET", f"pulls/{pr['number']}")
        final_state, final_run = workflow_result(api, sha, FAST_GATE, ("workflow_dispatch",))
        if (fresh["head"]["sha"] != sha or fresh["mergeable_state"] not in ("clean", "unstable")
                or api.call("GET", "branches/main")["commit"]["sha"] != base
                or candidate_ref(api, pr["head"]["ref"]) != sha
                or any(component_state(api, part, base) is None for part in manifest["components"])
                or final_state != "success" or final_run["id"] != run["id"]
                or final_run["run_attempt"] != run["run_attempt"]
                or main_build_snapshot(completed_main_attempts(api)) != snapshot):
            report(f"bulk #{pr['number']}: promotion state changed; waiting")
            return True
        try:
            result = api.call("PUT", f"pulls/{pr['number']}/merge", {"sha": sha, "merge_method": "merge"})
        except HTTPError as error:
            if error.code in (405, 409):
                error.close()
                report(f"bulk #{pr['number']}: GitHub refused promotion; waiting")
                return True
            raise
        if not result["merged"] or not result.get("sha"):
            raise RuntimeError("GitHub did not merge the checked batch")
        report(f"bulk #{pr['number']}: merged {len(expected)} component heads")
        ensure_main_checks(api, result["sha"], report, base_sha=base)
        return "merged"
    base = api.call("GET", "branches/main")["commit"]["sha"]
    members = select_bulk(api, candidates, base)
    if members:
        try:
            pr = create_candidate(api, members, base, report)
        except HTTPError as error:
            if error.code in (403, 409, 422):
                error.close()
                report(f"bulk: candidate assembly rejected (HTTP {error.code}); members remain opted in")
                return True
            raise
        if pr:
            dispatch_candidate(api, pr, bulk_manifest(base, members))
            return True
    return False


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
        events = (("workflow_dispatch",) if candidate["head"]["ref"].startswith(BULK_PREFIX)
                  else ("push", "pull_request", "workflow_dispatch", "merge_group"))
        state, run = workflow_result(api, candidate["head"]["sha"], gate, events,
                                     solo_retest_cutoff(api, candidate["head"]["sha"]) or 0)
        # A policy transition must retire old Build-derived greens even on
        # unlabelled/fork PRs that do not yet have a Fast checks run.
        publish_result(api, candidate["head"]["sha"], "pending" if state == "missing" else state, run, gate)
    if gate == FAST_GATE:
        ensure_main_checks(api, branch["commit"]["sha"], report)
    bulk_busy = reconcile_bulk(api, candidates, report) if gate == FAST_GATE else False
    if bulk_busy == "merged":
        return  # The candidate list predates main's new commit.
    updated_branch = False
    for candidate in candidates:
        number = candidate["number"]
        path = f"pulls/{number}"
        pr = api.call("GET", path)
        label = LABEL if LABEL in {item["name"] for item in pr["labels"]} else BULK_LABEL
        if not still_authorized(api, pr, label):
            continue
        if bulk_busy and label == BULK_LABEL:
            continue
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
            if not still_authorized(api, fresh, label) or fresh["head"]["sha"] != sha:
                report(f"#{number}: changed while inspecting; waiting")
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
                if not still_authorized(api, updated, label):
                    break
                if updated["head"]["sha"] != sha:
                    # Token-authored pushes don't start ordinary push CI.
                    # Dispatch is explicit and verifies the expected commit.
                    if dispatch(api, updated, gate, label):
                        report(f"#{number}: updated against main and dispatched {gate.name}")
                    else:
                        report(f"#{number}: changed before dispatch; waiting")
                    break
            else:
                report(f"#{number}: branch update pending; a later run will dispatch {gate.name}")
            continue
        cutoff = solo_retest_cutoff(api, sha) or 0
        state, run = workflow_result(api, sha, gate, after_id=cutoff)
        if run:
            publish_result(api, sha, state, run, gate)
        if state == "failure":
            report(f"#{number}: checks failed; waiting for a fix or manual rerun")
            continue
        if state != "success":
            if state == "missing":
                if dispatch(api, pr, gate, label):
                    report(f"#{number}: dispatched missing {gate.name}")
                else:
                    report(f"#{number}: changed before dispatch; waiting")
            else:
                report(f"#{number}: waiting for required checks ({state})")
            continue
        if bulk_busy:
            report(f"#{number}: waiting for checked bulk candidate before solo merge")
            continue
        if gate == FAST_GATE:
            health, health_snapshot = main_build_health(api)
            if health != "success":
                report(f"#{number}: full main Build health is {health}; automatic merges paused")
                return
        # Recheck consent and head immediately before the mutation. GitHub
        # enforces strict checks again atomically when accepting the merge.
        fresh = api.call("GET", path)
        if not still_authorized(api, fresh, label) or fresh["head"]["sha"] != sha:
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
        final_state, final_run = workflow_result(api, sha, gate, after_id=cutoff)
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
            result = api.call("PUT", f"{path}/merge", {"sha": sha, "merge_method": "squash"})
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
