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
  target_compile_options(fixture-process-policy PRIVATE -O2 -Wall -Wextra -Werror -UNDEBUG)
  target_link_options(fixture-process-policy PRIVATE -Wl,--wrap=posix_spawn)
  add_custom_target(fixture-process-lifecycle-native DEPENDS "${process_native}/process-oracle")
  add_dependencies(fixture-process-lifecycle-native fixture-process-policy)
else()
  add_custom_target(fixture-process-lifecycle-native)
endif()

# The native request bridge owns worker/fd/signal state. Build its existing
# standalone observer directly: no retained Core, exporter or fixture-tool edge.
# NativeFileProviderTest executes it in a fresh child because dispositions are
# process-global (SIGRTMIN on Linux, SIGUSR1 on Darwin).
if((CMAKE_SYSTEM_NAME STREQUAL "Linux" AND CMAKE_SYSTEM_PROCESSOR MATCHES "^(x86_64|amd64|AMD64)$")
    OR (CMAKE_SYSTEM_NAME STREQUAL "Darwin" AND CMAKE_SYSTEM_PROCESSOR MATCHES "^(x86_64|amd64|AMD64|arm64|aarch64|ARM64)$"))
  string(REGEX MATCH [[\("Host platform","([^"]+)"\)]] open_host_info "${ghc_info}")
  set(open_host "${CMAKE_MATCH_1}")
  string(REGEX MATCH [[\("Target platform","([^"]+)"\)]] open_target_info "${ghc_info}")
  set(open_target "${CMAKE_MATCH_1}")
  if(NOT open_host OR NOT open_host STREQUAL open_target OR NOT ghc_info MATCHES [[\("target word size","8"\)]])
    message(FATAL_ERROR "Native open request controls require native 64-bit GHC")
  endif()
  set(open_sdk_flags)
  set(open_c_flags)
  set(open_sdk_inputs)
  if(CMAKE_SYSTEM_NAME STREQUAL "Darwin")
    set(open_sdk "$ENV{SDKROOT}")
    if(NOT open_sdk)
      execute_process(COMMAND /usr/bin/xcrun --show-sdk-path OUTPUT_VARIABLE open_sdk
        OUTPUT_STRIP_TRAILING_WHITESPACE COMMAND_ERROR_IS_FATAL ANY)
      list(APPEND open_sdk_inputs /usr/bin/xcrun)
    endif()
    if(NOT IS_ABSOLUTE "${open_sdk}" OR NOT IS_DIRECTORY "${open_sdk}")
      message(FATAL_ERROR "Native open request controls require an absolute SDKROOT")
    endif()
    # Match nativeCompilerFlags: the selected SDK applies to C and linkage.
    list(APPEND open_sdk_flags "-optl--sysroot=${open_sdk}")
    list(APPEND open_c_flags "--sysroot=${open_sdk}")
    foreach(settings SDKSettings.json SDKSettings.plist)
      if(EXISTS "${open_sdk}/${settings}")
        list(APPEND open_sdk_inputs "${open_sdk}/${settings}")
      endif()
    endforeach()
    set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS ${open_sdk_inputs})
  endif()
  # CMake owns the C object and its compiler-generated system-header dependencies.
  enable_language(C)
  add_library(fixture-native-open-request-c OBJECT EXCLUDE_FROM_ALL
    "${PROJECT_SOURCE_DIR}/src/main/c/native-open-request.c")
  set_target_properties(fixture-native-open-request-c PROPERTIES
    C_STANDARD 11 C_STANDARD_REQUIRED YES C_EXTENSIONS NO)
  target_compile_definitions(fixture-native-open-request-c PRIVATE THC_OPEN_REQUEST_TEST)
  target_compile_options(fixture-native-open-request-c PRIVATE -O2 -Wall -Wextra -Werror ${open_c_flags})
  set(open_native "${PROJECT_SOURCE_DIR}/build/native-open-request/native")
  add_custom_command(OUTPUT "${open_native}/oracle"
    BYPRODUCTS "${open_native}/OriginalOpenRequestNative.hi" "${open_native}/OriginalOpenRequestNative.o"
    COMMAND "${CMAKE_COMMAND}" -E make_directory "${open_native}"
    COMMAND ${fixture_env} "${GHC}" --make -main-is OriginalOpenRequestNative.main
      -O2 -threaded -fforce-recomp -package unix -package ghc-internal
      ${open_sdk_flags} -optl-pthread -outputdir "${open_native}"
      "${PROJECT_SOURCE_DIR}/t/fixtures/compiler/OriginalOpenRequestNative.hs"
      $<TARGET_OBJECTS:fixture-native-open-request-c> -o "${open_native}/oracle"
    DEPENDS "${PROJECT_SOURCE_DIR}/t/fixtures/compiler/OriginalOpenRequestNative.hs"
      fixture-native-open-request-c $<TARGET_OBJECTS:fixture-native-open-request-c>
      "${PROJECT_SOURCE_DIR}/cmake/ProcessFixtures.cmake" ${toolchain_inputs} ${open_sdk_inputs}
    WORKING_DIRECTORY "${PROJECT_SOURCE_DIR}" VERBATIM
    COMMENT "Build the isolated native open request controls")
  add_custom_target(fixture-native-open-request DEPENDS "${open_native}/oracle")
else()
  add_custom_target(fixture-native-open-request)
endif()

# 131: process signal capture must be tested in isolated children. CMake tracks
# the C program's included implementation and platform headers; assertions stay
# enabled even with CMAKE_BUILD_TYPE=Release. Only ProcessSignalsTest reads the
# native observations. Fork/Loom failure controls have no generated fixture input.
set(signal_out "${PROJECT_SOURCE_DIR}/build/process-signals")
set(signal_outputs "${signal_out}/manifest.json")
set(signal_objects)
set(signal_control)
if(CMAKE_SYSTEM_NAME STREQUAL "Linux" AND CMAKE_SYSTEM_PROCESSOR MATCHES "^(x86_64|amd64|AMD64)$")
  add_executable(fixture-signal-control EXCLUDE_FROM_ALL
    "${PROJECT_SOURCE_DIR}/src/test/c/native-process-signals-test.c")
  set_target_properties(fixture-signal-control PROPERTIES
    C_STANDARD 11 C_STANDARD_REQUIRED YES C_EXTENSIONS NO
    OUTPUT_NAME capture-test RUNTIME_OUTPUT_DIRECTORY "${signal_out}/native")
  target_compile_options(fixture-signal-control PRIVATE -O2 -Wall -Wextra -Werror -UNDEBUG)
  target_link_libraries(fixture-signal-control PRIVATE ${CMAKE_DL_LIBS})
  set(signal_control fixture-signal-control)
  list(APPEND signal_outputs "${signal_out}/native/oracle" "${signal_out}/oracle.txt" "${signal_out}/native-controls.txt")
  list(APPEND signal_objects "${signal_out}/native/Main.hi" "${signal_out}/native/Main.o")
  foreach(label ghc-version native-build native-oracle capture-child-controls)
    foreach(suffix stdout stderr command.json)
      list(APPEND signal_outputs "${signal_out}/logs/${label}.${suffix}")
    endforeach()
  endforeach()
endif()
add_custom_command(OUTPUT ${signal_outputs} BYPRODUCTS ${signal_objects}
  COMMAND ${fixture_env} "${fixtures_exe}" process-signals
  DEPENDS ${signal_control} ${tool_sources} ${cabal_inputs} ${toolchain_inputs} "${fixtures_exe}"
    "${PROJECT_SOURCE_DIR}/t/fixtures/compiler/ProcessSignalsNative.hs"
    "${PROJECT_SOURCE_DIR}/src/main/c/native-process-signal-api.c"
    "${PROJECT_SOURCE_DIR}/src/test/c/native-process-signals-test.c"
    "${PROJECT_SOURCE_DIR}/src/test/resources/core/original-signal-install-descriptor.json"
    "${PROJECT_SOURCE_DIR}/src/test/resources/core/original-unix-signal-install-descriptor.json"
  WORKING_DIRECTORY "${PROJECT_SOURCE_DIR}" VERBATIM
  COMMENT "Generate process signal observations for their declared platform")
add_custom_target(fixture-process-signals DEPENDS ${signal_outputs})
