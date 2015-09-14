cmake_minimum_required(VERSION 2.8.11)
cmake_policy(SET CMP0048 NEW)
project(THC VERSION 0.1 LANGUAGES CXX)

add_subdirectory(rts)
add_subdirectory(tests)
