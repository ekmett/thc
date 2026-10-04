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

# 088: genuine aggregate aliases and representation boundaries. These two CBDs
# feed only the layout/boxed-levity owners; no native oracle or cached test result.
# export-core.sh compiles one source module with -no-link -dynamic and default
# .hi/.o suffixes. No closure= option is requested, so it emits no interface CBD.
set(aggregate_layout_outputs)
foreach(stage pre post)
  set(out "${PROJECT_SOURCE_DIR}/build/aggregate-layout")
  set(cbd "${out}/${stage}-core/AggregateLayoutAudit.cbd")
  set(stage_options)
  if(stage STREQUAL "post")
    list(APPEND stage_options -fplugin-opt=THC.Plugin:post-tidy)
  endif()
  add_custom_command(OUTPUT "${cbd}"
    BYPRODUCTS "${out}/${stage}-ghc/AggregateLayoutAudit.hi" "${out}/${stage}-ghc/AggregateLayoutAudit.o"
    COMMAND ${fixture_env} "THC_CORE_OUT=${out}/${stage}-core" "THC_GHC_OUT=${out}/${stage}-ghc"
      "${PROJECT_SOURCE_DIR}/bin/export-core.sh" --plugin-manifest "${plugin_manifest}"
      ${stage_options} "${PROJECT_SOURCE_DIR}/t/fixtures/compiler/AggregateLayoutAudit.hs"
    DEPENDS "${PROJECT_SOURCE_DIR}/t/fixtures/compiler/AggregateLayoutAudit.hs"
      ${plugin_outputs} ${toolchain_inputs} "${PROJECT_SOURCE_DIR}/bin/export-core.sh"
      "${PROJECT_SOURCE_DIR}/bin/toolchain.sh" "${PROJECT_SOURCE_DIR}/bin/plugin.py"
    WORKING_DIRECTORY "${PROJECT_SOURCE_DIR}" VERBATIM)
  list(APPEND aggregate_layout_outputs "${cbd}")
endforeach()
add_custom_target(fixture-aggregate-layout DEPENDS ${aggregate_layout_outputs})

# 088: sum signatures and actual GHC result values, isolated from sum-result and
# frontier. Consumers check ABI/values themselves; no cached success reports.
# No closure= export is requested. Native Main imports only SumLayoutAudit;
# separate stage/native object directories give every named product one writer.
set(sum_layout_out "${PROJECT_SOURCE_DIR}/build/sum-layout")
set(sum_layout_outputs)
foreach(stage pre post)
  set(cbd "${sum_layout_out}/${stage}-core/SumLayoutAudit.cbd")
  set(stage_options)
  if(stage STREQUAL "post")
    list(APPEND stage_options -fplugin-opt=THC.Plugin:post-tidy)
  endif()
  add_custom_command(OUTPUT "${cbd}"
    BYPRODUCTS "${sum_layout_out}/${stage}-ghc/SumLayoutAudit.hi" "${sum_layout_out}/${stage}-ghc/SumLayoutAudit.o"
    COMMAND ${fixture_env} "THC_CORE_OUT=${sum_layout_out}/${stage}-core" "THC_GHC_OUT=${sum_layout_out}/${stage}-ghc"
      "${PROJECT_SOURCE_DIR}/bin/export-core.sh" --plugin-manifest "${plugin_manifest}"
      ${stage_options} "${PROJECT_SOURCE_DIR}/t/fixtures/compiler/SumLayoutAudit.hs"
    DEPENDS "${PROJECT_SOURCE_DIR}/t/fixtures/compiler/SumLayoutAudit.hs"
      ${plugin_outputs} ${toolchain_inputs} "${PROJECT_SOURCE_DIR}/bin/export-core.sh"
      "${PROJECT_SOURCE_DIR}/bin/toolchain.sh" "${PROJECT_SOURCE_DIR}/bin/plugin.py"
    WORKING_DIRECTORY "${PROJECT_SOURCE_DIR}" VERBATIM)
  list(APPEND sum_layout_outputs "${cbd}")
endforeach()
set(sum_layout_native "${sum_layout_out}/native")
add_custom_command(OUTPUT "${sum_layout_native}/sum-layout-oracle"
  BYPRODUCTS "${sum_layout_native}/Main.hi" "${sum_layout_native}/Main.o"
    "${sum_layout_native}/SumLayoutAudit.hi" "${sum_layout_native}/SumLayoutAudit.o"
  COMMAND "${CMAKE_COMMAND}" -E make_directory "${sum_layout_native}"
  COMMAND ${fixture_env} "${GHC}" --make -O2 -fforce-recomp -dcore-lint -dstg-lint
    "-i${PROJECT_SOURCE_DIR}/t/fixtures/compiler" -odir "${sum_layout_native}" -hidir "${sum_layout_native}"
    "${PROJECT_SOURCE_DIR}/t/fixtures/compiler/SumLayoutAuditNative.hs" -o "${sum_layout_native}/sum-layout-oracle"
  DEPENDS "${PROJECT_SOURCE_DIR}/t/fixtures/compiler/SumLayoutAuditNative.hs"
    "${PROJECT_SOURCE_DIR}/t/fixtures/compiler/SumLayoutAudit.hs" ${toolchain_inputs}
  WORKING_DIRECTORY "${PROJECT_SOURCE_DIR}" VERBATIM)
add_custom_command(OUTPUT "${sum_layout_out}/oracle.tsv"
  COMMAND "${CMAKE_COMMAND}" "-DPROGRAM=${sum_layout_native}/sum-layout-oracle" "-DOUTPUT=${sum_layout_out}/oracle.tsv"
    -P "${PROJECT_SOURCE_DIR}/cmake/CaptureOutput.cmake"
  DEPENDS "${sum_layout_native}/sum-layout-oracle" "${PROJECT_SOURCE_DIR}/cmake/CaptureOutput.cmake"
  WORKING_DIRECTORY "${PROJECT_SOURCE_DIR}" VERBATIM)
add_custom_target(fixture-sum-layout DEPENDS ${sum_layout_outputs} "${sum_layout_out}/oracle.tsv")

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
