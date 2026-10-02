# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

"""Prepare only native fixtures needed by selected JUnit classes.

The persistent stamps are local acceleration hints. Every reuse checks both the
declared source bytes and every output byte; an unrecognised class runs the
complete preparation script instead of assuming it has no native inputs.
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
FULL_STAMP = STAMP_DIR / "full.json"
PROCESS_CORE_OUTPUTS = frozenset("build/process-lifecycle/core/" + name for name in (
    "manifest.json", "source.json", "pre.cbd", "post.cbd", "pre.audit.json", "post.audit.json",
    *[f"logs/{command}.{suffix}" for command in
      ("version", "libdir", "source-extract", "source-build", "unit", "imports", "pre-audit", "post-audit")
      for suffix in ("stdout", "stderr", "command.json")],
))
PROCESS_SIGNAL_OUTPUTS = frozenset('build/process-signals/' + name for name in (
    'manifest.json', 'oracle.txt', 'native-controls.txt',
))
FULL_OUTPUT_ROOTS = frozenset(f"build/{name}" for name in fast_inputs.BUILD_DIRS) | frozenset({
    "build/backend-annotations", "build/process-signals",
    "build/process-lifecycle/core",
    "build/vector-api", "build/truffle-strings",
    "build/aligned-scalar-memory", "build/addr-identity", "build/io-main-pap", "build/managed-mvars", "build/managed-md5-native",
    "build/pinned-addresses", "build/pinned-pointer-cells", "build/address-array-copy", "build/simd-capability-smoke", "build/managed-address-reads",
    "build/original-stdio", "build/original-stdio-read", "build/original-stdio-close", "build/original-stdio-seek", "build/original-stdio-truncate", "build/original-handle-readiness", "build/core-continuation", "build/live-async", "build/thread-async", "build/thread-status", "build/thread-label", "build/uncaught-self", "build/small-arrays", "build/floating-address", "build/atomic-address",
    "build/floating-byte-offset", "build/unaligned-scalar-memory",
    "build/explicit64-arrays", "build/mask-functions", "build/scalar-exception-results", "build/exception-result-layouts", "build/deep-evaluation", "build/interface-core",
    "build/original-fd-ready", "build/simd-calls", "build/sum-join", "build/record-fields", "build/selector-proof",
})
FULL_REQUIRED = frozenset(fast_inputs.REQUIRED) | frozenset({
    "build/backend-annotations/pre/BackendAnnotations.cbd", "build/backend-annotations/post/BackendAnnotations.cbd", "build/backend-annotations/interface.cbd",
    "build/thc-fixtures.path",
    *(PROCESS_SIGNAL_OUTPUTS if (platform.system(), platform.machine()) == ("Linux", "x86_64")
      else ("build/process-signals/manifest.json",)),
    *(PROCESS_CORE_OUTPUTS if fast_inputs.LINUX_X86_64_HOST else ()),
    *fast_inputs.RUBBISH_OUTPUTS,
    "build/vector-api/core/THC.Prim.cbd", "build/vector-api/core/VectorLoops.cbd",
    "build/vector-api/oracle.tsv",
    "build/truffle-strings/core/THC.Prim.cbd", "build/truffle-strings/core/StringPrimitives.cbd",
    "build/truffle-strings/core/IntrinsicOperands.cbd",
    "build/truffle-strings/core/TruffleStringExceptions.cbd", "build/truffle-strings/core/THC.Exception.cbd",
    "build/truffle-strings/core/THC.Internal.Exception.cbd",
    "build/truffle-strings/oracle.json",
    "build/truffle-strings/manifest.json", "build/truffle-strings/native/oracle",
    "build/selector-proof/manifest.json", "build/selector-proof/api/predicate",
    *[f"build/selector-proof/{stage}/{name}.cbd" for stage in ("pre", "post")
      for name in ("core/SelectorProofAudit", "SelectorProofAudit.roundtrip")],
    *[f"build/selector-proof/commands/{command}.{suffix}"
      for command in ("pre-export", "post-export", "predicate-build", "libdir", "predicate-run")
      for suffix in ("stdout", "stderr", "command.json")],
    "build/sum-join/manifest.json", "build/sum-join/oracle.tsv", "build/sum-join/native/oracle",
    *[f"build/sum-join/{stage}/{suffix}" for stage in ("pre", "post")
      for suffix in ("core/SumJoinAudit.cbd", "audit.json")],
    *[f"build/sum-join/commands/{command}.{suffix}"
      for command in ("ghc-version", "native-build", "native-run", "pre-export", "pre-audit", "post-export", "post-audit")
      for suffix in ("stdout", "stderr", "command.json")],
    "build/record-fields/manifest.json", "build/record-fields/pre/oracle", "build/record-fields/post/oracle",
    *[f"build/record-fields/{stage}/{name}.cbd" for stage in ("pre", "post", "installed")
      for name in ("RecordFieldLibrary", "RecordFieldClient")],
    *[f"build/record-fields/{stage}/{name}.json" for stage in ("pre", "post", "installed")
      for name in ("fieldAlias-audit", "duplicateFields-audit")],
    *[f"build/record-fields/logs/{command}.{suffix}"
      for command in ("plugin-build", "helper-location", "libdir", "pre-compile", "pre-native", "post-compile", "post-native",
                      "installed-RecordFieldLibrary", "installed-RecordFieldClient",
                      *[f"{stage}-audit-{entry}" for stage in ("pre", "post", "installed")
                        for entry in ("fieldAlias", "duplicateFields")])
      for suffix in ("stdout", "stderr", "command.json")],
    *fast_inputs.CLOSURE_INSPECTION_OUTPUTS,
    *fast_inputs.STABLE_NAME_OUTPUTS,
    *fast_inputs.DELIMITED_OUTPUTS,
    *fast_inputs.BCO_OUTPUTS,
    *fast_inputs.THREAD_INVENTORY_OUTPUTS,
    *fast_inputs.THREAD_SCHEDULING_OUTPUTS,
    "build/aligned-scalar-memory/manifest.json", "build/aligned-scalar-memory/oracle.tsv",
    *[f"build/aligned-scalar-memory/{stage}/{file}" for stage in ("pre", "post")
      for file in ("audit.json", "core/AlignedScalarMemoryAudit.cbd")],
    "build/hint-trace/oracle.tsv", "build/hint-trace/native/oracle", "build/hint-trace/native/oracle.eventlog",
    *[f"build/hint-trace/{stage}/{suffix}" for stage in ("pre", "post")
      for suffix in ("core/HintTraceAudit.cbd", "hints.audit.json", "traces.audit.json",
                     "event.audit.json", "marker.audit.json", "binary.audit.json", "addressHints.audit.json")],
    *fast_inputs.SCALAR_MEMORY_OUTPUTS,
    *fast_inputs.SIMD_ADDRESS_OUTPUTS,
    "build/native-malloc/oracle.txt",
    "build/simd-calls/manifest.json", "build/simd-calls/pre-core/SimdCallAudit.cbd",
    "build/simd-calls/pre-audit.json",
    "build/simd-floatx4-fma/manifest.json", "build/simd-floatx4-fma/pre-core/SimdFloatFma.cbd",
    "build/simd-floatx4-fma/pre-audit.json", "build/simd-floatx4-fma/pre-double-audit.json",
    *fast_inputs.SIMD_WIDE_FMA_OUTPUTS,
    *([] if platform.machine().lower() in ("arm64", "aarch64") else
      ["build/simd-calls/oracle.tsv", "build/simd-calls/post-core/SimdCallAudit.cbd",
       "build/simd-calls/post-audit.json", "build/simd-floatx4-fma/oracle.txt",
       "build/simd-floatx4-fma/post-core/SimdFloatFma.cbd", "build/simd-floatx4-fma/post-audit.json",
       "build/simd-floatx4-fma/post-double-audit.json"]),
    "build/interface-core/manifest.json", "build/interface-core/InterfaceLibrary.cbd",
    "build/interface-core/logs/native-oracle.stdout", "build/interface-core/native/oracle",
    "build/interface-core/full/InterfaceLibrary.hi", "build/interface-core/thin/InterfaceLibrary.hi",
    "build/interface-core/full/InterfaceLibrary.dyn_hi", "build/interface-core/full/InterfaceForeign.hi",
    "build/interface-core/source/InterfaceLibrary.saved",
    "build/interface-core/opaqueEntry-audit.json", "build/interface-core/inlineEntry-audit.json",
    "build/interface-core/recursiveEntry-audit.json",
    "build/interface-core/wrapperEntry-audit.json", "build/interface-core/installed-wrapper-facts.json",
    "build/interface-core/CBVCoercionAudit.cbd", "build/interface-core/direct/CBVCoercionAudit.cbd",
    "build/interface-core/full/CBVCoercionAudit.hi", "build/interface-core/thin/CBVCoercionAudit.hi",
    "build/interface-core/source/CBVCoercionAudit.saved", "build/interface-core/coercionEntry-audit.json",
    "build/interface-core/logs/helper-thin.stdout", "build/interface-core/logs/helper-thin.command.json",
    "build/interface-core/wired-unit.json", "build/interface-core/logs/helper-wired-unit.stdout",
    "build/interface-core/logs/helper-wired-unit.command.json",
    "build/interface-core/packages.json", "build/interface-core/driver-controls.json",
    "build/interface-core/cache-controls/facts.json", "build/interface-core/cache-controls/helper-calls",
    "build/interface-core/InterfaceForeign.cbd", "build/interface-core/foreign-packages.json",
    "build/interface-core/foreign-association.json", "build/interface-core/installed-bound-facts.json",
    "build/interface-core/foreign-alias/a.cbd", "build/interface-core/foreign-alias/b.cbd",
    "build/interface-core/source/InterfaceForeignAlias.hs.saved",
    *[f"build/interface-core/import-stubs/{variant}.cbd" for variant in ("plain", "labels", "labels-header", "capi-labels", "capi-labels-header", "finalizer-label", "extra-file", "wrapper", "instrumented")],
    *[f"build/interface-core/typed-foreign-exports/{variant}.cbd" for variant in ("a", "b", "signatures", "static-signatures", "foreign-file", "instrumented", "managed", "registration")],
    "build/interface-core/source/ForeignImportStubs.hs.saved",
    "build/interface-core/import-stubs/plain/ForeignImportStubs.hi",
    "build/interface-core/logs/import-stubs-native-oracle.stdout",
    "build/addr-identity/oracle.txt", "build/addr-identity/pre.audit.json", "build/addr-identity/post.audit.json",
    "build/core-continuation/core/CoreContinuationAudit.cbd", "build/core-continuation/audit.json",
    "build/core-continuation/application-audit.json",
    "build/core-continuation/nested-audit.json",
    "build/core-continuation/native-output.txt",
    "build/core-continuation/core/LazyIOCallbackAudit.cbd",
    "build/core-continuation/lazy-native-output.txt",
    "build/core-continuation/keep-alive-scalar-audit.json",
    "build/core-continuation/keep-alive-tuple-audit.json",
    "build/core-continuation/lazy-action-audit.json", "build/core-continuation/lazy-handler-audit.json",
    "build/live-async/manifest.json", "build/live-async/oracle.txt", "build/live-async/strict-oracle.txt",
    "build/live-async/pre/core/LiveAsyncAudit.cbd", "build/live-async/post/core/LiveAsyncAudit.cbd",
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
    "build/thread-label/manifest.json", "build/thread-label/oracle.txt",
    *[f"build/thread-label/{stage}/{suffix}" for stage in ("pre", "post")
      for suffix in ("core/ThreadLabelAudit.cbd", "selfLabel-audit.json", "overwriteLabel-audit.json",
                     "emptyLabel-audit.json", "deadLabel-audit.json", "deadOverwrite-audit.json")],
    "build/thread-status/manifest.json", "build/thread-status/oracle.txt",
    *[f"build/thread-status/{stage}/{suffix}" for stage in ("pre", "post")
      for suffix in ("core/ThreadStatusAudit.cbd", "selfStatus-audit.json", "maskedStatus-audit.json",
                     "finishedStatus-audit.json", "diedStatus-audit.json", "blockedStatus-audit.json")],
    "build/thread-async/manifest.json", "build/thread-async/oracle.txt", "build/thread-async/extra-oracle.txt",
    "build/thread-async/lazy-oracle.txt", "build/thread-async/saved-oracle.txt",
    "build/thread-async/external-saved-oracle.txt",
    "build/thread-async/scheduled-saved-oracle.txt",
    "build/thread-async/pre/scheduledSaved-audit.json", "build/thread-async/post/scheduledSaved-audit.json",
    "build/thread-async/pre/externalSaved-audit.json", "build/thread-async/post/externalSaved-audit.json",
    "build/thread-async/pre/core/ThreadAsyncAudit.cbd", "build/thread-async/post/core/ThreadAsyncAudit.cbd",
    "build/thread-async/pre/core/LazyForkAudit.cbd", "build/thread-async/post/core/LazyForkAudit.cbd",
    "build/thread-async/pre/lazyFork-audit.json", "build/thread-async/post/lazyFork-audit.json",
    "build/thread-async/pre/forkAndThrow-audit.json", "build/thread-async/post/forkAndThrow-audit.json",
    "build/thread-async/pre/killUncaught-audit.json", "build/thread-async/post/killUncaught-audit.json",
    "build/thread-async/pre/selfThrow-audit.json", "build/thread-async/post/selfThrow-audit.json",
    "build/thread-async/pre/maskedUnmaskSelf-audit.json", "build/thread-async/post/maskedUnmaskSelf-audit.json",
    "build/thread-async/pre/promptSelfThrow-audit.json", "build/thread-async/post/promptSelfThrow-audit.json",
    "build/thread-async/pre/promptMaskedUnmaskSelf-audit.json", "build/thread-async/post/promptMaskedUnmaskSelf-audit.json",
    *[f"build/thread-async/{stage}/{entry}-audit.json" for stage in ("pre", "post")
      for entry in ("savedSelfThrow", "savedMaskedSelf", "savedSuffixSelf", "savedMaskCatchSelf")],
    "build/uncaught-self/manifest.json", "build/uncaught-self/native/oracle",
    "build/uncaught-self/pre/core/UncaughtSelfAudit.cbd", "build/uncaught-self/post/core/UncaughtSelfAudit.cbd",
    "build/uncaught-self/pre/audit.json", "build/uncaught-self/post/audit.json",
    "build/uncaught-self/pre/io-audit.json", "build/uncaught-self/post/io-audit.json",
    "build/mask-functions/manifest.json",
    "build/mask-functions/pre/core/MaskFunctionAudit.cbd",
    "build/mask-functions/post/core/MaskFunctionAudit.cbd",
    "build/mask-functions/logs/native-oracle.stdout",
    "build/scalar-exception-results/manifest.json", "build/scalar-exception-results/native/oracle",
    "build/exception-result-layouts/manifest.json", "build/exception-result-layouts/native/oracle",
    "build/exception-result-layouts/oracle.tsv",
    *[f"build/exception-result-layouts/{stage}/{name}"
      for stage in (("pre",) if platform.machine().lower() in ("arm64", "aarch64") else ("pre", "post"))
      for name in ("core/ExceptionResultLayoutsAudit.cbd",
                   *[f"{family}Result-audit.json" for family in ("int8", "word8", "int16", "word16", "int32", "word32",
                       "int64", "word64", "float", "double", "empty", "nested", "sum", "vector", "unlifted", "unliftedPayload")])],
    "build/scalar-exception-results/logs/native-oracle.stdout",
    *[f"build/scalar-exception-results/{stage}/{name}" for stage in ("pre", "post")
      for name in ("core/ScalarExceptionResultsAudit.cbd",
                   *[f"{prefix}{suffix}-audit.json" for prefix in ("normal", "throw", "interrupt")
                     for suffix in ("Int", "Word", "Addr")])],
    "build/deep-evaluation/manifest.json", "build/deep-evaluation/native/oracle",
    *[f"build/deep-evaluation/{stage}/{name}" for stage in ("pre", "post")
      for name in ("core/DeepEvaluation.cbd", "core/THC.InterfaceClosure.cbd", "audit.json")],
    *[f"build/deep-evaluation/logs/{command}.{suffix}"
      for command in ("ghc-version", "native-compile", "native-oracle", "pre-export", "pre-audit", "post-export", "post-audit")
      for suffix in ("stdout", "stderr", "command.json")],
    "build/addr-identity/pre-core/AddressIdentityAudit.cbd", "build/addr-identity/post-core/AddressIdentityAudit.cbd",
    "build/io-main-pap/provenance.json", "build/managed-mvars/manifest.json", "build/managed-md5-native/provenance.json",
    "build/pinned-addresses/manifest.json",
    *fast_inputs.INTEGER_COMPLETION_OUTPUTS,
    "build/pinned-pointer-cells/manifest.json",
    "build/pinned-pointer-cells/oracle.tsv", "build/pinned-pointer-cells/pre/audit.json",
    "build/pinned-pointer-cells/post/audit.json",
    "build/pinned-pointer-cells/pre/core/PinnedPointerCellsAudit.cbd",
    "build/pinned-pointer-cells/post/core/PinnedPointerCellsAudit.cbd",
    "build/floating-address/manifest.json", "build/floating-address/oracle.tsv",
    "build/atomic-address/manifest.json", "build/atomic-address/oracle.tsv",
    "build/atomic-address/pre/audit.json", "build/atomic-address/post/audit.json",
    "build/atomic-address/pre/core/AtomicAddressAudit.cbd",
    "build/atomic-address/post/core/AtomicAddressAudit.cbd",
    "build/floating-address/pre/audit.json", "build/floating-address/post/audit.json",
    "build/floating-address/pre/core/FloatingAddressAudit.cbd",
    "build/floating-address/post/core/FloatingAddressAudit.cbd",
    "build/floating-byte-offset/manifest.json", "build/floating-byte-offset/oracle.tsv",
    "build/floating-byte-offset/pre/audit.json", "build/floating-byte-offset/post/audit.json",
    "build/floating-byte-offset/pre/core/FloatingByteOffsetAudit.cbd",
    "build/floating-byte-offset/post/core/FloatingByteOffsetAudit.cbd",
    "build/unaligned-scalar-memory/manifest.json", "build/unaligned-scalar-memory/oracle.tsv",
    "build/unaligned-scalar-memory/inputs.txt",
    "build/unaligned-scalar-memory/logs/ghc-inventory.stdout",
    "build/unaligned-scalar-memory/pre/audit.json", "build/unaligned-scalar-memory/post/audit.json",
    "build/unaligned-scalar-memory/pre/core/UnalignedScalarMemoryAudit.cbd",
    "build/unaligned-scalar-memory/post/core/UnalignedScalarMemoryAudit.cbd",
    "build/explicit64-arrays/manifest.json", "build/explicit64-arrays/oracle.tsv",
    "build/shrink-bytearrays/manifest.json", "build/shrink-bytearrays/oracle.tsv",
    "build/shrink-bytearrays/pre/core/ShrinkMutableByteArrayAudit.cbd",
    "build/shrink-bytearrays/post/core/ShrinkMutableByteArrayAudit.cbd",
    "build/shrink-bytearrays/pre/core/THC.InterfaceClosure.cbd",
    "build/shrink-bytearrays/post/core/THC.InterfaceClosure.cbd",
    "build/fetch-add-int-array/manifest.json", "build/fetch-add-int-array/oracle.tsv",
    "build/atomic-int-arrays/manifest.json", "build/atomic-int-arrays/oracle.tsv",
    "build/atomic-int-arrays/pre/core/AtomicIntArrayAudit.cbd",
    "build/atomic-int-arrays/post/core/AtomicIntArrayAudit.cbd",
    "build/atomic-int-arrays/pre/core/THC.InterfaceClosure.cbd",
    "build/atomic-int-arrays/post/core/THC.InterfaceClosure.cbd",
    "build/fetch-add-int-array/pre/core/FetchAddIntArrayAudit.cbd",
    "build/fetch-add-int-array/post/core/FetchAddIntArrayAudit.cbd",
    "build/fetch-add-int-array/pre/core/THC.InterfaceClosure.cbd",
    "build/fetch-add-int-array/post/core/THC.InterfaceClosure.cbd",
    "build/explicit64-arrays/pre/audit.json", "build/explicit64-arrays/post/audit.json",
    "build/explicit64-arrays/pre/core/Explicit64ArrayAudit.cbd",
    "build/explicit64-arrays/post/core/Explicit64ArrayAudit.cbd",
    "build/managed-address-reads/manifest.json",
    "build/original-stdio/manifest.json",
    "build/original-stdio-read/manifest.json", "build/original-stdio-read/oracle.json",
    "build/original-stdio-close/manifest.json", "build/original-stdio-close/oracle.json",
    "build/original-posix-dup/manifest.json", "build/original-posix-dup/oracle.json",
    "build/original-stdio-seek/manifest.json", "build/original-stdio-seek/oracle.json",
    "build/original-stdio-truncate/manifest.json", "build/original-stdio-truncate/oracle.json",
    *fast_inputs.ORIGINAL_FD_READY_OUTPUTS,
    *fast_inputs.ORIGINAL_RTS_LOCK_OUTPUTS,
    *fast_inputs.RTS_DIAGNOSTIC_OUTPUTS,
    *fast_inputs.RTS_SHUTDOWN_OUTPUTS,
    *(fast_inputs.ORIGINAL_OPEN_OUTPUTS if fast_inputs.LINUX_X86_64_HOST else {"build/original-open/manifest.json"}),
    *(fast_inputs.ORIGINAL_FCNTL_OUTPUTS if fast_inputs.LINUX_X86_64_HOST else {"build/original-fcntl/manifest.json"}),
    *(fast_inputs.ORIGINAL_ERRNO_OUTPUTS if fast_inputs.ERRNO_NATIVE_HOST else {"build/original-errno/manifest.json"}),
    *(fast_inputs.ORIGINAL_PROCESS_IDENTITY_OUTPUTS if fast_inputs.ERRNO_NATIVE_HOST else {"build/original-process-identity/manifest.json"}),
    *(fast_inputs.ORIGINAL_TERMIOS_OUTPUTS if fast_inputs.LINUX_X86_64_HOST else {"build/original-termios/manifest.json"}),
    *(fast_inputs.ORIGINAL_TCSETATTR_OUTPUTS if fast_inputs.LINUX_X86_64_HOST else {"build/original-tcsetattr/manifest.json"}),
    *(fast_inputs.ORIGINAL_TCGETATTR_OUTPUTS if fast_inputs.LINUX_X86_64_HOST else {"build/original-tcgetattr/manifest.json"}),
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
    "fast", "float-decode-originals", "generated", "ghc", "libs", "reports", "resources",
    "snapshot", "source-ghc", "test-results", "tmp",
})
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
    if group["outputs"] == ["build/original-stack-formatter"]:
        return _formatter_output_hashes(root)
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
    if group["outputs"] == ["build/original-fcntl"]:
        name = "build/original-fcntl/manifest.json"
        expected = fast_inputs.fcntl_artifact_hashes(json.loads(fast_inputs.file_path(root, name).read_text()))
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
        (root / "bin/prepare-tests.sh").read_text().splitlines())
        if line.strip() and (index == 0 or not line.lstrip().startswith("#"))]
    return hashlib.sha256(("\n".join(lines) + "\n").encode()).hexdigest()


def _full_key(root):
    plan = _preparation_plan(root)
    extra = ("build.gradle", "thc.cabal", "cabal.project", "Setup.hs",
             ".github/scripts/fast-fixtures.json",
             ".github/scripts/fast_fixtures.py")
    value = {"schema": 1, "identity": fast_inputs.identity(root),
             "declaration": {"plan": plan,
                             "roots": sorted(FULL_OUTPUT_ROOTS),
                             "required": sorted(FULL_REQUIRED)},
             "extraSources": {name: _digest(root / name) for name in extra}}
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
        if name == "build/process-signals":
            files.update(_output_hashes(root, {"outputs": [name]}))
            continue
        if name == "build/package-native-gc-carriers":
            files.update(_output_hashes(root, {"outputs": [name]}))
            continue
        if name == "build/original-stack-formatter":
            files.update(_formatter_output_hashes(root))
            continue
        if name == "build/process-lifecycle/core":
            files.update(_output_hashes(root, {"outputs": [name]}))
            continue
        if name in ("build/original-path-stat", "build/original-path-mode", "build/original-path-link", "build/original-directory-paths", "build/original-path-access", "build/original-unlinkat", "build/original-fstatat", "build/original-current-directory", "build/original-directory-streams"):
            files.update(_output_hashes(root, {"outputs": [name]}))
            continue
        if name.removeprefix("build/") in (fast_inputs.BYTEARRAY_FAMILIES | fast_inputs.SIMD_BYTEARRAY_FAMILIES) or name in ("build/float-decode", "build/pinned-addresses", "build/rts-diagnostics", "build/rts-shutdown", "build/original-rts-locks", "build/original-fd-ready", "build/original-open", "build/original-fcntl", "build/original-errno", "build/original-process-identity", "build/original-termios", "build/original-tcsetattr", "build/original-tcgetattr"):
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
        if name == "build/thc-fixtures.path":
            _require_prepared_encoder(root)
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
    run("fixtures-full", ["bin/prepare-tests.sh"])
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
    # Complete selections still have declared owners and can reuse each group's
    # verified products. Only unknown classes or wildcards need the fallback.
    if any(name not in owners for name in classes):
        return _prepare_full(root, run)
    if selection.get("mode") not in ("narrow", "full"):
        raise ValueError("Invalid selected test mode")

    groups = _group_order(manifest, {owners[name] for name in classes if owners[name] is not None})
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
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    _, owners = _manifest(root)
    selection = local_selection(args.tests, owners)
    if selection["mode"] == "narrow" and all(owners[name] is None for name in selection["junit"]["classes"]):
        print("Selected tests need no generated fixtures.")
        return
    # A developer's ambient package environment is not a fixture dependency.
    os.environ.setdefault("GHC_ENVIRONMENT", "-")
    ghc, pkg = subprocess.check_output(
        ["sh", "-c", '. ./bin/toolchain.sh; printf "%s\\n" "$GHC" "$GHC_PKG"'],
        cwd=root, text=True).splitlines()
    os.environ.update(GHC=ghc, GHC_PKG=pkg)
    def run(name, argv, stdout=None):
        print("+ " + repr(argv), flush=True)
        if stdout is None:
            subprocess.run(argv, cwd=root, check=True)
        else:
            with (root / stdout).open("w") as output:
                subprocess.run(argv, cwd=root, stdout=output, check=True)
    toolchain = {"platform": {"system": platform.system(), "machine": platform.machine()},
                 "toolchain": fast_inputs.toolchain(root)}
    print(prepare(root, selection, run, toolchain))


if __name__ == "__main__":
    main()
