#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

"""Validated native/Core fixture cache, never a cache of JVM test outcomes.

Only trusted-main workflows may publish these bundles. Hashes establish integrity
and freshness, not producer authenticity. Original preparation provenance is
copied verbatim. Workspace paths intentionally participate in the key.
"""
import argparse
import ast
import gzip
import hashlib
import io
import json
import os
from pathlib import Path, PurePosixPath
import platform
import re
import shutil
import stat
import subprocess
import sys
import tarfile
import zlib

SCHEMA = 1
GMP_NATIVE_HOST = platform.system() == "Linux" and platform.machine() == "x86_64"
HEX = re.compile(r"[0-9a-f]{64}\Z")
SELF = ".github/scripts/fast_inputs.py"
COMPILER_BUILD_INPUTS = ("thc.cabal", "cabal.project", "Setup.hs", "Makefile")
WIRED_SOURCE = "src/THC/Driver/Wired.hs"
# These are the runtime files actually fingerprinted by prepare-tests.sh's
# preparers. An additional recorded runtime source fails closed until reviewed.
RUNTIME_INPUTS = ("src/main/kotlin/thc/runtime/VectorMemoryPrimitives.kt",
                  "src/main/java/thc/runtime/DoubleX2.java")
MANIFEST_DIRS = """address-fields array-slices bignat-literals bit-primops
thread-status boxed-arrays boxed-array-extensions bytearray compare-byte-arrays data-to-tag double-arrays
explicit64-primops float-word-arrays fused-floating int-arrays int16-arrays int32-arrays
int8-arrays integer-primops managed-address-reads mutable-bytearray-size mutable-bytearrays mutvar stable-pointers weak-explicit shrink-bytearrays fetch-add-int-array
narrow-literal-proofs native-addresses libdw-unavailable original-stack original-stack-formatter original-stdio original-stdio-read original-stdio-close original-posix-dup original-open original-termios original-sigset original-stdio-seek original-stdio-truncate original-strerror original-fd-ready original-rts-locks original-handle-readiness original-posix-stat resize-bytearrays scalar-bitcasts short-bytes-slices sqrt
show-int show-word-list signed-narrow-primops simd-capability-smoke simd-calls simd-floatx4-fma synchronous-exceptions tuple-arithmetic word-floating""".split()
SIMD_FLOAT_FMA_OUTPUTS = frozenset("build/simd-floatx4-fma/" + name for name in (
    "manifest.json", "oracle.txt", "pre-core/SimdFloatFma.json", "post-core/SimdFloatFma.json",
    "pre-audit.json", "post-audit.json", "pre-double-audit.json", "post-double-audit.json"))
SIMD_SMOKE_SOURCES = frozenset("build/generated/simd/fixtures/" + name for name in (
    "GeneratedSimdSmoke.hs", "GeneratedSimdSmokeScalar.hs",
    "GeneratedSimdSmokeScalarNative.hs", "GeneratedSimdSmokeVectorNative.hs"))
SIMD_SMOKE_OUTPUTS = SIMD_SMOKE_SOURCES | frozenset("build/simd-capability-smoke/" + name for name in (
    "manifest.json", "pre-core/GeneratedSimdSmoke.json", "audits.json", "cases.tsv", "native/simd-smoke-oracle"))
PROVENANCE_DIRS = """aggregate-layout empty-join-input empty-tuple-input
floating-tuple state-tuple sum-layout sum-result tag-to-enum tuple-input
tuple-join tuple-return unsafe-equality simd simd-int32x4 simd-floatx4
simd-doublex2 simd-int16x8 simd-int8x16 simd-word8x16 simd-word16x8 simd-word32x4
simd-int32x4-multiply simd-int32x4-bytearray simd-word32x4-bytearray
simd-floatx4-bytearray simd-doublex2-bytearray""".split()
CHECK_DIRS = """aggregate-layout empty-join-input empty-tuple-input floating-tuple
state-tuple sum-layout sum-result tag-to-enum tuple-input tuple-join
tuple-return unsafe-equality""".split()
CORE_DIRS = ("build/core", "build/aggregate-core", "build/aggregate-post-core",
             "build/cbv-post-core", "build/source-core", "build/map/core", "build/map/boot-core")
REQUIRED = tuple(sorted({
    *(f"build/{d}/manifest.json" for d in MANIFEST_DIRS),
    *(["build/original-gmp/manifest.json"] if GMP_NATIVE_HOST else []),
    *(f"build/{d}/provenance.json" for d in PROVENANCE_DIRS),
    *(f"build/{d}/checks.json" for d in CHECK_DIRS),
    "build/floating/checks.json", "build/primop-coverage.json",
    "build/scalar-signatures/provenance.json", "build/aggregate-frontier.json",
    "build/aggregate-native/oracle.tsv", "build/native/oracle.tsv",
    "build/map/boot-provenance.json", "build/corpus/corpus.json",
    "build/core/THC.Prim.json", "build/core/THC.Fixtures.json",
    "build/aggregate-core/AggregateFrontier.json",
    "build/aggregate-post-core/AggregateFrontier.json",
    "build/map/core/GHC.InterfaceClosure.json",
    "build/map/boot-core/GHC.Internal.CString.json",
    *(f"build/core/{n}.json" for n in ("StrictFields", "SpeculationAudit",
      "RepresentationAudit", "SourceNotes", "CBVAudit", "CBVJoinAudit",
      "CBVCoercionAudit", "ConstructorFieldAudit", "DemandAudit")),
    *(f"build/cbv-post-core/{n}.json" for n in ("CBVAudit", "CBVJoinAudit", "CBVCoercionAudit")),
    "build/source-core/SourceNotes.json", "build/source-core/RepresentationAudit.json",
}))
BUILD_DIRS = frozenset(MANIFEST_DIRS + PROVENANCE_DIRS + ["original-gmp", "floating", "corpus",
    "scalar-signatures", "aggregate-native", "native", "map"] +
    [PurePosixPath(p).name for p in CORE_DIRS])
MAX_FILES = 30000
MAX_FILE_BYTES = 256 * 1024 * 1024
MAX_TOTAL_BYTES = 3 * 1024 * 1024 * 1024
MAX_MANIFEST_BYTES = 16 * 1024 * 1024
MAX_JSON_BYTES = 384 * 1024 * 1024
NATIVE_EXECUTABLES = frozenset({"build/unsafe-equality/api/predicate",
    "build/simd-capability-smoke/native/simd-smoke-oracle",
    "build/original-stdio/native/original-stdio-oracle",
    "build/original-stdio-read/native/original-stdio-read-oracle",
    "build/original-stdio-close/native/oracle",
    "build/original-posix-dup/native/oracle",
    "build/original-stdio-seek/native/oracle",
    "build/original-open/native/oracle",
    "build/original-termios/native/oracle",
    "build/original-sigset/native/oracle",
    "build/original-stdio-truncate/native/oracle",
    "build/original-strerror/native/oracle",
    "build/original-fd-ready/native/oracle",
    "build/original-handle-readiness/native/oracle",
    "build/original-posix-stat/native/oracle",
    "build/original-gmp/native/oracle",
    *(f"build/{name}/native/{name}" for name in
      ("state-tuple", "tuple-input", "tuple-return", "empty-tuple-input"))})
# The original stdio manifest fingerprints its commands, raw streams and numeric
# results as well as the semantic Core/oracle data. Admit only the reviewed 144
# cases and two export stages, not a general log/text/executable suffix rule.
ORIGINAL_STDIO_OUTPUTS = frozenset("build/original-stdio/" + name for name in (
    "manifest.json", "oracle.json", "native/original-stdio-oracle",
    *(f"results/{index}.txt" for index in range(144)),
    *(f"logs/{label}.{suffix}"
      for label in ("ghc-version", "ghc-info", "native-build", "pre-export", "post-export",
                    *(f"native-{index:03}" for index in range(144)),
                    *(f"{stage}-audit-{entry}" for stage in ("pre", "post") for entry in
                      ("originalWrite", "originalSafeWrite", "originalWriteErrno", "originalSafeWriteErrno")))
      for suffix in ("stdout", "stderr", "command.json")),
    *(f"{stage}/{name}" for stage in ("pre", "post")
      for name in ("core/OriginalStdioAudit.json", "core/THC.InterfaceClosure.json",
                   "originalWrite.audit.json", "originalSafeWrite.audit.json",
                   "originalWriteErrno.audit.json", "originalSafeWriteErrno.audit.json")),
))
ORIGINAL_STDIO_READ_OUTPUTS = frozenset("build/original-stdio-read/" + name for name in (
    "manifest.json", "oracle.json", "input.bin", "native/original-stdio-read-oracle",
    *(f"results/{index}.txt" for index in range(40)),
    *(f"logs/{label}.{suffix}"
      for label in ("ghc-version", "native-build", "pre-export", "post-export",
                    *(f"native-{index:03}" for index in range(40)),
                    *(f"{stage}-audit-{entry}" for stage in ("pre", "post") for entry in
                      ("originalRead", "originalSafeRead", "originalReadErrno", "originalSafeReadErrno")))
      for suffix in ("stdout", "stderr", "command.json")),
    *(f"{stage}/{name}" for stage in ("pre", "post")
      for name in ("core/OriginalStdioReadAudit.json", "core/THC.InterfaceClosure.json",
                   "originalRead.audit.json", "originalSafeRead.audit.json",
                   "originalReadErrno.audit.json", "originalSafeReadErrno.audit.json")),
))

ORIGINAL_HANDLE_READINESS_LOGS = (
    "ghc-version", "ghc-info", "native-build", "native--1", "native-1", "native-2",
    "pre-export", "post-export",
) + tuple(f"{stage}-audit-{entry}" for stage in ("pre", "post")
          for entry in ("originalIsTerminal", "originalIsTerminalErrno"))
ORIGINAL_HANDLE_READINESS_OUTPUTS = frozenset("build/original-handle-readiness/" + name for name in (
    "manifest.json", "oracle.json", "native/oracle",
    *(f"logs/{label}.{suffix}" for label in ORIGINAL_HANDLE_READINESS_LOGS
      for suffix in ("stdout", "stderr", "command.json")),
    *(f"{stage}/{name}" for stage in ("pre", "post") for name in (
        "core/OriginalHandleReadinessAudit.json", "core/THC.InterfaceClosure.json",
        "originalIsTerminal.audit.json", "originalIsTerminalErrno.audit.json")),
))

# One native oracle, two original Core stages and their exact logged commands.
# The exposed registration is evidence only: no package-db directory is cached.
ORIGINAL_GMP_ENTRIES = ("originalAdd", "originalAddWord", "originalCmp", "originalDivWord",
                        "originalModWord", "originalMul", "originalMulWord", "originalSub",
                        "originalQuotRem", "originalQuot", "originalRem")
ORIGINAL_GMP_OUTPUTS = frozenset("build/original-gmp/" + name for name in (
    "manifest.json", "oracle.json", "native/oracle", "exposed-ghc-internal.conf",
    *(f"logs/{label}.{suffix}" for label in (
        "ghc-version", "ghc-info", "original-registration", "package-init", "package-register",
        "native-build", "native-observations", "pre-export", "post-export",
        *(f"{stage}-audit-{entry}" for stage in ("pre", "post") for entry in ORIGINAL_GMP_ENTRIES))
      for suffix in ("stdout", "stderr", "command.json")),
    *(f"{stage}/{name}" for stage in ("pre", "post") for name in (
        "core/OriginalGmpAudit.json", "core/THC.InterfaceClosure.json",
        *(f"{entry}.audit.json" for entry in ORIGINAL_GMP_ENTRIES))),
))

ORIGINAL_RTS_LOCK_ENTRIES = ("originalLock", "originalUnlock")
ORIGINAL_OPEN_ENTRIES = ("originalOpen", "originalOpenSafe", "originalOpenInterruptible")
ORIGINAL_OPEN_OUTPUTS = frozenset("build/original-open/" + name for name in (
    "manifest.json", "oracle.json", "native/oracle",
    *(f"logs/{label}.{suffix}" for label in ("ghc-version", "ghc-info", "native-build", "native-run", "pre-export", "post-export",
        *(f"{stage}-audit-{entry}" for stage in ("pre", "post") for entry in ORIGINAL_OPEN_ENTRIES))
      for suffix in ("stdout", "stderr", "command.json")),
    *(f"{stage}/{name}" for stage in ("pre", "post") for name in (
        "core/OriginalOpenAudit.json", "core/THC.InterfaceClosure.json", *(f"{entry}.audit.json" for entry in ORIGINAL_OPEN_ENTRIES))),
))
ORIGINAL_RTS_LOCK_OUTPUTS = frozenset("build/original-rts-locks/" + name for name in (
    "manifest.json", "oracle.json", "declarations.json", "template-pre.json", "pre.json", "post.json",
    *(f"{stage}-{entry}.audit.json" for stage in ("pre", "post") for entry in ORIGINAL_RTS_LOCK_ENTRIES),
    *(f"logs/{label}.{suffix}" for label in ("version", "info", "libdir", "imports",
        *(f"{stage}-{entry}" for stage in ("pre", "post") for entry in ORIGINAL_RTS_LOCK_ENTRIES))
      for suffix in ("stdout", "stderr", "command.json")),
))

ORIGINAL_TERMIOS_ENTRIES = ("originalTermiosSize", "originalEcho", "originalIcanon", "originalVmin", "originalVtime",
                          "originalTcsanow", "originalSigsetSize", "originalSigttou", "originalSigBlock", "originalSigSetmask",
                          "originalLflag", "originalPokeLflag", "originalCC")
ORIGINAL_TERMIOS_OUTPUTS = frozenset("build/original-termios/" + name for name in (
    "manifest.json", "oracle.json", "native/oracle",
    *(f"logs/{label}.{suffix}" for label in (
        "ghc-version", "ghc-info", "native-build", "native-run", "pre-export", "post-export",
        *(f"{stage}-audit-{entry}" for stage in ("pre", "post") for entry in ORIGINAL_TERMIOS_ENTRIES))
      for suffix in ("stdout", "stderr", "command.json")),
    *(f"{stage}/{name}" for stage in ("pre", "post") for name in (
        "core/OriginalTermiosAudit.json", "core/THC.InterfaceClosure.json",
        *(f"{entry}.audit.json" for entry in ORIGINAL_TERMIOS_ENTRIES))),
))

ORIGINAL_SIGSET_ENTRIES = ("originalSigEmpty", "originalSigAdd")
ORIGINAL_SIGSET_OUTPUTS = frozenset("build/original-sigset/" + name for name in (
    "manifest.json", "oracle.json", "native/oracle",
    *(f"logs/{label}.{suffix}" for label in (
        "ghc-version", "ghc-info", "native-build", "native-run", "pre-export", "post-export",
        *(f"{stage}-audit-{entry}" for stage in ("pre", "post") for entry in ORIGINAL_SIGSET_ENTRIES))
      for suffix in ("stdout", "stderr", "command.json")),
    *(f"{stage}/{name}" for stage in ("pre", "post") for name in (
        "core/OriginalSigsetAudit.json", "core/THC.InterfaceClosure.json",
        *(f"{entry}.audit.json" for entry in ORIGINAL_SIGSET_ENTRIES))),
))

ORIGINAL_POSIX_STAT_ENTRIES = ("originalStatSize", "originalStatDev", "originalStatIno",
                             "originalStatMode", "originalStatLength", "originalStatTypes", "originalFstat", "originalFstatErrno")
ORIGINAL_POSIX_STAT_OUTPUTS = frozenset("build/original-posix-stat/" + name for name in (
    "manifest.json", "oracle.json", "native/oracle", "native/sample.bin",
    *(f"logs/{label}.{suffix}" for label in (
        "ghc-version", "ghc-info", "native-build", "native-run", "pre-export", "post-export",
        *(f"{stage}-audit-{entry}" for stage in ("pre", "post") for entry in ORIGINAL_POSIX_STAT_ENTRIES))
      for suffix in ("stdout", "stderr", "command.json")),
    *(f"{stage}/{name}" for stage in ("pre", "post") for name in (
        "core/OriginalPosixStatAudit.json", "core/THC.InterfaceClosure.json",
        *(f"{entry}.audit.json" for entry in ORIGINAL_POSIX_STAT_ENTRIES))),
))

ORIGINAL_POSIX_DUP_ENTRIES = ("originalDup", "originalDupErrno", "originalDup2", "originalDup2Errno")
ORIGINAL_POSIX_DUP_LOGS = ("ghc-version", "ghc-info", "native-build", "pre-export", "post-export") + tuple(
    f"native-{index}" for index in range(19)) + tuple(
    f"{stage}-audit-{entry}" for stage in ("pre", "post") for entry in ORIGINAL_POSIX_DUP_ENTRIES)
ORIGINAL_POSIX_DUP_OUTPUTS = frozenset("build/original-posix-dup/" + name for name in (
    "manifest.json", "oracle.json", "native/oracle",
    *(f"results/{index}.{suffix}" for index in range(19) for suffix in ("txt", "private", "other")),
    *(f"logs/{label}.{suffix}" for label in ORIGINAL_POSIX_DUP_LOGS for suffix in ("stdout", "stderr", "command.json")),
    *(f"{stage}/{name}" for stage in ("pre", "post") for name in (
        "core/OriginalPosixDupAudit.json", "core/THC.InterfaceClosure.json",
        *(f"{entry}.audit.json" for entry in ORIGINAL_POSIX_DUP_ENTRIES))),
))

ORIGINAL_STDIO_CLOSE_LOGS = (
    "ghc-version", "ghc-info", "native-build", "pre-export", "post-export",
) + tuple(f"native-{index}" for index in range(4)) + tuple(
    f"{stage}-audit-{entry}" for stage in ("pre", "post")
    for entry in ("originalClose", "originalCloseErrno"))
ORIGINAL_STDIO_CLOSE_OUTPUTS = frozenset("build/original-stdio-close/" + name for name in (
    "manifest.json", "oracle.json", "native/oracle",
    *(f"results/{index}.txt" for index in range(4)),
    "results/0.private", "results/2.private",
    *(f"logs/{label}.{suffix}" for label in ORIGINAL_STDIO_CLOSE_LOGS
      for suffix in ("stdout", "stderr", "command.json")),
    *(f"{stage}/{name}" for stage in ("pre", "post") for name in (
        "core/OriginalStdioCloseAudit.json", "core/THC.InterfaceClosure.json",
        "originalClose.audit.json", "originalCloseErrno.audit.json")),
))

ORIGINAL_STDIO_SEEK_LOGS = (
    "ghc-version", "ghc-info", "native-build", "native-constants", "pre-export", "post-export",
) + tuple(f"native-{index}" for index in range(24)) + tuple(
    f"{stage}-audit-{entry}" for stage in ("pre", "post")
    for entry in ("originalSeek", "originalSeekErrno"))
ORIGINAL_STDIO_SEEK_OUTPUTS = frozenset("build/original-stdio-seek/" + name for name in (
    "manifest.json", "oracle.json", "native/oracle",
    *(f"results/{index}.txt" for index in range(24)),
    *(f"results/{index}.private" for index in range(24) if index % 12 not in (7, 8)),
    *(f"logs/{label}.{suffix}" for label in ORIGINAL_STDIO_SEEK_LOGS
      for suffix in ("stdout", "stderr", "command.json")),
    *(f"{stage}/{name}" for stage in ("pre", "post") for name in (
        "core/OriginalStdioSeekAudit.json", "core/THC.InterfaceClosure.json",
        "originalSeek.audit.json", "originalSeekErrno.audit.json")),
))

ORIGINAL_STDIO_TRUNCATE_LOGS = (
    "ghc-version", "ghc-info", "native-build", "pre-export", "post-export",
) + tuple(f"native-{index}" for index in range(14)) + tuple(
    f"{stage}-audit-{entry}" for stage in ("pre", "post")
    for entry in ("originalTruncate", "originalTruncateErrno"))
ORIGINAL_STDIO_TRUNCATE_OUTPUTS = frozenset("build/original-stdio-truncate/" + name for name in (
    "manifest.json", "oracle.json", "native/oracle",
    *(f"results/{index}.txt" for index in range(14)),
    *(f"results/{index}.private" for index in list(range(5)) + list(range(7, 12))),
    *(f"logs/{label}.{suffix}" for label in ORIGINAL_STDIO_TRUNCATE_LOGS
      for suffix in ("stdout", "stderr", "command.json")),
    *(f"{stage}/{name}" for stage in ("pre", "post") for name in (
        "core/OriginalStdioTruncateAudit.json", "core/THC.InterfaceClosure.json",
        "originalTruncate.audit.json", "originalTruncateErrno.audit.json")),
))

ORIGINAL_STRERROR_OUTPUTS = frozenset("build/original-strerror/" + name for name in (
    "manifest.json", "oracle.json", "native/oracle",
    *(f"logs/{label}.{suffix}" for label in ("ghc-version", "native-build", "native-oracle")
      for suffix in ("stdout", "stderr", "command.json")),
))

ORIGINAL_FD_READY_ENTRIES = ("originalReadySafe", "originalReadyUnsafe")
ORIGINAL_FD_READY_NEGATIVES = (
    "wrong-unit", "dynamic-target", "non-function", "wrong-convention", "interruptible",
    "wrong-arity", "wrong-supplied-arity", "boolean-schema", "signed-cbool",
    "machine-timeout", "scalar-state", "machine-result",
)
ORIGINAL_FD_READY_LOGS = (
    "ghc-version", "ghc-info", "ghc-libdir", "ghc-internal-imports", "native-build", "native-observations",
) + tuple(f"audit-{entry}" for entry in ORIGINAL_FD_READY_ENTRIES) + tuple(
    f"negative-{label}-{entry}" for label in ORIGINAL_FD_READY_NEGATIVES for entry in ORIGINAL_FD_READY_ENTRIES)
ORIGINAL_FD_READY_OUTPUTS = frozenset("build/original-fd-ready/" + name for name in (
    "manifest.json", "oracle.json", "OriginalFDDeclarations.json", "Template.json",
    "OriginalFdReadyAudit.json", "facts.json", "native/oracle", "native/private-file",
    *(f"{entry}.audit.json" for entry in ORIGINAL_FD_READY_ENTRIES),
    *(f"negative/{label}.json" for label in ORIGINAL_FD_READY_NEGATIVES),
    *(f"negative/{label}-{entry}.audit.json" for label in ORIGINAL_FD_READY_NEGATIVES for entry in ORIGINAL_FD_READY_ENTRIES),
    *(f"logs/{label}.{suffix}" for label in ORIGINAL_FD_READY_LOGS for suffix in ("stdout", "stderr", "command.json")),
))

# Each attempt retains its logs without admitting arbitrary files from a build
# tree. Only artifacts referenced by the current manifest enter the cache.
ORIGINAL_STACK_FILES = frozenset((
    "native/original-stack-native",
    *(f"{stage}-core/{module}.json" for stage in ("pre", "post")
      for module in ("OriginalStackAudit", "THC.InterfaceClosure")),
    *(f"logs/{label}.{suffix}"
      for label in ("ghc-version", "thc-revision", "pre-export", "post-export",
                    "native-compile", "native-invariants")
      for suffix in ("stdout", "stderr", "command.json")),
))


def original_stack_artifact(name):
    match = re.fullmatch(r"build/original-stack/run-[1-9][0-9]*/(.+)", name)
    return match is not None and match.group(1) in ORIGINAL_STACK_FILES


def native_executable(name):
    return name in NATIVE_EXECUTABLES or (
        original_stack_artifact(name) and name.endswith("/native/original-stack-native")) or (
        original_stack_formatter_artifact(name) and name.endswith("/native/formatter"))


class CacheMiss(RuntimeError):
    """Unavailable, stale or invalid cache; fresh preparation is required."""


BOXED_ARRAY_EXTENSION_FILES = frozenset((
    "native/boxed-array-extensions-oracle",
    *(f"{stage}-core/{module}.json" for stage in ("pre", "post")
      for module in ("BoxedArrayExtensionsAudit", "THC.InterfaceClosure")),
    *(f"{stage}-{entry}.audit.json" for stage in ("pre", "post")
      for entry in ("boxedExtSizes", "boxedExtClone", "boxedExtCopy", "boxedExtMove", "boxedExtThaw", "boxedExtLazy")),
    *(f"logs/{label}.{suffix}" for label in (
        "ghc-version", "ghc-info", "pre-export", "post-export", "native-compile", "native-oracle",
        *(f"{stage}-audit-{entry}" for stage in ("pre", "post")
          for entry in ("boxedExtSizes", "boxedExtClone", "boxedExtCopy", "boxedExtMove", "boxedExtThaw", "boxedExtLazy")))
      for suffix in ("stdout", "stderr", "command.json")),
))


def boxed_array_extension_artifact(name):
    match = re.fullmatch(r"build/boxed-array-extensions/run-[1-9][0-9]*/(.+)", name)
    return match is not None and match.group(1) in BOXED_ARRAY_EXTENSION_FILES


def require(condition, message):
    if not condition:
        raise CacheMiss(message)


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":")).encode()


def sha(data):
    return hashlib.sha256(data).hexdigest()


def digest_stream(stream):
    h = hashlib.sha256()
    for block in iter(lambda: stream.read(1024 * 1024), b""):
        h.update(block)
    return h.hexdigest()


def digest(path):
    require(path.is_file(), "Missing regular input: " + str(path))
    with path.open("rb") as stream:
        return digest_stream(stream)


def relative(name):
    require(isinstance(name, str) and bool(name), "Invalid path type")
    require(not name.startswith("/") and "\\" not in name and all(ord(c) >= 32 for c in name)
            and all(p not in ("", ".", "..") for p in name.split("/")), "Unsafe path: " + name)
    require(not re.match(r"^[A-Za-z]:", name), "Drive-qualified path: " + name)
    return name


def file_path(root, name):
    path = root / relative(name)
    require(path.resolve() == path, "Symlink/noncanonical destination: " + name)
    # resolve() does not reveal a dangling leaf symlink whose target is itself.
    require(not path.is_symlink(), "Symlink file: " + name)
    return path


def wired_catalog(root):
    """Read the production exporter's literal catalog; never execute Haskell.

    These three lists deliberately use only ordinary string/tuple literals. A
    different declaration shape fails closed until the cache reader is reviewed.
    """
    source = file_path(root, WIRED_SOURCE).read_text()
    def table(name):
        matches = re.findall(r"^" + name + r"\s*=\s*\n(\s*\[.*?^\s*\])", source, re.M | re.S)
        require(len(matches) == 1, "Missing/ambiguous Wired catalog: " + name)
        try:
            value = ast.literal_eval(matches[0].strip())
        except (ValueError, SyntaxError) as error:
            raise CacheMiss("Nonliteral Wired catalog: " + name) from error
        require(isinstance(value, list) and bool(value), "Empty Wired catalog: " + name)
        return value
    def pairs(name):
        value = table(name)
        require(all(isinstance(row, tuple) and len(row) == 2 and
                    all(isinstance(part, str) for part in row) for row in value), "Invalid Wired pairs")
        require(len({row[0] for row in value}) == len(value), "Duplicate Wired source")
        return dict(value)
    modules, hashes = pairs("moduleSources"), pairs("sourceHashes")
    boot = table("bootSources")
    require(all(isinstance(path, str) and relative(path).endswith(".hs-boot") for path in boot)
            and len(set(boot)) == len(boot), "Invalid Wired boot sources")
    for path, module in modules.items():
        require(PurePosixPath(relative(path)).suffix in (".hs", ".hsc") and
                module == str(PurePosixPath(path).with_suffix("")).replace("/", ".") and
                re.fullmatch(r"[A-Z][A-Za-z0-9_]*(?:\.[A-Z][A-Za-z0-9_]*)*", module),
                "Invalid Wired module source")
    require(set(modules) | set(boot) <= hashes.keys(), "Unpinned Wired source")
    for path, digest in hashes.items():
        relative(path)
        require(HEX.fullmatch(digest) is not None, "Invalid Wired source pin")
    return modules, hashes


def original_stack_formatter_files(root=None):
    # Reuse the exporter's authoritative module names and HSC sources instead
    # of maintaining another manually synchronized list of 37 modules.
    root = Path(__file__).resolve().parents[2] if root is None else root
    modules, _ = wired_catalog(root)
    return frozenset((
        "native/formatter", "originals/generated.json", "originals/target-layout.json",
        *(f"originals/core/{module}.json" for module in modules.values()),
        *("originals/generated/" + str(PurePosixPath(path).with_suffix(".hs"))
          for path in modules if path.endswith(".hsc")),
        *(f"{stage}-core/OriginalStackFormatter.json" for stage in ("pre", "post")),
        *(f"{stage}-audit.json" for stage in ("pre", "post")),
        *(f"logs/{label}.{suffix}"
          for label in ("ghc-version", "plugin-build", "original-source-export", "pre-export",
                        "post-export", "native-compile", "native-observations", "pre-audit", "post-audit")
          for suffix in ("stdout", "stderr", "command.json")),
    ))


def original_stack_formatter_artifact(name):
    match = re.fullmatch(r"build/original-stack-formatter/run-[1-9][0-9]*/(.+)", name)
    return match is not None and match.group(1) in original_stack_formatter_files()


def formatter_artifact_hashes(root, manifest):
    """One complete reviewed attempt, excluding overlays and previous attempts."""
    require(isinstance(manifest, dict), "Invalid formatter manifest")
    artifacts = manifest.get("artifactHashes")
    require(isinstance(artifacts, dict) and bool(artifacts), "Missing formatter artifact hashes")
    attempts = set()
    for name, digest in artifacts.items():
        match = re.fullmatch(r"(build/original-stack-formatter/run-[1-9][0-9]*)/(.+)", relative(name))
        require(match is not None and isinstance(digest, str) and HEX.fullmatch(digest),
                "Invalid formatter artifact")
        attempts.add(match.group(1))
    require(len(attempts) == 1, "Mixed formatter attempts")
    expected = {next(iter(attempts)) + "/" + name for name in original_stack_formatter_files(root)}
    require(set(artifacts) == expected, "Incomplete/unreviewed formatter artifacts")
    return artifacts


def rts_lock_artifact_hashes(manifest):
    require(isinstance(manifest, dict) and type(manifest.get("schema")) is int and manifest.get("schema") == 1 and
            manifest.get("strictAccepted") is True and manifest.get("originalIdsChecked") is True and
            manifest.get("typeEqualityChecked") is True and manifest.get("installedArtifactsHashed") is False,
            "Invalid original RTS lock proof manifest")
    artifacts = manifest.get("artifactHashes")
    require(isinstance(artifacts, dict) and set(artifacts) ==
            ORIGINAL_RTS_LOCK_OUTPUTS - {"build/original-rts-locks/manifest.json"},
            "Incomplete/unreviewed RTS lock artifacts")
    require(all(isinstance(value, str) and HEX.fullmatch(value) for value in artifacts.values()),
            "Invalid RTS lock artifact hash")
    return artifacts


def original_open_artifact_hashes(manifest):
    require(isinstance(manifest, dict) and type(manifest.get("schema")) is int and manifest.get("schema") == 1,
            "Invalid original open manifest")
    if not GMP_NATIVE_HOST:
        require(manifest.get("supported") is False, "Unsupported original open host")
        return {}
    require(manifest.get("supported") is True and manifest.get("strictAccepted") is True and
            manifest.get("runtimeVerified") is False and manifest.get("installedArtifactsHashed") is False and
            type(manifest.get("nativeRows")) is int and manifest.get("nativeRows") == 13,
            "Invalid original open proof")
    artifacts = manifest.get("artifactHashes")
    require(isinstance(artifacts, dict) and set(artifacts) == ORIGINAL_OPEN_OUTPUTS - {"build/original-open/manifest.json"},
            "Incomplete/unreviewed original open artifacts")
    require(all(isinstance(value, str) and HEX.fullmatch(value) for value in artifacts.values()), "Invalid original open hash")
    return artifacts


def termios_artifact_hashes(manifest):
    require(isinstance(manifest, dict) and type(manifest.get("schema")) is int and manifest.get("schema") == 1,
            "Invalid original termios manifest")
    if not GMP_NATIVE_HOST:
        require(manifest.get("supported") is False and manifest.get("artifactHashes") == {}, "Unsupported termios host")
        return {}
    require(manifest.get("supported") is True and manifest.get("entries") == list(ORIGINAL_TERMIOS_ENTRIES) and
            manifest.get("strictAccepted") is True and manifest.get("runtimeVerified") is False and
            manifest.get("installedArtifactsHashed") is False and
            type(manifest.get("nativeRows")) is int and manifest.get("nativeRows") == 6,
            "Invalid original termios proof")
    artifacts = manifest.get("artifactHashes")
    require(isinstance(artifacts, dict) and set(artifacts) == ORIGINAL_TERMIOS_OUTPUTS - {"build/original-termios/manifest.json"},
            "Incomplete/unreviewed original termios artifacts")
    require(all(isinstance(value, str) and HEX.fullmatch(value) for value in artifacts.values()), "Invalid termios hash")
    return artifacts


def sigset_artifact_hashes(manifest):
    require(isinstance(manifest, dict) and type(manifest.get("schema")) is int and manifest.get("schema") == 1,
            "Invalid original sigset manifest")
    if not GMP_NATIVE_HOST:
        require(manifest.get("supported") is False and manifest.get("artifactHashes") == {}, "Unsupported sigset host")
        return {}
    require(manifest.get("supported") is True and manifest.get("entries") == list(ORIGINAL_SIGSET_ENTRIES) and
            manifest.get("strictAccepted") is True and manifest.get("runtimeVerified") is False and
            manifest.get("installedArtifactsHashed") is False and
            type(manifest.get("nativeRows")) is int and manifest.get("nativeRows") == 532,
            "Invalid original sigset proof")
    artifacts = manifest.get("artifactHashes")
    require(isinstance(artifacts, dict) and set(artifacts) == ORIGINAL_SIGSET_OUTPUTS - {"build/original-sigset/manifest.json"},
            "Incomplete/unreviewed original sigset artifacts")
    require(all(isinstance(value, str) and HEX.fullmatch(value) for value in artifacts.values()), "Invalid sigset hash")
    return artifacts


def gmp_artifact_hashes(manifest):
    require(isinstance(manifest, dict) and manifest.get("strictAccepted") is True,
            "Missing accepted GMP fixture receipt")
    artifacts = manifest.get("artifactHashes")
    require(isinstance(artifacts, dict) and set(artifacts) ==
            ORIGINAL_GMP_OUTPUTS - {"build/original-gmp/manifest.json"},
            "Incomplete/unreviewed GMP artifacts")
    require(all(isinstance(value, str) and HEX.fullmatch(value) for value in artifacts.values()),
            "Invalid GMP artifact hash")
    return artifacts


def command(argv, root):
    return subprocess.check_output(list(map(str, argv)), cwd=root, text=True).strip()


def tracked_files(root):
    return set(command(["git", "ls-files", "-z"], root).split("\0")) - {""}


def check_package_scope(root, pkg):
    # This cache intentionally supports the clean pinned CI installation only.
    # Additional user/override packages are not identified by the GHC version.
    require("GHC_PACKAGE_PATH" not in os.environ, "Custom GHC_PACKAGE_PATH is outside the cache scope")
    require(os.environ.get("GHC_ENVIRONMENT", "-") == "-", "Custom GHC_ENVIRONMENT is outside the cache scope")
    require(not command([pkg, "list", "--user", "--simple-output"], root),
            "User GHC packages are outside the cache scope")
    if os.environ.get("GHC_ENVIRONMENT") != "-":
        require(not any(list(p.glob(".ghc.environment.*")) for p in (root, *root.parents)),
                "Automatic GHC package environment is outside the cache scope")
        require(not list((Path.home()/".ghc").glob("*/environments/default")),
                "Default GHC package environment is outside the cache scope")


def toolchain(root):
    # The pinned version is the GHC compatibility gate. Scanning its installed
    # interfaces and libraries costs minutes and does not help ordinary PRs.
    ghc = str(Path(shutil.which(os.environ.get("GHC", "ghc")) or os.environ.get("GHC", "ghc")).resolve())
    pkg = str(Path(shutil.which(os.environ.get("GHC_PKG", "ghc-pkg")) or os.environ.get("GHC_PKG", "ghc-pkg")).resolve())
    version = command([ghc, "--numeric-version"], root)
    require(version == "9.14.1", "Requires GHC9.14.1")
    require(command([pkg, "--version"], root) == "GHC package manager version 9.14.1",
            "Requires ghc-pkg9.14.1")
    check_package_scope(root, pkg)
    settings = dict(ast.literal_eval(command([ghc, "--info"], root)))
    libdir = Path(command([ghc, "--print-libdir"], root)).resolve()
    require(libdir.is_dir() and len(libdir.parts) > 3, "Unsafe GHC libdir")
    java_home = os.environ.get("JAVA_HOME")
    require(bool(java_home), "JAVA_HOME must identify pinned Graal/JDK")
    release = Path(java_home).resolve() / "release"
    result = {"version": version, "target": settings["Target platform"],
        "ghcLauncher": {"path": ghc},
        "ghcBinary": {"path": str(libdir.parent / ("bin/ghc-" + version))},
        "ghcPkg": {"path": pkg}, "ghcLibdir": str(libdir),
        "pythonVersion": platform.python_version(),
        "javaRelease": {"path": str(release), "sha256": digest(release)}}
    result["environment"] = {k: v for k, v in sorted(os.environ.items()) if k in
        ("THC_SOURCE_NOTES", "THC_CORE_OUT", "THC_GHC_OUT", "GHC", "GHC_PKG", "GHC_ENVIRONMENT",
         "GHCRTS", "CC", "CFLAGS", "CPATH", "LIBRARY_PATH", "LD_LIBRARY_PATH", "LANG", "LC_ALL")}
    return result


def identity(root):
    tracked = tracked_files(root)
    sources = {name for name in tracked if name.startswith(("compiler/", "scripts/", "examples/", "src/main/resources/", "test/haskell-fixtures/", "src/THC/Driver/"))}
    sources.update((SELF, WIRED_SOURCE, *RUNTIME_INPUTS, *COMPILER_BUILD_INPUTS))
    require(all(name in tracked for name in sources), "Cache helper/runtime inputs must be tracked")
    require("scripts/prepare-tests.sh" in sources and "compiler/export-boot.py" in sources
            and "examples/coverage.json" in sources, "Incomplete authoritative source set")
    return {"schema": SCHEMA, "workspace": str(root),
        "platform": {"system": platform.system(), "machine": platform.machine(),
                     "byteOrder": sys.byteorder, "libc": list(platform.libc_ver())},
        "sources": {name: digest(file_path(root, name)) for name in sorted(sources)},
        "toolchain": toolchain(root)}


def cache_key(value):
    return "thc-fast-inputs-v" + str(SCHEMA) + "-" + sha(canonical(value))


def vendor_pins(root):
    """Read the original exporter's literal pin tables without executing it."""
    tables, result = {}, {}
    tree = ast.parse(file_path(root, "compiler/export-boot.py").read_text())
    for statement in tree.body:
        if not (isinstance(statement, ast.Assign) and len(statement.targets) == 1
                and isinstance(statement.targets[0], ast.Name)
                and statement.targets[0].id.endswith("_sources") and isinstance(statement.value, ast.Dict)):
            continue
        table = {}
        for key, node in zip(statement.value.keys, statement.value.values):
            name = ast.literal_eval(key)
            if isinstance(node, ast.Subscript) and isinstance(node.value, ast.Name):
                value = tables[node.value.id][ast.literal_eval(node.slice)]
            else:
                value = ast.literal_eval(node)
            require(isinstance(value, str) and HEX.fullmatch(value), "Invalid authoritative vendor pin")
            full = "vendor/ghc-9.14.1/" + relative(name)
            require(full not in result or result[full] == value, "Conflicting authoritative vendor pin")
            table[name] = value
            result[full] = value
        tables[statement.targets[0].id] = table
    require(bool(result), "No authoritative GHC source pins")
    return result


def allowed_payload(name, pins):
    parts = PurePosixPath(relative(name)).parts
    if name in pins:
        return True
    if name in ("build/primop-coverage.json", "build/aggregate-frontier.json"):
        return True
    if native_executable(name):
        return True
    if name in SIMD_SMOKE_SOURCES:
        return True
    if len(parts) < 3 or parts[0] != "build":
        return False
    if parts[1] == "compiler":
        return len(parts) == 3 and (parts[2] == "plugin.json" or
            bool(re.fullmatch(r"libHSthc-[\w.-]+\.(so|dylib)", parts[2])))
    if parts[1] == "original-stdio":
        return name in ORIGINAL_STDIO_OUTPUTS
    if parts[1] == "simd-capability-smoke":
        return name in SIMD_SMOKE_OUTPUTS
    if parts[1] == "simd-floatx4-fma":
        return name in SIMD_FLOAT_FMA_OUTPUTS
    if parts[1] == "original-stdio-read":
        return name in ORIGINAL_STDIO_READ_OUTPUTS
    if parts[1] == "original-stdio-close":
        return name in ORIGINAL_STDIO_CLOSE_OUTPUTS
    if parts[1] == "original-posix-dup":
        return name in ORIGINAL_POSIX_DUP_OUTPUTS
    if parts[1] == "original-stdio-seek":
        return name in ORIGINAL_STDIO_SEEK_OUTPUTS
    if parts[1] == "original-stdio-truncate":
        return name in ORIGINAL_STDIO_TRUNCATE_OUTPUTS
    if parts[1] == "libdw-unavailable":
        return name in ("build/libdw-unavailable/manifest.json", "build/libdw-unavailable/oracle.json", "build/libdw-unavailable/foreign-labels.json")
    if parts[1] == "native-addresses":
        return name in ("build/native-addresses/manifest.json", "build/native-addresses/oracle.json")
    if parts[1] == "original-strerror":
        return name in ORIGINAL_STRERROR_OUTPUTS
    if parts[1] == "original-fd-ready":
        return name in ORIGINAL_FD_READY_OUTPUTS
    if parts[1] == "original-handle-readiness":
        return name in ORIGINAL_HANDLE_READINESS_OUTPUTS
    if parts[1] == "original-posix-stat":
        return name in ORIGINAL_POSIX_STAT_OUTPUTS
    if parts[1] == "original-rts-locks":
        return name in ORIGINAL_RTS_LOCK_OUTPUTS
    if parts[1] == "original-open":
        return name in ORIGINAL_OPEN_OUTPUTS
    if parts[1] == "original-termios":
        return name in ORIGINAL_TERMIOS_OUTPUTS
    if parts[1] == "original-sigset":
        return name in ORIGINAL_SIGSET_OUTPUTS
    if parts[1] == "original-gmp":
        return name in ORIGINAL_GMP_OUTPUTS
    if parts[1] == "original-stack":
        return name == "build/original-stack/manifest.json" or original_stack_artifact(name)
    if parts[1] == "original-stack-formatter":
        return name == "build/original-stack-formatter/manifest.json" or original_stack_formatter_artifact(name)
    if parts[1] == "boxed-array-extensions":
        return name == "build/boxed-array-extensions/manifest.json" or boxed_array_extension_artifact(name)
    if parts[1] not in BUILD_DIRS or any(p in ("test-results", "reports", "classes", ".gradle") for p in parts):
        return False
    # Fixture inputs and recorded native objects only, not arbitrary executable
    # scripts, JARs, Gradle state or JUnit status. Native executables are data here.
    suffix = PurePosixPath(name).suffix
    return suffix in (".json", ".tsv", ".hs", ".hi", ".o", ".dyn_hi", ".dyn_o") or (
        not suffix and ("oracle" in parts[-1] or parts[-1] == "aggregate-frontier"))


def hashes_in(value, tc):
    """All fingerprint spellings used by current original preparation manifests."""
    if isinstance(value, dict):
        if "path" in value and "sha256" in value:
            yield value["path"], value["sha256"]
        for stem in ("ghcBinary", "ghcLauncher"):
            if stem + "Path" in value or stem + "Sha256" in value:
                path = value.get(stem + "Path")
                if path is None and stem == "ghcBinary":
                    # Legacy prepare-simd/floatx4/... named the launcher digest
                    # ghcBinarySha256. New memory preparers explicitly name both.
                    require(value.get("ghcVersion") == "9.14.1" and "ghcInfo" in value,
                            "Unknown pathless GHC fingerprint format")
                    path = tc["ghcLauncher"]["path"]
                yield path, value.get(stem + "Sha256")
        for key, child in value.items():
            if key in ("inputHashes", "artifactHashes") or (key == "inputs" and isinstance(child, dict)
                    and child and all(isinstance(v, str) and HEX.fullmatch(v) for v in child.values())):
                require(isinstance(child, dict), "Invalid original hash map")
                yield from child.items()
            else:
                yield from hashes_in(child, tc)
    elif isinstance(value, list):
        for child in value:
            yield from hashes_in(child, tc)


def original_name(root, value):
    require(isinstance(value, str), "Original fingerprint path is not a string")
    path = Path(value)
    if not path.is_absolute():
        return relative(value), False
    try:
        name = relative(path.resolve().relative_to(root).as_posix())
    except ValueError:
        # ghc-pkg legitimately returns lib/../lib interface paths. Their raw
        # provenance stays unchanged; external_allowed checks the resolved scope.
        return value, True
    require(str(root / name) == value, "Noncanonical original workspace path")
    return name, False


def external_allowed(name, current):
    path = Path(name).resolve()
    tc = current["toolchain"]
    explicit = {Path(v["path"]).resolve() for v in tc.values() if isinstance(v, dict) and "path" in v}
    return path in explicit or path.is_relative_to(Path(tc["ghcLibdir"]).resolve().parent)


def inventory(root, current, read, core_files, verified=None):
    """Derive inventory independently from preserved original manifests."""
    tracked, pins = tracked_files(root), vendor_pins(root)
    for name in core_files:
        p = PurePosixPath(relative(name))
        require(str(p.parent) in CORE_DIRS and p.suffix == ".json", "Unknown extra Core file: " + name)
    pending = list(dict.fromkeys((*REQUIRED, *core_files)))
    payload, external, expected, visited = {}, {}, {}, set()
    while pending:
        name = pending.pop()
        if name in visited:
            continue
        visited.add(name)
        require(name not in tracked and allowed_payload(name, pins), "Unknown/tracked payload: " + name)
        require(verified is None or name in verified, "Missing original dependency: " + name)
        # Restore has already hashed all bytes in one sequential archive pass.
        # Reuse those hashes and cached JSON rather than randomly seeking gzip
        # once per native artifact during dependency traversal.
        data = read(name) if verified is None or name.endswith(".json") else None
        if data is not None:
            require(len(data) <= MAX_FILE_BYTES, "Oversized payload: " + name)
        actual = sha(data) if verified is None else verified[name]
        require(name not in expected or expected[name] == actual, "Stale original artifact: " + name)
        require(name not in pins or pins[name] == actual, "Vendor source differs from authoritative pin: " + name)
        payload[name] = actual
        if not name.endswith(".json"):
            continue
        doc = json.loads(data)
        if name == "build/original-stack-formatter/manifest.json":
            formatter_artifact_hashes(root, doc)
        if name == "build/original-gmp/manifest.json":
            gmp_artifact_hashes(doc)
        if name == "build/original-rts-locks/manifest.json":
            rts_lock_artifact_hashes(doc)
        if name == "build/original-open/manifest.json":
            original_open_artifact_hashes(doc)
        if name == "build/original-termios/manifest.json":
            termios_artifact_hashes(doc)
        if name == "build/original-sigset/manifest.json":
            sigset_artifact_hashes(doc)
        # Core is data, not a provenance map: representation payloads must not be
        # interpreted as filesystem paths. All other preparation JSON is scanned.
        if isinstance(doc, dict) and "bindings" in doc and "module" in doc:
            continue
        for raw, recorded in hashes_in(doc, current["toolchain"]):
            require(isinstance(recorded, str) and HEX.fullmatch(recorded), "Invalid original fingerprint")
            target, outside = original_name(root, raw)
            if outside:
                require(external_allowed(target, current), "Unknown external fingerprint: " + target)
                require(target not in external or external[target] == recorded, "Conflicting external fingerprint")
                # Installed tools and interfaces are gated by the pinned toolchain version.
                external[target] = recorded
            elif target in tracked:
                require(current["sources"].get(target) == recorded,
                        "Stale or unkeyed tracked source fingerprint: " + target)
            else:
                require(target not in expected or expected[target] == recorded, "Conflicting original fingerprint: " + target)
                expected[target] = recorded
                if target in payload:
                    require(payload[target] == recorded, "Conflicting already-read artifact: " + target)
                else:
                    pending.append(target)
    require(len(payload) <= MAX_FILES, "Too many fixture files")
    return payload, external


def safe_mode(mode, name):
    require(type(mode) is int and mode & ~0o755 == 0 and mode & 0o600 == 0o600,
            "Unsafe payload permissions: " + name)
    if mode & 0o111:
        path = PurePosixPath(name)
        require(native_executable(name) or (not path.suffix and
                ("oracle" in path.name or path.name == "aggregate-frontier")) or path.suffix in (".so", ".dylib"),
                "Executable non-native input: " + name)
    return mode


def pack(root, current, output):
    require(not output.exists() and not output.is_symlink(), "Bundle already exists; preserve the prior attempt")
    core = sorted(p.relative_to(root).as_posix() for d in CORE_DIRS
                  for p in file_path(root, d).glob("*.json"))
    def read(name):
        path = file_path(root, name)
        require(path.is_file(), "Missing prepared input: " + name)
        return path.read_bytes()
    payload, external = inventory(root, current, read, core)
    require(sum(file_path(root, n).stat().st_size for n in payload) <= MAX_TOTAL_BYTES, "Fixture bundle too large")
    modes = {n: safe_mode((stat.S_IMODE(file_path(root, n).stat().st_mode) & 0o555) | 0o600, n)
             for n in payload}
    manifest = {"schema": SCHEMA, "identity": current, "key": cache_key(current),
                "coreFiles": core, "payload": payload, "modes": modes, "external": external}
    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open("xb") as stream, tarfile.open(fileobj=stream, mode="w:gz", compresslevel=3) as archive:
        raw = canonical(manifest)
        info = tarfile.TarInfo("bundle.json"); info.size = len(raw)
        archive.addfile(info, io.BytesIO(raw))
        for name in sorted(payload):
            path = file_path(root, name)
            require(digest(path) == payload[name], "Input changed during pack: " + name)
            info = archive.gettarinfo(str(path), arcname="files/" + name)
            require(info.isfile(), "Nonregular file during pack: " + name)
            info.mode = modes[name]
            with path.open("rb") as data:
                archive.addfile(info, data)
    print(f"PACKED {len(payload)} native/Core input files; no JVM outcomes", file=sys.stderr)
    return manifest


def restore(root, current, source):
    require(source.is_file() and not source.is_symlink(), "Bundle missing or linked")
    with tarfile.open(source, "r:gz") as archive:
        members, total = [], 0
        for member in archive:
            require(member.isfile() and not member.issparse() and 0 <= member.size <= MAX_FILE_BYTES,
                    "Linked/sparse/nonregular/oversized archive member")
            total += member.size
            require(total <= MAX_TOTAL_BYTES and len(members) < MAX_FILES + 1, "Archive expansion too large")
            members.append(member)
        names = [m.name for m in members]
        require(len(names) == len(set(names)), "Duplicate archive member")
        require(all(m.isfile() and not m.issparse() and 0 <= m.size <= MAX_FILE_BYTES for m in members),
                "Linked/sparse/nonregular/oversized archive member")
        require(sum(m.size for m in members) <= MAX_TOTAL_BYTES, "Archive expansion too large")
        for name in names:
            relative(name)
        require("bundle.json" in names and archive.getmember("bundle.json").size <= MAX_MANIFEST_BYTES,
                "Missing/oversized bundle manifest")
        manifest = json.load(archive.extractfile("bundle.json"))
        require(isinstance(manifest, dict), "Bundle manifest must be an object")
        require(manifest.get("schema") == SCHEMA and manifest.get("identity") == current
                and manifest.get("key") == cache_key(current), "Source/toolchain/platform/workspace identity mismatch")
        core_files = manifest.get("coreFiles")
        require(isinstance(core_files, list) and all(isinstance(n, str) for n in core_files)
                and len(core_files) == len(set(core_files)), "Invalid Core inventory")
        payload = manifest.get("payload")
        require(isinstance(payload, dict) and payload, "Missing payload inventory")
        modes = manifest.get("modes")
        require(isinstance(modes, dict) and set(modes) == set(payload), "Invalid mode inventory")
        require(set(names) == {"bundle.json", *("files/" + relative(n) for n in payload)},
                "Unknown/missing archive member")
        require(names == ["bundle.json", *("files/" + n for n in sorted(payload))],
                "Noncanonical archive order")
        require(all(not any(str(p) in payload for p in PurePosixPath(n).parents) for n in payload),
                "Conflicting file/directory payload paths")
        tracked, pins = tracked_files(root), vendor_pins(root)
        documents, json_bytes = {}, 0
        # Every path, destination and byte digest is checked before any writes.
        for name in sorted(payload):
            expected = payload[name]
            require(name not in tracked and allowed_payload(name, pins), "Unknown/tracked archive payload: " + name)
            require(isinstance(expected, str) and HEX.fullmatch(expected), "Invalid payload fingerprint")
            path = file_path(root, name)
            mode = safe_mode(modes[name], name)
            require(archive.getmember("files/" + name).mode == mode, "Archive mode mismatch: " + name)
            for parent in path.parents:
                if parent == root:
                    break
                require(not parent.exists() or parent.is_dir(), "Non-directory destination parent")
            if path.exists():
                require(path.is_file() and digest(path) == expected, "Conflicting existing file: " + name)
                require(stat.S_IMODE(path.stat().st_mode) == mode, "Conflicting existing permissions: " + name)
            with archive.extractfile("files/" + name) as stream:
                if name.endswith(".json"):
                    json_bytes += archive.getmember("files/" + name).size
                    require(json_bytes <= MAX_JSON_BYTES, "Too much JSON metadata")
                    documents[name] = stream.read()
                    actual = sha(documents[name])
                else:
                    actual = digest_stream(stream)
                require(actual == expected, "Corrupt payload: " + name)
        def read(name):
            require(name in payload, "Missing original dependency: " + name)
            return documents[name]
        actual, external = inventory(root, current, read, core_files, verified=payload)
        require(actual == payload and external == manifest.get("external"), "Original provenance inventory mismatch")
        for name in sorted(payload):
            path = file_path(root, name)
            if not path.exists():
                path.parent.mkdir(parents=True, exist_ok=True)
                with archive.extractfile("files/" + name) as src, path.open("xb") as dst:
                    shutil.copyfileobj(src, dst, 1024 * 1024)
                path.chmod(modes[name])
    print(f"HIT {cache_key(current)}: verified {len(payload)} fixture files; tests must run", file=sys.stderr)
    return manifest


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    for name in ("key", "pack", "restore"):
        p = commands.add_parser(name)
        p.add_argument("--root", type=Path, default=Path.cwd())
        if name == "key":
            p.add_argument("--output", type=Path, required=True)
        else:
            p.add_argument("--identity", type=Path, required=True)
            p.add_argument("--bundle", type=Path, required=True)
    args = parser.parse_args(argv)
    try:
        root = args.root.resolve()
        if args.command == "restore":
            # A known miss needs no scan of the installed GHC libraries.
            require(args.bundle.is_file() and not args.bundle.is_symlink(), "Bundle missing or linked")
        current = identity(root)  # Never trust identity supplied by a producer.
        if args.command == "key":
            args.output.parent.mkdir(parents=True, exist_ok=True)
            with args.output.open("x") as stream:
                json.dump(current, stream, sort_keys=True, indent=2); stream.write("\n")
            print(cache_key(current))
        else:
            require(json.loads(args.identity.read_bytes()) == current, "Current identity changed since key step")
            if args.command == "pack":
                pack(root, current, args.bundle)
            else:
                restore(root, current, args.bundle)
        return 0
    except (CacheMiss, json.JSONDecodeError, UnicodeDecodeError, tarfile.TarError,
            gzip.BadGzipFile, zlib.error, EOFError) as error:
        print("MISS: " + str(error), file=sys.stderr)
        return 1
    except (OSError, subprocess.SubprocessError, ValueError, KeyError, TypeError) as error:
        print("ERROR: " + str(error), file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
