cmake_minimum_required(VERSION 3.22.1)

project("engine")

add_library(${CMAKE_PROJECT_NAME} SHARED
    # List C/C++ source files with relative paths to this CMakeLists.txt.
    audio_engine.cpp
)

find_library(aaudio-lib aaudio)

target_link_libraries(${CMAKE_PROJECT_NAME}
    # List libraries to link with the target library
    ${aaudio-lib}
    android
)
