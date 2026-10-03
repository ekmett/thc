# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
# 068: independent observations for THC pointer aliases and explicit ownership.
# No Core export or package acquisition is involved. Both sets have one writer.
set(address_outputs)
set(address_objects)
foreach(family native-addresses native-malloc)
  set(out "${PROJECT_SOURCE_DIR}/build/${family}")
  list(APPEND address_outputs "${out}/manifest.json" "${out}/native/oracle")
  list(APPEND address_objects "${out}/native/Main.hi" "${out}/native/Main.o")
  foreach(label native-build native-oracle)
    foreach(suffix stdout stderr command.json)
      list(APPEND address_outputs "${out}/logs/${label}.${suffix}")
    endforeach()
  endforeach()
endforeach()
foreach(suffix stdout stderr command.json)
  list(APPEND address_outputs "${PROJECT_SOURCE_DIR}/build/native-addresses/logs/ghc-version.${suffix}")
endforeach()
list(APPEND address_outputs "${PROJECT_SOURCE_DIR}/build/native-addresses/oracle.json"
  "${PROJECT_SOURCE_DIR}/build/native-malloc/oracle.txt")
add_custom_command(OUTPUT ${address_outputs} BYPRODUCTS ${address_objects}
  COMMAND ${fixture_env} "${fixtures_exe}" native-addresses
  DEPENDS "${PROJECT_SOURCE_DIR}/t/fixtures/compiler/NativeAddressNative.hs"
    "${PROJECT_SOURCE_DIR}/t/fixtures/compiler/NativeMallocNative.hs"
    "${PROJECT_SOURCE_DIR}/src/test/resources/core/original-malloc-descriptors.json"
    ${tool_sources} ${cabal_inputs} "${fixtures_exe}" ${toolchain_inputs}
  WORKING_DIRECTORY "${PROJECT_SOURCE_DIR}" VERBATIM
  COMMENT "Generate native pointer alias and allocation oracles")
add_custom_target(fixture-native-addresses DEPENDS ${address_outputs})

# 070/071: THC owns diagnostic routing and guest-only shutdown. Native child
# processes supply the byte/exit reference without risking the host process.
foreach(family rts-diagnostics rts-shutdown)
  set(out "${PROJECT_SOURCE_DIR}/build/${family}")
  set(outputs "${out}/manifest.json" "${out}/oracle.json" "${out}/native/oracle")
  set(labels version native-build)
  if(family STREQUAL "rts-diagnostics")
    set(module RtsDiagnosticsNative)
    list(APPEND labels ascii empty bytes nul newline debug-ascii debug-empty
      debug-bytes debug-nul debug-newline trace-nul stack heap)
  else()
    set(module RtsShutdownNative)
    list(APPEND labels signals)
    foreach(case exit-0 exit-1 exit-2 exit-3 exit-4 term stop)
      foreach(fast 0 1)
        list(APPEND labels "${case}-${fast}")
      endforeach()
    endforeach()
  endif()
  foreach(label IN LISTS labels)
    foreach(suffix stdout stderr command.json)
      list(APPEND outputs "${out}/logs/${label}.${suffix}")
    endforeach()
  endforeach()
  add_custom_command(OUTPUT ${outputs}
    BYPRODUCTS "${out}/native/Main.hi" "${out}/native/Main.o"
    COMMAND ${fixture_env} "${fixtures_exe}" "${family}"
    DEPENDS "${PROJECT_SOURCE_DIR}/t/fixtures/compiler/${module}.hs"
      ${tool_sources} ${cabal_inputs} "${fixtures_exe}" ${toolchain_inputs}
    WORKING_DIRECTORY "${PROJECT_SOURCE_DIR}" VERBATIM
    COMMENT "Generate ${family} native process observations")
  add_custom_target(fixture-${family} DEPENDS ${outputs})
endforeach()

# 067: THC-owned saved termios pointers must retain aliases and obey context
# lifetime. This is distinct from libc termios structure/constant conformance.
set(termios_out "${PROJECT_SOURCE_DIR}/build/original-termios")
set(termios_outputs "${termios_out}/manifest.json")
set(termios_objects)
if(CMAKE_SYSTEM_NAME STREQUAL "Linux" AND CMAKE_SYSTEM_PROCESSOR MATCHES "^(x86_64|amd64|AMD64)$")
  list(APPEND termios_outputs "${termios_out}/saved/native/oracle" "${termios_out}/saved/oracle.json")
  list(APPEND termios_objects "${termios_out}/saved/native/Main.hi" "${termios_out}/saved/native/Main.o")
  set(termios_labels ghc-version ghc-info saved-native-build saved-native-run)
  foreach(stage pre post)
    list(APPEND termios_labels "saved-${stage}-export")
    list(APPEND termios_outputs "${termios_out}/saved/${stage}/core/OriginalSavedTermiosAudit.cbd"
      "${termios_out}/saved/${stage}/core/THC.InterfaceClosure.cbd")
    list(APPEND termios_objects "${termios_out}/saved/${stage}/ghc/OriginalSavedTermiosAudit.hi"
      "${termios_out}/saved/${stage}/ghc/OriginalSavedTermiosAudit.o")
    foreach(entry originalGetSavedTermios originalSetSavedTermios)
      list(APPEND termios_outputs "${termios_out}/saved/${stage}/${entry}.audit.json")
      list(APPEND termios_labels "saved-${stage}-audit-${entry}")
    endforeach()
  endforeach()
  foreach(label IN LISTS termios_labels)
    foreach(suffix stdout stderr command.json)
      list(APPEND termios_outputs "${termios_out}/logs/${label}.${suffix}")
    endforeach()
  endforeach()
endif()
# Other platforms produce only an explicit unsupported receipt; the JUnit class
# is enabled on Linux x86_64 only. No missing Linux products are called success.
add_custom_command(OUTPUT ${termios_outputs} BYPRODUCTS ${termios_objects}
  COMMAND ${fixture_env} "${fixtures_exe}" original-termios
  DEPENDS "${PROJECT_SOURCE_DIR}/t/fixtures/compiler/OriginalSavedTermiosAudit.hs"
    "${PROJECT_SOURCE_DIR}/t/fixtures/compiler/OriginalSavedTermiosNative.hs"
    ${tool_sources} ${cabal_inputs} ${audit_inputs} ${toolchain_inputs}
    "${fixtures_exe}" "${compact_exe}" ${plugin_outputs}
    "${PROJECT_SOURCE_DIR}/bin/export-core.sh" "${PROJECT_SOURCE_DIR}/bin/export-core.ps1"
    "${PROJECT_SOURCE_DIR}/bin/build-compiler.sh" "${PROJECT_SOURCE_DIR}/bin/toolchain.sh" "${PROJECT_SOURCE_DIR}/bin/plugin.py"
  WORKING_DIRECTORY "${PROJECT_SOURCE_DIR}" VERBATIM
  COMMENT "Generate the saved-termios fixture for its declared platform")
add_custom_target(fixture-original-termios DEPENDS ${termios_outputs})
