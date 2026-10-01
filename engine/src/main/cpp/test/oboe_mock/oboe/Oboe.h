#pragma once
#include <cstdint>
#include <memory>
#include <atomic>

namespace oboe {
    enum class DataCallbackResult { Continue, Stop };
    enum class Result { OK, ErrorDisconnected };
    class AudioStream {
    public:
        virtual ~AudioStream() = default;
        virtual int32_t getSampleRate() { return 48000; }
        virtual Result requestStart() { return Result::OK; }
        virtual Result requestPause() { return Result::OK; }
        virtual Result stop() { return Result::OK; }
        virtual Result close() { return Result::OK; }
    };
    class AudioStreamDataCallback {
    public:
        virtual ~AudioStreamDataCallback() = default;
        virtual DataCallbackResult onAudioReady(AudioStream *audioStream, void *audioData, int32_t numFrames) = 0;
    };
    class AudioStreamErrorCallback {
    public:
        virtual ~AudioStreamErrorCallback() = default;
        virtual void onErrorAfterClose(AudioStream *audioStream, Result error) {}
    };
    enum Direction { Input, Output };
    enum PerformanceMode { LowLatency };
    enum SharingMode { Exclusive };
    enum AudioFormat { Float };
    const int32_t Mono = 1;

    class AudioStreamBuilder {
    public:
        AudioStreamBuilder& setDirection(Direction) { return *this; }
        AudioStreamBuilder& setPerformanceMode(PerformanceMode) { return *this; }
        AudioStreamBuilder& setSharingMode(SharingMode) { return *this; }
        AudioStreamBuilder& setFormat(AudioFormat) { return *this; }
        AudioStreamBuilder& setChannelCount(int32_t) { return *this; }
        AudioStreamBuilder& setDataCallback(AudioStreamDataCallback*) { return *this; }
        AudioStreamBuilder& setErrorCallback(AudioStreamErrorCallback*) { return *this; }
        AudioStreamBuilder& setSampleRate(int32_t) { return *this; }
        AudioStreamBuilder& setFramesPerDataCallback(int32_t) { return *this; }
        Result openStream(std::shared_ptr<AudioStream>& stream) {
            stream = std::make_shared<AudioStream>();
            return Result::OK;
        }
    };
    inline const char* convertToText(Result) { return "OK"; }
}
