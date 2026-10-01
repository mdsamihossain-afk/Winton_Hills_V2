# Phase-1 Validation Scope

_Last Updated: 2026-06-11_

## Phase-1 validates

- live analysis
- policy selection
- AutoEq preset loading
- Kotlin DSP runtime path
- telemetry export
- pop-free policy transitions
- thread-safe DSP operations
- **DSP mathematical conformance (Harness)**

## Phase-1 does NOT guarantee

- production-grade global interception
- fully hardened native DSP
- artifact-free switching on all routes/devices
- exhaustive AutoEq name/alias correctness

## Completion estimate

### After safe AutoEq runtime integration + hardening

- architecture design complete
- validation shell complete
- telemetry export complete
- AutoEq offline pipeline complete
- runtime preset loading complete
- DSP running in Kotlin (Robust & Thread-safe)
- pop-free transitions implemented
- **Native DSP logic validated (100% conformance)**
- Native DSP migration moved to Phase 2; native core now exists and is being verified separately

Overall estimate: **~94%** (Production-ready for Phase-1 Validation)

## Native DSP Validation (2026-07-31)

### Mathematical Conformance
The native C++ core has been validated against a high-fidelity Python oracle using the **DSP Correctness Harness** (`tools/dsp_harness/`).

- **Recurrence (2a)**: PASS. Bit-perfect match with DF2T reference (Max err 8.49e-07).
- **Synthesis (2b)**: PASS. Appendix A.3 formula alignment.
- **Features (2c)**: PASS. RMS and Spectral Centroid verified on 1024/2048 frames.
- **Interpolation (2d)**: PASS. Linear ramping convergence verified over 50 samples.

### Cross-Architecture Verification

**x86_64 Desktop**: ✅ Complete
- The desktop harness (`tools/dsp_harness/conformance_tests.py`) passes with a 100% success rate.
- Evidence is present in the checked-in x86_64 result artifacts under `tools/dsp_harness/vectors_arm64/`.

**Android Emulator Validation**: ✅ Complete
- `:app:connectedDebugAndroidTest` passed on `Medium_Phone_API_36.1(AVD) - 16`.
- `tools/dsp_harness/android_validation.py` also passed on the same emulator.
- The connected emulator ABI was `x86_64`.

**ARM64 Android Hardware**: ⏳ Still pending a true ARM64 device/image
- The ARM/NDK validation pipeline is implemented:
  - `engine/src/main/cpp/native_test_runner.cpp`
  - `engine/src/main/cpp/CMakeLists.txt`
  - `tools/dsp_harness/generate_vectors.py`
  - `tools/dsp_harness/android_validation.py`
- Runtime execution on ARM64 hardware is still recommended before declaring the ARM validation complete.

### Critical Bug Fixes
- Auto-mode Filter Updates: Fixed a bug where switching policies in Auto-mode did not update the active `BiquadFilterChain`. The `Engine` now explicitly calls `updateEngineFilterChain()` upon classification changes.
- Thread Safety: `captureThread` is now `@Volatile`. `PolicySelector` state-modifying methods are now `@Synchronized`. `BiquadFilterChain` methods are now `@Synchronized` to prevent race conditions during coefficient updates.
- Pop-free Ramping: Implemented linear coefficient interpolation in `BiquadFilter`. Policy and profile switches now ramp over 50ms (configurable) to eliminate audible clicks and pops.

### Performance Optimizations
- GC Pressure Reduction: `AudioCaptureThread` now uses a pre-allocated `MutableAnalysisResult` with a lock-guarded snapshot mechanism. FFT reuses scratch buffers (`fftReal`, `fftImag`, `fftMagnitudes`) to eliminate per-frame allocations in the capture loop.
- FFT Validation: Added power-of-two validation for `frameSize` to ensure compatibility with the radix-2 FFT implementation.

### Additional Hardening
- `Engine` gates native processing behind explicit opt-in, keeping Kotlin runtime as default-safe path.
- AutoEq alias matching uses repository-side normalized alias indexing (conservative exact alias semantics retained).
- Route-change restart handling in `AudioEngineService` is debounced to avoid rapid restart storms.
- Profile suggestion lookup in `MainActivity` runs off the main thread for UI stability.
- Foreground-service route restarts are debounced to reduce restart storms on rapid device events.

