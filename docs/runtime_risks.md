# Runtime Risks

_Last Updated: 2026-06-11_

Future breakage risks to monitor:

- classifier instability under noisy or low-SNR environments
- aggressive profile matching causing incorrect auto-apply behavior
- Bluetooth route instability due to device/vendor stack differences
- migration risk while replacing Kotlin DSP path with native DSP path
- dataset size growth causing load-time/memory pressure

Current mitigations already in place (as of 2026-06-11):

- native runtime path is opt-in; Kotlin runtime remains default-safe in Phase-1
- route-change handling includes restart debounce to reduce restart storms
- AutoEq apply remains conservative (`exact normalized` -> `exact alias` -> fallback)
- profile suggestion work is off the main thread to reduce UI stalls
- FFT scratch buffers are reused in Kotlin analysis path to reduce allocation pressure
- **pop-free ramping**: policy and profile switches ramp linearly over 50ms (configurable) to eliminate audible pops and clicks
- **thread safety**: `captureThread` is `@Volatile`, policy selector and filter chain state-modifying methods are `@Synchronized` to prevent race conditions
- **auto-mode stability**: filter chain updates are now synchronized with classification changes to prevent stale policy application

