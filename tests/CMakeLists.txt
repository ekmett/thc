macro(add_test name)
   add_executable(test_${name} ${name}.cc)
   target_link_libraries(test_${name} rts)
endmacro()

add_test(hec)
