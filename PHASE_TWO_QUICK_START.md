# Phase 2 Quick Start Guide

**Status**: Phase 2 Native Core ⚠️ PARTIALLY CERTIFIED (2026-09-11)

This guide helps you get oriented with Phase 2 and start contributing.

---

## What is Phase 2?

Phase 1 (complete) validated the core audio engine architecture in Kotlin/JVM with a real-time validation shell.

**Phase 2** migrates the DSP processing to native C++/AAudio for:
- Better real-time safety
- Lower latency (~5-12ms → ~2-5ms)
- Reduced CPU overhead (<3% → <1.5%)
- Full ARM64 hardware support

Current state: the native DSP core, FFT module, JNI boundary, and verification harnesses are complete; hardware validation on ARM64 identified a deterministic FFT divergence at N=2048 and a logic bug in the Oboe analysis purity path.

---

## Key Documents

| Document | Purpose | Read Time |
|----------|---------|-----------|
| `PHASE_TWO_PLAN.md` | Strategic roadmap, epics, timeline, risks, metrics | 15 min |
| `PHASE_TWO_CHECKLIST.md` | Detailed task list with acceptance criteria | 10 min |
| `PHASE_ONE_COMPLETION.md` | What Phase 1 delivered (context) | 5 min |
| `docs/architecture.md` | Module boundaries and ownership | 5 min |
| `docs/runtime_limitations.md` | Known Phase-1 limitations | 5 min |

---

## Prerequisites

Before starting Phase 2 development:

### Environment
- Android Studio with JBR
- Android NDK (minimum API 21, recommended 29+)
- CMake 3.22+
- A connected ARM64 device or emulator (e.g., Pixel 6, Pixel 7)

### Verify Setup
```powershell
# Check build works
cd C:\Users\scatt\Downloads\Winton_Hills_V2-master\Winton_Hills_V2-master
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
./gradlew :engine:assemble :app:assembleDebug

# Verify native build
./gradlew :engine:compileDebugCpp
```

### Familiarize Yourself
1. Read `PHASE_ONE_COMPLETION.md` (5 min) for context
2. Review Kotlin DSP reference:
   - `engine/src/main/java/.../engine/BiquadFilter.kt`
   - `engine/src/main/java/.../engine/BiquadFilterChain.kt`
   - `engine/src/main/java/.../engine/AudioCaptureThread.kt`
3. Browse existing native code:
   - `engine/src/main/cpp/native_processing_engine.h` (headers/interfaces)
   - `engine/src/main/cpp/audio_engine.cpp` (JNI entry points)
   - `engine/src/main/cpp/native_processing_engine.cpp` (partial implementation)

---

## Current Phase: Epic 1 (Native DSP Implementation)

### Phase 1A: Biquad Filter (Starting Point)

**Goal**: Validate the native RBJ peaking EQ biquad filter in C++.

**Tasks** (see `PHASE_TWO_CHECKLIST.md` for details):
1. Create `engine/src/main/cpp/dsp/biquad_filter.h` header
2. Implement `engine/src/main/cpp/dsp/biquad_filter.cpp`
3. Write unit tests
4. Validate against Python oracle (DSP Harness)

**Expected Output**:
- A single-filter module that produces byte-for-byte identical output as the Kotlin reference
- Zero allocations in the hot processing path
- Pop-free coefficient ramping to eliminate audio clicks

**Success Criteria**:
- Unit tests pass
- DSP Harness PASS (bit-perfect match)
- No clicks during coefficient updates
- Performance ~1-2μs per sample

**Reference**:
- Kotlin version: `engine/src/main/java/.../engine/BiquadFilter.kt`
- Python oracle: `tools/dsp_harness/` (run via Python + C bridge)
- RBJ formula: https://www.earlevel.com/main/2013/10/18/cascaded-iir-filters/

---

## How to Contribute

### 1. Pick a Task
- Start with `PHASE_TWO_CHECKLIST.md` section "Phase 1A: Biquad Filter"
- Mark items as you complete them
- Update the checklist frequently

### 2. Implement
- Reference Kotlin code as the "golden spec"
- Use DSP Harness to validate correctness
- Keep memory allocations out of hot paths
- Document complex DSP logic with inline comments

### 3. Test
- Write unit tests (compare against Kotlin output)
- Run DSP Harness for bit-perfect conformance
- Profile for performance regressions
- Test on both desktop (x86_64) and ARM64 (emulator or device)

### 4. Review & Submit
- Code review checklist (per `PHASE_TWO_CHECKLIST.md`):
  - DSP correctness (bitwise vs. oracle)
  - Thread safety (no races)
  - Memory safety (no overflows, leaks)
  - Performance (meets latency targets)
- Update documentation
- Mark tasks as done in checklist

---

## Build & Run (Native Only)

### Build Native Library
```powershell
./gradlew :engine:compileDebugCpp
```

### Run Desktop Tests (If Available)
```powershell
# (CMake may provide desktop test target)
cd engine/src/main/cpp
cmake -B build .
cmake --build build
./build/tests  # if test executable exists
```

### Build for Android
```powershell
./gradlew :engine:assembleDebug
```

### Run on Device
```powershell
# Deploy and run app (with native DSP active)
./gradlew :app:installDebug
adb shell am start -n com.winton.validationshell/.MainActivity
```

---

## DSP Harness (Validation)

The Python-based DSP Correctness Harness is your primary validation tool.

### Quick Start
```powershell
cd tools\dsp_harness
python -m pytest test_biquad.py -v  # Run biquad conformance tests
```

### Understanding Results
- **PASS**: Native output matches Python oracle (within numerical tolerance)
- **FAIL**: Numerical error exceeds tolerance → investigate DSP logic
- **Tolerance**: Usually 1e-7 (float32) or 1e-15 (float64)

See `tools/dsp_harness/README.md` for full harness documentation.

---

## Common Questions

### Q: Where do I put new files?
A: 
- Headers: `engine/src/main/cpp/dsp/` or `engine/src/main/cpp/policy/`
- Implementation: Same directory as headers
- Tests: `engine/src/main/cpp/test/`
- CMakeLists.txt: Update as you add new files

### Q: How do I ensure my native code matches the Kotlin reference?
A:
1. Compare numerical output (within 1e-7 tolerance)
2. Run DSP Harness on both implementations
3. Test on desktop (x86_64) first, then ARM64 emulator, then hardware

### Q: What if native is slower than Kotlin?
A:
- Profile with Android Profiler (CPU/memory graphs)
- Check for unexpected allocations, lock contention, cache misses
- Consider SIMD optimizations for ARM64 (post-MVP)
- Verify thread affinity and scheduling

### Q: When does Phase 2 finish?
A:
- **Epic 1** (DSP impl): ~2 weeks
- **Epic 2** (JNI): ~1 week
- **Epic 3** (ARM validation): ~2 weeks
- **Epic 4** (hardening): ~1 week
- **Total**: ~6-8 weeks (see `PHASE_TWO_PLAN.md` timeline)

### Q: What's the "SPSC ring buffer" in the code?
A:
- SPSC = Single-Producer, Single-Consumer (wait-free lock-free queue)
- Used for thread-safe frame submission from capture thread → processing thread
- No allocations at runtime (fixed-size pre-allocated)
- See `native_processing_engine.h` lines 34-76

---

## Next Steps

1. **Read** `PHASE_TWO_PLAN.md` (15 min)
2. **Review** `PHASE_TWO_CHECKLIST.md` (10 min)
3. **Pick** Phase 1A task (1.1.1 or 1.1.2)
4. **Implement** with Kotlin reference + DSP Harness as guides
5. **Test** and mark complete in checklist
6. **Repeat** until Phase 1A done, then move to Phase 1B

---

## Resources

- **Kotlin Reference**: `engine/src/main/java/com/winton/validationshell/engine/`
- **Native Headers**: `engine/src/main/cpp/native_processing_engine.h`
- **DSP Harness**: `tools/dsp_harness/README.md`
- **Android NDK Docs**: https://developer.android.com/ndk
- **CMake for Android**: https://cmake.org/cmake/help/latest/manual/cmake-android-platform.7.html
- **RBJ Cookbook**: https://www.earlevel.com/main/2013/10/18/cascaded-iir-filters/

---

**Questions?** Check the relevant planning document or reach out to the audio engine team.

**Status**: Phase 2 Native Core ⚠️ PARTIALLY CERTIFIED — verified on real ARM64 silicon; defects identified
**Last Updated**: 2026-09-11

