add_library(rts rts.cc gc.cc)
target_include_directories(rts PUBLIC ${CMAKE_CURRENT_SOURCE_DIR})
target_link_libraries(rts ${Boost_LIBRARIES})
