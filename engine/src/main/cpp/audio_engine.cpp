#include <android/log.h>
#include <jni.h>

#include <vector>

#include "native_processing_engine.h"

#define TAG "WintonNativeEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

namespace {

winton::native::NativeProcessingEngine gEngine;

std::vector<winton::native::BandConfig> parseBands(JNIEnv* env, jfloatArray bandsArray) {
    std::vector<winton::native::BandConfig> bands;
    if (bandsArray == nullptr) {
        return bands;
    }

    const jsize length = env->GetArrayLength(bandsArray);
    if (length <= 0) {
        return bands;
    }

    const jint bandCount = length / 3;
    bands.reserve(static_cast<std::size_t>(bandCount));

    jfloat* values = env->GetFloatArrayElements(bandsArray, nullptr);
    if (values == nullptr) {
        return bands;
    }

    for (jint i = 0; i < bandCount; ++i) {
        bands.push_back({
            values[i * 3],
            values[i * 3 + 1],
            values[i * 3 + 2]
        });
    }

    env->ReleaseFloatArrayElements(bandsArray, values, JNI_ABORT);
    return bands;
}

} // namespace

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_winton_validationshell_engine_Engine_nativeStartEngine(
        JNIEnv*,
        jobject,
        jboolean conservative,
        jint sampleRate,
        jint bufferSize) {
    return gEngine.start(sampleRate, bufferSize, conservative == JNI_TRUE) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_winton_validationshell_engine_Engine_nativeStopEngine(
        JNIEnv*, jobject) {
    gEngine.stop();
}

JNIEXPORT void JNICALL
Java_com_winton_validationshell_engine_Engine_nativePauseForRoutingChange(
        JNIEnv*, jobject) {
    gEngine.pauseForRoutingChange();
}

JNIEXPORT jfloatArray JNICALL
Java_com_winton_validationshell_engine_Engine_nativeGetAnalysisData(
        JNIEnv* env, jobject) {
    const auto data = gEngine.latestAnalysis();
    jfloatArray arr = env->NewFloatArray(static_cast<jsize>(data.size()));
    if (arr == nullptr) {
        return nullptr;
    }
    env->SetFloatArrayRegion(arr, 0, static_cast<jsize>(data.size()), data.data());
    return arr;
}

JNIEXPORT void JNICALL
Java_com_winton_validationshell_engine_Engine_nativeSetPolicy(
        JNIEnv* env, jobject, jint policyId, jfloatArray bands) {
    gEngine.setPolicy(policyId, parseBands(env, bands));
}

JNIEXPORT void JNICALL
Java_com_winton_validationshell_engine_Engine_nativeSetBypassMode(
        JNIEnv*, jobject, jboolean bypass) {
    gEngine.setBypassMode(bypass == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_com_winton_validationshell_engine_Engine_nativeSetAutoPolicyEnabled(
        JNIEnv*, jobject, jboolean enabled) {
    gEngine.setAutoPolicyEnabled(enabled == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_com_winton_validationshell_engine_Engine_nativeUpdateHardwareConstraints(
        JNIEnv*, jobject, jboolean forceConservative, jboolean isBluetooth) {
    gEngine.updateHardwareConstraints(forceConservative == JNI_TRUE, isBluetooth == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_com_winton_validationshell_engine_Engine_nativeSetNativeCaptureEnabled(
        JNIEnv*, jobject, jboolean enabled) {
    gEngine.setNativeCaptureEnabled(enabled == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_com_winton_validationshell_engine_Engine_nativeSubmitAudioFrame(
        JNIEnv* env,
        jobject,
        jfloatArray samples,
        jint frameSize,
        jint sampleRate,
        jlong captureTimestampNs) {
    if (samples == nullptr || frameSize <= 0) {
        return;
    }

    jfloat* values = env->GetFloatArrayElements(samples, nullptr);
    if (values == nullptr) {
        LOGW("nativeSubmitAudioFrame: failed to access PCM frame");
        return;
    }

    gEngine.submitFrame(values, frameSize, sampleRate, static_cast<std::uint64_t>(captureTimestampNs));
    env->ReleaseFloatArrayElements(samples, values, JNI_ABORT);
}

JNIEXPORT jfloat JNICALL
Java_com_winton_validationshell_engine_Engine_nativeGetLatencyMs(
        JNIEnv*, jobject) {
    return gEngine.latencyMs();
}

}
