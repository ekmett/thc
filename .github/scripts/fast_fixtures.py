# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

"""Prepare only native fixtures needed by selected JUnit classes.

The persistent stamps are local acceleration hints. Every reuse checks both the
declared source bytes and every output byte; quarantined or unrecognised selections stop before running any producer.
The directory-dependent blanket preparation path is retired.
"""

import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import stat
import subprocess
import sys

import fast_inputs


MANIFEST = Path(".github/scripts/fast-fixtures.json")
STAMP_DIR = Path("build/fast/fixtures")
PROCESS_CORE_OUTPUTS = frozenset("build/process-lifecycle/core/" + name for name in (
    "manifest.json", "source.json", "pre.cbd", "post.cbd", "pre.audit.json", "post.audit.json",
    *[f"logs/{command}.{suffix}" for command in
      ("version", "libdir", "source-extract", "source-build", "unit", "imports", "pre-audit", "post-audit")
      for suffix in ("stdout", "stderr", "command.json")],
))
PROCESS_SIGNAL_OUTPUTS = frozenset('build/process-signals/' + name for name in (
    'manifest.json', 'oracle.txt', 'native-controls.txt',
))
# Compiler interface links are not consumed fixture payloads.
INTERMEDIATE_SUFFIXES = frozenset({".o", ".hi", ".dyn_o", ".dyn_hi"})
COMMON_SOURCES = (
    "src/driver/**/*.hs",
    "thc.cabal",
    "cabal.project",
    "Setup.hs",
    "Makefile",
    "src/compiler/THC/**/*.hs",
    "src/cbd/**/*.hs",
    "src/core-symbols/**/*.hs",
    "bin/build-compiler.sh",
    "bin/export-core.sh",
    "bin/toolchain.sh",
    "bin/plugin.py",
    # Shared dispatch and support; family producers belong to manifest sources.
    "t/haskell-fixtures/Main.hs",
    "t/haskell-fixtures/FixtureSupport.hs",
    "t/haskell-fixtures/AggregateFixtures.hs",
    "bin/audit-core.py",
    "bin/core_*.py",
    "bin/core-capabilities.json",
    "src/tools/primops/PrimopTools.hs",
    # The shared runtime ABI probe is also consumed by native fixture producers.
    "src/main/c/stdio-abi-probe.c",
    "src/main/resources/thc/scalar-primop-signatures.json",
    "src/main/resources/thc/core-native-overrides.json",
)


def _relative(value):
    path = Path(value)
    if not isinstance(value, str) or not value or path.is_absolute() or ".." in path.parts or "\x00" in value:
        raise ValueError(f"Unsafe fixture path: {value!r}")
    return path


def _digest(path):
    h = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def _manifest(root):
    data = json.loads((root / MANIFEST).read_text())
    if data.get("schema") != 1 or not isinstance(data.get("groups"), dict):
        raise ValueError("Invalid fast fixture manifest")
    owners = {}
    free = data.get("fixtureFreeJunit", [])
    if not isinstance(free, list):
        raise ValueError("Invalid fixture-free class inventory")
    for name in free:
        if not isinstance(name, str) or name in owners:
            raise ValueError(f"Duplicate/invalid fixture-free class: {name!r}")
        owners[name] = None
    for group_id, group in data["groups"].items():
        if not re.fullmatch(r"[a-z][a-z0-9-]*", group_id):
            raise ValueError(f"Invalid fixture group: {group_id!r}")
        if not all(isinstance(group.get(key), list) and group[key] for key in
                   ("junit", "commands", "outputs", "sources")):
            raise ValueError(f"Incomplete fixture group: {group_id}")
        for name in group["junit"]:
            if not isinstance(name, str) or name in owners:
                raise ValueError(f"Duplicate/invalid fixture class: {name!r}")
            owners[name] = group_id
        for path in [*group["outputs"], *group["sources"]]:
            _relative(path)
        systems = group.get("ciPlatforms", ["Linux", "Darwin", "Windows"])
        if (not isinstance(systems, list) or not systems or
                any(system not in ("Linux", "Darwin", "Windows") for system in systems) or
                len(systems) != len(set(systems))):
            raise ValueError(f"Invalid CI platforms: {group_id}")
        checks = group.get("ciChecks", [])
        if not isinstance(checks, list) or any(not isinstance(check, dict) or check.get("platform") != "Linux" for check in checks):
            raise ValueError(f"Invalid CI checks: {group_id}")
        for command in [*group["commands"], *checks]:
            argv = command.get("argv") if isinstance(command, dict) else None
            if not isinstance(argv, list) or not argv or not all(
                    isinstance(part, str) and part and "\x00" not in part for part in argv):
                raise ValueError(f"Invalid fixture command: {group_id}")
            if "stdout" in command:
                destination = _relative(command["stdout"])
                if not any(destination == _relative(output) or
                           _relative(output) in destination.parents for output in group["outputs"]):
                    raise ValueError(f"Undeclared fixture stdout: {group_id}")
    _group_order(data, data["groups"])
    return data, owners


def _group_order(manifest, selected):
    """Producer dependencies run first; ownership remains unique."""
    result, active = [], set()
    def visit(name):
        if name not in manifest["groups"]:
            raise ValueError(f"Unknown fixture dependency: {name}")
        if name in active:
            raise ValueError(f"Cyclic fixture dependency: {name}")
        if name in result:
            return
        required = manifest["groups"][name].get("requires", [])
        if not isinstance(required, list) or any(not isinstance(dep, str) for dep in required) or len(set(required)) != len(required):
            raise ValueError(f"Invalid fixture dependencies: {name}")
        active.add(name)
        for dep in sorted(required):
            visit(dep)
        active.remove(name)
        result.append(name)
    for name in sorted(selected):
        visit(name)
    return result


def _source_hashes(root, group):
    files = set()
    pinned = []
    if fast_inputs.WIRED_SOURCE in group["sources"]:
        # This explicit producer dependency brings its complete pinned source
        # catalog, including HSC, boot files, header and license, into the key.
        _, hashes = fast_inputs.wired_catalog(root)
        pinned = [fast_inputs.wired_source_path(path) for path in hashes]
    for pattern in (*COMMON_SOURCES, *group["sources"], *pinned):
        _relative(pattern)
        matches = list(root.glob(pattern))
        if not matches:
            raise RuntimeError(f"Missing fixture source: {pattern}")
        for path in matches:
            if path.is_symlink() or not path.is_file():
                raise RuntimeError(f"Unexpected fixture source: {path}")
            files.add(path.relative_to(root))
    return {str(path): _digest(root / path) for path in sorted(files)}


def cache_key(root, group_id, group, toolchain):
    """Return a source/toolchain identity without hashing the GHC executable."""
    root = Path(root).resolve()
    identity = {"schema": 1, "group": group_id, "definition": group,
                "sources": _source_hashes(root, group),
                "toolchain": toolchain}
    serialized = json.dumps(identity, sort_keys=True, separators=(",", ":"), allow_nan=False)
    return hashlib.sha256(serialized.encode()).hexdigest()


def _output_hashes(root, group):
    if group["outputs"] == ["build/process-signals"]:
        name = "build/process-signals/manifest.json"
        manifest = json.loads(fast_inputs.file_path(root, name).read_text())
        native = (platform.system(), platform.machine()) == ("Linux", "x86_64")
        if manifest.get("supported") is not native:
            raise ValueError("Process signal capture manifest does not match this host")
        if not native and manifest.get("artifactHashes") != {}:
            raise ValueError("Unsupported process signal capture must not claim native artifacts")
        outputs = PROCESS_SIGNAL_OUTPUTS if native else {name}
        return _output_hashes(root, {"outputs": sorted(outputs)})
    if group["outputs"] == ["build/package-native-gc-carriers"]:
        name = "build/package-native-gc-carriers/manifest.json"
        expected = fast_inputs.gc_carrier_artifact_hashes(root, json.loads(fast_inputs.file_path(root, name).read_text()))
        return _manifest_output_hashes(root, name, expected)
    if group["outputs"] == ["build/process-lifecycle/core"]:
        if not fast_inputs.LINUX_X86_64_HOST:
            return {}
        name = "build/process-lifecycle/core/manifest.json"
        manifest = json.loads(fast_inputs.file_path(root, name).read_text())
        expected = manifest.get("artifactHashes")
        if not isinstance(expected, dict) or set(expected) != PROCESS_CORE_OUTPUTS - {name}:
            raise ValueError("Incomplete process Core artifact inventory")
        source = json.loads(fast_inputs.file_path(root, "build/process-lifecycle/core/source.json").read_text())
        if source.get("archiveSha256") != "b431d2ba77607986fa84b42ff3021505b8637b8d638ff664be3292dd44aba8f0":
            raise ValueError("Wrong original process source archive")
        return _manifest_output_hashes(root, name, expected)
    for output, validator in (
            ("build/original-path-stat", fast_inputs.original_path_stat_artifact_hashes),
            ("build/original-path-mode", fast_inputs.original_path_mode_artifact_hashes),
            ("build/original-path-link", fast_inputs.original_path_link_artifact_hashes),
            ("build/original-directory-paths", fast_inputs.original_directory_paths_artifact_hashes),
            ("build/original-path-access", fast_inputs.original_path_access_artifact_hashes),
            ("build/original-unlinkat", fast_inputs.original_unlinkat_artifact_hashes),
            ("build/original-fstatat", fast_inputs.original_fstatat_artifact_hashes),
            ("build/original-current-directory", fast_inputs.original_current_directory_artifact_hashes),
            ("build/original-directory-streams", fast_inputs.original_directory_streams_artifact_hashes)):
        if output not in group["outputs"]:
            continue
        # Native pathname scratch includes symlinks and is deliberately not a
        # reusable fixture. Preserve the other POSIX fixture output inventories.
        remaining = [name for name in group["outputs"] if name != output]
        result = _output_hashes(root, {"outputs": remaining}) if remaining else {}
        if platform.system() == "Linux":
            name = output + "/manifest.json"
            expected = validator(json.loads(fast_inputs.file_path(root, name).read_text()))
            result.update(_manifest_output_hashes(root, name, expected))
        return result
    if group["outputs"] == ["build/thread-scheduling"]:
        name = "build/thread-scheduling/manifest.json"
        expected = fast_inputs.thread_scheduling_artifact_hashes(json.loads(fast_inputs.file_path(root, name).read_text()))
        return _manifest_output_hashes(root, name, expected)
    if group["outputs"] == ["build/simd-address-families"]:
        name = "build/simd-address-families/manifest.json"
        expected = fast_inputs.simd_address_artifact_hashes(json.loads(fast_inputs.file_path(root, name).read_text()))
        return _manifest_output_hashes(root, name, expected)
    if group["outputs"] == ["build/stable-names"]:
        name = "build/stable-names/manifest.json"
        expected = fast_inputs.stable_name_artifact_hashes(json.loads(fast_inputs.file_path(root, name).read_text()))
        return _manifest_output_hashes(root, name, expected)
    if group["outputs"] == ["build/scalar-memory-utilities"]:
        name = "build/scalar-memory-utilities/manifest.json"
        expected = fast_inputs.scalar_memory_artifact_hashes(json.loads(fast_inputs.file_path(root, name).read_text()))
        return _manifest_output_hashes(root, name, expected)
    if group["outputs"] == ["build/delimited-continuations"]:
        name = "build/delimited-continuations/manifest.json"
        expected = fast_inputs.delimited_artifact_hashes(json.loads(fast_inputs.file_path(root, name).read_text()))
        return _manifest_output_hashes(root, name, expected)
    if group["outputs"] == ["build/ghc-bco"]:
        name = "build/ghc-bco/manifest.json"
        expected = fast_inputs.bco_artifact_hashes(json.loads(fast_inputs.file_path(root, name).read_text()))
        return _manifest_output_hashes(root, name, expected)
    if group["outputs"] == ["build/thread-inventory"]:
        name = "build/thread-inventory/manifest.json"
        expected = fast_inputs.thread_inventory_artifact_hashes(json.loads(fast_inputs.file_path(root, name).read_text()))
        return _manifest_output_hashes(root, name, expected)
    family = group["outputs"][0].removeprefix("build/")
    if family in fast_inputs.SIMD_BYTEARRAY_FAMILIES:
        name = f"build/{family}/provenance.json"
        manifest = json.loads(fast_inputs.file_path(root, name).read_text())
        expected = fast_inputs.simd_bytearray_artifact_hashes(family, manifest)
        return _manifest_output_hashes(root, name, expected)
    if family in fast_inputs.BYTEARRAY_FAMILIES:
        name = f"build/{family}/manifest.json"
        manifest = json.loads(fast_inputs.file_path(root, name).read_text())
        expected = dict(fast_inputs.bytearray_artifact_hashes(family, manifest))
        return _manifest_output_hashes(root, name, expected)
    if group["outputs"][0] == "build/float-decode":
        name = "build/float-decode/manifest.json"
        manifest = json.loads(fast_inputs.file_path(root, name).read_text())
        expected = manifest.get("artifactHashes")
        fast_inputs.require(isinstance(expected, dict) and
                            set(expected) == fast_inputs.FLOAT_DECODE_OUTPUTS - {name},
                            "Incomplete floating decode fixture inventory")
        return _manifest_output_hashes(root, name, expected)
    if group["outputs"] == ["build/pinned-addresses"]:
        name = "build/pinned-addresses/manifest.json"
        expected = fast_inputs.pinned_address_artifact_hashes(json.loads(fast_inputs.file_path(root, name).read_text()))
        return _manifest_output_hashes(root, name, expected)
    if group["outputs"] == ["build/rts-diagnostics"]:
        name = "build/rts-diagnostics/manifest.json"
        expected = fast_inputs.rts_diagnostic_artifact_hashes(json.loads(fast_inputs.file_path(root, name).read_text()))
        return _manifest_output_hashes(root, name, expected)
    if group["outputs"] == ["build/rts-shutdown"]:
        name = "build/rts-shutdown/manifest.json"
        expected = fast_inputs.rts_shutdown_artifact_hashes(json.loads(fast_inputs.file_path(root, name).read_text()))
        return _manifest_output_hashes(root, name, expected)
    if group["outputs"] == ["build/original-rts-locks"]:
        name = "build/original-rts-locks/manifest.json"
        expected = fast_inputs.rts_lock_artifact_hashes(json.loads(fast_inputs.file_path(root, name).read_text()))
        return _manifest_output_hashes(root, name, expected)
    if group["outputs"] == ["build/original-fd-ready"]:
        name = "build/original-fd-ready/manifest.json"
        expected = fast_inputs.fd_ready_artifact_hashes(json.loads(fast_inputs.file_path(root, name).read_text()))
        return _manifest_output_hashes(root, name, expected)
    if group["outputs"] == ["build/original-open"]:
        name = "build/original-open/manifest.json"
        expected = fast_inputs.original_open_artifact_hashes(json.loads(fast_inputs.file_path(root, name).read_text()))
        return _manifest_output_hashes(root, name, expected)
    if group["outputs"] == ["build/original-errno"]:
        name = "build/original-errno/manifest.json"
        expected = fast_inputs.errno_artifact_hashes(json.loads(fast_inputs.file_path(root, name).read_text()))
        return _manifest_output_hashes(root, name, expected)
    if group["outputs"] == ["build/original-process-identity"]:
        name = "build/original-process-identity/manifest.json"
        expected = fast_inputs.process_identity_artifact_hashes(json.loads(fast_inputs.file_path(root, name).read_text()))
        return _manifest_output_hashes(root, name, expected)
    if group["outputs"] == ["build/original-termios"]:
        name = "build/original-termios/manifest.json"
        expected = fast_inputs.termios_artifact_hashes(json.loads(fast_inputs.file_path(root, name).read_text()))
        return _manifest_output_hashes(root, name, expected)
    if group["outputs"] == ["build/original-tcsetattr"]:
        name = "build/original-tcsetattr/manifest.json"
        expected = fast_inputs.tcsetattr_artifact_hashes(json.loads(fast_inputs.file_path(root, name).read_text()))
        return _manifest_output_hashes(root, name, expected)
    if group["outputs"] == ["build/original-tcgetattr"]:
        name = "build/original-tcgetattr/manifest.json"
        expected = fast_inputs.tcgetattr_artifact_hashes(json.loads(fast_inputs.file_path(root, name).read_text()))
        return _manifest_output_hashes(root, name, expected)
    files = set()
    for output in group["outputs"]:
        path = root / _relative(output)
        if path.is_symlink() or not path.exists():
            raise FileNotFoundError(f"Missing fixture output: {path}")
        path = fast_inputs.file_path(root, output)
        if path.is_file():
            files.add(path.relative_to(root))
        elif path.is_dir():
            members = list(path.rglob("*"))
            if not members:
                raise FileNotFoundError(f"Empty fixture output: {path}")
            for member in members:
                # export-boot overlays installed GHC interfaces with symlinks.
                # These compiler intermediates are not executable test inputs.
                if member.is_symlink() and member.suffix in INTERMEDIATE_SUFFIXES:
                    continue
                if member.is_symlink() or not (member.is_file() or member.is_dir()):
                    raise RuntimeError(f"Unexpected fixture output: {member}")
                if member.is_file():
                    files.add(member.relative_to(root))
        else:
            raise RuntimeError(f"Unexpected fixture output: {path}")
    if not files:
        raise FileNotFoundError("Fixture outputs contain no files")
    if Path("build/thc-fixtures.path") in files:
        _require_prepared_encoder(root)
    return {str(path): _digest(root / path) for path in sorted(files)}


def _require_prepared_encoder(root):
    executable = (root / "build/thc-fixtures.path").read_text().strip()
    if not executable or not (root / executable).is_file():
        raise FileNotFoundError(f"Missing prepared fixture executable: {executable}")


def _manifest_output_hashes(root, name, expected):
    path = fast_inputs.file_path(root, name)
    result = {name: _digest(path)}
    for artifact, recorded in sorted(expected.items()):
        actual = _digest(fast_inputs.file_path(root, artifact))
        if actual != recorded:
            raise RuntimeError("Stale original artifact: " + artifact)
        result[artifact] = actual
    return result


def _write_stamp(path, stamp):
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(".json.tmp")
    temporary.write_text(json.dumps(stamp, indent=2, sort_keys=True) + "\n")
    temporary.replace(path)


def quarantined_classes(root):
    """Classes withheld from execution, not reported as passing or deferred."""
    path = Path(root) / MANIFEST
    if not path.is_file():
        return set()  # The standalone selector also supports non-THC test repos.
    manifest, owners = _manifest(Path(root))
    blocked = {name for name, group in manifest["groups"].items() if group.get("quarantined")}
    return set(manifest.get("quarantinedJunit", [])) | {
        name for name, owner in owners.items() if owner is not None
        and blocked.intersection(_group_order(manifest, [owner]))}


def prepare(root, selection, run, toolchain):
    """Prepare selected JUnit fixtures; return {mode, rebuilt, reused}.

    ``run(name, argv, stdout=None)`` runs a command from the repository root.
    ``stdout`` is an optional repository-relative output file. Its parent is
    created here before invoking the command.
    """
    root = Path(root).resolve()
    manifest, owners = _manifest(root)
    classes = selection["junit"]["classes"]
    if not isinstance(classes, list) or not classes or not all(isinstance(name, str) for name in classes):
        raise ValueError("Invalid selected JUnit classes")
    if any(name not in owners for name in classes):
        raise ValueError("Blanket fixture preparation is quarantined; select an exact nonquarantined test class")
    if selection.get("mode") not in ("narrow", "full"):
        raise ValueError("Invalid selected test mode")

    withheld = set(classes) & set(manifest.get("quarantinedJunit", []))
    if withheld:
        raise ValueError("Quarantined tests cannot run: " + ", ".join(sorted(withheld))
                         + "; see docs/fixture-quarantine.log")

    groups = _group_order(manifest, {owners[name] for name in classes if owners[name] is not None})
    blocked = [name for name in groups if manifest["groups"][name].get("quarantined")]
    if blocked:
        raise ValueError("Quarantined fixtures cannot run: " + ", ".join(blocked)
                         + "; see docs/fixture-quarantine.log")
    def classify():
        state = []
        for group_id in groups:
            group = manifest["groups"][group_id]
            key = cache_key(root, group_id, group, toolchain)
            stamp_path = root / STAMP_DIR / (group_id + ".json")
            try:
                stamp = json.loads(stamp_path.read_text())
                reusable = (isinstance(stamp, dict) and stamp.get("schema") == 1 and
                            stamp.get("key") == key and stamp.get("outputs") == _output_hashes(root, group))
            except (FileNotFoundError, ValueError, OSError, RuntimeError):
                reusable = False
            state.append((group_id, group, key, stamp_path, reusable))
        return state

    state = classify()
    if any(not reusable for _, _, _, _, reusable in state):
        # All callers, including the CI recorder, must use the same invocation
        # paths as build-compiler.sh. Resolving symlink targets changes Cabal's
        # configuration identity even when they name the same compiler.
        os.environ.setdefault("GHC_ENVIRONMENT", "-")
        ghc, pkg = subprocess.check_output(
            ["sh", "-c", '. ./bin/toolchain.sh; printf "%s\\n" "$GHC" "$GHC_PKG"'],
            cwd=root, text=True).splitlines()
        os.environ.update(GHC=ghc, GHC_PKG=pkg)
        cabal = os.environ.get("CABAL", "cabal")
        cabal_options = ["--with-compiler=" + ghc, "--with-hc-pkg=" + pkg]
        run("fixture-scalar-signatures", [cabal, "run", *cabal_options, "exe:thc-primops", "--", "scalars"])
        run("fixture-compiler", ["bin/build-compiler.sh"])
        # A preparatory command may have updated a declared source. Never skip
        # a previously reusable group on an identity calculated before it ran.
        state = classify()
    rebuilt, reused = [], []
    for group_id, group, key, stamp_path, reusable in state:
        if reusable:
            reused.append(group_id)
            continue
        stamp_path.unlink(missing_ok=True)
        for index, command in enumerate(group["commands"]):
            stdout = command.get("stdout")
            if stdout is not None:
                (root / _relative(stdout)).parent.mkdir(parents=True, exist_ok=True)
            argv = command["argv"]
            if argv[0] == "cabal":
                argv = [cabal, argv[1], *cabal_options, *argv[2:]]
            run(f"fixture-{group_id}-{index:02d}", argv, stdout=stdout)
        _write_stamp(stamp_path, {"schema": 1, "key": key,
                                  "outputs": _output_hashes(root, group)})
        rebuilt.append(group_id)
    return {"mode": "selected", "rebuilt": rebuilt, "reused": reused}


def prepare_cmake(root, selection, run):
    """Select graph targets; Ninja, not directory receipts, owns freshness."""
    root = Path(root).resolve()
    manifest, owners = _manifest(root)
    classes = selection["junit"]["classes"]
    if not classes or any(name not in owners for name in classes):
        raise ValueError("Select exact known test classes for the fixture graph")
    blocked = set(classes) & quarantined_classes(root)
    if blocked:
        raise ValueError("Quarantined tests cannot run: " + ", ".join(sorted(blocked)))
    groups = _group_order(manifest, {owners[name] for name in classes if owners[name] is not None})
    pending = [name for name in groups if not manifest["groups"][name].get("cmakeTarget")]
    if pending:
        raise ValueError("Fixture file rules are not yet migrated: " + ", ".join(pending)
                         + "; see docs/fixture-inputs.log. No legacy preparation was run.")
    targets = [manifest["groups"][name]["cmakeTarget"] for name in groups]
    if targets:
        configure = ["cmake", "-S", ".", "-B", "build/fixtures", "-G", "Ninja"]
        for tool in ("GHC", "GHC_PKG", "CABAL"):
            if os.environ.get(tool):
                configure.append("-D" + tool + "=" + os.environ[tool])
        run("fixture-configure", configure)
        run("fixture-build", ["cmake", "--build", "build/fixtures", "--parallel", "2", "--target", *targets])
    return {"mode": "cmake", "targets": targets}


def local_selection(selector, owners):
    # Wildcards can also match method names in unrelated classes. Without
    # Gradle's discovered method inventory, use full preparation for them.
    if "*" in selector:
        return {"mode": "full", "junit": {"classes": [selector]}}
    classes = []
    for name in owners:
        candidate = name.rsplit(".", 1)[-1] if selector[:1].isupper() else name
        if selector == candidate or selector.startswith(candidate + "."):
            classes.append(name)
    return {"mode": "narrow" if classes else "full",
            "junit": {"classes": sorted(classes) or [selector]}}


def main():
    parser = argparse.ArgumentParser(description="Prepare fixtures for a Gradle test selector.")
    parser.add_argument("--tests", required=True)
    parser.add_argument("--cmake", action="store_true", help="Use the explicit file graph; reject unmigrated recipes")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    _, owners = _manifest(root)
    selection = local_selection(args.tests, owners)
    if selection["mode"] == "full":
        parser.error("Blanket fixture preparation is quarantined; select an exact nonquarantined test class")
    blocked = set(selection["junit"]["classes"]) & quarantined_classes(root)
    if blocked:
        parser.error("Quarantined tests cannot run: " + ", ".join(sorted(blocked))
                     + "; see docs/fixture-quarantine.log")
    if selection["mode"] == "narrow" and all(owners[name] is None for name in selection["junit"]["classes"]):
        print("Selected tests need no generated fixtures.")
        return
    def run(name, argv, stdout=None):
        print("+ " + repr(argv), flush=True)
        if stdout is None:
            subprocess.run(argv, cwd=root, check=True)
        else:
            with (root / stdout).open("w") as output:
                subprocess.run(argv, cwd=root, stdout=output, check=True)
    if args.cmake:
        print(prepare_cmake(root, selection, run))
        return
    # A developer's ambient package environment is not a fixture dependency.
    os.environ.setdefault("GHC_ENVIRONMENT", "-")
    ghc, pkg = subprocess.check_output(
        ["sh", "-c", '. ./bin/toolchain.sh; printf "%s\\n" "$GHC" "$GHC_PKG"'],
        cwd=root, text=True).splitlines()
    os.environ.update(GHC=ghc, GHC_PKG=pkg)
    toolchain = {"platform": {"system": platform.system(), "machine": platform.machine()},
                 "toolchain": fast_inputs.toolchain(root)}
    print(prepare(root, selection, run, toolchain))


if __name__ == "__main__":
    main()
