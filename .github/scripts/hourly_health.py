#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

"""Stop ordinary CI while the hourly suite has an unresolved failure."""
import json
import os
import subprocess


def latest_result(runs, repository, api):
    for run in runs:
        if (run["event"] not in ("schedule", "workflow_dispatch")
                or run["head_branch"] != "main"
                or run["head_repository"]["full_name"] != repository):
            continue
        # A rerun replaces the visible attempt; retain its earlier failure until
        # an attempt succeeds, even while queued/running or after cancellation.
        while True:
            if (run["status"] == "completed"
                    and run["conclusion"] not in ("cancelled", "skipped", "neutral")):
                return run
            if run["run_attempt"] == 1:
                break
            previous = api(f"repos/{repository}/actions/runs/{run['id']}/attempts/{run['run_attempt'] - 1}")
            if (any(previous[key] != run[key] for key in
                    ("id", "workflow_id", "head_sha", "head_branch", "event"))
                    or previous["head_repository"]["full_name"] != repository
                    or previous["run_attempt"] != run["run_attempt"] - 1
                    or previous["status"] != "completed"):
                raise RuntimeError("Cannot establish previous Hourly attempt")
            run = previous
    return None


def check(repository, api):
    workflows = api(f"repos/{repository}/actions/workflows?per_page=100")["workflows"]
    workflow = next((item for item in workflows
                     if item["path"] == ".github/workflows/hourly.yml"), None)
    if workflow is not None:
        page = 1
        while True:
            runs = api(f"repos/{repository}/actions/workflows/{workflow['id']}/runs"
                       f"?branch=main&per_page=100&page={page}")["workflow_runs"]
            result = latest_result(runs, repository, api)
            if result is not None:
                if result["conclusion"] != "success":
                    raise RuntimeError("Hourly regression: stop development and fix this run first: "
                                       + result["html_url"])
                print("Hourly suite passed: " + result["html_url"])
                return
            if len(runs) < 100:
                break
            page += 1
    print("Hourly suite has no completed baseline on main yet.")


def github_api(path):
    return json.loads(subprocess.check_output(["gh", "api", path], text=True))


if __name__ == "__main__":
    check(os.environ["GITHUB_REPOSITORY"], github_api)
