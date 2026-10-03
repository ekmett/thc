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
