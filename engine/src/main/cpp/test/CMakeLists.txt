cmake_minimum_required(VERSION 3.10)
project(WintonNativeTests)

set(CMAKE_CXX_STANDARD 20)

# Mock Android logging
add_library(android_mock INTERFACE)
target_include_directories(android_mock INTERFACE ${CMAKE_CURRENT_SOURCE_DIR}/android_mock)

# Mock Oboe
add_library(oboe_mock INTERFACE)
target_include_directories(oboe_mock INTERFACE ${CMAKE_CURRENT_SOURCE_DIR}/oboe_mock)

# Note: test files include .cpp files directly to access private members,
# so we don't list implementation files as separate sources to avoid duplicate symbols.

# 1. DSP Unit Tests
add_executable(dsp_unit_tests biquad_filter_test.cpp)
target_include_directories(dsp_unit_tests PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/..
    ${CMAKE_CURRENT_SOURCE_DIR}/../external/eigen
)

# 2. Precision Audit
add_executable(fft_precision_audit fft_precision_audit.cpp)
target_include_directories(fft_precision_audit PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/..
    ${CMAKE_CURRENT_SOURCE_DIR}/../external/eigen
)
target_compile_options(fft_precision_audit PRIVATE -O2 -ffp-contract=off)

# 3. Oboe Regression (Analysis Purity)
add_executable(oboe_regression_test oboe_regression_test.cpp)
target_include_directories(oboe_regression_test PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/..
    ${CMAKE_CURRENT_SOURCE_DIR}/../external/eigen
)
target_link_libraries(oboe_regression_test android_mock oboe_mock)

# 4. Feature Extraction (ZCR)
add_executable(feature_extraction_test feature_extraction_test.cpp)
target_include_directories(feature_extraction_test PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/..
    ${CMAKE_CURRENT_SOURCE_DIR}/../external/eigen
)
target_link_libraries(feature_extraction_test android_mock oboe_mock)

# 5. LM Fitter (Toy Case)
add_executable(lm_test lm_test.cpp)
target_include_directories(lm_test PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/..
    ${CMAKE_CURRENT_SOURCE_DIR}/../external/eigen
)

# 6. Policy Selector
add_executable(policy_test policy_test.cpp)
target_include_directories(policy_test PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/..)
