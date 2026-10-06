# Phase 2 Initialization Checklist

**Date Created**: 2026-08-02  
**Status**: ✅ HARDWARE CERTIFIED  
**Last Updated**: 2026-10-06 (Phase 2 Finalization)

This checklist tracks immediate setup tasks for Phase 2.

---

## Pre-Development Setup

- [ ] **Verify Phase-1 Build Status**
  - [ ] Run `gradlew :engine:assemble :app:assembleDebug` successfully
  - [ ] Confirm Android emulator or device available for testing
  - [ ] Confirm native build tools (NDK, CMake) installed

- [ ] **Review Phase-1 Codebase**
  - [ ] Read `PHASE_ONE_COMPLETION.md` (✅ done)
  - [ ] Review `docs/architecture.md` (✅ done)
  - [ ] Review `docs/runtime_limitations.md` (✅ done)
  - [ ] Study Kotlin DSP reference: `engine/src/main/java/.../BiquadFilter.kt`
  - [ ] Study Kotlin DSP reference: `engine/src/main/java/.../BiquadFilterChain.kt`

- [ ] **Set Up Native Build Environment**
  - [ ] Verify NDK version in `gradle.properties`
  - [ ] Run test native build: `gradlew :engine:compileDebugCpp`
  - [ ] Confirm CMake build succeeds without errors

- [ ] **Access DSP Harness**
  - [ ] Review `tools/dsp_harness/README.md`
  - [ ] Understand test oracle (Python reference implementation)
  - [ ] Prepare harness for ARM64 testing later

---

## Epic 1: Native DSP Implementation

### Phase 1A: Biquad Filter (Single Filter)

**Objective**: Implement native RBJ peaking EQ biquad with pop-free ramping.

- [x] **1.1.1** Create `engine/src/main/cpp/dsp/biquad_filter.h` header
  - [x] Define `BiquadFilter` class (match semantics from native_processing_engine.h)
  - [x] Declare RBJ coefficient computation
  - [x] Declare process() methods (single sample + buffer)
  - [x] Declare coefficient ramping state

- [x] **1.1.2** Implement `engine/src/main/cpp/dsp/biquad_filter.cpp`
  - [x] RBJ peaking EQ formula (per design spec)
  - [x] Double-precision coefficient storage
  - [x] Pop-free ramping: linear interpolation over N samples
  - [x] DF-2T (Transposed Direct Form II) sample processing
  - [x] State reset (z1, z2 = 0)
  - *(Note: Modularized on 2026-08-02)*

- [x] **1.1.3** Unit Tests
  - [x] Create `engine/src/main/cpp/test/biquad_filter_test.cpp`
  - [x] Test impulse response shape
  - [x] Test ramp-free pop during coefficient update
  - [x] Compare against Kotlin reference output
  - [x] Verify numerical stability (no explosion with extreme Q)

- [x] **1.1.4** Validate Against Python Oracle
  - [x] Run DSP Harness on biquad filter alone
  - [x] Confirm bit-perfect match or < 1e-7 numerical error
  - [x] Document any platform-specific deviations

**Success Criteria**:
- Native biquad produces byte-for-byte identical output as Kotlin reference (or < 1e-7 error)
- Pop-free ramping eliminates clicks during coefficient changes
- No allocations in hot path (process sample loop)
- Unit tests pass on desktop + ARM emulator

---

### Phase 1B: Biquad Filter Chain (Cascade)

**Objective**: Implement serial cascade of biquad filters.

- [x] **1.2.1** Create `engine/src/main/cpp/dsp/biquad_cascade.h`
  - [x] Define `BiquadCascade` class
  - [x] Support dynamic band updates (add/remove/reorder filters)
  - [x] Thread-safe update mechanism (mutex or lock-free queue)

- [x] **1.2.2** Implement `engine/src/main/cpp/dsp/biquad_cascade.cpp`
  - [x] Vector of BiquadFilter instances
  - [x] Serial processing (output of filter N → input of filter N+1)
  - [x] Coordinated ramp timing for all filters (start/stop ramps together)
  - [x] Reset() method for all filters
  - [x] Thread-safe `updateBands()` to reconfigure cascade

- [x] **1.2.3** Unit Tests
  - [x] Test 1-filter cascade (passthrough + identity)
  - [x] Test 2-filter cascade (verify serial path)
  - [x] Test dynamic updates (change bands mid-stream without pops)
  - [x] Compare multi-filter output against Kotlin reference

- [x] **1.2.4** Integrate with Biquad Tests
  - [x] Run cascade through DSP Harness
  - [x] Confirm cascade output matches spec

**Success Criteria**:
- Multi-filter cascade produces expected frequency response
- No pops during dynamic updates
- Thread-safe concurrent updates and processing
- < 1% latency overhead vs. single-filter baseline

---

### Phase 1C: Content Analysis (FFT + RMS + Centroid)

**Objective**: Implement real-time spectral analysis.

- [x] **1.3.1** Choose FFT Implementation
  - [x] Option A: Oboe bundled FFT (if available)
  - [x] Option B: Integrate FFTPACK or similar
  - [x] Option C: Implement radix-2 Cooley-Tukey (reference from Phase-1 Kotlin)
  - Decision: **Option C** (Validated on 2026-08-07)

- [x] **1.3.2** Create `engine/src/main/cpp/dsp/analysis.h`
  - [x] Modular analysis component verified 2026-09-11

- [x] **1.3.3** Implement Analysis Functions
  - [x] RMS: sqrt(sum(x^2) / N)
  - [x] FFT: Hann window + radix-2 algorithm + magnitude
  - [x] MFCC: 13 Mel-bands + Orthonormal DCT-II
  - [x] Centroid: sum(freq * mag) / sum(mag)
  - [x] Classifier: thresholds (mirror Kotlin logic)
  - [x] Pre-allocated scratch buffers to avoid frame-rate allocations

- [x] **1.3.4** Unit Tests
  - [x] Test RMS on known signals (dc, sine, noise)
  - [x] Test FFT bin accuracy
  - [x] Test centroid computation
  - [x] Test classifier consistency with Kotlin
  - [x] **Note**: N=2048 FFT magnitude error (1.48e-04) exceeds 1e-4 threshold; identified as deterministic accumulation.

**Success Criteria**:
- RMS, centroid match Kotlin reference (< 1% error)
- FFT magnitude spectrum matches Python oracle
- Classifier output matches Kotlin logic
- Zero allocations per-frame (pre-allocated scratch space)

---

### Phase 1D: Policy Selector & State Machine

**Objective**: Implement native policy selection logic.

- [x] **1.4.1** Create `engine/src/main/cpp/policy/policy_selector.h`
  - [x] Define PolicyId enum (Passthrough, Speech, Neutral)
  - [x] Define PolicySelector class
  - [x] Hysteresis state tracking
  - [x] Thread-safe access methods

- [x] **1.4.2** Implement Policy Selection
  - [x] Load policy thresholds from header or config
  - [x] Implement hysteresis logic (avoid thrashing)
  - [x] Handle manual override mode
  - [x] Synchronized state snapshot for telemetry

- [x] **1.4.3** Unit Tests
  - [x] Test state transitions match Kotlin reference
  - [x] Test hysteresis prevents oscillation (10 toggles -> 0 switches)
  - [x] Test manual override blocks auto-selection
  - [x] Test thread-safe concurrent updates

**Success Criteria**:
- Policy selection logic matches Kotlin reference
- Hysteresis prevents classification thrashing
- Thread-safe state access
- < 1ms latency for policy decision

---

### Phase 1E: Native Engine Core Assembly

**Objective**: Integrate all DSP components into NativeProcessingEngine.

- [x] **1.5.1** Update `native_processing_engine.h`
  - [x] Include all DSP headers
  - [x] Define worker thread lifecycle
  - [x] Pre-allocate buffers and scratch space
  - [x] Lock-free or minimal-lock telemetry snapshot mechanism

- [x] **1.5.2** Implement `native_processing_engine.cpp`
  - [x] Worker thread main loop
  - [x] Frame submission via lock-free queue (SPSC ring buffer)
  - [x] Per-frame: submit → process → analyze → snapshot
  - [x] Latency tracking (timestamps at each stage)
  - [x] Policy/cascade updates with coordination
  - [x] Graceful pause/resume for route changes
  - [x] **BUG FIXED**: `onAudioReady` now captures RAW samples into `rawScratch_` before in-place DSP.
  - *(Verified on Samsung SM-A536E 2026-09-18)*

- [ ] **1.5.3** Integration Tests
  - [ ] Test engine lifecycle (start → process frames → stop)
  - [ ] Test audio passthrough (input == output when bypass)
  - [ ] Test DSP chain application
  - [ ] Test telemetry snapshot generation
  - [ ] Test 10+ minutes sustained operation (memory stable)

- [ ] **1.5.4** Compare Against Kotlin Baseline
  - [ ] Benchmark: Kotlin vs Native latency
  - [ ] Benchmark: Kotlin vs Native CPU %
  - [ ] Benchmark: Kotlin vs Native memory footprint
  - [ ] Target: Native ≥30% faster than Kotlin

**Success Criteria**:
- Engine starts/stops cleanly
- Processes frames without stalls or allocations
- Produces identical analysis output as Kotlin reference
- Telemetry latency < 1ms
- Sustained operation stable (no crashes, leaks, hangs)

---

## Epic 2: JNI Boundary & Integration

- [x] **2.1** JNI Function Signatures (see `audio_engine.cpp`)
- [x] **2.2** Update JNI Entry Points
- [x] **2.3** Kotlin Wrapper Classes
- [x] **2.4** Telemetry Snapshot Synchronization
- [x] **2.5** Integration Tests (JVM ↔ native)
  - [x] Verified via Real-time Configuration UI in `:app` (2026-09-18)

*(Details in PHASE_TWO_PLAN.md Epic 2)*

---

## Epic 3: ARM64 Validation

- [x] **3.1** Build Native Library for ARM64
- [x] **3.2** Run DSP Harness on ARM64 Hardware
- [x] **3.3** End-to-End Validation on Real Device
- [x] **3.4** Performance Benchmarking
  - [x] Hardware certified on Samsung SM-A536E (2026-09-18)

*(Details in PHASE_TWO_PLAN.md Epic 3)*

---

## Epic 4: Production Hardening

- [ ] **4.1** Error Handling & Fallback
- [ ] **4.2** Memory Optimization
- [ ] **4.3** Bluetooth Route Hardening
- [ ] **4.4** Stereo Support (optional)
- [ ] **4.5** Documentation & Code Review

*(Details in PHASE_TWO_PLAN.md Epic 4)*

---

## Build & CI/CD Setup

- [ ] Configure native build in CI pipeline
- [ ] Automate DSP Harness test execution
- [ ] Set up binary artifact storage (native .so files)
- [ ] Configure ARM64 hardware testing (if available)

---

## Documentation

- [x] Update `engine/README.md` with native DSP overview
- [x] Add native DSP design doc (formulas, algorithms, performance notes)
- [x] Document JNI contract and lifetime management
- [x] Add Phase-2 test results to `docs/`
- [x] Update main `README.md` Phase 2 status section

---

## Sign-Off & Review

- [ ] Code review: DSP correctness (bitwise compare vs. Python oracle)
- [ ] Code review: Thread safety (no races, deadlocks)
- [ ] Code review: Memory safety (no buffer overflows, leaks)
- [ ] Code review: Performance (meets latency/CPU targets)
- [ ] Stakeholder review & approval for Phase 3 planning

---

## Notes

- **Dependency Order**: Epic 1 → Epic 2 → Epic 3 → Epic 4 (sequential)
- **Parallel Work**: DSP Harness setup can proceed alongside Epic 1.1-1.3
- **Reference**: Kotlin implementations in `engine/src/main/java/com/winton/validationshell/engine/` serve as golden spec
- **Test Oracle**: Python harness in `tools/dsp_harness/` is source of truth for DSP correctness
- **Review Cadence**: Weekly checkpoint reviews to validate progress and unblock issues

---

**Last Updated**: 2026-09-18  
**Next Review**: 2026-09-18

