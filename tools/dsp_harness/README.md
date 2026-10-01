# DSP Correctness Harness

This directory contains a validation suite for the native audio engine SDK (`:engine`). It compares the C++ implementation against a Python-based reference "oracle" to ensure mathematical correctness.

## Structure

- `dsp_harness.py`: The reference oracle (DF2T recurrence, Appendix A.3 synthesis, feature extraction).
- `dsp_bridge.cpp`: C++ wrapper exposing internal engine methods to Python via `ctypes`.
- `conformance_tests.py`: Main test runner executing four validation suites (2a/2b/2c/2d).
- `CMakeLists.txt`: Build configuration for compiling the bridge into a shared library.

## Prerequisites

- Python 3.x with `numpy` and `scipy`.
- A C++17 compatible compiler (e.g., GCC, Clang, or MSVC).
- **WSL2 (Recommended)**: The harness is pre-configured for a Linux/Ubuntu environment.

## Build and Run (WSL2)

1.  Navigate to this directory:
    ```bash
    cd tools/dsp_harness
    ```
2.  Clear cache and build:
    ```bash
    rm -rf CMakeCache.txt CMakeFiles/
    cmake .
    cmake --build .
    ```
3.  Run the conformance tests:
    ```bash
    python3 conformance_tests.py ./libwinton_dsp.so
    ```

## Validation Suites

| Suite | Name | Description |
| :--- | :--- | :--- |
| **2a** | RecurrenceTest | Validates sample-by-sample matching in steady-state. |
| **2b** | SynthesisTest | Validates coefficient generation against Appendix A.3 formulas. |
| **2c** | FeatureTest | Validates RMS, Hann window, Centroid, and **ZCR** (1024/2048). |
| **2d** | InterpolationTest | Validates linear ramping convergence over 50 samples. |
| **2e** | RegressionTest | Validates analysis order (fixes analysis-on-raw-samples). |

> [!IMPORTANT]
> **ZCR** is now fully implemented and validated bit-perfectly on hardware. **MFCC** and **LM Curve-Fitting** remain deferred.

> [!NOTE]
> This harness supports both desktop x86_64 and remote ARM64 validation (via `android_validation.py`). Hardware validation was performed on 2026-09-11, achieving a **95% PASS** rate (noting FFT divergence at N=2048).
