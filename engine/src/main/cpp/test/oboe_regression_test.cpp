#include <iostream>
#include <vector>
#include <array>
#include <cmath>
#include <cstring>
#include <algorithm>
#include <cassert>

#include "native_processing_engine.h"

using namespace winton::native;

// Access private members for verification
#define private public
#include "native_processing_engine.h"
#include "native_processing_engine.cpp"
#include "dsp/biquad_filter.h"
#include "dsp/biquad_filter.cpp"
#include "dsp/biquad_cascade.h"
#include "dsp/biquad_cascade.cpp"
#include "dsp/fft.h"
#include "dsp/fft.cpp"
#include "dsp/analysis.h"
#include "dsp/analysis.cpp"
#include "policy/policy_selector.h"
#include "policy/policy_selector.cpp"
#undef private

bool testAnalysisPurity() {
    std::cout << "Running Oboe Analysis Purity Test..." << std::endl;

    NativeProcessingEngine engine;
    engine.start(48000, 1024, false);

    // 1. Setup a heavy EQ (+12dB peaking at 1kHz)
    std::vector<BandConfig> bands = {{1000.0f, 12.0f, 1.0f}};
    engine.setPolicy(1, bands);
    engine.setBypassMode(false);

    // 2. Create a 1kHz sine wave input
    constexpr int kFrameSize = 1024;
    std::vector<float> input(kFrameSize);
    for (int i = 0; i < kFrameSize; ++i) {
        input[i] = 0.1f * std::sin(2.0 * M_PI * 1000.0 * i / 48000.0);
    }

    // Capture input RMS
    double sumSq = 0;
    for (float s : input) sumSq += s * s;
    float inputRms = std::sqrt(sumSq / kFrameSize);

    // 3. Invoke the Oboe callback
    // The engine's onAudioReady will copy to rawScratch_, then process input in-place, then submit rawScratch_
    oboe::AudioStream stream;
    engine.onAudioReady(&stream, input.data(), kFrameSize);

    // 4. Verify the input buffer WAS processed (should be boosted)
    sumSq = 0;
    for (float s : input) sumSq += s * s;
    float outputRms = std::sqrt(sumSq / kFrameSize);

    std::cout << "  Input RMS:  " << inputRms << std::endl;
    std::cout << "  Output RMS: " << outputRms << std::endl;

    if (outputRms < inputRms * 2.0f) {
        std::cout << "FAIL: Output buffer was not boosted (EQ failed or bypassed)" << std::endl;
        return false;
    }

    // 5. Verify the ANALYSIS queue contains the RAW samples (not boosted)
    // Access private queue_ via the hack in header if possible, or we check latestAnalysis
    // But latestAnalysis is computed in workerLoop asynchronously.

    // We'll wait a bit for the worker thread
    std::this_thread::sleep_for(std::chrono::milliseconds(50));
    auto analysis = engine.latestAnalysis();
    float analysisRms = analysis[0];

    std::cout << "  Analysis RMS: " << analysisRms << std::endl;

    // Use a tight tolerance
    if (std::abs(analysisRms - inputRms) > 1e-5f) {
        std::cout << "FAIL: Analysis RMS (" << analysisRms << ") does not match Input RMS (" << inputRms << ")" << std::endl;
        std::cout << "      This indicates analysis is running on PROCESSED samples." << std::endl;
        return false;
    }

    std::cout << "PASS: Analysis purity verified (Analysis RMS matches RAW Input RMS)" << std::endl;
    engine.stop();
    return true;
}

int main() {
    if (testAnalysisPurity()) {
        return 0;
    }
    return 1;
}
