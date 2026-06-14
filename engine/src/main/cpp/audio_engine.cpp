#include <jni.h>
#include <oboe/Oboe.h>
#include <android/log.h>
#include <memory>
#include <mutex>

#define TAG "WintonNativeEngine"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

class EngineCallback : public oboe::AudioStreamCallback {
public:
    oboe::DataCallbackResult onAudioReady(oboe::AudioStream *oboeStream, void *audioData, int32_t numFrames) override {
        if (!audioData || numFrames <= 0) return oboe::DataCallbackResult::Continue;

        // In a real implementation, we would process DSP here.
        // For now, we just zero out the buffer (passthrough/silence) to be safe.
        memset(audioData, 0, numFrames * oboeStream->getChannelCount() * sizeof(float));

        return oboe::DataCallbackResult::Continue;
    }

    void onErrorBeforeClose(oboe::AudioStream *oboeStream, oboe::Result error) override {
        LOGD("onErrorBeforeClose: %s", oboe::convertToText(error));
    }

    void onErrorAfterClose(oboe::AudioStream *oboeStream, oboe::Result error) override {
        if (error == oboe::Result::ErrorDisconnected) {
            LOGD("Stream disconnected (routing change or unplug).");
            // The stream is already closed. The Kotlin layer or a restart mechanism should handle reopening.
        }
    }
};

struct Engine {
    std::shared_ptr<oboe::AudioStream> stream;
    std::shared_ptr<EngineCallback> callback;
    std::mutex streamMutex;
    bool isPausedForRouting = false;

    Engine() : callback(std::make_shared<EngineCallback>()) {}

    bool start(int sampleRate, int framesPerBurst) {
        std::lock_guard<std::mutex> lock(streamMutex);
        oboe::AudioStreamBuilder builder;
        builder.setDirection(oboe::Direction::Output)
               ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
               ->setSharingMode(oboe::SharingMode::Exclusive)
               ->setFormat(oboe::AudioFormat::Float)
               ->setChannelCount(oboe::ChannelCount::Stereo)
               ->setSampleRate(sampleRate)
               ->setFramesPerCallback(framesPerBurst)
               ->setCallback(callback.get());

        oboe::Result result = builder.openStream(stream);
        if (result != oboe::Result::OK) {
            LOGE("Failed to open stream: %s", oboe::convertToText(result));
            return false;
        }

        result = stream->start();
        if (result != oboe::Result::OK) {
            LOGE("Failed to start stream: %s", oboe::convertToText(result));
            stream->close();
            return false;
        }

        isPausedForRouting = false;
        return true;
    }

    void stop() {
        std::lock_guard<std::mutex> lock(streamMutex);
        if (stream) {
            stream->stop();
            stream->close();
            stream.reset();
        }
    }

    void pauseForRouting() {
        std::lock_guard<std::mutex> lock(streamMutex);
        if (stream && !isPausedForRouting) {
            LOGD("Pausing stream for routing change...");
            stream->pause();
            isPausedForRouting = true;
        }
    }
};

static Engine gEngine;

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_winton_validationshell_engine_Engine_nativeStartEngine(
        JNIEnv* env,
        jobject thiz,
        jboolean conservative,
        jint sampleRate,
        jint bufferSize) {
    return gEngine.start(sampleRate, bufferSize) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_winton_validationshell_engine_Engine_nativeStopEngine(
        JNIEnv* env, jobject thiz) {
    gEngine.stop();
}

JNIEXPORT void JNICALL
Java_com_winton_validationshell_engine_Engine_nativePauseForRoutingChange(
        JNIEnv* env, jobject thiz) {
    gEngine.pauseForRouting();
}

JNIEXPORT jfloatArray JNICALL
Java_com_winton_validationshell_engine_Engine_nativeGetAnalysisData(
        JNIEnv* env, jobject thiz) {
    jfloatArray arr = env->NewFloatArray(8);
    float data[8] = {0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f};
    env->SetFloatArrayRegion(arr, 0, 8, data);
    return arr;
}

JNIEXPORT void JNICALL
Java_com_winton_validationshell_engine_Engine_nativeSetPolicy(
        JNIEnv* env, jobject thiz, jint policyId, jfloatArray bands) {
    // DSP parameters would be updated here.
}

JNIEXPORT jfloat JNICALL
Java_com_winton_validationshell_engine_Engine_nativeGetLatencyMs(
        JNIEnv* env, jobject thiz) {
    std::lock_guard<std::mutex> lock(gEngine.streamMutex);
    if (gEngine.stream) {
        auto result = gEngine.stream->calculateLatencyMillis();
        return result ? (jfloat)result.value() : -1.0f;
    }
    return -1.0f;
}

}
