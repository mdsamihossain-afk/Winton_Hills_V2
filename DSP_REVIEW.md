# DSP Engine Review & Hardening Log (2026-09-11)

## Overview
This document tracks critical bug fixes, performance optimizations, and documentation updates for the Phase-1 Kotlin DSP pipeline.

## Critical Bug Fixes
- **Auto-mode Filter Updates:** Fixed a bug where switching policies in Auto-mode did not update the active `BiquadFilterChain`. The `Engine` now explicitly calls `updateEngineFilterChain()` upon classification changes.
- **Thread Safety:**
    - `captureThread` in `Engine` is now `@Volatile`.
    - `PolicySelector` state-modifying methods are now `@Synchronized`.
    - `BiquadFilterChain` methods are now `@Synchronized` to prevent race conditions during coefficient updates.
- **Pop-free Ramping:** Implemented linear coefficient interpolation in `BiquadFilter`. Policy and profile switches now ramp over 50ms (configurable) to eliminate audible clicks and pops.

## Performance Optimizations
- **GC Pressure Reduction:**
    - `AudioCaptureThread` now uses a pre-allocated `MutableAnalysisResult` with a lock-guarded snapshot mechanism.
    - FFT reuses scratch buffers (`fftReal`, `fftImag`, `fftMagnitudes`) to eliminate per-frame allocations in the capture loop.
- **FFT Validation:** Added power-of-two validation for `frameSize` to ensure compatibility with the radix-2 FFT implementation.

## Implementation Details (Ramping)
- `BiquadFilter`: Added `updateCoefficients(config, rampDurationMs)` which calculates per-sample steps for `b0, b1, b2, a1, a2`.
- `BiquadFilterChain`: Added `updateBands(newBands, rampDurationMs)`.
    - Matches new bands to existing filters.
    - Ramps extra filters to neutral (0dB) instead of dropping them instantly.
    - Adds new filters starting from neutral and ramping to target.

## Status
- **Phase-1 Kotlin Path:** 100% logic complete, 95% hardened.
- **Native Path:** ⚠️ PARTIALLY CERTIFIED. Native DSP core and capture (Oboe) are implemented. Hardware validation on ARM64 revealed an FFT-magnitude divergence at N=2048 and a design defect in the Oboe analysis capture order.
- **Documentation:** README and verification docs updated to reflect current defects and precision limits.

## Next Steps
1. Implement native MFCC for parity with the reference oracle.
2. Port policy selection logic to C++.
3. Perform performance benchmarking (Kotlin vs Native speedup).
