#pragma once

#include <array>
#include <atomic>
#include <condition_variable>
#include <cstddef>
#include <cstdint>
#include <complex>
#include <mutex>
#include <thread>
#include <vector>

#include <oboe/Oboe.h>

#include "dsp/biquad_cascade.h"
#include "dsp/fft.h"
#include "dsp/analysis.h"
#include "policy/policy_selector.h"

namespace winton::native {

constexpr std::size_t kMaxFrameSamples = 2048;
constexpr std::size_t kAnalysisFieldCount = 8;

struct AnalysisFrame {
    std::uint64_t captureTimestampNs = 0;
    std::uint64_t submitTimestampNs = 0;
    std::uint64_t processedTimestampNs = 0;
    int sampleRate = 48000;
    int frameSize = 0;
    int policyId = 0;
    bool bypass = false;
    std::array<float, kMaxFrameSamples> samples{};
};

template <typename T, std::size_t Capacity>
class SPSCRingBuffer {
    static_assert(Capacity >= 2, "SPSC ring buffer needs capacity >= 2");
    static_assert((Capacity & (Capacity - 1)) == 0, "Capacity must be a power of two");

public:
    bool push(const T& value) {
        const auto head = head_.load(std::memory_order_relaxed);
        const auto next = (head + 1) & mask;
        if (next == tail_.load(std::memory_order_acquire)) {
            return false;
        }
        buffer_[head] = value;
        head_.store(next, std::memory_order_release);
        return true;
    }

    bool pop(T& value) {
        const auto tail = tail_.load(std::memory_order_relaxed);
        if (tail == head_.load(std::memory_order_acquire)) {
            return false;
        }
        value = buffer_[tail];
        tail_.store((tail + 1) & mask, std::memory_order_release);
        return true;
    }

    void clear() {
        T ignored{};
        while (pop(ignored)) {
        }
    }

    bool empty() const {
        return tail_.load(std::memory_order_acquire) == head_.load(std::memory_order_acquire);
    }

private:
    static constexpr std::size_t mask = Capacity - 1;
    std::array<T, Capacity> buffer_{};
    std::atomic<std::size_t> head_{0};
    std::atomic<std::size_t> tail_{0};
};

class NativeProcessingEngine : public oboe::AudioStreamDataCallback, public oboe::AudioStreamErrorCallback {
public:
    NativeProcessingEngine() = default;
    virtual ~NativeProcessingEngine() = default;

    bool start(int sampleRate, int framesPerBuffer, bool conservative);
    void stop();
    void pauseForRoutingChange();
    void setBypassMode(bool bypass);
    void setPolicy(int policyId, const std::vector<BandConfig>& bands);
    void setNativeCaptureEnabled(bool enabled);
    void setAutoPolicyEnabled(bool enabled);
    void updateHardwareConstraints(bool forceConservative, bool isBluetooth);
    void submitFrame(const float* samples, int frameSize, int sampleRate, std::uint64_t captureTimestampNs);
    std::array<float, kAnalysisFieldCount> latestAnalysis() const;
    float latencyMs() const;

    // Oboe Callbacks
    oboe::DataCallbackResult onAudioReady(oboe::AudioStream *audioStream, void *audioData, int32_t numFrames) override;
    void onErrorAfterClose(oboe::AudioStream *audioStream, oboe::Result error) override;

private:
    void workerLoop();
    void processFrame(AnalysisFrame frame);

    // Internal Stream Management
    oboe::Result openStream();
    void closeStream();

    static std::uint64_t nowNs();

    mutable std::mutex stateMutex_;
    std::condition_variable queueCondition_;
    std::thread worker_;
    std::atomic<bool> running_{false};
    std::atomic<bool> paused_{false};
    std::atomic<bool> bypassMode_{false};
    std::atomic<bool> nativeCaptureEnabled_{false};
    std::atomic<bool> autoPolicyEnabled_{false};
    std::atomic<bool> forceConservative_{false};
    std::atomic<bool> isBluetooth_{false};

    std::shared_ptr<oboe::AudioStream> stream_;

    bool firstFrameSeen_ = false;
    int sampleRate_ = 48000;
    int framesPerBuffer_ = 1024;
    int activePolicyId_ = 0;
    std::uint64_t engineStartNs_ = 0;
    std::uint64_t lastLatencyNs_ = 0;
    float startupLatencyMs_ = -1.0f;
    float lastProcessedSample_ = 0.0f;
    std::vector<BandConfig> bands_;
    BiquadCascade cascade_;
    PolicySelector policySelector_;
    SPSCRingBuffer<AnalysisFrame, 32> queue_;
    mutable std::mutex queueMutex_;
    mutable std::mutex latestMutex_;
    std::vector<float> workingBuffer_;
    std::vector<std::complex<float>> fftBuffer_;
    std::vector<float> fftMagnitudes_;
    std::array<float, kMaxFrameSamples> rawScratch_{};
    std::array<float, kAnalysisFieldCount> latestAnalysis_{};
};

} // namespace winton::native
