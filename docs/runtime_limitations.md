# Runtime Limitations

_Last Updated: 2026-06-11_

Current known limitations in Phase-1:

- native DSP path is functionally stubbed but its **mathematical core is 100% validated**
- native runtime path is intentionally opt-in; Kotlin runtime remains the default-safe execution path in Phase-1
- Bluetooth routes may require conservative behavior for stability
- AutoEq alias coverage is incomplete for long-tail model naming variants
- AutoEq apply is conservative (`exact normalized` then `exact alias`); suggestions can be broader but do not override apply safety rules
- route-change restart handling is debounced, but vendor/device route churn can still cause transient interruptions
- latency metrics can include estimated values when exact runtime measurements are unavailable

## Recent hardening (2026-06-11)

The following issues have been addressed:

- ✅ policy transitions now use pop-free ramping (50ms configurable interpolation) to eliminate artifacts
- ✅ thread safety: `captureThread` is now `@Volatile`, `PolicySelector` and `BiquadFilterChain` methods are `@Synchronized`
- ✅ auto-mode filter updates: `Engine` now correctly updates filter chain when classification changes
- ✅ GC pressure: pre-allocated `MutableAnalysisResult` and FFT scratch buffer reuse reduce allocation pressure
- ✅ FFT validation: power-of-two frame size validation ensures radix-2 FFT compatibility
- ✅ **Mathematical Conformance**: Native logic matches Python oracle references on x86_64; Android emulator validation is passing, and the ARM/NDK runner is ready for ARM64 hardware verification

Remaining limitations (non-blocking for Phase-1):
- exhaustive hardware route testing across all vendor implementations
- production-grade native C++/AAudio path implementation

