# Audio Engine SDK Overview

## 1. Architecture

The engine is a self-contained Android library (`.aar`) with two main components: a Kotlin/Java public API and a native C++ core for all real-time audio processing.

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
| - getAnalysisData(): FloatArray?                   |
+----------------------------------------------------+
| JNI Bridge                                         |
+----------------------------------------------------+
|           audio_engine.cpp (Native Core)           |
|                                                    |
|  [AAudio Input Stream] -> [Data Callback] -> [AAudio Output Stream]
|                               |                    |
|                               |                    |
|         +---------------------+--------------------+
|         |                     |                    |
| [Int16->Float]         [Analysis Module]     [Policy Module]
|         |                     | (FFT, RMS, etc.)   | (Crossfading)
|         |                     |                    |
| [Float->Int16] <--- [Process Policy A] <--- [Process Policy B]
|                                                    |
+----------------------------------------------------+
```

- **Host Application**: Any Android app that includes the engine library.
- **Engine.kt**: The public-facing API. It's the sole entry point for the host app. It uses JNI to communicate with the native layer.
- **audio_engine.cpp**: The C++ core where all processing occurs.
    - **AAudio Streams**: Manages real-time, low-latency audio input and output.
    - **Data Callback**: The heart of the engine. It runs on a dedicated, high-priority audio thread.
    - **Analysis Module**: Calculates RMS, Spectral Centroid, and classifies the audio signal (Speech/Music).
    - **Policy Module**: Applies audio processing based on the selected policy (e.g., Speech EQ). It includes a crossfader for smooth, artifact-free transitions between policies.

---

## 2. SDK Overview

This SDK provides real-time audio analysis and processing for Android applications.

### Key Features:

- **Low-Latency Audio I/O**: Built on AAudio for high-performance passthrough.
- **Real-time Analysis**: Provides RMS loudness, spectral centroid, and a Speech/Music/Mixed classification with a confidence score.
- **Dynamic Processing Policies**: Switchable EQs for different scenarios (Speech Intelligibility, Neutral Correction).
- **Automatic Mode**: Intelligently switches policies based on the detected audio content, with hysteresis to prevent rapid switching.
- **Safe & Stable**: Includes conservative fallback modes for problematic hardware and seamless crossfading to prevent audio artifacts.

### How to Integrate:

1.  Add the compiled `.aar` to your app's `libs` folder.
2.  Add it as a dependency in your `build.gradle.kts`:
    `implementation(files("libs/engine.aar"))`
3.  Instantiate the `Engine` class:
    `private val engine = Engine()`
4.  Use the public API methods to control the engine (see `Engine.kt` above).

---

## 3. Limitations

- **FFT Implementation**: The current FFT is a basic recursive implementation for demonstration. For production use, integrating a highly optimized C++ FFT library (e.g., FFTW, KissFFT) is recommended for better performance and accuracy.
- **Classification Model**: The Speech/Music classifier is heuristic-based (using RMS and Spectral Centroid). It is effective in many cases but is not a machine-learning model. It may misclassify complex audio.
- **Resource Usage**: Real-time audio processing is inherently resource-intensive. While optimized, running on very old or low-end devices may still result in glitches if the CPU cannot keep up, even in conservative mode.
