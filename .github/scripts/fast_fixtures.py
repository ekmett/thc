# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

"""Prepare only native fixtures needed by selected JUnit classes.

The persistent stamps are local acceleration hints. Every reuse checks both the
declared source bytes and every output byte; an unrecognised class runs the
complete preparation script instead of assuming it has no native inputs.
"""

import hashlib
import json
from pathlib import Path
import platform
import re
import stat
import sys

import fast_inputs


MANIFEST = Path(".github/scripts/fast-fixtures.json")
STAMP_DIR = Path("build/fast/fixtures")
FULL_STAMP = STAMP_DIR / "full.json"
# The shebang and non-comment command body of reviewed prepare-tests.sh. A new
# preparation command disables reuse until its output scope is reviewed.
FULL_PREPARATION_PLAN = "9d85272606811ad09d28048f36780426911cf71e44f2212716a22b2a3aa9b292"
FULL_OUTPUT_ROOTS = frozenset(f"build/{name}" for name in fast_inputs.BUILD_DIRS) | frozenset({
    "build/addr-identity", "build/io-main-pap", "build/managed-mvars", "build/managed-md5-native",
    "build/pinned-addresses", "build/pinned-pointer-cells", "build/simd-capability-smoke", "build/managed-address-reads",
    "build/original-stdio", "build/original-stdio-read", "build/original-stdio-close", "build/original-stdio-seek", "build/original-stdio-truncate", "build/original-handle-readiness", "build/core-continuation", "build/live-async", "build/thread-async", "build/thread-status", "build/uncaught-self", "build/small-arrays", "build/floating-address",
    "build/floating-byte-offset", "build/narrow-byte-offset", "build/int32-byte-offset",
    "build/explicit64-arrays", "build/mask-functions", "build/interface-core",
    "build/original-fd-ready", "build/simd-calls",
})
FULL_REQUIRED = frozenset(fast_inputs.REQUIRED) | frozenset({
    "build/simd-calls/manifest.json", "build/simd-calls/pre-core/SimdCallAudit.json",
    "build/simd-calls/pre-audit.json",
    *([] if platform.machine().lower() in ("arm64", "aarch64") else
      ["build/simd-calls/oracle.tsv", "build/simd-calls/post-core/SimdCallAudit.json",
       "build/simd-calls/post-audit.json"]),
    "build/interface-core/manifest.json", "build/interface-core/InterfaceLibrary.json",
    "build/interface-core/logs/native-oracle.stdout", "build/interface-core/native/oracle",
    "build/interface-core/full/InterfaceLibrary.hi", "build/interface-core/thin/InterfaceLibrary.hi",
    "build/interface-core/full/InterfaceLibrary.dyn_hi", "build/interface-core/full/InterfaceForeign.hi",
    "build/interface-core/source/InterfaceLibrary.saved",
    "build/interface-core/opaqueEntry-audit.json", "build/interface-core/inlineEntry-audit.json",
    "build/interface-core/recursiveEntry-audit.json",
    "build/interface-core/wrapperEntry-audit.json", "build/interface-core/installed-wrapper-facts.json",
    "build/interface-core/CBVCoercionAudit.json", "build/interface-core/direct/CBVCoercionAudit.json",
    "build/interface-core/full/CBVCoercionAudit.hi", "build/interface-core/thin/CBVCoercionAudit.hi",
    "build/interface-core/source/CBVCoercionAudit.saved", "build/interface-core/coercionEntry-audit.json",
    "build/interface-core/logs/helper-thin.stdout", "build/interface-core/logs/helper-thin.command.json",
    "build/interface-core/wired-unit.json", "build/interface-core/logs/helper-wired-unit.stdout",
    "build/interface-core/logs/helper-wired-unit.command.json",
    "build/interface-core/packages.json", "build/interface-core/driver-controls.json",
    "build/interface-core/InterfaceForeign.json", "build/interface-core/foreign-packages.json",
    "build/interface-core/foreign-association.json", "build/interface-core/installed-bound-facts.json",
    "build/interface-core/foreign-alias/a.json", "build/interface-core/foreign-alias/b.json",
    "build/interface-core/source/InterfaceForeignAlias.hs.saved",
    "build/addr-identity/oracle.txt", "build/addr-identity/pre.audit.json", "build/addr-identity/post.audit.json",
    "build/core-continuation/core/CoreContinuationAudit.json", "build/core-continuation/audit.json",
    "build/core-continuation/application-audit.json",
    "build/core-continuation/nested-audit.json",
    "build/core-continuation/native-output.txt",
    "build/core-continuation/core/LazyIOCallbackAudit.json",
    "build/core-continuation/lazy-native-output.txt",
    "build/core-continuation/keep-alive-scalar-audit.json",
    "build/core-continuation/keep-alive-tuple-audit.json",
    "build/core-continuation/lazy-action-audit.json", "build/core-continuation/lazy-handler-audit.json",
    "build/live-async/manifest.json", "build/live-async/oracle.txt", "build/live-async/strict-oracle.txt",
    "build/live-async/pre/core/LiveAsyncAudit.json", "build/live-async/post/core/LiveAsyncAudit.json",
    "build/live-async/pre/forceShared-audit.json", "build/live-async/post/forceShared-audit.json",
    "build/live-async/pre/strictWorker-audit.json", "build/live-async/post/strictWorker-audit.json",
    "build/live-async/pre/strictCall-audit.json", "build/live-async/post/strictCall-audit.json",
    "build/live-async/pre/strictEntry-audit.json", "build/live-async/post/strictEntry-audit.json",
    "build/live-async/pre/takeReady-audit.json", "build/live-async/post/takeReady-audit.json",
    "build/live-async/pre/takeRunning-audit.json", "build/live-async/post/takeRunning-audit.json",
    "build/live-async/pre/releaseGate-audit.json", "build/live-async/post/releaseGate-audit.json",
    "build/live-async/pre/prefixCount-audit.json", "build/live-async/post/prefixCount-audit.json",
    "build/live-async/pre/warmLoop-audit.json", "build/live-async/post/warmLoop-audit.json",
    "build/live-async/pre/asyncPayload-audit.json", "build/live-async/post/asyncPayload-audit.json",
    "build/thread-status/manifest.json", "build/thread-status/oracle.txt",
    *[f"build/thread-status/{stage}/{suffix}" for stage in ("pre", "post")
      for suffix in ("core/ThreadStatusAudit.json", "selfStatus-audit.json", "maskedStatus-audit.json",
                     "finishedStatus-audit.json", "diedStatus-audit.json", "blockedStatus-audit.json")],
    "build/thread-async/manifest.json", "build/thread-async/oracle.txt", "build/thread-async/extra-oracle.txt",
    "build/thread-async/lazy-oracle.txt",
    "build/thread-async/pre/core/ThreadAsyncAudit.json", "build/thread-async/post/core/ThreadAsyncAudit.json",
    "build/thread-async/pre/core/LazyForkAudit.json", "build/thread-async/post/core/LazyForkAudit.json",
    "build/thread-async/pre/lazyFork-audit.json", "build/thread-async/post/lazyFork-audit.json",
    "build/thread-async/pre/forkAndThrow-audit.json", "build/thread-async/post/forkAndThrow-audit.json",
    "build/thread-async/pre/killUncaught-audit.json", "build/thread-async/post/killUncaught-audit.json",
    "build/thread-async/pre/selfThrow-audit.json", "build/thread-async/post/selfThrow-audit.json",
    "build/thread-async/pre/maskedUnmaskSelf-audit.json", "build/thread-async/post/maskedUnmaskSelf-audit.json",
    "build/uncaught-self/manifest.json", "build/uncaught-self/native/oracle",
    "build/uncaught-self/pre/core/UncaughtSelfAudit.json", "build/uncaught-self/post/core/UncaughtSelfAudit.json",
    "build/uncaught-self/pre/audit.json", "build/uncaught-self/post/audit.json",
    "build/uncaught-self/pre/io-audit.json", "build/uncaught-self/post/io-audit.json",
    "build/mask-functions/manifest.json",
    "build/mask-functions/pre/core/MaskFunctionAudit.json",
    "build/mask-functions/post/core/MaskFunctionAudit.json",
    "build/mask-functions/logs/native-oracle.stdout",
    "build/addr-identity/pre-core/AddressIdentityAudit.json", "build/addr-identity/post-core/AddressIdentityAudit.json",
    "build/io-main-pap/provenance.json", "build/managed-mvars/manifest.json", "build/managed-md5-native/provenance.json",
    "build/pinned-addresses/manifest.json",
    "build/pinned-pointer-cells/manifest.json",
    "build/pinned-pointer-cells/oracle.tsv", "build/pinned-pointer-cells/pre/audit.json",
    "build/pinned-pointer-cells/post/audit.json",
    "build/pinned-pointer-cells/pre/core/PinnedPointerCellsAudit.json",
    "build/pinned-pointer-cells/post/core/PinnedPointerCellsAudit.json",
    "build/floating-address/manifest.json", "build/floating-address/oracle.tsv",
    "build/floating-address/pre/audit.json", "build/floating-address/post/audit.json",
    "build/floating-address/pre/core/FloatingAddressAudit.json",
    "build/floating-address/post/core/FloatingAddressAudit.json",
    "build/floating-byte-offset/manifest.json", "build/floating-byte-offset/oracle.tsv",
    "build/floating-byte-offset/pre/audit.json", "build/floating-byte-offset/post/audit.json",
    "build/floating-byte-offset/pre/core/FloatingByteOffsetAudit.json",
    "build/floating-byte-offset/post/core/FloatingByteOffsetAudit.json",
    "build/narrow-byte-offset/manifest.json", "build/narrow-byte-offset/oracle.tsv",
    "build/narrow-byte-offset/pre/audit.json", "build/narrow-byte-offset/post/audit.json",
    "build/narrow-byte-offset/pre/core/NarrowByteOffsetAudit.json",
    "build/narrow-byte-offset/post/core/NarrowByteOffsetAudit.json",
    "build/int32-byte-offset/manifest.json", "build/int32-byte-offset/oracle.tsv",
    "build/int32-byte-offset/pre/audit.json", "build/int32-byte-offset/post/audit.json",
    "build/int32-byte-offset/pre/core/Int32ByteOffsetAudit.json",
    "build/int32-byte-offset/post/core/Int32ByteOffsetAudit.json",
    "build/explicit64-arrays/manifest.json", "build/explicit64-arrays/oracle.tsv",
    "build/shrink-bytearrays/manifest.json", "build/shrink-bytearrays/oracle.tsv",
    "build/shrink-bytearrays/pre/core/ShrinkMutableByteArrayAudit.json",
    "build/shrink-bytearrays/post/core/ShrinkMutableByteArrayAudit.json",
    "build/shrink-bytearrays/pre/core/THC.InterfaceClosure.json",
    "build/shrink-bytearrays/post/core/THC.InterfaceClosure.json",
    "build/fetch-add-int-array/manifest.json", "build/fetch-add-int-array/oracle.tsv",
    "build/fetch-add-int-array/pre/core/FetchAddIntArrayAudit.json",
    "build/fetch-add-int-array/post/core/FetchAddIntArrayAudit.json",
    "build/fetch-add-int-array/pre/core/THC.InterfaceClosure.json",
    "build/fetch-add-int-array/post/core/THC.InterfaceClosure.json",
    "build/explicit64-arrays/pre/audit.json", "build/explicit64-arrays/post/audit.json",
    "build/explicit64-arrays/pre/core/Explicit64ArrayAudit.json",
    "build/explicit64-arrays/post/core/Explicit64ArrayAudit.json",
    "build/managed-address-reads/manifest.json",
    "build/original-stdio/manifest.json",
    "build/original-stdio-read/manifest.json", "build/original-stdio-read/oracle.json",
    "build/original-stdio-close/manifest.json", "build/original-stdio-close/oracle.json",
    "build/original-posix-dup/manifest.json", "build/original-posix-dup/oracle.json",
    "build/original-stdio-seek/manifest.json", "build/original-stdio-seek/oracle.json",
    "build/original-stdio-truncate/manifest.json", "build/original-stdio-truncate/oracle.json",
    *fast_inputs.ORIGINAL_FD_READY_OUTPUTS,
    *fast_inputs.ORIGINAL_RTS_LOCK_OUTPUTS,
    *(fast_inputs.ORIGINAL_OPEN_OUTPUTS if fast_inputs.GMP_NATIVE_HOST else {"build/original-open/manifest.json"}),
    *(fast_inputs.ORIGINAL_TERMIOS_OUTPUTS if fast_inputs.GMP_NATIVE_HOST else {"build/original-termios/manifest.json"}),
    "build/original-handle-readiness/manifest.json",
    "build/small-arrays/manifest.json",
    "build/simd-capability-smoke/manifest.json",
    # Smoke manifests hash generated Haskell inputs, independently of JVM codegen.
    *fast_inputs.SIMD_SMOKE_SOURCES,
})
# Compiler interfaces/objects and Gradle products are not consumed by JUnit;
# full receipt reuse checks the final Core/native fixture data instead.
INTERMEDIATE_SUFFIXES = frozenset({".o", ".hi", ".dyn_o", ".dyn_hi"})
# The reviewed preparation plan emits no fixture under build/generated; Gradle
# writes JVM products there. A future fixture there requires a plan/output review.
NON_FIXTURE_BUILD_ROOTS = frozenset({
    "aggregate-ghc", "aggregate-post-ghc", "cbv-post-ghc", "classes", "compiler",
    "fast", "generated", "ghc", "kotlin", "libs", "reports", "resources",
    "snapshot", "source-ghc", "test-results", "tmp",
})
COMMON_SOURCES = (
    "thc.cabal",
    "cabal.project",
    "Setup.hs",
    "Makefile",
    "compiler/THC/**/*.hs",
    "compiler/build.sh",
    "compiler/export.sh",
    "compiler/toolchain.sh",
    "compiler/plugin.py",
    "test/haskell-fixtures/**/*.hs",
    "scripts/audit-core.py",
    "scripts/core_*.py",
    "scripts/core-capabilities.json",
    "scripts/generate-scalar-signatures.py",
    "src/main/resources/thc/scalar-primop-signatures.json",
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
        for command in group["commands"]:
            argv = command.get("argv") if isinstance(command, dict) else None
            if not isinstance(argv, list) or not argv or not all(
                    isinstance(part, str) and part and "\x00" not in part for part in argv):
                raise ValueError(f"Invalid fixture command: {group_id}")
            if "stdout" in command:
                destination = _relative(command["stdout"])
                if not any(destination == _relative(output) or
                           _relative(output) in destination.parents for output in group["outputs"]):
                    raise ValueError(f"Undeclared fixture stdout: {group_id}")
    return data, owners


def _source_hashes(root, group):
    files = set()
    pinned = []
    if fast_inputs.WIRED_SOURCE in group["sources"]:
        # This explicit producer dependency brings its complete pinned source
        # catalog, including HSC, boot files, header and license, into the key.
        _, hashes = fast_inputs.wired_catalog(root)
        pinned = ["compiler/pinned-ghc-internal/" + path for path in hashes]
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
    if group["outputs"] == ["build/original-stack-formatter"]:
        return _formatter_output_hashes(root)
    if group["outputs"] == ["build/original-gmp"]:
        return _gmp_output_hashes(root)
    if group["outputs"] == ["build/original-rts-locks"]:
        name = "build/original-rts-locks/manifest.json"
        expected = fast_inputs.rts_lock_artifact_hashes(json.loads(fast_inputs.file_path(root, name).read_text()))
        return _manifest_output_hashes(root, name, expected)
    if group["outputs"] == ["build/original-open"]:
        name = "build/original-open/manifest.json"
        expected = fast_inputs.original_open_artifact_hashes(json.loads(fast_inputs.file_path(root, name).read_text()))
        return _manifest_output_hashes(root, name, expected)
    if group["outputs"] == ["build/original-termios"]:
        name = "build/original-termios/manifest.json"
        expected = fast_inputs.termios_artifact_hashes(json.loads(fast_inputs.file_path(root, name).read_text()))
        return _manifest_output_hashes(root, name, expected)
    files = set()
    for output in group["outputs"]:
        path = root / _relative(output)
        if path.is_symlink() or not path.exists():
            raise FileNotFoundError(f"Missing fixture output: {path}")
        if path.is_file():
            files.add(path.relative_to(root))
        elif path.is_dir():
            members = list(path.rglob("*"))
            if not members:
                raise FileNotFoundError(f"Empty fixture output: {path}")
            for member in members:
                if member.is_symlink() or not (member.is_file() or member.is_dir()):
                    raise RuntimeError(f"Unexpected fixture output: {member}")
                if member.is_file():
                    files.add(member.relative_to(root))
        else:
            raise RuntimeError(f"Unexpected fixture output: {path}")
    if not files:
        raise FileNotFoundError("Fixture outputs contain no files")
    return {str(path): _digest(root / path) for path in sorted(files)}


def _formatter_output_hashes(root):
    # The source exporter creates a symlink overlay of installed interfaces.
    # Only the manifest's exact reviewed artifacts are reusable fixture data.
    name = "build/original-stack-formatter/manifest.json"
    path = fast_inputs.file_path(root, name)
    manifest = json.loads(path.read_text())
    expected = fast_inputs.formatter_artifact_hashes(root, manifest)
    result = {name: _digest(path)}
    for artifact, recorded in sorted(expected.items()):
        actual = _digest(fast_inputs.file_path(root, artifact))
        if actual != recorded:
            raise RuntimeError("Stale formatter artifact: " + artifact)
        result[artifact] = actual
    return result


def _gmp_output_hashes(root):
    # Never traverse or fingerprint the test-local package database or installed
    # interfaces. The receipt names every consumed artifact, including the
    # exposed registration as inert provenance, with an exact closed inventory.
    name = "build/original-gmp/manifest.json"
    path = fast_inputs.file_path(root, name)
    expected = fast_inputs.gmp_artifact_hashes(json.loads(path.read_text()))
    return _manifest_output_hashes(root, name, expected)


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


def _preparation_plan(root):
    lines = [line.rstrip() for index, line in enumerate(
        (root / "scripts/prepare-tests.sh").read_text().splitlines())
        if line.strip() and (index == 0 or not line.lstrip().startswith("#"))]
    return hashlib.sha256(("\n".join(lines) + "\n").encode()).hexdigest()


def _full_key(root):
    if _preparation_plan(root) != FULL_PREPARATION_PLAN:
        raise RuntimeError("Full preparation commands have not been reviewed for receipt reuse")
    pins = fast_inputs.vendor_pins(root)
    vendor_root = root / "vendor/ghc-9.14.1"
    present = {}
    if vendor_root.exists():
        for path in vendor_root.rglob("*"):
            if path.is_symlink() or not (path.is_file() or path.is_dir()):
                raise RuntimeError("Unexpected vendored GHC source")
            if path.is_file():
                name = path.relative_to(root).as_posix()
                if name not in pins:
                    raise RuntimeError("Unpinned vendored GHC source")
                present[name] = _digest(path)
    for name, actual in present.items():
        if actual != pins[name]:
            raise RuntimeError("Vendored GHC source differs from its pinned hash")
    extra = ("build.gradle.kts", "thc.cabal", "cabal.project", "Setup.hs",
             ".github/scripts/fast-fixtures.json",
             ".github/scripts/fast_fixtures.py")
    value = {"schema": 1, "identity": fast_inputs.identity(root),
             "declaration": {"plan": FULL_PREPARATION_PLAN,
                             "roots": sorted(FULL_OUTPUT_ROOTS),
                             "required": sorted(FULL_REQUIRED)},
             "extraSources": {name: _digest(root / name) for name in extra},
             "vendor": present}
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(",", ":")).encode()).hexdigest()


def _full_output_hashes(root):
    build = root / "build"
    allowed = {Path(name).name for name in FULL_OUTPUT_ROOTS} | NON_FIXTURE_BUILD_ROOTS
    top_files = {Path(name).name for name in FULL_REQUIRED if Path(name).parent == Path("build")}
    for path in build.iterdir():
        if path.is_symlink() or path.name not in (allowed if path.is_dir() else top_files):
            raise RuntimeError(f"Unreviewed generated output: {path.relative_to(root)}")
    files = set(FULL_REQUIRED)
    for name in FULL_OUTPUT_ROOTS:
        path = root / name
        if not path.exists():
            continue
        if path.is_symlink() or not path.is_dir():
            raise RuntimeError(f"Unexpected full fixture root: {name}")
        if name == "build/original-stack-formatter":
            files.update(_formatter_output_hashes(root))
            continue
        if name == "build/original-gmp":
            if fast_inputs.GMP_NATIVE_HOST:
                files.update(_gmp_output_hashes(root))
            continue
        if name in ("build/original-rts-locks", "build/original-open", "build/original-termios"):
            files.update(_output_hashes(root, {"outputs": [name]}))
            continue
        for member in path.rglob("*"):
            # GHC's output directories can contain links to installed package
            # interfaces. They are not fixture inputs and are never followed.
            if member.is_symlink() and member.suffix in INTERMEDIATE_SUFFIXES:
                continue
            if member.is_symlink() or not (member.is_file() or member.is_dir()):
                raise RuntimeError(f"Unexpected full fixture output: {member}")
            if member.is_file() and member.suffix not in INTERMEDIATE_SUFFIXES:
                files.add(member.relative_to(root).as_posix())
    if len(files) > fast_inputs.MAX_FILES:
        raise RuntimeError("Too many full fixture outputs")
    result, total = {}, 0
    for name in sorted(files):
        path = fast_inputs.file_path(root, name)
        if not path.is_file():
            raise FileNotFoundError(f"Missing full fixture output: {name}")
        details = path.stat()
        if details.st_size > fast_inputs.MAX_FILE_BYTES:
            raise RuntimeError(f"Oversized full fixture output: {name}")
        total += details.st_size
        if total > fast_inputs.MAX_TOTAL_BYTES:
            raise RuntimeError("Full fixture outputs exceed the reviewed bound")
        result[name] = {"sha256": _digest(path), "mode": stat.S_IMODE(details.st_mode)}
    return result


def _prepare_full(root, run):
    stamp_path = root / FULL_STAMP
    try:
        key = _full_key(root)
        stamp = json.loads(stamp_path.read_text())
        if isinstance(stamp, dict) and stamp.get("schema") == 1 and stamp.get("key") == key \
                and stamp.get("outputs") == _full_output_hashes(root):
            return {"mode": "full", "rebuilt": [], "reused": ["full"]}
    except (OSError, ValueError, RuntimeError):
        pass
    stamp_path.unlink(missing_ok=True)
    run("fixtures-full", ["scripts/prepare-tests.sh"])
    # Preparation may update a generated source. Bind the receipt to the final
    # source identity and publish it only after every declared output is hashed.
    try:
        key = _full_key(root)
        outputs = _full_output_hashes(root)
        _write_stamp(stamp_path, {"schema": 1, "key": key, "outputs": outputs})
    except (OSError, ValueError, RuntimeError) as error:
        # Full preparation still ran. An unreviewed/missing output simply makes
        # the next full selection prepare again instead of trusting this run.
        print(f"Full fixture receipt unavailable: {error}", file=sys.stderr)
    return {"mode": "full", "rebuilt": ["full"], "reused": []}


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
    if selection.get("mode") == "full" or any(name not in owners for name in classes):
        return _prepare_full(root, run)
    if selection.get("mode") != "narrow":
        raise ValueError("Invalid selected test mode")

    groups = sorted({owners[name] for name in classes if owners[name] is not None
                     and (owners[name] != "original-gmp" or fast_inputs.GMP_NATIVE_HOST)})
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
        run("fixture-scalar-signatures", ["python3", "scripts/generate-scalar-signatures.py"])
        run("fixture-compiler", ["compiler/build.sh"])
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
            run(f"fixture-{group_id}-{index:02d}", command["argv"], stdout=stdout)
        _write_stamp(stamp_path, {"schema": 1, "key": key,
                                  "outputs": _output_hashes(root, group)})
        rebuilt.append(group_id)
    return {"mode": "selected", "rebuilt": rebuilt, "reused": reused}
