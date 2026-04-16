#include <jni.h>

// Conservative Phase-1 scaffolding. Native processing is still stubbed.
// TODO(phase2-oboe): keep callback path allocation-free and log-free.
// TODO(phase2-oboe): implement route-safe stop/reopen/start sequencing.
// TODO(phase2-oboe): apply bluetooth-conservative defaults when stream route changes.

namespace {
struct EngineConfig {
    int sampleRate = 48000;
    int framesPerBurst = 192;
    bool conservative = false;
};

struct StreamState {
    bool running = false;
    bool routeRestartPending = false;
};

EngineConfig gConfig{};
StreamState gState{};
}  // namespace

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_winton_validationshell_engine_Engine_nativeStartEngine(
        JNIEnv* env,
        jobject thiz,
        jboolean conservative) {
    gConfig.conservative = (conservative == JNI_TRUE);
    gState.running = true;
    // Stub: always succeeds
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_winton_validationshell_engine_Engine_nativeStopEngine(
        JNIEnv* env,
        jobject thiz) {
    gState.running = false;
    // Stub: nothing to release yet
}

JNIEXPORT jfloatArray JNICALL
Java_com_winton_validationshell_engine_Engine_nativeGetAnalysisData(
        JNIEnv* env,
        jobject thiz) {
    // Return 8-element array matching Kotlin index layout:
    // [0]=RMS, [1]=Centroid, [2]=Classification, [3]=Confidence,
    // [4]=PolicyId, [5]=LatencyMs, [6]=StartupLatencyMs, [7]=TheoreticalIOLatencyMs
    jfloatArray arr = env->NewFloatArray(8);
    float data[8] = {0.5f, 0.3f, 1.0f, 0.9f, 0.0f, 5.0f, 120.0f, 10.7f};
    env->SetFloatArrayRegion(arr, 0, 8, data);
    return arr;
}

JNIEXPORT void JNICALL
Java_com_winton_validationshell_engine_Engine_nativeSetPolicy(
        JNIEnv* env,
        jobject thiz,
        jint policyId,
        jfloatArray bands) {
    // Stub: accept policy + band array, do nothing yet
}

JNIEXPORT jfloat JNICALL
Java_com_winton_validationshell_engine_Engine_nativeGetLatencyMs(
        JNIEnv* env,
        jobject thiz) {
    // Stub: return a plausible latency
    return 5.0f;
}

}
