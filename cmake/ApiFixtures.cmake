# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
# Public API examples compare observable results with independent scalar code.
# These producers do not run the Core auditor and do not depend on its tables.
set(api_export_inputs)
foreach(script export-core.sh build-compiler.sh toolchain.sh plugin.py)
  list(APPEND api_export_inputs "${PROJECT_SOURCE_DIR}/bin/${script}")
endforeach()

# 125: public vector loops include masks, tails and conversions.
set(vector_out "${PROJECT_SOURCE_DIR}/build/vector-api")
set(vector_outputs "${vector_out}/core/VectorLoops.cbd" "${vector_out}/core/THC.Prim.cbd"
  "${vector_out}/native/oracle" "${vector_out}/oracle.tsv")
add_custom_command(OUTPUT ${vector_outputs}
  BYPRODUCTS "${vector_out}/native/Main.hi" "${vector_out}/native/Main.o"
    "${vector_out}/ghc/VectorLoops.hi" "${vector_out}/ghc/VectorLoops.o"
    "${vector_out}/ghc/THC/Prim.hi" "${vector_out}/ghc/THC/Prim.o"
  COMMAND ${fixture_env} "${fixtures_exe}" vector-api
  DEPENDS "${PROJECT_SOURCE_DIR}/src/examples/VectorLoops.hs"
    "${PROJECT_SOURCE_DIR}/src/runtime/THC/Prim.hs"
    "${PROJECT_SOURCE_DIR}/t/fixtures/compiler/VectorScalar.hs"
    ${api_export_inputs} "${fixtures_exe}" ${plugin_outputs} ${toolchain_inputs}
  WORKING_DIRECTORY "${PROJECT_SOURCE_DIR}" VERBATIM
  COMMENT "Generate vector API Core and independent scalar oracle")
add_custom_target(fixture-vector-api DEPENDS ${vector_outputs})

# 122: THC strings and suspended intrinsic operands; each module has one owner
# in this family, including exception modules used by its tagged integration test.
set(string_out "${PROJECT_SOURCE_DIR}/build/truffle-strings")
set(string_outputs "${string_out}/manifest.json" "${string_out}/native/oracle" "${string_out}/oracle.json")
set(string_objects "${string_out}/native/Main.hi" "${string_out}/native/Main.o")
foreach(module THC.Prim StringPrimitives IntrinsicOperands TruffleStringExceptions THC.Exception THC.Internal.Exception)
  list(APPEND string_outputs "${string_out}/core/${module}.cbd")
  string(REPLACE "." "/" module_path "${module}")
  list(APPEND string_objects "${string_out}/ghc/${module_path}.hi" "${string_out}/ghc/${module_path}.o")
endforeach()
add_custom_command(OUTPUT ${string_outputs} BYPRODUCTS ${string_objects}
  COMMAND ${fixture_env} "${fixtures_exe}" truffle-strings
  DEPENDS "${PROJECT_SOURCE_DIR}/src/examples/StringPrimitives.hs"
    "${PROJECT_SOURCE_DIR}/src/runtime/THC/Prim.hs"
    "${PROJECT_SOURCE_DIR}/src/runtime/THC/Exception.hs"
    "${PROJECT_SOURCE_DIR}/src/runtime/THC/Internal/Exception.hs"
    "${PROJECT_SOURCE_DIR}/t/fixtures/core/IntrinsicOperands.hs"
    "${PROJECT_SOURCE_DIR}/t/fixtures/compiler/TruffleStringExceptions.hs"
    "${PROJECT_SOURCE_DIR}/t/fixtures/compiler/StringScalar.hs"
    ${api_export_inputs} ${tool_sources} ${cabal_inputs}
    "${fixtures_exe}" ${plugin_outputs} ${toolchain_inputs}
  WORKING_DIRECTORY "${PROJECT_SOURCE_DIR}" VERBATIM
  COMMENT "Generate string API Core and independent scalar oracle")
add_custom_target(fixture-truffle-strings DEPENDS ${string_outputs})
