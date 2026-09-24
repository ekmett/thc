#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

"""Explicit native-vs-THC execution checks for the first Cabal run slice."""

import argparse
import json
from pathlib import Path
import shutil
import subprocess
import tempfile


HERE = Path(__file__).resolve().parent
FIXTURE = HERE / "fixtures" / "run-pure"


def checked(command, *, cwd, environment=None):
    return subprocess.run(list(map(str, command)), cwd=cwd, env=environment,
                          capture_output=True, text=True, timeout=120)


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def exercise(driver, runtime, thc_root, scratch):
    with tempfile.TemporaryDirectory(prefix="thc-run-", dir=scratch) as temporary:
        root = Path(temporary)
        package = root / 'real IO "café"'
        shutil.copytree(FIXTURE, package)
        source = package / "app" / "Main.hs"

        def run(label, *, backend=None):
            output = root / label
            environment = None
            if backend:
                import os
                environment = dict(os.environ, THC_BACKEND=backend)
            # This fixture lives below the repository's cabal.project in CI.
            # An explicit .cabal path requests independent package configuration.
            result = checked([driver, "run", package / "run-pure.cabal", "--exe", "completed",
                              "--dist-dir", output, "--thc-root", thc_root,
                              "--runtime", runtime], cwd=root, environment=environment)
            return result, output

        for backend in ("bytecode", "ast"):
            result, output = run("success-" + backend, backend=backend)
            require(result.returncode == 0, result.stderr)
            require(result.stdout == "", result.stdout)
            diagnostics = json.loads(result.stderr.splitlines()[-1])
            require(diagnostics["backend"] == backend, diagnostics)
            require(diagnostics["unsupportedTraps"] == 0, diagnostics)
            audit = json.loads((output / "thc-run/completed/audit.json").read_text())
            require(audit["accepted"] and audit["roots"] == ["main:Main.main"], audit)
            require({"newMutVar#", "writeMutVar#", "readMutVar#", "raise#"} <= {
                item["name"] for item in audit["primitives"]}, audit)
            exported = output / "thc-run/completed"
            for module in ("Main", "Answer"):
                core = json.loads((exported / "core" / (module + ".json")).read_text())
                path = str(package / "app" / (module + ".hs"))
                require(core["lowering"]["ticks"] == "source-notes-metadata" and core["sourceSpans"], core)
                require(any(item["path"] == path and item["content"]
                            for item in core["sourceFiles"]), core["sourceFiles"])
                require((exported / "ghc" / (module + ".hi")).is_file(), exported)
            for suffix in ("*.o", "*.dyn_o"):
                require(not list((exported / "ghc").rglob(suffix)), exported)
            native = output / "build/completed/completed"
            require(native.is_file(), native)
            native_result = checked([native], cwd=package)
            require(native_result.returncode == 0, native_result.stderr)
            require(native_result.stdout == result.stdout == "", (native_result.stdout, result.stdout))

        # The same strictly accepted Core must fail when the guest action reads
        # the wrong mutable value. This catches a runner that merely loads Core.
        original = source.read_text()
        source.write_text(original.replace("answer ==# 42#", "answer ==# 43#"))
        for backend in ("bytecode", "ast"):
            result, output = run("guest-failure-" + backend, backend=backend)
            require(result.returncode != 0, result.stderr)
            require(json.loads((output / "thc-run/completed/audit.json").read_text())["accepted"], output)
            native = output / "build/completed/completed"
            require(native.is_file(), native)
            native_result = checked([native], cwd=package)
            require(native_result.returncode != 0, native_result.stderr)
        source.write_text(original)

        # Ordinary console IO remains a strict rejection, never a diagnostic trap
        # or a disguised execution of Cabal's native executable.
        source.write_text('module Main where\nmain :: IO ()\nmain = putStrLn "native only"\n')
        result, output = run("unsupported-io")
        require(result.returncode != 0, result.stderr)
        require(result.stdout == "", result.stdout)
        require(not json.loads((output / "thc-run/completed/audit.json").read_text())["accepted"], output)
        native = output / "build/completed/completed"
        require(native.is_file(), native)
        native_result = checked([native], cwd=package)
        require(native_result.returncode == 0 and native_result.stdout == "native only\n", native_result)

        # A valid native executable with a different IO result cannot cross the
        # narrower host boundary used by THC run.
        source.write_text('module Main where\nmain :: IO Int\nmain = pure 42\n')
        result, output = run("wrong-io-result")
        require(result.returncode != 0 and result.stdout == "", result)
        audit = json.loads((output / "thc-run/completed/audit.json").read_text())
        require(not audit["accepted"] and any(issue["code"] == "io-main-boundary"
                                              for issue in audit["issues"]), audit)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--driver", type=Path, required=True)
    parser.add_argument("--runtime", type=Path, required=True)
    parser.add_argument("--thc-root", type=Path, required=True)
    parser.add_argument("--scratch", type=Path, required=True)
    args = parser.parse_args()
    args.scratch.mkdir(parents=True, exist_ok=True)
    exercise(args.driver.resolve(), args.runtime.resolve(), args.thc_root.resolve(), args.scratch.resolve())
    print("PASS: Cabal IO main builds, audits and executes through both THC backends")


if __name__ == "__main__":
    main()
