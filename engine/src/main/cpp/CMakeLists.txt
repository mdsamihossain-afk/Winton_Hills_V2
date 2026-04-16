cmake_minimum_required(VERSION 3.22.1)

project("engine" LANGUAGES CXX)

# TODO(phase2-oboe): keep native stream migration guarded and opt-in.
# TODO(phase2-oboe): when native callback path is real, enforce no-allocation hot path rules.

add_library(${CMAKE_PROJECT_NAME} SHARED
    audio_engine.cpp
)

find_library(log_lib log)
find_library(aaudio_lib aaudio)

target_link_libraries(${CMAKE_PROJECT_NAME}
    android
    ${log_lib}
)

# AAudio is only available on API 26+
if(aaudio_lib)
    target_link_libraries(${CMAKE_PROJECT_NAME} ${aaudio_lib})
endif()

