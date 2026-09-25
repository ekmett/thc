#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

"""Compile pinned original GHC cbits for this build platform; no generated algorithm."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import shutil
import subprocess

ROOT = Path(__file__).resolve().parent.parent
PINNED = {"md5.c": "4fa83bda7aacc8a1656d7e2d78251bbe70a04b56",
          "md5.h": "a87296687a2f3dc6748264ff2a8a0c919518db55"}


def compiler_target(clang, system, arch):
    """Keep the host ABI, but use the Linux vendor spelling required by Sulong."""
    default_target = subprocess.check_output([clang, "-dumpmachine"], text=True).strip()
    aliases = {"arm64": {"arm64", "aarch64"}, "aarch64": {"arm64", "aarch64"},
               "x86_64": {"x86_64"}, "AMD64": {"x86_64"}}
    parts = default_target.split("-")
    if (arch not in aliases or parts[0] not in aliases[arch] or len(parts) < 3 or
            system not in ("Darwin", "Linux") or
            not (parts[2].startswith("darwin") if system == "Darwin" else parts[2] == "linux")):
        raise SystemExit(f"Cbits must match this supported host platform: {system}/{arch}, clang={default_target}")
    command = [clang]
    target = default_target
    if system == "Linux":
        # Do not turn a musl, x32 or other ABI into GNU LP64 merely by changing
        # its triple. Only the vendor component (and arm64 alias) is normalized.
        if parts[2:] != ["linux", "gnu"]:
            raise SystemExit(f"Sulong cbits require the Linux GNU LP64 ABI: clang={default_target}")
        cpu = "aarch64" if arch in ("arm64", "aarch64") else "x86_64"
        expected = f"{cpu}-unknown-linux-gnu"
        command.append(f"--target={expected}")
        target = subprocess.check_output([*command, "-dumpmachine"], text=True).strip()
        if target != expected:
            raise SystemExit(f"Clang did not select the Sulong target: expected {expected}, got {target}")
    return command, target, default_target


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    reference = ROOT / "bench/experiments/pinned-addresses/reference"
    for name, expected in PINNED.items():
        data = (reference / name).read_bytes()
        if hashlib.sha1(f"blob {len(data)}\0".encode() + data).hexdigest() != expected:
            raise ValueError(f"Pinned original {name} changed")
    clang = shutil.which(os.environ.get("THC_CLANG", "clang"))
    if not clang:
        raise SystemExit("Original cbits require clang on PATH (or THC_CLANG); install a compatible LLVM compiler")
    system = platform.system()
    arch = platform.machine()
    compiler, target, default_target = compiler_target(clang, system, arch)
    ghc = shutil.which(os.environ.get("GHC", "ghc"))
    if not ghc or subprocess.check_output([ghc, "--numeric-version"], text=True).strip() != "9.14.1":
        raise SystemExit("Original cbits require the pinned GHC9.14.1 headers")
    libdir = Path(subprocess.check_output([ghc, "--print-libdir"], text=True).strip())
    headers = list(libdir.rglob("HsFFI.h"))
    if len(headers) != 1:
        raise SystemExit(f"Expected one pinned HsFFI.h, got {headers}")
    output = args.output.resolve() / "thc/cbits"
    output.mkdir(parents=True, exist_ok=True)
    commands = []
    for name, source in (("md5", ROOT / "src/main/c/md5-api.c"),):
        command = [*compiler, "-O1", "-g", "-fno-strict-aliasing", "-emit-llvm", "-c",
                   f"-ffile-prefix-map={ROOT}=.", f"-fdebug-prefix-map={ROOT}=.",
                   "-I", str(reference), "-I", str(headers[0].parent), str(source.relative_to(ROOT)),
                   "-o", str(output / (name + ".bc"))]
        subprocess.run(command, cwd=ROOT, check=True)
        commands.append(command)
    sources = [reference / n for n in PINNED] + [ROOT / "src/main/c/md5-api.c"]
    artifacts = [output / "md5.bc"]
    # The first native limb provider is intentionally Linux x86_64 only. Keep
    # the embedded LLVM container's DT_NEEDED entry: GMP receives real native
    # arena pointers, not the managed buffers used by original MD5.
    if system == "Linux" and arch == "x86_64":
        source = ROOT / "src/main/c/gmp-api.c"
        artifact = output / "gmp-api.so"
        command = [*compiler, "-O1", "-g", "-fembed-bitcode", "-shared", "-fPIC",
                   f"-ffile-prefix-map={ROOT}=.", f"-fdebug-prefix-map={ROOT}=.",
                   str(source.relative_to(ROOT)), "-lgmp", "-o", str(artifact)]
        subprocess.run(command, cwd=ROOT, check=True)
        commands.append(command)
        sources.append(source)
        artifacts.append(artifact)
    record = lambda p: {"path": str(p), "sha256": hashlib.sha256(p.read_bytes()).hexdigest()}
    manifest = {"schema": 1, "target": target, "compilerDefaultTarget": default_target,
                "system": system, "architecture": arch,
                "clangVersion": subprocess.check_output([clang, "--version"], text=True),
                "ghc": "9.14.1", "commands": commands,
                "sources": [record(p) for p in sources],
                "artifacts": [record(p) for p in artifacts]}
    (output / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    print(f"Compiled unchanged GHC MD5 for {target}")


if __name__ == "__main__":
    main()
