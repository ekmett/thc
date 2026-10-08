# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
# The auditor consumes its Python modules, declared capability tables and the
# CBD decoder. No executable fixture needs a generated JSON Core sibling.
file(GLOB audit_modules CONFIGURE_DEPENDS "${PROJECT_SOURCE_DIR}/bin/core_*.py")
set(audit_inputs ${audit_modules}
  "${PROJECT_SOURCE_DIR}/bin/audit-core.py"
  "${PROJECT_SOURCE_DIR}/bin/core-capabilities.json"
  "${PROJECT_SOURCE_DIR}/bin/simd-families.json"
  "${PROJECT_SOURCE_DIR}/src/main/resources/thc/scalar-primop-signatures.json"
  "${PROJECT_SOURCE_DIR}/src/main/resources/thc/core-native-overrides.json"
  "${PROJECT_SOURCE_DIR}/src/test/resources/thc/polyglot-abi.json")
function(audited_fixture name modules)
  cmake_parse_arguments(F "" "" "SOURCES;OUTPUTS;OBJECT_DIRS;BYPRODUCTS" ${ARGN})
  set(out "${PROJECT_SOURCE_DIR}/build/${name}")
  set(inputs)
  foreach(source IN LISTS F_SOURCES)
    list(APPEND inputs "${PROJECT_SOURCE_DIR}/${source}")
  endforeach()
  # Existing receipts also hash these exporter sources (including Windows).
  foreach(script export-core.sh export-core.ps1 windows-common.ps1 build-compiler.sh toolchain.sh plugin.py)
    list(APPEND inputs "${PROJECT_SOURCE_DIR}/bin/${script}")
  endforeach()
  set(outputs)
  foreach(output IN LISTS F_OUTPUTS)
    list(APPEND outputs "${out}/${output}")
  endforeach()
  set(objects "${out}/native/Main.hi" "${out}/native/Main.o")
  foreach(directory IN LISTS F_OBJECT_DIRS)
    foreach(module IN LISTS modules)
      list(APPEND objects "${out}/${directory}/${module}.hi" "${out}/${directory}/${module}.o")
    endforeach()
  endforeach()
  foreach(product IN LISTS F_BYPRODUCTS)
    list(APPEND objects "${out}/${product}")
  endforeach()
  add_custom_command(OUTPUT ${outputs} BYPRODUCTS ${objects}
    COMMAND ${fixture_env} "${fixtures_exe}" "${name}"
    DEPENDS ${inputs} ${audit_inputs} ${tool_sources} ${cabal_inputs}
      "${fixtures_exe}" "${compact_exe}" ${plugin_outputs} ${toolchain_inputs}
    WORKING_DIRECTORY "${PROJECT_SOURCE_DIR}" VERBATIM
    COMMENT "Generate ${name}: named Core, native and audit outputs")
  add_custom_target(fixture-${name} DEPENDS ${outputs})
endfunction()

# 013: addressable cells must preserve pointer values, ownership and byte access.
audited_fixture(pinned-pointer-cells PinnedPointerCellsAudit
  SOURCES t/fixtures/compiler/PinnedPointerCellsAudit.hs t/fixtures/compiler/PinnedPointerCellsNative.hs
  OBJECT_DIRS native pre/ghc post/ghc
  OUTPUTS manifest.json oracle.tsv native/oracle
    pre/core/PinnedPointerCellsAudit.cbd pre/core/THC.InterfaceClosure.cbd pre/audit.json
    post/core/PinnedPointerCellsAudit.cbd post/core/THC.InterfaceClosure.cbd post/audit.json)

# 015: quotient/remainder pairs, unsigned division and overflow flags.
set(integer_logs)
foreach(label ghc-version ghc-info pre-export pre-audit post-export post-audit native-build native-oracle)
  foreach(suffix stdout stderr command.json)
    list(APPEND integer_logs "commands/${label}.${suffix}")
  endforeach()
endforeach()
audited_fixture(integer-completion IntegerCompletionAudit
  SOURCES t/fixtures/compiler/IntegerCompletionAudit.hs
  OBJECT_DIRS native pre-ghc post-ghc
  OUTPUTS manifest.json NativeIntegerCompletion.hs requests.tsv oracle.tsv
    native/integer-completion-oracle pre-core/IntegerCompletionAudit.cbd
    post-core/IntegerCompletionAudit.cbd pre-audit.json post-audit.json ${integer_logs})

# 016: unsigned-to-floating rounding cannot be inferred from signed arithmetic.
audited_fixture(word-floating WordFloatingAudit
  SOURCES t/fixtures/compiler/WordFloatingAudit.hs t/fixtures/compiler/WordFloatingNative.hs
  OBJECT_DIRS native pre-ghc post-ghc
  OUTPUTS manifest.json oracle.tsv native/word-floating-oracle
    pre-core/WordFloatingAudit.cbd post-core/WordFloatingAudit.cbd pre-audit.json post-audit.json)

# 017: carry/multiply/division return every component across calls. This is the
# sole producer of these CBDs; the quarantined cbv recipe must not export them.
audited_fixture(tuple-arithmetic TupleArithmeticAudit
  SOURCES t/fixtures/compiler/TupleArithmeticAudit.hs
  OBJECT_DIRS native pre-ghc post-ghc
  OUTPUTS manifest.json NativeTupleArithmetic.hs oracle.tsv call-oracle.tsv
    native/tuple-arithmetic-oracle pre-core/TupleArithmeticAudit.cbd
    post-core/TupleArithmeticAudit.cbd pre-audit.json post-audit.json)

# 023: preserve Float/Double bit patterns across fields, captures and casts.
set(bitcast_reports)
set(bitcast_labels native-build native-oracle)
foreach(stage pre post)
  list(APPEND bitcast_labels "${stage}-export")
  foreach(family float double)
    foreach(operation Roundtrip Field Captured Decode Encode)
      list(APPEND bitcast_reports "${stage}-${family}${operation}-audit.json")
      list(APPEND bitcast_labels "${stage}-${family}${operation}-audit")
    endforeach()
  endforeach()
endforeach()
set(bitcast_logs)
foreach(label IN LISTS bitcast_labels)
  foreach(suffix stdout stderr command.json)
    list(APPEND bitcast_logs "commands/${label}.${suffix}")
  endforeach()
endforeach()
audited_fixture(scalar-bitcasts ScalarBitCastAudit
  SOURCES t/fixtures/compiler/ScalarBitCastAudit.hs t/fixtures/compiler/ScalarBitCastNative.hs
    src/tools/primops/PrimopTools.hs
  OBJECT_DIRS native pre-ghc post-ghc
  OUTPUTS manifest.json inputs.tsv oracle.tsv native/scalar-bitcast-oracle
    pre-core/ScalarBitCastAudit.cbd post-core/ScalarBitCastAudit.cbd
    pre-audit.json post-audit.json ${bitcast_reports} ${bitcast_logs})

# 025: inverse hyperbolic functions, min/max and decoded words, including calls.
set(floating_reports)
set(floating_labels native-build native-oracle)
set(floating_entries decodeWordsDirect decodeWordsCall asinhExample)
foreach(operation asinh acosh atanh min max)
  foreach(type Float Double)
    list(APPEND floating_entries "${operation}${type}")
  endforeach()
endforeach()
foreach(stage pre post)
  list(APPEND floating_labels "${stage}-export")
  foreach(entry IN LISTS floating_entries)
    list(APPEND floating_reports "${stage}-${entry}-audit.json")
    list(APPEND floating_labels "${stage}-${entry}-audit")
  endforeach()
endforeach()
set(floating_logs)
foreach(label IN LISTS floating_labels)
  foreach(suffix stdout stderr command.json)
    list(APPEND floating_logs "commands/${label}.${suffix}")
  endforeach()
endforeach()
audited_fixture(floating-remainder "FloatingRemainderAudit;InverseHyperbolic"
  SOURCES t/fixtures/compiler/FloatingRemainderAudit.hs t/fixtures/compiler/FloatingRemainderNative.hs
    t/fixtures/core/InverseHyperbolic.hs
  OBJECT_DIRS native pre-ghc post-ghc
  OUTPUTS manifest.json inputs.tsv oracle.tsv native/oracle
    pre-core/FloatingRemainderAudit.cbd post-core/FloatingRemainderAudit.cbd
    pre-core/InverseHyperbolic.cbd post-core/InverseHyperbolic.cbd
    ${floating_reports} ${floating_logs})

# 034: fused operations have rounding behavior separate multiply/add cannot test.
audited_fixture(fused-floating FloatingAudit
  SOURCES t/fixtures/compiler/FloatingAudit.hs t/fixtures/compiler/FloatingAuditNative.hs
  OBJECT_DIRS native pre-ghc post-ghc
  OUTPUTS manifest.json oracle.tsv native/floating-oracle
    pre-core/FloatingAudit.cbd post-core/FloatingAudit.cbd pre-audit.json post-audit.json)

# 048: small boxed arrays and safe slices retain values and laziness.
audited_fixture(small-arrays SmallArrayAudit
  SOURCES t/fixtures/compiler/SmallArrayAudit.hs t/fixtures/compiler/SmallArrayAuditNative.hs
  OBJECT_DIRS native pre/ghc post/ghc
  OUTPUTS manifest.json oracle.tsv native/oracle
    pre/core/SmallArrayAudit.cbd post/core/SmallArrayAudit.cbd pre/audit.json post/audit.json)

# 050: typed reads through managed addresses use these two declared CBDs only.
set(managed_reports)
foreach(stage pre post)
  foreach(entry word32Read wordRead int32Read intRead)
    list(APPEND managed_reports "${stage}-${entry}.audit.json")
  endforeach()
endforeach()
audited_fixture(managed-address-reads ManagedAddressReadAudit
  SOURCES t/fixtures/compiler/ManagedAddressReadAudit.hs t/fixtures/compiler/ManagedAddressReadNative.hs
  OBJECT_DIRS native pre-ghc post-ghc
  OUTPUTS manifest.json requests.tsv oracle.tsv native/managed-address-read-oracle
    pre-core/ManagedAddressReadAudit.cbd pre-core/THC.InterfaceClosure.cbd
    post-core/ManagedAddressReadAudit.cbd post-core/THC.InterfaceClosure.cbd ${managed_reports})

# 051: wide-character address offsets and representation.
set(wide_char_logs)
foreach(label ghc-version ghc-info native-compile native-oracle pre-export pre-audit post-export post-audit)
  foreach(suffix stdout stderr command.json)
    list(APPEND wide_char_logs "logs/${label}.${suffix}")
  endforeach()
endforeach()
audited_fixture(wide-char-address WideCharAddressAudit
  SOURCES t/fixtures/compiler/WideCharAddressAudit.hs t/fixtures/compiler/WideCharAddressNative.hs
  OBJECT_DIRS native pre/ghc post/ghc
  OUTPUTS manifest.json native/oracle
    pre/core/WideCharAddressAudit.cbd pre/core/THC.InterfaceClosure.cbd pre/audit.json
    post/core/WideCharAddressAudit.cbd post/core/THC.InterfaceClosure.cbd post/audit.json ${wide_char_logs})

# 053: byte/length/offset semantics across THC's memory boundary.
set(memory_logs)
foreach(label native-build native-oracle pre-export pre-audit post-export post-audit)
  foreach(suffix stdout stderr command.json)
    list(APPEND memory_logs "commands/${label}.${suffix}")
  endforeach()
endforeach()
audited_fixture(scalar-memory-utilities ScalarMemoryUtilities
  SOURCES t/fixtures/compiler/ScalarMemoryUtilities.hs t/fixtures/compiler/ScalarMemoryUtilitiesNative.hs
  OBJECT_DIRS native pre/ghc post/ghc
  OUTPUTS manifest.json oracle.tsv native/oracle
    pre/core/ScalarMemoryUtilities.cbd post/core/ScalarMemoryUtilities.cbd
    pre/audit.json post/audit.json ${memory_logs})

# 056: shared mutable state, prompt resumption, catch/mask state and parked
# continuations; native observations stay independent of compiler root layouts.
set(continuation_logs)
foreach(label ghc-version native-build native-run parked-native-build parked-native-run
    parked-export parked-audit pre-export post-export pre-audit post-audit)
  foreach(suffix stdout stderr command.json)
    list(APPEND continuation_logs "commands/${label}.${suffix}")
  endforeach()
endforeach()
audited_fixture(delimited-continuations DelimitedContinuations
  SOURCES t/fixtures/core/DelimitedContinuations.hs t/fixtures/compiler/DelimitedContinuationsNative.hs
    t/fixtures/core/ParkedControl.hs t/fixtures/compiler/ParkedControlNative.hs
  OBJECT_DIRS native pre/ghc post/ghc
  OUTPUTS manifest.json native/oracle parked/native/oracle
    pre/core/DelimitedContinuations.cbd post/core/DelimitedContinuations.cbd
    pre/audit.json post/audit.json parked/core/ParkedControl.cbd parked/audit.json ${continuation_logs}
  BYPRODUCTS parked/native/Main.hi parked/native/Main.o
    parked/native/ParkedControl.hi parked/native/ParkedControl.o
    parked/ghc/ParkedControl.hi parked/ghc/ParkedControl.o)

# 057: observable scheduling behavior; no assertions about an incidental order.
set(scheduling_reports)
foreach(stage pre post)
  foreach(entry emptySpark lazyPar lazySpark sparkValue currentCounter negativeCounter pinnedFork otherCounter timedDelay)
    list(APPEND scheduling_reports "${stage}/${entry}-audit.json")
  endforeach()
endforeach()
audited_fixture(thread-scheduling ThreadScheduling
  SOURCES t/fixtures/core/ThreadScheduling.hs t/fixtures/compiler/ThreadSchedulingNative.hs
  OBJECT_DIRS native pre/ghc post/ghc
  OUTPUTS manifest.json oracle.txt native/oracle pre/core/ThreadScheduling.cbd
    post/core/ThreadScheduling.cbd ${scheduling_reports})

# 061: label replacement and labels on completed threads.
set(label_reports)
foreach(stage pre post)
  foreach(entry selfLabel overwriteLabel emptyLabel deadLabel deadOverwrite)
    list(APPEND label_reports "${stage}/${entry}-audit.json")
  endforeach()
endforeach()
audited_fixture(thread-label ThreadLabelAudit
  SOURCES t/fixtures/compiler/ThreadLabelAudit.hs t/fixtures/compiler/ThreadLabelNative.hs
  OBJECT_DIRS native pre/ghc post/ghc
  OUTPUTS manifest.json oracle.txt native/oracle pre/core/ThreadLabelAudit.cbd
    post/core/ThreadLabelAudit.cbd ${label_reports})

# 082: explicit weak operations; this does not test GC scheduling.
audited_fixture(weak-explicit WeakAudit
  SOURCES t/fixtures/compiler/WeakAudit.hs
  OBJECT_DIRS native pre/ghc post/ghc
  OUTPUTS manifest.json NativeWeak.hs oracle.tsv native/weak-oracle
    pre/core/WeakAudit.cbd pre/core/THC.InterfaceClosure.cbd pre/audit.json
    post/core/WeakAudit.cbd post/core/THC.InterfaceClosure.cbd post/audit.json)

# 083: stable-name equality follows sharing, not native hash values.
set(stable_reports)
set(stable_labels ghc-version native-build native-run)
foreach(stage pre post)
  list(APPEND stable_labels "${stage}-export")
  foreach(entry sameLifted sameUnlifted differentUnlifted unevaluatedName)
    list(APPEND stable_reports "${stage}/${entry}-audit.json")
    list(APPEND stable_labels "${stage}-audit-${entry}")
  endforeach()
endforeach()
set(stable_logs)
foreach(label IN LISTS stable_labels)
  foreach(suffix stdout stderr command.json)
    list(APPEND stable_logs "commands/${label}.${suffix}")
  endforeach()
endforeach()
audited_fixture(stable-names StableNames
  SOURCES t/fixtures/core/StableNames.hs t/fixtures/compiler/StableNamesNative.hs
  OBJECT_DIRS native pre/ghc post/ghc
  OUTPUTS manifest.json native/oracle pre/core/StableNames.cbd post/core/StableNames.cbd
    ${stable_reports} ${stable_logs})

# 091,092,094: distinct explicit-width, address and byte-offset memory boundaries.
audited_fixture(explicit64-arrays Explicit64ArrayAudit
  SOURCES t/fixtures/compiler/Explicit64ArrayAudit.hs t/fixtures/compiler/Explicit64ArrayNative.hs
  OBJECT_DIRS native pre/ghc post/ghc
  OUTPUTS manifest.json oracle.tsv native/oracle pre/core/Explicit64ArrayAudit.cbd
    post/core/Explicit64ArrayAudit.cbd pre/audit.json post/audit.json)
audited_fixture(floating-address FloatingAddressAudit
  SOURCES t/fixtures/compiler/FloatingAddressAudit.hs t/fixtures/compiler/FloatingAddressNative.hs
  OBJECT_DIRS native pre/ghc post/ghc
  OUTPUTS manifest.json oracle.tsv native/oracle pre/core/FloatingAddressAudit.cbd
    post/core/FloatingAddressAudit.cbd pre/audit.json post/audit.json)
audited_fixture(floating-byte-offset FloatingByteOffsetAudit
  SOURCES t/fixtures/compiler/FloatingByteOffsetAudit.hs t/fixtures/compiler/FloatingByteOffsetNative.hs
  OBJECT_DIRS native pre/ghc post/ghc
  OUTPUTS manifest.json oracle.tsv native/oracle pre/core/FloatingByteOffsetAudit.cbd
    post/core/FloatingByteOffsetAudit.cbd pre/audit.json post/audit.json)

# 093: native-address atomics must preserve values across the memory boundary.
set(atomic_logs)
foreach(label version export-pre export-post audit-pre audit-post native-build native-oracle)
  foreach(suffix stdout stderr command.json)
    list(APPEND atomic_logs "logs/${label}.${suffix}")
  endforeach()
endforeach()
audited_fixture(atomic-address AtomicAddressAudit
  SOURCES t/fixtures/compiler/AtomicAddressAudit.hs t/fixtures/compiler/AtomicAddressNative.hs
  OBJECT_DIRS native pre/ghc post/ghc
  OUTPUTS manifest.json inputs.txt oracle.tsv native/oracle pre/core/AtomicAddressAudit.cbd
    post/core/AtomicAddressAudit.cbd pre/audit.json post/audit.json ${atomic_logs})

# 095: unaligned loads/stores exercise different offsets from aligned accesses.
set(unaligned_logs)
foreach(label ghc-version ghc-inventory pre-export post-export pre-audit post-audit native-build native-oracle)
  foreach(suffix stdout stderr command.json)
    list(APPEND unaligned_logs "logs/${label}.${suffix}")
  endforeach()
endforeach()
audited_fixture(unaligned-scalar-memory UnalignedScalarMemoryAudit
  SOURCES t/fixtures/compiler/UnalignedScalarMemoryAudit.hs t/fixtures/compiler/UnalignedScalarMemoryNative.hs
  OBJECT_DIRS native pre/ghc post/ghc
  OUTPUTS manifest.json inputs.txt oracle.tsv native/oracle pre/core/UnalignedScalarMemoryAudit.cbd
    post/core/UnalignedScalarMemoryAudit.cbd pre/audit.json post/audit.json ${unaligned_logs})

# 102: pointer-array copying must preserve the selected bytes and aliases.
set(copy_reports)
set(copy_labels native-build native-oracle)
foreach(stage pre post)
  list(APPEND copy_labels "${stage}-export")
  foreach(entry addrToArray arrayToAddr mutableArrayToAddr)
    list(APPEND copy_reports "${stage}-${entry}-audit.json")
    list(APPEND copy_labels "${stage}-${entry}-audit")
  endforeach()
endforeach()
set(copy_logs)
foreach(label IN LISTS copy_labels)
  foreach(suffix stdout stderr command.json)
    list(APPEND copy_logs "commands/${label}.${suffix}")
  endforeach()
endforeach()
audited_fixture(address-array-copy AddressArrayCopyAudit
  SOURCES t/fixtures/compiler/AddressArrayCopyAudit.hs t/fixtures/compiler/AddressArrayCopyNative.hs
  OBJECT_DIRS native pre-ghc post-ghc
  OUTPUTS manifest.json inputs.tsv oracle.tsv native/oracle pre-core/AddressArrayCopyAudit.cbd
    post-core/AddressArrayCopyAudit.cbd ${copy_reports} ${copy_logs})

# 104: bounded deep evaluation checks stack safety that shallow smoke misses.
set(deep_logs)
foreach(label ghc-version native-compile native-oracle pre-export pre-audit post-export post-audit)
  foreach(suffix stdout stderr command.json)
    list(APPEND deep_logs "logs/${label}.${suffix}")
  endforeach()
endforeach()
audited_fixture(deep-evaluation DeepEvaluation
  SOURCES t/fixtures/compiler/DeepEvaluation.hs t/fixtures/compiler/DeepEvaluationNative.hs
  OBJECT_DIRS native pre/ghc post/ghc
  OUTPUTS manifest.json native/oracle pre/core/DeepEvaluation.cbd pre/core/THC.InterfaceClosure.cbd
    post/core/DeepEvaluation.cbd post/core/THC.InterfaceClosure.cbd pre/audit.json post/audit.json ${deep_logs})

# 105/106: keep native one-word continuation observations separate from the
# boxed native model required for wider layouts. Both batch observations/audits.
foreach(group exception-result-layouts scalar-exception-results)
  set(exception_stages pre post)
  set(exception_outputs manifest.json native/oracle)
  set(exception_labels ghc-version native-compile native-oracle)
  set(exception_objects)
  if(group STREQUAL "exception-result-layouts")
    set(exception_module ExceptionResultLayoutsAudit)
    set(exception_driver ExceptionResultLayoutsNative)
    list(APPEND exception_outputs oracle.tsv)
    # GHC's AArch64 NCG cannot emit this vector result. The native model needs
    # no vector codegen; the pre-Tidy export uses -fno-code and writes only .hi.
    if(CMAKE_SYSTEM_PROCESSOR MATCHES "^(arm64|aarch64|ARM64)$")
      set(exception_stages pre)
    endif()
  else()
    set(exception_module ScalarExceptionResultsAudit)
    set(exception_driver ScalarExceptionResultsNative)
    list(APPEND exception_objects "native/${exception_module}.hi" "native/${exception_module}.o")
  endif()
  foreach(stage IN LISTS exception_stages)
    list(APPEND exception_outputs "${stage}/core/${exception_module}.cbd" "${stage}/audit.json")
    list(APPEND exception_labels "${stage}-export" "${stage}-audit")
    list(APPEND exception_objects "${stage}/ghc/${exception_module}.hi")
    if(NOT (group STREQUAL "exception-result-layouts" AND CMAKE_SYSTEM_PROCESSOR MATCHES "^(arm64|aarch64|ARM64)$"))
      list(APPEND exception_objects "${stage}/ghc/${exception_module}.o")
    endif()
  endforeach()
  foreach(label IN LISTS exception_labels)
    foreach(suffix stdout stderr command.json)
      list(APPEND exception_outputs "logs/${label}.${suffix}")
    endforeach()
  endforeach()
  audited_fixture("${group}" ""
    SOURCES "t/fixtures/compiler/${exception_module}.hs" "t/fixtures/compiler/${exception_driver}.hs"
    OUTPUTS ${exception_outputs} BYPRODUCTS ${exception_objects})
endforeach()

# 107: masking state, laziness and exception delivery are observable contracts.
set(mask_reports)
set(mask_labels ghc-version native-compile native-oracle)
foreach(stage pre post)
  list(APPEND mask_labels "${stage}-export")
  foreach(entry maskedFunction unmaskedFunction uninterruptibleFunction lazyFunctions bareMasks)
    list(APPEND mask_reports "${stage}/${entry}-audit.json")
    list(APPEND mask_labels "${stage}-audit-${entry}")
  endforeach()
endforeach()
set(mask_logs)
foreach(label IN LISTS mask_labels)
  foreach(suffix stdout stderr command.json)
    list(APPEND mask_logs "logs/${label}.${suffix}")
  endforeach()
endforeach()
audited_fixture(mask-functions MaskFunctionAudit
  SOURCES t/fixtures/compiler/MaskFunctionAudit.hs t/fixtures/compiler/MaskFunctionNative.hs
  OBJECT_DIRS native pre/ghc post/ghc
  OUTPUTS manifest.json native/oracle pre/core/MaskFunctionAudit.cbd
    post/core/MaskFunctionAudit.cbd ${mask_reports} ${mask_logs})

# 109: report live, blocked and completed thread states using synchronized cases.
set(status_reports)
foreach(stage pre post)
  foreach(entry selfStatus maskedStatus finishedStatus diedStatus blockedStatus)
    list(APPEND status_reports "${stage}/${entry}-audit.json")
  endforeach()
endforeach()
audited_fixture(thread-status ThreadStatusAudit
  SOURCES t/fixtures/compiler/ThreadStatusAudit.hs t/fixtures/compiler/ThreadStatusNative.hs
  OBJECT_DIRS native pre/ghc post/ghc
  OUTPUTS manifest.json native/oracle oracle.txt pre/core/ThreadStatusAudit.cbd
    post/core/ThreadStatusAudit.cbd ${status_reports})

# 111: the test executes this child to observe uncaught self-exception termination.
audited_fixture(uncaught-self UncaughtSelfAudit
  SOURCES t/fixtures/compiler/UncaughtSelfAudit.hs t/fixtures/compiler/UncaughtSelfNative.hs
  OBJECT_DIRS pre/ghc post/ghc
  OUTPUTS manifest.json native/oracle pre/core/UncaughtSelfAudit.cbd post/core/UncaughtSelfAudit.cbd
    pre/audit.json post/audit.json pre/io-audit.json post/io-audit.json)

# 115: synchronized live delivery and strict-entry paths retain continuation state.
set(live_reports)
foreach(stage pre post)
  foreach(entry forceShared strictWorker strictCall strictEntry takeReady takeRunning releaseGate prefixCount warmLoop asyncPayload)
    list(APPEND live_reports "${stage}/${entry}-audit.json")
  endforeach()
endforeach()
audited_fixture(live-async LiveAsyncAudit
  SOURCES t/fixtures/compiler/LiveAsyncAudit.hs t/fixtures/compiler/LiveAsyncNative.hs
  OBJECT_DIRS native pre/ghc post/ghc
  OUTPUTS manifest.json native/oracle oracle.txt strict-oracle.txt shutdown-oracle.txt pre/core/LiveAsyncAudit.cbd
    post/core/LiveAsyncAudit.cbd ${live_reports})

# 110: async delivery through suspended/masked continuations. The scheduling
# inputs are generated by their existing owner, including direct Ninja calls.
set(async_outputs manifest.json native/oracle native/lazy-oracle
  oracle.txt extra-oracle.txt saved-oracle.txt external-saved-oracle.txt
  scheduled-saved-oracle.txt yield-oracle.txt lazy-oracle.txt)
set(async_objects native/ThreadAsyncAudit.hi native/ThreadAsyncAudit.o
  native/lazy/Main.hi native/lazy/Main.o native/lazy/LazyForkAudit.hi native/lazy/LazyForkAudit.o)
foreach(stage pre post)
  list(APPEND async_outputs "${stage}/core/ThreadAsyncAudit.cbd" "${stage}/core/LazyForkAudit.cbd")
  list(APPEND async_objects "${stage}/ghc/ThreadAsyncAudit.hi" "${stage}/ghc/ThreadAsyncAudit.o"
    "${stage}/lazy-ghc/LazyForkAudit.hi" "${stage}/lazy-ghc/LazyForkAudit.o")
  foreach(entry forkAndThrow killUncaught selfThrow maskedUnmaskSelf promptSelfThrow
      promptMaskedUnmaskSelf savedSelfThrow savedMaskedSelf savedSuffixSelf savedMaskCatchSelf
      externalSaved scheduledSaved yieldProbe yieldMasked lazyFork)
    list(APPEND async_outputs "${stage}/${entry}-audit.json")
  endforeach()
endforeach()
audited_fixture(thread-async ""
  SOURCES t/fixtures/compiler/ThreadAsyncAudit.hs t/fixtures/compiler/ThreadAsyncNative.hs
    t/fixtures/compiler/LazyForkAudit.hs t/fixtures/compiler/LazyForkNative.hs
  OUTPUTS ${async_outputs} BYPRODUCTS ${async_objects})

# 087: native-address pinning and keepAlive lifetime, plus malformed ABI controls.
set(pinned_outputs manifest.json requests.tsv expected.tsv oracle.tsv native/pinned-address-oracle)
set(pinned_labels native-build native-oracle)
set(pinned_negatives read-word-not-word8 write-word-not-word8 read-address-is-word
  read-state-is-int read-offset-is-word contents-lifted-array contents-result-is-word
  allocation-size-is-word allocation-state-is-int aligned-alignment-is-word
  keepalive-state-is-int keepalive-result-word-not-word8)
foreach(stage pre post)
  list(APPEND pinned_outputs "${stage}/core/PinnedAddressAudit.cbd"
    "${stage}/core/THC.InterfaceClosure.cbd" "${stage}/negative-proofs.json")
  list(APPEND pinned_labels "${stage}-export")
  foreach(entry pinnedBytes alignedBytes keepAliveWord8 keepAliveLazy fingerprintByte)
    list(APPEND pinned_outputs "${stage}/${entry}.audit.json")
    list(APPEND pinned_labels "${stage}-${entry}-audit")
  endforeach()
  foreach(control IN LISTS pinned_negatives)
    list(APPEND pinned_outputs "${stage}/negative/${control}-0.cbd"
      "${stage}/negative/${control}-1.cbd" "${stage}/negative-${control}.audit.json")
    list(APPEND pinned_labels "${stage}-negative-${control}-audit")
  endforeach()
endforeach()
foreach(label IN LISTS pinned_labels)
  foreach(suffix stdout stderr command.json)
    list(APPEND pinned_outputs "commands/${label}.${suffix}")
  endforeach()
endforeach()
audited_fixture(pinned-addresses PinnedAddressAudit
  SOURCES t/fixtures/compiler/PinnedAddressAudit.hs t/fixtures/compiler/PinnedAddressAuditNative.hs
    src/tools/primops/PrimopTools.hs
  OBJECT_DIRS native pre/ghc post/ghc OUTPUTS ${pinned_outputs})

# 065: errno belongs to the guest context/carrier and survives foreign calls.
# Compile a five-row native reference and the real installed reset/get wrappers.
set(errno_outputs manifest.json oracle.json native/oracle native/observations.txt)
set(errno_labels ghc-version ghc-info native-build native-run)
foreach(stage pre post)
  list(APPEND errno_outputs "${stage}/core/OriginalErrnoAudit.cbd"
    "${stage}/core/THC.InterfaceClosure.cbd" "${stage}/originalResetErrno.audit.json")
  list(APPEND errno_labels "${stage}-export" "${stage}-audit-originalResetErrno")
endforeach()
foreach(label IN LISTS errno_labels)
  foreach(suffix stdout stderr command.json)
    list(APPEND errno_outputs "logs/${label}.${suffix}")
  endforeach()
endforeach()
audited_fixture(original-errno OriginalErrnoAudit
  SOURCES t/fixtures/compiler/OriginalErrnoAudit.hs t/fixtures/compiler/OriginalErrnoNative.hs
  OBJECT_DIRS pre/ghc post/ghc
  OUTPUTS ${errno_outputs})

# 055: BCO semantics against native GHC; one multi-entry audit for each CBD.
set(bco_outputs manifest.json native/oracle pre/core/GhcBCO.cbd post/core/GhcBCO.cbd
  pre/audit.json post/audit.json)
foreach(label ghc-version native-build native-run pre-export pre-audit post-export post-audit)
  foreach(suffix stdout stderr command.json)
    list(APPEND bco_outputs "commands/${label}.${suffix}")
  endforeach()
endforeach()
audited_fixture(ghc-bco GhcBCO
  SOURCES t/fixtures/core/GhcBCO.hs t/fixtures/compiler/GhcBCONative.hs
  OBJECT_DIRS native pre/ghc post/ghc
  OUTPUTS ${bco_outputs})

# 059: inspection exposes payloads without entering thunks and preserves
# continuation/annotation behavior. One audit covers all exported observations.
audited_fixture(closure-inspection ClosureInspectionAudit
  SOURCES t/fixtures/compiler/ClosureInspectionAudit.hs t/fixtures/compiler/ClosureInspectionNative.hs
  OBJECT_DIRS native ghc
  OUTPUTS manifest.json core/ClosureInspectionAudit.cbd native/oracle oracle.tsv audit.json)

# 058: observable thread membership, callback identity/masks and cancellation.
# The independent callback executable gets its own Main.o/hi and FFI stub files.
audited_fixture(thread-inventory ThreadInventory
  SOURCES t/fixtures/core/ThreadInventory.hs t/fixtures/compiler/ThreadInventoryNative.hs
    t/fixtures/compiler/CallbackIdentityNative.hs t/fixtures/compiler/callback-identity.c
  OBJECT_DIRS native pre/ghc post/ghc
  BYPRODUCTS native/callback/Main.hi native/callback/Main.o native/callback/Main_stub.h
    native/callback/ThreadInventory.hi native/callback/ThreadInventory.o
  OUTPUTS manifest.json native/oracle native/callback-oracle oracle.txt callback-oracle.txt
    pre/core/ThreadInventory.cbd pre/audit.json post/core/ThreadInventory.cbd post/audit.json)

# 060: no-op prefetch and observable THC trace records. Native GHC supplies only
# the semantic oracle; its nondeterministic eventlog format is not our fixture.
audited_fixture(hint-trace HintTraceAudit
  SOURCES t/fixtures/compiler/HintTraceAudit.hs t/fixtures/compiler/HintTraceNative.hs
  OBJECT_DIRS native pre/ghc post/ghc
  OUTPUTS manifest.json oracle.tsv native/oracle
    pre/core/HintTraceAudit.cbd post/core/HintTraceAudit.cbd pre/audit.json post/audit.json)

# 108: saved calls and lazy catch/keepAlive callbacks need independent native
# Main objects. Literal-case consumers have their own target below.
audited_fixture(core-continuation ""
  SOURCES t/fixtures/compiler/CoreContinuationAudit.hs t/fixtures/compiler/CoreContinuationNative.hs
    t/fixtures/compiler/LazyIOCallbackAudit.hs t/fixtures/compiler/LazyIOCallbackNative.hs
  OUTPUTS core/CoreContinuationAudit.cbd core/LazyIOCallbackAudit.cbd audit.json lazy-audit.json
    native-oracle lazy-native-oracle native-output.txt lazy-native-output.txt
  BYPRODUCTS ghc/CoreContinuationAudit.hi ghc/CoreContinuationAudit.o
    ghc/LazyIOCallbackAudit.hi ghc/LazyIOCallbackAudit.o
    native/CoreContinuationAudit.hi native/CoreContinuationAudit.o
    native/lazy/Main.hi native/lazy/Main.o native/lazy/LazyIOCallbackAudit.hi native/lazy/LazyIOCallbackAudit.o)

set(literal_out "${PROJECT_SOURCE_DIR}/build/large-literal-cases")
set(literal_outputs)
foreach(output core/LargeLiteralCaseAudit.cbd audit.json literal-manifest.json literal-native-output.txt literal-native-oracle)
  list(APPEND literal_outputs "${literal_out}/${output}")
endforeach()
add_custom_command(OUTPUT ${literal_outputs}
  BYPRODUCTS "${literal_out}/ghc/LargeLiteralCaseAudit.hi" "${literal_out}/ghc/LargeLiteralCaseAudit.o"
    "${literal_out}/native/LargeLiteralCaseAudit.hi" "${literal_out}/native/LargeLiteralCaseAudit.o"
  COMMAND ${fixture_env} "${fixtures_exe}" large-literal-cases
  DEPENDS "${PROJECT_SOURCE_DIR}/t/fixtures/compiler/LargeLiteralCaseAudit.hs"
    ${tool_sources} ${cabal_inputs} ${audit_inputs} ${toolchain_inputs}
    "${fixtures_exe}" "${compact_exe}" ${plugin_outputs}
    "${PROJECT_SOURCE_DIR}/bin/export-core.sh" "${PROJECT_SOURCE_DIR}/bin/toolchain.sh" "${PROJECT_SOURCE_DIR}/bin/plugin.py"
  WORKING_DIRECTORY "${PROJECT_SOURCE_DIR}" VERBATIM
  COMMENT "Generate the independent large-literal Core and native oracle")
add_custom_target(fixture-large-literal-cases DEPENDS ${literal_outputs})

# 076: one native process observes original ftruncate/COff behavior on private
# files and a pipe. Every file read to assemble the oracle is a declared product;
# rerunning overwrites it directly and does not require an empty result directory.
set(truncate_outputs manifest.json oracle.json native/oracle)
foreach(index RANGE 0 13)
  list(APPEND truncate_outputs "results/${index}.txt")
  if(index LESS 5 OR (index GREATER 6 AND index LESS 12))
    list(APPEND truncate_outputs "results/${index}.private")
  endif()
endforeach()
set(truncate_labels ghc-version ghc-info native-build native-observations)
foreach(stage pre post)
  list(APPEND truncate_outputs "${stage}/core/OriginalStdioTruncateAudit.cbd"
    "${stage}/core/THC.InterfaceClosure.cbd" "${stage}/audit.json")
  list(APPEND truncate_labels "${stage}-export" "${stage}-audit")
endforeach()
foreach(label IN LISTS truncate_labels)
  foreach(suffix stdout stderr command.json)
    list(APPEND truncate_outputs "logs/${label}.${suffix}")
  endforeach()
endforeach()
audited_fixture(original-stdio-truncate OriginalStdioTruncateAudit
  SOURCES t/fixtures/compiler/OriginalStdioTruncateAudit.hs t/fixtures/compiler/OriginalStdioTruncateAuditNative.hs
  OBJECT_DIRS native pre/ghc post/ghc
  OUTPUTS ${truncate_outputs})

# 080: input.bin is both the child's explicit stdin and its per-case seekable
# source. The producer writes it before running the single native observation
# process; no earlier fixture or ambient fd0 supplies the read payload.
set(read_outputs manifest.json oracle.json input.bin native/original-stdio-read-oracle)
foreach(index RANGE 0 39)
  list(APPEND read_outputs "results/${index}.txt")
endforeach()
set(read_labels ghc-version native-build native-observations)
foreach(stage pre post)
  list(APPEND read_outputs "${stage}/core/OriginalStdioReadAudit.cbd"
    "${stage}/core/THC.InterfaceClosure.cbd" "${stage}/audit.json")
  list(APPEND read_labels "${stage}-export" "${stage}-audit")
endforeach()
foreach(label IN LISTS read_labels)
  foreach(suffix stdout stderr command.json)
    list(APPEND read_outputs "logs/${label}.${suffix}")
  endforeach()
endforeach()
audited_fixture(original-stdio-read OriginalStdioReadAudit
  SOURCES t/fixtures/compiler/OriginalStdioReadAudit.hs t/fixtures/compiler/OriginalStdioReadNative.hs
  OBJECT_DIRS native pre/ghc post/ghc
  OUTPUTS ${read_outputs})
