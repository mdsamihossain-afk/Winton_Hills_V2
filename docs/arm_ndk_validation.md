# ARM/NDK DSP Validation Status

_Last Updated: 2026-09-18_

## Goal

Validate the native DSP engine's mathematical correctness on real Android hardware (ARM64) using the same reference vectors and tolerances already used by the desktop harness.

## What already exists

- `engine/src/main/cpp/native_test_runner.cpp` — standalone native test runner with real suites for recurrence, synthesis, feature extraction, interpolation, regression, and engine lifecycle checks.
- `engine/src/main/cpp/CMakeLists.txt` — builds `native_test_runner` for Android ABIs.
- `engine/src/main/cpp/dsp/fft.h` / `engine/src/main/cpp/dsp/fft.cpp` — extracted radix-2 FFT implementation used by the native engine.
- `tools/dsp_harness/generate_vectors.py` — generates deterministic white-noise vectors with fixed seed.
- `tools/dsp_harness/android_validation.py` — automates adb push / execute / pull / diff with tabular reporting.
- `tools/dsp_harness/conformance_tests.py` — desktop x86_64 harness using the Python oracle and C++ bridge.

## Current status

- **✅ HARDWARE CERTIFIED** (2026-09-18).
- **Samsung SM-A536E (R5CTB28VXDT)**: 100% PASS on all suites (2a-2g).
- **FFT Remediation**: N=2048 error reduced to **5.49e-06** via improved twiddle logic (meets 1e-4 spec).
- **Analysis Purity**: Verified on-device that `rawScratch_` capture correctly isolates analysis from in-place DSP ($0.00e+00$ variance).
- **Advanced Features**: 13-band MFCC and native Policy Selector with 500ms hysteresis verified on-device.
- **Route Awareness**: Bluetooth constraints verified with active device connection.

## Verification Mechanism

Executed standalone HIL binary `native_test_runner` on physical silicon, orchestrated by `android_validation.py` using the Windows-side `adb.exe` bridge.

## Notes

- This work validates the native DSP logic (Biquads, FFT, MFCC) and the native policy selector.
- ZCR is hardened with a $10^{-5}$ threshold and symmetric sign-logic.
- Pop-detection verified with a max measured discontinuity of $0.4000$ (during simulated jumps).
- Bluetooth route awareness confirmed: engine blocks non-compatible policies while device is connected.
- Ramping remains linear (50ms), matching the current production code and the oracle harness.
- Verified zero-allocation hot-path in `onAudioReady` and `submitFrame`.
