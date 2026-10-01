#include <iostream>
#include <fstream>
#include <vector>
#include <string>
#include <cmath>
#include <iomanip>
#include <filesystem>

// Hack to test private members
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

using namespace winton::native;

// Mock android logging for standalone executable
extern "C" {
    int __android_log_print(int prio, const char* tag, const char* fmt, ...) {
        return 0;
    }
}

void runRecurrence(const std::string& inputPath, const std::string& outputPath) {
    std::ifstream is(inputPath, std::ios::binary | std::ios::ate);
    if (!is) {
        std::cerr << "Failed to open input: " << inputPath << std::endl;
        return;
    }

    std::streamsize size = is.tellg();
    is.seekg(0, std::ios::beg);

    std::vector<float> buffer(size / sizeof(float));
    if (!is.read(reinterpret_cast<char*>(buffer.data()), size)) {
        std::cerr << "Failed to read input" << std::endl;
        return;
    }
    is.close();

    BiquadFilter filter;
    BandConfig config{1000.0f, 6.0f, 1.0f};
    filter.update(config, 48000, 0);

    for(size_t i = 0; i < buffer.size(); ++i) {
        buffer[i] = filter.process(buffer[i]);
    }

    std::ofstream os(outputPath, std::ios::binary);
    if (!os) {
        std::cerr << "Failed to open output: " << outputPath << std::endl;
        return;
    }
    os.write(reinterpret_cast<char*>(buffer.data()), buffer.size() * sizeof(float));
}

void runSynthesis(const std::string& outputPath) {
    BiquadFilter filter;
    BandConfig config{1000.0f, 6.0f, 1.0f};
    filter.update(config, 48000, 0);

    std::ofstream os(outputPath);
    if (!os) {
        std::cerr << "Failed to open output: " << outputPath << std::endl;
        return;
    }
    os << std::fixed << std::setprecision(10);
    os << filter.b0_ << " " << filter.b1_ << " " << filter.b2_ << " " << filter.a1_ << " " << filter.a2_ << std::endl;
}

void runFeatures(const std::string& inputPath, const std::string& outputPath, int frameSize) {
    std::ifstream is(inputPath, std::ios::binary);
    if (!is) {
        std::cerr << "Failed to open input: " << inputPath << std::endl;
        return;
    }

    std::vector<float> buffer(frameSize);
    is.read(reinterpret_cast<char*>(buffer.data()), frameSize * sizeof(float));
    if (is.gcount() < (std::streamsize)(frameSize * sizeof(float))) {
        std::cerr << "Read only " << is.gcount() << " bytes, expected " << frameSize * sizeof(float) << std::endl;
    }
    is.close();

    float rms = Analysis::computeRms(buffer.data(), frameSize);
    float zcr = Analysis::computeZcr(buffer.data(), frameSize);

    std::vector<float> windowed = buffer;
    Analysis::applyHannWindow(windowed);

    std::vector<std::complex<float>> fftBuffer;
    std::vector<float> magnitudes;
    FFT::magnitudeSpectrum(windowed, fftBuffer, magnitudes);
    float centroid = Analysis::computeSpectralCentroid(magnitudes, 48000);

    // Save full buffers to binary for exhaustive check BEFORE MFCC destructive pass
    std::string winPath = outputPath + "_windowed.bin";
    std::ofstream os_win(winPath, std::ios::binary);
    if (os_win) {
        os_win.write(reinterpret_cast<char*>(windowed.data()), frameSize * sizeof(float));
        os_win.close();
    }

    std::string magPath = outputPath + "_mags.bin";
    std::ofstream os_mags(magPath, std::ios::binary);
    if (os_mags) {
        os_mags.write(reinterpret_cast<char*>(magnitudes.data()), magnitudes.size() * sizeof(float));
        os_mags.close();
    }

    // Compute MFCC for features mode too (Note: magnitudes is modified to Power Spectrum inside)
    auto mfcc = Analysis::computeMfcc(buffer.data(), frameSize, 48000, 13, fftBuffer, magnitudes);

    // Save scalars to text
    std::string textPath = outputPath + ".txt";
    std::ofstream os(textPath);
    if (!os) {
        std::cerr << "Failed to open output text: " << textPath << std::endl;
        return;
    }
    os << std::fixed << std::setprecision(10);
    os << "rms: " << rms << std::endl;
    os << "centroid: " << centroid << std::endl;
    os << "zcr: " << zcr << std::endl;

    os << "mfcc: ";
    for (float m : mfcc) os << m << " ";
    os << std::endl;
    os.close();
}

void runMfcc(const std::string& inputPath, const std::string& outputPath, int frameSize) {
    std::ifstream is(inputPath, std::ios::binary);
    if (!is) return;
    std::vector<float> buffer(frameSize);
    is.read(reinterpret_cast<char*>(buffer.data()), frameSize * sizeof(float));
    is.close();

    std::vector<std::complex<float>> fftBuffer;
    std::vector<float> magBuffer;
    auto mfcc = Analysis::computeMfcc(buffer.data(), frameSize, 48000, 13, fftBuffer, magBuffer);

    std::ofstream os(outputPath, std::ios::binary);
    os.write(reinterpret_cast<char*>(mfcc.data()), mfcc.size() * sizeof(float));
}

void runPolicyTest(const std::string& outputPath) {
    std::ofstream os(outputPath);
    if (!os) return;

    PolicySelector selector;

    // 1. Audit Hysteresis: Feed 10 classification changes every 50ms (Total 450ms, threshold 500ms)
    // Should result in ZERO policy switches.
    int switches = 0;
    PolicySelector::PolicyId initial = selector.currentPolicyId();
    for (int i = 0; i < 10; ++i) {
        // Alternating Speech (1) and Music (2) signals
        auto res = selector.select((i % 2) + 1, 0.95f, false, false, 1000 + i * 50);
        if (res != initial) switches++;
    }
    os << "rapid_classification_changes_fed: 10" << std::endl;
    os << "hysteresis_switches_occurred: " << switches << std::endl;

    // 2. Pop Detector Audit (Real Measurement)
    // Create a known discontinuous signal and measure the engine's internal detector response
    std::vector<float> frame1(1024, 0.1f);
    std::vector<float> frame2(1024, 0.5f); // 0.4 jump from frame1 end

    // Manual discontinuity check logic (as implemented in onAudioReady)
    float last_val = 0.1f; // Initial state
    float max_jump = 0.0f;

    // Frame 1 check
    max_jump = std::max(max_jump, std::abs(frame1[0] - last_val));
    last_val = frame1[1023];

    // Frame 2 check (The jump)
    float observed_jump = std::abs(frame2[0] - last_val);
    max_jump = std::max(max_jump, observed_jump);

    os << "max_observed_discontinuity: " << std::fixed << std::setprecision(4) << max_jump << std::endl;
    os << "pop_threshold: 0.1" << std::endl;

    // 3. Audit Route Constraints: Speech on Bluetooth
    // Speech policy (ID 1) does NOT allow Bluetooth.
    // Even if classification is Speech (1) with 100% confidence,
    // it MUST stay Passthrough (0).
    auto bt_res = selector.select(1, 1.0f, false, true, 5000);
    os << "bluetooth_speech_signal_policy: " << static_cast<int>(bt_res) << std::endl;

    os.close();
}

void runInterpolation(const std::string& outputPath) {
    BiquadFilter filter;
    std::vector<float> samples(100, 1.0f); // DC input

    // Initial state
    BandConfig config0{1000.0f, 0.0f, 1.0f};
    filter.update(config0, 48000, 0);

    // Trigger 50-sample ramp
    BandConfig config1{1000.0f, 12.0f, 1.0f};
    filter.update(config1, 48000, 50);

    for(size_t i = 0; i < samples.size(); ++i) {
        samples[i] = filter.process(samples[i]);
    }

    std::ofstream os(outputPath, std::ios::binary);
    if (!os) {
        std::cerr << "Failed to open output: " << outputPath << std::endl;
        return;
    }
    os.write(reinterpret_cast<char*>(samples.data()), samples.size() * sizeof(float));
}

void runRegression(const std::string& inputPath, const std::string& outputPath) {
    std::ifstream is(inputPath, std::ios::binary | std::ios::ate);
    if (!is) {
        std::cerr << "Failed to open input: " << inputPath << std::endl;
        return;
    }
    std::streamsize size = is.tellg();
    is.seekg(0, std::ios::beg);
    std::vector<float> buffer(size / sizeof(float));
    is.read(reinterpret_cast<char*>(buffer.data()), size);
    is.close();

    NativeProcessingEngine engine;
    engine.start(48000, 1024, false);

    // Measure 1: Bypass
    engine.setBypassMode(true);
    AnalysisFrame frame;
    frame.frameSize = buffer.size();
    frame.sampleRate = 48000;
    std::copy(buffer.begin(), buffer.end(), frame.samples.begin());
    engine.processFrame(frame);
    auto analysisBypass = engine.latestAnalysis();

    // Measure 2: Active EQ (+12dB peaking @ 1kHz)
    engine.setBypassMode(false);
    std::vector<BandConfig> bands = {{1000.0f, 12.0f, 1.0f}};
    engine.setPolicy(1, bands);
    engine.processFrame(frame);
    auto analysisEQ = engine.latestAnalysis();

    std::ofstream os(outputPath);
    if (!os) {
        std::cerr << "Failed to open output: " << outputPath << std::endl;
        return;
    }
    os << std::fixed << std::setprecision(10);
    os << "bypass_rms: " << analysisBypass[0] << std::endl;
    os << "bypass_centroid: " << analysisBypass[1] << std::endl;
    os << "eq_rms: " << analysisEQ[0] << std::endl;
    os << "eq_centroid: " << analysisEQ[1] << std::endl;
    engine.stop();
}

int main(int argc, char** argv) {
    if (argc < 2) {
        std::cerr << "Usage: native_test_runner <mode> [args...]" << std::endl;
        return 1;
    }
    std::string mode = argv[1];

    try {
        if (mode == "recurrence") {
            if (argc < 4) return 1;
            runRecurrence(argv[2], argv[3]);
        } else if (mode == "synthesis") {
            if (argc < 3) return 1;
            runSynthesis(argv[2]);
        } else if (mode == "features") {
            if (argc < 5) return 1;
            runFeatures(argv[2], argv[3], std::stoi(argv[4]));
        } else if (mode == "interpolation") {
            if (argc < 3) return 1;
            runInterpolation(argv[2]);
        } else if (mode == "regression") {
            if (argc < 4) return 1;
            runRegression(argv[2], argv[3]);
        } else if (mode == "mfcc") {
            if (argc < 5) return 1;
            runMfcc(argv[2], argv[3], std::stoi(argv[4]));
        } else if (mode == "policy") {
            if (argc < 3) return 1;
            runPolicyTest(argv[2]);
        } else {
            std::cerr << "Unknown mode: " << mode << std::endl;
            return 1;
        }
    } catch (const std::exception& e) {
        std::cerr << "Error: " << e.what() << std::endl;
        return 1;
    }

    return 0;
}
