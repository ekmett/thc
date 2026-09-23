#!/usr/bin/env python3
"""Run the Int-result thunk experiment matrix in sequential JVM processes.

Build the runtime/classpath and optional size agent before running a phase.
--dry-run needs neither build output nor an agent jar and creates no files.
"""

import argparse
import hashlib
import json
import os
import platform
import shlex
import subprocess
import sys
import time
from dataclasses import dataclass
from pathlib import Path


FLAGS = [
    "--enable-native-access=ALL-UNNAMED", "-Xss2m", "-Xms512m", "-Xmx512m",
    "-XX:+UseCompactObjectHeaders", "-Dthc.traceCompilation=true",
    "-Dpolyglot.engine.StaticObjectStorageStrategy=field-based",
]
MAIN_CLASS = "thc.runtime.IntThunkExperiment"
TIMEOUT_SECONDS = 180


@dataclass(frozen=True)
class Run:
    label: str
    arguments: tuple
    extra_flags: tuple = ()


def matrix(phase, agent):
    """Keep mode order, JVM flags and window parameters identical across machines."""
    size_agent = f"-javaagent:{agent}"
    cache_on = "-Dthc.boxedValueCache=true"
    if phase == "check":
        yield Run("semantic/cache-off", ("check",))
        yield Run("semantic/cache-on", ("check",), (cache_on,))
    elif phase == "layout":
        for case in ["never", "partial", "force-all", "reread", "published-read", "shared", "cached"]:
            modes = ["ordinary", "inline-direct"] + (["inline-copy"] if case == "force-all" else [])
            for mode in modes:
                extra = (size_agent,) + ((cache_on,) if case == "cached" else ())
                yield Run(f"layout/{case}-{mode}", ("heap", mode, case, 1024), extra)
        for mode in ["ordinary", "inline-direct"]:
            yield Run(f"layout/headers-off-{mode}", ("heap", mode, "force-all", 1024),
                      (size_agent, "-XX:-UseCompactObjectHeaders"))
    elif phase == "screen":
        for case in ["force-all", "never", "partial", "shared", "cached", "reread", "published-read"]:
            modes = ["ordinary", "inline-direct"] + (["inline-copy"] if case == "force-all" else [])
            for mode in modes:
                extra = (cache_on,) if case == "cached" else ()
                yield Run(f"screen/{case}-{mode}", ("bench", mode, case, 65536, 2, 3, 4, 1000), extra)
    elif phase == "threshold":
        for mode in ["ordinary", "inline-copy", "inline-direct"]:
            yield Run(f"layout/partial-half-{mode}", ("heap", mode, "partial-half", 1024), (size_agent,))
            yield Run(f"threshold/partial-half-{mode}", ("bench", mode, "partial-half", 65536, 2, 3, 1, 1000))
        for mode in ["ordinary", "inline-direct"]:
            yield Run(f"threshold/force-all-1024-{mode}", ("bench", mode, "force-all", 1024, 2, 3, 1, 1000))
    elif phase == "confirm":
        # Balanced three-fork matrix: five 2s warmup and five 2s measured windows.
        for fork in range(3):
            cases = ["force-all", "reread", "published-read", "shared"]
            cases = cases[fork:] + cases[:fork]
            for case in cases:
                modes = ["ordinary", "inline-copy", "inline-direct"] if case == "force-all" else ["ordinary", "inline-direct"]
                offset = fork % len(modes)
                modes = modes[offset:] + modes[:offset]
                for mode in modes:
                    size = 262144 if case in ["reread", "published-read"] else 65536
                    yield Run(f"confirm/fork{fork + 1}-{case}-{mode}", ("bench", mode, case, size, 5, 5, 1, 2000))


def absolute(path):
    return Path(path).expanduser().absolute()


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def emit(record):
    print(json.dumps(record), flush=True)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("phase", choices=["check", "layout", "screen", "threshold", "confirm"])
    parser.add_argument("--project", type=Path, default=Path.cwd(), help="THC project root (default: current directory)")
    parser.add_argument("--jdk", type=Path, default=os.environ.get("JAVA_HOME") or None,
                        help="Graal JDK home (default: JAVA_HOME; required when unset)")
    parser.add_argument("--output", type=Path,
                        help="Results directory (default: PROJECT/bench/results/int-result-thunks)")
    parser.add_argument("--agent", type=Path,
                        help="Size agent jar (default: PROJECT/bench/experiments/int-result-thunks/tools/intthunk-sizes.jar)")
    parser.add_argument("--dry-run", action="store_true", help="Print planned commands without reading build files or running/writing anything")
    args = parser.parse_args(argv)
    if args.jdk is None:
        parser.error("--jdk is required when JAVA_HOME is unset")

    project = absolute(args.project)
    java = absolute(args.jdk) / "bin/java"
    output = absolute(args.output) if args.output is not None else project / "bench/results/int-result-thunks"
    agent = absolute(args.agent) if args.agent is not None else project / "bench/experiments/int-result-thunks/tools/intthunk-sizes.jar"
    classpath_file = project / "build/intthunk-classpath.txt"
    runs = list(matrix(args.phase, agent))

    # Argument parsing and planning precede all build-file reads. In a clean checkout,
    # dry-run still displays each complete command template using an explicit placeholder.
    if args.dry_run:
        classpath = f"<contents of {classpath_file}>"
        for run in runs:
            command = [str(java), *FLAGS, *run.extra_flags, "-cp", classpath, MAIN_CLASS, *map(str, run.arguments)]
            emit({"status": "dry-run", "label": run.label, "cwd": str(project), "command": command,
                  "shellCommand": shlex.join(command), "classpathSource": str(classpath_file),
                  "log": str(output / f"{run.label}.log"), "manifest": str(output / f"{run.label}.json")})
        return 0

    if not java.is_file() or not os.access(java, os.X_OK):
        parser.error(f"Java executable is missing or not executable: {java}")
    if not classpath_file.is_file():
        parser.error(f"Missing {classpath_file}; run ./gradlew intThunkClasspath in {project} first")
    classpath = classpath_file.read_text().strip()
    if not classpath:
        parser.error(f"Classpath file is empty: {classpath_file}")
    if any(any(flag.startswith("-javaagent:") for flag in run.extra_flags) for run in runs) and not agent.is_file():
        parser.error(f"Missing size agent jar: {agent}")
    # Refuse the entire phase before launching anything if it would overwrite results.
    for run in runs:
        for suffix in ("log", "json"):
            path = output / f"{run.label}.{suffix}"
            if path.exists():
                parser.error(f"Refusing to overwrite existing result: {path}")

    runner_hash = sha256(Path(__file__).read_bytes())
    for run in runs:
        path = output / f"{run.label}.log"
        path.parent.mkdir(parents=True, exist_ok=True)
        command = [str(java), *FLAGS, *run.extra_flags, "-cp", classpath, MAIN_CLASS, *map(str, run.arguments)]
        started = time.time()
        emit({"status": "starting", "label": run.label})
        timed_out = False
        with path.open("x") as log:
            try:
                completed = subprocess.run(command, cwd=project, stdout=log, stderr=subprocess.STDOUT,
                                           timeout=TIMEOUT_SECONDS)
                exit_code = completed.returncode
            except subprocess.TimeoutExpired:
                timed_out = True
                exit_code = 124
                log.write(f"\nRunner timeout after {TIMEOUT_SECONDS} seconds\n")
            except OSError as error:
                exit_code = 127
                log.write(f"\nCould not start JVM: {error}\n")
        metadata = {
            "label": run.label, "phase": args.phase, "command": command, "shellCommand": shlex.join(command),
            "cwd": str(project), "startedUnix": started, "elapsedSeconds": time.time() - started,
            "exitCode": exit_code, "timedOut": timed_out, "timeoutSeconds": TIMEOUT_SECONDS,
            "log": str(path), "logSha256": sha256(path.read_bytes()),
            "commandSha256": sha256(json.dumps(command, separators=(",", ":")).encode()),
            "classpathSource": str(classpath_file), "classpathSha256": sha256(classpath.encode()),
            "runnerSha256": runner_hash, "platform": platform.platform(), "pythonVersion": platform.python_version(),
        }
        with path.with_suffix(".json").open("x") as manifest:
            manifest.write(json.dumps(metadata, indent=2) + "\n")
        emit({"status": "finished", "label": run.label, "exitCode": exit_code})
        if exit_code:
            print(path.read_text(errors="replace")[-6000:], file=sys.stderr, flush=True)
            return exit_code if exit_code > 0 else 128 - exit_code
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
