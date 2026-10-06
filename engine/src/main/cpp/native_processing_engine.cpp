#include "native_processing_engine.h"
#include "dsp/dsp_constants.h"

#include <android/log.h>
#include <algorithm>
#include <chrono>
#include <cmath>
#include <complex>
#include <cstring>
#include <utility>
#include <vector>

#define TAG "WintonNativeCore"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace winton::native {

bool NativeProcessingEngine::start(int sampleRate, int framesPerBuffer, bool conservative) {
    std::lock_guard<std::mutex> lock(stateMutex_);
    if (running_) {
        return true;
    }

    sampleRate_ = std::max(8000, sampleRate);
    framesPerBuffer_ = std::max(32, framesPerBuffer);
    paused_.store(false, std::memory_order_release);
    running_.store(true, std::memory_order_release);
    firstFrameSeen_ = false;
    engineStartNs_ = nowNs();
    lastLatencyNs_ = 0;
    startupLatencyMs_ = -1.0f;

    if (conservative) {
        LOGI("Starting native engine in conservative mode (%d Hz, %d frames)", sampleRate_, framesPerBuffer_);
    } else {
        LOGI("Starting native engine (%d Hz, %d frames)", sampleRate_, framesPerBuffer_);
    }

    latestAnalysis_.fill(0.0f);
    latestAnalysis_[5] = -1.0f;
    latestAnalysis_[6] = -1.0f;
    latestAnalysis_[7] = -1.0f;
    workingBuffer_.reserve(kMaxFrameSamples);
    fftBuffer_.reserve(kMaxFrameSamples);
    fftMagnitudes_.reserve(kMaxFrameSamples / 2 + 1);
    queue_.clear();

    worker_ = std::thread(&NativeProcessingEngine::workerLoop, this);

    if (nativeCaptureEnabled_.load(std::memory_order_acquire)) {
        auto result = openStream();
        if (result != oboe::Result::OK) {
            LOGE("Failed to open Oboe stream: %s", oboe::convertToText(result));
            // Don't fail the whole start, but log it.
        } else {
            result = stream_->requestStart();
            if (result != oboe::Result::OK) {
                LOGE("Failed to start Oboe stream: %s", oboe::convertToText(result));
            }
        }
    }

    return true;
}

void NativeProcessingEngine::stop() {
    {
        std::lock_guard<std::mutex> lock(stateMutex_);
        if (!running_) {
            return;
        }
        running_.store(false, std::memory_order_release);
    }

    closeStream();

    queueCondition_.notify_all();
    if (worker_.joinable()) {
        worker_.join();
    }
    queue_.clear();
    LOGI("Native engine stopped");
}

void NativeProcessingEngine::pauseForRoutingChange() {
    paused_.store(true, std::memory_order_release);
    LOGD("Native engine paused for routing change");
    if (stream_) {
        stream_->requestPause();
    }
}

void NativeProcessingEngine::setBypassMode(bool bypass) {
    bypassMode_.store(bypass, std::memory_order_release);
}

void NativeProcessingEngine::setPolicy(int policyId, const std::vector<BandConfig>& bands) {
    std::lock_guard<std::mutex> lock(stateMutex_);
    activePolicyId_ = policyId;
    bands_ = bands;
    cascade_.updateBands(bands_, sampleRate_, 50);
}

void NativeProcessingEngine::setNativeCaptureEnabled(bool enabled) {
    nativeCaptureEnabled_.store(enabled, std::memory_order_release);
    LOGI("Native capture enabled: %d", enabled);
}

void NativeProcessingEngine::setAutoPolicyEnabled(bool enabled) {
    autoPolicyEnabled_.store(enabled, std::memory_order_release);
    LOGI("Native auto-policy enabled: %d", enabled);
}

void NativeProcessingEngine::updateHardwareConstraints(bool forceConservative, bool isBluetooth) {
    forceConservative_.store(forceConservative, std::memory_order_release);
    isBluetooth_.store(isBluetooth, std::memory_order_release);
    LOGI("Native constraints updated: conservative=%d, bluetooth=%d", forceConservative, isBluetooth);
}

void NativeProcessingEngine::submitFrame(const float* samples, int frameSize, int sampleRate, std::uint64_t captureTimestampNs) {
    if (!running_.load(std::memory_order_acquire) || samples == nullptr || frameSize <= 0) {
        return;
    }

    AnalysisFrame frame{};
    frame.captureTimestampNs = captureTimestampNs;
    frame.submitTimestampNs = nowNs();
    {
        std::lock_guard<std::mutex> lock(stateMutex_);
        frame.sampleRate = sampleRate > 0 ? sampleRate : sampleRate_;
        frame.policyId = activePolicyId_;
    }
    frame.frameSize = std::min<int>(frameSize, static_cast<int>(kMaxFrameSamples));
    frame.bypass = bypassMode_.load(std::memory_order_acquire);
    std::memcpy(frame.samples.data(), samples, sizeof(float) * frame.frameSize);

    while (!queue_.push(frame)) {
        AnalysisFrame dropped{};
        queue_.pop(dropped);
    }

    queueCondition_.notify_one();
}

oboe::DataCallbackResult NativeProcessingEngine::onAudioReady(oboe::AudioStream *audioStream, void *audioData, int32_t numFrames) {
    if (paused_.load(std::memory_order_acquire)) {
        return oboe::DataCallbackResult::Continue;
    }

    float* samples = static_cast<float*>(audioData);
    const int frameSize = std::min<int>(numFrames, static_cast<int>(kMaxFrameSamples));

    // 1. Capture RAW samples for analysis before any DSP processing
    std::memcpy(rawScratch_.data(), samples, sizeof(float) * frameSize);

    // 2. DSP Processing (IN-PLACE)
    bool bypass = bypassMode_.load(std::memory_order_relaxed);
    if (!bypass) {
        std::lock_guard<std::mutex> lock(stateMutex_);
        if (!cascade_.empty()) {
            cascade_.process(samples, frameSize);
        }
    }

    // 3. Discontinuity Check (Pop Detection)
    // Check if the jump from the last frame's end to this frame's start is > 0.1
    if (frameSize > 0) {
        float jump = std::abs(samples[0] - lastProcessedSample_);
        if (jump > 0.1f) {
            LOGW("Audio discontinuity detected: %.3f jump at frame boundary", jump);
        }
        lastProcessedSample_ = samples[frameSize - 1];
    }

    // 4. Submit RAW samples for background analysis
    // This ensures classification/RMS is unaffected by EQ boost/cut
    submitFrame(rawScratch_.data(), frameSize, audioStream->getSampleRate(), nowNs());

    return oboe::DataCallbackResult::Continue;
}

void NativeProcessingEngine::onErrorAfterClose(oboe::AudioStream *audioStream, oboe::Result error) {
    if (error == oboe::Result::ErrorDisconnected) {
        LOGI("Oboe stream disconnected, attempting to restart...");
        if (running_.load(std::memory_order_acquire)) {
            openStream();
            if (stream_) {
                stream_->requestStart();
            }
        }
    }
}

oboe::Result NativeProcessingEngine::openStream() {
    closeStream();

    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Input);
    builder.setPerformanceMode(oboe::PerformanceMode::LowLatency);
    builder.setSharingMode(oboe::SharingMode::Exclusive);
    builder.setFormat(oboe::AudioFormat::Float);
    builder.setChannelCount(oboe::Mono);
    builder.setDataCallback(this);
    builder.setErrorCallback(this);

    {
        std::lock_guard<std::mutex> lock(stateMutex_);
        builder.setSampleRate(sampleRate_);
        builder.setFramesPerDataCallback(framesPerBuffer_);
    }

    return builder.openStream(stream_);
}

void NativeProcessingEngine::closeStream() {
    if (stream_) {
        stream_->stop();
        stream_->close();
        stream_.reset();
    }
}

std::array<float, kAnalysisFieldCount> NativeProcessingEngine::latestAnalysis() const {
    std::lock_guard<std::mutex> lock(latestMutex_);
    return latestAnalysis_;
}

float NativeProcessingEngine::latencyMs() const {
    std::lock_guard<std::mutex> lock(latestMutex_);
    return latestAnalysis_[5];
}

void NativeProcessingEngine::workerLoop() {
    while (running_.load(std::memory_order_acquire)) {
        AnalysisFrame frame{};
        if (!queue_.pop(frame)) {
            std::unique_lock<std::mutex> lock(queueMutex_);
            queueCondition_.wait_for(lock, std::chrono::milliseconds(10), [this] {
                return !running_.load(std::memory_order_acquire) || !queue_.empty();
            });
            continue;
        }

        if (paused_.load(std::memory_order_acquire)) {
            continue;
        }

        processFrame(frame);
    }
}

void NativeProcessingEngine::processFrame(AnalysisFrame frame) {
    // 1. ANALYSIS: Perform analysis on the RAW captured samples
    const float rms = Analysis::computeRms(frame.samples.data(), frame.frameSize);

    // Use working buffer for windowed/spectral analysis to keep original samples clean for processing
    workingBuffer_.assign(frame.samples.begin(), frame.samples.begin() + frame.frameSize);
    Analysis::applyHannWindow(workingBuffer_);
    FFT::magnitudeSpectrum(workingBuffer_, fftBuffer_, fftMagnitudes_);

    const float centroid = Analysis::computeSpectralCentroid(fftMagnitudes_, frame.sampleRate);
    const float zcr = Analysis::computeZcr(frame.samples.data(), frame.frameSize);
    const auto [classification, confidence] = Analysis::classify(rms, centroid);

    // 2. AUTO-POLICY: Handle internal state transitions
    if (autoPolicyEnabled_.load(std::memory_order_acquire)) {
        using PolicyId = PolicySelector::PolicyId;
        uint64_t nowMs = nowNs() / 1'000'000;

        std::lock_guard<std::mutex> lock(stateMutex_);
        PolicyId nextId = policySelector_.select(
            classification,
            confidence,
            forceConservative_.load(std::memory_order_relaxed),
            isBluetooth_.load(std::memory_order_relaxed),
            nowMs
        );

        int nextIdInt = static_cast<int>(nextId);
        if (nextIdInt != activePolicyId_) {
            LOGI("Native Auto-Policy Switch: %d -> %d", activePolicyId_, nextIdInt);
            activePolicyId_ = nextIdInt;
            bands_ = PolicySelector::getConfig(nextId).bands;
            cascade_.updateBands(bands_, sampleRate_, 50); // 50-sample ramp
        }
    }

    // 3. PROCESSING:
    // If native capture is active, processing already happened in onAudioReady.
    // If Kotlin submission is active, we might want to process here if it hasn't been done.
    // BUT the current Engine.kt calls cascade_.process in AudioCaptureThread (Kotlin).
    // So for Phase 2, we are moving TOWARDS Oboe.

    int localPolicyId = 0;
    int localSampleRate = frame.sampleRate;
    int localFramesPerBuffer = framesPerBuffer_;
    std::uint64_t localEngineStartNs = engineStartNs_;
    bool localFirstFrameSeen = false;

    {
        std::lock_guard<std::mutex> lock(stateMutex_);
        localPolicyId = activePolicyId_;
        localSampleRate = sampleRate_;
        localFramesPerBuffer = framesPerBuffer_;
        localEngineStartNs = engineStartNs_;
        localFirstFrameSeen = firstFrameSeen_;
    }

    // 3. TELEMETRY: Calculate latencies and update latest analysis
    frame.processedTimestampNs = nowNs();
    const float latencyMs = static_cast<float>(frame.processedTimestampNs - frame.submitTimestampNs) / 1'000'000.0f;
    const float theoreticalLatencyMs = static_cast<float>(localFramesPerBuffer) * 2.0f / static_cast<float>(localSampleRate) * 1000.0f;

    float startupLatencyMs = -1.0f;
    if (!localFirstFrameSeen) {
        startupLatencyMs = static_cast<float>(frame.processedTimestampNs - localEngineStartNs) / 1'000'000.0f;
        std::lock_guard<std::mutex> lock(stateMutex_);
        firstFrameSeen_ = true;
        startupLatencyMs_ = startupLatencyMs;
    } else {
        startupLatencyMs = startupLatencyMs_;
    }

    std::array<float, kAnalysisFieldCount> result{};
    result[0] = rms;
    result[1] = centroid;
    result[2] = static_cast<float>(classification);
    result[3] = confidence;
    result[4] = static_cast<float>(localPolicyId);
    result[5] = latencyMs;
    result[6] = startupLatencyMs;
    result[7] = theoreticalLatencyMs;

    {
        std::lock_guard<std::mutex> lock(latestMutex_);
        latestAnalysis_ = result;
    }
    lastLatencyNs_ = frame.processedTimestampNs - frame.submitTimestampNs;
}

std::uint64_t NativeProcessingEngine::nowNs() {
    using namespace std::chrono;
    return duration_cast<nanoseconds>(steady_clock::now().time_since_epoch()).count();
}

} // namespace winton::native
