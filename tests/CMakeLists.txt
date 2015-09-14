# add a bare minimum test case
add_executable(test_hec hec.cc)
target_include_directories(test_hec PUBLIC ${CMAKE_SOURCE_DIR})
target_link_libraries(test_hec rts ${Boost_LIBRARIES})
