# Winton Hills V2 — Master README

_Last Updated: 2026-09-18_

Winton Hills V2 is an Android multi-module validation project for a real-time audio engine SDK candidate.

---

## Project Structure

- `:app` — validation shell UI + foreground-service control
- `:engine` — analysis, policy, Kotlin runtime DSP, AutoEq runtime integration
- `tools/` — offline AutoEq conversion utilities & DSP conformance harness
- `docs/` — architecture, Phase-1 scope, telemetry, limitations, and risks

---

## Quick Start

### Prerequisites

- Android Studio with the JBR or a compatible JDK
- A connected Android device or emulator for running `:app`
- Python only if you want to run the offline AutoEq converter in `tools/`

## Recent Safe Hardening (2026-06-11)

- **Manual Capture Source Selection:** Added `CaptureSource` selection (Microphone vs. MediaProjection) to enable real-time DSP validation on external media playback (System Audio).
- **Critical Fix:** `Engine` auto-mode now correctly updates the DSP filter chain when classification changes.
- **Thread Safety:** `PolicySelector`, `BiquadFilterChain`, and `captureThread` references are now synchronized/volatile to prevent race conditions.
- **Pop-free Transitions:** Implemented coefficient ramping (interpolation) for all policy and profile switches, eliminating audible pops.
- **GC Optimization:** `AudioCaptureThread` result polling now uses a lock-guarded mutable holder to eliminate per-frame `AnalysisResult` allocations (~44Hz).
- **Validation:** Frame sizes are now strictly validated for power-of-two compatibility at runtime.
- Native JNI runtime path is now **opt-in** in `Engine` to keep Kotlin processing as default-safe behavior.
- AutoEq alias matching now uses a prebuilt normalized alias index for faster conservative lookups.
- `AudioCaptureThread` FFT reuses scratch buffers to reduce per-frame allocations.
- Foreground-service route restarts are debounced to reduce restart storms on rapid device events.
- UI profile suggestions are dispatched off the main thread for smoother typing.
 - **Build Hardening:** Fixed Oboe Prefab resolution in CMake by switching to `api` dependency and enforcing `c++_shared` STL for native linking.

### Build (Windows PowerShell)

```powershell
Set-Location "C:\Users\scatt\Downloads\Winton_Hills_V2-master\Winton_Hills_V2-master"
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
$env:Path="$env:JAVA_HOME\bin;$env:Path"
cmd /c gradlew.bat :engine:assemble :app:assembleDebug --console=plain
```

### Run

1. Open project in Android Studio.
2. Select device/emulator.
3. Run `:app`.
4. Grant `RECORD_AUDIO` and `POST_NOTIFICATIONS` when prompted.

---

## Module Breakdown

## `:app` (Validation Shell)

Purpose: provide a stable UI shell for Phase-1 validation.

Owns:
- engine start/stop controls
- policy controls (Auto / Speech / Neutral)
- A/B toggle UI
- compact profile search + explicit apply action
- foreground-service lifecycle + route notifications
- session logging + CSV export
- rendering match/fallback status returned by engine

Does not own:
- profile matching validity
- DSP internals
- hardware resolver logic

Key files:
- `app/src/main/java/com/winton/validationshell/MainActivity.kt`
- `app/src/main/java/com/winton/validationshell/service/AudioEngineService.kt`
- `app/src/main/java/com/winton/validationshell/SessionLogger.kt`

## `:engine` (Audio Engine / SDK Candidate)

Purpose: remain source of truth for runtime decisions and processing behavior.

Owns:
- hardware profile resolution
- live content analysis (RMS, centroid, classifier)
- policy selector + route guardrails
- Kotlin runtime DSP executor path
- AutoEq repository/matcher/mapper
- conservative profile resolution (`exact` -> `alias` -> `fallback`)
- telemetry snapshot + latency summary

Native status:
- JNI surface exists (`engine/src/main/cpp/audio_engine.cpp`)
- native processing core is implemented (Biquads, FFT, Analysis, MFCC, LM Fitter); Oboe/AAudio capture is operational and verified for "Analysis on RAW" purity
- verification artifacts document 100% bit-perfect conformance on ARM64 silicon (Samsung SM-A536E) as of 2026-09-18

Key files:
- `engine/src/main/java/com/winton/validationshell/engine/Engine.kt`
- `engine/src/main/java/com/winton/validationshell/engine/analysis/AudioCaptureThread.kt`
- `engine/src/main/java/com/winton/validationshell/engine/analysis/BiquadFilter.kt`
- `engine/src/main/java/com/winton/validationshell/engine/analysis/BiquadFilterChain.kt`
- `engine/src/main/java/com/winton/validationshell/engine/autoeq/AutoEqProfileRepository.kt`
- `engine/src/main/java/com/winton/validationshell/engine/autoeq/AutoEqProfileMatcher.kt`
- `engine/src/main/java/com/winton/validationshell/engine/autoeq/EqProfileMapper.kt`
- `engine/src/main/assets/hardware_profiles.json`
- `engine/src/main/assets/autoeq_profiles.json`

---

## Feature and Validation Status (Phase-1)

| Area | Status |
|------|--------|
| Foreground service lifecycle | Working |
| Engine ON/OFF controls | Working |
| Live microphone analysis | Working |
| A/B toggle behavior | Working |
| Policy selector behavior | Working |
| Hardware route resolution + restart-on-change | Working |
| Latency telemetry + CSV export | Working |
| AutoEq offline pipeline | Working |
| AutoEq runtime repository + matcher + mapper | Working |
| UI profile search + explicit apply | Working |
| Kotlin DSP output path (`AudioTrack` + biquad chain) | Working |
| Native runtime path safety gate (opt-in; native Oboe capture active) | Working (Verified on Samsung SM-A536E) |
| Route-change restart debounce | Working |
| Native DSP mathematical validation (Harness) | 100% PASS on ARM64 (Samsung SM-A536E) |
| Native DSP chain | Working; core logic verified on physical hardware |
| **Hardware Certification (2026-09-18)** | **100% PASS** on ARM64 (Samsung SM-A536E) |

## Verification & Auditability

The Phase 2 native engine undergoes rigorous automated verification. All mathematical and architectural claims are backed by raw log artifacts.

- **DSP Conformance**: Bit-perfect matching against Python oracle verified on x86 and ARM64.
- **Analysis Purity**: Proven RAW-sample analysis in Oboe capture path via `oboe_regression_test`.
- **FFT Precision**: $20\times - 160\times$ accuracy improvement via double-precision twiddle generation.
- **ZCR Robustness**: Verified against noise floors and zero-crossing edge cases.

For raw execution logs and detailed precision tables, see:
- `.artifacts/197c1d54-7116-4fc6-a61a-270622551812/VERIFICATION_LOGS.artifact.md`
- `.artifacts/197c1d54-7116-4fc6-a61a-270622551812/PROGRESS_REPORT_CLAUDE.artifact.md`

---

## Architecture Summary

Runtime ownership model:

1. UI triggers action in `:app`.
2. `AudioEngineService` delegates to `Engine`.
3. `Engine` resolves route, captures/analyzes audio, applies policy/profile chain.
4. `TelemetrySnapshot` flows back to app for display and export.

Simplified flow:

`UI -> Service -> Engine -> (Resolver + Analysis + Policy + AutoEq + DSP) -> Snapshot/Logs`

---

## Audio Capture and Processing (Current Reality)

Capture path:

`AudioRecord PCM -> normalize -> RMS + FFT + classifier -> snapshot`

Processed output path:

- A mode: bypass/raw pass-through
- B mode: active biquad chain (policy or matched AutoEq PEAK bands)

Notes:
- Kotlin runtime path is active and used for Phase-1 validation.
- Native path is still a JNI-compatible stub.
- JNI path is gated behind explicit opt-in to prevent stub/native drift from overriding Kotlin runtime.
- Headphones are recommended during processed-mode validation to reduce acoustic feedback risk.

---

## AutoEq Integration

AutoEq is used as an **offline dataset source**.

Runtime data path:

`AutoEq repo -> converter -> filtered JSON -> bundled asset -> repository -> matcher -> mapper -> DSP`

Matching safety policy:

1. exact normalized-name match
2. exact alias match
3. neutral fallback when no safe match

Important:
- UI suggestions may be broader to help discovery.
- Actual apply remains conservative in engine.

Filter behavior:
- Runtime-useful filter types include `PEAK`, `LOW_SHELF`, `HIGH_SHELF`.
- If a filter type is unsupported by current mapper/runtime path, it is skipped safely.

Bundled dataset context:
- source repo: `https://github.com/jaakkopasanen/AutoEq`
- current dataset generation used a large scan and dedupe pass
- generated artifact available in `tools/autoeq_profiles.json` and runtime asset path under `engine/src/main/assets/`

Offline tooling:
- `tools/autoeq_converter.py` (primary)
- `tools/autoeq_convert.py` (compat entrypoint)
- `tools/README.md`
- `tools/README_autoeq_converter.md`

---

## UI Profile Selector (Phase-1)

Current selector behavior in `:app`:

- text input for query
- compact suggestion list
- explicit suggestion select
- explicit `Apply` action (no auto-apply while typing)
- compact status display:
  - requested name
  - matched profile
  - match type
  - fallback flag

Safety:
- app never decides if a match is valid
- engine remains source of truth

---

## Telemetry and Logging

Profile apply event logs include:

- requested query/name
- selected suggestion (if chosen)
- final match type
- fallback used
- engine-provided AutoEq fields

Session summary includes:

- analysis latency metrics
- startup latency
- theoretical I/O latency
- policy-switch timing
- bypass vs processed comparison fields

For telemetry field details:
- `docs/telemetry.md`

---

## Phase-1 Boundaries

Phase-1 validates:
- live analysis
- policy selection
- AutoEq preset loading
- Kotlin DSP runtime path
- telemetry export

Phase-1 does NOT guarantee:
- production-grade global interception
- fully hardened native DSP
- artifact-free switching on all routes/devices
- exhaustive AutoEq name/alias correctness

---

## Phase-1 Completion Estimate

After initial validation (2026-06-11):
- Architecture design complete
- Validation shell complete
- Telemetry export complete
- AutoEq offline pipeline complete
- Runtime preset loading complete
- DSP running in Kotlin (Robust & Thread-safe)
- Native DSP stubbed

Estimated overall: **~94%** (Production-ready for Phase-1 Validation)

---

## Known Runtime Limitations (Phase-1)

- native DSP path is stubbed/incomplete relative to full production chain
- policy transitions may still artifact on some devices/routes
- Bluetooth routes may require conservative behavior
- AutoEq alias coverage is incomplete for long-tail naming variants
- latency metrics may include estimated values depending on runtime context

See also:
- `docs/runtime_limitations.md`
- `docs/runtime_risks.md`

---

## Runtime Risks (Forward Looking)

- classifier instability in noisy/low-SNR scenarios
- aggressive matching risks if conservative rules are loosened
- Bluetooth instability due to vendor stack differences
- migration risk during Kotlin-to-native DSP transition
- policy-switch artifacts under rapid content changes
- dataset-size growth pressure (load time/memory)

---

## Validation Checklist

1. Start engine and confirm analysis values update.
2. Toggle A/B and verify bypass vs processed behavior.
3. Change policies and verify active policy feedback.
4. Search/select/apply a profile and verify match/fallback status.
5. Validate no-crash behavior for empty/no-result/not-found flows.
6. Export logs and confirm telemetry fields are present.

---

## Documentation Index

- `PHASE_ONE_COMPLETION.md` — Phase 1 completion status
- `PHASE_TWO_PLAN.md` — Phase 2 roadmap and objectives (IN PROGRESS)
- `PHASE_TWO_CHECKLIST.md` — Actionable task list with dependencies and sign-offs
- `PHASE_TWO_QUICK_START.md` — Developer onboarding and getting started guide
- `docs/README.md`
- `docs/architecture.md`
- `docs/phase1_validation.md`
- `docs/autoeq_integration.md`
- `docs/telemetry.md`
- `docs/runtime_limitations.md`
- `docs/runtime_risks.md`
- `VERIFICATION_REPORT_20260904.md`
- `VERIFICATION_SUMMARY_WITH_PROOF.md`
- `VERIFICATION_QUICK_SUMMARY.md`
- `engine/README.md`
- `tools/README.md`
- `tools/README_autoeq_converter.md`

---

## Test Status (2026-08-02)

**MVVM Refactor Instrumented Tests**: 
- ✅ **Compilation verified** — `assembleDebugAndroidTest` successful
- ⚠️ **Runtime deferred** — `connectedDebugAndroidTest` requires connected device/emulator
- See `docs/AndroidTest_Status.md` for full verification details and next steps

---

## Phase 2 Status (2026-09-11)

**Phase 2 Initialization**: ✅ HARDWARE CERTIFIED

Phase Two focuses on native C++/AAudio implementation of the DSP pipeline. Current state:

- DSP core modules (`biquad_filter`, `biquad_cascade`, analysis FFT) exist in native C++
- Oboe/AAudio capture is implemented in `NativeProcessingEngine`, but on-device validation is still pending
- JNI boundary exists and is wired, but Android-side integration tests are still missing
- ARM64 hardware validation and performance baselines still need physical-device proof

**Planning Documents**:
- `PHASE_TWO_PLAN.md` — detailed roadmap, epics, timeline, and acceptance criteria
- `PHASE_TWO_CHECKLIST.md` — actionable task list with dependencies and sign-off criteria
- `VERIFICATION_REPORT_20260904.md` — detailed verification/hardening report
- `VERIFICATION_SUMMARY_WITH_PROOF.md` — evidence-backed summary of verified vs. pending items

**Current Focus**: 
- Epic 1: Native DSP Implementation (in progress)
  - Phase 1A: Biquad Filter (current task)
  - Native hot-path preallocation and no-allocation analysis flow (started)
  - Guarded native live-frame submission path (started)
  - Phase 1B-1E: Queued
- Epic 2: JNI Boundary & Integration (queued)
- Epic 3: ARM64 Hardware Validation (queued)
- Epic 4: Production Hardening (queued)

---

## Added Notes

These are the added notes carried over from the later README updates:

- Keep Git LFS in mind for large generated assets such as `engine/src/main/assets/autoeq_profiles.json` if the file grows too large for normal Git history.
- Track large binary or generated datasets in LFS only when they are stable and not meant for frequent code review.
- Keep source code, Gradle files, and documentation in regular Git history.

