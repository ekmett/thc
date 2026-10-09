#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

"""Select owned CMake fixtures and independent native Windows Gradle resources."""

import argparse
import json
import os
from pathlib import Path
import re
import subprocess

MANIFEST = Path(".github/scripts/fast-fixtures.json")


def _relative(value):
    path = Path(value)
    if not isinstance(value, str) or not value or path.is_absolute() or ".." in path.parts or "\x00" in value:
        raise ValueError(f"Unsafe fixture path: {value!r}")
    return path


def _manifest(root):
    data = json.loads((root / MANIFEST).read_text())
    if data.get("schema") != 1 or not isinstance(data.get("groups"), dict):
        raise ValueError("Invalid fast fixture manifest")
    owners = {}
    free = data.get("fixtureFreeJunit", [])
    if not isinstance(free, list):
        raise ValueError("Invalid fixture-free class inventory")
    for name in free:
        if not isinstance(name, str) or name in owners:
            raise ValueError(f"Duplicate/invalid fixture-free class: {name!r}")
        owners[name] = None
    for group_id, group in data["groups"].items():
        if not re.fullmatch(r"[a-z][a-z0-9-]*", group_id):
            raise ValueError(f"Invalid fixture group: {group_id!r}")
        if not all(isinstance(group.get(key), list) and group[key] for key in
                   ("junit", "commands", "outputs", "sources")):
            raise ValueError(f"Incomplete fixture group: {group_id}")
        for name in group["junit"]:
            if not isinstance(name, str) or name in owners:
                raise ValueError(f"Duplicate/invalid fixture class: {name!r}")
            owners[name] = group_id
        for path in [*group["outputs"], *group["sources"]]:
            _relative(path)
        systems = group.get("ciPlatforms", ["Linux", "Darwin", "Windows"])
        if (not isinstance(systems, list) or not systems or
                any(system not in ("Linux", "Darwin", "Windows") for system in systems) or
                len(systems) != len(set(systems))):
            raise ValueError(f"Invalid CI platforms: {group_id}")
        if "gradleTask" in group and (not isinstance(group["gradleTask"], str)
                or not re.fullmatch(r"[A-Za-z][A-Za-z0-9]*", group["gradleTask"])
                or group.get("cmakeTarget") or group.get("quarantined") or systems != ["Windows"]):
            raise ValueError(f"Invalid native Windows Gradle fixture: {group_id}")
        checks = group.get("ciChecks", [])
        if not isinstance(checks, list) or any(not isinstance(check, dict) or check.get("platform") != "Linux" for check in checks):
            raise ValueError(f"Invalid CI checks: {group_id}")
        for command in [*group["commands"], *checks]:
            argv = command.get("argv") if isinstance(command, dict) else None
            if not isinstance(argv, list) or not argv or not all(
                    isinstance(part, str) and part and "\x00" not in part for part in argv):
                raise ValueError(f"Invalid fixture command: {group_id}")
            if "stdout" in command:
                destination = _relative(command["stdout"])
                if not any(destination == _relative(output) or
                           _relative(output) in destination.parents for output in group["outputs"]):
                    raise ValueError(f"Undeclared fixture stdout: {group_id}")
    _group_order(data, data["groups"])
    return data, owners


def _group_order(manifest, selected):
    """Producer dependencies run first; ownership remains unique."""
    result, active = [], set()
    def visit(name):
        if name not in manifest["groups"]:
            raise ValueError(f"Unknown fixture dependency: {name}")
        if name in active:
            raise ValueError(f"Cyclic fixture dependency: {name}")
        if name in result:
            return
        required = manifest["groups"][name].get("requires", [])
        if not isinstance(required, list) or any(not isinstance(dep, str) for dep in required) or len(set(required)) != len(required):
            raise ValueError(f"Invalid fixture dependencies: {name}")
        active.add(name)
        for dep in sorted(required):
            visit(dep)
        active.remove(name)
        result.append(name)
    for name in sorted(selected):
        visit(name)
    return result


def quarantined_classes(root):
    """Classes withheld from execution, not reported as passing or deferred."""
    path = Path(root) / MANIFEST
    if not path.is_file():
        return set()  # The standalone selector also supports non-THC test repos.
    manifest, owners = _manifest(Path(root))
    blocked = {name for name, group in manifest["groups"].items() if group.get("quarantined")}
    return set(manifest.get("quarantinedJunit", [])) | {
        name for name, owner in owners.items() if owner is not None
        and blocked.intersection(_group_order(manifest, [owner]))}


def prepare_cmake(root, selection, run):
    """Select graph targets; Ninja, not directory receipts, owns freshness."""
    root = Path(root).resolve()
    manifest, owners = _manifest(root)
    classes = selection["junit"]["classes"]
    if not classes or any(name not in owners for name in classes):
        raise ValueError("Select exact known test classes for the fixture graph")
    blocked = set(classes) & quarantined_classes(root)
    if blocked:
        raise ValueError("Quarantined tests cannot run: " + ", ".join(sorted(blocked)))
    groups = _group_order(manifest, {owners[name] for name in classes if owners[name] is not None})
    pending = [name for name in groups if not (manifest["groups"][name].get("cmakeTarget")
                                              or manifest["groups"][name].get("gradleTask"))]
    if pending:
        raise ValueError("Fixture file rules are not yet migrated: " + ", ".join(pending)
                         + "; see docs/fixture-inputs.log. No legacy preparation was run.")
    targets = [manifest["groups"][name]["cmakeTarget"] for name in groups
               if manifest["groups"][name].get("cmakeTarget")]
    build_cmake_targets(targets, run)
    result = {"mode": "cmake", "targets": targets}
    gradle_targets = [manifest["groups"][name]["gradleTask"] for name in groups
                     if manifest["groups"][name].get("gradleTask")]
    if gradle_targets:
        result["mode"] = "cmake+gradle" if targets else "gradle"
        result["gradleTargets"] = gradle_targets
        # Native Windows resources belong to Gradle; do not bootstrap the
        # macOS/Linux exporter graph for an independent Windows ABI control.
        if os.name == "nt":
            run("fixture-gradle-build", ["cmd", "/c", "gradlew.bat", "--no-daemon",
                                         "--max-workers=1", *gradle_targets])
    return result


def build_cmake_targets(targets, run):
    if not targets:
        return
    configure = ["cmake", "-S", ".", "-B", "build/fixtures", "-G", "Ninja"]
    for tool in ("GHC", "GHC_PKG", "CABAL"):
        if os.environ.get(tool):
            configure.append("-D" + tool + "=" + os.environ[tool])
    run("fixture-configure", configure)
    run("fixture-build", ["cmake", "--build", "build/fixtures", "--parallel", "4", "--target", *targets])


def local_selection(selector, owners):
    # Wildcards can match methods in unrelated classes; require an exact owner.
    if "*" in selector:
        return {"mode": "full", "junit": {"classes": [selector]}}
    classes = []
    for name in owners:
        candidate = name.rsplit(".", 1)[-1] if selector[:1].isupper() else name
        if selector == candidate or selector.startswith(candidate + "."):
            classes.append(name)
    return {"mode": "narrow" if classes else "full",
            "junit": {"classes": sorted(classes) or [selector]}}


def main():
    parser = argparse.ArgumentParser(description="Build declared fixtures with CMake/Ninja.")
    selection_args = parser.add_mutually_exclusive_group(required=True)
    selection_args.add_argument("--tests", help="Exact test class or method")
    selection_args.add_argument("--all", action="store_true", help="Build all admitted fixture targets")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    _, owners = _manifest(root)
    if args.all:
        selection = {"mode": "full", "junit": {"classes": sorted(set(owners) - quarantined_classes(root))}}
    else:
        selection = local_selection(args.tests, owners)
        if selection["mode"] == "full":
            parser.error("Select an exact known test class or use --all for admitted fixtures")
    def run(name, argv):
        print("+ " + repr(argv), flush=True)
        subprocess.run(argv, cwd=root, check=True)
    print(prepare_cmake(root, selection, run))


if __name__ == "__main__":
    main()
