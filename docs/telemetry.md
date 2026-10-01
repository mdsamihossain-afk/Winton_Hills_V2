# Telemetry

## Runtime snapshot (engine)

Core snapshot fields include:
- rms
- spectral centroid
- classification id/label
- confidence
- active policy id/name
- analysis latency
- startup latency
- theoretical I/O latency

## Session/export telemetry (app)

Session logs capture:
- engine start/stop events
- policy/mode changes
- route metadata
- latency summary metrics
- AutoEq apply telemetry fields

## AutoEq apply telemetry

Profile application logs include:
- requested_name
- selected_suggestion (if any)
- matched_profile_name
- match_type
- fallback_used

Compatibility note:
- Existing `AutoEq*` telemetry keys are kept for backward compatibility with current logs.

## Validation Telemetry

The project uses an off-device **Harness** (`tools/dsp_harness`) to verify the engine's data integrity.

### Hardware-in-the-loop (HIL) Validation
The `native_test_runner` executable is the native-side validation entry point. It is intended for bit-perfect validation on actual hardware and reports:
- **Max Error**: Absolute difference between native output and oracle reference.
- **Coefficient Convergence**: Verified delta during linear ramping transitions.
- **Feature Drift**: RMS and Centroid precision across different ABIs.

Current status:
- Desktop oracle validation exists and is reproducible.
- Android emulator validation exists.
- Physical ARM64 hardware validation still needs to be completed before the HIL path can be called fully verified.

