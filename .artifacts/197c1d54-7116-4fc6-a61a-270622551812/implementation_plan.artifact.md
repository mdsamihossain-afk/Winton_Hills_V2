# Implementation Plan - Real-Time Native Check Configuration

Add UI controls and service-level plumbing to enable real-time A/B testing of the **Native C++ Engine** vs. the **Kotlin JVM Engine**.

## User Review Required

> [!IMPORTANT]
> **Native Path is Opt-In**: By default, the app uses the Kotlin processing path. This plan adds a "Native Core" configuration section to the UI to allow explicit activation of the Hardware-Certified C++ path.
>
> **Capture Modes**: We will expose two distinct native controls:
> 1. **Native Processing**: Routes the DSP math (Biquads, FFT) through C++.
> 2. **Native Capture**: Bypasses Android's `AudioRecord` and uses **Oboe/AAudio** directly in C++.

## Proposed Changes

### [Engine SDK]

#### [MODIFY] [AudioEngineRepository.kt](file:///C:/Users/scatt/Downloads/Winton_Hills_V2-master/Winton_Hills_V2-master/app/src/main/java/com/winton/validationshell/data/AudioEngineRepository.kt)
- Add `isNativeProcessingEnabled: StateFlow<Boolean>`
- Add `isNativeCaptureEnabled: StateFlow<Boolean>`
- Add `setNativeProcessingEnabled(enabled: Boolean)`
- Add `setNativeCaptureEnabled(enabled: Boolean)`

#### [MODIFY] [ServiceAudioEngineRepository.kt](file:///C:/Users/scatt/Downloads/Winton_Hills_V2-master/Winton_Hills_V2-master/app/src/main/java/com/winton/validationshell/data/ServiceAudioEngineRepository.kt)
- Implement the new state flows and setters by delegating to `AudioEngineService`.

### [Foreground Service]

#### [MODIFY] [AudioEngineService.kt](file:///C:/Users/scatt/Downloads/Winton_Hills_V2-master/Winton_Hills_V2-master/app/src/main/java/com/winton/validationshell/service/AudioEngineService.kt)
- Add `MutableStateFlow` for native processing and capture states.
- Expose these as public methods for the repository.
- Ensure `engine.setNativeProcessingEnabled` and `engine.setNativeCaptureEnabled` are called correctly.

### [Validation Shell UI]

#### [MODIFY] [ValidationShellUiState.kt](file:///C:/Users/scatt/Downloads/Winton_Hills_V2-master/Winton_Hills_V2-master/app/src/main/java/com/winton/validationshell/ui/ValidationShellUiState.kt)
- Add `isNativeProcessingEnabled` and `isNativeCaptureEnabled` flags.

#### [MODIFY] [ValidationShellViewModel.kt](file:///C:/Users/scatt/Downloads/Winton_Hills_V2-master/Winton_Hills_V2-master/app/src/main/java/com/winton/validationshell/ui/ValidationShellViewModel.kt)
- Add event handlers: `onNativeProcessingToggled` and `onNativeCaptureToggled`.

#### [MODIFY] [ValidationShellScreen.kt](file:///C:/Users/scatt/Downloads/Winton_Hills_V2-master/Winton_Hills_V2-master/app/src/main/java/com/winton/validationshell/ui/ValidationShellScreen.kt)
- **New Section: Native Engine Core**: Add a section with two Material 3 Switches to toggle the native paths.
- Display a "Native Path Active" indicator in the `AnalysisPanel` when using the C++ core.

## Verification Plan

### Manual Verification
1. **Start Engine (Kotlin)**: Confirm real-time analysis updates using the default path.
2. **Enable Native Processing**: Toggle the switch and verify in Logcat: `nativeProcessingEnabled=true`. Confirm analysis still flows (now from JNI).
3. **Enable Native Capture**: Toggle Oboe capture and verify: `Native capture enabled: 1`.
4. **A/B Test**: Switch between policies and confirm the native `BiquadCascade` responds with the same pop-free behavior verified in the HIL tests.
5. **Bluetooth Check**: Connect BT headphones and confirm the native `PolicySelector` drops to Passthrough (if applicable) in real-time.
