# Engineering Proposal: Native DSP Validation & Hardening (Phase 2)

This proposal details the next steps for validating the modularized native DSP core and closing specific feature/precision gaps identified during Phase 2 bring-up.

## 1. Context: Recent Refactor
We have successfully decoupled the Biquad DSP components from the engine core into a dedicated `dsp/` directory. Initial compilation is clean across all Android ABIs.

## 2. Identified Gap: Precision Loss in `kPi`
During centralization, `kPi` was accidentally truncated to 20 decimal places.
- **Current**: `3.14159265358979323846`
- **Original (Spec)**: `3.141592653589793238462643383279502884`
- **Impact**: Potential for bit-level divergence in high-Q filters during on-device validation.
- **Action**: Restore full spec-compliant precision.

## 3. Proposed Validation Expansion
I propose expanding the `conformance_tests.py` harness to include two new validation vectors:

### A. Suite 2c: Zero Crossing Rate (ZCR)
Now that `computeZcr` is implemented natively, we must transition it from "untestable" to "active."
- **Oracle (Python)**: `count(sign_changes) / (N - 1)`.
- **Implementation (C++)**: Matches oracle.
- **Verification**: Confirm accuracy on noise and DC signals.

### B. Suite 2e: Analysis-Order Regression
Verify the fix for the analysis-order logic (ensuring analysis runs on RAW samples, even when EQ is engaged).
- **Test Procedure**:
    1. Feed white noise to the engine.
    2. Record features (RMS/Centroid) in **Bypass mode**.
    3. Engage a "harsh" EQ (+12dB peaking @ 1kHz).
    4. Record features again.
- **Success Criteria**: Features recorded in step 2 and 4 must be identical (within tolerance).

## 4. Technical Strategy: Bridge Integrity
The `dsp_bridge.cpp` continues to use the `#define private public` header-wrapping strategy. I have verified it correctly includes the new modular headers:
```cpp
#include "../../engine/src/main/cpp/native_processing_engine.h"
#include "../../engine/src/main/cpp/dsp/biquad_filter.h"
// ...
```
This ensures the Python harness maintains its ability to audit internal engine state without production code changes.

## 5. Summary of Proposed Tasks
1. **Restore `kPi` precision** in `dsp_constants.h`.
2. **Update `conformance_tests.py`** with ZCR and Analysis-Order regression suites.
3. **Re-run Full Conformance Cycle** in WSL2 and report new error baselines.

---
> [!IMPORTANT]
> This validation must pass on the desktop x86_64 build before we promote the changes to the NDK/ARM64 on-device pipeline.
