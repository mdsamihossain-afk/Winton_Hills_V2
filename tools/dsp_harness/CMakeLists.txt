cmake_minimum_required(VERSION 3.10)
project(WintonDspHarness)

set(CMAKE_CXX_STANDARD 17)

# Build the bridge and engine logic into a shared library
add_library(winton_dsp SHARED dsp_bridge.cpp)

# Ensure the compiler can find the headers
target_include_directories(winton_dsp PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/../../engine/src/main/cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/android_mock
)
