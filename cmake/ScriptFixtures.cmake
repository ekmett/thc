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
    "${pap_out}/${stage}/goodMain-audit.json" "${pap_out}/${stage}/badMain-audit.json"
    "${pap_out}/${stage}/nonUnitMain-audit.json" "${pap_out}/${stage}/unitBottomMain-audit.json"
    "${pap_out}/${stage}/functionMain-audit.json" "${pap_out}/${stage}/lazyMain-audit.json")
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

# 136: managed addresses through fields, closures, PAPs and tuple returns. The
# generated native driver and byte model share no generated inputs with another
# producer; GHC representation choices are not assertions in fixture generation.
set(address_out "${PROJECT_SOURCE_DIR}/build/address-fields")
set(address_outputs "${address_out}/manifest.json" "${address_out}/NativeAddressFields.hs"
  "${address_out}/oracle.tsv" "${address_out}/expected.tsv" "${address_out}/native/address-fields-oracle")
set(address_objects "${address_out}/native/Main.hi" "${address_out}/native/Main.o"
  "${address_out}/native/AddressFieldAudit.hi" "${address_out}/native/AddressFieldAudit.o")
foreach(stage pre post)
  list(APPEND address_outputs "${address_out}/${stage}/core/AddressFieldAudit.cbd" "${address_out}/${stage}/audit.json")
  list(APPEND address_objects "${address_out}/${stage}/ghc/AddressFieldAudit.hi" "${address_out}/${stage}/ghc/AddressFieldAudit.o")
endforeach()
add_custom_command(OUTPUT ${address_outputs} BYPRODUCTS ${address_objects}
  COMMAND ${fixture_env} "${Python3_EXECUTABLE}" "${PROJECT_SOURCE_DIR}/bin/prepare-address-fields.py"
  DEPENDS "${PROJECT_SOURCE_DIR}/bin/prepare-address-fields.py"
    "${PROJECT_SOURCE_DIR}/t/fixtures/compiler/AddressFieldAudit.hs"
    ${api_export_inputs} ${audit_inputs} ${tool_sources}
    "${compact_exe}" ${plugin_outputs} ${toolchain_inputs}
  WORKING_DIRECTORY "${PROJECT_SOURCE_DIR}" VERBATIM
  COMMENT "Generate address-field Core, native values and independent byte model")
add_custom_target(fixture-address-fields DEPENDS ${address_outputs})
