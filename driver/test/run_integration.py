#!/usr/bin/env python3
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
            result = checked([driver, "run", package, "--exe", "completed",
                              "--dist-dir", output, "--thc-root", thc_root,
                              "--runtime", runtime], cwd=root, environment=environment)
            return result, output

        for backend in ("bytecode", "ast"):
            result, output = run("success-" + backend, backend=backend)
            assert result.returncode == 0, result.stderr
            assert result.stdout == "", result.stdout
            diagnostics = json.loads(result.stderr.splitlines()[-1])
            assert diagnostics["backend"] == backend, diagnostics
            assert diagnostics["unsupportedTraps"] == 0, diagnostics
            audit = json.loads((output / "thc-run/completed/audit.json").read_text())
            assert audit["accepted"] and audit["roots"] == ["main:Main.main"], audit
            assert {"newMutVar#", "writeMutVar#", "readMutVar#", "raise#"} <= {
                item["name"] for item in audit["primitives"]}, audit
            native = output / "build/completed/completed"
            assert native.is_file(), native
            native_result = checked([native], cwd=package)
            assert native_result.returncode == 0, native_result.stderr
            assert native_result.stdout == result.stdout == ""

        # The same strictly accepted Core must fail when the guest action reads
        # the wrong mutable value. This catches a runner that merely loads Core.
        original = source.read_text()
        source.write_text(original.replace("answer ==# 42#", "answer ==# 43#"))
        result, output = run("guest-failure")
        assert result.returncode != 0, result.stderr
        assert json.loads((output / "thc-run/completed/audit.json").read_text())["accepted"]
        source.write_text(original)

        # Ordinary console IO remains a strict rejection, never a diagnostic trap
        # or a disguised execution of Cabal's native executable.
        source.write_text('module Main where\nmain :: IO ()\nmain = putStrLn "native only"\n')
        result, output = run("unsupported-io")
        assert result.returncode != 0, result.stderr
        assert result.stdout == "", result.stdout
        assert not json.loads((output / "thc-run/completed/audit.json").read_text())["accepted"]


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
