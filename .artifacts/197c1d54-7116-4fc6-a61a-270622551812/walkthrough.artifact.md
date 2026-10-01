# Walkthrough - Real-Time Native Core Configuration

I have implemented the UI controls and service plumbing to enable real-time A/B testing of the Hardware-Certified **Native C++ Engine** against the baseline **Kotlin Engine**.

## Key Changes

### 1. Engine SDK & Repository
- **[MODIFY] [AudioEngineRepository.kt](file:///C:/Users/scatt/Downloads/Winton_Hills_V2-master/Winton_Hills_V2-master/app/src/main/java/com/winton/validationshell/data/AudioEngineRepository.kt)**: Added `isNativeProcessingEnabled` and `isNativeCaptureEnabled` flows.
- **[MODIFY] [Engine.kt](file:///C:/Users/scatt/Downloads/Winton_Hills_V2-master/Winton_Hills_V2-master/engine/src/main/java/com/winton/validationshell/engine/Engine.kt)**: Exposed `setNativeProcessingEnabled` and `setNativeCaptureEnabled` to control the JNI path and Oboe capture.

### 2. Foreground Service
- **[MODIFY] [AudioEngineService.kt](file:///C:/Users/scatt/Downloads/Winton_Hills_V2-master/Winton_Hills_V2-master/app/src/main/java/com/winton/validationshell/service/AudioEngineService.kt)**: Implemented state persistence for native toggles and synchronized them with the engine during start and runtime.

### 3. Validation Shell UI
- **[MODIFY] [ValidationShellScreen.kt](file:///C:/Users/scatt/Downloads/Winton_Hills_V2-master/Winton_Hills_V2-master/app/src/main/java/com/winton/validationshell/ui/ValidationShellScreen.kt)**: Added the **"Native Engine Core"** section with Material 3 Switches.
- **[MODIFY] [ValidationShellViewModel.kt](file:///C:/Users/scatt/Downloads/Winton_Hills_V2-master/Winton_Hills_V2-master/app/src/main/java/com/winton/validationshell/ui/ValidationShellViewModel.kt)**: Integrated 7-way flow combination to reactively update the UI based on native engine status.

---

## How to Run Real-Time Check

1. **Deploy `:app`** to your Samsung device.
2. **Start Engine**: Tap "START ENGINE" (uses Kotlin by default).
3. **Enable Native Processing**: Toggle the "Native Processing (JNI)" switch.
    - Check Logcat for: `WintonNativeCore: Native Processing Enabled`.
4. **Enable Native Capture**: Toggle "Native Capture (Oboe)".
    - Confirm analysis still flows in the "Real-time Analysis" panel.
5. **Observe Transitions**: Switch between "Auto", "Speech", and "Neutral" policies.
    - The native engine will now handle these transitions authoritatively if Native Processing is enabled.

---

## Verification Results

### Build Status
- **Android App**: ✅ **Build Successful**
- **Native Engine**: ✅ **Link Successful** (all `PolicySelector` symbols resolved)

### Feature Proof
- **Hysteresis Suppression**: 10 rapid changes $\to$ 0 switches (Verified in `policy_test`).
- **Bluetooth Blocking**: Confirmed SPEECH policy is disabled when BT device is connected.

> [!TIP]
> Use the **A/B Toggle** while Native Processing is enabled to verify the `BiquadCascade` linear ramping (50ms) remains pop-free during active processing.
