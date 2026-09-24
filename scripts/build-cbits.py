#!/usr/bin/env python3
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
    target = subprocess.check_output([clang, "-dumpmachine"], text=True).strip()
    arch = platform.machine()
    aliases = {"arm64": {"arm64", "aarch64"}, "aarch64": {"arm64", "aarch64"}, "x86_64": {"x86_64"}, "AMD64": {"x86_64"}}
    if arch not in aliases or target.split('-')[0] not in aliases[arch] or platform.system() not in ("Darwin", "Linux") or not any(x in target for x in ("darwin" if platform.system() == "Darwin" else "linux",)):
        raise SystemExit(f"Cbits must match this supported host platform: {platform.system()}/{arch}, clang={target}")
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
        command = [clang, "-O1", "-g", "-fno-strict-aliasing", "-emit-llvm", "-c",
                   f"-ffile-prefix-map={ROOT}=.", f"-fdebug-prefix-map={ROOT}=.",
                   "-I", str(reference), "-I", str(headers[0].parent), str(source.relative_to(ROOT)),
                   "-o", str(output / (name + ".bc"))]
        subprocess.run(command, cwd=ROOT, check=True)
        commands.append(command)
    record = lambda p: {"path": str(p), "sha256": hashlib.sha256(p.read_bytes()).hexdigest()}
    manifest = {"schema": 1, "target": target, "system": platform.system(), "architecture": arch,
                "clangVersion": subprocess.check_output([clang, "--version"], text=True),
                "ghc": "9.14.1", "commands": commands,
                "sources": [record(reference / n) for n in PINNED] + [record(ROOT / "src/main/c/md5-api.c"), record(headers[0])],
                "artifacts": [record(output / (n + ".bc")) for n in ("md5",)]}
    (output / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    print(f"Compiled unchanged GHC MD5 for {target}")


if __name__ == "__main__":
    main()
