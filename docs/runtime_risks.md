# Runtime Risks

_Last Updated: 2026-03-17_

Future breakage risks to monitor:

- classifier instability under noisy or low-SNR environments
- aggressive profile matching causing incorrect auto-apply behavior
- Bluetooth route instability due to device/vendor stack differences
- migration risk while replacing Kotlin DSP path with native DSP path
- policy-switch artifacts during rapid content changes
- dataset size growth causing load-time/memory pressure

Current mitigations already in place:

- native runtime path is opt-in; Kotlin runtime remains default-safe in Phase-1
- route-change handling includes restart debounce to reduce restart storms
- AutoEq apply remains conservative (`exact normalized` -> `exact alias` -> fallback)
- profile suggestion work is off the main thread to reduce UI stalls
- FFT scratch buffers are reused in Kotlin analysis path to reduce allocation pressure

