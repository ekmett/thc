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
import tempfile
import zlib

SCHEMA = 1
LINUX_X86_64_HOST = platform.system() == "Linux" and platform.machine() == "x86_64"
ERRNO_NATIVE_HOST = platform.system() in ("Linux", "Darwin") and sys.maxsize > 2**32
HEX = re.compile(r"[0-9a-f]{64}\Z")
ORIGINAL_UNIX_UNIT = re.compile(r"unix-2\.8\.8\.0-(?:inplace|[0-9a-f]+)\Z")
ORIGINAL_DIRECTORY_UNIT = re.compile(r"directory-1\.3\.10\.0-(?:inplace|[0-9a-f]+)\Z")
SELF = ".github/scripts/fast_inputs.py"
COMPILER_BUILD_INPUTS = ("thc.cabal", "cabal.project", "Setup.hs", "Makefile")
WIRED_SOURCE = "src/driver/THC/Driver/Wired.hs"


def wired_source_path(name):
    return "nih/pinned/ghc-9.14.1/libraries/ghc-internal/" + (
        "src/" if name.startswith("GHC/") else "") + name


# Runtime sources fingerprinted by fixture producers. An additional recorded
# runtime source fails closed until reviewed.
RUNTIME_INPUTS = ("src/main/c/stdio-abi-probe.c",
                  "src/main/c/native-process-signal-api.c",
                  "src/test/c/native-process-signals-test.c",
                  "src/test/resources/core/original-signal-install-descriptor.json",
                  "src/test/resources/core/original-unix-signal-install-descriptor.json",
                  "src/main/java/thc/runtime/CoreOriginalStdio.java", "src/main/java/thc/runtime/OriginalStdioOp.java",
                  "src/main/java/thc/runtime/ProcessIdentity.java",
                  "src/main/java/thc/runtime/CoreEnvironmentForeign.java", "src/main/java/thc/runtime/EnvironmentOp.java", "src/main/java/thc/runtime/EnvironmentExpression.java",
                  "src/main/java/thc/runtime/VectorMemoryFamily.java",
                  "src/main/java/thc/runtime/VectorMemoryOp.java",
                  "src/main/java/thc/runtime/VectorReadCase.java",
                  "src/main/java/thc/runtime/CoreVectorMemory.java",
                  "src/main/java/thc/runtime/VectorByteArrayExpression.java",
                  "src/main/java/thc/runtime/VectorMemory.java")
MANIFEST_DIRS = """mask-functions pinned-pointer-cells wide-char-address unix-libc proxy-void rubbish-literals ghc-bco simd-arithmetic stable-names simd-address-families simd-wide-arrays delimited-continuations scalar-memory-utilities simd128-arrays address-array-copy address-fields array-slices atomic-address pinned-addresses bit-primops float-decode floating-remainder integer-completion unaligned-scalar-memory
thread-status thread-label hint-trace closure-inspection thread-inventory thread-scheduling boxed-arrays boxed-array-extensions boxed-cas bytearray compare-byte-arrays data-to-tag double-arrays
explicit64-primops float-word-arrays fused-floating int-arrays int16-arrays int32-arrays
int8-arrays integer-primops managed-mvars managed-address-reads mutable-bytearray-size mutable-bytearrays mutvar stable-pointers weak-explicit shrink-bytearrays fetch-add-int-array atomic-int-arrays
native-addresses native-malloc original-stack original-stdio-read original-errno original-termios original-tcsetattr original-tcgetattr original-stdio-truncate original-fd-ready original-rts-locks rts-diagnostics rts-shutdown original-handle-readiness original-posix-stat resize-bytearrays scalar-bitcasts short-bytes-slices
show-word-list signed-narrow-primops simd-capability-smoke simd-calls simd-floatx4-fma simd-wide-floating-fma synchronous-exceptions tuple-arithmetic word-floating""".split()
UNIX_LIBC_OUTPUTS = frozenset("build/unix-libc/" + name for name in (
    "manifest.json", "pre.cbd", "post.cbd", "oracle.json",
    "pre.audit.json", "post.audit.json",
    *(f"logs/{command}.{suffix}" for command in ("version", "libdir", "imports", "unit",
      "pre-audit", "post-audit")
      for suffix in ("stdout", "stderr", "command.json"))))
ORIGINAL_PATH_STAT_ENTRIES = ("pathStat", "pathLstat", "unixPathLstat")
ORIGINAL_PATH_STAT_OUTPUTS = frozenset("build/original-path-stat/" + name for name in (
    "manifest.json", "pre.cbd", "post.cbd", "oracle.json",
    *(f"{stage}-{entry}.audit.json" for stage in ("pre", "post") for entry in ORIGINAL_PATH_STAT_ENTRIES),
    *(f"logs/{command}.{suffix}" for command in ("version", "libdir", "imports", "unit", "ghc-imports",
      *(f"{stage}-audit-{entry}" for stage in ("pre", "post") for entry in ORIGINAL_PATH_STAT_ENTRIES))
      for suffix in ("stdout", "stderr", "command.json"))))
ORIGINAL_PATH_MODE_ENTRIES = ("pathMkdir", "pathChmod")
ORIGINAL_PATH_MODE_OUTPUTS = frozenset("build/original-path-mode/" + name for name in (
    "manifest.json", "pre.cbd", "post.cbd", "oracle.json",
    *(f"{stage}-{entry}.audit.json" for stage in ("pre", "post") for entry in ORIGINAL_PATH_MODE_ENTRIES),
    *(f"logs/{command}.{suffix}" for command in ("version", "libdir", "imports", "unit", "ghc-imports",
      *(f"{stage}-audit-{entry}" for stage in ("pre", "post") for entry in ORIGINAL_PATH_MODE_ENTRIES))
      for suffix in ("stdout", "stderr", "command.json"))))
ORIGINAL_PATH_LINK_ENTRIES = ("pathSymlink", "pathReadlink", "pathRename")
ORIGINAL_PATH_LINK_OUTPUTS = frozenset("build/original-path-link/" + name for name in (
    "manifest.json", "pre.cbd", "post.cbd", "oracle.json",
    *(f"{stage}-{entry}.audit.json" for stage in ("pre", "post") for entry in ORIGINAL_PATH_LINK_ENTRIES),
    *(f"logs/{command}.{suffix}" for command in ("version", "libdir", "imports", "unit", "ghc-imports",
      *(f"{stage}-audit-{entry}" for stage in ("pre", "post") for entry in ORIGINAL_PATH_LINK_ENTRIES))
      for suffix in ("stdout", "stderr", "command.json"))))
ORIGINAL_DIRECTORY_PATHS_ENTRIES = ("pathRemoveDirectory", "executableReadlink")
ORIGINAL_DIRECTORY_PATHS_OUTPUTS = frozenset("build/original-directory-paths/" + name for name in (
    "manifest.json", "pre.cbd", "post.cbd", "oracle.json",
    *(f"{stage}-{entry}.audit.json" for stage in ("pre", "post") for entry in ORIGINAL_DIRECTORY_PATHS_ENTRIES),
    *(f"logs/{command}.{suffix}" for command in ("version", "libdir", "imports", "unit", "ghc-imports",
      *(f"{stage}-audit-{entry}" for stage in ("pre", "post") for entry in ORIGINAL_DIRECTORY_PATHS_ENTRIES))
      for suffix in ("stdout", "stderr", "command.json"))))
ORIGINAL_PATH_ACCESS_ENTRIES = ("pathAccess",)
ORIGINAL_PATH_ACCESS_OUTPUTS = frozenset("build/original-path-access/" + name for name in (
    "manifest.json", "pre.cbd", "post.cbd", "oracle.json",
    *(f"{stage}-{entry}.audit.json" for stage in ("pre", "post") for entry in ORIGINAL_PATH_ACCESS_ENTRIES),
    *(f"logs/{command}.{suffix}" for command in ("version", "libdir", "imports", "unit", "ghc-imports",
      *(f"{stage}-audit-{entry}" for stage in ("pre", "post") for entry in ORIGINAL_PATH_ACCESS_ENTRIES))
      for suffix in ("stdout", "stderr", "command.json"))))
ORIGINAL_UNLINKAT_ENTRIES = ("pathUnlinkAt",)
ORIGINAL_UNLINKAT_OUTPUTS = frozenset("build/original-unlinkat/" + name for name in (
    "manifest.json", "pre.cbd", "post.cbd", "oracle.json",
    *(f"{stage}-{entry}.audit.json" for stage in ("pre", "post") for entry in ORIGINAL_UNLINKAT_ENTRIES),
    *(f"logs/{command}.{suffix}" for command in ("version", "libdir", "imports", "unit", "ghc-imports", "abi-compile", "abi-run",
      *(f"{stage}-audit-{entry}" for stage in ("pre", "post") for entry in ORIGINAL_UNLINKAT_ENTRIES))
      for suffix in ("stdout", "stderr", "command.json"))))
ORIGINAL_FSTATAT_ENTRIES = ("pathFstatAt",)
ORIGINAL_FSTATAT_OUTPUTS = frozenset("build/original-fstatat/" + name for name in (
    "manifest.json", "pre.cbd", "post.cbd", "oracle.json",
    *(f"{stage}-{entry}.audit.json" for stage in ("pre", "post") for entry in ORIGINAL_FSTATAT_ENTRIES),
    *(f"logs/{command}.{suffix}" for command in ("version", "libdir", "imports", "unit", "ghc-imports", "abi-compile", "abi-run",
      *(f"{stage}-audit-{entry}" for stage in ("pre", "post") for entry in ORIGINAL_FSTATAT_ENTRIES))
      for suffix in ("stdout", "stderr", "command.json"))))
ORIGINAL_CURRENT_DIRECTORY_ENTRIES = ("pathChdir", "pathGetCwd")
ORIGINAL_CURRENT_DIRECTORY_OUTPUTS = frozenset("build/original-current-directory/" + name for name in (
    "manifest.json", "pre.cbd", "post.cbd", "oracle.json", "unix.project", "unix-source.json",
    *(f"{stage}-{entry}.audit.json" for stage in ("pre", "post") for entry in ORIGINAL_CURRENT_DIRECTORY_ENTRIES),
    *(f"logs/{command}.{suffix}" for command in ("version", "libdir", "imports", "unit", "ghc-imports", "native-child", "unix-extract", "unix-build",
      *(f"{stage}-audit-{entry}" for stage in ("pre", "post") for entry in ORIGINAL_CURRENT_DIRECTORY_ENTRIES))
      for suffix in ("stdout", "stderr", "command.json"))))
ORIGINAL_DIRECTORY_STREAMS_ENTRIES = ("directoryOpen", "directoryFdOpen", "directoryClose", "directoryRead", "directoryName", "directoryFree")
ORIGINAL_DIRECTORY_STREAMS_OUTPUTS = frozenset("build/original-directory-streams/" + name for name in (
    "manifest.json", "pre.cbd", "post.cbd", "oracle.json", "unix-source.json",
    *(f"{stage}-{entry}.audit.json" for stage in ("pre", "post") for entry in ORIGINAL_DIRECTORY_STREAMS_ENTRIES),
    *(f"logs/{command}.{suffix}" for command in ("version", "libdir", "imports", "unit", "ghc-imports",
      *(f"{stage}-audit-{entry}" for stage in ("pre", "post") for entry in ORIGINAL_DIRECTORY_STREAMS_ENTRIES))
      for suffix in ("stdout", "stderr", "command.json"))))
PROXY_VOID_OUTPUTS = frozenset("build/proxy-void/" + name for name in (
    "manifest.json", "oracle.tsv", "native/oracle", "api/predicate",
    *(f"{stage}/{suffix}" for stage in ("pre", "post")
      for suffix in ("core/ProxyVoidAudit.cbd", "core/THC.InterfaceClosure.cbd", "audit.json")),
    *(f"commands/{command}.{suffix}" for command in
      ("ghc-version", "predicate-build", "predicate-run", "native-build", "native-run", "pre-export", "pre-audit", "post-export", "post-audit")
      for suffix in ("stdout", "stderr", "command.json"))))
IO_MAIN_PAP_OUTPUTS = frozenset("build/io-main-pap/" + name for name in (
    "provenance.json", "oracle.tsv", "native-stderr.txt", "native/io-main-pap-oracle",
    *(f"{stage}/core/{module}.{extension}" for stage in ("pre", "post")
      for module in ("IoMainPapAudit", "THC.InterfaceClosure") for extension in ("cbd",)),
    *(f"{stage}/{entry}-audit.json" for stage in ("pre", "post") for entry in ("goodMain", "badMain"))))
WEAK_OUTPUTS = frozenset("build/weak-explicit/" + name for name in (
    "manifest.json", "oracle.tsv", "NativeWeak.hs",
    *(f"{stage}/{suffix}" for stage in ("pre", "post")
      for suffix in ("core/WeakAudit.cbd", "core/THC.InterfaceClosure.cbd", "audit.json"))))
MASK_FUNCTION_ENTRIES = ("maskedFunction", "unmaskedFunction", "uninterruptibleFunction", "lazyFunctions", "bareMasks")
MASK_FUNCTION_OUTPUTS = frozenset("build/mask-functions/" + name for name in (
    "manifest.json", "native/oracle",
    *(f"{stage}/core/MaskFunctionAudit.cbd" for stage in ("pre", "post")),
    *(f"{stage}/{entry}-audit.json" for stage in ("pre", "post") for entry in MASK_FUNCTION_ENTRIES),
    *(f"logs/{command}.{suffix}" for command in ("ghc-version", "native-compile", "native-oracle",
      *(f"{stage}-export" for stage in ("pre", "post")),
      *(f"{stage}-audit-{entry}" for stage in ("pre", "post") for entry in MASK_FUNCTION_ENTRIES))
      for suffix in ("stdout", "stderr", "command.json"))))
RUBBISH_OUTPUTS = frozenset("build/rubbish-literals/" + name for name in (
    "manifest.json", "pre.cbd", "post.cbd", "oracle.json", "pre.audit.json", "post.audit.json", "native.s", "native.o", "native-codegen.json",
    *(f"logs/{command}.{suffix}" for command in
      ("version", "info", "libdir", "native-assemble", "pre-audit", "post-audit")
      for suffix in ("stdout", "stderr", "command.json"))))
BCO_ENTRIES = ('bcoConstant', 'bcoApply', 'bcoApplyTwo', 'bcoFunction', 'bcoArithmetic', 'bcoBranch', 'bcoLargeOperand', 'bcoSharing', 'bcoCase', 'bcoCaseNested', 'bcoCasePointer', 'bcoCaseFloat', 'bcoCaseDouble', 'bcoCaseLong', 'bcoCaseVoid', 'bcoPacked8', 'bcoPacked16', 'bcoPacked32', 'bcoCaseTuple', 'bcoCaseTupleCall', 'bcoCaseTupleOverapply', 'bcoCapturedPap', 'bcoCapturedAp', 'bcoCapturedNoUpd', 'bcoCapturedApChain', 'bcoCapturedRecursive', 'bcoCapturedFloat', 'bcoCapturedDouble', 'bcoCapturedLong', 'bcoCapturedNoUpdEscape', 'bcoApplyIntCore', 'bcoApplyFloatCore', 'bcoApplyDoubleCore', 'bcoApplyLongCore', 'bcoApplyVoidCore')
BCO_COMMANDS = ("ghc-version", "native-build", "native-run",
                *(f"{stage}-export" for stage in ("pre", "post")),
                *(f"{stage}-audit-{entry}" for stage in ("pre", "post") for entry in BCO_ENTRIES))
BCO_OUTPUTS = frozenset("build/ghc-bco/" + name for name in (
    "manifest.json", *(f"{stage}/{suffix}" for stage in ("pre", "post")
        for suffix in ("core/GhcBCO.cbd", *(f"{entry}-audit.json" for entry in BCO_ENTRIES))),
    *(f"commands/{command}.{suffix}" for command in BCO_COMMANDS for suffix in ("stdout", "stderr", "command.json"))))
STABLE_NAME_ENTRIES = ("sameLifted", "sameUnlifted", "differentUnlifted", "unevaluatedName")
STABLE_NAME_COMMANDS = ("ghc-version", "native-build", "native-run",
                       *(f"{stage}-export" for stage in ("pre", "post")),
                       *(f"{stage}-audit-{entry}" for stage in ("pre", "post") for entry in STABLE_NAME_ENTRIES))
STABLE_NAME_OUTPUTS = frozenset("build/stable-names/" + name for name in (
    "manifest.json", *(f"{stage}/{suffix}" for stage in ("pre", "post")
        for suffix in ("core/StableNames.cbd", *(f"{entry}-audit.json" for entry in STABLE_NAME_ENTRIES))),
    *(f"commands/{command}.{suffix}" for command in STABLE_NAME_COMMANDS for suffix in ("stdout", "stderr", "command.json"))))
THREAD_INVENTORY_ENTRIES = ("selfInventory", "boundQuery", "snapshotSize", "forkSnapshot",
                            "lazyFork", "forkMasks", "selfKilledStatus", "parkedFork", "callbackObservation")
SCALAR_MEMORY_ENTRIES = ("memoryCase", "pinCase", "thawCase", "shrinkCase", "differenceCase",
                         "remainderCase", "numericDifference", "numericRemainder")
SCALAR_MEMORY_OUTPUTS = frozenset("build/scalar-memory-utilities/" + name for name in (
    "manifest.json", "oracle.tsv", "native/oracle",
    *(f"{stage}/{suffix}" for stage in ("pre", "post")
      for suffix in ("core/ScalarMemoryUtilities.cbd", "audit.json")),
    *(f"commands/{command}.{suffix}" for command in ("native-build", "native-oracle", "pre-export", "post-export", "pre-audit", "post-audit")
      for suffix in ("stdout", "stderr", "command.json"))))
DELIMITED_ENTRIES = ("promptPure", "abortSuffix", "resumeTwice", "nestedPrompts", "sameTagNearest",
                     "capturedCatch", "capturedMask", "escapedResume", "ambientMask", "resumedTail", "resumedJoin",
                     "resumedScalar", "recapturedMask", "resumedApplication", "resumedScalarApplication", "polymorphicApplications",
                     "polymorphicScalarApplications")
DELIMITED_COMMANDS = ("ghc-version", "native-build", "native-run",
                      "parked-native-build", "parked-native-run", "parked-export", "parked-audit",
                      *(f"{stage}-export" for stage in ("pre", "post")),
                      *(f"{stage}-audit-{entry}" for stage in ("pre", "post") for entry in DELIMITED_ENTRIES))
DELIMITED_OUTPUTS = frozenset("build/delimited-continuations/" + name for name in (
    "manifest.json", "parked/audit.json", "parked/core/ParkedControl.cbd",
    *(f"{stage}/{suffix}" for stage in ("pre", "post")
        for suffix in ("core/DelimitedContinuations.cbd", *(f"{entry}-audit.json" for entry in DELIMITED_ENTRIES))),
    *(f"commands/{command}.{suffix}" for command in DELIMITED_COMMANDS for suffix in ("stdout", "stderr", "command.json"))))
THREAD_INVENTORY_OUTPUTS = frozenset("build/thread-inventory/" + name for name in (
    "manifest.json", "oracle.txt", "callback-oracle.txt", *(f"{stage}/{suffix}" for stage in ("pre", "post")
        for suffix in ("core/ThreadInventory.cbd", *(f"{entry}-audit.json" for entry in THREAD_INVENTORY_ENTRIES)))))
THREAD_SCHEDULING_ENTRIES = ("emptySpark", "lazyPar", "lazySpark", "sparkValue", "currentCounter", "negativeCounter",
                             "pinnedFork", "otherCounter", "timedDelay")
THREAD_SCHEDULING_OUTPUTS = frozenset("build/thread-scheduling/" + name for name in (
    "manifest.json", "oracle.txt", *(f"{stage}/{suffix}" for stage in ("pre", "post")
        for suffix in ("core/ThreadScheduling.cbd", *(f"{entry}-audit.json" for entry in THREAD_SCHEDULING_ENTRIES)))))
SIMD_FLOAT_FMA_OUTPUTS = frozenset("build/simd-floatx4-fma/" + name for name in (
    "manifest.json", "oracle.txt", "pre-core/SimdFloatFma.cbd", "post-core/SimdFloatFma.cbd",
    "pre-audit.json", "post-audit.json", "pre-double-audit.json", "post-double-audit.json"))
INTEGER_COMPLETION_OUTPUTS = frozenset("build/integer-completion/" + name for name in (
    "manifest.json", "requests.tsv", "oracle.tsv", "NativeIntegerCompletion.hs", "native/integer-completion-oracle",
    *(f"{stage}{suffix}" for stage in ("pre", "post") for suffix in ("-audit.json", "-core/IntegerCompletionAudit.cbd")),
    *(f"commands/{command}.{suffix}" for command in ("ghc-version", "ghc-info", "pre-export", "post-export",
      "pre-audit", "post-audit", "native-build", "native-oracle") for suffix in ("stdout", "stderr", "command.json"))))
# These producers retain different command spellings and artifact layouts.
# Admit their exact recorded evidence, not arbitrary files under each directory.
ADDRESS_ARRAY_COPY_ENTRIES = ("addrToArray", "arrayToAddr", "mutableArrayToAddr")
ADDRESS_ARRAY_COPY_OUTPUTS = frozenset("build/address-array-copy/" + name for name in (
    "manifest.json", "inputs.tsv", "oracle.tsv", "native/oracle",
    *(f"{stage}-core/AddressArrayCopyAudit.cbd" for stage in ("pre", "post")),
    *(f"{stage}-{entry}-audit.json" for stage in ("pre", "post") for entry in ADDRESS_ARRAY_COPY_ENTRIES),
    *(f"commands/{label}.{suffix}" for label in (
        "native-build", "native-oracle", *(f"{stage}-export" for stage in ("pre", "post")),
        *(f"{stage}-{entry}-audit" for stage in ("pre", "post") for entry in ADDRESS_ARRAY_COPY_ENTRIES))
      for suffix in ("stdout", "stderr", "command.json")),
))
ATOMIC_INT_ARRAY_ENTRIES = (
    *(f"fetch{operation}Result" for operation in ("Add", "Sub", "And", "Nand", "Or", "Xor")),
    *(f"casInt{width}Result" for width in ("", "8", "16", "32", "64")), "atomicLoadStore")
ATOMIC_INT_ARRAY_OUTPUTS = frozenset("build/atomic-int-arrays/" + name for name in (
    "manifest.json", "NativeAtomicIntArrays.hs", "requests.tsv", "oracle.tsv",
    *(f"{stage}/core/{module}.cbd" for stage in ("pre", "post") for module in ("AtomicIntArrayAudit", "THC.InterfaceClosure")),
    *(f"{stage}/{entry}.audit.json" for stage in ("pre", "post") for entry in ATOMIC_INT_ARRAY_ENTRIES),
    *(f"commands/{label}.{suffix}" for label in (
        "native-build", "native-oracle", *(f"{stage}-export" for stage in ("pre", "post")),
        *(f"{stage}-{entry}-audit" for stage in ("pre", "post") for entry in ATOMIC_INT_ARRAY_ENTRIES))
      for suffix in ("stdout", "stderr", "command.json")),
))
ATOMIC_ADDRESS_OUTPUTS = frozenset("build/atomic-address/" + name for name in (
    "manifest.json", "inputs.txt", "oracle.tsv",
    *(f"{stage}/{name}" for stage in ("pre", "post") for name in ("core/AtomicAddressAudit.cbd", "audit.json")),
    *(f"logs/{label}.{suffix}" for label in (
        "version", "native-build", "native-oracle", *(f"{step}-{stage}" for stage in ("pre", "post") for step in ("export", "audit")))
      for suffix in ("stdout", "stderr", "command.json")),
))
UNALIGNED_SCALAR_MEMORY_OUTPUTS = frozenset("build/unaligned-scalar-memory/" + name for name in (
    "manifest.json", "inputs.txt", "oracle.tsv",
    *(f"{stage}/{name}" for stage in ("pre", "post") for name in ("core/UnalignedScalarMemoryAudit.cbd", "audit.json")),
    *(f"logs/{label}.{suffix}" for label in (
        "ghc-version", "ghc-inventory", "native-build", "native-oracle",
        *(f"{stage}-{step}" for stage in ("pre", "post") for step in ("export", "audit")))
      for suffix in ("stdout", "stderr", "command.json")),
))
PINNED_POINTER_CELL_OUTPUTS = frozenset("build/pinned-pointer-cells/" + name for name in (
    "manifest.json", "oracle.tsv",
    *(f"{stage}/{name}" for stage in ("pre", "post")
      for name in ("audit.json", "core/PinnedPointerCellsAudit.cbd", "core/THC.InterfaceClosure.cbd")),
))
WIDE_CHAR_ADDRESS_OUTPUTS = frozenset("build/wide-char-address/" + name for name in (
    "manifest.json", "native/oracle",
    *(f"{stage}/{name}" for stage in ("pre", "post")
      for name in ("audit.json", "core/WideCharAddressAudit.cbd", "core/THC.InterfaceClosure.cbd")),
    *(f"logs/{label}.{suffix}" for label in (
        "ghc-version", "ghc-info", "native-compile", "native-oracle",
        *(f"{stage}-{step}" for stage in ("pre", "post") for step in ("export", "audit")))
      for suffix in ("stdout", "stderr", "command.json")),
))
MEMORY_FIXTURE_OUTPUTS = {
    "pinned-pointer-cells": PINNED_POINTER_CELL_OUTPUTS,
    "wide-char-address": WIDE_CHAR_ADDRESS_OUTPUTS,
    "address-array-copy": ADDRESS_ARRAY_COPY_OUTPUTS,
    "atomic-int-arrays": ATOMIC_INT_ARRAY_OUTPUTS,
    "atomic-address": ATOMIC_ADDRESS_OUTPUTS,
    "unaligned-scalar-memory": UNALIGNED_SCALAR_MEMORY_OUTPUTS,
}
HINT_TRACE_OUTPUTS = frozenset("build/hint-trace/" + name for name in (
    "manifest.json", "oracle.tsv", "native/oracle", "native/oracle.eventlog",
    *(f"{stage}/{suffix}" for stage in ("pre", "post")
      for suffix in ("core/HintTraceAudit.cbd", "hints.audit.json", "traces.audit.json",
                     "event.audit.json", "marker.audit.json", "binary.audit.json", "addressHints.audit.json"))))
SIMD_ARITHMETIC_SHAPES = ["Int8X32","Word8X32","Int8X64","Word8X64","Int16X32","Word16X32","Word64X2","Word32X8","Int32X8","Int32X16","Int64X2","FloatX4","DoubleX2","FloatX8","DoubleX4","Int64X4","Int64X8","Word64X4","Word64X8","Word32X16","FloatX16","DoubleX8","Int8X16","Int16X8","Int32X4","Word8X16","Word16X8","Word32X4","Int16X16","Word16X16"]
SIMD_ARITHMETIC_ENTRIES = tuple("shuffle" + shape + suffix for shape in SIMD_ARITHMETIC_SHAPES
    for suffix in ("Pattern1", "Pattern2"))
SIMD_ARITHMETIC_COMMANDS = ("ghc-version", "native-build", "native-oracle", "pre-export",
    *(name + "-audit" for name in SIMD_ARITHMETIC_ENTRIES))
SIMD_ARITHMETIC_OUTPUTS = frozenset("build/simd-arithmetic/" + name for name in (
    "manifest.json", "inputs.tsv", "oracle.tsv", "native/oracle", "pre-core/SimdArithmeticAudit.cbd",
    "sources/SimdArithmeticAudit.hs", "sources/SimdArithmeticScalar.hs", "sources/Native.hs",
    *(name + "-audit.json" for name in SIMD_ARITHMETIC_ENTRIES),
    *("commands/" + command + "." + suffix for command in SIMD_ARITHMETIC_COMMANDS
      for suffix in ("stdout", "stderr", "command.json"))))
CLOSURE_INSPECTION_OUTPUTS = frozenset("build/closure-inspection/" + name for name in (
    "manifest.json", "oracle.tsv", "native/oracle", "core/ClosureInspectionAudit.cbd",
    *(name + ".audit.json" for name in ("payload", "sizeConsistent", "pointerCount", "notStack",
                                       "noCCS", "noProvenance", "cleared", "annotated", "annotatedResume"))))
SIMD_WIDE_ARRAY_ENTRIES = tuple(shape + operation + mode
    for shape in ("int8X32","word8X32","int8X64","word8X64","int16X32","word16X32",
                  "int16X16","word16X16","int32X8","word32X8","int32X16","word32X16","int64X4","word64X4","int64X8","word64X8","floatX8","floatX16","doubleX4","doubleX8")
    for operation in ("Index", "Read", "Write") for mode in ("Packed", "Scalar"))
SIMD_WIDE_ARRAY_COMMANDS = ("ghc-version", "native-build", "native-oracle", "pre-export",
    *("pre-" + name + "-audit" for name in SIMD_WIDE_ARRAY_ENTRIES))
SIMD_WIDE_ARRAY_OUTPUTS = frozenset("build/simd-wide-arrays/" + name for name in (
    "manifest.json", "inputs.tsv", "oracle.tsv", "native/oracle", "pre-core/SimdWideArrayAudit.cbd",
    *("pre-" + name + "-audit.json" for name in SIMD_WIDE_ARRAY_ENTRIES),
    *("commands/" + command + "." + suffix for command in SIMD_WIDE_ARRAY_COMMANDS
      for suffix in ("stdout", "stderr", "command.json"))))
SIMD_ADDRESS_SHAPES = ("int8X16", "word8X16", "int16X8", "word16X8", "int64X2", "word64X2",
    "int8X32", "word8X32", "int8X64", "word8X64", "int16X32", "word16X32",
    "int32X4", "word32X4", "floatX4", "doubleX2", "int16X16", "word16X16",
    "int32X8", "word32X8", "int32X16", "word32X16", "int64X4", "word64X4", "int64X8", "word64X8",
    "floatX8", "floatX16", "doubleX4", "doubleX8")
SIMD_ADDRESS_ENTRIES = tuple(shape + operation + mode for shape in SIMD_ADDRESS_SHAPES
    for operation in ("Index", "Read", "Write") for mode in ("Packed", "Scalar")) + ("word64X2RoundtripScalar",)
# Eight independent seeds and three offsets per entry, including the owned roundtrip.
SIMD_ADDRESS_ROWS = len(SIMD_ADDRESS_ENTRIES) * 8 * 3
SIMD_ADDRESS_128_ROWS = sum(bool(re.match(
    r"(?:(?:int8|word8)X16|(?:int16|word16)X8|(?:int32|word32|float)X4|(?:int64|word64|double)X2)[A-Z]", entry))
    for entry in SIMD_ADDRESS_ENTRIES) * 8 * 3
SIMD_ADDRESS_NATIVE128 = platform.machine().lower() not in ("arm64", "aarch64")
SIMD_ADDRESS_STAGES = (("pre", "SimdAddressAudit"),) + ((("post128", "SimdAddress128Audit"),) if SIMD_ADDRESS_NATIVE128 else ())
SIMD_ADDRESS_MODES = ("scalar",) + (("vector128",) if SIMD_ADDRESS_NATIVE128 else ())
SIMD_ADDRESS_COMMANDS = ("ghc-version", *(mode + "-" + phase for mode in SIMD_ADDRESS_MODES for phase in ("build", "oracle")),
    *(stage + "-" + phase for stage, _ in SIMD_ADDRESS_STAGES for phase in ("export", "audit")))
SIMD_ADDRESS_OUTPUTS = frozenset("build/simd-address-families/" + path for path in (
    "manifest.json", *("source/" + name + ".hs" for name in
      ("SimdAddressAudit", "SimdAddress128Audit", "SimdAddressScalar", "ScalarNative", "VectorNative")),
    *(path for mode in SIMD_ADDRESS_MODES for path in (mode + "-inputs.tsv", mode + "-oracle.tsv", mode + "/oracle")),
    *(path for stage, module in SIMD_ADDRESS_STAGES for path in (stage + "-core/" + module + ".cbd", stage + "-audit.json")),
    *("commands/" + command + "." + suffix for command in SIMD_ADDRESS_COMMANDS for suffix in ("stdout", "stderr", "command.json"))))
SIMD128_ARRAY_ENTRIES = tuple(shape + operation + mode
    for shape in ("int8X16", "word8X16", "int16X8", "word16X8", "int64X2", "word64X2")
    for operation in ("Index", "Read", "Write") for mode in ("Packed", "Scalar"))
SIMD128_ARRAY_COMMANDS = ("ghc-version", "native-build", "native-oracle",
    *(stage + "-export" for stage in ("pre", "post")),
    *(stage + "-" + name + "-audit" for stage in ("pre", "post") for name in SIMD128_ARRAY_ENTRIES))
SIMD128_ARRAY_OUTPUTS = frozenset("build/simd128-arrays/" + name for name in (
    "manifest.json", "inputs.tsv", "oracle.tsv", "native/oracle",
    *(stage + "-core/Simd128ArrayAudit.cbd" for stage in ("pre", "post")),
    *(stage + "-" + name + "-audit.json" for stage in ("pre", "post") for name in SIMD128_ARRAY_ENTRIES),
    *("commands/" + command + "." + suffix for command in SIMD128_ARRAY_COMMANDS
      for suffix in ("stdout", "stderr", "command.json"))))
BIGNUM_SOURCES = frozenset(wired_source_path(path) for path in (
    "include/WordSize.h", "LICENSE", *(f"GHC/Internal/Bignum/{name}{suffix}"
      for name in ("BigNat", "Integer", "Natural") for suffix in (".hs", ".hs-boot"))))
BYTEARRAY_FAMILIES = {
    "bytearray": ("ByteArrayAudit", ("orderedBytes", "copiedBytes"), "NativeByteArray.hs"),
    "mutable-bytearrays": ("MutableByteArrayAudit", ("filledBytes", "movedBytes", "disjointBytes", "copiedMutableBytes", "copiedDisjointBytes", "publicReplicate"), "NativeMutableByteArrays.hs"),
    "resize-bytearrays": ("ResizeByteArrayAudit", ("resizedBytes", "resizedTwiceWrites"), None),
    "mutable-bytearray-size": ("MutableByteArraySizeAudit", ("freshSize", "pureSize", "resizedSizes", "pureAfterResize", "orderedSize"), None),
    "compare-byte-arrays": ("CompareByteArraysAudit", ("shortCompare", "shortPrefix", "shortSuffix", "rangeCompare", "aliasCompare"), "NativeCompareByteArrays.hs"),
}
BYTEARRAY_OUTPUTS = {}
for _family, (_module, _entries, _driver) in BYTEARRAY_FAMILIES.items():
    _original = _family == "compare-byte-arrays"
    _commands = ("ghc-version", "ghc-info", "bytestring-version", "bytestring-description", "native-build", "native-oracle") + (
        () if _original or _family == "bytearray" else ("compiler-build", "primop-coverage")) + (() if _driver else ("native-inputs",)) + tuple(
        name for stage in ("pre", "post") for name in (
            f"{stage}-export", *([f"{stage}-original-list"] if _original else []),
            *(f"{stage}-{entry}-audit" for entry in _entries)))
    _stage_outputs = tuple(name for stage in ("pre", "post") for name in (
            *(f"{stage}/core/{module}.cbd" for module in (_module, "THC.InterfaceClosure", "GHC.Internal.Base", "GHC.Internal.List")),
            f"{stage}/boot-provenance.json", *(f"{stage}/{entry}.audit.json" for entry in _entries))) if _original else tuple(
        name for stage in ("pre", "post") for name in (
            *(f"{stage}-core/{module}.cbd" for module in (_module, "THC.InterfaceClosure")),
            *(f"{stage}-{entry}.audit.json" for entry in _entries)))
    BYTEARRAY_OUTPUTS[_family] = frozenset(f"build/{_family}/" + name for name in (
        "manifest.json", "requests.tsv", "oracle.tsv", f"native/{_family}-oracle", *([_driver] if _driver else []), *_stage_outputs,
        *(f"commands/{name}.{suffix}" for name in _commands for suffix in ("stdout", "stderr", "command.json"))))
del _family, _module, _entries, _driver, _original, _commands, _stage_outputs
BYTEARRAY_NATIVES = frozenset(f"build/{family}/native/{family}-oracle" for family in BYTEARRAY_FAMILIES)
BYTEARRAY_SOURCES = frozenset(wired_source_path(name) for name in (
    "LICENSE", "GHC/Internal/Base.hs", "GHC/Internal/List.hs", "GHC/Internal/Exception/Type.hs-boot",
    "GHC/Internal/IO.hs-boot", "GHC/Internal/Num.hs-boot", "GHC/Internal/Enum.hs-boot", "GHC/Internal/Real.hs-boot"))
SIMD_BYTEARRAY_FAMILIES = {
    "simd-int32x4-bytearray": ("SimdInt32X4ByteArray", 9666, ("Word32ElemRep",)),
    "simd-word32x4-bytearray": ("SimdWord32X4ByteArray", 9666, ("Int32ElemRep",)),
    "simd-floatx4-bytearray": ("SimdFloatX4ByteArray", 6720, ("Int32ElemRep", "Word32ElemRep", "DoubleElemRep")),
    "simd-doublex2-bytearray": ("SimdDoubleX2ByteArray", 4384, ("Int64ElemRep", "Int32ElemRep", "Word32ElemRep", "FloatElemRep")),
}
def simd_bytearray_outputs(family, attempt, native):
    """Fixed proof/command inventory; never accept arbitrary prepare-run contents."""
    module, _, wrong = SIMD_BYTEARRAY_FAMILIES[family]
    floating = "floatx4" in family or "doublex2" in family
    root = f"build/{family}"
    require(isinstance(attempt, str) and re.fullmatch(re.escape(root) + r"/prepare-run-[A-Za-z0-9]+", attempt),
            "Invalid SIMD memory attempt")
    stages = ("pre", "post") if native else ("pre",)
    entries = tuple(f"{offset}{op}Case" for offset in ("vector", "scalar") for op in
                    (("Unit", "Index", "Read", "Write", "GraphIndex", "GraphStore") if floating else ("Unit", "Index", "Read", "Write", "Store")))
    graphs = tuple(f"{offset}{op}" for offset in ("vector", "scalar") for op in
                   (("IndexGraph" if floating else "IndexWorker"), "StoreGraph"))
    frontiers = ("vectorArgument", "readVectorEscape", "readTupleEscape",
                 "vectorReadWorker", "vectorWriteWorker", "scalarReadWorker", "scalarWriteWorker")
    local = tuple(f"{offset}{op}" for offset in ("vector", "scalar") for op in ("Index", "Read", "Write"))
    mutations = tuple(f"{stage}-wrong-{element}-{entry}" for stage in stages
                      for element in wrong for entry in local)
    audits = (*(f"{stage}-{entry}" for stage in stages for entry in (*entries, *graphs, *frontiers)),
              *mutations)
    commands = ("ghc-version", "ghc-info", "host", "architecture", "system", "compiler-build",
                *(f"{stage}-export" for stage in stages), *audits,
                *(("native-build", "native-oracle") if native else ()), *(("snan-oracle",) if native and floating else ()))
    return frozenset((
        f"{root}/expected.tsv", f"{root}/requests.tsv",
        *(f"{root}/{stage}-core/{module}.cbd" for stage in stages),
        *(f"{root}/{stage}-audit.json" for stage in stages),
        *(f"{attempt}/audits/{label}.json" for label in audits),
        *(f"{attempt}/mutations/{label}.cbd" for label in mutations),
        *(f"{attempt}/commands/{label}.{suffix}" for label in commands for suffix in ("stdout", "stderr", "command.json")),
        *((f"{root}/oracle.tsv", f"{root}/native/{family.removeprefix('simd-')}-oracle") if native else ()),
        *(f"{root}/{name}.tsv" for name in ("snan-expected", "snan-requests", "snan-oracle") if native and floating)))


def simd_bytearray_artifact_hashes(family, manifest):
    stages = manifest.get("stages")
    require(stages in (["pre"], ["pre", "post"]), "Invalid SIMD memory stages")
    native = len(stages) == 2
    _, rows, _ = SIMD_BYTEARRAY_FAMILIES[family]
    require(type(manifest.get("schema")) is int and manifest["schema"] == 1 and
            manifest.get("vector") == family.removeprefix("simd-"), "Invalid SIMD memory schema/family")
    require(type(manifest.get("modelRows")) is int and manifest["modelRows"] == rows and
            manifest.get("modelByteOrder") == "little", "Invalid SIMD memory model mode")
    require((type(manifest.get("nativeRows")) is int and manifest["nativeRows"] == rows and
             manifest.get("nativeByteOrder") == "little" and manifest.get("modelMatched") is True) if native else
            all(key in manifest and manifest[key] is None for key in ("nativeRows", "nativeByteOrder", "modelMatched")),
            "Invalid SIMD memory native mode")
    required = simd_bytearray_outputs(family, manifest.get("attempt"), native)
    records = manifest.get("artifacts")
    require(isinstance(records, list) and all(isinstance(row, dict) and set(row) == {"path", "sha256"} for row in records),
            "Invalid SIMD memory artifact records")
    hashes = {row["path"]: row["sha256"] for row in records}
    require(len(records) == len(hashes) and set(hashes) == required, "Incomplete SIMD memory artifact inventory")
    require(all(isinstance(value, str) and HEX.fullmatch(value) for value in hashes.values()), "Invalid SIMD memory artifact hash")
    return hashes
PINNED_ADDRESS_ENTRIES = ("pinnedBytes", "alignedBytes", "keepAliveWord8", "keepAliveLazy", "fingerprintByte")
PINNED_ADDRESS_NEGATIVES = ("read-word-not-word8", "write-word-not-word8", "read-address-is-word", "read-state-is-int",
    "read-offset-is-word", "contents-lifted-array", "contents-result-is-word", "allocation-size-is-word",
    "allocation-state-is-int", "aligned-alignment-is-word", "keepalive-state-is-int", "keepalive-result-word-not-word8")
PINNED_ADDRESS_COMMANDS = ("native-build", "native-oracle", *(f"{stage}-export" for stage in ("pre", "post")),
    *(f"{stage}-{name}-audit" for stage in ("pre", "post") for name in
      (*PINNED_ADDRESS_ENTRIES, *(f"negative-{label}" for label in PINNED_ADDRESS_NEGATIVES))))
PINNED_ADDRESS_OUTPUTS = frozenset("build/pinned-addresses/" + path for path in (
    "manifest.json", "requests.tsv", "expected.tsv", "oracle.tsv",
    *(f"native/{name}" for name in ("pinned-address-oracle", "Main.hi", "Main.o", "PinnedAddressAudit.hi", "PinnedAddressAudit.o")),
    *(f"{stage}/core/{name}.{extension}" for stage in ("pre", "post")
      for name in ("PinnedAddressAudit", "THC.InterfaceClosure") for extension in ("cbd",)),
    *(f"{stage}/negative-proofs.json" for stage in ("pre", "post")),
    *(f"{stage}/{name}.audit.json" for stage in ("pre", "post") for name in
      (*PINNED_ADDRESS_ENTRIES, *(f"negative-{label}" for label in PINNED_ADDRESS_NEGATIVES))),
    *(f"{stage}/negative/{label}-{index}.cbd" for stage in ("pre", "post") for label in PINNED_ADDRESS_NEGATIVES for index in (0, 1)),
    *(f"commands/{name}.{suffix}" for name in PINNED_ADDRESS_COMMANDS for suffix in ("stdout", "stderr", "command.json"))))
SIMD_WIDE_FMA_OUTPUTS = frozenset("build/simd-wide-floating-fma/" + name for name in (
    "manifest.json", "oracle.txt", "pre-core/SimdWideFloatFma.cbd", "pre-audit.json", "pre-double-audit.json"))
SIMD_SMOKE_SOURCES = frozenset("build/generated/simd/fixtures/" + name for name in (
    "GeneratedSimdSmoke.hs", "GeneratedSimdSmokeScalar.hs",
    "GeneratedSimdSmokeScalarNative.hs", "GeneratedSimdSmokeVectorNative.hs"))
SIMD_SMOKE_OUTPUTS = SIMD_SMOKE_SOURCES | frozenset("build/simd-capability-smoke/" + name for name in (
    "manifest.json", "pre-core/GeneratedSimdSmoke.cbd", "audits.json", "cases.tsv", "native/simd-smoke-oracle"))
PROVENANCE_DIRS = """io-main-pap
tag-to-enum
unsafe-equality simd simd-int32x4 simd-floatx4
simd-doublex2 simd-int32x4-bytearray simd-word32x4-bytearray
simd-floatx4-bytearray simd-doublex2-bytearray""".split()
CHECK_DIRS = """tag-to-enum
unsafe-equality""".split()
AGGREGATE_HOST_CBD_OUTPUTS = frozenset({
    "build/floating/core/FloatingAudit.cbd",
    *(f"build/{family}/{stage}-core/{module}.cbd" for family, module in (
        ("floating-tuple", "FloatingTupleAudit"), ("aggregate-layout", "AggregateLayoutAudit"),
        ("sum-layout", "SumLayoutAudit"), ("sum-result", "SumResultAudit"))
      for stage in ("pre", "post")),
    *(f"build/tag-to-enum/{stage}-core/{module}.cbd" for stage in ("pre", "post")
      for module in ("TagToEnumAudit", "TagToEnumExternal", "TagToEnumFrontier")),
})

SUM_RESULT_OUTPUTS = frozenset({
    *(f"build/sum-result/{stage}-core/SumResultAudit.cbd" for stage in ("pre", "post")),
    *(f"build/sum-result/{stage}-ghc/SumResultAudit.{suffix}" for stage in ("pre", "post") for suffix in ("hi", "o")),
    "build/sum-result/native/oracle", "build/sum-result/oracle.tsv", "build/sum-result/oracle-pairs.tsv",
    *(f"build/sum-result/native/{module}.{suffix}" for module in ("Main", "SumResultAudit") for suffix in ("hi", "o")),
})

FLOATING_TUPLE_OUTPUTS = frozenset({
    *(f"build/floating-tuple/{stage}-core/FloatingTupleAudit.cbd" for stage in ("pre", "post")),
    *(f"build/floating-tuple/{stage}-ghc/FloatingTupleAudit.{suffix}" for stage in ("pre", "post") for suffix in ("hi", "o")),
    "build/floating-tuple/native/oracle", "build/floating-tuple/oracle.tsv", "build/floating-tuple/bits.tsv",
    *(f"build/floating-tuple/native/{module}.{suffix}" for module in ("Main", "FloatingTupleAudit") for suffix in ("hi", "o")),
})

BASE_CORE_CBD_OUTPUTS = frozenset({
    *(f"build/core/{module}.cbd" for module in ("THC.Prim.Test", "Fixtures", "StrictFields", "SpeculationAudit",
        "RepresentationAudit", "SourceNotes", "CBVAudit", "CBVCoercionAudit", "ConstructorFieldAudit", "DemandAudit")),
    *(f"build/cbv-post-core/{module}.cbd" for module in ("CBVAudit", "CBVCoercionAudit")),
    *(f"build/source-core/{module}.cbd" for module in ("SourceNotes", "RepresentationAudit")),
    *(f"build/map/{folder}/GHC.Internal.{module}.cbd" for folder in ("core", "boot-core") for module in ("CString", "Err")),
    "build/map/core/GHC.InterfaceClosure.cbd",
})

CORE_CONTRACT_CBD_REQUIRED = frozenset({
    *(f"build/core/{module}.cbd" for module in ("StrictFields", "CBVAudit", "CBVCoercionAudit", "DemandAudit")),
    *(f"build/cbv-post-core/{module}.cbd" for module in ("CBVAudit", "CBVCoercionAudit")),
    "build/source-core/RepresentationAudit.cbd", "build/tuple-arithmetic/pre-core/TupleArithmeticAudit.cbd",
})

CORE_DIRS = ("build/core", "build/cbv-post-core", "build/source-core", "build/map/core", "build/map/boot-core")
REQUIRED = tuple(sorted({
    *CORE_CONTRACT_CBD_REQUIRED,
    *BASE_CORE_CBD_OUTPUTS,
    *AGGREGATE_HOST_CBD_OUTPUTS,
    *SUM_RESULT_OUTPUTS,
    *FLOATING_TUPLE_OUTPUTS,
    *RUBBISH_OUTPUTS,
    *PROXY_VOID_OUTPUTS,
    *WEAK_OUTPUTS,
    *UNIX_LIBC_OUTPUTS,
    *(ORIGINAL_PATH_STAT_OUTPUTS if platform.system() == "Linux" else []),
    *(ORIGINAL_PATH_MODE_OUTPUTS if platform.system() == "Linux" else []),
    *(ORIGINAL_PATH_LINK_OUTPUTS if platform.system() == "Linux" else []),
    *(ORIGINAL_DIRECTORY_PATHS_OUTPUTS if platform.system() == "Linux" else []),
    *(ORIGINAL_PATH_ACCESS_OUTPUTS if platform.system() == "Linux" else []),
    *(ORIGINAL_UNLINKAT_OUTPUTS if platform.system() == "Linux" else []),
    *(ORIGINAL_FSTATAT_OUTPUTS if platform.system() == "Linux" else []),
    *(ORIGINAL_CURRENT_DIRECTORY_OUTPUTS if platform.system() == "Linux" else []),
    *(ORIGINAL_DIRECTORY_STREAMS_OUTPUTS if platform.system() == "Linux" else []),
    *(f"build/{d}/manifest.json" for d in MANIFEST_DIRS),
    *(f"build/{d}/provenance.json" for d in PROVENANCE_DIRS),
    *(f"build/{d}/checks.json" for d in CHECK_DIRS),
    "build/floating/checks.json", "build/primop-coverage.json",
    "build/scalar-signatures/provenance.json",
    "build/native/oracle.tsv",
    "build/map/boot-provenance.json", "build/corpus/corpus.json",

}))
BUILD_DIRS = frozenset(MANIFEST_DIRS + PROVENANCE_DIRS + ["original-path-stat", "original-path-mode", "original-path-link", "original-directory-paths", "original-path-access", "original-unlinkat", "original-fstatat", "original-current-directory", "original-directory-streams", "floating", "corpus",
    "scalar-signatures", "native", "map"] +
    [PurePosixPath(p).name for p in CORE_DIRS])
FLOAT_DECODE_COMMANDS = ("native-build", "native-oracle",
                        *(label for stage in ("pre", "post") for label in
                          (f"{stage}-export", f"{stage}-audit")))
FLOAT_DECODE_OUTPUTS = frozenset("build/float-decode/" + name for name in (
    "manifest.json", "inputs.tsv", "oracle.tsv", "native/oracle",
    "original/core/GHC.Internal.Bignum.Integer.cbd", "original/boot-provenance.json",
    *(f"{stage}-core/{module}.cbd" for stage in ("pre", "post") for module in ("FloatDecodeAudit", "FloatDecode")),
    *(f"{stage}-audit.json" for stage in ("pre", "post")),
    *(f"commands/{command}.{suffix}" for command in FLOAT_DECODE_COMMANDS for suffix in ("stdout", "stderr", "command.json"))))
FLOATING_REMAINDER_ENTRIES = (*(op + kind for kind in ("Float", "Double") for op in ("asinh", "acosh", "atanh", "min", "max")),
                            "decodeWordsDirect", "decodeWordsCall", "asinhExample")
FLOATING_REMAINDER_COMMANDS = ("native-build", "native-oracle",
                              *(label for stage in ("pre", "post") for label in
                                (f"{stage}-export", *(f"{stage}-{name}-audit" for name in FLOATING_REMAINDER_ENTRIES))))
FLOATING_REMAINDER_OUTPUTS = frozenset("build/floating-remainder/" + name for name in (
    "manifest.json", "inputs.tsv", "oracle.tsv", "native/oracle",
    *(f"{stage}-core/{module}.cbd" for stage in ("pre", "post") for module in ("FloatingRemainderAudit", "InverseHyperbolic")),
    *(f"{stage}-{entry}-audit.json" for stage in ("pre", "post") for entry in FLOATING_REMAINDER_ENTRIES),
    *(f"commands/{command}.{suffix}" for command in FLOATING_REMAINDER_COMMANDS for suffix in ("stdout", "stderr", "command.json"))))
MAX_FILES = 30000
MAX_FILE_BYTES = 256 * 1024 * 1024
MAX_TOTAL_BYTES = 3 * 1024 * 1024 * 1024
MAX_MANIFEST_BYTES = 16 * 1024 * 1024
MAX_JSON_BYTES = 384 * 1024 * 1024
NATIVE_EXECUTABLES = frozenset({"build/simd/native/simd", "build/simd-int32x4/native/simd", "build/wide-char-address/native/oracle", "build/io-main-pap/native/io-main-pap-oracle", "build/mask-functions/native/oracle", "build/proxy-void/native/oracle", "build/proxy-void/api/predicate", "build/simd-arithmetic/native/oracle", "build/unsafe-equality/api/predicate", "build/float-decode/native/oracle",
    "build/floating-remainder/native/oracle",
    "build/pinned-addresses/native/pinned-address-oracle",
    "build/integer-completion/native/integer-completion-oracle",
    "build/bit-primops/native/bit-primops-oracle",
    "build/signed-narrow-primops/native/signed-narrow-primops-oracle",
    "build/explicit64-primops/native/explicit64-oracle",
    "build/hint-trace/native/oracle",
    "build/closure-inspection/native/oracle",
    "build/simd-wide-arrays/native/oracle",
    "build/simd128-arrays/native/oracle",
    "build/simd-address-families/scalar/oracle", "build/simd-address-families/vector128/oracle",
    "build/scalar-memory-utilities/native/oracle",
    "build/simd-capability-smoke/native/simd-smoke-oracle",
    "build/original-stdio-read/native/original-stdio-read-oracle",
    "build/original-errno/native/oracle",
    "build/original-tcsetattr/native/oracle",
    "build/original-tcgetattr/native/oracle",
    "build/original-termios/saved/native/oracle",
    "build/original-stdio-truncate/native/oracle",
    "build/original-fd-ready/native/oracle",
    "build/original-handle-readiness/native/oracle",
    "build/original-posix-stat/native/oracle",
})
ORIGINAL_STDIO_READ_OUTPUTS = frozenset("build/original-stdio-read/" + name for name in (
    "manifest.json", "oracle.json", "input.bin", "native/original-stdio-read-oracle",
    *(f"results/{index}.txt" for index in range(40)),
    *(f"logs/{label}.{suffix}"
      for label in ("ghc-version", "native-build", "native-observations", "pre-export", "post-export",
                    "pre-audit", "post-audit")
      for suffix in ("stdout", "stderr", "command.json")),
    *(f"{stage}/{name}" for stage in ("pre", "post")
      for name in ("core/OriginalStdioReadAudit.cbd", "core/THC.InterfaceClosure.cbd",
                   "audit.json")),
))

ORIGINAL_HANDLE_READINESS_LOGS = (
    "ghc-version", "ghc-info", "native-build",
    "pre-export", "post-export",
) + tuple(f"{stage}-audit-{entry}" for stage in ("pre", "post")
          for entry in ("originalIsTerminal",))
ORIGINAL_HANDLE_READINESS_OUTPUTS = frozenset("build/original-handle-readiness/" + name for name in (
    "manifest.json", "native/oracle",
    *(f"logs/{label}.{suffix}" for label in ORIGINAL_HANDLE_READINESS_LOGS
      for suffix in ("stdout", "stderr", "command.json")),
    *(f"{stage}/{name}" for stage in ("pre", "post") for name in (
        "core/OriginalHandleReadinessAudit.cbd", "core/THC.InterfaceClosure.cbd",
        "originalIsTerminal.audit.json")),
))

RTS_DIAGNOSTIC_OUTPUTS = frozenset("build/rts-diagnostics/" + name for name in (
    "manifest.json", "oracle.json",
    *(f"logs/{label}.{suffix}" for label in (
        "version", "native-build", "ascii", "empty", "bytes", "nul", "newline",
        "debug-ascii", "debug-empty", "debug-bytes", "debug-nul", "debug-newline",
        "trace-nul", "stack", "heap")
      for suffix in ("stdout", "stderr", "command.json")),
))

RTS_SHUTDOWN_OUTPUTS = frozenset("build/rts-shutdown/" + name for name in (
    "manifest.json", "oracle.json",
    *(f"logs/{label}.{suffix}" for label in ("version", "native-build", "signals",
        *(f"{case}-{fast}" for case in ("exit-0", "exit-1", "exit-2", "exit-3", "exit-4", "term", "stop") for fast in (0, 1)))
      for suffix in ("stdout", "stderr", "command.json")),
))

ORIGINAL_RTS_LOCK_ENTRIES = ("originalLock", "originalUnlock")
ORIGINAL_RTS_LOCK_OUTPUTS = frozenset("build/original-rts-locks/" + name for name in (
    "manifest.json", "oracle.json", "declarations.json", "declarations.cbd",
    "pre.cbd", "post.cbd",
    *(f"{stage}-{entry}.audit.json" for stage in ("pre", "post") for entry in ORIGINAL_RTS_LOCK_ENTRIES),
    *(f"logs/{label}.{suffix}" for label in ("version", "info", "libdir", "imports",
        *(f"{stage}-{entry}" for stage in ("pre", "post") for entry in ORIGINAL_RTS_LOCK_ENTRIES))
      for suffix in ("stdout", "stderr", "command.json")),
))

ORIGINAL_SAVED_TERMIOS_ENTRIES = ("originalGetSavedTermios", "originalSetSavedTermios")
ORIGINAL_TERMIOS_OUTPUTS = frozenset("build/original-termios/" + name for name in (
    "manifest.json",
    *(f"logs/{label}.{suffix}" for label in ("ghc-version", "ghc-info")
      for suffix in ("stdout", "stderr", "command.json")),
    "saved/oracle.json", "saved/native/oracle",
    *(f"logs/{label}.{suffix}" for label in (
        "saved-native-build", "saved-native-run", "saved-pre-export", "saved-post-export",
        *(f"saved-{stage}-audit-{entry}" for stage in ("pre", "post") for entry in ORIGINAL_SAVED_TERMIOS_ENTRIES))
      for suffix in ("stdout", "stderr", "command.json")),
    *(f"saved/{stage}/{name}" for stage in ("pre", "post") for name in (
        "core/OriginalSavedTermiosAudit.cbd", "core/THC.InterfaceClosure.cbd",
        *(f"{entry}.audit.json" for entry in ORIGINAL_SAVED_TERMIOS_ENTRIES))),
))

ORIGINAL_ERRNO_ENTRIES = ("originalResetErrno",)
ORIGINAL_ERRNO_OUTPUTS = frozenset("build/original-errno/" + name for name in (
    "manifest.json", "oracle.json", "native/oracle", "native/observations.txt",
    *(f"logs/{label}.{suffix}" for label in (
        "ghc-version", "ghc-info", "native-build", "native-run", "pre-export", "post-export",
        *(f"{stage}-audit-{entry}" for stage in ("pre", "post") for entry in ORIGINAL_ERRNO_ENTRIES))
      for suffix in ("stdout", "stderr", "command.json")),
    *(f"{stage}/{name}" for stage in ("pre", "post") for name in (
        "core/OriginalErrnoAudit.cbd", "core/THC.InterfaceClosure.cbd",
        *(f"{entry}.audit.json" for entry in ORIGINAL_ERRNO_ENTRIES))),
))

ORIGINAL_TCSETATTR_ENTRIES = ("originalTcsetattr",)
ORIGINAL_TCSETATTR_OUTPUTS = frozenset("build/original-tcsetattr/" + name for name in (
    "manifest.json", "oracle.json", "native/oracle",
    *(f"logs/{label}.{suffix}" for label in (
        "ghc-version", "ghc-info", "native-build", "native-run", "pre-export", "post-export",
        *(f"{stage}-audit-{entry}" for stage in ("pre", "post") for entry in ORIGINAL_TCSETATTR_ENTRIES))
      for suffix in ("stdout", "stderr", "command.json")),
    *(f"{stage}/{name}" for stage in ("pre", "post") for name in (
        "core/OriginalTcsetattrAudit.cbd", "core/THC.InterfaceClosure.cbd",
        *(f"{entry}.audit.json" for entry in ORIGINAL_TCSETATTR_ENTRIES))),
))

ORIGINAL_TCGETATTR_ENTRIES = ("originalTcgetattr",)
ORIGINAL_TCGETATTR_OUTPUTS = frozenset("build/original-tcgetattr/" + name for name in (
    "manifest.json", "oracle.json", "native/oracle",
    *(f"logs/{label}.{suffix}" for label in (
        "ghc-version", "ghc-info", "native-build", "native-run", "pre-export", "post-export",
        *(f"{stage}-audit-{entry}" for stage in ("pre", "post") for entry in ORIGINAL_TCGETATTR_ENTRIES))
      for suffix in ("stdout", "stderr", "command.json")),
    *(f"{stage}/{name}" for stage in ("pre", "post") for name in (
        "core/OriginalTcgetattrAudit.cbd", "core/THC.InterfaceClosure.cbd",
        *(f"{entry}.audit.json" for entry in ORIGINAL_TCGETATTR_ENTRIES))),
))

ORIGINAL_POSIX_STAT_ENTRIES = ("originalFstat", "originalFstatErrno")
ORIGINAL_POSIX_STAT_OUTPUTS = frozenset("build/original-posix-stat/" + name for name in (
    "manifest.json", "oracle.json", "native/oracle",
    *(f"logs/{label}.{suffix}" for label in (
        "ghc-version", "ghc-info", "native-build", "native-run", "pre-export", "post-export",
        *(f"{stage}-audit-{entry}" for stage in ("pre", "post") for entry in ORIGINAL_POSIX_STAT_ENTRIES))
      for suffix in ("stdout", "stderr", "command.json")),
    *(f"{stage}/{name}" for stage in ("pre", "post") for name in (
        "core/OriginalPosixStatAudit.cbd", "core/THC.InterfaceClosure.cbd",
        *(f"{entry}.audit.json" for entry in ORIGINAL_POSIX_STAT_ENTRIES))),
))

ORIGINAL_STDIO_TRUNCATE_LOGS = (
    "ghc-version", "ghc-info", "native-build", "native-observations",
    "pre-export", "post-export", "pre-audit", "post-audit",
)
ORIGINAL_STDIO_TRUNCATE_OUTPUTS = frozenset("build/original-stdio-truncate/" + name for name in (
    "manifest.json", "oracle.json", "native/oracle",
    *(f"results/{index}.txt" for index in range(14)),
    *(f"results/{index}.private" for index in list(range(5)) + list(range(7, 12))),
    *(f"logs/{label}.{suffix}" for label in ORIGINAL_STDIO_TRUNCATE_LOGS
      for suffix in ("stdout", "stderr", "command.json")),
    *(f"{stage}/{name}" for stage in ("pre", "post") for name in (
        "core/OriginalStdioTruncateAudit.cbd", "core/THC.InterfaceClosure.cbd",
        "audit.json")),
))


ORIGINAL_FD_READY_ENTRIES = ("originalReadySafe", "originalReadyUnsafe")
ORIGINAL_FD_READY_NEGATIVES = (
    "wrong-unit", "non-function", "wrong-convention", "interruptible",
    "wrong-arity", "wrong-supplied-arity", "signed-cbool",
    "machine-timeout", "scalar-state", "machine-result",
)
ORIGINAL_FD_READY_LOGS = (
    "ghc-version", "ghc-info", "ghc-libdir", "ghc-internal-imports", "native-build", "native-observations",
    "audit",
) + tuple(f"negative-{label}" for label in ORIGINAL_FD_READY_NEGATIVES)
ORIGINAL_FD_READY_OUTPUTS = frozenset("build/original-fd-ready/" + name for name in (
    "manifest.json", "oracle.json", "OriginalFDDeclarations.cbd",
    "OriginalFdReadyAudit.cbd", "native/oracle", "audit.json",
    *(f"negative/{label}.cbd" for label in ORIGINAL_FD_READY_NEGATIVES),
    *(f"negative/{label}.audit.json" for label in ORIGINAL_FD_READY_NEGATIVES),
    *(f"logs/{label}.{suffix}" for label in ORIGINAL_FD_READY_LOGS for suffix in ("stdout", "stderr", "command.json")),
))

# Each attempt retains its logs without admitting arbitrary files from a build
# tree. Only artifacts referenced by the current manifest enter the cache.
ORIGINAL_STACK_FILES = frozenset((
    "native/original-stack-native",
    *(f"{stage}-core/{module}.cbd" for stage in ("pre", "post")
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
    if name.endswith(".exe"):
        name = name[:-4]
    return name in NATIVE_EXECUTABLES or name in BYTEARRAY_NATIVES or (
        original_stack_artifact(name) and name.endswith("/native/original-stack-native"))


class CacheMiss(RuntimeError):
    """Unavailable, stale or invalid cache; fresh preparation is required."""


BOXED_ARRAY_EXTENSION_FILES = frozenset((
    "native/boxed-array-extensions-oracle",
    *(f"{stage}-core/{module}.cbd" for stage in ("pre", "post")
      for module in ("BoxedArrayExtensionsAudit", "THC.InterfaceClosure")),
    *(f"{stage}-{entry}.audit.json" for stage in ("pre", "post")
      for entry in ("boxedExtSizes", "boxedExtClone", "boxedExtCopy", "boxedExtMove", "boxedExtThaw", "boxedExtLazy")),
    *(f"logs/{label}.{suffix}" for label in (
        "ghc-version", "ghc-info", "pre-export", "post-export", "native-compile", "native-oracle",
        *(f"{stage}-audit-{entry}" for stage in ("pre", "post")
          for entry in ("boxedExtSizes", "boxedExtClone", "boxedExtCopy", "boxedExtMove", "boxedExtThaw", "boxedExtLazy")))
      for suffix in ("stdout", "stderr", "command.json")),
))

BOXED_CAS_ENTRIES = ("arrayCas", "arrayCasUnlifted", "smallCas", "smallCasUnlifted", "varCas",
                     "varCasUnlifted", "modifyValue", "modifyLazy", "modifyBottom", "boxedCasCounter")
BOXED_CAS_FILES = frozenset((
    "native/boxed-cas-oracle",
    *(f"{stage}-core/{module}.cbd" for stage in ("pre", "post")
      for module in ("BoxedCasAudit", "BoxedCasCounter", "THC.InterfaceClosure")),
    *(f"{stage}-{entry}.audit.json" for stage in ("pre", "post") for entry in BOXED_CAS_ENTRIES),
    *(f"logs/{label}.{suffix}" for label in (
        "ghc-version", "ghc-info", "pre-export", "post-export", "native-compile", "native-oracle",
        *(f"{stage}-audit-{entry}" for stage in ("pre", "post") for entry in BOXED_CAS_ENTRIES))
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


def rts_diagnostic_artifact_hashes(manifest):
    require(isinstance(manifest, dict) and type(manifest.get("schema")) is int and manifest.get("schema") == 1,
            "Invalid RTS diagnostic manifest")
    artifacts = manifest.get("artifactHashes")
    require(isinstance(artifacts, dict) and set(artifacts) ==
            RTS_DIAGNOSTIC_OUTPUTS - {"build/rts-diagnostics/manifest.json"},
            "Incomplete/unreviewed RTS diagnostic artifacts")
    require(all(isinstance(value, str) and HEX.fullmatch(value) for value in artifacts.values()),
            "Invalid RTS diagnostic artifact hash")
    return artifacts


def rts_shutdown_artifact_hashes(manifest):
    require(isinstance(manifest, dict) and type(manifest.get("schema")) is int and manifest.get("schema") == 1,
            "Invalid RTS shutdown manifest")
    artifacts = manifest.get("artifactHashes")
    require(isinstance(artifacts, dict) and set(artifacts) ==
            RTS_SHUTDOWN_OUTPUTS - {"build/rts-shutdown/manifest.json"},
            "Incomplete/unreviewed RTS shutdown artifacts")
    require(all(isinstance(value, str) and HEX.fullmatch(value) for value in artifacts.values()),
            "Invalid RTS shutdown artifact hash")
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


def fd_ready_artifact_hashes(manifest):
    require(isinstance(manifest, dict) and type(manifest.get("schema")) is int and manifest["schema"] == 1 and
            manifest.get("ghc") == "9.14.1" and manifest.get("entries") == list(ORIGINAL_FD_READY_ENTRIES),
            "Invalid original fdReady manifest")
    for field, expected in (("nativeRows", 168), ("negativeAudits", 10),
                            ("negativeControls", 10)):
        require(type(manifest.get(field)) is int and manifest[field] == expected,
                "Invalid original fdReady proof count: " + field)
    require(manifest.get("negativeControlLabels") == list(ORIGINAL_FD_READY_NEGATIVES),
            "Incomplete original fdReady rejection controls")
    artifacts = manifest.get("artifactHashes")
    require(isinstance(artifacts, dict) and set(artifacts) ==
            ORIGINAL_FD_READY_OUTPUTS - {"build/original-fd-ready/manifest.json"},
            "Incomplete/unreviewed original fdReady artifacts")
    require(all(isinstance(value, str) and HEX.fullmatch(value) for value in artifacts.values()),
            "Invalid original fdReady artifact hash")
    return artifacts


def termios_artifact_hashes(manifest):
    require(isinstance(manifest, dict) and type(manifest.get("schema")) is int and manifest.get("schema") == 1,
            "Invalid original termios manifest")
    if not LINUX_X86_64_HOST:
        require(manifest.get("supported") is False and manifest.get("artifactHashes") == {}, "Unsupported termios host")
        return {}
    require(manifest.get("supported") is True and manifest.get("entries") == list(ORIGINAL_SAVED_TERMIOS_ENTRIES) and
            manifest.get("strictAccepted") is True and manifest.get("runtimeVerified") is False and
            manifest.get("installedArtifactsHashed") is False and
            type(manifest.get("nativeRows")) is int and manifest.get("nativeRows") == 28,
            "Invalid original saved-termios proof")
    artifacts = manifest.get("artifactHashes")
    require(isinstance(artifacts, dict) and set(artifacts) == ORIGINAL_TERMIOS_OUTPUTS - {"build/original-termios/manifest.json"},
            "Incomplete/unreviewed original termios artifacts")
    require(all(isinstance(value, str) and HEX.fullmatch(value) for value in artifacts.values()), "Invalid termios hash")
    return artifacts


def errno_artifact_hashes(manifest):
    require(isinstance(manifest, dict) and type(manifest.get("schema")) is int and manifest.get("schema") == 1,
            "Invalid original errno manifest")
    if not ERRNO_NATIVE_HOST:
        require(manifest.get("supported") is False and manifest.get("artifactHashes") == {}, "Unsupported errno host")
        return {}
    require(manifest.get("ghc") == "9.14.1" and manifest.get("supported") is True and manifest.get("entries") == list(ORIGINAL_ERRNO_ENTRIES) and
            manifest.get("strictAccepted") is True and manifest.get("runtimeVerified") is False and
            manifest.get("installedArtifactsHashed") is False and
            type(manifest.get("nativeRows")) is int and manifest.get("nativeRows") == 5,
            "Invalid original errno proof")
    artifacts = manifest.get("artifactHashes")
    require(isinstance(artifacts, dict) and set(artifacts) == ORIGINAL_ERRNO_OUTPUTS - {"build/original-errno/manifest.json"},
            "Incomplete/unreviewed original errno artifacts")
    require(all(isinstance(value, str) and HEX.fullmatch(value) for value in artifacts.values()), "Invalid errno hash")
    return artifacts

def tcsetattr_artifact_hashes(manifest):
    require(isinstance(manifest, dict) and type(manifest.get("schema")) is int and manifest.get("schema") == 1,
            "Invalid original tcsetattr manifest")
    if not LINUX_X86_64_HOST:
        require(manifest.get("supported") is False and manifest.get("artifactHashes") == {}, "Unsupported tcsetattr host")
        return {}
    require(manifest.get("supported") is True and manifest.get("entries") == list(ORIGINAL_TCSETATTR_ENTRIES) and
            manifest.get("strictAccepted") is True and manifest.get("runtimeVerified") is False and
            manifest.get("installedArtifactsHashed") is False and
            type(manifest.get("nativeRows")) is int and manifest.get("nativeRows") == 22,
            "Invalid original tcsetattr proof")
    artifacts = manifest.get("artifactHashes")
    require(isinstance(artifacts, dict) and set(artifacts) == ORIGINAL_TCSETATTR_OUTPUTS - {"build/original-tcsetattr/manifest.json"},
            "Incomplete/unreviewed original tcsetattr artifacts")
    require(all(isinstance(value, str) and HEX.fullmatch(value) for value in artifacts.values()), "Invalid tcsetattr hash")
    return artifacts

def tcgetattr_artifact_hashes(manifest):
    require(isinstance(manifest, dict) and type(manifest.get("schema")) is int and manifest.get("schema") == 1,
            "Invalid original tcgetattr manifest")
    if not LINUX_X86_64_HOST:
        require(manifest.get("supported") is False and manifest.get("artifactHashes") == {}, "Unsupported tcgetattr host")
        return {}
    require(manifest.get("supported") is True and manifest.get("entries") == list(ORIGINAL_TCGETATTR_ENTRIES) and
            manifest.get("strictAccepted") is True and manifest.get("runtimeVerified") is False and
            manifest.get("installedArtifactsHashed") is False and
            type(manifest.get("nativeRows")) is int and manifest.get("nativeRows") == 12,
            "Invalid original tcgetattr proof")
    artifacts = manifest.get("artifactHashes")
    require(isinstance(artifacts, dict) and set(artifacts) == ORIGINAL_TCGETATTR_OUTPUTS - {"build/original-tcgetattr/manifest.json"},
            "Incomplete/unreviewed original tcgetattr artifacts")
    require(all(isinstance(value, str) and HEX.fullmatch(value) for value in artifacts.values()), "Invalid tcgetattr hash")
    return artifacts




def original_path_stat_artifact_hashes(manifest):
    require(isinstance(manifest, dict) and type(manifest.get("schema")) is int and manifest["schema"] == 1 and
            manifest.get("ghc") == "9.14.1" and isinstance(manifest.get("unixUnit"), str) and ORIGINAL_UNIX_UNIT.fullmatch(manifest["unixUnit"]) and
            manifest.get("entries") == list(ORIGINAL_PATH_STAT_ENTRIES) and
            manifest.get("installedArtifactsHashed") is False,
            "Invalid original path stat fixture receipt")
    artifacts = manifest.get("artifactHashes")
    require(isinstance(artifacts, dict) and set(artifacts) ==
            ORIGINAL_PATH_STAT_OUTPUTS - {"build/original-path-stat/manifest.json"},
            "Incomplete/unreviewed original path stat artifacts")
    require(all(isinstance(value, str) and HEX.fullmatch(value) for value in artifacts.values()),
            "Invalid original path stat artifact hash")
    return artifacts


def original_path_mode_artifact_hashes(manifest):
    require(isinstance(manifest, dict) and type(manifest.get("schema")) is int and manifest["schema"] == 1 and
            manifest.get("ghc") == "9.14.1" and isinstance(manifest.get("unixUnit"), str) and ORIGINAL_UNIX_UNIT.fullmatch(manifest["unixUnit"]) and
            manifest.get("entries") == list(ORIGINAL_PATH_MODE_ENTRIES) and
            manifest.get("installedArtifactsHashed") is False,
            "Invalid original path mode fixture receipt")
    artifacts = manifest.get("artifactHashes")
    require(isinstance(artifacts, dict) and set(artifacts) ==
            ORIGINAL_PATH_MODE_OUTPUTS - {"build/original-path-mode/manifest.json"},
            "Incomplete/unreviewed original path mode artifacts")
    require(all(isinstance(value, str) and HEX.fullmatch(value) for value in artifacts.values()),
            "Invalid original path mode artifact hash")
    return artifacts


def original_path_link_artifact_hashes(manifest):
    require(isinstance(manifest, dict) and type(manifest.get("schema")) is int and manifest["schema"] == 1 and
            manifest.get("ghc") == "9.14.1" and isinstance(manifest.get("unixUnit"), str) and ORIGINAL_UNIX_UNIT.fullmatch(manifest["unixUnit"]) and
            manifest.get("entries") == list(ORIGINAL_PATH_LINK_ENTRIES) and
            manifest.get("installedArtifactsHashed") is False,
            "Invalid original path link fixture receipt")
    artifacts = manifest.get("artifactHashes")
    require(isinstance(artifacts, dict) and set(artifacts) ==
            ORIGINAL_PATH_LINK_OUTPUTS - {"build/original-path-link/manifest.json"},
            "Incomplete/unreviewed original path link artifacts")
    require(all(isinstance(value, str) and HEX.fullmatch(value) for value in artifacts.values()),
            "Invalid original path link artifact hash")
    return artifacts

def original_directory_paths_artifact_hashes(manifest):
    require(isinstance(manifest, dict) and type(manifest.get("schema")) is int and manifest["schema"] == 1 and
            manifest.get("ghc") == "9.14.1" and isinstance(manifest.get("unixUnit"), str) and ORIGINAL_UNIX_UNIT.fullmatch(manifest["unixUnit"]) and
            manifest.get("entries") == list(ORIGINAL_DIRECTORY_PATHS_ENTRIES) and
            manifest.get("installedArtifactsHashed") is False and manifest.get("readlinkUnit") == "ghc-internal",
            "Invalid original directory pathname fixture receipt")
    artifacts = manifest.get("artifactHashes")
    require(isinstance(artifacts, dict) and set(artifacts) ==
            ORIGINAL_DIRECTORY_PATHS_OUTPUTS - {"build/original-directory-paths/manifest.json"},
            "Incomplete/unreviewed original directory pathname artifacts")
    require(all(isinstance(value, str) and HEX.fullmatch(value) for value in artifacts.values()),
            "Invalid original directory pathname artifact hash")
    return artifacts


def original_current_directory_artifact_hashes(manifest):
    require(isinstance(manifest, dict) and type(manifest.get("schema")) is int and manifest["schema"] == 1 and
            manifest.get("ghc") == "9.14.1" and isinstance(manifest.get("unixUnit"), str) and ORIGINAL_UNIX_UNIT.fullmatch(manifest["unixUnit"]) and
            manifest.get("entries") == list(ORIGINAL_CURRENT_DIRECTORY_ENTRIES) and
            manifest.get("installedArtifactsHashed") is False and
            manifest.get("nativeIsolatedChild") is True and manifest.get("coordinatorCwdUnchanged") is True and
            manifest.get("privateRebuiltUnix") is True and
            manifest.get("unixSourceReceipt") == "build/original-current-directory/unix-source.json" and
            manifest.get("unixArchiveSha256") == "a128dea3bfeb731a562f22d376fa606e902154d95321363f7ec1ea6b787a5a3e",
            "Invalid original current directory fixture receipt")
    artifacts = manifest.get("artifactHashes")
    require(isinstance(artifacts, dict) and set(artifacts) ==
            ORIGINAL_CURRENT_DIRECTORY_OUTPUTS - {"build/original-current-directory/manifest.json"},
            "Incomplete/unreviewed original current directory artifacts")
    require(all(isinstance(value, str) and HEX.fullmatch(value) for value in artifacts.values()),
            "Invalid original current directory artifact hash")
    return artifacts


def original_directory_streams_artifact_hashes(manifest):
    require(isinstance(manifest, dict) and type(manifest.get("schema")) is int and manifest["schema"] == 1 and
            manifest.get("ghc") == "9.14.1" and isinstance(manifest.get("unixUnit"), str) and ORIGINAL_UNIX_UNIT.fullmatch(manifest["unixUnit"]) and
            manifest.get("entries") == list(ORIGINAL_DIRECTORY_STREAMS_ENTRIES) and
            manifest.get("installedArtifactsHashed") is False and
            manifest.get("privateRebuiltUnix") is True and
            manifest.get("unixSourceReceipt") == "build/original-directory-streams/unix-source.json" and
            manifest.get("unixArchiveSha256") == "a128dea3bfeb731a562f22d376fa606e902154d95321363f7ec1ea6b787a5a3e",
            "Invalid original directory streams fixture receipt")
    artifacts = manifest.get("artifactHashes")
    require(isinstance(artifacts, dict) and set(artifacts) ==
            ORIGINAL_DIRECTORY_STREAMS_OUTPUTS - {"build/original-directory-streams/manifest.json"},
            "Incomplete/unreviewed original directory streams artifacts")
    require(all(isinstance(value, str) and HEX.fullmatch(value) for value in artifacts.values()),
            "Invalid original directory streams artifact hash")
    return artifacts


def original_path_access_artifact_hashes(manifest):
    require(isinstance(manifest, dict) and type(manifest.get("schema")) is int and manifest["schema"] == 1 and
            manifest.get("ghc") == "9.14.1" and isinstance(manifest.get("unixUnit"), str) and ORIGINAL_UNIX_UNIT.fullmatch(manifest["unixUnit"]) and
            manifest.get("entries") == list(ORIGINAL_PATH_ACCESS_ENTRIES) and
            manifest.get("installedArtifactsHashed") is False,
            "Invalid original path access fixture receipt")
    artifacts = manifest.get("artifactHashes")
    require(isinstance(artifacts, dict) and set(artifacts) ==
            ORIGINAL_PATH_ACCESS_OUTPUTS - {"build/original-path-access/manifest.json"},
            "Incomplete/unreviewed original path access artifacts")
    require(all(isinstance(value, str) and HEX.fullmatch(value) for value in artifacts.values()),
            "Invalid original path access artifact hash")
    return artifacts


def original_unlinkat_artifact_hashes(manifest):
    require(isinstance(manifest, dict) and type(manifest.get("schema")) is int and manifest["schema"] == 1 and
            manifest.get("ghc") == "9.14.1" and isinstance(manifest.get("directoryUnit"), str) and ORIGINAL_DIRECTORY_UNIT.fullmatch(manifest["directoryUnit"]) and
            manifest.get("entries") == list(ORIGINAL_UNLINKAT_ENTRIES) and
            manifest.get("installedArtifactsHashed") is False,
            "Invalid original unlinkat fixture receipt")
    artifacts = manifest.get("artifactHashes")
    require(isinstance(artifacts, dict) and set(artifacts) ==
            ORIGINAL_UNLINKAT_OUTPUTS - {"build/original-unlinkat/manifest.json"},
            "Incomplete/unreviewed original unlinkat artifacts")
    require(all(isinstance(value, str) and HEX.fullmatch(value) for value in artifacts.values()),
            "Invalid original unlinkat artifact hash")
    return artifacts


def original_fstatat_artifact_hashes(manifest):
    require(isinstance(manifest, dict) and type(manifest.get("schema")) is int and manifest["schema"] == 1 and
            manifest.get("ghc") == "9.14.1" and isinstance(manifest.get("directoryUnit"), str) and ORIGINAL_DIRECTORY_UNIT.fullmatch(manifest["directoryUnit"]) and
            manifest.get("entries") == list(ORIGINAL_FSTATAT_ENTRIES) and
            manifest.get("installedArtifactsHashed") is False,
            "Invalid original fstatat fixture receipt")
    artifacts = manifest.get("artifactHashes")
    require(isinstance(artifacts, dict) and set(artifacts) ==
            ORIGINAL_FSTATAT_OUTPUTS - {"build/original-fstatat/manifest.json"},
            "Incomplete/unreviewed original fstatat artifacts")
    require(all(isinstance(value, str) and HEX.fullmatch(value) for value in artifacts.values()),
            "Invalid original fstatat artifact hash")
    return artifacts


def command(argv, root):
    return subprocess.check_output(list(map(str, argv)), cwd=root, text=True).strip()


def tracked_files(root):
    files, submodules = set(), []
    for entry in command(["git", "ls-files", "--stage", "-z"], root).split("\0"):
        if not entry:
            continue
        info, name = entry.split("\t", 1)
        if info.split()[0] == "160000":
            submodules.append(name)
        else:
            files.add(name)
    if submodules:
        # Cabal already declares the upstream files this project distributes.
        # Expand only those paths, not the full GHC tree or its nested modules.
        declared = re.findall(r"^  (nih/pinned/\S+)\s*$", (root / "thc.cabal").read_text(), re.M)
        for module in submodules:
            prefix = module + "/"
            patterns = [path[len(prefix):] for path in declared if path.startswith(prefix)]
            if patterns:
                selected = command(["git", "ls-files", "-z", "--", *patterns], root / module)
                files.update(prefix + name for name in selected.split("\0") if name)
    return files


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
         "THC_INSTALLED_CORE_GHC", "THC_INSTALLED_CORE_GHC_PKG", "THC_INSTALLED_CORE_GHC_SOURCE",
         "GHCRTS", "CC", "CFLAGS", "CPATH", "LIBRARY_PATH", "LD_LIBRARY_PATH", "LANG", "LC_ALL")}
    return result


def identity(root):
    tracked = tracked_files(root)
    sources = {name for name in tracked if name.startswith(("src/compiler/", "src/cbd/", "t/fixtures/compiler/", "t/fixtures/core/", "t/fixtures/retained-core/", "t/fixtures/package-roots/", "nih/pinned/", "etc/", "src/core-symbols/", "bin/", "src/examples/", "src/main/resources/", "t/haskell-fixtures/", "src/driver/THC/Driver/", "src/tools/primops/"))}
    sources.update(name for name in tracked if name.startswith("cmake/"))
    sources.update((SELF, WIRED_SOURCE, *RUNTIME_INPUTS, *COMPILER_BUILD_INPUTS,
                    "CMakeLists.txt", ".github/scripts/fast_fixtures.py", ".github/scripts/fast-fixtures.json",
                    "src/test/resources/core/original-unix-libc-descriptors.json"))
    if ".gitmodules" in tracked:
        sources.add(".gitmodules")
    require(all(name in tracked for name in sources), "Cache helper/runtime inputs must be tracked")
    require("CMakeLists.txt" in sources and "bin/export-boot.py" in sources
            and "t/fixtures/core/coverage.json" in sources, "Incomplete authoritative source set")
    hashes = {name: digest(file_path(root, name)) for name in sorted(sources)}
    for name, expected in ghc_source_pins(root).items():
        require(hashes.get(name) == expected, "Pinned GHC source missing or changed: " + name)
    return {"schema": SCHEMA, "workspace": str(root),
        "platform": {"system": platform.system(), "machine": platform.machine(),
                     "byteOrder": sys.byteorder, "libc": list(platform.libc_ver())},
        "sources": hashes,
        "toolchain": toolchain(root)}


def cache_key(value):
    return "thc-fast-inputs-v" + str(SCHEMA) + "-" + sha(canonical(value))


def ghc_source_pins(root):
    """Read the original exporter's literal pin tables without executing it."""
    tables, result = {}, {}
    tree = ast.parse(file_path(root, "bin/export-boot.py").read_text())
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
            require(isinstance(value, str) and HEX.fullmatch(value), "Invalid authoritative GHC source pin")
            full = wired_source_path(relative(name))
            require(full not in result or result[full] == value, "Conflicting authoritative GHC source pin")
            table[name] = value
            result[full] = value
        tables[statement.targets[0].id] = table
    require(bool(result), "No authoritative GHC source pins")
    return result


def pinned_address_artifact_hashes(manifest):
    artifacts = manifest.get("artifactHashes")
    require(isinstance(artifacts, dict) and set(artifacts) == PINNED_ADDRESS_OUTPUTS - {"build/pinned-addresses/manifest.json"},
            "Incomplete pinned-address artifact inventory")
    require(all(isinstance(value, str) and HEX.fullmatch(value) for value in artifacts.values()), "Invalid pinned-address artifact hash")
    require(manifest.get("mode") == "full" and manifest.get("strictAccepted") is True, "Pinned-address diagnostic-only preparation")
    return artifacts


def memory_artifact_hashes(directory, manifest):
    require(directory in MEMORY_FIXTURE_OUTPUTS, "Unknown memory fixture: " + directory)
    require(isinstance(manifest, dict) and type(manifest.get("schema")) is int
            and manifest.get("schema") == 1 and manifest.get("ghc") == "9.14.1",
            "Invalid memory fixture manifest: " + directory)
    artifacts = manifest.get("artifactHashes")
    require(isinstance(artifacts, dict) and set(artifacts) ==
            MEMORY_FIXTURE_OUTPUTS[directory] - {f"build/{directory}/manifest.json"},
            "Incomplete/unreviewed memory fixture artifacts: " + directory)
    require(all(isinstance(value, str) and HEX.fullmatch(value) for value in artifacts.values()),
            "Invalid memory fixture artifact hash: " + directory)
    return artifacts


def bytearray_artifact_hashes(family, manifest):
    required = BYTEARRAY_OUTPUTS[family] - {f"build/{family}/manifest.json"}
    records = manifest.get("artifactHashes")
    require(isinstance(records, dict) and set(records) == required, "Incomplete byte-array artifact inventory")
    require(all(isinstance(value, str) and HEX.fullmatch(value) for value in records.values()), "Invalid byte-array artifact hash")
    require(manifest.get("entries") == list(BYTEARRAY_FAMILIES[family][1]), "Changed byte-array entry inventory")
    require(type(manifest.get("schema")) is int and manifest.get("schema") == 1 and manifest.get("ghc") == "9.14.1" and manifest.get("wordBits") == 64,
            "Wrong byte-array fixture schema/toolchain")
    return records


def simd_address_artifact_hashes(manifest):
    require(isinstance(manifest, dict) and type(manifest.get("schema")) is int and manifest["schema"] == 1 and
            manifest.get("ghc") == "9.14.1" and manifest.get("entries") == list(SIMD_ADDRESS_ENTRIES) and
            type(manifest.get("scalarRows")) is int and manifest["scalarRows"] == SIMD_ADDRESS_ROWS and
            type(manifest.get("nativeVector128Rows")) is int and
            manifest["nativeVector128Rows"] == (SIMD_ADDRESS_128_ROWS if SIMD_ADDRESS_NATIVE128 else 0), "Invalid vector-address provenance")
    require(isinstance(manifest.get("stages"), dict) and set(manifest["stages"]) == {stage for stage, _ in SIMD_ADDRESS_STAGES},
            "Missing vector-address Core stages")
    artifacts = manifest.get("artifactHashes")
    require(isinstance(artifacts, dict) and set(artifacts) == SIMD_ADDRESS_OUTPUTS - {"build/simd-address-families/manifest.json"},
            "Incomplete vector-address artifact closure")
    require(all(isinstance(value, str) and HEX.fullmatch(value) for value in artifacts.values()), "Invalid vector-address hash")
    return artifacts


def scalar_memory_artifact_hashes(manifest):
    require(isinstance(manifest, dict) and type(manifest.get("schema")) is int and manifest["schema"] == 1 and
            manifest.get("ghc") == "9.14.1" and manifest.get("entries") == list(SCALAR_MEMORY_ENTRIES) and
            manifest.get("stages") == ["pre", "post"] and type(manifest.get("nativeRows")) is int and
            manifest["nativeRows"] == 271, "Invalid scalar memory utilities provenance")
    artifacts = manifest.get("artifactHashes")
    require(isinstance(artifacts, dict) and set(artifacts) == SCALAR_MEMORY_OUTPUTS - {"build/scalar-memory-utilities/manifest.json"},
            "Incomplete scalar memory utilities artifacts")
    require(all(isinstance(value, str) and HEX.fullmatch(value) for value in artifacts.values()),
            "Invalid scalar memory utilities hash")
    return artifacts


def stable_name_artifact_hashes(manifest):
    require(isinstance(manifest, dict) and type(manifest.get("schema")) is int and manifest["schema"] == 1 and
            manifest.get("ghc") == "9.14.1" and manifest.get("entries") == list(STABLE_NAME_ENTRIES) and
            manifest.get("stages") == ["pre", "post"] and isinstance(manifest.get("native"), list) and
            len(manifest["native"]) == 20 and all(type(value) is int for value in manifest["native"]),
            "Invalid stable-name provenance")
    artifacts = manifest.get("artifactHashes")
    require(isinstance(artifacts, dict) and set(artifacts) == STABLE_NAME_OUTPUTS - {"build/stable-names/manifest.json"},
            "Incomplete stable-name artifacts")
    require(all(isinstance(value, str) and HEX.fullmatch(value) for value in artifacts.values()),
            "Invalid stable-name artifact hash")
    return artifacts


def bco_artifact_hashes(manifest):
    require(isinstance(manifest, dict) and type(manifest.get("schema")) is int and manifest["schema"] == 1 and
            manifest.get("ghc") == "9.14.1", "Invalid GHC BCO manifest")
    require(manifest.get("entries") == list(BCO_ENTRIES) and manifest.get("stages") == ["pre", "post"] and
            manifest.get("arguments") == [-2, 0, 7] and isinstance(manifest.get("native"), list) and
            len(manifest["native"]) == 3 * len(BCO_ENTRIES) and all(type(value) is int for value in manifest["native"]),
            "Invalid GHC BCO provenance")
    artifacts = manifest.get("artifactHashes")
    require(isinstance(artifacts, dict) and set(artifacts) == BCO_OUTPUTS - {"build/ghc-bco/manifest.json"},
            "Incomplete GHC BCO artifacts")
    require(all(isinstance(value, str) and HEX.fullmatch(value) for value in artifacts.values()), "Invalid GHC BCO artifact hash")
    return artifacts


def delimited_artifact_hashes(manifest):
    require(type(manifest.get("schema")) is int and manifest["schema"] == 1 and manifest.get("ghc") == "9.14.1",
            "Invalid delimited-continuation manifest")
    require(manifest.get("entries") == list(DELIMITED_ENTRIES) and manifest.get("stages") == ["pre", "post"] and
            manifest.get("arguments") == [-2, 0, 7] and isinstance(manifest.get("native"), list) and
            len(manifest["native"]) == 51 and all(type(value) is int for value in manifest["native"]),
            "Invalid delimited-continuation provenance")
    artifacts = manifest.get("artifactHashes")
    require(isinstance(artifacts, dict) and set(artifacts) == DELIMITED_OUTPUTS - {"build/delimited-continuations/manifest.json"},
            "Incomplete delimited-continuation artifacts")
    require(all(isinstance(value, str) and HEX.fullmatch(value) for value in artifacts.values()),
            "Invalid delimited-continuation artifact hash")
    return artifacts
def thread_inventory_artifact_hashes(manifest):
    require(type(manifest.get("schema")) is int and manifest["schema"] == 1 and manifest.get("ghc") == "9.14.1",
            "Invalid thread inventory manifest")
    require(manifest.get("entries") == list(THREAD_INVENTORY_ENTRIES) and manifest.get("stages") == ["pre", "post"] and
            manifest.get("nativeThread") == "unbound forkIO, threaded RTS -N2",
            "Invalid thread inventory provenance")
    artifacts = manifest.get("artifactHashes")
    require(isinstance(artifacts, dict) and set(artifacts) == THREAD_INVENTORY_OUTPUTS - {"build/thread-inventory/manifest.json"},
            "Incomplete thread inventory artifacts")
    require(all(isinstance(value, str) and HEX.fullmatch(value) for value in artifacts.values()),
            "Invalid thread inventory artifact hash")
    return artifacts


def thread_scheduling_artifact_hashes(manifest):
    require(type(manifest.get("schema")) is int and manifest["schema"] == 1 and manifest.get("ghc") == "9.14.1",
            "Invalid thread scheduling manifest")
    require(manifest.get("entries") == list(THREAD_SCHEDULING_ENTRIES) and manifest.get("stages") == ["pre", "post"] and
            manifest.get("nativeRTS") == "non-threaded: direct delay# uses the POSIX I/O manager",
            "Invalid thread scheduling provenance")
    artifacts = manifest.get("artifactHashes")
    require(isinstance(artifacts, dict) and set(artifacts) == THREAD_SCHEDULING_OUTPUTS - {"build/thread-scheduling/manifest.json"},
            "Incomplete thread scheduling artifacts")
    require(all(isinstance(value, str) and HEX.fullmatch(value) for value in artifacts.values()),
            "Invalid thread scheduling artifact hash")
    return artifacts


MANAGED_MVAR_ENTRIES = ("transitions", "lazyPayload", "aliasRoundTrip", "unliftedPayload", "closurePayload",
                        "waitTake", "waitRead", "waitPut", "makeBox")
MANAGED_MVAR_OUTPUTS = frozenset("build/managed-mvars/" + name for name in (
    "manifest.json", "contracts.json", "oracle.tsv", "context-oracle.tsv", "native/managed-mvar-oracle",
    "plugin/thc-core-plugin." + ("dylib" if platform.system() == "Darwin" else "so"),
    *(f"{stage}/core/{module}.cbd" for stage in ("pre", "post") for module in ("ManagedMVarAudit", "THC.InterfaceClosure")),
    *(f"{stage}/{entry}.audit.json" for stage in ("pre", "post") for entry in MANAGED_MVAR_ENTRIES),
    *(f"logs/{label}{suffix}" for label in ("ghc-version", "ghc-package-db", "ghc-info", "plugin-build", "plugin-metadata",
        "pre-export", "post-export", "native-build", "native-word-bits", "native-ready", "native-concurrent-N1", "native-concurrent-N2")
      for suffix in (".stdout", ".stderr", ".command.json"))))
SYNCHRONOUS_EXCEPTION_OUTPUTS = frozenset("build/synchronous-exceptions/" + name for name in (
    *(f"plugin/{field}." + ("dylib" if platform.system() == "Darwin" else "so") for field in ("sharedLibrary", "cabalSharedLibrary")),
    *(f"{stage}/core/{module}.cbd" for stage in ("pre", "post") for module in ("SynchronousExceptionsAudit", "THC.InterfaceClosure")),
    *(f"logs/{label}{suffix}" for label in ("ghc-version", "python-version", "cabal-plugin-build", "plugin-metadata",
        "pre-export", "post-export", "native-build", "native-word-bits", "native-oracle")
      for suffix in (".stdout", ".stderr", ".command.json"))))

CBV_CONTRACT_CBD_OUTPUTS = frozenset({
    *(f"build/core/{module}.cbd" for module in ("StrictFields", "CBVAudit", "CBVCoercionAudit", "DemandAudit")),
    *(f"build/cbv-post-core/{module}.cbd" for module in ("CBVAudit", "CBVCoercionAudit")),
    "build/source-core/RepresentationAudit.cbd", "build/tuple-arithmetic/pre-core/TupleArithmeticAudit.cbd",
})

HEAP_CORPUS_CBD_OUTPUTS = frozenset(
    {f"build/addr-identity/{stage}-core/{module}.cbd" for stage in ("pre", "post")
     for module in ("AddressIdentityAudit", "THC.InterfaceClosure")} |
    {f"build/corpus/groups/{group}/core/{module}.cbd" for group, modules in (
        ("int64-conversions", ("Int64Conversions",)), ("lists", ("ListCoverage", "CoverageSupport", "GHC.Internal.Base", "GHC.Internal.List", "GHC.InterfaceClosure")),
        ("functions", ("FunctionCoverage", "CoverageSupport")), ("trees", ("TreeCoverage",)),
        ("numeric", ("NumericCoverage",)), ("narrow-ints", ("NarrowIntCoverage",)),
        ("narrow-words", ("NarrowWordCoverage",)), ("pointers", ("PointerCoverage", "CoverageSupport")))
     for module in (*modules, "THC.InterfaceClosure")})


def allowed_payload(name):
    parts = PurePosixPath(relative(name)).parts
    if name.endswith(".json") and parts[-1][0].isupper() and any(
            part == "core" or part.endswith("-core") for part in parts[:-1]):
        return False
    if name == "build/primop-coverage.json":
        return True
    if native_executable(name):
        return True
    if name in SIMD_SMOKE_SOURCES:
        return True
    if len(parts) < 3 or parts[0] != "build":
        return False
    if str(PurePosixPath(name).parent) in CORE_DIRS:
        return name in BASE_CORE_CBD_OUTPUTS or name in AGGREGATE_HOST_CBD_OUTPUTS
    if name.endswith(".json") and name[:-5] + ".cbd" in AGGREGATE_HOST_CBD_OUTPUTS:
        return False
    if parts[1] == "aggregate-layout":
        return name in AGGREGATE_HOST_CBD_OUTPUTS or name in {
            f"build/aggregate-layout/{stage}-ghc/AggregateLayoutAudit.{suffix}"
            for stage in ("pre", "post") for suffix in ("hi", "o")}
    if parts[1] == "sum-layout":
        return name in AGGREGATE_HOST_CBD_OUTPUTS or name in {
            f"build/sum-layout/{stage}-ghc/SumLayoutAudit.{suffix}"
            for stage in ("pre", "post") for suffix in ("hi", "o")} or name in {
            "build/sum-layout/native/sum-layout-oracle", "build/sum-layout/oracle.tsv",
            *(f"build/sum-layout/native/{module}.{suffix}" for module in ("Main", "SumLayoutAudit") for suffix in ("hi", "o"))}
    if parts[1] == "floating-tuple":
        return name in FLOATING_TUPLE_OUTPUTS
    if parts[1] == "sum-result":
        return name in SUM_RESULT_OUTPUTS
    if parts[1] == "integer-completion":
        return name in INTEGER_COMPLETION_OUTPUTS
    if parts[1] == "unix-libc":
        return name in UNIX_LIBC_OUTPUTS
    if parts[1] == "original-path-stat":
        return name in ORIGINAL_PATH_STAT_OUTPUTS
    if parts[1] == "original-path-mode":
        return name in ORIGINAL_PATH_MODE_OUTPUTS
    if parts[1] == "original-path-link":
        return name in ORIGINAL_PATH_LINK_OUTPUTS
    if parts[1] == "original-directory-paths":
        return name in ORIGINAL_DIRECTORY_PATHS_OUTPUTS
    if parts[1] == "original-path-access":
        return name in ORIGINAL_PATH_ACCESS_OUTPUTS
    if parts[1] == "original-unlinkat":
        return name in ORIGINAL_UNLINKAT_OUTPUTS
    if parts[1] == "original-fstatat":
        return name in ORIGINAL_FSTATAT_OUTPUTS
    if parts[1] == "original-current-directory":
        return name in ORIGINAL_CURRENT_DIRECTORY_OUTPUTS
    if parts[1] == "original-directory-streams":
        return name in ORIGINAL_DIRECTORY_STREAMS_OUTPUTS
    if parts[1] == "io-main-pap":
        return name in IO_MAIN_PAP_OUTPUTS
    if parts[1] == "weak-explicit":
        return name in WEAK_OUTPUTS
    if parts[1] == "mask-functions":
        return name in MASK_FUNCTION_OUTPUTS
    if parts[1] == "proxy-void":
        return name in PROXY_VOID_OUTPUTS
    if parts[1] == "rubbish-literals":
        return name in RUBBISH_OUTPUTS
    if parts[1] in MEMORY_FIXTURE_OUTPUTS:
        return name in MEMORY_FIXTURE_OUTPUTS[parts[1]]
    if parts[1] == "compiler":
        return len(parts) == 3 and (parts[2] == "plugin.json" or
            bool(re.fullmatch(r"libHSthc-[\w.-]+\.(so|dylib)", parts[2])))
    if name in {f"build/thread-label/{stage}/core/ThreadLabelAudit.cbd" for stage in ("pre", "post")}:
        return True
    if parts[1] == "thread-inventory":
        return name in THREAD_INVENTORY_OUTPUTS
    if parts[1] == "thread-scheduling":
        return name in THREAD_SCHEDULING_OUTPUTS
    if parts[1] == "hint-trace":
        return name in HINT_TRACE_OUTPUTS
    if parts[1] == "closure-inspection":
        return name in CLOSURE_INSPECTION_OUTPUTS
    if parts[1] == "scalar-memory-utilities":
        return name in SCALAR_MEMORY_OUTPUTS
    if parts[1] == "stable-names":
        return name in STABLE_NAME_OUTPUTS
    if parts[1] == "delimited-continuations":
        return name in DELIMITED_OUTPUTS
    if parts[1] == "ghc-bco":
        return name in BCO_OUTPUTS
    if parts[1] == "simd-address-families":
        return name in SIMD_ADDRESS_OUTPUTS
    if parts[1] in BYTEARRAY_OUTPUTS:
        return name in BYTEARRAY_OUTPUTS[parts[1]]
    if parts[1] in SIMD_BYTEARRAY_FAMILIES:
        if name == f"build/{parts[1]}/provenance.json":
            return True
        attempt = "/".join(parts[:3]) if parts[2].startswith("prepare-run-") else f"build/{parts[1]}/prepare-run-placeholder"
        return name in simd_bytearray_outputs(parts[1], attempt, True)
    if parts[1] == "pinned-addresses":
        return name in PINNED_ADDRESS_OUTPUTS
    if parts[1] == "float-decode":
        return name in FLOAT_DECODE_OUTPUTS
    if parts[1] == "managed-mvars":
        return name in MANAGED_MVAR_OUTPUTS
    if parts[1] == "synchronous-exceptions" and name in SYNCHRONOUS_EXCEPTION_OUTPUTS:
        return True
    if name in {f"build/{family}/{stage}-core/{module}.cbd"
                for family, module in (("simd", "SimdInt64X2"), ("simd-int32x4", "SimdInt32X4"))
                for stage in ("pre", "post")}:
        return True
    if name in AGGREGATE_HOST_CBD_OUTPUTS:
        return True
    if name in CBV_CONTRACT_CBD_OUTPUTS:
        return True
    if name in HEAP_CORPUS_CBD_OUTPUTS:
        return True
    if parts[1] == "corpus" and "core" in parts and PurePosixPath(name).suffix == ".json":
        return False
    if parts[1] == "floating-remainder":
        return name in FLOATING_REMAINDER_OUTPUTS
    if name in {f"build/{family}/{folder.format(stage=stage)}/{module}.cbd"
                for family, folder, fixture in (("boxed-arrays", "{stage}/core", "BoxedArrayAudit"),
                                                ("array-slices", "{stage}-core", "ArraySliceAudit"),
                                                ("fetch-add-int-array", "{stage}/core", "FetchAddIntArrayAudit"),
                                                ("shrink-bytearrays", "{stage}/core", "ShrinkMutableByteArrayAudit"),
                                                ("managed-address-reads", "{stage}-core", "ManagedAddressReadAudit"))
                for stage in ("pre", "post") for module in (fixture, "THC.InterfaceClosure")}:
        return True
    if name in {f"build/{family}/{stage}-core/{module}.cbd"
                for family, module in (("word-floating", "WordFloatingAudit"),
                                       ("scalar-bitcasts", "ScalarBitCastAudit"),
                                       ("fused-floating", "FloatingAudit"))
                for stage in ("pre", "post")}:
        return True
    if name in {f"build/{family}/{stage}/core/{module}.cbd"
                for family, fixture in (("address-fields", "AddressFieldAudit"), ("data-to-tag", "DataToTagAudit"))
                for stage in ("pre", "post") for module in (fixture, "THC.InterfaceClosure")}:
        return True
    if parts[1] == "simd-capability-smoke":
        return name in SIMD_SMOKE_OUTPUTS
    if parts[1] == "simd-floatx4-fma":
        return name in SIMD_FLOAT_FMA_OUTPUTS
    if parts[1] == "simd-wide-floating-fma":
        return name in SIMD_WIDE_FMA_OUTPUTS
    if parts[1] == "original-stdio-read":
        return name in ORIGINAL_STDIO_READ_OUTPUTS
    if parts[1] == "original-stdio-truncate":
        return name in ORIGINAL_STDIO_TRUNCATE_OUTPUTS
    if parts[1] == "native-addresses":
        return name in ("build/native-addresses/manifest.json", "build/native-addresses/oracle.json")
    if parts[1] == "simd-arithmetic":
        return name in SIMD_ARITHMETIC_OUTPUTS
    if parts[1] == "simd-wide-arrays":
        return name in SIMD_WIDE_ARRAY_OUTPUTS
    if parts[1] == "simd128-arrays":
        return name in SIMD128_ARRAY_OUTPUTS
    if parts[1] == "native-malloc":
        return name in ("build/native-malloc/manifest.json", "build/native-malloc/oracle.txt")
    if parts[1] == "original-fd-ready":
        return name in ORIGINAL_FD_READY_OUTPUTS
    if parts[1] == "original-handle-readiness":
        return name in ORIGINAL_HANDLE_READINESS_OUTPUTS
    if parts[1] == "original-posix-stat":
        return name in ORIGINAL_POSIX_STAT_OUTPUTS
    if parts[1] == "rts-diagnostics":
        return name in RTS_DIAGNOSTIC_OUTPUTS
    if parts[1] == "rts-shutdown":
        return name in RTS_SHUTDOWN_OUTPUTS
    if parts[1] == "original-rts-locks":
        return name in ORIGINAL_RTS_LOCK_OUTPUTS
    if parts[1] == "original-errno":
        return name in ORIGINAL_ERRNO_OUTPUTS
    if parts[1] == "original-termios":
        return name in ORIGINAL_TERMIOS_OUTPUTS
    if parts[1] == "original-tcsetattr":
        return name in ORIGINAL_TCSETATTR_OUTPUTS
    if parts[1] == "original-tcgetattr":
        return name in ORIGINAL_TCGETATTR_OUTPUTS
    if parts[1] == "original-stack":
        return name == "build/original-stack/manifest.json" or original_stack_artifact(name)
    if parts[1] == "boxed-array-extensions":
        return name == "build/boxed-array-extensions/manifest.json" or boxed_array_extension_artifact(name)
    if parts[1] == "boxed-cas":
        match = re.fullmatch(r"build/boxed-cas/run-[1-9][0-9]*/(.+)", name)
        return name == "build/boxed-cas/manifest.json" or match is not None and match.group(1) in BOXED_CAS_FILES
    if parts[1] not in BUILD_DIRS or any(p in ("test-results", "reports", "classes", ".gradle") for p in parts):
        return False
    # Fixture inputs and recorded native objects only, not arbitrary executable
    # scripts, JARs, Gradle state or JUnit status. Native executables are data here.
    suffix = PurePosixPath(name).suffix
    return suffix in (".json", ".tsv", ".hs", ".hi", ".o", ".dyn_hi", ".dyn_o") or (
        not suffix and "oracle" in parts[-1])


def hashes_in(value, tc):
    """All fingerprint spellings used by current original preparation manifests."""
    if isinstance(value, dict):
        if set(value) == {"source", "modules", "unit", "sizes"}:
            # The standard publisher receipt retains original ZIP member paths.
            # Source ZIP and receipt hashes cover them; only ready CBD references
            # name executable files in the workspace.
            require(isinstance(value["source"], dict) and isinstance(value["modules"], list) and
                    isinstance(value["unit"], dict) and isinstance(value["sizes"], list), "Invalid CBD publication receipt")
            yield from hashes_in(value["source"], tc)
            yield from hashes_in(value["unit"].get("modules", []), tc)
            return
        if value.get("format") == "thc-core-packages":
            # Module members name paths inside the independently hashed ZIP,
            # not files relative to the checkout. Preserve the full ZIP hash;
            # the production package reader validates its members and layout.
            for unit in value.get("units", []):
                if "bundle" in unit:
                    yield from hashes_in(unit["bundle"], tc)
                else:
                    yield from hashes_in(unit.get("modules", []), tc)
            return
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
    tracked = tracked_files(root)
    for name in core_files:
        p = PurePosixPath(relative(name))
        require(str(p.parent) in CORE_DIRS and p.suffix == ".cbd", "Unknown extra Core file: " + name)
    pending = list(dict.fromkeys((*REQUIRED, *core_files)))
    payload, external, expected, visited = {}, {}, {}, set()
    while pending:
        name = pending.pop()
        if name in visited:
            continue
        visited.add(name)
        require(name not in tracked and allowed_payload(name), "Unknown/tracked payload: " + name)
        require(verified is None or name in verified, "Missing original dependency: " + name)
        # Restore has already hashed all bytes in one sequential archive pass.
        # Reuse those hashes and cached JSON rather than randomly seeking gzip
        # once per native artifact during dependency traversal.
        data = read(name) if verified is None or name.endswith(".json") else None
        if data is not None:
            require(len(data) <= MAX_FILE_BYTES, "Oversized payload: " + name)
        actual = sha(data) if verified is None else verified[name]
        require(name not in expected or expected[name] == actual, "Stale original artifact: " + name)
        payload[name] = actual
        if not name.endswith(".json"):
            continue
        doc = json.loads(data)
        if name.startswith("build/") and name.endswith("/provenance.json") and name.split("/")[1] in SIMD_BYTEARRAY_FAMILIES:
            simd_bytearray_artifact_hashes(name.split("/")[1], doc)
        if name == "build/io-main-pap/provenance.json":
            records = doc.get("artifacts")
            require(isinstance(records, list), "Missing IO-main PAP artifacts")
            paths = [record.get("path") for record in records]
            require(len(paths) == len(set(paths)) and set(paths) == IO_MAIN_PAP_OUTPUTS - {name},
                    "Incomplete/unreviewed IO-main PAP artifacts")
        if name == "build/mask-functions/manifest.json":
            artifacts = doc.get("artifactHashes")
            require(isinstance(artifacts, dict) and set(artifacts) == MASK_FUNCTION_OUTPUTS - {name},
                    "Incomplete/unreviewed mask-function artifacts")
        if name == "build/thread-inventory/manifest.json":
            thread_inventory_artifact_hashes(doc)
        if name == "build/thread-scheduling/manifest.json":
            thread_scheduling_artifact_hashes(doc)
        if name.startswith("build/") and name.endswith("/manifest.json") and name.split("/")[1] in BYTEARRAY_FAMILIES:
            bytearray_artifact_hashes(name.split("/")[1], doc)
        memory_directory = PurePosixPath(name).parent.name
        if name == f"build/{memory_directory}/manifest.json" and memory_directory in MEMORY_FIXTURE_OUTPUTS:
            memory_artifact_hashes(memory_directory, doc)
        if name == "build/scalar-memory-utilities/manifest.json":
            scalar_memory_artifact_hashes(doc)
        if name == "build/delimited-continuations/manifest.json":
            delimited_artifact_hashes(doc)
        if name == "build/ghc-bco/manifest.json":
            bco_artifact_hashes(doc)
        if name == "build/simd-address-families/manifest.json":
            simd_address_artifact_hashes(doc)
        if name == "build/stable-names/manifest.json":
            stable_name_artifact_hashes(doc)
        if name == "build/pinned-addresses/manifest.json":
            pinned_address_artifact_hashes(doc)
        if name == "build/float-decode/manifest.json":
            require(isinstance(doc.get("artifactHashes"), dict) and
                    set(doc["artifactHashes"]) == FLOAT_DECODE_OUTPUTS - {name},
                    "Incomplete floating decode fixture inventory")
        if name == "build/floating-remainder/manifest.json":
            require(isinstance(doc.get("artifactHashes"), dict) and
                    set(doc["artifactHashes"]) == FLOATING_REMAINDER_OUTPUTS - {name},
                    "Incomplete floating remainder fixture inventory")
        if name == "build/original-path-stat/manifest.json":
            original_path_stat_artifact_hashes(doc)
        if name == "build/original-path-mode/manifest.json":
            original_path_mode_artifact_hashes(doc)
        if name == "build/original-path-link/manifest.json":
            original_path_link_artifact_hashes(doc)
        if name == "build/original-directory-paths/manifest.json":
            original_directory_paths_artifact_hashes(doc)
        if name == "build/original-path-access/manifest.json":
            original_path_access_artifact_hashes(doc)
        if name == "build/original-unlinkat/manifest.json":
            original_unlinkat_artifact_hashes(doc)
        if name == "build/original-fstatat/manifest.json":
            original_fstatat_artifact_hashes(doc)
        if name == "build/original-current-directory/manifest.json":
            original_current_directory_artifact_hashes(doc)
        if name == "build/original-directory-streams/manifest.json":
            original_directory_streams_artifact_hashes(doc)
        if name == "build/rts-diagnostics/manifest.json":
            rts_diagnostic_artifact_hashes(doc)
        if name == "build/rts-shutdown/manifest.json":
            rts_shutdown_artifact_hashes(doc)
        if name == "build/original-rts-locks/manifest.json":
            rts_lock_artifact_hashes(doc)
        if name == "build/original-errno/manifest.json":
            errno_artifact_hashes(doc)
        if name == "build/original-termios/manifest.json":
            termios_artifact_hashes(doc)
        if name == "build/original-tcsetattr/manifest.json":
            tcsetattr_artifact_hashes(doc)
        if name == "build/original-tcgetattr/manifest.json":
            tcgetattr_artifact_hashes(doc)
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
                "oracle" in path.name) or path.suffix in (".so", ".dylib"),
                "Executable non-native input: " + name)
    return mode


def pack(root, current, output):
    require(not output.exists() and not output.is_symlink(), "Bundle already exists; preserve the prior attempt")
    core = sorted(p.relative_to(root).as_posix() for d in CORE_DIRS
                  for p in file_path(root, d).glob("*.cbd"))
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
        tracked = tracked_files(root)
        documents, json_bytes = {}, 0
        # Every path, destination and byte digest is checked before any writes.
        for name in sorted(payload):
            expected = payload[name]
            require(name not in tracked and allowed_payload(name), "Unknown/tracked archive payload: " + name)
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


def native_oracle(root, producer, family, inputs, outputs, cache_home):
    """Cache the explicitly separated GHC baseline, never its THC Core exports."""
    ghc = str(Path(shutil.which(os.environ.get("GHC", "ghc"))).resolve())
    pkg = str(Path(shutil.which(os.environ.get("GHC_PKG", "ghc-pkg"))).resolve())
    version = command([ghc, "--numeric-version"], root)
    require(version == "9.14.1", "Native oracle cache requires pinned GHC 9.14.1")
    libdir = Path(command([ghc, "--print-libdir"], root))
    package_db = Path(command([ghc, "--print-global-package-db"], root))
    files = [root / relative(name) for name in inputs]
    files += [Path(ghc), Path(pkg), libdir / "settings", package_db / "package.cache"]
    current = {"schema": 1, "family": family, "workspace": str(root),
        "platform": [platform.system(), platform.release(), platform.machine()],
        "ghc": version, "ghcInfo": command([ghc, "--info"], root),
        "packages": command([pkg, "dump", "--global"], root),
        "inputs": {str(path): digest(path) for path in files},
        "outputs": outputs,
        "environment": {name: os.environ.get(name) for name in
            ("GHC_PACKAGE_PATH", "GHC_ENVIRONMENT", "GHCRTS", "LANG", "LC_ALL", "LIBRARY_PATH", "LD_LIBRARY_PATH")}}
    key = sha(canonical(current))
    archive = cache_home / "v1" / (key + ".tar.gz")
    payload = None
    if archive.is_file():
        try:
            with tarfile.open(archive, "r:gz") as saved:
                record = json.load(saved.extractfile("identity.json"))
                require(record["identity"] == current, "Native oracle identity differs")
                payload = {}
                for name in outputs:
                    member = saved.getmember(name)
                    require(member.isfile(), "Native oracle cache contains a non-file")
                    data = saved.extractfile(member).read()
                    require(sha(data) == record["hashes"][name], "Native oracle cache digest differs")
                    payload[name] = (data, member.mode & 0o777)
        except (CacheMiss, OSError, ValueError, KeyError, tarfile.TarError, EOFError) as error:
            print("Native oracle cache miss: " + str(error), file=sys.stderr)
            payload = None
    if payload is not None:
        for name, (data, mode) in payload.items():
            destination = root / relative(name)
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_bytes(data)
            destination.chmod(mode)
        print(f"NATIVE ORACLE HIT {family}: {key}")
        return
    for step in ("native", "oracle"):
        subprocess.run([str(producer), "scalar", family, step], cwd=root, check=True)
    hashes = {name: digest(root / relative(name)) for name in outputs}
    archive.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(dir=archive.parent) as temporary:
        pending = Path(temporary) / "oracle.tar.gz"
        with tarfile.open(pending, "w:gz", compresslevel=1) as saved:
            data = canonical({"identity": current, "hashes": hashes})
            record = tarfile.TarInfo("identity.json")
            record.size = len(data)
            saved.addfile(record, io.BytesIO(data))
            for name in outputs:
                saved.add(root / relative(name), arcname=name, recursive=False)
        pending.replace(archive)
    print(f"NATIVE ORACLE BUILT {family}: {key}")


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    native = commands.add_parser("native-oracle")
    native.add_argument("--root", type=Path, default=Path.cwd())
    native.add_argument("--producer", type=Path, required=True)
    native.add_argument("--family", choices=("bit", "integer", "signed-narrow", "explicit64"), required=True)
    native.add_argument("--input", action="append", required=True)
    native.add_argument("--output", action="append", required=True)
    native.add_argument("--cache-home", type=Path, default=Path(os.environ.get("THC_NATIVE_ORACLE_CACHE", str(Path.home() / ".cache/thc-native-oracles"))))
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
        if args.command == "native-oracle":
            native_oracle(root, args.producer, args.family, args.input, args.output, args.cache_home)
            return 0
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
