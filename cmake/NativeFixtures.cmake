# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
# 100: a live PTY checks THC descriptor alias ownership. The native helper is a
# test input; it produces no persistent oracle data. Other platforms skip it.
if(CMAKE_SYSTEM_NAME STREQUAL "Linux" AND CMAKE_SYSTEM_PROCESSOR MATCHES "^(x86_64|amd64|AMD64)$")
  set(pty_outputs manifest.json native/oracle)
  set(pty_labels ghc-version ghc-info native-build)
  foreach(stage pre post)
    list(APPEND pty_outputs "${stage}/core/OriginalHandleReadinessAudit.cbd"
      "${stage}/core/THC.InterfaceClosure.cbd" "${stage}/originalIsTerminal.audit.json")
    list(APPEND pty_labels "${stage}-export" "${stage}-audit-originalIsTerminal")
  endforeach()
  foreach(label IN LISTS pty_labels)
    foreach(suffix stdout stderr command.json)
      list(APPEND pty_outputs "logs/${label}.${suffix}")
    endforeach()
  endforeach()
  audited_fixture(original-handle-readiness OriginalHandleReadinessAudit
    SOURCES t/fixtures/compiler/OriginalHandleReadinessAudit.hs t/fixtures/compiler/OriginalHandleReadinessNative.hs
    OBJECT_DIRS pre/ghc post/ghc OUTPUTS ${pty_outputs})
else()
  set(pty_manifest "${PROJECT_SOURCE_DIR}/build/original-handle-readiness/manifest.json")
  add_custom_command(OUTPUT "${pty_manifest}"
    COMMAND ${fixture_env} "${fixtures_exe}" original-handle-readiness
    DEPENDS ${tool_sources} ${cabal_inputs} "${fixtures_exe}"
    WORKING_DIRECTORY "${PROJECT_SOURCE_DIR}" VERBATIM
    COMMENT "Record that the Linux PTY fixture is unavailable on this platform")
  add_custom_target(fixture-original-handle-readiness DEPENDS "${pty_manifest}")
endif()

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
set(termios_plugin_inputs)
if(CMAKE_SYSTEM_NAME STREQUAL "Linux" AND CMAKE_SYSTEM_PROCESSOR MATCHES "^(x86_64|amd64|AMD64)$")
  set(termios_plugin_inputs ${plugin_outputs})
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
    "${fixtures_exe}" "${compact_exe}" ${termios_plugin_inputs}
    "${PROJECT_SOURCE_DIR}/bin/export-core.sh" "${PROJECT_SOURCE_DIR}/bin/export-core.ps1"
    "${PROJECT_SOURCE_DIR}/bin/build-compiler.sh" "${PROJECT_SOURCE_DIR}/bin/toolchain.sh" "${PROJECT_SOURCE_DIR}/bin/plugin.py"
  WORKING_DIRECTORY "${PROJECT_SOURCE_DIR}" VERBATIM
  COMMENT "Generate the saved-termios fixture for its declared platform")
add_custom_target(fixture-original-termios DEPENDS ${termios_outputs})

# 062/063: descriptor leases and termios image transport through THC. Each
# operation owns its private PTY server and exports; neither consumes the other's
# files. The server is also an explicit runtime input, not just a build oracle.
foreach(operation tcgetattr tcsetattr)
  if(operation STREQUAL "tcgetattr")
    set(module OriginalTcgetattr)
    set(entry originalTcgetattr)
  else()
    set(module OriginalTcsetattr)
    set(entry originalTcsetattr)
  endif()
  set(out "${PROJECT_SOURCE_DIR}/build/original-${operation}")
  set(outputs "${out}/manifest.json")
  set(objects)
  set(terminal_plugin_inputs)
  if(CMAKE_SYSTEM_NAME STREQUAL "Linux" AND CMAKE_SYSTEM_PROCESSOR MATCHES "^(x86_64|amd64|AMD64)$")
    set(terminal_plugin_inputs ${plugin_outputs})
    list(APPEND outputs "${out}/oracle.json" "${out}/native/oracle")
    list(APPEND objects "${out}/native/Main.hi" "${out}/native/Main.o")
    set(labels ghc-version ghc-info native-build native-run)
    foreach(stage pre post)
      list(APPEND outputs "${out}/${stage}/core/${module}Audit.cbd"
        "${out}/${stage}/core/THC.InterfaceClosure.cbd" "${out}/${stage}/${entry}.audit.json")
      list(APPEND objects "${out}/${stage}/ghc/${module}Audit.hi" "${out}/${stage}/ghc/${module}Audit.o")
      list(APPEND labels "${stage}-export" "${stage}-audit-${entry}")
    endforeach()
    foreach(label IN LISTS labels)
      foreach(suffix stdout stderr command.json)
        list(APPEND outputs "${out}/logs/${label}.${suffix}")
      endforeach()
    endforeach()
  endif()
  add_custom_command(OUTPUT ${outputs} BYPRODUCTS ${objects}
    COMMAND ${fixture_env} "${fixtures_exe}" "original-${operation}"
    DEPENDS "${PROJECT_SOURCE_DIR}/t/fixtures/compiler/${module}Audit.hs"
      "${PROJECT_SOURCE_DIR}/t/fixtures/compiler/${module}Native.hs"
      ${tool_sources} ${cabal_inputs} ${audit_inputs} ${toolchain_inputs}
      "${fixtures_exe}" "${compact_exe}" ${terminal_plugin_inputs}
      "${PROJECT_SOURCE_DIR}/bin/export-core.sh" "${PROJECT_SOURCE_DIR}/bin/export-core.ps1"
      "${PROJECT_SOURCE_DIR}/bin/build-compiler.sh" "${PROJECT_SOURCE_DIR}/bin/toolchain.sh" "${PROJECT_SOURCE_DIR}/bin/plugin.py"
    WORKING_DIRECTORY "${PROJECT_SOURCE_DIR}" VERBATIM
    COMMENT "Generate the ${operation} fixture for its declared platform")
  add_custom_target(fixture-original-${operation} DEPENDS ${outputs})
endforeach()
