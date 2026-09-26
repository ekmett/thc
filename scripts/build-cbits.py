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
import re
import shutil
import subprocess

ROOT = Path(__file__).resolve().parent.parent
PINNED = {"md5.c": "4fa83bda7aacc8a1656d7e2d78251bbe70a04b56",
          "md5.h": "a87296687a2f3dc6748264ff2a8a0c919518db55"}
STRERROR_SHA256 = "bf3a2129e508a108611b734864b63b234fae319c544c1cc500a53a2b7a91953b"
TEXT_SHA256 = {
    "cbits/utils.c": "4e2e096101ccfc7585cb06177fa9d4f523979aed584feb6814e92a868d234f5d",
    "cbits/measure_off.c": "fd5c712c6d93dc9b1121cd6a890a2eb47a0afd96c5e796877e370cfd8a226985",
    "LICENSE": "cf522e3d53b8d1768695fe5b66438baf4514fcd64b7a95739960f2f5b50c6ee8",
    "openbsd-memchr.c": "6058dd440eacf8f9929437d8f6bacc593066c3032407e4428ea5f0e6bccff053",
}
LIBDW_SHA256 = {
    "BeginPrivate.h": "9523f652d274067f5a89ca3ce9ad156f212c88941affbcdf23711f33f684055e",
    "EndPrivate.h": "636273ae8e7d978ab90ea1c51b2b05aeb624392b2f04c25894622acf84f1726e",
    "Libdw.c": "97f4914dc6dcee490531f6415d5c654e06fc7233d8ef616f4fa3bdbcac1aecec",
    "Libdw.h": "018610912b2f4cba487b887c7b1ad5a617fb282ad7b555583dfe649be680971a",
    "LibdwPool.c": "7e007421ec6a4a8cb6f5d5a70743558c0cd5efbc66194b4ad3dce89350bb23ea",
    "LibdwPool.h": "db0ca71e54f18b15bd8afbb66ba69b13675ec10b974bfcb838ddb1234127612c",
    "RtsUtils.h": "6257c9fb28c80ad62c5084b771ce71fd2b2afceaf428633a10e37dc5eb309649",
}


def compiler_target(clang, system, arch):
    """Keep the host ABI, but use the Linux vendor spelling required by Sulong."""
    default_target = subprocess.check_output([clang, "-dumpmachine"], text=True).strip()
    aliases = {"arm64": {"arm64", "aarch64"}, "aarch64": {"arm64", "aarch64"},
               "x86_64": {"x86_64"}, "AMD64": {"x86_64"}}
    parts = default_target.split("-")
    if (arch not in aliases or parts[0] not in aliases[arch] or len(parts) < 3 or
            system not in ("Darwin", "Linux", "Windows") or
            not (parts[2].startswith("darwin") if system == "Darwin" else
                 parts[2:] == ["windows", "gnu"] if system == "Windows" else parts[2] == "linux")):
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
    config = list(libdir.rglob("HsBaseConfig.h"))
    if len(config) != 1:
        raise SystemExit(f"Expected one pinned ghc-internal HsBaseConfig.h, got {config}")
    strerror = ROOT / "compiler/pinned-ghc-internal/cbits/strerror.c"
    if hashlib.sha256(strerror.read_bytes()).hexdigest() != STRERROR_SHA256:
        raise SystemExit("Original GHC 9.14.1 strerror.c changed")
    libdw = ROOT / "compiler/pinned-ghc-rts"
    for name, expected in LIBDW_SHA256.items():
        if hashlib.sha256((libdw / name).read_bytes()).hexdigest() != expected:
            raise SystemExit(f"Original GHC 9.14.1 {name} changed")
    text_source = ROOT / "compiler/pinned-text/2.1.3"
    for name, expected in TEXT_SHA256.items():
        if hashlib.sha256((text_source / name).read_bytes()).hexdigest() != expected:
            raise SystemExit(f"Original text 2.1.3 {name} changed")
    output = args.output.resolve() / "thc/cbits"
    output.mkdir(parents=True, exist_ok=True)
    commands = []
    sources = {"package-pointer": ROOT / "src/main/c/package-pointer-api.c",
               "md5": ROOT / "src/main/c/md5-api.c",
               "libdw-unavailable": ROOT / "src/main/c/libdw-unavailable.c"}
    # The locale scope is POSIX-specific. Windows errno/locale interoperability
    # needs its own proof; compiling strerror_s alone would not provide it.
    if system != "Windows":
        sources["strerror"] = strerror
        sources["strerror-locale"] = ROOT / "src/main/c/strerror-locale.c"
    if system == "Linux":
        sources["iconv"] = ROOT / "src/main/c/iconv-api.c"
        if arch == "x86_64":
            sources["text"] = ROOT / "src/main/c/text-api.c"
            sources["wait-status"] = ROOT / "src/main/c/wait-status-api.c"
    unix_headers = list(libdir.rglob("HsUnix.h")) if "wait-status" in sources else []
    if "wait-status" in sources and len(unix_headers) != 1:
        raise SystemExit(f"Expected one installed unix HsUnix.h, got {unix_headers}")
    for name, source in sources.items():
        command = [*compiler, "-O1", "-g", "-fno-strict-aliasing", "-emit-llvm", "-c",
                   f"-ffile-prefix-map={ROOT}=.", f"-fdebug-prefix-map={ROOT}=.",
                   "-I", str(reference), "-I", str(headers[0].parent), "-I", str(config[0].parent),
                   str(source.relative_to(ROOT)),
                   "-o", str(output / (name + ".bc"))]
        if name == "text":
            command.insert(1, "-D__STDC_NO_ATOMICS__=1")
            command.insert(1, "-fno-builtin-memchr")
        if name == "wait-status":
            command[1:1] = ["-I", str(unix_headers[0].parent)]
        subprocess.run(command, cwd=ROOT, check=True)
        commands.append(command)
        if name == "text":
            # Inspect the compiled artifact, not only source spelling. The
            # managed buffer must never escape to native libc's memchr.
            inspect = [*compiler, "-S", "-emit-llvm", str(output / "text.bc"), "-o", "-"]
            ir = subprocess.check_output(inspect, cwd=ROOT, text=True)
            commands.append(inspect)
            if (re.search(r'^declare .*@(?:memchr|thc_text_memchr)\(', ir, re.M) or
                    not re.search(r'^define .*@thc_text_memchr\(', ir, re.M)):
                raise SystemExit("text memchr dependency is not defined in the original managed bitcode")
            (output / "text-LICENSE").write_bytes((text_source / "LICENSE").read_bytes())
            (output / "text-memchr-LICENSE").write_bytes((text_source / "openbsd-memchr.c").read_bytes())
    source_files = [reference / n for n in PINNED] + [libdw / n for n in LIBDW_SHA256] + list(sources.values())
    source_files += [text_source / n for n in TEXT_SHA256]
    source_files += unix_headers + [p.parent / "HsUnixConfig.h" for p in unix_headers]
    artifacts = [output / (name + ".bc") for name in sources]
    if system == "Windows":
        # Sulong's PE dependency locator probes the guest filesystem even for
        # system DLLs. This stateless native bridge preserves IOAccess.NONE.
        artifact = output / "md5.dll"
        command = [*compiler, "-std=c11", "-O2", "-fno-strict-aliasing", "-shared",
                   "-I", str(reference), "-I", str(headers[0].parent),
                   str(ROOT / "src/main/c/md5-api.c"), "-o", str(artifact)]
        subprocess.run(command, cwd=ROOT, check=True)
        commands.append(command)
        artifacts.append(artifact)
    # The first native limb provider is intentionally Linux x86_64 only. Keep
    # the embedded LLVM container's DT_NEEDED entry: GMP receives real native
    # arena pointers, not the managed buffers used by original MD5.
    if system == "Linux" and arch == "x86_64":
        source = ROOT / "src/main/c/gmp-api.c"
        artifact = output / "gmp-api.so"
        command = [*compiler, "-O1", "-g", "-fembed-bitcode", "-shared", "-fPIC",
                   f"-ffile-prefix-map={ROOT}=.", f"-fdebug-prefix-map={ROOT}=.",
                   str(source.relative_to(ROOT)), "-lgmp", "-lm", "-o", str(artifact)]
        subprocess.run(command, cwd=ROOT, check=True)
        commands.append(command)
        source_files.append(source)
        artifacts.append(artifact)
    record = lambda p: {"path": str(p), "sha256": hashlib.sha256(p.read_bytes()).hexdigest()}
    manifest = {"schema": 1, "target": target, "compilerDefaultTarget": default_target,
                "system": system, "architecture": arch,
                "clangVersion": subprocess.check_output([clang, "--version"], text=True),
                "ghc": "9.14.1", "commands": commands,
                "sources": [record(p) for p in source_files],
                "artifacts": [record(p) for p in artifacts]}
    (output / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    print(f"Compiled C resources {', '.join(p.name for p in artifacts)} for {target}")


if __name__ == "__main__":
    main()
