cmake_minimum_required(VERSION 2.8.11)
cmake_policy(SET CMP0048 NEW)
project(THC VERSION 0.1 LANGUAGES CXX)

# make it easier to add options
macro(add_option var default type docstring)
    if(NOT DEFINED ${var})
        set(${var} ${default})
    endif()
    set(${var} ${${var}} CACHE ${type} ${docstring} FORCE)
endmacro()

# C++11
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

# boost
set(Boost_USE_STATIC_LIBS OFF)
set(Boost_USE_MULTITHREADED ON)
set(Boost_USE_STATIC_RUNTIME OFF)
find_package(Boost 1.45.0 REQUIRED COMPONENTS thread)
include_directories(${Boost_INCLUDE_DIRS})

link_libraries(${Boost_LIBRARIES})
include_directories(${CMAKE_SOURCE_DIR})

# doxygen

# add an option for building the API documentation
add_option(THC_BUILD_DOCUMENTATION TRUE BOOL "Set to TRUE to generate the API documentation. Set to FALSE to skip")

if (THC_BUILD_DOCUMENTATION)
  find_package(Doxygen)
  if (DOXYGEN_FOUND)
    configure_file(${CMAKE_SOURCE_DIR}/Doxyfile.in ${CMAKE_BINARY_DIR}/Doxyfile @ONLY)
    add_custom_target(doc ALL ${DOXYGEN_EXECUTABLE} ${CMAKE_BINARY_DIR}/Doxyfile
      WORKING_DIRECTORY ${CMAKE_BINARY_DIR}
      COMMENT "Generating API documentation with Doxygen"
      VERBATIM)
  endif(DOXYGEN_FOUND)
endif(THC_BUILD_DOCUMENTATION)

# directories
add_subdirectory(rts)
add_subdirectory(tests)
