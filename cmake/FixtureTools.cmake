# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
# Cabal selects tool paths and dependency units; configure only resolves a plan.
set(cabal_options --offline "--with-compiler=${GHC}" "--with-hc-pkg=${GHC_PKG}")
set(cabal_targets lib:thc exe:thc exe:thc-fixtures exe:thc-compact exe:thc-interface ${ci_cabal_targets})
if(THC_CI_SELECTION)
  # CI may acquire missing dependencies from the frozen cabal.project index.
  list(REMOVE_ITEM cabal_options --offline)
endif()
execute_process(COMMAND ${fixture_env} "${CABAL}" build ${cabal_options}
  ${cabal_targets} --dry-run
  WORKING_DIRECTORY "${PROJECT_SOURCE_DIR}" COMMAND_ERROR_IS_FATAL ANY)
set(plan_path "${PROJECT_SOURCE_DIR}/dist-newstyle/cache/plan.json")
file(READ "${plan_path}" plan)
file(SHA256 "${plan_path}" plan_hash)
set(plugin_db "${PROJECT_SOURCE_DIR}/build/compiler/plugin-package-dbs/cmake-${plan_hash}")
set(plugin_manifest "${PROJECT_SOURCE_DIR}/build/compiler/plugin.json")
set(cabal_inputs "${PROJECT_SOURCE_DIR}/thc.cabal" "${PROJECT_SOURCE_DIR}/cabal.project"
  "${PROJECT_SOURCE_DIR}/Setup.hs")
# These are tool source inputs, not searches for generated fixture outputs.
file(GLOB_RECURSE tool_sources CONFIGURE_DEPENDS
  "${PROJECT_SOURCE_DIR}/src/compiler/*.hs" "${PROJECT_SOURCE_DIR}/src/cbd/*.hs"
  "${PROJECT_SOURCE_DIR}/src/core-symbols/*.hs" "${PROJECT_SOURCE_DIR}/src/driver/*.hs"
  "${PROJECT_SOURCE_DIR}/t/haskell-fixtures/*.hs")
if(THC_CI_SELECTION)
  file(GLOB_RECURSE ci_haskell_sources CONFIGURE_DEPENDS
    "${PROJECT_SOURCE_DIR}/src/tools/primops/*.hs" "${PROJECT_SOURCE_DIR}/src/runtime/*.hs"
    "${PROJECT_SOURCE_DIR}/src/runtime/*.c" "${PROJECT_SOURCE_DIR}/src/runtime/*.h"
    "${PROJECT_SOURCE_DIR}/t/haskell-driver/*.hs" "${PROJECT_SOURCE_DIR}/t/haskell-affinity/*.hs"
    "${PROJECT_SOURCE_DIR}/t/compact-core/*.hs" "${PROJECT_SOURCE_DIR}/t/primop-tools/*.hs")
  list(APPEND tool_sources ${ci_haskell_sources})
endif()
file(GLOB cabal_configs CONFIGURE_DEPENDS
  "${PROJECT_SOURCE_DIR}/cabal.project.local" "${PROJECT_SOURCE_DIR}/cabal.project.freeze")
list(APPEND cabal_inputs ${cabal_configs})
execute_process(COMMAND "${CABAL}" path --config-file OUTPUT_VARIABLE cabal_config
  OUTPUT_STRIP_TRAILING_WHITESPACE COMMAND_ERROR_IS_FATAL ANY)
if(EXISTS "${cabal_config}")
  list(APPEND cabal_inputs "${cabal_config}")
endif()
set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS ${cabal_inputs})
execute_process(COMMAND "${GHC}" --print-global-package-db OUTPUT_VARIABLE ghc_db
  OUTPUT_STRIP_TRAILING_WHITESPACE COMMAND_ERROR_IS_FATAL ANY)
set(toolchain_inputs "${GHC}" "${GHC_PKG}" "${CABAL}" "${ghc_db}/package.cache" "${Python3_EXECUTABLE}" "${PROJECT_SOURCE_DIR}/bin/toolchain.sh")
# Changes within a selected compiler installation must invalidate fixture files,
# including replacing interfaces with retained Core under the same GHC version.
execute_process(COMMAND ${fixture_env} "${GHC}" --print-libdir OUTPUT_VARIABLE ghc_libdir
  OUTPUT_STRIP_TRAILING_WHITESPACE COMMAND_ERROR_IS_FATAL ANY)
file(GLOB_RECURSE ghc_package_inputs CONFIGURE_DEPENDS
  "${ghc_libdir}/*.hi" "${ghc_libdir}/*.dyn_hi" "${ghc_libdir}/*.a"
  "${ghc_libdir}/*.so" "${ghc_libdir}/*.dylib" "${ghc_libdir}/*.h" "${ghc_db}/*.conf")
list(APPEND toolchain_inputs "${ghc_libdir}/settings" ${ghc_package_inputs})
execute_process(COMMAND ${fixture_env} "${GHC}" --info OUTPUT_VARIABLE ghc_info
  COMMAND_ERROR_IS_FATAL ANY)
string(REGEX MATCHALL [[\("[^"]+ command","[^"]+"\)]] ghc_commands "${ghc_info}")
foreach(command IN LISTS ghc_commands)
  string(REGEX REPLACE [[.*","([^"]+)"\)]] "\\1" program_name "${command}")
  unset(program)
  find_program(program NAMES "${program_name}" NO_CACHE)
  if(program)
    list(APPEND toolchain_inputs "${program}")
  endif()
endforeach()
list(REMOVE_DUPLICATES toolchain_inputs)
set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS ${toolchain_inputs})

if(APPLE)
  set(shared_suffix dylib)
else()
  set(shared_suffix so)
endif()
string(JSON count LENGTH "${plan}" install-plan)
math(EXPR last "${count} - 1")
foreach(i RANGE ${last})
  string(JSON unit GET "${plan}" install-plan ${i} id)
  set(unit_index_${unit} ${i})
  string(JSON component ERROR_VARIABLE no_component GET "${plan}" install-plan ${i} component-name)
  string(JSON style ERROR_VARIABLE no_style GET "${plan}" install-plan ${i} style)
  if(style STREQUAL "local" AND (component MATCHES "^(lib|lib:compact-core|lib:core-symbols|lib:driver|exe:thc|exe:thc-fixtures|exe:thc-compact|exe:thc-interface)$" OR component IN_LIST ci_cabal_targets))
    string(JSON dist GET "${plan}" install-plan ${i} dist-dir)
    if(component MATCHES "^(exe|test):")
      string(JSON binary GET "${plan}" install-plan ${i} bin-file)
      string(MAKE_C_IDENTIFIER "${component}" binary_key)
      set(ci_binary_${binary_key} "${binary}")
      set(component_outputs "${binary}")
      if(component STREQUAL "exe:thc")
        set(driver_exe "${binary}")
      elseif(component STREQUAL "exe:thc-fixtures")
        set(fixtures_exe "${binary}")
      elseif(component STREQUAL "exe:thc-compact")
        set(compact_exe "${binary}")
      elseif(component STREQUAL "exe:thc-interface")
        set(interface_exe "${binary}")
      endif()
    else()
      set(library_dir "${dist}/build")
      if(component MATCHES "^lib:(.+)$")
        string(APPEND library_dir "/${CMAKE_MATCH_1}")
      endif()
      set(shared "${library_dir}/libHS${unit}-ghc9.14.1.${shared_suffix}")
      set(unit_products_${unit} "${shared}"
        "${PROJECT_SOURCE_DIR}/dist-newstyle/packagedb/ghc-9.14.1/${unit}.conf")
      set(component_outputs ${unit_products_${unit}})
      if(component STREQUAL "lib")
        set(plugin_unit "${unit}")
        set(plugin_shared "${shared}")
        set(plugin_copy "${PROJECT_SOURCE_DIR}/build/compiler/libHS${unit}-ghc9.14.1.${shared_suffix}")
      endif()
    endif()
    list(APPEND tool_outputs ${component_outputs})
    foreach(product IN LISTS component_outputs)
      string(APPEND cabal_product_checks "cabal_output([==[${product}]==] [==[${dist}/cache]==])\n")
    endforeach()
  endif()
endforeach()
configure_file("${PROJECT_SOURCE_DIR}/cmake/CabalProducts.cmake.in"
  "${CMAKE_CURRENT_BINARY_DIR}/CabalProducts.cmake" @ONLY)
if(NOT fixtures_exe OR NOT driver_exe OR NOT plugin_shared)
  message(FATAL_ERROR "Cabal plan is missing the selected fixture tools")
endif()
# All tools share Cabal's plan and registration database. One command owns
# those mutable files too; a later interface-only build must not invalidate an
# already-published plugin and make every fixture run again on the next build.
add_custom_command(OUTPUT ${tool_outputs}
  BYPRODUCTS "${plan_path}" "${PROJECT_SOURCE_DIR}/dist-newstyle/packagedb/ghc-9.14.1/package.cache"
  COMMAND "${CMAKE_COMMAND}" -DREPAIR=ON -P "${CMAKE_CURRENT_BINARY_DIR}/CabalProducts.cmake"
  COMMAND ${fixture_env} "${CABAL}" build ${cabal_options} ${cabal_targets} "-j${THC_BUILD_JOBS}"
  COMMAND "${CMAKE_COMMAND}" -P "${CMAKE_CURRENT_BINARY_DIR}/CabalProducts.cmake"
  DEPENDS "${CMAKE_CURRENT_BINARY_DIR}/CabalProducts.cmake" ${cabal_inputs} ${tool_sources} ${toolchain_inputs}
  WORKING_DIRECTORY "${PROJECT_SOURCE_DIR}" VERBATIM
  COMMENT "Build shared fixture tools with Cabal")

# The plugin's non-boot registration closure is known from the Cabal plan.
# Every registration and its cache is an output of one publication operation.
set(pending "${plugin_unit}")
while(pending)
  list(POP_FRONT pending unit)
  if(unit IN_LIST visited)
    continue()
  endif()
  list(APPEND visited "${unit}")
  list(APPEND plugin_build_inputs ${unit_products_${unit}})
  set(i "${unit_index_${unit}}")
  string(JSON type GET "${plan}" install-plan ${i} type)
  if(NOT type STREQUAL "pre-existing")
    list(APPEND plugin_outputs "${plugin_db}/${unit}.conf")
  endif()
  string(JSON n LENGTH "${plan}" install-plan ${i} depends)
  if(n GREATER 0)
    math(EXPR end "${n} - 1")
    foreach(d RANGE ${end})
      string(JSON dependency GET "${plan}" install-plan ${i} depends ${d})
      list(APPEND pending "${dependency}")
    endforeach()
  endif()
endwhile()
set(plugin_outputs "${plugin_manifest}" "${plugin_copy}" "${plugin_db}/package.cache" ${plugin_outputs})
add_custom_command(OUTPUT ${plugin_outputs}
  COMMAND ${fixture_env} "${Python3_EXECUTABLE}" bin/plugin.py --publish --ghc-pkg "${GHC_PKG}" --package-db "${plugin_db}" --depfile "${CMAKE_CURRENT_BINARY_DIR}/plugin.d"
  DEPFILE "${CMAKE_CURRENT_BINARY_DIR}/plugin.d"
  DEPENDS ${plugin_build_inputs} ${toolchain_inputs} "${PROJECT_SOURCE_DIR}/bin/plugin.py" "${plan_path}"
  WORKING_DIRECTORY "${PROJECT_SOURCE_DIR}" VERBATIM
  COMMENT "Publish the Core plugin and its declared package registrations")
# Java's synthetic CBD tests consume this path; this is its sole graph writer.
file(GENERATE OUTPUT "${CMAKE_CURRENT_BINARY_DIR}/thc-fixtures.path" CONTENT "${fixtures_exe}\n")
set(encoder_path "${PROJECT_SOURCE_DIR}/build/thc-fixtures.path")
add_custom_command(OUTPUT "${encoder_path}"
  COMMAND "${CMAKE_COMMAND}" -E copy "${CMAKE_CURRENT_BINARY_DIR}/thc-fixtures.path" "${encoder_path}"
  DEPENDS "${fixtures_exe}" "${CMAKE_CURRENT_BINARY_DIR}/thc-fixtures.path" VERBATIM)
file(GENERATE OUTPUT "${CMAKE_CURRENT_BINARY_DIR}/thc-compact.path" CONTENT "${compact_exe}\n")
set(compact_path "${PROJECT_SOURCE_DIR}/build/thc-compact.path")
add_custom_command(OUTPUT "${compact_path}"
  COMMAND "${CMAKE_COMMAND}" -E copy "${CMAKE_CURRENT_BINARY_DIR}/thc-compact.path" "${compact_path}"
  DEPENDS "${compact_exe}" "${CMAKE_CURRENT_BINARY_DIR}/thc-compact.path" VERBATIM)
add_custom_target(fixture-compact-model DEPENDS "${encoder_path}" "${fixtures_exe}")
add_custom_target(fixture-tools DEPENDS ${tool_outputs} ${plugin_outputs} "${encoder_path}" "${compact_path}")
