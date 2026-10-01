# Winton Hills V2 SDK Overview

_Last Updated: 2026-09-11_

## Introduction
The Winton Hills Audio Engine is a high-performance Android library designed for real-time audio analysis and processing. It provides a policy-driven framework to enhance audio based on content classification and hardware characteristics.

## Key Features (Phase-1)

- **Real-time Audio Analysis**: Live RMS, spectral centroid, and Speech/Music/Mixed classification with confidence scoring
- **Pop-free Policy Transitions**: Smooth coefficient ramping (50ms configurable) eliminates audible clicks and pops during policy switches
- **Thread-safe DSP**: Synchronized state management ensures stable multi-threaded operation
- **AutoEq Runtime Integration**: Conservative exact-match profile loading with fallback to neutral EQ
- **A/B Bypass Mode**: Compare processed vs. unprocessed audio in real-time
- **Hardware-aware Processing**: Automatic device routing detection with optimized profiles
- **Mathematical Validation**: Validated against a high-fidelity Python oracle for formula conformance

## Validation & Trust
The core DSP logic in the native engine has been cross-validated using the **DSP Correctness Harness** (`tools/dsp_harness/`). This ensures that the C++ implementation matches the mathematical specifications with bit-perfect accuracy on both host and target architectures.

> [!WARNING]
> **Partially Certified**: Validation has been performed on **ARM64 silicon** (Samsung SM-A536E). Results show 100% logic correctness for recurrence and ramping, but identify a deterministic precision divergence in FFT magnitude at N=2048 and a defect in the Oboe analysis capture sequence.

### 1. Engine (`com.winton.validationshell.engine.Engine`)
The main entry point for the SDK. It manages the lifecycle of the audio capture thread, hardware resolution, and policy application. Thread-safe lifecycle management with volatile references.

### 2. Policy Selector (`com.winton.validationshell.engine.policy.PolicySelector`)
Handles automatic switching between processing modes (e.g., Speech Intelligibility, Neutral Correction) based on real-time classification and confidence levels. State-modifying methods are synchronized to prevent race conditions.

### 3. Hardware Resolver (`com.winton.validationshell.engine.hardware.HardwareResolver`)
Identifies the output device (Speaker, Wired, Bluetooth) and maps it to specific hardware profiles for optimized processing.

### 4. BiquadFilterChain (`com.winton.validationshell.engine.analysis.BiquadFilterChain`)
Implements pop-free coefficient ramping for smooth DSP transitions. Supports policy and AutoEq profile application with configurable ramp durations.

## Integration Guide

### Initialization
```kotlin
val engine = Engine()
engine.initWithContext(applicationContext)
```

### Starting the Engine
```kotlin
val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
val profile = engine.resolveHardware(devices)
engine.startAudioEngine(profile.forceConservative)
```

### Polling Telemetry
```kotlin
val snapshot = engine.getSnapshot()
println("RMS: ${snapshot.rms}, Classification: ${snapshot.classificationLabel}")
```

### Changing Policies
```kotlin
engine.setAutoPolicy(true) // Enable automatic switching (uses pop-free ramping)
// OR
engine.setPolicy(PolicyConfig.SPEECH_INTELLIGIBILITY.id) // Manual override (pop-free)
```

### Applying AutoEq Profiles
```kotlin
engine.loadProfileByName("Sony WH-1000XM4") // Conservative exact/alias match
if (engine.applyMatchedProfile()) {
    // Profile applied with smooth pop-free transitions
}
```

## Best Practices
- **Foreground Service**: Always run the engine inside a Foreground Service to prevent the system from killing the audio process.
- **Permissions**: Ensure `RECORD_AUDIO` is granted before starting the engine.
- **Thread Safety**: The engine handles internal synchronization (volatile captureThread, synchronized state methods). UI updates should still be dispatched to the main thread.
- **Pop-free Transitions**: Policy and profile switches are automatically ramped over 50ms for smooth audio without artifacts.
- **Use Headphones**: During validation, use headphones to avoid acoustic feedback when testing processed mode.
