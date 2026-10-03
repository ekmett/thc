# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
# 112: entry through a partial application must execute IO and propagate failure.
set(pap_out "${PROJECT_SOURCE_DIR}/build/io-main-pap")
set(pap_outputs "${pap_out}/provenance.json" "${pap_out}/oracle.tsv"
  "${pap_out}/native-stderr.txt" "${pap_out}/native/io-main-pap-oracle")
set(pap_objects "${pap_out}/native/Main.hi" "${pap_out}/native/Main.o"
  "${pap_out}/native/IoMainPapAudit.hi" "${pap_out}/native/IoMainPapAudit.o")
foreach(stage pre post)
  list(APPEND pap_outputs "${pap_out}/${stage}/core/IoMainPapAudit.cbd"
    "${pap_out}/${stage}/core/THC.InterfaceClosure.cbd"
    "${pap_out}/${stage}/goodMain-audit.json" "${pap_out}/${stage}/badMain-audit.json")
  list(APPEND pap_objects "${pap_out}/${stage}/ghc/IoMainPapAudit.hi" "${pap_out}/${stage}/ghc/IoMainPapAudit.o")
endforeach()
add_custom_command(OUTPUT ${pap_outputs} BYPRODUCTS ${pap_objects}
  COMMAND ${fixture_env} "${Python3_EXECUTABLE}" "${PROJECT_SOURCE_DIR}/bin/prepare-io-main-pap.py"
  DEPENDS "${PROJECT_SOURCE_DIR}/bin/prepare-io-main-pap.py"
    "${PROJECT_SOURCE_DIR}/t/fixtures/compiler/IoMainPapAudit.hs"
    "${PROJECT_SOURCE_DIR}/t/fixtures/compiler/IoMainPapNative.hs"
    ${api_export_inputs} ${audit_inputs} ${tool_sources}
    "${compact_exe}" ${plugin_outputs} ${toolchain_inputs}
  WORKING_DIRECTORY "${PROJECT_SOURCE_DIR}" VERBATIM
  COMMENT "Generate IO-main partial application Core and native effect oracle")
add_custom_target(fixture-io-main-pap DEPENDS ${pap_outputs})
