#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

"""Locate the Cabal-built THC plugin without inventing a GHC package record."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import shutil
import subprocess
import sys
import tempfile


MANIFEST = Path("build/compiler/plugin.json")
REGISTRIES = Path("build/compiler/plugin-package-dbs")


def one_package_path(value):
    # ghc-pkg --simple-output quotes a registered path containing spaces.
    paths = shlex.split(value)
    if len(paths) != 1:
        raise RuntimeError("Expected one Cabal dynamic library directory")
    return paths[0]


def plugin_plan(root):
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
    dist = Path(library["dist-dir"]).resolve()
    if not dist.is_relative_to((root / "dist-newstyle/build").resolve()):
        raise RuntimeError("Unexpected Cabal library build directory")
    units = {}
    for item in plan["install-plan"]:
        key = item["id"]
        if key in units:
            raise RuntimeError("Duplicate Cabal plan unit ID: " + key)
        units[key] = item
    return library, units


def registration_fields(record):
    """Read identities/dependencies without rewriting the package declaration."""
    fields = {}
    current = None
    for line in record.splitlines():
        field = re.match(r"^([a-zA-Z0-9-]+):\s*(.*)$", line)
        if field:
            current = field[1]
            if current in fields:
                raise RuntimeError("Duplicate package registration field: " + current)
            fields[current] = field[2]
        elif line[:1].isspace() and current is not None:
            fields[current] += " " + line.strip()
        elif line.strip():
            raise RuntimeError("Invalid package registration")
    return {key: value.strip() for key, value in fields.items()}


def relocate_package_paths(record, quoted_root):
    """Complete ghc-pkg's partial expansion using its original quoted pkgroot.

    Keep GHC's string escapes intact, including Windows backslashes. Expanded
    paths/URLs and non-path declarations are unchanged; unknown placeholders
    remain subject to the caller's rejection check.
    """
    quoted = r'"(?:\\.|[^"\\])*"'
    if not isinstance(quoted_root, str) or not re.fullmatch(quoted, quoted_root):
        raise RuntimeError("Missing GHC-quoted original package root")
    fields = {"import-dirs", "library-dirs", "library-dirs-static", "dynamic-library-dirs",
              "data-dir", "include-dirs", "framework-dirs", "haddock-interfaces", "haddock-html"}
    root = quoted_root[1:-1]
    def path(match):
        token = match[0]
        wrapped = token.startswith('"')
        value = token[1:-1] if wrapped else token
        marker = "${pkgroot}"
        if not value.startswith(marker):
            return token
        suffix = value[len(marker):]
        if suffix and not suffix.startswith(("/", "\\")):
            return token
        if not wrapped:
            suffix = suffix.replace("\\", "\\\\").replace('"', '\\"')
        return '"' + root + suffix + '"'
    current = None
    result = []
    for line in record.splitlines(keepends=True):
        header = re.match(r"^([a-zA-Z0-9-]+):", line)
        if header:
            current = header[1]
        if current in fields:
            start = header.end() if header else 0
            line = line[:start] + re.sub(quoted + r"|[^\s]+", path, line[start:])
        result.append(line)
    return "".join(result)


def registry(root, ghc_pkg="ghc-pkg", package_db=None, inputs=None):
    """Publish actual Cabal registrations with a complete non-boot closure.

    GHC resolves plugin dependencies through the package DB even when a plugin
    library is supplied explicitly. Preserve Cabal's unit IDs and declarations;
    ghc-pkg expands paths at their original database before relocation.
    """
    root = Path(root).resolve()
    library, units = plugin_plan(root)
    if inputs is not None:
        inputs.add(root / "dist-newstyle/cache/plan.json")
    def query(*arguments):
        return subprocess.check_output([ghc_pkg, *arguments], cwd=root, text=True).strip()
    if query("--version") != "GHC package manager version 9.14.1":
        raise RuntimeError("THC plugin requires ghc-pkg 9.14.1")
    if inputs is not None:
        inputs.add(Path(query("--global", "--no-user-package-db", "list").splitlines()[0]) / "package.cache")
    common = ["--global", "--no-user-package-db", "--expand-pkgroot"]
    global_records = {}
    for record in re.split(r"(?m)^---[ \t]*$", query(*common, "dump")):
        if not record.strip():
            continue
        fields = registration_fields(record)
        key = fields.get("id")
        if not key or key in global_records:
            raise RuntimeError("Invalid global package registration identity")
        global_records[key] = record.strip() + "\n"

    pending = [library["id"]]
    selected = {}
    boot = {}
    store = None
    while pending:
        unit = pending.pop()
        if unit in selected or unit in boot:
            continue
        if not isinstance(unit, str) or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.+-]*", unit):
            raise RuntimeError("Invalid Cabal dependency unit ID")
        planned = units.get(unit)
        if planned is None:
            raise RuntimeError("Plugin dependency absent from Cabal plan: " + unit)
        dependencies = planned.get("depends", [])
        if not isinstance(dependencies, list) or not all(isinstance(value, str) for value in dependencies):
            raise RuntimeError("Invalid Cabal dependency list: " + unit)
        if planned.get("type") == "pre-existing":
            if unit not in global_records:
                raise RuntimeError("Planned boot dependency missing from selected ghc-pkg: " + unit)
            # Dump ignores expansion; describe expands the supported fields.
            # Residual path fields use its reported original pkgroot below.
            description = [*common, "--ipid", "describe", unit]
            record = query(*description) + "\n"
            destination = boot
        else:
            if planned.get("style") == "local":
                database = root / "dist-newstyle/packagedb/ghc-9.14.1"
                candidates = [database / (unit + ".conf")]
            elif planned.get("style") == "global":
                if store is None:
                    path = subprocess.check_output(
                        [os.environ.get("CABAL", "cabal"), "path", "--store-dir"], cwd=root, text=True).strip()
                    store = Path(path).resolve()
                    if not path or not Path(path).is_absolute() or not store.is_dir():
                        raise RuntimeError("Cabal did not report its configured store directory")
                candidates = list(store.glob("*/package.db/" + unit + ".conf"))
            else:
                raise RuntimeError("Unknown Cabal dependency registration style: " + unit)
            candidates = [path for path in candidates if path.is_file()]
            if len(candidates) != 1:
                raise RuntimeError("Expected one actual Cabal registration for " + unit)
            database = candidates[0].parent
            if inputs is not None:
                inputs.add(candidates[0])
            description = [*common, "--package-db", str(database), "--ipid", "describe", unit]
            record = query(*description) + "\n"
            destination = selected
        if "${pkgroot}" in record:
            original = query(*["--no-expand-pkgroot" if argument == "--expand-pkgroot" else argument
                               for argument in description])
            record = relocate_package_paths(record, registration_fields(original).get("pkgroot"))
        fields = registration_fields(record)
        if fields.get("id") != unit or set(fields.get("depends", "").split()) != set(dependencies):
            raise RuntimeError("Cabal registration differs from planned dependency closure: " + unit)
        if "${pkgroot}" in record or "${pkgrooturl}" in record:
            raise RuntimeError("Unexpanded package registration path: " + unit)
        if inputs is not None:
            suffix = "dylib" if sys.platform == "darwin" else "so"
            for name in fields.get("hs-libraries", "").split():
                # GHC.Unit.Info.unitHsLibs: HS libraries carry the GHC suffix;
                # C libraries drop their static-archive prefix (Cffi -> ffi).
                if name.startswith("HS"):
                    dynamic_name = name + "-ghc9.14.1"
                elif name.startswith("C"):
                    dynamic_name = name[1:]
                else:
                    raise RuntimeError("Unknown registered library naming convention: " + name)
                candidates = [Path(directory) / f"lib{dynamic_name}.{suffix}"
                              for directory in shlex.split(fields.get("dynamic-library-dirs", ""))]
                library_path = next((path for path in candidates if path.is_file()), None)
                if library_path is None:
                    raise RuntimeError("Missing registered dynamic library: " + name)
                inputs.add(library_path)
        destination[unit] = record
        pending.extend(dependencies)

    identity = json.dumps({"registrations": selected, "boot": boot}, sort_keys=True).encode()
    parent = root / REGISTRIES
    parent.mkdir(parents=True, exist_ok=True)
    database = parent / hashlib.sha256(identity).hexdigest()
    if package_db is not None:
        database = Path(package_db).resolve()
        if database.parent != parent or not re.fullmatch(r"cmake-[0-9a-f]{64}", database.name):
            raise RuntimeError("CMake plugin registry must be in build/compiler/plugin-package-dbs/cmake-<plan hash>")
        # One CMake rule owns these registrations. Unlike the immutable cache,
        # this publication can regenerate a missing member of its OUTPUT list.
        database.mkdir(parents=True, exist_ok=True)
        for unit, record in selected.items():
            destination = database / (unit + ".conf")
            if not destination.exists() or destination.read_text() != record:
                destination.write_text(record)
        subprocess.check_call([ghc_pkg, *common, "--package-db", str(database), "recache"], cwd=root, stdout=sys.stderr)
        subprocess.check_call([ghc_pkg, *common, "--package-db", str(database), "check"], cwd=root, stdout=sys.stderr)
        return {"schema": 1, "unitId": library["id"], "packageDb": str(database)}
    def verify():
        actual = {path.stem: path.read_text() for path in database.glob("*.conf")}
        if actual != selected or not (database / "package.cache").is_file():
            raise RuntimeError("Private plugin registry differs from its Cabal registrations")
        subprocess.check_call([ghc_pkg, *common, "--package-db", str(database), "check"], cwd=root, stdout=sys.stderr)
    if not database.exists():
        temporary = Path(tempfile.mkdtemp(prefix=".registry-", dir=parent))
        try:
            for unit, record in selected.items():
                (temporary / (unit + ".conf")).write_text(record)
            subprocess.check_call([ghc_pkg, *common, "--package-db", str(temporary), "recache"], cwd=root, stdout=sys.stderr)
            subprocess.check_call([ghc_pkg, *common, "--package-db", str(temporary), "check"], cwd=root, stdout=sys.stderr)
            try:
                temporary.rename(database)
            except OSError:
                if not database.is_dir():
                    raise
                verify()  # Another invocation may have published the same closure.
        finally:
            if temporary.exists():
                shutil.rmtree(temporary)
    else:
        verify()
    return {"schema": 1, "unitId": library["id"], "packageDb": str(database)}


def locate(root, ghc_pkg="ghc-pkg", package_db=None, inputs=None):
    root = Path(root).resolve()
    library, _ = plugin_plan(root)
    unit = library["id"]
    dist = Path(library["dist-dir"]).resolve()
    data = registry(root, ghc_pkg, package_db, inputs)
    package_db = data["packageDb"]
    def field(name):
        return subprocess.check_output(
            [ghc_pkg, "--no-user-package-db", "--unit-id", "field", unit, name, "--simple-output",
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
    return data | {"sharedLibrary": str(copy), "cabalSharedLibrary": str(original)}


def publish(root, ghc_pkg="ghc-pkg", package_db=None, depfile=None):
    root = Path(root).resolve()
    inputs = set() if depfile is not None else None
    data = locate(root, ghc_pkg, package_db, inputs)
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
    if depfile is not None:
        def escape(path):
            return str(path).replace("\\", "/").replace("$", "$$").replace("#", "\\#").replace(" ", "\\ ").replace(":", "\\:")
        Path(depfile).write_text(escape(manifest) + ": " + " ".join(escape(path) for path in sorted(inputs)) + "\n")
    return data


def read(root):
    root = Path(root).resolve()
    data = json.loads((root / MANIFEST).read_text())
    if data.get("schema") != 1 or not all(data.get(field) for field in
                                           ("unitId", "packageDb", "sharedLibrary", "cabalSharedLibrary")):
        raise RuntimeError("Invalid THC plugin manifest")
    database = Path(data["packageDb"]).resolve()
    legacy = root / "dist-newstyle/packagedb/ghc-9.14.1"
    private = database.parent == root / REGISTRIES and (re.fullmatch(r"cmake-[0-9a-f]{64}", database.name) or re.fullmatch(r"[0-9a-f]{64}", database.name))
    if Path(data["sharedLibrary"]).parent != root / "build/compiler" or not (database == legacy or private):
        raise RuntimeError("THC plugin manifest belongs to another checkout")
    if not Path(data["sharedLibrary"]).is_file() or not Path(data["packageDb"]).is_dir():
        raise RuntimeError("THC plugin manifest points to missing build products")
    return data


def response_arguments(contents):
    """Pinned GHC.ResponseFile grammar: backslash escapes even inside quotes."""
    words, word, quote, escaped = [], [], None, False
    for character in contents:
        if escaped:
            word.append(character)
            escaped = False
        elif character == "\\":
            escaped = True
        elif quote:
            if character == quote:
                quote = None
            else:
                word.append(character)
        elif character in "'\"":
            quote = character
        elif character.isspace():
            words.append("".join(word))
            word = []
        else:
            word.append(character)
    return [value for value in words + ["".join(word)] if value]


def external_plugin_argument(metadata, output, arguments):
    """GHC's direct loader reads a Haskell list; ordinary fplugin-opt is separate."""
    options = [output]
    # Like GHC's expandResponse, expand one level only. Keep the original argv
    # for GHC itself; this pass collects only the direct plugin's own options.
    arguments = iter(value for argument in arguments for value in
                     (response_arguments(Path(argument[1:]).read_text()) if argument.startswith("@") else [argument]))
    for argument in arguments:
        value = next(arguments, "") if argument == "-fplugin-opt" else \
            argument.removeprefix("-fplugin-opt=") if argument.startswith("-fplugin-opt=") else ""
        if value.startswith("THC.Plugin:"):
            options.append(value.removeprefix("THC.Plugin:"))
        elif value == "THC.Plugin":
            options.append("")
    def quoted(value):
        return '"' + ''.join(character if ord(character) >= 32 and character not in '\\"' else
                             "\\" + str(ord(character)) + "\\&" for character in value) + '"'
    return ("-fplugin-library=" + metadata["sharedLibrary"] + ";" + metadata["unitId"] +
            ";THC.Plugin;[" + ",".join(map(quoted, options)) + "]")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parent.parent)
    parser.add_argument("--ghc-pkg", default="ghc-pkg")
    parser.add_argument("--package-db", type=Path, help="CMake-owned plugin registry output")
    parser.add_argument("--depfile", type=Path, help="Write consumed Cabal registrations and libraries for Ninja")
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--publish", action="store_true")
    mode.add_argument("--registry-only", action="store_true",
                      help="Publish the actual package closure without requiring a shared THC library")
    mode.add_argument("--external-plugin", nargs=argparse.REMAINDER,
                      help="Render the direct plugin flag from OUTPUT followed by caller GHC arguments")
    parser.add_argument("--field", choices=("unitId", "packageDb", "sharedLibrary", "cabalSharedLibrary"))
    args = parser.parse_args()
    if (args.package_db is not None or args.depfile is not None) and not args.publish:
        parser.error("--package-db and --depfile require --publish")
    result = registry(args.root, args.ghc_pkg) if args.registry_only else \
        publish(args.root, args.ghc_pkg, args.package_db, args.depfile) if args.publish else read(args.root)
    if args.field and args.field not in result:
        parser.error("--registry-only does not locate shared-library fields")
    if args.external_plugin is not None:
        if not args.external_plugin or args.field:
            parser.error("--external-plugin needs OUTPUT and does not accept --field")
        print(external_plugin_argument(result, args.external_plugin[0], args.external_plugin[1:]))
    else:
        print(result[args.field] if args.field else json.dumps(result, sort_keys=True))
