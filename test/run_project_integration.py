#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

"""Native/Cabal versus THC execution across two packages and an internal library."""

import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile


FIXTURE = Path(__file__).resolve().parent / "fixtures/run-project"


def require(condition, detail):
    if not condition:
        raise AssertionError(detail)


def checked(command, *, cwd, backend=None):
    environment = dict(os.environ)
    if backend:
        environment["THC_BACKEND"] = backend
    return subprocess.run(list(map(str, command)), cwd=cwd, env=environment,
                          capture_output=True, text=True, timeout=180)


def plan_entry(output):
    plan = json.loads((output / "native/cache/plan.json").read_text())
    entries = [item for item in plan["install-plan"]
               if item.get("pkg-name") == "app-run" and
               item.get("component-name") == "exe:completed"]
    require(len(entries) == 1, entries)
    return plan, entries[0]


def unit(manifest, identifier):
    matches = [item for item in manifest["units"] if item["id"] == identifier]
    require(len(matches) == 1, matches)
    return matches[0]


def exercise(driver, runtime, thc_root, scratch):
    with tempfile.TemporaryDirectory(prefix="thc-project-", dir=scratch) as temporary:
        root = Path(temporary).resolve()
        project = root / 'three components "café"'
        shutil.copytree(FIXTURE, project)
        output = root / "output"
        source = project / "dep-data/src/Answer.hs"

        def run(backend, *, target="completed"):
            return checked([driver, "run", project, "--exe", target,
                            "--thc-root", thc_root, "--runtime", runtime,
                            "--dist-dir", output], cwd=root, backend=backend)

        manifest_paths = None
        for backend, target in (("ast", "completed"),
                                ("bytecode", "app-run:exe:completed")):
            result = run(backend, target=target)
            require(result.returncode == 0, result.stderr)
            require(result.stdout == "", result.stdout)
            diagnostics = json.loads(result.stderr.splitlines()[-1])
            require(diagnostics["backend"] == backend and
                    diagnostics["unsupportedTraps"] == 0 and
                    diagnostics["thunkEvaluationsByLabel"].get("answerValue") == 1,
                    diagnostics)
            plan, entry = plan_entry(output)
            native = checked([entry["bin-file"]], cwd=project)
            require(native.returncode == 0 and native.stdout == result.stdout, native)
            dep = next(item for item in plan["install-plan"] if item.get("pkg-name") == "dep-data")
            bridge = next(item for item in plan["install-plan"]
                          if item.get("component-name") == "lib:bridge")
            require(dep["flags"]["recent"] is True and
                    dep["id"] in bridge["depends"] and
                    bridge["id"] in entry["depends"], (dep, bridge, entry))
            build_info = json.loads(Path(dep["build-info"]).read_text())
            arguments = build_info["components"][0]["compiler-args"]
            require("-optP-DPROJECT_RECENT" in arguments, arguments)
            manifest = json.loads((output / "packages.json").read_text())
            require(manifest["format"] == "thc-core-packages" and
                    manifest["schema"] == 1, manifest)
            require({module["name"] for module in unit(manifest, bridge["id"])["modules"]}
                    == {"Bridge", "Paths_app_run"}, manifest)
            require({module["name"] for module in unit(manifest, entry["id"])["modules"]}
                    == {"Main"}, manifest)
            audit = json.loads((output / "audit.json").read_text())
            require(audit["accepted"] and audit["missingGlobals"] == [] and
                    any(item["id"] == dep["id"] + ":Answer.answerValue"
                        for item in audit["reachableBindings"]), audit)
            paths = {identifier: tuple(module["path"] for module in unit(manifest, identifier)["modules"])
                     for identifier in (dep["id"], bridge["id"], entry["id"])}
            if manifest_paths is not None:
                require(paths == manifest_paths, (paths, manifest_paths))
            manifest_paths = paths
            # GHC CPP currently mangles non-ASCII #line filenames in Answer.
            # Non-CPP project modules still preserve the canonical source path.
            main = json.loads((output / unit(manifest, entry["id"])["modules"][0]["path"]).read_text())
            main_source = project / "app-run/app/Main.hs"
            require(any(Path(file["path"]).resolve() == main_source.resolve() and file["content"]
                        for file in main["sourceFiles"]), main["sourceFiles"])

        original = source.read_text()
        require("I# 42#" in original, original)
        source.write_text(original.replace("I# 42#", "I# 41#"))
        result = run("ast")
        require(result.returncode != 0 and result.stdout == "", result)
        audit = json.loads((output / "audit.json").read_text())
        require(audit["accepted"], audit)
        changed = json.loads((output / "packages.json").read_text())
        require(tuple(module["path"] for module in unit(changed, dep["id"])["modules"])
                != manifest_paths[dep["id"]], changed)
        _, entry = plan_entry(output)
        native = checked([entry["bin-file"]], cwd=project)
        require(native.returncode != 0, native)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--driver", type=Path, required=True)
    parser.add_argument("--runtime", type=Path, required=True)
    parser.add_argument("--thc-root", type=Path, required=True)
    parser.add_argument("--scratch", type=Path, required=True)
    args = parser.parse_args()
    args.scratch.mkdir(parents=True, exist_ok=True)
    exercise(args.driver.resolve(), args.runtime.resolve(), args.thc_root.resolve(),
             args.scratch.resolve())
    print("PASS: Cabal project units, CPP/autogen, native and THC AST/bytecode execution")


if __name__ == "__main__":
    main()
