#pragma once

#include <cstdint>
#include <memory>
#include <string>

namespace oboe {

enum class DataCallbackResult {
    Continue,
    Stop
};

enum class Result {
    OK,
    ErrorDisconnected,
    ErrorInternal
    // Add others as needed
};

inline const char* convertToText(Result result) { return "MockResult"; }

enum class Direction { Input, Output };
enum class PerformanceMode { LowLatency };
enum class SharingMode { Exclusive };
enum class AudioFormat { Float };
const int32_t Mono = 1;

class AudioStream {
public:
    virtual ~AudioStream() = default;
    virtual Result requestStart() { return Result::OK; }
    virtual Result requestPause() { return Result::OK; }
    virtual Result stop() { return Result::OK; }
    virtual Result close() { return Result::OK; }
    virtual int32_t getSampleRate() { return 48000; }
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

class AudioStreamBuilder {
public:
    AudioStreamBuilder& setDirection(Direction direction) { return *this; }
    AudioStreamBuilder& setPerformanceMode(PerformanceMode mode) { return *this; }
    AudioStreamBuilder& setSharingMode(SharingMode mode) { return *this; }
    AudioStreamBuilder& setFormat(AudioFormat format) { return *this; }
    AudioStreamBuilder& setChannelCount(int32_t channelCount) { return *this; }
    AudioStreamBuilder& setDataCallback(AudioStreamDataCallback *callback) { return *this; }
    AudioStreamBuilder& setErrorCallback(AudioStreamErrorCallback *callback) { return *this; }
    AudioStreamBuilder& setSampleRate(int32_t sampleRate) { return *this; }
    AudioStreamBuilder& setFramesPerDataCallback(int32_t frames) { return *this; }

    Result openStream(std::shared_ptr<AudioStream>& stream) {
        stream = std::make_shared<AudioStream>();
        return Result::OK;
    }
};

} // namespace oboe
