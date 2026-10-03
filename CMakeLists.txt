# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
cmake_minimum_required(VERSION 3.24)
project(THCFixtures NONE)

# Gradle owns the application; Cabal owns compilation of the Haskell tools.
# This graph owns fixture files. No directory or success stamp is an output.
if(CMAKE_SOURCE_DIR STREQUAL CMAKE_BINARY_DIR)
  message(FATAL_ERROR "Use cmake -S . -B build/fixtures -G Ninja")
endif()
if(WIN32)
  message(FATAL_ERROR "The CMake fixture exporter currently supports macOS/Linux; see docs/windows.md")
endif()
find_package(Python3 3.12 REQUIRED COMPONENTS Interpreter)
find_program(GHC NAMES ghc-9.14.1 ghc HINTS "$ENV{HOME}/.ghcup/bin" REQUIRED)
find_program(CABAL NAMES cabal-3.16.0.0 cabal HINTS "$ENV{HOME}/.ghcup/bin" REQUIRED)
execute_process(COMMAND "${CMAKE_COMMAND}" -E env --unset=GHC_PACKAGE_PATH "GHC_ENVIRONMENT=-" "GHC=${GHC}" "GHC_PKG=${GHC_PKG}"
  sh -c ". ./bin/toolchain.sh; printf '%s\\n' \"$GHC\" \"$GHC_PKG\""
  WORKING_DIRECTORY "${PROJECT_SOURCE_DIR}" OUTPUT_VARIABLE toolchain
  OUTPUT_STRIP_TRAILING_WHITESPACE COMMAND_ERROR_IS_FATAL ANY)
string(REPLACE "\n" ";" toolchain "${toolchain}")
list(GET toolchain 0 GHC)
list(GET toolchain 1 GHC_PKG)
get_filename_component(python_bin "${Python3_EXECUTABLE}" DIRECTORY)
set(fixture_env "${CMAKE_COMMAND}" -E env --unset=GHC_PACKAGE_PATH "GHC=${GHC}" "GHC_PKG=${GHC_PKG}"
  "PATH=${python_bin}:$ENV{PATH}" "CABAL=${CABAL}" "GHC_ENVIRONMENT=-" "THC_SOURCE_NOTES=true" "THC_PYTHON=${Python3_EXECUTABLE}")
include(cmake/FixtureTools.cmake)
# Fixtures use the selected boot database plus the explicit plugin database.
# A user's package DB or GHC_PACKAGE_PATH must not supply an undeclared import.
list(APPEND fixture_env "GHC_PACKAGE_PATH=${ghc_db}"
  "THC_PLUGIN_MANIFEST=${plugin_manifest}" "THC_COMPACT=${compact_exe}")
include(cmake/ScalarFixtures.cmake)
include(cmake/CoreFixtures.cmake)
include(cmake/ArrayFixtures.cmake)
include(cmake/AuditedFixtures.cmake)
include(cmake/ApiFixtures.cmake)
include(cmake/NativeFixtures.cmake)
include(cmake/SimdFixtures.cmake)
include(cmake/ScriptFixtures.cmake)

# Keep the audited quarantine authoritative even for direct Ninja invocations.
set(inventory_path "${PROJECT_SOURCE_DIR}/.github/scripts/fast-fixtures.json")
set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS "${inventory_path}")
file(READ "${inventory_path}" inventory)
string(JSON group_count LENGTH "${inventory}" groups)
math(EXPR group_last "${group_count} - 1")
foreach(index RANGE ${group_last})
  string(JSON group MEMBER "${inventory}" groups ${index})
  string(JSON blocked ERROR_VARIABLE missing GET "${inventory}" groups "${group}" quarantined)
  if(blocked AND TARGET "fixture-${group}")
    message(FATAL_ERROR "Quarantined fixture has a build rule: ${group}")
  endif()
  string(JSON target ERROR_VARIABLE missing GET "${inventory}" groups "${group}" cmakeTarget)
  if(NOT missing AND NOT TARGET "${target}")
    message(FATAL_ERROR "Admitted fixture is missing its rule: ${group} -> ${target}")
  endif()
endforeach()
