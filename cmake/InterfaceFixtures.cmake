# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
# 132: record identity across direct Core and hydration from .hi files. The
# fixture compiles its own tiny library; it does not acquire installed packages.
set(record_out "${PROJECT_SOURCE_DIR}/build/record-fields")
set(record_outputs "${record_out}/manifest.json")
set(record_objects)
set(record_labels libdir installed-RecordFieldLibrary installed-RecordFieldClient)
foreach(stage pre post installed)
  foreach(module RecordFieldLibrary RecordFieldClient)
    list(APPEND record_outputs "${record_out}/${stage}/${module}.cbd")
  endforeach()
  foreach(entry fieldAlias duplicateFields)
    list(APPEND record_outputs "${record_out}/${stage}/${entry}-audit.json")
    list(APPEND record_labels "${stage}-audit-${entry}")
  endforeach()
  if(NOT stage STREQUAL "installed")
    list(APPEND record_outputs "${record_out}/${stage}/oracle" "${record_out}/${stage}/Main.cbd")
    list(APPEND record_labels "${stage}-compile" "${stage}-native")
    foreach(module Main RecordFieldLibrary RecordFieldClient)
      foreach(suffix hi o dyn_hi dyn_o)
        list(APPEND record_objects "${record_out}/${stage}/ghc/${module}.${suffix}")
      endforeach()
    endforeach()
  endif()
endforeach()
foreach(label IN LISTS record_labels)
  foreach(suffix stdout stderr command.json)
    list(APPEND record_outputs "${record_out}/logs/${label}.${suffix}")
  endforeach()
endforeach()
add_custom_command(OUTPUT ${record_outputs} BYPRODUCTS ${record_objects}
  COMMAND ${fixture_env} "THC_INTERFACE=${interface_exe}" "${fixtures_exe}" record-fields
  DEPENDS "${PROJECT_SOURCE_DIR}/t/fixtures/compiler/RecordFieldLibrary.hs"
    "${PROJECT_SOURCE_DIR}/t/fixtures/compiler/RecordFieldClient.hs"
    "${PROJECT_SOURCE_DIR}/t/fixtures/compiler/RecordFieldNative.hs"
    ${tool_sources} ${cabal_inputs} ${audit_inputs} ${plugin_outputs} ${toolchain_inputs}
    "${fixtures_exe}" "${compact_exe}" "${interface_exe}"
  WORKING_DIRECTORY "${PROJECT_SOURCE_DIR}" VERBATIM
  COMMENT "Generate record-field Core, native results and hydrated interfaces")
add_custom_target(fixture-record-fields DEPENDS ${record_outputs})

# Retained-interface demand uses the same small record sources with the ordinary
# typed provenance annotations; the thin variant has no plugin.
# The test consumes the registered full/thin interfaces, production source
# manifest and native results. No installed library Core is acquired here.
set(demand_out "${PROJECT_SOURCE_DIR}/build/record-fields-demand")
set(demand_outputs "${demand_out}/manifest.json" "${demand_out}/packages.json"
  "${demand_out}/native.tsv" "${demand_out}/tools.json" "${demand_out}/full/oracle")
foreach(mode full thin)
  foreach(module RecordFieldLibrary RecordFieldClient RecordFieldCold Main)
    foreach(suffix hi o)
      list(APPEND demand_outputs "${demand_out}/${mode}/${module}.${suffix}")
    endforeach()
  endforeach()
  list(APPEND demand_outputs "${demand_out}/${mode}/thc-record-demand-0.1.conf"
    "${demand_out}/${mode}/package.conf.d/package.cache"
    "${demand_out}/${mode}/package.conf.d/thc-record-demand-0.1.conf")
endforeach()
# Database-init diagnostics exist only when creating the private database.
# They are not consumed and are not freshness outputs of this file rule.
foreach(label base-id full-compile full-register thin-compile thin-register native)
  foreach(suffix stdout stderr command.json)
    list(APPEND demand_outputs "${demand_out}/logs/${label}.${suffix}")
  endforeach()
endforeach()
set(demand_cbd)
foreach(module RecordFieldLibrary RecordFieldClient RecordFieldCold Main)
  list(APPEND demand_cbd "${demand_out}/full/${module}.cbd")
endforeach()
add_custom_command(OUTPUT ${demand_outputs} BYPRODUCTS ${demand_cbd}
  COMMAND ${fixture_env} "THC_INTERFACE=${interface_exe}" "${fixtures_exe}" record-fields-demand
  DEPENDS "${PROJECT_SOURCE_DIR}/t/fixtures/compiler/RecordFieldLibrary.hs"
    "${PROJECT_SOURCE_DIR}/t/fixtures/compiler/RecordFieldClient.hs"
    "${PROJECT_SOURCE_DIR}/t/fixtures/compiler/RecordFieldCold.hs"
    "${PROJECT_SOURCE_DIR}/t/fixtures/compiler/RecordFieldNative.hs"
    ${tool_sources} ${cabal_inputs} ${plugin_outputs} ${toolchain_inputs} "${fixtures_exe}" "${interface_exe}"
  WORKING_DIRECTORY "${PROJECT_SOURCE_DIR}" VERBATIM
  COMMENT "Generate registered retained/thin record interfaces and native results")
add_custom_target(fixture-record-fields-demand DEPENDS ${demand_outputs})

# 072: adapt the selected GHC's private lock declarations, then compare THC's
# context-owned table with real RTS calls compiled in the producer's GHC session.
# The installed interfaces/libraries are toolchain inputs; there is no package
# Core acquisition or guessed native executable/object output.
set(lock_out "${PROJECT_SOURCE_DIR}/build/original-rts-locks")
set(lock_outputs)
foreach(name manifest.json oracle.json declarations.json declarations.cbd pre.cbd post.cbd)
  list(APPEND lock_outputs "${lock_out}/${name}")
endforeach()
set(lock_labels version info libdir imports)
foreach(stage pre post)
  foreach(entry originalLock originalUnlock)
    list(APPEND lock_outputs "${lock_out}/${stage}-${entry}.audit.json")
    list(APPEND lock_labels "${stage}-${entry}")
  endforeach()
endforeach()
foreach(label IN LISTS lock_labels)
  foreach(suffix stdout stderr command.json)
    list(APPEND lock_outputs "${lock_out}/logs/${label}.${suffix}")
  endforeach()
endforeach()
add_custom_command(OUTPUT ${lock_outputs}
  COMMAND ${fixture_env} "${fixtures_exe}" original-rts-locks --require-supported
  DEPENDS "${PROJECT_SOURCE_DIR}/t/fixtures/compiler/OriginalRtsLocksAudit.hs"
    ${tool_sources} ${cabal_inputs} ${audit_inputs} ${toolchain_inputs}
    "${fixtures_exe}" "${compact_exe}"
  WORKING_DIRECTORY "${PROJECT_SOURCE_DIR}" VERBATIM
  COMMENT "Generate original RTS lock Core and native state observations")
add_custom_target(fixture-original-rts-locks DEPENDS ${lock_outputs})

# 043: GHC constructs typed rubbish for scalar, aggregate and vector carriers.
# Native code observes the continuation, never unspecified filler bits. AArch64
# uses GHC's LLVM pipeline because its NCG cannot materialize these vectors.
set(rubbish_out "${PROJECT_SOURCE_DIR}/build/rubbish-literals")
set(rubbish_outputs)
foreach(name manifest.json pre.cbd post.cbd oracle.json pre.audit.json post.audit.json
    native.s native.o native-codegen.json)
  list(APPEND rubbish_outputs "${rubbish_out}/${name}")
endforeach()
set(rubbish_labels version info libdir native-assemble pre-audit post-audit)
if(CMAKE_SYSTEM_PROCESSOR MATCHES "^(arm64|aarch64|ARM64)$")
  list(APPEND rubbish_outputs "${rubbish_out}/native.ll")
  list(APPEND rubbish_labels native-llvm)
endif()
foreach(label IN LISTS rubbish_labels)
  foreach(suffix stdout stderr command.json)
    list(APPEND rubbish_outputs "${rubbish_out}/logs/${label}.${suffix}")
  endforeach()
endforeach()
add_custom_command(OUTPUT ${rubbish_outputs}
  COMMAND ${fixture_env} "${fixtures_exe}" rubbish-literals
  DEPENDS "${PROJECT_SOURCE_DIR}/t/fixtures/compiler/RubbishLiteralAudit.hs"
    ${tool_sources} ${cabal_inputs} ${audit_inputs} ${toolchain_inputs}
    "${fixtures_exe}" "${compact_exe}"
  WORKING_DIRECTORY "${PROJECT_SOURCE_DIR}" VERBATIM
  COMMENT "Generate typed rubbish Core and native continuation observations")
add_custom_target(fixture-rubbish-literals DEPENDS ${rubbish_outputs})

# 117: genuine installed unix declarations cross THC's context-owned descriptor
# and environment boundary. GHC's interpreter supplies native reference values;
# it leaves no executable, object or interface products in the scratch directory.
set(unix_out "${PROJECT_SOURCE_DIR}/build/unix-libc")
set(unix_outputs)
foreach(name manifest.json pre.cbd post.cbd oracle.json pre.audit.json post.audit.json)
  list(APPEND unix_outputs "${unix_out}/${name}")
endforeach()
foreach(label version libdir imports unit pre-audit post-audit)
  foreach(suffix stdout stderr command.json)
    list(APPEND unix_outputs "${unix_out}/logs/${label}.${suffix}")
  endforeach()
endforeach()
add_custom_command(OUTPUT ${unix_outputs}
  COMMAND ${fixture_env} "${fixtures_exe}" unix-libc
  DEPENDS "${PROJECT_SOURCE_DIR}/t/fixtures/compiler/UnixLibcAudit.hs"
    ${tool_sources} ${cabal_inputs} ${audit_inputs} ${toolchain_inputs}
    "${fixtures_exe}" "${compact_exe}"
  WORKING_DIRECTORY "${PROJECT_SOURCE_DIR}" VERBATIM
  COMMENT "Generate original unix Core and native descriptor/environment observations")
add_custom_target(fixture-unix-libc DEPENDS ${unix_outputs})

# 074: actual FD.hi declarations and a private native template, not whole-package
# Core acquisition. Each malformed CBD gets one audit covering both safety modes.
set(ready_out "${PROJECT_SOURCE_DIR}/build/original-fd-ready")
set(ready_outputs)
foreach(name manifest.json oracle.json OriginalFDDeclarations.cbd OriginalFdReadyAudit.cbd native/oracle audit.json)
  list(APPEND ready_outputs "${ready_out}/${name}")
endforeach()
set(ready_labels ghc-version ghc-info ghc-libdir ghc-internal-imports native-build native-observations audit)
foreach(control wrong-unit non-function wrong-convention interruptible wrong-arity
    wrong-supplied-arity signed-cbool machine-timeout scalar-state machine-result)
  list(APPEND ready_outputs "${ready_out}/negative/${control}.cbd" "${ready_out}/negative/${control}.audit.json")
  list(APPEND ready_labels "negative-${control}")
endforeach()
foreach(label IN LISTS ready_labels)
  foreach(suffix stdout stderr command.json)
    list(APPEND ready_outputs "${ready_out}/logs/${label}.${suffix}")
  endforeach()
endforeach()
add_custom_command(OUTPUT ${ready_outputs}
  BYPRODUCTS "${ready_out}/native/Main.hi" "${ready_out}/native/Main.o"
    "${ready_out}/native/OriginalFdReadyAudit.hi" "${ready_out}/native/OriginalFdReadyAudit.o"
  COMMAND ${fixture_env} "${fixtures_exe}" original-fd-ready
  DEPENDS "${PROJECT_SOURCE_DIR}/t/fixtures/compiler/OriginalFdReadyAudit.hs"
    "${PROJECT_SOURCE_DIR}/t/fixtures/compiler/OriginalFdReadyAuditNative.hs"
    "${PROJECT_SOURCE_DIR}/src/core-symbols/THC/CoreSymbols.hs"
    ${tool_sources} ${cabal_inputs} ${audit_inputs} ${toolchain_inputs}
    "${fixtures_exe}" "${compact_exe}"
  WORKING_DIRECTORY "${PROJECT_SOURCE_DIR}" VERBATIM
  COMMENT "Generate original readiness Core, native observations and ABI controls")
add_custom_target(fixture-original-fd-ready DEPENDS ${ready_outputs})

# Native JVM .hi envelope checks: stock GHC only, no plugin, CBD or acquisition.
set(hi_out "${PROJECT_SOURCE_DIR}/build/native-hi-reader")
set(hi_outputs)
foreach(mode normal safe max thin)
  if(mode STREQUAL "normal")
    set(compression 1)
  elseif(mode STREQUAL "max")
    set(compression 3)
  else()
    set(compression 2)
  endif()
  if(mode STREQUAL "thin")
    set(retain -fno-write-if-simplified-core)
  else()
    set(retain -fwrite-if-simplified-core)
  endif()
  set(hi_file "${hi_out}/${mode}/NativeHiFixture.hi")
  list(APPEND hi_outputs "${hi_file}")
  add_custom_command(OUTPUT "${hi_file}"
    BYPRODUCTS "${hi_out}/${mode}/NativeHiFixture.o"
    COMMAND "${CMAKE_COMMAND}" -E make_directory "${hi_out}/${mode}"
    COMMAND ${fixture_env} "${GHC}" -c -O1 -fforce-recomp -hide-all-packages -package base
      -this-unit-id thc-native-hi-fixture ${retain} "-fwrite-if-compression=${compression}"
      -hidir "${hi_out}/${mode}" -odir "${hi_out}/${mode}"
      "${PROJECT_SOURCE_DIR}/t/fixtures/compiler/NativeHiFixture.hs"
    DEPENDS "${PROJECT_SOURCE_DIR}/t/fixtures/compiler/NativeHiFixture.hs" ${toolchain_inputs}
    WORKING_DIRECTORY "${PROJECT_SOURCE_DIR}" VERBATIM
    COMMENT "Compile ${mode} GHC interface for native envelope checks")
endforeach()
add_custom_target(fixture-native-hi-reader DEPENDS ${hi_outputs})

# Native .hi execution: one ordinary GHC build owns the retained modules,
# its oracle executable and observed results. No plugin, helper or CBD producer.
set(hi_run_out "${PROJECT_SOURCE_DIR}/build/native-hi-execution")
# IO's declaration comes from the selected compiler installation. It need not
# retain executable Core; no fixture recreates this library-owned newtype.
execute_process(COMMAND ${fixture_env} "${GHC_PKG}" field ghc-internal import-dirs --simple-output
  OUTPUT_VARIABLE hi_internal_imports OUTPUT_STRIP_TRAILING_WHITESPACE
  COMMAND_ERROR_IS_FATAL ANY)
set(hi_types_source "${hi_internal_imports}/GHC/Internal/Types.hi")
add_custom_command(OUTPUT "${hi_run_out}/GHC.Internal.Types.hi"
  COMMAND "${CMAKE_COMMAND}" -E make_directory "${hi_run_out}"
  COMMAND "${CMAKE_COMMAND}" -E copy_if_different "${hi_types_source}" "${hi_run_out}/GHC.Internal.Types.hi"
  DEPENDS "${hi_types_source}" ${toolchain_inputs}
  VERBATIM COMMENT "Provide installed IO declarations to the native interface loader")
add_custom_command(OUTPUT "${hi_run_out}/NativeHiScalar.hi" "${hi_run_out}/NativeHiDependency.hi"
    "${hi_run_out}/NativeHiBox.hi" "${hi_run_out}/NativeHiBoxType.hi" "${hi_run_out}/native.tsv" "${hi_run_out}/io.tsv"
  BYPRODUCTS "${hi_run_out}/oracle${CMAKE_EXECUTABLE_SUFFIX}" "${hi_run_out}/Main.hi"
    "${hi_run_out}/Main.o" "${hi_run_out}/NativeHiScalar.o" "${hi_run_out}/NativeHiDependency.o" "${hi_run_out}/NativeHiBox.o" "${hi_run_out}/NativeHiBoxType.o"
  COMMAND "${CMAKE_COMMAND}" -E make_directory "${hi_run_out}"
  COMMAND ${fixture_env} "${GHC}" --make -O1 -fforce-recomp -hide-all-packages -package base
    -this-unit-id thc-native-hi-scalar -fwrite-if-simplified-core
    "-i${PROJECT_SOURCE_DIR}/t/fixtures/compiler" -hidir "${hi_run_out}" -odir "${hi_run_out}"
    "${PROJECT_SOURCE_DIR}/t/fixtures/compiler/NativeHiOracle.hs" -o "${hi_run_out}/oracle${CMAKE_EXECUTABLE_SUFFIX}"
  COMMAND "${hi_run_out}/oracle${CMAKE_EXECUTABLE_SUFFIX}" "${hi_run_out}/native.tsv" "${hi_run_out}/io.tsv"
  DEPENDS "${PROJECT_SOURCE_DIR}/t/fixtures/compiler/NativeHiScalar.hs"
    "${PROJECT_SOURCE_DIR}/t/fixtures/compiler/NativeHiDependency.hs"
    "${PROJECT_SOURCE_DIR}/t/fixtures/compiler/NativeHiBox.hs"
    "${PROJECT_SOURCE_DIR}/t/fixtures/compiler/NativeHiBoxType.hs"
    "${PROJECT_SOURCE_DIR}/t/fixtures/compiler/NativeHiOracle.hs" ${toolchain_inputs}
  WORKING_DIRECTORY "${PROJECT_SOURCE_DIR}" VERBATIM
  COMMENT "Compile retained interfaces and record native arithmetic and IO results")
add_custom_target(fixture-native-hi-execution DEPENDS "${hi_run_out}/GHC.Internal.Types.hi" "${hi_run_out}/NativeHiScalar.hi"
  "${hi_run_out}/NativeHiDependency.hi" "${hi_run_out}/NativeHiBox.hi" "${hi_run_out}/NativeHiBoxType.hi" "${hi_run_out}/native.tsv" "${hi_run_out}/io.tsv")
