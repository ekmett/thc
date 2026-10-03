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
