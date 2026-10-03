# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
# 121: actual CString library behavior. Only CString is exported, in a private
# directory; exception, list and bignum exports cannot write these files.
execute_process(COMMAND ${fixture_env} "${GHC_PKG}" field ghc-internal import-dirs --simple-output
  OUTPUT_VARIABLE ghc_internal_imports OUTPUT_STRIP_TRAILING_WHITESPACE
  COMMAND_ERROR_IS_FATAL ANY)
file(GLOB_RECURSE boot_interfaces CONFIGURE_DEPENDS "${ghc_internal_imports}/*.dyn_hi")
set(cstring_out "${PROJECT_SOURCE_DIR}/build/cstring")
set(cstring_outputs "${cstring_out}/boot-core/GHC.Internal.CString.cbd"
  "${cstring_out}/core/GHC.Internal.CString.cbd" "${cstring_out}/boot-provenance.json")
# The exporter maps installed dynamic interfaces to a private same-unit overlay.
# Enumerate the installed inputs at configuration time, never generated files.
set(cstring_objects "${cstring_out}/boot-ghc/GHC/Internal/CString.o")
foreach(interface IN LISTS boot_interfaces)
  file(RELATIVE_PATH relative "${ghc_internal_imports}" "${interface}")
  string(REGEX REPLACE "\\.dyn_hi$" ".hi" relative "${relative}")
  list(APPEND cstring_objects "${cstring_out}/boot-ghc/${relative}")
endforeach()
add_custom_command(OUTPUT ${cstring_outputs} BYPRODUCTS ${cstring_objects}
  COMMAND ${fixture_env} "${Python3_EXECUTABLE}" "${PROJECT_SOURCE_DIR}/bin/export-boot.py"
    --frontier cstring --build-dir "${cstring_out}"
  DEPENDS "${PROJECT_SOURCE_DIR}/bin/export-boot.py" "${PROJECT_SOURCE_DIR}/bin/plugin.py"
    "${PROJECT_SOURCE_DIR}/nih/pinned/ghc-9.14.1/libraries/ghc-internal/src/GHC/Internal/CString.hs"
    "${PROJECT_SOURCE_DIR}/nih/pinned/ghc-9.14.1/libraries/ghc-internal/LICENSE"
    ${boot_interfaces} ${plugin_outputs} ${toolchain_inputs}
  WORKING_DIRECTORY "${PROJECT_SOURCE_DIR}" VERBATIM
  COMMENT "Export only the pinned CString module")
add_custom_target(fixture-cstring DEPENDS ${cstring_outputs})

# 024: public exponent needs the original Integer worker. Give the bounded
# bignum frontier its own owner so native observations and audits cannot trigger
# a second acquisition or rely on a previous fixture run.
set(decode_out "${PROJECT_SOURCE_DIR}/build/float-decode")
set(bignum_out "${decode_out}/original")
set(bignum_outputs "${bignum_out}/boot-provenance.json")
set(bignum_sources "${PROJECT_SOURCE_DIR}/nih/pinned/ghc-9.14.1/libraries/ghc-internal/include/WordSize.h"
  "${PROJECT_SOURCE_DIR}/nih/pinned/ghc-9.14.1/libraries/ghc-internal/LICENSE")
set(bignum_objects)
foreach(module BigNat Natural Integer)
  foreach(directory boot-core core)
    list(APPEND bignum_outputs "${bignum_out}/${directory}/GHC.Internal.Bignum.${module}.cbd")
  endforeach()
  foreach(suffix hs hs-boot)
    list(APPEND bignum_sources "${PROJECT_SOURCE_DIR}/nih/pinned/ghc-9.14.1/libraries/ghc-internal/src/GHC/Internal/Bignum/${module}.${suffix}")
  endforeach()
  foreach(suffix hi o hi-boot o-boot)
    list(APPEND bignum_objects "${bignum_out}/boot-ghc/GHC/Internal/Bignum/${module}.${suffix}")
  endforeach()
endforeach()
foreach(interface IN LISTS boot_interfaces)
  file(RELATIVE_PATH relative "${ghc_internal_imports}" "${interface}")
  string(REGEX REPLACE "\\.dyn_hi$" ".hi" relative "${relative}")
  list(APPEND bignum_objects "${bignum_out}/boot-ghc/${relative}")
endforeach()
list(REMOVE_DUPLICATES bignum_objects)
add_custom_command(OUTPUT ${bignum_outputs} BYPRODUCTS ${bignum_objects}
  COMMAND ${fixture_env} "${Python3_EXECUTABLE}" "${PROJECT_SOURCE_DIR}/bin/export-boot.py"
    --frontier bignum --build-dir "${bignum_out}"
  DEPENDS "${PROJECT_SOURCE_DIR}/bin/export-boot.py" "${PROJECT_SOURCE_DIR}/bin/plugin.py"
    ${bignum_sources} ${boot_interfaces} ${plugin_outputs} ${toolchain_inputs}
  WORKING_DIRECTORY "${PROJECT_SOURCE_DIR}" VERBATIM
  COMMENT "Export the private bignum dependency for floating decomposition")

set(decode_outputs "${decode_out}/manifest.json" "${decode_out}/inputs.tsv"
  "${decode_out}/oracle.tsv" "${decode_out}/native/oracle")
set(decode_objects "${decode_out}/native/Main.hi" "${decode_out}/native/Main.o")
set(decode_labels native-build native-oracle)
foreach(module FloatDecodeAudit FloatDecode)
  list(APPEND decode_objects "${decode_out}/native/${module}.hi" "${decode_out}/native/${module}.o")
  foreach(stage pre post)
    list(APPEND decode_outputs "${decode_out}/${stage}-core/${module}.cbd")
    list(APPEND decode_objects "${decode_out}/${stage}-ghc/${module}.hi" "${decode_out}/${stage}-ghc/${module}.o")
  endforeach()
endforeach()
foreach(stage pre post)
  list(APPEND decode_outputs "${decode_out}/${stage}-audit.json")
  list(APPEND decode_labels "${stage}-export" "${stage}-audit")
endforeach()
foreach(label IN LISTS decode_labels)
  foreach(suffix stdout stderr command.json)
    list(APPEND decode_outputs "${decode_out}/commands/${label}.${suffix}")
  endforeach()
endforeach()
add_custom_command(OUTPUT ${decode_outputs} BYPRODUCTS ${decode_objects}
  COMMAND ${fixture_env} "${fixtures_exe}" float-decode
  DEPENDS "${PROJECT_SOURCE_DIR}/t/fixtures/compiler/FloatDecodeAudit.hs"
    "${PROJECT_SOURCE_DIR}/t/fixtures/compiler/FloatDecodeNative.hs"
    "${PROJECT_SOURCE_DIR}/t/fixtures/core/FloatDecode.hs"
    "${bignum_out}/core/GHC.Internal.Bignum.Integer.cbd" "${bignum_out}/boot-provenance.json"
    ${tool_sources} ${cabal_inputs} ${audit_inputs} ${toolchain_inputs}
    "${fixtures_exe}" "${compact_exe}" ${plugin_outputs}
    "${PROJECT_SOURCE_DIR}/bin/export-core.sh" "${PROJECT_SOURCE_DIR}/bin/export-core.ps1"
    "${PROJECT_SOURCE_DIR}/bin/build-compiler.sh" "${PROJECT_SOURCE_DIR}/bin/toolchain.sh" "${PROJECT_SOURCE_DIR}/bin/plugin.py"
  WORKING_DIRECTORY "${PROJECT_SOURCE_DIR}" VERBATIM
  COMMENT "Generate floating decomposition Core, native rows and two audits")
add_custom_target(fixture-float-decode DEPENDS ${decode_outputs})
