# Architecture

## Module boundaries

### `:app` (validation shell)

Owns:
- UI shell and interaction flow
- foreground service lifecycle and permissions
- profile apply UX and status rendering
- session logging and CSV export

Does not own:
- DSP internals
- hardware profile matching logic
- classifier/policy implementation details

### `:engine` (SDK candidate)

Owns:
- hardware resolver and profile database lookup
- content analysis/classifier
- policy selector and active policy state (thread-safe with synchronized state updates)
- Kotlin DSP executor path (`AudioCaptureThread`, `BiquadFilter`, `BiquadFilterChain`)
  - auto-mode filter chain updates synchronized with classification changes
  - pop-free coefficient ramping (50ms configurable interpolation)
  - pre-allocated analysis result with lock-guarded snapshot mechanism
  - FFT scratch buffer reuse for reduced GC pressure
- native DSP modules (`dsp/biquad_filter.*`, `dsp/biquad_cascade.*`, `dsp/fft.*`)
- native engine core (`native_processing_engine.*`) and JNI boundary (`audio_engine.cpp`)
- AutoEq runtime repository, matcher, mapper
- telemetry snapshot generation and latency tracker

Native status:
- JNI entry points exist (`audio_engine.cpp`)
- native processing core is implemented (Biquads, cascade, FFT, analysis)
- Oboe/AAudio capture and stream lifecycle are fully implemented but **violate analysis purity** (currently performs analysis on processed samples)
- native runtime path is verified for algorithm correctness but needs a logic fix for Oboe frame submission order

### `tools/` (offline utilities)

Owns:
- AutoEq desktop conversion utilities
- DSP Correctness Harness (Python oracle + C++ bridge)
- dataset filtering and dedupe steps
- converter reporting/statistics

Does not run on Android runtime.

## Runtime ownership model

- UI asks service to start/stop, set policy, toggle A/B, apply profile.
- Service delegates engine actions and polls telemetry snapshots.
- Engine resolves route, captures audio, analyzes frames, applies policy/AutoEq chain.
- Telemetry and events are exported through app logger.

