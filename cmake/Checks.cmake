# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
# Fresh checks are phony targets; only their real producers constrain readiness.
# Gradle owns Java task freshness. Its project state has one writer at a time.
set_property(GLOBAL APPEND PROPERTY JOB_POOLS gradle=1)
set(ci_env "${CMAKE_COMMAND}" -E env "GHC=${GHC}" "GHC_PKG=${GHC_PKG}"
  "CABAL=${CABAL}" "THC_FIXTURES=${fixtures_exe}" "THC_COMPACT=${compact_exe}")
set(ci_runner "${Python3_EXECUTABLE}" .github/scripts/fast_ci.py)
set(ci_reports "${PROJECT_SOURCE_DIR}/build/ci/check-results")
add_custom_target(ci-commit)

# Keep Java preparation in one Gradle invocation; its task graph orders compilation.
add_custom_target(ci-runtime
  COMMAND ${ci_env} ${ci_runner} check-command --report-dir "${ci_reports}/runtime-build" --
    ./gradlew --daemon "--max-workers=${THC_BUILD_JOBS}" --build-cache --profile installDist testClasses toolsJar
  WORKING_DIRECTORY "${PROJECT_SOURCE_DIR}" JOB_POOL gradle VERBATIM)
add_custom_target(ci-driver-help
  COMMAND ${ci_env} ${ci_runner} check-command --report-dir "${ci_reports}/driver-help" --
    "${ci_binary_exe_thc}" --help
  DEPENDS "${ci_binary_exe_thc}" WORKING_DIRECTORY "${PROJECT_SOURCE_DIR}" VERBATIM)
add_dependencies(ci-commit ci-driver-help)

get_filename_component(primop_bin "${ci_binary_exe_thc_primops}" DIRECTORY)
get_filename_component(driver_bin "${ci_binary_exe_thc}" DIRECTORY)
get_filename_component(interface_bin "${interface_exe}" DIRECTORY)
if(ci_suite_count GREATER 0)
  foreach(i RANGE ${ci_suite_last})
    string(JSON suite GET "${ci_selection}" haskell ${i})
    string(MAKE_C_IDENTIFIER "test:${suite}" key)
    set(options)
    if(suite STREQUAL "driver-tests")
      set(options --unit-only)
    endif()
    add_custom_target("ci-${suite}"
      COMMAND ${ci_env} "PATH=${primop_bin}:${driver_bin}:${interface_bin}:$ENV{PATH}" ${ci_runner} check-command
        --report-dir "${ci_reports}/${suite}" -- "${ci_binary_${key}}" ${options}
      DEPENDS "${ci_binary_${key}}" "${ci_binary_exe_thc_primops}" "${ci_binary_exe_thc}" "${interface_exe}"
      WORKING_DIRECTORY "${PROJECT_SOURCE_DIR}" VERBATIM)
    add_dependencies(ci-commit "ci-${suite}")
  endforeach()
endif()
string(JSON check_primops GET "${ci_selection}" primops)
if(check_primops)
  add_custom_target(ci-primop-metadata
    COMMAND ${ci_env} ${ci_runner} check-command --report-dir "${ci_reports}/scalars" --
      "${ci_binary_exe_thc_primops}" scalars
    COMMAND ${ci_env} ${ci_runner} check-command --report-dir "${ci_reports}/checklist" --
      "${ci_binary_exe_thc_primops}" coverage --check
    DEPENDS "${ci_binary_exe_thc_primops}" WORKING_DIRECTORY "${PROJECT_SOURCE_DIR}" VERBATIM)
  add_dependencies(ci-commit ci-primop-metadata)
endif()

string(JSON python_count LENGTH "${ci_selection}" python)
if(python_count GREATER 0)
  math(EXPR python_last "${python_count} - 1")
  foreach(i RANGE ${python_last})
    string(JSON script GET "${ci_selection}" python ${i} path)
    string(JSON optimized GET "${ci_selection}" python ${i} optimized)
    get_filename_component(name "${script}" NAME_WE)
    set(dependencies "${PROJECT_SOURCE_DIR}/${script}")
    if(name STREQUAL "test-audit-core" OR name STREQUAL "test-core-package-manifest")
      list(APPEND dependencies "${fixtures_exe}" "${compact_exe}")
    endif()
    add_custom_target("ci-${name}"
      COMMAND ${ci_env} ${ci_runner} check-command --report-dir "${ci_reports}/${name}" --
        "${Python3_EXECUTABLE}" "${script}"
      DEPENDS ${dependencies} WORKING_DIRECTORY "${PROJECT_SOURCE_DIR}" VERBATIM)
    add_dependencies(ci-commit "ci-${name}")
    if(optimized)
      add_custom_target("ci-${name}-optimized"
        COMMAND ${ci_env} ${ci_runner} check-command --report-dir "${ci_reports}/${name}-optimized" --
          "${Python3_EXECUTABLE}" -O "${script}"
        DEPENDS ${dependencies} WORKING_DIRECTORY "${PROJECT_SOURCE_DIR}" VERBATIM)
      add_dependencies(ci-commit "ci-${name}-optimized")
    endif()
  endforeach()
endif()

string(JSON check_protocol GET "${ci_selection}" protocol)
if(check_protocol)
  add_custom_target(ci-truffle-protocol
    COMMAND ${ci_env} ${ci_runner} check-command --report-dir "${ci_reports}/truffle-protocol" --
      ./gradlew --daemon "--max-workers=${THC_BUILD_JOBS}" --build-cache
      testMaterializableApi testReturnPolicy testReturnContinuations
    WORKING_DIRECTORY "${PROJECT_SOURCE_DIR}" JOB_POOL gradle VERBATIM)
  add_dependencies(ci-commit ci-truffle-protocol)
endif()

set(runtime_inputs ci-runtime)
string(JSON fixture_count LENGTH "${ci_selection}" fixtures)
if(fixture_count GREATER 0)
  math(EXPR fixture_last "${fixture_count} - 1")
  foreach(i RANGE ${fixture_last})
    string(JSON target GET "${ci_selection}" fixtures ${i})
    if(NOT TARGET "${target}")
      message(FATAL_ERROR "Unknown CI fixture producer: ${target}")
    endif()
    list(APPEND runtime_inputs "${target}")
  endforeach()
endif()
add_custom_target(ci-jvm
  COMMAND ${ci_env} ${ci_runner} jvm-group --group commit --cadence commit --reuse-daemon
    --report-dir "${PROJECT_SOURCE_DIR}/build/ci/group-results/commit"
  DEPENDS ${runtime_inputs}
  WORKING_DIRECTORY "${PROJECT_SOURCE_DIR}" JOB_POOL gradle VERBATIM)
add_dependencies(ci-commit ci-jvm)
