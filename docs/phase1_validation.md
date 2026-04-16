# Phase-1 Validation Scope

_Last Updated: 2026-03-17_

## Phase-1 validates

- live analysis
- policy selection
- AutoEq preset loading
- Kotlin DSP runtime path
- telemetry export

## Phase-1 does NOT guarantee

- production-grade global interception
- fully hardened native DSP
- artifact-free switching on all routes/devices
- exhaustive AutoEq name/alias correctness

## Completion estimate

### Before safe AutoEq runtime integration

- architecture design complete
- validation shell complete
- telemetry export complete
- AutoEq offline pipeline partial
- runtime preset loading partial
- DSP running in Kotlin
- native DSP not complete

Overall estimate: **~70-80%**

### After safe AutoEq runtime integration

- offline converter + runtime repository/matcher/mapper integrated
- conservative apply flow in place (`exact`/`alias`/`fallback`)

Overall estimate: **~85-90%**

## Recent hardening updates (safe/minimal diffs)

- `Engine` now gates native processing behind explicit opt-in, keeping Kotlin runtime as default-safe path.
- AutoEq alias matching uses repository-side normalized alias indexing (conservative exact alias semantics retained).
- FFT scratch buffers in `AudioCaptureThread` are reused to reduce hot-loop allocations.
- Route-change restart handling in `AudioEngineService` is debounced to avoid rapid restart storms.
- Profile suggestion lookup in `MainActivity` runs off the main thread for UI stability.
- Native `cpp`/CMake files gained TODO scaffolding only (no aggressive runtime behavior changes).

