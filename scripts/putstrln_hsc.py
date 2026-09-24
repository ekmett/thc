#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
"""Generate the seven genuine .hsc owners using installed GHC configuration."""

import argparse
import ast
import os
from pathlib import Path
import shlex
import shutil
import subprocess

from putstrln_export import GHC_COMMIT, GHC_TAG, HSC_MODULES, ROOT, digest, output, package_dirs, require, run, sibling_tool, source_checkout, toolchain, write


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ghc-source", required=True, type=Path)
    parser.add_argument("--out", type=Path, default=ROOT / "build/putstrln-generated")
    parser.add_argument("--ghc", default=os.environ.get("GHC", "ghc"))
    parser.add_argument("--ghc-pkg", default=os.environ.get("GHC_PKG"), help="Must be selected GHC's sibling (default: select it)")
    parser.add_argument("--hsc2hs", default=os.environ.get("HSC2HS"), help="Must be selected GHC's sibling (default: select it)")
    parser.add_argument("--module", action="append", choices=HSC_MODULES, help="Default: all seven modules")
    args = parser.parse_args()
    source = source_checkout(args.ghc_source)
    provenance = toolchain(args.ghc, args.ghc_pkg)
    info = dict(ast.literal_eval(provenance["ghcInfo"]))
    require(info.get("Host platform") == info.get("Target platform"), "This native hsc2hs recipe does not support cross compilation")
    includes = package_dirs(provenance, "ghc-internal", "include-dirs") + package_dirs(provenance, "rts", "include-dirs")
    hsc = sibling_tool(provenance["ghc"], args.hsc2hs, "hsc2hs")
    template = Path(provenance["libdir"]) / "template-hsc.h"
    require(template.is_file(), "Missing selected GHC's hsc2hs template")
    cc = info["C compiler command"]
    require(shutil.which(cc), "Missing installed GHC's configured C compiler: " + cc)
    out = args.out.resolve()
    require(not out.is_relative_to(source) and not out.is_relative_to(Path(provenance["libdir"]).resolve()),
            "Output must be outside the original source and installed toolchain")
    require(not out.exists(), "Use a fresh generation output directory; preserve previous evidence")
    out.mkdir(parents=True)
    provenance.update(sourceCommit=GHC_COMMIT, sourceTag=GHC_TAG, sourceRepository="https://github.com/ghc/ghc.git",
                      sourceRoot=str(source), hsc2hs=hsc, template=str(template),
                      hsc2hsVersion=output([hsc, "--version"]),
                      generatorSourceHashes={str(path): digest(path) for path in (Path(__file__).resolve(), ROOT / "scripts/putstrln_export.py")},
                      cCompiler=cc, cCompilerTarget=output([cc, "-dumpmachine"]),
                      includeDirectories=[str(p) for p in includes],
                      recipe="Original hsc2hs input; installed template/configuration/RTS headers; native sizeof/offsetof",
                      modules=[])
    write(out / "provenance.json", provenance)
    for module in dict.fromkeys(args.module or HSC_MODULES):
        relative = Path(*module.split("."))
        original = (source / "libraries/ghc-internal/src" / relative).with_suffix(".hsc")
        generated = (out / relative).with_suffix(".hs")
        generated.parent.mkdir(parents=True, exist_ok=True)
        argv = [hsc, "--verbose", "--keep-files", "--cc=" + cc, "--template=" + str(template)]
        argv += ["--cflag=" + flag for flag in shlex.split(info["C compiler flags"])]
        argv += ["-I" + str(p) for p in includes]
        argv += ["--output=" + str(generated), str(original)]
        record = {"module": module, "source": str(original), "sourceSha256": digest(original), "output": str(generated)}
        record.update(run(argv, out, out / (module + ".log")))
        if record["exit"] == 0:
            record["outputSha256"] = digest(generated)
        provenance["modules"].append(record)
        write(out / "provenance.json", provenance)
    return int(any(record["exit"] for record in provenance["modules"]))


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (ValueError, OSError, subprocess.CalledProcessError) as error:
        raise SystemExit(str(error))
