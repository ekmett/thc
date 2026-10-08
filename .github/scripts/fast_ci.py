#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

"""Execute fresh affected tests with reusable inputs and auditable phase timings.

This driver never treats Gradle task history or restored test XML as a result.
Both Test tasks run in one graph; compilation remains eligible for Gradle's build
cache and always-fresh native compilation/probes execute once for both forks.
"""
import argparse
from contextlib import ExitStack
from datetime import datetime, timezone
import hashlib
import importlib.util
import json
import io
import platform
import tarfile
import tempfile
import zipfile
from urllib.parse import urlparse
import os
import shutil
from pathlib import Path
import re
import signal
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
SHA = re.compile(r"[0-9a-f]{40}\Z")
HANDOFF_TASKS = {"default": "testDefault", "dense": "testDense"}
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


def write_trace(path, events):
    """Perfetto's Chrome JSON format; lanes show overlap, not OS thread identity."""
    ends = {}
    for event in sorted(events, key=lambda item: (item["ts"], -item["dur"])):
        lanes = ends.setdefault(event["pid"], [])
        lane = next((i for i, end in enumerate(lanes) if end <= event["ts"]), len(lanes))
        if lane == len(lanes):
            lanes.append(0)
        lanes[lane] = event["ts"] + event["dur"]
        event["tid"] = lane + 1
    labels = {1: "Build commands", 2: "Ninja edges", 3: "Gradle tasks"}
    metadata = [{"name": "process_name", "ph": "M", "pid": pid, "tid": 0,
                 "args": {"name": labels[pid]}} for pid in sorted(ends)]
    write_json(path, {"traceEvents": metadata + events, "displayTimeUnit": "ms"})


def ninja_events(path, previous, started):
    """Read only this invocation's appended log, grouping multi-output edges."""
    if not path.exists():
        return []
    data = path.read_bytes()
    require(data.startswith(previous), "Ninja log changed during tracing; refusing stale timings")
    header = data.splitlines()[0] if data else b""
    # v6 and v7 change command hashing, not the five timing/output fields.
    require(header in (b"# ninja log v5", b"# ninja log v6", b"# ninja log v7"),
            f"Unknown Ninja log format: {header!r}")
    edges = {}
    for line in data[len(previous):].decode().splitlines():
        if not line or line.startswith("#"):
            continue
        start, end, _, output, command = line.split("\t")
        key = (int(start), int(end), command)
        edges.setdefault(key, []).append(output)
    return [{"name": outputs[0], "cat": "Ninja edge", "ph": "X", "pid": 2,
             "ts": started + start * 1000, "dur": (end - start) * 1000,
             "args": {"outputs": outputs}}
            for (start, end, _), outputs in edges.items()]


def merge_traces(directory, output):
    events = []
    for path in sorted(directory.glob("*.events.json")):
        try:
            events.extend(event for event in json.loads(path.read_text())["traceEvents"] if event["ph"] == "X")
        except (OSError, ValueError, KeyError) as error:
            print(f"Incomplete build trace {path}: {error}", file=sys.stderr)
    write_trace(output, events)


class Recorder:
    def __init__(self, root, directory):
        self.root, self.directory = root, directory
        self.trace_directory = Path(os.environ.get("THC_BUILD_TRACE_DIR",
                                    directory / "traces" / f"run-{time.time_ns()}")).resolve()
        self.path = directory / "timings.json"
        self.data = json.loads(self.path.read_text()) if self.path.exists() else {
            "schema": 1, "started": utc(), "startedEpoch": time.time(),
            "revision": git(root, "rev-parse", "HEAD"), "phases": [],
        }

    def save(self):
        write_json(self.path, self.data)

    def command(self, name, argv, *, env=None, allowed=(0,), capture=False, stdout=None):
        self.directory.mkdir(parents=True, exist_ok=True)
        argv = list(map(str, argv))
        env = dict(os.environ if env is None else env)
        trace_dir = Path(env.get("THC_BUILD_TRACE_DIR", self.trace_directory)).resolve()
        trace_dir.mkdir(parents=True, exist_ok=True)
        env["THC_BUILD_TRACE_DIR"] = str(trace_dir)
        trace_prefix = trace_dir / f"{name}-{os.getpid()}-{time.time_ns()}"
        tool = Path(argv[0]).name
        ninja_log, previous = None, b""
        if tool in ("gradlew", "gradlew.bat"):
            env["THC_BUILD_TRACE"] = str(trace_prefix) + ".gradle.events.json"
        elif tool == "cmake" and "--build" in argv:
            build = self.root / argv[argv.index("--build") + 1]
            if (build / "build.ninja").is_file():
                # Recompact first so Ninja cannot rewrite old history beneath our
                # append offset. This retains hashes and never rebuilds outputs.
                subprocess.run(["ninja", "-C", str(build), "-t", "recompact"],
                               cwd=self.root, env=env, check=True, stdout=subprocess.DEVNULL)
                ninja_log = build / ".ninja_log"
                previous = ninja_log.read_bytes() if ninja_log.exists() else b""
        logfile = self.directory / (f"{len(self.data['phases']):02d}-{name}.log")
        phase = {"name": name, "command": list(map(str, argv)), "started": utc(),
                 "log": str(logfile.relative_to(self.root))}
        begin = time.monotonic()
        started = time.time_ns() // 1000
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
            events = [{"name": name, "cat": "Build command", "ph": "X", "pid": 1,
                       "ts": started, "dur": round(phase["seconds"] * 1000000),
                       "args": {"exitCode": phase.get("exitCode"), "command": phase["command"]}}]
            if ninja_log is not None:
                try:
                    # Artifact upload ignores hidden files. Keep the raw log
                    # beside the trace even if conversion fails or is interrupted.
                    raw_log = Path(str(trace_prefix) + ".ninja.log")
                    if ninja_log.exists():
                        shutil.copyfile(ninja_log, raw_log)
                        events[0]["args"].update(ninjaLog=raw_log.name, ninjaLogOffset=len(previous))
                    events.extend(ninja_events(ninja_log, previous, started))
                except (OSError, ValueError, RuntimeError) as error:
                    # Keep the build's real exit status and its command span.
                    events[0]["args"]["traceError"] = str(error)
                    print("Ninja trace incomplete: " + str(error), file=sys.stderr)
            write_trace(Path(str(trace_prefix) + ".command.events.json"), events)
        require(phase["exitCode"] in allowed, f"{name} failed: exit {phase['exitCode']}; see {logfile}")
        return phase["exitCode"], "".join(output)

    def parallel(self, commands, *, dependencies=None, environments=None, workers=4):
        """Run ready setup processes; terminate their groups on failure."""
        dependencies, environments = dependencies or {}, environments or {}
        queued, completed = dict(commands), set()
        require(len(queued) == len(commands), "Duplicate setup command")
        require(all(set(needs) <= queued.keys() for needs in dependencies.values()),
                "Unknown setup dependency")
        self.directory.mkdir(parents=True, exist_ok=True)
        children = []
        try:
            with ExitStack() as stack:
                pending = []
                while queued or pending:
                    for name, argv in list(queued.items()):
                        if len(pending) == workers:
                            break
                        if not set(dependencies.get(name, ())) <= completed:
                            continue
                        del queued[name]
                        logfile = self.directory / (name + ".log")
                        log = stack.enter_context(logfile.open("w"))
                        phase = {"name": name, "command": argv, "started": utc(),
                                 "startedEpoch": time.time(), "log": str(logfile.relative_to(self.root))}
                        print("Starting " + name + ": " + str(logfile), flush=True)
                        child = subprocess.Popen(argv, cwd=self.root, stdout=log, stderr=subprocess.STDOUT,
                                                 env=environments.get(name), start_new_session=True)
                        item = (child, phase, time.monotonic(), logfile)
                        children.append(item)
                        pending.append(item)
                    require(bool(pending), "Cyclic setup dependencies")
                    for item in list(pending):
                        child, phase, begin, logfile = item
                        code = child.poll()
                        if code is None:
                            continue
                        pending.remove(item)
                        phase.update(exitCode=code, seconds=round(time.monotonic() - begin, 6), finished=utc())
                        self.data["phases"].append(phase)
                        self.save()
                        print("::group::" + phase["name"], flush=True)
                        print(logfile.read_text(), end="", flush=True)
                        print("::endgroup::", flush=True)
                        require(code == 0, f"{phase['name']} failed: exit {code}; see {logfile}")
                        completed.add(phase["name"])
                    if pending:
                        time.sleep(0.1)
        finally:
            for child, _, _, _ in children:
                if child.poll() is None:
                    try:
                        os.killpg(child.pid, signal.SIGTERM)
                    except ProcessLookupError:
                        pass
            for child, phase, begin, _ in children:
                try:
                    child.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    os.killpg(child.pid, signal.SIGKILL)
                    child.wait()
                if "exitCode" not in phase:
                    phase.update(exitCode=child.returncode, seconds=round(time.monotonic() - begin, 6), finished=utc())
                    self.data["phases"].append(phase)
            self.save()
            events = [{"name": phase["name"], "cat": "Setup", "ph": "X", "pid": 1,
                       "ts": round(phase["startedEpoch"] * 1000000),
                       "dur": round(phase["seconds"] * 1000000),
                       "args": {"exitCode": phase["exitCode"]}}
                      for _, phase, _, _ in children]
            write_trace(self.trace_directory / f"setup-{time.time_ns()}.events.json", events)


def action_outputs(path):
    """Read the runner's single-line and multiline output command format."""
    lines = iter(path.read_text().splitlines())
    values = {}
    for line in lines:
        if "<<" in line:
            name, delimiter = line.split("<<", 1)
            value = []
            for part in lines:
                if part == delimiter:
                    break
                value.append(part)
            else:
                raise RuntimeError(f"Unterminated action output: {name}")
            values[name] = "\n".join(value)
        elif "=" in line:
            name, value = line.split("=", 1)
            values[name] = value
    return values


def cache_restores(recorder, layers):
    """Invoke the runner-downloaded official action, with isolated output files.

    setup/action.yml declares actions/cache@v4 as a dependency. The runner
    downloads it before executing the composite; github-script supplies the
    same Node runtime and cache-service credentials as a normal cache action.
    """
    action = Path(os.environ["THC_CACHE_ACTION"]) / "dist/restore-only/index.js"
    require(action.is_file(), f"Declared actions/cache@v4 restore entry point missing: {action}")
    commands, environments, outputs = [], {}, {}
    recorder.directory.mkdir(parents=True, exist_ok=True)
    for name, layer in layers.items():
        if not layer.get("enabled", True):
            continue
        output = recorder.directory / ("cache-" + name + ".outputs")
        output.write_text("")
        env = {key: value for key, value in os.environ.items() if not key.startswith("INPUT_")}
        env.update({"INPUT_PATH": layer["path"], "INPUT_KEY": layer["key"],
                    "INPUT_RESTORE-KEYS": layer.get("restore-keys", ""),
                    "INPUT_ENABLECROSSOSARCHIVE": "false", "INPUT_LOOKUP-ONLY": "false",
                    "INPUT_FAIL-ON-CACHE-MISS": "false", "GITHUB_OUTPUT": str(output)})
        job = "restore-" + name
        commands.append((job, [os.environ["THC_NODE"], str(action)]))
        environments[job] = env
        outputs[name] = output
    return commands, environments, outputs


def jam_package(root):
    """Select one binary pin; source edits cannot change its cache identity."""
    pin = json.loads((root / "etc/jam-graalvm.json").read_text())
    require(pin["schema"] == 1, "Unsupported JAM package pin schema")
    machine = {"AMD64": "x86_64", "aarch64": "arm64"}.get(platform.machine(), platform.machine())
    host = platform.system() + "-" + machine
    require(host in pin["platforms"], f"No JAM GraalVM package is pinned for {host}")
    package = pin["platforms"][host]
    runtime, transport = package["runtime"], package["transport"]
    def version(value):
        parts = tuple(int(part) for part in value.split("."))
        return parts + (0,) * max(0, 3 - len(parts))
    if "minimumMacOS" in runtime:
        require(version(platform.mac_ver()[0]) >= version(runtime["minimumMacOS"]),
                f"Pinned JAM package requires macOS {runtime['minimumMacOS']} or newer")
    if "minimumGlibc" in runtime:
        libc, found = platform.libc_ver()
        require(libc == "glibc" and version(found) >= version(runtime["minimumGlibc"]),
                f"Pinned JAM package requires glibc {runtime['minimumGlibc']} or newer; found {libc} {found}")
    require(runtime["installation"]["algorithm"] == "sha256-path-manifest-v1", "Unsupported JAM installation digest")
    digests = [runtime["installation"]["sha256"], transport["tarSha256"]]
    if "zipSha256" in transport:
        digests.append(transport["zipSha256"])
    require(all(re.fullmatch(r"[0-9a-f]{64}", digest) for digest in digests), "Invalid JAM package digest")
    identity = hashlib.sha256((host + "\n" + "\n".join(digests)).encode()).hexdigest()
    home = Path(os.environ.get("THC_TOOLS", "~/.cache/thc-toolchains")).expanduser() / ("jam-" + identity) / "graalvm"
    return package, home, "installed-jam-" + host + "-" + identity


def jam_identity(root):
    _, home, key = jam_package(root)
    with open(os.environ["GITHUB_OUTPUT"], "a") as stream:
        stream.write(f"jam-home={home.as_posix()}\njam-root={home.parent.as_posix()}\njam-key={key}\n")


def verify_jam(recorder, java_home):
    launcher = "gradlew.bat" if platform.system() == "Windows" else "gradlew"
    env = dict(os.environ, JAVA_HOME=str(java_home), GRAALVM_HOME=str(java_home))
    recorder.command("verify-jam", [str(recorder.root / launcher), "--no-daemon", "-q", "verifyJamToolchain"], env=env)


def jam_release_identity(root):
    """Match the selected release and the existing complete-verifier receipt."""
    package, _, _ = jam_package(root)
    home = Path(os.environ["JAVA_HOME"]).resolve()
    with (home / "release").open("rb") as stream:
        release = hashlib.file_digest(stream, "sha256").hexdigest()
    require(release == package["runtime"]["sha256"]["release"], "Pinned JAM release differs from JAVA_HOME")
    receipt = root / "build/toolchain/jam-verified.json"
    require(receipt.is_file(), "Run verifyJamToolchain before consuming the JAM package")
    verified = json.loads(receipt.read_text())
    require(Path(verified["javaHome"]).resolve() == home
            and verified["installation"] == package["runtime"]["installation"]["sha256"],
            "Run verifyJamToolchain on the selected pinned JAM package")
    return release


def install_jam(recorder):
    started = time.monotonic()
    package, home, key = jam_package(recorder.root)
    transport = package["transport"]
    require(not home.is_symlink(), "JAM cache home must be a directory, not a symbolic link")
    if not home.is_dir():
        kind, url = transport["kind"], transport["url"]
        parsed = urlparse(url)
        require(parsed.scheme == "https" and not parsed.username and not parsed.password,
                "JAM transport requires an HTTPS URL without credentials")
        require(kind in ("github-actions-artifact", "github-release-asset"), "Unsupported JAM package transport")
        token = ""
        if kind == "github-actions-artifact":
            require(parsed.netloc == "api.github.com" and "/actions/artifacts/" in parsed.path,
                    "JAM artifact transport requires the GitHub artifact API")
            token = os.environ.get("GH_TOKEN", "")
            require(bool(token), "Temporary JAM Actions artifacts require GH_TOKEN with producer Actions read access")
            require(datetime.fromisoformat(transport["expiresAt"].replace("Z", "+00:00")) > datetime.now(timezone.utc),
                    "Pinned JAM Actions artifact has expired; publish retained package bytes")
        else:
            require(parsed.netloc == "github.com" and "/releases/download/" in parsed.path,
                    "JAM release transport requires a versioned GitHub release asset URL")
        home.parent.parent.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(prefix="jam-acquire-", dir=home.parent.parent) as temporary:
            stage = Path(temporary)
            archive = stage / "package"
            # curl drops Authorization on cross-host redirects. Keep credentials
            # out of process arguments and CI receipts; remove the private config
            # with the temporary staging directory on success or failure.
            config = stage / "curl.conf"
            config.touch(mode=0o600)
            require(not any(char in token for char in '\n\r"'), "Invalid GitHub credential")
            config.write_text(f'header = "Authorization: Bearer {token}"\n' if token else "")
            recorder.command("download-jam", ["curl", "--config", str(config), "--fail", "--location", "--silent", "--show-error",
                                              "--connect-timeout", "30", "--max-time", "600", url, "--output", str(archive)])
            def check_digest(path, expected):
                with path.open("rb") as stream:
                    require(hashlib.file_digest(stream, "sha256").hexdigest() == expected, "Pinned JAM archive digest differs")
            if "zipSha256" in transport:
                check_digest(archive, transport["zipSha256"])
                with zipfile.ZipFile(archive) as outer:
                    files = [item for item in outer.infolist() if not item.is_dir()]
                    require(len(files) == 1 and files[0].filename.endswith(".tar.gz"), "JAM ZIP must contain one tar.gz")
                    payload = stage / "payload.tar.gz"
                    with outer.open(files[0]) as source, payload.open("wb") as target:
                        shutil.copyfileobj(source, target)
            else:
                payload = archive
            check_digest(payload, transport["tarSha256"])
            unpacked = stage / "unpacked"
            unpacked.mkdir()
            with tarfile.open(payload) as tar:
                tar.extractall(unpacked, filter="data")
            require((unpacked / "graalvm/release").is_file(), "JAM package must contain graalvm/release")
            require(not home.parent.exists(), f"Incomplete JAM cache exists: {home.parent}; preserve it for inspection")
            unpacked.rename(home.parent)
    recorder.data["jamPackage"] = {"cacheKey": key, "javaHome": home.as_posix(), "transport": transport,
                                   "acquisitionSeconds": round(time.monotonic() - started, 6),
                                   "installation": package["runtime"]["installation"]}
    recorder.data["passed"] = True
    recorder.save()
    with open(os.environ["GITHUB_ENV"], "a") as stream:
        stream.write(f"JAVA_HOME={home.as_posix()}\nGRAALVM_HOME={home.as_posix()}\n")
    with open(os.environ["GITHUB_PATH"], "a") as stream:
        stream.write(f"{home.as_posix()}/bin\n")
    return home


def setup_toolchain(recorder):
    """Restore independent caches and check out sources, then install misses."""
    host = (platform.system(), platform.machine())
    releases = {
        ("Darwin", "arm64"): ("aarch64-apple-darwin", "4e521e008fe0813db6db4b91cfeebd0c44c80c68afb458ea32a1c94cf5c7cc1d"),
        ("Linux", "x86_64"): ("x86_64-linux", "9ed5da5449b48043a0d17e767c05d2ef585e25a639bb934329496c6d2fad9cf8"),
    }
    require(os.environ.get("GITHUB_ACTIONS") == "true" and host in releases,
            "Toolchain setup requires a hosted Linux x64 or macOS ARM64 Actions runner")
    ghcup_arch, ghcup_sha = releases[host]
    _, java_home, _ = jam_package(recorder.root)
    # Upstream release SHA256SUMS / asset digests. Installed tools are a separate
    # cache layer; source and dependency edits never change their cache keys.
    download = r'''
set -euo pipefail
download() {
  curl --fail --location --silent --show-error --retry 3 "$1" --output "$2"
  printf '%s  %s\n' "$3" "$2" | shasum -a 256 --check
}
mkdir -p "$THC_TOOLS"
'''
    haskell = download + r'''
ghc_bin="$THC_GHCUP_ROOT/ghc/9.14.1/bin"
cabal_bin="$THC_GHCUP_ROOT/cabal/3.16.0.0"
export GHCUP_INSTALL_BASE_PREFIX="$(dirname "$THC_GHCUP_ROOT")"
if [ ! -x "$ghc_bin/ghc" ] || [ ! -x "$cabal_bin/cabal" ]; then
  ghcup="$THC_TOOLS/ghcup-0.2.6.2"
  if [ ! -x "$ghcup" ]; then
    download "https://downloads.haskell.org/ghcup/0.2.6.2/GHCUP_ARCH-ghcup-0.2.6.2" "$ghcup" GHCUP_SHA
    chmod +x "$ghcup"
  fi
  export GHCUP_SKIP_UPDATE_CHECK=1
  if [ ! -x "$ghc_bin/ghc" ]; then "$ghcup" install ghc 9.14.1; fi
  if [ ! -x "$cabal_bin/cabal" ]; then "$ghcup" install cabal 3.16.0.0 --isolate "$cabal_bin"; fi
fi
test "$("$ghc_bin/ghc" --numeric-version)" = 9.14.1
test "$("$ghc_bin/ghc-pkg" --version)" = 'GHC package manager version 9.14.1'
test "$("$cabal_bin/cabal" --numeric-version)" = 3.16.0.0
'''
    haskell = haskell.replace("GHCUP_ARCH", ghcup_arch).replace("GHCUP_SHA", ghcup_sha)
    if host[0] == "Darwin":
        native = r'''
set -euo pipefail
if [ ! -x /opt/homebrew/Cellar/llvm@18/18.1.8/bin/clang ]; then
  brew install llvm@18
fi
ln -sfn /opt/homebrew/Cellar/llvm@18/18.1.8 /opt/homebrew/opt/llvm@18
command -v cmake && command -v ninja || brew install cmake ninja
/opt/homebrew/opt/llvm@18/bin/clang --version
'''
        llvm_bin = "/opt/homebrew/opt/llvm@18/bin"
    else:
        native = r'''
set -euo pipefail
if ! dpkg-query -W -f='${Status}\n' clang-18 llvm-18 libgmp-dev cmake ninja-build 2>/dev/null | awk '$0 != "install ok installed" {bad=1} END {exit bad}'; then
  sudo apt-get update
  sudo apt-get install --yes clang-18 llvm-18 libgmp-dev cmake ninja-build
fi
/usr/lib/llvm-18/bin/clang --version
'''
        llvm_bin = "/usr/lib/llvm-18/bin"
    commands = [
        ("source-submodules", ["git", "-c", "core.autocrlf=false", "submodule", "update", "--init", "--depth", "1", "--jobs", "4"]),
        ("haskell-toolchain", ["bash", "-c", haskell]),
        ("graalvm-toolchain", [sys.executable, str(recorder.root / ".github/scripts/fast_ci.py"),
                               "install-jam", "--report-dir", str(recorder.directory / "jam-acquisition")]),
        ("native-toolchain", ["bash", "-c", native]),
    ]
    layers = json.loads(os.environ.get("THC_CACHE_LAYERS", "{}"))
    if layers:
        restores, environments, outputs = cache_restores(recorder, layers)
        dependencies = {"haskell-toolchain": ["restore-cabal"],
                        "graalvm-toolchain": ["restore-graalvm"]}
        if host[0] == "Darwin":
            dependencies["haskell-toolchain"].append("restore-ghc")
            dependencies["native-toolchain"] = ["restore-llvm"]
        recorder.parallel(commands + restores, dependencies=dependencies, environments=environments)
        with open(os.environ["GITHUB_OUTPUT"], "a") as stream:
            for name, output in outputs.items():
                hit = action_outputs(output).get("cache-hit", "")
                stream.write(f"{name}-cache-hit={hit}\n")
    else:
        recorder.parallel(commands)
    verify_jam(recorder, java_home)
    with open(os.environ["GITHUB_ENV"], "a") as stream:
        stream.write(f"JAVA_HOME={java_home}\nGRAALVM_HOME={java_home}\n")
        stream.write(f"GHC={os.environ['THC_GHCUP_ROOT']}/ghc/9.14.1/bin/ghc\n"
                     f"GHC_PKG={os.environ['THC_GHCUP_ROOT']}/ghc/9.14.1/bin/ghc-pkg\n"
                     f"CABAL={os.environ['THC_GHCUP_ROOT']}/cabal/3.16.0.0/cabal\n")
    with open(os.environ["GITHUB_PATH"], "a") as stream:
        stream.write(f"{java_home}/bin\n{os.environ['THC_GHCUP_ROOT']}/ghc/9.14.1/bin\n"
                     f"{os.environ['THC_GHCUP_ROOT']}/cabal/3.16.0.0\n{llvm_bin}\n")
    recorder.data["passed"] = True
    recorder.save()


def validate_xml(directory, expected, patterns=None):
    """Require fresh suites; only the Windows-only suite may be disabled on Linux."""
    files = sorted(directory.glob("TEST-*.xml"))
    require(bool(files), "No fresh JUnit XML")
    classes, cases, platform_skips = set(), [], []
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
        require(not any(suite.findall(".//" + tag) for tag in ("failure", "error")),
                f"Unsuccessful testcase in {name}")
        skipped = suite.findall(".//skipped")
        if skipped:
            # Fast checks run on Linux; this entire suite is @EnabledOnOs(WINDOWS)
            # and has its own native Windows workflow. Never accept an arbitrary
            # assumption abort, partial suite or missing Windows run as a pass.
            require(sys.platform == "linux" and name in (
                        "thc.WindowsDistributionTest", "thc.runtime.WindowsDirectoryStreamsTest",
                        "thc.runtime.WindowsCodePagesTest", "thc.runtime.WindowsAbiInitializationTest")
                    and len(skipped) == count and int(suite.attrib.get("skipped", "0")) == count
                    and all(len(case.findall("skipped")) == 1 for case in children),
                    f"Unsuccessful testcase in {name}")
        for case in children:
            require(case.attrib.get("classname") == name, f"Mismatched testcase class: {name}")
            require(bool(case.attrib.get("name")), f"Unnamed testcase: {name}")
            (platform_skips if skipped else cases).append([name, case.attrib["name"]])
    require(classes == set(expected),
            f"JUnit class mismatch: missing={sorted(set(expected) - classes)}, extra={sorted(classes - set(expected))}")
    require(not any(totals[key] for key in ("failures", "errors"))
            and totals["skipped"] == len(platform_skips),
            "JUnit reports failures/errors/skips: " + repr(totals))
    require(bool(cases), "No executed JUnit testcases")
    if patterns is not None:
        validate_methods(cases + platform_skips, expected, patterns)
    return {**totals, "classes": sorted(classes), "cases": sorted(cases),
            "platformSkippedCases": sorted(platform_skips), "xmlFiles": len(files)}


def validate_methods(cases, classes, patterns):
    for name in classes:
        if name in patterns:
            continue
        methods = {p[len(name) + 1:] for p in patterns if p.startswith(name + ".")}
        observed = {case.split("(", 1)[0] for owner, case in cases if owner == name}
        require(methods and observed == methods,
                f"Partial JUnit method mismatch in {name}: expected={sorted(methods)}, actual={sorted(observed)}")


def gradle_command(selection, *, install_dist=False, fail_fast=True, reuse_daemon=False):
    require(selection.get("runnable") is True, "Selector could not establish a runnable test inventory")
    require(selection.get("mode") in ("narrow", "full"), "Invalid selection mode")
    classes = selection["junit"]["classes"]
    require(classes and len(set(classes)) == len(classes), "Empty/duplicate selected classes")
    require(all(isinstance(c, str) and re.fullmatch(r"[A-Za-z_][\w.$]*", c) for c in classes),
            "Invalid selected class name")
    # Standalone runs avoid external daemon state; CI batches own a fresh worker daemon.
    argv = ["./gradlew", "--daemon" if reuse_daemon else "--no-daemon", "--max-workers=4", "--build-cache", "--continue",
            "--init-script", ".github/scripts/fast_ci.init.gradle"]
    if install_dist:
        argv.append("installDist")
    if selection["mode"] == "narrow":
        patterns = selection["junit"]["patterns"]
        require(isinstance(patterns, list) and patterns and all(isinstance(p, str) for p in patterns)
                and len(patterns) == len(set(patterns)), "Empty/duplicate narrow patterns")
        require(all(p in classes or p.rsplit(".", 1)[0] in classes
                    and re.fullmatch(r"[A-Za-z_$][\w$]*", p.rsplit(".", 1)[-1]) for p in patterns),
                "Narrow patterns must name selected classes or exact test methods")
        require({p if p in classes else p.rsplit(".", 1)[0] for p in patterns} == set(classes),
                "Narrow patterns must cover every selected class")
        require(not any(p.rsplit(".", 1)[0] in patterns for p in patterns if p not in classes),
                "A full class must not hide a partial method selection")
    else:
        require(selection["junit"]["patterns"] == ["*"], "Full mode must run every test")
    for task in HANDOFF_TASKS.values():
        argv.extend([task, "--rerun"])
        if fail_fast:
            argv.append("--fail-fast")
        if selection["mode"] == "narrow":
            for name in patterns:
                argv.extend(["--tests", name])
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
    return ["./gradlew", "--no-daemon", "--max-workers=4", "--build-cache",
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


PYTHON_AUDITORS = {"bin/test-audit-core.py", "bin/test-core-package-manifest.py"}


def python_auditor_environment(recorder):
    fixtures.build_cmake_targets(["fixture-tools", "prepare-foreign-ownership"], recorder.command)
    env = os.environ.copy()
    for variable, marker in (("THC_FIXTURES", "thc-fixtures.path"), ("THC_COMPACT", "thc-compact.path")):
        path = Path((recorder.root / "build" / marker).read_text().strip())
        require(path.is_file() and os.access(path, os.X_OK), f"Missing graph-produced {variable}: {path}")
        env[variable] = str(path)
    command = recorder.root / "build/foreign-ownership.json"
    require(command.is_file(), f"Missing graph-produced ownership command: {command}")
    env["THC_FOREIGN_OWNERSHIP"] = str(command)
    return env


def run_python_checks(recorder, selection, automation_checked):
    failures, auditor_env, auditor_failed = [], None, False
    for index, command in enumerate(python_commands(selection, sys.executable, automation_checked)):
        env = None
        if PYTHON_AUDITORS.intersection(command):
            if auditor_failed:
                continue
            if auditor_env is None:
                try:
                    auditor_env = python_auditor_environment(recorder)
                except (RuntimeError, OSError) as error:
                    failures.append(f"Python auditors could not prepare their inputs: {error}")
                    auditor_failed = True
                    continue
            env = auditor_env
        try:
            recorder.command(f"python-{index:03d}", command, env=env)
        except RuntimeError as error:
            failures.append(str(error))
    return failures


def haskell_suites(selection):
    selected = selection.get("haskell")
    require(isinstance(selected, dict) and isinstance(selected.get("suites"), list)
            and selected.get("count") == len(selected["suites"]),
            "Invalid Haskell test selection")
    suites = selected["suites"]
    require(isinstance(suites, list) and all(name in ("driver-tests", "primop-tools", "compact-core-tests") for name in suites)
            and len(suites) == len(set(suites)),
            "Unknown or duplicate Haskell test suite")
    return suites


def haskell_compile_targets(selection):
    selected = selection.get("haskell")
    require(isinstance(selected, dict), "Invalid Haskell compilation selection")
    targets = selected.get("compileTargets", [])
    require(isinstance(targets, list) and all(isinstance(target, str) and
            re.fullmatch(r"test:[a-z][a-z0-9-]*-full-core", target) for target in targets)
            and len(targets) == len(set(targets)), "Invalid or duplicate Haskell compilation target")
    return targets


def preserve_previous(root, destination, task="test"):
    # Move only the exact test task output, not build/ or unrelated user data.
    require(task in ("test", "polyglotTest", *HANDOFF_TASKS.values()), "Unknown test task output")
    for source, suffix in ((root / "build/test-results" / task, "xml"),
                           (root / "build/reports/tests" / task, "html")):
        if source.exists() or source.is_symlink():
            require(source.is_dir() and not source.is_symlink(), f"Unexpected test output: {source}")
            target = destination / suffix
            require(not target.exists(), f"Receipt already exists: {target}; use a fresh report directory")
            target.parent.mkdir(parents=True, exist_ok=True)
            source.rename(target)


def run_modes(recorder, selection, *, install_dist=False):
    root, directory = recorder.root, recorder.directory
    for mode, task in HANDOFF_TASKS.items():
        preserve_previous(root, directory / ("prior-" + mode), task)
    code, _ = recorder.command("junit-handoff-modes", gradle_command(selection, install_dist=install_dist),
                               allowed=tuple(range(-128, 256)))
    # Save both failed and successful forks before validating either. Each task
    # owns its XML directory; --continue lets the other fork run after a failure.
    for mode, task in HANDOFF_TASKS.items():
        preserve_previous(root, directory / mode, task)
    failures = [] if code == 0 else [f"Gradle handoff batch failed with exit {code}"]
    summaries = {}
    for mode in HANDOFF_TASKS:
        try:
            xml = directory / mode / "xml"
            summary = validate_xml(xml, selection["junit"]["classes"],
                                   selection["junit"]["patterns"] if selection["mode"] == "narrow" else None)
            # HandoffTest belongs to every smoke/full selection. This marker is
            # printed only after checking the actual fork property and runtime
            # context, without changing either to manufacture the expected mode.
            proof = xml / "TEST-thc.runtime.HandoffTest.xml"
            require(proof.is_file(), f"Missing {mode} test-process handoff proof")
            markers = [line for output in ET.parse(proof).getroot().findall("system-out")
                       for line in (output.text or "").splitlines() if line.startswith("THC_HANDOFF_MODE=")]
            expected = "THC_HANDOFF_MODE=" + ("true" if mode == "dense" else "false")
            require(markers == [expected], f"Wrong/missing {mode} test-process handoff proof: {markers}")
            summary["handoffSlabs"] = mode == "dense"
            summaries[mode] = summary
            write_json(directory / mode / "summary.json", summary)
        except (RuntimeError, ValueError, ET.ParseError) as error:
            failures.append(f"{mode}: {error}")
    if len(summaries) == 2 and any(summaries["default"][key] != summaries["dense"][key]
                                    for key in ("cases", "platformSkippedCases")):
        failures.append("Default and dense handoff executed different testcase sets")
    return summaries, failures


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
    jam_release_identity(root)
    _, output = recorder.command("input-identity", [sys.executable, ".github/scripts/fast_inputs.py",
                                "key", "--output", str(identity_path)], capture=True)
    keys = [line for line in output.splitlines() if re.fullmatch(r"thc-fast-inputs-v1-[0-9a-f]{64}", line)]
    require(len(keys) == 1, "Input helper did not return exactly one cache key")
    # Gradle itself validates task inputs. This prefix prevents reuse across tool,
    # dependency, compiler-plugin, wrapper or cache-policy changes.
    paths = [root / name for name in ("build.gradle", "settings.gradle", "gradle.properties",
             "gradlew", "nih/gradle/wrapper/gradle-wrapper.jar", "nih/gradle/wrapper/gradle-wrapper.properties")]
    paths.extend(sorted((root / "src/gradle").glob("*.gradle")))
    paths.extend(sorted(path for path in (root / "src/build").rglob("*")
                        if path.is_file() and path.suffix in (".java", ".gradle")
                        and not any(part in ("build", ".gradle") for part in path.relative_to(root / "src/build").parts)))
    paths.extend([root / "etc/jam-graalvm.json", release, Path(__file__), root / ".github/scripts/fast_ci.init.gradle"])
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
                                "--base", base, "--head", head, "--cadence", "commit"], capture=True)
    selection = json.loads(output)
    write_json(recorder.directory / "selection.json", selection)
    require(selection.get("cadence") == "commit", "Fast checks require an explicit commit-cadence selection")
    gradle_command(selection)  # Fail closed before preparing or running anything.
    polyglot = polyglot_command(selection)
    selected_haskell = haskell_suites(selection)
    compile_targets = haskell_compile_targets(selection)
    recorder.data["selection"] = {key: selection[key] for key in
                                  ("mode", "reasons", "requestedMode", "cadence", "deferred") if key in selection}
    recorder.data.update(requestedBase=base, selectionBase=base)
    # Check generated documentation against the actual pinned GHC API on hits
    # as well as misses. Keep this fresh report separate from cached provenance.
    recorder.command("primop-checklist", ["cabal", "run", "exe:thc-primops", "--", "coverage",
                     "--check", "--output", str(recorder.directory / "primop-coverage.json")])
    inputs = fixtures.prepare_cmake(recorder.root, selection, recorder.command)
    recorder.data["nativeInputs"] = inputs
    recorder.save()
    failures = []
    if compile_targets:
        try:
            recorder.command("haskell-compile", ["cabal", "build", *compile_targets,
                                                 "-fdevelopment", "-ffull-core-tests"])
        except RuntimeError as error:
            failures.append("haskell-compile: " + str(error))
    automation_sha = os.environ.get("FAST_AUTOMATION_SHA", "")
    automation_checked = bool(SHA.fullmatch(automation_sha)) and automation_sha == git(recorder.root, "rev-parse", "HEAD")
    recorder.data["automationReused"] = automation_sha if automation_checked else None
    failures.extend(run_python_checks(recorder, selection, automation_checked))
    summaries = {}
    try:
        summaries, mode_failures = run_modes(recorder, selection, install_dist=False)
        failures.extend(mode_failures)
    except (RuntimeError, ValueError, ET.ParseError) as error:
        failures.append(f"handoff modes: {error}")
    for suite in selected_haskell:
        try:
            recorder.command(suite, ["cabal", "test", suite, "-fdevelopment",
                                    "--test-show-details=direct"] +
                             (["--test-options=--unit-only"] if suite == "driver-tests" else []))
        except RuntimeError as error:
            failures.append(suite + ": " + str(error))
    polyglot_summary = None
    if polyglot is not None:
        try:
            for demo in ("polyglot", "javascript"):
                recorder.command(f"{demo}-demo", [f"bin/{demo}-demo.sh"])
            polyglot_summary = run_polyglot(recorder, selection)
        except (RuntimeError, ValueError, ET.ParseError) as error:
            failures.append(f"polyglot: {error}")
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
    deferred = {scope: {kind: len(tests) for kind, tests in coverage.items()}
                for scope, coverage in recorder.data.get("selection", {}).get("deferred", {}).items()}
    lines = ["## Fast checks", "", f"Revision: `{recorder.data['revision']}`", "",
             f"Elapsed since checkout: {recorder.data['totalSeconds']:.1f}s (includes setup/cache steps).",
             f"Native inputs: {recorder.data.get('nativeInputs', 'not reached')}.",
             f"Scope: {recorder.data.get('selection', {}).get('cadence', recorder.data.get('selection', {}).get('mode', 'not reached'))}.",
             f"Deferred coverage: {deferred}; exact classes are in selection.json.", "",
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


COMMON_OUTPUTS = ("dist-newstyle", ".gradle", "src/build/build", "src/build/.gradle",
                  "build/classes", "build/generated", "build/resources", "build/install",
                  "build/diagnostics", "build/libs", "build/scripts", "build/plugin",
                  "build/native", "build/compiler", "build/thc-fixtures.path", "build/fixtures")


def compile_common(recorder, *, reuse_daemon=False):
    recorder.data["selection"] = {"mode": "compile-only", "reasons": []}
    recorder.command("common-cabal", ["cabal", "build", "exe:thc"])
    recorder.command("common-gradle", ["./gradlew", "--daemon" if reuse_daemon else "--no-daemon",
                                      "--max-workers=4", "--build-cache", "--profile", "installDist"])
    recorder.data.update(passed=True, nativeInputs="not acquired")
    recorder.save()


def compile_test_support(recorder, *, reuse_daemon=False):
    recorder.command("common-test-tools", ["cabal", "build", "exe:thc-primops"])
    recorder.command("common-fixture-dependencies", ["cabal", "build", "--only-dependencies",
        "lib:thc", "exe:thc-fixtures", "exe:thc-compact", "exe:thc-interface"])
    recorder.command("common-scalars", ["cabal", "run", "exe:thc-primops", "--", "scalars"])
    configure = ["cmake", "-S", ".", "-B", "build/fixtures", "-G", "Ninja"]
    for tool in ("GHC", "GHC_PKG", "CABAL"):
        if os.environ.get(tool):
            configure.append("-D" + tool + "=" + os.environ[tool])
    recorder.command("common-fixture-configure", configure)
    recorder.command("common-fixture-tools", ["cmake", "--build", "build/fixtures", "--parallel", "4", "--target", "fixture-tools"])
    recorder.command("common-test-classes", ["./gradlew", "--daemon" if reuse_daemon else "--no-daemon",
                                            "--max-workers=4", "--build-cache", "--profile", "testClasses", "toolsJar"])
    recorder.data.update(passed=True, nativeInputs="not acquired")
    recorder.save()


def common_identity(root):
    import library_bundle
    identity = library_bundle.identity(root)
    require(subprocess.check_output(["ghc", "--numeric-version"], text=True).strip() == "9.14.1",
            "Grouped jobs require GHC 9.14.1")
    identity["ghcLibdir"] = subprocess.check_output(["ghc", "--print-libdir"], text=True).strip()
    return identity


def pack_common(root, archive):
    """Only compile outputs; fixture acquisition and test results stay in their jobs."""
    identity = common_identity(root)
    archive.parent.mkdir(parents=True, exist_ok=True)
    with tarfile.open(archive, "w:gz", compresslevel=1) as bundle:
        data = json.dumps(identity).encode()
        record = tarfile.TarInfo("common-identity.json")
        record.size = len(data)
        bundle.addfile(record, io.BytesIO(data))
        for name in COMMON_OUTPUTS:
            if (root / name).exists():
                bundle.add(root / name, arcname=name)


def restore_common(root, archive):
    with tarfile.open(archive, "r:*") as bundle:
        record = bundle.extractfile("common-identity.json")
        require(record is not None and json.load(record) == common_identity(root),
                "Common outputs differ in source, Build attempt, platform or pinned toolchain")
        members = [member for member in bundle.getmembers() if member.name != "common-identity.json"]
        require(all(any(member.name == name or member.name.startswith(name + "/")
                        for name in COMMON_OUTPUTS) for member in members),
                "Unexpected common compile output")
        bundle.extractall(root, members=members, filter="data")


def run_group(recorder, name, *, reuse_daemon=False, cadence=None, prepared=False, exact_class=None):
    import fast_select
    selection = fast_select.group_selection(recorder.root, name, cadence=cadence, exact_class=exact_class)
    manifest, owners = fixtures._manifest(recorder.root)
    require(all(c in owners for c in selection["junit"]["classes"]), "Unowned selected class")
    recorder.data["selection"] = {"mode": "group", "group": name, "cadence": cadence,
                                  "junit": selection["junit"], "exactClass": exact_class, "reasons": []}
    # Unknown ownership fails above; a group job must never widen to all fixtures.
    recorder.data["nativeInputs"] = ({"mode": "cmake-graph"} if prepared else
                                    fixtures.prepare_cmake(recorder.root, selection, recorder.command))
    for group in fixtures._group_order(manifest, {owners[c] for c in selection["junit"]["classes"] if owners[c]}):
        for index, check in enumerate(manifest["groups"][group].get("ciChecks", [])):
            if check["platform"] == platform.system():
                recorder.command(f"proof-{group}-{index}", check["argv"])
    for mode, task in HANDOFF_TASKS.items():
        preserve_previous(recorder.root, recorder.directory / ("prior-" + mode), task)
    code, _ = recorder.command("junit-handoff-modes", gradle_command(selection, fail_fast=False, reuse_daemon=reuse_daemon),
                               allowed=tuple(range(-128, 256)))
    for mode, task in HANDOFF_TASKS.items():
        preserve_previous(recorder.root, recorder.directory / mode, task)
    require(code == 0, f"Grouped handoff tests failed: exit {code}")
    cases = []
    for mode in HANDOFF_TASKS:
        xml = recorder.directory / mode / "xml"
        suites = [ET.parse(path).getroot() for path in sorted(xml.glob("TEST-*.xml"))]
        require(suites and not any(suite.findall(".//failure") or suite.findall(".//error") for suite in suites),
                "Missing or failed fresh grouped JUnit results")
        observed = {suite.attrib.get("name") for suite in suites}
        expected = set(selection["junit"]["classes"])
        require(observed == expected,
                f"Grouped JUnit class mismatch: missing={sorted(expected-observed)}, extra={sorted(observed-expected)}")
        require(all(int(suite.attrib.get("tests", "-1")) == len(suite.findall("testcase")) > 0
                    for suite in suites), "Empty/inconsistent grouped JUnit suite")
        proof = ET.parse(xml / "TEST-thc.runtime.HandoffTest.xml").getroot()
        markers = [line for out in proof.findall("system-out") for line in (out.text or "").splitlines()
                   if line.startswith("THC_HANDOFF_MODE=")]
        require(markers == ["THC_HANDOFF_MODE=" + ("true" if mode == "dense" else "false")],
                f"Wrong {mode} handoff mode: {markers}")
        # Keep original JUnit platform/tag exclusions and assumption semantics.
        cases.append(sorted((case.attrib["classname"], case.attrib["name"])
                            for suite in suites for case in suite.findall("testcase")))
        validate_methods(cases[-1], selection["junit"]["classes"], selection["junit"]["patterns"])
    require(cases[0] == cases[1], "Grouped handoff modes ran different testcase sets")
    recorder.data.update(passed=True, testCasesPerMode=len(cases[0]))
    recorder.save()


# These are the admitted standalone checks previously listed in test-common.yml.
# Only the two CBD consumers require built tools; other models read source files.
COMMON_PYTHON_CHECKS = (
    "test-audit-core", "test-plugin", "test-core-enums", "test-sum-layout", "test-core-sums",
    "test-tuple-inputs", "test-empty-join-inputs", "test-library-frontier", "test-sequence-model",
    "test-core-vectors", "test-simd-families", "test-floatx4-model", "test-doublex2-model",
    "test-core-vector-memory", "test-core-word32-vector-memory", "test-core-float-vector-memory",
    "test-core-double-vector-memory", "test-core-bytearrays", "test-core-arrays", "test-address-fields",
    "test-core-data-tags", "test-show-word-list-model", "test-short-bytes-slices-model", "test-array-slice-model",
)
COMMON_OPTIMIZED_CHECKS = (
    "test-build-cbits", "test-core-managed-memory", "test-core-managed-files", "test-core-package-manifest",
    "test-managed-mvar-fixtures", "test-managed-mvars", "test-synchronous-exception-fixtures",
)


def commit_plan(root, base, head):
    import fast_select
    # Cadence applies to the runtime group below. This selection supplies only
    # source/model checks; an uncertain or dirty diff conservatively runs them all.
    changed = fast_select.select(root, base, head)
    require(changed.get("runnable"), "Cannot establish CI check ownership")
    full = changed["mode"] == "full"
    affected = changed["affected"]
    scripts = [{"path": "bin/" + name + ".py", "optimized": name in COMMON_OPTIMIZED_CHECKS}
               for name in (*COMMON_PYTHON_CHECKS, *COMMON_OPTIMIZED_CHECKS)
               if full or "bin/" + name + ".py" in affected["python"]]
    paths = set(changed["changedPaths"])
    if full or paths & {"tools/compare-map-runtimes.py", "tools/test-compare-map-runtimes.py"}:
        scripts.append({"path": "tools/test-compare-map-runtimes.py", "optimized": False})
    suites = ["primop-tools", "compact-core-tests", "driver-tests", "cpu-affinity-api"] if full else affected["haskell"]
    runtime = fast_select.group_selection(root, "commit", cadence="commit")
    manifest, owners = fixtures._manifest(root)
    groups = fixtures._group_order(manifest, {owners[name] for name in runtime["junit"]["classes"] if owners[name]})
    return {"python": scripts, "haskell": suites, "primops": "primop-tools" in suites,
            "protocol": full or any(path.startswith(("tools/truffle-protocol/", "src/gradle/materializable-api",
                                                     "src/gradle/protocol-runtime")) for path in paths),
            "fixtures": [manifest["groups"][name]["cmakeTarget"] for name in groups],
            "selection": {key: changed[key] for key in ("mode", "base", "head", "reasons", "affected")}}


def commit_checks(recorder, base, head):
    plan = commit_plan(recorder.root, base, head)
    selection = recorder.directory / "selection.json"
    write_json(selection, plan)
    configure = ["cmake", "-S", ".", "-B", "build/ci/graph", "-G", "Ninja",
                 "-DTHC_BUILD_JOBS=4", "-DTHC_CI_SELECTION=" + str(selection)]
    for tool in ("GHC", "GHC_PKG", "CABAL"):
        if os.environ.get(tool):
            configure.append("-D" + tool + "=" + os.environ[tool])
    recorder.command("check-graph", configure)
    recorder.command("check-build", ["cmake", "--build", "build/ci/graph", "--parallel", "4", "--target", "ci-commit"])
    recorder.data.update(passed=True, selection=plan["selection"])
    recorder.save()


def main(argv=None):
    argv = list(sys.argv[1:] if argv is None else argv)
    execution = []
    if "--" in argv:
        split = argv.index("--")
        argv, execution = argv[:split], argv[split + 1:]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("start", "identify", "run", "publish", "finish", "group", "pack-common", "restore-common", "compile-common", "compile-test-support", "setup-toolchain", "jam-identity", "install-jam", "verify-jam", "commit-checks", "jvm-group", "check-command"))
    parser.add_argument("--report-dir", type=Path, default=Path(os.environ.get("FAST_REPORT_DIR", ROOT / "build/fast/results")))
    parser.add_argument("--identity", type=Path)
    parser.add_argument("--group")
    parser.add_argument("--exact-class", help="One exact admitted class within the selected group")
    parser.add_argument("--cadence", choices=("commit", "hourly", "nightly"))
    parser.add_argument("--reuse-daemon", action="store_true",
                        help="Reuse the CI job's worker daemon for builds and grouped tests")
    parser.add_argument("--archive", type=Path)
    parser.add_argument("--base", default=os.environ.get("FAST_BASE_SHA", ""))
    parser.add_argument("--head", default=os.environ.get("FAST_HEAD_SHA", "HEAD"))
    args = parser.parse_args(argv)
    # Each fresh graph test invocation retains its own XML, including failures.
    # Re-running Ninja must not require the user to empty a report directory.
    report_dir = args.report_dir.resolve()
    if args.command == "jvm-group":
        report_dir /= "run-" + str(time.time_ns())
    recorder = Recorder(ROOT, report_dir)
    identity_path = args.identity or recorder.directory / "identity.json"
    try:
        if args.command == "start":
            require(not recorder.path.exists(), "Use a fresh report directory for each run")
            expected = os.environ.get("EXPECTED_SHA", "")
            require(not expected or expected == git(ROOT, "rev-parse", "HEAD"), "Dispatched revision mismatch")
            recorder.save()
        elif args.command == "jam-identity":
            jam_identity(ROOT)
        elif args.command == "verify-jam":
            verify_jam(recorder, Path(os.environ["JAVA_HOME"]))
        elif args.command == "install-jam":
            install_jam(recorder)
        elif args.command == "setup-toolchain":
            setup_toolchain(recorder)
        elif args.command == "compile-common":
            compile_common(recorder, reuse_daemon=args.reuse_daemon)
        elif args.command == "compile-test-support":
            compile_test_support(recorder, reuse_daemon=args.reuse_daemon)
        elif args.command == "commit-checks":
            commit_checks(recorder, args.base, args.head)
        elif args.command == "check-command":
            require(bool(execution), "A graph check needs its explicit command")
            recorder.command("check", execution)
            recorder.data["passed"] = True
            recorder.save()
        elif args.command == "jvm-group":
            run_group(recorder, args.group, reuse_daemon=args.reuse_daemon, cadence=args.cadence, prepared=True, exact_class=args.exact_class)
        elif args.command == "group":
            run_group(recorder, args.group, reuse_daemon=args.reuse_daemon, cadence=args.cadence, exact_class=args.exact_class)
        elif args.command == "pack-common":
            pack_common(ROOT, args.archive)
        elif args.command == "restore-common":
            restore_common(ROOT, args.archive)
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
    except (RuntimeError, OSError, ValueError, KeyError, subprocess.CalledProcessError, tarfile.TarError, zipfile.BadZipFile) as error:
        recorder.data["driverError"] = str(error)
        recorder.save()
        print("Fast checks failed: " + str(error), file=sys.stderr)
        return 1
    finally:
        # Nested check-command/jvm-group processes own fragments only. Their
        # coordinator merges after all children exit, including failed builds.
        if args.command in ("verify-jam", "install-jam", "setup-toolchain", "commit-checks", "run", "group", "compile-common", "compile-test-support"):
            merge_traces(recorder.trace_directory, recorder.directory / "build-trace.json")
    return 0


if __name__ == "__main__":
    sys.exit(main())
