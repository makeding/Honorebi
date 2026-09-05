cmake_minimum_required(VERSION 3.16)
project(komorebi-tsreadex-tests LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 14)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_executable(
    tsreadex_pipeline_test
    tsreadex_pipeline_test.cpp
    ../util.cpp
    ../servicefilter.cpp
    ../id3conv.cpp
    ../aac.cpp
    ../huffman.cpp
)
target_include_directories(tsreadex_pipeline_test PRIVATE ..)

enable_testing()
add_test(NAME tsreadex_pipeline_test COMMAND tsreadex_pipeline_test)
