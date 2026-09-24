#!/usr/bin/env python3
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
"""Bounded, source-only GHC 9.14.1 export; never a runnable IO claim."""

import argparse
from collections import Counter, defaultdict
from contextlib import contextmanager
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import shlex
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
GHC_COMMIT = "902339d332fb4ce2b3c87dcac1ee6495d41ad886"
GHC_TAG = "ghc-9.14.1-release"
HSC_MODULES = (
    "GHC.Internal.ExecutionStack.Internal", "GHC.Internal.InfoProv.Types",
    "GHC.Internal.Heap.Constants", "GHC.Internal.Heap.InfoTable.Types",
    "GHC.Internal.Heap.InfoTable", "GHC.Internal.Stack.CCS", "GHC.Internal.Stack.Constants",
)
CONFIGURED_MODULES = {
    "GHC.Internal.Encoding.UTF8", "GHC.Internal.Float.ConversionUtils",
}
# Reviewed compulsory constructor wrappers for the pinned source-only recipe.
# GHC's exporter does not yet distinguish compulsory from ordinary unfoldings.
COMPULSORY_WRAPPERS = {
    "ghc-internal:GHC.Internal.Data.Type.Equality.$WHRefl",
    "ghc-internal:GHC.Internal.Heap.Closures.$WRetFun",
    "ghc-internal:GHC.Internal.Heap.Closures.$WCatchRetryFrame",
}


def read(path):
    return json.loads(Path(path).read_text())


def write(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(json.dumps(value, indent=2) + "\n")
    temporary.replace(path)


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def output(argv):
    return subprocess.check_output(argv, text=True).strip()


def require(condition, message):
    if not condition:
        raise ValueError(message)


def source_checkout(path):
    path = path.resolve()
    require(output(["git", "-C", str(path), "rev-parse", "HEAD"]) == GHC_COMMIT,
            "Expected the official ghc-9.14.1-release commit " + GHC_COMMIT)
    # No source modifications or untracked replacements are accepted. Ignored
    # generated files are never consulted; .hsc outputs need separate provenance.
    require(not output(["git", "-C", str(path), "status", "--porcelain", "--untracked-files=all"]),
            "Use a clean, unmodified official GHC source checkout")
    require((path / "libraries/ghc-internal/src").is_dir(), "Missing ghc-internal sources")
    return path


def sibling_tool(ghc, requested, name):
    path = Path(shutil.which(requested or str(Path(ghc).parent / name)) or "")
    require(path.is_file(), "Missing selected GHC's sibling tool: " + name)
    path = path.resolve()
    require(path.parent == Path(ghc).resolve().parent, "Tool is not from selected GHC's bindir: " + str(path))
    return str(path)


def toolchain(ghc, pkg=None):
    executable = shutil.which(ghc)
    require(executable, "Missing GHC executable: " + ghc)
    ghc = str(Path(executable).resolve())
    pkg = sibling_tool(ghc, pkg, "ghc-pkg")
    require(output([ghc, "--numeric-version"]) == "9.14.1", "Expected GHC 9.14.1")
    require(output([pkg, "--version"]) == "GHC package manager version 9.14.1",
            "Expected ghc-pkg 9.14.1 from the same installation")
    libdir = Path(output([ghc, "--print-libdir"])).resolve()
    database = Path(output([ghc, "--print-global-package-db"])).resolve()
    require(database.is_dir() and database.is_relative_to(libdir), "GHC global package database is outside its libdir")
    return {"ghc": ghc, "ghcPkg": pkg,
            "ghcVersion": "9.14.1", "ghcInfo": output([ghc, "--info"]),
            "libdir": str(libdir), "globalPackageDatabase": str(database),
            "packageQuery": [pkg, "--global", "--no-user-package-db", "--global-package-db=" + str(database)],
            "compilerPackageFlags": ["-package-env", "-", "-clear-package-db", "-package-db", str(database)],
            "installedArtifactsHashed": False}


def package_dirs(tools, package, field):
    paths = [Path(p).resolve() for p in shlex.split(output(
        tools["packageQuery"] + ["field", package, field, "--simple-output"]))]
    require(paths and all(p.is_dir() for p in paths), "Missing installed " + field)
    require(all(p.is_relative_to(Path(tools["libdir"])) for p in paths),
            "Installed package path is outside selected GHC's libdir: " + field)
    return paths


def run(argv, cwd, log):
    """Preserve failed attempts as well as successful compiler invocations."""
    log.parent.mkdir(parents=True, exist_ok=True)
    original = log
    index = 0
    while log.exists():
        index += 1
        log = original.with_name(original.stem + f".attempt{index}.log")
    record = {"argv": [str(a) for a in argv], "cwd": str(cwd), "log": str(log)}
    with log.open("x") as stream:
        result = subprocess.run(record["argv"], cwd=cwd, stdout=stream, stderr=subprocess.STDOUT)
    record["exit"] = result.returncode
    write(log.with_suffix(".command.json"), record)
    print(f"{log.name}: exit {result.returncode}", flush=True)
    return record


def verify_hashes(hashes):
    for path, expected in hashes.items():
        require(digest(path) == expected, "Changed provenance input: " + path)


def recipe_hashes(root):
    paths = [root / "scripts" / name for name in (
        "putstrln_export.py", "putstrln_hsc.py", "putstrln_inventory.py",
        "audit-core.py", "core-capabilities.json",
    )]
    paths += sorted((root / "scripts").glob("core_*.py"))
    paths += sorted((root / "src/main/resources/thc").glob("*.json"))
    return {str(path): digest(path) for path in paths}


def verify_plugin(record):
    require(record["exit"] == 0, "Plugin build did not succeed")
    verify_hashes(record["sourceHashes"])
    require(digest(record["binary"]) == record["binarySha256"], "Changed recipe-owned plugin binary")
    capability = any('"foreignCall"' in Path(path).read_text() for path in record["sourceHashes"])
    require(capability == record["foreignMetadataExporterAvailable"], "Plugin capability differs from built sources")


def build_plugin(root, directory, tools):
    """Compile only fresh source snapshots/objects; never trust a supplied DSO."""
    require(not directory.exists(), "Plugin build directory must be fresh")
    directory.mkdir(parents=True)
    source_root = directory / "source"
    originals, snapshots = {}, {}
    for source in sorted((root / "compiler/THC").rglob("*.hs")):
        target = source_root / source.relative_to(root / "compiler")
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(source.read_bytes())
        snapshots[str(target)] = digest(target)
        originals[str(source)] = snapshots[str(target)]
    require((source_root / "THC/Plugin.hs").is_file(), "Missing plugin source entry")
    suffix = "dylib" if sys.platform == "darwin" else "so"
    binary = directory / f"libHSthc-core-plugin-0.1-ghc9.14.1.{suffix}"
    # Same compiler/build.sh compilation flags, with forced fresh outputs and
    # direct-library loading; no registration/package database is required.
    argv = [tools["ghc"], "--make", "-fforce-recomp", "-O1", "-dynamic", "-shared", "-fPIC"]
    argv += tools["compilerPackageFlags"]
    for package in ("ghc", "bytestring", "directory", "filepath", "containers"):
        argv += ["-package", package]
    argv += ["-this-unit-id", "thc-core-plugin-0.1", "-hisuf", "dyn_hi", "-osuf", "dyn_o",
             "-i", "-i" + str(source_root), "-odir", str(directory), "-hidir", str(directory),
             str(source_root / "THC/Plugin.hs"), "-o", str(binary)]
    record = {"binary": str(binary), "originalSourceHashes": originals, "sourceHashes": snapshots,
              "foreignMetadataExporterAvailable": any('"foreignCall"' in Path(p).read_text() for p in snapshots)}
    write(directory / "provenance.json", record)
    record.update(run(argv, directory, directory / "build.log"))
    if record["exit"] == 0:
        record["binarySha256"] = digest(binary)
    write(directory / "provenance.json", record)
    verify_plugin(record)
    return record


def configuration_text(module, text):
    """Retain source OPTIONS -O2 without letting it undo interface isolation."""
    def replace(match):
        flags = shlex.split(match[1])
        resets = any(re.fullmatch(r"-O(?:[012])?", flag) for flag in flags)
        require("-fno-ignore-interface-pragmas" not in flags,
                "Explicit source override of interface isolation: " + module)
        if not resets:
            return match[0]
        require(module in CONFIGURED_MODULES, "Unreviewed source optimization override: " + module)
        return match[0][:-3].rstrip() + " -fignore-interface-pragmas #-}"
    return re.sub(r"\{-#\s*OPTIONS_GHC\s+(.*?)#-\}", replace, text, flags=re.S)


def create_overlay(installed, overlay):
    count = 0
    for source in sorted(installed.rglob("*.dyn_hi")):
        target = overlay / source.relative_to(installed).with_suffix(".hi")
        target.parent.mkdir(parents=True, exist_ok=True)
        target.symlink_to(source)
        count += 1
    require(count, "Installed ghc-internal has no dynamic interfaces")


@contextmanager
def isolated_interface(installed, overlay, relative, saved):
    """Never overwrite the installed target, even on compiler failure."""
    interface = (overlay / relative).with_suffix(".hi")
    original = (installed / relative).with_suffix(".dyn_hi")
    require(interface.is_symlink() and interface.resolve() == original.resolve() and original.is_file(),
            "Overlay no longer contains the installed interface: " + str(interface))
    interface.unlink()
    try:
        yield
    finally:
        if interface.exists() or interface.is_symlink():
            require(not interface.is_symlink(), "Compiler output unexpectedly became a symlink")
            destination = (saved / relative).with_suffix(".hi")
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.move(interface, destination)
        interface.symlink_to(original)


def validate_export(directory, module, roots):
    source = directory / (module + ".json")
    data = read(source)
    require(data["module"] == module and data["ghc"] == "9.14.1" and
            data.get("boundary") == "optimized-Core-after-Tidy-before-CorePrep", "Wrong export boundary")
    matched = sorted(set(roots) & {b["name"] for b in data["bindings"]})
    # Missing roots may indicate changed optimizer-private identities. Never
    # silently treat the plugin's skipped closure walk as a successful export.
    require(set(roots) <= set(matched), "Requested roots were not exported: " + repr(set(roots) - set(matched)))
    closure = directory / "THC.InterfaceClosure.json"
    require(closure.is_file(), "Missing interface-closure evidence")
    imported = {b["id"] for b in read(closure)["bindings"]}
    require(imported <= COMPULSORY_WRAPPERS, "Unexpected interface body: " + repr(imported - COMPULSORY_WRAPPERS))
    return {"paths": [str(source), str(closure)], "matchedRoots": matched,
            "exportHashes": {str(p): digest(p) for p in (source, closure)},
            "interfaceBodies": sorted(imported)}


def merge_exports(records):
    """Admit only validated successful records, not directory-glob artifacts."""
    bindings, constructors, origins, duplicates = {}, {}, {}, []
    admitted = [r for r in records if r.get("exit") == 0]
    for record in admitted:
        for path, expected in record.get("exportHashes", {}).items():
            require(digest(path) == expected, "Changed admitted export: " + path)
    paths = [Path(r["paths"][i]) for i in (0, 1) for r in admitted]
    for path in paths:
        data = read(path)
        is_interface = data["module"] == "THC.InterfaceClosure"
        for binding in data["bindings"]:
            key = binding["id"]
            if key in bindings:
                require(is_interface and key in COMPULSORY_WRAPPERS,
                        "Conflicting independently exported source identity: " + key)
                duplicates.append({"id": key, "selected": origins[key], "discarded": str(path)})
            else:
                bindings[key], origins[key] = binding, str(path)
        for con in data.get("constructors", []):
            require(con["id"] not in constructors or constructors[con["id"]] == con,
                    "Conflicting constructor identity: " + con["id"])
            constructors[con["id"]] = con
    return ({"schema": 1, "ghc": "9.14.1", "module": "Research.PutStrLnClosure", "unit": "source-only-merge",
             "bindings": list(bindings.values()), "constructors": list(constructors.values())},
            {"bindingOrigins": origins, "duplicates": duplicates})


def generated_sources(manifest, source):
    if manifest is None:
        return {}
    data = read(manifest)
    require(data["sourceCommit"] == GHC_COMMIT and data["ghcVersion"] == "9.14.1",
            "Wrong hsc2hs provenance release")
    result = {}
    for record in data["modules"]:
        require(record["module"] in HSC_MODULES and record["module"] not in result,
                "Unexpected or duplicate generated module")
        original = source / "libraries/ghc-internal/src" / Path(*record["module"].split(".")).with_suffix(".hsc")
        require(record["exit"] == 0 and Path(record["source"]).resolve() == original.resolve(),
                "Invalid hsc2hs source/exit provenance")
        require(digest(original) == record["sourceSha256"] and digest(record["output"]) == record["outputSha256"],
                "Changed hsc2hs source or output")
        result[record["module"]] = record
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ghc-source", required=True, type=Path)
    parser.add_argument("--out", type=Path, default=ROOT / "build/putstrln-source-only")
    parser.add_argument("--ghc", default=os.environ.get("GHC", "ghc"))
    parser.add_argument("--ghc-pkg", default=os.environ.get("GHC_PKG"), help="Must be selected GHC's sibling (default: select it)")
    parser.add_argument("--generated-manifest", type=Path)
    parser.add_argument("--rounds", type=int, default=8)
    parser.add_argument("--max-modules", type=int, default=30)
    args = parser.parse_args()
    require(args.rounds > 0 and args.max_modules >= 0, "Invalid export bounds")
    source = source_checkout(args.ghc_source)
    src = source / "libraries/ghc-internal/src"
    out = args.out.resolve()
    require(not out.exists(), "Use a fresh output directory; existing evidence is never overwritten")
    tools = toolchain(args.ghc, args.ghc_pkg)
    require(not out.is_relative_to(source) and not out.is_relative_to(Path(tools["libdir"]).resolve()),
            "Output must be outside the original source and installed toolchain")
    installed_dirs = package_dirs(tools, "ghc-internal", "import-dirs")
    require(len(installed_dirs) == 1, "Expected one installed ghc-internal interface root")
    installed = installed_dirs[0]
    generated = generated_sources(args.generated_manifest, source)
    if args.generated_manifest:
        generation = read(args.generated_manifest)
        for key in ("ghcInfo", "libdir", "globalPackageDatabase", "packageQuery"):
            require(generation.get(key) == tools[key], "hsc2hs used a different or unbound GHC configuration: " + key)
        require(generation.get("template") == str(Path(tools["libdir"]) / "template-hsc.h"), "hsc2hs template is not bound to selected GHC")
        sibling_tool(tools["ghc"], generation["hsc2hs"], "hsc2hs")
        expected_includes = package_dirs(tools, "ghc-internal", "include-dirs") + package_dirs(tools, "rts", "include-dirs")
        require(generation["includeDirectories"] == [str(p) for p in expected_includes], "hsc2hs used different installed headers")
    out.mkdir(parents=True)
    provenance_inputs = recipe_hashes(ROOT)
    plugin_build = build_plugin(ROOT, out / "plugin", tools)
    plugin = Path(plugin_build["binary"])
    overlay = out / "overlay"
    create_overlay(installed, overlay)
    tracked = output(["git", "-C", str(source), "ls-files", "-z", "libraries/ghc-internal/src"]).split("\0")
    source_files = {".".join((source / name).relative_to(src).with_suffix("").parts): source / name
                    for name in tracked if name.endswith(".hs")}
    source_files.update({name: Path(r["output"]) for name, r in generated.items()})
    state = {"recipe": "source-only-installed-interface-inputs", "sourceCommit": GHC_COMMIT,
             "sourceRoot": str(source), "thcRevision": output(["git", "-C", str(ROOT), "rev-parse", "HEAD"]),
             "toolchain": tools, "pluginBuild": plugin_build, "recipeInputHashes": provenance_inputs,
             "installedInterfaces": str(installed),
             "compiled": {}, "failed": {}, "bootSources": {},
             "foreignMetadataExporterAvailable": plugin_build["foreignMetadataExporterAvailable"]}
    if args.generated_manifest:
        state["generatedManifest"] = {"path": str(args.generated_manifest.resolve()), "sha256": digest(args.generated_manifest)}
    write(out / "export-state.json", state)
    include = package_dirs(tools, "ghc-internal", "include-dirs")
    common = [tools["ghc"], "-c", "-dynamic", "-fforce-recomp", "-XHaskell2010"] + tools["compilerPackageFlags"]
    internal = ["-XNoImplicitPrelude", "-this-unit-id", "ghc-internal", "-package", "ghc-internal", "-i" + str(overlay),
                "-hidir", str(overlay), "-odir", str(out / "boot-objects")]
    internal += ["-I" + str(p) for p in include + [source / "libraries/ghc-internal/include"]]

    def with_boot(argv, log, stack=()):
        for _ in range(25):
            record = run(argv, ROOT, log)
            if record["exit"] == 0:
                return record
            matches = re.findall(re.escape(str(overlay)) + r"/([A-Za-z0-9_/]+)\.hi-boot", Path(record["log"]).read_text())
            missing = [m for m in dict.fromkeys(matches) if not (overlay / (m + ".hi-boot")).exists()]
            if not missing:
                return record
            for name in missing:
                boot = src / (name + ".hs-boot")
                if not boot.is_file() or name in stack:
                    return record
                require(configuration_text(name.replace("/", "."), boot.read_text()) == boot.read_text(),
                        "Unreviewed boot-source OPTIONS override")
                boot_record = with_boot(common + internal + ["-fignore-interface-pragmas", "-dcore-lint", str(boot)],
                                        out / "logs" / (name.replace("/", ".") + ".boot.log"), stack + (name,))
                if boot_record["exit"]:
                    return record
                state["bootSources"][name] = {"source": str(boot), "sha256": digest(boot), **boot_record}
        raise ValueError("Exceeded original hs-boot dependency bound")

    def compile_module(module, path, roots, application=False):
        target = out / "source-exports" / module
        target.mkdir(parents=True)
        own = out / "module-objects" / module
        own.mkdir(parents=True)
        text = path.read_text()
        configured = configuration_text(module, text)
        compile_path = path
        record = {"source": str(path), "sha256": digest(path), "roots": sorted(roots)}
        if configured != text:
            compile_path = out / "configuration-source" / Path(*module.split(".")).with_suffix(".hs")
            compile_path.parent.mkdir(parents=True, exist_ok=True)
            compile_path.write_text(configured)
            record.update(configurationSource=str(compile_path), configurationSha256=digest(compile_path),
                          configurationOnlyChange="Append ignore-interface-pragmas after original source OPTIONS optimization flag")
        if module in generated:
            record["hsc2hsProvenance"] = generated[module]
        options = [str(target), "post-tidy", "source-notes"] + ["closure=" + r for r in sorted(roots)]
        flags = ["-O2", "-fignore-interface-pragmas", "-dcore-lint", "-g", "-odir", str(own),
                 "-fplugin-library=" + str(plugin) + ";thc-core-plugin-0.1;THC.Plugin;" + json.dumps(options)]
        try:
            verify_plugin(plugin_build)
            if application:
                result = run(common + ["-hidir", str(own)] + flags + [str(compile_path)], ROOT, out / "logs/Main.log")
            else:
                with isolated_interface(installed, overlay, Path(*module.split(".")), own):
                    result = with_boot(common + internal + flags + [str(compile_path)], out / "logs" / (module + ".log"))
            record.update(result)
            verify_plugin(plugin_build)
            if record["exit"] == 0:
                record.update(validate_export(target, module, roots))
        except ValueError as error:
            record.update(exit=1, validationFailure=str(error))
        state["failed" if record["exit"] else "compiled"][module] = record
        write(out / "export-state.json", state)
        return record["exit"] == 0

    require(compile_module("Main", ROOT / "compiler/putstrln/Main.hs", {"main"}, application=True),
            "Main export failed; retained command/log and partial artifacts")
    verify_hashes(provenance_inputs)
    sys.path.insert(0, str(ROOT / "scripts"))
    spec = importlib.util.spec_from_file_location("core_audit", ROOT / "scripts/audit-core.py")
    audit = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(audit)
    caps = read(ROOT / "scripts/core-capabilities.json")
    for iteration in range(args.rounds):
        merged, provenance = merge_exports(list(state["compiled"].values()))
        write(out / "merged-core.json", merged)
        write(out / "merge-provenance.json", provenance)
        report = audit.Audit([(str(out / "merged-core.json"), merged)], caps).run(["main:Main.main"], io_main=True)
        verify_hashes(provenance_inputs)
        write(out / f"audit-round-{iteration:02d}.json", report)
        write(out / "latest-audit.json", report)
        print(json.dumps({"round": iteration, **report["summary"],
                          "issues": dict(Counter(i["code"] for i in report["issues"]))}), flush=True)
        wanted, unresolved = defaultdict(set), []
        missing = report["missingGlobals"] + [{"id": i["detail"]} for i in report["issues"] if i["code"] == "missing-constructor"]
        for item in missing:
            key = item["id"]
            module = next((m for m in sorted(source_files, key=len, reverse=True)
                           if key.startswith("ghc-internal:" + m + ".")), None)
            if module is None or module in state["compiled"] or module in state["failed"]:
                unresolved.append(key)
            else:
                wanted[module].add(key[len("ghc-internal:" + module + "."):])
        state.update(unresolved=unresolved, requestedNextModules=sorted(wanted), lastAuditRound=iteration,
                     mergedSha256=digest(out / "merged-core.json"), auditSha256=digest(out / "latest-audit.json"),
                     auditedModules=sorted(state["compiled"]))
        write(out / "export-state.json", state)
        # Always finish on an audit of the exact admitted successful exports.
        if not wanted or len(state["compiled"]) - 1 >= args.max_modules or iteration == args.rounds - 1:
            break
        for module, roots in sorted(wanted.items()):
            if len(state["compiled"]) - 1 >= args.max_modules:
                break
            compile_module(module, source_files[module], roots)
    return 1 if state["failed"] else 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (ValueError, OSError, subprocess.CalledProcessError) as error:
        raise SystemExit(str(error))
