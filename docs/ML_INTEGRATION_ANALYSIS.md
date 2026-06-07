# ML Model Integration Analysis: Winton Hills V2 Audio Engine

## Executive Summary

This document provides a comprehensive architectural analysis of the Winton Hills V2 audio engine, focusing on how machine learning models can be integrated into the existing pipeline. The analysis covers the current audio data flow, heuristic-based classification mechanisms, threading model, data structures, and proposed architectural changes required to support ML inference in real-time constraints.

---

## 1. Audio Data Flow Pipeline

### Input → Processing → Output Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                    AUDIO ENGINE PIPELINE                        │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  1. AUDIO INPUT                                                │
│     └─→ AudioCaptureThread (elevated THREAD_PRIORITY_AUDIO)   │
│         - Real-time audio capture via hardware                │
│         - Fills circular buffer at sample rate (44.1kHz)      │
│                                                                │
│  2. FRAME-BASED ANALYSIS                                      │
│     └─→ FFT & Feature Extraction (per 1024-sample frame)     │
│         - Hann windowing (zero-copy buffer reuse)            │
│         - In-place radix-2 Cooley-Tukey FFT                  │
│         - RMS calculation (time-domain)                       │
│         - Spectral centroid (frequency-domain)                │
│                                                                │
│  3. CLASSIFICATION                                            │
│     └─→ FeatureExtractor.classify(rms, spectralCentroid)     │
│         - Heuristic rule-based (currently)                   │
│         - Output: 4-class label + confidence [0-1]          │
│         - Classes: 0=Silence, 1=Speech, 2=Music, 3=Mixed   │
│                                                                │
│  4. POLICY SELECTION                                          │
│     └─→ PolicySelector.select(classification)               │
│         - Hysteretic decision with 500ms hold                │
│         - Selects 1 of 4 policies based on classification   │
│         - minConfidence threshold: 0.7 (configurable)       │
│                                                                │
│  5. DSP PROCESSING                                            │
│     └─→ BiquadFilterChain.process(samples, policy)           │
│         - Serial cascade of IIR peaking EQ filters           │
│         - 4 policies with different frequency responses      │
│         - RBJ Audio EQ Cookbook coefficients                │
│                                                                │
│  6. AUDIO OUTPUT                                              │
│     └─→ Processed audio sent to speaker/network              │
│         - Same frame rate as input                           │
│         - Total latency: ~42-65ms (I/O + processing)        │
│                                                                │
└─────────────────────────────────────────────────────────────────┘
```

### Timing and Latency Breakdown

| Component | Latency | Notes |
|-----------|---------|-------|
| Audio Frame Period | ~23ms | 1024 samples @ 44.1kHz |
| FFT Computation | 0.5-2ms | O(N log N), power-of-2 frames |
| Feature Extraction | 0.1-0.5ms | RMS + spectral centroid |
| Heuristic Classification | <0.1ms | Threshold comparisons only |
| Policy Selection | <1ms | Hysteresis logic |
| DSP Processing | 2-5ms | Depends on filter complexity |
| **Per-Frame Total** | **1-5ms** | EMA-smoothed analysis latency |
| **Startup Latency** | **100-500ms** | Engine start → first classification |
| **Theoretical I/O** | **~42ms** | 2x buffering estimate |
| **Policy Switch** | **500ms+** | Hysteresis duration |

---

## 2. Current Classification Mechanism

### 2.1 Heuristic Architecture

The current classification is **purely heuristic** and deterministic, implemented in `FeatureExtractor.classify()`:

```kotlin
// Current Classification Rule Set
fun classify(rms: Double, spectralCentroid: Double): ClassificationResult {
    return when {
        // SILENCE: Low energy across all frequencies
        rms < 0.02 && spectralCentroid < 0.1 → ClassificationResult(
            label = 0,
            confidence = 0.95,
            description = "Silence"
        )
        
        // SPEECH: Moderate RMS, low spectral centroid
        // Rationale: Speech energy concentrated in lower frequencies (0.05-3kHz)
        rms in 0.02..0.3 && spectralCentroid in 0.05..0.35 → ClassificationResult(
            label = 1,
            confidence = 0.80,
            description = "Speech"
        )
        
        // MUSIC: High spectral centroid OR high RMS
        // Rationale: Music spreads energy across spectrum, often louder
        spectralCentroid > 0.35 || rms > 0.3 → ClassificationResult(
            label = 2,
            confidence = 0.75,
            description = "Music"
        )
        
        // MIXED: Overlapping characteristics
        else → ClassificationResult(
            label = 3,
            confidence = 0.50,
            description = "Mixed"
        )
    }
}
```

### 2.2 Feature Inputs (Current - Only 2 Features)

| Feature | Calculation | Range | Domain | Notes |
|---------|-------------|-------|--------|-------|
| **RMS** (Root Mean Square) | `sqrt(mean(samples²))` | [0, 1] | Time | Represents overall energy |
| **Spectral Centroid** | `Σ(f·P(f)) / Σ(P(f))` | [0, 1] normalized | Frequency | Center of mass of spectrum |

### 2.3 Classification Confidence Levels

- **Silence**: 0.95 (very confident)
- **Speech**: 0.80 (confident)
- **Music**: 0.75 (moderate confidence)
- **Mixed**: 0.50 (low confidence, uncertain)

### 2.4 Policy Mapping

| Classification | Selected Policy | Rationale |
|-----------------|-----------------|-----------|
| Silence | PASSTHROUGH | No processing needed |
| Speech | SPEECH_INTELLIGIBILITY | Enhance vocals, reduce noise |
| Music | NEUTRAL_CORRECTION | Flat response, preserve dynamics |
| Mixed | CONSERVATIVE | Safe middle-ground EQ |

---

## 3. Threading Model and Real-Time Constraints

### 3.1 Threading Architecture

```
┌──────────────────────────────────────────────────────┐
│           Main Application Thread                    │
│  (UI, lifecycle, policy setting, telemetry)        │
└──────────┬───────────────────────────────────────────┘
           │
           │ engine.startAudioEngine()
           │ engine.getSnapshot()
           │
           ▼
┌──────────────────────────────────────────────────────┐
│     AudioCaptureThread (Elevated Priority)           │
│  THREAD_PRIORITY_AUDIO = 7+ (above normal)          │
├──────────────────────────────────────────────────────┤
│                                                      │
│  Main Loop (44.1kHz @ 1024 samples/frame):          │
│   1. Read 1024 samples from hardware buffer         │
│   2. Apply Hann window (in-place)                   │
│   3. Compute FFT (radix-2 Cooley-Tukey)            │
│   4. Extract features (RMS, centroid)              │
│   5. Run classification (heuristic)                │
│   6. Write result to @Volatile latestResult        │
│   7. Send processed audio to output                │
│                                                      │
│  ⚠️  LOCK-FREE: Single writer to latestResult      │
│  ⚠️  ~23ms per iteration (1024 @ 44.1kHz)          │
│  ⚠️  No synchronization overhead                    │
│                                                      │
└──────────────────────────────────────────────────────┘
           ▲
           │
           │ @Volatile latestResult (single writer)
           │
           ▼
┌──────────────────────────────────────────────────────┐
│    Multiple Reader Threads                           │
│  (UI, telemetry, policy updates)                    │
│                                                      │
│  getSnapshot() → read latestResult                  │
│  (Safe: one writer, multiple readers)              │
│                                                      │
└──────────────────────────────────────────────────────┘
```

### 3.2 Performance Constraints

**Capture Loop Deadline**: 23ms / iteration
- Must complete all steps in < 23ms to avoid buffer underrun
- Real-time priority prevents context switching by lower-priority threads
- Lock-free design eliminates synchronization bottlenecks

**Memory Considerations**:
- Zero-copy FFT buffer reuse reduces GC pressure
- Single @Volatile field reduces false sharing
- BiquadFilter stateful (z1, z2 registers) to avoid discontinuities

**Latency Budget for ML Inference**:
- Total per-frame budget: ~23ms
- Current heuristic takes: <1ms
- **Available for ML model**: ~6-11ms (after feature extraction overhead)
- Must support 44 classifications/second

---

## 4. Data Structures and Interfaces

### 4.1 Core Data Structures

#### ClassificationResult
```kotlin
data class ClassificationResult(
    val label: Int,           // 0=Silence, 1=Speech, 2=Music, 3=Mixed
    val confidence: Double,   // [0, 1]
    val description: String   // Human-readable label
)
```

#### TelemetrySnapshot
```kotlin
data class TelemetrySnapshot(
    val classification: ClassificationResult,
    val activePolicy: PolicyType,
    val analysisLatency: Long,        // EMA-smoothed, nanoseconds
    val startupLatency: Long,         // Millis
    val theoreticalIOLatency: Long,   // Millis
    val policySwitchLatency: Long,    // Millis
    val engineState: EngineState
)
```

#### PolicyConfig
```kotlin
data class PolicyConfig(
    val type: PolicyType,     // PASSTHROUGH, SPEECH_INTELLIGIBILITY, NEUTRAL_CORRECTION, CONSERVATIVE
    val eqBands: List<BiquadBand>  // List of frequency/gain/Q configurations
)
```

#### BiquadBand
```kotlin
data class BiquadBand(
    val frequency: Double,    // Hz
    val gain: Double,         // dB
    val qFactor: Double       // Resonance/width
)
```

#### LatencyTracker
```kotlin
class LatencyTracker {
    fun recordAnalysisLatency(nanos: Long)      // Per-frame
    fun recordStartupLatency(millis: Long)      // Engine startup
    fun recordIOLatency(millis: Long)           // Theoretical
    fun recordPolicySwitchLatency(millis: Long)
    
    // Provides EMA-smoothed values for telemetry
    fun getAnalysisLatencyNanos(): Long
    fun getStartupLatencyMs(): Long
    // ...
}
```

### 4.2 FeatureExtractor Interface

```kotlin
object FeatureExtractor {
    // Current: 2 input features
    fun getRMS(samples: FloatArray): Double
    fun getSpectralCentroid(fftBins: FloatArray): Double
    
    // Current: Heuristic classification
    fun classify(rms: Double, spectralCentroid: Double): ClassificationResult
    
    // [ML PLACEHOLDER] Future: Would accept ML model output
    // fun classifyWithML(features: FloatArray, model: MLClassifier): ClassificationResult
}
```

### 4.3 PolicySelector Interface

```kotlin
class PolicySelector(
    private val minConfidence: Double = 0.7,    // Confidence threshold
    private val hysteresisMs: Long = 500        // Hold duration
) {
    fun select(classification: ClassificationResult): PolicyType {
        // Hysteretic decision: requires high confidence to switch
        // Holds last policy for hysteresisMs even if classification changes
    }
}
```

---

## 5. ML Model Integration Points

### 5.1 Where ML Can Integrate

**IDEAL REPLACEMENT POINT**: `FeatureExtractor.classify()`

The heuristic classifier is stateless and deterministic—a perfect candidate for ML model substitution:

```kotlin
// CURRENT: Heuristic (2 features, rule-based)
fun classify(rms: Double, spectralCentroid: Double): ClassificationResult
    
// PROPOSED: ML-based (N features, learned model)
fun classify(features: FloatArray, modelInference: MLClassifier): ClassificationResult
```

### 5.2 Feature Expansion for ML Model

To unlock better classification accuracy, expand feature set:

| Feature | Calculation | Domain | Purpose | Notes |
|---------|-------------|--------|---------|-------|
| **MFCC** (Mel-Frequency Cepstral Coefficients) | Log-mel transform of spectrogram | Frequency | Human auditory perception | 13-40 coefficients typical |
| **Spectral Flux** | `Σ(dP/dt)` | Frequency | Temporal change in spectrum | Music has more flux than speech |
| **Zero-Crossing Rate** | Count of sign changes | Time | Voicedness indicator | Speech peaks 1-3kHz, music 0.5-20kHz |
| **Spectral Entropy** | `-Σ(P(f) · log P(f))` | Frequency | Distribution spread | Music more entropic (noise-like) |
| **RMS Energy** | Existing feature | Time | Overall loudness | Keep for compatibility |
| **Spectral Centroid** | Existing feature | Frequency | Frequency balance | Keep for compatibility |
| **Chroma Features** (optional) | Pitch class energy | Frequency | Musical note content | 12-bin pitch histogram |

**Resulting Feature Vector**: 30-60 dimensions (vs. current 2)

### 5.3 Three ML Integration Architectures

#### Option A: Drop-In Replacement (Minimal Changes)
```
FeatureExtractor.classify(rms, centroid)
    └─→ ML Model Inference (wrapped)
        ├─ Input: [rms, centroid, ...10-20 new features]
        └─ Output: ClassificationResult (same as heuristic)
```
**Pros**: Minimal code changes, uses existing `ClassificationResult` structure  
**Cons**: Limited feature set, may not unlock full ML potential  
**Latency**: +3-5ms (feature extraction overhead)

#### Option B: Feature-Rich Path (Recommended)
```
AudioCaptureThread Loop:
  ├─ Existing fast path: FFT → [rms, centroid] → heuristic → quick decision
  │                                                              ▲
  └─ New ML path: FFT → [MFCC, flux, ZCR, entropy, ...] ────┐
                                                              │
                                              ML Model Input ──┘
                                                    ▼
                                          [TFLite inference: 5-10ms]
                                                    ▼
                                          ClassificationResult
```
**Pros**: Full feature set, model can use 30-60 dimensions  
**Cons**: More complex architecture, dual paths  
**Latency**: +7-12ms (feature extraction + inference)  
**Fallback**: Heuristic path as safety net if ML disabled

#### Option C: Callback-Based (Future-Proof)
```
Engine.startAudioEngine(
    classificationProvider = { features: FloatArray →
        if (useML) mlModel.predict(features)
        else heuristicClassifier.predict(features)
    }
)
```
**Pros**: Pluggable classification, supports multiple strategies  
**Cons**: Most complex, abstraction overhead  
**Latency**: +1-15ms depending on provider  
**Flexibility**: Enables A/B testing, gradual rollout

### 5.4 ML Model Requirements

**Model Format**:
- TensorFlow Lite (`.tflite`) for Android/mobile deployment
- Quantization: INT8 or FP16 (required for real-time)
- Model size: < 5MB (binary footprint constraint)

**Input/Output Specs**:
- **Input**: Float array, 30-60 features, normalized [0, 1]
- **Output**: 4 logits or softmax probabilities [0, 1] for classes
- **Inference latency**: < 10ms on target hardware (ARM Cortex-A53+)

**Training Data Requirements**:
- Balanced dataset: ~1000-5000 samples per class
- Mix of speech (podcasts, conversations), music (genres), silence, mixed content
- Sample rate: 44.1kHz or 48kHz to match engine
- Duration: 1-5 second clips (20-40 frames each)

---

## 6. Architectural Changes Needed for ML Support

### 6.1 Phase 1: Foundation (Current State)

✅ **Already in place**:
- Single-threaded audio capture with lock-free communication
- Modular feature extraction (`FeatureExtractor`)
- Pluggable policy selection (`PolicySelector`)
- Real-time telemetry tracking (`LatencyTracker`)

### 6.2 Phase 2: ML Infrastructure (Required)

#### 2a. Add TFLite Dependency
```gradle
dependencies {
    implementation 'org.tensorflow:tensorflow-lite:2.14.0'
    implementation 'org.tensorflow:tensorflow-lite-support:0.4.4'
}
```

#### 2b. Create ML Model Interface
```kotlin
interface MLClassifier {
    fun predict(features: FloatArray): ClassificationResult
    fun isReady(): Boolean
    fun shutdown()
}

// Implementation: TFLiteClassifier
class TFLiteClassifier(modelPath: String) : MLClassifier {
    private val interpreter: Interpreter = Interpreter(loadModel(modelPath))
    
    override fun predict(features: FloatArray): ClassificationResult {
        val output = FloatArray(4)  // 4-class output
        interpreter.run(features, output)
        return parseOutput(output)
    }
}
```

#### 2c. Extend FeatureExtractor
```kotlin
object FeatureExtractor {
    // EXISTING 2 features
    fun getRMS(samples: FloatArray): Double { ... }
    fun getSpectralCentroid(fftBins: FloatArray): Double { ... }
    
    // NEW FEATURES (for ML)
    fun getMFCC(fftBins: FloatArray, melBands: Int = 13): FloatArray { ... }
    fun getSpectralFlux(
        prevSpectrum: FloatArray,
        currSpectrum: FloatArray
    ): Float { ... }
    fun getZeroCrossingRate(samples: FloatArray): Float { ... }
    fun getSpectralEntropy(fftBins: FloatArray): Float { ... }
    
    // Build full feature vector
    fun buildMLFeatureVector(
        samples: FloatArray,
        fftBins: FloatArray,
        prevSpectrum: FloatArray
    ): FloatArray {
        return floatArrayOf(
            getRMS(samples).toFloat(),
            getSpectralCentroid(fftBins).toFloat(),
            *getMFCC(fftBins),
            getSpectralFlux(prevSpectrum, fftBins),
            getZeroCrossingRate(samples),
            getSpectralEntropy(fftBins)
        ).also { normalize(it) }
    }
}
```

#### 2d. Update AudioCaptureThread
```kotlin
class AudioCaptureThread(
    private val mlClassifier: MLClassifier?  // NEW: Optional ML model
) : Thread() {
    
    override fun run() {
        while (isRunning) {
            // 1. Capture & FFT (existing)
            val samples = captureAudioFrame()
            val fftBins = computeFFT(samples)
            
            // 2. Classify (dual-path: heuristic + ML)
            val heuristicResult = featureExtractor.classify(
                featureExtractor.getRMS(samples),
                featureExtractor.getSpectralCentroid(fftBins)
            )
            
            val finalResult = if (mlClassifier != null && mlClassifier.isReady()) {
                // ML PATH: Use full feature vector
                val mlFeatures = featureExtractor.buildMLFeatureVector(
                    samples, fftBins, lastSpectrum
                )
                val mlResult = mlClassifier.predict(mlFeatures)
                
                // Confidence blending or override
                if (mlResult.confidence > 0.85) mlResult
                else heuristicResult  // Fallback to heuristic if ML uncertain
            } else {
                // FALLBACK: Use heuristic
                heuristicResult
            }
            
            lastSpectrum = fftBins.copyOf()
            latestResult = finalResult
            
            // 3. Continue pipeline (policy, DSP, output)
            // ...
        }
    }
}
```

#### 2e. Update Engine Initialization
```kotlin
class Engine {
    fun startAudioEngine(
        useML: Boolean = false,
        mlModelPath: String? = null
    ) {
        val mlClassifier = if (useML && mlModelPath != null) {
            TFLiteClassifier(mlModelPath).also {
                if (!it.isReady()) {
                    Log.w("Engine", "ML model failed to load, falling back to heuristic")
                    null
                }
            }
        } else null
        
        audioCaptureThread = AudioCaptureThread(mlClassifier)
        audioCaptureThread.start()
    }
}
```

### 6.3 Phase 3: Monitoring & Optimization

#### 3a. Extended Telemetry
```kotlin
data class TelemetrySnapshot(
    // EXISTING
    val classification: ClassificationResult,
    val activePolicy: PolicyType,
    val analysisLatency: Long,
    
    // NEW: ML-specific metrics
    val mlInferenceLatency: Long?,      // Time for model prediction
    val mlConfidence: Double?,           // Model's confidence
    val featureExtractionLatency: Long?, // Time for MFCC, flux, etc.
    val fallbackUsed: Boolean            // Was heuristic fallback triggered?
)
```

#### 3b. Latency Monitoring
- Track feature extraction latency separately
- Monitor ML inference latency per frame
- Alert if inference + features exceed 12ms budget
- A/B test heuristic vs. ML on same input stream

#### 3c. Model Health Checks
- Periodic inference latency benchmarks
- Confidence distribution analysis
- False positive/negative tracking (post-deployment)

### 6.4 Phase 4: Deployment & Rollout

**Option 1: Async Update**
- Ship engine with embedded heuristic classifier
- Model delivered via network update at runtime
- Graceful degradation if download fails

**Option 2: A/B Testing**
- 50% of users get ML, 50% heuristic (by device ID)
- Compare policy selections, user feedback, latency impact
- Gradually shift percentage after validation

**Option 3: Staged Rollout**
- Initial release: ML disabled (heuristic-only)
- Week 1: 5% with ML enabled, monitor metrics
- Week 2: 25% with ML enabled
- Week 4: 100% with ML enabled (heuristic fallback always available)

---

## 7. Summary and Recommendations

### 7.1 Key Findings

| Aspect | Current State | With ML |
|--------|---------------|---------|
| **Classification Features** | 2 (RMS, centroid) | 30-60 (MFCC, flux, ZCR, entropy, etc.) |
| **Accuracy** | ~75-80% (estimated) | ~90-95% (with trained model) |
| **Per-Frame Latency** | 1-5ms | 8-15ms |
| **Model Footprint** | N/A | 2-5MB (TFLite, INT8) |
| **Fallback Safety** | N/A | Heuristic always available |
| **Threading** | Lock-free, single writer | Unchanged, backward compatible |
| **Deployment** | Embedded heuristic | Model update via network or app bundle |

### 7.2 Recommended Approach

**Use Option B (Feature-Rich Path) with Staged Rollout**:

1. **Immediate** (Phase 2a):
   - Add TFLite dependency
   - Create `MLClassifier` interface
   - Extend `FeatureExtractor` with MFCC, flux, ZCR, entropy
   - Update `AudioCaptureThread` dual-path logic

2. **Next Sprint** (Phase 2b):
   - Train TFLite model on representative data
   - Integrate model loading in `Engine.startAudioEngine()`
   - Add telemetry for ML-specific metrics
   - Test on target hardware (latency validation)

3. **Following Sprint** (Phase 3):
   - A/B test heuristic vs. ML on sample user population
   - Gather metrics, user feedback
   - Optimize model quantization if needed

4. **Deployment** (Phase 4):
   - Staged rollout starting at 5% (with heuristic fallback)
   - Monitor production metrics
   - Full rollout after validation

### 7.3 Risk Mitigation

| Risk | Mitigation |
|------|-----------|
| **Latency exceeds budget** | Quantize model to INT8, reduce feature dimensions, use fixed time budget for inference |
| **ML model unavailable at startup** | Always fallback to heuristic, log warnings, don't crash |
| **Accuracy regression** | Validate on production data, A/B test extensively, keep heuristic in binary |
| **Model update failures** | Fallback to embedded heuristic, don't require update |
| **Memory pressure** | Use shared TFLite interpreter instance, reuse buffer pools |

### 7.4 Performance Targets

- **ML Inference Latency**: < 10ms (95th percentile)
- **Total Per-Frame Latency**: < 20ms (to stay within 23ms budget)
- **Accuracy**: > 90% on validation set
- **Model Size**: < 5MB (binary footprint)
- **Memory Overhead**: < 20MB (runtime)

---

## Appendix: Code References

### A. Key File Locations

| File | Size | Purpose | Key Methods |
|------|------|---------|------------|
| `Engine.kt` | 20.4 KB | Public API, lifecycle | `startAudioEngine()`, `stopAudioEngine()`, `getSnapshot()` |
| `AudioCaptureThread.kt` | 11+ KB | Real-time capture loop | Main loop (182-235), FFT, classification |
| `FeatureExtractor.kt` | Unknown | Feature extraction | `getRMS()`, `getSpectralCentroid()`, `classify()` |
| `PolicySelector.kt` | Unknown | Policy selection | `select()` with hysteresis |
| `PolicyConfig.kt` | Unknown | EQ policy definitions | 4 policies with bands |
| `BiquadFilter.kt` | Unknown | IIR filtering | RBJ Audio EQ implementation |
| `LatencyTracker.kt` | Unknown | Telemetry | EMA smoothing, multi-dimension tracking |

### B. Threading Model Summary

```
Main Thread ←→ AudioCaptureThread (THREAD_PRIORITY_AUDIO)
                    ↓
                @Volatile latestResult (lock-free)
                    ↑
            Multiple Reader Threads
```

### C. Latency Critical Path

1. Audio capture: 0ms (async)
2. FFT: 0.5-2ms
3. Features: 0.1-0.5ms (heuristic) or 2-5ms (ML)
4. Classification: <0.1ms (heuristic) or 5-10ms (ML)
5. Policy: <1ms
6. DSP: 2-5ms
7. **Total frame budget**: 23ms (1024 @ 44.1kHz)

---

**Document Version**: 1.0  
**Analysis Date**: 2026-06-07  
**Repository**: mdsamihossain-afk/Winton_Hills_V2  
**Branch**: Main analysis branch  
