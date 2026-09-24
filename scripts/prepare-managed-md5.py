#!/usr/bin/env python3
"""Compile the exact GHC MD5 C source and retain its visible managed-ABI oracle.

Run under the common resource gate. This is not an original-Haskell Fingerprint
test and does not enable or exercise general FFI. First failures are retained.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shlex
import shutil
import subprocess
import sys


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def blob(path):
    data = path.read_bytes()
    return hashlib.sha1(f"blob {len(data)}\0".encode() + data).hexdigest()


def inventory():
    result = []
    for length in (0, 1, 2, 7, 15, 16, 31, 55, 56, 57, 63, 64, 65, 95,
                   119, 120, 127, 128, 129, 255, 256, 257, 1024):
        splits = dict.fromkeys(min(x, length) for x in (0, 1, length // 2, 55, 56, 63, 64, length))
        for split in splits:
            for seed in (0, 1, 127, 255):
                result.append((length, split, seed, 0, 0))
    for high in (0, 0xffffffff):
        for length in (17, 64, 129):
            for split in (0, 8, 16):
                result.append((length, split, 127, 0xfffffff0, high))
    return result


def check_rows(path):
    lines = path.read_text().splitlines()
    expected_layout = ["layout", "88", "0", "16", "24", "4", sys.byteorder]
    if lines[0].split("\t") != expected_layout:
        raise ValueError("Native C layout/byte order mismatch")
    cases = inventory()
    seen, aliases = set(), set()
    for line in lines[1:]:
        fields = line.split("\t")
        if fields[0] == "alias":
            if len(fields) != 7:
                raise ValueError("Malformed alias row")
            case, io, length, oo, phase = map(int, fields[1:6])
            expected = ((32, 16, 160), (48, 8, 160), (112, 8, 160), (128, 65, 56), (128, 65, 112))
            if not (0 <= case < 5 and 0 <= phase < 3) or (io, length, oo) != expected[case]:
                raise ValueError("Unexpected alias case")
            key = (case, phase)
            if key in aliases or len(bytes.fromhex(fields[6])) != 256:
                raise ValueError("Duplicate/malformed alias snapshot")
            aliases.add(key)
            if phase == 2 and bytes.fromhex(fields[6])[32:120] != bytes(88):
                raise ValueError("Alias final context not cleared")
            continue
        if len(fields) != 13 or fields[0] != "case":
            raise ValueError("Malformed native case")
        case, length, split, seed, co, io, oo, low, high, phase = map(int, fields[1:11])
        if not 0 <= case < len(cases) or cases[case] != (length, split, seed, low, high):
            raise ValueError("Native case differs from independent inventory")
        if (co, io, oo) != ((case % 3) * 4, (0, 1, 7)[case % 3], (0, 3, 11)[case % 3]):
            raise ValueError("Native address offsets differ")
        key = (case, phase)
        if phase not in range(4) or key in seen:
            raise ValueError("Duplicate/unknown snapshot phase")
        seen.add(key)
        context, output = bytes.fromhex(fields[11]), bytes.fromhex(fields[12])
        if len(context) != 104 or len(output) != 32:
            raise ValueError("Malformed native snapshot width")
        if context[:co] != b"\xa5" * co or context[co + 88:] != b"\xa5" * (16 - co):
            raise ValueError("Context sentinel changed")
        if output[:oo] != b"\xd3" * oo or output[oo + 16:] != b"\xd3" * (16 - oo):
            raise ValueError("Output sentinel changed")
        if phase < 3:
            if output != b"\xd3" * 32:
                raise ValueError("Output changed before Final")
            added = (0, split, length)[phase]
            count = (((high << 32) | low) + added) & ((1 << 64) - 1)
            expected_count = (count & 0xffffffff).to_bytes(4, sys.byteorder) + (count >> 32).to_bytes(4, sys.byteorder)
            if context[co + 16:co + 24] != expected_count:
                raise ValueError("Native counter update mismatch")
            if phase == 0:
                initial = b"".join(x.to_bytes(4, sys.byteorder) for x in (0x67452301, 0xefcdab89, 0x98badcfe, 0x10325476))
                if context[co:co + 16] != initial or context[co + 24:co + 88] != b"\xa5" * 64:
                    raise ValueError("Init altered scratch or initial state")
        else:
            if context[co:co + 88] != bytes(88):
                raise ValueError("Final context not cleared")
            if low == high == 0:
                data = bytes((i * 73 + seed * 19 + (i >> 3)) & 255 for i in range(length))
                if output[oo:oo + 16] != hashlib.md5(data).digest():
                    raise ValueError("Native digest differs from independent hashlib")
    if seen != {(case, phase) for case in range(len(cases)) for phase in range(4)}:
        raise ValueError("Missing native case/phase")
    if aliases != {(case, phase) for case in range(5) for phase in range(3)}:
        raise ValueError("Missing alias case/phase")
    return {"cases": len(cases), "caseRows": len(seen), "aliasCases": 5, "aliasRows": len(aliases),
            "hashlibCases": sum(low == high == 0 for _, _, _, low, high in cases)}


def main():
    root = Path(__file__).resolve().parent.parent
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=root / "build/managed-md5-native")
    parser.add_argument("--reference-dir", type=Path, default=root / "bench/experiments/pinned-addresses/reference")
    parser.add_argument("--cc", default=os.environ.get("CC", "cc"))
    parser.add_argument("--ghc", default=os.environ.get("GHC", "ghc"))
    args = parser.parse_args()
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    reference = args.reference_dir.resolve()
    expected_blobs = {"md5.c": "4fa83bda7aacc8a1656d7e2d78251bbe70a04b56",
                      "md5.h": "a87296687a2f3dc6748264ff2a8a0c919518db55"}
    for name, expected in expected_blobs.items():
        if blob(reference / name) != expected:
            raise ValueError(f"Pinned GHC source blob mismatch: {name}")

    def run(name, command):
        (output / f"{name}.command.txt").write_text(shlex.join(map(str, command)) + "\n")
        result = subprocess.run(list(map(str, command)), cwd=root, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        (output / f"{name}.stdout").write_bytes(result.stdout)
        (output / f"{name}.stderr").write_bytes(result.stderr)
        (output / f"{name}.exit-status.txt").write_text(str(result.returncode) + "\n")
        if result.returncode:
            raise RuntimeError(f"First failure {name}: exit {result.returncode}; retained {output}")
        return result.stdout.decode()

    cc = Path(shutil.which(args.cc) or args.cc).resolve(strict=True)
    ghc = Path(shutil.which(args.ghc) or args.ghc).resolve(strict=True)
    run("cc-version", [cc, "--version"])
    libdir = Path(run("ghc-libdir", [ghc, "--print-libdir"]).strip())
    headers = list(libdir.rglob("HsFFI.h"))
    if len(headers) != 1:
        raise ValueError(f"Expected one installed HsFFI.h, got {headers}")
    executable = output / "managed-md5-native"
    driver = root / "compiler/test-fixtures/ManagedMd5Native.c"
    run("compile", [cc, "-std=c11", "-O2", "-fno-strict-aliasing", "-Wall", "-Wextra",
                    "-I", reference, "-I", headers[0].parent, driver, reference / "md5.c", "-o", executable])
    run("native", [executable])
    counts = check_rows(output / "native.stdout")
    def record(path):
        path = path.resolve()
        try:
            name = str(path.relative_to(root))
        except ValueError:
            name = str(path)
        return {"path": name, "sha256": digest(path)}
    sources = [Path(__file__), driver, reference / "md5.c", reference / "md5.h", headers[0],
               root / "src/main/kotlin/thc/runtime/LiteralAddresses.kt",
               root / "src/main/kotlin/thc/runtime/ManagedMd5.kt",
               root / "src/test/kotlin/thc/runtime/ManagedMd5Test.kt"]
    artifacts = sorted(p for p in output.iterdir() if p.is_file())
    provenance = {"schema": 1, "byteOrder": sys.byteorder, "contextSize": 88,
                  "contextOffsets": [0, 16, 24], "contextAlignment": 4,
                  "referenceGitBlobs": expected_blobs, "independentModelMatched": True, **counts,
                  "sources": list(map(record, sources)), "artifacts": list(map(record, artifacts)),
                  "tools": [record(cc), record(ghc)]}
    (output / "provenance.json").write_text(json.dumps(provenance, indent=2, sort_keys=True) + "\n")
    print(json.dumps(counts, sort_keys=True))
    print(f"PASS native C ABI/context oracle; provenance sha256={digest(output / 'provenance.json')}")


if __name__ == "__main__":
    main()
