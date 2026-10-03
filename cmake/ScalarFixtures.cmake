# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
# Inventory 003-006: arithmetic at sign/width boundaries, checked against native
# GHC. Each Core stage emits its module and (where required) interface closure
# together. No other fixture may write these paths.
function(scalar_fixture name family module driver_name oracle_name)
  set(out "${PROJECT_SOURCE_DIR}/build/${name}")
  set(source "${PROJECT_SOURCE_DIR}/t/fixtures/compiler/${module}.hs")
  set(driver "${out}/${driver_name}")
  set(native "${out}/native/${oracle_name}")
  set(oracle "${out}/oracle.tsv")
  set(manifest "${out}/manifest.json")
  add_custom_command(OUTPUT "${driver}"
    COMMAND ${fixture_env} "${fixtures_exe}" scalar ${family} driver
    DEPENDS "${fixtures_exe}" WORKING_DIRECTORY "${PROJECT_SOURCE_DIR}" VERBATIM)
  # Native GHC baseline: independent of the THC exporter and runtime. Hash the
  # driver, imported fixture module and producer recipe; restore only its files.
  set(native_outputs "${native}" "${oracle}" "${out}/native/Main.hi" "${out}/native/Main.o"
    "${out}/native/${module}.hi" "${out}/native/${module}.o")
  set(native_inputs "${driver}" "${source}" "${PROJECT_SOURCE_DIR}/t/haskell-fixtures/Main.hs"
    "${PROJECT_SOURCE_DIR}/t/haskell-fixtures/FixtureSupport.hs"
    "${PROJECT_SOURCE_DIR}/cmake/ScalarFixtures.cmake" "${PROJECT_SOURCE_DIR}/.github/scripts/fast_inputs.py")
  set(native_args)
  foreach(input IN LISTS native_inputs)
    file(RELATIVE_PATH relative "${PROJECT_SOURCE_DIR}" "${input}")
    list(APPEND native_args --input "${relative}")
  endforeach()
  foreach(output IN LISTS native_outputs)
    file(RELATIVE_PATH relative "${PROJECT_SOURCE_DIR}" "${output}")
    list(APPEND native_args --output "${relative}")
  endforeach()
  add_custom_command(OUTPUT ${native_outputs}
    COMMAND ${fixture_env} "${Python3_EXECUTABLE}" .github/scripts/fast_inputs.py native-oracle
      --producer "${fixtures_exe}" --family "${family}" ${native_args}
    DEPENDS ${native_inputs} "${fixtures_exe}" ${toolchain_inputs}
    WORKING_DIRECTORY "${PROJECT_SOURCE_DIR}" VERBATIM
    COMMENT "Restore or build the independent native GHC ${family} baseline")
  set(stages core)
  if(family STREQUAL "bit")
    set(stages pre-core post-core)
  endif()
  foreach(stage IN LISTS stages)
    set(outputs "${out}/${stage}/${module}.cbd")
    if(NOT family STREQUAL "explicit64")
      list(APPEND outputs "${out}/${stage}/THC.InterfaceClosure.cbd")
    endif()
    add_custom_command(OUTPUT ${outputs}
      BYPRODUCTS "${out}/${stage}-ghc/${module}.hi" "${out}/${stage}-ghc/${module}.o"
      COMMAND ${fixture_env} "${fixtures_exe}" scalar ${family} ${stage}
      DEPENDS "${source}" "${fixtures_exe}" ${plugin_outputs} ${toolchain_inputs}
        "${PROJECT_SOURCE_DIR}/bin/export-core.sh" "${PROJECT_SOURCE_DIR}/bin/toolchain.sh"
      WORKING_DIRECTORY "${PROJECT_SOURCE_DIR}" VERBATIM)
    list(APPEND core_outputs ${outputs})
  endforeach()
  # The existing consumer validates these source hashes as well as the data.
  set(receipt_sources "${source}" "${PROJECT_SOURCE_DIR}/thc.cabal"
    "${PROJECT_SOURCE_DIR}/t/haskell-fixtures/Main.hs"
    "${PROJECT_SOURCE_DIR}/t/haskell-fixtures/FixtureSupport.hs")
  foreach(script build-compiler.sh export-core.sh export-core.ps1 windows-common.ps1 toolchain.sh plugin.py)
    list(APPEND receipt_sources "${PROJECT_SOURCE_DIR}/bin/${script}")
  endforeach()
  file(GLOB compiler_sources CONFIGURE_DEPENDS "${PROJECT_SOURCE_DIR}/src/compiler/THC/*.hs")
  add_custom_command(OUTPUT "${manifest}"
    COMMAND ${fixture_env} "${fixtures_exe}" scalar ${family} manifest
    DEPENDS ${core_outputs} "${driver}" "${native}" "${oracle}" "${fixtures_exe}"
      ${receipt_sources} ${compiler_sources}
    WORKING_DIRECTORY "${PROJECT_SOURCE_DIR}" VERBATIM)
  add_custom_target(fixture-${name} DEPENDS "${manifest}" ${core_outputs} "${driver}" "${native}" "${oracle}" "${encoder_path}")
endfunction()
scalar_fixture(signed-narrow-primops signed-narrow SignedNarrowPrimopsAudit NativeSignedNarrowPrimops.hs signed-narrow-primops-oracle)
scalar_fixture(bit-primops bit BitPrimopsAudit NativeBitPrimops.hs bit-primops-oracle)
scalar_fixture(integer-primops integer IntegerPrimopsAudit NativeIntegerPrimops.hs integer-primops-oracle)
scalar_fixture(explicit64-primops explicit64 Explicit64PrimopsAudit NativeExplicit64.hs explicit64-oracle)
