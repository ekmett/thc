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
RTS_FLOAT_SHA256 = {
    "StgPrimFloat.c": "9cf152e52641b332634c9a9a0a24114f7d4640b08d17a35a020abb7bde4cf8c0",
    "StgPrimFloat.h": "486279f796cfc733a7d371e2445201a21b66a82a08ed501fdb82491c473b8f13",
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


def bitcode_target(disassembler, artifact, expected):
    # llvm-dis reads the actual module; unlike compiling IR again, it cannot
    # replace an incompatible module triple with the driver's selected target.
    ir = subprocess.check_output([disassembler, str(artifact), "-o", "-"], text=True)
    targets = re.findall(r'^target triple = "([^"]+)"$', ir, re.M)
    if targets != [expected]:
        raise SystemExit(f"Compiled bitcode target mismatch for {artifact.name}: expected {expected}, got {targets}")
    return targets[0]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    reference = ROOT / "nih/pinned/ghc-9.14.1/libraries/ghc-internal"
    clang = shutil.which(os.environ.get("THC_CLANG", "clang"))
    if not clang:
        raise SystemExit("Original cbits require clang on PATH (or THC_CLANG); install a compatible LLVM compiler")
    system = platform.system()
    if system == "Darwin" and "SDKROOT" not in os.environ:
        os.environ["SDKROOT"] = subprocess.check_output(["/usr/bin/xcrun", "--show-sdk-path"], text=True).strip()
    arch = platform.machine()
    compiler, target, default_target = compiler_target(clang, system, arch)
    if system == "Darwin":
        compiler.append("--sysroot=" + os.environ["SDKROOT"])
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
    rts = ROOT / "nih/pinned/ghc-9.14.1/rts"
    for name, expected in RTS_FLOAT_SHA256.items():
        if hashlib.sha256((rts / name).read_bytes()).hexdigest() != expected:
            raise SystemExit(f"Original GHC 9.14.1 {name} changed")
    output = args.output.resolve() / "thc/cbits"
    # This producer owns only thc/cbits, not sibling generated resources.
    if output.exists():
        shutil.rmtree(output)
    output.mkdir(parents=True, exist_ok=True)
    commands = []
    pointer_compiler, pointer_target = compiler, target
    disassembler = None
    if system == "Windows":
        # Sulong 25.3.4.1's managed Windows runtime uses the MSVC ABI. Compile
        # this portable pointer bridge for that target; native GHC DLLs below
        # still use the selected MinGW compiler/headers. Never relabel bitcode
        # or turn off Sulong's target check to accept a different ABI.
        pointer_target = "x86_64-pc-windows-msvc19.33.0"
        pointer_compiler = [clang, "--target=" + pointer_target]
        actual = subprocess.check_output([*pointer_compiler, "-dumpmachine"], text=True).strip()
        if actual != pointer_target:
            raise SystemExit(f"Clang did not select the Windows Sulong pointer ABI: {actual}")
        sibling = Path(clang).with_name("llvm-dis.exe")
        disassembler = shutil.which(os.environ.get("THC_LLVM_DIS", str(sibling) if sibling.is_file() else "llvm-dis"))
        if not disassembler:
            raise SystemExit("Windows cbits require llvm-dis beside Clang, on PATH, or selected by THC_LLVM_DIS")
    sources = {"package-pointer": ROOT / "src/main/c/package-pointer-api.c"}
    if system in ("Linux", "Darwin"):
        sources["iconv"] = ROOT / "src/main/c/iconv-api.c"
        if system == "Linux" and arch == "x86_64":
            sources["wait-status"] = ROOT / "src/main/c/wait-status-api.c"
    unix_headers = list(libdir.rglob("HsUnix.h")) if "wait-status" in sources else []
    if "wait-status" in sources and len(unix_headers) != 1:
        raise SystemExit(f"Expected one installed unix HsUnix.h, got {unix_headers}")
    bitcode_targets = {}
    for name, source in sources.items():
        selected_compiler = pointer_compiler if name == "package-pointer" else compiler
        command = [*selected_compiler, "-O1", "-g", "-fno-strict-aliasing", "-emit-llvm", "-c",
                   f"-ffile-prefix-map={ROOT}=.", f"-fdebug-prefix-map={ROOT}=.",
                   "-I", str(reference / "cbits"), "-I", str(reference / "include"), "-I", str(headers[0].parent), "-I", str(config[0].parent),
                   str(source.relative_to(ROOT)),
                   "-o", str(output / (name + ".bc"))]
        if name == "wait-status":
            command[1:1] = ["-I", str(unix_headers[0].parent)]
        subprocess.run(command, cwd=ROOT, check=True)
        commands.append(command)
        expected_target = pointer_target if name == "package-pointer" else target
        artifact = output / (name + ".bc")
        bitcode_targets[artifact.name] = bitcode_target(disassembler, artifact, expected_target) if disassembler else expected_target
    source_files = list(sources.values())
    source_files += unix_headers + [p.parent / "HsUnixConfig.h" for p in unix_headers]
    artifacts = [output / (name + ".bc") for name in sources]
    # The original RTS floating helpers contain no GHC heap state. Supply the
    # whole translation unit to ordinary native imports, including its public
    # signed/unsigned Float/Double encoders, without loading a second RTS.
    artifact = output / ("rts-float" + {"Windows": ".dll", "Darwin": ".dylib", "Linux": ".so"}[system])
    command = [*compiler, "-O1", "-g", "-fno-strict-aliasing", "-shared",
               *([] if system == "Windows" else ["-fPIC"]),
               f"-ffile-prefix-map={ROOT}=.", f"-fdebug-prefix-map={ROOT}=.",
               "-I", str(headers[0].parent), str(rts / "StgPrimFloat.c"),
               "-lm", "-o", str(artifact)]
    subprocess.run(command, cwd=ROOT, check=True)
    commands.append(command)
    source_files += [rts / name for name in RTS_FLOAT_SHA256]
    artifacts.append(artifact)
    if system == "Windows":
        source = ROOT / "src/main/c/windows-malloc.c"
        artifact = output / "windows-malloc.dll"
        # Keep the calls real in both the DLL and native probe; the compiler
        # must not elide malloc/free and thereby change the observed errno.
        options = [*compiler, "-std=c11", "-Wall", "-Wextra", "-Werror", "-O2", "-fno-builtin"]
        command = [*options, "-shared", str(source), "-o", str(artifact)]
        subprocess.run(command, cwd=ROOT, check=True)
        commands.append(command)
        probe = output / "windows-malloc-probe.exe"
        command = [*options, "-DTHC_MALLOC_PROBE", str(source), "-o", str(probe)]
        subprocess.run(command, cwd=ROOT, check=True)
        commands.append(command)
        command = [str(probe)]
        layout = json.loads(subprocess.check_output(command, cwd=ROOT, text=True))
        commands.append(command)
        if (layout["abi"] != 0x0808080404 or layout["enomem"] <= 0 or
                layout["failureErrno"] != layout["enomem"] or
                len({layout[k].lower() for k in ("mallocModule", "freeModule", "errnoModule")}) != 1):
            raise SystemExit("Windows malloc/free/errno probe did not establish one LLP64 CRT")
        receipt = output / "windows-malloc-abi.json"
        receipt.write_text(json.dumps({"schema": 1, "target": target, "layout": layout,
            "dllSha256": hashlib.sha256(artifact.read_bytes()).hexdigest()}, indent=2) + "\n")
        source_files.append(source)
        artifacts.extend([artifact, receipt])
        source = ROOT / "src/main/c/windows-io-api.c"
        artifact = output / "windows-io.dll"
        command = [*options, "-shared", str(source), "-lws2_32", "-o", str(artifact)]
        subprocess.run(command, cwd=ROOT, check=True)
        commands.append(command)
        receipt = output / "windows-io-abi.json"
        receipt.write_text(json.dumps({"schema": 1, "target": target,
            "dllSha256": hashlib.sha256(artifact.read_bytes()).hexdigest(),
            "loanSize": 16, "resultSize": 16}, indent=2) + "\n")
        source_files.append(source)
        artifacts.extend([artifact, receipt])
    record = lambda p: {"path": str(p), "sha256": hashlib.sha256(p.read_bytes()).hexdigest()}
    manifest = {"schema": 1, "target": target, "compilerDefaultTarget": default_target,
                "bitcodeTargets": bitcode_targets,
                "system": system, "architecture": arch,
                "clangVersion": subprocess.check_output([clang, "--version"], text=True),
                "ghc": "9.14.1", "commands": commands,
                "sources": [record(p) for p in source_files],
                "artifacts": [record(p) for p in artifacts]}
    if disassembler:
        manifest["bitcodeInspector"] = {"path": disassembler,
                                       "version": subprocess.check_output([disassembler, "--version"], text=True)}
    (output / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    print(f"Compiled C resources {', '.join(p.name for p in artifacts)} for {target}")


if __name__ == "__main__":
    main()
