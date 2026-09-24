#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

"""Execute fresh affected tests with reusable inputs and auditable phase timings.

This driver never treats Gradle task history or restored test XML as a result.
Only the Test task is rerun; compilation remains eligible for Gradle's build cache.
"""
import argparse
from contextlib import ExitStack
from datetime import datetime, timezone
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
SHA = re.compile(r"[0-9a-f]{40}\Z")
FIXTURES_SPEC = importlib.util.spec_from_file_location("fast_fixtures", Path(__file__).with_name("fast_fixtures.py"))
fixtures = importlib.util.module_from_spec(FIXTURES_SPEC)
FIXTURES_SPEC.loader.exec_module(fixtures)


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def utc():
    return datetime.now(timezone.utc).isoformat()


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + ".tmp")
    temporary.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n")
    temporary.replace(path)


def git(root, *args):
    return subprocess.check_output(["git", "-C", str(root), *args], text=True).strip()


class Recorder:
    def __init__(self, root, directory):
        self.root, self.directory = root, directory
        self.path = directory / "timings.json"
        self.data = json.loads(self.path.read_text()) if self.path.exists() else {
            "schema": 1, "started": utc(), "startedEpoch": time.time(),
            "revision": git(root, "rev-parse", "HEAD"), "phases": [],
        }

    def save(self):
        write_json(self.path, self.data)

    def command(self, name, argv, *, env=None, allowed=(0,), capture=False, stdout=None):
        self.directory.mkdir(parents=True, exist_ok=True)
        logfile = self.directory / (f"{len(self.data['phases']):02d}-{name}.log")
        phase = {"name": name, "command": list(map(str, argv)), "started": utc(),
                 "log": str(logfile.relative_to(self.root))}
        begin = time.monotonic()
        output = []
        print("+ " + repr(phase["command"]), flush=True)
        try:
            with ExitStack() as stack:
                log = stack.enter_context(logfile.open("w"))
                destination = stack.enter_context((self.root / stdout).open("w")) if stdout else subprocess.PIPE
                child = stack.enter_context(subprocess.Popen(argv, cwd=self.root, env=env, stdout=destination,
                                         stderr=subprocess.PIPE if stdout else subprocess.STDOUT, text=True))
                # Native oracle stdout is data. Diagnostics go only to its log.
                for line in child.stderr if stdout else child.stdout:
                    log.write(line)
                    log.flush()
                    print(line, end="", flush=True)
                    if capture:
                        output.append(line)
                phase["exitCode"] = child.wait()
        except OSError as error:
            phase["error"] = str(error)
            phase["exitCode"] = 127
        finally:
            phase.update(seconds=round(time.monotonic() - begin, 6), finished=utc())
            self.data["phases"].append(phase)
            self.save()
        require(phase["exitCode"] in allowed, f"{name} failed: exit {phase['exitCode']}; see {logfile}")
        return phase["exitCode"], "".join(output)


def validate_xml(directory, expected):
    """Require fresh nonempty successful suites for exactly the requested classes."""
    files = sorted(directory.glob("TEST-*.xml"))
    require(bool(files), "No fresh JUnit XML")
    classes, cases = set(), []
    totals = {"tests": 0, "failures": 0, "errors": 0, "skipped": 0}
    for path in files:
        suite = ET.parse(path).getroot()
        require(suite.tag == "testsuite", f"Unexpected JUnit root in {path}")
        name = suite.attrib.get("name", "")
        require(name and name not in classes, f"Missing/duplicate suite: {name}")
        classes.add(name)
        children = suite.findall("testcase")
        count = int(suite.attrib.get("tests", "-1"))
        require(count > 0 and count == len(children), f"Empty/inconsistent suite: {name}")
        for key in totals:
            number = int(suite.attrib.get(key, "0"))
            require(number >= 0, f"Invalid JUnit {key}: {name}")
            totals[key] += number
        require(not any(suite.findall(".//" + tag) for tag in ("failure", "error", "skipped")),
                f"Unsuccessful testcase in {name}")
        for case in children:
            require(case.attrib.get("classname") == name, f"Mismatched testcase class: {name}")
            require(bool(case.attrib.get("name")), f"Unnamed testcase: {name}")
            cases.append([name, case.attrib["name"]])
    require(classes == set(expected),
            f"JUnit class mismatch: missing={sorted(set(expected) - classes)}, extra={sorted(classes - set(expected))}")
    require(not any(totals[key] for key in ("failures", "errors", "skipped")),
            "JUnit reports failures/errors/skips: " + repr(totals))
    return {**totals, "classes": sorted(classes), "cases": sorted(cases), "xmlFiles": len(files)}


def gradle_command(selection):
    require(selection.get("runnable") is True, "Selector could not establish a runnable test inventory")
    require(selection.get("mode") in ("narrow", "full"), "Invalid selection mode")
    classes = selection["junit"]["classes"]
    require(classes and len(set(classes)) == len(classes), "Empty/duplicate selected classes")
    require(all(isinstance(c, str) and re.fullmatch(r"[A-Za-z_][\w.$]*", c) for c in classes),
            "Invalid selected class name")
    argv = ["./gradlew", "--daemon", "--max-workers=4", "--build-cache",
            "--init-script", ".github/scripts/fast_ci.init.gradle", "test", "--rerun"]
    if selection["mode"] == "narrow":
        require(selection["junit"]["patterns"] == classes, "Narrow patterns must name entire selected classes")
        for name in classes:
            argv.extend(["--tests", name])
    else:
        require(selection["junit"]["patterns"] == ["*"], "Full mode must run every test")
    return argv


def polyglot_command(selection):
    optional = selection.get("polyglot")
    require(isinstance(optional, dict) and type(optional.get("required")) is bool,
            "Missing polyglot selection")
    classes = optional.get("classes")
    require(isinstance(classes, list)
            and all(isinstance(name, str) and re.fullmatch(r"[A-Za-z_][\w.$]*", name)
                    for name in classes)
            and len(set(classes)) == len(classes), "Invalid polyglot class inventory")
    require(bool(classes) == optional["required"], "Polyglot selection has no exact classes")
    if not optional["required"]:
        return None
    return ["./gradlew", "--daemon", "--max-workers=4", "--build-cache",
            "--init-script", ".github/scripts/fast_ci.init.gradle", "polyglotTest", "--rerun"]


def python_commands(selection, executable, automation_checked=False):
    for command in selection["python"]["commands"]:
        require(isinstance(command, list) and len(command) >= 2 and command[0] == "python3"
                and all(isinstance(part, str) and "\0" not in part for part in command),
                "Invalid Python argv from selector")
        if automation_checked and re.fullmatch(r"\.github/scripts/test_[A-Za-z0-9_]+\.py", command[1]):
            continue  # The required automation job ran these in both modes.
        yield [executable, *command[1:]]
        yield [executable, "-O", *command[1:]]


def haskell_suites(selection):
    selected = selection.get("haskell")
    require(isinstance(selected, dict) and isinstance(selected.get("suites"), list)
            and selected.get("count") == len(selected["suites"]),
            "Invalid Haskell test selection")
    suites = selected["suites"]
    require(isinstance(suites, list) and suites in ([], ["driver-tests"]),
            "Unknown or duplicate Haskell test suite")
    return suites


def preserve_previous(root, destination, task="test"):
    # Move only the exact test task output, not build/ or unrelated user data.
    require(task in ("test", "polyglotTest"), "Unknown test task output")
    for source, suffix in ((root / "build/test-results" / task, "xml"),
                           (root / "build/reports/tests" / task, "html")):
        if source.exists() or source.is_symlink():
            require(source.is_dir() and not source.is_symlink(), f"Unexpected test output: {source}")
            target = destination / suffix
            require(not target.exists(), f"Receipt already exists: {target}; use a fresh report directory")
            target.parent.mkdir(parents=True, exist_ok=True)
            source.rename(target)


def run_mode(recorder, selection, mode):
    root, directory = recorder.root, recorder.directory
    preserve_previous(root, directory / ("prior-" + mode))
    env = dict(os.environ)
    env["JAVA_TOOL_OPTIONS"] = (env.get("JAVA_TOOL_OPTIONS", "") +
                               " -Dthc.handoffSlabs=" + ("true" if mode == "dense" else "false")).strip()
    code, _ = recorder.command("junit-" + mode, gradle_command(selection), env=env,
                               allowed=tuple(range(-128, 256)))
    # Preserve failed runs as well as successful ones before the next Test task.
    preserve_previous(root, directory / mode)
    summary = validate_xml(directory / mode / "xml", selection["junit"]["classes"])
    require(code == 0, f"Gradle {mode} failed with exit {code}")
    write_json(directory / mode / "summary.json", summary)
    return summary


def run_polyglot(recorder, selection):
    command = polyglot_command(selection)
    if command is None:
        return None
    root, directory = recorder.root, recorder.directory
    preserve_previous(root, directory / "prior-polyglot", "polyglotTest")
    code, _ = recorder.command("polyglot-junit", command, allowed=tuple(range(-128, 256)))
    preserve_previous(root, directory / "polyglot", "polyglotTest")
    summary = validate_xml(directory / "polyglot/xml", selection["polyglot"]["classes"])
    require(code == 0, f"Gradle polyglotTest failed with exit {code}")
    write_json(directory / "polyglot/summary.json", summary)
    return summary


def identify(recorder, identity_path):
    root = recorder.root
    release = Path(os.environ["JAVA_HOME"]) / "release"
    values = dict(line.split("=", 1) for line in release.read_text().splitlines() if "=" in line)
    require(values.get("GRAALVM_VERSION", "").strip('"') == "25.3.4.1"
            and values.get("JAVA_VERSION", "").strip('"').split(".")[0] == "25",
            "Fast checks require pinned GraalVM25.3.4.1 / Java25")
    _, output = recorder.command("input-identity", [sys.executable, ".github/scripts/fast_inputs.py",
                                "key", "--output", str(identity_path)], capture=True)
    keys = [line for line in output.splitlines() if re.fullmatch(r"thc-fast-inputs-v1-[0-9a-f]{64}", line)]
    require(len(keys) == 1, "Input helper did not return exactly one cache key")
    # Gradle itself validates task inputs. This prefix prevents reuse across tool,
    # dependency, compiler-plugin, wrapper or cache-policy changes.
    paths = [root / name for name in ("build.gradle.kts", "settings.gradle.kts", "gradle.properties",
             "gradlew", "gradle/wrapper/gradle-wrapper.jar", "gradle/wrapper/gradle-wrapper.properties")]
    paths.extend([release, Path(__file__), root / ".github/scripts/fast_ci.init.gradle"])
    h = hashlib.sha256()
    for path in paths:
        h.update(path.name.encode() + b"\0" + hashlib.sha256(path.read_bytes()).digest())
    gradle_key = "thc-fast-gradle-v1-" + sys.platform + "-" + os.uname().machine + "-" + h.hexdigest()
    recorder.data.update(nativeKey=keys[0], gradlePrefix=gradle_key)
    recorder.save()
    if os.environ.get("GITHUB_OUTPUT"):
        with open(os.environ["GITHUB_OUTPUT"], "a") as stream:
            stream.write(f"native-key={keys[0]}\ngradle-prefix={gradle_key}\n")


def execute(recorder, base, head, identity_path):
    _, output = recorder.command("select", [sys.executable, ".github/scripts/fast_select.py",
                                "--base", base, "--head", head], capture=True)
    selection = json.loads(output)
    write_json(recorder.directory / "selection.json", selection)
    gradle_command(selection)  # Fail closed before preparing or running anything.
    polyglot = polyglot_command(selection)
    selected_haskell = haskell_suites(selection)
    recorder.data["selection"] = {key: selection[key] for key in ("mode", "reasons")}
    recorder.data.update(requestedBase=base, selectionBase=base)
    # Check generated documentation against the actual pinned GHC API on hits
    # as well as misses. Keep this fresh report separate from cached provenance.
    recorder.command("primop-checklist", [sys.executable, "scripts/primop-coverage.py",
                     "--check", "--output", str(recorder.directory / "primop-coverage.json")])
    identity = json.loads(identity_path.read_text())
    inputs = fixtures.prepare(recorder.root, selection, recorder.command,
                              {"platform": identity["platform"], "toolchain": identity["toolchain"]})
    recorder.data["nativeInputs"] = inputs
    recorder.save()
    failures = []
    if selected_haskell:
        try:
            recorder.command("driver-plugin", ["compiler/build.sh"])
            recorder.command("driver-launcher", ["./gradlew", "installDist"])
            recorder.command("driver-tests", ["cabal", "test", "driver-tests", "-fdevelopment",
                                              "--test-show-details=direct"])
        except RuntimeError as error:
            failures.append("driver-tests: " + str(error))
    automation_sha = os.environ.get("FAST_AUTOMATION_SHA", "")
    automation_checked = bool(SHA.fullmatch(automation_sha)) and automation_sha == git(recorder.root, "rev-parse", "HEAD")
    recorder.data["automationReused"] = automation_sha if automation_checked else None
    for index, command in enumerate(python_commands(selection, sys.executable, automation_checked)):
        try:
            recorder.command(f"python-{index:03d}", command)
        except RuntimeError as error:
            failures.append(str(error))
    summaries = {}
    for mode in ("default", "dense"):
        try:
            summaries[mode] = run_mode(recorder, selection, mode)
        except (RuntimeError, ValueError, ET.ParseError) as error:
            failures.append(f"{mode}: {error}")
    if len(summaries) == 2 and summaries["default"]["cases"] != summaries["dense"]["cases"]:
        failures.append("Default and dense handoff executed different testcase sets")
    polyglot_summary = None
    if polyglot is not None:
        try:
            polyglot_summary = run_polyglot(recorder, selection)
        except (RuntimeError, ValueError, ET.ParseError) as error:
            failures.append(f"polyglot: {error}")
        try:
            recorder.command("javascript-demo", ["scripts/javascript-demo.sh"])
        except RuntimeError as error:
            failures.append(f"javascript-demo: {error}")
    recorder.data.update(testSummaries=summaries, polyglotSummary=polyglot_summary,
                         failures=failures, passed=not failures)
    recorder.save()
    require(not failures, "\n".join(failures))


def publication_allowed(root, env):
    if env.get("GITHUB_REF") != "refs/heads/main":
        return False
    event = env.get("GITHUB_EVENT_NAME")
    if event not in ("push", "workflow_dispatch"):
        return False
    head = git(root, "rev-parse", "HEAD")
    if not SHA.fullmatch(head) or env.get("GITHUB_SHA") != head:
        return False
    if event == "workflow_dispatch" and env.get("EXPECTED_SHA") != head:
        return False
    # Token-authored merges need dispatch. Only exact current-main dispatches may
    # seed caches; a PR or an arbitrary dispatch branch never may.
    remote = git(root, "ls-remote", "origin", "refs/heads/main").split()
    return remote == [head, "refs/heads/main"]


def successful_revision(recorder):
    return (recorder.data.get("passed") is True and not recorder.data.get("driverError")
            and recorder.data.get("revision") == git(recorder.root, "rev-parse", "HEAD"))


def finish(recorder):
    recorder.data.update(finished=utc(), totalSeconds=round(time.time() - recorder.data["startedEpoch"], 6))
    recorder.data["actionCaches"] = {key: os.environ.get(key, "not-reported") for key in
        ("TOOLCHAIN_CACHE_HIT", "GRADLE_CACHE_HIT", "NATIVE_CACHE_HIT")}
    recorder.save()
    lines = ["## Fast checks", "", f"Revision: `{recorder.data['revision']}`", "",
             f"Elapsed since checkout: {recorder.data['totalSeconds']:.1f}s (includes setup/cache steps).",
             f"Native inputs: {recorder.data.get('nativeInputs', 'not reached')}.",
             f"Scope: {recorder.data.get('selection', {}).get('mode', 'not reached')}.", "",
             "| Phase | Seconds | Exit |", "| --- | ---: | ---: |"]
    lines.extend(f"| {phase['name']} | {phase['seconds']:.3f} | {phase['exitCode']} |"
                 for phase in recorder.data["phases"])
    lines.extend(["", "Warm ordinary PRs target 2–3 minutes; cold/bootstrap and widened checks may take longer."])
    text = "\n".join(lines) + "\n"
    (recorder.directory / "summary.md").write_text(text)
    print(text)
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a") as stream:
            stream.write(text)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("start", "identify", "run", "publish", "finish"))
    parser.add_argument("--report-dir", type=Path, default=Path(os.environ.get("FAST_REPORT_DIR", ROOT / "build/fast/results")))
    parser.add_argument("--identity", type=Path)
    parser.add_argument("--base", default=os.environ.get("FAST_BASE_SHA", ""))
    parser.add_argument("--head", default=os.environ.get("FAST_HEAD_SHA", "HEAD"))
    args = parser.parse_args(argv)
    recorder = Recorder(ROOT, args.report_dir.resolve())
    identity_path = args.identity or recorder.directory / "identity.json"
    try:
        if args.command == "start":
            require(not recorder.path.exists(), "Use a fresh report directory for each run")
            expected = os.environ.get("EXPECTED_SHA", "")
            require(not expected or expected == git(ROOT, "rev-parse", "HEAD"), "Dispatched revision mismatch")
            recorder.save()
        elif args.command == "identify":
            identify(recorder, identity_path)
        elif args.command == "run":
            execute(recorder, args.base, args.head, identity_path)
        elif args.command == "publish":
            allowed = successful_revision(recorder) and publication_allowed(ROOT, os.environ)
            if os.environ.get("GITHUB_OUTPUT"):
                with open(os.environ["GITHUB_OUTPUT"], "a") as stream:
                    stream.write("allowed=" + str(allowed).lower() + "\n")
            print("Trusted-main cache publication: " + str(allowed).lower())
        else:
            finish(recorder)
    except (RuntimeError, OSError, ValueError, KeyError, subprocess.CalledProcessError) as error:
        recorder.data["driverError"] = str(error)
        recorder.save()
        print("Fast checks failed: " + str(error), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
