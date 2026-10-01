cmake_minimum_required(VERSION 3.22.1)

project("engine" LANGUAGES CXX)

# TODO(phase2-oboe): keep native stream migration guarded and opt-in.
# TODO(phase2-oboe): when native callback path is real, enforce no-allocation hot path rules.

add_library(${CMAKE_PROJECT_NAME} SHARED
    audio_engine.cpp
    native_processing_engine.cpp
    dsp/biquad_filter.cpp
    dsp/biquad_cascade.cpp
    dsp/fft.cpp
    dsp/analysis.cpp
    policy/policy_selector.cpp
)

target_compile_features(${CMAKE_PROJECT_NAME} PRIVATE cxx_std_20)

find_library(log_lib log)
find_library(aaudio_lib aaudio)

find_package(oboe REQUIRED CONFIG)

target_link_libraries(${CMAKE_PROJECT_NAME}
    android
    ${log_lib}
    oboe::oboe
)

# Standalone test runner for ARM/NDK validation
# Note: native_test_runner.cpp includes the implementation files directly via #include,
# so we don't compile them as separate source files for this target
add_executable(native_test_runner native_test_runner.cpp)
target_link_libraries(native_test_runner
    android
    ${log_lib}
    oboe::oboe
)
target_compile_features(native_test_runner PRIVATE cxx_std_20)

# Unit tests for DSP core (biquad, cascade)
add_executable(dsp_unit_tests test/biquad_filter_test.cpp)
target_include_directories(dsp_unit_tests PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
target_compile_features(dsp_unit_tests PRIVATE cxx_std_20)

# AAudio is only available on API 26+
if(aaudio_lib)
    target_link_libraries(${CMAKE_PROJECT_NAME} ${aaudio_lib})
endif()

