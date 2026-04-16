# Audio Engine SDK Overview

_Last Updated: 2026-03-17_

Scope: `:engine` public API, analysis path, and Phase-1 readiness notes.

Canonical Phase-1 boundaries and completion estimates are maintained in:
- `README.md`
- `docs/phase1_validation.md`

## What's Changed (2026-03-17)

- Native runtime processing is now explicitly opt-in via `Engine.setNativeProcessingEnabled(...)`; Kotlin runtime remains default-safe.
- AutoEq alias matching now uses a prebuilt normalized alias index in `AutoEqProfileRepository` for conservative exact alias lookups.
- `AudioCaptureThread` now reuses FFT scratch buffers to reduce per-frame allocation pressure.
- App-side route restarts are debounced in foreground service to reduce rapid restart storms on route churn.
- App profile suggestions are dispatched off the main thread for smoother typing with large datasets.
- Native `cpp`/CMake have TODO scaffolding only for Phase-2 Oboe-like hardening (no aggressive runtime changes).

## What's Changed (2026-03-14)

- Kotlin runtime output path is active in `AudioCaptureThread` with processed/bypass A/B behavior.
- New DSP primitives are documented: `BiquadFilter` and `BiquadFilterChain`.
- AutoEq apply behavior is clarified: mapped PEAK bands are applied to Kotlin chain, with JNI payload forwarding when native is present.

> Current status: the public SDK API is stable in Kotlin (`Engine.kt`).
> Live mic analysis (RMS/centroid/classifier) runs in Kotlin via `AudioCaptureThread`.
> Kotlin runtime DSP now includes an `AudioTrack` output path with a biquad chain for A/B validation.
> JNI/native code remains wired but is still a stub.
> Native runtime processing is now guarded behind explicit opt-in.

## 1. Architecture

The engine is a self-contained Android library (`.aar`) with two components: a Kotlin public API and an optional JNI/native backend.

**Diagram:**

```
+----------------------------------------------------+
|                  Host Application                  |
| (e.g., 'wins_1' Validation Shell)                  |
+----------------------------------------------------+
| Calls                                              |
+----------------------------------------------------+
|              Engine.kt (Public API)                |
| - startAudioEngine(conservative: Boolean)          |
| - stopAudioEngine()                                |
| - setPolicy(policyId: Int)                         |
| - setAutoPolicy(enabled: Boolean)                  |
| - getSnapshot(): TelemetrySnapshot         ◄ prefer|
| - getAnalysisData(): FloatArray     (compat/JNI)   |
+----------------------------------------------------+
| Kotlin analysis path (default) + JNI bridge        |
+----------------------------------------------------+
|      AudioCaptureThread.kt (Live Analysis Core)    |
|                                                    |
|  [AudioRecord Input] -> [Analysis Thread] -> [Telemetry]
|                               |                    |
|                               |                    |
|         +---------------------+--------------------+
|         |                     |                    |
| [Int16->Float]         [FFT/Classifier]      [Policy Selector]
|         |                     | (FFT, RMS, etc.)   | (Hysteresis + route guards)
|         |                     |                    |
| [Policy IDs + Metrics] + [A/B Output: AudioTrack + BiquadFilterChain]
| (native output DSP still stubbed)
|                                                    |
+----------------------------------------------------+
```

- **Host Application**: Any Android app that includes the engine library.
- **Engine.kt**: Public-facing API and sole entry point used by the host app.
- **TelemetrySnapshot.kt**: Typed data class wrapping a single analysis frame; preferred over raw `FloatArray`.
- **AudioCaptureThread.kt**: Captures microphone PCM, computes features/classification, and writes bypass/processed output.
- **audio_engine.cpp**: JNI stub surface kept for native bring-up; currently not a full DSP/audio-output implementation.

### TelemetrySnapshot Fields

`TelemetrySnapshot` is the preferred way to consume engine output. Build one via `Engine.getSnapshot()` or `TelemetrySnapshot.fromFloatArray(data)`.

| Field                    | Type    | Description                                                         |
|--------------------------|---------|---------------------------------------------------------------------|
| `rms`                    | Float   | RMS level (0.0 – 1.0)                                               |
| `spectralCentroid`       | Float   | Normalised spectral centroid (0.0 = low-freq, 1.0 = Nyquist)        |
| `classificationId`       | Int     | Raw classifier output: 0=Silence, 1=Speech, 2=Music, 3=Mixed        |
| `classificationLabel`    | String  | Human-readable label derived from `classificationId`                |
| `confidence`             | Float   | Classifier confidence score (0.0 – 1.0)                             |
| `activePolicyId`         | Int     | Integer ID of the currently active processing policy                |
| `activePolicyName`       | String  | Human-readable policy name (e.g. "Speech Intelligibility")          |
| `analysisLatencyMs`      | Float   | EMA-smoothed analysis frame latency in ms; -1 if not yet available  |
| `startupLatencyMs`       | Float   | Engine-start to first valid frame in ms; -1 if not yet measured     |
| `theoreticalIoLatencyMs` | Float   | Buffer-based theoretical I/O latency in ms; -1 if not set           |
| `timestampMs`            | Long    | Wall-clock timestamp when the snapshot was produced (ms since epoch)|

---

## 2. SDK Overview

This SDK provides real-time audio analysis and processing for Android applications.

### Key Features:

- **Live Microphone Analysis**: Built on `AudioRecord` for real-time feature extraction.
- **Real-time Analysis**: Provides RMS loudness, spectral centroid, and a Speech/Music/Mixed classification with a confidence score.
- **Dynamic Processing Policies**: Switchable EQs for different scenarios (Speech Intelligibility, Neutral Correction).
- **Automatic Mode**: Intelligently switches policies based on the detected audio content, with hysteresis to prevent rapid switching.
- **Safe & Stable**: Includes conservative fallback modes for problematic hardware and route-aware policy guardrails.
- **AutoEq Runtime Lookup (Phase-1)**: Loads compact bundled JSON profiles, matches by normalized headphone name/alias, and falls back to neutral when no exact match exists.

### How to Integrate:

Choose one integration path depending on your setup:

1. **Same repository / multi-module app (recommended here)**
   Add module dependency in your app `build.gradle.kts`:
   `implementation(project(":engine"))`

2. **External app using a distributed AAR**
   Add the compiled `.aar` to your app's `libs` folder and use:
   `implementation(files("libs/engine.aar"))`

3. Instantiate the `Engine` class:
   `private val engine = Engine()`

4. Use the public API methods to control the engine (see `Engine.kt` above).

---

## 3. Limitations

- **FFT Implementation**: The current FFT is a pure-Kotlin radix-2 implementation suitable for validation; production may require optimized native DSP.
- **Classification Model**: The Speech/Music classifier is heuristic-based (using RMS and Spectral Centroid). It is effective in many cases but is not a machine-learning model. It may misclassify complex audio.
- **Native DSP Path**: JNI is present but still stubbed; no full native output-processing chain is active yet. Runtime JNI processing is opt-in to avoid accidental stub-path activation.
- **Monitoring Topology**: Kotlin processed output currently mirrors captured mic audio for validation; use headphones to avoid acoustic feedback loops.
- **AutoEq Filter Coverage**: Runtime mapper currently applies only `PEAK` filters to engine biquad bands; shelf filters are parsed but skipped safely.
- **Apply Matching**: `applyMatchedProfile()` uses conservative exact matching only (normalized name, then exact alias) to avoid accidental wrong-profile application. Typo-tolerant scoring is available in `suggestProfiles()` for typeahead only.
- **Shelf Filters**: AutoEq `LOW_SHELF` and `HIGH_SHELF` filter types are parsed but not yet mapped to engine biquad bands; only `PEAK` filters are applied in Phase-1.

---

## 4. AutoEq Integration (Phase-1)

The AutoEq flow is split into two stages:

1. **Offline desktop conversion** (`tools/autoeq_converter.py`)
   - Scans AutoEq repo recursively (supports real-world filenames like `{Model} ParametricEQ.txt`).
   - Extracts preamp + parametric filters.
   - Emits compact deterministic JSON for Android assets.
   - Bundled output: **6 017 profiles** (8 850 files scanned, 2 833 deduped).

2. **Runtime engine lookup** (`:engine`)
   - Loads `src/main/assets/autoeq_profiles.json` on first use via `AutoEqProfileRepository`.
   - Typeahead (`suggestProfiles`) uses tiered typo-tolerant scoring (7 tiers, see below).
   - Apply (`loadProfileByName`) uses conservative exact/alias matching only.
   - Alias exact matching is index-backed for deterministic lookup performance.
   - Maps `PEAK` filters to engine biquad band configs via `EqProfileMapper`.
   - Falls back to neutral when no match is found.

### Typeahead scoring tiers (suggestProfiles)

| Score | Match type                                              |
|-------|---------------------------------------------------------|
| 420   | Exact normalized match                                  |
| 320   | Target starts with full query string                    |
| 300   | Brand token starts with first query token               |
| 260   | All query tokens are prefixes of target tokens          |
| 235   | Brand token within 1 edit distance of first query token |
| 180   | Target contains full query string                       |
| 170   | All query tokens match (typo-tolerant, 1-edit distance) |

### Engine API additions

- `loadProfileByName(name: String): Boolean` — conservative exact match; returns true if found
- `applyMatchedProfile(): Boolean` — pushes loaded profile to engine; returns true if applied
- `clearMatchedProfile()` — resets to neutral
- `suggestProfiles(query: String, limit: Int = 8): List<String>` — tiered typo-tolerant typeahead
- `getProfileMatchTelemetry(): Map<String, String>` — match source, confidence, fallback flag

### App-shell behavior

- The validation UI exposes a manual `Headphone name` input + `Apply` action.
- While typing, a 150 ms debounced call to `suggestProfiles()` populates up to 6 tappable suggestion cards.
- After each apply attempt, UI shows `Last match` status (matched/unmatched + apply state).
- The same metadata is included in session logs under `AUTOEQ_MANUAL_APPLY`.

---

## 5. Current Reality Check

- `Engine.kt` is the public SDK surface used by `:app`.
- Live feature extraction/classification runs in Kotlin (`AudioCaptureThread`).
- Native (`audio_engine.cpp`) currently provides a JNI-compatible stub path only.
- Kotlin runtime remains the default processing path unless native processing is explicitly enabled.
- Policy switching is logical/config selection in Phase-1; full click-free DSP transitions are future work.
- `applyMatchedProfile()` now applies mapped PEAK bands to the Kotlin runtime chain when available; JNI payload is still sent when native backend is present.

---

## 6. Validation Checklist (Phase-1)

Use this checklist to verify the engine behavior from the host app (`:app`):

- Start engine and confirm analysis values update at runtime.
- Toggle A/B mode and confirm active policy + mode indicator changes.
- Change policies (Auto/Speech/Neutral) and confirm selector behavior is stable.
- Try manual profile apply from the app UI and confirm `Last match` status updates correctly.
- Change output route (speaker/wired/bluetooth when available) and confirm safe fallback behavior.
- Stop engine and export session logs from the app shell.
- Confirm exported telemetry includes latency fields and route/policy metadata.

Expected telemetry fields from a full session summary include:

- `AnalysisLatencySmoothedMs`, `AnalysisLatencyMinMs`, `AnalysisLatencyMaxMs`
- `StartupLatencyMs`, `TheoreticalIOLatencyMs`
- `BypassLatencyAvgMs`, `ProcessedLatencyAvgMs`, `AddedLatencyVsBypassMs`

---

## 7. File Inventory

| File                                                   | Purpose                                                              |
|--------------------------------------------------------|----------------------------------------------------------------------|
| `Engine.kt`                                            | Public API surface — start/stop, analysis, policy, latency           |
| `TelemetrySnapshot.kt`                                 | Typed analysis-frame snapshot; preferred over raw `FloatArray`       |
| `analysis/AudioCaptureThread.kt`                       | Background mic capture + radix-2 FFT + per-frame classification      |
| `analysis/BiquadFilter.kt`                             | Stateful peaking biquad primitive for Kotlin runtime DSP             |
| `analysis/BiquadFilterChain.kt`                        | Serial biquad chain built from policy/AutoEq band lists             |
| `analysis/FeatureExtractor.kt`                         | Stateless RMS, spectral centroid, Hann window, heuristic classifier  |
| `hardware/HardwareProfile.kt`                          | Data class: `RouteType`, `LatencyClass`, buffer/sample/conservative  |
| `hardware/HardwareResolver.kt`                         | Route detection + profile DB lookup + built-in fallback              |
| `hardware/ProfileDatabase.kt`                          | JSON asset loader + tiered device matching (4 tiers)                 |
| `autoeq/AutoEqModels.kt`                               | Runtime AutoEq data model (`AutoEqProfile`, filters, match result)   |
| `autoeq/AutoEqNameNormalizer.kt`                       | Shared normalization + stable ID helper                              |
| `autoeq/AutoEqProfileRepository.kt`                    | JSON-backed AutoEq profile repository loader                         |
| `autoeq/AutoEqProfileMatcher.kt`                       | Tiered typeahead scoring (7 tiers, typo-tolerant) + conservative exact apply |
| `autoeq/EqProfileMapper.kt`                            | Maps supported AutoEq PEQ PEAK filters to engine band configs        |
| `latency/LatencyTracker.kt`                            | 4-metric tracker (analysis EMA, startup, theoretical I/O, switch)    |
| `policy/PolicyConfig.kt`                               | Policy definitions: Passthrough, Speech, Neutral, Conservative       |
| `policy/PolicySelector.kt`                             | Auto-mode selector with hysteresis + route-compatibility guards      |
| `src/main/assets/hardware_profiles.json`               | Local device profile database (12 entries)                           |
| `src/main/assets/autoeq_profiles.json`                 | Compact bundled AutoEq runtime dataset (6 017 profiles, deduped)     |
| `src/main/cpp/audio_engine.cpp`                        | JNI stub — native bring-up surface only; no active DSP pipeline yet  |

---

## 8. Build & Publish (Local)

Build library module:

```powershell
Set-Location "C:\Users\scatt\Downloads\Winton_Hills_V2-master\Winton_Hills_V2-master"
cmd /c gradlew.bat :engine:assemble --console=plain
```

Publish `:engine` AAR to local Maven (SDK extraction dry-run):

```powershell
Set-Location "C:\Users\scatt\Downloads\Winton_Hills_V2-master\Winton_Hills_V2-master"
cmd /c gradlew.bat :engine:publishReleasePublicationToMavenLocal --console=plain
```

If Gradle cannot find Java, set `JAVA_HOME` first:

```powershell
$env:JAVA_HOME="C:\Path\To\JDK"
$env:Path="$env:JAVA_HOME\bin;$env:Path"
```

---

## 9. Kotlin DSP Runtime (Implemented in Kotlin path)

Real audible processing is implemented in the Kotlin runtime path.
This remains separate from JNI/native so the SDK can be validated while native DSP is stubbed.

1. **Filter primitives**
   - `analysis/BiquadFilter.kt` (stateful peaking biquad, Audio EQ Cookbook equations).
   - `analysis/BiquadFilterChain.kt` (ordered serial processing of multiple bands).

2. **Output path in capture thread**
   - `analysis/AudioCaptureThread.kt` includes an `AudioTrack` stream output.
   - Analysis path remains unchanged (RMS/FFT/classification from captured PCM).

3. **Policy -> chain wiring**
   - `Engine.setPolicy(...)` builds/updates the active filter chain from `PolicyConfig.bands`.
   - Conservative/passthrough policies map to an empty/null chain.

4. **A/B mode wiring**
   - `setBypassMode(...)` routes output mode so:
     - A mode writes raw captured PCM to output.
     - B mode writes chain-processed PCM to output.

5. **AutoEq apply wiring**
   - `loadProfileByName(...)` remains conservative (exact/alias).
   - `applyMatchedProfile()` rebuilds chain from mapped AutoEq PEAK bands.

### Verification targets

- Clear audible difference between A and B with `Speech Intelligibility` policy.
- AutoEq manual apply causes audible tonal change in B mode.
- Existing telemetry fields (`rms`, centroid, classifier, latency) remain stable.
- Session CSV format remains backward-compatible.

### Reference

- Audio EQ Cookbook (R. Bristow-Johnson):  
  `https://webaudio.github.io/Audio-EQ-Cookbook/audio-eq-cookbook.html`
