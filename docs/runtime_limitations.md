# Runtime Limitations

_Last Updated: 2026-03-17_

Current known limitations in Phase-1:

- native DSP path is stubbed/incomplete relative to full production processing
- native runtime path is intentionally opt-in; Kotlin runtime remains the default-safe execution path in Phase-1
- policy transitions may still produce artifacts on some devices/routes
- Bluetooth routes may require conservative behavior for stability
- AutoEq alias coverage is incomplete for long-tail model naming variants
- AutoEq apply is conservative (`exact normalized` then `exact alias`); suggestions can be broader but do not override apply safety rules
- route-change restart handling is debounced, but vendor/device route churn can still cause transient interruptions
- FFT buffer reuse reduces allocation pressure, but callback/hot-path hardening is not yet production-complete
- latency metrics can include estimated values when exact runtime measurements are unavailable

