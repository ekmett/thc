# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
# Inventory 007-012: scalar array reads/writes, byte aliasing and literal bounds.
# One existing Haskell invocation produces each family's named files together.
# Every stage/group has a private Core/object directory; no shared array producer.
function(array_fixture name driver oracle)
  set(out "${PROJECT_SOURCE_DIR}/build/${name}")
  set(outputs "${out}/${driver}" "${out}/native/${oracle}"
    "${out}/oracle.tsv" "${out}/manifest.json")
  if(name MATCHES "^int(16|32)-arrays$")
    list(APPEND outputs "${out}/literal-oracle.tsv")
  endif()
  set(inputs)
  set(objects "${out}/native/Main.o" "${out}/native/Main.hi")
  set(index 0)
  foreach(source IN LISTS ARGN)
    list(APPEND inputs "${PROJECT_SOURCE_DIR}/${source}")
    get_filename_component(module "${source}" NAME_WE)
    list(APPEND objects "${out}/native/${module}.o" "${out}/native/${module}.hi")
    foreach(stage pre post)
      list(APPEND outputs "${out}/${stage}/${index}/core/${module}.cbd"
        "${out}/${stage}/${index}/core/THC.InterfaceClosure.cbd")
      list(APPEND objects "${out}/${stage}/${index}/ghc/${module}.o"
        "${out}/${stage}/${index}/ghc/${module}.hi")
    endforeach()
    math(EXPR index "${index} + 1")
  endforeach()
  # The producer includes these source bytes in its consumer-checked manifest.
  foreach(script build-compiler.sh export-core.sh toolchain.sh plugin.py)
    list(APPEND inputs "${PROJECT_SOURCE_DIR}/bin/${script}")
  endforeach()
  add_custom_command(OUTPUT ${outputs} BYPRODUCTS ${objects}
    COMMAND ${fixture_env} "${fixtures_exe}" array "${name}" "${plugin_manifest}"
    DEPENDS ${inputs} "${fixtures_exe}" ${plugin_outputs} ${toolchain_inputs}
    WORKING_DIRECTORY "${PROJECT_SOURCE_DIR}" VERBATIM
    COMMENT "Generate ${name}: native oracle and pre/post Core")
  add_custom_target(fixture-${name} DEPENDS ${outputs})
endfunction()
array_fixture(int-arrays NativeIntArray.hs int-array-oracle
  t/fixtures/core/UnboxedArrays.hs t/fixtures/compiler/IntArrayAudit.hs)
array_fixture(int8-arrays NativeInt8Array.hs int8-array-oracle
  t/fixtures/core/Unboxed8Arrays.hs t/fixtures/compiler/Int8ArrayAudit.hs)
array_fixture(int16-arrays NativeInt16Array.hs int16-array-oracle
  t/fixtures/core/Unboxed16Arrays.hs t/fixtures/compiler/Int16ArrayAudit.hs)
array_fixture(int32-arrays NativeInt32Array.hs int32-array-oracle
  t/fixtures/core/Unboxed32Arrays.hs t/fixtures/compiler/Int32ArrayAudit.hs)
array_fixture(double-arrays NativeDoubleArray.hs double-array-oracle
  t/fixtures/core/UnboxedDoubleArrays.hs t/fixtures/compiler/DoubleArrayAudit.hs)
array_fixture(float-word-arrays NativeFloatWordArray.hs float-word-array-oracle
  t/fixtures/core/UnboxedFloatArrays.hs t/fixtures/core/UnboxedWordArrays.hs
  t/fixtures/compiler/FloatArrayAudit.hs t/fixtures/compiler/WordArrayAudit.hs)
