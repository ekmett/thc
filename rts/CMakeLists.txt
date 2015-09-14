add_library(rts rts.cc gc.cc)
target_include_directories(rts PUBLIC ${CMAKE_CURRENT_SOURCE_DIR})

include(CheckCXXCompilerFlag)
CHECK_CXX_COMPILER_FLAG("-std=c++11" COMPILER_SUPPORTS_CXX11)
CHECK_CXX_COMPILER_FLAG("-std=c++0x" COMPILER_SUPPORTS_CXX0X)
if(COMPILER_SUPPORTS_CXX11)
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -std=c++11")
elseif(COMPILER_SUPPORTS_CXX0X)
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -std=c++0x")
else()
        message(STATUS "The compiler ${CMAKE_CXX_COMPILER} has no C++11 support. Please use a different C++ compiler.")
endif()

# We need boost::thread_specific_ptr<> to hack around clang's disabled thread_local support
set(Boost_USE_STATIC_LIBS OFF)
set(Boost_USE_MULTITHREADED ON)
set(Boost_USE_STATIC_RUNTIME OFF)
find_package(Boost 1.45.0 REQUIRED COMPONENTS thread lockfree)

if(Boost_FOUND)
    include_directories(${Boost_INCLUDE_DIRS} ${BOOST_LOCKFREE_DIR})
    target_link_libraries(rts ${Boost_LIBRARIES})
endif()
