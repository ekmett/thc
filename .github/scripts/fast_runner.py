#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

"""Select a hosted Fast runner only for an exact, CI-only PR merge diff.

Failure to establish the event's exact merge parents or complete changed-file
inventory leaves trusted work on the usual persistent runner. Fork routing is
decided separately from the event before this checkout is inspected.
"""

import json
import os
from pathlib import Path
import re
import subprocess


SHA = re.compile(r"[0-9a-f]{40}\Z")
CI_SCRIPT = re.compile(r"\.github/scripts/[A-Za-z0-9_.-]+\.(?:py|json|gradle)\Z")
CI_WORKFLOW = re.compile(r"\.github/workflows/[A-Za-z0-9_.-]+\.ya?ml\Z")


def git(root, *args):
    result = subprocess.run(["git", "-C", str(root), *args], capture_output=True, check=True)
    return result.stdout


def ci_only_paths(changes):
    """Only added/modified automation files; renames/deletions stay conservative."""
    if not changes or not changes.endswith(b"\0"):
        return False
    fields = changes[:-1].split(b"\0")
    if len(fields) % 2:
        return False
    for status, raw_path in zip(fields[::2], fields[1::2]):
        if status not in (b"A", b"M"):
            return False
        try:
            path = raw_path.decode("utf-8")
        except UnicodeDecodeError:
            return False
        if not (CI_SCRIPT.fullmatch(path) or CI_WORKFLOW.fullmatch(path)
                or path in ("docs/contributing.md", "docs/fast-ci.md")):
            return False
    return True


def hosted_ci_only(root, event, checkout_sha, repository):
    """Require GitHub's exact two-parent PR merge before inspecting its diff."""
    if (event.get("repository", {}).get("full_name") != repository
            or event.get("pull_request", {}).get("base", {}).get("ref") != "main"):
        return False
    pr = event["pull_request"]
    base, head = pr["base"], pr["head"]
    if (base.get("repo", {}).get("full_name") != repository
            or head.get("repo", {}).get("full_name") != repository
            or head.get("repo", {}).get("fork") is not False):
        return False
    base_sha, head_sha = base.get("sha"), head.get("sha")
    if not all(isinstance(sha, str) and SHA.fullmatch(sha)
               for sha in (base_sha, head_sha, checkout_sha)):
        return False
    try:
        if git(root, "rev-parse", "HEAD").decode().strip() != checkout_sha:
            return False
        parents = git(root, "rev-list", "--parents", "-n", "1", checkout_sha).decode().split()
        if parents != [checkout_sha, base_sha, head_sha]:
            return False
        changes = git(root, "diff", "--no-ext-diff", "--no-textconv", "--name-status",
                      "--no-renames", "-z", base_sha, checkout_sha, "--")
    except (OSError, subprocess.CalledProcessError, UnicodeDecodeError):
        return False
    return ci_only_paths(changes)


def main():
    if os.environ.get("GITHUB_EVENT_NAME") != "pull_request":
        return 1
    try:
        event = json.loads(Path(os.environ["GITHUB_EVENT_PATH"]).read_text())
        return 0 if hosted_ci_only(Path.cwd(), event, os.environ["GITHUB_SHA"],
                                   os.environ["GITHUB_REPOSITORY"]) else 1
    except (AttributeError, KeyError, OSError, ValueError, TypeError):
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
