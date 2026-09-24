#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

"""Locate the Cabal-built THC plugin without inventing a GHC package record."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import shlex
import shutil
import subprocess
import sys


MANIFEST = Path("build/compiler/plugin.json")


def one_package_path(value):
    # ghc-pkg --simple-output quotes a registered path containing spaces.
    paths = shlex.split(value)
    if len(paths) != 1:
        raise RuntimeError("Expected one Cabal dynamic library directory")
    return paths[0]


def locate(root, ghc_pkg="ghc-pkg"):
    root = Path(root).resolve()
    plan = json.loads((root / "dist-newstyle/cache/plan.json").read_text())
    if plan.get("compiler-id") != "ghc-9.14.1":
        raise RuntimeError("THC plugin requires a GHC 9.14.1 Cabal build")
    libraries = [item for item in plan.get("install-plan", [])
                 if item.get("pkg-name") == "thc" and item.get("component-name") == "lib"
                 and item.get("style") == "local"]
    if len(libraries) != 1:
        raise RuntimeError("Expected exactly one local thc Cabal library")
    library = libraries[0]
    if Path(library["pkg-src"]["path"]).resolve() != root:
        raise RuntimeError("Cabal plan belongs to another checkout")
    unit = library["id"]
    dist = Path(library["dist-dir"]).resolve()
    if not dist.is_relative_to(root / "dist-newstyle/build"):
        raise RuntimeError("Unexpected Cabal library build directory")
    package_db = root / "dist-newstyle/packagedb/ghc-9.14.1"
    def field(name):
        return subprocess.check_output(
            [ghc_pkg, "--unit-id", "field", unit, name, "--simple-output",
             "--package-db", str(package_db)], text=True).strip()
    if field("id") != unit:
        raise RuntimeError("Cabal library is not registered under its planned unit ID")
    hs_library = field("hs-libraries")
    if not hs_library or len(hs_library.split()) != 1 or "/" in hs_library:
        raise RuntimeError("Unexpected Cabal library name")
    if one_package_path(field("dynamic-library-dirs")) != str(dist / "build"):
        raise RuntimeError("Cabal library registration points outside its build")
    suffix = "dylib" if sys.platform == "darwin" else "so"
    original = dist / "build" / f"lib{hs_library}-ghc9.14.1.{suffix}"
    if not original.is_file():
        raise RuntimeError("Cabal shared THC library is missing; set shared: True")
    copy = root / "build/compiler" / original.name
    return {"schema": 1, "unitId": unit, "packageDb": str(package_db),
            "sharedLibrary": str(copy), "cabalSharedLibrary": str(original)}


def publish(root, ghc_pkg="ghc-pkg"):
    root = Path(root).resolve()
    data = locate(root, ghc_pkg)
    source, destination = Path(data["cabalSharedLibrary"]), Path(data["sharedLibrary"])
    destination.parent.mkdir(parents=True, exist_ok=True)
    def digest(path):
        with path.open("rb") as stream:
            return hashlib.file_digest(stream, "sha256").digest()
    if not destination.is_file() or digest(source) != digest(destination):
        temporary = destination.with_name(destination.name + ".tmp")
        shutil.copy2(source, temporary)
        os.replace(temporary, destination)
    manifest = root / MANIFEST
    rendered = json.dumps(data, indent=2, sort_keys=True) + "\n"
    if not manifest.exists() or manifest.read_text() != rendered:
        temporary = manifest.with_name(manifest.name + ".tmp")
        temporary.write_text(rendered)
        os.replace(temporary, manifest)
    return data


def read(root):
    root = Path(root).resolve()
    data = json.loads((root / MANIFEST).read_text())
    if data.get("schema") != 1 or not all(data.get(field) for field in
                                           ("unitId", "packageDb", "sharedLibrary", "cabalSharedLibrary")):
        raise RuntimeError("Invalid THC plugin manifest")
    if Path(data["sharedLibrary"]).parent != root / "build/compiler" or \
            Path(data["packageDb"]) != root / "dist-newstyle/packagedb/ghc-9.14.1":
        raise RuntimeError("THC plugin manifest belongs to another checkout")
    if not Path(data["sharedLibrary"]).is_file() or not Path(data["packageDb"]).is_dir():
        raise RuntimeError("THC plugin manifest points to missing build products")
    return data


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parent.parent)
    parser.add_argument("--ghc-pkg", default="ghc-pkg")
    parser.add_argument("--publish", action="store_true")
    parser.add_argument("--field", choices=("unitId", "packageDb", "sharedLibrary", "cabalSharedLibrary"))
    args = parser.parse_args()
    result = publish(args.root, args.ghc_pkg) if args.publish else read(args.root)
    print(result[args.field] if args.field else json.dumps(result, sort_keys=True))
