# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
# Native controls for THC process launch/wait/descriptor/signal ownership.
# These need the installed process package, not a rebuilt retained-Core package.
# The consuming Java classes explicitly support Linux x86_64 only.
if(CMAKE_SYSTEM_NAME STREQUAL "Linux" AND CMAKE_SYSTEM_PROCESSOR MATCHES "^(x86_64|amd64|AMD64)$")
  if(NOT CMAKE_C_COMPILER)
    if(DEFINED ENV{THC_CLANG} AND NOT "$ENV{THC_CLANG}" STREQUAL "")
      set(CMAKE_C_COMPILER "$ENV{THC_CLANG}" CACHE FILEPATH "Native fixture C compiler")
    else()
      find_program(CMAKE_C_COMPILER NAMES clang REQUIRED)
    endif()
  endif()
  enable_language(C)
  set(process_native "${PROJECT_SOURCE_DIR}/build/process-lifecycle/native")
  add_custom_command(OUTPUT "${process_native}/process-oracle"
    BYPRODUCTS "${process_native}/Main.hi" "${process_native}/Main.o"
    COMMAND "${CMAKE_COMMAND}" -E make_directory "${process_native}"
    COMMAND ${fixture_env} "${GHC}" --make -O1 -threaded -fforce-recomp
      -package process -package unix -outputdir "${process_native}"
      "${PROJECT_SOURCE_DIR}/t/fixtures/process-lifecycle/Main.hs" -o "${process_native}/process-oracle"
    DEPENDS "${PROJECT_SOURCE_DIR}/t/fixtures/process-lifecycle/Main.hs" ${toolchain_inputs}
    WORKING_DIRECTORY "${PROJECT_SOURCE_DIR}" VERBATIM
    COMMENT "Build the native process lifecycle reference")
  # Ordinary CMake C compilation owns object files and compiler-discovered header
  # dependencies. The standalone child owns SIGCHLD, never the test JVM.
  add_executable(fixture-process-policy EXCLUDE_FROM_ALL
    "${PROJECT_SOURCE_DIR}/t/fixtures/process-lifecycle/sigchld-policy.c"
    "${PROJECT_SOURCE_DIR}/src/main/c/native-process-api.c")
  set_target_properties(fixture-process-policy PROPERTIES
    C_STANDARD 11 C_STANDARD_REQUIRED YES C_EXTENSIONS NO
    OUTPUT_NAME sigchld-policy RUNTIME_OUTPUT_DIRECTORY "${process_native}")
  target_compile_options(fixture-process-policy PRIVATE -O2 -Wall -Wextra -Werror)
  target_link_options(fixture-process-policy PRIVATE -Wl,--wrap=posix_spawn)
  add_custom_target(fixture-process-lifecycle-native DEPENDS "${process_native}/process-oracle")
  add_dependencies(fixture-process-lifecycle-native fixture-process-policy)
else()
  add_custom_target(fixture-process-lifecycle-native)
endif()
