# SPDX-FileCopyrightText: 2026 Edward Kmett <ekmett@gmail.com>
# SPDX-License-Identifier: BSD-2-Clause OR Apache-2.0

add_library(jam-vm SHARED)
add_library(jam-vm-static STATIC)
add_library(jam::vm ALIAS jam-vm)
add_library(jam::vm-static ALIAS jam-vm-static)
foreach(target jam-vm jam-vm-static)
  target_sources(${target} PRIVATE src/adapter/jam_vm.cc)
  target_sources(${target} PRIVATE FILE_SET CXX_MODULES FILES
    src/thc_jam.ccm src/thc/jam/heap.ccm src/thc/jam/weak.ccm src/thc/jam/candidate.ccm)
  target_include_directories(${target} PUBLIC
    "$<BUILD_INTERFACE:${CMAKE_CURRENT_SOURCE_DIR}/src/adapter>"
    "$<INSTALL_INTERFACE:${CMAKE_INSTALL_INCLUDEDIR}>")
  target_link_libraries(${target} PRIVATE jam::jam)
  set_target_properties(${target} PROPERTIES CXX_VISIBILITY_PRESET hidden
    VISIBILITY_INLINES_HIDDEN YES POSITION_INDEPENDENT_CODE ON)
endforeach()
target_compile_definitions(jam-vm PRIVATE JAM_VM_BUILD)
target_compile_definitions(jam-vm-static PUBLIC JAM_VM_STATIC)
if(APPLE)
  set(JAM_VM_RUNTIME "${CMAKE_CURRENT_SOURCE_DIR}/.toolchains/runtime" CACHE PATH "Matching libc++ and working Darwin unwind runtime")
  foreach(target jam-vm jam-vm-static)
    target_link_directories(${target} PRIVATE "${JAM_VM_RUNTIME}")
    target_link_libraries(${target} PRIVATE c++abi)
    set_target_properties(${target} PROPERTIES BUILD_RPATH "${JAM_VM_RUNTIME}")
  endforeach()
endif()
# Static archives do not incorporate their transitive libraries.
set(jam_vm_archives "$<TARGET_FILE:jam-vm-static>\n$<TARGET_FILE:jam>\n$<TARGET_FILE:work::work>\n$<TARGET_FILE:native::native>\n$<TARGET_FILE:native::minimal>\n")
if(WIN32)
  string(APPEND jam_vm_archives "${jam_runtime}/${jam_builtins}.lib\n")
endif()
file(GENERATE OUTPUT "${PROJECT_BINARY_DIR}/jam-vm-static-libraries-$<CONFIG>.txt"
  CONTENT "${jam_vm_archives}")
if(JAM_BUILD_TESTS)
  add_executable(jam-candidate-registry-test t/candidate-registry-test.cc)
  target_sources(jam-candidate-registry-test PRIVATE FILE_SET CXX_MODULES FILES
    t/candidate-registry.ccm src/thc/jam/candidate.ccm src/thc/jam/weak.ccm)
  add_test(NAME jam-candidate-registry-test COMMAND jam-candidate-registry-test)
  add_executable(jam-weak-registry-test t/weak-registry-test.cc)
  target_sources(jam-weak-registry-test PRIVATE FILE_SET CXX_MODULES FILES
    t/weak-registry.ccm src/thc/jam/weak.ccm)
  add_test(NAME jam-weak-registry-test COMMAND jam-weak-registry-test)
  add_executable(jam-vm-test t/jam_vm_test.cc)
  set_target_properties(jam-vm-test PROPERTIES CXX_STANDARD 14 CXX_SCAN_FOR_MODULES OFF)
  target_link_libraries(jam-vm-test PRIVATE jam-vm)
  add_test(NAME jam-vm-test COMMAND jam-vm-test)
  add_executable(jam-vm-static-test t/jam_vm_test.cc)
  set_target_properties(jam-vm-static-test PROPERTIES CXX_STANDARD 14 CXX_SCAN_FOR_MODULES OFF)
  target_link_libraries(jam-vm-static-test PRIVATE jam-vm-static)
  add_test(NAME jam-vm-static-test COMMAND jam-vm-static-test)
  add_executable(jam-generational-test t/generational_test.cc)
  set_target_properties(jam-generational-test PROPERTIES CXX_STANDARD 14 CXX_SCAN_FOR_MODULES OFF)
  target_link_libraries(jam-generational-test PRIVATE jam-vm)
  add_test(NAME jam-generational-test COMMAND jam-generational-test)
  if(WIN32)
    target_link_libraries(jam-vm-test PRIVATE onecore)
    target_link_libraries(jam-generational-test PRIVATE onecore)
  endif()

  set_tests_properties(jam-vm-test jam-vm-static-test jam-generational-test jam-weak-registry-test jam-candidate-registry-test PROPERTIES LABELS vm)
endif()

# Runtime tools receive this directory explicitly; standalone wrappers default
# to vm/build-jam. Keep DLLs next to the C ABI tests on Windows.
foreach(target jam-vm jam-vm-static jam-vm-test jam-vm-static-test jam-generational-test)
  if(TARGET ${target})
    set_target_properties(${target} PROPERTIES
      RUNTIME_OUTPUT_DIRECTORY "$<1:${PROJECT_BINARY_DIR}>"
      LIBRARY_OUTPUT_DIRECTORY "$<1:${PROJECT_BINARY_DIR}>"
      ARCHIVE_OUTPUT_DIRECTORY "$<1:${PROJECT_BINARY_DIR}>")
  endif()
endforeach()
find_file(jam_native_license LICENSE.md
  HINTS "${jam_native_SOURCE_DIR}" "${native_DIR}/../../../share/native"
  NO_DEFAULT_PATH REQUIRED)
configure_file("${jam_native_license}"
  "${PROJECT_BINARY_DIR}/native-LICENSE.md" COPYONLY)

find_package(Python3 3.10 REQUIRED COMPONENTS Interpreter)
if(JAM_BUILD_TESTS)
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
set(jam_vm_environment "JAM_NATIVE_BUILD=${PROJECT_BINARY_DIR}")
foreach(runtime jdk graalvm)
  add_custom_target(jam-${runtime}
    COMMAND "${CMAKE_COMMAND}" -E env "${jam_vm_environment}" "JAM_NATIVE_CONFIG=$<CONFIG>"
      "${Python3_EXECUTABLE}" "${CMAKE_CURRENT_SOURCE_DIR}/tools/build_runtime.py" ${runtime}
    DEPENDS jam-vm jam-vm-static
    USES_TERMINAL VERBATIM)
endforeach()
add_custom_target(jam-substratevm DEPENDS jam-graalvm)

# Runtime checks are explicit: an ordinary ctest needs no downloaded JDK.
set(JAM_VM_TEST_RUNTIMES "" CACHE STRING "Runtime checks to register: jdk;graalvm;substratevm")
if(JAM_BUILD_TESTS)
  foreach(runtime IN LISTS JAM_VM_TEST_RUNTIMES)
    if(NOT runtime MATCHES "^(jdk|graalvm|substratevm)$")
      message(FATAL_ERROR "Unknown JAM_VM_TEST_RUNTIMES entry: ${runtime}")
    endif()
    add_test(NAME jam-${runtime}
      COMMAND "${CMAKE_COMMAND}" -E env "${jam_vm_environment}" "JAM_NATIVE_CONFIG=$<CONFIG>"
        "${Python3_EXECUTABLE}" "${CMAKE_CURRENT_SOURCE_DIR}/tools/check_runtime.py" ${runtime})
    set_tests_properties(jam-${runtime} PROPERTIES LABELS "${runtime}" RUN_SERIAL TRUE)
  endforeach()
endif()

set_target_properties(jam-vm PROPERTIES EXPORT_NAME vm)
set_target_properties(jam-vm-static PROPERTIES EXPORT_NAME vm-static)
install(TARGETS jam-vm jam-vm-static EXPORT jamTargets
  RUNTIME DESTINATION "${CMAKE_INSTALL_BINDIR}"
  LIBRARY DESTINATION "${CMAKE_INSTALL_LIBDIR}"
  ARCHIVE DESTINATION "${CMAKE_INSTALL_LIBDIR}")
install(FILES src/adapter/jam_vm.h DESTINATION "${CMAKE_INSTALL_INCLUDEDIR}")
