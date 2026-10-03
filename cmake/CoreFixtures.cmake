# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
# 113: shared execution smoke. 118: source spans in real Core. 120: strict fields.
# Separate GHC object directories prevent shared intermediate writers.
function(core_fixture name core_dir object_dir)
  cmake_parse_arguments(F "" "" "SOURCES;MODULES" ${ARGN})
  set(inputs)
  foreach(source IN LISTS F_SOURCES)
    list(APPEND inputs "${PROJECT_SOURCE_DIR}/${source}")
  endforeach()
  set(outputs)
  set(objects)
  foreach(module IN LISTS F_MODULES)
    list(APPEND outputs "${PROJECT_SOURCE_DIR}/${core_dir}/${module}.cbd")
    string(REPLACE "." "/" module_path "${module}")
    list(APPEND objects "${PROJECT_SOURCE_DIR}/${object_dir}/${module_path}.o"
      "${PROJECT_SOURCE_DIR}/${object_dir}/${module_path}.hi")
  endforeach()
  add_custom_command(OUTPUT ${outputs} BYPRODUCTS ${objects}
    COMMAND ${fixture_env} "THC_CORE_OUT=${PROJECT_SOURCE_DIR}/${core_dir}"
      "THC_GHC_OUT=${PROJECT_SOURCE_DIR}/${object_dir}"
      "${PROJECT_SOURCE_DIR}/bin/export-core.sh" --plugin-manifest "${plugin_manifest}" ${inputs}
    DEPENDS ${inputs} ${plugin_outputs} ${toolchain_inputs}
      "${PROJECT_SOURCE_DIR}/bin/export-core.sh" "${PROJECT_SOURCE_DIR}/bin/toolchain.sh"
    WORKING_DIRECTORY "${PROJECT_SOURCE_DIR}" VERBATIM)
  add_custom_target(fixture-${name} DEPENDS ${outputs} "${encoder_path}")
endfunction()
core_fixture(runtime-core build/core build/ghc/runtime
  SOURCES t/fixtures/core/Fixtures.hs t/fixtures/compiler/THC/Prim/Test.hs
  MODULES Fixtures THC.Prim.Test)
core_fixture(source-core build/source-core build/source-ghc
  SOURCES t/fixtures/compiler/SourceNotes.hs t/fixtures/compiler/RepresentationAudit.hs
  MODULES SourceNotes RepresentationAudit)
core_fixture(strict-fields build/core build/ghc/strict-fields
  SOURCES t/fixtures/compiler/StrictFields.hs MODULES StrictFields)

set(native_dir "${PROJECT_SOURCE_DIR}/build/native")
set(native "${native_dir}/native-oracle")
set(native_inputs "${PROJECT_SOURCE_DIR}/t/fixtures/compiler/THC/Prim/Test.hs")
foreach(module NativeOracle NativeTiming Fixtures MapWorkload)
  list(APPEND native_inputs "${PROJECT_SOURCE_DIR}/t/fixtures/core/${module}.hs")
endforeach()
set(native_objects)
foreach(module Main NativeTiming Fixtures MapWorkload THC/Prim/Test)
  list(APPEND native_objects "${native_dir}/${module}.o" "${native_dir}/${module}.hi")
endforeach()
add_custom_command(OUTPUT "${native}" BYPRODUCTS ${native_objects}
  COMMAND "${CMAKE_COMMAND}" -E make_directory "${native_dir}"
  COMMAND ${fixture_env} "${GHC}" --make -O2 -fforce-recomp -dcore-lint -dstg-lint
    "-i${PROJECT_SOURCE_DIR}/t/fixtures/core" "-i${PROJECT_SOURCE_DIR}/t/fixtures/compiler"
    -odir "${native_dir}" -hidir "${native_dir}"
    "${PROJECT_SOURCE_DIR}/t/fixtures/core/NativeOracle.hs" -o "${native}"
  DEPENDS ${native_inputs} ${toolchain_inputs} WORKING_DIRECTORY "${PROJECT_SOURCE_DIR}" VERBATIM)
add_custom_command(OUTPUT "${native_dir}/oracle.tsv"
  COMMAND "${CMAKE_COMMAND}" "-DPROGRAM=${native}" "-DOUTPUT=${native_dir}/oracle.tsv"
    -P "${PROJECT_SOURCE_DIR}/cmake/CaptureOutput.cmake"
  DEPENDS "${native}" "${PROJECT_SOURCE_DIR}/cmake/CaptureOutput.cmake"
  WORKING_DIRECTORY "${PROJECT_SOURCE_DIR}" VERBATIM)
add_custom_target(fixture-runtime-core-native DEPENDS "${native_dir}/oracle.tsv")
add_dependencies(fixture-runtime-core-native fixture-runtime-core)
