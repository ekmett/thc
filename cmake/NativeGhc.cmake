# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
# Direct native GHC links do not read Cabal program-default-options.
set(native_ghc "${GHC}")
set(native_ghc_inputs "${CMAKE_CURRENT_LIST_FILE}")
if(APPLE)
  foreach(tool clang ld)
    execute_process(COMMAND /usr/bin/xcrun --sdk macosx --find "${tool}"
      OUTPUT_VARIABLE apple_${tool} OUTPUT_STRIP_TRAILING_WHITESPACE
      COMMAND_ERROR_IS_FATAL ANY)
  endforeach()
  set(apple_sdk "$ENV{SDKROOT}")
  if(NOT apple_sdk)
    execute_process(COMMAND /usr/bin/xcrun --sdk macosx --show-sdk-path
      OUTPUT_VARIABLE apple_sdk OUTPUT_STRIP_TRAILING_WHITESPACE
      COMMAND_ERROR_IS_FATAL ANY)
  endif()
  foreach(path IN ITEMS "${apple_clang}" "${apple_ld}" "${apple_sdk}")
    if(NOT IS_ABSOLUTE "${path}" OR NOT EXISTS "${path}")
      message(FATAL_ERROR "Apple native GHC selection is not an existing absolute path: ${path}")
    endif()
  endforeach()
  # Boot package metadata can retain an absolute CLT SDK library directory.
  # Put the selected SDK first, as well as overriding GHC's C/link drivers.
  list(APPEND native_ghc "-L${apple_sdk}/usr/lib"
    "-pgmc${apple_clang}" "-pgml${apple_clang}"
    -optc-isysroot "-optc${apple_sdk}"
    -optl-isysroot "-optl${apple_sdk}" "-optl-fuse-ld=${apple_ld}")
  list(APPEND native_ghc_inputs "${apple_clang}" "${apple_ld}"
    "${apple_sdk}/SDKSettings.json" "${apple_sdk}/usr/lib/libSystem.tbd")
endif()
