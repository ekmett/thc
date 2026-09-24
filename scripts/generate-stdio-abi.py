#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

"""Probe actual host C widths and errno values, without native runtime IO or GHC headers."""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import platform
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parent.parent
SOURCE = ROOT / "src/main/c/stdio-abi-probe.c"
ERRNOS = {"ENOENT", "EACCES", "EEXIST", "EBADF", "EINVAL", "EIO", "ENOTSUP", "EBUSY", "EISDIR"}
WIDTHS = {"charBits": 8, "pointer": 8, "int": 4, "size": 8, "ssize": 8}


def validate_probe(value):
    if not isinstance(value, dict) or value.keys() != {"widths", "errno"}:
        raise ValueError("Malformed stdio ABI probe")
    widths = value["widths"]
    if not isinstance(widths, dict) or widths.keys() != WIDTHS.keys() or any(
            type(widths[name]) is not int or widths[name] != size for name, size in WIDTHS.items()):
        raise ValueError("Original stdio calls require exact LP64 widths")
    errors = value["errno"]
    if not isinstance(errors, dict) or errors.keys() != ERRNOS or any(
            type(number) is not int or not 0 < number <= 0x7fffffff for number in errors.values()):
        raise ValueError("Invalid host CInt errno values")
    return value


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    clang = shutil.which(os.environ.get("THC_CLANG", "clang"))
    if not clang:
        raise SystemExit("Original stdio ABI probing requires clang (or THC_CLANG)")
    # Share the existing host-only target check; importing does not run its GHC build.
    spec = importlib.util.spec_from_file_location("build_cbits", ROOT / "scripts/build-cbits.py")
    cbits = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(cbits)
    system, arch = platform.system(), platform.machine()
    compiler, target, default_target = cbits.compiler_target(clang, system, arch)
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="stdio-abi-", dir=output) as temporary:
        executable = Path(temporary) / "probe"
        subprocess.run([*compiler, "-std=c11", str(SOURCE), "-o", str(executable)], check=True)
        probe = validate_probe(json.loads(subprocess.check_output([str(executable)], text=True)))
    manifest = dict(schema=1, system=system, architecture=arch, target=target,
                    compilerDefaultTarget=default_target,
                    compilerVersion=subprocess.check_output([clang, "--version"], text=True),
                    sourceSha256=hashlib.sha256(SOURCE.read_bytes()).hexdigest(), **probe)
    destination = output / "thc/native/stdio-host-abi.json"
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text(json.dumps(manifest, indent=2) + "\n")
    print(f"Probed original stdio ABI and errno values for {target}")


if __name__ == "__main__":
    main()
