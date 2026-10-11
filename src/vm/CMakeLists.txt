# SPDX-FileCopyrightText: 2026 Edward Kmett <ekmett@gmail.com>
# SPDX-License-Identifier: BSD-2-Clause OR Apache-2.0

cmake_minimum_required(VERSION 4.4)
project(THCVM VERSION 0.1.0 LANGUAGES CXX)
set(CMAKE_CXX_STANDARD 26)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
include(GNUInstallDirs)
include(CMakePackageConfigHelpers)
include(FetchContent)
option(THC_VM_BUILD_TESTS "Build VM adapter and tooling tests" ${PROJECT_IS_TOP_LEVEL})
if(THC_VM_BUILD_TESTS)
  enable_testing()
endif()
# HotSpot and SubstrateVM reserve x28. The collector must use its TLS context.
if(JAM_CONTEXT_X28)
  message(FATAL_ERROR "THC VM requires JAM_CONTEXT_X28=OFF")
endif()
set(JAM_CONTEXT_X28 OFF CACHE BOOL "Use the collector TLS context" FORCE)
set(JAM_BUILD_VM OFF CACHE BOOL "VM integration belongs to THC" FORCE)
set(JAM_BUILD_TESTS OFF CACHE BOOL "Build collector tests" FORCE)
set(JAM_BUILD_DOCS OFF CACHE BOOL "Build collector documentation" FORCE)
set(WORK_BUILD_TESTS OFF CACHE BOOL "Build work dependency tests" FORCE)
set(WORK_BUILD_DOCS OFF CACHE BOOL "Build work dependency documentation" FORCE)
set(HINT_BUILD_TESTS OFF CACHE BOOL "Build hint dependency tests" FORCE)
set(HINT_BUILD_DOCS OFF CACHE BOOL "Build hint dependency documentation" FORCE)
set(FETCHCONTENT_TRY_FIND_PACKAGE_MODE NEVER)
# First declarations win, including when the collector declares its dependencies.
# Exact source overrides support offline builds with the same pinned revisions.
FetchContent_Declare(jam_native GIT_REPOSITORY https://github.com/ekmett/native.git
  GIT_TAG caa4e64b277cfd9eac0c5f1021a4ca032ced0dd1)
FetchContent_Declare(work GIT_REPOSITORY https://github.com/ekmett/work.git
  GIT_TAG f948373e155fe3cf7e689f41039e44844fa5547a)
FetchContent_Declare(hint GIT_REPOSITORY https://github.com/ekmett/hint.git
  GIT_TAG b24b9da02325ea949db5b76f5a90302d9b1c5f83)
FetchContent_Declare(jam GIT_REPOSITORY https://github.com/ekmett/jam.git
  GIT_TAG 8774a75f83f2b8240e201d53b42017123d9ee762)
FetchContent_MakeAvailable(jam)

add_library(thc-vm SHARED)
add_library(thc-vm-static STATIC)
add_library(thc::vm ALIAS thc-vm)
add_library(thc::vm-static ALIAS thc-vm-static)
foreach(target thc-vm thc-vm-static)
  target_sources(${target} PRIVATE src/adapter/thc_vm.cc)
  target_sources(${target} PRIVATE FILE_SET CXX_MODULES FILES
    src/thc_jam.ccm src/thc/jam/heap.ccm src/thc/jam/weak.ccm src/thc/jam/candidate.ccm)
  target_include_directories(${target} PUBLIC
    "$<BUILD_INTERFACE:${CMAKE_CURRENT_SOURCE_DIR}/src/adapter>"
    "$<INSTALL_INTERFACE:${CMAKE_INSTALL_INCLUDEDIR}>")
  target_link_libraries(${target} PRIVATE jam::jam)
  set_target_properties(${target} PROPERTIES CXX_VISIBILITY_PRESET hidden
    VISIBILITY_INLINES_HIDDEN YES POSITION_INDEPENDENT_CODE ON)
endforeach()
target_compile_definitions(thc-vm PRIVATE THC_VM_BUILD)
target_compile_definitions(thc-vm-static PUBLIC THC_VM_STATIC)
if(APPLE)
  set(THC_VM_RUNTIME "${CMAKE_CURRENT_SOURCE_DIR}/.toolchains/runtime" CACHE PATH "Matching libc++ and working Darwin unwind runtime")
  foreach(target thc-vm thc-vm-static)
    target_link_directories(${target} PRIVATE "${THC_VM_RUNTIME}")
    target_link_libraries(${target} PRIVATE c++abi)
    set_target_properties(${target} PROPERTIES BUILD_RPATH "${THC_VM_RUNTIME}")
  endforeach()
endif()
# Static archives do not incorporate their transitive libraries.
set(thc_vm_archives "$<TARGET_FILE:thc-vm-static>\n$<TARGET_FILE:jam::jam>\n$<TARGET_FILE:work::work>\n$<TARGET_FILE:native::native>\n$<TARGET_FILE:native::minimal>\n")
if(WIN32)
  execute_process(COMMAND "${CMAKE_CXX_COMPILER}" --print-runtime-dir
    OUTPUT_VARIABLE thc_runtime OUTPUT_STRIP_TRAILING_WHITESPACE COMMAND_ERROR_IS_FATAL ANY)
  if(CMAKE_SYSTEM_PROCESSOR MATCHES "^(ARM64|arm64|aarch64)$")
    set(thc_builtins clang_rt.builtins-aarch64)
  else()
    set(thc_builtins clang_rt.builtins-x86_64)
  endif()
  if(NOT EXISTS "${thc_runtime}/${thc_builtins}.lib")
    message(FATAL_ERROR "THC VM requires ${thc_builtins}.lib from the Clang toolchain")
  endif()
  string(APPEND thc_vm_archives "${thc_runtime}/${thc_builtins}.lib\n")
endif()
file(GENERATE OUTPUT "${PROJECT_BINARY_DIR}/thc-vm-static-libraries-$<CONFIG>.txt"
  CONTENT "${thc_vm_archives}")
if(THC_VM_BUILD_TESTS)
  add_executable(thc-candidate-registry-test t/candidate-registry-test.cc)
  target_sources(thc-candidate-registry-test PRIVATE FILE_SET CXX_MODULES FILES
    t/candidate-registry.ccm src/thc/jam/candidate.ccm src/thc/jam/weak.ccm)
  add_test(NAME thc-candidate-registry-test COMMAND thc-candidate-registry-test)
  add_executable(thc-weak-registry-test t/weak-registry-test.cc)
  target_sources(thc-weak-registry-test PRIVATE FILE_SET CXX_MODULES FILES
    t/weak-registry.ccm src/thc/jam/weak.ccm)
  add_test(NAME thc-weak-registry-test COMMAND thc-weak-registry-test)
  add_executable(thc-vm-test t/thc_vm_test.cc)
  set_target_properties(thc-vm-test PROPERTIES CXX_STANDARD 14 CXX_SCAN_FOR_MODULES OFF)
  target_link_libraries(thc-vm-test PRIVATE thc-vm)
  add_test(NAME thc-vm-test COMMAND thc-vm-test)
  add_executable(thc-vm-static-test t/thc_vm_test.cc)
  set_target_properties(thc-vm-static-test PROPERTIES CXX_STANDARD 14 CXX_SCAN_FOR_MODULES OFF)
  target_link_libraries(thc-vm-static-test PRIVATE thc-vm-static)
  add_test(NAME thc-vm-static-test COMMAND thc-vm-static-test)
  add_executable(thc-generational-test t/generational_test.cc)
  set_target_properties(thc-generational-test PROPERTIES CXX_STANDARD 14 CXX_SCAN_FOR_MODULES OFF)
  target_link_libraries(thc-generational-test PRIVATE thc-vm)
  add_test(NAME thc-generational-test COMMAND thc-generational-test)
  if(WIN32)
    target_link_libraries(thc-vm-test PRIVATE onecore)
    target_link_libraries(thc-generational-test PRIVATE onecore)
  endif()

  set_tests_properties(thc-vm-test thc-vm-static-test thc-generational-test thc-weak-registry-test thc-candidate-registry-test PROPERTIES LABELS vm)
endif()

# Runtime tools receive this directory explicitly; standalone wrappers default
# to vm/build-jam. Keep DLLs next to the C ABI tests on Windows.
foreach(target thc-vm thc-vm-static thc-vm-test thc-vm-static-test thc-generational-test)
  if(TARGET ${target})
    set_target_properties(${target} PROPERTIES
      RUNTIME_OUTPUT_DIRECTORY "$<1:${PROJECT_BINARY_DIR}>"
      LIBRARY_OUTPUT_DIRECTORY "$<1:${PROJECT_BINARY_DIR}>"
      ARCHIVE_OUTPUT_DIRECTORY "$<1:${PROJECT_BINARY_DIR}>")
  endif()
endforeach()
# Package the exact dependency terms alongside the archive closure.
foreach(dependency jam jam_native work hint)
  FetchContent_GetProperties(${dependency} SOURCE_DIR dependency_source)
  if(dependency STREQUAL "jam_native")
    set(license_name native)
  else()
    set(license_name ${dependency})
  endif()
  configure_file("${dependency_source}/LICENSE.md"
    "${PROJECT_BINARY_DIR}/${license_name}-LICENSE.md" COPYONLY)
endforeach()

find_package(Python3 3.10 REQUIRED COMPONENTS Interpreter)
if(THC_VM_BUILD_TESTS)
  add_test(NAME jam-prepare-graal-test
    COMMAND "${Python3_EXECUTABLE}" "${CMAKE_CURRENT_SOURCE_DIR}/t/prepare-graal-test.py")
  set_tests_properties(jam-prepare-graal-test PROPERTIES LABELS vm)
  add_test(NAME jam-release-runtime-test
    COMMAND "${Python3_EXECUTABLE}" "${CMAKE_CURRENT_SOURCE_DIR}/t/release-runtime-test.py")
  set_tests_properties(jam-release-runtime-test PROPERTIES LABELS vm)
  add_test(NAME jam-macos-deployment-test
    COMMAND "${Python3_EXECUTABLE}" "${CMAKE_CURRENT_SOURCE_DIR}/t/macos-deployment-test.py")
  set_tests_properties(jam-macos-deployment-test PROPERTIES LABELS vm)
endif()
set(thc_vm_environment "THC_VM_NATIVE_BUILD=${PROJECT_BINARY_DIR}")
foreach(runtime graalvm)
  add_custom_target(jam-${runtime}
    COMMAND "${CMAKE_COMMAND}" -E env "${thc_vm_environment}" "THC_VM_NATIVE_CONFIG=$<CONFIG>"
      "${Python3_EXECUTABLE}" "${CMAKE_CURRENT_SOURCE_DIR}/tools/build_runtime.py" ${runtime}
    DEPENDS thc-vm thc-vm-static
    USES_TERMINAL VERBATIM)
endforeach()
add_custom_target(jam-substratevm DEPENDS jam-graalvm)

# Runtime checks are explicit: an ordinary ctest needs no downloaded JDK.
set(THC_VM_TEST_RUNTIMES "" CACHE STRING "Runtime checks to register: graalvm;substratevm")
if(THC_VM_BUILD_TESTS)
  foreach(runtime IN LISTS THC_VM_TEST_RUNTIMES)
    if(NOT runtime MATCHES "^(graalvm|substratevm)$")
      message(FATAL_ERROR "Unknown THC_VM_TEST_RUNTIMES entry: ${runtime}")
    endif()
    add_test(NAME jam-${runtime}
      COMMAND "${CMAKE_COMMAND}" -E env "${thc_vm_environment}" "THC_VM_NATIVE_CONFIG=$<CONFIG>"
        "${Python3_EXECUTABLE}" "${CMAKE_CURRENT_SOURCE_DIR}/tools/check_runtime.py" ${runtime})
    set_tests_properties(jam-${runtime} PROPERTIES LABELS "${runtime}" RUN_SERIAL TRUE)
  endforeach()
endif()

set_target_properties(thc-vm PROPERTIES EXPORT_NAME vm)
set_target_properties(thc-vm-static PROPERTIES EXPORT_NAME vm-static)
install(TARGETS thc-vm thc-vm-static EXPORT thcVMTargets
  RUNTIME DESTINATION "${CMAKE_INSTALL_BINDIR}"
  LIBRARY DESTINATION "${CMAKE_INSTALL_LIBDIR}"
  ARCHIVE DESTINATION "${CMAKE_INSTALL_LIBDIR}")
install(FILES src/adapter/thc_vm.h DESTINATION "${CMAKE_INSTALL_INCLUDEDIR}")

install(EXPORT thcVMTargets NAMESPACE thc:: DESTINATION "${CMAKE_INSTALL_LIBDIR}/cmake/thcVM")
configure_package_config_file(cmake/thcVMConfig.cmake.in
  "${PROJECT_BINARY_DIR}/thcVMConfig.cmake"
  INSTALL_DESTINATION "${CMAKE_INSTALL_LIBDIR}/cmake/thcVM")
write_basic_package_version_file("${PROJECT_BINARY_DIR}/thcVMConfigVersion.cmake"
  VERSION ${PROJECT_VERSION} COMPATIBILITY SameMajorVersion)
install(FILES "${PROJECT_BINARY_DIR}/thcVMConfig.cmake"
  "${PROJECT_BINARY_DIR}/thcVMConfigVersion.cmake"
  DESTINATION "${CMAKE_INSTALL_LIBDIR}/cmake/thcVM")
install(FILES LICENSE.md NOTICE.md DESTINATION "${CMAKE_INSTALL_DATADIR}/thc-vm")
