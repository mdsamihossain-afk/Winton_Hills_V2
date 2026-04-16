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

