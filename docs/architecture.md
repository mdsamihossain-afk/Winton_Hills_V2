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
- policy selector and active policy state
- Kotlin DSP executor path (`AudioCaptureThread`, `BiquadFilter`, `BiquadFilterChain`)
- AutoEq runtime repository, matcher, mapper
- telemetry snapshot generation and latency tracker

Native status:
- JNI entry points exist (`audio_engine.cpp`)
- native processing chain is still a stub in Phase-1

### `tools/` (offline utilities)

Owns:
- AutoEq desktop conversion utilities
- dataset filtering and dedupe steps
- converter reporting/statistics

Does not run on Android runtime.

## Runtime ownership model

- UI asks service to start/stop, set policy, toggle A/B, apply profile.
- Service delegates engine actions and polls telemetry snapshots.
- Engine resolves route, captures audio, analyzes frames, applies policy/AutoEq chain.
- Telemetry and events are exported through app logger.

