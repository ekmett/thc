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
